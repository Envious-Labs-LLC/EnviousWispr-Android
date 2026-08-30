#!/usr/bin/env bash
# test-hooks.sh — every guard, and every other check that decides something, in BOTH directions.
# A guard never observed failing is a comment.
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
DIG_DIR=""; NOHOOKS=""
# Only the exact paths THIS run reserved. A `scripts/.digest-control-*` glob would take a concurrent
# session's directory with it.
trap 'rm -rf "$MAINREPO" "$MAINREPO.git"; rm -f "$STDERR" "$EDITPLAN" "$SENTINEL" /tmp/.ew-android-issue-9901-pending-plan.md; [ -n "$DIG_DIR" ] && rm -rf "$DIG_DIR"; [ -n "$NOHOOKS" ] && rm -rf "$NOHOOKS"' EXIT

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

echo "command-safety.py — the shell writes, which no git hook can see"
assert "an ordinary read command"              allow command-safety.py '{"tool_input":{"command":"git status --short"}}'
assert_main "an ordinary read command"         allow command-safety.py '{"tool_input":{"command":"git status --short"}}'
assert_main "heredoc into a ship path on main" deny  command-safety.py '{"tool_input":{"command":"cat > app/src/main/Foo.kt <<EOF"}}'
assert_main "append into a ship path on main"  deny  command-safety.py '{"tool_input":{"command":"echo x >> app/src/main/Foo.kt"}}'
assert_main "the >| redirection on main"       deny  command-safety.py '{"tool_input":{"command":"echo x >| app/src/main/Foo.kt"}}'
assert_main "tee into a ship path on main"     deny  command-safety.py '{"tool_input":{"command":"printf x | tee app/src/main/Foo.kt"}}'
assert_main "sed -i into a ship path on main"  deny  command-safety.py '{"tool_input":{"command":"sed -i \"\" s/a/b/ app/src/main/AndroidManifest.xml"}}'
assert_main "sed -i.bak into a ship path"      deny  command-safety.py '{"tool_input":{"command":"sed -i.bak s/a/b/ app/src/main/Foo.kt"}}'
assert_main "a bare & starts a new command"    deny  command-safety.py '{"tool_input":{"command":"ls & echo x > app/src/main/Foo.kt"}}'
assert_main "env wrapping a real tee"          deny  command-safety.py '{"tool_input":{"command":"env FOO=1 tee app/src/main/Foo.kt"}}'
assert_main "write to a local-only path"       allow command-safety.py '{"tool_input":{"command":"cat > docs/internal/x.md <<EOF"}}'
# The false-positive half, which decides whether a guard survives contact with ordinary work.
assert_main "a quoted redirect is not a write" allow command-safety.py '{"tool_input":{"command":"printf %s \"see > app/src/main/Foo.kt\""}}'
assert_main "a later pipeline stage"           allow command-safety.py '{"tool_input":{"command":"printf x | tee /tmp/log | cat app/src/main/Foo.kt"}}'
assert_main "printing the word tee"            allow command-safety.py '{"tool_input":{"command":"printf %s tee app/src/main/Foo.kt"}}'
assert_main "a newline inside quotes"          allow command-safety.py '{"tool_input":{"command":"printf \"a\nb\" app/src/main/Foo.kt"}}'
# `--no-verify` is denied on EVERY branch, because skipping a hook is never the fix for what it says.
assert "--no-verify on a branch"               deny  command-safety.py '{"tool_input":{"command":"git commit --no-verify -m x"}}'
assert_main "--no-verify on main"              deny  command-safety.py '{"tool_input":{"command":"git commit --no-verify -m x"}}'
assert "an ordinary commit on a branch"        allow command-safety.py '{"tool_input":{"command":"git commit -m x"}}'
echo

# THE COMMIT CHECK IS A REAL GIT HOOK NOW, so it is exercised by RUNNING COMMITS rather than by feeding
# command strings to a parser. Every form below was a separate defect while this was a parser. Here they
# are one code path, because git computes the staged set before it calls the hook.
echo "githooks/pre-commit — arming"
# WITHOUT THIS ASSERTION THE WHOLE SUITE CAN PASS IN A CLONE WHERE NO COMMIT IS PROTECTED, because every
# control below arms its own throwaway repository. The hook files are tracked; git does not arm them.
ARMED=$(git config --local --get core.hooksPath 2>/dev/null || true)
if [ "$ARMED" = "scripts/githooks" ]; then
    PASS=$((PASS+1)); echo "  ok    this checkout has the commit hook armed"
else
    FAIL=$((FAIL+1)); echo "  FAIL  this checkout is NOT armed. Run: git config core.hooksPath scripts/githooks"
fi
# One setting arms BOTH hooks. An earlier design needed a second, forcing a merge commit so a
# fast-forward could not slip past; the ref hook sees the fast-forward itself, so that is gone.
echo

