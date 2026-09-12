# Mushroom Hill completion audit

## Scope and outcome

Direct implementation review of both MHZ acts for Sonic, Tails and Sonic with
Tails, using the supported locked-on ROM and shipped `FixBugs=0` behavior.
After the current camera-boundary correction, the user explicitly excluded
further Knuckles-route work. Standalone Sonic & Knuckles entry is not part of
this locked-on-ROM scope. The existing engine level-select/debug controls are
retained; the console pulley menu-unlock sequence is not implemented.

The user ended trace-driven development after the initial diagnostic round.
No further replay, probe or capture was run. Historical measurements remain in
`docs/status/trace-frontier-log.md`; they are not completion gates or a claim
of frame-perfect parity. This review supplements `mhz-analysis.md`.

Worktree: `.worktrees/feature-ai-mhz-completion`, branch
`feature/ai-mhz-completion`, base `10f844594b`. References were read from the
root checkout's `docs/skdisasm/sonic3k.asm`. Runtime assets still come from ROM.
Locked-on ROM identity: CRC32 `63522553`, SHA-1
`cfbf98c36c776677290a872547ac47c53d2761d6`.

## Feature closure

| Feature | Direct implementation evidence |
|---|---|
| Entry and camera progression | `Sonic3kMHZEvents` implements the locked-on height-based Act 1 minimum X, dynamic maximum Y, miniboss lock and Act 2 route boundaries. Corrected the mistaken character test in `sub_54B80`'s port; the ROM tests standalone mode. |
| Traversal objects and badniks | Existing mushroom, vine, bar, pulley, breakable-wall, pollen, Dragonfly, Mushmeanie and Madmole implementations and focused tests cover the playable mechanics. Corrected Madmole's deferred body deletion. Verified Mushmeanie's left-wall addition and attacking-player pointer against the owning routines. |
| Act 1 cutscene | Shared `_unkFAA9` door latch now belongs to `MhzZoneRuntimeState`, survives object streaming, resets on full level reload and participates in rewind. |
| Miniboss and Act 1 → Act 2 | Existing encounter/child graph and `updateAct1BackgroundEvent` implement defeat, results, the `$4200` coordinate shift, target-level reload and `TITLE_OWNER` in-level title-card handoff. The earlier deferred title-card note was obsolete. |
| Seasons, parallax and animated art | Existing event regions select spring/autumn/gold palettes; `SwScrlMhz` handles normal and boss deformation, and registered animation owners provide the ROM-backed background/AniPLC effects. There is no native AnPal cycle to add. Existing focused tests cover these owners. |
| Act 2 custom arena | Background stages load the ROM custom layout, blocks, chunks and art through the mutation/ROM pipeline, wait or redraw at the native airborne boundary, initialize pillars/spikes and restore both planes after the boss signal. Reviewed against `loc_5528A` through `loc_55486`. |
| End boss and exit | Existing boss/ship children, normal scroll table, repeat/clamp logic and restore sequence implement the Sonic/Tails encounter. `loc_7645E`'s grab-wait completion requests FBZ Act 1 with immediate level deactivation; the existing boss test exercises the whole terminal state sequence. Corrected debris direction, initialization, flicker and deferred deletion. |
| Rewind and integration | Existing child graph tests plus the new shared-latch recreation/restore test cover changed state ownership. The focused MHZ run also includes required AIZ/loading/bootstrap/decoding gates. Final suite evidence follows below. |

No additional missing Sonic/Tails gameplay feature was identified in this direct
review. This is an implementation-completion judgment, not proof that every
possible route is frame-perfect. No new visual capture was made, following the
user's direction to finish without further trace work.

## Corrections and ROM evidence

- **Madmole:** `loc_8D6CA` moves the body and calls `Obj_Wait`;
  `loc_8D6D6` clears the parent busy bit and calls `Go_Delete_Sprite`. That
  helper installs next-pass deletion, so `loc_8D602` still publishes touch at
  the final submerged position. The engine now retains position, mapping,
  velocity and reserved slot until that next pass.
- **Door latch:** `loc_6300C` reads global `_unkFAA9` when recreating the door.
  `loc_60DE` clears its containing work RAM on full reload. The old claim
  that death necessarily preserves it was incorrect.
- **Boss fragments:** `CreateChild6_Simple` copies mappings/art tile without
  parent render flags. `loc_766CA` sets only bit 2, so `Set_IndexedVelocity`
  uses the unmirrored table for either boss orientation. The init pass draws
  without movement; `Obj_FlickerMove` starts hidden on the first movement
  pass, and `Go_Delete_Sprite_3` defers slot release until the next pass.
