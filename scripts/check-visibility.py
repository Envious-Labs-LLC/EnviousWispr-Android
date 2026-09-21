#!/usr/bin/env python3
"""Two source-shape rules over app/src/main (#191, REF-08), a drift guard for the shape the sweep left.

Rule A. A TOP-LEVEL declaration (brace depth zero) with Kotlin's public default fails unless
        scripts/visibility-allowlist.txt names it as `<path>:<Name>`, path relative to the repository. The
        shipped allowlist is the components Android constructs by class name (the manifest's and the
        worker); everything else app-only is `internal` or `private`. Depth is read by a scanner over the
        Kotlin lexical grammar, never by indentation: code, line comment, nesting block comment, string
        with escapes and `${ }` templates, raw string with templates and the quote-run rule, character
        literal. A public member of a class is not this rule's population: `internal` on the class makes
        its members unreachable outside the module, and the compiler's "exposes internal type" error
        covers the members of the public classes.

Rule B. A `when (subject) {` block whose every non-else arm names a member of ONE app enum or sealed type
        (a bare member, `Type.MEMBER`, `is Type.Member`, or the literal `null`) is closed, and an
        `else ->` arm in it fails: the compiler already refuses a non-exhaustive `when` over such a type,
        so the `else` only hides the next member. A block with a range, a guard, a literal, a type test on
        a foreign type, or a Boolean subject is open to this check and passes; the compiler, not this
        check, is the authority once the `else` is gone. Enum members and sealed children are read from
        the same tree by regex, at any nesting depth.

    scripts/check-visibility.py                 # the repository's app/src/main/java
    scripts/check-visibility.py --root <dir>    # another tree with the same layout (the test fixtures)

Exit 0 when clean, 1 with one `file:line: <rule> <what>` per hit, 2 on a usage or I/O error. Never
greps outside the scoped root. Wired as the Code lane's `visibility` obligation by scripts/validate-pr.sh
and required by scripts/check-validation.sh; pinned in both directions by VisibilityCheckTest.
"""
import os
import re
import sys

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ALLOWLIST = os.path.join(REPO, "scripts", "visibility-allowlist.txt")
SOURCE = os.path.join("app", "src", "main", "java")

MODIFIERS = {
    "abstract", "open", "sealed", "data", "enum", "annotation", "value", "inline", "suspend", "operator",
    "infix", "expect", "actual", "final", "const", "lateinit", "tailrec", "external", "inner", "fun",
}
KEYWORDS = {"class", "object", "interface", "fun", "val", "var", "typealias"}
VISIBILITY = {"private", "internal", "protected", "public"}

# ---------------------------------------------------------------------------------------------------
# The scanner: which characters are CODE, so braces and declarations are read only there.
# ---------------------------------------------------------------------------------------------------

