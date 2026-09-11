# Common Utility Extractions Design

## Overview

Extract duplicated patterns across the codebase into reusable utilities in a new `com.openggf.util` package, plus migrate legacy objects to existing infrastructure where it already exists.

## Scope

**4 new utility classes** in `com.openggf.util`:
1. `PatternDecompressor` — ROM decompression → Pattern[] conversion
2. `LazyMappingHolder` — Lazy-loading guard for static sprite mappings
3. `FboHelper` — OpenGL framebuffer object creation/destruction
4. `AnimationTimer` — Frame-cycling animation timer

**2 migration tasks** (no new classes):
5. Migrate legacy `appendDebug(List<GLCommand>)` objects → existing `DebugRenderContext` API
6. Migrate manual off-screen checks → existing `AbstractObjectInstance.isOnScreen(margin)`

## Game-Specific Divergences

Each extraction was audited for S1/S2/S3K differences:

- **PatternDecompressor:** The `bytes → Pattern[]` core is identical across all three games. Divergences exist only in *how* bytes are obtained (S1: ByteArrayInputStream; S2: synchronized FileChannel; S3K: FileChannel). The utility handles only the shared core. Callers retain game-specific channel/sync logic.
- **DebugRenderContext (migration):** S2 badniks already use `DebugRenderContext`. S2 platforms and S3K objects still use raw `GLCommand` construction. Migration unifies both onto the existing API.
- **FboHelper:** TilePriorityFBO uses `GL_CLAMP_TO_EDGE`; background renderers use `GL_REPEAT`. The helper accepts `wrapMode` as a parameter.
- **OffscreenHelper (migration):** Objects already have `isOnScreen(margin)` on the base class with cached CameraBounds. Legacy objects with manual `Camera.getInstance().getY() + 224 + 128` patterns are migrated to use it.
- **LazyMappingHolder:** No game divergence. The functional interface (`MappingLoader`) accepts game-specific loader methods.
- **AnimationTimer:** No game divergence. Pure mechanical boilerplate.

---

## 1. PatternDecompressor

**Package:** `com.openggf.util`

**Purpose:** Eliminate the identical 7-line decompression→Pattern[] loop duplicated in 7+ art loading files across all three games.

### API

```java
public final class PatternDecompressor {

    /**
     * Core: convert raw decompressed bytes into Pattern[].
     * Replaces the loop duplicated in every art loader.
     */
    public static Pattern[] fromBytes(byte[] data);

    /**
     * Bounded variant: convert at most maxCount patterns.
     * Used by S3K ring art (combined ring + HUD data, only want ring portion).
     */
    public static Pattern[] fromBytes(byte[] data, int maxCount);

    /**
     * Convenience: decompress Nemesis data from ROM and convert to Pattern[].
     * Uses rom.getFileChannel() without internal synchronization.
     * Callers that share a Rom across threads (e.g. Sonic2ObjectArt) must
     * synchronize on the Rom instance externally.
     */
    public static Pattern[] nemesis(Rom rom, int address) throws IOException;

    /**
     * Convenience: decompress Kosinski data from ROM and convert to Pattern[].
     * Same synchronization caveat as nemesis().
     */
    public static Pattern[] kosinski(Rom rom, int address) throws IOException;

    /**
     * Convenience: decompress Kosinski Moduled data from ROM and convert to Pattern[].
     * Same synchronization caveat as nemesis().
     */
    public static Pattern[] kosinskiModuled(Rom rom, int address) throws IOException;
}
```

### Call site impact

**Before (repeated in 7+ files):**
```java
FileChannel channel = rom.getFileChannel();
channel.position(address);
byte[] result = NemesisReader.decompress(channel);
int patternCount = result.length / Pattern.PATTERN_SIZE_IN_ROM;
Pattern[] patterns = new Pattern[patternCount];
for (int i = 0; i < patternCount; i++) {
    patterns[i] = new Pattern();
    byte[] subArray = Arrays.copyOfRange(result,
        i * Pattern.PATTERN_SIZE_IN_ROM,
        (i + 1) * Pattern.PATTERN_SIZE_IN_ROM);
    patterns[i].fromSegaFormat(subArray);
}
```

**After:**
```java
Pattern[] patterns = PatternDecompressor.nemesis(rom, address);
```

