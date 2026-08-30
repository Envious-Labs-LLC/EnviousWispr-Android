#!/usr/bin/env bash
# test-hooks.sh — every guard, both directions. A guard never observed failing is a comment.
#
# Each case asserts BOTH that the guard fires on a real violation AND that it stays silent on the
# legitimate case. The second half is the one that matters most: `check-must-not-fire-on-a-clean-tree`
# calls the always-fires failure the more dangerous one, because it looks like protection while training
# the reader to skim past it.
#
# JSON payloads are built with python, never `echo`. An early run of these controls used `echo` with \n
# inside the string, zsh interpreted the escape, the payload stopped being valid JSON, and three guards
# read as silently passing when they had never been reached.

set -uo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/../.." || exit 2
HOOKS="scripts/hooks"
PASS=0; FAIL=0

payload() { python3 -c "import json,sys; print(json.dumps(json.loads(sys.argv[1])))" "$1"; }

# assert <name> <deny|allow> <hook> <json>
assert() {
    local name="$1" want="$2" hook="$3" json="$4"
    local out; out=$(payload "$json" | "$HOOKS/$hook" 2>/dev/null)
    local got="allow"; [ -n "$out" ] && got="deny"
    if [ "$got" = "$want" ]; then
        PASS=$((PASS+1)); printf '  ok    %-58s %s\n' "$name" "$got"
    else
        FAIL=$((FAIL+1)); printf '  FAIL  %-58s wanted %s, got %s\n' "$name" "$want" "$got"
    fi
}

BRANCH=$(git rev-parse --abbrev-ref HEAD)
echo "Guard controls (branch: $BRANCH)"
echo

