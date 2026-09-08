package com.openggf.game.sonic1;

import com.openggf.data.Rom;
import com.openggf.data.RomByteReader;
import com.openggf.game.GameServices;
import com.openggf.game.ObjectArtProvider;
import com.openggf.game.session.ActiveGameplayTeamResolver;
import com.openggf.level.objects.HudStaticArt;
import com.openggf.level.objects.AnimalType;
import com.openggf.game.sonic1.constants.Sonic1Constants;
import com.openggf.graphics.GraphicsManager;
import com.openggf.level.Level;
import com.openggf.level.Palette;
import com.openggf.level.Pattern;
import com.openggf.level.objects.ObjectArtKeys;
import com.openggf.level.objects.ObjectSpriteSheet;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.level.render.SpriteMappingFrame;
import com.openggf.level.render.SpriteMappingPiece;
import com.openggf.level.render.SpriteMappingPieces;
import com.openggf.sprites.animation.SpriteAnimationEndAction;
import com.openggf.sprites.animation.SpriteAnimationScript;
import com.openggf.sprites.animation.SpriteAnimationSet;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * ObjectArtProvider for Sonic 1.
 * Loads HUD art and game object art (lamppost, etc.) from the Sonic 1 ROM.
 */
public class Sonic1ObjectArtProvider implements ObjectArtProvider {
    private static final Logger LOGGER = Logger.getLogger(Sonic1ObjectArtProvider.class.getName());
    private static final int ANIMAL_TILE_BANK_SIZE = 18;
    private static final int RESULTS_SCORE_DIGIT_PAIR_COUNT = 8;
    private static final int RESULTS_SCORE_DIGIT_TILE_COUNT = RESULTS_SCORE_DIGIT_PAIR_COUNT * 2;
    private static final int HUD_TEXT_E_PAIR_INDEX = 22;
    private static final int MZ_LAVA_WALL_OBGFX_WORD = 0x63A8;
    /**
     * Remaps S3K Knuckles life-icon tile indices onto the native merged S1 HUD palette.
     *
     * <p>The active S1 HUD line is not the raw Sonic character palette; it is the
     * merged gameplay line after the zone palette overwrites the blue ramp.
     * Donated Knuckles life-icon art therefore needs to target the live S1 HUD
     * slots directly: red at 12-14, yellow at 15, skin at 10-11, white at 6,
     * black at 1.
     */
    private static final int[] S3K_TO_S1_LIVES_PALETTE_REMAP = {
            0, 6, 12, 13, 14, 15, 15, 14, 15, 15, 10, 11, 6, 7, 1, 1
    };
    private static final AnimalType[] ENDING_ANIMAL_ORDER = {
            AnimalType.FLICKY, AnimalType.RABBIT, AnimalType.PENGUIN, AnimalType.SEAL,
            AnimalType.PIG, AnimalType.CHICKEN, AnimalType.SQUIRREL
    };
    private static final AnimalType[] DEFAULT_ANIMALS = { AnimalType.RABBIT, AnimalType.FLICKY };
    private static final AnimalType[][] ZONE_ANIMALS = {
            { AnimalType.RABBIT, AnimalType.FLICKY },   // GHZ
            { AnimalType.PENGUIN, AnimalType.SEAL },    // LZ
            { AnimalType.SQUIRREL, AnimalType.SEAL },   // MZ
            { AnimalType.PIG, AnimalType.FLICKY },      // SLZ
            { AnimalType.PIG, AnimalType.CHICKEN },     // SYZ
            { AnimalType.RABBIT, AnimalType.CHICKEN }   // SBZ
    };

    private RomByteReader romReader;
    private Pattern[] hudDigitPatterns;
    private Pattern[] hudTextPatterns;
    private Pattern[] hudLivesPatterns;
    private Pattern[] hudLivesNumbers;
    private Pattern[] hudHexDigits;
    private HudStaticArt hudStaticArt;
    private int animalTypeA = AnimalType.RABBIT.ordinal();
    private int animalTypeB = AnimalType.FLICKY.ordinal();
    private int currentZoneIndex = -1;

    private final Map<String, PatternSpriteRenderer> renderers = new HashMap<>();
    private final Map<String, ObjectSpriteSheet> sheets = new HashMap<>();
    private final Map<String, SpriteAnimationSet> animations = new HashMap<>();
    private final List<String> rendererKeys = new ArrayList<>();
    private final List<ObjectSpriteSheet> sheetOrder = new ArrayList<>();
    private final List<PatternSpriteRenderer> rendererOrder = new ArrayList<>();

    @Override
    public void loadArtForZone(int zoneIndex) throws IOException {
        if (currentZoneIndex == zoneIndex) {
            return;
        }

        // Clear previous registrations on zone change
        renderers.clear();
        sheets.clear();
        animations.clear();
        rendererKeys.clear();
        sheetOrder.clear();
        rendererOrder.clear();

        Rom rom = GameServices.rom().getRom();
        if (rom == null) {
            throw new IllegalStateException("ROM not loaded");
        }
        RomByteReader reader = RomByteReader.fromRom(rom);
        this.romReader = reader;
        Sonic1ObjectArt art = new Sonic1ObjectArt(rom, reader);

        hudDigitPatterns = art.loadUncompressedPatterns(
                Sonic1Constants.ART_UNC_HUD_NUMBERS_ADDR,
                Sonic1Constants.ART_UNC_HUD_NUMBERS_SIZE);

        hudTextPatterns = art.loadNemesisPatterns(Sonic1Constants.ART_NEM_HUD_ADDR);

        hudLivesPatterns = art.loadNemesisPatterns(Sonic1Constants.ART_NEM_LIFE_ICON_ADDR);
        rebuildHudStaticArt();
        overrideLivesArtFromDonor();

        hudLivesNumbers = art.loadUncompressedPatterns(
                Sonic1Constants.ART_UNC_LIVES_NUMBERS_ADDR,
                Sonic1Constants.ART_UNC_LIVES_NUMBERS_SIZE);

        hudHexDigits = art.loadUncompressedPatterns(
                Sonic1Constants.ART_UNC_TEXT_ADDR,
                Sonic1Constants.ART_UNC_TEXT_SIZE);

        // Load lamppost art
        loadLamppostArt(art);

        // Load signpost art
        loadSignpostArt(art);

        // Load bridge art (GHZ) - also used by Scenery 0x1C subtype 3
        loadBridgeArt(art);

        // Load SLZ cannon art (fireball launcher base, used by Scenery 0x1C subtypes 0-2)
        loadSlzCannonArt(art);

        // Load GHZ edge wall art
        loadGhzEdgeWallArt(art);

        // Load purple rock art (GHZ)
        loadPurpleRockArt(art);

        // Load breakable wall art (GHZ/SLZ)
        loadBreakableWallArt(art, zoneIndex);

        // Load spike art
        loadSpikeArt(art);

        // Load monitor art
        loadMonitorArt(art);

        // Load explosion art (used by monitors and badniks)
        loadExplosionArt(art);

        // Load shield art
        loadShieldArt(art);
        loadInvincibilityStarsArt(art);

        // Load spring art (all zones)
        loadSpringArt(art);

        // Load dynamic points popups and escaped animals (zone-dependent)
        loadAnimalAndPointsArt(art, zoneIndex);

        // Load Buzz Bomber art (GHZ/MZ/SYZ badnik + missile + dissolve)
        loadBuzzBomberArt(art);

        // Load Crabmeat art (GHZ/SYZ badnik + projectile)
        loadCrabmeatArt(art);

        // Load Chopper art (GHZ badnik)
        loadChopperArt(art);

        // Load Motobug art (GHZ badnik + exhaust smoke)
        loadMotobugArt(art);

        // Load Newtron art (GHZ badnik - two subtypes: walking + missile-firing)
        loadNewtronArt(art);

        // Load Caterkiller art (MZ/SBZ badnik - segmented worm)
        loadCaterkillerArt(art);

        // Load Batbrain/Basaran art (MZ badnik - ceiling bat)
        loadBatbrainArt(art);

        // Load Yadrin art (SYZ badnik - spiky hedgehog)
        loadYadrinArt(art);

        // Load Roller art (SYZ badnik - rolling armadillo)
        loadRollerArt(art);

        // Load results screen art (reuses title card + HUD text)
        loadResultsScreenArt(art);

        // Load SS results emerald art (Nem_ResultEm)
        loadResultsEmeraldArt(art);

        registerSheet(ObjectArtKeys.GAME_OVER, art.loadGameOverSheet());

        // Load Giant Ring art (uncompressed, all zones)
        loadGiantRingArt(art);

        // Load Giant Ring Flash art (Nemesis, loaded with ring)
        loadGiantRingFlashArt(art);

        // Load swinging platform art (zone-dependent: GHZ/MZ, SLZ, SBZ, GHZ giant ball)
        loadSwingingPlatformArt(art, zoneIndex);

        // Load spiked pole helix art (GHZ only)
        if (zoneIndex == Sonic1Constants.ZONE_GHZ) {
            loadSpikedPoleHelixArt(art);
        }

        // Load hidden bonus art (end-of-act point popups, all zones)
        loadHiddenBonusArt(art);

        // Load prison capsule art (all zones - appears in every boss act)
        loadPrisonArt(art);

        // Load button/switch art (MZ, SYZ, LZ, SBZ)
        if (zoneIndex == Sonic1Constants.ZONE_MZ || zoneIndex == Sonic1Constants.ZONE_SYZ
                || zoneIndex == Sonic1Constants.ZONE_LZ || zoneIndex == Sonic1Constants.ZONE_SBZ) {
            loadButtonArt(art, zoneIndex);
        }

        // Load MZ-specific art (fireball, smash block, push block, glass block, moving block, collapsing floor)
        if (zoneIndex == Sonic1Constants.ZONE_MZ) {
            loadMzFireballArt(art);
            loadMzSmashBlockArt(art);
            loadMzPushBlockArt(art);
            loadMzGlassBlockArt(art);
            loadMzChainedStomperArt(art);
            loadMzMovingBlockArt(art);
            loadMzLavaGeyserArt(art);
            loadMzCollapsingFloorArt(art);
        }

        // Load SLZ-specific art (fan, pylon, fireball, collapsing floor, seesaw)
        if (zoneIndex == Sonic1Constants.ZONE_SLZ) {
            loadSlzFireballArt(art);
            loadSlzCollapsingFloorArt(art);
            loadSlzFanArt(art);
            loadSlzPylonArt(art);
            loadSlzSeesawArt(art);
            loadSlzSeesawBallArt(art);
        }

        // Load LZ-specific art (breakable pole, flapping door, waterfall, push block, moving block,
        // labyrinth block, conveyor, bubbles, jaws, burrobot, orbinaut, gargoyle, harpoon)
        if (zoneIndex == Sonic1Constants.ZONE_LZ) {
            loadLzPushBlockArt(art);
            loadLzFlappingDoorArt(art);
            loadLzWaterfallArt(art);
            loadLzSplashArt(art);
            loadLzMovingBlockArt(art);
            loadLabyrinthBlockArt(art);
            loadLzConveyorArt(art);
            loadBubblesArt(art);
            loadJawsArt(art);
            loadBurrobotArt(art);
            loadOrbinautArt(art, zoneIndex);
            loadGargoyleArt(art);
            loadHarpoonArt(art);
        }

        // Orbinaut appears in LZ/SLZ/SBZ with zone-specific palette usage.
        if (zoneIndex == Sonic1Constants.ZONE_SLZ || zoneIndex == Sonic1Constants.ZONE_SBZ) {
            loadOrbinautArt(art, zoneIndex);
        }

        // Bomb enemy appears in SLZ and SBZ
        if (zoneIndex == Sonic1Constants.ZONE_SLZ || zoneIndex == Sonic1Constants.ZONE_SBZ) {
            loadBombArt(art);
        }

        // Load SYZ-specific art (bumper, big spiked ball, small spikeball chain)
        if (zoneIndex == Sonic1Constants.ZONE_SYZ) {
            loadBumperArt(art);
            loadBigSpikedBallArt(art);
            loadSyzSpikeballChainArt(art);
        }

        // Load LZ-specific spikeball chain art
        if (zoneIndex == Sonic1Constants.ZONE_LZ) {
            loadLzSpikeballChainArt(art);
        }

        // Load SBZ-specific art (moving blocks - short stomper + long slide floor, collapsing floor, vanishing platform)
        if (zoneIndex == Sonic1Constants.ZONE_SBZ) {
            loadSbzMovingBlockShortArt(art);
            loadSbzMovingBlockLongArt(art);
            loadSbzCollapsingFloorArt(art);
            loadSbzVanishingPlatformArt(art);
            loadSbzTrapDoorArt(art);
            loadSbzSpinningPlatformArt(art);
            loadSbzSmallDoorArt(art);
            loadSbzElectrocuterArt(art);
            loadSbzFlamethrowerArt(art);
            loadSbzGirderArt(art);
            loadSbzSawArt(art);
            loadSbzStomperDoorArt(art);
            loadSbzRunningDiscArt(art);
            loadSbzJunctionArt(art);
            loadBallHogArt(art);
            loadSbz2EggmanArt(art);
            loadSbz2ButtonArt(art);
            loadSbz2FalseFloorArt(art);
        }

        // Load boss art (GHZ/MZ/SYZ/LZ/SLZ: Eggman, weapons/chain anchor, exhaust flame)
        if (zoneIndex == Sonic1Constants.ZONE_GHZ || zoneIndex == Sonic1Constants.ZONE_MZ
                || zoneIndex == Sonic1Constants.ZONE_SYZ || zoneIndex == Sonic1Constants.ZONE_LZ
                || zoneIndex == Sonic1Constants.ZONE_SLZ) {
            loadBossArt(art);
        }

        // Load FZ boss art (Final Zone: Eggman machine, cylinders, plasma)
        // ROM treats FZ as SBZ act 2 (resolveRomZoneAct maps level 0x92 to ZONE_SBZ, act 2)
        if (zoneIndex == Sonic1Constants.ZONE_SBZ) {
            loadFZBossArt(art);
        }

        currentZoneIndex = zoneIndex;
        LOGGER.info("Sonic1ObjectArtProvider loaded zone " + zoneIndex + ": digits=" +
                (hudDigitPatterns != null ? hudDigitPatterns.length : 0) +
                " text=" + (hudTextPatterns != null ? hudTextPatterns.length : 0) +
                " lives=" + (hudLivesPatterns != null ? hudLivesPatterns.length : 0) +
                " livesNums=" + (hudLivesNumbers != null ? hudLivesNumbers.length : 0) +
                " renderers=" + rendererKeys.size());
    }

    /**
     * Loads lamppost art (Nem_Lamp) with ROM-parsed S1 mappings (Map_Lamp).
     */
    private void loadLamppostArt(Sonic1ObjectArt art) {
        registerSheet(ObjectArtKeys.CHECKPOINT, art.buildArtSheetFromRom(
                Sonic1Constants.ART_NEM_LAMPPOST_ADDR, Sonic1Constants.MAP_LAMPPOST_ADDR, 0, 1));
    }

    /**
     * Loads signpost art (Nem_Sign) with ROM-parsed S1 mappings (Map_Sign).
     */
    private void loadSignpostArt(Sonic1ObjectArt art) {
        registerSheet(ObjectArtKeys.SIGNPOST, art.buildArtSheetFromRom(
                Sonic1Constants.ART_NEM_SIGNPOST_ADDR, Sonic1Constants.MAP_SIGNPOST_ADDR, 0, 1));
    }

    /**
     * Loads purple rock art (Nem_PplRock) with ROM-parsed S1 mappings (Map_PRock).
     */
    private void loadPurpleRockArt(Sonic1ObjectArt art) {
        registerSheet(ObjectArtKeys.ROCK, art.buildArtSheetFromRom(
                Sonic1Constants.ART_NEM_PURPLE_ROCK_ADDR, Sonic1Constants.MAP_PURPLE_ROCK_ADDR, 3, 1));
    }

    /**
     * Loads breakable wall art (Nem_GhzWall1 for GHZ, Nem_SlzWall for SLZ) with
     * ROM-parsed S1 mappings (Map_Smash).
     * <p>
     * GHZ uses patterns at offset 0, SLZ loads separate art at +4 offset.
     * Each section is 32x64 pixels (2 columns × 4 rows of 2×2 tile pieces).
     * <p>
     * Palette line 2 from disassembly: make_art_tile(ArtTile_GHZ_SLZ_Smashable_Wall,2,0)
     */
    private void loadBreakableWallArt(Sonic1ObjectArt art, int zoneIndex) {
        // GHZ uses Nem_GhzWall1, SLZ uses Nem_SlzWall (loaded at +4 tile offset)
        int artAddr = (zoneIndex == Sonic1Constants.ZONE_SLZ)
                ? Sonic1Constants.ART_NEM_SLZ_BREAKABLE_WALL_ADDR
                : Sonic1Constants.ART_NEM_GHZ_BREAKABLE_WALL_ADDR;

        Pattern[] patterns = art.loadNemesisPatterns(artAddr);
        if (patterns.length == 0) return;

        // SLZ art loads at ArtTile+4, so prepend 4 empty placeholder patterns
        if (zoneIndex == Sonic1Constants.ZONE_SLZ) {
            Pattern[] padded = new Pattern[patterns.length + 4];
            for (int i = 0; i < 4; i++) {
                padded[i] = new Pattern();
            }
            System.arraycopy(patterns, 0, padded, 4, patterns.length);
            patterns = padded;
        }

        List<SpriteMappingFrame> mappings = art.loadMappingFrames(Sonic1Constants.MAP_SMASH_ADDR);

        // Palette line 2 from disassembly: make_art_tile(ArtTile_GHZ_SLZ_Smashable_Wall,2,0)
        registerSheet(ObjectArtKeys.BREAKABLE_WALL,
                new ObjectSpriteSheet(patterns, mappings, 2, 1));
    }

    /**
     * Loads GHZ edge wall art (Nem_GhzWall2) with ROM-parsed S1 mappings (Map_Edge).
     * Palette line 2 from disassembly: make_art_tile(ArtTile_GHZ_Edge_Wall,2,0)
     */
    private void loadGhzEdgeWallArt(Sonic1ObjectArt art) {
        registerSheet(ObjectArtKeys.GHZ_EDGE_WALL, art.buildArtSheetFromRom(
                Sonic1Constants.ART_NEM_GHZ_EDGE_WALL_ADDR, Sonic1Constants.MAP_GHZ_EDGE_WALL_ADDR, 2, 1));
    }

    /**
     * Loads bridge art (Nem_Bridge) and creates S1-format sprite mappings.
     * Mappings from docs/s1disasm/_maps/Bridge.asm (Map_Bri).
     * <p>
     * Frame 0: Single log segment (2x2 tiles, 16x16 px)
     * Frame 1: Bridge stump with rope (2 pieces, used by Scenery 0x1C subtype 3)
     * Frame 2: Rope only (2x1 tiles, 16x8 px)
     */
    private void loadBridgeArt(Sonic1ObjectArt art) {
        registerSheet(ObjectArtKeys.BRIDGE, art.buildArtSheetFromRom(
                Sonic1Constants.ART_NEM_BRIDGE_ADDR, Sonic1Constants.MAP_BRIDGE_ADDR, 2, 1));
    }

    /**
     * Loads SLZ fan art (Nem_Fan) and creates S1-format sprite mappings.
     * Mappings from docs/s1disasm/_maps/Fan.asm (Map_Fan_internal).
     * <p>
     * Palette line 2 from disassembly: make_art_tile(ArtTile_SLZ_Fan,2,0).
     * 5 mapping frames (frames 0-1 facing left, frames 2-4 unused by standard subtypes
     * but included for completeness). The mapping table has 5 entries:
     * .fan1, .fan2, .fan3, .fan2, .fan1 (mirrored pattern for facing variants).
     * <p>
     * Subtype bit 0 selects frame offset: 0 = frames 0-2 (left), 1 = frames 2-4 (right variant).
     * The disassembly adds obAniFrame to a base of 0 or 2 depending on subtype bit 0.
     */
    /**
     * Loads SLZ seesaw art (Nem_Seesaw) and creates S1-format sprite mappings.
     * From Pattern Load Cues: plcm Nem_Seesaw, ArtTile_SLZ_Seesaw
     * Palette line 0 from disassembly: make_art_tile(ArtTile_SLZ_Seesaw,0,0)
     *
     * Mapping table has 4 entries but only 2 unique frames (sloping and flat):
     *   Frame 0 = .sloping (tilted left/right, 7 pieces)
     *   Frame 1 = .flat (level, 4 pieces)
     *   Frame 2 = .sloping (same as frame 0, but rendered with x-flip)
     *   Frame 3 = .flat (same as frame 1)
     */
    private void loadSlzSeesawArt(Sonic1ObjectArt art) {
        registerSheet(ObjectArtKeys.SLZ_SEESAW, art.buildArtSheetFromRom(
                Sonic1Constants.ART_NEM_SLZ_SEESAW_ADDR, Sonic1Constants.MAP_SLZ_SEESAW_ADDR, 0, 1));
    }

    /**
     * Loads SLZ seesaw spikeball art (Nem_SlzSpike) and creates S1-format sprite mappings.
     * From Pattern Load Cues: plcm Nem_SlzSpike, ArtTile_SLZ_Spikeball
     * Palette line 0: make_art_tile(ArtTile_SLZ_Spikeball,0,0)
     * Frame 0 (.red): 3x3 tile 0; Frame 1 (.silver): 3x3 tile 9
     */
    private void loadSlzSeesawBallArt(Sonic1ObjectArt art) {
        registerSheet(ObjectArtKeys.SLZ_SEESAW_BALL, art.buildArtSheetFromRom(
                Sonic1Constants.ART_NEM_SLZ_SPIKEBALL_ADDR, Sonic1Constants.MAP_SLZ_SEESAW_BALL_ADDR, 0, 1));
    }

    private void loadSlzFanArt(Sonic1ObjectArt art) {
        registerSheet(ObjectArtKeys.SLZ_FAN, art.buildArtSheetFromRom(
                Sonic1Constants.ART_NEM_SLZ_FAN_ADDR, Sonic1Constants.MAP_SLZ_FAN_ADDR, 2, 1));
    }

    /**
     * Loads SLZ foreground pylon art (Nem_Pylon) and creates S1-format sprite mappings.
     * The pylon is a tall decorative metal structure that renders in the foreground with
     * parallax scrolling in Star Light Zone.
     * <p>
     * Mappings from docs/s1disasm/_maps/Pylon.asm (Map_Pylon_internal).
     * <p>
     * Palette line 0, priority=1 from disassembly:
     * make_art_tile(ArtTile_SLZ_Pylon,0,1)
     */
    private void loadSlzPylonArt(Sonic1ObjectArt art) {
        registerSheet(ObjectArtKeys.SLZ_PYLON, art.buildArtSheetFromRom(
                Sonic1Constants.ART_NEM_SLZ_PYLON_ADDR, Sonic1Constants.MAP_SLZ_PYLON_ADDR, 0, 1));
    }

    /**
     * Loads SLZ fireball launcher / lava thrower art (Nem_SlzCannon) and creates
     * S1-format sprite mappings. Used by Scenery object 0x1C subtypes 0-2.
     * Mappings from docs/s1disasm/_maps/Scenery.asm (Map_Scen).
     * <p>
     * Palette line 2 from disassembly: make_art_tile(ArtTile_SLZ_Fireball_Launcher,2,0).
     * Single frame: 2x4 tiles (16x32 px) at offset (-8, -16).
     */
    private void loadSlzCannonArt(Sonic1ObjectArt art) {
        registerSheet(ObjectArtKeys.SCENERY, art.buildArtSheetFromRom(
                Sonic1Constants.ART_NEM_SLZ_CANNON_ADDR, Sonic1Constants.MAP_SCENERY_ADDR, 2, 1));
    }