### Files to update
- `Sonic1ObjectArt.java` — `loadNemesisPatterns()`
- `Sonic2ObjectArt.java` — `loadNemesisPatterns()` (must preserve `synchronized(rom)` block externally)
- `Sonic3kObjectArt.java` — `loadNemesisPatterns()`, `bytesToPatterns()`, KosM variants
- `Sonic1RingArt.java` — `loadRingPatterns()`
- `Sonic2RingArt.java` — `loadRingPatterns()`
- `Sonic3kRingArt.java` — `loadRingPatterns()` (uses bounded variant: `fromBytes(data, RING_PATTERN_COUNT)` because S3K decompresses combined ring + HUD art but only needs the ring portion)

### S2 synchronization note
`Sonic2ObjectArt.loadNemesisPatterns()` currently wraps decompression in `synchronized(rom)`. After migration:
```java
synchronized (rom) {
    patterns = PatternDecompressor.nemesis(rom, artAddr);
}
```
The `synchronized` block moves to wrap the PatternDecompressor call. Verify all S2 call sites preserve this.

---

## 2. LazyMappingHolder

**Package:** `com.openggf.util`

**Purpose:** Eliminate the 15-line `ensureMappingsLoaded()` boilerplate duplicated in 10+ object instance classes.

### API

```java
/**
 * Lazy-loading holder for static sprite mapping data.
 * Not thread-safe; assumes single-threaded game loop access.
 * Load is attempted only once per instance. If loading fails (IOException,
 * LevelManager not initialized, etc.), subsequent calls return an empty list
 * without retrying — matching the existing ensureMappingsLoaded() contract.
 */
public final class LazyMappingHolder {

    private List<SpriteMappingFrame> mappings;
    private boolean attempted;

    /**
     * Returns cached mappings, loading on first access.
     * Handles LevelManager null check, RomByteReader creation,
     * try/catch with logging internally.
     * Returns empty list if load was attempted but failed.
     *
     * @param mappingAddr ROM address of the mapping table
     * @param loader      Game-specific loader (e.g. S2SpriteDataLoader::loadMappingFrames)
     * @param label       Object label for log messages (e.g. "Obj2B")
     */
    public List<SpriteMappingFrame> get(int mappingAddr, MappingLoader loader, String label);

    @FunctionalInterface
    public interface MappingLoader {
        List<SpriteMappingFrame> load(RomByteReader reader, int addr) throws IOException;
    }
}
```

### Call site impact

**Before (repeated in 10+ files):**
```java
private static List<SpriteMappingFrame> mappings;
private static boolean mappingLoadAttempted;

private static void ensureMappingsLoaded() {
    if (mappingLoadAttempted) return;
    mappingLoadAttempted = true;
    LevelManager manager = LevelManager.getInstance();
    if (manager == null || manager.getGame() == null) return;
    try {
        Rom rom = manager.getGame().getRom();
        RomByteReader reader = RomByteReader.fromRom(rom);
        mappings = S2SpriteDataLoader.loadMappingFrames(reader, Sonic2Constants.MAP_UNC_OBJ2B_ADDR);
        LOGGER.fine("Loaded " + mappings.size() + " Obj2B mapping frames");
    } catch (IOException | RuntimeException e) {
        LOGGER.warning("Failed to load Obj2B mappings: " + e.getMessage());
    }
}
```

**After:**
```java
private static final LazyMappingHolder MAPPINGS = new LazyMappingHolder();

// At call site:
List<SpriteMappingFrame> frames = MAPPINGS.get(
    Sonic2Constants.MAP_UNC_OBJ2B_ADDR,
    S2SpriteDataLoader::loadMappingFrames,
    "Obj2B");
```

### Files to update
- `RisingPillarObjectInstance.java`
- `FallingPillarObjectInstance.java`
- `SwingingPlatformObjectInstance.java` (4 separate holders)
- `MCZBrickObjectInstance.java`
- `ARZPlatformObjectInstance.java`
- `SignpostObjectInstance.java`
- `LargeRotPformObjectInstance.java`
- `SidewaysPformObjectInstance.java`
- Plus other object instances with the same pattern

---

## 3. FboHelper

**Package:** `com.openggf.util`

**Purpose:** Eliminate the duplicated 15-20 line FBO creation/teardown sequence across 4 renderer classes.

### API

