# S3K Sprite Mask Post-Pass Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement a ROM-grounded CPU-side `Spritemask_flag` post-pass so the Gumball machine's helper mask suppresses later sprite content correctly.

**Architecture:** Keep tile-priority compositing in the existing shader path, but add a sprite-order-aware post-processor before final sprite draw submission. The new logic should operate on final ordered sprite pieces, detect mask helpers from ROM-backed data, and suppress later pieces in the same frame without adding Gumball-specific shader hacks.

**Tech Stack:** Java, LWJGL/OpenGL renderer, JUnit, Sonic 3K disassembly references

---

### Task 1: Add Failing Mask Tests

**Files:**
- Modify: `src/test/java/com/openggf/game/sonic3k/TestGumballFgPriorityDiagnostics.java`
- Create: `src/test/java/com/openggf/graphics/TestSpriteMaskPostPass.java`

- [ ] **Step 1: Write the failing tests**
- [ ] **Step 2: Run the focused tests to verify they fail for the expected reason**
- [ ] **Step 3: Commit the test-only change if the worktree strategy allows**

### Task 2: Add Ordered Sprite-Mask Post-Processor

**Files:**
- Create: `src/main/java/com/openggf/graphics/SpriteMaskPostProcessor.java`
- Create: `src/main/java/com/openggf/graphics/SpritePieceSubmission.java`
- Modify: `src/main/java/com/openggf/graphics/GraphicsManager.java`
- Modify: `src/main/java/com/openggf/graphics/PatternRenderCommand.java`
- Modify: `src/main/java/com/openggf/graphics/BatchedPatternRenderer.java`
- Modify: `src/main/java/com/openggf/graphics/InstancedPatternRenderer.java`

- [ ] **Step 1: Introduce immutable sprite-piece submission records carrying order, bounds, tile, and priority**
- [ ] **Step 2: Collect ordered sprite pieces before final draw submission**
- [ ] **Step 3: Apply the mask post-pass when the frame raised `Spritemask_flag`**
- [ ] **Step 4: Submit only surviving pieces to the existing draw path**

### Task 3: Wire ROM Mask Sources

**Files:**
- Modify: `src/main/java/com/openggf/game/sonic3k/objects/GumballMachineObjectInstance.java`
- Modify: `src/main/java/com/openggf/level/render/PatternSpriteRenderer.java`
- Modify: `src/main/java/com/openggf/level/LevelManager.java`

- [ ] **Step 1: Ensure the helper frame `0x17` contributes enough information for mask detection without special-casing geometry**
- [ ] **Step 2: Expose per-piece tile index/order data needed by the post-processor**
- [ ] **Step 3: Keep bucket ordering and tile-priority shader behavior unchanged**

### Task 4: Verify Gumball Composition

**Files:**
- Modify: `src/test/java/com/openggf/game/sonic3k/TestGumballFgPriorityDiagnostics.java`
- Modify: `src/test/java/com/openggf/graphics/TestSpriteMaskPostPass.java`

- [ ] **Step 1: Run focused tests and make them pass**
- [ ] **Step 2: Run `mvn -q -DskipTests compile`**
- [ ] **Step 3: Run the smallest targeted Maven test set for the new mask behavior**
- [ ] **Step 4: Report residual gaps if visual parity still needs in-engine confirmation**
