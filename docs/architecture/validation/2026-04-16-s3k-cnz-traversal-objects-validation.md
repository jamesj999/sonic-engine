# S3K CNZ Traversal Objects Validation

Validation date: `2026-04-17`

Regression gate run:

```bash
mvn -q "-Dtest=TestCnzTraversalRegistry,TestCnzTraversalObjectArt,TestSonic3kObjectArtProvider,TestSonic3kCnzRuntimeStateRegistration,TestSonic3kCnzScroll,TestS3kCnzWaterHelpersHeadless,TestS3kCnzTeleporterRouteHeadless,TestS3kCnzLocalTraversalHeadless,TestS3kCnzDirectedTraversalHeadless,TestS3kCnzTubeTraversalHeadless" "-Ds3k.rom.path=Sonic and Knuckles & Sonic 3 (W) [!].gen" test
```

Observed result: `MSE:OK modules=1 passed=68 failed=0 errors=0 skipped=0`

Visual capture run:

```bash
mvn -q "-Dtest=TestS3kCnzVisualCapture" "-Ds3k.rom.path=Sonic and Knuckles & Sonic 3 (W) [!].gen" test
```

Observed result: `MSE:OK modules=1 passed=69 failed=0 errors=0 skipped=0`

Engine PNG inventory generated under `target/s3k-cnz-visual/engine/`:
- `cnz_hover_fan.png`
- `cnz_cannon.png`
- `cnz_cylinder.png`
- `cnz_vacuum_tube.png`
- `cnz_spiral_tube.png`

Reference PNG inventory currently available under `target/s3k-cnz-visual/reference/` remains limited to the previously exported CNZ act-start frames:
- `cnz_act1_start_f000.png`
- `cnz_act1_start_f015.png`
- `cnz_act1_start_f030.png`
- `cnz_act1_start_f045.png`
- `cnz_act2_start_f000.png`
- `cnz_act2_start_f015.png`
- `cnz_act2_start_f030.png`
- `cnz_act2_start_f045.png`

Because this pass does not yet have stable-retro traversal-state captures for the individual gimmicks, the object-specific rows below are intentionally conservative. `LIKELY` means the engine beat was captured successfully and the corresponding headless/registry/art regressions are green, but this pass did not include a direct ROM screenshot pair for that exact traversal moment. `SKIP` means this pass did not generate a dedicated engine beat for that row and no suitable ROM reference frame exists in the current inventory.

| Beat | ROM anchor | Engine trigger | Result | Notes |
|------|------------|----------------|--------|-------|
| Balloon launch arc | `Obj_CNZBalloon` | Spawn at the first Act 1 balloon and land on it | SKIP | Balloon behavior is covered by `TestS3kCnzLocalTraversalHeadless`, but this validation pass did not add a dedicated balloon PNG and the current stable-retro inventory has only act-start frames. |
| Rising platform travel limit | `Obj_CNZRisingPlatform` | Stand on subtype-driven platform until it stops | SKIP | Rising-platform behavior is covered by `TestS3kCnzLocalTraversalHeadless`, but there is no direct engine-vs-ROM traversal screenshot pair in the current capture set. |
| Trap door open/close cadence | `Obj_CNZTrapDoor` | Stand on the door and wait through one cycle | SKIP | Trap-door cadence is covered headlessly, but this pass did not add a dedicated visual beat and no matching ROM traversal capture is present. |
| Hover fan push window | `Obj_CNZHoverFan` | Enter and leave the force window in Act 1 | LIKELY | Engine capture `cnz_hover_fan.png` exported successfully. Hover-fan subtype/window parity is covered by `TestS3kCnzLocalTraversalHeadless`, but there is no paired stable-retro traversal screenshot for this exact beat yet. |
| Cannon capture and launch angle | `Obj_CNZCannon` | Trigger one representative cannon subtype | LIKELY | Engine capture `cnz_cannon.png` exported successfully. `TestS3kCnzDirectedTraversalHeadless` and `TestCnzTraversalObjectArt` pass, but the current reference inventory does not contain a direct ROM cannon capture/launch frame. |
| Cylinder capture and release | `Obj_CNZCylinder` | Trigger one representative cylinder subtype | LIKELY | Engine capture `cnz_cylinder.png` exported successfully. Cylinder route/release parity is covered by `TestS3kCnzDirectedTraversalHeadless`, but this pass has no matching stable-retro cylinder traversal frame. |
| Vacuum tube transport | `Obj_CNZVacuumTube` | Trigger one representative route | LIKELY | Engine capture `cnz_vacuum_tube.png` exported successfully. Vacuum-tube subtype `0x00/0x10/0x20/0x30` behavior is covered by `TestS3kCnzTubeTraversalHeadless`, but no direct ROM traversal screenshot pair was available in this pass. |
| Spiral tube transport | `Obj_CNZSpiralTube` | Trigger one representative route | LIKELY | Engine capture `cnz_spiral_tube.png` exported successfully. The S&K-side `off_33320` route family and same-frame release semantics are covered by `TestS3kCnzTubeTraversalHeadless`, but the current reference inventory lacks a matching stable-retro spiral traversal capture. |
