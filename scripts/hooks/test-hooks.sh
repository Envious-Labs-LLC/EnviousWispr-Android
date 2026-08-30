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

# THREE OUTCOMES, NOT TWO. The first version read empty stdout as `allow` and any stdout as `deny`, with
# stderr sent to /dev/null and the exit status never looked at. A guard that CRASHED therefore passed every
# single allow control, and a traceback printed to stdout would have passed a deny control — the silent
# third answer that `validation-discipline` FACT: silent-empty-traps says always collapses into "no".
# `error` is that third answer, given a name so it can fail. A deny is only a deny when the JSON says so.
assert_at() {
    local dir="$1" name="$2" want="$3" hook="$4" json="$5"
    local out rc got err="$STDERR"
    out=$(payload "$json" | "$dir/$hook" 2>"$err"); rc=$?
    if [ "$rc" -ne 0 ] || [ -s "$err" ]; then
        got="error"
    elif [ -z "$out" ]; then
        got="allow"
    elif printf '%s' "$out" | python3 -c '
import json, sys
try:
    decision = json.load(sys.stdin)["hookSpecificOutput"]["permissionDecision"]
except Exception:
    sys.exit(1)
sys.exit(0 if decision == "deny" else 1)
'; then
        got="deny"
    else
        got="error"
    fi
    if [ "$got" = "$want" ]; then
        PASS=$((PASS+1)); printf '  ok    %-58s %s\n' "$name" "$got"
    else
        FAIL=$((FAIL+1)); printf '  FAIL  %-58s wanted %s, got %s\n' "$name" "$want" "$got"
        [ -s "$err" ] && sed 's/^/          /' "$err"
    fi
}

# assert <name> <deny|allow> <hook> <json>          — run against this checkout, on whatever branch it is
assert()      { assert_at "$HOOKS" "$@"; }
# assert_main <name> <deny|allow> <hook> <json>     — run against the throwaway repository that is on main
assert_main() { assert_at "$ON_MAIN" "$@"; }

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
MAINREPO=$(mktemp -d) || exit 2
# Deliberately OUTSIDE $MAINREPO: session-end-check.sh is asserted against that repository being CLEAN,
# and a stray file inside it would make the clean control impossible to reach.
STDERR=$(mktemp) || exit 2
SENTINEL=/tmp/.ew-android-issue-9901-context-read
# A plan file under a FIXED name would overwrite and then delete a real file of that name. The suite
# cleans up after itself, so it must only ever create something nothing else owns.
EDITPLAN=$(mktemp docs/feature-requests/issue-9902-2026-01-01-control-XXXXXX.md) || exit 2
trap 'rm -rf "$MAINREPO" "$MAINREPO.git"; rm -f "$STDERR" "$EDITPLAN" "$SENTINEL" /tmp/.ew-android-issue-9901-pending-plan.md' EXIT

