package com.openggf.game.sonic1.credits;

import com.openggf.control.InputHandler;
import com.openggf.camera.Camera;
import com.openggf.game.GameServices;
import com.openggf.game.GameStateManager;
import com.openggf.game.TitleScreenProvider;
import com.openggf.game.sonic1.Sonic1ObjectArt;
import com.openggf.game.sonic1.constants.Sonic1Constants;
import com.openggf.game.sonic1.titlescreen.Sonic1TitleScreenDataLoader;
import com.openggf.game.sonic1.titlescreen.Sonic1TitleScreenManager;
import com.openggf.graphics.GraphicsManager;
import com.openggf.graphics.PatternAtlasRange;
import com.openggf.level.Palette;
import com.openggf.level.Pattern;
import com.openggf.level.objects.ObjectSpriteSheet;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.level.render.SpriteMappingFrame;
import com.openggf.level.render.SpriteMappingPiece;
import com.openggf.physics.TrigLookupTable;
import com.openggf.data.Rom;
import com.openggf.data.RomByteReader;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

import com.openggf.configuration.SonicConfiguration;
import com.openggf.configuration.SonicConfigurationService;

/**
 * Manages the post-credits "TRY AGAIN" / "END" screen for Sonic 1.
 * <p>
 * From the disassembly (sonic.asm:4193-4241, TryAgainEnd):
 * <ol>
 *   <li>Load PLC_TryAgain art (Eggman + emeralds + credit text)</li>
 *   <li>Load ending palette</li>
 *   <li>Spawn Object 8B (EndEggman) - which conditionally spawns 8C and credit text</li>
 *   <li>Main loop: 1800 frame timer or START press</li>
 *   <li>Exit to Sega screen (title screen)</li>
 * </ol>
 * <p>
 * This is a self-contained screen that creates its own renderers (not part of
 * the level object pipeline).
 */
public class TryAgainEndManager {
    private static final Logger LOGGER = Logger.getLogger(TryAgainEndManager.class.getName());

    /** ROM: move.w #1800,(v_generictimer).w — 30 seconds at 60fps. */
    private static final int SCREEN_TIMER = 1800;

    private static final int TOTAL_EMERALDS = 6;

    // ---- Screen-space positions (ROM VDP values minus $80 offset) ----
    /** Eggman X: move.w #$120,obX(a0) → screen $120-$80 = $A0 (160) */
    private static final int EGGMAN_X = 0x120 - 0x80;
    /** Eggman Y: move.w #$F4,obScreenY(a0) → screen $F4-$80 = $74 (116) */
    private static final int EGGMAN_Y = 0xF4 - 0x80;
    /** Emerald center X: move.w #$120,objoff_38(a1) → screen $120-$80 = $A0 (160) */
    private static final int EMERALD_CENTER_X = 0x120 - 0x80;
    /** Emerald center Y: move.w #$EC,objoff_3A(a1) → screen $EC-$80 = $6C (108) */
    private static final int EMERALD_CENTER_Y = 0xEC - 0x80;
    /** Emerald orbit radius: move.b #$1C,objoff_3C(a1) */
    private static final int EMERALD_RADIUS = 0x1C;
    /** Initial emerald angle: move.b #$80,obAngle(a1) */
    private static final int EMERALD_INITIAL_ANGLE = 0x80;
    /** Delay increment per emerald: addi.w #10,d3 */
    private static final int EMERALD_DELAY_INCREMENT = 10;
    /** Juggle timer: move.w #112,eegg_time(a0) */
    private static final int JUGGLE_TIMER = 112;

    private static final int PALETTE_COUNT = 4;
    /** VDP palette RAM start address for destination offset calculation. */
    private static final int V_PALETTE_RAM = 0xFB00;
    /** Disassembly palette ID for Sonic (palid_Sonic = 3). */
    private static final int DISASM_SONIC_PALID = 3;

    /** Pattern base for Eggman art in the GPU pattern atlas. */
    private static final int EGGMAN_PATTERN_BASE = PatternAtlasRange.RESULTS_SCREENS.base();
    /** Pattern base for emerald art in the GPU pattern atlas. */
    private static final int EMERALD_PATTERN_BASE = PatternAtlasRange.RESULTS_SCREENS.base() + 0x1000;

