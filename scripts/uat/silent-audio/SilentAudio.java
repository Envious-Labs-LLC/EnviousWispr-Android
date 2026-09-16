import android.content.Context;
import android.media.*;
import android.os.*;
import java.io.*;
import java.lang.reflect.*;
import java.nio.file.*;

/** ADB-shell-only UAT helper. Never packaged in any APK. No audio is read off the phone. */
public final class SilentAudio {
    static final java.util.concurrent.atomic.AtomicBoolean takeOwed = new java.util.concurrent.atomic.AtomicBoolean();
    static void cancelTake() {
        if (!takeOwed.getAndSet(false)) return;
        try {
            new ProcessBuilder("am", "start", "-n", "com.envi.wispr/.ui.VoiceInputActivity",
                "--ez", "cancel", "true").start().waitFor();
        } catch (Exception e) { System.err.println("CANCEL_FAILED " + e); }
    }
    static Object call(Object o, String name, Class<?>[] types, Object... args) throws Exception {
        return o.getClass().getMethod(name, types).invoke(o, args);
    }
    static int constant(Class<?> c, String name) throws Exception { return c.getField(name).getInt(null); }
    public static void main(String[] args) {
        int result = 1;
        try { run(args); result = 0; } catch (Throwable t) { t.printStackTrace(); } finally { cancelTake(); }
        System.exit(result);
    }
    static void run(String[] args) throws Exception {
        if (android.os.Process.myUid() != 2000) throw new SecurityException("ADB shell required");
        if (args.length != 1) throw new IllegalArgumentException("local 16k mono s16le PCM required");
        byte[] pcm = Files.readAllBytes(Paths.get(args[0]));
        if (pcm.length < 3200 || pcm.length > 960000 || pcm.length % 2 != 0)
            throw new IllegalArgumentException("PCM must be 0.1 to 30 seconds, whole samples");
        // Deadline fallback: process death also releases the binder-owned routing policy.
        Thread watchdog = new Thread(() -> { SystemClock.sleep(60000); cancelTake(); System.exit(124); });
        watchdog.setDaemon(true); watchdog.start();
        Looper.prepareMainLooper();
        Class<?> at = Class.forName("android.app.ActivityThread");
        Object thread = at.getMethod("systemMain").invoke(null);
        Context system = (Context) at.getMethod("getSystemContext").invoke(thread);
        Context context = system.createPackageContext("com.android.shell", 0);
        Field attribution = context.getClass().getDeclaredField("mAttributionSource");
        attribution.setAccessible(true);
        attribution.set(context, new android.content.AttributionSource.Builder(2000)
            .setPackageName("com.android.shell").build());
        Field opPackage = context.getClass().getDeclaredField("mOpPackageName");
        opPackage.setAccessible(true); opPackage.set(context, "com.android.shell");
        android.app.Application app = new android.app.Application();
        Method attach = android.app.Application.class.getDeclaredMethod("attach", Context.class);
        attach.setAccessible(true); attach.invoke(app, context);
        Field initial = at.getDeclaredField("mInitialApplication");
        initial.setAccessible(true); initial.set(thread, app);
        int uid = context.getPackageManager().getApplicationInfo("com.envi.wispr", 0).uid;
        Class<?> ruleClass = Class.forName("android.media.audiopolicy.AudioMixingRule");
        Class<?> ruleBuilder = Class.forName(ruleClass.getName() + "$Builder");
        Object rb = ruleBuilder.getConstructor().newInstance();
        call(rb, "setTargetMixRole", new Class[]{int.class}, constant(ruleClass, "MIX_ROLE_INJECTOR"));
        call(rb, "addMixRule", new Class[]{int.class, Object.class}, constant(ruleClass, "RULE_MATCH_UID"), uid);
        Object rule = call(rb, "build", new Class[]{});
        Class<?> mixClass = Class.forName("android.media.audiopolicy.AudioMix");
        Object mb = Class.forName(mixClass.getName()+"$Builder").getConstructor(ruleClass).newInstance(rule);
        int flags = constant(mixClass, "ROUTE_FLAG_LOOP_BACK");
        call(mb, "setRouteFlags", new Class[]{int.class}, flags); // NO RENDER flag, ever.
        call(mb, "setFormat", new Class[]{AudioFormat.class}, new AudioFormat.Builder()
            .setSampleRate(16000).setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build());
        Object mix = call(mb, "build", new Class[]{});
        Class<?> policyClass = Class.forName("android.media.audiopolicy.AudioPolicy");
        Object pb = Class.forName(policyClass.getName()+"$Builder").getConstructor(Context.class).newInstance(context);
        call(pb, "addMix", new Class[]{mixClass}, mix);
        Object policy = call(pb, "build", new Class[]{});
        AudioManager manager = context.getSystemService(AudioManager.class);
        int status = (int)call(manager, "registerAudioPolicy", new Class[]{policyClass}, policy);
        if (status != 0) throw new IllegalStateException("registerAudioPolicy="+status);
        AudioTrack track = null;
        try {
            track = (AudioTrack)call(policy, "createAudioTrackSource", new Class[]{mixClass}, mix);
            if (track == null || track.getState() != AudioTrack.STATE_INITIALIZED)
                throw new IllegalStateException("injector unavailable");
            track.play();
            // Feed only digital zero until the virtual route is verified. Never play speech to a speaker.
            byte[] zero = new byte[1024];
            long deadline = SystemClock.elapsedRealtime()+3000;
            AudioDeviceInfo routed;
            do {
                if (track.write(zero, 0, zero.length) != zero.length) throw new IOException("zero write");
                routed = track.getRoutedDevice();
                if (SystemClock.elapsedRealtime() > deadline) throw new IOException("route unavailable");
            } while (routed == null);
            if (routed.getType() != AudioDeviceInfo.TYPE_REMOTE_SUBMIX)
                throw new SecurityException("Refusing non-submix route: "+routed.getType());
            System.out.println("READY uid="+uid+" route=REMOTE_SUBMIX type="+routed.getType()+" flags="+flags+" render=false");
            System.out.flush();
            BufferedReader input = new BufferedReader(new InputStreamReader(System.in));
            if (!"ARM".equals(input.readLine())) throw new IOException("ARM required");
            takeOwed.set(true);
            System.out.println("ARMED"); System.out.flush();
            if (!"GO".equals(input.readLine())) throw new IOException("GO required");
            int offset=0;
            while (offset<pcm.length) {
                routed=track.getRoutedDevice();
                if (routed==null || routed.getType()!=AudioDeviceInfo.TYPE_REMOTE_SUBMIX)
                    throw new SecurityException("Virtual route lost");
                int n=track.write(pcm, offset, Math.min(1024,pcm.length-offset));
                if(n<=0) throw new IOException("inject write="+n);
                offset+=n;
            }
            // Zero tail drains queued speech through the virtual pipe before the caller stops capture.
            for(int i=0;i<16;i++) if(track.write(zero,0,zero.length)!=zero.length) throw new IOException("tail write");
            System.out.println("INJECTED bytes="+offset+" route=REMOTE_SUBMIX render=false");
            System.out.flush();
            if (!"CLOSE".equals(input.readLine())) throw new IOException("CLOSE required");
        } finally {
            try {
                if(track!=null) { try { if(track.getState()==AudioTrack.STATE_INITIALIZED) track.stop(); } finally { track.release(); } }
            } finally {
                call(manager,"unregisterAudioPolicy",new Class[]{policyClass},policy);
                System.out.println("CLEANED policy=unregistered");
            }
        }
    }
}
