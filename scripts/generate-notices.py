#!/usr/bin/env python3
"""Regenerate the repository-root THIRD-PARTY-NOTICES.txt.

Why this is a script and not a hand-written file: a notices file is a set of legal claims about
software we redistribute, and a hand-maintained one goes stale silently. Issue #15 was filed because
the shipped asset named an S1-mini revision the app had stopped using and called llama.cpp Apache-2.0
when llama.cpp's own LICENSE says MIT. Nothing linked either claim to the thing it described.

Every licence name this writes comes from the publisher's own declaration:

  * a Maven dependency's licence comes from its POM, which is the file the publisher uploaded beside
    the artifact. The Gradle cache is consulted first; a dependency Gradle fetched without its POM is
    downloaded from the same repository Gradle would have used, and cached under CACHE_DIR;
  * a component with no Maven coordinate (the local sherpa-onnx AAR, the llama.cpp submodule) is
    declared in BUNDLED below, naming the file or URL its licence was read from, with the version
    MEASURED from the artifact we actually ship.

It FAILS CLOSED. A dependency whose POM cannot be obtained, or whose POM declares no licence, aborts
the run and is named. A notices file that says "unknown" about something we ship is worse than none.

Usage, from the repository root:

    ./gradlew -I scripts/notices-deps.init.gradle :app:printNoticesDependencies \
        --console=plain > /tmp/deps.txt
    python3 scripts/generate-notices.py /tmp/deps.txt

Run it after any dependency change. `ThirdPartyNoticesTest` goes red when a dependency declared in
`app/build.gradle.kts` is missing from the generated file, which is the reminder to re-run it.
"""

from __future__ import annotations

import re
import subprocess
import sys
import urllib.error
import urllib.request
import xml.etree.ElementTree as ET
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
GRADLE_CACHE = Path.home() / ".gradle" / "caches" / "modules-2" / "files-2.1"
CACHE_DIR = REPO / "scripts" / ".notices-pom-cache"
TEXTS = REPO / "scripts" / "license-texts"
OUTPUT = REPO / "THIRD-PARTY-NOTICES.txt"

# POMs come in two shapes: most declare the Maven namespace on <project>, and older hand-written ones
# (javax.inject:1 among our dependencies) declare none. A namespaced XPath silently returns nothing for
# the second shape, which reads exactly like "this publisher declared no licence". Matching on the LOCAL
# tag name reads both. Caught by this script's own fail-closed check on the first run.
def _local(element: ET.Element) -> str:
    return element.tag.rsplit("}", 1)[-1]


def _children(parent: ET.Element, name: str) -> list[ET.Element]:
    return [child for child in parent if _local(child) == name]


def _text(parent: ET.Element, name: str) -> str:
    for child in _children(parent, name):
        return (child.text or "").strip()
    return ""

# The repositories `settings.gradle.kts` declares, in the order Gradle consults them. A POM is only
# ever fetched from a repository this project already trusts for the artifact itself.
REPOSITORIES = [
    "https://dl.google.com/dl/android/maven2",
    "https://repo1.maven.org/maven2",
]

# One line of `scripts/notices-deps.init.gradle`'s output. Gradle resolves the classpath and prints
# each component; nothing here interprets Gradle's printed dependency TREE, which is a grammar we do
# not own. Reading that tree dropped 277 lines whose version came from a bill of materials and left
# two Compose libraries out of the licence file entirely.
COORD = re.compile(r"^NOTICES-DEP ([^\s:]+):([^\s:]+):(\S+)$")

# Components that ship inside the APK with no Maven coordinate to read a licence from. Each names the
# evidence behind its claim, so a reader can re-check it without trusting this file.
BUNDLED = [
    {
        "name": "sherpa-onnx",
        "version": "1.12.29",
        "license": "Apache-2.0",
        "source": "https://github.com/k2-fsa/sherpa-onnx",
        "note": "Runs the speech model. Bundled as the local app/libs/sherpa-onnx.aar, not resolved from Maven.",
        "evidence": (
            "Version measured with `strings` on jni/arm64-v8a/libsherpa-onnx-jni.so inside that AAR. "
            "Licence read from https://github.com/k2-fsa/sherpa-onnx/blob/v1.12.29/LICENSE, the tag "
            "matching that version. The AAR carries no licence file of its own."
        ),
    },
    {
        "name": "ONNX Runtime",
        "version": "1.17.1",
        "license": "MIT License",
        "source": "https://github.com/microsoft/onnxruntime",
        "note": "Redistributed inside the sherpa-onnx AAR. Not a declared dependency of this project.",
        "evidence": (
            "Version measured with `strings` on jni/arm64-v8a/libonnxruntime.so inside "
            "app/libs/sherpa-onnx.aar. Licence read from "
            "https://github.com/microsoft/onnxruntime/blob/v1.17.1/LICENSE."
        ),
    },
    {
        "name": "llama.cpp",
        "version": "(read from the submodule at generation time)",
        "license": "MIT License",
        "source": "https://github.com/ggml-org/llama.cpp",
        "note": "Built from source as the :llama-android module. Runs the local polish model.",
        "evidence": (
            "Commit read from `git submodule status third_party/llama.cpp`. Licence read from "
            "third_party/llama.cpp/LICENSE in this checkout."
        ),
    },
]

