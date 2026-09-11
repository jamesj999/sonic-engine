
> Historical note: this document may mention legacy JUnit 4 migration details. New and updated tests in this repository must use JUnit 5 / Jupiter only; do not create new JUnit 4 tests, rules, or runners.


> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement palette animation (AnPal_*) cycling for all remaining S3K zones that have non-stub routines in the ROM, plus the HCZ1 _Resize palette mutation.

**Architecture:** Each zone gets a `PaletteCycle` inner class in `Sonic3kPaletteCycler.java`, following the existing AIZ pattern. ROM data table addresses are found via `RomOffsetFinder`, added to `Sonic3kConstants.java`, loaded via `safeSlice()`, and cycled per-frame using counter/step/limit logic matching the disassembly. Each zone also gets a headless test proving its palette colors change over time.

**Tech Stack:** Java 21, Maven, and S3K ROM (`Sonic and Knuckles & Sonic 3 (W) [!].gen`). Any new or updated tests should use JUnit 5 / Jupiter only; the JUnit 4 references below are legacy migration context.

---

## Shared Context for All Tasks

### Files Every Task Touches

- **Modify:** `src/main/java/com/openggf/game/sonic3k/constants/Sonic3kConstants.java` (add ANPAL_*_ADDR/SIZE constants)
- **Modify:** `src/main/java/com/openggf/game/sonic3k/Sonic3kPaletteCycler.java` (add PaletteCycle subclass + loadXxxCycles method + case in loadCycles switch)
- **Create:** `src/test/java/com/openggf/game/sonic3k/TestS3k<Zone>PaletteCycling.java`

### Reference Files (Read-Only)

- **Pattern to follow:** `Sonic3kPaletteCycler.java` — see `Aiz2WaterCycle` (multi-channel with camera-X switching) and `Aiz2TorchCycle` (simple single-channel) as templates
- **Test pattern:** `TestS3kAiz2PaletteCycling.java` — headless test that loads a zone, ticks animation, and asserts palette colors change
- **Constants pattern:** `Sonic3kConstants.java` lines 397-424 — ANPAL_AIZ*_ADDR/SIZE naming convention
- **Disassembly:** `docs/skdisasm/sonic3k.asm` and `docs/skdisasm/s3.asm` — authoritative source for routine logic and data tables

### How to Find ROM Addresses

Use `RomOffsetFinder` to search for data table labels:
```bash
mvn exec:java "-Dexec.mainClass=com.openggf.tools.disasm.RomOffsetFinder" "-Dexec.args=--game s3k search AnPal_PalHCZ" -q
```

If `search` doesn't find the label (inline `dc.w` data without a `binclude`), use `search-rom` with the first few data bytes from the disassembly:
```bash
mvn exec:java "-Dexec.mainClass=com.openggf.tools.disasm.RomOffsetFinder" "-Dexec.args=--game s3k search-rom \"0EC8 0EC0 0EA0 0E80\"" -q
```

### Palette Line Mapping (ROM to Engine)

| ROM Name | Engine Call |
|----------|------------|
| `Normal_palette_line_1+$XX` | `level.getPalette(0).getColor(XX/2)` |
| `Normal_palette_line_2+$XX` | `level.getPalette(1).getColor(XX/2)` |
| `Normal_palette_line_3+$XX` | `level.getPalette(2).getColor(XX/2)` |
| `Normal_palette_line_4+$XX` | `level.getPalette(3).getColor(XX/2)` |

Byte offset `$XX` / 2 = color index (each color is 2 bytes in Sega format).

A "longword write" (4 bytes) writes 2 consecutive colors. A "word write" (2 bytes) writes 1 color.

### Water Palette Sync

HCZ and CNZ also write to `Water_palette_line_*`. The engine's S3K underwater palette system is not yet fully wired for palette cycling. **For now, implement only `Normal_palette_line_*` writes and add a `// TODO: sync to underwater palette` comment.** This keeps scope manageable and the visual fix is still correct for above-water rendering.

### Test Pattern

Every test follows this structure (copy from `TestS3kAiz2PaletteCycling.java`):