    // ---- Renderers ----
    private PatternSpriteRenderer eggmanRenderer;
    private PatternSpriteRenderer emeraldRenderer;
    private Sonic1CreditsTextRenderer textRenderer;

    // ---- Eggman state (Object 8B) ----
    private int eggRoutine;
    private int eggAnimIndex;
    private int eggAnimFrameIndex;
    private int eggAnimDelay;
    private int eggFrameId;
    private int eggJuggleTimer;

    // Ani_EEgg animation data
    private static final int[][] EGG_ANIM_FRAMES = {
            {0},                                                               // anim 0: frame 0, afRoutine
            {2},                                                               // anim 1: frame 2, afRoutine
            {4, 5, 6, 5, 4, 5, 6, 5, 4, 5, 6, 5, 7, 5, 6, 5}                // anim 2: END loop
    };
    private static final int[] EGG_ANIM_DELAYS = {5, 5, 7};

    // ---- Emerald state (Object 8C) ----
    private int emeraldCount;
    private int[] emeraldFrame;
    private int[] emeraldAngle;
    private int[] emeraldDelay;
    private int[] emeraldDelayInit;
    private int[] emeraldVelocity;
    private int[] emeraldX;
    private int[] emeraldY;

    // ---- General ----
    private int timer;
    private boolean exitRequested;
    private boolean initialized;
    private boolean hasAllEmeralds;

    /**
     * Initializes the TRY AGAIN / END screen.
     */
    public void initialize() {
        exitRequested = false;
        timer = SCREEN_TIMER;

        GameStateManager gsm = GameServices.gameState();
        int collected = gsm.getEmeraldCount();
        hasAllEmeralds = collected >= TOTAL_EMERALDS;

        loadArt();

        // Initialize text renderer
        textRenderer = new Sonic1CreditsTextRenderer();
        Sonic1TitleScreenDataLoader dataLoader = resolveTitleScreenDataLoader();
        if (dataLoader != null && !dataLoader.isDataLoaded()) {
            dataLoader.loadData();
        }
        textRenderer.initialize(dataLoader);

        // Initialize Eggman (Object 8B)
        if (hasAllEmeralds) {
            eggAnimIndex = 2; // END animation
        } else {
            eggAnimIndex = 0; // TRY AGAIN animation
        }
        eggRoutine = 2; // EEgg_Animate (ROM falls through from Main)
        eggAnimFrameIndex = 0;
        eggAnimDelay = EGG_ANIM_DELAYS[eggAnimIndex];
        eggFrameId = EGG_ANIM_FRAMES[eggAnimIndex][0];

        // Initialize emeralds (Object 8C) - only if not all collected
        if (!hasAllEmeralds) {
            initEmeralds(gsm);
        }

        initialized = true;
        LOGGER.info("TryAgainEnd initialized (emeralds=" + collected + ", mode=" +
                (hasAllEmeralds ? "END" : "TRY AGAIN") + ")");
    }

    private void initEmeralds(GameStateManager gsm) {
        int uncollected = TOTAL_EMERALDS - gsm.getEmeraldCount();
        if (uncollected <= 0) {
            emeraldCount = 0;
            return;
        }
        emeraldCount = uncollected;
        emeraldFrame = new int[emeraldCount];
        emeraldAngle = new int[emeraldCount];
        emeraldDelay = new int[emeraldCount];
        emeraldDelayInit = new int[emeraldCount];
        emeraldVelocity = new int[emeraldCount];
        emeraldX = new int[emeraldCount];
        emeraldY = new int[emeraldCount];

        int idx = 0;
        int delay = 0;
        for (int id = 0; id < TOTAL_EMERALDS && idx < emeraldCount; id++) {
            if (gsm.hasEmerald(id)) {
                continue;
            }
            emeraldFrame[idx] = id + 1;
            emeraldAngle[idx] = EMERALD_INITIAL_ANGLE;
            emeraldDelay[idx] = delay;
            emeraldDelayInit[idx] = delay;
            emeraldVelocity[idx] = 0;
            computeEmeraldPos(idx);
            delay += EMERALD_DELAY_INCREMENT;
            idx++;
        }
    }

