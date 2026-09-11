# S3K Gumball Glass Layer Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Restore correct Gumball machine layering so the internal pile is masked by the machine body while the front glass/door layer remains visible above the pile.

**Architecture:** Keep the current CPU-side SAT collection path, but stop treating frame `0x17` as ordinary drawable art inside the same replay stream. Instead, interpret the `0x7C0` helper pair as SAT mask-control markers derived from ROM post-processing semantics, then replay the surviving visible machine pieces in the correct front-to-back order. Preserve the current reward-ball bucket behavior unless new evidence disproves it.

**Tech Stack:** Java, LWJGL/OpenGL renderer, JUnit, Sonic 3K disassembly references

---

### Task 1: Freeze The Currently Working Parts

**Files:**
- Modify: `src/test/java/com/openggf/graphics/TestSpriteSatMaskPostProcessor.java`
- Modify: `src/test/java/com/openggf/level/render/TestSpritePieceRendererSatPreparation.java`
- Test: `src/test/java/com/openggf/game/sonic3k/objects/TestGumballItemPriority.java`

- [ ] **Step 1: Add an explicit regression assertion that the helper pair itself is not replayed as visible art**

- [ ] **Step 2: Add an explicit regression assertion that the current reward-ball priority remains unchanged**

- [ ] **Step 3: Run the focused manual test set and keep it green before touching replay logic**

Run:
```powershell
$launcher = 'C:\Users\farre\.m2\repository\org\junit\platform\junit-platform-launcher\1.10.3\junit-platform-launcher-1.10.3.jar'
$cp = 'target\classes;target\test-classes;' + $launcher + ';' + (Get-Content .tmp_classpath.txt)
New-Item -ItemType Directory -Force target\manual-test-classes | Out-Null
javac -cp $cp -d target\manual-test-classes target\ManualJupiterRunner.java `
  src\test\java\com\openggf\graphics\TestSpriteSatMaskPostProcessor.java `
  src\test\java\com\openggf\level\render\TestSpritePieceRendererSatPreparation.java `
  src\test\java\com\openggf\sprites\render\TestPlayerSpriteRendererPaletteContext.java `
  src\test\java\com\openggf\game\sonic3k\objects\TestGumballItemPriority.java
java -cp ('target\manual-test-classes;' + $cp) ManualJupiterRunner
```

Expected:
```text
tests=8 failed=0 succeeded=8
```

### Task 2: Replace “Band Clip” Semantics With Helper-Control Semantics

**Files:**
- Modify: `src/main/java/com/openggf/graphics/SpriteSatMaskPostProcessor.java`
- Modify: `src/main/java/com/openggf/graphics/SpriteSatEntry.java`
- Test: `src/test/java/com/openggf/graphics/TestSpriteSatMaskPostProcessor.java`

- [ ] **Step 1: Extend SAT processing so a `0x7C0` marker pair becomes a control record, not a drawable replay candidate**

- [ ] **Step 2: Make the control record describe the intended masked scanline interval independently of normal piece replay**

- [ ] **Step 3: Preserve the original SAT order of all non-helper pieces through post-processing**

- [ ] **Step 4: Add a focused red test for “mask control entries do not consume front-layer glass ordering”**

### Task 3: Separate Visible Front Layer From Mask Control

**Files:**
- Modify: `src/main/java/com/openggf/game/sonic3k/objects/GumballMachineObjectInstance.java`
- Modify: `src/main/java/com/openggf/level/render/PatternSpriteRenderer.java`
- Modify: `src/main/java/com/openggf/graphics/GraphicsManager.java`
- Test: `src/test/java/com/openggf/graphics/TestSpriteSatMaskPostProcessor.java`

- [ ] **Step 1: Identify which machine-visible front pieces must survive above the pile after mask processing**

- [ ] **Step 2: Replay those visible front pieces after mask control has been established, without reintroducing the helper garbage tiles**

- [ ] **Step 3: Keep the `0x16` mixed-priority mapping data intact and avoid another object-wide priority override**

- [ ] **Step 4: Add a focused red test for “machine front layer remains visible above masked pile”**

### Task 4: Verify Against The Known Problem Cases

**Files:**
- Modify: `src/test/java/com/openggf/graphics/TestSpriteSatMaskPostProcessor.java`
- Modify: `src/test/java/com/openggf/level/render/TestSpritePieceRendererSatPreparation.java`

- [ ] **Step 1: Run `mvn -q -DskipTests compile`**

- [ ] **Step 2: Run the focused manual test set again**

- [ ] **Step 3: Recheck the Gumball startup scene visually**

- [ ] **Step 4: Confirm these exact outcomes**

Expected:
```text
1. The internal pile is masked by the machine body again.
2. The front glass/door layer appears above the pile.
3. The helper pair does not show garbage tiles before or after the mask region.
4. The spawned reward ball still appears in front of the machine body but behind the door/glass layer.
```