    /**
     * Loads spike art (Nem_Spikes) with ROM-parsed S1 mappings (Map_Spike).
     * Palette line 0 from disassembly: make_art_tile(ArtTile_Spikes,0,0).
     */
    private void loadSpikeArt(Sonic1ObjectArt art) {
        registerSheet(ObjectArtKeys.SPIKE, art.buildArtSheetFromRom(
                Sonic1Constants.ART_NEM_SPIKES_ADDR, Sonic1Constants.MAP_SPIKE_ADDR, 0, 1));
    }

    /**
     * Loads monitor art (Nem_Monitors) and creates S1-format sprite mappings and animations.
     * Mappings from docs/s1disasm/_maps/Monitor.asm (Map_Monitor_internal).
     * Animations from docs/s1disasm/_anim/Monitor.asm (Ani_Monitor).
     */
    private void loadMonitorArt(Sonic1ObjectArt art) {
        ObjectSpriteSheet sheet = art.buildArtSheetFromRom(
                Sonic1Constants.ART_NEM_MONITOR_ADDR, Sonic1Constants.MAP_MONITOR_ADDR, 0, 1);
        registerSheet(ObjectArtKeys.MONITOR, sheet);
        animations.put(ObjectArtKeys.ANIM_MONITOR, createMonitorAnimations());
    }

    /**
     * Creates monitor animation scripts from S1 disassembly Ani_Monitor.
     * <p>
     * 10 animations (0-9): static, eggman, sonic, shoes, shield,
     * invincible, rings, s, goggles, breaking.
     * <p>
     * Non-static animations cycle: box→icon→icon→box-variant→icon→icon→box-variant→icon→icon
     */
    private SpriteAnimationSet createMonitorAnimations() {
        SpriteAnimationSet set = new SpriteAnimationSet();

        // Anim 0: .static - dc.b 1, 0, 1, 2, afEnd
        set.addScript(0, new SpriteAnimationScript(1,
                List.of(0, 1, 2), SpriteAnimationEndAction.LOOP, 0));

        // Anim 1: .eggman - dc.b 1, 0, 3, 3, 1, 3, 3, 2, 3, 3, afEnd
        set.addScript(1, new SpriteAnimationScript(1,
                List.of(0, 3, 3, 1, 3, 3, 2, 3, 3), SpriteAnimationEndAction.LOOP, 0));

        // Anim 2: .sonic - dc.b 1, 0, 4, 4, 1, 4, 4, 2, 4, 4, afEnd
        set.addScript(2, new SpriteAnimationScript(1,
                List.of(0, 4, 4, 1, 4, 4, 2, 4, 4), SpriteAnimationEndAction.LOOP, 0));

        // Anim 3: .shoes - dc.b 1, 0, 5, 5, 1, 5, 5, 2, 5, 5, afEnd
        set.addScript(3, new SpriteAnimationScript(1,
                List.of(0, 5, 5, 1, 5, 5, 2, 5, 5), SpriteAnimationEndAction.LOOP, 0));

        // Anim 4: .shield - dc.b 1, 0, 6, 6, 1, 6, 6, 2, 6, 6, afEnd
        set.addScript(4, new SpriteAnimationScript(1,
                List.of(0, 6, 6, 1, 6, 6, 2, 6, 6), SpriteAnimationEndAction.LOOP, 0));

        // Anim 5: .invincible - dc.b 1, 0, 7, 7, 1, 7, 7, 2, 7, 7, afEnd
        set.addScript(5, new SpriteAnimationScript(1,
                List.of(0, 7, 7, 1, 7, 7, 2, 7, 7), SpriteAnimationEndAction.LOOP, 0));

        // Anim 6: .rings - dc.b 1, 0, 8, 8, 1, 8, 8, 2, 8, 8, afEnd
        set.addScript(6, new SpriteAnimationScript(1,
                List.of(0, 8, 8, 1, 8, 8, 2, 8, 8), SpriteAnimationEndAction.LOOP, 0));

        // Anim 7: .s - dc.b 1, 0, 9, 9, 1, 9, 9, 2, 9, 9, afEnd
        set.addScript(7, new SpriteAnimationScript(1,
                List.of(0, 9, 9, 1, 9, 9, 2, 9, 9), SpriteAnimationEndAction.LOOP, 0));

        // Anim 8: .goggles - dc.b 1, 0, $A, $A, 1, $A, $A, 2, $A, $A, afEnd
        set.addScript(8, new SpriteAnimationScript(1,
                List.of(0, 10, 10, 1, 10, 10, 2, 10, 10), SpriteAnimationEndAction.LOOP, 0));

        // Anim 9: .breaking - dc.b 2, 0, 1, 2, $B, afBack, 1
        // Plays frames 0→1→2→11, then holds on frame 11 (broken shell)
        set.addScript(9, new SpriteAnimationScript(2,
                List.of(0, 1, 2, 11), SpriteAnimationEndAction.LOOP_BACK, 1));

        return set;
    }

    /**
     * Loads explosion art (Nem_Explode) and creates S1-format sprite mappings.
     * Mappings from docs/s1disasm/_maps/Explosions.asm (Map_ExplodeItem).
     * Used by monitor break, badnik destruction, etc.
     */
    private void loadExplosionArt(Sonic1ObjectArt art) {
        registerSheet(ObjectArtKeys.EXPLOSION, art.buildArtSheetFromRom(
                Sonic1Constants.ART_NEM_EXPLOSION_ADDR, Sonic1Constants.MAP_EXPLOSION_ADDR, 0, 1));
    }

    private void loadShieldArt(Sonic1ObjectArt art) {
        List<SpriteMappingFrame> shieldMappings = createShieldMappingsFromRom(art);
        registerSheet(ObjectArtKeys.SHIELD, art.buildArtSheet(
                Sonic1Constants.ART_NEM_SHIELD_ADDR, shieldMappings, 0, 1));
    }

    private List<SpriteMappingFrame> createShieldMappingsFromRom(Sonic1ObjectArt art) {
        List<SpriteMappingFrame> romFrames = art.loadMappingFrames(Sonic1Constants.MAP_SHIELD_ADDR, 8);
        if (romFrames.size() < 4) {
            return List.of();
        }

        List<SpriteMappingFrame> shieldFrames = new ArrayList<>(4);
        // Map_Shield's first pointer deliberately targets a zero byte inside .shield1,
        // producing the completely invisible frame used between the three full frames.
        shieldFrames.add(romFrames.get(0));
        shieldFrames.add(romFrames.get(1));
        shieldFrames.add(romFrames.get(2));
        shieldFrames.add(romFrames.get(3));
        return List.copyOf(shieldFrames);
    }

    private void loadInvincibilityStarsArt(Sonic1ObjectArt art) {
        List<SpriteMappingFrame> romFrames = art.loadMappingFrames(Sonic1Constants.MAP_SHIELD_ADDR, 8);
        List<SpriteMappingFrame> starMappings = romFrames.size() >= 8
                ? List.copyOf(romFrames.subList(4, 8))
                : List.of();
        registerSheet(ObjectArtKeys.INVINCIBILITY_STARS, art.buildArtSheet(
                Sonic1Constants.ART_NEM_INVINCIBILITY_STARS_ADDR,
                starMappings, 0, 1));
    }

    /**
     * Loads Sonic 1 points popups and zone-specific animal art.
     * <p>
     * S1 uses two animal art banks per zone (Anml_VarIndex), selected at runtime
     * when an enemy is destroyed.
     */
    private void loadAnimalAndPointsArt(Sonic1ObjectArt art, int zoneIndex) {
        AnimalType[] zoneAnimals = resolveZoneAnimals(zoneIndex);
        animalTypeA = zoneAnimals[0].ordinal();
        animalTypeB = zoneAnimals[1].ordinal();

        Pattern[] firstAnimalPatterns = loadAnimalPatterns(art, zoneAnimals[0]);
        Pattern[] secondAnimalPatterns = loadAnimalPatterns(art, zoneAnimals[1]);
        if (firstAnimalPatterns.length == 0 || secondAnimalPatterns.length == 0) {
            LOGGER.warning("Failed to load S1 animal art for zone " + zoneIndex);
            return;
        }

        Pattern[] combinedAnimals = createCombinedAnimalPatterns(firstAnimalPatterns, secondAnimalPatterns);
        registerSheet(ObjectArtKeys.ANIMAL,
                new ObjectSpriteSheet(combinedAnimals, createAnimalMappings(art), 0, 1));

        ObjectSpriteSheet endingAnimalSheet = createEndingAnimalSheet(art);
        registerSheet(ObjectArtKeys.ANIMAL_ENDING, endingAnimalSheet);

        registerSheet(ObjectArtKeys.END_SONIC, createEndingSonicSheet(art));
        registerSheet(ObjectArtKeys.END_EMERALDS, createEndingEmeraldsSheet(art));
        registerSheet(ObjectArtKeys.END_STH, createEndingSTHSheet(art));

        registerSheet(ObjectArtKeys.POINTS, art.buildArtSheet(
                Sonic1Constants.ART_NEM_POINTS_ADDR, createPointsMappingsFromRom(art), 1, 0));
    }

    private AnimalType[] resolveZoneAnimals(int zoneIndex) {
        if (zoneIndex < 0 || zoneIndex >= ZONE_ANIMALS.length) {
            return DEFAULT_ANIMALS;
        }
        return ZONE_ANIMALS[zoneIndex];
    }

    private Pattern[] loadAnimalPatterns(Sonic1ObjectArt art, AnimalType type) {
        int address = switch (type) {
            case RABBIT -> Sonic1Constants.ART_NEM_ANIMAL_RABBIT_ADDR;
            case CHICKEN -> Sonic1Constants.ART_NEM_ANIMAL_CHICKEN_ADDR;
            case PENGUIN -> Sonic1Constants.ART_NEM_ANIMAL_PENGUIN_ADDR;
            case SEAL -> Sonic1Constants.ART_NEM_ANIMAL_SEAL_ADDR;
            case PIG -> Sonic1Constants.ART_NEM_ANIMAL_PIG_ADDR;
            case FLICKY -> Sonic1Constants.ART_NEM_ANIMAL_FLICKY_ADDR;
            case SQUIRREL -> Sonic1Constants.ART_NEM_ANIMAL_SQUIRREL_ADDR;
            default -> -1;
        };
        if (address < 0) {
            return new Pattern[0];
        }
        return art.loadNemesisPatterns(address);
    }

    private Pattern[] createCombinedAnimalPatterns(Pattern[] first, Pattern[] second) {
        int firstLength = Math.max(ANIMAL_TILE_BANK_SIZE, first.length);
        int secondLength = Math.max(ANIMAL_TILE_BANK_SIZE, second.length);
        Pattern[] combined = new Pattern[firstLength + secondLength];

        for (int i = 0; i < combined.length; i++) {
            combined[i] = new Pattern();
        }
        for (int i = 0; i < first.length; i++) {
            combined[i] = first[i];
        }
        for (int i = 0; i < second.length; i++) {
            combined[firstLength + i] = second[i];
        }
        return combined;
    }

    private ObjectSpriteSheet createEndingAnimalSheet(Sonic1ObjectArt art) {
        Pattern[] patterns = createEndingAnimalPatterns(art);
        if (patterns.length == 0) {
            return null;
        }
        return new ObjectSpriteSheet(patterns, createEndingAnimalMappings(art), 0, 1);
    }

    private Pattern[] createEndingAnimalPatterns(Sonic1ObjectArt art) {
        Pattern[] combined = new Pattern[ENDING_ANIMAL_ORDER.length * ANIMAL_TILE_BANK_SIZE];
        for (int i = 0; i < combined.length; i++) {
            combined[i] = new Pattern();
        }

        for (int i = 0; i < ENDING_ANIMAL_ORDER.length; i++) {
            Pattern[] speciesPatterns = loadAnimalPatterns(art, ENDING_ANIMAL_ORDER[i]);
            int copyLength = Math.min(ANIMAL_TILE_BANK_SIZE, speciesPatterns.length);
            int destination = i * ANIMAL_TILE_BANK_SIZE;
            if (copyLength > 0) {
                System.arraycopy(speciesPatterns, 0, combined, destination, copyLength);
            }
        }
        return combined;
    }

    private List<SpriteMappingFrame> createEndingAnimalMappings(Sonic1ObjectArt art) {
        List<SpriteMappingFrame> frames = new ArrayList<>();
        for (int i = 0; i < ENDING_ANIMAL_ORDER.length; i++) {
            int tileOffset = i * ANIMAL_TILE_BANK_SIZE;
            frames.addAll(loadAnimalMappingFrames(art, ENDING_ANIMAL_ORDER[i].mappingSet(), tileOffset));
        }
        return List.copyOf(frames);
    }

    private List<SpriteMappingFrame> createAnimalMappings(Sonic1ObjectArt art) {
        List<SpriteMappingFrame> frames = new ArrayList<>();
        AnimalType.MappingSet[] sets = {
                AnimalType.MappingSet.A,
                AnimalType.MappingSet.B,
                AnimalType.MappingSet.C,
                AnimalType.MappingSet.D,
                AnimalType.MappingSet.E
        };

        // Frame order matches AnimalObjectInstance.getFrameIndex():
        // ((mappingSet * 2) + artVariant) * 3 + animFrame
        for (AnimalType.MappingSet mappingSet : sets) {
            frames.addAll(loadAnimalMappingFrames(art, mappingSet, 0));
            frames.addAll(loadAnimalMappingFrames(art, mappingSet, ANIMAL_TILE_BANK_SIZE));
        }
        return List.copyOf(frames);
    }

    private List<SpriteMappingFrame> loadAnimalMappingFrames(
            Sonic1ObjectArt art, AnimalType.MappingSet mappingSet, int tileOffset) {
        int mappingAddr = switch (mappingSet) {
            case A, D -> Sonic1Constants.MAP_ANIMAL2_ADDR;
            case B, C -> Sonic1Constants.MAP_ANIMAL3_ADDR;
            case E -> Sonic1Constants.MAP_ANIMAL1_ADDR;
        };
        return art.loadMappingFramesWithTileOffset(mappingAddr, 3, tileOffset);
    }

    private List<SpriteMappingFrame> createPointsMappingsFromRom(Sonic1ObjectArt art) {
        List<SpriteMappingFrame> romFrames = art.loadMappingFrames(Sonic1Constants.MAP_POINTS_ADDR);
        if (romFrames.size() < 5) {
            return List.of();
        }

        List<SpriteMappingFrame> frames = new ArrayList<>(6);
        frames.addAll(romFrames.subList(0, 5));
        frames.add(romFrames.get(3));
        return List.copyOf(frames);
    }

    /**
     * Loads results screen art by compositing title card patterns + HUD text patterns
     * into a single VRAM-aligned pattern array, matching the original S1 layout.
     * <p>
     * VRAM layout:
     * <ul>
     *   <li>$570-$57F (indices 0-15): Writable bonus digit slots (time + ring bonus)</li>
     *   <li>$580+ (indices 16+): Title card art (Nem_TitleCard)</li>
     *   <li>$6CA+ (indices 0x15A+): HUD text art (Nem_Hud: SCORE/TIME/RINGS labels)</li>
     * </ul>
     * <p>
     * Mapping tile IDs are relative to ArtTile_Title_Card ($580).
     * Array index = tile_id + RESULTS_TILE_ADJUST (0x10).
     */
    private void loadResultsScreenArt(Sonic1ObjectArt art) {
        Pattern[] titleCardPatterns = art.loadNemesisPatterns(
                Sonic1Constants.ART_NEM_TITLE_CARD_ADDR);
        if (titleCardPatterns.length == 0) {
            LOGGER.warning("Failed to load title card art for results screen");
            return;
        }
        if (hudTextPatterns == null || hudTextPatterns.length == 0) {
            LOGGER.warning("HUD text patterns not available for results screen");
            return;
        }

        // HUD text starts at VRAM $6CA. Results mappings also reference
        // $6E2-$6F1 for score digits/trailing zero (loaded from Art_Hud).
        int hudTextStartIndex = Sonic1Constants.VRAM_RESULTS_HUD_TEXT - Sonic1Constants.VRAM_RESULTS_BASE;
        int hudScoreDigitsStartIndex =
                (Sonic1Constants.VRAM_RESULTS_HUD_TEXT + 0x18) - Sonic1Constants.VRAM_RESULTS_BASE;
        int totalSize = Math.max(
                hudTextStartIndex + hudTextPatterns.length,
                hudScoreDigitsStartIndex + RESULTS_SCORE_DIGIT_TILE_COUNT);

        Pattern[] compositePatterns = new Pattern[totalSize];

        // Fill with blank patterns
        for (int i = 0; i < totalSize; i++) {
            compositePatterns[i] = new Pattern();
        }

        // Copy title card patterns at index RESULTS_TILE_ADJUST (0x10)
        int titleCardStart = Sonic1Constants.RESULTS_TILE_ADJUST;
        for (int i = 0; i < titleCardPatterns.length && (titleCardStart + i) < totalSize; i++) {
            compositePatterns[titleCardStart + i] = titleCardPatterns[i];
        }

        // Copy HUD text patterns at hudTextStartIndex
        for (int i = 0; i < hudTextPatterns.length && (hudTextStartIndex + i) < totalSize; i++) {
            compositePatterns[hudTextStartIndex + i] = hudTextPatterns[i];
        }
        copyResultsScoreDigitTiles(compositePatterns, hudScoreDigitsStartIndex);

        List<SpriteMappingFrame> mappings = Sonic1ResultsMappingLoader.load(romReader);
        ObjectSpriteSheet sheet = new ObjectSpriteSheet(compositePatterns, mappings, 0, 1);
        registerSheet(ObjectArtKeys.RESULTS, sheet);

        LOGGER.info("Results screen art loaded: " + totalSize + " composite patterns, "
                + mappings.size() + " frames");
    }

    /**
     * Loads the Sonic 1 special-stage results emerald art (Nem_ResultEm).
     * Creates 7 mapping frames matching Map_SSRC from Obj7F:
     * frames 0-5 are the 6 emerald colors, frame 6 is blank (flash toggle).
     */
    private void loadResultsEmeraldArt(Sonic1ObjectArt art) {
        Pattern[] emeraldPatterns = art.loadNemesisPatterns(
                Sonic1Constants.ART_NEM_SS_RESULT_EM_ADDR);
        if (emeraldPatterns.length == 0) {
            LOGGER.warning("Failed to load SS results emerald art");
            return;
        }

        List<SpriteMappingFrame> frames = art.loadMappingFrames(Sonic1Constants.MAP_SS_RESULT_EMERALDS_ADDR);

        ObjectSpriteSheet sheet = new ObjectSpriteSheet(emeraldPatterns, frames, 0, 1);
        registerSheet(ObjectArtKeys.SS_RESULTS_EMERALDS, sheet);

        LOGGER.info("SS results emerald art loaded: " + emeraldPatterns.length
                + " patterns, " + frames.size() + " frames");
    }

    private void copyResultsScoreDigitTiles(Pattern[] dest, int startIndex) {
        if (dest == null || hudDigitPatterns == null || hudDigitPatterns.length < 2) {
            return;
        }

        // Tile pair $6E2-$6E3 is "E" (from Nem_Hud), followed by six "0" pairs.
        // The final pair at $6F0-$6F1 is forced blank; results mappings use it as trailing blank.
        copyPatternPair(dest, startIndex, hudTextPatterns, HUD_TEXT_E_PAIR_INDEX);
        for (int pair = 1; pair < RESULTS_SCORE_DIGIT_PAIR_COUNT - 1; pair++) {
            copyPatternPair(dest, startIndex + (pair * 2), hudDigitPatterns, 0);
        }
        // Explicitly clear trailing pair in case HUD source art has non-blank data there.
        int trailingPairIndex = startIndex + ((RESULTS_SCORE_DIGIT_PAIR_COUNT - 1) * 2);
        if (trailingPairIndex >= 0 && trailingPairIndex + 1 < dest.length) {
            dest[trailingPairIndex].copyFrom(new Pattern());
            dest[trailingPairIndex + 1].copyFrom(new Pattern());
        }
    }

    private void copyPatternPair(Pattern[] dest, int destIndex, Pattern[] src, int srcIndex) {
        if (src == null || srcIndex < 0 || srcIndex + 1 >= src.length) {
            return;
        }
        if (destIndex < 0 || destIndex + 1 >= dest.length) {
            return;
        }
        if (dest[destIndex] == null) {
            dest[destIndex] = new Pattern();
        }
        if (dest[destIndex + 1] == null) {
            dest[destIndex + 1] = new Pattern();
        }
        dest[destIndex].copyFrom(src[srcIndex]);
        dest[destIndex + 1].copyFrom(src[srcIndex + 1]);
    }

    /**
     * Loads spring art (Nem_HSpring + Nem_VSpring) and creates sprite sheets and animations.
     * <p>
     * S1 springs use two separate art sets:
     * <ul>
     *   <li>Nem_HSpring (horizontal plate) - used for up/down springs (3 frames: idle, flat, extended)</li>
     *   <li>Nem_VSpring (vertical plate) - used for left/right springs (3 frames: idle, flat, extended)</li>
     * </ul>
     * <p>
     * Red springs use palette line 0, yellow springs use palette line 1
     * (from disassembly: bset #5,obGfx for yellow).
     * <p>
     * Mappings from docs/s1disasm/_maps/Springs.asm (Map_Spring_internal).
     * Animations from docs/s1disasm/_anim/Springs.asm (Ani_Spring).
     */
    private void loadSpringArt(Sonic1ObjectArt art) {
        List<SpriteMappingFrame> springMappings = art.loadMappingFrames(Sonic1Constants.MAP_SPRING_ADDR);
        List<SpriteMappingFrame> verticalMappings = List.copyOf(springMappings.subList(0, 3));
        List<SpriteMappingFrame> horizontalMappings = List.copyOf(springMappings.subList(3, 6));

        // Load horizontal spring art (for up/down springs)
        Pattern[] hPatterns = art.loadNemesisPatterns(
                Sonic1Constants.ART_NEM_HSPRING_ADDR);
        if (hPatterns.length > 0) {
            // Red up/down springs: palette 0
            ObjectSpriteSheet vSheet = new ObjectSpriteSheet(hPatterns, verticalMappings, 0, 1);
            registerSheet(ObjectArtKeys.SPRING_VERTICAL, vSheet);
            // Yellow up/down springs: palette 1
            ObjectSpriteSheet vSheetYellow = new ObjectSpriteSheet(hPatterns, verticalMappings, 1, 1);
            registerSheet(ObjectArtKeys.SPRING_VERTICAL_RED, vSheetYellow);
        } else {
            LOGGER.warning("Failed to load horizontal spring art (Nem_HSpring)");
        }

        // Load vertical spring art (for left/right springs)
        Pattern[] vPatterns = art.loadNemesisPatterns(
                Sonic1Constants.ART_NEM_VSPRING_ADDR);
        if (vPatterns.length > 0) {
            // Red left/right springs: palette 0
            ObjectSpriteSheet hSheet = new ObjectSpriteSheet(vPatterns, horizontalMappings, 0, 1);
            registerSheet(ObjectArtKeys.SPRING_HORIZONTAL, hSheet);
            // Yellow left/right springs: palette 1
            ObjectSpriteSheet hSheetYellow = new ObjectSpriteSheet(vPatterns, horizontalMappings, 1, 1);
            registerSheet(ObjectArtKeys.SPRING_HORIZONTAL_RED, hSheetYellow);
        } else {
            LOGGER.warning("Failed to load vertical spring art (Nem_VSpring)");
        }

        // Register spring animation scripts
        SpriteAnimationSet springAnims = createSpringAnimations();
        animations.put(ObjectArtKeys.ANIM_SPRING, springAnims);
    }