    private void loadArt() {
        try {
            Rom rom = GameServices.rom().getRom();
            RomByteReader reader = RomByteReader.fromRom(rom);
            Sonic1ObjectArt art = new Sonic1ObjectArt(rom, reader);
            GraphicsManager gm = GameServices.graphics();

            // Load ending palette (ROM: PalLoad_Fade palid_Ending)
            loadEndingPalette(rom, gm);

            // Eggman art (Nem_TryAgain)
            Pattern[] eggPatterns = art.loadNemesisPatterns(Sonic1Constants.ART_NEM_TRY_AGAIN_ADDR);
            if (eggPatterns.length > 0) {
                ObjectSpriteSheet sheet = new ObjectSpriteSheet(
                        eggPatterns, createTryAgainEggmanMappings(), 0, 1);
                eggmanRenderer = new PatternSpriteRenderer(sheet);
                for (int i = 0; i < eggPatterns.length; i++) {
                    gm.cachePatternTexture(eggPatterns[i], EGGMAN_PATTERN_BASE + i);
                }
                eggmanRenderer.ensurePatternsCached(gm, EGGMAN_PATTERN_BASE);
            }

            // Emerald art (Nem_EndEm - same as Object 88)
            Pattern[] emPatterns = art.loadNemesisPatterns(Sonic1Constants.ART_NEM_END_EMERALDS_ADDR);
            if (emPatterns.length > 0) {
                ObjectSpriteSheet sheet = new ObjectSpriteSheet(
                        emPatterns, createEndingEmeraldsMappings(), 0, 1);
                emeraldRenderer = new PatternSpriteRenderer(sheet);
                for (int i = 0; i < emPatterns.length; i++) {
                    gm.cachePatternTexture(emPatterns[i], EMERALD_PATTERN_BASE + i);
                }
                emeraldRenderer.ensurePatternsCached(gm, EMERALD_PATTERN_BASE);
            }
        } catch (Exception e) {
            LOGGER.severe("Failed to load TryAgainEnd art: " + e);
        }
    }

    /**
     * Loads the ending palette from ROM palette pointer table and uploads to GPU.
     * ROM: PalLoad_Fade palid_Ending
     * <p>
     * Reads the palette ID from the ending zone's level header (byte 15), then
     * applies the same ROM-revision adjustment as Sonic1.java. REV01 removed the
     * Level Select palette entry, shifting all IDs down by 1, but the level headers
     * still reference the OLD (disassembly) IDs.
     */
    private void loadEndingPalette(Rom rom, GraphicsManager gm) throws IOException {
        // Read the ending zone level header to get palette ID (byte 15)
        int headerAddr = Sonic1Constants.LEVEL_HEADERS_ADDR + Sonic1Constants.ZONE_ENDZ * 16;
        int headerPaletteId = rom.readByte(headerAddr + 15) & 0xFF;

        // Apply ROM revision adjustment (same as Sonic1.java):
        // The disassembly defines palid_Sonic=3, but REV01 shifted it to 2.
        // Level headers still contain old IDs, so we detect the actual Sonic
        // palette position and adjust the offset accordingly.
        int sonicPaletteId = findSonicPaletteId(rom);
        int paletteId = sonicPaletteId + (headerPaletteId - DISASM_SONIC_PALID);
        LOGGER.info("TryAgainEnd: header palette ID=" + headerPaletteId
                + " sonicId=" + sonicPaletteId + " adjusted=" + paletteId);

        int tableAddr = Sonic1Constants.PALETTE_TABLE_ADDR;
        int entryAddr = tableAddr + paletteId * 8;
        if (entryAddr + 8 > rom.getSize()) {
            LOGGER.warning("TryAgainEnd: palette entry " + paletteId + " out of ROM bounds");
            return;
        }

        int sourceAddr = rom.read32BitAddr(entryAddr) & 0x00FFFFFF;
        int destinationAddr = rom.read16BitAddr(entryAddr + 4) & 0xFFFF;
        int countWord = rom.read16BitAddr(entryAddr + 6) & 0xFFFF;
        int dataBytes = (countWord + 1) * 4;

        LOGGER.info("TryAgainEnd: palette entry " + paletteId + " source=0x"
                + Integer.toHexString(sourceAddr) + " dest=0x" + Integer.toHexString(destinationAddr)
                + " size=" + dataBytes);

        if (sourceAddr + dataBytes > rom.getSize()) {
            LOGGER.warning("TryAgainEnd: palette data out of ROM bounds");
            return;
        }

        Palette[] palettes = new Palette[PALETTE_COUNT];
        for (int i = 0; i < PALETTE_COUNT; i++) {
            palettes[i] = new Palette();
        }

        byte[] data = rom.readBytes(sourceAddr, dataBytes);
        int destinationOffset = destinationAddr - V_PALETTE_RAM;

        for (int dataOffset = 0; dataOffset + 1 < data.length; dataOffset += Palette.BYTES_PER_COLOR) {
            int paletteByteOffset = destinationOffset + dataOffset;
            if (paletteByteOffset < 0) continue;
            int paletteIndex = paletteByteOffset / Palette.PALETTE_SIZE_IN_ROM;
            if (paletteIndex < 0 || paletteIndex >= PALETTE_COUNT) continue;
            int colorIndex = (paletteByteOffset % Palette.PALETTE_SIZE_IN_ROM) / Palette.BYTES_PER_COLOR;
            if (colorIndex < 0 || colorIndex >= Palette.PALETTE_SIZE) continue;
            palettes[paletteIndex].getColor(colorIndex).fromSegaFormat(data, dataOffset);
        }

        if (gm.isGlInitialized()) {
            for (int i = 0; i < PALETTE_COUNT; i++) {
                gm.cachePaletteTexture(palettes[i], i);
            }
        }
    }

