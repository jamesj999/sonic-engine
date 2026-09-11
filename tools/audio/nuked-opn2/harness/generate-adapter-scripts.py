#!/usr/bin/env python3
"""Emit the register scripts that TestYm2612ChipNukedParity runs through both
the Java facade (Ym2612Chip) and the pinned C build (adapter_parity_harness.c).

Every script starts with a `type` line: 3 is ym3438_mode_ym2612|readmode (the
engine's chip type 0), 2 is readmode alone (engine chip types 1 and 2). The
scripts are deterministic; the fuzz script is expanded from a fixed seed here
so the Java and C sides need no shared random generator.
"""
import os
import random
import sys

OUT = sys.argv[1]
os.makedirs(OUT, exist_ok=True)

# Sonic 1 bomb voice as written by the S1 SMPS driver (register, value).
BOMB_REGS = [0x30, 0x38, 0x34, 0x3C, 0x50, 0x58, 0x54, 0x5C, 0x60, 0x68, 0x64, 0x6C,
             0x70, 0x78, 0x74, 0x7C, 0x80, 0x88, 0x84, 0x8C, 0x40, 0x48, 0x44, 0x4C]
BOMB_VALS = [0x21, 0x30, 0x10, 0x32, 0x1F, 0x1F, 0x1F, 0x1F, 0x05, 0x18, 0x05, 0x10,
             0x0B, 0x1F, 0x10, 0x10, 0x1F, 0x2F, 0x4F, 0x2F, 0x0D, 0x07, 0x04, 0x80]


def bomb(lines, fb_alg, channel=0, port=0):
    lines.append(f"write {port} {0xB0 + channel} {fb_alg}")
    for reg, val in zip(BOMB_REGS, BOMB_VALS):
        lines.append(f"write {port} {reg + channel} {val}")


def note_on(lines, channel=0, port=0, key=0xF0, fnum_hi=0x22, fnum_lo=0x69, pan=0xC0):
    lines.append(f"write {port} {0xA4 + channel} {fnum_hi}")
    lines.append(f"write {port} {0xA0 + channel} {fnum_lo}")
    lines.append(f"write {port} {0xB4 + channel} {pan}")
    lines.append(f"write 0 40 {key | channel | (4 if port else 0)}")


def emit(name, lines):
    with open(os.path.join(OUT, name + ".txt"), "w") as f:
        f.write("\n".join(lines) + "\n")


for type_flags, suffix in ((3, "ym2612"), (2, "ym3438")):
    # All eight algorithms with feedback 7 and feedback 0.
    for fb in (7, 0):
        for alg in range(8):
            lines = [f"type {type_flags}"]
            bomb(lines, (fb << 3) | alg)
            note_on(lines)
            lines.append("render 2048")
            emit(f"bomb-alg{alg}-fb{fb}-{suffix}", lines)

    # Channel 3 special mode: per-operator frequencies.
    lines = [f"type {type_flags}"]
    bomb(lines, 0xFA, channel=2)
    lines += ["write 0 39 64", "write 0 172 25", "write 0 168 52", "write 0 173 34",
              "write 0 169 86", "write 0 174 43", "write 0 170 120", "write 0 166 49",
              "write 0 162 154", "write 0 182 192", "write 0 40 242", "render 2048"]
    emit(f"ch3-special-{suffix}", lines)

    # Partial key-on: one operator bit at a time.
    for bit in range(4):
        lines = [f"type {type_flags}"]
        bomb(lines, 0xFF)
        note_on(lines, key=0x10 << bit)
        lines.append("render 1024")
        emit(f"partial-keyon-op{bit}-{suffix}", lines)

    # LFO with AMS/PMS per channel.
    lines = [f"type {type_flags}", "write 0 34 15"]
    for ch in range(3):
        bomb(lines, 0xFA, channel=ch)
        lines.append(f"write 0 {0x60 + ch} {0x80 | 0x05}")
        note_on(lines, channel=ch, fnum_hi=0x22 + ch, pan=0xC0 | (ch << 4) | ch)
    lines.append("render 4096")
    emit(f"lfo-ams-pms-{suffix}", lines)

    # SSG-EG: each of the eight enabled modes on operator 1.
    for mode in range(8):
        lines = [f"type {type_flags}"]
        bomb(lines, 0xC7)
        lines += ["write 0 80 16", "write 0 96 16", "write 0 112 0", "write 0 128 255",
                  f"write 0 144 {8 | mode}"]
        note_on(lines)
        lines.append("render 4096")
        emit(f"ssg-eg-mode{mode}-{suffix}", lines)

    # Timers and CSM: overflow flags read back through status.
    lines = [f"type {type_flags}"]
    bomb(lines, 0xC7, channel=2)
    note_on(lines, channel=2, key=0x00)
    lines += ["write 0 36 0", "write 0 37 1", "write 0 38 128", "write 0 39 143",
              "render 1100", "status", "render 1100", "status", "write 0 39 48", "status",
              "render 300", "status"]
    emit(f"timers-csm-{suffix}", lines)

    # silenceAll after a keyed voice, then a fresh voice on port 1.
    lines = [f"type {type_flags}"]
    bomb(lines, 0xFA)
    note_on(lines)
    lines.append("render 512")
    for key in (0x00, 0x04, 0x01, 0x05, 0x02, 0x06):
        lines.append(f"write 0 40 {key}")
    for reg in range(0x30, 0x90):
        lines.append(f"write 0 {reg} 255")
        lines.append(f"write 1 {reg} 255")
    bomb(lines, 0xFA, channel=1, port=1)
    note_on(lines, channel=1, port=1)
    lines.append("render 1024")
    emit(f"silence-all-{suffix}", lines)

    # DAC enable with a ramp of 0x2A writes, FM6 keyed underneath.
    lines = [f"type {type_flags}"]
    bomb(lines, 0xFA, channel=2, port=1)
    note_on(lines, channel=2, port=1)
    lines += ["render 64", "write 0 43 128"]
    for step in range(64):
        lines.append(f"write 0 42 {(step * 4) & 0xFF}")
        lines.append("render 3")
    lines += ["write 0 43 0", "render 256"]
    emit(f"dac-ramp-{suffix}", lines)

    # Seeded fuzz over the writable register file, chip type fixed.
    rng = random.Random(0x5EED0000 | type_flags)
    lines = [f"type {type_flags}"]
    for _ in range(4000):
        roll = rng.random()
        if roll < 0.08:
            lines.append(f"render {rng.randint(1, 40)}")
        elif roll < 0.12:
            lines.append(f"write 0 40 {rng.choice([0x00, 0x01, 0x02, 0x04, 0x05, 0x06]) | (rng.randint(0, 15) << 4)}")
        elif roll < 0.15:
            lines.append(f"write 0 {rng.choice([0x22, 0x24, 0x25, 0x26, 0x27, 0x2A, 0x2B])} {rng.randint(0, 255)}")
        else:
            port = rng.randint(0, 1)
            reg = rng.randint(0x30, 0xB6)
            if (reg & 3) == 3:
                reg -= 1
            lines.append(f"write {port} {reg} {rng.randint(0, 255)}")
    lines.append("render 512")
    emit(f"fuzz-{suffix}", lines)