    /**
     * Creates spring animation scripts from S1 disassembly Ani_Spring.
     * <p>
     * Since vertical and horizontal sheets both use the same frame indices (0-2),
     * only two animation IDs are needed: idle (hold on frame 0) and triggered.
     * <p>
     * Ani_Spring anim 0 (vertical trigger): speed=0, frames [1,0,0,2,2,2,2,2,2,0], afRoutine
     * Ani_Spring anim 1 (horizontal trigger): speed=0, frames [1,0,0,2,2,2,2,2,2,0], afRoutine
     * Both are structurally identical with re-indexed frames.
     */
    private SpriteAnimationSet createSpringAnimations() {
        SpriteAnimationSet set = new SpriteAnimationSet();

        // Anim 0: Idle - hold on frame 0
        set.addScript(0, new SpriteAnimationScript(0,
                List.of(0), SpriteAnimationEndAction.HOLD, 0));

        // Anim 1: Triggered - spring bounce animation, then switch back to idle
        // From disassembly: dc.b 0, 1, 0, 0, 2, 2, 2, 2, 2, 2, 0, afRoutine
        // afRoutine increments obRoutine, which resets to idle state.
        // In our engine: SWITCH to anim 0 (idle) when complete.
        set.addScript(1, new SpriteAnimationScript(0,
                List.of(1, 0, 0, 2, 2, 2, 2, 2, 2, 0), SpriteAnimationEndAction.SWITCH, 0));

        return set;
    }

    /**
     * Loads Crabmeat art (Nem_Crabmeat) and creates sprite sheet.
     * Crabmeat and its projectiles share the same art tile set (ArtTile_Crabmeat = $400).
     * Mappings from docs/s1disasm/_maps/Crabmeat.asm (Map_Crab_internal).
     * <p>
     * 7 mapping frames:
     * 0 (.stand): Standing/idle - 4 pieces (symmetric, right half is h-flipped left)
     * 1 (.walk): Walking - 4 pieces
     * 2 (.slope1): Walking on slope - 4 pieces
     * 3 (.slope2): Walking on slope (other leg) - 4 pieces
     * 4 (.firing): Firing projectiles - 6 pieces (symmetric)
     * 5 (.ball1): Projectile frame 1 - 1 piece (16x16)
     * 6 (.ball2): Projectile frame 2 - 1 piece (16x16)
     */
    private void loadCrabmeatArt(Sonic1ObjectArt art) {
        Pattern[] patterns = art.loadNemesisPatterns(
                Sonic1Constants.ART_NEM_CRABMEAT_ADDR);
        if (patterns.length == 0) {
            LOGGER.warning("Failed to load Crabmeat art");
            return;
        }

        List<SpriteMappingFrame> mappings = art.loadMappingFrames(Sonic1Constants.MAP_CRABMEAT_ADDR);
        ObjectSpriteSheet sheet = new ObjectSpriteSheet(patterns, mappings, 0, 1);
        registerSheet(ObjectArtKeys.CRABMEAT, sheet);
    }

    /**
     * Loads Chopper art (Nem_Chopper) and creates S1-format sprite mappings.
     * Mappings from docs/s1disasm/_maps/Chopper.asm (Map_Chop_internal).
     * 2 frames: mouth shut (frame 0) and mouth open (frame 1).
     * Each frame is a single 4x4 (32x32 pixel) sprite piece.
     */
    private void loadChopperArt(Sonic1ObjectArt art) {
        registerSheet(ObjectArtKeys.CHOPPER, art.buildArtSheetFromRom(
                Sonic1Constants.ART_NEM_CHOPPER_ADDR, Sonic1Constants.MAP_CHOPPER_ADDR, 0, 1));
    }

    /**
     * Loads Jaws art (Nem_Jaws) with ROM-parsed S1 mappings (Map_Jaws).
     */
    private void loadJawsArt(Sonic1ObjectArt art) {
        Pattern[] patterns = art.loadNemesisPatterns(
                Sonic1Constants.ART_NEM_JAWS_ADDR);
        if (patterns.length == 0) {
            LOGGER.warning("Failed to load Jaws art");
            return;
        }

        List<SpriteMappingFrame> mappings = art.loadMappingFrames(Sonic1Constants.MAP_JAWS_ADDR);
        // make_art_tile(ArtTile_Jaws, 1, 0) -> palette line 1, priority 0
        ObjectSpriteSheet sheet = new ObjectSpriteSheet(patterns, mappings, 1, 1);
        registerSheet(ObjectArtKeys.JAWS, sheet);
    }

    /**
     * Loads Burrobot art (Nem_Burrobot) with ROM-parsed S1 mappings (Map_Burro).
     */
    private void loadBurrobotArt(Sonic1ObjectArt art) {
        Pattern[] patterns = art.loadNemesisPatterns(
                Sonic1Constants.ART_NEM_BURROBOT_ADDR);
        if (patterns.length == 0) {
            LOGGER.warning("Failed to load Burrobot art");
            return;
        }

        List<SpriteMappingFrame> mappings = art.loadMappingFrames(Sonic1Constants.MAP_BURROBOT_ADDR);
        ObjectSpriteSheet sheet = new ObjectSpriteSheet(patterns, mappings, 0, 1);
        registerSheet(ObjectArtKeys.BURROBOT, sheet);
    }

    /**
     * Loads Orbinaut art (Nem_Orbinaut) and creates S1 mappings from Map_Orb.
     * LZ/SBZ use palette 0, SLZ uses palette 1.
     */
    private void loadOrbinautArt(Sonic1ObjectArt art, int zoneIndex) {
        Pattern[] patterns = art.loadNemesisPatterns(
                Sonic1Constants.ART_NEM_ORBINAUT_ADDR);
        if (patterns.length == 0) {
            LOGGER.warning("Failed to load Orbinaut art");
            return;
        }

        int paletteLine = zoneIndex == Sonic1Constants.ZONE_SLZ ? 1 : 0;
        List<SpriteMappingFrame> mappings = art.loadMappingFrames(Sonic1Constants.MAP_ORBINAUT_ADDR);
        ObjectSpriteSheet sheet = new ObjectSpriteSheet(patterns, mappings, paletteLine, 1);
        registerSheet(ObjectArtKeys.ORBINAUT, sheet);
    }

    /**
     * Loads LZ flapping door art (Nem_FlapDoor) with ROM-parsed S1 mappings (Map_Flap).
     */
    private void loadLzFlappingDoorArt(Sonic1ObjectArt art) {
        Pattern[] patterns = art.loadNemesisPatterns(
                Sonic1Constants.ART_NEM_LZ_FLAP_DOOR_ADDR);
        if (patterns.length == 0) {
            LOGGER.warning("Failed to load LZ flapping door art");
            return;
        }

        List<SpriteMappingFrame> mappings = art.loadMappingFrames(Sonic1Constants.MAP_LZ_FLAPPING_DOOR_ADDR);
        ObjectSpriteSheet sheet = new ObjectSpriteSheet(patterns, mappings, 2, 1);
        registerSheet(ObjectArtKeys.LZ_FLAPPING_DOOR, sheet);
    }

    /**
     * Loads LZ waterfall/splash art (Nem_Splash) with ROM-parsed S1 mappings (Map_WFall).
     */
    private void loadLzWaterfallArt(Sonic1ObjectArt art) {
        Pattern[] patterns = art.loadNemesisPatterns(
                Sonic1Constants.ART_NEM_LZ_SPLASH_ADDR);
        if (patterns.length == 0) {
            LOGGER.warning("Failed to load LZ waterfall art");
            return;
        }

        List<SpriteMappingFrame> mappings = art.loadMappingFrames(Sonic1Constants.MAP_LZ_WATERFALL_ADDR);
        ObjectSpriteSheet sheet = new ObjectSpriteSheet(patterns, mappings, 2, 1);
        registerSheet(ObjectArtKeys.LZ_WATERFALL, sheet);
    }

    /**
     * Loads LZ water splash art (Object 0x08 - Map_Splash).
     * Shares Nem_Splash patterns with the waterfall but uses separate mappings.
     * Palette line 2, priority 0 (make_art_tile(ArtTile_LZ_Splash, 2, 0)).
     */
    private void loadLzSplashArt(Sonic1ObjectArt art) {
        Pattern[] patterns = art.loadNemesisPatterns(
                Sonic1Constants.ART_NEM_LZ_SPLASH_ADDR);
        if (patterns.length == 0) {
            LOGGER.warning("Failed to load LZ splash art");
            return;
        }

        List<SpriteMappingFrame> mappings = art.loadMappingFrames(Sonic1Constants.MAP_LZ_SPLASH_ADDR);
        ObjectSpriteSheet sheet = new ObjectSpriteSheet(patterns, mappings, 2, 1);
        registerSheet(ObjectArtKeys.LZ_SPLASH, sheet);
    }

    /**
     * Loads LZ Gargoyle head and fireball art (Nem_Gargoyle).
     * Mappings from docs/s1disasm/_maps/Gargoyle.asm (Map_Gar_internal).
     * 4 frames: 0-1 head (palette 2), 2-3 fireball (palette 0).
     */
    private void loadGargoyleArt(Sonic1ObjectArt art) {
        Pattern[] patterns = art.loadNemesisPatterns(
                Sonic1Constants.ART_NEM_LZ_GARGOYLE_ADDR);
        if (patterns.length == 0) {
            LOGGER.warning("Failed to load LZ gargoyle art");
            return;
        }
        List<SpriteMappingFrame> mappings = art.loadMappingFrames(Sonic1Constants.MAP_LZ_GARGOYLE_ADDR);
        ObjectSpriteSheet sheet = new ObjectSpriteSheet(patterns, mappings, 2, 0);
        registerSheet(ObjectArtKeys.LZ_GARGOYLE, sheet);
    }

    /**
     * Loads LZ Harpoon spike trap art (Nem_Harpoon) with ROM-parsed S1 mappings (Map_Harp).
     */
    private void loadHarpoonArt(Sonic1ObjectArt art) {
        Pattern[] patterns = art.loadNemesisPatterns(
                Sonic1Constants.ART_NEM_LZ_HARPOON_ADDR);
        if (patterns.length == 0) {
            LOGGER.warning("Failed to load LZ harpoon art");
            return;
        }
        List<SpriteMappingFrame> mappings = art.loadMappingFrames(Sonic1Constants.MAP_LZ_HARPOON_ADDR);
        // make_art_tile(ArtTile_LZ_Harpoon, 0, 0) -> palette line 0, priority 0
        ObjectSpriteSheet sheet = new ObjectSpriteSheet(patterns, mappings, 0, 1);
        registerSheet(ObjectArtKeys.LZ_HARPOON, sheet);
    }

    /**
     * Loads Motobug art (Nem_Motobug) and creates S1-format sprite mappings.
     * Mappings from docs/s1disasm/_maps/Moto Bug.asm (Map_Moto_internal).
     * 7 frames: 3 body frames (walk cycle), 3 smoke frames, 1 blank frame.
     */
    private void loadMotobugArt(Sonic1ObjectArt art) {
        Pattern[] patterns = art.loadNemesisPatterns(
                Sonic1Constants.ART_NEM_MOTOBUG_ADDR);
        if (patterns.length == 0) {
            LOGGER.warning("Failed to load Motobug art");
            return;
        }

        List<SpriteMappingFrame> mappings = art.loadMappingFrames(Sonic1Constants.MAP_MOTOBUG_ADDR);
        ObjectSpriteSheet sheet = new ObjectSpriteSheet(patterns, mappings, 0, 1);
        registerSheet(ObjectArtKeys.MOTOBUG, sheet);
    }

    /**
     * Loads Newtron art (Nem_Newtron) and creates S1-format sprite mappings.
     * Mappings from docs/s1disasm/_maps/Newtron.asm (Map_Newt_internal).
     * 11 frames: Trans, Norm, Fires, Drop1-3, Fly1a/b, Fly2a/b, Blank.
     */
    private void loadNewtronArt(Sonic1ObjectArt art) {
        Pattern[] patterns = art.loadNemesisPatterns(
                Sonic1Constants.ART_NEM_NEWTRON_ADDR);
        if (patterns.length == 0) {
            LOGGER.warning("Failed to load Newtron art");
            return;
        }

        List<SpriteMappingFrame> mappings = art.loadMappingFrames(Sonic1Constants.MAP_NEWTRON_ADDR);
        ObjectSpriteSheet sheet = new ObjectSpriteSheet(patterns, mappings, 0, 1);
        registerSheet(ObjectArtKeys.NEWTRON, sheet);
    }

    /**
     * Loads Caterkiller art (Nem_Cater) and S1-format sprite mappings.
     * Mappings loaded from Map_Cat.
     * 24 frames total:
     *   Frames 0-7: Head at various Y offsets (bobbing animation)
     *   Frames 8-15: Body segment at various Y offsets
     *   Frames 16-23: Body segment with legs (alternate art at tile $6)
     */
    private void loadCaterkillerArt(Sonic1ObjectArt art) {
        Pattern[] patterns = art.loadNemesisPatterns(
                Sonic1Constants.ART_NEM_CATERKILLER_ADDR);
        if (patterns.length == 0) {
            LOGGER.warning("Failed to load Caterkiller art");
            return;
        }

        List<SpriteMappingFrame> mappings = art.loadMappingFrames(Sonic1Constants.MAP_CATERKILLER_ADDR);
        ObjectSpriteSheet sheet = new ObjectSpriteSheet(patterns, mappings, 1, 1);
        registerSheet(ObjectArtKeys.CATERKILLER, sheet);
    }

    /**
     * Loads button/switch art and creates S1-format sprite mappings.
     * <p>
     * MZ uses Nem_MzSwitch (palette 2), LZ/SYZ/SBZ use Nem_LzSwitch (palette 0).
     * MZ PLC loads at ArtTile_Button+4; non-MZ PLCs load at ArtTile_Button.
     * The object always references ArtTile_Button+4, so non-MZ art needs a 4-tile skip.
     * <p>
     * Reference: docs/s1disasm/_incObj/32 Button.asm (But_Main)
     * Mappings: docs/s1disasm/_maps/Button.asm (Map_But_internal)
     */
    private void loadButtonArt(Sonic1ObjectArt art, int zoneIndex) {
        int artAddr;
        int paletteIndex;
        String artName;

        if (zoneIndex == Sonic1Constants.ZONE_MZ) {
            // MZ: make_art_tile(ArtTile_Button+4,2,0) — palette line 2
            artAddr = Sonic1Constants.ART_NEM_MZ_SWITCH_ADDR;
            paletteIndex = 2;
            artName = "MzSwitch";
        } else {
            // SYZ/LZ/SBZ: make_art_tile(ArtTile_Button+4,0,0) — palette line 0
            artAddr = Sonic1Constants.ART_NEM_LZ_SWITCH_ADDR;
            paletteIndex = 0;
            artName = "LzSwitch";
        }

        Pattern[] patterns = art.loadNemesisPatterns(artAddr);
        if (patterns.length == 0) {
            LOGGER.warning("Failed to load button art (" + artName + ")");
            return;
        }

        // Non-MZ zones load Nem_LzSwitch at ArtTile_Button (PLC offset +0), but the
        // object references ArtTile_Button+4 via make_art_tile. Skip the first 4 tiles
        // so sprite sheet tile indices align with the mapping frames.
        // MZ loads Nem_MzSwitch directly at ArtTile_Button+4 so no skip is needed.
        if (zoneIndex != Sonic1Constants.ZONE_MZ && patterns.length > 4) {
            patterns = Arrays.copyOfRange(patterns, 4, patterns.length);
        }

        List<SpriteMappingFrame> mappings = art.loadMappingFrames(Sonic1Constants.MAP_BUTTON_ADDR);
        if (!mappings.isEmpty()) {
            ObjectSpriteSheet sheet = new ObjectSpriteSheet(patterns, mappings, paletteIndex, 1);
            registerSheet(ObjectArtKeys.BUTTON, sheet);
        }
    }

    /**
     * Loads Batbrain/Basaran art (Nem_Basaran) and creates S1-format sprite mappings.
     * Mappings from docs/s1disasm/_maps/Basaran.asm (Map_Bas_internal).
     * 4 frames: still (hanging from ceiling), fly1, fly2, fly3.
     * <p>
     * From disassembly: make_art_tile(ArtTile_Basaran,0,1) - palette 0, priority bit set.
     */
    private void loadBatbrainArt(Sonic1ObjectArt art) {
        Pattern[] patterns = art.loadNemesisPatterns(
                Sonic1Constants.ART_NEM_BASARAN_ADDR);
        if (patterns.length == 0) {
            LOGGER.warning("Failed to load Batbrain art");
            return;
        }

        List<SpriteMappingFrame> mappings = art.loadMappingFrames(Sonic1Constants.MAP_BASARAN_ADDR);
        // make_art_tile(ArtTile_Basaran, 0, 1) - palette line 0
        ObjectSpriteSheet sheet = new ObjectSpriteSheet(patterns, mappings, 0, 1);
        registerSheet(ObjectArtKeys.BATBRAIN, sheet);
    }

    /**
     * Loads Bomb enemy art (Nem_Bomb) and creates S1-format sprite mappings.
     * Mappings from docs/s1disasm/_maps/Bomb Enemy.asm (Map_Bomb_internal).
     * 12 frames: stand1-2, walk1-4, activate1-2, fuse1-2, shrapnel1-2.
     * <p>
     * From disassembly: make_art_tile(ArtTile_Bomb,0,0) - palette 0, no priority bit.
     */
    private void loadBombArt(Sonic1ObjectArt art) {
        Pattern[] patterns = art.loadNemesisPatterns(
                Sonic1Constants.ART_NEM_BOMB_ADDR);
        if (patterns.length == 0) {
            LOGGER.warning("Failed to load Bomb art");
            return;
        }

        List<SpriteMappingFrame> mappings = art.loadMappingFrames(Sonic1Constants.MAP_BOMB_ADDR);
        ObjectSpriteSheet sheet = new ObjectSpriteSheet(patterns, mappings, 0, 1);
        registerSheet(ObjectArtKeys.BOMB, sheet);
    }

    /**
     * Loads Ball Hog art (Nem_BallHog) and S1-format sprite mappings.
     * Mappings loaded from Map_Hog.
     * 6 frames: Stand, Open, Squat, Leap, Ball1, Ball2.
     * The Ball Hog and its cannonball share the same sprite sheet.
     * <p>
     * From disassembly: make_art_tile(ArtTile_Ball_Hog,1,0) - palette 1, no priority bit.
     */
    private void loadBallHogArt(Sonic1ObjectArt art) {
        Pattern[] patterns = art.loadNemesisPatterns(
                Sonic1Constants.ART_NEM_BALL_HOG_ADDR);
        if (patterns.length == 0) {
            LOGGER.warning("Failed to load Ball Hog art");
            return;
        }

        List<SpriteMappingFrame> mappings = art.loadMappingFrames(Sonic1Constants.MAP_BALL_HOG_ADDR);
        // make_art_tile(ArtTile_Ball_Hog, 1, 0) - palette line 1
        ObjectSpriteSheet sheet = new ObjectSpriteSheet(patterns, mappings, 1, 1);
        registerSheet(ObjectArtKeys.BALL_HOG, sheet);
    }

    /**
     * Loads Yadrin art (Nem_Yadrin) and creates S1-format sprite mappings.
     * Mappings from docs/s1disasm/_maps/Yadrin.asm (Map_Yad_internal).
     * 6 frames: walk0-walk5, used in two animations (stand + walk).
     * <p>
     * From disassembly: make_art_tile(ArtTile_Yadrin,1,0) - palette 1, no priority bit.
     */
    private void loadYadrinArt(Sonic1ObjectArt art) {
        Pattern[] patterns = art.loadNemesisPatterns(
                Sonic1Constants.ART_NEM_YADRIN_ADDR);
        if (patterns.length == 0) {
            LOGGER.warning("Failed to load Yadrin art");
            return;
        }

        List<SpriteMappingFrame> mappings = art.loadMappingFrames(Sonic1Constants.MAP_YADRIN_ADDR);
        // make_art_tile(ArtTile_Yadrin, 1, 0) - palette line 1
        ObjectSpriteSheet sheet = new ObjectSpriteSheet(patterns, mappings, 1, 1);
        registerSheet(ObjectArtKeys.YADRIN, sheet);
    }

    /**
     * Loads Roller art (Nem_Roller) and creates S1-format sprite mappings.
     * Mappings from docs/s1disasm/_maps/Roller.asm (Map_Roll_internal).
     * 5 frames: stand, fold, roll1, roll2, roll3.
     * <p>
     * From disassembly: make_art_tile(ArtTile_Roller,0,0) - palette 0, no priority bit.
     */
    private void loadRollerArt(Sonic1ObjectArt art) {
        Pattern[] patterns = art.loadNemesisPatterns(
                Sonic1Constants.ART_NEM_ROLLER_ADDR);
        if (patterns.length == 0) {
            LOGGER.warning("Failed to load Roller art");
            return;
        }

        List<SpriteMappingFrame> mappings = art.loadMappingFrames(Sonic1Constants.MAP_ROLLER_ADDR);
        // make_art_tile(ArtTile_Roller, 0, 0) - palette line 0
        ObjectSpriteSheet sheet = new ObjectSpriteSheet(patterns, mappings, 0, 1);
        registerSheet(ObjectArtKeys.ROLLER, sheet);
    }

    /**
     * Loads Buzz Bomber art (Nem_Buzz) and creates sprite sheets for the Buzz Bomber,
     * its missile, and the missile dissolve effect.
     * All three share the same Nemesis-compressed art tile set (ArtTile_Buzz_Bomber = $444).
     * Missile dissolve uses a separate VRAM region (ArtTile_Missile_Disolve = $41C),
     * but in practice shares the same ROM art with different tile offsets in mappings.
     */
    private void loadBuzzBomberArt(Sonic1ObjectArt art) {
        Pattern[] patterns = art.loadNemesisPatterns(
                Sonic1Constants.ART_NEM_BUZZ_BOMBER_ADDR);
        if (patterns.length == 0) {
            LOGGER.warning("Failed to load Buzz Bomber art");
            return;
        }

        // Buzz Bomber body: palette 0, art tile $444
        List<SpriteMappingFrame> buzzMappings = art.loadMappingFrames(Sonic1Constants.MAP_BUZZ_BOMBER_ADDR);
        ObjectSpriteSheet buzzSheet = new ObjectSpriteSheet(patterns, buzzMappings, 0, 1);
        registerSheet(ObjectArtKeys.BUZZ_BOMBER, buzzSheet);

        // Missile: palette 1, shares same art tiles (base $444, missile tiles at offset $24+)
        List<SpriteMappingFrame> missileMappings = art.loadMappingFrames(Sonic1Constants.MAP_BUZZ_MISSILE_ADDR);
        ObjectSpriteSheet missileSheet = new ObjectSpriteSheet(patterns, missileMappings, 1, 1);
        registerSheet(ObjectArtKeys.BUZZ_BOMBER_MISSILE, missileSheet);

        // Missile dissolve: palette 0
        // In the original ROM, dissolve references ArtTile_Missile_Disolve ($41C) which is
        // a separate VRAM region. However, $41C is marked as "Unused" in Constants.asm and
        // no PLC ever loads art to that address. Object 24 itself is marked "unused?" in the
        // disassembly. The dissolve effect was likely cut during development.
        // We reuse buzz bomber patterns as a visual stand-in since the original has no art loaded.
        List<SpriteMappingFrame> dissolveMappings = art.loadMappingFrames(Sonic1Constants.MAP_UNUSED_EXPLOSION_ADDR);
        ObjectSpriteSheet dissolveSheet = new ObjectSpriteSheet(patterns, dissolveMappings, 0, 1);
        registerSheet(ObjectArtKeys.BUZZ_BOMBER_MISSILE_DISSOLVE, dissolveSheet);
    }

