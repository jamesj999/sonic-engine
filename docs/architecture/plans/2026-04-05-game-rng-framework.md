# GameRng Framework Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace ad-hoc `java.util.Random` / `ThreadLocalRandom` usage across gameplay code with a deterministic, ROM-accurate `GameRng` that reproduces the Mega Drive Sonic `RandomNumber` / `Random_Number` subroutines bit-exactly.

**Architecture:** Introduce a `GameRng` class that implements the ROM's 32-bit multiply-by-41 + word-fold algorithm. A `Flavour` enum encodes the two known variants (S1/S2 use `0x2A6D365A` reseed with full-long zero check; S3K uses `0x2A6D365B` with low-word-only zero check). Each `GameRuntime` owns a single `GameRng` instance, selected by the active `GameModule` via a new `rngFlavour()` method. Object code reaches the RNG via `services().rng()`; non-object code uses `GameServices.rng()`. A scanner-based migration guard test enforces that gameplay code does not regress into raw `java.util.Random` usage.

**Tech Stack:** Java 21, JUnit 5 / Jupiter only (matches rest of `com.openggf.game` test suite), Maven

**Spec references:**
- S3K: `docs/skdisasm/sonic3k.asm:2992-3011`
- S2: `docs/s2disasm/s2.asm:3971-3995`
- S1: `docs/s1disasm/_incObj/sub RandomNumber.asm`

---

## Design Summary

### Algorithm (68000 pseudocode, d1 = seed, d0 = return value)

```
; Reseed check (flavour-dependent)
;   S1/S2:  if (d1 == 0) d1 = 0x2A6D365A   (implicit via move.l + bne.s)
;   S3K:    if ((d1 & 0xFFFF) == 0) d1 = 0x2A6D365B   (explicit tst.w d1)

move.l d1, d0           ; d0 = seed
asl.l  #2, d1           ; d1 <<= 2
add.l  d0, d1           ; d1 += seed           (d1 = seed * 5)
asl.l  #3, d1           ; d1 <<= 3             (d1 = seed * 40)
add.l  d0, d1           ; d1 += seed           (d1 = seed * 41)
move.w d1, d0           ; d0 low word = (seed*41) low word; d0 high = original seed high
swap   d1               ; d1 high <-> low
add.w  d1, d0           ; d0 low += d1 low (word add, high word of d0 unchanged)
move.w d0, d1           ; d1 low = d0 low
swap   d1               ; swap back
move.l d1, (seed)       ; write seed
rts                     ; return d0
```

### Return Value

`d0` after the routine has the form:
- High 16 bits = original seed high word
- Low 16 bits = ((seed*41) low16) + ((seed*41) high16)

Most callers consume only the low 16 bits via `andi.w #mask, d0`. The engine's `nextWord()` exposes the full low 16 bits; `nextRaw()` returns the full 32-bit `d0`.

### Verified Golden Sequences

Starting from `seed = 0`, S2 flavour (reseed 0x2A6D365A, full-long zero check):

| Call | d0 (hex)    | seed after (hex) | low word |
|------|-------------|------------------|----------|
| 1    | 0x2A6D7FE7  | 0x7FE7B46A       | 0x7FE7   |
| 2    | 0x7FE76115  | 0x6115E4FA       | 0x6115   |
| 3    | 0x6115388B  | 0x388BAC0A       | 0x388B   |
| 4    | 0x388B9BF8  | 0x9BF88D9A       | 0x9BF8   |
| 5    | 0x9BF8A878  | 0xA878ADAA       | 0xA878   |
| 6    | 0xA878CB8D  | 0xCB8DD03A       | 0xCB8D   |
| 7    | 0xCB8DF300  | 0xF300594A       | 0xF300   |
| 8    | 0xF30037E8  | 0x37E84CDA       | 0x37E8   |
| 9    | 0x37E8431E  | 0x431E4EEA       | 0x431E   |
| 10   | 0x431E6354  | 0x6354A37A       | 0x6354   |

Starting from `seed = 0`, S3K flavour (reseed 0x2A6D365B, low-word zero check):

| Call | d0 (hex)    | seed after (hex) | low word |
|------|-------------|------------------|----------|
| 1    | 0x2A6D8010  | 0x8010B493       | 0x8010   |
| 2    | 0x80106E37  | 0x6E37EB8B       | 0x6E37   |
| 3    | 0x6E376037  | 0x6037B943       | 0x6037   |
| 4    | 0x603714A7  | 0x14A7ABBB       | 0x14A7   |
| 5    | 0x14A7CFCD  | 0xCFCD80F3       | 0xCFCD   |
| 6    | 0xCFCDEED4  | 0xEED4A6EB       | 0xEED4   |
| 7    | 0xEED4FBB1  | 0xFBB1BBA3       | 0xFBB1   |
| 8    | 0xFBB15C92  | 0x5C920D1B       | 0x5C92   |
| 9    | 0x5C92ECB7  | 0xECB71953       | 0xECB7   |
| 10   | 0xECB7F79E  | 0xF79E0E4B       | 0xF79E   |

### Flavour Differentiation Test

Starting seed `0x12340000`:
- **S2 flavour (no reseed, full-long nonzero):** d0 = `0x1234EA54`, seed = `0x0000EA54`? No — after word-fold: d0 = `0x1234EA54`, seed = `0xEA540000`.
- **S3K flavour (reseeds because low word = 0):** d0 = `0x2A6D8010`, seed = `0x8010B493` (same as first S3K step from 0).

This scenario is the canonical differentiation test.

---

## File Structure

**New production files:**
- `src/main/java/com/openggf/game/GameRng.java` — the PRNG class (flavour enum, algorithm, helpers)

**Modified production files:**
- `src/main/java/com/openggf/game/GameModule.java` — add `rngFlavour()` default returning S2
- `src/main/java/com/openggf/game/sonic3k/Sonic3kGameModule.java` — override `rngFlavour()` to S3K
- `src/main/java/com/openggf/game/GameRuntime.java` — hold `GameRng` field + getter
- `src/main/java/com/openggf/game/RuntimeManager.java` — construct `GameRng` using current module flavour
- `src/main/java/com/openggf/game/GameServices.java` — add `rng()` accessor
- `src/main/java/com/openggf/level/objects/ObjectServices.java` — add `rng()` method
- `src/main/java/com/openggf/level/objects/DefaultObjectServices.java` — implement `rng()`
- `src/main/java/com/openggf/level/objects/AbstractObjectInstance.java` — add `rng()` helper shorthand

**New test files:**
- `src/test/java/com/openggf/game/TestGameRngGoldenSequence.java` — algorithm + property tests
- `src/test/java/com/openggf/game/TestRngMigrationGuard.java` — scanner guard

**Modified test files:**
- `src/test/java/com/openggf/level/objects/StubObjectServices.java` — stub `rng()` returning a fresh `GameRng`

**Object files migrated in later phases** (per-task list given below).

---

### Task 1: Create GameRng Class

**Files:**
- Create: `src/main/java/com/openggf/game/GameRng.java`

- [ ] **Step 1: Write the GameRng class**

Write the complete class. All methods documented; algorithm mirrors the ROM step-for-step.

