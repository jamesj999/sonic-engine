# ICZ RNG ownership and lifecycle audit

Date: 2026-07-27
Branch: `bugfix/ai-trace-s3k-icz-rng-ownership`
Baseline: `41f3bc62f2ee674b005bdab2c4536e7ce556dd64`

## Scope and baseline

This is a diagnosis-only follow-up to the original late-window audit. No
production behavior was changed. The complete ICZ replay reproduced:

```text
29 errors, 0 warnings
first error: frame 24140 -- rings mismatch (expected=3, actual=2)
```

The investigation compared the committed native per-frame RNG seed and SST
snapshots with temporary engine seed/caller/slot logging. All temporary Java
logging was removed after capture.

## Probe status

`tools/bizhawk/probes/icz_rng_ownership_probe.lua` uses `ProbeRuntime`, which
sets invisible emulation, sound off, uncapped emulation, and 6400% speed. Its
hooks are registered only after the semantic level/ICZ2/camera gate. It pairs
the `Random_Number` entry at `$001D24` with its RTS at `$001D4A`, asserts the
predicted result and post-seed, records the return PC plus A0/A1 SST context,
and unregisters/flushes/exits through the shared runtime.

The corrected dual-hook probe was run from this worktree with:

```bash
env BIZHAWK_HOME=/home/farrell/code/projects/OpenGGF/docs/BizHawk-2.11-linux-x64 \
  OGGF_OUT=/home/farrell/code/projects/OpenGGF/.worktrees/trace-s3k-icz-rng-ownership/target/icz-rng-native.txt \
  tools/bizhawk/run_bizhawk_lua.sh \
  tools/bizhawk/probes/icz_rng_ownership_probe.lua \
  src/test/resources/traces/s3k/_movies/s3k-complete-sonic-tails.bk2 \
  s3k.gen
```

BizHawk exited successfully and produced 43 paired RNG records in the
worktree-local scratch artifact `target/icz-rng-native.txt` (SHA-256
`1cb145cad2592f3de716133820bdd205b4b97111958a99f12119d033f94f43f5`).
The semantic camera gate armed at trace f17118; the capture ended on the first
snow return at f22733. It directly proves the native owner sequence only
within that late window:

| Trace frame | Return PC | SST | Native transition |
|---:|---:|---|---|
| 22486 | `$02C92E` | slot 5, `Obj_Animal`, routine `$02`, x/y `$4169/$06E3` | `528B07B3 -> 73EF3BAB` |
| 22733 | `$08B6C2` | slot 10, snow particle, routine `$02` | `73EF3BAB -> 1FB38E63` |

`$02C92E` returns from the subtype-zero animal RNG call at `loc_2C924`
(`docs/skdisasm/sonic3k.asm:61049-61055`). `$08B6C2` returns from the snow
particle RNG call in `loc_8B6AE`
(`docs/skdisasm/sonic3k.asm:189957-189985`).

The follow-up `tools/bizhawk/probes/icz_slot20_allocation_probe.lua` arms at
the earlier ICZ2 `$3888` gameplay-counter boundary and records only the
f14488-f14511 `Process_Sprites`, allocation, dispatch, and RNG window. Its
scratch capture contains 144 lines (SHA-256
`b09cc8ad94fa9fc11f7e80c380d0c3708742578f2f9eb47ca49161f349dbe932`).
It directly covers f14502.

## First persistent ownership mismatch and correction

The first one-frame seed skew is f2661 and realigns at f2662. Several other
short timing skews also realign. Before the correction, the first mismatch
that did not realign was f14502:

| Frame | Native owner/result | Baseline engine owner/result |
|---:|---|---|
| 14501 | no call; seed `E9697A23` | no call; seed `E9697A23` |
| 14502 | miniboss explosion controller: `E9697A23 -> F17F8F9B` | same controller draw, then snow particle: `F17F8F9B -> AD40FFD3` |
| 14503 | seed remains `F17F8F9B` | seed remains `AD40FFD3` |

The extra engine consumer was a first-dispatch `SnowdustParticle` in slot 20,
owned by an `IczSnowPileObjectInstance` emitter in slot 5. Native f14501 has
already retired the `loc_8B660` emitter SST: native slot 5 is code
`$0001CEF2`, subtype `$0D`. At f14502 native slot 20 is instead code
`$00083F68`, routine `$02`, created by the miniboss explosion topology.

The ROM emitter at `loc_8B660` increments its timer/count and calls
`AllocateObject`; only an allocated child later reaches `loc_8B6AE` and calls
`Random_Number` (`docs/skdisasm/sonic3k.asm:189930-189985`).

The aligned native allocation timeline identifies late emitter retirement as
the owner:

