# Common Utility Extractions Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Extract 4 reusable utility classes into `com.openggf.util` and migrate legacy objects to existing infrastructure, reducing duplication across 50+ files.

**Architecture:** New `com.openggf.util` package with focused, single-purpose utility classes. Each utility is TDD'd independently, then call sites are migrated. Two migrations use existing infrastructure (DebugRenderContext, isOnScreen) with no new classes.

**Tech Stack:** Java 21, LWJGL/OpenGL (FboHelper), JUnit (tests)

**Spec:** `docs/architecture/designs/2026-03-20-common-utility-extractions-design.md`

---

## File Structure

**New files:**
- `src/main/java/com/openggf/util/PatternDecompressor.java` — bytes→Pattern[] + ROM convenience methods
- `src/main/java/com/openggf/util/LazyMappingHolder.java` — lazy-loading holder with functional interface
- `src/main/java/com/openggf/util/FboHelper.java` — FBO creation/destruction + viewport save/restore
- `src/main/java/com/openggf/util/AnimationTimer.java` — frame-cycling timer
- `src/test/java/com/openggf/util/TestPatternDecompressor.java`
- `src/test/java/com/openggf/util/TestLazyMappingHolder.java`
- `src/test/java/com/openggf/util/TestAnimationTimer.java`

**Modified files (by task):**
- Task 1: PatternDecompressor → 6 art loader files
- Task 2: LazyMappingHolder → 8 object instance files
- Task 3: FboHelper → 4 renderer files
- Task 4: AnimationTimer → representative badnik files (not exhaustive migration)
- Task 5: DebugRenderContext migration → ~38 object files with `appendDebug(List<GLCommand>)`
- Task 6: isOnScreen migration → ~3 object files with manual camera math

---

### Task 1: PatternDecompressor

**Files:**
- Create: `src/main/java/com/openggf/util/PatternDecompressor.java`
- Create: `src/test/java/com/openggf/util/TestPatternDecompressor.java`
- Modify: `src/main/java/com/openggf/game/sonic1/Sonic1ObjectArt.java:102-124`
- Modify: `src/main/java/com/openggf/game/sonic2/Sonic2ObjectArt.java:341-413`
- Modify: `src/main/java/com/openggf/game/sonic3k/Sonic3kObjectArt.java:900-943`
- Modify: `src/main/java/com/openggf/game/sonic1/Sonic1RingArt.java:56-74`
- Modify: `src/main/java/com/openggf/game/sonic2/Sonic2RingArt.java:49-67`
- Modify: `src/main/java/com/openggf/game/sonic3k/Sonic3kRingArt.java:60-76`

- [ ] **Step 1: Write failing test for `fromBytes()`**

```java
package com.openggf.util;

import com.openggf.level.Pattern;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class TestPatternDecompressor {

    @Test
    public void testFromBytesEmptyInput() {
        Pattern[] result = PatternDecompressor.fromBytes(new byte[0]);
        assertEquals(0, result.length);
    }

    @Test
    public void testFromBytesNullInput() {
        Pattern[] result = PatternDecompressor.fromBytes(null);
        assertEquals(0, result.length);
    }

    @Test
    public void testFromBytesSinglePattern() {
        // One pattern = 32 bytes (PATTERN_SIZE_IN_ROM)
        // Build a known pattern: top-left pixel = palette index 3, rest transparent
        byte[] data = new byte[Pattern.PATTERN_SIZE_IN_ROM];
        data[0] = 0x30; // First nibble = 3, second nibble = 0
        Pattern[] result = PatternDecompressor.fromBytes(data);
        assertEquals(1, result.length);
        assertEquals(3, result[0].getPixel(0, 0));
        assertEquals(0, result[0].getPixel(1, 0));
    }

    @Test
    public void testFromBytesTwoPatterns() {
        byte[] data = new byte[Pattern.PATTERN_SIZE_IN_ROM * 2];
        data[0] = 0x10; // First pattern: pixel(0,0) = 1
        data[Pattern.PATTERN_SIZE_IN_ROM] = 0x20; // Second pattern: pixel(0,0) = 2
        Pattern[] result = PatternDecompressor.fromBytes(data);
        assertEquals(2, result.length);
        assertEquals(1, result[0].getPixel(0, 0));
        assertEquals(2, result[1].getPixel(0, 0));
    }

    @Test
    public void testFromBytesBounded() {
        byte[] data = new byte[Pattern.PATTERN_SIZE_IN_ROM * 5];
        Pattern[] result = PatternDecompressor.fromBytes(data, 3);
        assertEquals(3, result.length);
    }

    @Test
    public void testFromBytesBoundedExceedingCount() {
        byte[] data = new byte[Pattern.PATTERN_SIZE_IN_ROM * 2];
        Pattern[] result = PatternDecompressor.fromBytes(data, 10);
        assertEquals(2, result.length); // maxCount > available, returns all
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -Dtest=TestPatternDecompressor -pl . -Dmse=off`
Expected: Compilation error — `PatternDecompressor` does not exist