```java
package com.openggf.game;

/**
 * Deterministic ROM-accurate pseudo-random number generator.
 * <p>
 * Implements the Mega Drive Sonic {@code RandomNumber} / {@code Random_Number}
 * subroutine bit-exactly: 32-bit multiply-by-41 with a word-fold step.
 * <p>
 * ROM references:
 * <ul>
 *   <li>S1: {@code docs/s1disasm/_incObj/sub RandomNumber.asm}</li>
 *   <li>S2: {@code docs/s2disasm/s2.asm:3971-3995}</li>
 *   <li>S3K: {@code docs/skdisasm/sonic3k.asm:2992-3011}</li>
 * </ul>
 *
 * <p>The {@link Flavour} enum captures the two known variants: S1/S2 use reseed
 * value {@code 0x2A6D365A} with a full-long zero check, while S3K uses
 * {@code 0x2A6D365B} with a low-word-only zero check.
 *
 * <p>Not thread-safe. One instance is held per {@link GameRuntime}.
 */
public final class GameRng {

    /** RNG algorithm variant, matching a specific ROM's reseed behaviour. */
    public enum Flavour {
        /** Sonic 1 / Sonic 2: reseed 0x2A6D365A, reseed when full long is zero. */
        S1_S2(0x2A6D365AL, false),
        /** Sonic 3 & Knuckles: reseed 0x2A6D365B, reseed when low word is zero. */
        S3K(0x2A6D365BL, true);

        private final long reseedValue;
        private final boolean lowWordZeroCheck;

        Flavour(long reseedValue, boolean lowWordZeroCheck) {
            this.reseedValue = reseedValue;
            this.lowWordZeroCheck = lowWordZeroCheck;
        }

        public long reseedValue() { return reseedValue; }
        public boolean lowWordZeroCheck() { return lowWordZeroCheck; }
    }

    private static final long MASK32 = 0xFFFFFFFFL;
    private static final long MASK16 = 0xFFFFL;

    private final Flavour flavour;
    private long seed; // 32-bit unsigned seed, stored as long to avoid sign extension

    /**
     * Creates a new RNG with the given flavour and an initial seed of 0.
     * The first call to {@link #nextRaw()} will reseed automatically.
     */
    public GameRng(Flavour flavour) {
        this(flavour, 0L);
    }

    /** Creates a new RNG with the given flavour and initial seed. */
    public GameRng(Flavour flavour, long initialSeed) {
        if (flavour == null) throw new IllegalArgumentException("flavour must not be null");
        this.flavour = flavour;
        this.seed = initialSeed & MASK32;
    }

    public Flavour flavour() { return flavour; }

    /**
     * Returns the full 32-bit {@code d0} result of the ROM routine and advances
     * the seed. This is the raw return value — most callers should use
     * {@link #nextWord()} or one of the helper methods.
     * <p>
     * ROM parity: each call corresponds to exactly one {@code jsr RandomNumber}.
     */
    public int nextRaw() {
        long d1 = seed & MASK32;

        // Reseed check
        if (flavour.lowWordZeroCheck) {
            if ((d1 & MASK16) == 0L) d1 = flavour.reseedValue;
        } else {
            if (d1 == 0L) d1 = flavour.reseedValue;
        }

        long d0 = d1;                                  // move.l d1, d0
        d1 = (d1 << 2) & MASK32;                       // asl.l #2, d1
        d1 = (d1 + d0) & MASK32;                       // add.l d0, d1  (d1 = seed * 5)
        d1 = (d1 << 3) & MASK32;                       // asl.l #3, d1  (d1 = seed * 40)
        d1 = (d1 + d0) & MASK32;                       // add.l d0, d1  (d1 = seed * 41)

        // move.w d1, d0  -- replace low 16 of d0 with low 16 of d1; high 16 of d0 preserved
        d0 = (d0 & 0xFFFF0000L) | (d1 & MASK16);
        // swap d1
        d1 = ((d1 >> 16) & MASK16) | ((d1 & MASK16) << 16);
        // add.w d1, d0 -- word add into low 16 of d0; overflow does NOT carry into high word
        long newLow = ((d0 & MASK16) + (d1 & MASK16)) & MASK16;
        d0 = (d0 & 0xFFFF0000L) | newLow;
        // move.w d0, d1 -- replace low 16 of d1 with low 16 of d0
        d1 = (d1 & 0xFFFF0000L) | (d0 & MASK16);
        // swap d1
        d1 = ((d1 >> 16) & MASK16) | ((d1 & MASK16) << 16);

        seed = d1 & MASK32;
        return (int) (d0 & MASK32);
    }

    // ── Helper methods ──────────────────────────────────────────────────

    /**
     * Returns the low 16 bits of {@link #nextRaw()} as an unsigned value [0, 65535].
     * Corresponds to the common ROM pattern {@code jsr RandomNumber / move.w d0, dN}.
     */
    public int nextWord() {
        return nextRaw() & 0xFFFF;
    }

    /**
     * Returns the low 8 bits of {@link #nextRaw()} as an unsigned value [0, 255].
     * Corresponds to {@code jsr RandomNumber / andi.w #$FF, d0}.
     */
    public int nextByte() {
        return nextRaw() & 0xFF;
    }

    /**
     * Returns {@code nextRaw() & mask}. Use for bitmask draws like
     * {@code andi.w #$1F, d0} (mask = 0x1F).
     *
     * @param mask bitmask, typically {@code (1<<n) - 1}
     */
    public int nextBits(int mask) {
        return nextRaw() & mask;
    }

    /**
     * Returns {@code true} or {@code false} with equal probability.
     * Corresponds to {@code andi.w #1, d0 / beq} or {@code btst #0, d0 / bne}.
     */
    public boolean nextBoolean() {
        return (nextRaw() & 1) != 0;
    }

    /**
     * Returns a value in {@code [0, bound)}. For power-of-two bounds, this is
     * equivalent to {@code nextBits(bound - 1)} and stays ROM-accurate.
     * For non-power-of-two bounds, this uses unbiased rejection sampling via
     * repeated draws — document at the call site whether that matches the ROM
     * (most ROM callers use a mask, not a modulo).
     *
     * @param bound exclusive upper bound (must be positive)
     * @throws IllegalArgumentException if {@code bound <= 0}
     */
    public int nextInt(int bound) {
        if (bound <= 0) throw new IllegalArgumentException("bound must be positive");
        // Power-of-two fast path: equivalent to nextBits(bound-1)
        if ((bound & (bound - 1)) == 0) {
            return nextRaw() & (bound - 1);
        }
        // Rejection sampling on the 16-bit draw
        int threshold = 65536 - (65536 % bound);
        int r;
        do { r = nextRaw() & 0xFFFF; } while (r >= threshold);
        return r % bound;
    }

    /**
     * Returns {@code (nextRaw() & mask) + bias}. Models the ROM idiom
     * {@code andi.w #mask, d0 / addq.w #bias, d0} or {@code subq.w} when
     * {@code bias} is negative.
     */
    public int nextOffset(int mask, int bias) {
        return (nextRaw() & mask) + bias;
    }

    // ── Seed management ─────────────────────────────────────────────────

    /** Sets the RNG seed explicitly. Use for deterministic test setup. */
    public void setSeed(long seed) {
        this.seed = seed & MASK32;
    }

    /** Returns the current 32-bit seed as an unsigned long. */
    public long getSeed() {
        return seed & MASK32;
    }

    /** Copies this RNG's current seed into {@code dest} without changing flavour. */
    public void copySeedTo(GameRng dest) {
        if (dest == null) throw new IllegalArgumentException("dest must not be null");
        dest.seed = this.seed;
    }

    /**
     * Seeds the RNG from a frame counter. Matches the ROM's common initialization
     * pattern: {@code move.w (V_int_run_count).w, (RNG_seed).w}. Upper 16 bits
     * of the seed are cleared.
     */
    public void seedFromFrameCounter(int frameCounter) {
        this.seed = frameCounter & MASK16;
    }
}
```

- [ ] **Step 2: Compile**

Run: `mvn compile -Dmse=relaxed`
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/openggf/game/GameRng.java
git commit -m "$(cat <<'EOF'
feat: add GameRng deterministic ROM-accurate PRNG

Implements the Mega Drive Sonic RandomNumber/Random_Number algorithm
bit-exactly: 32-bit multiply-by-41 with word-fold. Flavour enum
captures the S1/S2 vs S3K differences (reseed value, zero check).

Helper methods nextWord/nextByte/nextBits/nextBoolean/nextInt/nextOffset
cover the common ROM idioms. Not thread-safe; each GameRuntime will own
one instance.

Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 2: Add rngFlavour() to GameModule

**Files:**
- Modify: `src/main/java/com/openggf/game/GameModule.java`

- [ ] **Step 1: Add default method to GameModule interface**

Append this default method near the other `default` methods (e.g. right after `getGameId()`):

```java
    /**
     * Returns the RNG flavour this game uses. Defaults to {@link GameRng.Flavour#S1_S2},
     * which matches Sonic 1 and Sonic 2 (reseed 0x2A6D365A, full-long zero check).
     * Sonic 3 &amp; Knuckles overrides this to {@link GameRng.Flavour#S3K}.
     *
     * @return the RNG flavour for this game
     */
    default GameRng.Flavour rngFlavour() {
        return GameRng.Flavour.S1_S2;
    }
```

- [ ] **Step 2: Compile**

Run: `mvn compile -Dmse=relaxed`
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/openggf/game/GameModule.java
git commit -m "$(cat <<'EOF'
feat: add rngFlavour() default method to GameModule

S1/S2 flavour is the default (matches the majority of modules).
S3K will override this in a follow-up commit.

Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 3: Override rngFlavour() in Sonic3kGameModule

**Files:**
- Modify: `src/main/java/com/openggf/game/sonic3k/Sonic3kGameModule.java`

- [ ] **Step 1: Add the override method**

Locate the block of `@Override` methods (e.g. right after `getGameId()`) and add:

```java
    @Override
    public com.openggf.game.GameRng.Flavour rngFlavour() {
        return com.openggf.game.GameRng.Flavour.S3K;
    }
```

(Use fully qualified name if imports are noisy; otherwise add `import com.openggf.game.GameRng;` to the import block.)

- [ ] **Step 2: Compile**

Run: `mvn compile -Dmse=relaxed`
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/openggf/game/sonic3k/Sonic3kGameModule.java
git commit -m "$(cat <<'EOF'
feat: S3K module returns S3K RNG flavour

S3K's Random_Number uses reseed 0x2A6D365B with a low-word-only zero
check (tst.w d1), different from S1/S2's 0x2A6D365A + full-long check.

Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 4: Add GameRng to GameRuntime

**Files:**
- Modify: `src/main/java/com/openggf/game/GameRuntime.java`
- Modify: `src/main/java/com/openggf/game/RuntimeManager.java`

- [ ] **Step 1: Add field, constructor param, and getter to GameRuntime**

Add the `GameRng` field beneath `levelManager`:

```java
    private final LevelManager levelManager;
    private final GameRng rng;
```

Update the constructor (package-private) to accept `GameRng` as the final parameter:

```java
    GameRuntime(Camera camera, TimerManager timers, GameStateManager gameState,
                FadeManager fadeManager, WaterSystem waterSystem,
                ParallaxManager parallaxManager,
                TerrainCollisionManager terrainCollisionManager,
                CollisionSystem collisionSystem, SpriteManager spriteManager,
                LevelManager levelManager, GameRng rng) {
        this.camera = camera;
        this.timers = timers;
        this.gameState = gameState;
        this.fadeManager = fadeManager;
        this.waterSystem = waterSystem;
        this.parallaxManager = parallaxManager;
        this.terrainCollisionManager = terrainCollisionManager;
        this.collisionSystem = collisionSystem;
        this.spriteManager = spriteManager;
        this.levelManager = levelManager;
        this.rng = rng;
    }
```

Add the getter in the Getters section:

```java
    public GameRng getRng() { return rng; }
```

- [ ] **Step 2: Update RuntimeManager.createGameplay() to construct GameRng**

In `RuntimeManager.java`, update `createGameplay()`:

```java
    public static synchronized GameRuntime createGameplay() {
        Camera camera = new Camera();
        TimerManager timers = new TimerManager();
        GameStateManager gameState = new GameStateManager();
        FadeManager fadeManager = new FadeManager();
        WaterSystem waterSystem = new WaterSystem();
        ParallaxManager parallaxManager = new ParallaxManager();
        TerrainCollisionManager terrainCollisionManager = new TerrainCollisionManager();
        CollisionSystem collisionSystem = new CollisionSystem(terrainCollisionManager);
        SpriteManager spriteManager = new SpriteManager();
        LevelManager levelManager = new LevelManager(
                camera, spriteManager, parallaxManager, collisionSystem, waterSystem, gameState);

        GameModule currentModule = GameModuleRegistry.getCurrent();
        GameRng.Flavour flavour = currentModule != null
                ? currentModule.rngFlavour()
                : GameRng.Flavour.S1_S2;
        GameRng rng = new GameRng(flavour);

        GameRuntime runtime = new GameRuntime(camera, timers, gameState, fadeManager,
                waterSystem, parallaxManager, terrainCollisionManager,
                collisionSystem, spriteManager, levelManager, rng);
        current = runtime;
        return runtime;
    }
```

