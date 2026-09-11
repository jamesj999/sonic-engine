# S2 title-card mode flip: the arm frame S2 does not have

Point-in-time investigation, 2026-08-20, base `b9a9685db`, branch
`bugfix/ai-s2-modeflip-phase-r1`.

## The ROM frame at issue

All three level routines clear the control lock and the title-card game-mode bit
**inline, with no vsync**, before the main loop's first wait:

- S2 `move.b #0,(Control_Locked).w` / `bclr #GameModeFlag_TitleCard,(Game_Mode).w`
  ahead of `Level_MainLoop`'s `WaitForVint` — `docs/s2disasm/s2.asm:5081, 5085, 5088-5091`.
  The leave loop at :5060-5066 reads `TitleCard_Background`'s cleared id three
  instructions after the `RunObjects` that deleted it, in the SAME iteration, so its
  26th pass (1 leading pass at :5006 + 25 loop iterations) is also the fall-through row.
- S3K `move.b #0,(Ctrl_1_locked).w` / `bclr #7,(Game_mode).w` ahead of `LevelLoop`'s
  `Wait_VSync` — `docs/skdisasm/sonic3k.asm:7859, 7883, 7888-7891`.
- S1 runs a real `Level_LoadObj` / `ExecuteObjects` pass with no V-int of its own
  between `Level_TtlCardLoop` and `Level_MainLoop` —
  `docs/s1disasm/sonic.asm:2895-2897, 2999-3003`.

The frame that *ends* on that first main-loop wait is therefore a settled, unlocked
level frame that has consumed no input: the recorded "arm frame", stamped as the
segment's `bk2_frame_offset` and deliberately not recorded as a row.

## MEASURED

Instrumented `GameLoopTitleCardLifecycle` (temporary probe, not committed) over
`TestS1GhzMazeRoundTripChain` and `TestS2EhzHalfpipeRoundTripChain`:

```
PROBE-RELEASE preludePasses=1 provider=Sonic1TitleCardManager result=SETUP_ONLY
PROBE-RELEASE preludePasses=0 provider=TitleCardManager     result=GAMEPLAY_FRAME
```

S1's release ends its host step as a setup step — a standalone arm frame with no
recorded row. S2's release returns `GAMEPLAY_FRAME` and the game loop falls through
into the destination's row 0 **in the same host step**, so the arm frame exists in S2
only fused with row 0 and is never observable between steps. `AbstractRunChainTest`
encodes that fusion directly: it asserted `framesConsumed == 1` at the return
comparator's attach, i.e. that the S2 return has already consumed row 0.

Also measured, same probe run: the S3K prefix chain reaches its return with no
title-card release at all, so S3K's phase was not measured here and is taken from
`TraceRunBoundaryComparator`'s existing citation and the `framesConsumed == 0`
statement already in `AbstractRunChainTest`.

## ESTABLISHED

The brief's premise holds. S2 has no engine step for the ROM's arm frame; S1 does,
via `levelObjectPreludePassesAtRelease()`. The difference is structural, not a
tolerated tie: the two games sit on different sides of the same ROM instant, and the
return-boundary comparator's phase-agnostic `start_x`/`start_y`-versus-row-0
selection is what absorbs it.

## NOT ESTABLISHED — why the re-phase did not land

A prototype re-phase is on this branch (see the commit that carries this file):
`TitleCardProvider.releasesAfterFinalLockedPass()` / `completeFinalLockedPass()`,
implemented for S2 so the release runs at the END of the 26th pass's frame rather
than at the start of the frame after it, and the releasing step stays a setup step.

With it, **every compared physics row of `s2-ehz-halfpipe-roundtrip` stays green**,
in both of the run's returns, with the V-blank anchor and the comparator base both
re-keyed to `framesConsumed == 0`. Two things do not settle:

1. **Dynamic-art gap edge stamps shift +1.** Both returns report every
   `run_gap.edge[N].movie_logical_frame` one higher than recorded (e.g. expected
   9675/9700, actual 9676/9701). The gap edges are re-rowed by
   `TraceRunDynamicArtGapJournal.rowsCountedBackFromAdmission`, counting back from
   the admission instant's unannounced-row total; moving admission off the fused row
   moves that reference. The reference has to become "the last movie row the engine
   has actually run" (`bk2FrameOffset + rowsConsumed - 1`), which needs the
   recorder's own admission convention in `tools/bizhawk-headless` read before it can
   be changed — the failure mode of guessing is a silent one-row shift that reads as
   a physics divergence.
2. `TestTraceRunReplayWalkerControlFlow.uncomparedInteriorReturnVblankBudgetRunsToTheLastConsumedDestinationRow`
   pins `uncomparedInteriorReturnVblankBudget`'s `returnFramesConsumed < 1` throw.
   The prototype relaxes it to `< 0`; the contract that guard states deserves to be
   re-derived deliberately rather than relaxed to make an arm pass.

Guards (`-Pguards`, 500 tests) are green on the prototype, as are
`TestTitleCardObjectExecution` (re-phased: the 26 passes are now counted after the
releasing step, and `Level_frame_counter` is 0 at the arm frame and 1 after one more
step), `TestS1GhzMazeRoundTripChain`, `TestS3kSonicTailsCompleteEmeraldRunPrefix`,
`TestTraceRunBoundaryComparator`, `TestTraceRunDynamicArtGapJournal`,
`TestTraceRunPlaybackCoordinator` and `TestTracePlaybackProfile`.

## Consequence for the comparator simplification

`TraceRunBoundaryComparator.comparePosition`'s selection must NOT be collapsed to
`start_x`/`start_y` yet. It is only safe once all three games agree on the arm frame,
and S2 does not — and the prototype that makes it agree is not landable until the
gap-edge admission reference above is settled.

## Follow-up, 2026-08-20: residual 1 is closed

Settled on `bugfix/ai-gap-edge-stamping-r1` (base `aea82587b`). The recorder stamps a
gap edge with the movie row it is *executing* — `PrepareDynamicArtCursor(rowsConsumed)`
runs before `host.Advance()`
("tools/bizhawk-headless/src/Recording/S2RunCaptureRunner.cs":207-222) — and arms the
destination after that row completes (:529-532), so its own cursor at the arm holds
`bk2FrameOffset - 1`.

Measured, base vs. this prototype on the same run: edge stamps and per-edge
`unannouncedRowsAtEmit` are identical; only the admission-instant unannounced total
moves, by exactly one. So the defect was never in the anchor row but in the row the
delta is subtracted *from*. The base is now
`bk2FrameOffset + destinationRowsConsumed - 1`; the staleness test stays on the
admission row. With that change this prototype's `TestS2EhzHalfpipeRoundTripChain`
passes outright. Residual 2 is unchanged.