    @Override
    public void registerLevelTileArt(Level level, int zoneIndex) {
        registerPlatformSheet(level, zoneIndex);
        registerCollapsingLedgeSheet(level, zoneIndex);
        registerMzBrickSheet(level, zoneIndex);
        registerLargeGrassyPlatformSheet(level, zoneIndex);
        registerLavaWallSheet(level, zoneIndex);
        registerFloatingBlockSheet(level, zoneIndex);
        registerCirclingPlatformSheet(level, zoneIndex);
        registerStaircaseSheet(level, zoneIndex);
        registerElevatorSheet(level, zoneIndex);
        if (zoneIndex == Sonic1Constants.ZONE_SYZ) {
            registerSpinningLightSheet(level);
            registerBossBlockSheet(level);
        }
        if (zoneIndex == Sonic1Constants.ZONE_LZ) {
            registerSbz3BigDoorSheet(level, zoneIndex);
        }
    }

    /**
     * Registers the platform sprite sheet using level tile patterns.
     * Must be called AFTER the level is loaded since platforms use zone tileset art
     * (ArtTile_Level) rather than dedicated Nemesis-compressed object art.
     * <p>
     * Zone-specific mappings from disassembly:
     * <ul>
     *   <li>GHZ: 2 frames (small 64x24, large column 64x140)</li>
     *   <li>SYZ: 1 frame (64x20)</li>
     *   <li>SLZ: 1 frame (64x16)</li>
     * </ul>
     *
     * @param level     The loaded level to extract patterns from
     * @param zoneIndex The current zone index
     */
    public void registerPlatformSheet(Level level, int zoneIndex) {
        if (level == null) {
            return;
        }

        List<SpriteMappingFrame> mappings;
        int maxTileNeeded;

        switch (zoneIndex) {
            case Sonic1Constants.ZONE_SYZ -> {
                mappings = loadMappingFrames(Sonic1Constants.MAP_PLATFORM_SYZ_ADDR);
                // Highest tile: 0x55 + (3*4) = 0x61
                maxTileNeeded = 0x61;
            }
            case Sonic1Constants.ZONE_SLZ -> {
                mappings = loadMappingFrames(Sonic1Constants.MAP_PLATFORM_SLZ_ADDR);
                // Highest tile: 0x21 + (4*4) = 0x31
                maxTileNeeded = 0x31;
            }
            default -> {
                // GHZ (and any other zone with platforms)
                mappings = loadMappingFrames(Sonic1Constants.MAP_PLATFORM_GHZ_ADDR);
                // Highest tile: 0xD5 + (4*4) = 0xE5
                maxTileNeeded = 0xE5;
            }
        }

        int patternCount = level.getPatternCount();
        int copyCount = Math.min(patternCount, maxTileNeeded);
        if (copyCount == 0) {
            LOGGER.warning("No level patterns available for platform art");
            return;
        }
        Pattern[] patterns = new Pattern[copyCount];
        for (int i = 0; i < copyCount; i++) {
            patterns[i] = level.getPattern(i);
        }

        // Palette line 2 (make_art_tile(ArtTile_Level, 2, 0))
        ObjectSpriteSheet sheet = new ObjectSpriteSheet(patterns, mappings, 2, 1);
        registerSheet(ObjectArtKeys.PLATFORM, sheet);
    }

    /**
     * Registers the SLZ circling platform sprite sheet using level tile patterns.
     * Must be called AFTER the level is loaded since the circling platform uses zone tileset art
     * (make_art_tile(ArtTile_Level,2,0)).
     * <p>
     * SLZ only. 1 frame from docs/s1disasm/_maps/SLZ Circling Platform.asm:
     * <ul>
     *   <li>Frame 0 (.platform): 48x16 platform (2 pieces of 3x2 tiles)</li>
     * </ul>
     *
     * @param level     The loaded level to extract patterns from
     * @param zoneIndex The current zone index
     */
    /**
     * Registers the SLZ elevator sprite sheet using level tile patterns.
     * Must be called AFTER the level is loaded since elevators use zone tileset art
     * (make_art_tile(ArtTile_Level,2,0)).
     * <p>
     * SLZ only. 1 frame from docs/s1disasm/_maps/SLZ Elevators.asm:
     * <ul>
     *   <li>Frame 0 (.elevator): 80x32 platform (3 pieces using tile $41)</li>
     * </ul>
     *
     * @param level     The loaded level to extract patterns from
     * @param zoneIndex The current zone index
     */
    public void registerElevatorSheet(Level level, int zoneIndex) {
        if (level == null || zoneIndex != Sonic1Constants.ZONE_SLZ) {
            return;
        }

        List<SpriteMappingFrame> mappings = loadMappingFrames(Sonic1Constants.MAP_SLZ_ELEVATOR_ADDR);

        // Highest tile: $41 + (4*4) = $51
        int maxTileNeeded = 0x51;
        int patternCount = level.getPatternCount();
        int copyCount = Math.min(patternCount, maxTileNeeded);
        if (copyCount == 0) {
            LOGGER.warning("No level patterns available for SLZ elevator art");
            return;
        }
        Pattern[] patterns = new Pattern[copyCount];
        for (int i = 0; i < copyCount; i++) {
            patterns[i] = level.getPattern(i);
        }

        // Palette line 2 (make_art_tile(ArtTile_Level, 2, 0))
        ObjectSpriteSheet sheet = new ObjectSpriteSheet(patterns, mappings, 2, 1);
        registerSheet(ObjectArtKeys.SLZ_ELEVATOR, sheet);
    }

    public void registerCirclingPlatformSheet(Level level, int zoneIndex) {
        if (level == null || zoneIndex != Sonic1Constants.ZONE_SLZ) {
            return;
        }

        List<SpriteMappingFrame> mappings = loadMappingFrames(Sonic1Constants.MAP_SLZ_CIRCLING_PLATFORM_ADDR);

        // Highest tile: $51 + (3*2) = $57
        int maxTileNeeded = 0x57;
        int patternCount = level.getPatternCount();
        int copyCount = Math.min(patternCount, maxTileNeeded);
        if (copyCount == 0) {
            LOGGER.warning("No level patterns available for SLZ circling platform art");
            return;
        }
        Pattern[] patterns = new Pattern[copyCount];
        for (int i = 0; i < copyCount; i++) {
            patterns[i] = level.getPattern(i);
        }

        // Palette line 2 (make_art_tile(ArtTile_Level, 2, 0))
        ObjectSpriteSheet sheet = new ObjectSpriteSheet(patterns, mappings, 2, 1);
        registerSheet(ObjectArtKeys.SLZ_CIRCLING_PLATFORM, sheet);
    }

    /**
     * Registers the SLZ staircase sprite sheet using level tile patterns.
     * Must be called AFTER the level is loaded since the staircase uses zone tileset art
     * (make_art_tile(ArtTile_Level,2,0)).
     * <p>
     * SLZ only. 1 frame from docs/s1disasm/_maps/Staircase.asm:
     * <ul>
     *   <li>Frame 0 (.block): 32x32 block (1 piece of 4x4 tiles at tile $21)</li>
     * </ul>
     *
     * @param level     The loaded level to extract patterns from
     * @param zoneIndex The current zone index
     */
    public void registerStaircaseSheet(Level level, int zoneIndex) {
        if (level == null || zoneIndex != Sonic1Constants.ZONE_SLZ) {
            return;
        }

        List<SpriteMappingFrame> mappings = loadMappingFrames(Sonic1Constants.MAP_SLZ_STAIRCASE_ADDR);

        // Highest tile: $21 + (4*4) = $31
        int maxTileNeeded = 0x31;
        int patternCount = level.getPatternCount();
        int copyCount = Math.min(patternCount, maxTileNeeded);
        if (copyCount == 0) {
            LOGGER.warning("No level patterns available for SLZ staircase art");
            return;
        }
        Pattern[] patterns = new Pattern[copyCount];
        for (int i = 0; i < copyCount; i++) {
            patterns[i] = level.getPattern(i);
        }

        // Palette line 2 (make_art_tile(ArtTile_Level, 2, 0))
        ObjectSpriteSheet sheet = new ObjectSpriteSheet(patterns, mappings, 2, 1);
        registerSheet(ObjectArtKeys.SLZ_STAIRCASE, sheet);
    }

    /**
     * Registers the collapsing ledge sprite sheet using level tile patterns.
     * Must be called AFTER the level is loaded since ledges use zone tileset art
     * (make_art_tile(ArtTile_Level,2,0)) — same as platforms.
     * <p>
     * GHZ only. 4 frames from Map_Ledge:
     * <ul>
     *   <li>Frame 0 (.left): Left-facing ledge, 16 pieces</li>
     *   <li>Frame 1 (.right): Right-facing ledge, 16 pieces</li>
     *   <li>Frame 2 (.leftsmash): Left-facing fragments, 23 pieces</li>
     *   <li>Frame 3 (.rightsmash): Right-facing fragments, 25 pieces</li>
     * </ul>
     *
     * @param level     The loaded level to extract patterns from
     * @param zoneIndex The current zone index
     */
    public void registerCollapsingLedgeSheet(Level level, int zoneIndex) {
        if (level == null || zoneIndex != Sonic1Constants.ZONE_GHZ) {
            return;
        }

        List<SpriteMappingFrame> mappings = loadMappingFrames(Sonic1Constants.MAP_COLLAPSING_LEDGE_ADDR);

        // Highest tile: 0xC1 + (2*2) = 0xC5
        int maxTileNeeded = 0xC5;
        int patternCount = level.getPatternCount();
        int copyCount = Math.min(patternCount, maxTileNeeded);
        if (copyCount == 0) {
            LOGGER.warning("No level patterns available for collapsing ledge art");
            return;
        }
        Pattern[] patterns = new Pattern[copyCount];
        for (int i = 0; i < copyCount; i++) {
            patterns[i] = level.getPattern(i);
        }

        // Palette line 2 (make_art_tile(ArtTile_Level, 2, 0))
        ObjectSpriteSheet sheet = new ObjectSpriteSheet(patterns, mappings, 2, 1);
        registerSheet(ObjectArtKeys.COLLAPSING_LEDGE, sheet);
    }

    /**
     * Registers the MZ large grassy platform sprite sheet using level tile patterns.
     * Must be called AFTER the level is loaded since these platforms use zone tileset art
     * (make_art_tile(ArtTile_Level, 2, 1)) -- same tile base as other level objects.
     * <p>
     * MZ only. 3 frames from docs/s1disasm/_maps/MZ Large Grassy Platforms.asm:
     * <ul>
     *   <li>Frame 0 (.wide): Wide flat platform (13 pieces, width $40)</li>
     *   <li>Frame 1 (.sloped): Sloped platform that catches fire (10 pieces, width $40)</li>
     *   <li>Frame 2 (.narrow): Narrow platform (6 pieces, width $20)</li>
     * </ul>
     *
     * @param level     The loaded level to extract patterns from
     * @param zoneIndex The current zone index
     */
    public void registerLargeGrassyPlatformSheet(Level level, int zoneIndex) {
        if (level == null || zoneIndex != Sonic1Constants.ZONE_MZ) {
            return;
        }

        List<SpriteMappingFrame> mappings = loadMappingFrames(
                Sonic1Constants.MAP_MZ_LARGE_GRASSY_PLATFORM_ADDR);

        // Highest tile used: 0x57 + (2*3) = 0x5D
        int maxTileNeeded = 0x5D;
        int patternCount = level.getPatternCount();
        int copyCount = Math.min(patternCount, maxTileNeeded);
        if (copyCount == 0) {
            LOGGER.warning("No level patterns available for MZ large grassy platform art");
            return;
        }
        Pattern[] patterns = new Pattern[copyCount];
        for (int i = 0; i < copyCount; i++) {
            patterns[i] = level.getPattern(i);
        }

        // Palette line 2, priority 1: make_art_tile(ArtTile_Level, 2, 1)
        ObjectSpriteSheet sheet = new ObjectSpriteSheet(patterns, mappings, 2, 1);
        registerSheet(ObjectArtKeys.MZ_LARGE_GRASSY_PLATFORM, sheet);
    }

    /**
     * Loads MZ Fireball art (Nem_MzFire) for the Burning Grass object (0x35).
     * Uses ArtTile_MZ_Fireball ($345) at palette line 0, no priority.
     * <p>
     * Mappings from docs/s1disasm/_maps/Fireballs.asm (Map_Fire_internal):
     * <ul>
     *   <li>Frame 0 (.vertical1): 2x4 tiles at (-8, -$18), startTile 0</li>
     *   <li>Frame 1 (.vertical2): 2x4 tiles at (-8, -$18), startTile 8</li>
     *   <li>Frame 2 (.vertcollide): 2x3 tiles at (-8, -$10), startTile $10</li>
     *   <li>Frame 3 (.horizontal1): 4x2 tiles at (-$18, -8), startTile $16</li>
     *   <li>Frame 4 (.horizontal2): 4x2 tiles at (-$18, -8), startTile $1E</li>
     *   <li>Frame 5 (.horicollide): 3x2 tiles at (-$10, -8), startTile $26</li>
     * </ul>
     *
     * The Burning Grass animation (Ani_GFire) uses frames: {5, 0, $20, 1, $21, afEnd}.
     * Frame $20 = frame 0 with V-flip, frame $21 = frame 1 with V-flip.
     * Our engine handles flip at render time, so we only need the 6 base frames.
     */
    private void loadMzFireballArt(Sonic1ObjectArt art) {
        Pattern[] patterns = art.loadNemesisPatterns(
                Sonic1Constants.ART_NEM_MZ_FIREBALL_ADDR);
        if (patterns.length == 0) {
            LOGGER.warning("Failed to load MZ fireball art");
            return;
        }

        List<SpriteMappingFrame> mappings = art.loadMappingFrames(Sonic1Constants.MAP_FIREBALL_ADDR);
        // make_art_tile(ArtTile_MZ_Fireball, 0, 0) -> palette line 0, no priority
        ObjectSpriteSheet sheet = new ObjectSpriteSheet(patterns, mappings, 0, 0);
        registerSheet(ObjectArtKeys.MZ_FIREBALL, sheet);
    }

    /**
     * Loads SLZ Fireball art (Nem_MzFire at ArtTile_SLZ_Fireball=$480).
     * Same art data as MZ fireball, loaded under a separate key for SLZ zone.
     * From PLC_SLZ: plcm Nem_MzFire, ArtTile_SLZ_Fireball
     * <p>
     * Uses palette line 0, no priority: make_art_tile(ArtTile_SLZ_Fireball,0,0).
     */
    private void loadSlzFireballArt(Sonic1ObjectArt art) {
        Pattern[] patterns = art.loadNemesisPatterns(
                Sonic1Constants.ART_NEM_MZ_FIREBALL_ADDR);
        if (patterns.length == 0) {
            LOGGER.warning("Failed to load SLZ fireball art");
            return;
        }

        List<SpriteMappingFrame> mappings = art.loadMappingFrames(Sonic1Constants.MAP_FIREBALL_ADDR);
        // make_art_tile(ArtTile_SLZ_Fireball, 0, 0) -> palette line 0, no priority
        ObjectSpriteSheet sheet = new ObjectSpriteSheet(patterns, mappings, 0, 0);
        registerSheet(ObjectArtKeys.SLZ_FIREBALL, sheet);
    }

    /**
     * Loads MZ Lava Geyser art (Nem_Lava at ArtTile_MZ_Lava=$3A8).
     * Used by Objects 0x4C (GeyserMaker) and 0x4D (LavaGeyser).
     * From PLC_MZ: plcm Nem_Lava, ArtTile_MZ_Lava
     * <p>
     * Uses palette line 3, priority bit set for maker (make_art_tile(ArtTile_MZ_Lava,3,1)),
     * no priority for geyser children (make_art_tile(ArtTile_MZ_Lava,3,0)).
     * We use palette line 3, no priority for the sheet; priority is handled at render time.
     */
    private void loadMzLavaGeyserArt(Sonic1ObjectArt art) {
        Pattern[] patterns = art.loadNemesisPatterns(
                Sonic1Constants.ART_NEM_LAVA_ADDR);
        if (patterns.length == 0) {
            LOGGER.warning("Failed to load MZ lava geyser art");
            return;
        }

        List<SpriteMappingFrame> mappings = art.loadMappingFrames(Sonic1Constants.MAP_MZ_LAVA_GEYSER_ADDR);
        // make_art_tile(ArtTile_MZ_Lava, 3, 0) -> palette line 3, no priority
        ObjectSpriteSheet sheet = new ObjectSpriteSheet(patterns, mappings, 3, 0);
        registerSheet(ObjectArtKeys.MZ_LAVA_GEYSER, sheet);
    }

    /**
     * Loads MZ Lava Wall art using level patterns (which include both zone tiles and
     * Nem_Lava tiles loaded via PLC_MZ at ArtTile_MZ_Lava=$3A8).
     * <p>
     * Object 0x4E creates two sub-objects: the main wall (animated leading edge + solid body)
     * and a trailing section (solid body only, frame 4). The leading edge cycles through
     * animation frames 0-3 at speed 9 (Ani_LWall). The body uses a single repeated lava
     * tile from the zone art.
     * <p>
     * VDP tile resolution (obGfx = make_art_tile(ArtTile_MZ_Lava,3,0) = $63A8):
     * <ul>
     *   <li>Edge tiles: mapping tile $60/$70/$80 + obGfx -> VRAM tile $408/$418/$428</li>
     *   <li>Body tiles: mapping tile $72A (with pal/pri/flip flags) + obGfx -> VRAM tile $2D2
     *       (flags cancel via 16-bit add overflow)</li>
     * </ul>
     *
     * @param level     The loaded level to extract patterns from
     * @param zoneIndex The current zone index
     */
    public void registerLavaWallSheet(Level level, int zoneIndex) {
        if (level == null || zoneIndex != Sonic1Constants.ZONE_MZ) {
            return;
        }

        List<SpriteMappingFrame> mappings = createLavaWallMappingsFromRom(
                loadMappingFrames(Sonic1Constants.MAP_MZ_LAVA_WALL_ADDR));

        // Highest tile used: $428 + (4*4-1) = $437
        int maxTileNeeded = 0x438;
        int patternCount = level.getPatternCount();
        int copyCount = Math.min(patternCount, maxTileNeeded);
        if (copyCount == 0) {
            LOGGER.warning("No level patterns available for MZ lava wall art");
            return;
        }
        Pattern[] patterns = new Pattern[copyCount];
        for (int i = 0; i < copyCount; i++) {
            patterns[i] = level.getPattern(i);
        }

        // Palette line 3: make_art_tile(ArtTile_MZ_Lava, 3, 0)
        ObjectSpriteSheet sheet = new ObjectSpriteSheet(patterns, mappings, 3, 0);
        registerSheet(ObjectArtKeys.MZ_LAVA_WALL, sheet);
    }

    static List<SpriteMappingFrame> createLavaWallMappingsFromRom(List<SpriteMappingFrame> rawFrames) {
        return rawFrames.stream()
                .map(frame -> new SpriteMappingFrame(frame.pieces().stream()
                        .map(Sonic1ObjectArtProvider::remapLavaWallPiece)
                        .toList()))
                .toList();
    }

    private static SpriteMappingPiece remapLavaWallPiece(SpriteMappingPiece piece) {
        int finalTile = (SpriteMappingPieces.toTileWord(piece) + MZ_LAVA_WALL_OBGFX_WORD) & 0x7FF;
        return SpriteMappingPieces.withAttributes(
                piece,
                finalTile,
                false,
                false,
                0,
                false);
    }

    /**
     * Loads MZ Smashable Green Block art (Nem_MzBlock) and creates S1-format sprite mappings.
     * <p>
     * From docs/s1disasm/_incObj/51 Smashable Green Block.asm:
     * <pre>
     *     move.w  #make_art_tile(ArtTile_MZ_Block,2,0),obGfx(a0)
     * </pre>
     * ArtTile_MZ_Block = $2B8, palette line 2.
     * <p>
     * Mappings from docs/s1disasm/_maps/Smashable Green Block.asm (Map_Smab_internal):
     * <ul>
     *   <li>Frame 0 (.two): Intact block - 2 pieces of 4x2 tiles stacked vertically</li>
     *   <li>Frame 1 (.four): Fragment layout - 4 pieces of 2x2 tiles in quadrants</li>
     * </ul>
     */
    private void loadMzSmashBlockArt(Sonic1ObjectArt art) {
        Pattern[] patterns = art.loadNemesisPatterns(
                Sonic1Constants.ART_NEM_MZ_BLOCK_ADDR);
        if (patterns.length == 0) {
            LOGGER.warning("Failed to load MZ smashable green block art");
            return;
        }

        List<SpriteMappingFrame> mappings = art.loadMappingFrames(Sonic1Constants.MAP_MZ_SMASH_BLOCK_ADDR);
        // make_art_tile(ArtTile_MZ_Block, 2, 0) -> palette line 2, no priority
        ObjectSpriteSheet sheet = new ObjectSpriteSheet(patterns, mappings, 2, 1);
        registerSheet(ObjectArtKeys.MZ_SMASH_BLOCK, sheet);
    }

    /**
     * Loads MZ Collapsing Floor art (Nem_MzBlock) and creates S1-format sprite mappings.
     * <p>
     * From docs/s1disasm/_incObj/53 Collapsing Floors.asm:
     * <pre>
     *     move.w  #make_art_tile(ArtTile_MZ_Block,2,0),obGfx(a0)
     * </pre>
     * ArtTile_MZ_Block = $2B8, palette line 2.
     * <p>
     * Uses Map_CFlo frames 0 (intact) and 1 (smash: 8 pieces of 2x2).
     */
    private void loadMzCollapsingFloorArt(Sonic1ObjectArt art) {
        Pattern[] patterns = art.loadNemesisPatterns(
                Sonic1Constants.ART_NEM_MZ_BLOCK_ADDR);
        if (patterns.length == 0) {
            LOGGER.warning("Failed to load MZ collapsing floor art");
            return;
        }

        List<SpriteMappingFrame> mappings = List.copyOf(
                art.loadMappingFrames(Sonic1Constants.MAP_COLLAPSING_FLOOR_ADDR).subList(0, 2));
        // make_art_tile(ArtTile_MZ_Block, 2, 0) -> palette line 2
        ObjectSpriteSheet sheet = new ObjectSpriteSheet(patterns, mappings, 2, 1);
        registerSheet(ObjectArtKeys.MZ_COLLAPSING_FLOOR, sheet);
    }

    /**
     * Loads SLZ Collapsing Floor art (Nem_SlzBlock) and creates S1-format sprite mappings.
     * <p>
     * From docs/s1disasm/_incObj/53 Collapsing Floors.asm:
     * <pre>
     *     move.w  #make_art_tile(ArtTile_SLZ_Collapsing_Floor,2,0),obGfx(a0)
     * </pre>
     * ArtTile_SLZ_Collapsing_Floor = $4E0, palette line 2.
     * Uses Map_CFlo frames 2 (intact) and 3 (smash: 8 pieces of 2x2 with varied tiles).
     */
    private void loadSlzCollapsingFloorArt(Sonic1ObjectArt art) {
        Pattern[] patterns = art.loadNemesisPatterns(
                Sonic1Constants.ART_NEM_SLZ_COLLAPSING_FLOOR_ADDR);
        if (patterns.length == 0) {
            LOGGER.warning("Failed to load SLZ collapsing floor art");
            return;
        }

        List<SpriteMappingFrame> mappings = List.copyOf(
                art.loadMappingFrames(Sonic1Constants.MAP_COLLAPSING_FLOOR_ADDR).subList(2, 4));
        // make_art_tile(ArtTile_SLZ_Collapsing_Floor, 2, 0) -> palette line 2
        ObjectSpriteSheet sheet = new ObjectSpriteSheet(patterns, mappings, 2, 1);
        registerSheet(ObjectArtKeys.SLZ_COLLAPSING_FLOOR, sheet);
    }

