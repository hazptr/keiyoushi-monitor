#!/usr/bin/env python3
"""build-site job: write the public recheck.json that gets deployed to gh-pages alongside
status.json. This is the only thing that tells a polling node what to check and against which
image/commit — nothing else in this repo ever pushes work to a node.

The bundle itself is never committed to gh-pages — it's a GitHub Release asset; this script
only records its URL + checksum + size (bundleUrl/bundleSha256/bundleSize) so the controller can
trust recheck.json's own bytes for all three, fetched once, with no second round-trip to re-derive
them. harnessRuntimeCommit records which commit of THIS repo built the node image's baked-in
harness runtime, so the node can refuse a bundle built against a mismatched runtime.

  write_recheck_manifest.py <recheck-list.txt> <kei-commit-sha> <run-id> <node-image-ref> \
                             <bundle-sha256-hex> <bundle-size-bytes> <bundle-url> \
                             <harness-runtime-commit-sha> <out.json> [max]
"""
import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from node_schema import MAX_EXTENSIONS_DEFAULT, validate_recheck_manifest  # noqa: E402


def main():
    if len(sys.argv) not in (10, 11):
        sys.exit(
            "usage: write_recheck_manifest.py <recheck-list.txt> <kei-commit> <run-id> "
            "<node-image-ref> <bundle-sha256> <bundle-size> <bundle-url> "
            "<harness-runtime-commit> <out.json> [max]"
        )
    (list_file, kei_commit, run_id, node_image, bundle_sha256, bundle_size, bundle_url,
     harness_runtime_commit, out_file) = sys.argv[1:10]
    cap = int(sys.argv[10]) if len(sys.argv) == 11 else MAX_EXTENSIONS_DEFAULT

    exts = [l.strip() for l in Path(list_file).read_text().splitlines() if l.strip()][:cap]
    manifest = {
        "generated": __import__("datetime").datetime.now(__import__("datetime").timezone.utc).isoformat(timespec="minutes"),
        "keiCommit": kei_commit,
        "runId": str(run_id),
        "nodeImage": node_image,
        "extensions": exts,
        "bundleUrl": bundle_url,
        "bundleSha256": bundle_sha256.strip().lower(),
        "bundleSize": int(bundle_size),
        "harnessRuntimeCommit": harness_runtime_commit,
    }
    validate_recheck_manifest(manifest)  # fail the publish job rather than ship a broken manifest
    Path(out_file).write_text(json.dumps(manifest, indent=1))
    print(f"wrote {out_file}: {len(exts)} extension(s), image {node_image}, bundle {bundle_url}")


if __name__ == "__main__":
    main()
