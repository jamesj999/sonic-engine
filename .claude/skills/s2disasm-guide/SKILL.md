---
name: s2disasm-guide
description: Use when locating Sonic 2 disassembly routines, labels, compression types, or verified ROM offsets.
---

# Sonic 2 disassembly lookup

The root is `docs/s2disasm/s2.asm`; most object routines are inline.
Look for `ObjXX`, `ObjXX_Index`, `ArtNem_`, and `Map_` labels. Sprite mappings
live under `mappings/sprite/`; object fields use `routine`, `x_pos`, `y_pos`.

Disassemblies are optional research submodules; initialize the relevant one if
needed. Runtime data comes from the user-supplied ROM, not these reference files.

## Lookup commands

Run from the repository/worktree root; replace the example label with the target:

```bash
mvn exec:java "-Dexec.mainClass=com.openggf.tools.disasm.RomOffsetFinder" \
  "-Dexec.args=--game s2 search <label>" -q
mvn exec:java "-Dexec.mainClass=com.openggf.tools.disasm.RomOffsetFinder" \
  "-Dexec.args=--game s2 verify <label>" -q
```

Use `rg -n '<label>|<routine>' docs/s2disasm/s2.asm` to inspect callers and dispatch tables.
For split files, use `rg --files` under the relevant disassembly directory.
A name match is a candidate, not proof: verify bytes/decompression and the
routine's pointer to the asset. Check the actual ROM filename and revision.

## Porting details

S2/S3K mapping frames have a word piece count and 6-byte pieces (signed word X).
Y offsets are signed bytes. Decode the size byte and flags using the existing
mapping loader; sprite tiles are column-major. Do not reuse a parser for a
different mapping format solely because the art looks similar.

The shipped disassembly build uses `fixBugs = 0`.
Model that branch, including its bugs. When porting a conditional, comment the
flag, chosen branch, and what the fixed branch changes. Preserve instruction
width, signedness, carry, and fallthrough where they affect the routine.

## Conditional routing

For object/boss integration, use the matching implementation skill.
For PLC/DPLC queue ownership use `../plc-system/SKILL.md`; for compared trace
fields or timing admission use `../trace-replay-bug-fixing/SKILL.md` and the
current timing contract it references. This lookup skill does not select replay behavior.