    /**
     * Finds the actual Sonic palette ID in the PalPointers table.
     * Matches Sonic1.java's findSonicPaletteId(): scans for the first entry
     * (after title/menu) targeting palette line 0 with exactly 1 palette line (32 bytes).
     * This handles REV01 where the Level Select entry was removed, shifting IDs by -1.
     */
    private int findSonicPaletteId(Rom rom) throws IOException {
        int tableAddr = Sonic1Constants.PALETTE_TABLE_ADDR;
        for (int id = 2; id < 10; id++) {
            int entryAddr = tableAddr + id * 8;
            if (entryAddr + 8 > rom.getSize()) break;
            int dest = rom.read16BitAddr(entryAddr + 4) & 0xFFFF;
            int countWord = rom.read16BitAddr(entryAddr + 6) & 0xFFFF;
            int byteCount = (countWord + 1) * 4;
            if (dest == V_PALETTE_RAM && byteCount == 32) {
                return id;
            }
        }
        return DISASM_SONIC_PALID; // fallback
    }

    /**
     * Updates the screen each frame.
     */
    public void update(InputHandler inputHandler) {
        if (!initialized) {
            return;
        }

        // Update Eggman state machine
        switch (eggRoutine) {
            case 2 -> updateEggAnimate();
            case 4 -> updateEggJuggle();
            case 6 -> updateEggWait();
            default -> { }
        }

        // Update emeralds
        for (int i = 0; i < emeraldCount; i++) {
            updateSingleEmerald(i);
        }

        // Check START press (mapped to configured jump key, matching ROM's Start button)
        int startKey = GameServices.configuration().getInt(SonicConfiguration.JUMP);
        if (inputHandler != null
                && ((startKey > 0 && inputHandler.isKeyPressed(startKey))
                        || inputHandler.logical().menuAccept())) {
            exitRequested = true;
            return;
        }

        // Timer countdown
        timer--;
        if (timer <= 0) {
            exitRequested = true;
        }
    }

    // ---- Eggman routines (Object 8B) ----

    /** Routine 2: EEgg_Animate */
    private void updateEggAnimate() {
        eggAnimDelay--;
        if (eggAnimDelay >= 0) {
            return;
        }
        eggAnimDelay = EGG_ANIM_DELAYS[eggAnimIndex];

        int[] frames = EGG_ANIM_FRAMES[eggAnimIndex];
        eggAnimFrameIndex++;

        if (eggAnimFrameIndex >= frames.length) {
            if (eggAnimIndex < 2) {
                // afRoutine: advance to EEgg_Juggle.
                // Do NOT change eggAnimIndex here — EEgg_Wait toggles it
                // via bchg #0,obAnim when the juggle timer expires.
                eggRoutine = 4;
                updateEggJuggle();
                return;
            } else {
                // afEnd: loop
                eggAnimFrameIndex = 0;
            }
        }
        eggFrameId = frames[eggAnimFrameIndex];
    }

