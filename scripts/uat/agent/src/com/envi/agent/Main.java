package com.envi.agent;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.UiAutomation;
import android.graphics.Rect;
import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * The phone-side half of the harness's fast controls (wispr-eyes), started by the host with
 * `app_process` as the shell user and reached over an adb-forwarded local socket.
 *
 * WHY IT EXISTS. `adb shell input` starts a new JVM for every tap (hundreds of milliseconds) and
 * `uiautomator dump` takes about two seconds AND connects UiAutomation with no flags, which suppresses
 * every other accessibility service, our own paste service included, for as long as it runs. This
 * process connects UiAutomation ONCE with FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES, so the paste
 * service stays bound, reads the tree from it on demand, and injects input through InputManager.
 *
 * LIFETIME. It exits by itself after `--idle-exit-ms` (default ten minutes) with no command, so it never
 * lingers on the founder's daily phone, and a second copy refuses to start while one holds the socket.
 *
 * PROTOCOL. One command per line; every reply is `OK <byte count>\n<bytes>` or `ERR <message>\n`.
 *   ping | dump | tap X Y WINDOW | swipe X1 Y1 X2 Y2 MS WINDOW | text STRING | key CODE | ime | idle IDLE_MS TIMEOUT_MS | quit
 *   A coordinate action names the window it is aimed at (an id from `dump`). It is REFUSED unless that
 *   window is the topmost one containing the point the finger lands on: this process sees every window,
 *   rootless ones included, so no caller can press or drag under a dialog, the keyboard or a bar.
 *   settle QUIET_MS CHANGE_MS TIMEOUT_MS ACTION...  runs ACTION, waits up to CHANGE_MS for the screen to
 *     change (a window or content event), then until no event for QUIET_MS; answers `changed` or `same`.
 *     Event-driven on the phone, so the host does not poll dumps to learn when a press has landed.
 */
public final class Main {
    private static final String SOCKET = "wispr-agent";
    /** UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES; the whole point of this process. */
    private static final int DONT_SUPPRESS = 1;

    private static UiAutomation automation;
    private static Method inject;
    private static Object injector;
    private static volatile long lastCommand = SystemClock.uptimeMillis();

    public static void main(String[] args) throws Exception {
        long idleExitMs = 10 * 60 * 1000L;
        for (int i = 0; i + 1 < args.length; i++) if (args[i].equals("--idle-exit-ms")) idleExitMs = Long.parseLong(args[i + 1]);
        LocalServerSocket server;
        try {
            server = new LocalServerSocket(SOCKET);
        } catch (java.io.IOException taken) {
            System.out.println("ALREADY_RUNNING " + SOCKET);
            System.out.flush();
            return;
        }
        exemptHiddenApis();
        if (Looper.getMainLooper() == null) Looper.prepareMainLooper();
        HandlerThread thread = new HandlerThread("wispr-agent-uia");
        thread.start();
        Object connection = Class.forName("android.app.UiAutomationConnection").getDeclaredConstructor().newInstance();
        automation = newUiAutomation(thread.getLooper(), connection);
        UiAutomation.class.getDeclaredMethod("connect", int.class).invoke(automation, DONT_SUPPRESS);
        AccessibilityServiceInfo info = new AccessibilityServiceInfo();
        info.eventTypes = AccessibilityEvent.TYPES_ALL_MASK;
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC;
        info.flags = AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
                | AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
                | AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS;
        automation.setServiceInfo(info);
        resolveInjector();
        final long idleLimit = idleExitMs;
        Thread watchdog = new Thread(() -> {
            while (true) {
                try { Thread.sleep(5_000); } catch (InterruptedException e) { return; }
                if (SystemClock.uptimeMillis() - lastCommand > idleLimit) {
                    try { UiAutomation.class.getDeclaredMethod("disconnect").invoke(automation); } catch (Throwable ignored) { }
                    System.exit(0);
                }
            }
        }, "wispr-agent-idle");
        watchdog.setDaemon(true);
        watchdog.start();

        System.out.println("READY " + SOCKET);
        System.out.flush();
        while (true) {
            LocalSocket client = server.accept();
            if (!serve(client)) break;
        }
        try { UiAutomation.class.getDeclaredMethod("disconnect").invoke(automation); } catch (Throwable ignored) { }
        System.exit(0);
    }

