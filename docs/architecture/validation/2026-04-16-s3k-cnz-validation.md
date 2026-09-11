# S3K CNZ Visual Validation

Date: 2026-04-16  
Engine build: `7b47151f77b03373da3f91d8de283c1016ee2cfa`  
Zone: `CNZ` = Carnival Night Zone (Sonic 3 & Knuckles), not Sonic 2 Casino Night Zone  
Reference: `stable-retro` game id `SonicAndKnuckles3-Genesis-v0`

## Evidence

- Full CNZ regression gate passed:
  - `mvn test "-Ds3k.rom.path=Sonic and Knuckles & Sonic 3 (W) [!].gen" "-Dtest=TestCnzZoneRuntimeState,TestS3kCnzPatternAnimation,TestS3kCnzPaletteCycling,TestSonic3kCnzRuntimeStateRegistration,TestSonic3kCnzScroll,TestS3kCnzAct1EventFlow,TestS3kCnzBossScrollHandler,TestS3kCnzMinibossArenaHeadless,TestS3kCnzTeleporterRouteHeadless,TestS3kCnzWaterHelpersHeadless"`
  - Result: `MSE:OK modules=1 passed=44 failed=0 errors=0 skipped=0`
- Engine-side CNZ capture utility passed:
  - `mvn test "-Ds3k.rom.path=Sonic and Knuckles & Sonic 3 (W) [!].gen" "-Dtest=com.openggf.game.sonic3k.TestS3kCnzVisualCapture#captureCnzReferenceFrames"`
  - Result: `MSE:OK modules=1 passed=44 failed=0 errors=0 skipped=0`
- ROM-side reference PNGs were exported from built-in `stable-retro` CNZ states to `target/s3k-cnz-visual/reference/`:
  - `cnz_act1_start_f000.png`
  - `cnz_act1_start_f015.png`
  - `cnz_act1_start_f030.png`
  - `cnz_act1_start_f045.png`
  - `cnz_act2_start_f000.png`
  - `cnz_act2_start_f015.png`
  - `cnz_act2_start_f030.png`
  - `cnz_act2_start_f045.png`
- Engine-side PNGs were exported to `target/s3k-cnz-visual/engine/`:
  - `cnz_a1_start.png`
  - `cnz_a1_settle.png`
  - `cnz_a2_start.png`
  - `cnz_a2_settle.png`
  - `cnz_a2_knuckles_route.png`

## Limits

- The imported `stable-retro` data set exposes built-in CNZ Act 1 and Act 2 start states, but no built-in late Act 1 miniboss, underwater, or Knuckles-route reference states.
- The current agent environment can generate local PNG artifacts but cannot directly semantically inspect those local images. Rows below therefore use:
  - `PASS` for beats locked by deterministic tests and explicit ROM/disassembly anchors.
  - `LIKELY` where ROM and engine captures were both produced, but final semantic image inspection remains manual.
  - `SKIP` where no trustworthy ROM state or dedicated deterministic assertion was available in this pass.

## Feature Results