echo "githooks — every way \`main\` can move, by actually moving it"
HOOKREPO=$(mktemp -d) || exit 2
mkdir -p "$HOOKREPO/app/src/main" "$HOOKREPO/docs" "$HOOKREPO/scripts/hooks" "$HOOKREPO/scripts/githooks" || exit 2
git init -q -b main "$HOOKREPO" || exit 2
cp "$HOOKS/ship_paths.py" "$HOOKREPO/scripts/hooks/" || exit 2
cp scripts/githooks/pre-commit scripts/githooks/reference-transaction "$HOOKREPO/scripts/githooks/" || exit 2
gitc() { git -C "$HOOKREPO" -c user.email=t@t -c user.name=t "$@"; }
# FIXTURE MANAGEMENT, NOT A SEAM IN THE GUARD. Putting `main` back where a control found it is itself a
# forward ref move onto ship-path content, so the hook refuses it and the suite cannot clean up after
# itself. This restores with hooks pointed at an empty directory. The shipped hook is untouched: only
# this harness's own housekeeping bypasses it, and every assertion above runs against the armed repo.
NOHOOKS=$(mktemp -d) || exit 2
gitraw() { git -C "$HOOKREPO" -c user.email=t@t -c user.name=t -c core.hooksPath="$NOHOOKS" "$@"; }
restore_main() {
    gitraw rebase --abort >/dev/null 2>&1; gitraw merge --abort >/dev/null 2>&1
    gitraw am --abort >/dev/null 2>&1
    gitraw checkout -q -f main >/dev/null 2>&1 || return 1
    gitraw update-ref refs/heads/main "$FF_BEFORE" >/dev/null 2>&1 || return 1
    gitraw reset -q --hard "$FF_BEFORE" >/dev/null 2>&1 || return 1
}
# THE SHIP FIXTURE IS COMMITTED FIRST, and this is the difference between a control that proves the hook
# refused and one that passes for the wrong reason. While `Foo.kt` was untracked, `git commit -a` had
# nothing to include and failed because git found NOTHING TO COMMIT — which reads as `deny` and says
# nothing at all about the hook.
printf 'base\n' > "$HOOKREPO/app/src/main/Foo.kt" || exit 2
: > "$HOOKREPO/docs/ok.md" || exit 2
gitc add -A >/dev/null 2>&1 || exit 2
gitc commit -q -m base >/dev/null 2>&1 || exit 2
git -C "$HOOKREPO" config core.hooksPath scripts/githooks || exit 2

commit_case() {  # commit_case <name> <allow|deny> <git args...>
    local name="$1" want="$2"; shift 2
    printf 'changed %s\n' "$RANDOM" > "$HOOKREPO/app/src/main/Foo.kt"
    gitc reset -q >/dev/null 2>&1
    local got="deny"
    if gitc "$@" >/dev/null 2>&1; then got="allow"; fi
    gitc reset -q --hard HEAD >/dev/null 2>&1
    if [ "$got" = "$want" ]; then PASS=$((PASS+1)); printf '  ok    %-58s %s\n' "$name" "$got"
    else FAIL=$((FAIL+1)); printf '  FAIL  %-58s wanted %s, got %s\n' "$name" "$want" "$got"; fi
}
# THE CONTROL ON THE CONTROLS, and it is permanent because a probe run once by hand is not a control.
# While the ship fixture was untracked, `git commit -a` failed for having NOTHING TO COMMIT and four
# controls read as `deny` while proving nothing about the hook. The pair below is what makes that
# impossible to repeat: the same commit must SUCCEED with the hook unarmed and FAIL once it is armed.
printf 'unarmed %s\n' "$RANDOM" > "$HOOKREPO/app/src/main/Foo.kt" || exit 2
git -C "$HOOKREPO" config --unset core.hooksPath || exit 2
if gitc commit -q -am unarmed >/dev/null 2>&1; then
    PASS=$((PASS+1)); printf '  ok    %-58s %s\n' "the same commit, hook deliberately unarmed" "allow"
else
    FAIL=$((FAIL+1)); printf '  FAIL  %-58s it never reached a real commit\n' "the same commit, hook unarmed"
fi
gitc reset -q --hard HEAD >/dev/null 2>&1
git -C "$HOOKREPO" config core.hooksPath scripts/githooks || exit 2

commit_case "git commit -am"                   deny commit -am wip
commit_case "git commit -a -m, separated"      deny commit -a -m wip
commit_case "an explicit -- pathspec"          deny commit -m wip -- app/src/main/Foo.kt
commit_case "a bare positional pathspec"       deny commit -m wip app/src/main/Foo.kt
# A DELETION is a ship-path commit. `--diff-filter=ACMRT` omitted `D`, so removing a ship path from
# `main` was allowed while changing one was refused.
gitc rm -q app/src/main/Foo.kt >/dev/null 2>&1 || exit 2
if gitc commit -q -m del >/dev/null 2>&1; then
    FAIL=$((FAIL+1)); printf '  FAIL  %-58s wanted deny, got allow\n' "deleting a ship path"
else PASS=$((PASS+1)); printf '  ok    %-58s %s\n' "deleting a ship path" "deny"; fi
gitc reset -q --hard HEAD >/dev/null 2>&1
# A RENAME reported only by its destination would hide the ship path it came from.
gitc mv app/src/main/Foo.kt docs/moved.md >/dev/null 2>&1 || exit 2
if gitc commit -q -m mv >/dev/null 2>&1; then
    FAIL=$((FAIL+1)); printf '  FAIL  %-58s wanted deny, got allow\n' "renaming a ship path into docs"
else PASS=$((PASS+1)); printf '  ok    %-58s %s\n' "renaming a ship path into docs" "deny"; fi
gitc reset -q --hard HEAD >/dev/null 2>&1
# `--amend` needs its own setup: commit_case unstages first, and amending an EMPTY index rewrites only a
# message, which touches no file and is correctly allowed.
printf 'amended\n' > "$HOOKREPO/app/src/main/Foo.kt" || exit 2
gitc add app/src/main/Foo.kt >/dev/null 2>&1 || exit 2
if gitc commit -q --amend -m x >/dev/null 2>&1; then
    FAIL=$((FAIL+1)); printf '  FAIL  %-58s wanted deny, got allow\n' "git commit --amend, ship path staged"