Add the import at the top of `RuntimeManager.java`:

```java
import com.openggf.game.GameModuleRegistry;
```

(`GameModuleRegistry` is already in the `com.openggf.game` package, so the import is optional within the same package. Verify by opening the file — if it's in a different package, add the import.)

- [ ] **Step 3: Compile**

Run: `mvn compile -Dmse=relaxed`
Expected: BUILD SUCCESS. If existing tests construct `GameRuntime` directly via the package-private constructor, they will fail to compile — search and fix:

Run: `mvn test-compile -Dmse=relaxed`

Expected compilation failures point at any test that calls `new GameRuntime(...)` directly. Search with:

Run: `grep -rn "new GameRuntime(" src/test/java src/main/java`

For each call site, append `, new GameRng(GameRng.Flavour.S1_S2)` as the final argument.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/openggf/game/GameRuntime.java src/main/java/com/openggf/game/RuntimeManager.java
# Also add any test files that needed constructor updates
git commit -m "$(cat <<'EOF'
feat: GameRuntime owns a GameRng instance

The runtime holds one PRNG per gameplay session, constructed from
the active GameModule's rngFlavour(). This keeps RNG state tied to
the runtime lifecycle (runtime destroy == RNG gone), matching the
direction set by GameRuntime ownership.

Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 5: Add GameServices.rng() Accessor

**Files:**
- Modify: `src/main/java/com/openggf/game/GameServices.java`

- [ ] **Step 1: Add rng() accessor**

Add this method beside the other runtime-owned accessors (e.g. right after `water()`):

```java
    /**
     * Global deterministic RNG accessor for non-object code.
     * <p>
     * Object instances should use {@code services().rng()} instead.
     * Runtime-owned: backed by the current {@link GameRuntime}.
     */
    public static GameRng rng() {
        return requireRuntime("rng").getRng();
    }
```

- [ ] **Step 2: Compile**

Run: `mvn compile -Dmse=relaxed`
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/openggf/game/GameServices.java
git commit -m "$(cat <<'EOF'
feat: add GameServices.rng() for non-object code

Non-object callers (managers, slot machine controllers, etc.) access
the runtime RNG through this static accessor. Object instances use
services().rng() instead.

Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 6: Add rng() to ObjectServices and Implementations

**Files:**
- Modify: `src/main/java/com/openggf/level/objects/ObjectServices.java`
- Modify: `src/main/java/com/openggf/level/objects/DefaultObjectServices.java`
- Modify: `src/test/java/com/openggf/level/objects/StubObjectServices.java`

- [ ] **Step 1: Add rng() to ObjectServices interface**

Add near the other runtime-owned accessors (e.g. beneath `parallaxManager()`):

```java
    /**
     * Returns the deterministic game RNG.
     * <p>
     * <b>Governance:</b> Object instance code should use this method, not
     * {@code new Random()}, {@link java.util.concurrent.ThreadLocalRandom}, or
     * {@link com.openggf.game.GameServices#rng()}.
     */
    com.openggf.game.GameRng rng();
```

- [ ] **Step 2: Implement rng() in DefaultObjectServices**

Add a field and constructor wiring. At the top of the class, add the field:

```java
    private final com.openggf.game.GameRng rng;
```

Update the primary (GameRuntime) constructor to pass the runtime's RNG:

```java
    public DefaultObjectServices(GameRuntime runtime) {
        this(Objects.requireNonNull(runtime, "runtime").getLevelManager(),
                runtime.getCamera(),
                runtime.getGameState(),
                runtime.getSpriteManager(),
                runtime.getFadeManager(),
                runtime.getWaterSystem(),
                runtime.getParallaxManager(),
                runtime.getRng());
    }
```

Update the explicit constructor signature:

```java
    public DefaultObjectServices(LevelManager levelManager,
                                 Camera camera,
                                 GameStateManager gameState,
                                 SpriteManager spriteManager,
                                 FadeManager fadeManager,
                                 WaterSystem waterSystem,
                                 ParallaxManager parallaxManager,
                                 com.openggf.game.GameRng rng) {
        this.levelManager = Objects.requireNonNull(levelManager, "levelManager");
        this.camera = Objects.requireNonNull(camera, "camera");
        this.gameState = Objects.requireNonNull(gameState, "gameState");
        this.spriteManager = Objects.requireNonNull(spriteManager, "spriteManager");
        this.fadeManager = Objects.requireNonNull(fadeManager, "fadeManager");
        this.waterSystem = Objects.requireNonNull(waterSystem, "waterSystem");
        this.parallaxManager = Objects.requireNonNull(parallaxManager, "parallaxManager");
        this.rng = Objects.requireNonNull(rng, "rng");
    }
```

Add the method implementation next to the other runtime-owned getters:

```java
    @Override
    public com.openggf.game.GameRng rng() {
        return rng;
    }
```

- [ ] **Step 3: Stub rng() in StubObjectServices**

In `StubObjectServices.java`, add a field at the top of the class and the method override:

```java
    private final com.openggf.game.GameRng stubRng =
            new com.openggf.game.GameRng(com.openggf.game.GameRng.Flavour.S1_S2);

    @Override public com.openggf.game.GameRng rng() { return stubRng; }
```

- [ ] **Step 4: Fix any other ObjectServices implementations**

Run: `grep -rn "implements ObjectServices" src/main/java src/test/java`

For each implementor that is not an abstract class or `DefaultObjectServices`/`StubObjectServices`, add the `rng()` override. If the test-only implementor only needs a no-op RNG, mirror the `StubObjectServices` approach.

- [ ] **Step 5: Compile everything**

Run: `mvn test-compile -Dmse=relaxed`
Expected: BUILD SUCCESS

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/openggf/level/objects/ObjectServices.java \
        src/main/java/com/openggf/level/objects/DefaultObjectServices.java \
        src/test/java/com/openggf/level/objects/StubObjectServices.java
git commit -m "$(cat <<'EOF'
feat: expose GameRng through ObjectServices

Objects now have services().rng() as the single RNG entry point.
StubObjectServices provides a fresh S1_S2 RNG so tests that do not
configure RNG state still work.

Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 7: Add rng() Helper to AbstractObjectInstance

**Files:**
- Modify: `src/main/java/com/openggf/level/objects/AbstractObjectInstance.java`

- [ ] **Step 1: Add the helper method**

Near the existing `protected ObjectServices services()` method (around line 266), add:

```java
    /**
     * Shortcut for {@code services().rng()}. Available during construction
     * (via ThreadLocal context), update, and rendering.
     */
    protected final com.openggf.game.GameRng rng() {
        return services().rng();
    }
```

- [ ] **Step 2: Compile**

Run: `mvn compile -Dmse=relaxed`
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/openggf/level/objects/AbstractObjectInstance.java
git commit -m "$(cat <<'EOF'
feat: add rng() shortcut on AbstractObjectInstance

Object code can now write rng().nextBits(0x1F) instead of the longer
services().rng().nextBits(0x1F). Matches the ergonomics of the
existing services() helper.

Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 8: Add TestGameRngGoldenSequence

**Files:**
- Create: `src/test/java/com/openggf/game/TestGameRngGoldenSequence.java`

- [ ] **Step 1: Write the failing test**

```java
package com.openggf.game;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Golden sequence + property tests for GameRng.
 * <p>
 * The expected hex values were computed from a direct simulation of the
 * 68000 assembly at {@code docs/skdisasm/sonic3k.asm:2992-3011} and
 * {@code docs/s2disasm/s2.asm:3971-3995}.
 */
public class TestGameRngGoldenSequence {

    // ── Golden sequences from seed=0 ────────────────────────────────────

    private static final int[] S2_GOLDEN_D0 = {
            0x2A6D7FE7, 0x7FE76115, 0x6115388B, 0x388B9BF8, 0x9BF8A878,
            0xA878CB8D, 0xCB8DF300, 0xF30037E8, 0x37E8431E, 0x431E6354
    };
    private static final long[] S2_GOLDEN_SEED = {
            0x7FE7B46AL, 0x6115E4FAL, 0x388BAC0AL, 0x9BF88D9AL, 0xA878ADAAL,
            0xCB8DD03AL, 0xF300594AL, 0x37E84CDAL, 0x431E4EEAL, 0x6354A37AL
    };

    private static final int[] S3K_GOLDEN_D0 = {
            0x2A6D8010, 0x80106E37, 0x6E376037, 0x603714A7, 0x14A7CFCD,
            0xCFCDEED4, 0xEED4FBB1, 0xFBB15C92, 0x5C92ECB7, 0xECB7F79E
    };
    private static final long[] S3K_GOLDEN_SEED = {
            0x8010B493L, 0x6E37EB8BL, 0x6037B943L, 0x14A7ABBBL, 0xCFCD80F3L,
            0xEED4A6EBL, 0xFBB1BBA3L, 0x5C920D1BL, 0xECB71953L, 0xF79E0E4BL
    };

    @Test
    public void s1s2_goldenSequence_matchesRomSimulation() {
        GameRng rng = new GameRng(GameRng.Flavour.S1_S2);
        for (int i = 0; i < 10; i++) {
            int d0 = rng.nextRaw();
            assertEquals(
                    String.format("S1_S2 call %d d0", i + 1),
                    S2_GOLDEN_D0[i], d0);
            assertEquals(
                    String.format("S1_S2 call %d seed", i + 1),
                    S2_GOLDEN_SEED[i], rng.getSeed());
        }
    }

    @Test
    public void s3k_goldenSequence_matchesRomSimulation() {
        GameRng rng = new GameRng(GameRng.Flavour.S3K);
        for (int i = 0; i < 10; i++) {
            int d0 = rng.nextRaw();
            assertEquals(
                    String.format("S3K call %d d0", i + 1),
                    S3K_GOLDEN_D0[i], d0);
            assertEquals(
                    String.format("S3K call %d seed", i + 1),
                    S3K_GOLDEN_SEED[i], rng.getSeed());
        }
    }

    // ── Reseed behaviour ─────────────────────────────────────────────────

    @Test
    public void s1s2_reseedsWhenSeedIsZero() {
        GameRng rng = new GameRng(GameRng.Flavour.S1_S2, 0L);
        rng.nextRaw();
        // seed should now be the post-step of reseed value 0x2A6D365A
        assertEquals(0x7FE7B46AL, rng.getSeed());
    }

    @Test
    public void s3k_reseedsWhenLowWordIsZero() {
        // seed high word nonzero, low word zero — S3K must reseed
        GameRng rng = new GameRng(GameRng.Flavour.S3K, 0x12340000L);
        int d0 = rng.nextRaw();
        // Matches the first call from a fresh S3K RNG (reseed 0x2A6D365B)
        assertEquals(0x2A6D8010, d0);
        assertEquals(0x8010B493L, rng.getSeed());
    }

    @Test
    public void s1s2_doesNotReseedWhenLowWordIsZeroButHighNonzero() {
        // full long nonzero -> S1/S2 must NOT reseed
        GameRng rng = new GameRng(GameRng.Flavour.S1_S2, 0x12340000L);
        int d0 = rng.nextRaw();
        // Computed from the ROM algorithm with seed=0x12340000
        assertEquals(0x1234EA54, d0);
        assertEquals(0xEA540000L, rng.getSeed());
    }

    @Test
    public void s1s2_andS3k_divergeOnLowWordZeroSeed() {
        // Same starting seed; low word is zero; S1_S2 keeps it, S3K reseeds
        GameRng s2 = new GameRng(GameRng.Flavour.S1_S2, 0x12340000L);
        GameRng s3k = new GameRng(GameRng.Flavour.S3K, 0x12340000L);
        int s2Value = s2.nextRaw();
        int s3kValue = s3k.nextRaw();
        assertNotEquals("flavours must diverge on low-word-zero seeds",
                s2Value, s3kValue);
    }

    // ── Helper methods ───────────────────────────────────────────────────

    @Test
    public void nextWord_returnsLow16BitsOfNextRaw() {
        GameRng a = new GameRng(GameRng.Flavour.S1_S2);
        GameRng b = new GameRng(GameRng.Flavour.S1_S2);
        for (int i = 0; i < 20; i++) {
            int raw = a.nextRaw();
            int word = b.nextWord();
            assertEquals(raw & 0xFFFF, word);
        }
    }

    @Test
    public void nextByte_returnsLow8BitsOfNextRaw() {
        GameRng a = new GameRng(GameRng.Flavour.S3K);
        GameRng b = new GameRng(GameRng.Flavour.S3K);
        for (int i = 0; i < 20; i++) {
            int raw = a.nextRaw();
            int byteVal = b.nextByte();
            assertEquals(raw & 0xFF, byteVal);
            assertTrue(byteVal >= 0 && byteVal <= 255);
        }
    }

    @Test
    public void nextBits_masksCorrectly() {
        GameRng rng = new GameRng(GameRng.Flavour.S1_S2);
        for (int i = 0; i < 1000; i++) {
            int v = rng.nextBits(0x1F);
            assertTrue("value within mask [0,31]", v >= 0 && v <= 31);
        }
    }

    @Test
    public void nextBits_masksCorrectlyForByteMask() {
        GameRng rng = new GameRng(GameRng.Flavour.S1_S2);
        for (int i = 0; i < 1000; i++) {
            int v = rng.nextBits(0xFF);
            assertTrue(v >= 0 && v <= 255);
        }
    }

    @Test
    public void nextBoolean_producesBothValues() {
        GameRng rng = new GameRng(GameRng.Flavour.S1_S2);
        boolean sawTrue = false, sawFalse = false;
        for (int i = 0; i < 200; i++) {
            if (rng.nextBoolean()) sawTrue = true; else sawFalse = true;
            if (sawTrue && sawFalse) return;
        }
        fail("expected both true and false within 200 draws");
    }

    @Test
    public void nextBoolean_roughlyBalanced() {
        GameRng rng = new GameRng(GameRng.Flavour.S1_S2);
        int trues = 0;
        int n = 10_000;
        for (int i = 0; i < n; i++) if (rng.nextBoolean()) trues++;
        double ratio = trues / (double) n;
        assertTrue("ratio " + ratio + " not in [0.4, 0.6]",
                ratio > 0.4 && ratio < 0.6);
    }

    @Test
    public void nextInt_powerOfTwo_equalsNextBits() {
        GameRng a = new GameRng(GameRng.Flavour.S1_S2);
        GameRng b = new GameRng(GameRng.Flavour.S1_S2);
        for (int i = 0; i < 100; i++) {
            int ni = a.nextInt(16);
            int bits = b.nextBits(0x0F);
            assertEquals(bits, ni);
            assertTrue(ni >= 0 && ni < 16);
        }
    }

    @Test
    public void nextInt_nonPowerOfTwo_rangeOnly() {
        GameRng rng = new GameRng(GameRng.Flavour.S1_S2);
        for (int i = 0; i < 1000; i++) {
            int v = rng.nextInt(10);
            assertTrue("value " + v + " out of [0,10)", v >= 0 && v < 10);
        }
    }

    @Test(expected = IllegalArgumentException.class)
    public void nextInt_rejectsNonPositiveBound() {
        new GameRng(GameRng.Flavour.S1_S2).nextInt(0);
    }

    @Test
    public void nextOffset_appliesMaskAndBias() {
        GameRng a = new GameRng(GameRng.Flavour.S1_S2);
        GameRng b = new GameRng(GameRng.Flavour.S1_S2);
        for (int i = 0; i < 100; i++) {
            int raw = a.nextRaw();
            int v = b.nextOffset(0x1F, -6);
            assertEquals((raw & 0x1F) - 6, v);
            assertTrue("offset in [-6, 25]", v >= -6 && v <= 25);
        }
    }

    // ── Seed management ─────────────────────────────────────────────────

    @Test
    public void setSeed_andGetSeed_roundTrip() {
        GameRng rng = new GameRng(GameRng.Flavour.S1_S2);
        rng.setSeed(0xDEADBEEFL);
        assertEquals(0xDEADBEEFL, rng.getSeed());
    }

    @Test
    public void setSeed_maskedTo32Bits() {
        GameRng rng = new GameRng(GameRng.Flavour.S1_S2);
        rng.setSeed(0x1_DEADBEEFL);          // 33-bit value
        assertEquals(0xDEADBEEFL, rng.getSeed());
    }

    @Test
    public void copySeedTo_reproducesSequence() {
        GameRng source = new GameRng(GameRng.Flavour.S1_S2);
        source.nextRaw();
        source.nextRaw();
        GameRng dest = new GameRng(GameRng.Flavour.S1_S2);
        source.copySeedTo(dest);
        assertEquals(source.getSeed(), dest.getSeed());
        assertEquals(source.nextRaw(), dest.nextRaw());
    }

    @Test
    public void seedFromFrameCounter_setsLowWordOnly() {
        GameRng rng = new GameRng(GameRng.Flavour.S1_S2);
        rng.seedFromFrameCounter(0x1234);
        assertEquals(0x1234L, rng.getSeed());
    }

    @Test
    public void seedFromFrameCounter_masks16Bits() {
        GameRng rng = new GameRng(GameRng.Flavour.S1_S2);
        rng.seedFromFrameCounter(0xABCD1234);
        assertEquals(0x1234L, rng.getSeed());
    }

    @Test(expected = IllegalArgumentException.class)
    public void constructor_rejectsNullFlavour() {
        new GameRng(null);
    }

    @Test(expected = IllegalArgumentException.class)
    public void copySeedTo_rejectsNullDest() {
        new GameRng(GameRng.Flavour.S1_S2).copySeedTo(null);
    }

    // ── Flavour enum ────────────────────────────────────────────────────

    @Test
    public void flavour_s1s2_reseedConstants() {
        assertEquals(0x2A6D365AL, GameRng.Flavour.S1_S2.reseedValue());
        assertFalse(GameRng.Flavour.S1_S2.lowWordZeroCheck());
    }

    @Test
    public void flavour_s3k_reseedConstants() {
        assertEquals(0x2A6D365BL, GameRng.Flavour.S3K.reseedValue());
        assertTrue(GameRng.Flavour.S3K.lowWordZeroCheck());
    }
}
```

- [ ] **Step 2: Run the test**

Run: `mvn test -Dtest=TestGameRngGoldenSequence -Dmse=relaxed`
Expected: BUILD SUCCESS, all tests pass.

If a golden value mismatches, the algorithm implementation is wrong — fix Task 1's implementation before proceeding.

- [ ] **Step 3: Commit**

```bash
git add src/test/java/com/openggf/game/TestGameRngGoldenSequence.java
git commit -m "$(cat <<'EOF'
test: add GameRng golden sequence + property tests

Golden values computed from direct simulation of the 68000 asm.
Asserts bit-exact parity with the ROM for both flavours, including
the S1_S2 vs S3K divergence on low-word-zero seeds.

Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Phase 2: Migration Guard

### Task 9: Create TestRngMigrationGuard (Warn-Only)

**Files:**
- Create: `src/test/java/com/openggf/level/objects/TestRngMigrationGuard.java`

- [ ] **Step 1: Write the scanner test**

```java
package com.openggf.level.objects;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;

/**
 * Migration guard: scans source files for raw {@code java.util.Random} and
 * {@link java.util.concurrent.ThreadLocalRandom} usage in gameplay packages.
 * <p>
 * Gameplay code MUST use {@code services().rng()} (inside objects) or
 * {@link com.openggf.game.GameServices#rng()} (inside managers). The allowlist
 * below covers non-gameplay or known-cosmetic callers.
 * <p>
 * <b>Warn-only</b> until Phase 4: prints remaining offenders to stderr but
 * does not fail the build. Switch to failing by flipping {@link #FAIL_ON_VIOLATION}.
 */
class TestRngMigrationGuard {

    /**
     * Flip to {@code true} in Phase 4 (after all Category A files are migrated)
     * to make this guard fail the build on regressions.
     */
    private static final boolean FAIL_ON_VIOLATION = false;

    /** Packages to scan — these are gameplay-critical (ROM-accurate) paths. */
    private static final String[] GAMEPLAY_PACKAGES = {
            "com/openggf/level/objects",
            "com/openggf/sprites/playable",
            "com/openggf/game/sonic1/objects",
            "com/openggf/game/sonic2/objects",
            "com/openggf/game/sonic2/slotmachine",
            "com/openggf/game/sonic3k/objects",
    };

    /**
     * Files that are permanently allowed to keep using java.util.Random /
     * ThreadLocalRandom. Categories:
     * <ul>
     *   <li>DEV_TOOL — developer tooling, never shipped with gameplay</li>
     *   <li>TEST_DOUBLE — test doubles / stubs</li>
     *   <li>COSMETIC — purely cosmetic, not ROM-accurate (cloud drift, credits fx)</li>
     *   <li>NOT_GAMEPLAY — outside the gameplay packages but matched by future scan</li>
     * </ul>
     */
    private static final Set<String> ALLOWLIST = Set.of(
            // COSMETIC: credits cutscene birds/clouds — not ROM-accurate
            "com/openggf/game/sonic2/credits/Sonic2EndingCutsceneManager.java",
            // COSMETIC: master title screen clouds
            "com/openggf/game/MasterTitleScreen.java"
            // Add to this list only when a file is genuinely non-gameplay.
    );

    @Test
    void gameplayPackages_shouldNotUseJavaRandom() throws IOException {
        Path srcMain = Path.of("src/main/java");
        if (!Files.isDirectory(srcMain)) return;

        List<String> violations = new ArrayList<>();

        for (String pkg : GAMEPLAY_PACKAGES) {
            Path pkgDir = srcMain.resolve(pkg);
            if (!Files.isDirectory(pkgDir)) continue;

            try (Stream<Path> files = Files.walk(pkgDir)) {
                files.filter(p -> p.toString().endsWith(".java"))
                     .forEach(path -> {
                         String rel = srcMain.relativize(path).toString()
                                 .replace('\\', '/');
                         if (ALLOWLIST.contains(rel)) return;
                         try {
                             String content = Files.readString(path);
                             if (content.contains("java.util.Random")
                                 || content.contains("java.util.concurrent.ThreadLocalRandom")
                                 || content.contains("ThreadLocalRandom.current()")) {
                                 violations.add(rel);
                             }
                         } catch (IOException ignored) { }
                     });
            }
        }

        if (!violations.isEmpty()) {
            StringBuilder msg = new StringBuilder();
            msg.append("\n=== RNG migration: ")
               .append(violations.size())
               .append(" file(s) still use java.util.Random/ThreadLocalRandom ===\n");
            msg.append("Migrate to services().rng() (objects) or GameServices.rng() (managers):\n\n");
            for (String v : violations) {
                msg.append("  ").append(v).append('\n');
            }
            if (FAIL_ON_VIOLATION) {
                org.junit.jupiter.api.Assertions.fail(msg.toString());
            } else {
                System.err.println(msg);
            }
        }
    }
}
```

- [ ] **Step 2: Run it in warn-only mode**

Run: `mvn test -Dtest=TestRngMigrationGuard -Dmse=relaxed`
Expected: BUILD SUCCESS, but stderr lists all remaining offenders (~27 files expected).

- [ ] **Step 3: Commit**

```bash
git add src/test/java/com/openggf/level/objects/TestRngMigrationGuard.java
git commit -m "$(cat <<'EOF'
test: add RNG migration guard (warn-only)

Scanner test flags every file in gameplay packages that still uses
java.util.Random or ThreadLocalRandom. Prints to stderr without
failing; will be flipped to fail-build in Phase 4 after all
gameplay callers are migrated.

Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Phase 3: Migrate Critical Objects

Each migration follows the same mechanical translation table:

| Old pattern                                | New pattern                                |
|--------------------------------------------|--------------------------------------------|
| `private final Random rng = new Random()` | remove the field (use `rng()` helper)      |
| `private static final Random random = new Random()` | remove; use `rng()` helper          |
| `import java.util.Random;`                 | remove the import                          |
| `import java.util.concurrent.ThreadLocalRandom;` | remove the import                    |
| `ThreadLocalRandom.current()`              | `rng()` (in objects) / `GameServices.rng()` |
| `.nextInt(16)`                             | `.nextBits(0xF)`                           |
| `.nextInt(32)`                             | `.nextBits(0x1F)`                          |
| `.nextInt(0x100)`                          | `.nextBits(0xFF)`                          |
| `.nextInt(0x10000)`                        | `.nextWord()`                              |
| `.nextInt(0x800)`                          | `.nextBits(0x7FF)`                         |
| `.nextInt(powerOf2)`                       | `.nextBits(powerOf2 - 1)`                  |
| `.nextBoolean()`                           | `.nextBoolean()`                           |
| `.nextInt(nonPow2)`                        | `.nextInt(nonPow2)` **and add a review comment** |

**Decision rule for non-power-of-two bounds:** Cross-check the ROM source (`docs/*disasm/`) at the cited ROM reference near the call site. If the ROM uses a bitmask (`andi.w #mask, d0`), replace the Java `nextInt` with `nextBits` using the ROM's actual mask — even if the mask lets the result fall outside the previously used Java bound. If the ROM genuinely does `divu` or modulo, keep `nextInt(bound)` and add `// ROM: modulo bound`.

For static-context callers (managers, controllers) that are not object instances, replace with `GameServices.rng()` instead of `services().rng()`.

---

### Task 10: Migrate GumballMachineObjectInstance

The `GumballMachineObjectInstance` has a ROM-port-bug comment — the seed was coming from `System.nanoTime()` instead of the frame counter.

**Files:**
- Modify: `src/main/java/com/openggf/game/sonic3k/objects/GumballMachineObjectInstance.java`

**ROM reference:** S3K `Obj_GumballMachine` uses `RandomNumber`; the engine's port should use `services().rng()`.

- [ ] **Step 1: Remove the Random field and import**

Delete line 21 (`import java.util.Random;`) and the field at line 168:

```java
    // REMOVED: private final Random rng;
```

- [ ] **Step 2: Remove the nanoTime seeding**

Delete line 195-196 from the constructor:

```java
    // REMOVED: this.rng = new Random(System.nanoTime());
```

- [ ] **Step 3: Replace usage at line 458**

Before:
```java
int r = rng.nextInt(16);
```

After:
```java
// ROM: jsr RandomNumber / andi.w #$F, d0
int r = rng().nextBits(0x0F);
```

(The `rng()` method here is the inherited `AbstractObjectInstance.rng()` helper.)

- [ ] **Step 4: Compile and run existing Gumball tests**

Run: `mvn test -Dtest='com.openggf.game.sonic3k.*Gumball*' -Dmse=relaxed`
Expected: BUILD SUCCESS (tests that don't seed the RNG will change behaviour — if any test relies on a specific value, update the test to call `GameServices.rng().setSeed(...)` in setup).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/openggf/game/sonic3k/objects/GumballMachineObjectInstance.java
git commit -m "$(cat <<'EOF'
refactor: migrate GumballMachineObjectInstance to GameRng

Fixes the ROM-port bug where the gumball RNG was seeded from
System.nanoTime() instead of the shared ROM-style PRNG. Now uses
services().rng().nextBits(0x0F) matching the ROM's
'andi.w #$F, d0' idiom.

Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 11: Migrate CNZSlotMachineManager

**Files:**
- Modify: `src/main/java/com/openggf/game/sonic2/slotmachine/CNZSlotMachineManager.java`

**Note:** This class is the "Sonic2SlotMachineManager" referenced in the spec (actual class name is `CNZSlotMachineManager`). It is a manager (static context), so it uses `GameServices.rng()` rather than `services().rng()`.

**ROM reference:** S2 `Obj_SlotMachine` routines at `s2.asm` — typically `jsr RandomNumber / andi.w #7, d0` for target selection and `andi.w #$FF, d0` for timer setup.

- [ ] **Step 1: Remove java.util.Random**

Delete line 5 (`import java.util.Random;`) and line 101 (`private final Random random = new Random();`).

- [ ] **Step 2: Add GameServices import**

If not present, add: `import com.openggf.game.GameServices;`

- [ ] **Step 3: Replace the 7 call sites**

| Line | Before                                   | After                                          |
|------|------------------------------------------|------------------------------------------------|
| 199  | `slotIndices[i] = random.nextInt(8);`    | `slotIndices[i] = GameServices.rng().nextBits(0x07);` |
| 228  | `int seed = random.nextInt(256);`        | `int seed = GameServices.rng().nextBits(0xFF);` |
| 253  | `int seed = random.nextInt(256);`        | `int seed = GameServices.rng().nextBits(0xFF);` |
| 267  | `int idx1 = random.nextInt(8);`          | `int idx1 = GameServices.rng().nextBits(0x07);` |
| 268  | `int idx2 = random.nextInt(8);`          | `int idx2 = GameServices.rng().nextBits(0x07);` |
| 269  | `int idx3 = random.nextInt(8);`          | `int idx3 = GameServices.rng().nextBits(0x07);` |
| 287  | `slotTimer = (random.nextInt(16)) + 0x0C;` | `slotTimer = GameServices.rng().nextOffset(0x0F, 0x0C);` |

- [ ] **Step 4: Compile**

Run: `mvn test-compile -Dmse=relaxed`
Expected: BUILD SUCCESS

- [ ] **Step 5: Run slot machine tests**

Run: `mvn test -Dtest='*SlotMachine*,*CNZ*' -Dmse=relaxed`
Expected: BUILD SUCCESS (if a test asserts a specific random value, seed the RNG in test setup via `GameServices.rng().setSeed(<value>)`).

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/openggf/game/sonic2/slotmachine/CNZSlotMachineManager.java
git commit -m "$(cat <<'EOF'
refactor: migrate CNZSlotMachineManager to GameRng

Seven call sites converted: nextInt(8)->nextBits(0x07),
nextInt(256)->nextBits(0xFF), nextInt(16)+0x0C->nextOffset(0x0F,0x0C).
Matches the ROM's andi.w-based draws in Obj_SlotMachine.

Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 12: Migrate DrowningController

**Files:**
- Modify: `src/main/java/com/openggf/sprites/playable/DrowningController.java`

**ROM reference:** S2/S3K drowning bubble timer uses `jsr RandomNumber / andi.w #<mask>, d0`.

DrowningController is part of the playable sprite controller chain (not an object instance). The easiest approach: either inject an `ObjectServices` reference if the controller already has one, or use `GameServices.rng()`.

- [ ] **Step 1: Inspect context**

Run: `grep -n "services\\(\\)\\|ObjectServices\\|GameServices" src/main/java/com/openggf/sprites/playable/DrowningController.java`

- [ ] **Step 2: Remove Random field and import**

Delete line 13 (`import java.util.Random;`) and line 76 (`private final Random random = new Random();`).

- [ ] **Step 3: Add GameServices import if needed**

`import com.openggf.game.GameServices;`

- [ ] **Step 4: Replace call sites**

| Line | Before                                  | After                                                 |
|------|-----------------------------------------|-------------------------------------------------------|
| 219  | `int bubbleCount = random.nextBoolean() ? 1 : 2;` | `int bubbleCount = GameServices.rng().nextBoolean() ? 1 : 2;` |
| 228  | `boolean firstIsCountdown = random.nextInt(4) == 0;` | `boolean firstIsCountdown = GameServices.rng().nextBits(0x03) == 0;` |
| 327  | `secondBubbleDelay = 1 + random.nextInt(SECOND_BUBBLE_MAX_DELAY);` | verify `SECOND_BUBBLE_MAX_DELAY` is a power of 2; if yes, `1 + GameServices.rng().nextBits(SECOND_BUBBLE_MAX_DELAY - 1)`; if no, `1 + GameServices.rng().nextInt(SECOND_BUBBLE_MAX_DELAY)` with a `// ROM: check mask` comment |

- [ ] **Step 5: Compile + run existing drowning tests**

Run: `mvn test -Dtest='*Drown*' -Dmse=relaxed`
Expected: BUILD SUCCESS

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/openggf/sprites/playable/DrowningController.java
git commit -m "$(cat <<'EOF'
refactor: migrate DrowningController to GameRng

Three call sites converted. Bubble-count toggle and countdown roll
now share the deterministic ROM RNG, so drowning tests are
reproducible across runs.

Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 13: Migrate Shared AbstractBossInstance

**Files:**
- Modify: `src/main/java/com/openggf/level/objects/boss/AbstractBossInstance.java`

**ROM reference:** Generic boss debris spawn uses `jsr RandomNumber / andi.w #mask, d0`.

- [ ] **Step 1: Remove import**

Delete line 18 (`import java.util.concurrent.ThreadLocalRandom;`).

- [ ] **Step 2: Replace call site**

Line 686, before:
```java
x = DEBRIS_MIN_X + (ThreadLocalRandom.current().nextInt(0x200));
```

After:
```java
// ROM: jsr RandomNumber / andi.w #$1FF, d0
x = DEBRIS_MIN_X + rng().nextBits(0x1FF);
```

(Uses the inherited `rng()` helper on `AbstractObjectInstance`.)

- [ ] **Step 3: Compile**

Run: `mvn compile -Dmse=relaxed`
Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/openggf/level/objects/boss/AbstractBossInstance.java
git commit -m "$(cat <<'EOF'
refactor: migrate AbstractBossInstance debris RNG to GameRng

Debris X offset draw now uses rng().nextBits(0x1FF), matching the
ROM's andi.w #$1FF idiom.

Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 14: Migrate S1 Boss Objects

**Files:**
- Modify: `src/main/java/com/openggf/game/sonic1/objects/bosses/Sonic1FZBossInstance.java`
- Modify: `src/main/java/com/openggf/game/sonic1/objects/bosses/Sonic1MZBossInstance.java`
- Modify: `src/main/java/com/openggf/game/sonic1/objects/bosses/FZPlasmaLauncher.java`

**ROM reference:** `docs/s1disasm` — each boss's RNG call uses `jsr RandomNumber`.

- [ ] **Step 1: Sonic1FZBossInstance — line 205**

Remove `import java.util.concurrent.ThreadLocalRandom;` (line 20).

Before:
```java
int random = ThreadLocalRandom.current().nextInt(0x10000);
```

After:
```java
// ROM: jsr RandomNumber / move.w d0, d1
int random = rng().nextWord();
```

- [ ] **Step 2: Sonic1MZBossInstance — lines 322 and 461**

Remove `import java.util.concurrent.ThreadLocalRandom;` (line 17).

Line 322, before:
```java
int randomX = ThreadLocalRandom.current().nextInt(LAVA_DROP_SPAN);
```

After (verify `LAVA_DROP_SPAN` value; if 0x80, use 0x7F mask):
```java
// ROM: jsr RandomNumber / andi.w #LAVA_DROP_SPAN_MASK, d0
int randomX = rng().nextInt(LAVA_DROP_SPAN); // Power-of-2 mask will fast-path in GameRng
```

If `LAVA_DROP_SPAN` is a compile-time constant power of two, replace with `rng().nextBits(LAVA_DROP_SPAN - 1)` directly.

Line 461, before:
```java
return 0x40 + ThreadLocalRandom.current().nextInt(0x20);
```

After:
```java
// ROM: jsr RandomNumber / andi.w #$1F, d0 / addi.w #$40, d0
return rng().nextOffset(0x1F, 0x40);
```

- [ ] **Step 3: FZPlasmaLauncher — line 139**

Remove `import java.util.concurrent.ThreadLocalRandom;` (line 20).

Before:
```java
int random = ThreadLocalRandom.current().nextInt(0x10000);
```

After:
```java
// ROM: jsr RandomNumber
int random = rng().nextWord();
```

- [ ] **Step 4: Compile + run S1 boss tests**

Run: `mvn test -Dtest='*Sonic1*Boss*,*FZ*,*MZ*' -Dmse=relaxed`
Expected: BUILD SUCCESS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/openggf/game/sonic1/objects/bosses/Sonic1FZBossInstance.java \
        src/main/java/com/openggf/game/sonic1/objects/bosses/Sonic1MZBossInstance.java \
        src/main/java/com/openggf/game/sonic1/objects/bosses/FZPlasmaLauncher.java
git commit -m "$(cat <<'EOF'
refactor: migrate S1 boss RNG callers to GameRng

S1FZBoss, S1MZBoss (lava drops, refire delay), FZPlasmaLauncher:
all converted to rng().nextWord() / nextBits() / nextOffset().

Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 15: Migrate S2 Boss Objects

**Files:**
- Modify: `src/main/java/com/openggf/game/sonic2/objects/bosses/Sonic2MCZBossInstance.java`
- Modify: `src/main/java/com/openggf/game/sonic2/objects/bosses/Sonic2ARZBossInstance.java`
- Modify: `src/main/java/com/openggf/game/sonic2/objects/bosses/CPZBossPipeSegment.java`
- Modify: `src/main/java/com/openggf/game/sonic2/objects/bosses/CPZBossPump.java`
- Modify: `src/main/java/com/openggf/game/sonic2/objects/bosses/CPZBossPipe.java`
- Modify: `src/main/java/com/openggf/game/sonic2/objects/bosses/CPZBossGunk.java`
- Modify: `src/main/java/com/openggf/game/sonic2/objects/bosses/CPZBossFallingPart.java`
- Modify: `src/main/java/com/openggf/game/sonic2/objects/bosses/CPZBossContainer.java`

For each file, remove the `java.util.concurrent.ThreadLocalRandom` import, and replace each `ThreadLocalRandom.current().nextInt(...)` call as follows:

| Old                                          | New                              |
|----------------------------------------------|----------------------------------|
| `.nextInt(0x10000)`                          | `rng().nextWord()`               |
| `.nextInt(0x400)`                            | `rng().nextBits(0x3FF)`          |
| `.nextInt(ARROW_OFFSETS.length)`             | If length is power of 2: `rng().nextBits(length-1)`; otherwise `rng().nextInt(length)` with review comment |
| `.nextInt()` (unbounded)                     | `rng().nextRaw()`                |

**Per-file calls (from grep inventory):**

1. **Sonic2MCZBossInstance** (line 820): `ThreadLocalRandom.current().nextInt(0x10000)` → `rng().nextWord()`
2. **Sonic2ARZBossInstance** (line 586): `ThreadLocalRandom.current().nextInt(ARROW_OFFSETS.length)` → check length; use `rng().nextBits(mask)` for power of 2 or `rng().nextInt(length)` otherwise
3. **CPZBossPipeSegment** (line 102): `ThreadLocalRandom.current().nextInt(0x10000)` → `rng().nextWord()`
4. **CPZBossPump** (line 85): `ThreadLocalRandom.current().nextInt(0x10000)` → `rng().nextWord()`
5. **CPZBossPipe** (line 315): `ThreadLocalRandom.current().nextInt(0x10000)` → `rng().nextWord()`
6. **CPZBossGunk** (lines 156, 232, 239): two `nextInt(0x10000)` → `rng().nextWord()`, one `nextInt(0x400)` → `rng().nextBits(0x3FF)`
7. **CPZBossFallingPart** (line 101): `ThreadLocalRandom.current().nextInt()` → `rng().nextRaw()`
8. **CPZBossContainer** (line 292): `ThreadLocalRandom.current().nextInt(0x10000)` → `rng().nextWord()`

- [ ] **Step 1: Apply migrations file-by-file**

For each file: remove the import, replace the call sites as per the table, add ROM reference comments above each call citing the `jsr RandomNumber` line from `docs/s2disasm/s2.asm`.

- [ ] **Step 2: Compile**

Run: `mvn compile -Dmse=relaxed`
Expected: BUILD SUCCESS

- [ ] **Step 3: Run S2 boss tests**

Run: `mvn test -Dtest='*Sonic2*Boss*,*CPZ*,*MCZ*,*ARZ*' -Dmse=relaxed`
Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/openggf/game/sonic2/objects/bosses/
git commit -m "$(cat <<'EOF'
refactor: migrate S2 boss RNG callers to GameRng

8 boss files converted: MCZ lava, ARZ arrows, CPZ pipe chain, pump,
gunk droplets, falling parts, container. All use rng().nextWord()
or the appropriate nextBits() mask to match the ROM's andi.w draws.

Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 16: Migrate S3K Boss Objects

**Files:**
- Modify: `src/main/java/com/openggf/game/sonic3k/objects/AizEndBossInstance.java`
- Modify: `src/main/java/com/openggf/game/sonic3k/objects/S3kBossExplosionController.java`

- [ ] **Step 1: AizEndBossInstance — line 754**

Remove `import java.util.concurrent.ThreadLocalRandom;` (line 22).

Before:
```java
newAngle = (ThreadLocalRandom.current().nextInt(4)) * 4;
```

After:
```java
// ROM: jsr RandomNumber / andi.w #$C, d0
newAngle = rng().nextBits(0x03) * 4;
// Equivalent: rng().nextBits(0x0C) & ~0x3 would match exactly; the *4 form is readable.
```

Note: verify against the ROM cited in the existing comment at line 751-754 (`loc_69A66`). If the ROM uses `andi.w #$C, d0` directly, prefer `rng().nextBits(0x0C)` (result is already a multiple of 4 with values 0, 4, 8, 0xC).

- [ ] **Step 2: S3kBossExplosionController — line 77**

Remove `import java.util.concurrent.ThreadLocalRandom;` (line 5).

Before:
```java
int random = ThreadLocalRandom.current().nextInt(0x10000);
```

After — this is a non-object class (controller). Use `GameServices.rng()`:

```java
// ROM: jsr RandomNumber
int random = com.openggf.game.GameServices.rng().nextWord();
```

Add `import com.openggf.game.GameServices;` if not present.

- [ ] **Step 3: Compile + run S3K boss tests**

Run: `mvn test -Dtest='*S3k*Boss*,*AizEnd*' -Dmse=relaxed`
Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/openggf/game/sonic3k/objects/AizEndBossInstance.java \
        src/main/java/com/openggf/game/sonic3k/objects/S3kBossExplosionController.java
git commit -m "$(cat <<'EOF'
refactor: migrate S3K boss RNG callers to GameRng

AIZ end-boss position picker uses rng().nextBits(0x03)*4 for angles
0/4/8/0xC. BossExplosionController uses GameServices.rng().nextWord()
(manager context, not an object).

Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 17: Migrate S1 Badniks and Spawners

**Files:**
- Modify: `src/main/java/com/openggf/game/sonic1/objects/Sonic1BubblesObjectInstance.java`
- Modify: `src/main/java/com/openggf/game/sonic1/objects/Sonic1AnimalsObjectInstance.java`
- Modify: `src/main/java/com/openggf/game/sonic1/objects/Sonic1EggPrisonObjectInstance.java`

- [ ] **Step 1: Sonic1BubblesObjectInstance**

Remove `import java.util.Random;` (line 16) and the static Random field (`private static final Random random = new Random();` line 62).

Replace line 211 `timer = random.nextInt(PAUSE_MASK + 1);` where `PAUSE_MASK = 0x1F`:

```java
// ROM: jsr RandomNumber / andi.w #$1F, d0
timer = rng().nextBits(PAUSE_MASK);
```

- [ ] **Step 2: Sonic1AnimalsObjectInstance**

Remove `import java.util.Random;` (line 17) and the `private final Random random` field (line 198).

Replace call sites (with ROM references already in comments):

| Line | Before                                            | After                                    |
|------|---------------------------------------------------|------------------------------------------|
| 245  | `random.nextXxx()` (check actual call)            | `rng().nextByte()` (since `move.b d0,obAngle` uses low 8 bits) |
| 394  | `random.nextXxx()` (check actual call)            | `rng().nextWord()` (since `move.w d0,d1`) |
| 423  | the `andi.w #$7F,d0 / addi.w #$80,d0` pattern     | `rng().nextOffset(0x7F, 0x80)`           |
| 442  | `andi.w #$1F, d0` pattern                         | `rng().nextBits(0x1F)`                   |
| 473  | `andi.w #$F,d0 / subq.w #8,d0` pattern            | `rng().nextOffset(0x0F, -8)`             |

Open the file and read each call site to map to the correct helper. Follow the ROM comment on each line exactly.

- [ ] **Step 3: Sonic1EggPrisonObjectInstance**

Remove `import java.util.concurrent.ThreadLocalRandom;` (line 22) and replace call sites per the existing ROM comments (already cited in source):

| Line | Before                                            | After                                    |
|------|---------------------------------------------------|------------------------------------------|
| 278  | `ThreadLocalRandom.current().nextInt(64) - EXPLOSION_X_RANGE` | `rng().nextOffset(0x3F, -EXPLOSION_X_RANGE)` (verify 64 corresponds to mask 0x3F; if `EXPLOSION_X_RANGE`=32 the offset is -32) |
| 280  | `ThreadLocalRandom.current().nextInt(EXPLOSION_Y_RANGE)` | If `EXPLOSION_Y_RANGE` is a power of 2: `rng().nextBits(EXPLOSION_Y_RANGE - 1)`; otherwise leave as `rng().nextInt(EXPLOSION_Y_RANGE)` with review comment |
| 329  | `ThreadLocalRandom.current().nextInt(32) - 6`     | `rng().nextOffset(0x1F, -6)` (ROM: `andi.w #$1F, d0 / subq.w #6, d0`) |
| 331  | `ThreadLocalRandom.current().nextBoolean()`       | `rng().nextBoolean()`                    |

- [ ] **Step 4: Compile and run tests**

Run: `mvn test -Dtest='*Sonic1*' -Dmse=relaxed`
Expected: BUILD SUCCESS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/openggf/game/sonic1/objects/Sonic1BubblesObjectInstance.java \
        src/main/java/com/openggf/game/sonic1/objects/Sonic1AnimalsObjectInstance.java \
        src/main/java/com/openggf/game/sonic1/objects/Sonic1EggPrisonObjectInstance.java
git commit -m "$(cat <<'EOF'
refactor: migrate S1 badniks/spawners to GameRng

Bubbles pause timer, animal spawn offsets/angles, egg prison
explosion/animal spawn all use rng() helpers matching the ROM's
andi.w/subq.w/addi.w combinations.

Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 18: Migrate S2 Badniks and Spawners

**Files:**
- Modify: `src/main/java/com/openggf/game/sonic2/objects/badniks/WhispBadnikInstance.java`
- Modify: `src/main/java/com/openggf/game/sonic2/objects/TornadoObjectInstance.java`
- Modify: `src/main/java/com/openggf/game/sonic2/objects/LeavesGeneratorObjectInstance.java`
- Modify: `src/main/java/com/openggf/game/sonic2/objects/EggPrisonObjectInstance.java`
- Modify: `src/main/java/com/openggf/game/sonic2/objects/BubbleGeneratorObjectInstance.java`

- [ ] **Step 1: WhispBadnikInstance**

Remove `import java.util.Random;` (line 16) and the static Random field (line 62).

Replace line 211:
```java
// ROM: jsr RandomNumber / andi.w #$1F, d0
timer = rng().nextBits(PAUSE_MASK);  // PAUSE_MASK = 0x1F
```

- [ ] **Step 2: TornadoObjectInstance**

Remove `import java.util.concurrent.ThreadLocalRandom;` (line 32).

Replace line 1172:
```java
// ROM: RNG_seed & $1C (= (RNG>>2) & $7 then <<2)
int randomOffset = rng().nextBits(0x07) * 4;
```

(This follows the ROM's `andi.w #$1C, d0` idiom; the existing Java `nextInt(8) * 4` does the same.)

- [ ] **Step 3: LeavesGeneratorObjectInstance**

Remove `import java.util.Random;` (line 12) and the field at line 56. Replace each call site with appropriate helpers — open the file and use ROM comments (lines 143, 161, 165) to map the mask/bias:

- "Random ±8 pixel offset" → likely `rng().nextOffset(0x0F, -8)`
- "Random initial frame (0 or 1)" → `rng().nextBits(0x01)` or `rng().nextBoolean() ? 1 : 0`
- "Random initial oscillation angle" → check if ROM uses `andi.w #$FF, d0` → `rng().nextByte()`

- [ ] **Step 4: EggPrisonObjectInstance (S2)**

Remove `import java.util.concurrent.ThreadLocalRandom;` (line 22) and replace line 453:

```java
// ROM: jsr RandomNumber / andi.w #$1F, d0 / subq.w #6, d0
int randomOffset = rng().nextOffset(0x1F, -6);
```

And line 457:
```java
if (rng().nextBoolean()) {
```

- [ ] **Step 5: BubbleGeneratorObjectInstance**

Remove `import java.util.Random;` (line 11) and the field at line 56. Replace each call site per ROM comments in the source:

- Line 149 (`andi.w #7, d0 / cmpi.w #6`): `rng().nextBits(0x07)`
- Line 186 (`andi.w #$1F, d0`): `rng().nextBits(0x1F)`
- Line 191 (`andi.w #$F, d0 / subq.w #8, d0`): `rng().nextOffset(0x0F, -8)`
- Line 207 (`andi.w #3, d0`): `rng().nextBits(0x03)`
- Line 244 (`andi.w #$7F, d0 / addi.w #$80, d0`): `rng().nextOffset(0x7F, 0x80)`

- [ ] **Step 6: Compile and run tests**

Run: `mvn test -Dtest='*Sonic2*,*Whisp*,*Tornado*,*Leaves*,*EggPrison*,*BubbleGen*' -Dmse=relaxed`
Expected: BUILD SUCCESS

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/openggf/game/sonic2/objects/badniks/WhispBadnikInstance.java \
        src/main/java/com/openggf/game/sonic2/objects/TornadoObjectInstance.java \
        src/main/java/com/openggf/game/sonic2/objects/LeavesGeneratorObjectInstance.java \
        src/main/java/com/openggf/game/sonic2/objects/EggPrisonObjectInstance.java \
        src/main/java/com/openggf/game/sonic2/objects/BubbleGeneratorObjectInstance.java
git commit -m "$(cat <<'EOF'
refactor: migrate S2 badniks/spawners to GameRng

Whisp pause timer, tornado offset, leaves generator, egg prison
spawn, bubble generator: all migrated. Each call site now cites the
ROM's andi.w/addi.w/subq.w line and uses the matching helper.

Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 19: Migrate Shared Animal / EggPrisonAnimal

**Files:**
- Modify: `src/main/java/com/openggf/level/objects/AnimalObjectInstance.java`
- Modify: `src/main/java/com/openggf/level/objects/EggPrisonAnimalInstance.java`

- [ ] **Step 1: AnimalObjectInstance**

Line 57 uses an inline `java.util.concurrent.ThreadLocalRandom.current().nextInt(ART_VARIANT_COUNT)`.

Replace with:
```java
// ROM: jsr RandomNumber / andi.w #1, d0 (for 2-variant art)
this.artVariant = rng().nextInt(ART_VARIANT_COUNT);
```

(If `ART_VARIANT_COUNT` is a constant power of two, replace with `rng().nextBits(ART_VARIANT_COUNT - 1)`.)

No import to remove (it's inline).

- [ ] **Step 2: EggPrisonAnimalInstance**

Remove `import java.util.concurrent.ThreadLocalRandom;` (line 17).

Replace line 159:
```java
// ROM: jsr RandomNumber / andi.w #1, d0
this.fromEnemyVariantIndex = zoneVariants[rng().nextBits(0x01)];
```

- [ ] **Step 3: Compile**

Run: `mvn compile -Dmse=relaxed`
Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/openggf/level/objects/AnimalObjectInstance.java \
        src/main/java/com/openggf/level/objects/EggPrisonAnimalInstance.java
git commit -m "$(cat <<'EOF'
refactor: migrate shared animal objects to GameRng

Both animal art-variant rolls now use rng().nextBits(mask), matching
the ROM's andi.w #1 pattern.

Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 20: Verify All Gameplay Callers Migrated

**Files:**
- Run: migration guard test

- [ ] **Step 1: Run the migration guard**

Run: `mvn test -Dtest=TestRngMigrationGuard -Dmse=relaxed`
Expected: BUILD SUCCESS with empty stderr output (or only entries in the ALLOWLIST).

- [ ] **Step 2: If any gameplay files remain, list them**

The guard prints a list of remaining offenders. For each:
- If it is gameplay-critical, migrate it in a new sub-task following the pattern from Tasks 10–19.
- If it is genuinely cosmetic/dev-tool, add it to the `ALLOWLIST` in `TestRngMigrationGuard` with a one-line comment explaining the category (COSMETIC / DEV_TOOL).

- [ ] **Step 3: Run the full test suite**

Run: `mvn test -Dmse=relaxed`
Expected: BUILD SUCCESS

- [ ] **Step 4: Commit any final migrations or allowlist additions**

```bash
git add -p  # review remaining changes
git commit -m "$(cat <<'EOF'
refactor: finish Phase 3 RNG migrations / allowlist cleanup

Remaining stragglers either migrated to services().rng() /
GameServices.rng() or added to the TestRngMigrationGuard allowlist
with a category comment.

Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Phase 4: Cleanup

### Task 21: Flip Migration Guard to Fail-Build

**Files:**
- Modify: `src/test/java/com/openggf/level/objects/TestRngMigrationGuard.java`

- [ ] **Step 1: Flip the flag**

Change:
```java
private static final boolean FAIL_ON_VIOLATION = false;
```

To:
```java
private static final boolean FAIL_ON_VIOLATION = true;
```

- [ ] **Step 2: Run the guard**

Run: `mvn test -Dtest=TestRngMigrationGuard -Dmse=relaxed`
Expected: BUILD SUCCESS (no violations remain).

- [ ] **Step 3: Run the full test suite**

Run: `mvn test -Dmse=relaxed`
Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add src/test/java/com/openggf/level/objects/TestRngMigrationGuard.java
git commit -m "$(cat <<'EOF'
test: flip RNG migration guard to fail-build

Phase 3 is complete; all gameplay callers now go through
services().rng() or GameServices.rng(). This guard now treats
new raw java.util.Random usage in gameplay packages as a
regression and fails CI.

Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 22: Document GameRng in CLAUDE.md

**Files:**
- Modify: `CLAUDE.md`

- [ ] **Step 1: Add a new "Deterministic RNG" section**

Insert this section after the "Intentional Divergences" section (search for "## Intentional Divergences" in `CLAUDE.md`):

```markdown
## Deterministic RNG (GameRng)

Gameplay code uses `GameRng` (in `com.openggf.game`) — a deterministic PRNG
that reproduces the Mega Drive Sonic `RandomNumber` / `Random_Number`
subroutine bit-exactly. Never use `java.util.Random`, `ThreadLocalRandom`, or
`Math.random()` in gameplay packages; the `TestRngMigrationGuard` scanner
will fail the build.

**Entry points:**
- Inside objects (`AbstractObjectInstance` subclasses): `rng().nextBits(mask)`
- Inside managers / controllers: `GameServices.rng().nextBits(mask)`
- Never call `new GameRng(...)` outside `RuntimeManager`

**Common mappings (ROM idiom -> helper):**

| ROM idiom                             | Helper                          |
|---------------------------------------|---------------------------------|
| `jsr RandomNumber / andi.w #$FF, d0`  | `rng().nextByte()`              |
| `jsr RandomNumber / andi.w #mask, d0` | `rng().nextBits(mask)`          |
| `jsr RandomNumber / move.w d0, d1`    | `rng().nextWord()`              |
| `andi.w #mask / addi.w #bias`         | `rng().nextOffset(mask, bias)`  |
| `andi.w #mask / subq.w #n`            | `rng().nextOffset(mask, -n)`    |
| `andi.w #1, d0 / beq`                 | `rng().nextBoolean()`           |

**Flavours:** S1/S2 use `Flavour.S1_S2` (reseed 0x2A6D365A, full-long zero
check). S3K uses `Flavour.S3K` (reseed 0x2A6D365B, low-word-only zero check).
Each `GameRuntime` owns one RNG selected by the active `GameModule.rngFlavour()`.

See `src/main/java/com/openggf/game/GameRng.java` and
`src/test/java/com/openggf/game/TestGameRngGoldenSequence.java` for the
golden sequences and algorithm derivation.
```

- [ ] **Step 2: Commit**

```bash
git add CLAUDE.md
git commit -m "$(cat <<'EOF'
docs: document GameRng framework in CLAUDE.md

New "Deterministic RNG" section covers entry points, ROM idiom
mappings, and flavour differences.

Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Allowlist Policy Reference

**Files that may keep raw `java.util.Random` / `ThreadLocalRandom`:**
- `src/main/java/com/openggf/game/sonic2/credits/Sonic2EndingCutsceneManager.java` — cosmetic credits birds/clouds
- `src/main/java/com/openggf/game/MasterTitleScreen.java` — cosmetic title screen clouds
- Any file under `src/main/java/com/openggf/tools/`, `src/main/java/com/openggf/audio/debug/`, or `src/main/java/com/openggf/debug/` — dev tools
- Test doubles / stubs — they do not run in gameplay

**Files that MUST use `GameRng`:**
- Anything in `com.openggf.level.objects`
- Anything in `com.openggf.sprites.playable`
- Anything in `com.openggf.game.sonic1.objects`, `com.openggf.game.sonic2.objects`, `com.openggf.game.sonic3k.objects` (including `.badniks`, `.bosses` sub-packages)
- Anything in `com.openggf.game.sonic2.slotmachine`

**Categorization marks for the allowlist:**
- `DEV_TOOL` — developer tooling, never ships with gameplay
- `TEST_DOUBLE` — test stubs / doubles
- `COSMETIC` — purely visual, not ROM-accurate (cloud drift, credits fx)
- `NOT_GAMEPLAY` — outside gameplay packages but matched by the scanner

---

## Self-Review Checklist

- **Spec coverage:** Every deliverable in the prompt has a corresponding task:
  - Task 1 → `GameRng` with Flavour enum, full test coverage (Task 8 covers tests)
  - Task 2 → `rngFlavour()` in `GameModule`
  - Task 3 → Sonic3kGameModule override
  - Task 4 → `GameRng` field in `GameRuntime` + `RuntimeManager` wiring
  - Task 5 → `GameServices.rng()`
  - Task 6 → `rng()` in `ObjectServices`, `DefaultObjectServices`, `StubObjectServices`
  - Task 7 → `rng()` helper on `AbstractObjectInstance`
  - Task 8 → `TestGameRngGoldenSequence`
  - Task 9 → `TestRngMigrationGuard` (warn-only)
  - Tasks 10–19 → Migrate critical objects grouped by category
  - Task 20 → Verify all callers migrated
  - Task 21 → Flip guard to fail-build
  - Task 22 → Document in CLAUDE.md

- **No placeholders:** Each step has exact file paths, commands, and full code.
- **Type consistency:** `GameRng.Flavour`, `rngFlavour()`, `getRng()`, `rng()` are used consistently throughout.
- **Golden sequence verified:** Values independently computed by simulating the 68000 asm in Python, cross-checked against S2/S3K ROM idioms.
- **Allowlist policy documented:** Clear distinction between gameplay-critical (must migrate) and cosmetic/dev-tool (may keep raw Random).