    /** Serves one connection; false once a `quit` arrives. */
    private static boolean serve(LocalSocket client) {
        try (LocalSocket socket = client) {
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            OutputStream out = socket.getOutputStream();
            String line;
            while ((line = in.readLine()) != null) {
                lastCommand = SystemClock.uptimeMillis();
                if (line.equals("quit")) {
                    // Quit means quit, whether or not the goodbye reaches the host (review round 3).
                    try { reply(out, "bye"); } catch (Throwable ignored) { }
                    return false;
                }
                try {
                    reply(out, handle(line));
                } catch (Throwable error) {
                    String message = (error.getClass().getSimpleName() + ": " + error.getMessage()).replace('\n', ' ');
                    out.write(("ERR " + message + "\n").getBytes(StandardCharsets.UTF_8));
                    out.flush();
                }
            }
        } catch (Throwable ignored) {
            // A dropped client is the host going away; wait for the next one.
        }
        return true;
    }

    private static void reply(OutputStream out, String body) throws Exception {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        out.write(("OK " + bytes.length + "\n").getBytes(StandardCharsets.UTF_8));
        out.write(bytes);
        out.flush();
    }

    private static String handle(String line) throws Exception {
        if (line.indexOf('\r') >= 0) throw new IllegalArgumentException("a command may not contain a carriage return");
        String[] parts = line.split(" ", 2);
        String rest = parts.length > 1 ? parts[1] : "";
        String[] a = rest.isEmpty() ? new String[0] : rest.split(" ");
        switch (parts[0]) {
            case "ping": return "pong";
            case "dump": return dump();
            case "tap":
                requireTopmost(Float.parseFloat(a[0]), Float.parseFloat(a[1]), a[2]);
                tap(Float.parseFloat(a[0]), Float.parseFloat(a[1]));
                return "";
            case "swipe":
                requireTopmost(Float.parseFloat(a[0]), Float.parseFloat(a[1]), a[5]);
                swipe(Float.parseFloat(a[0]), Float.parseFloat(a[1]), Float.parseFloat(a[2]), Float.parseFloat(a[3]), Long.parseLong(a[4]));
                return "";
            case "text": text(rest); return "";
            case "key": key(Integer.parseInt(a[0])); return "";
            case "settle": return settle(rest);
            case "ime": return imeShown() ? "shown" : "hidden";
            case "idle":
                try { automation.waitForIdle(Long.parseLong(a[0]), Long.parseLong(a[1])); return "idle"; }
                catch (java.util.concurrent.TimeoutException e) { return "busy"; }
            default: throw new IllegalArgumentException("unknown command " + parts[0]);
        }
    }

    private static final int CHANGE_EVENTS = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
            | AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED | AccessibilityEvent.TYPE_WINDOWS_CHANGED
            | AccessibilityEvent.TYPE_VIEW_SCROLLED;
    private static volatile long lastChange;

    private static String settle(String rest) throws Exception {
        String[] p = rest.split(" ", 4);
        long quiet = Long.parseLong(p[0]), change = Long.parseLong(p[1]), timeout = Long.parseLong(p[2]);
        String action = p[3];
        long start = SystemClock.uptimeMillis();
        lastChange = 0;
        automation.setOnAccessibilityEventListener(event -> {
            // Status bar churn (a clock tick) is not the app answering the press.
            CharSequence pkg = event.getPackageName();
            if ((event.getEventType() & CHANGE_EVENTS) != 0 && (pkg == null || !"com.android.systemui".contentEquals(pkg))) {
                lastChange = SystemClock.uptimeMillis();
            }
        });
        try {
            handle(action);
            long changeBy = start + change, end = start + timeout;
            while (lastChange == 0 && SystemClock.uptimeMillis() < changeBy) Thread.sleep(5);
            if (lastChange == 0) return activeWindowDrawn() ? "same" : "busy";
            while (SystemClock.uptimeMillis() < end && SystemClock.uptimeMillis() - lastChange < quiet) Thread.sleep(5);
            // QUIET IS NOT DRAWN. Between one page leaving and the next drawing there can be a quiet gap
            // with no app window content at all (2026-10-01, emulator Settings: a read there saw only the
            // status bar). Wait, bounded, until the active window has a root with children.
            while (SystemClock.uptimeMillis() < end && !activeWindowDrawn()) Thread.sleep(10);
            if (!activeWindowDrawn()) return "busy";
            return SystemClock.uptimeMillis() - lastChange >= quiet ? "changed" : "busy";
        } finally {
            automation.setOnAccessibilityEventListener(null);
        }
    }