    /**
     * Loads SBZ Collapsing Floor art (Nem_SbzFloor) and creates S1-format sprite mappings.
     * <p>
     * From docs/s1disasm/_incObj/53 Collapsing Floors.asm:
     * <pre>
     *     move.w  #make_art_tile(ArtTile_SBZ_Collapsing_Floor,2,0),obGfx(a0)
     * </pre>
     * ArtTile_SBZ_Collapsing_Floor = $3F5, palette line 2.
     * Uses the same mapping layout as MZ (Map_CFlo frames 0 and 1).
     * <p>
     * Nem_SbzFloor decompresses to only 4 patterns, but the intact floor frame uses
     * 4x2 tile pieces requiring 8 tiles. On real hardware, both SBZ PLCs load the same
     * art at adjacent offsets to provide the full 8 tiles:
     * <pre>
     *     PLC_SBZ:  plcm Nem_SbzFloor, ArtTile_SBZ_Collapsing_Floor     ; tiles 0-3
     *     PLC_SBZ2: plcm Nem_SbzFloor, ArtTile_SBZ_Collapsing_Floor+4   ; tiles 4-7
     * </pre>
     * We replicate this by appending a second copy of the patterns.
     */
    private void loadSbzCollapsingFloorArt(Sonic1ObjectArt art) {
        Pattern[] patterns = art.loadNemesisPatterns(
                Sonic1Constants.ART_NEM_SBZ_COLLAPSING_FLOOR_ADDR);
        if (patterns.length == 0) {
            LOGGER.warning("Failed to load SBZ collapsing floor art");
            return;
        }

        // Duplicate patterns to match dual PLC loading (Nem_SbzFloor loaded at +0 and +4)
        Pattern[] doubled = new Pattern[patterns.length * 2];
        System.arraycopy(patterns, 0, doubled, 0, patterns.length);
        System.arraycopy(patterns, 0, doubled, patterns.length, patterns.length);
        patterns = doubled;

        List<SpriteMappingFrame> mappings = List.copyOf(
                art.loadMappingFrames(Sonic1Constants.MAP_COLLAPSING_FLOOR_ADDR).subList(0, 2));
        // make_art_tile(ArtTile_SBZ_Collapsing_Floor, 2, 0) -> palette line 2
        ObjectSpriteSheet sheet = new ObjectSpriteSheet(patterns, mappings, 2, 1);
        registerSheet(ObjectArtKeys.SBZ_COLLAPSING_FLOOR, sheet);
    }

    /**
     * Loads SBZ Vanishing Platform art (Nem_SbzBlock) with ROM-backed Map_VanP mappings.
     * <p>
     * From docs/s1disasm/_incObj/6C SBZ Vanishing Platforms.asm:
     * <pre>
     *     move.w  #make_art_tile(ArtTile_SBZ_Vanishing_Block,2,0),obGfx(a0)
     * </pre>
     * ArtTile_SBZ_Vanishing_Block = $4C3, palette line 2.
     * <p>
     */
    private void loadSbzVanishingPlatformArt(Sonic1ObjectArt art) {
        Pattern[] patterns = art.loadNemesisPatterns(
                Sonic1Constants.ART_NEM_SBZ_VANISHING_BLOCK_ADDR);
        if (patterns.length == 0) {
            LOGGER.warning("Failed to load SBZ vanishing platform art");
            return;
        }

        List<SpriteMappingFrame> mappings = art.loadMappingFrames(
                Sonic1Constants.MAP_SBZ_VANISHING_PLATFORM_ADDR);
        // make_art_tile(ArtTile_SBZ_Vanishing_Block, 2, 0) -> palette line 2
        ObjectSpriteSheet sheet = new ObjectSpriteSheet(patterns, mappings, 2, 1);
        registerSheet(ObjectArtKeys.SBZ_VANISHING_PLATFORM, sheet);
    }

    /**
     * Loads SBZ Electrocuter art (Nem_Electric) and creates S1-format sprite mappings.
     * <p>
     * From docs/s1disasm/_incObj/6E Electrocuter.asm:
     * <pre>
     *     move.w  #make_art_tile(ArtTile_SBZ_Electric_Orb,0,0),obGfx(a0)
     * </pre>
     * ArtTile_SBZ_Electric_Orb = $47E, palette line 0.
     */
    private void loadSbzElectrocuterArt(Sonic1ObjectArt art) {
        Pattern[] patterns = art.loadNemesisPatterns(
                Sonic1Constants.ART_NEM_SBZ_ELECTROCUTER_ADDR);
        if (patterns.length == 0) {
            LOGGER.warning("Failed to load SBZ electrocuter art");
            return;
        }

        List<SpriteMappingFrame> mappings = art.loadMappingFrames(Sonic1Constants.MAP_SBZ_ELECTROCUTER_ADDR);
        // make_art_tile(ArtTile_SBZ_Electric_Orb, 0, 0) -> palette line 0
        ObjectSpriteSheet sheet = new ObjectSpriteSheet(patterns, mappings, 0, 1);
        registerSheet(ObjectArtKeys.SBZ_ELECTROCUTER, sheet);
    }

    /**
     * Loads SBZ Saw / Pizza Cutter art (Nem_Cutter) and creates S1-format sprite mappings.
     * <p>
     * From docs/s1disasm/_incObj/6A Saws and Pizza Cutters.asm:
     * <pre>
     *     move.w  #make_art_tile(ArtTile_SBZ_Saw,2,0),obGfx(a0)
     * </pre>
     * ArtTile_SBZ_Saw = $3B5, palette line 2, no priority bit.
     * <p>
     * Mappings from docs/s1disasm/_maps/Saws and Pizza Cutters.asm (Map_Saw_internal):
     * 4 frames: pizzacutter1, pizzacutter2, groundsaw1, groundsaw2.
     */
    private void loadSbzSawArt(Sonic1ObjectArt art) {
        Pattern[] patterns = art.loadNemesisPatterns(
                Sonic1Constants.ART_NEM_SBZ_SAW_ADDR);
        if (patterns.length == 0) {
            LOGGER.warning("Failed to load SBZ saw art");
            return;
        }

        List<SpriteMappingFrame> mappings = art.loadMappingFrames(Sonic1Constants.MAP_SBZ_SAW_ADDR);
        // make_art_tile(ArtTile_SBZ_Saw, 2, 0) -> palette line 2, no priority
        ObjectSpriteSheet sheet = new ObjectSpriteSheet(patterns, mappings, 2, 1);
        registerSheet(ObjectArtKeys.SBZ_SAW, sheet);
    }

    /**
     * Loads SBZ Flamethrower art (Nem_FlamePipe) and creates S1-format sprite mappings.
     * <p>
     * From docs/s1disasm/_incObj/6D Flamethrower.asm:
     * <pre>
     *     move.w  #make_art_tile(ArtTile_SBZ_Flamethrower,0,1),obGfx(a0)
     * </pre>
     * ArtTile_SBZ_Flamethrower = $3D9, palette line 0, priority bit set.
     * <p>
     * Mappings from docs/s1disasm/_maps/Flamethrower.asm (Map_Flame_internal):
     * 22 frames total - 11 pipe frames (0-10) and 11 valve frames (11-21).
     */
    private void loadSbzFlamethrowerArt(Sonic1ObjectArt art) {
        Pattern[] patterns = art.loadNemesisPatterns(
                Sonic1Constants.ART_NEM_SBZ_FLAMETHROWER_ADDR);
        if (patterns.length == 0) {
            LOGGER.warning("Failed to load SBZ flamethrower art");
            return;
        }

        List<SpriteMappingFrame> mappings = art.loadMappingFrames(Sonic1Constants.MAP_SBZ_FLAMETHROWER_ADDR);
        // make_art_tile(ArtTile_SBZ_Flamethrower, 0, 1) -> palette line 0, priority
        ObjectSpriteSheet sheet = new ObjectSpriteSheet(patterns, mappings, 0, 1);
        registerSheet(ObjectArtKeys.SBZ_FLAMETHROWER, sheet);
    }

    /**
     * Loads SBZ Girder Block art (Nem_Girder) and creates S1-format sprite mappings.
     * <p>
     * From docs/s1disasm/_incObj/70 Girder Block.asm:
     * <pre>
     *   move.l  #Map_Gird,obMap(a0)
     *   move.w  #make_art_tile(ArtTile_SBZ_Girder,2,0),obGfx(a0)
     * </pre>
     * ArtTile_SBZ_Girder = $2F0, palette line 2.
     */
    private void loadSbzGirderArt(Sonic1ObjectArt art) {
        Pattern[] patterns = art.loadNemesisPatterns(
                Sonic1Constants.ART_NEM_SBZ_GIRDER_ADDR);
        if (patterns.length == 0) {
            LOGGER.warning("Failed to load SBZ girder art");
            return;
        }

        List<SpriteMappingFrame> mappings = art.loadMappingFrames(Sonic1Constants.MAP_SBZ_GIRDER_ADDR);
        // make_art_tile(ArtTile_SBZ_Girder, 2, 0) -> palette line 2
        ObjectSpriteSheet sheet = new ObjectSpriteSheet(patterns, mappings, 2, 1);
        registerSheet(ObjectArtKeys.SBZ_GIRDER, sheet);
    }

    /**
     * Loads SBZ Trap Door art (Nem_TrapDoor) and creates S1-format sprite mappings.
     * <p>
     * From docs/s1disasm/_incObj/69 SBZ Spinning Platforms.asm:
     * <pre>
     *   move.l  #Map_Trap,obMap(a0)
     *   move.w  #make_art_tile(ArtTile_SBZ_Trap_Door,2,0),obGfx(a0)
     * </pre>
     * ArtTile_SBZ_Trap_Door = $492, palette line 2.
     */
    private void loadSbzTrapDoorArt(Sonic1ObjectArt art) {
        Pattern[] patterns = art.loadNemesisPatterns(
                Sonic1Constants.ART_NEM_SBZ_TRAP_DOOR_ADDR);
        if (patterns.length == 0) {
            LOGGER.warning("Failed to load SBZ trap door art");
            return;
        }

        List<SpriteMappingFrame> mappings = art.loadMappingFrames(Sonic1Constants.MAP_SBZ_TRAP_DOOR_ADDR);
        // make_art_tile(ArtTile_SBZ_Trap_Door, 2, 0) -> palette line 2
        ObjectSpriteSheet sheet = new ObjectSpriteSheet(patterns, mappings, 2, 1);
        registerSheet(ObjectArtKeys.SBZ_TRAP_DOOR, sheet);
    }

    /**
     * Loads SBZ Small Door art (Nem_SbzDoor1) and creates S1-format sprite mappings.
     * <p>
     * From docs/s1disasm/_incObj/2A SBZ Small Door.asm:
     * <pre>
     *   move.l  #Map_ADoor,obMap(a0)
     *   move.w  #make_art_tile(ArtTile_SBZ_Door,2,0),obGfx(a0)
     * </pre>
     * ArtTile_SBZ_Door = $2E8, palette line 2.
     */
    private void loadSbzSmallDoorArt(Sonic1ObjectArt art) {
        Pattern[] patterns = art.loadNemesisPatterns(
                Sonic1Constants.ART_NEM_SBZ_SMALL_DOOR_ADDR);
        if (patterns.length == 0) {
            LOGGER.warning("Failed to load SBZ small door art");
            return;
        }

        List<SpriteMappingFrame> mappings = art.loadMappingFrames(Sonic1Constants.MAP_SBZ_SMALL_DOOR_ADDR);
        // make_art_tile(ArtTile_SBZ_Door, 2, 0) -> palette line 2
        ObjectSpriteSheet sheet = new ObjectSpriteSheet(patterns, mappings, 2, 1);
        registerSheet(ObjectArtKeys.SBZ_SMALL_DOOR, sheet);
    }

    /**
     * Loads SBZ Spinning Platform art (Nem_SpinPform) and creates S1-format sprite mappings.
     * <p>
     * From docs/s1disasm/_incObj/69 SBZ Spinning Platforms.asm:
     * <pre>
     *   move.l  #Map_Spin,obMap(a0)
     *   move.w  #make_art_tile(ArtTile_SBZ_Spinning_Platform,0,0),obGfx(a0)
     * </pre>
     * ArtTile_SBZ_Spinning_Platform = $4DF, palette line 0.
     */
    private void loadSbzSpinningPlatformArt(Sonic1ObjectArt art) {
        Pattern[] patterns = art.loadNemesisPatterns(
                Sonic1Constants.ART_NEM_SBZ_SPINNING_PLATFORM_ADDR);
        if (patterns.length == 0) {
            LOGGER.warning("Failed to load SBZ spinning platform art");
            return;
        }

        List<SpriteMappingFrame> mappings = art.loadMappingFrames(
                Sonic1Constants.MAP_SBZ_SPINNING_PLATFORM_ADDR);
        // make_art_tile(ArtTile_SBZ_Spinning_Platform, 0, 0) -> palette line 0
        ObjectSpriteSheet sheet = new ObjectSpriteSheet(patterns, mappings, 0, 1);
        registerSheet(ObjectArtKeys.SBZ_SPINNING_PLATFORM, sheet);
    }

    /**
     * Loads MZ Green Glass Block art (Nem_MzGlass) and creates sprite mappings.
     * <p>
     * 3 frames from docs/s1disasm/_maps/MZ Large Green Glass Blocks.asm (Map_Glass_internal):
     * <ul>
     *   <li>Frame 0 (.tall): Tall block ($48 half-height), 12 pieces</li>
     *   <li>Frame 1 (.shine): Reflected shine overlay, 2 pieces</li>
     *   <li>Frame 2 (.short): Short block ($38 half-height), 10 pieces</li>
     * </ul>
     * <p>
     * obGfx: make_art_tile(ArtTile_MZ_Glass_Pillar, 2, 1) = palette line 2, priority set.
     * <p>
     * Reference: docs/s1disasm/_incObj/30 MZ Large Green Glass Blocks.asm
     */
    private void loadMzGlassBlockArt(Sonic1ObjectArt art) {
        Pattern[] patterns = art.loadNemesisPatterns(
                Sonic1Constants.ART_NEM_MZ_GLASS_ADDR);
        if (patterns.length == 0) {
            LOGGER.warning("Failed to load MZ glass block art");
            return;
        }

        List<SpriteMappingFrame> mappings = art.loadMappingFrames(Sonic1Constants.MAP_MZ_GLASS_ADDR);
        // make_art_tile(ArtTile_MZ_Glass_Pillar, 2, 1) -> palette line 2, priority 1
        ObjectSpriteSheet sheet = new ObjectSpriteSheet(patterns, mappings, 2, 1);
        registerSheet(ObjectArtKeys.MZ_GLASS_BLOCK, sheet);
    }

    /**
     * Loads MZ Chained Stomper art (Nem_MzMetal) and creates sprite mappings.
     * <p>
     * obGfx: make_art_tile(ArtTile_MZ_Spike_Stomper, 0, 0) = palette line 0, no priority.
     * <p>
     * 11 frames from docs/s1disasm/_maps/Chained Stompers.asm (Map_CStom_internal):
     * <ul>
     *   <li>Frame 0: Wide block (main solid piece)</li>
     *   <li>Frame 1: Spikes (uses spike art, NOT Nem_MzMetal - handled separately by object)</li>
     *   <li>Frame 2: Ceiling anchor piece</li>
     *   <li>Frames 3-7: Chain segments (1-5 links)</li>
     *   <li>Frame 8: Chain segment (same as frame 7, duplicate in ROM)</li>
     *   <li>Frame 9: Medium block</li>
     *   <li>Frame 10: Small block</li>
     * </ul>
     * <p>
     * Reference: docs/s1disasm/_incObj/31 Chained Stompers.asm
     */
    private void loadMzChainedStomperArt(Sonic1ObjectArt art) {
        Pattern[] patterns = art.loadNemesisPatterns(
                Sonic1Constants.ART_NEM_MZ_METAL_ADDR);
        if (patterns.length == 0) {
            LOGGER.warning("Failed to load MZ metal block art");
            return;
        }

        List<SpriteMappingFrame> mappings = art.loadMappingFrames(Sonic1Constants.MAP_MZ_CHAINED_STOMPER_ADDR);
        // make_art_tile(ArtTile_MZ_Spike_Stomper, 0, 0) -> palette line 0, no priority
        ObjectSpriteSheet sheet = new ObjectSpriteSheet(patterns, mappings, 0, 1);
        registerSheet(ObjectArtKeys.MZ_CHAINED_STOMPER, sheet);
    }

    /**
     * Loads MZ Pushable Block art (Nem_MzBlock) and creates S1-format sprite mappings.
     * <p>
     * From docs/s1disasm/_incObj/33 Pushable Blocks.asm:
     * <pre>
     *     move.w  #make_art_tile(ArtTile_MZ_Block,2,0),obGfx(a0)
     * </pre>
     * ArtTile_MZ_Block = $2B8, palette line 2.
     * Shares Nem_MzBlock art with the Smashable Green Block (0x51).
     * <p>
     * Mappings from docs/s1disasm/_maps/Pushable Blocks.asm (Map_Push_internal):
     * <ul>
     *   <li>Frame 0 (.single): Single 32x32 block - 1 piece of 4x4 tiles at tile 8</li>
     *   <li>Frame 1 (.four): Row of 4 blocks - 4 pieces of 4x4 tiles spaced 32px apart</li>
     * </ul>
     */
    private void loadMzPushBlockArt(Sonic1ObjectArt art) {
        Pattern[] patterns = art.loadNemesisPatterns(
                Sonic1Constants.ART_NEM_MZ_BLOCK_ADDR);
        if (patterns.length == 0) {
            LOGGER.warning("Failed to load MZ push block art");
            return;
        }

        List<SpriteMappingFrame> mappings = art.loadMappingFrames(Sonic1Constants.MAP_PUSH_BLOCK_ADDR);
        // make_art_tile(ArtTile_MZ_Block, 2, 0) -> palette line 2, no priority
        ObjectSpriteSheet sheet = new ObjectSpriteSheet(patterns, mappings, 2, 1);
        registerSheet(ObjectArtKeys.MZ_PUSH_BLOCK, sheet);
    }

    /**
     * Loads LZ Pushable Block art (Nem_LzPole) and related LZ pole mappings.
     * <p>
     * This Nemesis set is shared by:
     * <ul>
     *   <li>Object 0x33 push blocks ({@code ArtTile_LZ_Push_Block})</li>
     *   <li>Object 0x0B breakable pole ({@code ArtTile_LZ_Pole})</li>
     * </ul>
     * Both use palette line 2 and VRAM base $3DE.
     */
    private void loadLzPushBlockArt(Sonic1ObjectArt art) {
        Pattern[] patterns = art.loadNemesisPatterns(
                Sonic1Constants.ART_NEM_LZ_POLE_ADDR);
        if (patterns.length == 0) {
            LOGGER.warning("Failed to load LZ push block art");
            return;
        }

        List<SpriteMappingFrame> mappings = art.loadMappingFrames(Sonic1Constants.MAP_PUSH_BLOCK_ADDR);
        // make_art_tile(ArtTile_LZ_Push_Block, 2, 0) -> palette line 2, no priority
        ObjectSpriteSheet sheet = new ObjectSpriteSheet(patterns, mappings, 2, 1);
        registerSheet(ObjectArtKeys.LZ_PUSH_BLOCK, sheet);

        // Object 0x0B uses the same art source with Map_Pole mappings.
        List<SpriteMappingFrame> poleMappings = art.loadMappingFrames(Sonic1Constants.MAP_LZ_BREAKABLE_POLE_ADDR);
        ObjectSpriteSheet poleSheet = new ObjectSpriteSheet(patterns, poleMappings, 2, 1);
        registerSheet(ObjectArtKeys.LZ_BREAKABLE_POLE, poleSheet);
    }

    /**
     * Loads MZ Moving Block art (Nem_MzBlock, same art as push/smash blocks).
     * <p>
     * From docs/s1disasm/_incObj/52 Moving Blocks.asm:
     * <pre>
     *   move.w  #make_art_tile(ArtTile_MZ_Block,2,0),obGfx(a0)
     * </pre>
     * ArtTile_MZ_Block = $2B8, palette line 2.
     * <p>
     */
    private void loadMzMovingBlockArt(Sonic1ObjectArt art) {
        Pattern[] patterns = art.loadNemesisPatterns(
                Sonic1Constants.ART_NEM_MZ_BLOCK_ADDR);
        if (patterns.length == 0) {
            LOGGER.warning("Failed to load MZ moving block art");
            return;
        }

        List<SpriteMappingFrame> mappings = art.loadMappingFrames(Sonic1Constants.MAP_MZ_SBZ_MOVING_BLOCK_ADDR);
        // make_art_tile(ArtTile_MZ_Block, 2, 0) -> palette line 2, no priority
        ObjectSpriteSheet sheet = new ObjectSpriteSheet(patterns, mappings, 2, 1);
        registerSheet(ObjectArtKeys.MZ_MOVING_BLOCK, sheet);
    }

    /**
     * Loads LZ Moving Block art (Nem_LzBlock3).
     * <p>
     * From docs/s1disasm/_incObj/52 Moving Blocks.asm:
     * <pre>
     *   move.l  #Map_MBlockLZ,obMap(a0)
     *   move.w  #make_art_tile(ArtTile_LZ_Moving_Block,2,0),obGfx(a0)
     * </pre>
     * ArtTile_LZ_Moving_Block = $3BC, palette line 2.
     * <p>
     */
    private void loadLzMovingBlockArt(Sonic1ObjectArt art) {
        Pattern[] patterns = art.loadNemesisPatterns(
                Sonic1Constants.ART_NEM_LZ_MOVING_BLOCK_ADDR);
        if (patterns.length == 0) {
            LOGGER.warning("Failed to load LZ moving block art");
            return;
        }

        List<SpriteMappingFrame> mappings = art.loadMappingFrames(Sonic1Constants.MAP_LZ_MOVING_BLOCK_ADDR);
        // make_art_tile(ArtTile_LZ_Moving_Block, 2, 0) -> palette line 2, no priority
        ObjectSpriteSheet sheet = new ObjectSpriteSheet(patterns, mappings, 2, 1);
        registerSheet(ObjectArtKeys.LZ_MOVING_BLOCK, sheet);
    }

    /**
     * Loads LZ Conveyor Belt art (Nem_LzWheel) and creates S1-format sprite mappings.
     * <p>
     * From docs/s1disasm/_incObj/63 LZ Conveyor.asm:
     * <pre>
     *   move.w  #make_art_tile(ArtTile_LZ_Conveyor_Belt,2,0),obGfx(a0)  ; platforms
     *   move.w  #make_art_tile(ArtTile_LZ_Conveyor_Belt,0,0),obGfx(a0)  ; wheels
     * </pre>
     * ArtTile_LZ_Conveyor_Belt = $3F6, palette line 2 (platforms) or 0 (wheels).
     * <p>
     * Mappings from docs/s1disasm/_maps/LZ Conveyor.asm (Map_LConv_internal):
     * 5 frames: wheel1..wheel4 (32x32 each), platform (32x16).
     */
    private void loadLzConveyorArt(Sonic1ObjectArt art) {
        Pattern[] patterns = art.loadNemesisPatterns(
                Sonic1Constants.ART_NEM_LZ_WHEEL_ADDR);
        if (patterns.length == 0) {
            LOGGER.warning("Failed to load LZ conveyor belt art");
            return;
        }

        List<SpriteMappingFrame> mappings = art.loadMappingFrames(Sonic1Constants.MAP_LZ_CONVEYOR_ADDR);
        // Platforms use palette 2 (make_art_tile(...,2,0)), wheels use palette 0 (make_art_tile(...,0,0)).
        // We use palette 2 as the sheet default since platforms are the primary usage.
        // The wheel subtype overrides to palette 0 in the object code.
        ObjectSpriteSheet sheet = new ObjectSpriteSheet(patterns, mappings, 2, 1);
        registerSheet(ObjectArtKeys.LZ_CONVEYOR, sheet);
    }