# Every setup step is checked. A half-built repository makes controls fail for a reason that has nothing
# to do with the guards, which is the slowest kind of red to read.
git init -q -b main "$MAINREPO" || exit 2
[ "$(git -C "$MAINREPO" symbolic-ref --short HEAD)" = "main" ] || exit 2
mkdir -p "$MAINREPO/scripts/hooks" "$MAINREPO/app/src/main" || exit 2
cp "$HOOKS"/*.py "$HOOKS"/session-end-check.sh "$MAINREPO/scripts/hooks/" || exit 2
git -C "$MAINREPO" -c user.email=t@t -c user.name=t commit -q --allow-empty -m base || exit 2
ON_MAIN="$MAINREPO/scripts/hooks"

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
assert_main "a later pipeline stage is not a tee target" allow command-safety.py '{"tool_input":{"command":"printf x | tee /tmp/log | cat app/src/main/Foo.kt"}}'
assert_main "tee into a ship path on main"     deny  command-safety.py '{"tool_input":{"command":"printf x | tee app/src/main/Foo.kt"}}'
# COMMAND POSITION. Every recognition here used to match a WORD anywhere in the token list, so a command
# that merely PRINTED the words was denied. These three are ordinary correct work and must stay allowed.
assert_main "printing the word git commit"     allow command-safety.py '{"tool_input":{"command":"printf %s git commit -a"}}'
assert_main "printing the word tee"            allow command-safety.py '{"tool_input":{"command":"printf %s tee app/src/main/Foo.kt"}}'
assert_main "a later -a is not this commit's"  allow command-safety.py '{"tool_input":{"command":"git commit -m x ; ls -a"}}'
# And the position parse must still find a real commit behind git's global options.
assert_main "git -C before the subcommand"     deny  command-safety.py '{"tool_input":{"command":"git -C . commit -am wip"}}'
assert_main "FOO=1 git commit -am"             deny  command-safety.py '{"tool_input":{"command":"FOO=1 git commit -am wip"}}'
# ROUND 4's population: git's own option grammar. An option VALUE that looks like a flag, a dry
# run, and a bare positional pathspec are all ordinary, and all three were denied while the
# arguments were read as a flat bag of strings instead of by position.
assert_main 'an option value that looks like a flag'       allow command-safety.py '{"tool_input": {"command": "git commit -m -a"}}'
assert_main 'a filename that looks like a flag'            allow command-safety.py '{"tool_input": {"command": "git commit -F -a"}}'
assert_main 'a dry run writes no history'                  allow command-safety.py '{"tool_input": {"command": "git commit --dry-run -am x"}}'
assert_main 'a positional non-ship pathspec'               allow command-safety.py '{"tool_input": {"command": "git commit -m x docs/note.md"}}'
# And the other direction: real commits that escaped while the shell and git forms were narrow.
assert_main 'a bare & starts a new command'                deny  command-safety.py '{"tool_input": {"command": "git status & git commit -am x"}}'
assert_main 'a newline starts a new command'               deny  command-safety.py '{"tool_input": {"command": "git status\ngit commit -am x"}}'
assert_main 'env FOO=1 git commit -am'                     deny  command-safety.py '{"tool_input": {"command": "env FOO=1 git commit -am x"}}'
assert_main 'the compact -C. global option'                deny  command-safety.py '{"tool_input": {"command": "git -C. commit -am x"}}'
assert_main 'a positional SHIP pathspec'                   deny  command-safety.py '{"tool_input": {"command": "git commit -m x app/src/main/Foo.kt"}}'
assert_main 'sed -i.bak into a ship path'                  deny  command-safety.py '{"tool_input": {"command": "sed -i.bak s/a/b/ app/src/main/Foo.kt"}}'
assert_main 'the >| redirection into a ship path'          deny  command-safety.py '{"tool_input": {"command": "echo x >| app/src/main/Foo.kt"}}'
# `-S[<keyid>]` and `-u[<mode>]` take an optional ATTACHED value. Consuming the next argument ate the
# pathspec here, and ate the `-a` out of a real all-files commit below.
assert_main "--gpg-sign keeps its pathspec"    allow command-safety.py '{"tool_input":{"command":"git commit --gpg-sign -m x docs/note.md"}}'
assert_main "-S does not swallow -a"           deny  command-safety.py '{"tool_input":{"command":"git commit -S -a -m x"}}'
assert_main "a pathspec read from a file"      allow command-safety.py '{"tool_input":{"command":"git commit --pathspec-from-file=list.txt -m x"}}'
# The ABSTENTION, which is the design and therefore needs a control: an option this parser does not know
# must yield no decision rather than a guess about where the subcommand is.
assert_main "an unknown git global option"     allow command-safety.py '{"tool_input":{"command":"git --nonsense commit -am x"}}'
# And a newline INSIDE quotes is text, not a command separator.
assert_main "a newline inside quotes"          allow command-safety.py '{"tool_input":{"command":"printf \"a\nb\" app/src/main/Foo.kt"}}'
# The staged-set half, which needs a real index rather than a command string.
: > "$MAINREPO/app/src/main/Foo.kt"; git -C "$MAINREPO" add app/src/main/Foo.kt
assert_main "staged ship path on main"         deny  command-safety.py '{"tool_input":{"command":"git commit -m x"}}'
assert_main "a non-commit git with it staged"  allow command-safety.py '{"tool_input":{"command":"git log --oneline"}}'
# An explicit pathspec decides what the commit CONTAINS, so an unrelated staged file must not deny it.
mkdir -p "$MAINREPO/docs"; : > "$MAINREPO/docs/note.md"
assert_main "pathspec excluding the ship path" allow command-safety.py '{"tool_input":{"command":"git commit -m x -- docs/note.md"}}'
git -C "$MAINREPO" reset -q
assert_main "empty index on main"              allow command-safety.py '{"tool_input":{"command":"git commit -m x"}}'
echo

echo "check-plan-gates.py — the Tier 0 plan gates"
PLAN='docs/feature-requests/issue-9901-2026-01-01-control.md'
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
printf 'User Rubric: N/A — internal only\n**Lane:** Code\n\nteh design is fine.\n' > "$EDITPLAN"
assert "typo fix in a complete plan"           allow check-plan-gates.py "$(python3 -c "
import json; print(json.dumps({'tool_input':{'file_path':'$EDITPLAN','old_string':'teh','new_string':'the'}}))")"
printf 'no rubric here\n' > "$EDITPLAN"
assert "edit leaving a plan incomplete"        deny  check-plan-gates.py "$(python3 -c "
import json; print(json.dumps({'tool_input':{'file_path':'$EDITPLAN','old_string':'no rubric here','new_string':'still no rubric'}}))")"
# An empty Write is an exactly-known document, not a failure to reconstruct one. The gates must judge it.
assert "an empty plan is still judged"         deny  check-plan-gates.py "{\"tool_input\":{\"file_path\":\"$PLAN\",\"content\":\"\"}}"
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

echo "session-end-check.sh — both directions, in a repository whose state we control"
# The silent half needs a genuinely clean, genuinely pushed repository, and both halves of that matter:
# with no remote at all, `HEAD --not --remotes` counts every commit as unpushed, which is correct and
# would make the clean case unreachable. So give the throwaway repo a real bare origin and push to it.
git init -q --bare "$MAINREPO.git" || exit 2
git -C "$MAINREPO" remote add origin "$MAINREPO.git" || exit 2
git -C "$MAINREPO" add -A >/dev/null 2>&1 || exit 2
git -C "$MAINREPO" -c user.email=t@t -c user.name=t commit -q -m fixtures >/dev/null 2>&1 || exit 2
git -C "$MAINREPO" push -q -u origin main >/dev/null 2>&1 || exit 2
CLEAN_OUT=$("$MAINREPO/scripts/hooks/session-end-check.sh" 2>&1)
if [ -z "$CLEAN_OUT" ]; then
    PASS=$((PASS+1)); echo "  ok    a clean tree is completely silent"
else
    FAIL=$((FAIL+1)); echo "  FAIL  a clean tree printed ${#CLEAN_OUT} chars: $CLEAN_OUT"
fi
: > "$MAINREPO/app/src/main/Leftover.kt"
DIRTY_OUT=$("$MAINREPO/scripts/hooks/session-end-check.sh" 2>&1)
if printf '%s' "$DIRTY_OUT" | grep -q "1 file(s) dirty"; then
    PASS=$((PASS+1)); echo "  ok    one untracked file is reported"
else
    FAIL=$((FAIL+1)); echo "  FAIL  one untracked file reported: ${DIRTY_OUT:-nothing}"
fi
rm -f "$MAINREPO/app/src/main/Leftover.kt"
echo

echo "session-end-check.sh — against this checkout"
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
