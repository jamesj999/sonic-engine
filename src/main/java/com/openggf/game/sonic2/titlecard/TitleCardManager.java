package com.openggf.game.sonic2.titlecard;

import com.openggf.game.sonic2.constants.Sonic2Constants;
import com.openggf.game.sonic2.resources.Sonic2PlcService;
import com.openggf.game.sonic2.resources.Sonic2RuntimePlcPublisher;
import com.openggf.game.sonic2.Sonic2ObjectArtProvider;
import com.openggf.game.titlecard.TitleCardElement;
import com.openggf.game.titlecard.TitleCardMappings;
import com.openggf.game.GameServices;
import com.openggf.game.rewind.RewindSnapshottable;
import com.openggf.game.session.SessionManager;

import com.openggf.data.Rom;
import com.openggf.data.RomManager;
import com.openggf.game.TitleCardProvider;
import com.openggf.graphics.GLCommand;
import com.openggf.graphics.GraphicsManager;
import com.openggf.graphics.PatternAtlasRange;
import com.openggf.graphics.TitleCardSpriteRenderer;
import com.openggf.level.Pattern;
import com.openggf.util.PatternDecompressor;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Manages the Sonic 2 title card display.
 *
 * <p>The title card appears:
 * <ul>
 *   <li>When a level first loads</li>
 *   <li>After the player loses a life and respawns</li>
 *   <li>When returning from a special stage</li>
 * </ul>
 *
 * <p>State machine:
 * <pre>
 * SLIDE_IN → DISPLAY → SLIDE_OUT → COMPLETE
 * </pre>
 */