- [ ] **Step 3: Implement PatternDecompressor**

```java
package com.openggf.util;

import com.openggf.level.Pattern;
import com.openggf.data.Rom;
import com.openggf.tools.KosinskiReader;
import com.openggf.tools.NemesisReader;

import java.io.IOException;
import java.util.Arrays;

/**
 * Converts decompressed ROM data into Pattern arrays.
 * <p>
 * The core {@link #fromBytes} methods handle the bytes→Pattern[] conversion
 * that was previously duplicated in every art loader. The convenience methods
 * ({@link #nemesis}, {@link #kosinski}, {@link #kosinskiModuled}) additionally
 * handle ROM channel setup and decompression.
 * <p>
 * These methods use {@code rom.getFileChannel()} without internal synchronization.
 * Callers that share a Rom across threads (e.g. Sonic2ObjectArt) must synchronize
 * on the Rom instance externally.
 */
public final class PatternDecompressor {

    private PatternDecompressor() {}

    public static Pattern[] fromBytes(byte[] data) {
        if (data == null || data.length == 0) {
            return new Pattern[0];
        }
        int count = data.length / Pattern.PATTERN_SIZE_IN_ROM;
        return buildPatterns(data, count);
    }

    public static Pattern[] fromBytes(byte[] data, int maxCount) {
        if (data == null || data.length == 0) {
            return new Pattern[0];
        }
        int available = data.length / Pattern.PATTERN_SIZE_IN_ROM;
        int count = Math.min(available, maxCount);
        return buildPatterns(data, count);
    }

    public static Pattern[] nemesis(Rom rom, int address) throws IOException {
        var channel = rom.getFileChannel();
        channel.position(address);
        byte[] data = NemesisReader.decompress(channel);
        return fromBytes(data);
    }

    public static Pattern[] kosinski(Rom rom, int address) throws IOException {
        var channel = rom.getFileChannel();
        channel.position(address);
        byte[] data = KosinskiReader.decompress(channel);
        return fromBytes(data);
    }

    public static Pattern[] kosinskiModuled(Rom rom, int address) throws IOException {
        var channel = rom.getFileChannel();
        channel.position(address);
        byte[] data = KosinskiReader.decompressModuled(channel);
        return fromBytes(data);
    }

    private static Pattern[] buildPatterns(byte[] data, int count) {
        Pattern[] patterns = new Pattern[count];
        for (int i = 0; i < count; i++) {
            patterns[i] = new Pattern();
            byte[] tile = Arrays.copyOfRange(
                    data,
                    i * Pattern.PATTERN_SIZE_IN_ROM,
                    (i + 1) * Pattern.PATTERN_SIZE_IN_ROM);
            patterns[i].fromSegaFormat(tile);
        }
        return patterns;
    }
}
```

