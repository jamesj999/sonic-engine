# Master Title Legal Disclaimer Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a white-text-on-black legal disclaimer screen shown on engine startup, before the master title screen. Configurable via `config.json`, dismissible by any key after a 5-second readability gate, integrated with the existing `FadeManager` for entry/exit/master-title-reveal fades.

**Architecture:** A new `LegalDisclaimerScreen` class in `com.openggf.game` (sibling of `MasterTitleScreen`) renders its own GL pass — owns a `TexturedQuadRenderer`, `PixelFont`, and 1×1 white texture (tinted black for the background). Its state machine is extracted into a testable `LegalDisclaimerState` POJO. A new `GameMode.LEGAL_DISCLAIMER` enum value, new `SHOW_LEGAL_DISCLAIMER_ON_STARTUP` config key, and new `Engine.exitLegalDisclaimer()` handler chain the three fade transitions: fade-from-black over the disclaimer text, fade-to-black on dismiss, and a final fade-from-black to reveal the master title (or the configured fallback startup mode).

**Tech Stack:** Java 21, LWJGL/OpenGL (existing patterns), JUnit 5 (Jupiter only).

**Spec:** `docs/architecture/designs/2026-05-26-master-title-legal-disclaimer-design.md`

---

## File Structure

**New files:**
- `src/main/java/com/openggf/game/LegalDisclaimerState.java` — POJO holding state, frame counter, and dismissed flag. No GL, no input. Headlessly testable.
- `src/main/java/com/openggf/game/LegalDisclaimerScreen.java` — GL-bound screen that composes the state with renderers, font, fade callbacks, and disclaimer text layout. Hosts the static `wrapParagraph` text-wrap helper.
- `src/test/java/com/openggf/game/TestLegalDisclaimerState.java` — JUnit 5 tests for the state machine.
- `src/test/java/com/openggf/game/TestLegalDisclaimerTextWrap.java` — JUnit 5 tests for the word-wrap helper. Uses a function-supplied measure, no GL.
- `src/test/java/com/openggf/game/TestLegalDisclaimerHandoff.java` — JUnit 5 test driving the real `GameLoop` with a Mockito-mocked screen to verify the dispatch wiring.

**Modified files:**
- `src/main/java/com/openggf/game/GameMode.java` — add `LEGAL_DISCLAIMER` enum constant.
- `src/main/java/com/openggf/configuration/SonicConfiguration.java` — add `SHOW_LEGAL_DISCLAIMER_ON_STARTUP` enum constant.
- `src/main/java/com/openggf/configuration/SonicConfigurationService.java` — default value `true`.
- `src/main/java/com/openggf/control/InputHandler.java` — add public `isAnyKeyJustPressed()` helper.
- `src/test/java/com/openggf/control/TestInputHandler.java` — new test file for the helper (file may not exist yet; create if not).
- `src/main/java/com/openggf/GameLoop.java` — add supplier/exit-handler setters, add step-loop branch.
- `src/main/java/com/openggf/Engine.java` — add field, constructor hooks, init startup gate, exit handler, draw branch.
- `CONFIGURATION.md` — document the new config key.
- `CHANGELOG.md` — release note.
- `README.md` — note the in-engine notice.
- `AGENTS.md` and `CLAUDE.md` — note the new boot phase for agent reference.
- `src/test/java/com/openggf/TestEngine.java`, `src/test/java/com/openggf/game/TestEngineContext.java`, `src/test/java/com/openggf/editor/TestEditorToggleIntegration.java` (and any other test discovered in Task 12 Step 1 that drives `Engine.init()`) — disable the disclaimer in setup.

`HeadlessTestRunner` is intentionally NOT modified: it bypasses
`Engine.init()` and boots managers directly via `TestEnvironment` +
`GameServices`, so the disclaimer screen never affects its tests.

The split between `LegalDisclaimerState` and `LegalDisclaimerScreen` is the key decomposition: the state machine is the testable core, and the screen is the thin GL wrapper that drives it. The `wrapParagraph` helper lives on the screen because it is screen-layout logic, but is package-private static so the test reaches it without constructing the screen.

---

## Task 1: Add `GameMode.LEGAL_DISCLAIMER` enum constant

**Files:**
- Modify: `src/main/java/com/openggf/game/GameMode.java`

- [ ] **Step 1: Add the enum constant.**

Open `src/main/java/com/openggf/game/GameMode.java`. After the existing `MASTER_TITLE_SCREEN` constant (around line 39), add:

```java
    /** Legal disclaimer screen shown on engine startup before MASTER_TITLE_SCREEN. */
    LEGAL_DISCLAIMER,
```

The final file should have the new entry placed before `TRY_AGAIN_END`.

- [ ] **Step 2: Compile.**

Run: `mvn -DskipTests compile`
Expected: BUILD SUCCESS.

- [ ] **Step 3: Commit.**

```bash
git add src/main/java/com/openggf/game/GameMode.java
git commit -m "feat: add GameMode.LEGAL_DISCLAIMER enum constant"
```

Fill in the trailer block as prompted by the prepare-commit-msg hook. For this commit:
- `Changelog: n/a` (no user-visible behavior yet)
- `Guide: n/a`
- `Known-Discrepancies: n/a`
- `S3K-Known-Discrepancies: n/a`
- `Agent-Docs: n/a`
- `Configuration-Docs: n/a`
- `Skills: n/a`

---

## Task 2: Add `SHOW_LEGAL_DISCLAIMER_ON_STARTUP` config key

**Files:**
- Modify: `src/main/java/com/openggf/configuration/SonicConfiguration.java`
- Modify: `src/main/java/com/openggf/configuration/SonicConfigurationService.java`

- [ ] **Step 1: Add the enum constant.**

Open `src/main/java/com/openggf/configuration/SonicConfiguration.java`. After the existing `MASTER_TITLE_SCREEN_ON_STARTUP` constant (around line 345), add:

```java
	/**
	 * Whether to show the legal disclaimer screen on startup before the
	 * master title screen. Default true. Test harnesses set this false.
	 */
	SHOW_LEGAL_DISCLAIMER_ON_STARTUP,
```

- [ ] **Step 2: Add the default value.**

Open `src/main/java/com/openggf/configuration/SonicConfigurationService.java`. After the existing default for `MASTER_TITLE_SCREEN_ON_STARTUP` (around line 317), add:

```java
		putDefault(SonicConfiguration.SHOW_LEGAL_DISCLAIMER_ON_STARTUP, true);
```

- [ ] **Step 3: Compile.**

Run: `mvn -DskipTests compile`
Expected: BUILD SUCCESS.

- [ ] **Step 4: Commit.**

```bash
git add src/main/java/com/openggf/configuration/SonicConfiguration.java src/main/java/com/openggf/configuration/SonicConfigurationService.java
git commit -m "feat: add SHOW_LEGAL_DISCLAIMER_ON_STARTUP config key (default true)"
```

Trailer block: `Configuration-Docs: n/a` (CONFIGURATION.md is updated in Task 11, when the user-facing entry lands together with the changelog). All other trailers: `n/a`.

---

## Task 3: Add `InputHandler.isAnyKeyJustPressed()` helper (TDD)

**Files:**
- Modify: `src/main/java/com/openggf/control/InputHandler.java`
- Create or modify: `src/test/java/com/openggf/control/TestInputHandler.java`

- [ ] **Step 1: Write the failing test.**

If `src/test/java/com/openggf/control/TestInputHandler.java` does not yet exist, create it with this contents:

