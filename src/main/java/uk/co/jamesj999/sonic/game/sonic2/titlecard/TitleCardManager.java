package uk.co.jamesj999.sonic.game.sonic2.titlecard;

import uk.co.jamesj999.sonic.data.Rom;
import uk.co.jamesj999.sonic.data.RomManager;
import uk.co.jamesj999.sonic.game.TitleCardProvider;
import uk.co.jamesj999.sonic.game.sonic2.constants.Sonic2Constants;
import uk.co.jamesj999.sonic.graphics.GraphicsManager;
import uk.co.jamesj999.sonic.level.Pattern;
import uk.co.jamesj999.sonic.level.PatternDesc;
import uk.co.jamesj999.sonic.tools.NemesisReader;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
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
public class TitleCardManager implements TitleCardProvider {
    private static final Logger LOGGER = Logger.getLogger(TitleCardManager.class.getName());

    private static TitleCardManager instance;

    /**
     * Display hold duration in frames before starting the exit sequence.
     *
     * On original hardware, the title card would remain visible while the console
     * performed expensive operations: decompressing Nemesis/Kosinski art, loading
     * level layout data, initializing objects, etc. This created a natural pause
     * where the title card was fully visible before exiting.
     *
     * Our engine loads data much faster (often in a single frame), so we add an
     * artificial hold period to simulate the original timing and give players
     * time to read the zone name. 60 frames ≈ 1 second at 60fps.
     */
    private static final int DISPLAY_HOLD_DURATION = 60;

    /**
     * Text wait duration in frames before sliding out.
     * From disassembly: $2D = 45 frames (lines 5066-5072 in s2.asm).
     * This wait starts AFTER the background has fully exited.
     */
    private static final int TEXT_WAIT_DURATION = 0x2D;  // 45 frames

    /** Screen dimensions */
    private static final int SCREEN_WIDTH = 320;
    private static final int SCREEN_HEIGHT = 224;

    /** Pattern base ID for title card art (high ID to avoid conflicts) */
    private static final int PATTERN_BASE = 0x40000;

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
    private int lastLoadedZone = -1;  // Track which zone's letters we've loaded

    private TitleCardManager() {}

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
        this.state = TitleCardState.SLIDE_IN;
        this.stateTimer = 0;
        this.frameCounter = 0;
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
            RomManager romManager = RomManager.getInstance();
            if (!romManager.isRomAvailable()) {
                LOGGER.warning("ROM not available for title card art loading");
                return;
            }
            Rom rom = romManager.getRom();

            // Load ArtNem_TitleCard - base title card art (E,N,O,Z, numbers, bar, stripes)
            titleCardBasePatterns = loadNemesisPatterns(rom,
                    Sonic2Constants.ART_NEM_TITLE_CARD_ADDR, "TitleCard");
            LOGGER.info("Loaded " + (titleCardBasePatterns != null ? titleCardBasePatterns.length : 0) +
                    " title card base patterns");