> **API verified:** `KosinskiReader.decompress(ReadableByteChannel)` for standard Kosinski, `KosinskiReader.decompressModuled(ReadableByteChannel)` for moduled. `NemesisReader.decompress(ReadableByteChannel)` for Nemesis. All take `ReadableByteChannel` (which `FileChannel` implements). `rom.getFileChannel()` returns `FileChannel` — call `.position(address)` on it before passing to decompressors.

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn test -Dtest=TestPatternDecompressor -pl . -Dmse=off`
Expected: All 6 tests PASS

- [ ] **Step 5: Commit utility class + tests**

```bash
git add src/main/java/com/openggf/util/PatternDecompressor.java src/test/java/com/openggf/util/TestPatternDecompressor.java
git commit -m "feat: add PatternDecompressor utility for bytes→Pattern[] conversion"
```

- [ ] **Step 6: Migrate Sonic1ObjectArt.loadNemesisPatterns()**

In `src/main/java/com/openggf/game/sonic1/Sonic1ObjectArt.java`, replace the body of `loadNemesisPatterns(int address)` (lines ~102-124) with:

```java
public Pattern[] loadNemesisPatterns(int address) {
    try {
        return PatternDecompressor.nemesis(rom, address);
    } catch (IOException e) {
        LOG.warning("Failed to decompress Nemesis art at 0x"
                + Integer.toHexString(address) + ": " + e.getMessage());
        return new Pattern[0];
    }
}
```

Add import: `import com.openggf.util.PatternDecompressor;`

Remove now-unused imports: `ByteArrayInputStream`, `Channels`, `ReadableByteChannel`, `Arrays` (if unused elsewhere), `NEMESIS_READ_BUFFER_SIZE` constant (if unused elsewhere).

- [ ] **Step 7: Migrate Sonic2ObjectArt.loadNemesisPatterns()**

In `src/main/java/com/openggf/game/sonic2/Sonic2ObjectArt.java`, replace lines ~341-362 with:

```java
private Pattern[] loadNemesisPatterns(int artAddr) throws IOException {
    synchronized (rom) {
        return PatternDecompressor.nemesis(rom, artAddr);
    }
}
```

**Critical:** The `synchronized(rom)` block MUST be preserved — S2 shares the Rom across threads.

Add import: `import com.openggf.util.PatternDecompressor;`

- [ ] **Step 8: Migrate Sonic3kObjectArt**

In `src/main/java/com/openggf/game/sonic3k/Sonic3kObjectArt.java`:

Replace `loadNemesisPatterns()` (lines ~900-905) with:
```java
private Pattern[] loadNemesisPatterns(Rom rom, int addr) throws IOException {
    return PatternDecompressor.nemesis(rom, addr);
}
```

Replace `bytesToPatterns()` (lines ~928-943) with:
```java
private Pattern[] bytesToPatterns(byte[] data) {
    return PatternDecompressor.fromBytes(data);
}
```

Add import: `import com.openggf.util.PatternDecompressor;`

- [ ] **Step 9: Migrate ring art loaders**

**Sonic1RingArt.java** — replace `loadRingPatterns()` (lines ~56-74):
```java
private Pattern[] loadRingPatterns() throws IOException {
    return PatternDecompressor.nemesis(rom, Sonic1Constants.ART_NEM_RING_ADDR);
}
```

**Sonic2RingArt.java** — replace `loadRingPatterns()` (lines ~49-67):
```java
private Pattern[] loadRingPatterns() throws IOException {
    return PatternDecompressor.nemesis(rom, RING_ART_ADDR);
}
```

**Sonic3kRingArt.java** — replace `loadRingPatterns()` (lines ~60-76):
```java
private Pattern[] loadRingPatterns() throws IOException {
    FileChannel channel = rom.getFileChannel();
    channel.position(Sonic3kConstants.ART_NEM_RING_HUD_TEXT_ADDR);
    byte[] result = NemesisReader.decompress(channel);
    return PatternDecompressor.fromBytes(result, RING_PATTERN_COUNT);
}
```

> S3K ring art can't use `PatternDecompressor.nemesis()` directly because it needs the bounded variant. It decompresses Nemesis itself, then uses `fromBytes(data, maxCount)`.

Add `import com.openggf.util.PatternDecompressor;` to each file. Remove now-unused `Arrays` imports.

- [ ] **Step 10: Run full test suite**

Run: `mvn test -Dmse=off`
Expected: All existing tests pass — no behavioral change.

- [ ] **Step 11: Commit migrations**

```bash
git add -A
git commit -m "refactor: migrate art loaders to PatternDecompressor utility"
```

---

### Task 2: LazyMappingHolder

**Files:**
- Create: `src/main/java/com/openggf/util/LazyMappingHolder.java`
- Create: `src/test/java/com/openggf/util/TestLazyMappingHolder.java`
- Modify: `src/main/java/com/openggf/game/sonic2/objects/RisingPillarObjectInstance.java`
- Modify: `src/main/java/com/openggf/game/sonic2/objects/FallingPillarObjectInstance.java`
- Modify: `src/main/java/com/openggf/game/sonic2/objects/SwingingPlatformObjectInstance.java`
- Modify: `src/main/java/com/openggf/game/sonic2/objects/MCZBrickObjectInstance.java`
- Modify: `src/main/java/com/openggf/game/sonic2/objects/ARZPlatformObjectInstance.java`
- Modify: `src/main/java/com/openggf/game/sonic2/objects/LargeRotPformObjectInstance.java`
- Modify: `src/main/java/com/openggf/game/sonic2/objects/SwingingPformObjectInstance.java`
- Modify: `src/main/java/com/openggf/game/sonic2/objects/ARZRotPformsObjectInstance.java`

- [ ] **Step 1: Write failing test**

```java
package com.openggf.util;

