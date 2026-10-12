#!/usr/bin/env python3
"""Validating extractor for build-all.sh's archive (the `build` job's packaged classpath output,
consumed by the `sweep` and `publish` jobs).

Both `build-all.sh` (packer) and this script agree on a fixed, relative archive layout — no
member is ever an absolute path, and this script re-validates that independently before writing
anything to disk (same two-layer approach as node/extract_bundle.py for the recheck bundle):

    wt/<relative path under the extensions-source checkout>
    gradle/<relative path under $GRADLE_USER_HOME/caches>
    MANIFEST.json   {"wt": "<original absolute WT path>", "gradle_caches": "<original absolute
                      GRADLE_USER_HOME/caches path>"} — the prefixes build-all.sh stripped, needed
                      here to rewrite classpath.txt (which contains absolute paths baked in by
                      Gradle at pack time) onto THIS job's absolute paths, since this job's
                      $GITHUB_WORKSPACE/$HOME almost certainly differ from the job that packed it.

This is the fix for the `tar --absolute-names -C /` finding: the archive itself can never name an
absolute path (tar never even sees one — build-all.sh stages into a relative tree before taring),
and every member is independently re-checked here (no leading "/", no ".." component, must
resolve under a FIXED extraction root this script picks) before extraction, on top of Python's
own tarfile "data" filter.

    extract_build.py <archive.tar.zst> <wt-dest> <gradle-caches-dest>
"""
import json
import os
import subprocess
import sys
import tarfile
import tempfile


def is_safe_member(name: str, prefix: str) -> bool:
    if not name.startswith(prefix):
        return False
    rest = name[len(prefix):]
    if rest.startswith("/") or rest.startswith("\\"):
        return False
    if ".." in rest.replace("\\", "/").split("/"):
        return False
    return True


def main():
    if len(sys.argv) != 4:
        sys.exit("usage: extract_build.py <archive.tar.zst> <wt-dest> <gradle-caches-dest>")
    archive, wt_dest, gradle_dest = sys.argv[1:4]
    wt_dest = os.path.realpath(wt_dest)
    gradle_dest = os.path.realpath(gradle_dest)
    os.makedirs(wt_dest, exist_ok=True)
    os.makedirs(gradle_dest, exist_ok=True)

    with tempfile.TemporaryDirectory() as tmp:
        decompressed = os.path.join(tmp, "build.tar")
        with open(decompressed, "wb") as out:
            subprocess.run(["zstd", "-d", "-T0", "-c", archive], check=True, stdout=out)
        with tarfile.open(decompressed) as tf:
            members = tf.getmembers()
            manifest_member = next((m for m in members if m.name == "MANIFEST.json"), None)
            if manifest_member is None:
                sys.exit("archive has no MANIFEST.json; refusing to extract")
            manifest = json.loads(tf.extractfile(manifest_member).read().decode())
            old_wt = manifest.get("wt")
            old_gradle = manifest.get("gradle_caches")
            if not old_wt or not old_gradle:
                sys.exit("MANIFEST.json is missing wt/gradle_caches")

            wt_members, gradle_members = [], []
            for m in members:
                if m.name in ("MANIFEST.json", "wt", "gradle"):
                    continue  # MANIFEST.json handled above; "wt"/"gradle" are just the two
                              # top-level container dirs build-all.sh tarred from its stage root —
                              # their contents (not these entries themselves) are what matters.
                # build-all.sh stages into the archive with `cp -al` (hardlinks) for speed, and
                # GNU tar itself also turns a second occurrence of the same (device, inode) —
                # e.g. incremental-compile output two extensions share — into a hardlink entry on
                # its own. That's expected, not a path escape: the link TARGET is itself just
                # another member's name, so it gets the exact same prefix/path validation as
                # every other member, and must point within the SAME subtree (wt/ or gradle/) as
                # the link itself. Symlinks are refused outright — their target is a free-form
                # string, not bound to an in-archive member at all.
                if m.issym():
                    sys.exit(f"refusing to extract symlink member: {m.name}")
                if not (m.isfile() or m.isdir() or m.islnk()):
                    sys.exit(f"refusing to extract non-regular member: {m.name}")
                if is_safe_member(m.name, "wt/"):
                    prefix = "wt/"
                    bucket = wt_members
                elif is_safe_member(m.name, "gradle/"):
                    prefix = "gradle/"
                    bucket = gradle_members
                else:
                    sys.exit(f"refusing to extract unexpected/unsafe member path: {m.name}")
                if m.islnk():
                    if not is_safe_member(m.linkname, prefix):
                        sys.exit(f"refusing to extract hardlink with an unsafe/cross-subtree target: {m.name} -> {m.linkname}")
                bucket.append(m)

            def extract(dest, members_subset, strip):
                for m in members_subset:
                    m.name = m.name[len(strip):] or "."
                    if m.islnk():
                        m.linkname = m.linkname[len(strip):] or "."
                try:
                    tf.extractall(dest, members=members_subset, filter="data")
                except TypeError:
                    # Hard-fail rather than silently falling back to the unfiltered
                    # extractall() on an older Python — this runner's Python is pinned by
                    # setup-python, so hitting this means that pin drifted, not that it's
                    # safe to proceed without this second layer of defense.
                    sys.exit("this Python has no tarfile extractall(filter=...) (needs 3.12+); "
                             "refusing to extract without it")

            extract(wt_dest, wt_members, "wt/")
            extract(gradle_dest, gradle_members, "gradle/")

    # classpath.txt files were written by Gradle at pack time with absolute paths rooted at
    # old_wt / old_gradle; rewrite them onto this job's actual extraction roots.
    rewritten = 0
    for root, _, files in os.walk(wt_dest):
        if "classpath.txt" not in files or not root.endswith(os.path.join("build", "ext-run")):
            continue
        p = os.path.join(root, "classpath.txt")
        text = open(p).read()
        new_text = text.replace(old_wt, wt_dest).replace(old_gradle, gradle_dest)
        if new_text != text:
            open(p, "w").write(new_text)
            rewritten += 1
    print(f"[extract-build] extracted {len(wt_members)} wt member(s), {len(gradle_members)} "
          f"gradle-cache member(s); rewrote {rewritten} classpath.txt file(s) -> {wt_dest} / {gradle_dest}")


if __name__ == "__main__":
    main()