else PASS=$((PASS+1)); printf '  ok    %-58s %s\n' "git commit --amend, ship path staged" "deny"; fi
gitc reset -q --hard HEAD >/dev/null 2>&1
if gitc commit -q --amend -m "reworded" >/dev/null 2>&1; then
    PASS=$((PASS+1)); printf '  ok    %-58s %s\n' "git commit --amend, message only" "allow"
else FAIL=$((FAIL+1)); printf '  FAIL  %-58s wanted allow, got deny\n' "git commit --amend, message only"; fi
# A MERGE COMMIT raises a different event from `pre-commit`, so this went unexamined until the ref hook
# existed. A delegating `pre-merge-commit` was written for it and later deleted: `reference-transaction`
# sees the ref move whatever event produced it.
gitc checkout -q -b side >/dev/null 2>&1 || exit 2
printf 'on the side\n' > "$HOOKREPO/app/src/main/Foo.kt" || exit 2
gitc commit -q -am side >/dev/null 2>&1 || exit 2
gitc checkout -q main >/dev/null 2>&1 || exit 2
: > "$HOOKREPO/docs/diverge.md" || exit 2
gitc add -A >/dev/null 2>&1 || exit 2
gitc commit -q -m diverge >/dev/null 2>&1 || exit 2
if gitc merge --no-ff -m merge side >/dev/null 2>&1; then
    FAIL=$((FAIL+1)); printf '  FAIL  %-58s wanted deny, got allow\n' "a merge carrying a ship path"
else PASS=$((PASS+1)); printf '  ok    %-58s %s\n' "a merge carrying a ship path" "deny"; fi
gitc merge --abort >/dev/null 2>&1; gitc reset -q --hard HEAD >/dev/null 2>&1
# The allow half for a merge. Two deny controls and no proof an ordinary merge goes through is the half
# that decides whether a guard survives contact.
gitc checkout -q -b docmerge >/dev/null 2>&1 || exit 2
: > "$HOOKREPO/docs/merged.md" || exit 2
gitc add -A >/dev/null 2>&1 || exit 2; gitc commit -q -m "docs on a branch" >/dev/null 2>&1 || exit 2
gitc checkout -q main >/dev/null 2>&1 || exit 2
if gitc merge -m merge docmerge >/dev/null 2>&1; then
    PASS=$((PASS+1)); printf '  ok    %-58s %s\n' "a merge carrying only docs" "allow"
else FAIL=$((FAIL+1)); printf '  FAIL  %-58s wanted allow, got deny\n' "a merge carrying only docs"; fi
gitc merge --abort >/dev/null 2>&1; gitc reset -q --hard HEAD >/dev/null 2>&1 || exit 2

# EVERY FORM BELOW CREATES NO COMMIT, so no commit hook of any kind sees it. Each one moved `main` onto
# ship-path work until `reference-transaction` was added, and each was found in a separate review round
# while the design was still adding commit-event hooks.
# TWO BRANCHES WITH DIFFERENT RELATIONSHIPS TO MAIN, and the ORDER matters. `ffside` must be a
# DESCENDANT so a fast-forward is actually possible; `divergent` must share only an older ancestor. An
# earlier version built ffside first and then advanced main, which left ffside un-fast-forwardable — so
# the fast-forward control passed as `deny` because git refused the merge, not because the hook did. The
# causal pair below is what caught that, which is the whole reason it exists.
ANCESTOR=$(gitc rev-parse main) || exit 2
: > "$HOOKREPO/docs/onmain.md" || exit 2
gitc add -A >/dev/null 2>&1 || exit 2
gitc commit -q -m onmain >/dev/null 2>&1 || exit 2
FF_BEFORE=$(gitc rev-parse main) || exit 2

gitc checkout -q -b ffside >/dev/null 2>&1 || exit 2
printf 'fast forward\n' > "$HOOKREPO/app/src/main/Foo.kt" || exit 2
gitc commit -q -am ff >/dev/null 2>&1 || exit 2

gitc checkout -q -b divergent "$ANCESTOR" >/dev/null 2>&1 || exit 2
printf 'divergent\n' > "$HOOKREPO/app/src/main/Foo.kt" || exit 2
gitc commit -q -am divergent >/dev/null 2>&1 || exit 2
gitc checkout -q main >/dev/null 2>&1 || exit 2
# Prove the two relationships are what the controls below assume, rather than assuming them.
gitc merge-base --is-ancestor "$FF_BEFORE" ffside || exit 2
gitc merge-base --is-ancestor "$FF_BEFORE" divergent && exit 2