def code_mask(text):
    """For each character, True when it is code (not a comment, string or character literal).

    Kotlin lexical states, the closed list the plan names: code, line comment, block comment (nesting),
    string ("…" with escapes and `${ }` templates), raw string (\"\"\"…\"\"\" with templates, ending at the
    last three quotes of a run), character literal ('x' or one escape). A `${` inside either string pushes
    a nested CODE state that ends at its matching `}`, so a lambda or a nested string inside a template is
    read by the same rules.
    """
    n = len(text)
    mask = [False] * n
    # a stack of states: ("code", brace_depth_at_entry) | "line" | ("block", depth) | "str" | "raw" | "chr"
    stack = [("code", 0)]
    depth = 0  # brace depth inside the CURRENT code state
    i = 0
    while i < n:
        state = stack[-1]
        c = text[i]
        kind = state if isinstance(state, str) else state[0]
        if kind == "code":
            if text.startswith("//", i):
                stack.append("line"); i += 2; continue
            if text.startswith("/*", i):
                stack.append(("block", 1)); i += 2; continue
            if text.startswith('"""', i):
                stack.append("raw"); i += 3; continue
            if c == '"':
                stack.append("str"); i += 1; continue
            if c == "'":
                stack.append("chr"); i += 1; continue
            if c == "}" and len(stack) > 1 and depth == state[1]:
                # the `}` that closes a `${` template: not code, back to the enclosing string
                stack.pop()
                i += 1
                continue
            mask[i] = True
            if c == "{":
                depth += 1
            elif c == "}":
                depth -= 1
            i += 1
            continue
        if kind == "line":
            if c == "\n":
                stack.pop()
            i += 1
            continue
        if kind == "block":
            if text.startswith("/*", i):
                stack[-1] = ("block", state[1] + 1); i += 2; continue
            if text.startswith("*/", i):
                if state[1] == 1:
                    stack.pop()
                else:
                    stack[-1] = ("block", state[1] - 1)
                i += 2
                continue
            i += 1
            continue
        if kind == "str":
            if c == "\\":
                i += 6 if text.startswith("\\u", i) else 2
                continue
            if text.startswith("${", i):
                stack.append(("code", depth)); i += 2; continue
            if c == '"':
                stack.pop()
            i += 1
            continue
        if kind == "raw":
            if text.startswith("${", i):
                stack.append(("code", depth)); i += 2; continue
            if text.startswith('"""', i):
                j = i
                while j < n and text[j] == '"':
                    j += 1
                # a run of quotes ends the literal at its last three
                stack.pop()
                i = j
                continue
            i += 1
            continue
        if kind == "chr":
            if c == "\\":
                i += 6 if text.startswith("\\u", i) else 2
                continue
            if c == "'":
                stack.pop()
            i += 1
            continue
        raise AssertionError(kind)
    return mask


def code_only(text):
    """The text with every non-code character replaced by a space, newlines kept, so line numbers hold."""
    mask = code_mask(text)
    return "".join(ch if (mask[i] or ch == "\n") else " " for i, ch in enumerate(text))


def depth_at_line_starts(code):
    """Nesting depth at the start of each line of the code-only text (index 0 = line 1): braces plus
    parentheses, so a `val` inside a primary constructor's parameter list is not top-level either."""
    depths = []
    depth = 0
    for line in code.split("\n"):
        depths.append(depth)
        depth += line.count("{") - line.count("}") + line.count("(") - line.count(")")
    return depths


# ---------------------------------------------------------------------------------------------------
# Rule A
# ---------------------------------------------------------------------------------------------------

DECL_LINE = re.compile(r"^\s*(?P<words>(?:[A-Za-z_]\w*\s+)*?)(?P<kw>class|object|interface|fun|val|var|typealias)\b(?P<rest>.*)$")
NAME_AFTER = re.compile(r"^\s*(?:<[^>]*>\s*)?(?:[\w.]+\.)?(?P<name>`[^`]+`|[A-Za-z_]\w*)")


def top_level_public(code, allowlisted_names):
    """Rule A hits: (line, name) for top-level declarations with the public default."""
    lines = code.split("\n")
    depths = depth_at_line_starts(code)
    hits = []
    for idx, line in enumerate(lines):
        if depths[idx] != 0:
            continue
        m = DECL_LINE.match(line)
        if not m:
            continue
        words = m.group("words").split()
        if any(w in VISIBILITY for w in words):
            continue
        if any(w not in MODIFIERS and not w.startswith("@") for w in words):
            continue  # not a declaration line (e.g. `return fun ...` cannot occur at depth 0 anyway)
        # the name: on this line, or the first token of the next non-blank line (coverage D2)
        rest = m.group("rest")
        nm = NAME_AFTER.match(rest)
        if nm is None:
            look = idx + 1
            while look < len(lines) and not lines[look].strip():
                look += 1
            nm = NAME_AFTER.match(lines[look]) if look < len(lines) else None
        if nm is None:
            continue
        name = nm.group("name").strip("`")
        if name in allowlisted_names:
            continue
        hits.append((idx + 1, m.group("kw"), name))
    return hits


