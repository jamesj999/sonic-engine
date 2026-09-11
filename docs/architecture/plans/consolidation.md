# Consolidation Plan

## Audit Summary
- 41 `*Manager` classes in `src/main/java`.
- Clusters:
  - Core services: `AudioManager`, `TimerManager`, `GameStateManager`, `RomManager`, `DebugOverlayManager`, `ParallaxManager`, `TerrainCollisionManager`.
  - Level/object systems: `LevelManager` plus object/ring/plane/solid/touch/HUD managers.
  - Sprite systems: sprite registry/render/collision + per-sprite helpers.
  - Sonic 2 subsystems: level events, oscillation, palette/pattern animation, water surface, title card, special stage.

## Low-Risk Consolidations (Targeted First)
- **Ring subsystem:** Merge `RingPlacementManager`, `RingRenderManager`, and `LostRingManager` into a single `RingManager` with internal components (spawn window + renderer + lost ring pool).
- **CNZ bumpers:** Merge `CNZBumperPlacementManager` + `CNZBumperCollisionManager` into a single `CNZBumperManager`.
- **Sonic 2 level animation:** Replace `Sonic2AnimatedPatternManager` + `Sonic2PaletteCycleManager` with a single `Sonic2LevelAnimationManager` (implements both interfaces).
- **Special stage objects:** Fold `Sonic2SpecialStageObjectManager` into `Sonic2SpecialStageManager` as a private nested class.
- **Note:** `ObjectRenderManager` consolidation is deferred to medium-risk due to widespread usage across object instances.

## Medium-Risk Consolidations (After Test Coverage)
- **Object system:** Merge `ObjectManager`, `ObjectPlacementManager`, `SolidObjectManager`, `TouchResponseManager`, and `PlaneSwitcherManager` into a unified `ObjectSystem` with clear update phases.
- **Sprite system:** Merge `SpriteManager`, `SpriteRenderManager`, and `SpriteCollisionManager` into a `SpriteSystem`.
- **Per-sprite controllers:** Consolidate `PlayableSpriteMovementManager`, `PlayableSpriteAnimationManager`, `SpindashDustManager`, and `DrowningManager` into a single per-sprite controller owned by `AbstractPlayableSprite`.
- **Core services:** Provide an `EngineContext`/`GameServices` facade for `GameStateManager`, `TimerManager`, `RomManager`, and `DebugOverlayManager`.

## High-Risk Consolidations ✅ COMPLETED
### Collision Pipeline Unification ✅
- **Status:** Infrastructure complete, ready for incremental migration
- **Implemented:** `CollisionSystem` orchestrator with 3-phase pipeline (terrain → solid → post)
- **Testing:** `CollisionTrace` interface with `RecordingCollisionTrace` and `NoOpCollisionTrace`
- **Files created:**
  - `physics/CollisionSystem.java` – Main orchestrator
  - `physics/CollisionTrace.java` – Trace interface
  - `physics/CollisionEvent.java` – Event record
  - `physics/RecordingCollisionTrace.java` – For testing
  - `physics/NoOpCollisionTrace.java` – Production no-op
- **Tests:** `CollisionSystemTest.java` with 17 tests covering traces, flags, null handling

### Rendering/UI Pipeline Unification ✅
- **Status:** Infrastructure complete, wired to GraphicsManager and LevelManager
- **Implemented:** `UiRenderPipeline` with scene → overlay → fade ordering
- **Testing:** `RenderOrderRecorder` for compliance testing
- **Files created:**
  - `graphics/pipeline/UiRenderPipeline.java` – Main orchestrator
  - `graphics/pipeline/RenderPhase.java` – Phase enum
  - `graphics/pipeline/RenderCommand.java` – Command record
  - `graphics/pipeline/RenderOrderRecorder.java` – Order verification
- **Tests:** `RenderOrderTest.java` (17 tests), `FadeManagerTest.java` (35 tests)

## Test Plan
### Medium-Risk Coverage
- Object placement windowing and remembered objects (spawn/trim behavior).
- Object lifecycle: persistent vs non-persistent unload rules.
- Sprite render bucket ordering and high/low priority separation.
- Sprite collision update behavior with debug toggle + input edge cases.

### High-Risk Coverage ✅ COMPLETED
- ✅ `FadeManager` state machine (white/black fade sequences + hold) – 35 tests
- ✅ `CollisionSystem` trace recording and comparison – 17 tests
- ✅ `RenderOrderRecorder` render order verification – 17 tests
- Optional: solid object standing contact/headroom edge cases with stub objects.

## Next Steps for Full Migration
The infrastructure is in place. To complete the migration:

1. **Enable shadow mode** in development to run both pipelines and compare traces
2. **Add micro-scenario fixtures** for edge cases (platform+slope, headroom, ride transitions)
3. **Migrate terrain probing** into CollisionSystem by updating PlayableSpriteMovement to use CollisionSystem.step()
4. **Migrate solid contact resolution** by moving the call from ObjectManager.update() to CollisionSystem
5. **Enable unified pipeline** once all trace comparisons pass