- **Act 1 camera:** `sub_54B80` tests `SK_alone_flag`, not character identity.
  The supported locked-on game uses min X `$C0` for player Y below `$580`,
  otherwise zero, including Knuckles. The existing Knuckles test now checks
  this boundary; no further Knuckles work was undertaken.
- **Mushmeanie checks:** both wall branches join `loc_8DBB4`'s `add.w d1,x_pos`.
  `Check_PlayerCollision` stores the selected player in parent `$44` before
  `loc_8DC42` reads the velocity reversed by `loc_8DC9C`. Neither required a
  gameplay change; added a left-wall regression check and corrected the docs.

## Validation

All commands use Java 21 and `-Dmse=off`. ROM-backed checks use absolute paths
to the existing root ROM files. Maven build/report trees are local to each
worktree; no target trees were copied.

- Owner direct MHZ run: **622 tests, zero failures/errors/skips**, on base
  `10f844594b` plus the door, Madmole and fragment corrections, before the
  final camera correction. Command arguments: `target/mhz-direct-all-command.txt`;
  completed log: `target/mhz-direct-all.log`. It selects 41 MHZ-related class
  names plus the required S3K stability gates, without replay tests.
- Initial full-suite attempt in the owner checkout was interrupted (exit 143)
  after a confirmed AppKit wait under sandbox restrictions. It is not a
  completed result. Isolated graphics recheck with desktop access passed
  7 tests without skips (`target/mhz-gl-recheck.log` in the validation checkout).
- Initial guards completed 656 tests, with one failure and two errors from
  temp-path alias normalization and missing Lua/PowerShell. The configured
  rerun uses canonical `TMPDIR=/private/tmp`, Lua 5.4.8 via `LUA_BIN`, and
  PowerShell 7.6.6 on PATH. No source behavior was changed to bypass guards.
- Configured owner guard run completed successfully: **656 tests, zero
  failures/errors/skips**, `target/mhz-guards-configured.log`, finished
  2026-09-12 11:07:03 BST. Command: `PATH="/private/tmp/openggf-release-pwsh:$PATH"
  LUA_BIN=/private/tmp/openggf-slicer-lua/lua-5.4.8/src/lua TMPDIR=/private/tmp
  mvn -Dmse=off -Pguards test -B`.
- Final owner direct run completed successfully on all current source edits:
  **622 tests, zero failures/errors/skips**, finished 2026-09-12 11:09:04 BST.
  `target/mhz-direct-final-command.txt` records the same 41-name selector and
  absolute ROM property as the earlier direct run, with isolated reports at
  `target/mhz-direct-final-reports`; log `target/mhz-direct-final.log`.
  This includes the corrected camera boundary, shared door state, fragment
  phases and all required AIZ/loading/bootstrap/decoding checks.
- Auxiliary ordinary full suite completed with exit 1 on the earlier
  Madmole/fragment-direction candidate: **20,292 tests, 27 failures, 8 errors,
  97 skips**, finished 2026-09-12 11:15:06 BST. Log:
  `.worktrees/mhz-replay-validation/target/mhz-full-candidate.log`; fresh XML
  reports: `target/mhz-full-candidate-reports` in that checkout. Command:
  `mvn -Dmse=off -Dsonic1.rom.path="<absolute root S1 REV01 ROM>"
  -Dsonic2.rom.path="<absolute root S2 REV01 ROM>"
  -Ds3k.rom.path="<absolute root locked-on ROM>"
  -Dopenggf.surefire.reports=target/mhz-full-candidate-reports test`.
  The final direct run above validates the newer MHZ changes; the broad run
  is not claimed as a green full suite or as validation of newer source.

The broad run's failing classes are outside MHZ: FBZ route/compatibility tests
(14 failures), sample-mod packaging (6), S2 donor HUD (1), Mod API hook policy
(1), audio/reference/shell tests (4 failures and 3 errors), mod scaffolding
(1 failure), cross-game donor ROM loading (2 errors), and graphics contexts
(3 errors). These were not diagnosed as MHZ regressions or silently labelled
baseline failures. Observed platform/setup errors include Linux-only
`/usr/bin/bash`, unsupported GLSL 410, BSD `base64` arguments and a donor ROM
lookup of `s3k.gen` despite supplied test properties.

All 97 skipped cases were inspected. They include opt-in benchmarks/captures,
missing reference artifacts, EGL availability, ROM consumers that still look
for `s1.gen`/`s2.gen` instead of supplied properties, and a CNZ spin-tube setup
assumption. No ROM files were copied, renamed or linked to disguise those
coverage gaps. No MHZ case was skipped in the final focused run.