            // Load ArtNem_TitleCard2 - zone name alphabet (keep in "RAM")
            titleCard2RawPatterns = loadNemesisPatterns(rom,
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
            LOGGER.warning("Failed to load title card art: " + e.getMessage());
            e.printStackTrace();
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

    private Pattern[] loadNemesisPatterns(Rom rom, int address, String name) {
        try {
            byte[] compressed = rom.readBytes(address, 8192);
            try (ByteArrayInputStream bais = new ByteArrayInputStream(compressed);
                 ReadableByteChannel channel = Channels.newChannel(bais)) {
                byte[] decompressed = NemesisReader.decompress(channel);
                int patternCount = decompressed.length / Pattern.PATTERN_SIZE_IN_ROM;
                Pattern[] patterns = new Pattern[patternCount];
                for (int i = 0; i < patternCount; i++) {
                    patterns[i] = new Pattern();
                    byte[] subArray = Arrays.copyOfRange(decompressed,
                            i * Pattern.PATTERN_SIZE_IN_ROM,
                            (i + 1) * Pattern.PATTERN_SIZE_IN_ROM);
                    patterns[i].fromSegaFormat(subArray);
                }
                return patterns;
            }
        } catch (IOException e) {
            LOGGER.warning("Failed to load " + name + " patterns: " + e.getMessage());
            return new Pattern[0];
        }
    }

    /**
     * Ensures art is cached to GPU.
     */
    private void ensureArtCached() {
        if (artCached || !artLoaded || combinedPatterns == null) {
            return;
        }

        GraphicsManager graphicsManager = GraphicsManager.getInstance();
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

        switch (state) {
            case SLIDE_IN -> updateSlideIn();
            case DISPLAY -> updateDisplay();
            case EXIT_LEFT_SWOOSH -> updateExitLeftSwoosh();
            case EXIT_BOTTOM_BAR -> updateExitBottomBar();
            case EXIT_BACKGROUND -> updateExitBackground();
            case TEXT_WAIT -> updateTextWait();
            case TEXT_EXIT -> updateTextExit();
            case COMPLETE -> {}
        }
    }

    private void updateSlideIn() {
        // Update all elements
        for (TitleCardElement element : elements) {
            element.updateSlideIn();
        }

        // Check if all elements have reached their targets
        boolean allAtTarget = elements.stream().allMatch(TitleCardElement::isAtTarget);
        if (allAtTarget) {
            state = TitleCardState.DISPLAY;
            stateTimer = 0;
            LOGGER.fine("Title card entered DISPLAY state at frame " + frameCounter);
        }
    }

    private void updateDisplay() {
        // Hold the title card visible for DISPLAY_HOLD_DURATION frames.
        //
        // On original hardware, the Mega Drive would spend significant time here
        // decompressing art (Nemesis, Kosinski), loading level layouts, and
        // initializing game objects. The title card naturally stayed visible
        // during this loading period.
        //
        // Our engine completes these operations nearly instantly, so we add an
        // artificial hold to match the original game's pacing and give players
        // time to see which zone they're entering.
        if (stateTimer >= DISPLAY_HOLD_DURATION) {
            state = TitleCardState.EXIT_LEFT_SWOOSH;
            stateTimer = 0;
            // Initialize left swoosh exit - from disassembly line 5054:
            // move.w #$A,(TitleCard_Left+titlecard_location).w
            if (leftSwooshElement != null) {
                leftSwooshElement.startExit();
            }
            LOGGER.fine("Title card entered EXIT_LEFT_SWOOSH state at frame " + frameCounter);
        }
    }

    /**
     * Exit phase 1: Left swoosh (red stripes) slides out.
     * From disassembly: Obj34_LeftPartOut (routine $E)
     * When complete, triggers bottom bar exit.
     */
    private void updateExitLeftSwoosh() {
        if (leftSwooshElement != null) {
            leftSwooshElement.updateSlideOut();

            if (leftSwooshElement.hasExited()) {
                // Trigger bottom bar exit - from disassembly line 27303:
                // move.b #$10,TitleCard_Bottom-TitleCard_Left+routine(a0)
                state = TitleCardState.EXIT_BOTTOM_BAR;
                stateTimer = 0;
                if (bottomBarElement != null) {
                    bottomBarElement.startExit();
                }
                LOGGER.fine("Title card entered EXIT_BOTTOM_BAR state at frame " + frameCounter);
            }
        } else {
            // No left swoosh, skip to bottom bar
            state = TitleCardState.EXIT_BOTTOM_BAR;
            stateTimer = 0;
            if (bottomBarElement != null) {
                bottomBarElement.startExit();
            }
        }
    }

    /**
     * Exit phase 2: Bottom bar slides out.
     * From disassembly: Obj34_BottomPartOut (routine $10)
     * When complete, triggers background exit.
     */
    private void updateExitBottomBar() {
        if (bottomBarElement != null) {
            bottomBarElement.updateSlideOut();

            if (bottomBarElement.hasExited()) {
                // Trigger background exit - from disassembly line 27328:
                // move.b #$12,TitleCard_Background-TitleCard_Bottom+routine(a0)
                state = TitleCardState.EXIT_BACKGROUND;
                stateTimer = 0;
                if (blueBackgroundElement != null) {
                    blueBackgroundElement.startExit();
                }
                LOGGER.fine("Title card entered EXIT_BACKGROUND state at frame " + frameCounter);
            }
        } else {
            // No bottom bar, skip to background
            state = TitleCardState.EXIT_BACKGROUND;
            stateTimer = 0;
            if (blueBackgroundElement != null) {
                blueBackgroundElement.startExit();
            }
        }
    }

    /**
     * Exit phase 3: Background scrolls out.
     * From disassembly: Obj34_BackgroundOut (routine $14)
     * When complete, starts the text wait period.
     *
     * <p>In the original game, the background object is deleted when its internal
     * location counter reaches -$30, and the main loop (lines 5061-5062) checks
     * on the NEXT VBlank whether the object is gone. This creates a 1-frame delay
     * between the background finishing and the text timer starting.
     *
     * <p>We also verify the background is completely off-screen (blueBottom <= 0)
     * before transitioning, to ensure no visual remnant remains.
     */
    private void updateExitBackground() {
        if (blueBackgroundElement != null) {
            blueBackgroundElement.updateSlideOut();

            if (blueBackgroundElement.hasExited()) {
                // Verify background is completely off-screen (blueY + 152 <= 0)
                int blueBottom = blueBackgroundElement.getCurrentY() + 152;
                if (blueBottom <= 0) {
                    // Background is fully gone - now text gets its wait timer
                    // From disassembly lines 5065-5072:
                    // move.w #$2D,TitleCard_ZoneName-TitleCard+anim_frame_duration(a1)
                    state = TitleCardState.TEXT_WAIT;
                    stateTimer = 0;
                    LOGGER.fine("Title card entered TEXT_WAIT state at frame " + frameCounter);
                }
                // If blueBottom > 0, wait another frame for it to fully disappear
            }
        } else {
            // No background element, skip to text wait
            state = TitleCardState.TEXT_WAIT;
            stateTimer = 0;
        }
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

        if (zoneNameExited && zoneTextExited && actNumberExited) {
            // Mark transition as pending - actual transition happens next frame
            // This ensures elements are drawn at their final positions first
            textExitTransitionPending = true;
            LOGGER.fine("Title card text exit complete, transition pending at frame " + frameCounter);
        }
    }

    /**
     * Renders the title card.
     * Call this from Engine.draw() when in TITLE_CARD mode.
     */
    public void draw() {
        ensureArtCached();

        GraphicsManager graphicsManager = GraphicsManager.getInstance();
        if (graphicsManager == null) {
            return;
        }

        // Draw black background during SLIDE_IN state only.
        // This covers the level graphics until the title card elements are in place.
        // Once exit begins (EXIT_LEFT_SWOOSH and beyond), the level becomes visible
        // behind the remaining title card elements, matching the original behavior.
        if (state == TitleCardState.SLIDE_IN) {
            graphicsManager.registerCommand(new uk.co.jamesj999.sonic.graphics.GLCommand(
                    uk.co.jamesj999.sonic.graphics.GLCommand.CommandType.RECTI,
                    -1,
                    0.0f, 0.0f, 0.0f,  // Black
                    0, 0, SCREEN_WIDTH, SCREEN_HEIGHT
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
        // Draw blue top block - covers Y=0 to Y=152 (above yellow bar)
        // Animates vertically from above screen
        if (blueBackgroundElement != null && blueBackgroundElement.isVisible()) {
            int blueY = blueBackgroundElement.getCurrentY();
            int blueTop = blueY;
            int blueBottom = blueY + 152;  // Blue box is 152 pixels tall

            // Only draw if visible on screen
            if (blueBottom > 0) {
                // Blue color RGB(48, 87, 206) from original title card
                graphicsManager.registerCommand(new uk.co.jamesj999.sonic.graphics.GLCommand(
                        uk.co.jamesj999.sonic.graphics.GLCommand.CommandType.RECTI,
                        -1,
                        48.0f/255.0f, 87.0f/255.0f, 206.0f/255.0f,  // Title card blue
                        0, blueTop, SCREEN_WIDTH, blueBottom
                ));
            }
        }

        // Draw yellow bottom block - extends from Y=152 to bottom of screen (Y=224)
        // This is the yellow area that "SONIC THE HEDGEHOG" text sits on
        // Yellow covers full width when bar is at target, slides off with bar during exit
        if (bottomBarElement != null && bottomBarElement.isVisible()) {
            int barX = bottomBarElement.getCurrentX();
            int targetX = 232;  // Bar's target X position
            int barWidth = 0x48;  // 72 pixels

            // Yellow left edge:
            // - When bar is at/past target: left edge at 0 (full width)
            // - When bar is moving out (past target going right): left edge follows bar
            int yellowLeft;
            if (barX <= targetX) {
                // Bar is at or approaching target - full yellow coverage
                yellowLeft = 0;
            } else {
                // Bar is moving out to the right - yellow follows
                yellowLeft = barX - targetX;
            }

            // Yellow right edge always extends past screen
            int yellowRight = SCREEN_WIDTH + 50;
            int yellowTop = 152;
            int yellowBottom = SCREEN_HEIGHT;

            // Only draw if visible on screen
            if (yellowLeft < SCREEN_WIDTH) {
                graphicsManager.registerCommand(new uk.co.jamesj999.sonic.graphics.GLCommand(
                        uk.co.jamesj999.sonic.graphics.GLCommand.CommandType.RECTI,
                        -1,
                        1.0f, 1.0f, 0.0f,  // Bright yellow
                        yellowLeft, yellowTop, yellowRight, yellowBottom
                ));
            }
        }

        // Draw red left block - from X=0 to swoosh position, full height
        // This is the solid red background behind the red triangles
        if (leftSwooshElement != null && leftSwooshElement.isVisible()) {
            int redRight = leftSwooshElement.getCurrentX();

            // Only draw if visible on screen
            if (redRight > 0) {
                // Red from palette line 0 index 12 (0x000e): B=0, G=0, R=7
                // MD format (0-7) to float: R=7/7=1.0, G=0, B=0
                graphicsManager.registerCommand(new uk.co.jamesj999.sonic.graphics.GLCommand(
                        uk.co.jamesj999.sonic.graphics.GLCommand.CommandType.RECTI,
                        -1,
                        1.0f, 0.0f, 0.0f,  // Pure red
                        0, 0, redRight, SCREEN_HEIGHT
                ));
            }
        }
    }

    /**
     * Renders a single element using its mapping frame.
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

        int centerX = element.getCurrentX();
        int centerY = element.getY();

        for (TitleCardMappings.SpritePiece piece : pieces) {
            renderSpritePiece(graphicsManager, piece, centerX, centerY);
        }
    }

    /**
     * Renders a single sprite piece.
     */
    private void renderSpritePiece(GraphicsManager graphicsManager,
                                    TitleCardMappings.SpritePiece piece,
                                    int originX, int originY) {
        int baseTileIndex = piece.tileIndex();

        // Convert VRAM tile index to our pattern array index
        // Array index = VRAM address - VRAM_BASE
        int patternArrayIndex = baseTileIndex - VRAM_BASE;
        if (patternArrayIndex < 0) {
            patternArrayIndex = 0;
        }

        int widthTiles = piece.widthTiles();
        int heightTiles = piece.heightTiles();

        // Render each 8x8 tile in the piece (column-major order like VDP)
        for (int tx = 0; tx < widthTiles; tx++) {
            for (int ty = 0; ty < heightTiles; ty++) {
                int tileOffset = tx * heightTiles + ty;
                int arrayIndex = patternArrayIndex + tileOffset;

                // Bounds check
                if (arrayIndex < 0 || arrayIndex >= combinedPatterns.length) {
                    continue;
                }

                int patternId = PATTERN_BASE + arrayIndex;

                // Calculate screen position
                int tileX = originX + piece.xOffset() + (tx * 8);
                int tileY = originY + piece.yOffset() + (ty * 8);

                // Handle flipping - swap column/row order
                if (piece.hFlip()) {
                    tileX = originX + piece.xOffset() + ((widthTiles - 1 - tx) * 8);
                }
                if (piece.vFlip()) {
                    tileY = originY + piece.yOffset() + ((heightTiles - 1 - ty) * 8);
                }

                // Build PatternDesc with flip flags and palette
                int descIndex = patternId & 0x7FF;
                if (piece.hFlip()) {
                    descIndex |= 0x800;
                }
                if (piece.vFlip()) {
                    descIndex |= 0x1000;
                }
                descIndex |= (piece.paletteIndex() & 0x3) << 13;
                PatternDesc desc = new PatternDesc(descIndex);

                graphicsManager.renderPatternWithId(patternId, desc, tileX, tileY);
            }
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
        elements.clear();
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