    /**
     * Loads LZ Labyrinth Block art (Object 0x61) from four separate Nemesis sources.
     * <p>
     * The object uses make_art_tile(ArtTile_LZ_Blocks,2,0) as obGfx base ($3E6, palette 2).
     * Its mappings reference tiles at different offsets that span multiple PLC entries:
     * <ul>
     *   <li>Frame 0 (.sinkblock): tile 0 -> Nem_LzDoor2 (loaded at ArtTile_LZ_Blocks=$3E6)</li>
     *   <li>Frame 1 (.riseplatform): tile $69/$75 -> Nem_LzPlatfm (ArtTile_LZ_Rising_Platform=$44F)</li>
     *   <li>Frame 2 (.cork): tile $11A -> Nem_Cork (ArtTile_LZ_Cork=$500)</li>
     *   <li>Frame 3 (.block): tile $5FA -> Nem_LzBlock1 via 11-bit VRAM wraparound ($3E6+$5FA=$9E0&$7FF=$1E0)</li>
     * </ul>
     * <p>
     * We assemble all four sources into a single pattern array with tile indices
     * matching the mapping references (frame 3 is remapped from $5FA to a contiguous index).
     * <p>
     * Reference: docs/s1disasm/_maps/LZ Blocks.asm, docs/s1disasm/_incObj/61 LZ Blocks.asm
     */
    private void loadLabyrinthBlockArt(Sonic1ObjectArt art) {
        // Load all four art sets
        Pattern[] blocksPatterns = art.loadNemesisPatterns(
                Sonic1Constants.ART_NEM_LZ_BLOCKS_ADDR);
        Pattern[] risingPlatformPatterns = art.loadNemesisPatterns(
                Sonic1Constants.ART_NEM_LZ_RISING_PLATFORM_ADDR);
        Pattern[] corkPatterns = art.loadNemesisPatterns(
                Sonic1Constants.ART_NEM_LZ_CORK_ADDR);
        Pattern[] block1Patterns = art.loadNemesisPatterns(
                Sonic1Constants.ART_NEM_LZ_BLOCK1_ADDR);

        if (blocksPatterns.length == 0) {
            LOGGER.warning("Failed to load LZ labyrinth block base art");
            return;
        }

        // Build combined pattern array. Frame 3's tile $5FA wraps via 11-bit VRAM addressing
        // to a different art source. We remap it to a contiguous index after cork patterns.
        //
        // Layout: [0..blocksLen), [$69..+platLen), [$11A..+corkLen), [block1Start..+block1Len)
        int block1Start = 0x11A + corkPatterns.length;
        int totalPatterns = block1Start + block1Patterns.length;
        totalPatterns = Math.max(totalPatterns, 0x69 + risingPlatformPatterns.length);

        Pattern[] combined = new Pattern[totalPatterns];
        for (int i = 0; i < totalPatterns; i++) {
            combined[i] = new Pattern();
        }

        // Copy Nem_LzDoor2 (blocks base) at tile 0
        for (int i = 0; i < blocksPatterns.length && i < totalPatterns; i++) {
            combined[i] = blocksPatterns[i];
        }
        // Copy Nem_LzPlatfm at tile $69
        for (int i = 0; i < risingPlatformPatterns.length && (0x69 + i) < totalPatterns; i++) {
            combined[0x69 + i] = risingPlatformPatterns[i];
        }
        // Copy Nem_Cork at tile $11A
        for (int i = 0; i < corkPatterns.length && (0x11A + i) < totalPatterns; i++) {
            combined[0x11A + i] = corkPatterns[i];
        }
        // Copy Nem_LzBlock1 at block1Start (remapped from $5FA)
        for (int i = 0; i < block1Patterns.length && (block1Start + i) < totalPatterns; i++) {
            combined[block1Start + i] = block1Patterns[i];
        }

        List<SpriteMappingFrame> mappings = createLabyrinthBlockMappings(block1Start);
        ObjectSpriteSheet sheet = new ObjectSpriteSheet(combined, mappings, 2, 1);
        registerSheet(ObjectArtKeys.LZ_LABYRINTH_BLOCK, sheet);
    }

    /**
     * Creates LZ Labyrinth Block sprite mappings from S1 disassembly Map_LBlock_internal.
     * <p>
     * From docs/s1disasm/_maps/LZ Blocks.asm:
     * <pre>
     * .sinkblock:     spritePiece -$10, -$10, 4, 4, 0, 0, 0, 0, 0
     * .riseplatform:  spritePiece -$20, -$C, 4, 3, $69, 0, 0, 0, 0
     *                 spritePiece    0, -$C, 4, 3, $75, 0, 0, 0, 0
     * .cork:          spritePiece -$10, -$10, 4, 4, $11A, 0, 0, 0, 0
     * .block:         spritePiece -$10, -$10, 4, 4, $5FA, 1, 1, 3, 1
     * </pre>
     *
     * @param block1TileStart remapped tile index for frame 3's $5FA tiles
     */
    private List<SpriteMappingFrame> createLabyrinthBlockMappings(int block1TileStart) {
        List<SpriteMappingFrame> frames = new ArrayList<>(
                loadMappingFrames(Sonic1Constants.MAP_LZ_BLOCK_ADDR));
        SpriteMappingPiece block = frames.get(3).pieces().get(0);
        frames.set(3, new SpriteMappingFrame(List.of(SpriteMappingPieces.withAttributes(
                block, block1TileStart, false, false, 0, false))));
        return frames;
    }

    /**
     * Loads LZ Bubbles art (Nem_Bubbles) and creates S1-format sprite mappings.
     * <p>
     * From docs/s1disasm/_incObj/64 Bubbles.asm:
     * <pre>
     *   move.w  #make_art_tile(ArtTile_LZ_Bubbles,0,1),obGfx(a0)
     * </pre>
     * ArtTile_LZ_Bubbles = $348, palette line 0, priority 1.
     * <p>
     * Mappings from docs/s1disasm/_maps/Bubbles.asm (Map_Bub_internal).
     * 23 mapping frames: bubble growth (0-6), burst (7-8), countdown numbers (9-18),
     * bubble maker (19-21), blank (22).
     */
    private void loadBubblesArt(Sonic1ObjectArt art) {
        Pattern[] patterns = art.loadNemesisPatterns(
                Sonic1Constants.ART_NEM_LZ_BUBBLES_ADDR);
        if (patterns.length == 0) {
            LOGGER.warning("Failed to load LZ bubbles art");
            return;
        }

        List<SpriteMappingFrame> mappings = art.loadMappingFrames(Sonic1Constants.MAP_LZ_BUBBLES_ADDR);
        // make_art_tile(ArtTile_LZ_Bubbles, 0, 1) -> palette line 0, priority 1
        ObjectSpriteSheet sheet = new ObjectSpriteSheet(patterns, mappings, 0, 1);
        registerSheet(ObjectArtKeys.LZ_BUBBLES, sheet);
    }

    /**
     * Loads SBZ Moving Block (short) art (Nem_Stomper).
     * <p>
     * From docs/s1disasm/_incObj/52 Moving Blocks.asm:
     * <pre>
     *   move.w  #make_art_tile(ArtTile_SBZ_Moving_Block_Short,1,0),obGfx(a0)
     * </pre>
     * ArtTile_SBZ_Moving_Block_Short = $2C0, palette line 1.
     * Used for subtype $28.
     * <p>
     * Shares Map_MBlock mappings with MZ, using frame 2.
     */
    private void loadSbzMovingBlockShortArt(Sonic1ObjectArt art) {
        Pattern[] patterns = art.loadNemesisPatterns(
                Sonic1Constants.ART_NEM_SBZ_STOMPER_ADDR);
        if (patterns.length == 0) {
            LOGGER.warning("Failed to load SBZ short moving block art");
            return;
        }

        List<SpriteMappingFrame> mappings = art.loadMappingFrames(Sonic1Constants.MAP_MZ_SBZ_MOVING_BLOCK_ADDR);
        // make_art_tile(ArtTile_SBZ_Moving_Block_Short, 1, 0) -> palette line 1, no priority
        ObjectSpriteSheet sheet = new ObjectSpriteSheet(patterns, mappings, 1, 1);
        registerSheet(ObjectArtKeys.SBZ_MOVING_BLOCK_SHORT, sheet);
    }

    /**
     * Loads SBZ Moving Block (long) art (Nem_SlideFloor).
     * <p>
     * From docs/s1disasm/_incObj/52 Moving Blocks.asm:
     * <pre>
     *   move.w  #make_art_tile(ArtTile_SBZ_Moving_Block_Long,2,0),obGfx(a0)
     * </pre>
     * ArtTile_SBZ_Moving_Block_Long = $460, palette line 2.
     * Used for SBZ subtypes other than $28 (e.g., $39).
     * <p>
     * Shares Map_MBlock mappings with MZ.
     */
    private void loadSbzMovingBlockLongArt(Sonic1ObjectArt art) {
        Pattern[] patterns = art.loadNemesisPatterns(
                Sonic1Constants.ART_NEM_SBZ_SLIDE_FLOOR_ADDR);
        if (patterns.length == 0) {
            LOGGER.warning("Failed to load SBZ long moving block art");
            return;
        }

        List<SpriteMappingFrame> mappings = art.loadMappingFrames(Sonic1Constants.MAP_MZ_SBZ_MOVING_BLOCK_ADDR);
        // make_art_tile(ArtTile_SBZ_Moving_Block_Long, 2, 0) -> palette line 2, no priority
        ObjectSpriteSheet sheet = new ObjectSpriteSheet(patterns, mappings, 2, 1);
        registerSheet(ObjectArtKeys.SBZ_MOVING_BLOCK_LONG, sheet);
    }

    /**
     * Loads SBZ Stomper and Door art (Object 0x6B).
     * <p>
     * Combines two Nemesis art blocks into one sprite sheet:
     * <ul>
     *   <li>Nem_Stomper (ArtTile_SBZ_Moving_Block_Short = $2C0) - stomper block frames</li>
     *   <li>Nem_SbzDoor2 (ArtTile_SBZ_Horizontal_Door = $46F) - horizontal sliding door frame</li>
     * </ul>
     * <p>
     * The stomper patterns occupy the first N indices. The door patterns are appended
     * starting at index N. Mapping tile indices for the door frame are remapped from
     * their original VRAM offsets ($1AF, $1B2 relative to $2C0) to the compact array.
     * <p>
     * obGfx: make_art_tile(ArtTile_SBZ_Moving_Block_Short,1,0) -> palette line 1.
     * <p>
     * Map_Stomp has 5 frames:
     * <ul>
     *   <li>Frame 0 (.door): 4 pieces using Nem_SbzDoor2 tiles (horizontal sliding door)</li>
     *   <li>Frame 1-3 (.stomper): 8 pieces each using Nem_Stomper tiles (stomper block)</li>
     *   <li>Frame 4 (.bigdoor): SBZ3 diagonal door using level tiles (separate sheet)</li>
     * </ul>
     * <p>
     * Reference: docs/s1disasm/_incObj/6B SBZ Stomper and Door.asm
     * Reference: docs/s1disasm/_maps/SBZ Stomper and Door.asm
     */
    /**
     * Loads SBZ Running Disc spot art (Nem_SbzWheel1) and creates S1-format sprite mappings.
     * <p>
     * From docs/s1disasm/_incObj/67 Running Disc.asm:
     * <pre>
     *   move.l  #Map_Disc,obMap(a0)
     *   move.w  #make_art_tile(ArtTile_SBZ_Disc,2,1),obGfx(a0)
     * </pre>
     * ArtTile_SBZ_Disc = $344, palette line 2, priority bit 1.
     * <p>
     * The mappings consist of a single frame: one 2x2 (16x16) piece at (-8,-8).
     * From docs/s1disasm/_maps/Running Disc.asm: spritePiece -8, -8, 2, 2, 0, 0, 0, 0, 0
     */
    private void loadSbzRunningDiscArt(Sonic1ObjectArt art) {
        Pattern[] patterns = art.loadNemesisPatterns(
                Sonic1Constants.ART_NEM_SBZ_RUNNING_DISC_ADDR);
        if (patterns.length == 0) {
            LOGGER.warning("Failed to load SBZ running disc art");
            return;
        }

        List<SpriteMappingFrame> mappings = art.loadMappingFrames(Sonic1Constants.MAP_SBZ_RUNNING_DISC_ADDR);

        // make_art_tile(ArtTile_SBZ_Disc, 2, 1) -> palette line 2, priority bit 1
        ObjectSpriteSheet sheet = new ObjectSpriteSheet(patterns, mappings, 2, 1);
        registerSheet(ObjectArtKeys.SBZ_RUNNING_DISC, sheet);
    }

    /**
     * Loads SBZ junction wheel art (Object 0x66).
     * Nemesis art: Nem_SbzWheel2 at ART_NEM_SBZ_JUNCTION_ADDR.
     * Art tile: make_art_tile(ArtTile_SBZ_Junction, 2, 0) -> palette line 2, no priority.
     * 17 mapping frames: 0-15 = gap at 16 rotational positions, 16 = full circle (child display).
     * Reference: docs/s1disasm/_maps/Rotating Junction.asm
     */
    private void loadSbzJunctionArt(Sonic1ObjectArt art) {
        Pattern[] patterns = art.loadNemesisPatterns(
                Sonic1Constants.ART_NEM_SBZ_JUNCTION_ADDR);
        if (patterns.length == 0) {
            LOGGER.warning("Failed to load SBZ junction art");
            return;
        }

        List<SpriteMappingFrame> mappings = art.loadMappingFrames(Sonic1Constants.MAP_SBZ_JUNCTION_ADDR);

        // make_art_tile(ArtTile_SBZ_Junction, 2, 0) -> palette line 2, no priority
        ObjectSpriteSheet sheet = new ObjectSpriteSheet(patterns, mappings, 2, 1);
        registerSheet(ObjectArtKeys.SBZ_JUNCTION, sheet);
    }

    private void loadSbzStomperDoorArt(Sonic1ObjectArt art) {
        Pattern[] stomperPatterns = art.loadNemesisPatterns(
                Sonic1Constants.ART_NEM_SBZ_STOMPER_ADDR);
        Pattern[] doorPatterns = art.loadNemesisPatterns(
                Sonic1Constants.ART_NEM_SBZ_HORIZONTAL_DOOR_ADDR);
        if (stomperPatterns.length == 0) {
            LOGGER.warning("Failed to load SBZ stomper art for Object 0x6B");
            return;
        }

        // Build combined patterns array: [stomperPatterns..., doorPatterns...]
        int stomperCount = stomperPatterns.length;
        int doorCount = doorPatterns.length;
        Pattern[] combined = new Pattern[stomperCount + doorCount];
        System.arraycopy(stomperPatterns, 0, combined, 0, stomperCount);
        System.arraycopy(doorPatterns, 0, combined, stomperCount, doorCount);

        // Remap door tile indices:
        // Original: tile $1AF relative to ArtTile_SBZ_Moving_Block_Short ($2C0)
        //   -> absolute VRAM tile = $2C0 + $1AF = $46F = ArtTile_SBZ_Horizontal_Door
        //   -> door pattern index 0 in Nem_SbzDoor2
        //   -> in combined array: stomperCount + 0
        // Original: tile $1B2 -> absolute $472 -> door pattern index 3
        //   -> in combined array: stomperCount + 3
        List<SpriteMappingFrame> mappings = createStomperDoorMappingsFromRom(
                art.loadMappingFrames(Sonic1Constants.MAP_SBZ_STOMPER_DOOR_ADDR), stomperCount);
        // make_art_tile(ArtTile_SBZ_Moving_Block_Short, 1, 0) -> palette line 1
        ObjectSpriteSheet sheet = new ObjectSpriteSheet(combined, mappings, 1, 1);
        registerSheet(ObjectArtKeys.SBZ_STOMPER_DOOR, sheet);
    }

    /**
     * Builds sprite mappings for the SBZ Stomper and Door (Object 0x6B) from ROM data.
     * <p>
     * From docs/s1disasm/_maps/SBZ Stomper and Door.asm (Map_Stomp_internal):
     * <ul>
     *   <li>Frame 0 (.door): 4 pieces, 128x24 horizontal sliding door</li>
     *   <li>Frame 1 (.stomper): 8 pieces, 56x64 stomper block with yellow/black stripes</li>
     *   <li>Frame 2 (.stomper): Same as frame 1 (duplicate entry in table)</li>
     *   <li>Frame 3 (.stomper): Same as frame 1 (duplicate entry in table)</li>
     * </ul>
     * <p>
     * Frame 4 (.bigdoor) is excluded; it uses level tiles and is handled
     * by a separate sheet registered via {@link #registerSbz3BigDoorSheet}.
     *
     * @param doorBase index in the combined pattern array where Nem_SbzDoor2 starts
     */
    static List<SpriteMappingFrame> createStomperDoorMappingsFromRom(
            List<SpriteMappingFrame> rawFrames, int doorBase) {
        if (rawFrames.size() < 4) {
            return List.of();
        }
        List<SpriteMappingFrame> frames = new ArrayList<>(4);
        frames.add(remapStomperDoorFrame(rawFrames.get(0), doorBase));
        frames.add(rawFrames.get(1));
        frames.add(rawFrames.get(2));
        frames.add(rawFrames.get(3));
        return List.copyOf(frames);
    }

    private static SpriteMappingFrame remapStomperDoorFrame(SpriteMappingFrame rawFrame, int doorBase) {
        return new SpriteMappingFrame(rawFrame.pieces().stream()
                .map(piece -> SpriteMappingPieces.withTileIndex(piece, doorBase + (piece.tileIndex() - 0x1AF)))
                .toList());
    }

    /**
     * Registers the SBZ3 Big Door sprite sheet using level tile patterns.
     * Must be called AFTER the level is loaded since the big door uses level art at
     * {@code ArtTile_Level+$1F0} (palette line 2).
     * <p>
     * Only relevant when zone == LZ (SBZ3 reuses the LZ zone slot).
     * <p>
     * Frame 4 (.bigdoor) from docs/s1disasm/_maps/SBZ Stomper and Door.asm:
     * 14 pieces forming a 256x128 diagonal door.
     * <p>
     * Reference: docs/s1disasm/_incObj/6B SBZ Stomper and Door.asm (.isSBZ3)
     *
     * @param level     The loaded level to extract patterns from
     * @param zoneIndex The current zone index
     */
    public void registerSbz3BigDoorSheet(Level level, int zoneIndex) {
        if (level == null || zoneIndex != Sonic1Constants.ZONE_LZ) {
            return;
        }

        // Big door tiles start at ArtTile_Level + $1F0
        // Highest tile offset in .bigdoor: $58 + (4*4 - 1) = $67
        // Absolute max pattern index = $1F0 + $67 = $257
        int tileBase = 0x1F0;
        int maxTileNeeded = tileBase + 0x68;
        int patternCount = level.getPatternCount();
        int copyCount = Math.min(patternCount, maxTileNeeded);
        if (copyCount <= tileBase) {
            LOGGER.warning("Not enough level patterns for SBZ3 big door");
            return;
        }
        Pattern[] patterns = new Pattern[copyCount];
        for (int i = 0; i < copyCount; i++) {
            patterns[i] = level.getPattern(i);
        }

        List<SpriteMappingFrame> mappings = createSbz3BigDoorMappingsFromRom(
                loadMappingFrames(Sonic1Constants.MAP_SBZ_STOMPER_DOOR_ADDR), tileBase);
        // make_art_tile(ArtTile_Level+$1F0, 2, 0) -> palette line 2
        ObjectSpriteSheet sheet = new ObjectSpriteSheet(patterns, mappings, 2, 1);
        registerSheet(ObjectArtKeys.SBZ3_BIG_DOOR, sheet);
    }

    /**
     * Builds the SBZ3 big diagonal door mapping from ROM data.
     * <p>
     * From docs/s1disasm/_maps/SBZ Stomper and Door.asm (.bigdoor):
     * 14 pieces forming a 256x128 diagonal sliding door.
     *
     * @param tileBase the base tile index ($1F0) in the patterns array
     */
    static List<SpriteMappingFrame> createSbz3BigDoorMappingsFromRom(
            List<SpriteMappingFrame> rawFrames, int tileBase) {
        if (rawFrames.size() < 5) {
            return List.of();
        }
        return List.of(new SpriteMappingFrame(rawFrames.get(4).pieces().stream()
                .map(piece -> SpriteMappingPieces.withTileIndex(piece, tileBase + piece.tileIndex()))
                .toList()));
    }

    /**
     * Registers the MZ Brick sprite sheet using level tile patterns.
     * Must be called AFTER the level is loaded since bricks use zone tileset art
     * (make_art_tile(ArtTile_Level, 2, 0)).
     * <p>
     * MZ only. Single frame from docs/s1disasm/_maps/MZ Bricks.asm (Map_Brick_internal):
     * One 32x32 brick (4x4 piece at tile index 1).
     *
     * @param level     The loaded level to extract patterns from
     * @param zoneIndex The current zone index
     */
    public void registerMzBrickSheet(Level level, int zoneIndex) {
        if (level == null || zoneIndex != Sonic1Constants.ZONE_MZ) {
            return;
        }

        List<SpriteMappingFrame> mappings = loadMappingFrames(Sonic1Constants.MAP_MZ_BRICK_ADDR);

        // Highest tile: 0x01 + (4*4) = 0x11
        int maxTileNeeded = 0x11;
        int patternCount = level.getPatternCount();
        int copyCount = Math.min(patternCount, maxTileNeeded);
        if (copyCount == 0) {
            LOGGER.warning("No level patterns available for MZ brick art");
            return;
        }
        Pattern[] patterns = new Pattern[copyCount];
        for (int i = 0; i < copyCount; i++) {
            patterns[i] = level.getPattern(i);
        }

        // Palette line 2 (make_art_tile(ArtTile_Level, 2, 0))
        ObjectSpriteSheet sheet = new ObjectSpriteSheet(patterns, mappings, 2, 1);
        registerSheet(ObjectArtKeys.MZ_BRICK, sheet);
    }

    /**
     * Registers the SYZ spinning light sprite sheet using level tile patterns.
     * Must be called AFTER the level is loaded since the lamp uses zone tileset art
     * (make_art_tile(ArtTile_Level,0,0)).
     * <p>
     * 6 frames of 32x16 animation. Each frame has two 4x1 tile pieces,
     * the second v-flipped. Tile indices progress by 4 per frame:
     * 0x31, 0x35, 0x39, 0x3D, 0x41, 0x45.
     * <p>
     * Reference: docs/s1disasm/_incObj/12 Light.asm, docs/s1disasm/_maps/Light.asm
     *
     * @param level the loaded level to extract patterns from
     */
    public void registerSpinningLightSheet(Level level) {
        if (level == null) {
            return;
        }

        List<SpriteMappingFrame> mappings = loadMappingFrames(Sonic1Constants.MAP_SYZ_SPINNING_LIGHT_ADDR);
        // Highest tile: 0x45 + 4 = 0x49
        int maxTileNeeded = 0x49;

        int patternCount = level.getPatternCount();
        int copyCount = Math.min(patternCount, maxTileNeeded);
        if (copyCount == 0) {
            LOGGER.warning("No level patterns available for spinning light art");
            return;
        }
        Pattern[] patterns = new Pattern[copyCount];
        for (int i = 0; i < copyCount; i++) {
            patterns[i] = level.getPattern(i);
        }

        // Palette line 0 (make_art_tile(ArtTile_Level, 0, 0))
        ObjectSpriteSheet sheet = new ObjectSpriteSheet(patterns, mappings, 0, 1);
        registerSheet(ObjectArtKeys.SYZ_SPINNING_LIGHT, sheet);
    }