# The full licence texts reproduced in Part 4, each a verbatim copy of a file in this repository.
# Keyed by heading; the value is the file and the components the text discharges the notice for.
#
# One canonical Apache-2.0 copy serves every Apache component, which is what that licence asks for: a
# copy of the licence, not a per-component copyright line. MIT and BSD-3-Clause DO carry a copyright
# line, so each of those components gets its own text rather than a template with placeholders in it.
FULL_TEXTS = [
    (
        "Apache License 2.0",
        "Apache-2.0.txt",
        "Every component above marked Apache-2.0, including sherpa-onnx and the AndroidX, Kotlin and "
        "Compose libraries.",
    ),
    ("MIT License - llama.cpp", "MIT-llama.cpp.txt", "llama.cpp."),
    ("MIT License - ONNX Runtime", "MIT-onnxruntime.txt", "ONNX Runtime."),
    (
        "BSD 3-Clause License - Qualcomm GenieX",
        "BSD-3-Clause-geniex.txt",
        "com.qualcomm.qti:geniex-android. Also subject to the Qualcomm Terms of Use named in Part 3.",
    ),
    (
        "BSD 3-Clause License - Protocol Buffers",
        "BSD-3-Clause-protobuf.txt",
        "androidx.datastore:datastore-preferences-external-protobuf.",
    ),
]

# MIT and BSD-3-Clause each require the component's OWN copyright line, so one shared text cannot
# discharge them the way a single Apache-2.0 copy discharges every Apache component. This maps each
# such Maven component to the file reproducing its notice; a new MIT or BSD dependency with no entry
# here aborts the run rather than shipping under a neighbour's copyright.
COMPONENT_TEXTS = {
    "com.qualcomm.qti:geniex-android": "BSD-3-Clause-geniex.txt",
    "androidx.datastore:datastore-preferences-external-protobuf": "BSD-3-Clause-protobuf.txt",
}
NEEDS_OWN_TEXT = {"MIT", "BSD-3-Clause"}

# Publishers spell one licence several ways. Normalising keeps the listing readable and, more
# importantly, keeps an unrecognised spelling VISIBLE instead of collapsing it into a neighbour.
ALIASES = {
    "the apache software license, version 2.0": "Apache-2.0",
    "the apache license, version 2.0": "Apache-2.0",
    "apache license, version 2.0": "Apache-2.0",
    "apache license 2.0": "Apache-2.0",
    "apache 2.0": "Apache-2.0",
    "apache-2.0": "Apache-2.0",
    "mit license": "MIT",
    "the mit license": "MIT",
    "mit": "MIT",
    "bsd 3-clause license": "BSD-3-Clause",
    "the bsd 3-clause license": "BSD-3-Clause",
    "bsd-3-clause": "BSD-3-Clause",
}

# Terms published only at a URL. They are named and linked, never copied: a copy of terms that can
# change on the publisher's website would become a second, drifting version of them.
URL_ONLY = {
    "Android Software Development Kit License": "https://developer.android.com/studio/terms",
    "ML Kit Terms of Service": "https://developers.google.com/ml-kit/terms",
    "Qualcomm Terms of Use": "https://www.qualcomm.com/site/terms-of-use",
}

RULE = "-" * 80


def fail(message: str) -> None:
    print(f"generate-notices: {message}", file=sys.stderr)
    sys.exit(1)


def normalise(name: str) -> str:
    return ALIASES.get(name.strip().lower(), name.strip())


def resolved_coordinates(text: str) -> dict[tuple[str, str], str]:
    """Every component Gradle resolved onto the release runtime classpath.

    Gradle reports each component once, already resolved, so there is no conflict to adjudicate and
    no version to pick. A group:artifact reported twice would mean the file was built from two runs;
    that is refused rather than silently keeping the last one.
    """
    resolved: dict[tuple[str, str], str] = {}
    for line in text.splitlines():
        match = COORD.match(line.strip())
        if not match:
            continue
        group, artifact, version = match.groups()
        previous = resolved.get((group, artifact))
        if previous is not None and previous != version:
            fail(
                f"{group}:{artifact} appears at both {previous} and {version}; the input mixes "
                "two Gradle runs and no single version can be published"
            )
        resolved[(group, artifact)] = version
    return resolved


