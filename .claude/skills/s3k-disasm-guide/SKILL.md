---
name: s3k-disasm-guide
description: Use when locating Sonic 3 & Knuckles disassembly routines, labels, compression types, or verified ROM offsets.
---

# Sonic 3 & Knuckles disassembly lookup

The primary locked-on source is `docs/skdisasm/sonic3k.asm`.
Prefer its S&K-half addresses below `0x200000`; `s3.asm` describes the S3 half.
If both halves match, choose the reference actually used by the locked-on routine.
Some objects legitimately point into the S3 half; do not invent an S&K substitute.
Compression is indicated by label suffix (`_KosM`, `_Kos`, `_Nem`, `_Eni`),
not the `.bin` file extension.

Disassemblies are optional research submodules; initialize the relevant one if
needed. Runtime data comes from the user-supplied ROM, not these reference files.

## Lookup commands

Run from the repository/worktree root; replace the example label with the target:

```bash
mvn exec:java "-Dexec.mainClass=com.openggf.tools.disasm.RomOffsetFinder" \
  "-Dexec.args=--game s3k search <label>" -q
mvn exec:java "-Dexec.mainClass=com.openggf.tools.disasm.RomOffsetFinder" \
  "-Dexec.args=--game s3k verify <label>" -q
```

Use `rg -n '<label>|<routine>' docs/skdisasm/sonic3k.asm` to inspect callers and dispatch tables.
For split files, use `rg --files` under the relevant disassembly directory.
A name match is a candidate, not proof: verify bytes/decompression and the
routine's pointer to the asset. Check the actual ROM filename and revision.

## Porting details

S2/S3K mapping frames have a word piece count and 6-byte pieces (signed word X).
Y offsets are signed bytes. Decode the size byte and flags using the existing
mapping loader; sprite tiles are column-major. Do not reuse a parser for a
different mapping format solely because the art looks similar.

The shipped disassembly build uses `FixBugs = 0`.
Model that branch, including its bugs. When porting a conditional, comment the
flag, chosen branch, and what the fixed branch changes. Preserve instruction
width, signedness, carry, and fallthrough where they affect the routine.

## Zone-set distinction

`S3kZoneSet.S3KL` uses the 256-entry object table for zone IDs 0–6;
`SKL` uses the 185-entry table for 7–13. FBZ is ID 4 and uses S3KL object IDs
while its level is from the S&K half. Resolve IDs with
`Sonic3kObjectRegistry.getPrimaryName(id, zoneSet)`.
Level-half decisions are separate: inspect the owning predicate, such as
`SSEntry_CheckLevel` (`Current_zone < 7 && Current_zone != 4`).

## Conditional routing

For object/boss integration, use the matching implementation skill.
For PLC/DPLC queue ownership use `../plc-system/SKILL.md`; for compared trace
fields or timing admission use `../trace-replay-bug-fixing/SKILL.md` and the
current timing contract it references. This lookup skill does not select replay behavior.
