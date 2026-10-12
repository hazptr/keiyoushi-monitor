#!/usr/bin/env python3
"""Split an extension list into N shard files, round-robin, skipping blanks/comments.
    split.py <extensions-file> <n-shards> <out-dir>
Writes <out-dir>/shard-0.txt .. shard-{n-1}.txt.
"""
import sys
from pathlib import Path

ext_file, n, out_dir = sys.argv[1], int(sys.argv[2]), Path(sys.argv[3])
out_dir.mkdir(parents=True, exist_ok=True)
lines = [
    line.strip()
    for line in Path(ext_file).read_text().splitlines()
    if line.strip() and not line.strip().startswith("#")
]
shards = [[] for _ in range(n)]
for i, line in enumerate(lines):
    shards[i % n].append(line)
for i, shard in enumerate(shards):
    (out_dir / f"shard-{i}.txt").write_text("\n".join(shard) + ("\n" if shard else ""))
print(f"split {len(lines)} extensions into {n} shards")
