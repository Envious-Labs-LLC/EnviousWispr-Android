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

echo "check-protected-paths.py — edit time"
if [ "$BRANCH" = "main" ]; then
    assert "ship-path edit on main"            deny  check-protected-paths.py '{"tool_input":{"file_path":"app/src/main/Foo.kt"}}'
    assert "Room schema on main"               deny  check-protected-paths.py '{"tool_input":{"file_path":"app/schemas/1.json"}}'
    assert "submodule pointer on main"         deny  check-protected-paths.py '{"tool_input":{"file_path":".gitmodules"}}'
    assert "local-only path on main"           allow check-protected-paths.py '{"tool_input":{"file_path":"docs/internal/x.md"}}'
else
    assert "ship-path edit on a branch"        allow check-protected-paths.py '{"tool_input":{"file_path":"app/src/main/Foo.kt"}}'
    echo "  note  the deny half needs main; re-run this script there"
fi
echo

echo "command-safety.py — commit gate and shell writes"
if [ "$BRANCH" = "main" ]; then
    assert "git commit -am on main"            deny  command-safety.py '{"tool_input":{"command":"git commit -am wip"}}'
    assert "heredoc into a ship path on main"  deny  command-safety.py '{"tool_input":{"command":"cat > app/src/main/Foo.kt <<EOF"}}'
    assert "sed -i into a ship path on main"   deny  command-safety.py '{"tool_input":{"command":"sed -i \"\" s/a/b/ app/src/main/AndroidManifest.xml"}}'
    assert "write to a local-only path"        allow command-safety.py '{"tool_input":{"command":"cat > docs/internal/x.md <<EOF"}}'
    assert "an ordinary read command"          allow command-safety.py '{"tool_input":{"command":"git status --short"}}'
else
    assert "git commit -am on a branch"        allow command-safety.py '{"tool_input":{"command":"git commit -am wip"}}'
    assert "an ordinary read command"          allow command-safety.py '{"tool_input":{"command":"git status --short"}}'
    echo "  note  the deny half needs main; re-run this script there"
fi
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
rm -f "$SENTINEL" /tmp/.ew-android-issue-9901-pending-plan.md
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