```java
@RequiresRom(SonicGame.SONIC_3K)
public class TestS3k<Zone>PaletteCycling {
    @ClassRule public static RequiresRomRule romRule = new RequiresRomRule();
    private static SharedLevel sharedLevel;

    @BeforeClass
    public static void loadLevel() throws Exception {
        SonicConfigurationService.getInstance()
            .setConfigValue(SonicConfiguration.S3K_SKIP_INTROS, true);
        sharedLevel = SharedLevel.load(SonicGame.SONIC_3K, ZONE_ID, ACT);
    }

    @AfterClass
    public static void cleanup() {
        if (sharedLevel != null) sharedLevel.dispose();
    }

    private HeadlessTestFixture fixture;

    @Before
    public void setUp() {
        fixture = HeadlessTestFixture.builder()
                .withSharedLevel(sharedLevel)
                .build();
    }

    private void tickAnimation() {
        AnimatedPatternManager apm = LevelManager.getInstance().getAnimatedPatternManager();
        if (apm != null) apm.update();
    }

    @Test
    public void <channelName>ModifiesPalette() {
        Level level = LevelManager.getInstance().getCurrentLevel();
        Palette pal = level.getPalette(PALETTE_INDEX);
        Palette.Color color = pal.getColor(COLOR_INDEX);
        int initialR = color.r & 0xFF;
        int initialG = color.g & 0xFF;
        int initialB = color.b & 0xFF;

        boolean changed = false;
        for (int frame = 0; frame < FRAME_COUNT; frame++) {
            fixture.stepIdleFrames(1);
            tickAnimation();
            if ((color.r & 0xFF) != initialR || (color.g & 0xFF) != initialG
                    || (color.b & 0xFF) != initialB) {
                changed = true;
                break;
            }
        }
        assertTrue("Expected palette[N] color M to change", changed);
    }
}
```

### Build/Test Commands

```bash
mvn compile -Dmse=relaxed                              # Verify compilation
mvn test -Dtest=TestS3k<Zone>PaletteCycling -Dmse=relaxed  # Run zone test
mvn test -Dtest=TestS3kAiz2PaletteCycling -Dmse=relaxed    # Regression check
```

---

## Task 1: HCZ — Hydrocity Zone Palette Cycling + _Resize Mutation

**Zone ID:** 0x01. **Acts:** Shared routine (HCZ2 is a stub/rts).

**Files:**
- Modify: `src/main/java/com/openggf/game/sonic3k/constants/Sonic3kConstants.java`
- Modify: `src/main/java/com/openggf/game/sonic3k/Sonic3kPaletteCycler.java`
- Modify: `src/main/java/com/openggf/game/sonic3k/events/Sonic3kHCZEvents.java` (create if absent, or add to existing zone events for HCZ1 _Resize mutation)
- Create: `src/test/java/com/openggf/game/sonic3k/TestS3kHczPaletteCycling.java`

### AnPal Channel Spec (from disassembly)

**Channel 1: Water animation** (timer period 8)
- Counter: `Palette_cycle_counter0`, step +8, wrap by mask `& 0x18` (cycles 0,8,16,24)
- Gated by: `Palette_cycle_counters+$00` byte — if non-zero, skip (resets counter1 to 0)
- Table: `AnPal_PalHCZ1` (32 bytes = 16 words, 4 frames x 4 colors)
- Destination: `Normal_palette_line_3+$06` → palette[2] colors 3-6 (2 longwords = 4 colors)
- Water sync: Also writes to `Water_palette_line_3+$06` (TODO: defer)

**Data (AnPal_PalHCZ1):**
```
$0EC8, $0EC0, $0EA0, $0E80,
$0EC0, $0EA0, $0E80, $0EC8,
$0EA0, $0E80, $0EC8, $0EC0,
$0E80, $0EC8, $0EC0, $0EA0
```

### HCZ1 _Resize Palette Mutation

HCZ1_Resize writes 3 colors to `Normal_palette_line_4+$10` (palette[3] colors 8-10) based on camera position:
- **Cave entry** (cameraX < $360 AND cameraY >= $3E0): writes `$0680, $0240, $0220`
- **Cave exit** (various thresholds): writes `$0CEE, $0ACE, $008A`

This needs to be added to the HCZ zone event handler, similar to `Sonic3kAIZEvents.updateStage2PaletteColor()`.

### Steps