# ---------------------------------------------------------------------------------------------------
# Rule B
# ---------------------------------------------------------------------------------------------------

ENUM_DECL = re.compile(r"\benum\s+class\s+(?P<name>[A-Za-z_]\w*)[^{]*\{", re.S)
SEALED_DECL = re.compile(r"\bsealed\s+(?:class|interface)\s+(?P<name>[A-Za-z_]\w*)")
SEALED_CHILD = re.compile(r"\b(?:data\s+)?(?:object|class)\s+(?P<child>[A-Za-z_]\w*)\s*(?:\([^()]*(?:\([^()]*\)[^()]*)*\))?\s*:\s*(?:[\w.]+\.)?(?P<parent>[A-Za-z_]\w*)\b")
ENUM_MEMBER = re.compile(r"^\s*(?:@\w+(?:\([^)]*\))?\s*)*(?P<member>[A-Z][A-Z0-9_]*)\s*(?:\(|,|;|$)")


def closed_sets(code_by_file):
    """{TypeName: {members}} for every enum class and sealed type declared in the tree."""
    sets = {}
    for code in code_by_file.values():
        for m in ENUM_DECL.finditer(code):
            body_start = m.end()
            # the enum entries run to the first `;` or the closing brace at the same depth
            depth = 1
            j = body_start
            while j < len(code):
                if code[j] == "{":
                    depth += 1
                elif code[j] == "}":
                    depth -= 1
                    if depth == 0:
                        break  # j stays ON the closing brace, so the slice excludes it
                elif code[j] == ";" and depth == 1:
                    break
                j += 1
            members = set()
            for raw in code[body_start:j].replace(",", "\n").split("\n"):
                em = ENUM_MEMBER.match(raw)
                if em:
                    members.add(em.group("member"))
            if members:
                sets.setdefault(m.group("name"), set()).update(members)
        sealed = {m.group("name") for m in SEALED_DECL.finditer(code)}
        if sealed:
            for cm in SEALED_CHILD.finditer(code):
                if cm.group("parent") in sealed:
                    sets.setdefault(cm.group("parent"), set()).add(cm.group("child"))
    return sets


ARM = re.compile(r"^\s*(?P<left>.+?)\s*->")
MEMBER_REF = re.compile(r"^(?:is\s+)?(?:[\w.]+\.)?(?P<type>[A-Za-z_]\w*)\.(?P<member>[A-Za-z_]\w*)$|^(?P<bare>[A-Za-z_]\w*)$")


def arm_members(left, sets):
    """The (type, member) each comma-separated piece of an arm's left side names, or None if any piece is
    not a member reference of a known closed set (or `null`)."""
    found = []
    for piece in [p.strip() for p in left.split(",")]:
        if piece == "null":
            continue
        m = MEMBER_REF.match(piece)
        if not m:
            return None
        if m.group("bare"):
            owners = [t for t, ms in sets.items() if m.group("bare") in ms]
            if len(owners) != 1:
                return None
            found.append((owners[0], m.group("bare")))
        else:
            t, member = m.group("type"), m.group("member")
            if t not in sets or member not in sets[t]:
                return None
            found.append((t, member))
    return found