refmove_case() {  # refmove_case <name> <git args...>
    local name="$1"; shift
    # The baseline must be true BEFORE the case, or a stale ref makes "it did not move" meaningless.
    [ "$(gitc rev-parse main)" = "$FF_BEFORE" ] || exit 2
    local got="deny"
    gitc "$@" >/dev/null 2>&1
    [ "$(gitc rev-parse main)" = "$FF_BEFORE" ] || got="allow"
    restore_main || exit 2
    if [ "$got" = "deny" ]; then PASS=$((PASS+1)); printf '  ok    %-58s %s\n' "$name" "deny"
    else FAIL=$((FAIL+1)); printf '  FAIL  %-58s wanted deny, got allow\n' "$name"; fi
}
refmove_case "a fast-forward merge"            merge --ff-only ffside
refmove_case "git reset --hard onto a descendant" reset --hard ffside
refmove_case "git checkout -B main"            checkout -B main ffside
refmove_case "git update-ref refs/heads/main"  update-ref refs/heads/main ffside
# The three DIVERGENT shapes, which the reversed ancestry test used to wave through.
refmove_case "git rebase onto a ship branch"   rebase divergent
refmove_case "git reset --hard onto a divergent branch" reset --hard divergent
refmove_case "git checkout -B main, divergent" checkout -B main divergent
# A move BACKWARD is how a mistake is undone and must stay allowed.
ROOT_COMMIT=$(gitc rev-list --max-parents=0 main | tail -1) || exit 2
if gitc reset --hard "$ROOT_COMMIT" >/dev/null 2>&1 && [ "$(gitc rev-parse main)" = "$ROOT_COMMIT" ]; then
    PASS=$((PASS+1)); printf '  ok    %-58s %s\n' "resetting main backwards" "allow"
else FAIL=$((FAIL+1)); printf '  FAIL  %-58s wanted allow, got deny\n' "resetting main backwards"; fi
restore_main || exit 2
# THE CAUSAL PAIR for the ref hook, the same shape the commit hook already has. Without it every deny
# above could be a command that simply failed.
mv "$HOOKREPO/scripts/githooks/reference-transaction" "$HOOKREPO/rt.hidden" || exit 2
gitc merge --ff-only ffside >/dev/null 2>&1
if [ "$(gitc rev-parse main)" != "$FF_BEFORE" ]; then
    PASS=$((PASS+1)); printf '  ok    %-58s %s\n' "the same fast-forward with the ref hook removed" "allow"
else FAIL=$((FAIL+1)); printf '  FAIL  %-58s it never reached a real ref move\n' "the same fast-forward, hook removed"; fi
mv "$HOOKREPO/rt.hidden" "$HOOKREPO/scripts/githooks/reference-transaction" || exit 2
restore_main || exit 2

