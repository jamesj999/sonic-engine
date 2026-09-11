# Services Migration Cleanup Design

**Date:** 2026-03-22
**Branch:** `feature/ai-common-utility-refactors`
**Status:** Approved

## Context

The two-tier service architecture refactor migrated 308 object files from `GameServices`/`getInstance()` singleton lookups to the injectable `services()` pattern via `ObjectServices`. This cleanup resolves the remaining issues: 3 layering violations in the game-agnostic `level/objects` package, coverage gaps in `ObjectServices`, and ~97 mechanical migration candidates in instance methods.

## Scope

### In Scope

1. Fix layering violations in `level/objects`
2. Expand `ObjectServices` with zone feature methods
3. Migrate remaining `GameServices.*` instance-method calls to `services()`

### Out of Scope

- `DefaultPowerUpSpawner` game-specific imports (accepted as documented bridge)
- Constructor/factory/static context calls (cannot use `services()`)
- `GameServices.rom()` calls (ROM is global, not context-specific)
- Level-transition methods (`requestZoneAndAct`, `requestCreditsTransition`, `invalidateForegroundTilemap`, `advanceZoneActOnly`) — these are global operations
- `GameServices.level().getCurrentLevelMusicId()` — single call in `Sonic2ARZBossInstance`, used during boss defeat to restore zone music; this is a level-transition-adjacent operation
- Non-ObjectInstance classes (HudRenderManager, etc.)

## Design

### 1. Layering Violation Fixes

#### AbstractBossInstance: Remove Sonic2Sfx Import

**File:** `level/objects/boss/AbstractBossInstance.java`

`AbstractBossInstance` imports `com.openggf.game.sonic2.audio.Sonic2Sfx` to provide default SFX IDs for `getBossHitSfxId()` and `getBossExplosionSfxId()`.

**Fix:** Make both methods abstract. This removes the S2-specific import from the game-agnostic base class.

```java
// Before (level/objects/boss/AbstractBossInstance.java)
protected int getBossHitSfxId() {
    return Sonic2Sfx.BOSS_HIT.id;      // 0xAC
}
protected int getBossExplosionSfxId() {
    return Sonic2Sfx.BOSS_EXPLOSION.id; // 0xC4
}

// After
protected abstract int getBossHitSfxId();
protected abstract int getBossExplosionSfxId();
```

**Affected concrete subclasses** (must add overrides):

| Class | Hierarchy | Returns |
|-------|-----------|---------|
| `AbstractS1EggmanBossInstance` | abstract, extends AbstractBossInstance | `Sonic1Sfx` equivalents |
| `Sonic1FZBossInstance` | extends AbstractBossInstance directly | `Sonic1Sfx` equivalents |
| `Sonic2EHZBossInstance` | extends AbstractBossInstance | `Sonic2Sfx.BOSS_HIT.id` / `Sonic2Sfx.BOSS_EXPLOSION.id` |
| `Sonic2CPZBossInstance` | extends AbstractBossInstance | same |
| `Sonic2HTZBossInstance` | extends AbstractBossInstance | same |
| `Sonic2ARZBossInstance` | extends AbstractBossInstance | same |
| `Sonic2CNZBossInstance` | extends AbstractBossInstance | same |
| `Sonic2MCZBossInstance` | extends AbstractBossInstance | same |
| `Sonic2MTZBossInstance` | extends AbstractBossInstance | same |
| `Sonic2WFZBossInstance` | extends AbstractBossInstance | same |
| `Sonic2MechaSonicInstance` | extends AbstractBossInstance | same |
| `Sonic2DeathEggRobotInstance` | extends AbstractBossInstance | same |
| `AizMinibossInstance` | extends AbstractBossInstance | `Sonic3kSfx` equivalents |
| `AizMinibossCutsceneInstance` | extends AbstractBossInstance | already overrides `getBossHitSfxId()` |

S1 zone bosses (GHZ, MZ, SYZ, SLZ, LZ) extend `AbstractS1EggmanBossInstance`, so implementing the methods there covers all 5.

#### ObjectRenderManager: Remove Sonic2ObjectArtKeys Import

**File:** `level/objects/ObjectRenderManager.java`

Remove convenience methods `getEHZBossRenderer()` and `getEHZBossSheet()`. The 6 callers (all in `game/sonic2/objects/bosses/`) should use `services().renderManager().getRenderer(Sonic2ObjectArtKeys.EHZ_BOSS)` and `services().renderManager().getSheet(Sonic2ObjectArtKeys.EHZ_BOSS)` instead, using the existing key-based `getRenderer(String)` / `getSheet(String)` methods on `ObjectRenderManager`.

#### DefaultPowerUpSpawner: Document Bridge Role

