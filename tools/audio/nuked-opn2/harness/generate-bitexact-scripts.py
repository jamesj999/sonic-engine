#!/usr/bin/env python3
"""Emit the synthetic bit-exactness scripts (round 1): the smoke patch and a
sweep over EG rates, SSG-EG modes, LFO settings, detune/multiple, channel-3
special mode and CSM, timers, DAC writes, test registers, bus edge cases and
a seeded fuzz. Every script is written once per chip-type flag set 0..3 as
<name>-t<type>.txt; both harnesses parse the same grammar."""
import os
import random
import sys

OUT = sys.argv[1]
os.makedirs(OUT, exist_ok=True)

FRAME = 24
SEC = 53267  # output frames per second (clock/144), rounded

BOMB_REGS = [0x30, 0x38, 0x34, 0x3C, 0x50, 0x58, 0x54, 0x5C, 0x60, 0x68, 0x64, 0x6C,
             0x70, 0x78, 0x74, 0x7C, 0x80, 0x88, 0x84, 0x8C, 0x40, 0x48, 0x44, 0x4C]
BOMB_VALS = [0x21, 0x30, 0x10, 0x32, 0x1F, 0x1F, 0x1F, 0x1F, 0x05, 0x18, 0x05, 0x10,
             0x0B, 0x1F, 0x10, 0x10, 0x1F, 0x2F, 0x4F, 0x2F, 0x0D, 0x07, 0x04, 0x80]

SMOKE = [
    (0, 0x22, 0x00), (0, 0x27, 0x00), (0, 0x28, 0x00), (0, 0xB0, 0x07), (0, 0xB4, 0xC0),
    (0, 0x30, 0x01), (0, 0x34, 0x01), (0, 0x38, 0x01), (0, 0x3C, 0x01),
    (0, 0x40, 0x00), (0, 0x44, 0x7F), (0, 0x48, 0x7F), (0, 0x4C, 0x7F),
    (0, 0x50, 0x1F), (0, 0x54, 0x1F), (0, 0x58, 0x1F), (0, 0x5C, 0x1F),
    (0, 0x60, 0x00), (0, 0x64, 0x00), (0, 0x68, 0x00), (0, 0x6C, 0x00),
    (0, 0x70, 0x00), (0, 0x74, 0x00), (0, 0x78, 0x00), (0, 0x7C, 0x00),
    (0, 0x80, 0x0F), (0, 0x84, 0x0F), (0, 0x88, 0x0F), (0, 0x8C, 0x0F),
    (0, 0x90, 0x00), (0, 0x94, 0x00), (0, 0x98, 0x00), (0, 0x9C, 0x00),
    (0, 0xA4, 0x22), (0, 0xA0, 0x69), (0, 0x28, 0xF0),
]

scripts = {}


def reg(lines, part, r, v):
    lines.append(f"reg {part} {r} {v}")


def frames(lines, n):
    lines.append(f"clock {n * FRAME}")


def bomb(lines, fb_alg, channel=0, part=0):
    reg(lines, part, 0xB0 + channel, fb_alg)
    for r, v in zip(BOMB_REGS, BOMB_VALS):
        reg(lines, part, r + channel, v)


def single_op(lines, channel=0, part=0, dt_mul=0x01, tl=0, ar=31, dr=0, sr=0, sl=0, rr=15, ks=0, ssg=0, am=0):
    """Algorithm 7 voice with OP1 audible and OP2..4 silent."""
    reg(lines, part, 0xB0 + channel, 0x07)
    reg(lines, part, 0xB4 + channel, 0xC0)
    for op in (0, 4, 8, 12):
        reg(lines, part, 0x30 + op + channel, dt_mul if op == 0 else 0x01)
        reg(lines, part, 0x40 + op + channel, tl if op == 0 else 0x7F)
        reg(lines, part, 0x50 + op + channel, (ks << 6) | ar)
        reg(lines, part, 0x60 + op + channel, (am << 7) | dr)
        reg(lines, part, 0x70 + op + channel, sr)
        reg(lines, part, 0x80 + op + channel, (sl << 4) | rr)
        reg(lines, part, 0x90 + op + channel, ssg if op == 0 else 0)


def note(lines, channel=0, part=0, key=0xF0, fnum=0x269, block=4, pan=0xC0):
    reg(lines, part, 0xA4 + channel, (block << 3) | (fnum >> 8))
    reg(lines, part, 0xA0 + channel, fnum & 0xFF)
    reg(lines, part, 0xB4 + channel, pan)
    reg(lines, 0, 0x28, key | channel | (4 if part else 0))