```java
public final class FboHelper {

    /**
     * Holds the GL resource IDs for a framebuffer object.
     * depthId = 0 means no depth buffer.
     */
    public record FboHandle(int fboId, int textureId, int depthId) {
        public boolean hasDepth() { return depthId != 0; }
    }

    /**
     * Create color-only FBO (no depth buffer).
     * @param wrapMode GL_REPEAT or GL_CLAMP_TO_EDGE
     */
    public static FboHandle createColorOnly(int width, int height, int wrapMode);

    /**
     * Create FBO with color + depth attachments.
     * @param wrapMode GL_REPEAT or GL_CLAMP_TO_EDGE
     */
    public static FboHandle createWithDepth(int width, int height, int wrapMode);

    /**
     * Delete all GL resources held by the handle.
     */
    public static void destroy(FboHandle handle);

    /**
     * Save current viewport. Returns 4-element array [x, y, w, h].
     */
    public static int[] saveViewport();

    /**
     * Restore viewport from a previously saved array.
     */
    public static void restoreViewport(int[] saved);
}
```

### Texture parameters
All FBOs use `GL_RGBA8` internal format for cross-platform compatibility (macOS OpenGL 4.1 requires RGBA8; TilePriorityFBO was changed from GL_R8 for this reason). All use `GL_NEAREST` min/mag filtering. The `wrapMode` parameter accommodates:
- `GL_REPEAT` — BackgroundRenderer, SpecialStageBackgroundRenderer, Sonic1SpecialStageBackgroundRenderer
- `GL_CLAMP_TO_EDGE` — TilePriorityFBO

### Call site impact

**Before (repeated in 4 files):**
```java
fboId = glGenFramebuffers();
fboTextureId = glGenTextures();
glBindTexture(GL_TEXTURE_2D, fboTextureId);
glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, width, height, 0, GL_RGBA, GL_UNSIGNED_BYTE, (ByteBuffer) null);
glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_REPEAT);
glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_REPEAT);
glBindTexture(GL_TEXTURE_2D, 0);
fboDepthId = glGenRenderbuffers();
glBindRenderbuffer(GL_RENDERBUFFER, fboDepthId);
glRenderbufferStorage(GL_RENDERBUFFER, GL_DEPTH_COMPONENT16, width, height);
glBindFramebuffer(GL_FRAMEBUFFER, fboId);
glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, fboTextureId, 0);
glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_RENDERBUFFER, fboDepthId);
glBindFramebuffer(GL_FRAMEBUFFER, 0);
```

**After:**
```java
FboHandle fbo = FboHelper.createWithDepth(width, height, GL_REPEAT);
```

### Files to update
- `TilePriorityFBO.java` — `createColorOnly(..., GL_CLAMP_TO_EDGE)`
- `BackgroundRenderer.java` — `createWithDepth(..., GL_REPEAT)`
- `SpecialStageBackgroundRenderer.java` — `createWithDepth(..., GL_REPEAT)`
- `Sonic1SpecialStageBackgroundRenderer.java` — `createWithDepth(..., GL_REPEAT)`

---

## 4. AnimationTimer

**Package:** `com.openggf.util`

**Purpose:** Replace the manual timer increment + modulo frame cycling boilerplate in 25+ objects.

### API

```java
public final class AnimationTimer {

    private int timer;
    private int frame;
    private final int duration;
    private final int frameCount;

    /**
     * @param duration  Frames between animation advances (matches ANIM_DURATION constants)
     * @param frameCount Total animation frames (drives modulo wrap)
     */
    public AnimationTimer(int duration, int frameCount);

    /**
     * Advance timer by one frame.
     * @return true if the animation frame changed this tick
     */
    public boolean tick();

    public int getFrame();
    public void reset();

    /**
     * Set frame directly for external state transitions.
     * Caller must ensure 0 <= frame < frameCount.
     */
    public void setFrame(int frame);
}
```

### Call site impact

**Before (repeated in 25+ files):**
```java
private int walkAnimTimer;
private int walkAnimFrame;

walkAnimTimer++;
if (walkAnimTimer >= WALK_ANIM_DURATION) {
    walkAnimTimer = 0;
    walkAnimFrame = (walkAnimFrame + 1) % 3;
}
animFrame = FRAME_WALK_1 + walkAnimFrame;
```

**After:**
```java
private final AnimationTimer walkAnim = new AnimationTimer(3, 3);

walkAnim.tick();
animFrame = FRAME_WALK_1 + walkAnim.getFrame();
```

### Files to update (representative, not exhaustive)
- `GrounderBadnikInstance.java`
- `CrawlBadnikInstance.java`
- `SpikerBadnikInstance.java`
- `NebulaBadnikInstance.java`
- `SignpostObjectInstance.java`
- Other badniks/objects with manual animation timers