def else_over_closed_set(code, sets):
    """Rule B hits: (line, type) for an `else ->` in a when whose other arms all name one closed set.

    Works on the code-only TEXT, not on lines: the subject may hold nested parentheses
    (`when (val verdict = adapter.keyCheckVerdict(a, b))`), and a whole `when` may sit on one line with
    `;`-separated arms. The block is the span from its `{` to the matching `}`; its arms are the depth-1
    segments split at newlines and semicolons.
    """
    hits = []
    for m in re.finditer(r"\bwhen\s*\(", code):
        # the subject: balanced parentheses from the `(`
        i = m.end() - 1
        depth = 0
        j = i
        while j < len(code):
            if code[j] == "(":
                depth += 1
            elif code[j] == ")":
                depth -= 1
                if depth == 0:
                    break
            j += 1
        k = j + 1
        while k < len(code) and code[k] in " \t\r\n":
            k += 1
        if k >= len(code) or code[k] != "{":
            continue
        # the block: from `{` to its matching `}`
        depth = 0
        b = k
        while b < len(code):
            if code[b] == "{":
                depth += 1
            elif code[b] == "}":
                depth -= 1
                if depth == 0:
                    break
            b += 1
        body = code[k + 1:b]
        # depth-1 segments, split at newlines and semicolons
        segments = []
        depth = 0
        seg_start = 0
        for idx, ch in enumerate(body):
            if ch in "{(":
                depth += 1
            elif ch in "})":
                depth -= 1
            elif ch in "\n;" and depth == 0:
                segments.append((seg_start, body[seg_start:idx]))
                seg_start = idx + 1
        segments.append((seg_start, body[seg_start:]))
        arms = []
        for off, seg in segments:
            am = ARM.match(seg)
            if am:
                line = code.count("\n", 0, k + 1 + off + am.start("left")) + 1
                arms.append((line, am.group("left").strip()))
        else_lines = [ln for ln, left in arms if left == "else"]
        if not else_lines:
            continue
        others = [left for ln, left in arms if left != "else"]
        if not others:
            continue
        types = set()
        closed = True
        for left in others:
            refs = arm_members(left, sets)
            if refs is None:
                closed = False
                break
            types.update(t for t, _ in refs)
        if closed and len(types) == 1:
            for ln in else_lines:
                hits.append((ln, next(iter(types))))
    return hits


# ---------------------------------------------------------------------------------------------------

def load_allowlist(path):
    entries = {}
    if not os.path.exists(path):
        return entries
    for raw in open(path, encoding="utf-8"):
        line = raw.split("#", 1)[0].strip()
        if not line:
            continue
        if ":" not in line:
            print(f"{path}: an allowlist entry is `<path>:<Name>`, got {line!r}", file=sys.stderr)
            sys.exit(2)
        rel, name = line.rsplit(":", 1)
        entries.setdefault(rel.strip(), set()).add(name.strip())
    return entries


def main(argv):
    root = REPO
    if "--root" in argv:
        i = argv.index("--root")
        if i + 1 >= len(argv):
            print("usage: check-visibility.py [--root <dir>]", file=sys.stderr)
            return 2
        root = os.path.abspath(argv[i + 1])
    source = os.path.join(root, SOURCE)
    if not os.path.isdir(source):
        print(f"no source root at {source}", file=sys.stderr)
        return 2
    allow = load_allowlist(ALLOWLIST if root == REPO else os.path.join(root, "scripts", "visibility-allowlist.txt"))
    code_by_file = {}
    for dirpath, _, names in os.walk(source):
        for name in sorted(names):
            if name.endswith(".kt"):
                path = os.path.join(dirpath, name)
                with open(path, encoding="utf-8") as fh:
                    code_by_file[path] = code_only(fh.read())
    sets = closed_sets(code_by_file)
    hits = []
    for path in sorted(code_by_file):
        rel = os.path.relpath(path, root)
        code = code_by_file[path]
        for line, kw, name in top_level_public(code, allow.get(rel, set())):
            hits.append(f"{rel}:{line}: public-default top-level {kw} {name} (make it internal or private, or allowlist `{rel}:{name}` with a reason)")
        for line, type_name in else_over_closed_set(code, sets):
            hits.append(f"{rel}:{line}: else over the closed set {type_name} (name its remaining members; the compiler then owns exhaustiveness)")
    if hits:
        for h in hits:
            print(h)
        print(f"{len(hits)} visibility hit(s) in {len(code_by_file)} files under {SOURCE}")
        return 1
    print(f"clean: {len(code_by_file)} files under {SOURCE}, {len(sets)} closed sets known")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
