# Continue screens for 0.6

Implement the production Game Over -> Continue -> level/title path for S1,
S2 and locked-on S3K. Assets come from the supplied ROM through existing
mapping, decompression, palette and sprite-rendering helpers.

## Owners and boundaries

- `GameModule.createContinueScreenProvider` constructs the game-owned screen.
  Providers own ROM countdown, character animation, input acceptance and music.
- `MenuScreenModeController` handles input dispatch;
  `GameLoopContinueCoordinator` connects the existing fade and restart lifecycle
  without expanding the central loop. `EngineRenderDispatcher` selects the
  screen-space drawing path. Continue is a non-rewindable menu boundary.
- `GameStateManager` spends a continue and resets lives/score without clearing
  emerald progress. S1/S2 clear the checkpoint; S3K retains it and saves updated
  life/continue counts, matching `Cont_GotoLevel` / `ContinueScreen` / `loc_5C48A`.
- The PLC lifecycle gets a Continue phase: S1 `VBlank_Continue` and S2 `Vint_Menu`
  publish player art but do not service/arm the Nemesis PLC queue.
- A dedicated virtual pattern range avoids stale level/title art collisions.

## Work and verification

Shared mode/restart wiring and per-game screen providers are separate workstreams
in the task checkout. Only the lead runs Maven in that checkout. A separate
updated-base checkout owns baseline reports. Focused tests cover countdown,
start edges, exit animation, timeout, inventory/checkpoint policy, ROM assets,
input during fades and render dispatch. Compare full ordinary-suite and fresh
JVM guard results before integration and after merging into develop. Run release
trace comparison where the new mode changes existing trace expectations.

Do not hide incomplete ROM effects or extend trace authority to implement the
screen. Preserve unrelated main-workspace changes. Finish with release notes,
known-bug correction, verified develop integration/push and task cleanup.
