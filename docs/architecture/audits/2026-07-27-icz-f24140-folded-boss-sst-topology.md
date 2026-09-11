# ICZ frame 24140 folded-boss SST topology audit

## Outcome

The missed scattered-ring pickup at ICZ complete-run frame 24140 is a
downstream object-slot phase error. `IczEndBossInstance` folds six native
child objects into the parent but retained allocator pressure for only three.
Reserving all six restores the native allocation topology and advances the
trace frontier to frame 24179.

## Lost-ring timeline

Sonic is hit near frame 24023. The corresponding positive-X ring is native
slot 35 (`Obj37`, code `$0001A64A`) and engine slot 42.

| Event | Native slot 35 | Engine slot 42 |
|---|---:|---:|
| Spawn origin | `$43B0,$06B3` | `$43B0,$06B3` |
| First visible position, f24024 | `$43B0,$06AF` | `$43B0,$06AF` |
| Initial X/Y velocity before gravity | `$00C4,$FC14` | `$00C4,$FC14` |
| Floor probe/bounce | f24127 | f24126 |
| Position before pickup, f24139 | `$43FD,$0696` | `$43FD,$0695` |
| Pickup at f24140 | routine 6, rings 2→3 | routine 2, rings remain 2 |

The engine's f24024 first-step state is
`x=$43B0C4, y=$06AF14, xVel=$00C4, yVel=$FC2C`; movement and `$18`
gravity agree with native. At f24126 the engine has
`x=$43F360, y=$06B8C0, xVel=$00C4, yVel=$FCC1` and has already taken the
floor branch, while native slot 35 is still descending at `$43F3,$06C0`.

`Obj_Bouncing_Ring` gates its floor probe with
`(V_int_run_count+3+d7)&7`, where `Process_Sprites` initializes `d7` from
the SST count and decrements it for each slot
(`docs/skdisasm/sonic3k.asm:35549-35616,35965-35980`). Native slot 35 has
phase `$4A`; engine slot 42 has `$43`. The differing low three bits explain
the one-frame floor-probe skew without changing motion math or collision
tolerance.

## Earliest allocation divergence

The ring is created from a frozen-player block. Native uses slot 35; the
engine block is slot 41 and creates the second ring in slot 42. The block in
turn is created by an ICZ boss frost puff. The triggering native puff uses
slot 28 while the engine uses a folded-effect reservation at slot 12.

The first boss snow particles initially agree exactly. By frame 23000,
native later snow slots are three higher than their engine counterparts:
native slots 31-39 match engine slots 28-36 position-for-position.

Those three missing slots belong to the ICZ end boss:

| Native slot | Owner |
|---:|---|
| 25 | Robotnik ship |
| 26 | top body child |
| 27 | middle body child |
| 28 | bottom solid body child |
| 29 | Robotnik/head child created by the ship |
| 30 | bottom hurt child created by the bottom body |

At `loc_71C36`, `Obj_ICZEndBoss` creates `ChildObjDat_72336` and
`ChildObjDat_7233E`: one ship plus three structural children. The ship and
bottom body then create the two additional children during their object
dispatches (`docs/skdisasm/sonic3k.asm:150612-150634,150875-150908`).
All six remain live concurrently.

The engine models their rendering and behavior inside the boss parent, which
is valid only if the folded objects' SST pressure is retained. Previously it
reserved three slots. That shifted snow allocation, then frost-puff and
frozen-block allocation, and finally changed the spilled ring's slot-owned
floor cadence.

## Fix and regression contract

`IczEndBossInstance.ensureStructuralChildSlots()` now reserves six slots
after the boss parent and records the bottom structural child's native fourth
reserved position. The reservation remains owned by the boss lifecycle and
uses the existing generic child-slot allocator.

`foldedIczEndBossReservesEveryLiveNativeChildSst` is the focused regression
contract. Before the production change it failed with expected 6, actual 3;
afterward it passes.

No ring-phase compensation, collision expansion, ICZ-specific shared-runtime
branch, trace hydration, or frame predicate is involved.

## Verification

- Baseline complete replay: 29 errors, first f24140 `rings`, expected 3,
  actual 2.
- Post-fix complete replay: 31 errors, first f24179 `rings`, expected 5,
  actual 6.
- Focused boss contract: pass.

The later frame-24179 contact is intentionally left as the next independent
frontier.
