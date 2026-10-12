#!/usr/bin/env python3
"""Safely extract a recheck bundle (harness/ci/pack-recheck-bundle.sh's output) inside the
sandbox, under a FIXED destination root (/work/bundle — see node/entrypoint.sh; never "/" or any
other image path) that this script's caller picks, not something the archive itself can name —
the bundle's own members are RELATIVE, with no leading "/".

This is the fix for the review finding on `tar --absolute-names -C /`: the bundle itself can
never specify an absolute path, and every member is independently re-validated here before
extraction — no absolute path, no `..` component, and the resolved destination must stay under
the chosen root. Checksum-verified against the digest recheck.json pinned (bundleSha256), before
any of that path validation even runs.

Each member's relative name is literally its original absolute path with the leading "/" stripped
(pack-recheck-bundle.sh's `--transform 's,^/,,'`), so classpath.txt — written by Gradle on the
GitHub-hosted build job with absolute paths baked in — needs those same old absolute paths
rewritten onto `<dest_root>/<old absolute path>` after extraction; done here, not left to the
caller, since this script is the one place that knows both the old and the new root.

    extract_bundle.py <bundle.tar.zst> <sha256-hex> <dest-root>
"""
import hashlib
import os
import subprocess
import sys
import tarfile
import tempfile


def sha256_of(path: str) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def is_safe_member(name: str, dest_root: str) -> bool:
    if name.startswith("/") or name.startswith("\\"):
        return False
    if ".." in name.replace("\\", "/").split("/"):
        return False
    resolved = os.path.normpath(os.path.join(dest_root, name))
    if dest_root == os.sep:
        # dest_root "/" already ends in the separator; "/" + os.sep would double it and never
        # match anything, so every resolved path is trivially "under root" once the two checks
        # above (no leading "/", no "..") have passed.
        return resolved.startswith(os.sep)
    return resolved == dest_root or resolved.startswith(dest_root + os.sep)


def main():
    if len(sys.argv) != 4:
        sys.exit("usage: extract_bundle.py <bundle.tar.zst> <sha256-hex> <dest-root>")
    bundle, expected_sha, dest_root = sys.argv[1:4]
    expected_sha = expected_sha.lower()

    actual = sha256_of(bundle)
    if actual != expected_sha:
        sys.exit(f"checksum mismatch: expected {expected_sha}, got {actual}; refusing to extract")

    dest_root = os.path.realpath(dest_root)
    with tempfile.TemporaryDirectory() as tmp:
        decompressed = os.path.join(tmp, "bundle.tar")
        # zstd isn't a stdlib codec; shell out to the `zstd` binary the image ships, same as the
        # pack side.
        with open(decompressed, "wb") as out:
            subprocess.run(["zstd", "-d", "-T0", "-c", bundle], check=True, stdout=out)
        with tarfile.open(decompressed) as tf:
            members = tf.getmembers()
            for m in members:
                # GNU tar itself turns a second occurrence of the same (device, inode) — e.g. a
                # dependency jar/class dir shared by several of the recheck subset's extensions —
                # into a hardlink entry pointing back at the first one. That's tar's own space
                # saving, not something pack-recheck-bundle.sh asked for, and it's not a path
                # escape: the link TARGET is itself just another member's name, so it gets the
                # exact same path validation as every other member. Symlinks are refused outright
                # — their target is a free-form string, not bound to an in-archive member at all —
                # and so is anything that isn't a plain file, directory, or in-archive hardlink.
                if m.issym():
                    sys.exit(f"refusing to extract symlink member: {m.name}")
                if not (m.isfile() or m.isdir() or m.islnk()):
                    sys.exit(f"refusing to extract non-regular member: {m.name}")
                if not is_safe_member(m.name, dest_root):
                    sys.exit(f"refusing to extract unsafe member path: {m.name}")
                if m.islnk() and not is_safe_member(m.linkname, dest_root):
                    sys.exit(f"refusing to extract hardlink with an unsafe target: {m.name} -> {m.linkname}")
            # Python's own tarfile "data" extraction filter (3.12+) is a second, independent
            # layer of the same no-absolute/no-".." rule enforced above. Hard-fail rather than
            # silently falling back to the unfiltered extractall() on an older Python — this
            # image pins its own Python version, so hitting this means that pin drifted, not
            # that it's safe to proceed without the second layer of defense.
            try:
                tf.extractall(dest_root, members=members, filter="data")
            except TypeError:
                sys.exit("this Python has no tarfile extractall(filter=...) (needs 3.12+); "
                          "refusing to extract without it")

    # Rewrite classpath.txt absolute paths onto dest_root — each original absolute path "/x/y"
    # became the member "x/y" under dest_root, so the rewrite is purely mechanical: prefix every
    # absolute-looking line with dest_root.
    rewritten = 0
    for root, _, files in os.walk(dest_root):
        if "classpath.txt" not in files or not root.endswith(os.path.join("build", "ext-run")):
            continue
        p = os.path.join(root, "classpath.txt")
        lines = open(p).read().splitlines()
        new_lines = [dest_root + ln if ln.startswith("/") else ln for ln in lines]
        if new_lines != lines:
            open(p, "w").write("\n".join(new_lines) + ("\n" if new_lines else ""))
            rewritten += 1
    print(f"extracted {bundle} -> {dest_root} ({len(members)} member(s)), checksum verified; "
          f"rewrote {rewritten} classpath.txt file(s)")


if __name__ == "__main__":
    main()