def key_off(lines, channel=0, part=0):
    reg(lines, 0, 0x28, channel | (4 if part else 0))


# (a) smoke patch, the exact TestNukedOpn2PortSmoke pacing and length.
lines = ["pace 4 28"]
for p, r, v in SMOKE:
    reg(lines, p, r, v)
frames(lines, 4096)
scripts["smoke"] = lines

# (b1) all algorithms with feedback 0 and 7, key-off release tail.
for fb in (0, 7):
    for alg in range(8):
        lines = []
        bomb(lines, (fb << 3) | alg)
        note(lines)
        frames(lines, SEC)
        key_off(lines)
        frames(lines, SEC // 2)
        scripts[f"alg{alg}-fb{fb}"] = lines

# (b2) envelope rates.
for ar in (0, 1, 2, 4, 8, 12, 16, 20, 24, 28, 30, 31):
    lines = []
    single_op(lines, ar=ar, dr=8, sl=3, sr=4, rr=6)
    note(lines)
    frames(lines, SEC)
    key_off(lines)
    frames(lines, SEC // 2)
    scripts[f"eg-ar{ar:02d}"] = lines
for dr in (0, 2, 6, 10, 14, 18, 22, 26, 31):
    lines = []
    single_op(lines, ar=31, dr=dr, sl=8, sr=0, rr=15)
    note(lines)
    frames(lines, SEC)
    key_off(lines)
    frames(lines, SEC // 4)
    scripts[f"eg-dr{dr:02d}"] = lines
for sr in (0, 4, 8, 16, 24, 31):
    lines = []
    single_op(lines, ar=31, dr=20, sl=2, sr=sr, rr=10)
    note(lines)
    frames(lines, SEC)
    key_off(lines)
    frames(lines, SEC // 4)
    scripts[f"eg-sr{sr:02d}"] = lines
for rr in (0, 1, 3, 7, 11, 15):
    lines = []
    single_op(lines, ar=31, dr=0, sl=0, sr=0, rr=rr)
    note(lines)
    frames(lines, SEC // 4)
    key_off(lines)
    frames(lines, SEC)
    scripts[f"eg-rr{rr:02d}"] = lines
for sl in (0, 5, 10, 15):
    lines = []
    single_op(lines, ar=31, dr=16, sl=sl, sr=6, rr=8)
    note(lines)
    frames(lines, SEC)
    key_off(lines)
    frames(lines, SEC // 4)
    scripts[f"eg-sl{sl:02d}"] = lines
for ks in range(4):
    for block in (0, 3, 7):
        lines = []
        single_op(lines, ar=12, dr=10, sl=4, sr=6, rr=8, ks=ks)
        note(lines, block=block, fnum=0x3A5)
        frames(lines, SEC)
        key_off(lines)
        frames(lines, SEC // 4)
        scripts[f"eg-ks{ks}-blk{block}"] = lines

# (b3) SSG-EG: every enabled mode, instant and slow attack, with a retrigger.
for mode in range(8, 16):
    for ar in (31, 20):
        lines = []
        single_op(lines, ar=ar, dr=12, sl=0, sr=12, rr=15, ssg=mode)
        note(lines)
        frames(lines, SEC)
        key_off(lines)
        frames(lines, SEC // 4)
        note(lines, fnum=0x1C0, block=3)
        frames(lines, SEC // 2)
        scripts[f"ssg{mode:02d}-ar{ar}"] = lines

# (b4) LFO frequency with AMS/PMS per channel on six keyed channels.
for freq in range(8):
    lines = [f"reg 0 34 {8 | freq}"]
    for ch in range(6):
        part = ch // 3
        c = ch % 3
        bomb(lines, 0xFA if ch % 2 else 0xC7, channel=c, part=part)
        for op in (0, 4, 8, 12):
            reg(lines, part, 0x60 + op + c, 0x80 | 0x05)
        note(lines, channel=c, part=part, fnum=0x200 + ch * 0x30, block=2 + (ch % 5),
             pan=0xC0 | ((ch & 3) << 4) | ((ch * 2 + 1) & 7))
    frames(lines, SEC)
    scripts[f"lfo-f{freq}"] = lines
lines = []
bomb(lines, 0xFA)
for op in (0, 4, 8, 12):
    reg(lines, 0, 0x60 + op, 0x80 | 0x05)
note(lines, pan=0xF7)
frames(lines, SEC // 4)
reg(lines, 0, 0x22, 0x0D)
frames(lines, SEC // 4)
reg(lines, 0, 0x22, 0x00)
frames(lines, SEC // 4)
reg(lines, 0, 0x22, 0x08)
frames(lines, SEC // 4)
scripts["lfo-toggle"] = lines

# (b5) detune and multiple across three blocks.
for dt in range(8):
    for mul in (0, 1, 7, 15):
        lines = []
        for ch, block in ((0, 2), (1, 5), (2, 7)):
            single_op(lines, channel=ch, dt_mul=(dt << 4) | mul, ar=31, dr=0, sl=0, sr=0, rr=15)
            note(lines, channel=ch, fnum=0x2A0 + ch * 0x55, block=block)
        frames(lines, SEC // 2)
        scripts[f"dt{dt}-mul{mul:02d}"] = lines

# (b6) channel 3 special mode and CSM.
lines = []
bomb(lines, 0xFA, channel=2)
reg(lines, 0, 0x27, 0x40)
for r, v in ((0xAC, 25), (0xA8, 52), (0xAD, 34), (0xA9, 86), (0xAE, 43), (0xAA, 120), (0xA6, 49), (0xA2, 154)):
    reg(lines, 0, r, v)
reg(lines, 0, 0xB6, 0xC0)
reg(lines, 0, 0x28, 0xF2)
frames(lines, SEC // 2)
reg(lines, 0, 0x27, 0x00)
frames(lines, SEC // 4)
reg(lines, 0, 0x27, 0x40)
frames(lines, SEC // 4)
scripts["ch3-special"] = lines
lines = []
bomb(lines, 0xC7, channel=2)
note(lines, channel=2, key=0x00)
reg(lines, 0, 0x24, 0xF0)
reg(lines, 0, 0x25, 0x00)
reg(lines, 0, 0x27, 0x85)
for _ in range(8):
    frames(lines, SEC // 16)
    lines.append("status 0")
    lines.append("irq")
reg(lines, 0, 0x27, 0x95)
lines.append("status 0")
frames(lines, SEC // 8)
lines.append("status 0")
scripts["ch3-csm"] = lines

# (b7) timers.
for name, period in (("full", 0x3FF), ("mid", 0x300), ("short", 0x100), ("zero", 0x000)):
    lines = []
    reg(lines, 0, 0x24, period >> 2)
    reg(lines, 0, 0x25, period & 3)
    reg(lines, 0, 0x27, 0x05)
    for _ in range(12):
        frames(lines, 64)
        lines.append("status 0")
        lines.append("status 1")
        lines.append("irq")
    reg(lines, 0, 0x27, 0x15)
    lines.append("status 0")
    frames(lines, 64)
    lines.append("status 0")
    reg(lines, 0, 0x27, 0x00)
    frames(lines, 2048)
    lines.append("status 0")
    scripts[f"timer-a-{name}"] = lines
    lines = []
    reg(lines, 0, 0x26, period >> 2)
    reg(lines, 0, 0x27, 0x0A)
    for _ in range(12):
        frames(lines, 256)
        lines.append("status 0")
        lines.append("status 2")
        lines.append("irq")
    reg(lines, 0, 0x27, 0x2A)
    lines.append("status 0")
    frames(lines, 256)
    lines.append("status 0")
    scripts[f"timer-b-{name}"] = lines
lines = []
reg(lines, 0, 0x24, 0x80)
reg(lines, 0, 0x25, 0x01)
reg(lines, 0, 0x26, 0x40)
reg(lines, 0, 0x27, 0x0F)
for _ in range(64):
    frames(lines, 48)
    lines.append("status 0")
    lines.append("status 3")
    lines.append("irq")
    reg(lines, 0, 0x27, 0x3F)
scripts["timer-both-reset"] = lines
lines = []
reg(lines, 0, 0x24, 0x00)
reg(lines, 0, 0x25, 0x00)
reg(lines, 0, 0x27, 0x01)
for _ in range(16):
    frames(lines, 128)
    lines.append("status 0")
    lines.append("irq")
scripts["timer-a-disabled-flag"] = lines

# (b8) DAC.
for enabled in (1, 0):
    lines = []
    bomb(lines, 0xFA, channel=2, part=1)
    note(lines, channel=2, part=1)
    frames(lines, 64)
    reg(lines, 0, 0x2B, 0x80 if enabled else 0x00)
    for step in range(256):
        reg(lines, 0, 0x2A, (step * 7) & 0xFF)
        frames(lines, 2)
    reg(lines, 0, 0x2B, 0x00 if enabled else 0x80)
    for step in range(64):
        reg(lines, 0, 0x2A, 0x80 + (step & 1) * 0x40)
        frames(lines, 1)
    frames(lines, 256)
    scripts[f"dac-ramp-{'en' if enabled else 'dis'}"] = lines
lines = ["reg 0 43 128", "write 0 42"]
for step in range(4096):
    lines.append(f"write 1 {(step * 3) & 0xFF}")
    lines.append(f"clock {1 + (step % 5)}")
scripts["dac-fine"] = lines

# (b9) test registers.
lines = []
bomb(lines, 0xFA)
note(lines)
frames(lines, 32)
for value in (0x40, 0x41, 0xC0, 0xC1, 0x01, 0x02, 0x04, 0x08, 0x10, 0x20, 0x00):
    reg(lines, 0, 0x21, value)
    for _ in range(4):
        frames(lines, 3)
        lines.append("status 0")
for value in (0x08, 0x10, 0x40, 0x80, 0xC0, 0x00):
    reg(lines, 0, 0x2C, value)
    frames(lines, 16)
    lines.append("status 0")
frames(lines, 128)
scripts["test-regs"] = lines

# (b10) pan and TL changes mid-note, both ports.
lines = []
for ch in range(6):
    bomb(lines, 0xFA, channel=ch % 3, part=ch // 3)
    note(lines, channel=ch % 3, part=ch // 3, fnum=0x200 + ch * 0x20, block=3)
for step in range(64):
    for ch in range(6):
        reg(lines, ch // 3, 0xB4 + ch % 3, ((step + ch) & 3) << 6)
        reg(lines, ch // 3, 0x40 + ch % 3, (step * 2) & 0x7F)
        reg(lines, ch // 3, 0x4C + ch % 3, (127 - step) & 0x7F)
    frames(lines, 32)
scripts["pan-tl"] = lines

# (b11) key-on edge cases.
lines = []
bomb(lines, 0xFF)
for bit in range(4):
    note(lines, key=0x10 << bit)
    frames(lines, 512)
note(lines, key=0xF0)
frames(lines, 512)
reg(lines, 0, 0x28, 0xF0)
frames(lines, 512)
reg(lines, 0, 0x28, 0xF3)
reg(lines, 0, 0x28, 0xF7)
frames(lines, 512)
reg(lines, 0, 0x28, 0x00)
frames(lines, 512)
scripts["keyon-edges"] = lines

# (b12) bus edge cases: strobes with no clocks between, address-only, data-only.
lines = ["pace 0 0"]
bomb(lines, 0xFA)
note(lines)
frames(lines, 256)
lines += ["write 0 48", "write 1 34", "clock 1", "write 1 35", "clock 2", "write 0 64", "write 0 68",
          "clock 1", "write 1 5", "clock 1", "write 3 7", "clock 1", "write 2 176", "clock 1",
          "write 3 200", "clock 3", "write 1 9", "clock 1", "write 0 16", "clock 1", "write 1 255",
          "clock 20", "write 0 40", "clock 1", "write 1 0", "write 1 240", "clock 1", "write 1 240"]
frames(lines, 512)
scripts["bus-edges"] = lines

# (b13) seeded fuzz over the whole bus.
for seed in range(3):
    rng = random.Random(0xBEEF00 + seed)
    lines = []
    for _ in range(20000):
        roll = rng.random()
        if roll < 0.10:
            lines.append(f"clock {rng.randint(0, 40)}")
        elif roll < 0.14:
            lines.append(f"status {rng.randint(0, 3)}")
        elif roll < 0.15:
            lines.append("irq")
        elif roll < 0.30:
            lines.append(f"write {rng.randint(0, 3)} {rng.randint(0, 255)}")
        elif roll < 0.36:
            reg(lines, 0, 0x28, rng.choice([0, 1, 2, 3, 4, 5, 6, 7]) | (rng.randint(0, 15) << 4))
        elif roll < 0.42:
            reg(lines, 0, rng.choice([0x21, 0x22, 0x24, 0x25, 0x26, 0x27, 0x2A, 0x2B, 0x2C]), rng.randint(0, 255))
        else:
            reg(lines, rng.randint(0, 1), rng.randint(0x30, 0xB7), rng.randint(0, 255))
    frames(lines, 512)
    scripts[f"fuzz-s{seed}"] = lines

for name, body in scripts.items():
    for chip_type in range(4):
        with open(os.path.join(OUT, f"{name}-t{chip_type}.txt"), "w") as f:
            f.write(f"# {name}, chip type flags {chip_type}\n")
            f.write(f"type {chip_type}\n")
            f.write("\n".join(body) + "\n")
print(f"{len(scripts)} scripts x 4 chip types")