def pom_path(group: str, artifact: str, version: str) -> Path | None:
    """The POM for a coordinate, from the Gradle cache, our own cache, or the publisher."""
    directory = GRADLE_CACHE / group / artifact / version
    if directory.is_dir():
        for candidate in directory.glob(f"*/{artifact}-{version}.pom"):
            return candidate

    # Gradle keeps only what it needed. A dependency resolved through Gradle Module Metadata often
    # has no POM on disk at all, so it is fetched from the same repository Gradle would have used.
    local = CACHE_DIR / group / artifact / f"{artifact}-{version}.pom"
    if local.is_file():
        return local

    relative = f"{group.replace('.', '/')}/{artifact}/{version}/{artifact}-{version}.pom"
    for repository in REPOSITORIES:
        url = f"{repository}/{relative}"
        try:
            with urllib.request.urlopen(url, timeout=30) as response:
                body = response.read()
        except (urllib.error.URLError, urllib.error.HTTPError, TimeoutError):
            continue
        local.parent.mkdir(parents=True, exist_ok=True)
        local.write_bytes(body)
        return local
    return None


def licences_from_pom(pom: Path, depth: int = 0) -> list[str]:
    """The licence names a POM declares, following at most one parent.

    Some publishers declare licences only on a parent POM. An unbounded walk would eventually
    attribute some ancestor's licence to an artifact that never claimed it, so the walk is bounded
    and a miss fails closed rather than guessing.
    """
    root = ET.parse(pom).getroot()
    blocks = _children(root, "licenses")

    # A <licenses> element is the publisher SPEAKING. Dropping a licence inside it because it has no
    # readable name would publish a subset of what the publisher declared, and falling through to a
    # parent would publish somebody else's terms entirely. Both are refusals, not filters.
    if blocks:
        names: list[str] = []
        for block in blocks:
            entries = _children(block, "license")
            if not entries:
                fail(f"{pom} has an empty <licenses> element, so its declaration cannot be read")
            for licence in entries:
                name = _text(licence, "name")
                if not name or "${" in name:
                    fail(
                        f"{pom} declares a licence with no literal name, so what the publisher "
                        "granted cannot be stated here"
                    )
                names.append(name)
        return names

    if depth >= 1:
        return []
    parents = _children(root, "parent")
    if not parents:
        return []
    parent = parents[0]
    group = _text(parent, "groupId")
    artifact = _text(parent, "artifactId")
    version = _text(parent, "version")
    if not (group and artifact and version):
        return []
    parent_pom = pom_path(group, artifact, version)
    return licences_from_pom(parent_pom, depth + 1) if parent_pom else []


def submodule_commit() -> str:
    output = subprocess.run(
        ["git", "submodule", "status", "third_party/llama.cpp"],
        cwd=REPO,
        capture_output=True,
        text=True,
        check=False,
    ).stdout
    match = re.search(r"([0-9a-f]{40})", output)
    if not match:
        fail("could not read the llama.cpp commit from `git submodule status third_party/llama.cpp`")
    return match.group(1)


def bundled_block(item: dict[str, str]) -> list[str]:
    return [
        RULE,
        item["name"],
        f"  Version: {item['version']}",
        f"  License: {item['license']}",
        f"  Source:  {item['source']}",
        f"  Note:    {item['note']}",
        f"  Read from: {item['evidence']}",
        RULE,
        "",
    ]