```java
package com.openggf.control;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_A;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_SPACE;
import static org.lwjgl.glfw.GLFW.GLFW_PRESS;
import static org.lwjgl.glfw.GLFW.GLFW_RELEASE;

class TestInputHandler {

    @Test
    void isAnyKeyJustPressedReturnsFalseWhenNoKeysPressed() {
        InputHandler handler = new InputHandler();
        assertFalse(handler.isAnyKeyJustPressed());
    }

    @Test
    void isAnyKeyJustPressedReturnsTrueOnTransitionToPressed() {
        InputHandler handler = new InputHandler();
        handler.handleKeyEvent(GLFW_KEY_SPACE, GLFW_PRESS);
        assertTrue(handler.isAnyKeyJustPressed());
    }

    @Test
    void isAnyKeyJustPressedReturnsFalseOnceFrameAdvances() {
        InputHandler handler = new InputHandler();
        handler.handleKeyEvent(GLFW_KEY_ENTER, GLFW_PRESS);
        assertTrue(handler.isAnyKeyJustPressed());
        handler.update();
        assertFalse(handler.isAnyKeyJustPressed());
    }

    @Test
    void isAnyKeyJustPressedDetectsAnyKeyNotJustConfiguredOnes() {
        InputHandler handler = new InputHandler();
        handler.handleKeyEvent(GLFW_KEY_A, GLFW_PRESS);
        assertTrue(handler.isAnyKeyJustPressed());
    }

    @Test
    void isAnyKeyJustPressedReturnsFalseAfterKeyReleased() {
        InputHandler handler = new InputHandler();
        handler.handleKeyEvent(GLFW_KEY_SPACE, GLFW_PRESS);
        handler.update();
        handler.handleKeyEvent(GLFW_KEY_SPACE, GLFW_RELEASE);
        assertFalse(handler.isAnyKeyJustPressed());
    }
}
```

If the file already exists, append the five test methods above to its body and merge the imports.

- [ ] **Step 2: Run to verify it fails.**

Run: `mvn "-Dtest=TestInputHandler" test`
Expected: compilation failure (`cannot find symbol method isAnyKeyJustPressed()`).

- [ ] **Step 3: Implement the helper.**

Open `src/main/java/com/openggf/control/InputHandler.java`. After the existing `isKeyPressed(int)` method (around line 85), add:

```java
    /**
     * Returns true when at least one key transitioned from not-pressed to
     * pressed during the current frame. Mirrors {@link #isKeyPressed(int)}
     * but checks all keys at once. Used by full-screen prompts that
     * accept any input to dismiss.
     */
    public boolean isAnyKeyJustPressed() {
        for (int i = 0; i < MAX_KEYS; i++) {
            if (keys[i] && !previousKeys[i]) {
                return true;
            }
        }
        return false;
    }
```

- [ ] **Step 4: Run tests.**

Run: `mvn "-Dtest=TestInputHandler" test`
Expected: BUILD SUCCESS, 5 tests pass.

- [ ] **Step 5: Commit.**

```bash
git add src/main/java/com/openggf/control/InputHandler.java src/test/java/com/openggf/control/TestInputHandler.java
git commit -m "feat: add InputHandler.isAnyKeyJustPressed() helper"
```

All trailers `n/a`.

---

## Task 4: Create `LegalDisclaimerState` POJO with state machine (TDD)

**Files:**
- Create: `src/main/java/com/openggf/game/LegalDisclaimerState.java`
- Create: `src/test/java/com/openggf/game/TestLegalDisclaimerState.java`

- [ ] **Step 1: Write the failing test.**

Create `src/test/java/com/openggf/game/TestLegalDisclaimerState.java`:

```java
package com.openggf.game;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestLegalDisclaimerState {

    @Test
    void initialStateIsFadeIn() {
        LegalDisclaimerState state = new LegalDisclaimerState();
        assertEquals(LegalDisclaimerState.Phase.FADE_IN, state.getPhase());
        assertFalse(state.isDismissed());
        assertFalse(state.isDismissPromptVisible());
    }

    @Test
    void onFadeInCompleteAdvancesToReadingAndResetsFrameCounter() {
        LegalDisclaimerState state = new LegalDisclaimerState();
        for (int i = 0; i < 50; i++) {
            state.tick(false);
        }
        state.onFadeInComplete();
        assertEquals(LegalDisclaimerState.Phase.READING, state.getPhase());
        assertEquals(0, state.getReadingFrameCounter());
        assertFalse(state.isDismissPromptVisible());
    }

    @Test
    void readingIgnoresKeyPressesForFullGate() {
        LegalDisclaimerState state = new LegalDisclaimerState();
        state.onFadeInComplete();
        for (int i = 0; i < LegalDisclaimerState.READING_FRAMES - 1; i++) {
            state.tick(true);
            assertEquals(LegalDisclaimerState.Phase.READING, state.getPhase());
            assertFalse(state.isDismissed());
        }
    }

    @Test
    void readingAdvancesToDismissibleAtFrame300() {
        LegalDisclaimerState state = new LegalDisclaimerState();
        state.onFadeInComplete();
        for (int i = 0; i < LegalDisclaimerState.READING_FRAMES; i++) {
            state.tick(false);
        }
        assertEquals(LegalDisclaimerState.Phase.DISMISSIBLE, state.getPhase());
        assertTrue(state.isDismissPromptVisible());
    }

    @Test
    void dismissibleAdvancesToExitingOnKeyPress() {
        LegalDisclaimerState state = new LegalDisclaimerState();
        state.onFadeInComplete();
        for (int i = 0; i < LegalDisclaimerState.READING_FRAMES; i++) {
            state.tick(false);
        }
        state.tick(true);
        assertEquals(LegalDisclaimerState.Phase.EXITING, state.getPhase());
        assertFalse(state.isDismissed());
    }

    @Test
    void exitingIgnoresFurtherKeyPresses() {
        LegalDisclaimerState state = new LegalDisclaimerState();
        state.onFadeInComplete();
        for (int i = 0; i < LegalDisclaimerState.READING_FRAMES; i++) {
            state.tick(false);
        }
        state.tick(true);
        LegalDisclaimerState.Phase phaseBefore = state.getPhase();
        state.tick(true);
        state.tick(true);
        assertEquals(phaseBefore, state.getPhase());
    }

    @Test
    void onFadeOutCompleteSetsDismissed() {
        LegalDisclaimerState state = new LegalDisclaimerState();
        state.onFadeInComplete();
        for (int i = 0; i < LegalDisclaimerState.READING_FRAMES; i++) {
            state.tick(false);
        }
        state.tick(true);
        state.onFadeOutComplete();
        assertTrue(state.isDismissed());
    }

    @Test
    void fadeInIgnoresKeyPresses() {
        LegalDisclaimerState state = new LegalDisclaimerState();
        state.tick(true);
        state.tick(true);
        assertEquals(LegalDisclaimerState.Phase.FADE_IN, state.getPhase());
    }

    @Test
    void readingFrameCounterAdvancesEachTick() {
        LegalDisclaimerState state = new LegalDisclaimerState();
        state.onFadeInComplete();
        state.tick(false);
        state.tick(false);
        state.tick(false);
        assertEquals(3, state.getReadingFrameCounter());
    }
}
```

- [ ] **Step 2: Run to verify it fails.**

Run: `mvn "-Dtest=TestLegalDisclaimerState" test`
Expected: compilation failure (`cannot find symbol class LegalDisclaimerState`).

- [ ] **Step 3: Implement the state class.**

Create `src/main/java/com/openggf/game/LegalDisclaimerState.java`:

```java
package com.openggf.game;

/**
 * State machine for {@link LegalDisclaimerScreen}, extracted into its own
 * class so it can be exercised headlessly without GL resources.
 * <p>
 * Phases:
 * <ol>
 *   <li>{@link Phase#FADE_IN} — fade-from-black animation is playing. Input
 *       ignored. Transitions to {@link Phase#READING} when
 *       {@link #onFadeInComplete()} is called.</li>
 *   <li>{@link Phase#READING} — 5 second readability gate. Input ignored.
 *       Transitions to {@link Phase#DISMISSIBLE} after
 *       {@link #READING_FRAMES} ticks.</li>
 *   <li>{@link Phase#DISMISSIBLE} — "Press any key to continue" prompt is
 *       visible. Any key transitions to {@link Phase#EXITING}.</li>
 *   <li>{@link Phase#EXITING} — fade-to-black is playing. Input ignored.
 *       {@link #onFadeOutComplete()} sets {@link #isDismissed()} to
 *       {@code true} so the host loop can hand off.</li>
 * </ol>
 */
public final class LegalDisclaimerState {

    /** 5 seconds at 60 fps. */
    public static final int READING_FRAMES = 300;

    public enum Phase {
        FADE_IN,
        READING,
        DISMISSIBLE,
        EXITING
    }

    private Phase phase = Phase.FADE_IN;
    private int readingFrameCounter;
    private boolean dismissed;

    public Phase getPhase() {
        return phase;
    }

    public int getReadingFrameCounter() {
        return readingFrameCounter;
    }

    public boolean isDismissed() {
        return dismissed;
    }

    public boolean isDismissPromptVisible() {
        return phase == Phase.DISMISSIBLE;
    }

    public void onFadeInComplete() {
        if (phase == Phase.FADE_IN) {
            phase = Phase.READING;
            readingFrameCounter = 0;
        }
    }

    public void onFadeOutComplete() {
        if (phase == Phase.EXITING) {
            dismissed = true;
        }
    }

    /**
     * Advances one frame.
     *
     * @param anyKeyJustPressed whether any key transitioned to pressed
     *                          this frame (from
     *                          {@link com.openggf.control.InputHandler#isAnyKeyJustPressed()})
     * @return {@code true} if the phase transitioned to
     *         {@link Phase#EXITING} on this tick — useful for the host
     *         screen to kick off the fade-to-black exactly once.
     */
    public boolean tick(boolean anyKeyJustPressed) {
        switch (phase) {
            case READING -> {
                readingFrameCounter++;
                if (readingFrameCounter >= READING_FRAMES) {
                    phase = Phase.DISMISSIBLE;
                }
            }
            case DISMISSIBLE -> {
                if (anyKeyJustPressed) {
                    phase = Phase.EXITING;
                    return true;
                }
            }
            default -> { }
        }
        return false;
    }
}
```

- [ ] **Step 4: Run tests.**

Run: `mvn "-Dtest=TestLegalDisclaimerState" test`
Expected: BUILD SUCCESS, 9 tests pass.

- [ ] **Step 5: Commit.**

```bash
git add src/main/java/com/openggf/game/LegalDisclaimerState.java src/test/java/com/openggf/game/TestLegalDisclaimerState.java
git commit -m "feat: add LegalDisclaimerState testable state machine"
```

All trailers `n/a`.

---

## Task 5: Create `LegalDisclaimerScreen` class (with text wrap helper)

**Files:**
- Create: `src/main/java/com/openggf/game/LegalDisclaimerScreen.java`
- Create: `src/test/java/com/openggf/game/TestLegalDisclaimerTextWrap.java`