Add a class-level comment documenting the intentional bridge role and why the game-specific imports are accepted. No structural change.

### 2. ObjectServices Expansion

Add 3 methods to `ObjectServices` and `DefaultObjectServices`:

```java
// In ObjectServices interface (level/objects/ObjectServices.java)
// New import: com.openggf.game.ZoneFeatureProvider (consistent with existing game package imports)
int featureZoneId();
int featureActId();
ZoneFeatureProvider zoneFeatureProvider();

// In DefaultObjectServices (level/objects/DefaultObjectServices.java)
// New import: com.openggf.game.ZoneFeatureProvider
@Override
public int featureZoneId() {
    return LevelManager.getInstance().getFeatureZoneId();
}

@Override
public int featureActId() {
    return LevelManager.getInstance().getFeatureActId();
}

@Override
public ZoneFeatureProvider zoneFeatureProvider() {
    return LevelManager.getInstance().getZoneFeatureProvider();
}
```

These are context-specific values that would differ in a level editor context. The `level.objects` -> `game` import direction is consistent with existing imports (`GameStateManager`, `LevelState`, `RespawnState`, `PlayableEntity`).

### 3. Mechanical Migration

Migrate remaining `GameServices.*` calls in object instance methods to `services()` equivalents:

| Old Pattern | New Pattern |
|-------------|------------|
| `GameServices.level().getObjectManager()` | `services().objectManager()` |
| `GameServices.level().getObjectRenderManager()` | `services().renderManager()` |
| `GameServices.level().getRomZoneId()` | `services().romZoneId()` |
| `GameServices.level().getCurrentAct()` | `services().currentAct()` |
| `GameServices.level().getCurrentLevel()` | `services().currentLevel()` |
| `GameServices.level().getLevelGamestate()` | `services().levelGamestate()` |
| `GameServices.level().getCheckpointState()` | `services().checkpointState()` |
| `GameServices.level().getFeatureZoneId()` | `services().featureZoneId()` |
| `GameServices.level().getFeatureActId()` | `services().featureActId()` |
| `GameServices.level().getZoneFeatureProvider()` | `services().zoneFeatureProvider()` |
| `GameServices.audio().playSfx(...)` | `services().playSfx(...)` |
| `GameServices.audio().playMusic(...)` | `services().playMusic(...)` |
| `GameServices.audio().fadeOutMusic()` | `services().fadeOutMusic()` |
| `GameServices.camera()` | `services().camera()` |
| `GameServices.gameState()` | `services().gameState()` |

**Exclusion rules** — do NOT migrate calls in:
- Constructor bodies (`services()` not yet injected)
- Factory/registry lambdas (no instance context)
- Static field initializers
- Non-ObjectInstance classes

## Commit Structure

Matches the existing branch commit pattern:

1. `refactor: fix layering violations in level/objects base classes`
   - `level/objects/boss/AbstractBossInstance.java`: abstract SFX methods, remove Sonic2Sfx import
   - `level/objects/ObjectRenderManager.java`: remove EHZ convenience methods, remove Sonic2ObjectArtKeys import
   - `level/objects/DefaultPowerUpSpawner.java`: add bridge documentation
   - All boss subclasses: add `getBossHitSfxId()` / `getBossExplosionSfxId()` overrides
   - EHZ boss child objects: replace `renderManager.getEHZBossRenderer()` with `services().renderManager().getRenderer(Sonic2ObjectArtKeys.EHZ_BOSS)`
2. `feat: expand ObjectServices with zone feature methods`
   - Add `featureZoneId()`, `featureActId()`, `zoneFeatureProvider()` to interface + default impl
3. `refactor: migrate remaining level/objects GameServices calls to services()`
4. `refactor: migrate remaining S1 object GameServices calls to services()`
5. `refactor: migrate remaining S2 object GameServices calls to services()`
6. `refactor: migrate remaining S3K object GameServices calls to services()`

## Post-Migration State

After completion, `GameServices` calls in object files will only remain in justified contexts:

| Category | Approx Calls | Rationale |
|----------|-------------|-----------|
| Constructor/factory context | ~50 | `services()` unavailable at construction time |
| `GameServices.rom()` | ~16 | ROM is a global resource |
| `GameServices.debugOverlay()` | ~7 | Static field initializers |
| Level-transition methods | ~10 | Global operations, not context-specific |
| Non-ObjectInstance classes | ~5 | Not injected with ObjectServices |

## Verification

- `mvn compile` must pass after each commit
- `mvn test` must pass after final commit
- No new `GameServices.*` imports introduced in `level/objects` (except DefaultPowerUpSpawner bridge)
- All existing tests remain green
