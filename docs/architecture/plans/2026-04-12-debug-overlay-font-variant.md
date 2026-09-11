# Debug Overlay Pixel Font Variant Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add enum-backed pixel font selection so the debug overlay can use the no-shadow sheet while all existing callers keep the current font by default.

**Architecture:** Introduce a small `PixelFontVariant` enum in `com.openggf.graphics` and thread it into `PixelFontTextRenderer` via a new constructor overload. `DebugRenderer` opts into `PIXEL_FONT_NO_SHADOW`, while tests verify both the default and explicit variant resource path selection.

**Tech Stack:** Java 21, JUnit 5

**Spec:** `docs/architecture/designs/2026-04-12-debug-overlay-font-variant-design.md`

---

### Task 1: Add failing renderer tests for font variant selection

**Files:**
- Modify: `src/test/java/com/openggf/graphics/TestPixelFontTextRenderer.java`

- [ ] **Step 1: Write the failing tests**

```java
@Test
void defaultConstructor_usesDefaultPixelFontResource() {
    FakePixelFont font = new FakePixelFont();
    TestablePixelFontTextRenderer renderer = new TestablePixelFontTextRenderer(font);

    renderer.drawSampleText();

    assertEquals("pixel-font.png", font.lastFontPath);
}

@Test
void variantConstructor_usesSelectedPixelFontResource() {
    FakePixelFont font = new FakePixelFont();
    TestablePixelFontTextRenderer renderer =
            new TestablePixelFontTextRenderer(font, PixelFontVariant.PIXEL_FONT_NO_SHADOW);

    renderer.drawSampleText();

    assertEquals("pixel-font-ns.png", font.lastFontPath);
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q -Dtest=TestPixelFontTextRenderer test`

Expected: FAIL because `PixelFontTextRenderer` does not yet expose variant-based resource selection.

### Task 2: Implement enum-backed font selection

**Files:**
- Create: `src/main/java/com/openggf/graphics/PixelFontVariant.java`
- Modify: `src/main/java/com/openggf/graphics/PixelFontTextRenderer.java`

- [ ] **Step 1: Add the enum**

```java
package com.openggf.graphics;

public enum PixelFontVariant {
    PIXEL_FONT("pixel-font.png"),
    PIXEL_FONT_NO_SHADOW("pixel-font-ns.png");

    private final String resourcePath;

    PixelFontVariant(String resourcePath) {
        this.resourcePath = resourcePath;
    }

    public String resourcePath() {
        return resourcePath;
    }
}
```

- [ ] **Step 2: Update `PixelFontTextRenderer`**

```java
private final PixelFontVariant fontVariant;

public PixelFontTextRenderer() {
    this(new PixelFont(), PixelFontVariant.PIXEL_FONT);
}

public PixelFontTextRenderer(PixelFontVariant fontVariant) {
    this(new PixelFont(), fontVariant);
}

protected PixelFontTextRenderer(PixelFont font) {
    this(font, PixelFontVariant.PIXEL_FONT);
}

protected PixelFontTextRenderer(PixelFont font, PixelFontVariant fontVariant) {
    this.font = Objects.requireNonNull(font, "font");
    this.fontVariant = Objects.requireNonNull(fontVariant, "fontVariant");
}

font.init(fontVariant.resourcePath(), renderer);
```

- [ ] **Step 3: Run test to verify it passes**

Run: `mvn -q -Dtest=TestPixelFontTextRenderer test`

Expected: PASS

### Task 3: Opt the debug overlay into the no-shadow font

**Files:**
- Modify: `src/main/java/com/openggf/debug/DebugRenderer.java`

- [ ] **Step 1: Update the renderer construction**

```java
this.performanceTextRenderer = new PixelFontTextRenderer(PixelFontVariant.PIXEL_FONT_NO_SHADOW);
```

- [ ] **Step 2: Run focused verification**

Run: `mvn -q -Dtest=TestPixelFontTextRenderer,TestDebugOverlayManagerReset test`

Expected: PASS