    /** Whether an input-method window (the on-screen keyboard) is showing, so typed keys have somewhere to go. */
    private static boolean imeShown() {
        List<AccessibilityWindowInfo> windows = automation.getWindows();
        if (windows == null) return false;
        for (AccessibilityWindowInfo window : windows) if (window.getType() == AccessibilityWindowInfo.TYPE_INPUT_METHOD) return true;
        return false;
    }

    /** Refuses unless window `id` is the topmost window whose bounds contain (x, y). */
    private static void requireTopmost(float x, float y, String id) {
        List<AccessibilityWindowInfo> windows = automation.getWindows();
        if (windows == null || windows.isEmpty()) throw new IllegalStateException("no windows are visible, so where a touch lands is unknown");
        AccessibilityWindowInfo top = null;
        Rect bounds = new Rect();
        for (AccessibilityWindowInfo window : windows) {
            window.getBoundsInScreen(bounds);
            if (bounds.contains((int) x, (int) y) && (top == null || window.getLayer() > top.getLayer())) top = window;
        }
        if (top == null) throw new IllegalStateException("no window contains (" + (int) x + ", " + (int) y + ")");
        if (!String.valueOf(top.getId()).equals(id)) {
            throw new IllegalStateException("covered: (" + (int) x + ", " + (int) y + ") belongs to window " + top.getId()
                    + " (type " + top.getType() + "), not the aimed window " + id);
        }
    }

    private static boolean activeWindowDrawn() {
        clearCache();
        AccessibilityNodeInfo root = automation.getRootInActiveWindow();
        return root != null && root.getChildCount() > 0;
    }

    // ---- reading -------------------------------------------------------------------------------

    /** Every window's tree, in the `uiautomator dump` shape the harness already parses. */
    private static String dump() {
        // THE CACHE GOES STALE. UiAutomation keeps an accessibility node cache that a scroll updates only
        // partly; read through it and a row reports a negative bottom edge (measured 2026-10-01 on the
        // emulator's Settings list after one scroll). Clear it so every dump is read fresh.
        clearCache();
        StringBuilder xml = new StringBuilder(64 * 1024);
        xml.append("<?xml version='1.0' encoding='UTF-8' standalone='yes' ?><hierarchy rotation=\"0\">");
        List<AccessibilityWindowInfo> windows = automation.getWindows();
        boolean any = false;
        if (windows != null) {
            // Bottom layer first, so later nodes draw over earlier ones, as on screen.
            windows.sort((x, y) -> Integer.compare(x.getLayer(), y.getLayer()));
            for (AccessibilityWindowInfo window : windows) {
                AccessibilityNodeInfo root = window.getRoot();
                // EVERY window is reported, a rootless one too: it still covers what is under it, and leaving
                // it out let a press go under it (code review round 2).
                if (root != null) any = true;
                // WINDOW IDENTITY TRAVELS WITH ITS NODES, so the host can refuse a target that a higher
                // window (a dialog, the keyboard, a bar) covers: a tap goes to whatever is on top.
                Rect wb = new Rect();
                window.getBoundsInScreen(wb);
                xml.append("<window id=\"").append(window.getId()).append("\" layer=\"").append(window.getLayer())
                        .append("\" type=\"").append(window.getType()).append("\" bounds=\"[").append(wb.left).append(',')
                        .append(wb.top).append("][").append(wb.right).append(',').append(wb.bottom).append("]\">");
                if (root != null) node(xml, root, 0);
                xml.append("</window>");
            }
        }
        if (!any) {
            // No window had a root: fall back to the active window alone, outside any <window>, so the host
            // knows it has no covering information for these nodes.
            // Its nodes carry no window, and the host refuses to press anything without one.
            AccessibilityNodeInfo root = automation.getRootInActiveWindow();
            if (root != null) node(xml, root, 0);
        }
        xml.append("</hierarchy>");
        return xml.toString();
    }

    private static Method clearCache;

    private static void clearCache() {
        try {
            if (clearCache == null) clearCache = UiAutomation.class.getMethod("clearCache");
            clearCache.invoke(automation);
        } catch (Throwable error) {
            throw new IllegalStateException("the accessibility cache could not be cleared, so a dump could be stale", error);
        }
    }