    /**
     * Registers the SYZ boss block sprite sheet using level tile patterns.
     * Must be called AFTER the level is loaded since the block uses zone tileset art
     * (make_art_tile(ArtTile_Level,2,0)).
     * <p>
     * 5 frames: whole block (32x32) + 4 quarter fragments (16x16 each).
     * Tile indices start at $71 from the zone tileset. Palette line 2.
     * <p>
     * Reference: docs/s1disasm/_incObj/76 SYZ Boss Blocks.asm,
     * docs/s1disasm/_maps/SYZ Boss Blocks.asm
     *
     * @param level the loaded level to extract patterns from
     */
    public void registerBossBlockSheet(Level level) {
        if (level == null) {
            return;
        }

        List<SpriteMappingFrame> mappings = loadMappingFrames(Sonic1Constants.MAP_SYZ_BOSS_BLOCK_ADDR);
        // Highest tile: $7D + (2*2) = $81
        int maxTileNeeded = 0x81;

        int patternCount = level.getPatternCount();
        int copyCount = Math.min(patternCount, maxTileNeeded);
        if (copyCount == 0) {
            LOGGER.warning("No level patterns available for boss block art");
            return;
        }
        Pattern[] patterns = new Pattern[copyCount];
        for (int i = 0; i < copyCount; i++) {
            patterns[i] = level.getPattern(i);
        }

        // ROM: make_art_tile(ArtTile_Level,2,0) — palette line 2
        ObjectSpriteSheet sheet = new ObjectSpriteSheet(patterns, mappings, 2, 1);
        registerSheet(ObjectArtKeys.SYZ_BOSS_BLOCK, sheet);
    }

    /**
     * Loads Giant Ring art (Art_BigRing) - uncompressed 98-tile ring sprite.
     * Mappings from ROM Map_GRing.
     * 4 frames: front view, angled, edge-on, angled reverse.
     */
    private void loadGiantRingArt(Sonic1ObjectArt art) {
        Pattern[] patterns = art.loadUncompressedPatterns(
                Sonic1Constants.ART_UNC_GIANT_RING_ADDR,
                Sonic1Constants.ART_UNC_GIANT_RING_SIZE);
        if (patterns.length == 0) {
            LOGGER.warning("Failed to load Giant Ring art");
            return;
        }

        List<SpriteMappingFrame> mappings = art.loadMappingFrames(Sonic1Constants.MAP_GIANT_RING_ADDR);
        ObjectSpriteSheet sheet = new ObjectSpriteSheet(patterns, mappings, 1, 1);
        registerSheet(ObjectArtKeys.GIANT_RING, sheet);
    }

    /**
     * Loads Giant Ring Flash art (Nem_BigFlash) - Nemesis-compressed flash sprite.
     * Mappings from ROM Map_Flash.
     * 8 frames of expanding flash effect.
     */
    private void loadGiantRingFlashArt(Sonic1ObjectArt art) {
        Pattern[] patterns = art.loadNemesisPatterns(
                Sonic1Constants.ART_NEM_GIANT_RING_FLASH_ADDR);
        if (patterns.length == 0) {
            LOGGER.warning("Failed to load Giant Ring Flash art");
            return;
        }

        List<SpriteMappingFrame> mappings = art.loadMappingFrames(Sonic1Constants.MAP_GIANT_RING_FLASH_ADDR);
        ObjectSpriteSheet sheet = new ObjectSpriteSheet(patterns, mappings, 1, 1);
        registerSheet(ObjectArtKeys.GIANT_RING_FLASH, sheet);
    }

    /**
     * Loads hidden bonus point popup art (Nem_Bonus) and creates mappings.
     * <p>
     * From docs/s1disasm/_incObj/7D Hidden Bonuses.asm:
     * make_art_tile(ArtTile_Hidden_Points,0,1) — palette line 0, priority bit set.
     * <p>
     * Mappings from docs/s1disasm/_maps/Hidden Bonuses.asm (Map_Bonus_internal):
     * <ul>
     *   <li>Frame 0: .blank — 0 pieces (no rendering)</li>
     *   <li>Frame 1: ._10000 — 1 piece, 4x3 (32x24), pattern $00, at (-$10, -$C)</li>
     *   <li>Frame 2: ._1000 — 1 piece, 4x3 (32x24), pattern $0C, at (-$10, -$C)</li>
     *   <li>Frame 3: ._100 — 1 piece, 4x3 (32x24), pattern $18, at (-$10, -$C)</li>
     * </ul>
     */
    private void loadHiddenBonusArt(Sonic1ObjectArt art) {
        Pattern[] patterns = art.loadNemesisPatterns(
                Sonic1Constants.ART_NEM_HIDDEN_BONUS_ADDR);
        if (patterns.length == 0) {
            LOGGER.warning("Failed to load hidden bonus art");
            return;
        }

        List<SpriteMappingFrame> mappings = art.loadMappingFrames(Sonic1Constants.MAP_HIDDEN_BONUS_ADDR);
        // make_art_tile(ArtTile_Hidden_Points, 0, 1) — palette 0, priority set
        ObjectSpriteSheet sheet = new ObjectSpriteSheet(patterns, mappings, 0, 1);
        registerSheet(ObjectArtKeys.HIDDEN_BONUS, sheet);
    }

    /**
     * Creates hidden bonus sprite mappings from S1 disassembly Map_Bonus_internal.
     * <p>
     * S1 5-byte piece format: y_offset, size, pattern_word, x_offset.
     * Size 0x05 = 2x2 (16x16 pixels).
     */
    private List<SpriteMappingFrame> createHiddenBonusMappings() {
        List<SpriteMappingFrame> frames = new ArrayList<>();

        // Frame 0: .blank — 0 pieces (empty, used for subtype 0 = 0 points)
        frames.add(new SpriteMappingFrame(List.of()));

        // Frame 1: ._10000 — spritePiece -$10, -$C, 4, 3, 0, 0, 0, 0, 0
        frames.add(new SpriteMappingFrame(List.of()));

        // Frame 2: ._1000 — spritePiece -$10, -$C, 4, 3, $C, 0, 0, 0, 0
        frames.add(new SpriteMappingFrame(List.of()));

        // Frame 3: ._100 — spritePiece -$10, -$C, 4, 3, $18, 0, 0, 0, 0
        frames.add(new SpriteMappingFrame(List.of()));

        return frames;
    }

    /**
     * Loads spiked pole helix art (GHZ only).
     * <p>
     * Art: Nem_SpikePole (ArtTile_GHZ_Spike_Pole = $398, palette line 2).
     * 8 mapping frames representing the spike ball at different rotation angles.
     * <p>
     * Disassembly: docs/s1disasm/_incObj/17 Spiked Pole Helix.asm.
     */
    private void loadSpikedPoleHelixArt(Sonic1ObjectArt art) {
        Pattern[] patterns = art.loadNemesisPatterns(
                Sonic1Constants.ART_NEM_SPIKE_POLE_ADDR);
        if (patterns.length == 0) {
            return;
        }
        List<SpriteMappingFrame> mappings = art.loadMappingFrames(Sonic1Constants.MAP_SPIKED_POLE_HELIX_ADDR);
        // make_art_tile(ArtTile_GHZ_Spike_Pole, 2, 0) — palette line 2
        ObjectSpriteSheet sheet = new ObjectSpriteSheet(patterns, mappings, 2, 1);
        registerSheet(ObjectArtKeys.SPIKED_POLE_HELIX, sheet);
    }

    /**
     * Loads swinging platform art for the current zone.
     * <p>
     * Zone-specific art from Pattern Load Cues:
     * <ul>
     *   <li>GHZ: Nem_Swing (ArtTile $380, palette 2) + Nem_Ball (ArtTile $3AA, palette 2)</li>
     *   <li>MZ:  Nem_Swing (ArtTile $380, palette 2)</li>
     *   <li>SLZ: Nem_SlzSwing (ArtTile $3DC, palette 2)</li>
     *   <li>SBZ: Nem_SyzSpike1 (ArtTile $391, palette 0)</li>
     * </ul>
     */
    private void loadSwingingPlatformArt(Sonic1ObjectArt art, int zoneIndex) {
        // GHZ/MZ swinging platform (Nem_Swing)
        if (zoneIndex == Sonic1Constants.ZONE_GHZ || zoneIndex == Sonic1Constants.ZONE_MZ) {
            Pattern[] patterns = art.loadNemesisPatterns(
                    Sonic1Constants.ART_NEM_SWING_ADDR);
            if (patterns.length > 0) {
                List<SpriteMappingFrame> mappings = art.loadMappingFrames(Sonic1Constants.MAP_SWING_GHZ_ADDR);
                // make_art_tile(ArtTile_GHZ_MZ_Swing, 2, 0) — palette line 2
                ObjectSpriteSheet sheet = new ObjectSpriteSheet(patterns, mappings, 2, 1);
                registerSheet(ObjectArtKeys.SWING_GHZ, sheet);
            }
        }

        // GHZ giant ball variant (Nem_Ball) — subtype $1X
        if (zoneIndex == Sonic1Constants.ZONE_GHZ) {
            Pattern[] patterns = art.loadNemesisPatterns(
                    Sonic1Constants.ART_NEM_GIANT_BALL_ADDR);
            if (patterns.length > 0) {
                List<SpriteMappingFrame> mappings = art.loadMappingFrames(Sonic1Constants.MAP_GIANT_BALL_ADDR);
                // make_art_tile(ArtTile_GHZ_Giant_Ball, 2, 0) — palette line 2
                ObjectSpriteSheet sheet = new ObjectSpriteSheet(patterns, mappings, 2, 1);
                registerSheet(ObjectArtKeys.SWING_GIANT_BALL, sheet);
            }
        }

        // SLZ swinging platform (Nem_SlzSwing)
        if (zoneIndex == Sonic1Constants.ZONE_SLZ) {
            Pattern[] patterns = art.loadNemesisPatterns(
                    Sonic1Constants.ART_NEM_SLZ_SWING_ADDR);
            if (patterns.length > 0) {
                List<SpriteMappingFrame> mappings = art.loadMappingFrames(Sonic1Constants.MAP_SWING_SLZ_ADDR);
                // make_art_tile(ArtTile_SLZ_Swing, 2, 0) — palette line 2
                ObjectSpriteSheet sheet = new ObjectSpriteSheet(patterns, mappings, 2, 1);
                registerSheet(ObjectArtKeys.SWING_SLZ, sheet);
            }
        }

        // SBZ spiked ball on chain (Nem_SyzSpike1)
        if (zoneIndex == Sonic1Constants.ZONE_SBZ) {
            Pattern[] patterns = art.loadNemesisPatterns(
                    Sonic1Constants.ART_NEM_SBZ_SPIKED_BALL_ADDR);
            if (patterns.length > 0) {
                List<SpriteMappingFrame> mappings = art.loadMappingFrames(Sonic1Constants.MAP_BIG_SPIKED_BALL_ADDR);
                // make_art_tile(ArtTile_SBZ_Swing, 0, 0) — palette line 0
                ObjectSpriteSheet sheet = new ObjectSpriteSheet(patterns, mappings, 0, 1);
                registerSheet(ObjectArtKeys.SWING_SBZ_BALL, sheet);
            }
        }
    }


    /**
     * Loads big spiked ball art for SYZ (Object 0x58).
     * Uses same Nemesis art (Nem_SyzSpike1) and mappings (Map_BBall) as SBZ ball on chain,
     * but loaded at ArtTile_SYZ_Big_Spikeball ($396) instead of ArtTile_SBZ_Swing ($391).
     * Only uses frame 0 (the ball sprite), but all 3 frames are registered for completeness.
     */
    /**
     * Loads SYZ pinball bumper art (Nem_Bumper) and creates S1-format sprite mappings.
     * <p>
     * Reference: docs/s1disasm/_incObj/47 Bumper.asm (Bump_Main)
     * Art: make_art_tile(ArtTile_SYZ_Bumper,0,0) = $380, palette line 0
     * Mappings from docs/s1disasm/_maps/Bumper.asm (Map_Bump_internal)
     * <p>
     * 3 frames:
     * <ul>
     *   <li>Frame 0 (.normal): 32x32 idle (2 pieces)</li>
     *   <li>Frame 1 (.bumped1): 24x24 compressed hit (2 pieces)</li>
     *   <li>Frame 2 (.bumped2): 32x32 expanded hit (2 pieces)</li>
     * </ul>
     */
    private void loadBumperArt(Sonic1ObjectArt art) {
        Pattern[] patterns = art.loadNemesisPatterns(
                Sonic1Constants.ART_NEM_BUMPER_ADDR);
        if (patterns.length == 0) {
            LOGGER.warning("Failed to load SYZ bumper art");
            return;
        }

        List<SpriteMappingFrame> mappings = art.loadMappingFrames(Sonic1Constants.MAP_SYZ_BUMPER_ADDR);
        // make_art_tile(ArtTile_SYZ_Bumper,0,0) — palette line 0
        ObjectSpriteSheet sheet = new ObjectSpriteSheet(patterns, mappings, 0, 1);
        registerSheet(ObjectArtKeys.BUMPER, sheet);
    }

    private void loadBigSpikedBallArt(Sonic1ObjectArt art) {
        Pattern[] patterns = art.loadNemesisPatterns(
                Sonic1Constants.ART_NEM_SBZ_SPIKED_BALL_ADDR);
        if (patterns.length > 0) {
            List<SpriteMappingFrame> mappings = art.loadMappingFrames(Sonic1Constants.MAP_BIG_SPIKED_BALL_ADDR);
            // make_art_tile(ArtTile_SYZ_Big_Spikeball, 0, 0) — palette line 0
            ObjectSpriteSheet sheet = new ObjectSpriteSheet(patterns, mappings, 0, 1);
            registerSheet(ObjectArtKeys.SYZ_BIG_SPIKED_BALL, sheet);
        }
    }

    /**
     * Loads SYZ spiked ball and chain art (Nem_SyzSpike2, Object 0x57).
     * <p>
     * From Pattern Load Cues: plcm Nem_SyzSpike2, ArtTile_SYZ_Spikeball_Chain
     * Map_SBall has 1 frame: 16x16 ball (same art for chain links and end ball in SYZ).
     * Palette line 0. Collision type $98 (hurt + size 0x18).
     */
    private void loadSyzSpikeballChainArt(Sonic1ObjectArt art) {
        Pattern[] patterns = art.loadNemesisPatterns(
                Sonic1Constants.ART_NEM_SYZ_SMALL_SPIKEBALL_ADDR);
        if (patterns.length > 0) {
            List<SpriteMappingFrame> mappings = art.loadMappingFrames(Sonic1Constants.MAP_SYZ_SPIKEBALL_CHAIN_ADDR);
            // make_art_tile(ArtTile_SYZ_Spikeball_Chain, 0, 0) — palette line 0
            ObjectSpriteSheet sheet = new ObjectSpriteSheet(patterns, mappings, 0, 1);
            registerSheet(ObjectArtKeys.SYZ_SPIKEBALL_CHAIN, sheet);
        }
    }


    /**
     * Loads LZ spiked ball and chain art (Nem_LzSpikeBall, Object 0x57).
     * <p>
     * From Pattern Load Cues: plcm Nem_LzSpikeBall, ArtTile_LZ_Spikeball_Chain
     * Map_SBall2 has 3 frames:
     *   Frame 0: chain link (16x16, tile 0)
     *   Frame 1: large spikeball (32x32, tile 4)
     *   Frame 2: wall base/attachment (16x16, tile $14)
     * Palette line 0.
     */
    private void loadLzSpikeballChainArt(Sonic1ObjectArt art) {
        Pattern[] patterns = art.loadNemesisPatterns(
                Sonic1Constants.ART_NEM_LZ_SPIKEBALL_ADDR);
        if (patterns.length > 0) {
            List<SpriteMappingFrame> mappings = art.loadMappingFrames(Sonic1Constants.MAP_LZ_SPIKEBALL_CHAIN_ADDR);
            // make_art_tile(ArtTile_LZ_Spikeball_Chain, 0, 0) — palette line 0
            ObjectSpriteSheet sheet = new ObjectSpriteSheet(patterns, mappings, 0, 1);
            registerSheet(ObjectArtKeys.LZ_SPIKEBALL_CHAIN, sheet);
        }
    }



    /**
     * Loads prison capsule art (Nem_Prison) and creates S1-format sprite mappings.
     * Mappings from docs/s1disasm/_maps/Prison Capsule.asm (Map_Pri_internal).
     * <p>
     * Appears in every zone's final act. Uses palette line 1 for capsule body,
     * palette line 0 for switch pieces.
     */
    private void loadPrisonArt(Sonic1ObjectArt art) {
        Pattern[] patterns = art.loadNemesisPatterns(
                Sonic1Constants.ART_NEM_PRISON_ADDR);
        if (patterns.length == 0) {
            LOGGER.warning("Failed to load prison capsule art");
            return;
        }

        List<SpriteMappingFrame> mappings = art.loadMappingFrames(Sonic1Constants.MAP_PRISON_ADDR);
        // Palette line 0 as base; capsule body pieces override to pal 1 per-piece
        ObjectSpriteSheet sheet = new ObjectSpriteSheet(patterns, mappings, 0, 1);
        registerSheet(ObjectArtKeys.EGG_PRISON, sheet);
    }

    /**
     * Loads boss art for GHZ: Eggman ship/face/flame, boss weapons (chain anchor),
     * and exhaust flame for escape sequence.
     * ROM: Nem_Eggman, Nem_Weapons, Nem_Exhaust.
     */
    private void loadBossArt(Sonic1ObjectArt art) {
        // Nem_Eggman: Main Eggman art (ship body, face variants, flames)
        Pattern[] eggmanPatterns = art.loadNemesisPatterns(
                Sonic1Constants.ART_NEM_EGGMAN_ADDR);

        // Nem_Exhaust: Boss exhaust/escape flame (ArtTile_Eggman_Exhaust = ArtTile_Eggman + $12A)
        // Escape flame frames 11-12 in Eggman mappings reference tiles $12A+.
        // Merge exhaust patterns into the Eggman array at offset $12A so a single
        // renderer can draw all frames including escape flames.
        Pattern[] exhaustPatterns = art.loadNemesisPatterns(
                Sonic1Constants.ART_NEM_BOSS_EXHAUST_ADDR);

        if (eggmanPatterns.length > 0) {
            // Merge: place exhaust patterns at offset $12A in a combined array
            int exhaustOffset = 0x12A; // ArtTile_Eggman_Exhaust - ArtTile_Eggman
            int mergedLength = Math.max(eggmanPatterns.length,
                    exhaustOffset + exhaustPatterns.length);
            Pattern[] mergedPatterns = java.util.Arrays.copyOf(eggmanPatterns, mergedLength);
            for (int i = 0; i < exhaustPatterns.length; i++) {
                mergedPatterns[exhaustOffset + i] = exhaustPatterns[i];
            }

            // Eggman uses make_art_tile(ArtTile_Eggman, 0, 0) — palette line 0
            // Ship body pieces use palette 1, face pieces use palette 0
            // (palette per-piece is encoded in the mappings)
            List<SpriteMappingFrame> mappings =
                    art.loadMappingFrames(Sonic1Constants.MAP_EGGMAN_ADDR);
            if (!mappings.isEmpty()) {
                ObjectSpriteSheet sheet = new ObjectSpriteSheet(mergedPatterns, mappings, 0, mappings.size());
                registerSheet(ObjectArtKeys.EGGMAN, sheet);
            }
        }

        // Nem_Weapons: Boss weapons art (chain anchor frames for ball/chain)
        Pattern[] weaponsPatterns = art.loadNemesisPatterns(
                Sonic1Constants.ART_NEM_BOSS_WEAPONS_ADDR);
        if (weaponsPatterns.length > 0) {
            List<SpriteMappingFrame> mappings =
                    art.loadMappingFrames(Sonic1Constants.MAP_BOSS_ITEMS_ADDR);
            if (!mappings.isEmpty()) {
                ObjectSpriteSheet sheet = new ObjectSpriteSheet(weaponsPatterns, mappings, 0, mappings.size());
                registerSheet(ObjectArtKeys.BOSS_WEAPONS, sheet);
            }
        }

        // GHZ Ball art (Nem_Ball) is already loaded by loadSwingingPlatformArt as SWING_GIANT_BALL.
        // The boss ball uses the same art with make_art_tile(ArtTile_GHZ_Giant_Ball, 2, 0).
        // Register an alias so boss code can find it under BOSS_BALL key too.
        ObjectSpriteSheet ballSheet = sheets.get(ObjectArtKeys.SWING_GIANT_BALL);
        if (ballSheet != null) {
            registerSheet(ObjectArtKeys.BOSS_BALL, ballSheet);
        }

        // Boss defeat explosions use getBossExplosionRenderer() which looks for the S2 key.
        // Register S1's standard explosion art under that key so BossExplosionObjectInstance works.
        // S1 explosion has 5 frames (vs S2's 7); frames 5-6 will be no-ops (graceful bounds check).
        ObjectSpriteSheet explosionSheet = sheets.get(ObjectArtKeys.EXPLOSION);
        if (explosionSheet != null) {
            registerSheet(ObjectArtKeys.BOSS_EXPLOSION,
                    explosionSheet);
        }
    }