- [ ] **Step 1:** Find ROM address for `AnPal_PalHCZ1` using RomOffsetFinder or search-rom with bytes `0EC8 0EC0 0EA0 0E80`
- [ ] **Step 2:** Add `ANPAL_HCZ1_ADDR` and `ANPAL_HCZ1_SIZE = 32` to `Sonic3kConstants.java`
- [ ] **Step 3:** Create `HczCycle extends PaletteCycle` in `Sonic3kPaletteCycler.java` following the `Aiz1Cycle` gameplay-mode pattern (timer=7, counter0 & 0x18, 4 colors to palette[2])
- [ ] **Step 4:** Add `loadHczCycles()` method and register in `loadCycles()` switch case 0x01
- [ ] **Step 5:** Implement HCZ1 _Resize palette mutation in the appropriate event handler (check if `Sonic3kHCZEvents` exists; if not, the palette write can be added as a secondary channel in `HczCycle` that checks camera position instead of using a timer)
- [ ] **Step 6:** Write `TestS3kHczPaletteCycling.java` — load HCZ Act 1 (zone 0x01, act 0), tick 60 frames, assert palette[2] color 3 changes
- [ ] **Step 7:** Run test: `mvn test -Dtest=TestS3kHczPaletteCycling -Dmse=relaxed`
- [ ] **Step 8:** Run regression: `mvn test -Dtest=TestS3kAiz2PaletteCycling -Dmse=relaxed`
- [ ] **Step 9:** Commit: `feat(s3k): HCZ palette cycling and _Resize mutation`

---

## Task 2: CNZ — Carnival Night Zone Palette Cycling

**Zone ID:** 0x03. **Acts:** Shared routine for both acts.

**Files:**
- Modify: `src/main/java/com/openggf/game/sonic3k/constants/Sonic3kConstants.java`
- Modify: `src/main/java/com/openggf/game/sonic3k/Sonic3kPaletteCycler.java`
- Create: `src/test/java/com/openggf/game/sonic3k/TestS3kCnzPaletteCycling.java`

### AnPal Channel Spec

**Channel 1: Bumpers/teacups** (timer period 4)
- Counter0: step +6, wrap at 0x60 (96 bytes, 16 frames)
- Table Normal: `AnPal_PalCNZ_1` (96 bytes)
- Table Water: `AnPal_PalCNZ_2` (96 bytes)
- Destination: `Normal_palette_line_4+$12` → palette[3] colors 9-11 (1 longword + 1 word = 3 colors)
- Water sync: writes to `Water_palette_line_4+$12` (TODO: defer)

