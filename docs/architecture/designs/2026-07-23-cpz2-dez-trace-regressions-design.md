# CPZ2 and DEZ Trace Regression Fixes

## Scope

Restore the previously passing Sonic 2 CPZ2 and DEZ level-select trace replays
without changing trace data, weakening comparisons, or adding zone/frame
exceptions.

## CPZ2 Runtime Fix

`TailsCPU_Flying_Part2` clamps the delayed leader Y target to
`Water_Level_1 - $10` whenever water is active. The shared sidekick controller
already exposes this ROM-derived clamp for Tails respawn flight, but the normal
catch-up flight path stores the unbounded history Y.

Apply the existing semantic water clamp when catch-up flight samples its delayed
leader target. Add focused controller coverage proving that a target below the
water ceiling is clamped while an above-water target is unchanged.

## DEZ Bootstrap Fix

Sonic 2 initializes `Sonic_Pos_Record_Buf` before the title-card movement
window. `Obj01_Init` temporarily moves Sonic by `(-$20,+4)`, fills all 64
history entries, then restores his native start position. DEZ's native ROM start
is `(0x0060,0x012D)`, whereas its first recorded gameplay position is
`(0x0060,0x012C)`.

The replay bootstrap currently uses the first gameplay position too early, so
DEZ's prefilled history is one pixel high. Preserve the level loader's native
start position through the history-prefill phase, then continue using the
existing input-only title-card simulation to reach frame zero. The implementation
must be generic for S2 native-prelude traces and derive its value from level
startup state, not the DEZ zone id or trace contents.

Add focused bootstrap coverage for the native-start/history distinction and
retain the frame-zero comparison as the end-to-end assertion.

## Verification

Run the focused unit tests first, then both affected trace replays with the
Sonic 2 REV01 ROM. Finally run all `*TraceReplay` tests because the sidekick
controller and S2 bootstrap paths are shared. Record the resulting frontier
movement in `docs/status/trace-frontier-log.md`.