The screen itself owns GL resources and isn't headlessly testable, but the
text-wrap helper inside it is pure logic and can be TDD'd separately. The
spec calls for runtime word-wrap against `PixelFont.measureWidth` so the
layout survives font-metric changes — keep that property by exposing a
package-private static helper that takes a measure function (no direct
`PixelFont` dependency, so the test doesn't need OpenGL).

- [ ] **Step 1: Write the failing test for the wrap helper.**

Create `src/test/java/com/openggf/game/TestLegalDisclaimerTextWrap.java`:

```java
package com.openggf.game;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.ToIntFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TestLegalDisclaimerTextWrap {

    /** Fixed 6 px per char measure, matches a simple monospaced fixture. */
    private static final ToIntFunction<String> SIX_PER_CHAR =
            s -> s.length() * 6;

    @Test
    void singleShortLineIsReturnedAsIs() {
        List<String> out = LegalDisclaimerScreen.wrapParagraph(
                "short line", SIX_PER_CHAR, 100);
        assertEquals(List.of("short line"), out);
    }

    @Test
    void exactlyFittingLineIsNotSplit() {
        // "abcdef ghij" = 11 chars = 66 px, fits in 66 px
        List<String> out = LegalDisclaimerScreen.wrapParagraph(
                "abcdef ghij", SIX_PER_CHAR, 66);
        assertEquals(List.of("abcdef ghij"), out);
    }

    @Test
    void overflowingLineSplitsAtWordBoundary() {
        // "abcdef ghij" = 66 px, max = 60 → split into "abcdef" + "ghij"
        List<String> out = LegalDisclaimerScreen.wrapParagraph(
                "abcdef ghij", SIX_PER_CHAR, 60);
        assertEquals(List.of("abcdef", "ghij"), out);
    }

    @Test
    void multiWordParagraphWrapsAcrossSeveralLines() {
        // 6 chars per word, 6 px per char → 36 px per word, plus 6 px space
        // Max 84 px = up to two 6-char words per line.
        List<String> out = LegalDisclaimerScreen.wrapParagraph(
                "alphas bravos charlies deltas echoes", SIX_PER_CHAR, 84);
        assertEquals(List.of(
                "alphas bravos",
                "charlies",
                "deltas echoes"
        ), out);
    }

    @Test
    void emptyParagraphReturnsSingleEmptyLine() {
        List<String> out = LegalDisclaimerScreen.wrapParagraph(
                "", SIX_PER_CHAR, 100);
        assertEquals(List.of(""), out);
    }

    @Test
    void wordLongerThanMaxStillEmittedAsOwnLine() {
        // "supercalifragilistic" alone exceeds width; helper must not loop.
        List<String> out = LegalDisclaimerScreen.wrapParagraph(
                "supercalifragilistic foo", SIX_PER_CHAR, 60);
        assertEquals(List.of("supercalifragilistic", "foo"), out);
    }
}
```

- [ ] **Step 2: Run to verify it fails.**

Run: `mvn "-Dtest=TestLegalDisclaimerTextWrap" test`
Expected: compilation failure (`cannot find symbol class LegalDisclaimerScreen` or method `wrapParagraph`).

- [ ] **Step 3: Create the screen class.**

Create `src/main/java/com/openggf/game/LegalDisclaimerScreen.java`:

```java
package com.openggf.game;

import com.openggf.control.InputHandler;
import com.openggf.graphics.FadeManager;
import com.openggf.graphics.PixelFont;
import com.openggf.graphics.PngTextureLoader;
import com.openggf.graphics.TexturedQuadRenderer;

import org.lwjgl.system.MemoryUtil;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.ToIntFunction;
import java.util.logging.Logger;

import static org.lwjgl.opengl.GL11.GL_BLEND;
import static org.lwjgl.opengl.GL11.GL_NEAREST;
import static org.lwjgl.opengl.GL11.GL_ONE_MINUS_SRC_ALPHA;
import static org.lwjgl.opengl.GL11.GL_RGBA;
import static org.lwjgl.opengl.GL11.GL_SRC_ALPHA;
import static org.lwjgl.opengl.GL11.GL_TEXTURE_2D;
import static org.lwjgl.opengl.GL11.GL_TEXTURE_MAG_FILTER;
import static org.lwjgl.opengl.GL11.GL_TEXTURE_MIN_FILTER;
import static org.lwjgl.opengl.GL11.GL_UNSIGNED_BYTE;
import static org.lwjgl.opengl.GL11.glBindTexture;
import static org.lwjgl.opengl.GL11.glBlendFunc;
import static org.lwjgl.opengl.GL11.glEnable;
import static org.lwjgl.opengl.GL11.glGenTextures;
import static org.lwjgl.opengl.GL11.glTexImage2D;
import static org.lwjgl.opengl.GL11.glTexParameteri;

/**
 * Legal disclaimer screen shown on engine startup before the master title
 * screen. Renders a white-text-on-black layout, gates dismissal for 5 s,
 * and chains entry/exit fades through {@link FadeManager}.
 */
public class LegalDisclaimerScreen {

    private static final Logger LOGGER = Logger.getLogger(LegalDisclaimerScreen.class.getName());
    private static final int SCREEN_W = 320;
    private static final int SCREEN_H = 224;

    /** Side margin in px on each edge. */
    private static final int SIDE_MARGIN = 20;

    /** Maximum width in px for a wrapped body line. */
    static final int BODY_MAX_WIDTH = SCREEN_W - (SIDE_MARGIN * 2);

    /**
     * Body text scale. PixelFont is 9 px/char wide at scale 1.0; at scale
     * 0.75 each char is ~6.75 px wide, giving ~41 chars per 280 px line.
     * The chosen scale keeps the three paragraphs inside Y=48..Y=200 so
     * the dismiss prompt at Y=210 has clearance. Do not raise above 0.85
     * without recomputing the layout (see TestLegalDisclaimerLayoutFits).
     */
    static final float BODY_SCALE = 0.75f;

    /** Pixel advance from one body line to the next. */
    static final int BODY_LINE_HEIGHT = 8;

    /** Pixel gap inserted for each empty paragraph entry. */
    static final int BODY_PARAGRAPH_GAP = 10;

    /** First body line baseline Y. */
    static final int BODY_START_Y = 48;

    /** Dismiss prompt baseline Y. Body must not extend past this. */
    static final int PROMPT_Y = 210;

    private static final String HEADER = "LEGAL NOTICE";

    /**
     * Disclaimer body, one entry per paragraph. Word-wrapped at draw time
     * by {@link #wrapParagraph} so the layout survives font-metric changes.
     * Empty string entries produce a blank line between paragraphs.
     * Package-private so {@link TestLegalDisclaimerTextWrap} can verify
     * the wrapped body fits before the dismiss prompt.
     */
    static final String[] BODY_PARAGRAPHS = {
        "OpenGGF is an independent, open-source reimplementation of the original Sonic the Hedgehog games for the Sega Mega Drive / Genesis. It is developed and verified against community-maintained disassemblies.",
        "",
        "No copyrighted Sega assets are distributed with this engine. ROM data, sprites, music, and all other game assets must be supplied by the user from a legally obtained copy.",
        "",
        "This project is not affiliated with or endorsed by Sega. Sonic the Hedgehog and all related characters, names, and trademarks are the property of Sega Corporation, to which no claim is made."
    };

    private static final String PROMPT = "Press any key to continue";

    private final FadeManager fadeManager;
    private final LegalDisclaimerState state = new LegalDisclaimerState();

    private TexturedQuadRenderer renderer;
    private PixelFont font;
    private int solidWhiteTextureId;
    private int frameCounter;

    public LegalDisclaimerScreen(FadeManager fadeManager) {
        this.fadeManager = Objects.requireNonNull(fadeManager, "fadeManager");
    }

    public void initialize() {
        try {
            renderer = new TexturedQuadRenderer();
            renderer.init();
            font = new PixelFont();
            font.init("pixel-font.png", renderer);
            solidWhiteTextureId = createSolidWhiteTexture();
            fadeManager.startFadeFromBlack(state::onFadeInComplete);
            LOGGER.info("Legal disclaimer screen initialized");
        } catch (IOException e) {
            LOGGER.severe("Failed to initialize legal disclaimer screen: " + e.getMessage());
            throw new RuntimeException("Failed to initialize legal disclaimer screen", e);
        }
    }

    private static int createSolidWhiteTexture() {
        ByteBuffer pixel = MemoryUtil.memAlloc(4);
        pixel.put((byte) 0xFF).put((byte) 0xFF).put((byte) 0xFF).put((byte) 0xFF).flip();
        int texId = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, texId);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, 1, 1, 0, GL_RGBA, GL_UNSIGNED_BYTE, pixel);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glBindTexture(GL_TEXTURE_2D, 0);
        MemoryUtil.memFree(pixel);
        return texId;
    }

    public void update(InputHandler inputHandler) {
        frameCounter++;
        boolean keyPressed = inputHandler.isAnyKeyJustPressed();
        boolean enteredExiting = state.tick(keyPressed);
        if (enteredExiting) {
            fadeManager.startFadeToBlack(state::onFadeOutComplete);
        }
    }

    public void draw() {
        if (renderer == null) {
            return;
        }
        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);

        // Solid black background
        renderer.drawTexture(solidWhiteTextureId, 0, 0, SCREEN_W, SCREEN_H,
                0f, 0f, 0f, 1f);

        // Header (full scale)
        font.drawTextCentered(HEADER, SCREEN_W, 22, 1f, 1f, 1f, 1f);

        // Body — wrap each paragraph at draw time, at BODY_SCALE.
        int bodyY = BODY_START_Y;
        ToIntFunction<String> measure = s -> font.measureWidth(s, BODY_SCALE);
        for (String paragraph : BODY_PARAGRAPHS) {
            if (paragraph.isEmpty()) {
                bodyY += BODY_PARAGRAPH_GAP;
                continue;
            }
            List<String> lines = wrapParagraph(paragraph, measure, BODY_MAX_WIDTH);
            for (String line : lines) {
                int x = (SCREEN_W - measure.applyAsInt(line)) / 2;
                font.drawText(line, x, bodyY, BODY_SCALE, 0.95f, 0.95f, 0.95f, 1f);
                bodyY += BODY_LINE_HEIGHT;
            }
        }

        // Dismiss prompt (pulses when visible, full scale)
        if (state.isDismissPromptVisible()) {
            float pulse = 0.6f + 0.4f * (float) Math.sin(frameCounter * 0.05);
            font.drawTextCentered(PROMPT, SCREEN_W, PROMPT_Y, pulse, pulse, pulse, 1f);
        }
    }

    /**
     * Greedy word-wrap. Decoupled from {@link PixelFont} so it can be tested
     * without OpenGL: the caller supplies the per-string width measure.
     * Words longer than {@code maxWidth} get their own line (do not loop or
     * truncate). An empty input returns a single empty line so the caller
     * can preserve paragraph break spacing.
     */
    static List<String> wrapParagraph(String paragraph,
                                      ToIntFunction<String> measure,
                                      int maxWidth) {
        if (paragraph.isEmpty()) {
            return List.of("");
        }
        List<String> out = new ArrayList<>();
        String[] words = paragraph.split(" ");
        StringBuilder current = new StringBuilder();
        for (String word : words) {
            if (current.length() == 0) {
                current.append(word);
                continue;
            }
            String candidate = current + " " + word;
            if (measure.applyAsInt(candidate) <= maxWidth) {
                current.setLength(0);
                current.append(candidate);
            } else {
                out.add(current.toString());
                current.setLength(0);
                current.append(word);
            }
        }
        if (current.length() > 0) {
            out.add(current.toString());
        }
        return out;
    }

    public void setProjectionMatrix(float[] projectionMatrix) {
        if (renderer != null && projectionMatrix != null) {
            renderer.setProjectionMatrix(projectionMatrix);
        }
    }

    public boolean isDismissed() {
        return state.isDismissed();
    }

    public void cleanup() {
        if (font != null) {
            font.cleanup();
        }
        PngTextureLoader.deleteTexture(solidWhiteTextureId);
        if (renderer != null) {
            renderer.cleanup();
        }
        LOGGER.info("Legal disclaimer screen cleaned up");
    }
}
```

- [ ] **Step 4: Run the wrap helper tests.**

Run: `mvn "-Dtest=TestLegalDisclaimerTextWrap" test`
Expected: BUILD SUCCESS, 6 tests pass.

- [ ] **Step 5: Write a layout-fits test.**

All layout constants on `LegalDisclaimerScreen` (`BODY_SCALE`,
`BODY_MAX_WIDTH`, `BODY_LINE_HEIGHT`, `BODY_PARAGRAPH_GAP`, `BODY_START_Y`,
`PROMPT_Y`, `BODY_PARAGRAPHS`) are package-private, so the same-package
test reaches them by name — no reflection, no `setAccessible`.

Append to `src/test/java/com/openggf/game/TestLegalDisclaimerTextWrap.java`:

```java
    /**
     * Mirrors PixelFont.measureWidth(s, scale) — 9 px/char × scale, rounded.
     */
    private static int measureLike(String s, float scale) {
        return Math.round(s.length() * 9f * scale);
    }

    @Test
    void bodyParagraphsFitBetweenStartYAndPromptY() {
        ToIntFunction<String> measure =
                s -> measureLike(s, LegalDisclaimerScreen.BODY_SCALE);

        int y = LegalDisclaimerScreen.BODY_START_Y;
        for (String paragraph : LegalDisclaimerScreen.BODY_PARAGRAPHS) {
            if (paragraph.isEmpty()) {
                y += LegalDisclaimerScreen.BODY_PARAGRAPH_GAP;
                continue;
            }
            List<String> lines = LegalDisclaimerScreen.wrapParagraph(
                    paragraph, measure, LegalDisclaimerScreen.BODY_MAX_WIDTH);
            y += lines.size() * LegalDisclaimerScreen.BODY_LINE_HEIGHT;
        }
        assertTrue(y <= LegalDisclaimerScreen.PROMPT_Y,
                "Body wraps to Y=" + y + " which overlaps the dismiss prompt at Y="
                        + LegalDisclaimerScreen.PROMPT_Y);
    }
```

Also add the missing import at the top of the file:

```java
import static org.junit.jupiter.api.Assertions.assertTrue;
```

(`List` and `ToIntFunction` are already imported by the wrap tests.)

This is a compile-time contract: any future change to a layout constant
or paragraph that breaks vertical fit fails this test, with the exact Y
overrun reported.

- [ ] **Step 6: Run the layout test.**

Run: `mvn "-Dtest=TestLegalDisclaimerTextWrap" test`
Expected: BUILD SUCCESS, 7 tests pass.

- [ ] **Step 7: Compile the full module.**

Run: `mvn -DskipTests compile`
Expected: BUILD SUCCESS.

- [ ] **Step 8: Commit.**

```bash
git add src/main/java/com/openggf/game/LegalDisclaimerScreen.java src/test/java/com/openggf/game/TestLegalDisclaimerTextWrap.java
git commit -m "feat: add LegalDisclaimerScreen with word-wrap helper and layout test"
```

All trailers `n/a`.

---

## Task 6: Wire `GameLoop` supplier/exit-handler setters and `step()` branch

**Files:**
- Modify: `src/main/java/com/openggf/GameLoop.java`

- [ ] **Step 1: Add the field declarations.**

In `src/main/java/com/openggf/GameLoop.java`, around line 134 next to the existing `masterTitleScreenSupplier` field, add:

```java
    private java.util.function.Supplier<LegalDisclaimerScreen> legalDisclaimerSupplier;
    private Runnable legalDisclaimerExitHandler;
```

If `LegalDisclaimerScreen` is not yet imported, add `import com.openggf.game.LegalDisclaimerScreen;` to the import block (alphabetize within the `com.openggf.game` block).

- [ ] **Step 2: Add the setter methods.**

Right after `setMasterTitleExitHandler` (around line 306), add:

```java
    public void setLegalDisclaimerScreenSupplier(java.util.function.Supplier<LegalDisclaimerScreen> legalDisclaimerSupplier) {
        this.legalDisclaimerSupplier = legalDisclaimerSupplier;
    }

    public void setLegalDisclaimerExitHandler(Runnable legalDisclaimerExitHandler) {
        this.legalDisclaimerExitHandler = legalDisclaimerExitHandler;
    }
```

- [ ] **Step 3: Add the step() branch.**

Around line 460, immediately above the existing `MASTER_TITLE_SCREEN` branch (`if (currentGameMode == GameMode.MASTER_TITLE_SCREEN) { ... }`), add:

```java
        if (currentGameMode == GameMode.LEGAL_DISCLAIMER) {
            LegalDisclaimerScreen disclaimer = legalDisclaimerSupplier != null
                    ? legalDisclaimerSupplier.get()
                    : null;
            if (disclaimer != null) {
                disclaimer.update(inputHandler);
                if (disclaimer.isDismissed() && legalDisclaimerExitHandler != null) {
                    legalDisclaimerExitHandler.run();
                    legalDisclaimerExitHandler = null;
                }
            }
            inputHandler.update();
            return;
        }
```

The `legalDisclaimerExitHandler = null` line guards against the handler firing twice if `update()` is called again before mode changes.

- [ ] **Step 4: Compile.**

Run: `mvn -DskipTests compile`
Expected: BUILD SUCCESS.

- [ ] **Step 5: Commit.**

```bash
git add src/main/java/com/openggf/GameLoop.java
git commit -m "feat: wire GameLoop step dispatch for LEGAL_DISCLAIMER mode"
```

All trailers `n/a`.

---

## Task 7: Wire `Engine.draw()` branch for the disclaimer

**Files:**
- Modify: `src/main/java/com/openggf/Engine.java`

- [ ] **Step 1: Add the field declaration.**

Around line 147 in `Engine.java`, next to the existing `private MasterTitleScreen masterTitleScreen;` declaration, add:

```java
	private com.openggf.game.LegalDisclaimerScreen legalDisclaimerScreen;
```

(Either keep the fully-qualified form or add an import — match whichever the file already uses for `MasterTitleScreen`.)

- [ ] **Step 2: Add the draw() branch.**

In `Engine.draw()` around line 1388, immediately before the existing `MASTER_TITLE_SCREEN` branch, add:

```java
		if (getCurrentGameMode() == GameMode.LEGAL_DISCLAIMER) {
			if (camera != null) {
				camera.setX((short) 0);
				camera.setY((short) 0);
			}
			if (legalDisclaimerScreen != null) {
				legalDisclaimerScreen.setProjectionMatrix(getProjectionMatrixBuffer());
				legalDisclaimerScreen.draw();
			}
			return;
		}
```

- [ ] **Step 3: Compile.**

Run: `mvn -DskipTests compile`
Expected: BUILD SUCCESS.

- [ ] **Step 4: Commit.**

```bash
git add src/main/java/com/openggf/Engine.java
git commit -m "feat: add LEGAL_DISCLAIMER draw dispatch in Engine.draw()"
```

All trailers `n/a`.

---

## Task 8: Implement `Engine.exitLegalDisclaimer()` and accessor

**Files:**
- Modify: `src/main/java/com/openggf/Engine.java`

- [ ] **Step 1: Add the accessor.**

Below the existing `getMasterTitleScreen()` method (around line 451 in `Engine.java`), add:

```java
	public com.openggf.game.LegalDisclaimerScreen getLegalDisclaimerScreen() {
		return legalDisclaimerScreen;
	}
```

- [ ] **Step 2: Add the exit handler.**

After the existing `exitMasterTitleScreen(String)` method (around line 487), add:

```java
	/**
	 * Called by GameLoop when the disclaimer's fade-to-black completes.
	 * Cleans up the disclaimer GL resources, then either builds the
	 * MasterTitleScreen (if MASTER_TITLE_SCREEN_ON_STARTUP is true) or
	 * runs Phase 2 init directly. In both branches the host owns the
	 * post-disclaimer reveal fade-from-black: none of
	 * enterConfiguredStartupMode's three sub-paths (title screen,
	 * level select, default level load) starts its own intro fade.
	 */
	public void exitLegalDisclaimer() {
		if (legalDisclaimerScreen != null) {
			legalDisclaimerScreen.cleanup();
			legalDisclaimerScreen = null;
		}

		boolean masterTitleOnStartup = configService.getBoolean(SonicConfiguration.MASTER_TITLE_SCREEN_ON_STARTUP);
		if (masterTitleOnStartup) {
			masterTitleScreen = new MasterTitleScreen(configService);
			masterTitleScreen.initialize();
			gameLoop.setGameMode(GameMode.MASTER_TITLE_SCREEN);
		} else {
			initializeGame();
		}

		graphicsManager.getFadeManager().startFadeFromBlack(null);
	}
```

If `SonicConfiguration` is not already imported in `Engine.java`, add the import; if `GameMode` isn't either, add it. Most likely they are; verify before saving.

- [ ] **Step 3: Compile.**

Run: `mvn -DskipTests compile`
Expected: BUILD SUCCESS.

- [ ] **Step 4: Commit.**

```bash
git add src/main/java/com/openggf/Engine.java
git commit -m "feat: implement Engine.exitLegalDisclaimer() with reveal fade"
```

All trailers `n/a`.

---

## Task 9: Wire the `Engine` constructor and `init()` to create the disclaimer

**Files:**
- Modify: `src/main/java/com/openggf/Engine.java`

- [ ] **Step 1: Wire the gameLoop supplier and exit-handler — in the Engine *constructor*, not `init()`.**

The existing `setMasterTitleScreenSupplier` and `setMasterTitleExitHandler`
calls live in the `Engine` constructor (around `Engine.java:200`), where
the `gameLoop` field is freshly assigned. Immediately after those two lines
add:

```java
		this.gameLoop.setLegalDisclaimerScreenSupplier(() -> legalDisclaimerScreen);
		this.gameLoop.setLegalDisclaimerExitHandler(this::exitLegalDisclaimer);
```

The lifecycle is identical to the master-title hooks: supplier returns the
current field value (which may be null until `init()` creates it), and the
exit handler is a method reference that's safe to bind even before any
disclaimer exists.

- [ ] **Step 2: Add the startup gate — in `Engine.init()`.**

This change *does* go in `init()`, around line 386, in the same block as
the existing `MASTER_TITLE_SCREEN_ON_STARTUP` check. Replace the existing
block:

```java
		boolean masterTitleOnStartup = configService.getBoolean(SonicConfiguration.MASTER_TITLE_SCREEN_ON_STARTUP);
		if (masterTitleOnStartup) {
			masterTitleScreen = new MasterTitleScreen(configService);
			masterTitleScreen.initialize();
			gameLoop.setGameMode(GameMode.MASTER_TITLE_SCREEN);
			// Skip Phase 2 entirely - will be called on game selection
		} else {
			// === PHASE 2: ROM loading, sprites, audio, level ===
			initializeGame();
		}
```

with:

```java
		boolean showLegalDisclaimer = configService.getBoolean(SonicConfiguration.SHOW_LEGAL_DISCLAIMER_ON_STARTUP);
		boolean masterTitleOnStartup = configService.getBoolean(SonicConfiguration.MASTER_TITLE_SCREEN_ON_STARTUP);

		if (showLegalDisclaimer) {
			legalDisclaimerScreen = new com.openggf.game.LegalDisclaimerScreen(
					graphicsManager.getFadeManager());
			legalDisclaimerScreen.initialize();
			gameLoop.setGameMode(GameMode.LEGAL_DISCLAIMER);
			// master title (if enabled) or Phase 2 init is deferred to
			// exitLegalDisclaimer once the user dismisses the screen.
		} else if (masterTitleOnStartup) {
			masterTitleScreen = new MasterTitleScreen(configService);
			masterTitleScreen.initialize();
			gameLoop.setGameMode(GameMode.MASTER_TITLE_SCREEN);
			// Skip Phase 2 entirely - will be called on game selection
		} else {
			// === PHASE 2: ROM loading, sprites, audio, level ===
			initializeGame();
		}
```

- [ ] **Step 3: Compile and run focused tests only.**

Run: `mvn -DskipTests compile`
Expected: BUILD SUCCESS.

Then run only the focused tests added so far in this plan:

Run: `mvn "-Dtest=TestLegalDisclaimerState,TestLegalDisclaimerTextWrap,TestInputHandler" test`
Expected: BUILD SUCCESS, all focused tests pass.

**Do NOT run the full `mvn test` here.** The full suite will fail at this
point because Engine-booting tests (`TestEngine`, `TestEngineContext`,
`TestEditorToggleIntegration`) now land in `LEGAL_DISCLAIMER` instead of
their expected mode. Those are corrected in Task 12; the full-suite
checkpoint is at the end of Task 12, not here.

- [ ] **Step 4: Commit.**

```bash
git add src/main/java/com/openggf/Engine.java
git commit -m "feat: gate legal disclaimer screen at engine startup"
```

All trailers `n/a`.

---

## Task 10: Add handoff test that drives the real `GameLoop`

**Files:**
- Create: `src/test/java/com/openggf/game/TestLegalDisclaimerHandoff.java`

This test exercises the actual `GameLoop` ↔ exit-handler wiring — not a
shadow copy of the dispatch logic. It uses Mockito to mock
`LegalDisclaimerScreen` so the GL-bound resources are never constructed,
then drives the real `GameLoop.step()` and asserts the supplier and
exit-handler hooks fire. The construction pattern mirrors
`TestGameLoop.setUp()` (which uses the `new GameLoop(InputHandler)`
constructor at `GameLoop.java:228`).

- [ ] **Step 1: Write the test.**

Create `src/test/java/com/openggf/game/TestLegalDisclaimerHandoff.java`:

```java
package com.openggf.game;

import com.openggf.GameLoop;
import com.openggf.control.InputHandler;
import com.openggf.game.session.EngineContext;
import com.openggf.game.session.EngineServices;
import com.openggf.game.session.SessionManager;
import com.openggf.game.sonic2.Sonic2GameModule;
import com.openggf.tests.TestEnvironment;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Drives the real {@link GameLoop} with a Mockito-mocked
 * {@link LegalDisclaimerScreen} to verify that {@code step()}:
 *   1. calls {@code disclaimer.update(...)} every frame while in
 *      {@link GameMode#LEGAL_DISCLAIMER},
 *   2. invokes the registered exit handler exactly once when
 *      {@code isDismissed()} flips to {@code true},
 *   3. honors a mode transition performed inside the exit handler so
 *      the disclaimer stops being polled on subsequent frames.
 *
 * <p>The third assertion catches the failure mode where the dispatch
 * fires the handler but the handler (or {@code Engine.exitLegalDisclaimer})
 * fails to change the game mode — the disclaimer would otherwise be
 * polled forever with the handler permanently nulled.
 *
 * <p>This test does <strong>not</strong> exercise the real
 * {@link com.openggf.Engine#exitLegalDisclaimer} because that method
 * touches GL ({@code cleanup}, {@code MasterTitleScreen.initialize},
 * {@code startFadeFromBlack}) and ROM init ({@code initializeGame}),
 * which require the full singleton/OpenGL stack. The post-fade behavior
 * (GL cleanup, master-title build, reveal fade) is covered by manual
 * launch verification in Task 13.
 *
 * <p>Construction follows the existing {@link com.openggf.TestGameLoop}
 * pattern: full singleton stack via {@link EngineServices#configure} and
 * {@link TestEnvironment#activeGameplayMode()}, plus the
 * {@code GameLoop(InputHandler)} test constructor.
 */
class TestLegalDisclaimerHandoff {

    private GameLoop gameLoop;
    private InputHandler inputHandler;
    private LegalDisclaimerScreen mockScreen;

    @BeforeEach
    void setUp() {
        EngineServices.configure(EngineContext.fromLegacySingletonsForBootstrap());
        GameModuleRegistry.setCurrent(new Sonic2GameModule());
        TestEnvironment.activeGameplayMode();
        inputHandler = mock(InputHandler.class);
        gameLoop = new GameLoop(inputHandler);
        mockScreen = mock(LegalDisclaimerScreen.class);
    }

    @AfterEach
    void tearDown() {
        SessionManager.clear();
        GameModuleRegistry.setCurrent(new Sonic2GameModule());
    }

    @Test
    void stepCallsDisclaimerUpdateWhileInLegalDisclaimerMode() {
        gameLoop.setLegalDisclaimerScreenSupplier(() -> mockScreen);
        gameLoop.setLegalDisclaimerExitHandler(() -> { });
        gameLoop.setGameMode(GameMode.LEGAL_DISCLAIMER);
        when(mockScreen.isDismissed()).thenReturn(false);

        gameLoop.step();
        gameLoop.step();
        gameLoop.step();

        verify(mockScreen, times(3)).update(inputHandler);
    }

    @Test
    void exitHandlerFiresAndAdvancesGameMode() {
        AtomicInteger exitCalls = new AtomicInteger();
        // Stand-in for Engine.exitLegalDisclaimer's mode-set responsibility.
        // The real method also cleans up GL and starts a reveal fade; those
        // are exercised by manual launch (see Task 13). Here we verify the
        // contract that matters at the GameLoop layer: the handler must
        // advance the game mode out of LEGAL_DISCLAIMER.
        Runnable exitHandler = () -> {
            exitCalls.incrementAndGet();
            gameLoop.setGameMode(GameMode.MASTER_TITLE_SCREEN);
        };
        gameLoop.setLegalDisclaimerScreenSupplier(() -> mockScreen);
        gameLoop.setLegalDisclaimerExitHandler(exitHandler);
        gameLoop.setGameMode(GameMode.LEGAL_DISCLAIMER);

        // Frame 1: not dismissed → mode stays LEGAL_DISCLAIMER.
        when(mockScreen.isDismissed()).thenReturn(false);
        gameLoop.step();
        assertEquals(0, exitCalls.get());
        assertEquals(GameMode.LEGAL_DISCLAIMER, gameLoop.getCurrentGameMode());

        // Frame 2: dismissed → handler fires, mode advances.
        when(mockScreen.isDismissed()).thenReturn(true);
        gameLoop.step();
        assertEquals(1, exitCalls.get());
        assertEquals(GameMode.MASTER_TITLE_SCREEN, gameLoop.getCurrentGameMode());

        // Frame 3: mode is now MASTER_TITLE_SCREEN → disclaimer no longer
        // polled. mockScreen.update was called on frames 1 and 2 only.
        gameLoop.step();
        assertEquals(1, exitCalls.get());
        verify(mockScreen, times(2)).update(inputHandler);
    }

    @Test
    void exitHandlerThatLeavesModeInLegalDisclaimerStillFiresOnlyOnce() {
        // Pathological case: handler is invoked but fails to change mode.
        // The dispatch nulls the handler after first fire, so we should
        // not re-invoke even though isDismissed() keeps returning true.
        AtomicInteger exitCalls = new AtomicInteger();
        gameLoop.setLegalDisclaimerScreenSupplier(() -> mockScreen);
        gameLoop.setLegalDisclaimerExitHandler(exitCalls::incrementAndGet);
        gameLoop.setGameMode(GameMode.LEGAL_DISCLAIMER);
        when(mockScreen.isDismissed()).thenReturn(true);

        gameLoop.step();
        gameLoop.step();
        gameLoop.step();

        assertEquals(1, exitCalls.get(),
                "handler must not fire repeatedly even if mode is stuck");
    }

    @Test
    void otherGameModesDoNotTouchDisclaimer() {
        gameLoop.setLegalDisclaimerScreenSupplier(() -> mockScreen);
        gameLoop.setLegalDisclaimerExitHandler(() -> { });
        gameLoop.setGameMode(GameMode.MASTER_TITLE_SCREEN);

        gameLoop.step();

        verify(mockScreen, never()).update(inputHandler);
        verify(mockScreen, never()).isDismissed();
    }
}
```

- [ ] **Step 2: Run the test.**

Run: `mvn "-Dtest=TestLegalDisclaimerHandoff" test`
Expected: BUILD SUCCESS, 4 tests pass.

The tests call `gameLoop.step()` directly and verify via Mockito that
`mockScreen.update(...)` runs. Because that verification depends on the
real `GameLoop.step()` branch added in Task 6, a missing or mis-named
branch would cause `verify(...).update(...)` to fail with zero
invocations — no shadow dispatch path can silently make this pass.

`exitHandlerFiresAndAdvancesGameMode` additionally asserts that the
handler's mode transition takes effect on the next frame: if a future
edit to the GameLoop dispatch or this handler contract fails to change
the mode, the test fails because frame 3's call would still re-enter
the LEGAL_DISCLAIMER branch and `update()` would be called a third
time. The test exercises the GameLoop ↔ handler contract with a fake
handler; the real `Engine.exitLegalDisclaimer` is covered by Task 13
manual launch.

If a reviewer wants extra confidence, they may locally and temporarily
comment out the Task 6 branch and re-run the test to watch it fail.
That confirmation step is **not** a required plan checkpoint — it is
optional reviewer diligence — to avoid the risk of an implementer
forgetting to restore the branch in a partial commit. The test itself,
unmodified, is the contract.

- [ ] **Step 3: Commit.**

```bash
git add src/test/java/com/openggf/game/TestLegalDisclaimerHandoff.java
git commit -m "test: cover LEGAL_DISCLAIMER GameLoop dispatch with mock screen"
```

All trailers `n/a`.

---

## Task 11: Update user-facing and agent-facing documentation

**Files:**
- Modify: `CONFIGURATION.md`
- Modify: `CHANGELOG.md`
- Modify: `README.md`
- Modify: `AGENTS.md`
- Modify: `CLAUDE.md`

- [ ] **Step 1: Read existing entries.**

```bash
grep -n "MASTER_TITLE_SCREEN_ON_STARTUP" CONFIGURATION.md
```

Pick the formatting that file uses (table row vs. heading vs. bullet).

- [ ] **Step 2: Add a `SHOW_LEGAL_DISCLAIMER_ON_STARTUP` entry to `CONFIGURATION.md`.**

Insert next to or below the `MASTER_TITLE_SCREEN_ON_STARTUP` entry, matching the file's style. Example bullet form:

```markdown
- `SHOW_LEGAL_DISCLAIMER_ON_STARTUP` (boolean, default `true`) — show the
  legal disclaimer screen on engine startup before the master title screen.
  Set `false` for headless tests, trace replay sessions, or CI runs that
  should not have to drive past a 5-second screen.
```

- [ ] **Step 3: Add a release-notes entry to `CHANGELOG.md`.**

Under the next-release / Unreleased section, add:

```markdown
### Added

- Legal disclaimer screen shown on engine startup before the master title
  screen. White text on black, 5-second readability gate, any-key dismiss,
  fade-in/out/master-title-fade-in transitions. Toggle with the new
  `SHOW_LEGAL_DISCLAIMER_ON_STARTUP` config key.
```

- [ ] **Step 4: Update `README.md`.**

Find the "Disclaimer:" block (around line 19) and add a sentence noting the in-engine screen:

```markdown
> The disclaimer is also shown in-engine on startup; it can be disabled by
> setting `SHOW_LEGAL_DISCLAIMER_ON_STARTUP=false` in `config.json`.
```

- [ ] **Step 5: Update `AGENTS.md` and `CLAUDE.md`.**

Both files document agent-facing repository context. The Engine startup
order is part of that context, and a future agent reasoning about
`Engine.init()` or `GameMode` should know `LEGAL_DISCLAIMER` runs first
when the flag is on.

Locate the section that describes the master title screen / startup flow.
In each file, append a short note adjacent to the existing master-title
mention:

```markdown
**Startup order:** `Engine.init()` now boots through `GameMode.LEGAL_DISCLAIMER` first when `SHOW_LEGAL_DISCLAIMER_ON_STARTUP=true` (the default). The disclaimer screen owns a `FadeManager` reveal, a 5-second readability gate, and a fade-to-black on dismiss; control then chains into the existing `MASTER_TITLE_SCREEN` or direct-gameplay path inside `Engine.exitLegalDisclaimer()`. Set the flag `false` in tests that boot the full `Engine`.
```

Place it under whatever heading already covers Engine boot / startup. If
neither file has an obvious section, put it in the same place as the
existing `MASTER_TITLE_SCREEN_ON_STARTUP` mention. If neither file
mentions the master title screen, add the note in the architecture /
boot-order section that's closest to that level of detail.

- [ ] **Step 6: Commit.**

```bash
git add CONFIGURATION.md CHANGELOG.md README.md AGENTS.md CLAUDE.md
git commit -m "docs: document SHOW_LEGAL_DISCLAIMER_ON_STARTUP and in-engine notice"
```

Trailer block:
- `Changelog: updated`
- `Configuration-Docs: updated`
- `Agent-Docs: updated`
- `Guide: n/a`
- `Known-Discrepancies: n/a`
- `S3K-Known-Discrepancies: n/a`
- `Skills: n/a`

---

## Task 12: Disable disclaimer in every test that calls `Engine.init()`

**Files (initial scope — re-verify in Step 1):**
- `src/test/java/com/openggf/TestEngine.java`
- `src/test/java/com/openggf/game/TestEngineContext.java`
- `src/test/java/com/openggf/editor/TestEditorToggleIntegration.java`

`HeadlessTestRunner` does **not** go through `Engine.init()` (it boots
managers directly via `TestEnvironment` + `GameServices`), so the
disclaimer screen never affects HeadlessTestRunner-driven tests. The risk
is concentrated in the small set of tests that call
`new Engine(...).init()` or otherwise drive the full startup path.

- [ ] **Step 1: Identify every Engine.init() caller.**

Run these searches and union the results. Some patterns may match the same
file; that's fine.

```bash
grep -rln "new Engine(" src/test
grep -rln "Engine\.init(" src/test
grep -rln "engine\.init(" src/test
grep -rln "StartupRouteResolver" src/test
```

Also grep for anything that sets `MASTER_TITLE_SCREEN_ON_STARTUP=true` or
relies on the default behavior:

```bash
grep -rn "MASTER_TITLE_SCREEN_ON_STARTUP" src/test
```

For every file in the union, decide:

- **Test is not about the disclaimer:** set
  `SHOW_LEGAL_DISCLAIMER_ON_STARTUP=false` before the engine boots.
- **Test asserts post-disclaimer mode (e.g. expects to land in
  MASTER_TITLE_SCREEN):** same — set the flag false. Without this, the
  test will now land in `LEGAL_DISCLAIMER` instead of
  `MASTER_TITLE_SCREEN` and fail.
- **Test is explicitly about the disclaimer:** leave the flag true.

- [ ] **Step 2: Add the setting in each identified test.**

The exact form depends on how the test wires its `SonicConfigurationService`.
Two common shapes; pick the one each test already uses for the
master-title flag (or a similar boolean):

```java
configService.setConfigValue(SonicConfiguration.SHOW_LEGAL_DISCLAIMER_ON_STARTUP, false);
```

or, if the test uses a `Map<String, Object>` to seed the service:

```java
configMap.put(SonicConfiguration.SHOW_LEGAL_DISCLAIMER_ON_STARTUP.name(), false);
```

**Add only `SHOW_LEGAL_DISCLAIMER_ON_STARTUP=false`. Do not touch
`MASTER_TITLE_SCREEN_ON_STARTUP`.**

Setting the disclaimer flag false is sufficient: with the disclaimer
disabled, `Engine.init()` falls through to its existing
`MASTER_TITLE_SCREEN_ON_STARTUP` check, so a test that expected to land in
`MASTER_TITLE_SCREEN` still lands there, and a test that expected
direct-gameplay startup still gets that. Adding both flags would
silently re-route tests that legitimately expect the master title screen
into direct-gameplay mode and mask real bugs.

If a test does already disable `MASTER_TITLE_SCREEN_ON_STARTUP`, just add
the disclaimer flag next to it. If it does not, add only the disclaimer
flag — that preserves the test's current startup expectation.

- [ ] **Step 3: Run the full test suite.**

Run: `mvn test`
Expected: BUILD SUCCESS. No tests hang, no tests fail because of the
disclaimer.

If a test now hangs (e.g., is waiting for a 5-second readability gate it
doesn't know about), it boots the engine via a path you missed. Find it
with:

```bash
grep -rn "new Engine\|StartupRouteResolver\|gameLoop\.setGameMode" src/test
```

Update the flag in the failing test and re-run.

- [ ] **Step 4: Commit.**

```bash
git add src/test
git commit -m "test: disable legal disclaimer in Engine.init() test callers"
```

All trailers `n/a`.

---

## Task 13: Manual launch verification

This is not a code step but it is required before claiming the feature done.

- [ ] **Step 1: Build the jar.**

```bash
mvn package -DskipTests
```

- [ ] **Step 2: Launch with defaults.**

```bash
java -jar target/OpenGGF-0.6.prerelease-jar-with-dependencies.jar
```

Verify:
- Disclaimer text fades in from black.
- 5 seconds elapse before the "Press any key to continue" prompt appears.
- Prompt pulses smoothly.
- Pressing any key triggers fade-to-black.
- Master title screen fades from black.
- Game-select menu works as before.

- [ ] **Step 3: Launch with `SHOW_LEGAL_DISCLAIMER_ON_STARTUP=false`.**

Edit `config.json` and set the flag to `false`. Re-launch. Verify:
- No disclaimer screen.
- Master title appears as before.

- [ ] **Step 4: Launch with `MASTER_TITLE_SCREEN_ON_STARTUP=false` and `TITLE_SCREEN_ON_STARTUP=false` and `LEVEL_SELECT_ON_STARTUP=false`.**

Keep `SHOW_LEGAL_DISCLAIMER_ON_STARTUP=true`. Verify after dismissing the disclaimer the screen fades back in to gameplay (not stuck on black). This validates the direct-gameplay reveal-fade fix from spec finding #2.

- [ ] **Step 5: Restore `config.json`.**

Reset the modified flags. Optionally commit any new `config.json` defaults if the repo's `config.json` is tracked (verify with `git status` first; do not commit the local config if it contains personal paths).

---

## Self-Review

Spec coverage:

- Hardcoded disclaimer text (spec line ~210) → Task 5 (as paragraphs, wrapped at draw time)
- Runtime word-wrap honoring spec layout (spec line ~250) → Task 5 wrap helper + test
- Body fits before dismiss prompt vertically → Task 5 `bodyParagraphsFitBetweenStartYAndPromptY` test
- New `GameMode.LEGAL_DISCLAIMER` (spec line ~99) → Task 1
- New `SHOW_LEGAL_DISCLAIMER_ON_STARTUP` config (spec line ~88) → Task 2 + Task 11
- `InputHandler.isAnyKeyJustPressed()` (spec line ~88) → Task 3
- State machine (spec line ~63) → Task 4
- GL-bound screen (spec line ~32) → Task 5
- `GameLoop.step()` branch (spec line ~101) → Task 6
- `Engine.draw()` branch (spec line ~120) → Task 7
- `exitLegalDisclaimer` with unconditional reveal fade (spec line ~165) → Task 8
- Constructor-time supplier hooks + `init()` startup gate (spec line ~141) → Task 9
- Unit test of state machine → Task 4
- Handoff test using real `GameLoop` + mocked screen, asserting mode transitions out of `LEGAL_DISCLAIMER` (spec line ~354) → Task 10
- `Engine.exitLegalDisclaimer` GL/ROM-bound post-fade work (cleanup, master title build, reveal fade) → not unit-tested; covered by Task 13 manual launch
- Manual launch with direct-gameplay path (spec line ~300) → Task 13
- Test harness skip across every `Engine.init()` caller (spec line ~302) → Task 12
- `AGENTS.md` / `CLAUDE.md` note about new boot phase (spec line ~398) → Task 11 Step 5

All spec items covered.

Type consistency: `LegalDisclaimerState.Phase` is the only enum-typed surface, used identically in tests and state class. `isAnyKeyJustPressed()` is named identically in the test, the state's `tick(boolean)` parameter, and the screen's `update()` call. `exitLegalDisclaimer()` is named identically in the handler interface (`Runnable`), `Engine`, and the `setLegalDisclaimerExitHandler` setter. `wrapParagraph(String, ToIntFunction<String>, int)` signature matches between the test, the screen's static method, and the screen's `draw()` call site.

No placeholders.

---

## Out-of-scope follow-ups

These are noted for the future, not in scope here:

- "Accept once and remember" persistence — would require a new persisted-state file or `config.json` entry capturing acceptance. Spec deliberately uses a per-launch config flag instead.
- Localization of the disclaimer text.
- Logo / branding artwork on the disclaimer background.
- Audio cue on key press.