---

## 5. Migration: Legacy Debug Rendering → DebugRenderContext

**No new class.** Migrate objects still using raw `appendDebug(List<GLCommand>)` + private `appendLine()` to the existing `DebugRenderContext` API (`com.openggf.debug`).

### Existing API (already in codebase)
```java
// DebugRenderContext already provides:
ctx.drawLine(x1, y1, x2, y2, r, g, b);
ctx.drawRect(cx, cy, halfW, halfH, r, g, b);
ctx.drawCross(x, y, size, r, g, b);
ctx.drawArrow(x1, y1, x2, y2, r, g, b);
```

### Migration pattern
Each legacy object:
1. Change method signature from `appendDebug(List<GLCommand> commands)` to `appendDebugRenderCommands(DebugRenderContext ctx)` (matching the interface S2 badniks already use)
2. Replace `appendLine(commands, x1, y1, x2, y2, r, g, b)` with `ctx.drawLine(x1, y1, x2, y2, r, g, b)`
3. Replace manual box-drawing (4 appendLine calls) with `ctx.drawRect(cx, cy, halfW, halfH, r, g, b)`
4. Delete the private `appendLine()` method

### Files to update
- S2 platforms: `RisingPillarObjectInstance`, `FallingPillarObjectInstance`, `ARZPlatformObjectInstance`, `SwingingPlatformObjectInstance`, `MCZBrickObjectInstance`, `LargeRotPformObjectInstance`
- S3K objects: `AizFallingLogObjectInstance`, `FloatingPlatformObjectInstance`, other S3K objects with `appendDebug`

---

## 6. Migration: Manual Off-Screen Checks → isOnScreen(margin)

**No new class.** Migrate objects using manual `Camera.getInstance().getY() + 224 + 128` patterns to the existing `AbstractObjectInstance.isOnScreen(margin)` method which uses pre-cached `CameraBounds`.

### Existing API (already in codebase)
```java
// From AbstractObjectInstance:
// Uses pre-computed CameraBounds (updated once per frame by ObjectManager).
// margin = pixels of extra space beyond camera bounds, applied to all four sides.
protected boolean isOnScreen(int margin) {
    return cameraBounds.contains(getX(), getY(), margin);
}
```

**Note:** `isOnScreen(margin)` checks all four sides (X and Y), while many legacy patterns only check Y (below screen). This is a safe superset — an object that was only cleaned up when falling below screen will now also be cleaned up when going far left/right/above. This matches the ROM's `MarkObjGone` behaviour which also checks both axes. Legacy patterns that intentionally check only Y (rare) should be reviewed individually.

### Migration pattern
**Before:**
```java
Camera camera = Camera.getInstance();
int screenBottom = camera.getY() + 224 + 128;
if (y > screenBottom) {
    setDestroyed(true);
}
```

**After:**
```java
if (!isOnScreen(128)) {
    setDestroyed(true);
}
```

### Files to update
- `RisingPillarObjectInstance.java` (debris cleanup)
- `RisingPillarDebrisInstance` (inner class)
- `FallingPillarObjectInstance.java`
- Other objects with manual Camera.getInstance() off-screen checks

---

## Implementation Order

1. **PatternDecompressor** — cleanest extraction, no behavioral risk
2. **LazyMappingHolder** — self-contained, straightforward
3. **FboHelper** — isolated to 4 renderer files
4. **AnimationTimer** — simple, high-volume
5. **DebugRenderContext migration** — uses existing API, mechanical changes
6. **isOnScreen() migration** — uses existing API, mechanical changes

## Testing Strategy

- **PatternDecompressor:** Unit test `fromBytes()` with known pattern data. Unit test bounded variant. After refactoring all art loaders, run full `mvn test` suite to verify all three games produce identical pattern data.
- **LazyMappingHolder:** Unit test the guard behavior (load-once semantics, null LevelManager returns empty list, failure returns empty list without retry).
- **FboHelper:** Requires OpenGL context — verify via existing headless rendering tests or manual testing.
- **AnimationTimer:** Unit test `tick()` return values, frame cycling, reset, and setFrame.
- **Migrations:** Existing tests cover the objects being migrated. No new tests needed — just verify existing tests stay green.
- **Full regression:** After each extraction, run `mvn test` to catch any behavioral changes across S1/S2/S3K.
