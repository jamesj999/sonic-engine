# Solid Ordering Call-Site Inventory

## Raw reference commands

```powershell
rg -n 'SolidObject' docs/s1disasm
rg -n 'SolidObject|SlopedSolid_SingleCharacter|PlatformObject' docs/s2disasm
rg -n 'SolidObjectFull|SolidObjectTop|SolidObjectTopSloped2' docs/skdisasm
```

## Raw inventory baseline

| Command | Count |
| --- | ---: |
| `rg -n 'SolidObject' docs/s1disasm` | 41 |
| `rg -n 'SolidObject|SlopedSolid_SingleCharacter|PlatformObject' docs/s2disasm` | 268 |
| `rg -n 'SolidObjectFull|SolidObjectTop|SolidObjectTopSloped2' docs/skdisasm` | 422 |

## Deduped routine inventory

| Game | Routine | Helper | File | Line | Sentinel? | Notes |
| --- | --- | --- | --- | ---: | --- | --- |
| S2 | ObjB2 / Tornado | SolidObject | docs/s2disasm/s2.asm | 78301 | yes | SCZ main calls SolidObject inline before follow motion |
| S2 | Obj86 / Flipper | JmpTo2_SlopedSolid | docs/s2disasm/s2.asm | 57865 | yes | Needs same-frame clear/no-contact semantics |
| S2 | Obj40 / Springboard | JmpTo_SlopedSolid_SingleCharacter | docs/s2disasm/s2.asm | 51830 | yes | Standing latch currently crosses frame seam |
| S3K | Obj05 / AIZ-LRZ Rock | SolidObjectFull | docs/skdisasm/sonic3k.asm | 43930 | yes | Engine currently compensates with pre-contact snapshot getters |
| S3K | Obj7B / Hand Launcher | SolidObjectTop | docs/skdisasm/sonic3k.asm | 59305 | yes | Captured players bypass normal rider tracking |
| S1 | Engine frame-order bridge | n/a | src/main/java/com/openggf/LevelFrameStep.java | 87 | yes | S1 runs object exec before player physics, unlike S2/S3K |
| S1 | Engine frame-order bridge | n/a | src/main/java/com/openggf/level/LevelManager.java | 1094 | yes | updateObjectPositionsWithoutTouches pre-applies velocity |
