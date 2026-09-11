# Audio Performance Overlay Metric Design

## Problem

The unified audio presentation work now runs at the outer-frame boundary in
`Engine.presentOuterAudioFrame()`. The existing `audio` profiler section in
`GameLoop` no longer surrounds that work; it only marks the audio step as
updated. Its measured duration is therefore effectively zero and falls below
the performance overlay's six-row limit.

## Design

Measure the complete outer-frame audio boundary as one aggregate `audio`
section. The measurement covers both:

- `GameLoop.presentOuterFrame()`, which performs synthesis and PCM
  presentation; and
- `AudioManager.update()`, which pumps the presentation sink and audio device.

Because this boundary executes inside the existing `update` profiler section,
it must not use nested `beginSection()` and `endSection()` calls. Instead,
`Engine` will measure elapsed nanoseconds and call
`PerformanceProfiler.recordSectionTime("audio", elapsedNanos)`. That API
credits the elapsed time to `audio` while removing the same interval from the
active `update` section, preventing double-counting.

The obsolete `GameLoop` audio timing calls will be removed. The
`audioUpdatedThisStep` lifecycle remains unchanged.

The overlay legend will deterministically include `audio` whenever that
section exists in the snapshot. If `audio` is already among the six
highest-cost sections, the existing top-six ordering is unchanged. Otherwise,
the legend shows the five highest-cost non-audio sections plus `audio`, ordered
by descending time. The pie chart continues to represent every measured
section and is not subject to the legend's six-row selection.

## Scope

This change restores one aggregate metric and guarantees that its legend row is
visible. It does not add audio submetrics, change audio scheduling, or change
the overlay's six-row limit.

## Testing

A focused accounting test will verify that the engine-owned outer-frame audio
boundary credits a positive elapsed interval to `audio` without
double-counting it in `update`.

Pure legend-selection tests will verify that:

- audio ranked below the first six sections is still selected;
- audio is not duplicated when it already ranks in the first six; and
- the ordinary highest-six selection is unchanged when audio is absent.

Existing engine, game-loop, profiler, and audio presentation tests will then be
run to detect scheduling, accounting, or rendering regressions.