**Channel 2: Background** (always runs, gated by channel 1's timer tick)
- Counter: `Palette_cycle_counters+$02`, step +6, wrap at 0xB4 (180 bytes, 30 frames)
- Table Normal: `AnPal_PalCNZ_3` (180 bytes)
- Table Water: `AnPal_PalCNZ_4` (180 bytes)
- Destination: `Normal_palette_line_3+$12` → palette[2] colors 9-11 (3 colors)

**Channel 3: Tertiary** (timer period 3, independent)
- Counter: `Palette_cycle_counters+$04`, step +4, wrap at 0x40 (64 bytes, 16 frames)
- Table: `AnPal_PalCNZ_5` (64 bytes)
- Destination: `Normal_palette_line_3+$0E` → palette[2] colors 7-8 (1 longword = 2 colors)
- Water sync: writes to `Water_palette_line_3+$0E` (TODO: defer)

### Steps

- [ ] **Step 1:** Find ROM addresses for `AnPal_PalCNZ_1` through `AnPal_PalCNZ_5`
- [ ] **Step 2:** Add 5 ADDR/SIZE constant pairs to `Sonic3kConstants.java`
- [ ] **Step 3:** Create `CnzCycle extends PaletteCycle` with 3 independent channels, each with its own timer and counter
- [ ] **Step 4:** Add `loadCnzCycles()` and register in `loadCycles()` case 0x03
- [ ] **Step 5:** Write `TestS3kCnzPaletteCycling.java` — load CNZ Act 1 (zone 0x03, act 0), test palette[3] color 9 and palette[2] color 7 change
- [ ] **Step 6:** Run test and regression check
- [ ] **Step 7:** Commit: `feat(s3k): CNZ palette cycling (3 channels, bumpers/background/tertiary)`

---

## Task 3: ICZ — IceCap Zone Palette Cycling

**Zone ID:** 0x05. **Acts:** Shared routine for both acts.

**Files:**
- Modify: `src/main/java/com/openggf/game/sonic3k/constants/Sonic3kConstants.java`
- Modify: `src/main/java/com/openggf/game/sonic3k/Sonic3kPaletteCycler.java`
- Create: `src/test/java/com/openggf/game/sonic3k/TestS3kIczPaletteCycling.java`

### AnPal Channel Spec

**Channel 1: Geyser/ice** (timer period 6)
- Counter0: step +4, wrap at 0x40 (64 bytes, 16 frames)
- Table: `AnPal_PalICZ_1` (64 bytes)
- Destination: `Normal_palette_line_3+$1C` → palette[2] colors 14-15 (1 longword = 2 colors)

**Channel 2: Conditional** (timer period 10, gated by `Events_bg+$16 != 0`)
- Counter: `Palette_cycle_counters+$02`, step +4, wrap at 0x48 (72 bytes, 18 frames)
- Table: `AnPal_PalICZ_2` (72 bytes)
- Destination: `Normal_palette_line_4+$1C` → palette[3] colors 14-15 (1 longword = 2 colors)
- **Only active when `Events_bg+$16` is non-zero** (boss/event flag). For initial implementation, check if Sonic3kLevelEventManager exposes this flag; if not, always enable the channel and add a TODO.

**Channel 3: Conditional** (timer period 8, same gate)
- Counter: `Palette_cycle_counters+$04`, step +4, wrap at 0x18 (24 bytes, 6 frames)
- Table: `AnPal_PalICZ_3` (24 bytes)
- Destination: `Normal_palette_line_4+$18` → palette[3] colors 12-13 (1 longword = 2 colors)

**Channel 4: Always runs** (no timer gate, ticks every frame that channel 1 ticks)
- Counter: `Palette_cycle_counters+$06`, step +4, wrap at 0x40 (64 bytes, 16 frames)
- Table: `AnPal_PalICZ_4` (64 bytes)
- Destination: `Normal_palette_line_3+$18` → palette[2] colors 12-13 (1 longword = 2 colors)

### Steps

- [ ] **Step 1:** Find ROM addresses for `AnPal_PalICZ_1` through `AnPal_PalICZ_4`
- [ ] **Step 2:** Add 4 ADDR/SIZE constant pairs to `Sonic3kConstants.java`
- [ ] **Step 3:** Create `IczCycle extends PaletteCycle` with 4 channels; channels 2-3 gated by a flag (default to always-on with TODO)
- [ ] **Step 4:** Add `loadIczCycles()` and register in `loadCycles()` case 0x05
- [ ] **Step 5:** Write `TestS3kIczPaletteCycling.java` — load ICZ Act 1 (zone 0x05, act 0), test palette[2] color 14 changes (channel 1, always active)
- [ ] **Step 6:** Run test and regression check
- [ ] **Step 7:** Commit: `feat(s3k): ICZ palette cycling (4 channels, 2 conditionally gated)`

---

## Task 4: LBZ — Launch Base Zone Palette Cycling

**Zone ID:** 0x06. **Acts:** Separate data tables, shared logic.

**Files:**
- Modify: `src/main/java/com/openggf/game/sonic3k/constants/Sonic3kConstants.java`
- Modify: `src/main/java/com/openggf/game/sonic3k/Sonic3kPaletteCycler.java`
- Create: `src/test/java/com/openggf/game/sonic3k/TestS3kLbzPaletteCycling.java`

### AnPal Channel Spec

**Single channel** (timer period 4), act-specific data:
- Counter0: step +6, wrap at 0x12 (18 bytes, 3 frames)
- Table Act 1: `AnPal_PalLBZ1` (18 bytes)
- Table Act 2: `AnPal_PalLBZ2` (18 bytes)
- Destination: `Normal_palette_line_3+$10` → palette[2] colors 8-10 (1 longword + 1 word = 3 colors)

**Data (AnPal_PalLBZ1):** `$08E0, $00C0, $0080, $00C0, $0080, $08E0, $0080, $08E0, $00C0`
**Data (AnPal_PalLBZ2):** `$0EEA, $0EA4, $0C62, $0EA4, $0C62, $0EEA, $0C62, $0EEA, $0EA4`

### Steps

- [ ] **Step 1:** Find ROM addresses for `AnPal_PalLBZ1` and `AnPal_PalLBZ2`
- [ ] **Step 2:** Add `ANPAL_LBZ1_ADDR`, `ANPAL_LBZ1_SIZE = 18`, `ANPAL_LBZ2_ADDR`, `ANPAL_LBZ2_SIZE = 18`
- [ ] **Step 3:** Create `LbzCycle extends PaletteCycle` — takes one data table, single channel with timer=3, counter0 step +6, wrap 0x12, writes 3 colors to palette[2] colors 8-10
- [ ] **Step 4:** Add `loadLbzCycles(reader, list, actIndex)` that picks the act-specific table, register in `loadCycles()` case 0x06
- [ ] **Step 5:** Write `TestS3kLbzPaletteCycling.java` — load LBZ Act 1 (zone 0x06, act 0), test palette[2] color 8 changes over 20 frames
- [ ] **Step 6:** Run test and regression check
- [ ] **Step 7:** Commit: `feat(s3k): LBZ palette cycling (per-act data tables)`

---

## Task 5: LRZ — Lava Reef Zone Palette Cycling

**Zone ID:** 0x09. **Acts:** Different channel counts per act (S3K version).

**Files:**
- Modify: `src/main/java/com/openggf/game/sonic3k/constants/Sonic3kConstants.java`
- Modify: `src/main/java/com/openggf/game/sonic3k/Sonic3kPaletteCycler.java`
- Create: `src/test/java/com/openggf/game/sonic3k/TestS3kLrzPaletteCycling.java`

### AnPal Channel Spec

**Shared channels (both acts):**
- **Channel A:** Timer period 16. Counter0 step +8, wrap at 0x80 (128 bytes, 16 frames). Table: `AnPal_PalLRZ12_1`. Dest: `Normal_palette_line_3+$02` → palette[2] colors 1-4 (2 longwords = 4 colors)
- **Channel B:** Always runs with channel A's tick. Counter counters+$02, step +4, wrap at 0x1C (28 bytes, 7 frames). Table: `AnPal_PalLRZ12_2`. Dest: `Normal_palette_line_4+$02` → palette[3] colors 1-2 (1 longword = 2 colors)

**Act 1 extra (S3K only):**
- **Channel C:** Timer period 8 (independent). Counter counters+$04, step +2, wrap at 0x22 (34 bytes, 17 frames). Table: `AnPal_PalLRZ1_3`. Dest: `Normal_palette_line_3+$16` → palette[2] color 11 (1 word = 1 color)

**Act 2 extra (S3K only):**
- **Channel D:** Timer period 16 (independent). Counter counters+$04, step +8, wrap at 0x100 (256 bytes, 32 frames). Table: `AnPal_PalLRZ2_3`. Dest: `Normal_palette_line_4+$16` → palette[3] colors 11-14 (2 longwords = 4 colors). Note: known ROM bug where it writes same 2 colors twice instead of 4 unique — replicate the bug.

### Steps

- [ ] **Step 1:** Find ROM addresses for `AnPal_PalLRZ12_1`, `AnPal_PalLRZ12_2`, `AnPal_PalLRZ1_3`, `AnPal_PalLRZ2_3`
- [ ] **Step 2:** Add 4 ADDR/SIZE constant pairs
- [ ] **Step 3:** Create `Lrz1Cycle` (channels A+B+C) and `Lrz2Cycle` (channels A+B+D) extending `PaletteCycle`
- [ ] **Step 4:** Add `loadLrzCycles(reader, list, actIndex)` and register in case 0x09
- [ ] **Step 5:** Write `TestS3kLrzPaletteCycling.java` — load LRZ Act 1, test palette[2] color 1 changes over 100 frames (timer period 16 means first tick at frame 16)
- [ ] **Step 6:** Run test and regression check
- [ ] **Step 7:** Commit: `feat(s3k): LRZ palette cycling (shared + per-act channels)`

---

## Task 6: BPZ — Balloon Park Zone Palette Cycling (Competition)

**Zone ID:** 0x0E. **Acts:** Shared routine.

**Files:**
- Modify: `src/main/java/com/openggf/game/sonic3k/constants/Sonic3kConstants.java`
- Modify: `src/main/java/com/openggf/game/sonic3k/Sonic3kPaletteCycler.java`
- Create: `src/test/java/com/openggf/game/sonic3k/TestS3kBpzPaletteCycling.java`

### AnPal Channel Spec

**Channel 1: Balloons** (timer period 8)
- Counter0: step +6, wrap at 0x12 (18 bytes, 3 frames)
- Table: `AnPal_PalBPZ_1` (18 bytes)
- Dest: `Normal_palette_line_3+$1A` → palette[2] colors 13-15 (3 colors)

**Data:** `$00EE, $00AE, $006C, $00AE, $006E, $00EE, $006E, $00EE, $00AE`

**Channel 2: Background** (timer period 18)
- Counter: `Palette_cycle_counters+$02`, step +6, wrap at 0x7E (126 bytes, 21 frames)
- Table: `AnPal_PalBPZ_2` (126 bytes)
- Dest: `Normal_palette_line_4+$04` → palette[3] colors 2-4 (3 colors)

### Steps

- [ ] **Step 1:** Find ROM addresses for `AnPal_PalBPZ_1` (search bytes `00EE 00AE 006C`) and `AnPal_PalBPZ_2`
- [ ] **Step 2:** Add 2 ADDR/SIZE constant pairs
- [ ] **Step 3:** Create `BpzCycle extends PaletteCycle` with 2 independent channels
- [ ] **Step 4:** Add `loadBpzCycles()` and register in case 0x0E
- [ ] **Step 5:** Write `TestS3kBpzPaletteCycling.java` — load BPZ Act 1 (zone 0x0E, act 0), test palette[2] color 13 changes
- [ ] **Step 6:** Run test and regression check
- [ ] **Step 7:** Commit: `feat(s3k): BPZ palette cycling (balloons + background)`

---

## Task 7: CGZ — Chrome Gadget Zone Palette Cycling (Competition)

**Zone ID:** 0x10. **Acts:** Shared routine.

**Files:**
- Modify: `src/main/java/com/openggf/game/sonic3k/constants/Sonic3kConstants.java`
- Modify: `src/main/java/com/openggf/game/sonic3k/Sonic3kPaletteCycler.java`
- Create: `src/test/java/com/openggf/game/sonic3k/TestS3kCgzPaletteCycling.java`

### AnPal Channel Spec

**Single channel** (timer period 10):
- Counter0: step +8, wrap at 0x50 (80 bytes, 10 frames)
- Table: `AnPal_PalCGZ` (80 bytes)
- Dest: `Normal_palette_line_3+$04` → palette[2] colors 2-5 (2 longwords = 4 colors)

**Data (AnPal_PalCGZ):**
```
$000E, $0008, $0004, $0EEE,
$000C, $0006, $0002, $0CCE,
$000A, $0004, $0000, $0AAE,
$0008, $0002, $0000, $088E,
$0006, $0000, $0000, $066E,
$0004, $0000, $0000, $044E,
$0006, $0000, $0000, $066E,
$0008, $0002, $0000, $088E,
$000A, $0004, $0002, $0AAE,
$000C, $0006, $0004, $0CCE
```

### Steps

- [ ] **Step 1:** Find ROM address for `AnPal_PalCGZ` (search bytes `000E 0008 0004 0EEE`)
- [ ] **Step 2:** Add `ANPAL_CGZ_ADDR` and `ANPAL_CGZ_SIZE = 80`
- [ ] **Step 3:** Create `CgzCycle extends PaletteCycle` — simple single channel, timer=9, counter0 step +8, wrap 0x50, writes 4 colors to palette[2] colors 2-5
- [ ] **Step 4:** Add `loadCgzCycles()` and register in case 0x10
- [ ] **Step 5:** Write `TestS3kCgzPaletteCycling.java` — load CGZ Act 1 (zone 0x10, act 0), test palette[2] color 2 changes over 60 frames
- [ ] **Step 6:** Run test and regression check
- [ ] **Step 7:** Commit: `feat(s3k): CGZ palette cycling (10-frame light animation)`

---

## Task 8: EMZ — Endless Mine Zone Palette Cycling (Competition)

**Zone ID:** 0x11. **Acts:** Shared routine.

**Files:**
- Modify: `src/main/java/com/openggf/game/sonic3k/constants/Sonic3kConstants.java`
- Modify: `src/main/java/com/openggf/game/sonic3k/Sonic3kPaletteCycler.java`
- Create: `src/test/java/com/openggf/game/sonic3k/TestS3kEmzPaletteCycling.java`

### AnPal Channel Spec

**Channel 1: Emerald glow** (timer period 8)
- Counter0: step +2, wrap at 0x3C (60 bytes, 30 frames)
- Table: `AnPal_PalEMZ_1` (60 bytes)
- Dest: `Normal_palette_line_3+$1C` → palette[2] color 14 (1 word = 1 color)

**Channel 2: Background** (timer period 32)
- Counter: `Palette_cycle_counters+$02`, step +4, wrap at 0x34 (52 bytes, 13 frames)
- Table: `AnPal_PalEMZ_2` (52 bytes)
- Dest: `Normal_palette_line_4+$12` → palette[3] colors 9-10 (1 longword = 2 colors)

### Steps

- [ ] **Step 1:** Find ROM addresses for `AnPal_PalEMZ_1` and `AnPal_PalEMZ_2`
- [ ] **Step 2:** Add 2 ADDR/SIZE constant pairs
- [ ] **Step 3:** Create `EmzCycle extends PaletteCycle` with 2 independent channels
- [ ] **Step 4:** Add `loadEmzCycles()` and register in case 0x11
- [ ] **Step 5:** Write `TestS3kEmzPaletteCycling.java` — load EMZ Act 1 (zone 0x11, act 0), test palette[2] color 14 changes over 60 frames
- [ ] **Step 6:** Run test and regression check
- [ ] **Step 7:** Commit: `feat(s3k): EMZ palette cycling (emerald glow + background)`

---

## Task 9: FBZ — Mark as Intentionally Skipped

**Zone ID:** 0x04. FBZ has no real palette cycling — only a one-bit flicker toggle (`_unkF7C1`) in the S3K version, and nothing in the S3 version. No data tables exist.

**Files:**
- Modify: `src/main/java/com/openggf/game/sonic3k/Sonic3kPaletteCycler.java`

### Steps

- [ ] **Step 1:** Update the FBZ case comment in `loadCycles()` to document why it's intentionally empty:
  ```java
  // FBZ (zone 0x04) — AnPal_FBZ: no palette table cycling.
  // S3K version toggles bit 0 of _unkF7C1 every other frame (flicker effect),
  // not a color table animation. S3 version is rts. Intentionally no-op.
  case 0x04: break;
  ```
- [ ] **Step 2:** Commit: `docs(s3k): document FBZ palette animation as intentional no-op`

---

## Task 10: Final Integration and Documentation Update

**Files:**
- Modify: `AGENTS_S3K.md` (update zone inventory status column)
- Modify: `CLAUDE.md` (add new test classes to "Keep these S3K tests green" list)

### Steps

- [ ] **Step 1:** Run all new palette cycling tests together:
  ```bash
  mvn test -Dtest="TestS3kAiz2PaletteCycling,TestS3kHczPaletteCycling,TestS3kCnzPaletteCycling,TestS3kIczPaletteCycling,TestS3kLbzPaletteCycling,TestS3kLrzPaletteCycling,TestS3kBpzPaletteCycling,TestS3kCgzPaletteCycling,TestS3kEmzPaletteCycling" -Dmse=relaxed
  ```
- [ ] **Step 2:** Run existing S3K regression tests:
  ```bash
  mvn test -Dtest="TestS3kAiz1SkipHeadless,TestSonic3kLevelLoading,TestSonic3kBootstrapResolver,TestSonic3kDecodingUtils" -Dmse=relaxed
  ```
- [ ] **Step 3:** Update `AGENTS_S3K.md` zone inventory table — change all implemented zones from "Stubbed" to "Implemented", mark FBZ as "N/A (no cycling)"
- [ ] **Step 4:** Commit: `docs(s3k): update palette animation zone inventory after full implementation`