# THE DENY HALVES USED TO BE UNREACHABLE FROM A BRANCH, and the suite printed a note saying so. That left
# the blocking behaviour of two guards never once observed, which is precisely the state this file exists
# to prevent: a guard is silent when it allows, so an unexercised deny half is indistinguishable from a
# broken one.
#
# The fix is NOT a test seam. Making `branch()` settable would ship an unlogged bypass anyone could reach
# (validation-discipline RULE: a-test-seam-on-a-GUARD-is-a-bypass). Instead, build a throwaway git
# repository whose current branch really is `main`, copy the guards into it, and run them there. Both
# guards derive their repository root from their own `__file__`, so the copy reads the throwaway repo and
# the SHIPPED BYTES are what gets exercised — no source modification anywhere.
MAINREPO=$(mktemp -d)
trap 'rm -rf "$MAINREPO"' EXIT
git init -q -b main "$MAINREPO"
mkdir -p "$MAINREPO/scripts/hooks" "$MAINREPO/app/src/main"
cp "$HOOKS"/*.py "$MAINREPO/scripts/hooks/"
git -C "$MAINREPO" -c user.email=t@t -c user.name=t commit -q --allow-empty -m base
ON_MAIN="$MAINREPO/scripts/hooks"

# assert_main <name> <deny|allow> <hook> <json> — the same contract, run inside the `main` repository.
assert_main() {
    local name="$1" want="$2" hook="$3" json="$4"
    local out; out=$(payload "$json" | "$ON_MAIN/$hook" 2>/dev/null)
    local got="allow"; [ -n "$out" ] && got="deny"
    if [ "$got" = "$want" ]; then
        PASS=$((PASS+1)); printf '  ok    %-58s %s\n' "$name" "$got"
    else
        FAIL=$((FAIL+1)); printf '  FAIL  %-58s wanted %s, got %s\n' "$name" "$want" "$got"
    fi
}

echo "check-protected-paths.py — edit time"
assert "ship-path edit on a branch"            allow check-protected-paths.py '{"tool_input":{"file_path":"app/src/main/Foo.kt"}}'
assert_main "ship-path edit on main"           deny  check-protected-paths.py '{"tool_input":{"file_path":"app/src/main/Foo.kt"}}'
assert_main "Room schema on main"              deny  check-protected-paths.py '{"tool_input":{"file_path":"app/schemas/1.json"}}'
assert_main "submodule pointer on main"        deny  check-protected-paths.py '{"tool_input":{"file_path":".gitmodules"}}'
assert_main "the ignore file on main"          deny  check-protected-paths.py '{"tool_input":{"file_path":".gitignore"}}'
assert_main "local-only path on main"          allow check-protected-paths.py '{"tool_input":{"file_path":"docs/internal/x.md"}}'
assert_main "the benchmark is not a ship path" allow check-protected-paths.py '{"tool_input":{"file_path":"accelerator-benchmark/src/main/B.kt"}}'
echo

echo "command-safety.py — commit gate and shell writes"
assert "git commit -am on a branch"            allow command-safety.py '{"tool_input":{"command":"git commit -am wip"}}'
assert "an ordinary read command"              allow command-safety.py '{"tool_input":{"command":"git status --short"}}'
assert_main "git commit -am on main"           deny  command-safety.py '{"tool_input":{"command":"git commit -am wip"}}'
assert_main "explicit pathspec on main"        deny  command-safety.py '{"tool_input":{"command":"git commit -m x -- app/src/main/Foo.kt"}}'
assert_main "heredoc into a ship path on main" deny  command-safety.py '{"tool_input":{"command":"cat > app/src/main/Foo.kt <<EOF"}}'
assert_main "append into a ship path on main"  deny  command-safety.py '{"tool_input":{"command":"echo x >> app/src/main/Foo.kt"}}'
assert_main "sed -i into a ship path on main"  deny  command-safety.py '{"tool_input":{"command":"sed -i \"\" s/a/b/ app/src/main/AndroidManifest.xml"}}'
assert_main "write to a local-only path"       allow command-safety.py '{"tool_input":{"command":"cat > docs/internal/x.md <<EOF"}}'
assert_main "an ordinary read command"         allow command-safety.py '{"tool_input":{"command":"git status --short"}}'
# The false-positive half. A quoted `>` is text, not shell syntax, and denying it would fire the guard on
# ordinary correct work — the failure `guard-design-pre-read` calls worse than having no guard at all.
assert_main "a quoted redirect is not a write" allow command-safety.py '{"tool_input":{"command":"printf %s \"see > app/src/main/Foo.kt\""}}'
# The staged-set half, which needs a real index rather than a command string.
: > "$MAINREPO/app/src/main/Foo.kt"; git -C "$MAINREPO" add app/src/main/Foo.kt
assert_main "staged ship path on main"         deny  command-safety.py '{"tool_input":{"command":"git commit -m x"}}'
git -C "$MAINREPO" reset -q
assert_main "empty index on main"              allow command-safety.py '{"tool_input":{"command":"git commit -m x"}}'
echo

echo "check-plan-gates.py — the Tier 0 plan gates"
PLAN='docs/feature-requests/issue-9901-2026-01-01-control.md'
SENTINEL=/tmp/.ew-android-issue-9901-context-read
rm -f "$SENTINEL"
assert "a non-plan file"                       allow check-plan-gates.py '{"tool_input":{"file_path":"app/src/main/Foo.kt","content":"x"}}'
assert "new plan, prior context not attested"  deny  check-plan-gates.py "{\"tool_input\":{\"file_path\":\"$PLAN\",\"content\":\"body\"}}"
touch "$SENTINEL"
assert "attested, but no User Rubric"          deny  check-plan-gates.py "{\"tool_input\":{\"file_path\":\"$PLAN\",\"content\":\"**Lane:** Code\"}}"
assert "rubric present, no lane"               deny  check-plan-gates.py "{\"tool_input\":{\"file_path\":\"$PLAN\",\"content\":\"User Rubric: N/A — internal tooling only\"}}"
assert "bare N/A rubric is not an answer"      deny  check-plan-gates.py "{\"tool_input\":{\"file_path\":\"$PLAN\",\"content\":\"User Rubric: N/A\n**Lane:** Code\"}}"
assert "unknown lane spelling"                 deny  check-plan-gates.py "{\"tool_input\":{\"file_path\":\"$PLAN\",\"content\":\"User Rubric: N/A — internal only\n**Lane:** docs\"}}"
assert "attested, rubric, valid lane"          allow check-plan-gates.py "{\"tool_input\":{\"file_path\":\"$PLAN\",\"content\":\"User Rubric: N/A — internal only\n**Lane:** Code\"}}"
LONG=$(python3 -c "print('User Rubric: N/A — internal only\n**Lane:** Code\n' + 'x '*2200)")
assert "long plan naming no consolidation"     deny  check-plan-gates.py "$(python3 -c "
import json,sys; print(json.dumps({'tool_input':{'file_path':'$PLAN','content':sys.argv[1]}}))" "$LONG")"
# The FALSE-POSITIVE half of the plan gate, and it is the one that was wrong. The hook judged the
# fragment a call carries rather than the document the call produces, so fixing a single typo in a
# finished plan arrived as a "body" with no rubric and no lane, and all three gates fired on a plan that
# satisfied every one of them.
EDITPLAN='docs/feature-requests/issue-9902-2026-01-01-control.md'
printf 'User Rubric: N/A — internal only\n**Lane:** Code\n\nteh design is fine.\n' > "$EDITPLAN"
assert "typo fix in a complete plan"           allow check-plan-gates.py "$(python3 -c "
import json; print(json.dumps({'tool_input':{'file_path':'$EDITPLAN','old_string':'teh','new_string':'the'}}))")"
printf 'no rubric here\n' > "$EDITPLAN"
assert "edit leaving a plan incomplete"        deny  check-plan-gates.py "$(python3 -c "
import json; print(json.dumps({'tool_input':{'file_path':'$EDITPLAN','old_string':'no rubric here','new_string':'still no rubric'}}))")"
rm -f "$EDITPLAN"
rm -f "$SENTINEL" /tmp/.ew-android-issue-9901-pending-plan.md
echo

# The README carries a copy of the live hook registration, because `.claude/` is gitignored and that copy
# is the only thing a fresh clone can restore the wiring from. A copy nothing compares is how the two
# drift apart silently, so compare them here. The README says this check exists; that sentence is only
# true while this block is.
echo "registration mirror — README against the live settings"
if [ -f .claude/settings.json ]; then
    if python3 - <<'PY'
import json, re, sys
readme = open("scripts/hooks/README.md", encoding="utf-8").read()
block = re.search(r"```json\n(.*?)```", readme, re.S)
if not block:
    print("  FAIL  README carries no json block to compare"); sys.exit(1)
mirrored = json.loads("{" + block.group(1).strip() + "}")
live = json.load(open(".claude/settings.json", encoding="utf-8"))
if mirrored.get("hooks") == live.get("hooks"):
    sys.exit(0)
print("  the README block and .claude/settings.json disagree:")
print("  README:", json.dumps(mirrored.get("hooks"), sort_keys=True))
print("  live:  ", json.dumps(live.get("hooks"), sort_keys=True))
sys.exit(1)
PY
    then PASS=$((PASS+1)); echo "  ok    README mirrors the armed registration"
    else FAIL=$((FAIL+1)); echo "  FAIL  README and .claude/settings.json disagree"; fi
else
    echo "  note  .claude/settings.json is absent, so no guard is armed in this checkout"
fi
echo

echo "session-end-check.sh — silent when clean"
OUT=$("$HOOKS/session-end-check.sh" 2>&1)
DIRTY=$(( $(git status --porcelain | wc -l) ))
AHEAD=$(git rev-list --count '@{u}'..HEAD 2>/dev/null || git rev-list --count origin/main..HEAD 2>/dev/null || echo 0)
if [ "$DIRTY" -eq 0 ] && [ "$AHEAD" -eq 0 ]; then
    if [ -z "$OUT" ]; then PASS=$((PASS+1)); echo "  ok    clean tree is completely silent"
    else FAIL=$((FAIL+1)); echo "  FAIL  clean tree printed ${#OUT} chars"; fi
else
    if [ -n "$OUT" ]; then PASS=$((PASS+1)); echo "  ok    leftovers reported ($DIRTY dirty, $AHEAD unpushed)"
    else FAIL=$((FAIL+1)); echo "  FAIL  $DIRTY dirty and $AHEAD unpushed, reported nothing"; fi
fi
echo
echo "$PASS passed, $FAIL failed"
[ "$FAIL" -eq 0 ]