    /** Routine 4: EEgg_Juggle */
    private void updateEggJuggle() {
        eggRoutine = 6;

        // Signal emeralds — direction from bit 0 of eggAnimIndex
        // (matches ROM: btst #0,obAnim(a0))
        int velocity = 2;
        if ((eggAnimIndex & 1) != 0) {
            velocity = -2;
        }
        for (int i = 0; i < emeraldCount; i++) {
            emeraldVelocity[i] = velocity;
            int angleAdjust = velocity << 3;
            emeraldAngle[i] = (emeraldAngle[i] + angleAdjust) & 0xFF;
        }

        eggFrameId++;
        eggJuggleTimer = JUGGLE_TIMER;
    }

    /** Routine 6: EEgg_Wait */
    private void updateEggWait() {
        eggJuggleTimer--;
        if (eggJuggleTimer < 0) {
            // bchg #0,obAnim — toggle animation index bit 0.
            // In TRY AGAIN mode this alternates between anim 0 and 1;
            // in END mode routine 6 is never reached (anim 2 loops via afEnd).
            eggAnimIndex ^= 1;
            eggRoutine = 2;
            eggAnimFrameIndex = 0;
            eggAnimDelay = EGG_ANIM_DELAYS[eggAnimIndex];
            eggFrameId = EGG_ANIM_FRAMES[eggAnimIndex][0];
        }
    }

    // ---- Emerald update (Object 8C TCha_Move) ----

    private void updateSingleEmerald(int idx) {
        if (emeraldVelocity[idx] == 0) {
            return;
        }

        if (emeraldDelay[idx] > 0) {
            emeraldDelay[idx]--;
            if (emeraldDelay[idx] > 0) {
                computeEmeraldPos(idx);
                return;
            }
        }

        emeraldAngle[idx] = (emeraldAngle[idx] + emeraldVelocity[idx]) & 0xFF;

        int angle = emeraldAngle[idx] & 0xFF;
        if (angle == 0 || angle == 0x80) {
            emeraldVelocity[idx] = 0;
            emeraldDelay[idx] = emeraldDelayInit[idx];
        }

        computeEmeraldPos(idx);
    }

    private void computeEmeraldPos(int idx) {
        int angle = emeraldAngle[idx] & 0xFF;
        int sin = TrigLookupTable.sinHex(angle);
        int cos = TrigLookupTable.cosHex(angle);
        emeraldX[idx] = EMERALD_CENTER_X + ((cos * EMERALD_RADIUS) >> 8);
        emeraldY[idx] = EMERALD_CENTER_Y + ((sin * EMERALD_RADIUS) >> 8);
    }

    // ---- Drawing ----

    /**
     * Draws all screen objects.
     */
    public void draw() {
        if (!initialized) {
            return;
        }

        // Draw "TRY AGAIN" text (has its own batch internally)
        if (!hasAllEmeralds && textRenderer != null) {
            textRenderer.draw(Sonic1CreditsMappings.FRAME_TRY_AGAIN);
        }

        // Draw emeralds and Eggman in a single batch
        GraphicsManager gm = GameServices.graphics();
        Camera camera = GameServices.camera();
        int camX = camera.getX();
        int camY = camera.getY();

        gm.beginPatternBatch();

        if (emeraldRenderer != null && emeraldRenderer.isReady()) {
            for (int i = 0; i < emeraldCount; i++) {
                emeraldRenderer.drawFrameIndex(emeraldFrame[i],
                        emeraldX[i] + camX, emeraldY[i] + camY, false, false);
            }
        }

        if (eggmanRenderer != null && eggmanRenderer.isReady()) {
            eggmanRenderer.drawFrameIndex(eggFrameId,
                    EGGMAN_X + camX, EGGMAN_Y + camY, false, false);
        }

        gm.flushPatternBatch();
    }

    /**
     * @return true if the screen should exit (START pressed or timer expired)
     */
    public boolean consumeExitRequest() {
        if (exitRequested) {
            exitRequested = false;
            return true;
        }
        return false;
    }

    // ========================================================================
    // Eggman mappings (Map_EEgg from _maps/Try Again & End Eggman.asm)
    // ========================================================================

