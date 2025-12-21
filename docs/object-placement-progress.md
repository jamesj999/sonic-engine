# Object Placement Progress (2025-12-21)
## What’s done
- Reviewed `docs/sonic2_rev01_objects_plan.md` and Rev01 disassembly for ground-truth data.
- Confirmed `Off_Objects` pointer table starts at ROM `0x0E6800` (Rev01). The table is built with stride 2 (acts per zone) even for zones with 1 or 3 acts; extra entries are padded with Null/duplicate lists.
- Parsed record format from `ChkLoadObj` in `s2.asm`:
  - x: 16-bit, terminator `0xFFFF`
  - y/flags word: bit15 respawn-tracked, bits13-14 render flags, bits0-11 Y position
  - id: byte, subtype: byte
- Implemented:
  - `RomByteReader` (BE helpers, pointer16)
  - `ObjectSpawn` immutable record
  - `Sonic2ObjectPlacement` (Rev01 offsets, act stride=2)
  - `ZoneAct` pointer indexing with configurable stride
  - `ObjectPlacementManager` spawning/despawning with camera window, directional refresh, and respawn tracking hooks
  - Level integration + debug markers when `DEBUG_VIEW_ENABLED`
  - `RingSpawn` and `Sonic2RingPlacement` (Rev01 ring table at `0x0E4300`, 4-byte ring groups expanded to individual rings)
  - `RingPlacementManager` with debug ring markers
  - `NemesisReader` (Nemesis decompression used by ring art)
  - `Sonic2RingArt` + ring sprite sheet (ROM ring art at `0x7945C`, mappings from `0x12382`)
  - `RingRenderManager` to render animated ring sprites
  - Debug overlay labels for object ID/subtype/flags in `DebugRenderer`
  - Tests: reader unit tests; placement snapshots for EHZ1/HTZ1/MCZ1/OOZ1/MTZ2/SCZ/DEZ; act-clamp tests for MTZ3 and single-act zones; pointer table offset check; ring placement snapshot + single-act fallback

## Outstanding / Next options
1) Respawn-bit fidelity: map placements to the respawn table and wire consume/remember events when objects are collected/destroyed.
2) Backtracking parity: match `ObjectsManager` bucket scanning more closely (current backtracking does a full window refresh).
3) Audit object placement pointer entries for CPZ/ARZ/CNZ/WFZ (current table entries point to an empty list; confirm against disassembly).
4) Ring placement audit: confirm ring table indexing against disassembly and verify ring spacing if any zones appear offset.
5) Ring art parity: confirm ring sprite alignment/animation and mapping format against disassembly or a known viewer.
6) Acts-per-zone handling: keep stride=2 for Rev01 tables but document any per-zone exceptions once pointer tables are verified.
7) CSV/JSON export hook for placement data (deprioritized for now).
8) Integration touch-ups: add more placement snapshots once CPZ/ARZ/CNZ/WFZ tables are verified.