| Trace frame | Native event |
|---:|---|
| 14493 | slot-5 `loc_8B660` calls plain `AllocateObject`; the snow child receives slot 16 and first-dispatches that frame |
| 14500 | the boss body reaches its post-defeat handoff; slot 20 is still an unrelated transient |
| 14501 | slot 5 sees `$38` bit 5 and deletes; slot 20 is empty |
| 14502 | slot-15 explosion control calls `AllocateObjectAfterCurrent`, receives slot 20, and alone advances `E9697A23 -> F17F8F9B` |

`BossDefeated` writes `$3F` to the boss body's `$2E`.
`Wait_FadeToLevelMusic` decrements it and reaches `loc_713E8` on the 64th
following body dispatch; that routine follows `_unkFAAE`, verifies
`loc_8B660`, and sets its `$38` bit 5 while the independent
`Child6_CreateBossExplosion` SST continues
(`docs/skdisasm/sonic3k.asm:149867-149875,179656-179670,180814-180829`).

The engine had an unused `defeatTimer` initialized to the unrelated `$B3`
value and stopped the emitter only when the explosion-controller SST finished.
Temporary baseline logging observed the extra snow allocation at engine object
frame 21501 before the explosion-controller draw, and emitter stop only at
21528. The correction sets the body timer to `$3F` and lets the defeated body
stop the semantic active snow owner on its 64th dispatch. It deliberately
leaves the already-established folded signpost/results flow at the explosion
controller completion boundary; moving that whole flow earlier causes a
separate control regression and is not required to model the emitter write.

The focused RED test
`bossDefeatedWaitStopsSnowEmitterBeforeExplosionControllerFinishes` proves
that the emitter remains live through the 63rd body wait and is stopped on the
64th while the explosion controller remains independent.

## Ice-cube allocation versus first dispatch

Temporary engine logging recorded both examined cube shatters. Every one of
the twelve children received a real SST slot and all twelve dispatched:

| Native frame / engine object frame | Parent | Child slots | Result |
|---|---|---|---|
| f19134 / 6802 | slot 18, x/y `$26C0/$00B3` | 20-31 | 12 allocated, 12 RNG draws |
| f19863 / 7531 | slot 16, x/y `$2CC3/$02F6` | 20-26 and 28-32 | 12 allocated, 12 RNG draws |

The native SST snapshots show the same parents, positions, child subtypes
`$00..$16`, and child routine `$02`. `CreateChild1_Normal` stops on allocation
failure (`docs/skdisasm/sonic3k.asm:176924-176957`); each allocated child
first dispatches `loc_8B432`, which then calls `Random_Number`
(`docs/skdisasm/sonic3k.asm:189690-189705`).

Consequently, the previously suspected eager `IceCubeDebris` construction is
not the observed owner of this trace's persistent mismatch. No examined child
failed allocation, and constructor-versus-dispatch timing does not explain
the f14502 extra call. The broader constructor-order concern remains a
separate architectural risk, but it must not be presented as the ICZ
f24140 root cause without a failing allocation reproduction.

## Corrected downstream seed and owner crosswalk

Post-correction temporary engine logging proves the ownership sequence
realigns beyond the local window:

| Event | Native | Corrected engine |
|---|---|---|
| f14502 explosion | `E9697A23 -> F17F8F9B`, slot 15 allocates slot 20 | `E9697A23 -> F17F8F9B`, explosion slot 15 only |
| f22486 animal | `528B07B3 -> 73EF3BAB`, slot 5 | `528B07B3 -> 73EF3BAB`, slot 5 |
| f22733 first snow | `73EF3BAB -> 1FB38E63`, slot 10 | `73EF3BAB -> 1FB38E63`, slot 10 |
| later snow | `1FB38E63 -> E19CCDDB -> 1A2FF813 -> ECB9BB0B` | the same successor sequence in slots 11-13 and 15 |

The former three “extra animal” consumers disappear; they were a cascade of
the earlier shifted seed ownership, not independent animal-lifetime defects.

## f24140 ring event

The first reported error is a missed collection, not a ring-counter write
discrepancy. Native rings change from 2 to 3 at f24140. Immediately before the
change, Sonic is near `$43ED/$06AD`; native lost-ring slot 35
(`Obj_Lost_Ring`, code `$0001A64A`) is at `$43FD/$0696`, within collection
range. The engine stays at 2 because its scattered lost-ring geometry differs.
Despite the now-aligned RNG ownership sequence, the full replay remains 29
errors with first mismatch f24140. The missed pickup is therefore the next
separate geometry/lifetime frontier; it must not be repaired through ring
counting, collision tolerance, RNG adjustment, or an f24140 exception.
