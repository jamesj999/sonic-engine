# LBZ frame 23533: not a speed cap -- a Ribot child running one row ahead

Attribution round for the frontier `TestS3kLbzZoneSliceTraceReplay` moved onto
after the Flybot767 wake-phase fix. Measured at `2e15b6251`.

```
Totals: 4585 errors, 0 warnings.
First error: frame 23533 -- x_speed mismatch (expected=0x016F, actual=0x0200)
```

Attributed, not fixed. The owner is upstream of the reported field and the
remedy is not a limit.

## The round number is not a cap

`0x0200` against `0x016F` reads as a value clamped to a limit. It is not.

At row 23533 (`physics.csv` row `5BED` -- the frame column is hexadecimal) the
engine is in the **hurt** state and the ROM is not:

| | ROM @23533 | engine @23533 | ROM @23534 |
|---|---|---|---|
| `rings` | 21 | 0 | 0 |
| `routine` | `0x02` | `0x04` | `0x04` |
| `status_byte` | `0x06` | `0x02` | `0x02` |
| `animation_id` | `0x02` | `0x1A` | `0x1A` |
| `x_speed` / `y_speed` | `0x016F` / `-0018` | `0x0200` / `-0400` | `0x0200` / `-0400` |

`0x0200` and `-0x400` are the S3K hurt knockback, not a speed limit, and the ROM
writes exactly those values one row later. The recording confirms the ROM's
timing independently: 21 `Obj_LostRings` (`object_code 0x0001A64A`) appear in aux
`object_appeared` at frame **23534**, and `rings` drops `21 -> 0` on the same row.

So this is the same class as LBZ 411 -- an event landing one row early -- not an
acceleration source and not a cap. Nothing here should be tuned.

## What hurts him

`HURT by=RibotChild at=(1270,063C)`, from a probe on the hurt path. The hazard is
ROM slot 20, `object_code 0x0008C370` = `loc_8C370` (`sonic3k.asm:191313`), a
`parent3`-owning child of `Obj_Ribot` (`sonic3k.asm:191259`) that draws through
`Child_DrawTouch_Sprite`. The engine calls it `RibotChild`. It is long-lived, not
a projectile: aux `object_appeared` creates it at frame 23436, ~97 rows earlier.

## The child is one row ahead of the ROM -- corrected from two

**This section is a correction.** Its first version said two rows. It is one,
constantly, and the error is instructive enough to keep.

Measured with a frame-delimited probe carrying the driver's compared row, per
rule 121. Within one engine frame the order is `SCAN` -> `MOVE` -> `DRIVE idx=N`,
so the `MOVE` in the frame that ends with `DRIVE idx=N` writes the engine's
end-of-row-N position.

Walking the whole ROM-covered stretch, engine end-of-row-`N` equals ROM
`object_state` frame `N+1` at every row from 23476 to 23533:

```
row    engine MOVE       ROM object_state
23477  (12E8,061E)       (12E9,0620)
23478  (12E6,061B)       (12E8,061E)
...
23492  (12C8,0601)       (12CB,0602)
23493  (12C5,05FF)       (12C8,0601)
```

**How "two" happened.** The lead was measured at rows 23531 and 23534 -- both
adjacent to a point where the ROM series *stalls*. Row `5BBA` (23482) is a lag
row: `lag_counter=0001`, `gameplay_frame_counter` unchanged, and the ROM repeats
the previous position. Where the ROM repeats a value, `eng(N) == rom(N+1)` and
`eng(N) == rom(N+2)` are both true, and the larger was reported.

That is the same failure mode this document already warns about one section
down, in a new place: two sequences compared by value cannot be disambiguated
across a stall, because the stall makes two different offsets produce identical
matches. The fix is to classify only where the reference series is locally
strictly monotonic, and to print the ambiguity rather than pick from it.

The engine handles that lag row correctly, incidentally: it emits no `MOVE` at
row 23482, matching the ROM's hold.

## Both candidate mechanisms are ruled out

The two candidates named for the walk-back were a per-frame double-step and a
creation-frame error that persisted. Neither survives.

**Not a double-step.** The lead is exactly one row at 23476 and exactly one row
at 23533, constant across every covered row between. A per-frame double-step
would accumulate; over ~57 covered rows it would be ~57 rows of lead.

**Not a creation-frame offset.** The engine `CREATE`s the child at compared row
23436 and `MOVE`s it in that same row. The ROM does the same: aux
`object_appeared` installs `0x0008C370` in slot 20 at frame 23436, and the
creating helper `CreateChild1_Normal` (`sonic3k.asm:176924`) allocates through
`AllocateObjectAfterCurrent` (`sonic3k.asm:176929`), which takes a slot after the
parent -- so `Process_Sprites` reaches the child later in the same frame's walk
and the child runs its own routine in its creation frame. Both sides create and
run it on row 23436, so no offset is acquired there.

So it is neither of the two shapes, and specifically **not** a third member of the
`Obj_WaitOffscreen` wake-phase family closed earlier today: that class was closed
at one member from the ROM side, and this child does not wait offscreen at all.

## Where the row is acquired, and why this round cannot say

The lead is gained somewhere in rows 23437-23476. `object_state` emits no rows
for slot 20 in that window -- its coverage begins at 23477 -- because the stream
only records objects near the player, and the child spends that window
approaching.

This is rule 119 exactly: the walk-back bounds only what the recorded rows cover,
and the acquisition sits in a window nothing recorded. It is a coverage gap by
construction, not a measurement left untaken.

The parent gives no signal either: ROM slot 19, `object_code 0x0008C2E8`, sits at
`(12B0,064C)` unmoving for the whole window, so nothing about its phase is
observable from position.

Naming what would settle it, without proposing a fix: a probe on the child's
state machine across 23436-23477 compared against the parent's routine
transitions, or a re-record of this segment with `object_state` forced on for the
slot. The mechanism is **unnamed**, and that is the honest state.

## It is masked, not fresh

Checked rather than assumed, because a first error moving is not the same as a
row becoming wrong. The **pre-fix** report at `fd7739bdf^` already contains
**9 error spans starting at 23533**, with identical values -- `x_speed 0x016F` vs
`0x0200`, `g_speed`, `y`, `y_speed`, `routine`, `rolling`. So 23533 is a
pre-existing divergence that the frame-411 error was hiding, and the wake-phase
fix neither caused nor moved it.

The first attempt to check this reported "not present" because the walker looked
for a `frame` key while the report stores `start_frame` / `end_frame`. That was a
fact about the walker. Rule 123, one level down: an absence needs the shape
checked before it is believed.

## Named upstream owner

The owner is **the Ribot child's own timing** -- not the touch path, not the
player's physics, and not any limit. It leads the ROM by exactly one row, gained
once, inside a window the recording does not cover, after a creation frame that
matches. Deliberately not fixed.