| Beat | ROM anchor | Engine trigger | Result | Notes |
|------|------------|----------------|--------|-------|
| Act 1 miniboss entry shift | `CNZ1BGE_Normal` | Camera X `>= $3000` | PASS | `TestSonic3kCnzScroll.bossBackgroundModeUsesBossScrollPathForEarlyAndLateRefreshPhases` pins the threshold switch into `ACT1_MINIBOSS_PATH` and verifies the boss scroll path replaces the normal 7/16 background path. |
| Arena destruction and lowering | `CNZ1_ScreenEvent` + `Obj_CNZMinibossTop` | Force top-piece collision | PASS | `TestS3kCnzMinibossArenaHeadless.minibossTopHitQueuesArenaChunkRemovalAndLowersBossBase` verifies explicit chunk-removal coordinates and lowering-row accumulation. |
| FG refresh and collision handoff | `CNZ1BGE_AfterBoss` | First `Events_fg_5` after defeat path | PASS | `TestS3kCnzMinibossArenaHeadless.scrollControlBridgeSignalAdvancesCnzEventState` proves the real scroll-control producer drives the same `Events_fg_5` seam consumed by `Sonic3kCNZEvents`. |
| Seamless Act 1 -> Act 2 reload | `CNZ1BGE_DoTransition` | Second `Events_fg_5` at reload gate | PASS | `TestS3kCnzAct1EventFlow.productionPostBossChainAdvancesToReloadGateAndManagerSeesTransitionRequest` verifies the reload request, zone/act word `0x0301`, and world offsets `-0x3000,+0x0200`. |
| Act 2 Knuckles teleporter route | `CNZ2_ScreenEvent` + `Obj_CNZTeleporter` | Publish route, arm teleporter, ground player | PASS | `TestS3kCnzTeleporterRouteHeadless.knucklesTeleporterRequiresPublishedRouteBeforeLockingControl` verifies clamp to `$4A40`, control lock, beam spawn, and the late-route camera window `$4750-$48E0`. |
| Pachinko DMA phase | `AnimateTiles_CNZ` | Publish deform phase and update CNZ animated tiles | PASS | `TestS3kCnzPatternAnimation` verifies phase derivation from `(Events_bg+$10 - Camera_X_pos_BG_copy) & $3F`, graph registration, and tile changes in VRAM range `$308+`. ROM and engine opening-state PNG sequences were also generated for manual inspection. |
| Underwater palette parity | `AnPal_CNZ` | Advance CNZ palette cycle with underwater surface present | PASS | `TestS3kCnzPaletteCycling` verifies the three CNZ palette channels and their underwater mirroring through the palette-ownership registry. No built-in `stable-retro` underwater CNZ state was available in this pass. |
| Route-specific PLC art presence | `PLC_EggCapsule` + `ArtKosM_CNZTeleport` | Run CNZ Act 2 route and bounded end-boss handoff | LIKELY | Engine-side `cnz_a2_knuckles_route.png` was generated, and `TestS3kCnzTeleporterRouteHeadless` verifies the beam/capsule separation, but this pass did not have a matching ROM-side late-route state to complete direct visual confirmation. |
| CNZ shake behavior | `ShakeScreen_Setup` | Act 2 background/boss-end beats | SKIP | The anchor was documented in the spec, but this pass did not isolate a trustworthy CNZ-specific shake trigger with matching ROM and engine captures. |
| Opening visual parity | Built-in `stable-retro` states `CarnivalNightZone.Act1` / `CarnivalNightZone.Act2` | `TestS3kCnzVisualCapture#captureCnzReferenceFrames` | LIKELY | ROM and engine PNGs were produced for both acts' opening/start-settle frames. Final semantic image comparison remains a manual review step because the local agent cannot inspect generated PNGs directly. |

## Summary

7 PASS / 2 LIKELY / 0 FAIL / 1 SKIP

## Issues Found

- The first full Task 9 regression sweep exposed a real harness bug: `TestCnzZoneRuntimeState` instantiated CNZ events without a configured runtime even though `setWaterTargetY(...)` now intentionally mirrors into the shared water system. The test was corrected to bootstrap the normal S3K runtime before asserting CNZ state exposure.
- The `stable-retro` environment was installed in WSL, but the S3K ROM had not been imported and `Pillow` was missing from that venv. Both were corrected during this validation pass.
- Late-route and underwater ROM visual states were not available as built-in `stable-retro` states, which limits direct visual confirmation for those beats.

## Recommendations

- Perform a manual side-by-side review of:
  - `target/s3k-cnz-visual/reference/`
  - `target/s3k-cnz-visual/engine/`
  This is the remaining step needed to upgrade the `LIKELY` rows to `PASS` without changing code.
- Add a dedicated late Act 1 miniboss and Act 2 Knuckles-route `stable-retro` state if repeated CNZ validation is expected.
- Add a dedicated CNZ shake assertion or capture harness once the exact CNZ invocation point for `ShakeScreen_Setup` is pinned to a reproducible runtime beat.