    private static void node(StringBuilder xml, AccessibilityNodeInfo node, int index) {
        Rect r = new Rect();
        node.getBoundsInScreen(r);
        xml.append("<node index=\"").append(index).append('"');
        attr(xml, "text", node.getText());
        attr(xml, "resource-id", node.getViewIdResourceName());
        attr(xml, "class", node.getClassName());
        attr(xml, "package", node.getPackageName());
        attr(xml, "content-desc", node.getContentDescription());
        flag(xml, "checkable", node.isCheckable());
        flag(xml, "checked", node.isChecked());
        flag(xml, "clickable", node.isClickable());
        flag(xml, "enabled", node.isEnabled());
        flag(xml, "focusable", node.isFocusable());
        flag(xml, "focused", node.isFocused());
        flag(xml, "scrollable", node.isScrollable());
        flag(xml, "long-clickable", node.isLongClickable());
        flag(xml, "password", node.isPassword());
        flag(xml, "selected", node.isSelected());
        flag(xml, "showing-hint", node.isShowingHintText());
        xml.append(" bounds=\"[").append(r.left).append(',').append(r.top).append("][")
                .append(r.right).append(',').append(r.bottom).append("]\">");
        int count = node.getChildCount();
        for (int i = 0; i < count; i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) node(xml, child, i);
        }
        xml.append("</node>");
    }

    private static void attr(StringBuilder xml, String name, CharSequence value) {
        xml.append(' ').append(name).append("=\"");
        if (value != null) {
            for (int i = 0; i < value.length(); i++) {
                char c = value.charAt(i);
                switch (c) {
                    case '&': xml.append("&amp;"); break;
                    case '<': xml.append("&lt;"); break;
                    case '>': xml.append("&gt;"); break;
                    case '"': xml.append("&quot;"); break;
                    case '\n': xml.append("&#10;"); break;
                    default:
                        // XML 1.0 forbids C0 controls (bar tab) and U+FFFE/U+FFFF; a lone surrogate also breaks a parser.
                        if ((c < 0x20 && c != '\t') || c == 0xFFFE || c == 0xFFFF) xml.append(' ');
                        else if (Character.isHighSurrogate(c) && i + 1 < value.length() && Character.isLowSurrogate(value.charAt(i + 1))) { xml.append(c).append(value.charAt(++i)); }
                        else if (Character.isSurrogate(c)) xml.append(' ');
                        else xml.append(c);
                }
            }
        }
        xml.append('"');
    }

    private static void flag(StringBuilder xml, String name, boolean value) {
        xml.append(' ').append(name).append("=\"").append(value).append('"');
    }

    // ---- input ---------------------------------------------------------------------------------

    private static void tap(float x, float y) throws Exception {
        long down = SystemClock.uptimeMillis();
        motion(down, down, MotionEvent.ACTION_DOWN, x, y);
        boolean lifted = false;
        try {
            motion(down, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, x, y);
            lifted = true;
        } finally {
            // A DOWN with no UP leaves a finger on the glass for the whole system; never leave one.
            if (!lifted) cancel(down, x, y);
        }
    }

    private static void cancel(long down, float x, float y) {
        try { motion(down, SystemClock.uptimeMillis(), MotionEvent.ACTION_CANCEL, x, y); } catch (Throwable ignored) { }
    }

    /** A drag at a steady speed: `ms` long, one move every 8 ms, so a slow drag does not fling. */
    private static void swipe(float x1, float y1, float x2, float y2, long ms) throws Exception {
        long down = SystemClock.uptimeMillis();
        motion(down, down, MotionEvent.ACTION_DOWN, x1, y1);
        boolean lifted = false;
        try {
            int steps = (int) Math.max(1, ms / 8);
            for (int i = 1; i <= steps; i++) {
                float f = (float) i / steps;
                long when = down + ms * i / steps;
                long wait = when - SystemClock.uptimeMillis();
                if (wait > 0) Thread.sleep(wait);
                motion(down, when, MotionEvent.ACTION_MOVE, x1 + (x2 - x1) * f, y1 + (y2 - y1) * f);
            }
            // Hold still before lifting, so the list stops where the finger stops instead of flinging on.
            Thread.sleep(60);
            motion(down, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, x2, y2);
            lifted = true;
        } finally {
            if (!lifted) cancel(down, x2, y2);
        }
    }

    private static void motion(long down, long when, int action, float x, float y) throws Exception {
        MotionEvent event = MotionEvent.obtain(down, when, action, x, y, 0);
        event.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        send(event);
        event.recycle();
    }

    private static void text(String value) throws Exception {
        KeyEvent[] events = KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD).getEvents(value.toCharArray());
        if (events == null) throw new IllegalArgumentException("text has characters the virtual keyboard map cannot type");
        // Every key (a character or a shift) that went DOWN is released if a later event fails, flagged
        // cancelled, so a failure mid-text never leaves a key held (code review round 2).
        java.util.ArrayDeque<KeyEvent> held = new java.util.ArrayDeque<>();
        boolean finished = false;
        try {
            for (KeyEvent event : events) {
                send(event);
                if (event.getAction() == KeyEvent.ACTION_DOWN) held.push(event);
                else if (event.getAction() == KeyEvent.ACTION_UP) held.removeIf(down -> down.getKeyCode() == event.getKeyCode());
            }
            finished = true;
        } finally {
            if (!finished) {
                for (KeyEvent down : held) {
                    long now = SystemClock.uptimeMillis();
                    try { send(new KeyEvent(down.getDownTime(), now, KeyEvent.ACTION_UP, down.getKeyCode(), 0, down.getMetaState(), -1, 0, KeyEvent.FLAG_CANCELED)); } catch (Throwable ignored) { }
                }
            }
        }
    }

    private static void key(int code) throws Exception {
        long now = SystemClock.uptimeMillis();
        send(new KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0));
        try {
            send(new KeyEvent(now, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, code, 0));
        } catch (Exception failed) {
            // A key left down repeats; release it, flagged cancelled, then report the failure.
            try { send(new KeyEvent(now, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, code, 0, 0, -1, 0, KeyEvent.FLAG_CANCELED)); } catch (Throwable ignored) { }
            throw failed;
        }
    }

    /** InputManager when reachable (fast, no UiAutomation), else UiAutomation's own injector. */
    private static void send(InputEvent event) throws Exception {
        if (inject != null) {
            // ASYNC, as scrcpy injects. WAIT_FOR_FINISH blocks on the app handling each event, and a drag
            // of ~40 moves on a busy page took 4 to 6 s on the emulator (2026-10-01). Timing is ours:
            // `swipe` paces its moves, and the host waits for the screen to settle after every action.
            Object ok = inject.invoke(injector, event, 0 /* INJECT_INPUT_EVENT_MODE_ASYNC */);
            if (Boolean.TRUE.equals(ok)) return;
        }
        if (!automation.injectInputEvent(event, false)) throw new IllegalStateException("input event was not injected");
    }

    private static void resolveInjector() {
        for (String name : new String[]{"android.hardware.input.InputManagerGlobal", "android.hardware.input.InputManager"}) {
            try {
                Class<?> type = Class.forName(name);
                Object instance = type.getDeclaredMethod("getInstance").invoke(null);
                Method method = type.getMethod("injectInputEvent", InputEvent.class, int.class);
                injector = instance;
                inject = method;
                return;
            } catch (Throwable ignored) {
                // Try the next owner; UiAutomation remains the fallback.
            }
        }
    }

    // ---- hidden API plumbing (the same path the platform's own `uiautomator` shell tool takes) ---

    private static void exemptHiddenApis() throws Exception {
        Method getDeclaredMethod = Class.class.getDeclaredMethod("getDeclaredMethod", String.class, Class[].class);
        Class<?> runtime = Class.forName("dalvik.system.VMRuntime");
        Method getRuntime = (Method) getDeclaredMethod.invoke(runtime, "getRuntime", null);
        Method exemptions = (Method) getDeclaredMethod.invoke(runtime, "setHiddenApiExemptions", new Class<?>[]{String[].class});
        exemptions.invoke(getRuntime.invoke(null), new Object[]{new String[]{"L"}});
    }

    private static UiAutomation newUiAutomation(Looper looper, Object connection) throws Exception {
        for (Constructor<?> constructor : UiAutomation.class.getDeclaredConstructors()) {
            Class<?>[] types = constructor.getParameterTypes();
            Object[] values = new Object[types.length];
            boolean looperSet = false, connectionSet = false;
            for (int i = 0; i < types.length; i++) {
                if (types[i] == Looper.class) { values[i] = looper; looperSet = true; }
                else if (types[i].isInstance(connection)) { values[i] = connection; connectionSet = true; }
                else if (types[i] == int.class) values[i] = 0;
            }
            if (looperSet && connectionSet) {
                constructor.setAccessible(true);
                return (UiAutomation) constructor.newInstance(values);
            }
        }
        throw new NoSuchMethodException("no UiAutomation constructor takes a Looper and a connection");
    }

    private Main() { }
}
