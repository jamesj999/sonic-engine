#!/usr/bin/env python3
"""Convert FmSfxRenderTool write logs (<frame> <port> <reg> <val>, captured at
the chip's internal output rate so one frame is exactly 24 cycles) into
harness scripts: an `at <frame>` line per distinct frame stamp followed by the
`reg` lines of that frame, and a final `at <frames>` so the render tail is
clocked. Each log becomes one body script; the chip type is prefixed by the
harness runner (or the -t<type> expansion below)."""
import os
import sys

SRC, OUT = sys.argv[1], sys.argv[2]
os.makedirs(OUT, exist_ok=True)
count = 0
for name in sorted(os.listdir(SRC)):
    if not name.endswith("-ym-writes.txt"):
        continue
    stem = name[: -len("-ym-writes.txt")]
    with open(os.path.join(SRC, name)) as f:
        header = f.readline().strip()
        writes = [line.split() for line in f if line.strip()]
    fields = dict(part.split("=") for part in header[2:].split())
    frames = int(fields["frames"])
    body = [f"# {stem}: {header[2:]}"]
    last = None
    for frame, port, reg, val in writes:
        if frame != last:
            body.append(f"at {frame}")
            last = frame
        body.append(f"reg {int(port)} {int(reg, 16)} {int(val, 16)}")
    body.append(f"at {frames}")
    for chip_type in range(4):
        with open(os.path.join(OUT, f"{stem}-t{chip_type}.txt"), "w") as f:
            f.write(f"type {chip_type}\n" + "\n".join(body) + "\n")
    count += 1
    keyons = sum(1 for w in writes if w[2] == "28" and int(w[3], 16) & 0xF0)
    print(f"{stem}: {len(writes)} writes, {keyons} key-ons, {frames} frames")
print(f"{count} logs converted")