def main() -> None:
    if len(sys.argv) != 2:
        fail("usage: generate-notices.py <gradle-dependencies-output>")
    input_path = Path(sys.argv[1])
    if not input_path.is_file():
        fail(f"{input_path} does not exist; run the ./gradlew command in this script's docstring")

    for _, filename, _ in FULL_TEXTS:
        if not (TEXTS / filename).is_file():
            fail(f"scripts/license-texts/{filename} is missing; the full text must be in the repository")

    resolved = resolved_coordinates(input_path.read_text())
    if not resolved:
        fail(f"no NOTICES-DEP lines in {input_path}; the Gradle run probably failed")

    entries: list[tuple[str, str]] = []
    unobtainable: list[str] = []
    undeclared: list[str] = []
    unrecognised: dict[str, list[str]] = {}

    for (group, artifact), version in sorted(resolved.items()):
        coordinate = f"{group}:{artifact}:{version}"
        pom = pom_path(group, artifact, version)
        if pom is None:
            unobtainable.append(coordinate)
            continue
        names = licences_from_pom(pom)
        if not names:
            undeclared.append(coordinate)
            continue
        normalised = [normalise(name) for name in names]
        for name in normalised:
            if name in URL_ONLY:
                unrecognised.setdefault(name, []).append(coordinate)
        entries.append((coordinate, " AND ".join(normalised)))

    if unobtainable:
        fail(
            "could not obtain a POM for these dependencies, so their licence cannot be stated:\n  "
            + "\n  ".join(unobtainable)
        )
    if undeclared:
        fail(
            "these POMs declare no licence, so nothing may be claimed for them here:\n  "
            + "\n  ".join(undeclared)
        )

    named = {normalise(name) for _, licence in entries for name in licence.split(" AND ")}
    unmapped = named - {"Apache-2.0", "MIT", "BSD-3-Clause"} - set(URL_ONLY)
    if unmapped:
        fail(
            "these licence names have neither a reproduced text nor a URL-only entry, so the file "
            "would name terms it does not discharge:\n  " + "\n  ".join(sorted(unmapped))
        )

    uncovered = []
    for coordinate, licence in entries:
        if not NEEDS_OWN_TEXT.intersection(licence.split(" AND ")):
            continue
        module = coordinate.rsplit(":", 1)[0]
        filename = COMPONENT_TEXTS.get(module)
        if filename is None or not (TEXTS / filename).is_file():
            uncovered.append(f"{coordinate} ({licence})")
    if uncovered:
        fail(
            "MIT and BSD-3-Clause require each component's own copyright line, and these have no "
            "text in scripts/license-texts. Add the file, add it to COMPONENT_TEXTS and to "
            "FULL_TEXTS:\n  " + "\n  ".join(uncovered)
        )

    bundled = [dict(item) for item in BUNDLED]
    for item in bundled:
        if item["name"] == "llama.cpp":
            item["version"] = submodule_commit()

    out: list[str] = [
        "THIRD-PARTY NOTICES - EnviousWispr Android",
        "=" * 42,
        "",
        "EnviousWispr Android is licensed under the GNU GPL version 3; see LICENSE.",
        "It redistributes the components below, each under its own terms.",
        "",
        "GENERATED FILE. Do not edit by hand. Regenerate with:",
        "",
        "    ./gradlew -I scripts/notices-deps.init.gradle :app:printNoticesDependencies \\",
        "        --console=plain > /tmp/deps.txt",
        "    python3 scripts/generate-notices.py /tmp/deps.txt",
        "",
        "Licence names for Maven dependencies are read from each publisher's own POM. Components",
        "with no Maven coordinate are listed first and name the file or URL their licence was read",
        "from. The app separately ships app/src/main/assets/THIRD_PARTY_NOTICES.txt, which covers",
        "the speech and polish MODELS, the bundled font, the voice activity detector and the",
        "provider icons, and is the file a user can read from inside the app.",
        "",
        "",
        "PART 1 - COMPONENTS BUNDLED WITHOUT A MAVEN COORDINATE",
        "=" * 54,
        "",
    ]
    for item in bundled:
        out.extend(bundled_block(item))

    out.extend(
        [
            "",
            "PART 2 - MAVEN DEPENDENCIES ON THE RELEASE RUNTIME CLASSPATH",
            "=" * 59,
            "",
            f"{len(entries)} components, each with the licence its own POM declares.",
            "",
        ]
    )
    width = max(len(coordinate) for coordinate, _ in entries)
    out.extend(f"  {coordinate.ljust(width)}  {licence}" for coordinate, licence in entries)
    out.append("")

    if unrecognised:
        out.extend(
            [
                "",
                "PART 3 - TERMS PUBLISHED ONLY AT A URL",
                "=" * 38,
                "",
                "These publishers ship under terms hosted on their own site. The terms are named and",
                "linked rather than copied, because a copy of terms that can change would become a",
                "second version of them.",
                "",
            ]
        )
        for name in sorted(unrecognised):
            out.append(f"  {name}")
            out.append(f"      {URL_ONLY[name]}")
            out.extend(f"      {coordinate}" for coordinate in unrecognised[name])
            out.append("")

    out.extend(["", "PART 4 - FULL LICENCE TEXTS", "=" * 27, ""])
    for heading, filename, covers in FULL_TEXTS:
        out.extend(["=" * 80, heading, f"Covers: {covers}", "=" * 80, ""])
        out.append((TEXTS / filename).read_text().rstrip())
        out.extend(["", ""])

    OUTPUT.write_text("\n".join(out).rstrip() + "\n")
    print(f"wrote {OUTPUT.relative_to(REPO)}: {len(bundled)} bundled, {len(entries)} Maven components")
    if unrecognised:
        print("named but not reproduced: " + ", ".join(sorted(unrecognised)))


if __name__ == "__main__":
    main()