# `git am` raises a third event again, and a `pre-applypatch` hook written for it was also deleted once
# the ref hook existed. Installing a hook is never evidence it runs, so this builds a real patch on a
# branch and applies it to `main`.
gitc checkout -q -b patchside >/dev/null 2>&1 || exit 2
printf 'via a patch\n' > "$HOOKREPO/app/src/main/Foo.kt" || exit 2
gitc commit -q -am "patch" >/dev/null 2>&1 || exit 2
PATCHDIR="$HOOKREPO/.patches"
gitc format-patch -1 -o "$PATCHDIR" >/dev/null 2>&1 || exit 2
gitc checkout -q main >/dev/null 2>&1 || exit 2
if gitc am "$PATCHDIR"/*.patch >/dev/null 2>&1; then
    FAIL=$((FAIL+1)); printf '  FAIL  %-58s wanted deny, got allow\n' "git am applying a ship path"
else PASS=$((PASS+1)); printf '  ok    %-58s %s\n' "git am applying a ship path" "deny"; fi
gitc am --abort >/dev/null 2>&1; gitc reset -q --hard HEAD >/dev/null 2>&1 || exit 2
# The allow half for the same hook: a docs-only patch must apply.
gitc checkout -q -b docpatch >/dev/null 2>&1 || exit 2
printf 'doc change\n' > "$HOOKREPO/docs/patched.md" || exit 2
gitc add -A >/dev/null 2>&1 || exit 2; gitc commit -q -m "doc patch" >/dev/null 2>&1 || exit 2
gitc format-patch -1 -o "$HOOKREPO/.docpatches" >/dev/null 2>&1 || exit 2
gitc checkout -q main >/dev/null 2>&1 || exit 2
if gitc am "$HOOKREPO/.docpatches"/*.patch >/dev/null 2>&1; then
    PASS=$((PASS+1)); printf '  ok    %-58s %s\n' "git am applying a docs-only patch" "allow"
else FAIL=$((FAIL+1)); printf '  FAIL  %-58s wanted allow, got deny\n' "git am applying a docs-only patch"; fi
gitc am --abort >/dev/null 2>&1; gitc reset -q --hard HEAD >/dev/null 2>&1 || exit 2
# The direction that matters more: a commit touching nothing shipped must go straight through.
: > "$HOOKREPO/docs/two.md" || exit 2
gitc add -A >/dev/null 2>&1 || exit 2
if gitc commit -q -m docs >/dev/null 2>&1; then
    PASS=$((PASS+1)); printf '  ok    %-58s %s\n' "a docs-only commit on main" "allow"
else FAIL=$((FAIL+1)); printf '  FAIL  %-58s wanted allow, got deny\n' "a docs-only commit on main"; fi
# AND IT FAILS CLOSED. Unlike the PreToolUse guards, a broken check here must not answer "yes": it is the
# only thing between a ship path and `main`, and it runs once, on one commit.
mv "$HOOKREPO/scripts/hooks/ship_paths.py" "$HOOKREPO/ship_paths.hidden" || exit 2
printf 'broken classifier\n' > "$HOOKREPO/app/src/main/Foo.kt" || exit 2
gitc add -A >/dev/null 2>&1 || exit 2
if gitc commit -q -m x >/dev/null 2>&1; then
    FAIL=$((FAIL+1)); printf '  FAIL  %-58s wanted deny, got allow\n' "a broken classifier does not approve"
else PASS=$((PASS+1)); printf '  ok    %-58s %s\n' "a broken classifier does not approve" "deny"; fi
mv "$HOOKREPO/ship_paths.hidden" "$HOOKREPO/scripts/hooks/ship_paths.py" || exit 2
rm -rf "$HOOKREPO"
echo

echo "githooks/reference-transaction — must not break clone, fetch or pull"
REMOTE_W=$(mktemp -d) || exit 2
git init -q --bare -b main "$REMOTE_W/origin.git" || exit 2
mkdir -p "$REMOTE_W/a/app/src/main" "$REMOTE_W/a/docs" "$REMOTE_W/a/scripts/hooks" "$REMOTE_W/a/scripts/githooks" || exit 2
git init -q -b main "$REMOTE_W/a" || exit 2
cp "$HOOKS/ship_paths.py" "$REMOTE_W/a/scripts/hooks/" || exit 2
cp scripts/githooks/pre-commit scripts/githooks/reference-transaction "$REMOTE_W/a/scripts/githooks/" || exit 2
ga() { git -C "$REMOTE_W/a" -c user.email=t@t -c user.name=t "$@"; }
printf 'shipped\n' > "$REMOTE_W/a/app/src/main/Foo.kt" || exit 2
: > "$REMOTE_W/a/docs/ok.md" || exit 2
ga add -A >/dev/null 2>&1 || exit 2
ga commit -q -m "base carrying ship paths" >/dev/null 2>&1 || exit 2
ga remote add origin "$REMOTE_W/origin.git" || exit 2
ga push -q -u origin main >/dev/null 2>&1 || exit 2

refallow() {  # refallow <name> <dir> <ref-to-watch> <git args...>
    local name="$1" dir="$2" watch="$3"; shift 3
    local before after
    before=$(git -C "$dir" rev-parse --verify --quiet "$watch" || echo none)
    git -C "$dir" -c user.email=t@t -c user.name=t "$@" >/dev/null 2>&1
    after=$(git -C "$dir" rev-parse --verify --quiet "$watch" || echo none)
    # Exit status alone proves nothing: a command that did nothing also exits 0.
    if [ "$before" != "$after" ]; then
        PASS=$((PASS+1)); printf '  ok    %-58s %s\n' "$name" "allow"
    else
        FAIL=$((FAIL+1)); printf '  FAIL  %-58s the ref did not move\n' "$name"
    fi
}
# A SMOKE TEST, not a hook control: an ordinary clone is not armed, because the setting is local and does
# not exist until after the clone. It is here because a clone that broke would be the loudest failure.
if git clone -q "$REMOTE_W/origin.git" "$REMOTE_W/b" >/dev/null 2>&1; then
    PASS=$((PASS+1)); printf '  ok    %-58s %s\n' "cloning the repository at all (smoke test)" "ok"
else
    FAIL=$((FAIL+1)); printf '  FAIL  %-58s the clone itself failed\n' "cloning the repository"
fi
git -C "$REMOTE_W/b" config core.hooksPath scripts/githooks || exit 2
printf 'shipped again\n' > "$REMOTE_W/a/app/src/main/Foo.kt" || exit 2
ga commit -q -am "upstream ship change" >/dev/null 2>&1 || exit 2
ga push -q origin main >/dev/null 2>&1 || exit 2
refallow "fetching upstream ship-path work"    "$REMOTE_W/b" refs/remotes/origin/main fetch
refallow "pulling it into main"                "$REMOTE_W/b" refs/heads/main pull --ff-only
# The remote does not have to be called `origin`. A first version tested `refs/remotes/origin/main` by
# name and refused a legitimate pull in any checkout whose remote is named anything else.
git clone -q --origin upstream "$REMOTE_W/origin.git" "$REMOTE_W/c" >/dev/null 2>&1 || exit 2
git -C "$REMOTE_W/c" config core.hooksPath scripts/githooks || exit 2
printf 'third upstream change\n' > "$REMOTE_W/a/app/src/main/Foo.kt" || exit 2
ga commit -q -am "third" >/dev/null 2>&1 || exit 2
ga push -q origin main >/dev/null 2>&1 || exit 2
refallow "pulling from a remote NOT called origin" "$REMOTE_W/c" refs/heads/main pull --ff-only
# `git pull --rebase` REPLAYS local commits on top of the upstream, so the upstream sits INSIDE the new
# history and `NEW` is not reachable from it. Judging from the old `main` would count the upstream's own
# ship-path changes as local and refuse an ordinary pull. Both directions, because the fix has to keep
# refusing the local half.
printf 'fourth upstream ship change\n' > "$REMOTE_W/a/app/src/main/Foo.kt" || exit 2
ga commit -q -am fourth >/dev/null 2>&1 || exit 2
ga push -q origin main >/dev/null 2>&1 || exit 2
: > "$REMOTE_W/b/docs/local-notes.md" || exit 2
git -C "$REMOTE_W/b" add -A >/dev/null 2>&1 || exit 2
git -C "$REMOTE_W/b" -c user.email=t@t -c user.name=t commit -q -m "local docs" >/dev/null 2>&1 || exit 2
refallow "pull --rebase, upstream ship plus local docs" "$REMOTE_W/b" refs/heads/main pull --rebase
# The same shape with LOCAL ship work must still be refused. It is committed with hooks pointed at an
# empty directory, because pre-commit would stop it earlier and this control is about the REF hook.
NOHOOKS_B=$(mktemp -d) || exit 2
printf 'fifth upstream\n' > "$REMOTE_W/a/app/src/main/Foo.kt" || exit 2
ga commit -q -am fifth >/dev/null 2>&1 || exit 2
ga push -q origin main >/dev/null 2>&1 || exit 2
printf 'local ship work\n' > "$REMOTE_W/b/app/src/main/Bar.kt" || exit 2
git -C "$REMOTE_W/b" -c core.hooksPath="$NOHOOKS_B" add -A >/dev/null 2>&1 || exit 2
git -C "$REMOTE_W/b" -c core.hooksPath="$NOHOOKS_B" -c user.email=t@t -c user.name=t \
    commit -q -m "local ship" >/dev/null 2>&1 || exit 2
RB_BEFORE=$(git -C "$REMOTE_W/b" rev-parse main) || exit 2
git -C "$REMOTE_W/b" -c user.email=t@t -c user.name=t pull --rebase >/dev/null 2>&1
if [ "$(git -C "$REMOTE_W/b" rev-parse main)" = "$RB_BEFORE" ]; then
    PASS=$((PASS+1)); printf '  ok    %-58s %s\n' "pull --rebase, upstream ship plus local SHIP" "deny"
else
    FAIL=$((FAIL+1)); printf '  FAIL  %-58s wanted deny, got allow\n' "pull --rebase, upstream ship plus local SHIP"
fi
git -C "$REMOTE_W/b" rebase --abort >/dev/null 2>&1
rm -rf "$NOHOOKS_B"
# A DIVERGENT history that changes a ship path, reverts it, and ends at a tree identical to main's. Tree
# equality alone would wave this through as if it were an amend; the exception requires the PARENTS to
# match too, which pins it to a rewrite of exactly one commit.
git -C "$REMOTE_W/c" checkout -q -b divergent-revert HEAD~1 >/dev/null 2>&1 || \
    git -C "$REMOTE_W/c" checkout -q -b divergent-revert >/dev/null 2>&1 || exit 2
DR_ORIG=$(cat "$REMOTE_W/c/app/src/main/Foo.kt" 2>/dev/null || echo x)
printf 'divergent change\n' > "$REMOTE_W/c/app/src/main/Foo.kt" || exit 2
git -C "$REMOTE_W/c" -c core.hooksPath=/nonexistent -c user.email=t@t -c user.name=t \
    commit -q -am "divergent change" >/dev/null 2>&1 || exit 2
git -C "$REMOTE_W/c" checkout -q main -- . >/dev/null 2>&1 || true
git -C "$REMOTE_W/c" -c core.hooksPath=/nonexistent -c user.email=t@t -c user.name=t \
    commit -q -am "back to main's content" >/dev/null 2>&1 || true
git -C "$REMOTE_W/c" checkout -q main >/dev/null 2>&1 || exit 2
DV_BEFORE=$(git -C "$REMOTE_W/c" rev-parse main) || exit 2
git -C "$REMOTE_W/c" reset --hard divergent-revert >/dev/null 2>&1
if [ "$(git -C "$REMOTE_W/c" rev-parse main)" = "$DV_BEFORE" ]; then
    PASS=$((PASS+1)); printf '  ok    %-58s %s\n' "a divergent history ending at main's own tree" "deny"
else
    FAIL=$((FAIL+1)); printf '  FAIL  %-58s wanted deny, got allow\n' "a divergent history ending at main's own tree"
    git -C "$REMOTE_W/c" reset -q --hard "$DV_BEFORE" >/dev/null 2>&1
fi
git -C "$REMOTE_W/c" checkout -q -f main >/dev/null 2>&1 || exit 2
git -C "$REMOTE_W/c" reset -q --hard "$DV_BEFORE" >/dev/null 2>&1 || exit 2

# A MERGE COMMIT's own conflict resolution. `git log --name-only` shows nothing for a merge without `-m`,
# so a resolution that edits a ship path only in the merge commit was invisible even though both sides
# touched nothing but docs.
git -C "$REMOTE_W/c" checkout -q -b sideA >/dev/null 2>&1 || exit 2
printf 'A\n' > "$REMOTE_W/c/docs/conflict.md" || exit 2
git -C "$REMOTE_W/c" add -A >/dev/null 2>&1 || exit 2
git -C "$REMOTE_W/c" -c user.email=t@t -c user.name=t commit -q -m sideA >/dev/null 2>&1 || exit 2
git -C "$REMOTE_W/c" checkout -q main >/dev/null 2>&1 || exit 2
printf 'B\n' > "$REMOTE_W/c/docs/conflict.md" || exit 2
git -C "$REMOTE_W/c" add -A >/dev/null 2>&1 || exit 2
git -C "$REMOTE_W/c" -c user.email=t@t -c user.name=t commit -q -m sideB >/dev/null 2>&1 || exit 2
MG_BEFORE=$(git -C "$REMOTE_W/c" rev-parse main) || exit 2
# The merge is built ON A BRANCH with hooks off, then main is fast-forwarded onto it. Committing the
# merge on main directly would be answered by `pre-commit`, whose staged set does contain the resolution
# — and this control is about whether the REF hook can SEE a merge commit's own changes, which
# `git log --name-only` does not show without `-m`.
git -C "$REMOTE_W/c" checkout -q -b mergeside >/dev/null 2>&1 || exit 2
git -C "$REMOTE_W/c" -c core.hooksPath=/nonexistent merge --no-commit sideA >/dev/null 2>&1
printf 'resolved\n' > "$REMOTE_W/c/docs/conflict.md" || exit 2
printf 'sneaked in during the resolution\n' > "$REMOTE_W/c/app/src/main/Resolved.kt" || exit 2
git -C "$REMOTE_W/c" -c core.hooksPath=/nonexistent add -A >/dev/null 2>&1 || exit 2
git -C "$REMOTE_W/c" -c core.hooksPath=/nonexistent -c user.email=t@t -c user.name=t \
    commit -q -m "merge with a ship path in the resolution" >/dev/null 2>&1 || exit 2
git -C "$REMOTE_W/c" checkout -q main >/dev/null 2>&1 || exit 2
git -C "$REMOTE_W/c" merge --ff-only mergeside >/dev/null 2>&1
if [ "$(git -C "$REMOTE_W/c" rev-parse main)" = "$MG_BEFORE" ]; then
    PASS=$((PASS+1)); printf '  ok    %-58s %s\n' "a ship path added only in a merge resolution" "deny"
else
    FAIL=$((FAIL+1)); printf '  FAIL  %-58s wanted deny, got allow\n' "a ship path added only in a merge resolution"
fi
git -C "$REMOTE_W/c" merge --abort >/dev/null 2>&1
git -C "$REMOTE_W/c" -c core.hooksPath=/nonexistent reset -q --hard "$MG_BEFORE" >/dev/null 2>&1 || exit 2

# A branch that changes a ship path and then REVERTS it has no endpoint difference at all, so a hook
# diffing two endpoints let both of those commits into main's history.
git -C "$REMOTE_W/c" checkout -q -b revertside >/dev/null 2>&1 || exit 2
ORIG=$(cat "$REMOTE_W/c/app/src/main/Foo.kt") || exit 2
printf 'temporarily changed\n' > "$REMOTE_W/c/app/src/main/Foo.kt" || exit 2
git -C "$REMOTE_W/c" -c core.hooksPath=/nonexistent -c user.email=t@t -c user.name=t commit -q -am change >/dev/null 2>&1 || exit 2
printf '%s\n' "$ORIG" > "$REMOTE_W/c/app/src/main/Foo.kt" || exit 2
git -C "$REMOTE_W/c" -c core.hooksPath=/nonexistent -c user.email=t@t -c user.name=t commit -q -am revert >/dev/null 2>&1 || exit 2
git -C "$REMOTE_W/c" checkout -q main >/dev/null 2>&1 || exit 2
RV_BEFORE=$(git -C "$REMOTE_W/c" rev-parse main) || exit 2
git -C "$REMOTE_W/c" merge --ff-only revertside >/dev/null 2>&1
if [ "$(git -C "$REMOTE_W/c" rev-parse main)" = "$RV_BEFORE" ]; then
    PASS=$((PASS+1)); printf '  ok    %-58s %s\n' "a branch that changes a ship path and reverts it" "deny"
else
    FAIL=$((FAIL+1)); printf '  FAIL  %-58s wanted deny, got allow\n' "a branch that changes a ship path and reverts it"
fi
# And a `main` deleted and recreated at local ship work is judged, not waved through as a clone.
git -C "$REMOTE_W/c" checkout -q -b recreate >/dev/null 2>&1 || exit 2
printf 'recreated local\n' > "$REMOTE_W/c/app/src/main/Baz.kt" || exit 2
git -C "$REMOTE_W/c" add -A >/dev/null 2>&1 || exit 2
git -C "$REMOTE_W/c" -c user.email=t@t -c user.name=t commit -q -m recreate >/dev/null 2>&1 || exit 2
git -C "$REMOTE_W/c" branch -q -D main >/dev/null 2>&1 || exit 2
if git -C "$REMOTE_W/c" branch main recreate >/dev/null 2>&1; then
    FAIL=$((FAIL+1)); printf '  FAIL  %-58s wanted deny, got allow\n' "recreating a deleted main at local ship work"
    git -C "$REMOTE_W/c" branch -q -D main >/dev/null 2>&1
else
    PASS=$((PASS+1)); printf '  ok    %-58s %s\n' "recreating a deleted main at local ship work" "deny"
fi
# The allow half: recreating it at its own configured upstream. `for-each-ref` cannot answer once the
# branch is gone, so the upstream has to come from `branch.main.remote` and `branch.main.merge`.
UPSTREAM_TIP=$(git -C "$REMOTE_W/c" rev-parse refs/remotes/upstream/main) || exit 2
if git -C "$REMOTE_W/c" branch main "$UPSTREAM_TIP" >/dev/null 2>&1; then
    PASS=$((PASS+1)); printf '  ok    %-58s %s\n' "recreating a deleted main at its upstream" "allow"
else
    FAIL=$((FAIL+1)); printf '  FAIL  %-58s wanted allow, got deny\n' "recreating a deleted main at its upstream"
fi
# And the discriminator still holds: LOCAL ship-path work cannot ride the same route.
git -C "$REMOTE_W/b" checkout -q -b localwork >/dev/null 2>&1 || exit 2
printf 'local only\n' > "$REMOTE_W/b/app/src/main/Bar.kt" || exit 2
git -C "$REMOTE_W/b" add -A >/dev/null 2>&1 || exit 2
git -C "$REMOTE_W/b" -c user.email=t@t -c user.name=t commit -q -m local >/dev/null 2>&1 || exit 2
git -C "$REMOTE_W/b" checkout -q main >/dev/null 2>&1 || exit 2
if git -C "$REMOTE_W/b" merge --ff-only localwork >/dev/null 2>&1; then
    FAIL=$((FAIL+1)); printf '  FAIL  %-58s wanted deny, got allow\n' "local work still cannot fast-forward main"
else
    PASS=$((PASS+1)); printf '  ok    %-58s %s\n' "local work still cannot fast-forward main" "deny"
fi
rm -rf "$REMOTE_W"
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
echo "change-digest.sh — the fingerprint a validation receipt is pinned to"
# Every digest invocation is checked. Comparing two EMPTY strings reports "same", so a script that had
# stopped working entirely would pass the two controls that assert sameness.
# It RETURNS NON-ZERO on failure rather than printing anything. An earlier version printed a unique
# marker so two failures could not compare equal — but a unique marker also DIFFERS from a valid digest,
# so every control expecting "differs" passed when its second invocation failed. Both directions have to
# be unsatisfiable by a broken script, and the only value that manages that is no value at all.
digest() {
    local out
    out=$(scripts/change-digest.sh 2>/dev/null) || return 2
    [ -n "$out" ] || return 2
    printf '%s' "$out"
}
check() {  # check <name> <same|differs> <before> <after>
    local got="differs"; [ "$3" = "$4" ] && got="same"
    if [ "$got" = "$2" ]; then PASS=$((PASS+1)); printf '  ok    %-58s %s\n' "$1" "$got"
    else FAIL=$((FAIL+1)); printf '  FAIL  %-58s wanted %s, got %s\n' "$1" "$2" "$got"; fi
}
# A FIXED path in the real working tree would overwrite and then delete a real file of that name, and
# every mutation below is checked, because a `git add` that silently failed would make the control that
# matters most — "staging does not move it" — pass without staging anything.
# A DIRECTORY reserved by mktemp, with the probe inside it. `mktemp -u` reserved nothing, so another
# process could take the name first; creating the file instead put an empty probe INTO the baseline, and
# then "removing it returns to the baseline" compared against a tree that already contained it. A
# reserved directory that is empty at baseline avoids both.
DIG_DIR=$(mktemp -d "scripts/.digest-control-XXXXXX") || exit 2
DIG_PROBE="$DIG_DIR/probe"
BASE_DIGEST=$(digest) || exit 2
THIS=$(digest) || exit 2
check "the same tree twice"                    same    "$BASE_DIGEST" "$THIS"
printf 'probe\n' > "$DIG_PROBE" || exit 2
UNTRACKED_DIGEST=$(digest) || exit 2
check "one new untracked file"                 differs "$BASE_DIGEST" "$UNTRACKED_DIGEST"
git add "$DIG_PROBE" >/dev/null 2>&1 || exit 2
# THE ONE THAT MATTERS. `git add` is the next step of the normal Phase 3 route to a commit, so a digest
# that moved here would fail correct runs and be disabled rather than fixed.
THIS=$(digest) || exit 2
check "staging that file does not move it"     same    "$UNTRACKED_DIGEST" "$THIS"
chmod +x "$DIG_PROBE" || exit 2
THIS=$(digest) || exit 2
check "the executable bit moves it"            differs "$UNTRACKED_DIGEST" "$THIS"
# Staging a DELETION was the other half of the same defect, so it gets the same control.
git rm -q --cached -f "$DIG_PROBE" >/dev/null 2>&1 || exit 2
rm -f "$DIG_PROBE" || exit 2
THIS=$(digest) || exit 2
check "removing it returns to the baseline"    same    "$BASE_DIGEST" "$THIS"
# The real object database must not grow. `git add -A` writes a blob per file and `write-tree` writes the
# trees; sent to the real store they would be unreachable garbage after every validation run.
# The content must be UNIQUE and must exist only between the two counts. Measuring after the probe was
# removed counted a tree whose blobs the object database already had, so a digest writing into the real
# store would have shown no growth at all.
LEAK_PROBE="$DIG_DIR/object-leak-$RANDOM-$RANDOM"
printf 'unique %s %s\n' "$RANDOM" "$RANDOM" > "$LEAK_PROBE" || exit 2
OBJ_BEFORE=$(find .git/objects -type f | wc -l | tr -d " ")
digest >/dev/null || exit 2
OBJ_AFTER=$(find .git/objects -type f | wc -l | tr -d " ")
rm -f "$LEAK_PROBE" || exit 2
if [ "$OBJ_BEFORE" = "$OBJ_AFTER" ]; then
    PASS=$((PASS+1)); echo "  ok    hashing new content leaves no loose objects behind"
else
    FAIL=$((FAIL+1)); echo "  FAIL  hashing new content left $((OBJ_AFTER - OBJ_BEFORE)) loose object(s)"
fi
# A repository with NO COMMITS has no HEAD to read, which is the enumeration failing. The point of the
# control is that a failure produces no digest at all: a well-formed hash of an unknown subset would be
# the most confident-looking wrong answer this script could give.
DIG_REPO=$(mktemp -d) || exit 2
git init -q -b main "$DIG_REPO" || exit 2
mkdir -p "$DIG_REPO/scripts" || exit 2
cp scripts/change-digest.sh "$DIG_REPO/scripts/" || exit 2
DIG_OUT=$("$DIG_REPO/scripts/change-digest.sh" 2>/dev/null); DIG_RC=$?
if [ "$DIG_RC" -eq 2 ] && [ -z "$DIG_OUT" ]; then
    PASS=$((PASS+1)); echo "  ok    a failed enumeration exits 2 and prints nothing"
else
    FAIL=$((FAIL+1)); echo "  FAIL  a failed enumeration gave exit $DIG_RC and '${DIG_OUT}'"
fi
rm -rf "$DIG_REPO"
echo

echo "$PASS passed, $FAIL failed"
[ "$FAIL" -eq 0 ]