import com.openggf.level.objects.SpriteMappingFrame;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class TestLazyMappingHolder {

    @Test
    public void testReturnsEmptyListWhenLevelManagerNull() {
        // LevelManager.getInstance() returns null in test env without setup
        LazyMappingHolder holder = new LazyMappingHolder();
        List<SpriteMappingFrame> result = holder.get(0x1234,
                (reader, addr) -> { throw new AssertionError("should not be called"); },
                "TestObj");
        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @Test
    public void testLoadAttemptedOnlyOnce() {
        LazyMappingHolder holder = new LazyMappingHolder();
        // First call — LevelManager is null, returns empty
        holder.get(0x1234, (reader, addr) -> Collections.emptyList(), "TestObj");
        // Second call — should not attempt load again
        List<SpriteMappingFrame> result = holder.get(0x1234,
                (reader, addr) -> { throw new AssertionError("should not retry"); },
                "TestObj");
        assertNotNull(result);
        assertTrue(result.isEmpty());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -Dtest=TestLazyMappingHolder -pl . -Dmse=off`
Expected: Compilation error — `LazyMappingHolder` does not exist

- [ ] **Step 3: Implement LazyMappingHolder**

```java
package com.openggf.util;

import com.openggf.data.RomByteReader;
import com.openggf.level.LevelManager;
import com.openggf.level.objects.SpriteMappingFrame;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.logging.Logger;

/**
 * Lazy-loading holder for static sprite mapping data.
 * Not thread-safe; assumes single-threaded game loop access.
 * Load is attempted only once per instance. If loading fails (IOException,
 * LevelManager not initialized, etc.), subsequent calls return an empty list
 * without retrying.
 */
public final class LazyMappingHolder {

    private static final Logger LOG = Logger.getLogger(LazyMappingHolder.class.getName());

    private List<SpriteMappingFrame> mappings;
    private boolean attempted;

    @FunctionalInterface
    public interface MappingLoader {
        List<SpriteMappingFrame> load(RomByteReader reader, int addr) throws IOException;
    }

    public List<SpriteMappingFrame> get(int mappingAddr, MappingLoader loader, String label) {
        if (attempted) {
            return mappings != null ? mappings : Collections.emptyList();
        }
        attempted = true;

        LevelManager manager = LevelManager.getInstance();
        if (manager == null || manager.getGame() == null) {
            return Collections.emptyList();
        }

        try {
            var rom = manager.getGame().getRom();
            var reader = RomByteReader.fromRom(rom);
            mappings = loader.load(reader, mappingAddr);
            LOG.fine("Loaded " + mappings.size() + " " + label + " mapping frames");
            return mappings;
        } catch (IOException | RuntimeException e) {
            LOG.warning("Failed to load " + label + " mappings: " + e.getMessage());
            return Collections.emptyList();
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn test -Dtest=TestLazyMappingHolder -pl . -Dmse=off`
Expected: All tests PASS

- [ ] **Step 5: Commit utility class + tests**

```bash
git add src/main/java/com/openggf/util/LazyMappingHolder.java src/test/java/com/openggf/util/TestLazyMappingHolder.java
git commit -m "feat: add LazyMappingHolder utility for lazy sprite mapping loading"
```

- [ ] **Step 6: Migrate object instances**

For each file listed in "Modify" above, apply this pattern:

1. Replace the two static fields:
   ```java
   // REMOVE:
   private static List<SpriteMappingFrame> mappings;
   private static boolean mappingLoadAttempted;
   // REPLACE WITH:
   private static final LazyMappingHolder MAPPINGS = new LazyMappingHolder();
   ```

2. Delete the entire `ensureMappingsLoaded()` method.

3. Replace all calls to `ensureMappingsLoaded()` + references to `mappings` with:
   ```java
   List<SpriteMappingFrame> mappings = MAPPINGS.get(
       Sonic2Constants.MAP_UNC_OBJxx_ADDR,
       S2SpriteDataLoader::loadMappingFrames,
       "ObjXX");
   ```
   Use the correct constant and label for each object (MAP_UNC_OBJ2B_ADDR for RisingPillar, MAP_UNC_OBJ23_ADDR for FallingPillar, etc.).

4. Add import: `import com.openggf.util.LazyMappingHolder;`

**Special case — SwingingPlatformObjectInstance.java:** This file has 4 mapping sets. Replace with 4 holders:
```java
private static final LazyMappingHolder OOZ_MAPPINGS = new LazyMappingHolder();
private static final LazyMappingHolder MCZ_MAPPINGS = new LazyMappingHolder();
private static final LazyMappingHolder ARZ_MAPPINGS = new LazyMappingHolder();
private static final LazyMappingHolder TRAP_MAPPINGS = new LazyMappingHolder();
```

- [ ] **Step 7: Run full test suite**

Run: `mvn test -Dmse=off`
Expected: All existing tests pass.

- [ ] **Step 8: Commit migrations**

```bash
git add -A
git commit -m "refactor: migrate object instances to LazyMappingHolder utility"
```

---

### Task 3: FboHelper

**Files:**
- Create: `src/main/java/com/openggf/util/FboHelper.java`
- Modify: `src/main/java/com/openggf/graphics/TilePriorityFBO.java:37-86`
- Modify: `src/main/java/com/openggf/level/render/BackgroundRenderer.java:125-162`
- Modify: `src/main/java/com/openggf/game/sonic2/specialstage/SpecialStageBackgroundRenderer.java:96-123`
- Modify: `src/main/java/com/openggf/game/sonic1/specialstage/Sonic1SpecialStageBackgroundRenderer.java:108-129`

> **No unit test** — FBO creation requires an OpenGL context. Verified via existing headless rendering tests + manual testing.

- [ ] **Step 1: Implement FboHelper**

```java
package com.openggf.util;

import java.util.logging.Logger;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL30.*;

/**
 * Helper for OpenGL framebuffer object creation and lifecycle.
 * All FBOs use GL_RGBA8 internal format (macOS OpenGL 4.1 compatibility)
 * and GL_NEAREST filtering.
 */
public final class FboHelper {

    private static final Logger LOG = Logger.getLogger(FboHelper.class.getName());

    private FboHelper() {}

    public record FboHandle(int fboId, int textureId, int depthId) {
        public boolean hasDepth() { return depthId != 0; }
    }

    public static FboHandle createColorOnly(int width, int height, int wrapMode) {
        int fboId = glGenFramebuffers();
        int textureId = createTexture(width, height, wrapMode);

        glBindFramebuffer(GL_FRAMEBUFFER, fboId);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0,
                GL_TEXTURE_2D, textureId, 0);

        checkStatus("color-only");
        glBindFramebuffer(GL_FRAMEBUFFER, 0);

        return new FboHandle(fboId, textureId, 0);
    }

    public static FboHandle createWithDepth(int width, int height, int wrapMode) {
        int fboId = glGenFramebuffers();
        int textureId = createTexture(width, height, wrapMode);

        int depthId = glGenRenderbuffers();
        glBindRenderbuffer(GL_RENDERBUFFER, depthId);
        glRenderbufferStorage(GL_RENDERBUFFER, GL_DEPTH_COMPONENT16, width, height);
        glBindRenderbuffer(GL_RENDERBUFFER, 0);

        glBindFramebuffer(GL_FRAMEBUFFER, fboId);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0,
                GL_TEXTURE_2D, textureId, 0);
        glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT,
                GL_RENDERBUFFER, depthId);

        checkStatus("color+depth");
        glBindFramebuffer(GL_FRAMEBUFFER, 0);

        return new FboHandle(fboId, textureId, depthId);
    }

    public static void destroy(FboHandle handle) {
        if (handle.fboId() != 0) {
            glDeleteFramebuffers(handle.fboId());
        }
        if (handle.textureId() != 0) {
            glDeleteTextures(handle.textureId());
        }
        if (handle.depthId() != 0) {
            glDeleteRenderbuffers(handle.depthId());
        }
    }

    public static int[] saveViewport() {
        int[] viewport = new int[4];
        glGetIntegerv(GL_VIEWPORT, viewport);
        return viewport;
    }

    public static void restoreViewport(int[] saved) {
        glViewport(saved[0], saved[1], saved[2], saved[3]);
    }

    private static int createTexture(int width, int height, int wrapMode) {
        int textureId = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, textureId);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, width, height, 0,
                GL_RGBA, GL_UNSIGNED_BYTE, (java.nio.ByteBuffer) null);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, wrapMode);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, wrapMode);
        glBindTexture(GL_TEXTURE_2D, 0);
        return textureId;
    }

    private static void checkStatus(String label) {
        int status = glCheckFramebufferStatus(GL_FRAMEBUFFER);
        if (status != GL_FRAMEBUFFER_COMPLETE) {
            LOG.severe("FBO creation failed (" + label + ") with status: " + status);
        }
    }
}
```

> **Note:** Read the actual import paths in the existing renderer files to verify the LWJGL OpenGL static import pattern used in this project (e.g. `org.lwjgl.opengl.GL11.*` vs `org.lwjgl.opengl.GL30.*`). Match the existing convention.

- [ ] **Step 2: Commit utility**

```bash
git add src/main/java/com/openggf/util/FboHelper.java
git commit -m "feat: add FboHelper utility for FBO creation/lifecycle"
```

- [ ] **Step 3: Migrate TilePriorityFBO**

In `src/main/java/com/openggf/graphics/TilePriorityFBO.java`, replace the FBO creation block in `init()` (lines ~46-77) with:

```java
fboHandle = FboHelper.createColorOnly(width, height, GL_CLAMP_TO_EDGE);
fboId = fboHandle.fboId();
textureId = fboHandle.textureId();
```

Add a `private FboHelper.FboHandle fboHandle;` field. Update `cleanup()` to use `FboHelper.destroy(fboHandle)`.

> **TilePriorityFBO special:** This file has a `glFinish()` call after FBO creation for macOS driver compatibility. Keep that call AFTER the `FboHelper.createColorOnly()` return.

- [ ] **Step 4: Migrate BackgroundRenderer**

In `src/main/java/com/openggf/level/render/BackgroundRenderer.java`, replace `createFBO()` method body (lines ~125-162) with:

```java
private void createFBO(int width, int height) {
    fboHandle = FboHelper.createWithDepth(width, height, GL_REPEAT);
    fboId = fboHandle.fboId();
    fboTextureId = fboHandle.textureId();
    fboDepthId = fboHandle.depthId();
}
```

Update cleanup method to use `FboHelper.destroy(fboHandle)`.

- [ ] **Step 5: Migrate SpecialStageBackgroundRenderer and Sonic1SpecialStageBackgroundRenderer**

Same pattern as BackgroundRenderer for both files. Replace FBO creation blocks with `FboHelper.createWithDepth(FBO_WIDTH, FBO_HEIGHT, GL_REPEAT)`.

- [ ] **Step 6: Run full test suite**

Run: `mvn test -Dmse=off`
Expected: All existing tests pass.

- [ ] **Step 7: Commit migrations**

```bash
git add -A
git commit -m "refactor: migrate FBO creation to FboHelper utility"
```

---

### Task 4: AnimationTimer

**Files:**
- Create: `src/main/java/com/openggf/util/AnimationTimer.java`
- Create: `src/test/java/com/openggf/util/TestAnimationTimer.java`
- Modify: representative badnik files (GrounderBadnikInstance, CrawlBadnikInstance, SpikerBadnikInstance, NebulaBadnikInstance)

- [ ] **Step 1: Write failing test**

```java
package com.openggf.util;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class TestAnimationTimer {

    @Test
    public void testInitialState() {
        AnimationTimer timer = new AnimationTimer(3, 4);
        assertEquals(0, timer.getFrame());
    }

    @Test
    public void testNoAdvanceBeforeDuration() {
        AnimationTimer timer = new AnimationTimer(3, 4);
        assertFalse(timer.tick()); // tick 1
        assertEquals(0, timer.getFrame());
        assertFalse(timer.tick()); // tick 2
        assertEquals(0, timer.getFrame());
    }

    @Test
    public void testAdvancesAtDuration() {
        AnimationTimer timer = new AnimationTimer(3, 4);
        timer.tick(); // 1
        timer.tick(); // 2
        boolean changed = timer.tick(); // 3 — should advance
        assertTrue(changed);
        assertEquals(1, timer.getFrame());
    }

    @Test
    public void testWrapsAround() {
        AnimationTimer timer = new AnimationTimer(1, 3);
        timer.tick(); // frame 0→1
        timer.tick(); // frame 1→2
        timer.tick(); // frame 2→0 (wrap)
        assertEquals(0, timer.getFrame());
    }

    @Test
    public void testReset() {
        AnimationTimer timer = new AnimationTimer(2, 3);
        timer.tick(); // timer=1
        timer.tick(); // timer>=2, frame advances to 1
        assertEquals(1, timer.getFrame());
        timer.reset();
        assertEquals(0, timer.getFrame());
        // After reset, timer restarts — first tick shouldn't advance (duration=2)
        assertFalse(timer.tick());
        assertEquals(0, timer.getFrame());
    }

    @Test
    public void testSetFrame() {
        AnimationTimer timer = new AnimationTimer(3, 4);
        timer.setFrame(2);
        assertEquals(2, timer.getFrame());
    }

    @Test
    public void testDurationOne() {
        // Duration 1 means advance every tick
        AnimationTimer timer = new AnimationTimer(1, 3);
        assertTrue(timer.tick());
        assertEquals(1, timer.getFrame());
        assertTrue(timer.tick());
        assertEquals(2, timer.getFrame());
        assertTrue(timer.tick());
        assertEquals(0, timer.getFrame()); // wrap
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -Dtest=TestAnimationTimer -pl . -Dmse=off`
Expected: Compilation error — `AnimationTimer` does not exist

- [ ] **Step 3: Implement AnimationTimer**

```java
package com.openggf.util;

/**
 * Simple frame-cycling animation timer.
 * Replaces the manual timer++/modulo pattern used across 25+ objects.
 */
public final class AnimationTimer {

    private int timer;
    private int frame;
    private final int duration;
    private final int frameCount;

    public AnimationTimer(int duration, int frameCount) {
        this.duration = duration;
        this.frameCount = frameCount;
    }

    /**
     * Advance timer by one frame.
     * @return true if the animation frame changed this tick
     */
    public boolean tick() {
        timer++;
        if (timer >= duration) {
            timer = 0;
            frame = (frame + 1) % frameCount;
            return true;
        }
        return false;
    }

    public int getFrame() {
        return frame;
    }

    public void reset() {
        timer = 0;
        frame = 0;
    }

    public void setFrame(int frame) {
        this.frame = frame;
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn test -Dtest=TestAnimationTimer -pl . -Dmse=off`
Expected: All tests PASS

- [ ] **Step 5: Commit utility class + tests**

```bash
git add src/main/java/com/openggf/util/AnimationTimer.java src/test/java/com/openggf/util/TestAnimationTimer.java
git commit -m "feat: add AnimationTimer utility for frame-cycling animation"
```

- [ ] **Step 6: Migrate representative badniks**

For each badnik with the `animTimer++` / modulo pattern, apply the migration. Read each file first to understand the exact field names and usage pattern.

**General migration pattern:**

1. Replace timer + frame fields with `AnimationTimer`:
   ```java
   // REMOVE:
   private int walkAnimTimer;
   private int walkAnimFrame;
   // ADD:
   private final AnimationTimer walkAnim = new AnimationTimer(WALK_ANIM_DURATION, 3);
   ```

2. Replace the manual tick logic:
   ```java
   // REMOVE:
   walkAnimTimer++;
   if (walkAnimTimer >= WALK_ANIM_DURATION) {
       walkAnimTimer = 0;
       walkAnimFrame = (walkAnimFrame + 1) % 3;
   }
   // ADD:
   walkAnim.tick();
   ```

3. Replace frame reads: `walkAnimFrame` → `walkAnim.getFrame()`

4. Add import: `import com.openggf.util.AnimationTimer;`

> **Important:** Read each file carefully before migrating. The `AnimationTimer.tick()` uses `>=` for comparison (advances when `timer >= duration`). Most objects match this. However, some use `>` instead (e.g., NebulaBadnikInstance: `animTimer > ANIM_SPEED`). For those objects, pass `duration + 1` to the `AnimationTimer` constructor to preserve identical frame timing. Example: if original code is `animTimer > 3`, use `new AnimationTimer(4, frameCount)`.
>
> To find `>` cases before migrating, grep: `animTimer\s*>\s*[A-Z_0-9]` (without `=`).

- [ ] **Step 7: Run full test suite**

Run: `mvn test -Dmse=off`
Expected: All existing tests pass.

- [ ] **Step 8: Commit migrations**

```bash
git add -A
git commit -m "refactor: migrate representative badniks to AnimationTimer utility"
```

---

### Task 5: DebugRenderContext Migration

**Files to modify** (all contain `appendDebug(List<GLCommand> commands)` + private `appendLine()`):
- S1 objects: `Sonic1BigSpikedBallObjectInstance`, `Sonic1ElevatorObjectInstance`, `Sonic1CirclingPlatformObjectInstance`, `Sonic1RunningDiscObjectInstance`, `Sonic1HarpoonObjectInstance`, `Sonic1SpikedPoleHelixObjectInstance`, `Sonic1SeesawObjectInstance`, `Sonic1SwingingPlatformObjectInstance`, `Sonic1SpikedBallChainObjectInstance`
- S2 objects: `ARZRotPformsObjectInstance`, `ARZPlatformObjectInstance`, `ArrowShooterObjectInstance`, `ArrowProjectileInstance`, `GrounderBadnikInstance`, `FallingPillarObjectInstance`, `MCZRotPformsObjectInstance`, `MCZDrawbridgeObjectInstance`, `MCZBridgeObjectInstance`, `MCZBrickObjectInstance`, `CPZStaircaseObjectInstance`, `CPZPlatformObjectInstance`, `ConveyorObjectInstance`, `CogObjectInstance`, `CollapsingPlatformObjectInstance`, `LargeRotPformObjectInstance`, `GrabObjectInstance`, `MTZLongPlatformObjectInstance`, `GrounderRockProjectile`, `MovingVineObjectInstance`, `MTZLongPlatformCogInstance`, `MTZPlatformObjectInstance`, `RisingPillarObjectInstance`, `SidewaysPformObjectInstance`, `StomperObjectInstance`, `SwingingPformObjectInstance`, `SwingingPlatformObjectInstance`, `VineSwitchObjectInstance`
- S3K objects: `FloatingPlatformObjectInstance`

> **No new class or test needed.** The `DebugRenderContext` API and `ObjectInstance.appendDebugRenderCommands(DebugRenderContext)` already exist. This is a mechanical migration.

- [ ] **Step 1: Understand the migration pattern**

Read `src/main/java/com/openggf/debug/DebugRenderContext.java` and `src/main/java/com/openggf/level/objects/ObjectInstance.java` to confirm the target interface.

The `ObjectInstance` interface already defines:
```java
default void appendDebugRenderCommands(DebugRenderContext ctx) {
    // Default no-op
}
```

Each legacy object currently has:
```java
private void appendDebug(List<GLCommand> commands) { ... }
private void appendLine(List<GLCommand> commands, ...) { ... }
```

Some objects also call `appendDebug()` from within their `appendDebugRenderCommands()` override or from a render method that builds `List<GLCommand>`. Read each file to find where `appendDebug` is called from to understand how to wire the new override.

- [ ] **Step 2: Migrate objects in batches**

For each file:

1. Change `appendDebug(List<GLCommand> commands)` to `@Override public void appendDebugRenderCommands(DebugRenderContext ctx)`.
2. Replace `appendLine(commands, x1, y1, x2, y2, r, g, b)` calls with `ctx.drawLine(x1, y1, x2, y2, r, g, b)`.
3. Replace manual 4-line box drawing with `ctx.drawRect(cx, cy, halfW, halfH, r, g, b)`.
4. Replace center cross patterns (2 lines) with `ctx.drawCross(x, y, size, r, g, b)`.
5. Delete the private `appendLine()` method.
6. Add import: `import com.openggf.debug.DebugRenderContext;`
7. Remove import: `import com.openggf.graphics.GLCommand;` (if no longer used elsewhere in the file — check first!).

> **Caution:** Some files still call `appendDebug()` from within a method that builds `List<GLCommand>` for non-debug rendering. In those cases, the wiring to the ObjectInstance interface may need adjustment. Read the call site before changing the method signature.

> **Caution:** `DebugRenderContext.drawLine()` uses `BlendType.ONE_MINUS_SRC_ALPHA` while legacy `appendLine()` uses `BlendType.SOLID`. This is a visual difference in how debug lines blend with the background. Since this is debug-only rendering, the change is acceptable, but be aware of it.

- [ ] **Step 3: Run full test suite after each batch**

Migrate in batches of ~10 files. After each batch:

Run: `mvn test -Dmse=off`
Expected: All tests pass.

- [ ] **Step 4: Commit each batch**

```bash
git add -A
git commit -m "refactor: migrate S2 platform objects to DebugRenderContext"
```

Repeat for S1 and S3K batches.

---

### Task 6: isOnScreen() Migration

**Files to modify:**
- `src/main/java/com/openggf/game/sonic2/objects/RisingPillarObjectInstance.java` (lines ~229-234, ~526-531)
- `src/main/java/com/openggf/game/sonic2/objects/FallingPillarObjectInstance.java` (uses `cameraMaxY + OFFSCREEN_Y_MARGIN`)
- `src/main/java/com/openggf/game/sonic1/objects/bosses/Sonic1FalseFloorInstance.java` (uses `camera.getY() + 224 + 64`)

> **No new class or test needed.** Uses existing `AbstractObjectInstance.isOnScreen(margin)`.

- [ ] **Step 1: Audit each file**

Read each file to understand the exact off-screen pattern:

- **RisingPillarObjectInstance:** Uses `camera.getY() + 224 + 128` → margin = 128
- **FallingPillarObjectInstance:** Uses `Camera.getInstance().getMaxY()` + `OFFSCREEN_Y_MARGIN` — check what `getMaxY()` returns vs `isOnScreen(margin)`
- **Sonic1FalseFloorInstance:** Uses `camera.getY() + 224 + 64` → margin = 64

> **Critical check for FallingPillar:** `getMaxY()` may compute camera bottom differently from `isOnScreen()`. Read both implementations to verify equivalence before migrating. If they differ, do NOT migrate — leave the manual check.

- [ ] **Step 2: Migrate RisingPillarObjectInstance**

Replace lines ~229-234 (debris cleanup in `updateDebris()`):
```java
// REMOVE:
Camera camera = Camera.getInstance();
int screenBottom = camera.getY() + 224 + 128;
if (y > screenBottom) {
    setDestroyed(true);
}
// ADD:
if (!isOnScreen(128)) {
    setDestroyed(true);
}
```

Same for the inner class RisingPillarDebrisInstance (lines ~526-531).

Remove unused `Camera` import if no longer referenced.

- [ ] **Step 3: Migrate remaining files (if audit confirms equivalence)**

Apply same pattern to FallingPillar and Sonic1FalseFloor, adjusting margin values.

- [ ] **Step 4: Run full test suite**

Run: `mvn test -Dmse=off`
Expected: All existing tests pass.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "refactor: migrate manual off-screen checks to isOnScreen(margin)"
```