    /**
     * Loads FZ boss art and creates sprite sheets for all FZ components.
     * Art comes from multiple Nemesis banks:
     *   Nem_FzBoss      -> ArtTile_FZ_Boss ($300): cylinders, plasma, cockpit
     *   Nem_FzEggman    -> ArtTile_FZ_Eggman_Fleeing ($3A0): escape legs + damaged ship
     *   Nem_Sbz2Eggman  -> ArtTile_FZ_Eggman_No_Vehicle ($470): Eggman body (Map_SEgg)
     *   Nem_Eggman      -> ArtTile_Eggman: standard boss ship (escape phase)
     * PLC reference: _inc/Pattern Load Cues.asm line 387
     */
    private void loadFZBossArt(Sonic1ObjectArt art) {
        // Nem_FzBoss: Art for cylinders, plasma, cockpit shell
        Pattern[] fzPatterns = art.loadNemesisPatterns(Sonic1Constants.ART_NEM_FZ_BOSS_ADDR);
        if (fzPatterns.length == 0) return;

        // Nem_Sbz2Eggman: Eggman body without vehicle — used by Map_SEgg
        // PLC loads this to ArtTile_FZ_Eggman_No_Vehicle ($470)
        Pattern[] seggPatterns = art.loadNemesisPatterns(Sonic1Constants.ART_NEM_SBZ2_EGGMAN_ADDR);

        // Nem_FzEggman: escape legs and damaged ship overlays (Map_FZLegs/Map_FZDamaged)
        // PLC loads this to ArtTile_FZ_Eggman_Fleeing ($3A0).
        Pattern[] fzEggmanPatterns = art.loadNemesisPatterns(Sonic1Constants.ART_NEM_FZ_EGGMAN_ADDR);

        // FZ_SEGG: Eggman in machine (Map_SEgg) — combat phase rendering.
        // Map_SEgg's intube frame uses pattern word $6F0. On hardware this is added to
        // obGfx (ArtTile_FZ_Eggman_No_Vehicle = $470), wrapping in 11-bit VRAM tile space
        // and spilling into flag bits. To preserve ROM behavior, build a combined tile bank
        // anchored at ArtTile_FZ_Boss ($300) and pre-resolve mapping words through the same
        // add.w behavior.
        List<SpriteMappingFrame> seggRawMappings =
                art.loadMappingFrames(Sonic1Constants.MAP_SEGG_ADDR);
        if (!seggRawMappings.isEmpty()) {
            Pattern[] seggSource = seggPatterns.length > 0 ? seggPatterns : fzPatterns;
            int virtualBaseTile = Sonic1Constants.ART_TILE_FZ_BOSS;
            int seggBaseOffset = Sonic1Constants.ART_TILE_FZ_EGGMAN_NO_VEHICLE - virtualBaseTile;
            int combinedSeggSize = Math.max(fzPatterns.length, seggBaseOffset + seggSource.length);
            Pattern[] seggCombined = new Pattern[combinedSeggSize];
            for (int i = 0; i < seggCombined.length; i++) {
                seggCombined[i] = new Pattern();
            }
            System.arraycopy(fzPatterns, 0, seggCombined, 0, fzPatterns.length);
            System.arraycopy(seggSource, 0, seggCombined, seggBaseOffset, seggSource.length);
            List<SpriteMappingFrame> seggMappings = remapMappingsForObjectBase(
                    seggRawMappings,
                    Sonic1Constants.ART_TILE_FZ_EGGMAN_NO_VEHICLE,
                    virtualBaseTile
            );
            registerSheet(ObjectArtKeys.FZ_SEGG,
                    new ObjectSpriteSheet(seggCombined, seggMappings, 0, seggMappings.size()));
        }

        // FZ_CYLINDER: Crushing cylinders (Map_EggCyl)
        List<SpriteMappingFrame> cylMappings =
                art.loadMappingFrames(Sonic1Constants.MAP_FZ_EGGCYL_ADDR);
        if (!cylMappings.isEmpty()) {
            registerSheet(ObjectArtKeys.FZ_CYLINDER,
                    new ObjectSpriteSheet(fzPatterns, cylMappings, 0, cylMappings.size()));
        }

        // FZ_PLASMA_LAUNCHER: Plasma turret (Map_PLaunch)
        List<SpriteMappingFrame> launchMappings =
                art.loadMappingFrames(Sonic1Constants.MAP_FZ_PLAUNCH_ADDR);
        if (!launchMappings.isEmpty()) {
            registerSheet(ObjectArtKeys.FZ_PLASMA_LAUNCHER,
                    new ObjectSpriteSheet(fzPatterns, launchMappings, 0, launchMappings.size()));
        }

        // FZ_PLASMA: Energy ball projectiles (Map_Plasma)
        List<SpriteMappingFrame> plasmaMappings =
                art.loadMappingFrames(Sonic1Constants.MAP_FZ_PLASMA_ADDR);
        if (!plasmaMappings.isEmpty()) {
            registerSheet(ObjectArtKeys.FZ_PLASMA,
                    new ObjectSpriteSheet(fzPatterns, plasmaMappings, 0, plasmaMappings.size()));
        }

        // FZ_LEGS: Escape ship legs (Map_FZLegs), sourced from Nem_FzEggman.
        if (fzEggmanPatterns.length > 0) {
            List<SpriteMappingFrame> legsMappings =
                    art.loadMappingFrames(Sonic1Constants.MAP_FZ_LEGS_ADDR);
            if (!legsMappings.isEmpty()) {
                registerSheet(ObjectArtKeys.FZ_LEGS,
                        new ObjectSpriteSheet(fzEggmanPatterns, legsMappings, 0, legsMappings.size()));
            }
        }

        // Standard Eggman art for escape phase (Map_Eggman)
        // Also loads exhaust flame merged at tile offset $12A
        loadBossArt(art);

        // FZ_DAMAGED: Damaged ship mapping (Map_FZDamaged), sourced from Nem_FzEggman.
        if (fzEggmanPatterns.length > 0) {
            List<SpriteMappingFrame> damagedMappings =
                    art.loadMappingFrames(Sonic1Constants.MAP_FZ_DAMAGED_ADDR);
            if (!damagedMappings.isEmpty()) {
                registerSheet(ObjectArtKeys.FZ_DAMAGED,
                        new ObjectSpriteSheet(fzEggmanPatterns, damagedMappings, 0, damagedMappings.size()));
            }
        }
    }

    private static List<SpriteMappingFrame> remapMappingsForObjectBase(List<SpriteMappingFrame> sourceMappings,
                                                                        int objectBaseTile,
                                                                        int virtualBaseTile) {
        List<SpriteMappingFrame> remapped = new ArrayList<>(sourceMappings.size());
        for (SpriteMappingFrame frame : sourceMappings) {
            List<SpriteMappingPiece> remappedPieces = new ArrayList<>(frame.pieces().size());
            for (SpriteMappingPiece piece : frame.pieces()) {
                int rawPatternWord = SpriteMappingPieces.toTileWord(piece);
                int summedPatternWord = (objectBaseTile + rawPatternWord) & 0xFFFF;

                int remappedTile = (summedPatternWord & 0x7FF) - virtualBaseTile;
                boolean remappedHFlip = (summedPatternWord & 0x0800) != 0;
                boolean remappedVFlip = (summedPatternWord & 0x1000) != 0;
                int remappedPalette = (summedPatternWord >> 13) & 0x3;
                boolean remappedPriority = (summedPatternWord & 0x8000) != 0;

                remappedPieces.add(SpriteMappingPieces.withAttributes(
                        piece,
                        remappedTile,
                        remappedHFlip,
                        remappedVFlip,
                        remappedPalette,
                        remappedPriority));
            }
            remapped.add(new SpriteMappingFrame(remappedPieces));
        }
        return remapped;
    }

    /**
     * Registers the floating block/door sprite sheets.
     * <p>
     * SYZ/SLZ blocks use level tile patterns (ArtTile_Level), so this must be called
     * AFTER the level is loaded. LZ doors use dedicated Nemesis-compressed art
     * (Nem_LzDoor1 for vertical doors, both combined for the full frame set).
     * <p>
     * Mappings from docs/s1disasm/_maps/Floating Blocks and Doors.asm (Map_FBlock):
     * <ul>
     *   <li>Frame 0 (.syz1x1):     1 piece,  SYZ 32x32 square block</li>
     *   <li>Frame 1 (.syz2x2):     4 pieces, SYZ 64x64 quad block</li>
     *   <li>Frame 2 (.syz1x2):     2 pieces, SYZ 32x64 tall block</li>
     *   <li>Frame 3 (.syzrect2x2): 4 pieces, SYZ 64x52 rectangular blocks (tile $81)</li>
     *   <li>Frame 4 (.syzrect1x3): 3 pieces, SYZ 32x78 tall rectangular blocks (tile $81)</li>
     *   <li>Frame 5 (.slz):        1 piece,  SLZ 32x32 square block (tile $21)</li>
     *   <li>Frame 6 (.lzvert):     2 pieces, LZ 16x64 vertical door</li>
     *   <li>Frame 7 (.lzhoriz):    4 pieces, LZ 128x32 horizontal door (tile $22)</li>
     * </ul>
     *
     * @param level     The loaded level to extract patterns from (for SYZ/SLZ)
     * @param zoneIndex The current zone index
     */
    public void registerFloatingBlockSheet(Level level, int zoneIndex) {
        if (zoneIndex == Sonic1Constants.ZONE_LZ) {
            // LZ doors: load dedicated Nemesis art (both vert and horiz combined)
            try {
                Rom rom = GameServices.rom().getRom();
                if (rom != null) {
                    RomByteReader reader = RomByteReader.fromRom(rom);
                    Sonic1ObjectArt art = new Sonic1ObjectArt(rom, reader);
                    registerLzFloatingBlockSheet(art);
                }
            } catch (IOException e) {
                LOGGER.warning("Failed to get ROM for LZ floating block art: " + e.getMessage());
            }
        } else {
            // SYZ/SLZ: use level tile patterns
            registerSyzSlzFloatingBlockSheet(level);
        }
    }

    /**
     * Registers the SYZ/SLZ floating block sheet using level tile patterns.
     * These blocks use make_art_tile(ArtTile_Level,2,0) — palette line 2.
     */
    private void registerSyzSlzFloatingBlockSheet(Level level) {
        if (level == null) {
            return;
        }

        List<SpriteMappingFrame> mappings = createFloatingBlockMappingsSyzSlz();

        // Highest tile index used: frame 3/4 use tile $81 + (4*4-1) = $90
        int maxTileNeeded = 0x91;
        int patternCount = level.getPatternCount();
        int copyCount = Math.min(patternCount, maxTileNeeded);
        if (copyCount == 0) {
            LOGGER.warning("No level patterns available for floating block art");
            return;
        }
        Pattern[] patterns = new Pattern[copyCount];
        for (int i = 0; i < copyCount; i++) {
            patterns[i] = level.getPattern(i);
        }

        // Palette line 2 (make_art_tile(ArtTile_Level, 2, 0))
        ObjectSpriteSheet sheet = new ObjectSpriteSheet(patterns, mappings, 2, 1);
        registerSheet(ObjectArtKeys.SYZ_FLOATING_BLOCK, sheet);
    }

    /**
     * Registers the LZ floating block (door) sheet using dedicated Nemesis art.
     * LZ doors use make_art_tile(ArtTile_LZ_Door,2,0) — palette line 2.
     * Both vertical door (Nem_LzDoor1) and horizontal door (Nem_LzDoor2) art
     * are loaded and combined into a single pattern array.
     */
    private void registerLzFloatingBlockSheet(Sonic1ObjectArt art) {
        // Load vertical door art
        Pattern[] vertPatterns = art.loadNemesisPatterns(
                Sonic1Constants.ART_NEM_LZ_DOOR_VERT_ADDR);
        // Load horizontal door art
        Pattern[] horizPatterns = art.loadNemesisPatterns(
                Sonic1Constants.ART_NEM_LZ_DOOR_HORIZ_ADDR);

        // Combine: vert patterns first, then horiz patterns
        // Vertical door art starts at tile 0, horizontal door art at tile $22
        // The mappings reference tile 0 for vertical and tile $22 for horizontal
        int totalPatterns = Math.max(vertPatterns.length, 0x22) + horizPatterns.length;
        Pattern[] combined = new Pattern[totalPatterns];
        // Copy vertical patterns
        for (int i = 0; i < vertPatterns.length; i++) {
            combined[i] = vertPatterns[i];
        }
        // Fill gap with blank patterns if needed
        for (int i = vertPatterns.length; i < 0x22; i++) {
            combined[i] = new Pattern();
        }
        // Copy horizontal patterns at offset $22
        for (int i = 0; i < horizPatterns.length; i++) {
            combined[0x22 + i] = horizPatterns[i];
        }

        List<SpriteMappingFrame> mappings = createFloatingBlockMappingsLz();

        // Palette line 2 (make_art_tile(ArtTile_LZ_Door, 2, 0))
        ObjectSpriteSheet sheet = new ObjectSpriteSheet(combined, mappings, 2, 1);
        registerSheet(ObjectArtKeys.LZ_FLOATING_BLOCK, sheet);
    }

    /**
     * SYZ/SLZ floating block mappings from docs/s1disasm/_maps/Floating Blocks and Doors.asm.
     * These use level tile patterns (ArtTile_Level = 0).
     * Returns frames 0-5 (SYZ variants + SLZ).
     */
    private List<SpriteMappingFrame> createFloatingBlockMappingsSyzSlz() {
        List<SpriteMappingFrame> frames = new ArrayList<>(
                loadMappingFrames(Sonic1Constants.MAP_FLOATING_BLOCK_ADDR));
        for (int i = 6; i < frames.size(); i++) {
            frames.set(i, new SpriteMappingFrame(List.of()));
        }
        return frames;
    }

    /**
     * LZ floating block (door) mappings from docs/s1disasm/_maps/Floating Blocks and Doors.asm.
     * These use dedicated Nem_LzDoor1/Nem_LzDoor2 art.
     * Returns 8 frames but only frames 6-7 have actual pieces for LZ.
     */
    private List<SpriteMappingFrame> createFloatingBlockMappingsLz() {
        List<SpriteMappingFrame> frames = new ArrayList<>(
                loadMappingFrames(Sonic1Constants.MAP_FLOATING_BLOCK_ADDR));
        for (int i = 0; i < 6; i++) {
            frames.set(i, new SpriteMappingFrame(List.of()));
        }
        return frames;
    }

    private void registerSheet(String key, ObjectSpriteSheet sheet) {
        if (sheet == null) {
            return;
        }
        PatternSpriteRenderer renderer = new PatternSpriteRenderer(sheet);

        int existingIndex = rendererKeys.indexOf(key);
        if (existingIndex >= 0) {
            // Replace in-place to keep pattern cache order stable across re-registration.
            sheets.put(key, sheet);
            renderers.put(key, renderer);
            sheetOrder.set(existingIndex, sheet);
            rendererOrder.set(existingIndex, renderer);
            return;
        }

        sheets.put(key, sheet);
        renderers.put(key, renderer);
        rendererKeys.add(key);
        sheetOrder.add(sheet);
        rendererOrder.add(renderer);
    }

    private List<SpriteMappingFrame> loadMappingFrames(int mappingAddr) {
        if (romReader == null) {
            try {
                Rom rom = GameServices.rom().getRom();
                if (rom == null) {
                    throw new IllegalStateException("ROM not loaded");
                }
                romReader = RomByteReader.fromRom(rom);
            } catch (IOException ex) {
                throw new IllegalStateException("Unable to load Sonic 1 ROM mapping data", ex);
            }
        }
        return S1SpriteDataLoader.loadMappingFrames(romReader, mappingAddr);
    }

    @Override
    public Pattern[] getHudDigitPatterns() {
        return hudDigitPatterns;
    }

    @Override
    public Pattern[] getHudTextPatterns() {
        return hudTextPatterns;
    }

    @Override
    public Pattern[] getHudLivesPatterns() {
        return hudLivesPatterns;
    }

    @Override
    public Pattern[] getHudLivesNumbers() {
        return hudLivesNumbers;
    }

    @Override
    public Pattern[] getHudHexDigitPatterns() {
        return hudHexDigits;
    }

    @Override
    public HudStaticArt getHudStaticArt() {
        return hudStaticArt;
    }

    private void overrideLivesArtFromDonor() {
        if (!com.openggf.game.CrossGameFeatureProvider.isS3kDonorActive()) {
            return;
        }
        String mainChar = ActiveGameplayTeamResolver.resolveMainCharacterCode(GameServices.configuration());
        if (!"knuckles".equalsIgnoreCase(mainChar)) {
            return;
        }
        Pattern[] knuxLife = loadS3kKnucklesLivesPatterns();
        if (knuxLife != null && knuxLife.length > 0) {
            hudLivesPatterns = knuxLife;
            rebuildHudStaticArt();
            LOGGER.info("Overrode S1 lives icon with Knuckles art from S3K donor (" + knuxLife.length + " tiles)");
        }
    }

    private void rebuildHudStaticArt() {
        hudStaticArt = Sonic1HudStaticArtFactory.create(hudTextPatterns, hudLivesPatterns);
    }

    Pattern[] loadS3kKnucklesLivesPatterns() {
        try {
            com.openggf.data.Rom donorRom = GameServices.rom().getSecondaryRom("s3k");
            Pattern[] patterns = com.openggf.util.PatternDecompressor.nemesis(donorRom,
                    com.openggf.game.sonic3k.constants.Sonic3kConstants.ART_NEM_KNUCKLES_LIFE_ICON_ADDR);
            if (patterns != null && patterns.length > 0) {
                remapPaletteIndices(patterns);
            }
            return patterns;
        } catch (Exception e) {
            LOGGER.warning("Failed to load Knuckles life icon from donor: " + e.getMessage());
            return null;
        }
    }

    private void remapPaletteIndices(Pattern[] tiles) {
        for (Pattern tile : tiles) {
            for (int y = 0; y < Pattern.PATTERN_HEIGHT; y++) {
                for (int x = 0; x < Pattern.PATTERN_WIDTH; x++) {
                    int oldIdx = tile.getPixel(x, y) & 0x0F;
                    tile.setPixel(x, y, (byte) S3K_TO_S1_LIVES_PALETTE_REMAP[oldIdx]);
                }
            }
        }
    }

    @Override
    public PatternSpriteRenderer getRenderer(String key) {
        return renderers.get(key);
    }

    @Override
    public ObjectSpriteSheet getSheet(String key) {
        return sheets.get(key);
    }

    @Override
    public SpriteAnimationSet getAnimations(String key) {
        return animations.get(key);
    }

    @Override
    public int getZoneData(String key, int zoneIndex) {
        return switch (key) {
            case ObjectArtKeys.ANIMAL_TYPE_A -> animalTypeA;
            case ObjectArtKeys.ANIMAL_TYPE_B -> animalTypeB;
            default -> -1;
        };
    }

    @Override
    public List<String> getRendererKeys() {
        return new ArrayList<>(rendererKeys);
    }

    @Override
    public int getRegularPatternCount() {
        return sheetOrder.stream().mapToInt(sheet -> sheet.getPatterns().length).sum();
    }

    @Override
    public int ensurePatternsCached(GraphicsManager graphicsManager, int baseIndex) {
        int next = baseIndex;
        for (int i = 0; i < rendererOrder.size(); i++) {
            ObjectSpriteSheet sheet = sheetOrder.get(i);
            PatternSpriteRenderer renderer = rendererOrder.get(i);
            int count = sheet.getPatterns().length;
            renderer.ensurePatternsCached(graphicsManager, next);
            next += count;
        }
        return next;
    }

    @Override
    public boolean isReady() {
        return currentZoneIndex >= 0 && hudDigitPatterns != null && hudDigitPatterns.length > 0;
    }

    @Override
    public int getHudTextPaletteLine() {
        return 0; // Sonic 1: yellow HUD text is in palette line 0
    }

    @Override
    public int getHudFlashPaletteLine() {
        return 0; // Sonic 1: life icon and flash both use palette line 0 (Sonic's palette)
    }

    @Override
    public Palette getHudLivesPaletteOverride() {
        if (!com.openggf.game.CrossGameFeatureProvider.isS3kDonorActive()) {
            return null;
        }
        String mainChar = ActiveGameplayTeamResolver.resolveMainCharacterCode(GameServices.configuration());
        if (!"knuckles".equalsIgnoreCase(mainChar)) {
            return null;
        }
        if (GameServices.levelOrNull() == null || GameServices.levelOrNull().getCurrentLevel() == null) {
            return null;
        }
        return buildS1KnucklesLivesHudPaletteOverride(GameServices.levelOrNull().getCurrentLevel().getPalette(0));
    }

    static Palette buildS1KnucklesLivesHudPaletteOverride(Palette basePalette) {
        if (basePalette == null) {
            return null;
        }
        Palette override = basePalette.deepCopy();
        setColor(override, 12, 255, 73, 109);
        setColor(override, 13, 219, 0, 36);
        setColor(override, 14, 109, 0, 36);
        return override;
    }

    private static void setColor(Palette palette, int index, int r, int g, int b) {
        Palette.Color color = palette.getColor(index);
        color.r = (byte) r;
        color.g = (byte) g;
        color.b = (byte) b;
    }

    // ========================================================================
    // Ending sequence art (Obj87, Obj88, Obj89)
    // ========================================================================

    private ObjectSpriteSheet createEndingSonicSheet(Sonic1ObjectArt art) {
        Pattern[] patterns = art.loadNemesisPatterns(Sonic1Constants.ART_NEM_END_SONIC_ADDR);
        if (patterns.length == 0) {
            return null;
        }
        return new ObjectSpriteSheet(patterns, art.loadMappingFrames(Sonic1Constants.MAP_END_SONIC_ADDR), 0, 1);
    }

    private ObjectSpriteSheet createEndingEmeraldsSheet(Sonic1ObjectArt art) {
        Pattern[] patterns = art.loadNemesisPatterns(Sonic1Constants.ART_NEM_END_EMERALDS_ADDR);
        if (patterns.length == 0) {
            return null;
        }
        return new ObjectSpriteSheet(patterns, art.loadMappingFrames(Sonic1Constants.MAP_END_EMERALDS_ADDR), 0, 1);
    }

    private ObjectSpriteSheet createEndingSTHSheet(Sonic1ObjectArt art) {
        Pattern[] patterns = art.loadNemesisPatterns(Sonic1Constants.ART_NEM_END_STH_ADDR);
        if (patterns.length == 0) {
            return null;
        }
        return new ObjectSpriteSheet(patterns, art.loadMappingFrames(Sonic1Constants.MAP_END_STH_ADDR), 0, 1);
    }

    // ── SBZ2 cutscene art ──────────────────────────────────────────────

    /**
     * Loads SBZ2 Eggman sprite art (Nem_Sbz2Eggman) with ROM-backed Map_SEgg mappings.
     * obGfx = make_art_tile(ArtTile_Eggman,0,0) -> palette 0.
     * Frames 0-4 cover the SBZ2 cutscene sequence (stand, laugh1, laugh2, jump1, jump2).
     */
    private void loadSbz2EggmanArt(Sonic1ObjectArt art) {
        List<SpriteMappingFrame> allMappings = art.loadMappingFrames(Sonic1Constants.MAP_SEGG_ADDR);
        if (allMappings.size() < 5) {
            return;
        }
        List<SpriteMappingFrame> mappings = List.copyOf(allMappings.subList(0, 5));
        registerSheet(ObjectArtKeys.SBZ2_EGGMAN, art.buildArtSheet(
                Sonic1Constants.ART_NEM_SBZ2_EGGMAN_ADDR, mappings, 0, 1));
    }

    /**
     * Loads SBZ2 button sprite art (Nem_Button / LZ switch) with ROM-backed Map_But mappings.
     * obGfx = make_art_tile(ArtTile_SBZ2_Button,0,0) -> palette 0.
     * Shared Nemesis art with LZ switch (ART_NEM_LZ_SWITCH_ADDR).
     * 2 frames: unpressed, pressed.
     */
    private void loadSbz2ButtonArt(Sonic1ObjectArt art) {
        Pattern[] patterns = art.loadNemesisPatterns(Sonic1Constants.ART_NEM_LZ_SWITCH_ADDR);
        if (patterns.length <= 4) {
            return;
        }
        patterns = Arrays.copyOfRange(patterns, 4, patterns.length);

        List<SpriteMappingFrame> allMappings = art.loadMappingFrames(Sonic1Constants.MAP_BUTTON_ADDR);
        if (allMappings.size() < 2) {
            return;
        }
        List<SpriteMappingFrame> mappings = List.copyOf(allMappings.subList(0, 2));
        registerSheet(ObjectArtKeys.SBZ2_BUTTON, new ObjectSpriteSheet(patterns, mappings, 0, 1));
    }

    /**
     * Loads SBZ2 false floor art (Nem_SBZ_VanishingBlock) with ROM-backed Map_FFloor mappings.
     * obGfx = make_art_tile(ArtTile_Eggman_Trap_Floor,2,0) -> palette 2.
     * 5 frames: whole block, then 4 quarter-block fragments (TL, TR, BL, BR).
     */
    private void loadSbz2FalseFloorArt(Sonic1ObjectArt art) {
        ObjectSpriteSheet sheet = art.buildArtSheetFromRom(
                Sonic1Constants.ART_NEM_SBZ_VANISHING_BLOCK_ADDR,
                Sonic1Constants.MAP_SBZ_FALSE_FLOOR_ADDR,
                2,
                1);
        if (sheet != null) {
            registerSheet(ObjectArtKeys.SBZ2_FALSE_FLOOR, sheet);
        }
    }
}
