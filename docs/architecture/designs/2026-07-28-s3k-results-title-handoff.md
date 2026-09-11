# S3K Results-to-Title Handoff Design

## Problem

The AIZ Act 1 replay reaches the results exit with the engine still holding the
players in ending animation `$13` through trace frame 8799. The ROM has restored
the players to animation `$05` by frame 8796 and submits the first Act 2 title
card KosM job on the following title-card dispatch. Recorded hardware therefore
expects `ArtKosM_TitleCardRedAct` at frame 8800, but the engine has no matching
production job pending.

The failed job is `KOS_MODULE_QUEUE` ordinal 23, fingerprint
`sha256:10eb568a…cffb0ca`, ROM source `$0D6F28`, destination tile `$500`.
Ordinals 24–26 are the remaining Act 2 title-card jobs.

## ROM ownership

The disassembly separates three responsibilities:

1. `Obj_LevelResultsWait2` waits for its children, clears `_unkFAA8`, and mutates
   its own SST into `Obj_TitleCard`.
2. `Obj_EndSignControlAwaitStart` independently observes `_unkFAA8` and calls
   `Restore_PlayerControl`.
3. The rebased `Obj_TitleCardInit` queues Red Act, S3K zone text, Act 2, and AIZ
   title art on its next object dispatch.

Relevant source is `sonic3k.asm:62108-62166`, `62684-62725`, and
`180361-180424`.

## Current mismatch

`S3kResultsScreenObjectInstance.updateExitQueue()` serializes the displaced ROM
owners:

1. retire embedded result children;
2. consume carried child-retire dispatches;
3. restore player control;
4. consume `postControlHandoffDelayEntries`;
5. call `onExitReady()`, which initializes the in-level title card.

That chain makes control restoration and title initialization depend on the
same retained result owner. It cannot represent the ROM's independent
end-sign-control owner and delays the title-card KosM submission.

## Design

Use the existing `GameState.endOfLevelActive` signal as the ROM `_unkFAA8`
equivalent rather than adding a parallel event-manager signal.

- The result owner publishes by clearing `endOfLevelActive` when its children
  and legitimate result-child retirement dispatches are complete. At that
  boundary it enters a retained, captured title-init phase rather than deleting
  itself.
- The later-slot `S3kBossDefeatSignpostFlow` is the independent end-sign-control
  owner. It observes the cleared signal in its normal object pass and performs
  the complete ROM `Restore_PlayerControl` writes: clear object control and
  in-air, set animation and previous animation to `$05`, and clear the animation
  frame and timers.
- Title-card initialization occurs on the next dispatch of the mutated result
  owner and submits the four normal ROM-backed KosM jobs through
  `Sonic3kTitleCardManager`.
- `postControlHandoffDelayEntries` must no longer serialize title initialization
  behind control restoration for this lifecycle. If another ROM flow genuinely
  needs retained-owner dispatch accounting, express it through the typed flow
  configuration at its existing owner.
- Determine whether `waitDurationAdjustment` or
  `carriedResultsRetireDispatches` double-counts a displaced owner by a
  dispatch-level production test. Do not tune either value against frame 8796.

## Resolved dispatch accounting

Two production-dispatch tests identify the remaining owner errors without
consulting trace state:

- `Obj_LevelResultsWait2` owns the literal `#90` counter written by
  `Obj_LevelResultsWait`. AIZ's two-entry `waitDurationAdjustment` repeated
  displaced entries which the boss/signpost bridge already carries into
  `Obj_EndSignControlWait`, so the typed AIZ adjustment is zero.
- Each result child consumes the `render_flags.on_screen` bit produced by the
  preceding `Render_Sprites` pass before moving by `$20`. The embedded engine
  elements instead moved and immediately compared their centres against
  hard-coded `-256`/`576` bounds. They now retain the prior render bit and
  recompute it from the ROM-authored `width_pixels` against the native
  320-pixel viewport. The final queue-9 child clears the parent count on queue
  dispatch 18; the parent observes zero and publishes on dispatch 19.

This is shared results-child behavior derived from
`LevelResults_MoveElement`/`Render_Sprites`, while the zero wait adjustment is
owned only by the delayed AIZ boss/signpost flow. Neither rule depends on a
zone name, trace frame, or recorded value.

The recorded hardware authority remains unchanged: it may release only a
matching prepared production job. The repair creates the job at the ROM-owned
boundary; it does not relax ordinal, fingerprint, or service-boundary matching.

## Effect partition

The publication dispatch performs result-owner effects that the ROM executes
before or as it changes routine: finish legitimate child retirement, clear
`endOfLevelActive`, update apparent act/music/camera/event handoff exactly once,
and retain the object in a title-init phase. It must not initialize title art,
delete the object, mark it complete, or restore player control.

The following result-owner dispatch initializes the in-level title card,
including its four KosM submissions, and then hands lifecycle ownership to the
title-card manager. It must not repeat publication effects. Act 2 and special
result paths retain their existing direct flag/deletion behavior. SOZ1 and DEZ1
retain their ROM no-title-card branches and must not leave a retained result
owner behind.

## Rewind

The retained result phase and publication state are captured scalar state; they
must not be `final`, static, or trace-hydrated. The title-card manager owns the
post-initialization lifecycle and must provide a rewind snapshot/rebind contract
for its active phase and four submitted hardware handles. Restore must rebind
existing captured submissions by stable production identity; it must neither
create jobs that production did not submit nor duplicate ordinals. If the
existing hardware queue snapshot already owns the handles, the title manager
captures only its lifecycle references and rebinds to those restored handles.

Two-sided rewind coverage takes snapshots immediately before publication, after
publication but before title init, and after title init. Each restore must
preserve independent control consumption and title ownership without duplicate
side effects or job submissions.

## Validation

A production-path lifecycle test will drive results through final child
retirement and assert by owner dispatch:

1. publication by clearing `endOfLevelActive`;
2. later-slot `S3kBossDefeatSignpostFlow` control restoration in the same object
   pass where slot order permits;
3. no title job on the publication dispatch;
4. exactly four title jobs on the following result-owner dispatch, beginning
   with fingerprint `10eb568a…cffb0ca`;
5. no duplicate title jobs on later dispatches.

Focused verification includes results/signpost lifecycle tests, the AIZ replay
and complete-run canary, HCZ/MGZ result transitions, SOZ1 and DEZ1 no-title
paths, Act 2/special deletion paths, hardware-timing authority guards, rewind
coverage, and the project trace sweep. A moved trace frontier must be recorded
in `docs/status/trace-frontier-log.md`.

The bounded implementation advances the AIZ1-to-HCZ hardware frontier from
ordinal 23/raw frame 8800 to ordinal 27/raw frame 8943. All four mutated-title
jobs (23–26) are now submitted and admitted. Ordinal 27 belongs to the later
post-title `LoadEnemyArt` owner: the first `PLCKosM_AIZ` request,
`ArtKosM_AIZ_MonkeyDude` at `ArtTile_MonkeyDude`. It is deliberately left for
a separate investigation rather than extending this design by cadence tuning.