public class TitleCardManager implements TitleCardProvider,
        RewindSnapshottable<TitleCardManager.Snapshot> {
    private static final Logger LOGGER = Logger.getLogger(TitleCardManager.class.getName());
    public static final String REWIND_KEY = "s2-title-card";

    private static TitleCardManager instance;

    /*
     * The leave sequence's length is fixed by the Obj34 leave routines, not by
     * how far this engine's overlay elements happen to have travelled. ROM
     * order (docs/s2disasm/s2.asm:4913-5066):
     *
     *   Level_TtlCard's scroll-in wait loop (:4914-4925) runs before
     *   InitPlayers (:4945), so the players do not exist for it at all.
     *   :5003-5006 then run ObjectsManager / RingsManager /
     *   SpecialCNZBumpers / RunObjects once -- one player object pass with no
     *   WaitForVint of its own. :5056-5058 arm the leave flags
     *   (TitleCard_ZoneName titlecard_leaveflag = -1, TitleCard_Left routine
     *   $E, titlecard_location $A) and :5060-5066 loop WaitForVint /
     *   RunObjects / BuildSprites / RunPLC_RAM until TitleCard_Background is
     *   unloaded.
     *
     * That loop is exactly 25 iterations:
     *   Obj34_LeftPartOut (:27518-27540) steps titlecard_location
     *   $A -> 6 -> 2 -> 0 (the -2 clamp) -> -4 and, on the pass that reads a
     *   negative location, hands TitleCard_Bottom routine $10 and deletes
     *   itself: 5 passes.
     *   Obj34_BottomPartOut (:27542-27551) starts at location 0 and adds 4
     *   per pass, deleting itself and handing TitleCard_Background routine
     *   $12 on the pass that reads $28: 11 passes.
     *   Obj34_BackgroundOutInit/Out (:27587-27604) sets location $F0 and
     *   subtracts $20 per pass, deleting itself on the pass that computes
     *   -$30: 9 passes.
     *
     * So the players run 1 + 25 = 26 object passes between InitPlayers and
     * Level_MainLoop (:5087).
     */
    private static final int LEAVE_PRELOOP_PASSES = 1;
    private static final int LEAVE_LEFT_PASSES = 5;
    private static final int LEAVE_BOTTOM_PASSES = 11;
    private static final int LEAVE_BACKGROUND_PASSES = 9;
    private static final int LEAVE_LOOP_PASSES =
            LEAVE_LEFT_PASSES + LEAVE_BOTTOM_PASSES + LEAVE_BACKGROUND_PASSES;
    private static final int LEAVE_PLAYABLE_PASSES =
            LEAVE_PRELOOP_PASSES + LEAVE_LOOP_PASSES;

    /**
     * Text wait duration in frames before sliding out.
     * From disassembly: $2D = 45 frames (lines 5066-5072 in s2.asm).
     * This wait starts AFTER the background has fully exited.
     */
    private static final int TEXT_WAIT_DURATION = 0x2D;  // 45 frames

    /**
     * X position the zone-name piece holds while the locked title-card loop is
     * still running: {@code spriteScreenPositionXCentered(0)} = 128 + 320/2.
     * docs/s2disasm/s2.asm:27369, docs/s2disasm/s2.macros.asm:276-280
     */
    private static final int EXIT_TAIL_ZONE_NAME_X_TARGET = 0x120;

    /**
     * X position the zone-name piece returns to on the way out:
     * {@code spriteScreenPositionX(screen_width+128)} = 128 + 320 + 128.
     * docs/s2disasm/s2.asm:27369
     */
    private static final int EXIT_TAIL_ZONE_NAME_X_SOURCE = 0x240;

    /** Obj34_WaitAndGoAway slide speed: {@code moveq #$20,d0}. s2.asm:27615 */
    private static final int EXIT_TAIL_SLIDE_SPEED = 0x20;

    /**
     * Far-off-screen cut-off: Obj34_WaitAndGoAway stops trying to display a
     * piece past {@code #$200} and goes straight to the art load + delete.
     * docs/s2disasm/s2.asm:27622-27625
     */
    private static final int EXIT_TAIL_OFFSCREEN_LIMIT = 0x200;

    /** Native game width (320-pixel frame everything is authored for). */
    private static final int SCREEN_WIDTH = 320;
    private static final int SCREEN_HEIGHT = 224;

    /**
     * Returns the configured viewport width in game pixels.
     * At native 320 equals SCREEN_WIDTH exactly (xOffset == 0 - byte-identical).
     */
    private int viewportWidth() {
        try {
            int w = GameServices.graphics().getProjectionWidth();
            return w > 0 ? w : SCREEN_WIDTH;
        } catch (Exception ignored) {
            return SCREEN_WIDTH;
        }
    }

    /**
     * Horizontal offset to centre the 320-wide title-card composition in the
     * configured viewport. Zero at native 320 - byte-identical.
     */
    private int xOffset() {
        return (viewportWidth() - SCREEN_WIDTH) / 2;
    }

    /** Pattern base ID for title card art (high ID to avoid conflicts) */
    private static final int PATTERN_BASE = PatternAtlasRange.TITLE_CARDS.base();

    /**
     * VRAM layout for title card art:
     * - $580-$58F: E, N, O, Z letters (first 16 tiles of ArtNem_TitleCard)
     * - $590-$5AF: Act numbers and misc (next ~32 tiles)
     * - $5B0-$5D7: "SONIC THE HEDGEHOG" bar (~40 tiles)
     * - $5D4-$5D7: Red stripes (~4 tiles, overlaps with above)
     * - $5DE-$63F: Zone name letters from ArtNem_TitleCard2 (~98 tiles)
     *
     * Total span: $580 to ~$640 = ~192 tiles
     */
    private static final int VRAM_BASE = 0x580;
    private static final int VRAM_END = 0x700;  // Conservative upper bound
    private static final int VRAM_TITLE_CARD2_START = 0x5DE;  // Where TitleCard2 letters start

    /**
     * Charset lookup for title card letters.
     * Maps letter (A-Z) to tile offset in ArtNem_TitleCard2.
     * E, N, O, Z are -1 because they exist in ArtNem_TitleCard (base).
     *
     * From s2.asm charset definitions:
     * charset 'A',0
     * charset 'B',"\4\8\xC\4\x10\x14\x18\x1C\x1E\x22\x26\x2A\4\4\x30\x34\x38\x3C\x40\x44\x48\x4C\x52\x56\4"
     */
    private static final int[] LETTER_OFFSETS = {
            0,    // A = 0x00
            4,    // B = 0x04
            8,    // C = 0x08
            12,   // D = 0x0C
            -1,   // E = in ArtNem_TitleCard (skip)
            16,   // F = 0x10
            20,   // G = 0x14
            24,   // H = 0x18
            28,   // I = 0x1C
            30,   // J = 0x1E
            34,   // K = 0x22
            38,   // L = 0x26
            42,   // M = 0x2A
            -1,   // N = in ArtNem_TitleCard (skip)
            -1,   // O = in ArtNem_TitleCard (skip)
            48,   // P = 0x30
            52,   // Q = 0x34
            56,   // R = 0x38
            60,   // S = 0x3C
            64,   // T = 0x40
            68,   // U = 0x44
            72,   // V = 0x48
            76,   // W = 0x4C
            82,   // X = 0x52
            86,   // Y = 0x56
            -1    // Z = in ArtNem_TitleCard (skip)
    };

    /**
     * Charset lookup for letter sizes (tile count).
     * From s2.asm: charset 'a',"\4\4\4\4\4\4\4\4\2\4\4\4\6\4\4\4\4\4\4\4\4\4\6\4\4"
     */
    private static final int[] LETTER_SIZES = {
            4,    // A
            4,    // B
            4,    // C
            4,    // D
            4,    // E
            4,    // F
            4,    // G
            4,    // H
            2,    // I (narrow)
            4,    // J
            4,    // K
            4,    // L
            6,    // M (wide)
            4,    // N
            4,    // O
            4,    // P
            4,    // Q
            4,    // R
            4,    // S
            4,    // T
            4,    // U
            4,    // V
            6,    // W (wide)
            4,    // X
            4,    // Y
            4     // Z
    };

    /**
     * Zone names for title card letter loading.
     * These match the internal zone order (0-10).
     */
    private static final String[] ZONE_NAMES = {
            "EMERALD HILL",     // 0 - EHZ
            "CHEMICAL PLANT",   // 1 - CPZ
            "AQUATIC RUIN",     // 2 - ARZ
            "CASINO NIGHT",     // 3 - CNZ
            "HILL TOP",         // 4 - HTZ
            "MYSTIC CAVE",      // 5 - MCZ
            "OIL OCEAN",        // 6 - OOZ
            "METROPOLIS",       // 7 - MTZ
            "SKY CHASE",        // 8 - SCZ
            "WING FORTRESS",    // 9 - WFZ
            "DEATH EGG"         // 10 - DEZ
    };

    // Current state
    private TitleCardState state = TitleCardState.COMPLETE;
    private int stateTimer = 0;
    private int frameCounter = 0;

    /**
     * LoadZoneTiles DMA chunk size: the {@code $1000}-byte granule its
     * {@code rol.w #4,d7 / andi.w #$F,d7} chunk-count extraction is defined against
     * (docs/s2disasm/s2.asm:6485-6486).
     */
    private static final int ZONE_TILE_DMA_CHUNK_BYTES = 0x1000;

    /** Remaining LoadZoneTiles {@code dbf} iterations, one VBlank each (s2.asm:6519). */
    private int zoneTileUploadFramesLeft = 0;

    /**
     * Flag to delay TEXT_EXIT -> COMPLETE transition by one frame.
     * This ensures elements are drawn at their final positions before
     * the state transitions, matching the original game's behavior where
     * DisplaySprite is called before DeleteObject.
     */
    private boolean textExitTransitionPending = false;

    // Current zone/act
    private int currentZone = 0;
    private int currentAct = 0;

    // Elements
    private final List<TitleCardElement> elements = new ArrayList<>();
    private TitleCardElement zoneNameElement;
    private TitleCardElement zoneTextElement;
    private TitleCardElement actNumberElement;
    private TitleCardElement bottomBarElement;
    private TitleCardElement leftSwooshElement;
    private TitleCardElement blueBackgroundElement;

    // Unified VRAM-aligned pattern array
    // Index = VRAM address - VRAM_BASE
    private Pattern[] combinedPatterns;

    // Raw ArtNem_TitleCard2 patterns (full alphabet, stays in "RAM")
    private Pattern[] titleCard2RawPatterns;

    // Base ArtNem_TitleCard patterns
    private Pattern[] titleCardBasePatterns;

    private boolean artLoaded = false;
    private boolean artCached = false;
    private boolean exitPlcsQueued;
    private int lastLoadedZone = -1;  // Track which zone's letters we've loaded

    /**
     * Object passes dispatched since {@code Level:} armed the title-card leave
     * flags, or {@code 0} while the card is still in its pre-{@code InitPlayers}
     * phases. Pass 1 is the {@code jsr (RunObjects).l} at
     * docs/s2disasm/s2.asm:5006; passes 2..26 are the 25 iterations of the
     * leave loop at docs/s2disasm/s2.asm:5060-5066.
     */
    private int leavePass;

    /** Whether the gameplay-phase Obj34_WaitAndGoAway tail is running. */
    private boolean exitTailActive;
    /** anim_frame_duration of the zone-name piece during that tail. */
    private int exitTailWaitFrames;
    /** x_pixel of the zone-name piece during that tail. */
    private int exitTailZoneNameX;

    /** Immutable live-title state used by the gameplay rewind registry. */
    public record Snapshot(
            TitleCardState state,
            int stateTimer,
            int frameCounter,
            int currentZone,
            int currentAct,
            int zoneTileUploadFramesLeft,
            boolean textExitTransitionPending,
            boolean artLoaded,
            boolean artCached,
            boolean exitPlcsQueued,
            int lastLoadedZone,
            int leavePass,
            boolean exitTailActive,
            int exitTailWaitFrames,
            int exitTailZoneNameX,
            Pattern[] combinedPatterns,
            Pattern[] titleCard2RawPatterns,
            Pattern[] titleCardBasePatterns,
            TitleCardElement.Snapshot[] elements) {
        public Snapshot {
            Objects.requireNonNull(state, "state");
            combinedPatterns = copy(combinedPatterns);
            titleCard2RawPatterns = copy(titleCard2RawPatterns);
            titleCardBasePatterns = copy(titleCardBasePatterns);
            elements = elements == null ? new TitleCardElement.Snapshot[0] : elements.clone();
        }

        @Override
        public Pattern[] combinedPatterns() {
            return copy(combinedPatterns);
        }

        @Override
        public Pattern[] titleCard2RawPatterns() {
            return copy(titleCard2RawPatterns);
        }

        @Override
        public Pattern[] titleCardBasePatterns() {
            return copy(titleCardBasePatterns);
        }

        @Override
        public TitleCardElement.Snapshot[] elements() {
            return elements.clone();
        }

        private static Pattern[] copy(Pattern[] source) {
            return source == null ? null : source.clone();
        }
    }

    public TitleCardManager() {}

    @Override
    public String key() {
        return REWIND_KEY;
    }

    @Override
    public Snapshot capture() {
        TitleCardElement.Snapshot[] elementSnapshots = elements.stream()
                .map(TitleCardElement::capture)
                .toArray(TitleCardElement.Snapshot[]::new);
        return new Snapshot(
                state, stateTimer, frameCounter, currentZone, currentAct,
                zoneTileUploadFramesLeft, textExitTransitionPending,
                artLoaded, artCached, exitPlcsQueued, lastLoadedZone,
                leavePass, exitTailActive, exitTailWaitFrames, exitTailZoneNameX,
                combinedPatterns, titleCard2RawPatterns, titleCardBasePatterns,
                elementSnapshots);
    }

    @Override
    public void restore(Snapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        state = snapshot.state();
        stateTimer = snapshot.stateTimer();
        frameCounter = snapshot.frameCounter();
        currentZone = snapshot.currentZone();
        currentAct = snapshot.currentAct();
        zoneTileUploadFramesLeft = snapshot.zoneTileUploadFramesLeft();
        textExitTransitionPending = snapshot.textExitTransitionPending();
        artLoaded = snapshot.artLoaded();
        artCached = snapshot.artCached();
        exitPlcsQueued = snapshot.exitPlcsQueued();
        lastLoadedZone = snapshot.lastLoadedZone();
        leavePass = snapshot.leavePass();
        exitTailActive = snapshot.exitTailActive();
        exitTailWaitFrames = snapshot.exitTailWaitFrames();
        exitTailZoneNameX = snapshot.exitTailZoneNameX();
        combinedPatterns = snapshot.combinedPatterns();
        titleCard2RawPatterns = snapshot.titleCard2RawPatterns();
        titleCardBasePatterns = snapshot.titleCardBasePatterns();

        TitleCardElement.Snapshot[] elementSnapshots = snapshot.elements();
        if (elementSnapshots.length == 0) {
            elements.clear();
            zoneNameElement = null;
            zoneTextElement = null;
            actNumberElement = null;
            bottomBarElement = null;
            leftSwooshElement = null;
            blueBackgroundElement = null;
            return;
        }

        createElements();
        if (elementSnapshots.length != elements.size()) {
            throw new IllegalStateException(
                    "S2 title-card rewind element count mismatch: expected "
                            + elements.size() + ", actual " + elementSnapshots.length);
        }
        for (int i = 0; i < elementSnapshots.length; i++) {
            elements.get(i).restore(elementSnapshots[i]);
        }
    }

    @Override
    public void resetForMissingSnapshot() {
        reset();
    }

    public static synchronized TitleCardManager getInstance() {
        if (instance == null) {
            instance = new TitleCardManager();
        }
        return instance;
    }

    /**
     * Initializes the title card for a zone/act.
     *
     * @param zoneIndex Zone index (0-10)
     * @param actIndex  Act index (0-2)
     */
    public void initialize(int zoneIndex, int actIndex) {
        this.currentZone = zoneIndex;
        this.currentAct = actIndex;
        this.exitPlcsQueued = false;
        this.exitTailActive = false;
        this.leavePass = 0;
        this.state = TitleCardState.SLIDE_IN;
        this.stateTimer = 0;
        this.frameCounter = 0;
        this.zoneTileUploadFramesLeft = 0;
        this.textExitTransitionPending = false;

        // Load base art if needed
        if (!artLoaded) {
            loadArt();
        }

        // Load zone-specific letters if zone changed
        if (lastLoadedZone != zoneIndex) {
            loadZoneLetters(zoneIndex);
            lastLoadedZone = zoneIndex;
            // Force GPU cache refresh when letters change
            artCached = false;
        }

        // Reset art cache to ensure fresh GPU upload
        artCached = false;

        // Create elements for this zone/act
        createElements();

        LOGGER.info("Title card initialized for zone " + zoneIndex + " act " + actIndex);
    }

    /**
     * Creates the animated elements for the current zone/act.
     */
    private void createElements() {
        elements.clear();

        // Create all elements
        zoneNameElement = TitleCardElement.createZoneName(currentZone);
        zoneTextElement = TitleCardElement.createZoneText();
        bottomBarElement = TitleCardElement.createBottomBar();
        leftSwooshElement = TitleCardElement.createLeftSwoosh();
        blueBackgroundElement = TitleCardElement.createBlueBackground();

        elements.add(zoneNameElement);
        elements.add(zoneTextElement);
        elements.add(bottomBarElement);
        elements.add(leftSwooshElement);
        elements.add(blueBackgroundElement);  // Blue background animates with other elements

        // Only add act number for multi-act zones
        if (!TitleCardMappings.isSingleActZone(currentZone)) {
            actNumberElement = TitleCardElement.createActNumber(currentAct);
            elements.add(actNumberElement);
        } else {
            actNumberElement = null;
        }

        // Extend each element's off-screen entry/exit endpoint so the composition
        // slides fully on/off a wider-than-320 viewport. Zero at native 320 -
        // byte-identical. Without this, the centred (xOffset-shifted) elements and
        // the red/yellow blocks that follow them never clear the side bands.
        int edgeMargin = Math.max(0, viewportWidth() - SCREEN_WIDTH);
        for (TitleCardElement element : elements) {
            element.setEdgeMargin(edgeMargin);
        }
    }

    /**
     * Loads title card art from ROM.
     *
     * The title card uses art from two Nemesis-compressed sources:
     * - ArtNem_TitleCard: E, N, O, Z letters, numbers, "SONIC THE HEDGEHOG" bar, stripes
     * - ArtNem_TitleCard2: Full alphabet for zone names (kept in "RAM" for letter lookup)
     */
    private void loadArt() {
        try {
            RomManager romManager = GameServices.rom();
            if (!romManager.isRomAvailable()) {
                LOGGER.warning("ROM not available for title card art loading");
                return;
            }
            Rom rom = romManager.getRom();

            // Load ArtNem_TitleCard - base title card art (E,N,O,Z, numbers, bar, stripes)
            titleCardBasePatterns = PatternDecompressor.nemesis(rom,
                    Sonic2Constants.ART_NEM_TITLE_CARD_ADDR, "TitleCard");
            LOGGER.info("Loaded " + (titleCardBasePatterns != null ? titleCardBasePatterns.length : 0) +
                    " title card base patterns");

            // Load ArtNem_TitleCard2 - zone name alphabet (keep in "RAM")
            titleCard2RawPatterns = PatternDecompressor.nemesis(rom,
                    Sonic2Constants.ART_NEM_TITLE_CARD2_ADDR, "TitleCard2");
            LOGGER.info("Loaded " + (titleCard2RawPatterns != null ? titleCard2RawPatterns.length : 0) +
                    " title card 2 alphabet patterns");

            // Null safety
            if (titleCardBasePatterns == null) titleCardBasePatterns = new Pattern[0];
            if (titleCard2RawPatterns == null) titleCard2RawPatterns = new Pattern[0];

            // Create unified VRAM-aligned pattern array
            // Array index = VRAM address - VRAM_BASE
            int totalSize = VRAM_END - VRAM_BASE;
            combinedPatterns = new Pattern[totalSize];
            Pattern emptyPattern = new Pattern();
            Arrays.fill(combinedPatterns, emptyPattern);

            // Copy ArtNem_TitleCard to VRAM $580 (index 0)
            // This contains E, N, O, Z letters, act numbers, "SONIC THE HEDGEHOG" bar, red stripes
            for (int i = 0; i < titleCardBasePatterns.length && i < totalSize; i++) {
                if (titleCardBasePatterns[i] != null) {
                    combinedPatterns[i] = titleCardBasePatterns[i];
                }
            }
            LOGGER.info("Copied " + titleCardBasePatterns.length + " TitleCard patterns to VRAM $580+");

            // Note: Zone-specific letters will be copied in loadZoneLetters()
            // when initialize() is called with a zone index

            artLoaded = true;

        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Failed to load title card art", e);
            artLoaded = false;
        }
    }

    /**
     * Loads zone-specific letters from ArtNem_TitleCard2 into VRAM.
     *
     * The original game copies only the letters needed for each zone name
     * from ArtNem_TitleCard2 (in RAM) to VRAM starting at $5DE.
     * Letters E, N, O, Z are skipped because they already exist in ArtNem_TitleCard.
     *
     * @param zoneIndex The zone index (0-10)
     */
    private void loadZoneLetters(int zoneIndex) {
        if (titleCard2RawPatterns == null || combinedPatterns == null) {
            LOGGER.warning("Cannot load zone letters - art not loaded");
            return;
        }

        String zoneName = (zoneIndex >= 0 && zoneIndex < ZONE_NAMES.length)
                ? ZONE_NAMES[zoneIndex] : ZONE_NAMES[0];

        // Track which letters we've already copied (like the original's 'used' bitmap)
        boolean[] usedLetters = new boolean[26];
        // E, N, O, Z are pre-marked as "used" since they're in ArtNem_TitleCard
        usedLetters['E' - 'A'] = true;
        usedLetters['N' - 'A'] = true;
        usedLetters['O' - 'A'] = true;
        usedLetters['Z' - 'A'] = true;

        // VRAM destination starts at $5DE
        int vramDest = VRAM_TITLE_CARD2_START - VRAM_BASE;

        LOGGER.info("Loading letters for zone: " + zoneName);

        // Process each character in the zone name
        for (char c : zoneName.toCharArray()) {
            if (c == ' ') continue;  // Skip spaces
            if (c < 'A' || c > 'Z') continue;  // Skip non-letters

            int letterIndex = c - 'A';

            // Skip if we've already copied this letter
            if (usedLetters[letterIndex]) continue;
            usedLetters[letterIndex] = true;

            // Get source offset and size from charset tables
            int srcOffset = LETTER_OFFSETS[letterIndex];
            int tileCount = LETTER_SIZES[letterIndex];

            // Letters in ArtNem_TitleCard have -1 offset (shouldn't happen due to usedLetters check)
            if (srcOffset < 0) continue;

            // Copy tiles from "RAM" (titleCard2RawPatterns) to "VRAM" (combinedPatterns)
            for (int i = 0; i < tileCount; i++) {
                int srcIndex = srcOffset + i;
                int destIndex = vramDest + i;

                if (srcIndex < titleCard2RawPatterns.length &&
                    destIndex < combinedPatterns.length &&
                    titleCard2RawPatterns[srcIndex] != null) {
                    combinedPatterns[destIndex] = titleCard2RawPatterns[srcIndex];
                }
            }

            LOGGER.fine("Copied letter " + c + " (" + tileCount + " tiles) from offset " +
                       srcOffset + " to VRAM $" + Integer.toHexString(VRAM_BASE + vramDest));

            // Advance VRAM destination
            vramDest += tileCount;
        }

        LOGGER.info("Loaded " + (vramDest - (VRAM_TITLE_CARD2_START - VRAM_BASE)) +
                   " tiles for zone " + zoneName);
    }


    /**
     * Ensures art is cached to GPU.
     */
    private void ensureArtCached() {
        if (artCached || !artLoaded || combinedPatterns == null) {
            return;
        }

        GraphicsManager graphicsManager = GameServices.graphics();
        if (graphicsManager == null) {
            return;
        }

        // Cache all patterns in the VRAM-aligned array
        int cachedCount = 0;
        for (int i = 0; i < combinedPatterns.length; i++) {
            if (combinedPatterns[i] != null) {
                graphicsManager.cachePatternTexture(combinedPatterns[i], PATTERN_BASE + i);
                cachedCount++;
            }
        }
        LOGGER.info("Cached " + cachedCount + " title card patterns to GPU");

        artCached = true;
    }

    /**
     * Updates the title card state machine.
     * Call this once per frame while in TITLE_CARD mode.
     */
    public void update() {
        frameCounter++;
        stateTimer++;

        if (exitTailActive) {
            updateOmittedPresentationExitTail();
            return;
        }

        if (leavePass > 0) {
            leavePass++;
            if (leavePass > LEAVE_PLAYABLE_PASSES) {
                // The leave loop's exit condition has been met, so Level:
                // falls through to :5068-5080 and Level_StartGame.
                enterTextWait();
                return;
            }
        }

        switch (state) {
            case SLIDE_IN -> updateSlideIn();
            case DISPLAY -> updateDisplay();
            case ZONE_TILE_UPLOAD -> updateZoneTileUpload();
            case EXIT_LEFT_SWOOSH -> updateExitLeftSwoosh();
            case EXIT_BOTTOM_BAR -> updateExitBottomBar();
            case EXIT_BACKGROUND -> updateExitBackground();
            // The overlay states below animate the pieces for display; the ROM
            // routine that owns when their art loads fire runs alongside them.
            case TEXT_WAIT -> {
                if (advanceZoneNamePieceTail()) {
                    queueExitPlcs();
                }
                updateTextWait();
            }
            case TEXT_EXIT -> {
                if (advanceZoneNamePieceTail()) {
                    queueExitPlcs();
                }
                updateTextExit();
            }
            case COMPLETE -> {}
        }
    }

    private void updateSlideIn() {
        // Update all elements
        for (TitleCardElement element : elements) {
            element.updateSlideIn();
        }

        // Level_TtlCard's re-loop test names one piece and one piece only:
        // it reads (TitleCard_ZoneName+x_pos) and compares it against that
        // object's titlecard_x_target (docs/s2disasm/s2.asm:4920-4921). The
        // other pieces keep moving under RunObjects while the loop spins, and
        // the loop never waits on them. Testing every element instead held the
        // card for the LAST piece to arrive: "ZONE" and the act number carry
        // anim_frame_duration $1C against the zone name's $1B
        // (Obj34_TitleCardData, :27369-27371), so they land one iteration
        // later than the piece the ROM actually watches.
        //
        // The zone name lands on the same iteration in both models, but by two
        // offsetting routes worth naming. Obj34_Wait skips the move while the
        // post-decrement duration is non-zero and moves on the pass that takes
        // it to zero (:27380-27387), i.e. $1B gives 26 skips, not 27; against
        // that, Obj34_Init writes the zone-name child over the parent's own
        // slot (:27328-27346), so RunObjects has already passed it and it
        // misses the creation pass. 26 skips from the second iteration and the
        // engine's 27 from the first both put the eighteenth and last 16px
        // step (Obj34_MoveTowardsTargetPosition, :27493-27499) on iteration 45.
        if (zoneNameElement != null && zoneNameElement.isAtTarget()) {
            // Both halves of the re-loop test live in the SAME iteration: the
            // x_pos compare falls through to tst.l (Plc_Buffer).w and only a
            // failure of either branches back to Level_TtlCard (:4919-4924).
            // So an already-drained queue leaves the loop on the very
            // iteration the zone name arrives -- entering DISPLAY to discover
            // that on the next iteration spends a frame the ROM does not.
            // DISPLAY still owns the case the ROM spends real iterations on:
            // the piece is parked at its target while the cue drains.
            if (!plcQueueBusy()) {
                enterZoneTileUpload();
                return;
            }
            state = TitleCardState.DISPLAY;
            stateTimer = 0;
            LOGGER.fine("Title card entered DISPLAY state at frame " + frameCounter);
        }
    }

    /**
     * Second half of Level_TtlCard's re-loop test (docs/s2disasm/s2.asm:
     * 4923-4924): {@code tst.l (Plc_Buffer).w / bne.s Level_TtlCard} keeps the
     * card locked until the queued pattern load cue has fully drained. Each
     * iteration of that loop runs one {@code VintID_TitleCard} VBlank, whose
     * {@code Vint_TitleCard} tail is {@code bra.w ProcessDPLC}
     * (docs/s2disasm/s2.asm:1071), and {@code ProcessDPLC} decompresses exactly
     * six patterns per VBlank (docs/s2disasm/s2.asm:2202-2213,
     * {@code move.w #6,(Plc_FramePatternsLeft).w}). The card's hold is
     * therefore outstanding patterns divided by six per frame -- a quantity the
     * PLC queue already tracks -- not a wall-clock constant. Asking the queue
     * whether it is still busy models the ROM's own test directly and at the
     * ROM's own rate; the prior {@code DISPLAY_HOLD_DURATION = 60} stand-in for
     * "hardware decompression time" was a fitted number with no ROM source.
     */
    private void updateDisplay() {
        if (!plcQueueBusy()) {
            enterZoneTileUpload();
        }
    }

    /**
     * Leaves {@code Level_TtlCard} the way the ROM does: straight into
     * {@code bsr.w LoadZoneTiles} (docs/s2disasm/s2.asm:4938), ahead of any of
     * the leave sequence.
     */
    private void enterZoneTileUpload() {
        state = TitleCardState.ZONE_TILE_UPLOAD;
        stateTimer = 0;
        zoneTileUploadFramesLeft = zoneTileUploadVBlanks();
        LOGGER.fine("Title card entered ZONE_TILE_UPLOAD state at frame " + frameCounter
                + " for " + zoneTileUploadFramesLeft + " VBlank(s)");
        if (zoneTileUploadFramesLeft <= 0) {
            enterLeftSwooshExit();
        }
    }

    /**
     * VBlanks LoadZoneTiles spends uploading the zone's 8x8 art
     * (docs/s2disasm/s2.asm:6471-6524).
     *
     * <p>The routine Kosinski-decodes the {@code LevelArtPointers} 8x8 stream into
     * Chunk_Table and takes the resulting write pointer as the byte size
     * ({@code move.w a1,d3}, :6483). It then extracts the DMA chunk count with
     * {@code rol.w #4,d7 / andi.w #$F,d7} (:6485-6486) -- bits 15-12 of that size, which
     * is {@code size / $1000} -- and runs a {@code dbf d7,-} loop (:6506-6523) whose body
     * contains {@code bsr.w WaitForVint} (:6519). {@code dbf} executes the body
     * {@code d7 + 1} times, so the cost is {@code size / $1000 + 1} VBlanks: one per
     * {@code $1000}-byte DMA chunk plus one for the leading partial chunk.
     *
     * <p>Every term here is the ROM's. The size is the engine's own decompressed art
     * length ({@link Sonic2Level#getZoneTileArtByteSize()}), decoded from the same stream
     * at the same address, so the resulting per-zone frame counts are a consequence of the
     * zone's art rather than a table.
     */
    private int zoneTileUploadVBlanks() {
        if (!(GameServices.levelOrNull() instanceof com.openggf.level.LevelManager lm)) {
            return 0;
        }
        if (!(lm.getCurrentLevel() instanceof com.openggf.game.sonic2.Sonic2Level level)) {
            return 0;
        }
        int byteSize = level.getZoneTileArtByteSize();
        if (byteSize <= 0) {
            return 0;
        }
        return (byteSize / ZONE_TILE_DMA_CHUNK_BYTES) + 1;
    }

    /** LoadZoneTiles' zone-art upload hold, one VBlank per DMA chunk. */
    private void updateZoneTileUpload() {
        if (zoneTileUploadFramesLeft > 0) {
            zoneTileUploadFramesLeft--;
        }
        if (zoneTileUploadFramesLeft <= 0) {
            enterLeftSwooshExit();
        }
    }

    private void enterLeftSwooshExit() {
        state = TitleCardState.EXIT_LEFT_SWOOSH;
        stateTimer = 0;
        // This frame is the s2.asm:5003-5006 pass: InitPlayers has run and
        // RunObjects dispatches the players once, but the leave flags at
        // :5056-5058 are only armed after it, so no leave piece moves yet.
        leavePass = LEAVE_PRELOOP_PASSES;
        // Initialize left swoosh exit - from disassembly line 5054:
        // move.w #$A,(TitleCard_Left+titlecard_location).w
        if (leftSwooshElement != null) {
            leftSwooshElement.startExit();
        }
        LOGGER.fine("Title card entered EXIT_LEFT_SWOOSH state at frame " + frameCounter);
    }

    /** Models {@code tst.l (Plc_Buffer).w} (docs/s2disasm/s2.asm:4923). */
    private boolean plcQueueBusy() {
        if (SessionManager.getCurrentWorldSession() == null) {
            return false;
        }
        Sonic2PlcService plcService =
                GameServices.module().getGameService(Sonic2PlcService.class);
        return plcService != null && plcService.isBusy();
    }

    /**
     * Exit phase 1: Left swoosh (red stripes) slides out.
     * From disassembly: Obj34_LeftPartOut (routine $E)
     * When complete, triggers bottom bar exit.
     */
    private void updateExitLeftSwoosh() {
        if (leftSwooshElement != null) {
            leftSwooshElement.updateSlideOut();
        }
        // Obj34_LeftPartOut hands the bottom piece routine $10 on its fifth
        // pass, whatever the overlay's own travel has reached
        // (docs/s2disasm/s2.asm:27518-27540).
        if (leaveLoopPass() >= LEAVE_LEFT_PASSES) {
            state = TitleCardState.EXIT_BOTTOM_BAR;
            stateTimer = 0;
            if (bottomBarElement != null) {
                bottomBarElement.startExit();
            }
            LOGGER.fine("Title card entered EXIT_BOTTOM_BAR state at frame " + frameCounter);
        }
    }

    /**
     * Iterations of the s2.asm:5060-5066 leave loop completed so far, i.e. the
     * leave-sequence pass count less the leading s2.asm:5006 pass.
     */
    private int leaveLoopPass() {
        return leavePass - LEAVE_PRELOOP_PASSES;
    }

    private void enterTextWait() {
        state = TitleCardState.TEXT_WAIT;
        stateTimer = 0;
        leavePass = 0;
        // s2.asm:5066-5080 writes routine $16 and anim_frame_duration $2D to
        // the surviving pieces here, after the leave loop and before
        // Level_MainLoop, whether or not the card was displayed.
        exitTailWaitFrames = TEXT_WAIT_DURATION;
        exitTailZoneNameX = EXIT_TAIL_ZONE_NAME_X_TARGET;
        LOGGER.fine("Title card entered TEXT_WAIT state at frame " + frameCounter);
    }

    /**
     * Exit phase 2: Bottom bar slides out.
     * From disassembly: Obj34_BottomPartOut (routine $10)
     * When complete, triggers background exit.
     */
    private void updateExitBottomBar() {
        if (bottomBarElement != null) {
            bottomBarElement.updateSlideOut();
        }
        // Obj34_BottomPartOut hands the background routine $12 on the pass that
        // reads titlecard_location $28, its eleventh
        // (docs/s2disasm/s2.asm:27542-27551).
        if (leaveLoopPass() >= LEAVE_LEFT_PASSES + LEAVE_BOTTOM_PASSES) {
            state = TitleCardState.EXIT_BACKGROUND;
            stateTimer = 0;
            if (blueBackgroundElement != null) {
                blueBackgroundElement.startExit();
            }
            LOGGER.fine("Title card entered EXIT_BACKGROUND state at frame " + frameCounter);
        }
    }

    /**
     * Exit phase 3: Background scrolls out.
     * From disassembly: Obj34_BackgroundOut (routine $14)
     * When complete, starts the text wait period.
     *
     * <p>In the original game, the background object is deleted when its internal
     * location counter reaches -$30. There is <em>no</em> extra V-blank between
     * that deletion and the loop falling through: the leave loop at
     * docs/s2disasm/s2.asm:5060-5066 does
     * {@code move.b #VintID_TitleCard,(Vint_routine).w} / {@code bsr.w WaitForVint}
     * (:5060-5061), then {@code jsr (RunObjects).l} (:5062) -- which is the pass
     * that deletes {@code TitleCard_Background} -- and then reads the cleared id
     * three instructions later, {@code tst.b (TitleCard_Background+id).w} /
     * {@code bne.s -} (:5065-5066), in the <em>same</em> iteration. The fall-through
     * at :5068 runs on through {@code Level_StartGame} (:5084) into
     * {@code Level_MainLoop}, whose own {@code bsr.w WaitForVint} (:5089-5091) is
     * the next V-blank, so no V-blank is spent between the delete and the
     * fall-through. The deleting iteration is therefore both the last leave-loop
     * pass and the fall-through row -- a previous version of this javadoc claimed a
     * 1-frame delay here, and the disassembly refutes it.
     *
     * <p>We also verify the background is completely off-screen (blueBottom <= 0)
     * before transitioning, to ensure no visual remnant remains.
     */
    private void updateExitBackground() {
        if (blueBackgroundElement != null) {
            blueBackgroundElement.updateSlideOut();
        }
        // Obj34_BackgroundOut deletes itself on the pass that computes -$30,
        // its ninth (docs/s2disasm/s2.asm:27587-27604). The loop's
        // tst.b (TitleCard_Background+id).w (:5065) reads the cleared id in the
        // SAME iteration as the RunObjects call that deleted it (:5062), so that
        // iteration is both the last pass and the fall-through row -- it is the
        // 26th and final pass counted by LEAVE_PLAYABLE_PASSES, not a pass
        // followed by a separate fall-through frame.
    }

    /**
     * Exit phase 4: Text waits for 45 frames ($2D) before exiting.
     * From disassembly: Obj34_WaitAndGoAway (routine $16)
     * The level is visible behind the text during this phase.
     *
     * <p>Original behavior: anim_frame_duration starts at $2D (45), decrements each
     * frame, and exits when it reaches 0. This means 45 frames of display before
     * the exit animation starts.
     *
     * <p>Our stateTimer is incremented before the check, so we use > instead of >=
     * to ensure exactly 45 frames of waiting (stateTimer values 1-45, transition
     * when stateTimer becomes 46).
     */
    private void updateTextWait() {
        if (stateTimer > TEXT_WAIT_DURATION) {
            state = TitleCardState.TEXT_EXIT;
            stateTimer = 0;
            // Start text element exits
            if (zoneNameElement != null) zoneNameElement.startExit();
            if (zoneTextElement != null) zoneTextElement.startExit();
            if (actNumberElement != null) actNumberElement.startExit();
            LOGGER.fine("Title card entered TEXT_EXIT state at frame " + frameCounter);
        }
    }

    /**
     * Exit phase 5: Text elements slide out.
     * From disassembly: Obj34_WaitAndGoAway continues after wait expires.
     *
     * <p>The original game's behavior is:
     * <ol>
     *   <li>Move sprite toward titlecard_x_source at 32 pixels/frame</li>
     *   <li>Display sprite via DisplaySprite</li>
     *   <li>Delete object when x == titlecard_x_source OR x > $200</li>
     * </ol>
     *
     * <p>This means the sprite is displayed at its final position BEFORE deletion.
     * To match this, we delay the COMPLETE transition by one frame after all
     * elements finish their animation, ensuring they're drawn at their final
     * (off-screen) positions.
     */
    private void updateTextExit() {
        // Check if we should transition (delayed by one frame)
        if (textExitTransitionPending) {
            state = TitleCardState.COMPLETE;
            stateTimer = 0;
            textExitTransitionPending = false;
            LOGGER.fine("Title card COMPLETE at frame " + frameCounter);
            return;
        }

        // Update text elements
        if (zoneNameElement != null) zoneNameElement.updateSlideOut();
        if (zoneTextElement != null) zoneTextElement.updateSlideOut();
        if (actNumberElement != null) actNumberElement.updateSlideOut();

        // Check if all text elements have exited
        boolean zoneNameExited = (zoneNameElement == null || zoneNameElement.hasExited());
        boolean zoneTextExited = (zoneTextElement == null || zoneTextElement.hasExited());
        boolean actNumberExited = (actNumberElement == null || actNumberElement.hasExited());

        // The two art loads are owned by advanceZoneNamePieceTail(), which
        // models Obj34_WaitAndGoAway's own x_pixel test rather than this
        // overlay element's viewport-relative exit.

        if (zoneNameExited && zoneTextExited && actNumberExited) {
            // Mark transition as pending - actual transition happens next frame
            // This ensures elements are drawn at their final positions first
            textExitTransitionPending = true;
            LOGGER.fine("Title card text exit complete, transition pending at frame " + frameCounter);
        }
    }

    /**
     * Arms the gameplay-phase title-card tail for a presentation that was
     * omitted.
     *
     * <p>{@code Level_TtlCard} leaves its locked loop as soon as the zone-name
     * piece has reached {@code titlecard_x_target} and {@code Plc_Buffer} is
     * empty (s2.asm:4914-4925), but the pieces themselves are still live: just
     * before the main level loop the game rewrites their routine to {@code $16}
     * with {@code anim_frame_duration = $2D} (s2.asm:5066-5080), so
     * {@code Obj34_WaitAndGoAway} runs on ordinary gameplay frames. Omitting the
     * presentation must still run that tail, because the frame the zone-name
     * piece leaves is where the game loads the standard-water and per-zone
     * animal art (s2.asm:27605-27637).
     *
     * <p>Only the zone-name piece is modelled: {@code
     * Obj34_LoadStandardWaterAndAnimalArt} gates the two {@code LoadPLC} calls
     * on {@code cmpa.w #TitleCard_ZoneName,a0} (s2.asm:27630), so the other
     * pieces only delete themselves.
     */
    @Override
    public void beginOmittedPresentationExitTail(int zoneIndex, int actIndex) {
        // Animal_PLCTable is indexed by Current_Zone (s2.asm:27633-27636), so
        // the tail needs the zone the omitted card would have shown.
        currentZone = zoneIndex;
        currentAct = actIndex;
        exitTailActive = true;
        exitPlcsQueued = false;
        exitTailWaitFrames = TEXT_WAIT_DURATION;
        exitTailZoneNameX = EXIT_TAIL_ZONE_NAME_X_TARGET;
        // The presentation is omitted, so no piece may render; the tail is an
        // object lifetime, not an overlay.
        elements.clear();
        zoneNameElement = null;
        zoneTextElement = null;
        actNumberElement = null;
        bottomBarElement = null;
        leftSwooshElement = null;
        blueBackgroundElement = null;
        textExitTransitionPending = false;
        stateTimer = 0;
        // TEXT_WAIT is the phase the native game is in here: control released,
        // title-card game-mode bit cleared, pieces still alive. It also keeps
        // the level loop dispatching update() each frame.
        state = TitleCardState.TEXT_WAIT;
    }

    /**
     * One gameplay frame of {@code Obj34_WaitAndGoAway} for the zone-name piece.
     * docs/s2disasm/s2.asm:27605-27637
     */
    private void updateOmittedPresentationExitTail() {
        if (advanceZoneNamePieceTail()) {
            finishOmittedPresentationExitTail();
        }
    }

    /**
     * One gameplay pass of {@code Obj34_WaitAndGoAway} for the zone-name piece,
     * returning true on the pass that reaches {@code
     * Obj34_LoadStandardWaterAndAnimalArt} (docs/s2disasm/s2.asm:27605-27637).
     *
     * <p>This is the single owner of that routine's timing. {@code Level}
     * arms it identically whether or not the card was displayed: the routine
     * byte and {@code anim_frame_duration = $2D} are written after the leave
     * loop and immediately before {@code Level_MainLoop}
     * (docs/s2disasm/s2.asm:5066-5080), so the count always starts on the
     * first main-loop iteration. The presentation path previously decided the
     * same event from its overlay elements' viewport-relative {@code
     * hasExited()} plus a state-transition pass that consumed a frame without
     * moving the piece, which fired the two art loads two frames late.
     */
    private boolean advanceZoneNamePieceTail() {
        // tst.w anim_frame_duration(a0) / subq.w #1 / bra DisplaySprite
        if (exitTailWaitFrames > 0) {
            exitTailWaitFrames--;
            return false;
        }
        // cmp.w titlecard_x_source(a0),d1 / beq Obj34_LoadStandardWaterAndAnimalArt
        if (exitTailZoneNameX == EXIT_TAIL_ZONE_NAME_X_SOURCE) {
            return true;
        }
        // sub.w d0,x_pixel(a0) with d0 negated while below the source position
        exitTailZoneNameX += EXIT_TAIL_SLIDE_SPEED;
        // cmpi.w #$200,x_pixel(a0) / bhi Obj34_LoadStandardWaterAndAnimalArt
        return exitTailZoneNameX == EXIT_TAIL_ZONE_NAME_X_SOURCE
                || exitTailZoneNameX > EXIT_TAIL_OFFSCREEN_LIMIT;
    }

    private void finishOmittedPresentationExitTail() {
        queueExitPlcs();
        exitTailActive = false;
        state = TitleCardState.COMPLETE;
        stateTimer = 0;
    }

    private void queueExitPlcs() {
        if (exitPlcsQueued) {
            return;
        }
        try {
            Sonic2PlcService plcService = GameServices.module().getGameService(Sonic2PlcService.class);
            if (plcService != null) {
                Sonic2PlcService.Operation[] transaction = {
                        Sonic2PlcService.appendOperation(Sonic2Constants.PLC_STD_WATER),
                        Sonic2PlcService.appendOperation(animalPlcForZone(currentZone))};
                if (GameServices.module().getObjectArtProvider() instanceof Sonic2ObjectArtProvider artProvider
                        && GameServices.levelOrNull() != null) {
                    Sonic2RuntimePlcPublisher.transact(artProvider, plcService,
                            GameServices.levelOrNull()::refreshObjectArtPatterns, transaction);
                } else {
                    plcService.transact(transaction);
                }
                exitPlcsQueued = true;
            }
        } catch (Exception e) {
            // The presentation renderer also runs without a gameplay module in focused tests.
            LOGGER.log(Level.FINE, "Title card exit PLCs not queued", e);
        }
    }

    private static int animalPlcForZone(int zone) {
        return switch (zone) {
            case 0 -> Sonic2Constants.PLC_ANIMALS_EHZ;
            case 1 -> Sonic2Constants.PLC_ANIMALS_CPZ;
            case 2 -> Sonic2Constants.PLC_ANIMALS_ARZ;
            case 3 -> Sonic2Constants.PLC_ANIMALS_CNZ;
            case 4, 7, 9 -> Sonic2Constants.PLC_ANIMALS_HTZ_MTZ_WFZ;
            case 5 -> Sonic2Constants.PLC_ANIMALS_MCZ;
            case 6 -> Sonic2Constants.PLC_ANIMALS_OOZ;
            case 8 -> Sonic2Constants.PLC_ANIMALS_SCZ;
            case 10 -> Sonic2Constants.PLC_ANIMALS_DEZ;
            default -> Sonic2Constants.PLC_ANIMALS_EHZ;
        };
    }

    /**
     * Renders the title card.
     * Call this from Engine.draw() when in TITLE_CARD mode.
     */
    public void draw() {
        ensureArtCached();

        GraphicsManager graphicsManager = GameServices.graphics();
        if (graphicsManager == null) {
            return;
        }

        // Draw black background during SLIDE_IN state only.
        // This covers the level graphics until the title card elements are in place.
        // Once exit begins (EXIT_LEFT_SWOOSH and beyond), the level becomes visible
        // behind the remaining title card elements, matching the original behavior.
        if (state == TitleCardState.SLIDE_IN) {
            // Span the full viewport so no level bleeds through on wider screens.
            // xOffset()==0 at native 320 - byte-identical.
            graphicsManager.registerCommand(new GLCommand(
                    GLCommand.CommandType.RECTI,
                    -1,
                    0.0f, 0.0f, 0.0f,  // Black
                    0, 0, viewportWidth(), SCREEN_HEIGHT
            ));
        }

        // Draw background plane elements (yellow bottom, red left)
        // These are drawn before sprites, similar to how the VDP draws planes behind sprites
        drawBackgroundPlanes(graphicsManager);

        // Begin pattern batch for sprite rendering
        graphicsManager.beginPatternBatch();

        // Draw sprites with correct layering:
        // Red triangles first (lowest priority - behind text)
        if (leftSwooshElement != null && leftSwooshElement.isVisible()) {
            renderElement(graphicsManager, leftSwooshElement);
        }

        // Then other elements (zone name, ZONE, act number, bottom bar)
        for (TitleCardElement element : elements) {
            if (element != leftSwooshElement && element.isVisible()) {
                renderElement(graphicsManager, element);
            }
        }

        graphicsManager.flushPatternBatch();
    }

    /**
     * Draws the background plane elements (colored rectangles).
     *
     * In the original game, the title card uses plane tiles to draw:
     * - Blue background (tile $5A) - handled by Engine.java glClearColor
     * - Yellow bottom block (tile $5C) - from Y=160 to bottom of screen
     * - Red left block (tile $58) - from X=0 to swoosh position, full height
     *
     * The yellow bar slides in from the right and slides out to the right.
     * The red block follows the left swoosh animation.
     */
    private void drawBackgroundPlanes(GraphicsManager graphicsManager) {
        int vw = viewportWidth();
        int xOff = xOffset();

        // Draw blue top block - covers Y=0 to Y=152 (above yellow bar).
        // Spans the full viewport width so no level bleeds through on wider screens.
        // xOff==0 at native 320 - byte-identical.
        if (blueBackgroundElement != null && blueBackgroundElement.isVisible()) {
            int blueY = blueBackgroundElement.getCurrentY();
            int blueTop = blueY;
            int blueBottom = blueY + 152;  // Blue box is 152 pixels tall

            // Only draw if visible on screen
            if (blueBottom > 0) {
                // Blue color RGB(48, 87, 206) from original title card
                graphicsManager.registerCommand(new GLCommand(
                        GLCommand.CommandType.RECTI,
                        -1,
                        48.0f/255.0f, 87.0f/255.0f, 206.0f/255.0f,  // Title card blue
                        0, blueTop, vw, blueBottom
                ));
            }
        }

        // Draw yellow bottom block - extends from Y=152 to bottom of screen (Y=224).
        // Yellow covers full viewport width when bar is at target, follows bar during exit.
        // xOff==0 at native 320 - byte-identical.
        if (bottomBarElement != null && bottomBarElement.isVisible()) {
            int barX = bottomBarElement.getCurrentX();
            int targetX = 232;  // Bar's target X position (in 320-space)

            // Yellow left edge:
            // - When bar is at/past target: left edge at 0 (full width)
            // - When bar is moving out (past target going right): left edge follows bar.
            //   barX and targetX are both in 320-space so the delta is viewport-independent.
            int yellowLeft;
            if (barX <= targetX) {
                // Bar is at or approaching target - full yellow coverage
                yellowLeft = 0;
            } else {
                // Bar is moving out to the right - yellow follows
                yellowLeft = barX - targetX;
            }

            // Yellow right edge always extends past the viewport
            int yellowRight = vw + 50;
            int yellowTop = 152;
            int yellowBottom = SCREEN_HEIGHT;

            // Only draw if visible (yellowLeft is viewport-independent, compare vs vw)
            if (yellowLeft < vw) {
                graphicsManager.registerCommand(new GLCommand(
                        GLCommand.CommandType.RECTI,
                        -1,
                        1.0f, 1.0f, 0.0f,  // Bright yellow
                        yellowLeft, yellowTop, yellowRight, yellowBottom
                ));
            }
        }

        // Draw red left block - from X=0 to the swoosh's screen position, full height.
        // The swoosh's screen X = currentX (320-space) + xOff.
        // At native xOff==0 so redRight = currentX - byte-identical.
        if (leftSwooshElement != null && leftSwooshElement.isVisible()) {
            int redRight = leftSwooshElement.getCurrentX() + xOff;

            // Only draw if visible on screen
            if (redRight > 0) {
                // Red from palette line 0 index 12 (0x000e): B=0, G=0, R=7
                // MD format (0-7) to float: R=7/7=1.0, G=0, B=0
                graphicsManager.registerCommand(new GLCommand(
                        GLCommand.CommandType.RECTI,
                        -1,
                        1.0f, 0.0f, 0.0f,  // Pure red
                        0, 0, redRight, SCREEN_HEIGHT
                ));
            }
        }
    }

    /**
     * Renders a single element using its mapping frame.
     *
     * <p>xOffset() centres the 320-wide composition in the viewport.
     * At native 320 xOffset()==0 - byte-identical.
     */
    private void renderElement(GraphicsManager graphicsManager, TitleCardElement element) {
        if (!artLoaded || combinedPatterns == null) {
            return;
        }

        int frameIndex = element.getFrameIndex();
        // Skip background-only elements (frameIndex == -1)
        if (frameIndex < 0) {
            return;
        }
        TitleCardMappings.SpritePiece[] pieces = TitleCardMappings.getFrame(frameIndex);

        int centerX = element.getCurrentX() + xOffset();
        int centerY = element.getY();

        for (TitleCardMappings.SpritePiece piece : pieces) {
            TitleCardSpriteRenderer.renderSpritePiece(
                    graphicsManager, piece, centerX, centerY,
                    VRAM_BASE, PATTERN_BASE, combinedPatterns.length);
        }
    }

    /**
     * Returns true if the title card animation is fully complete.
     * Use this to determine when to stop drawing the title card.
     */
    public boolean isComplete() {
        return state == TitleCardState.COMPLETE;
    }

    /**
     * Returns true if player control should be released.
     * From disassembly lines 5073-5078: control is unlocked and the title card
     * game mode flag is cleared at the START of TEXT_WAIT, not when the title
     * card is complete. This allows the player to move while the text is still
     * visible on screen.
     */
    public boolean shouldReleaseControl() {
        return state == TitleCardState.TEXT_WAIT ||
               state == TitleCardState.TEXT_EXIT ||
               state == TitleCardState.COMPLETE;
    }

    /**
     * Returns true if the title card overlay should still be drawn.
     * The overlay remains visible during TEXT_WAIT and TEXT_EXIT phases,
     * even though player control has been released and the game mode has
     * returned to LEVEL. This creates the effect of the text floating over
     * the level while the player can already move.
     */
    public boolean isOverlayActive() {
        return state == TitleCardState.TEXT_WAIT ||
               state == TitleCardState.TEXT_EXIT;
    }

    /**
     * Gets the current state.
     */
    public TitleCardState getState() {
        return state;
    }

    /**
     * Resets the manager state.
     */
    public void reset() {
        state = TitleCardState.COMPLETE;
        stateTimer = 0;
        frameCounter = 0;
        textExitTransitionPending = false;
        zoneTileUploadFramesLeft = 0;
        exitPlcsQueued = false;
        exitTailActive = false;
        exitTailWaitFrames = 0;
        exitTailZoneNameX = 0;
        leavePass = 0;
        elements.clear();
        zoneNameElement = null;
        zoneTextElement = null;
        actNumberElement = null;
        bottomBarElement = null;
        leftSwooshElement = null;
        blueBackgroundElement = null;
    }

    /**
     * Player physics runs only for the object passes the ROM dispatches with
     * the player objects created.
     *
     * <p>{@code InitPlayers} is at docs/s2disasm/s2.asm:4945, after the
     * {@code Level_TtlCard} scroll-in wait loop at :4914-4925, so Sonic and
     * Tails do not exist for the card's slide-in and hold at all. They exist
     * for the single {@code RunObjects} at :5006 and for the 25 iterations of
     * the leave loop at :5060-5066 -- 26 passes, tracked by {@link #leavePass}.
     */
    @Override
    public boolean shouldRunPlayerPhysics() {
        return leavePass > 0;
    }

    /**
     * The level's placed objects have exactly the same ROM lifetime as the
     * players, and for the same reason: {@code Level:} calls
     * {@code ObjectsManager} at docs/s2disasm/s2.asm:5003 -- one instruction
     * before the {@code InitPlayers} window cited above -- so the object RAM
     * the {@code Level_TtlCard} scroll-in loop at :4914-4925 dispatches holds
     * only the Obj34 title-card pieces, never a level object. The level
     * objects therefore run for the single {@code RunObjects} at :5006 and the
     * 25 leave-loop iterations at :5060-5066, and for nothing before them.
     *
     * <p>Without this the engine aged every placed object by the full length
     * of the card's slide-in and hold. That is invisible on a fresh headless
     * load, whose card is only the leave window, but a re-entry card (a star
     * post restart, or a special-stage return inside a run) is far longer: the
     * CPZ act 2 special-stage return ran 91 object passes where the ROM runs
     * 26, drifting the ObjA7 Grabber cluster (x_vel {@code #-$40} per pass,
     * s2.asm:76662-76668) about 17 pixels and putting a badnik in the player's
     * path that the ROM never places there.
     *
     * @see #shouldRunPlayerPhysics()
     */
    @Override
    public boolean shouldRunLevelObjectsDuringLockedPhase() {
        return leavePass > 0;
    }

    /**
     * ROM {@code RunObjects} extends its slot count to {@code LevelOnly_Object_RAM}
     * only when {@code Game_Mode} is exactly {@code GameModeID_Level}
     * (docs/s2disasm/s2.asm:29812-29818); {@code GameModeFlag_TitleCard} is set for
     * the whole pre-{@code Level_MainLoop} window (set s2.asm:4758, cleared
     * s2.asm:5087). So the 26 pre-main-loop passes above run Sonic and Tails --
     * both inside {@code Object_RAM} -- but never Obj05 Tails' tails or the
     * dust/shield/bubble/star slots that follow {@code Object_RAM_End}
     * (docs/s2disasm/s2.constants.asm:1145-1176).
     */
    @Override
    public boolean shouldRunLevelOnlyFixedSlotsDuringLockedPhase() {
        return false;
    }


    /**
     * Gets the current zone index.
     */
    public int getCurrentZone() {
        return currentZone;
    }

    /**
     * Gets the current act index.
     */
    public int getCurrentAct() {
        return currentAct;
    }
}