    private List<SpriteMappingFrame> createTryAgainEggmanMappings() {
        List<SpriteMappingFrame> frames = new ArrayList<>(8);

        // Frame 0: M_EEgg_Try1
        frames.add(frame(
                piece(-0x10, -0x17, 2, 2, 0x00, false, false, 0, false),
                piece(-0x20,    -7, 4, 1, 0x04, false, false, 0, false),
                piece(    0, -0x17, 2, 1, 0x08, false, false, 0, false),
                piece(    0,  -0xF, 4, 2, 0x0A, false, false, 0, false),
                piece(-0x10,     1, 2, 3, 0x23, false, false, 0, false),
                piece(    0,     1, 2, 3, 0x23,  true, false, 0, false),
                piece(-0x14,  0x18, 2, 1, 0x29, false, false, 0, false),
                piece(    4,  0x18, 2, 1, 0x29,  true, false, 0, false)
        ));

        // Frame 1: M_EEgg_Try2
        frames.add(frame(
                piece(-0x20, -0x18, 4, 2, 0x12, false, false, 0, false),
                piece(-0x18,    -8, 3, 1, 0x1A, false, false, 0, false),
                piece(    0, -0x18, 2, 2, 0x00,  true, false, 0, false),
                piece(    0,    -8, 4, 1, 0x04,  true, false, 0, false),
                piece(-0x10,     0, 2, 3, 0x1D, false, false, 0, false),
                piece(    0,     0, 2, 3, 0x1D,  true, false, 0, false),
                piece(-0x14,  0x18, 2, 1, 0x29, false, false, 0, false),
                piece(    4,  0x18, 2, 1, 0x29,  true, false, 0, false)
        ));

        // Frame 2: M_EEgg_Try3
        frames.add(frame(
                piece(-0x10, -0x17, 2, 1, 0x08,  true, false, 0, false),
                piece(-0x20,  -0xF, 4, 2, 0x0A,  true, false, 0, false),
                piece(    0, -0x17, 2, 2, 0x00,  true, false, 0, false),
                piece(    0,    -7, 4, 1, 0x04,  true, false, 0, false),
                piece(-0x10,     1, 2, 3, 0x23, false, false, 0, false),
                piece(    0,     1, 2, 3, 0x23,  true, false, 0, false),
                piece(-0x14,  0x18, 2, 1, 0x29, false, false, 0, false),
                piece(    4,  0x18, 2, 1, 0x29,  true, false, 0, false)
        ));

        // Frame 3: M_EEgg_Try4
        frames.add(frame(
                piece(-0x10, -0x18, 2, 2, 0x00, false, false, 0, false),
                piece(-0x20,    -8, 4, 1, 0x04, false, false, 0, false),
                piece(    0, -0x18, 4, 2, 0x12,  true, false, 0, false),
                piece(    0,    -8, 3, 1, 0x1A,  true, false, 0, false),
                piece(-0x10,     0, 2, 3, 0x1D, false, false, 0, false),
                piece(    0,     0, 2, 3, 0x1D,  true, false, 0, false),
                piece(-0x14,  0x18, 2, 1, 0x29, false, false, 0, false),
                piece(    4,  0x18, 2, 1, 0x29,  true, false, 0, false)
        ));

        // Frame 4: M_EEgg_End1
        frames.add(frame(
                piece(-0x18, -0x13, 3, 3, 0x2B, false, false, 0, false),
                piece(-0x20,  -0xB, 1, 1, 0x34, false, false, 0, false),
                piece(-0x10,     5, 2, 1, 0x35, false, false, 0, false),
                piece(-0x18,   0xD, 3, 1, 0x37, false, false, 0, false),
                piece(    0, -0x13, 3, 3, 0x2B,  true, false, 0, false),
                piece( 0x18,  -0xB, 1, 1, 0x34,  true, false, 0, false),
                piece(    0,     5, 2, 1, 0x35,  true, false, 0, false),
                piece(    0,   0xD, 3, 1, 0x37,  true, false, 0, false),
                piece(-0x20,  0x10, 4, 2, 0x73, false, false, 0, false),
                piece(    0,  0x10, 4, 2, 0x7B, false, false, 0, false),
                piece(-0x20,  0x1C, 4, 1, 0x5B, false, false, 0, false),
                piece(    0,  0x1C, 4, 1, 0x5B,  true, false, 0, false)
        ));

        // Frame 5: M_EEgg_End2
        frames.add(frame(
                piece(-0x10, -0x2E, 2, 4, 0x3A, false, false, 0, false),
                piece(-0x18, -0x26, 1, 1, 0x42, false, false, 0, false),
                piece(-0x10,  -0xE, 2, 4, 0x43, false, false, 0, false),
                piece(    0, -0x2E, 2, 4, 0x3A,  true, false, 0, false),
                piece( 0x10, -0x26, 1, 1, 0x42,  true, false, 0, false),
                piece(    0,  -0xE, 2, 4, 0x43,  true, false, 0, false),
                piece(-0x18,  0x10, 4, 2, 0x67, false, false, 0, false),
                piece(    8,  0x10, 2, 2, 0x6F, false, false, 0, false),
                piece(-0x20,  0x1C, 4, 1, 0x5F, false, false, 0, false),
                piece(    0,  0x1C, 4, 1, 0x5F,  true, false, 0, false)
        ));

        // Frame 6: M_EEgg_End3
        frames.add(frame(
                piece(-0x18, -0x3C, 3, 4, 0x4B, false, false, 0, false),
                piece(-0x18, -0x1C, 3, 1, 0x57, false, false, 0, false),
                piece(-0x10, -0x14, 1, 1, 0x5A, false, false, 0, false),
                piece(    0, -0x3C, 3, 4, 0x4B,  true, false, 0, false),
                piece(    0, -0x1C, 3, 1, 0x57,  true, false, 0, false),
                piece(    8, -0x14, 1, 1, 0x5A,  true, false, 0, false),
                piece(-0x18,  0x10, 4, 2, 0x67, false, false, 0, false),
                piece(    8,  0x10, 2, 2, 0x6F, false, false, 0, false),
                piece(-0x20,  0x1C, 4, 1, 0x63, false, false, 0, false),
                piece(    0,  0x1C, 4, 1, 0x63,  true, false, 0, false)
        ));

        // Frame 7: M_EEgg_End4
        frames.add(frame(
                piece(-0x18,  -0xC, 3, 3, 0x2B, false, false, 0, false),
                piece(-0x20,    -4, 1, 1, 0x34, false, false, 0, false),
                piece(-0x10,   0xC, 2, 1, 0x35, false, false, 0, false),
                piece(-0x18,  0x14, 3, 1, 0x37, false, false, 0, false),
                piece(    0,  -0xC, 3, 3, 0x2B,  true, false, 0, false),
                piece( 0x18,    -4, 1, 1, 0x34,  true, false, 0, false),
                piece(    0,   0xC, 2, 1, 0x35,  true, false, 0, false),
                piece(    0,  0x14, 3, 1, 0x37,  true, false, 0, false),
                piece(-0x20,  0x18, 4, 1, 0x83, false, false, 0, false),
                piece(    0,  0x18, 4, 1, 0x87, false, false, 0, false),
                piece(-0x20,  0x1C, 4, 1, 0x5B, false, false, 0, false),
                piece(    0,  0x1C, 4, 1, 0x5B,  true, false, 0, false)
        ));

        return frames;
    }

    // ========================================================================
    // Emerald mappings (Map_ECha - same as Object 88 ending emeralds)
    // ========================================================================

    private List<SpriteMappingFrame> createEndingEmeraldsMappings() {
        List<SpriteMappingFrame> frames = new ArrayList<>(7);

        // Frame 0: unused placeholder (emerald indices start at 1)
        frames.add(frame(
                piece(-4, -4, 1, 1, 0, false, false, 0, false)
        ));

        // Frames 1-6: six emerald colors
        for (int i = 0; i < 6; i++) {
            frames.add(frame(
                    piece(-8, -8, 2, 2, i * 4, false, false, 0, false)
            ));
        }

        return frames;
    }

    private static SpriteMappingFrame frame(SpriteMappingPiece... pieces) {
        return new SpriteMappingFrame(List.of(pieces));
    }

    private static SpriteMappingPiece piece(int xOff, int yOff, int w, int h,
                                            int tile, boolean hFlip, boolean vFlip,
                                            int palette, boolean priority) {
        return new SpriteMappingPiece(xOff, yOff, w, h, tile, hFlip, vFlip, palette, priority);
    }

    private Sonic1TitleScreenDataLoader resolveTitleScreenDataLoader() {
        TitleScreenProvider provider = GameServices.module().getTitleScreenProvider();
        if (provider instanceof Sonic1TitleScreenManager manager) {
            return manager.getDataLoader();
        }
        Sonic1TitleScreenDataLoader fallback = new Sonic1TitleScreenDataLoader();
        fallback.loadData();
        return fallback;
    }
}
