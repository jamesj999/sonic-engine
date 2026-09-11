package com.openggf.game.sonic3k;

import com.openggf.data.Rom;
import com.openggf.data.RomByteReader;
import com.openggf.game.sonic3k.constants.Sonic3kConstants;
import com.openggf.game.sonic3k.constants.Sonic3kZoneIds;
import com.openggf.level.objects.ObjectArtKeys;
import com.openggf.level.objects.ObjectSpriteSheet;
import com.openggf.level.render.SpriteMappingFrame;
import com.openggf.level.render.SpriteMappingPiece;
import com.openggf.level.resources.CompressionType;
import com.openggf.tests.RomTestUtils;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

public class TestSonic3kPlcArtRegistry {
    private static final int MAX_SANE_FRAMES = 256;
    private static final int MAX_SANE_FRAME_PIECES = 80;
    private static final int MAX_SANE_FRAME_TILES = 512;
    private static final int MAX_SANE_FRAME_SPAN_PIXELS = 1024;
    private static final int MAX_SANE_ABS_PIECE_OFFSET_PIXELS = 2048;
    private static final int STANDALONE_S3_HALF_START = 0x200000;
    private static final int FIRST_SKL_ZONE = 0x07;
    private static final Set<String> REVIEWED_S3_SIDE_ART_REFERENCES = Set.of(
            // sonic3k.asm DPLCPtr_CutsceneKnux uses the shared cutscene sheet;
            // RomOffsetFinder resolves these labels only from the Sonic 3 half.
            reviewedS3SideReference(Sonic3kObjectArtKeys.CUTSCENE_KNUCKLES, "artAddr", 0x382DC6),
            reviewedS3SideReference(Sonic3kObjectArtKeys.CUTSCENE_KNUCKLES, "mappingAddr", 0x364016),
            reviewedS3SideReference(Sonic3kObjectArtKeys.CUTSCENE_KNUCKLES, "dplcAddr", 0x36430E)
    );
    private static final Set<String> REVIEWED_HARDCODED_MAPPING_DEBT = Set.of(
            "standalone:" + Sonic3kObjectArtKeys.MGZ_ENDBOSS_SCALED + ":mappingAddr<=0",
            "provider:" + Sonic3kObjectArtKeys.MONITOR + ":buildMonitorAnimations"
    );
    private static final Set<String> HARDCODED_MAPPING_BUILDERS = Set.of();

    @Test
    public void cnzEndBossMappingsMatchRomShape() throws IOException {
        File romFile = RomTestUtils.ensureSonic3kRomAvailable();
        assumeTrue(romFile != null && romFile.exists(), "Sonic 3K ROM not available");

        try (Rom rom = new Rom()) {
            assumeTrue(rom.open(romFile.getPath()), "Failed to open Sonic 3K ROM");
            List<SpriteMappingFrame> frames = S3kSpriteDataLoader.loadMappingFrames(
                    RomByteReader.fromRom(rom), Sonic3kConstants.MAP_CNZ_END_BOSS_ADDR, 13);

            assertEquals(13, frames.size());
            assertEquals(List.of(6, 2, 2, 2, 2, 4, 6, 6, 2, 0, 1, 3, 3),
                    frames.stream().map(frame -> frame.pieces().size()).toList());
            SpriteMappingPiece body = frames.get(0).pieces().getFirst();
            assertEquals(4, body.widthTiles());
            assertEquals(4, body.heightTiles());
            assertEquals(0, body.tileIndex());
            SpriteMappingPiece field = frames.get(6).pieces().getFirst();
            assertEquals(4, field.widthTiles());
            assertEquals(3, field.heightTiles());
            assertEquals(0x2E, field.tileIndex());
        }
    }

    @Test
    public void sharedLevelArtEntriesIncludeSpikesAndSprings() {
        Sonic3kPlcArtRegistry.ZoneArtPlan plan = Sonic3kPlcArtRegistry.getPlan(0x00, 0);

        assertNotNull(plan);
        assertTrue(plan.levelArt().size() >= 7, "Expected at least 7 shared level art entries");

        boolean hasSpikes = plan.levelArt().stream()
                .anyMatch(e -> Sonic3kObjectArtKeys.SPIKES.equals(e.key()));
        assertTrue(hasSpikes, "Expected spikes entry in level art");
    }

    @Test
    public void aiz1PlanIncludesBadnikAndLevelArt() {
        Sonic3kPlcArtRegistry.ZoneArtPlan plan = Sonic3kPlcArtRegistry.getPlan(0x00, 0);
        assertEquals(10, plan.standaloneArt().size());

        // Verify Bloominator
        Sonic3kPlcArtRegistry.StandaloneArtEntry bloominator = plan.standaloneArt().stream()
                .filter(e -> e.key().equals(Sonic3kObjectArtKeys.BLOOMINATOR))
                .findFirst().orElse(null);
        assertNotNull(bloominator);

        // Verify Rhinobot has DPLC
        Sonic3kPlcArtRegistry.StandaloneArtEntry rhinobot = plan.standaloneArt().stream()
                .filter(e -> e.key().equals(Sonic3kObjectArtKeys.RHINOBOT))
                .findFirst().orElse(null);
        assertNotNull(rhinobot);
        assertTrue(rhinobot.dplcAddr() > 0);

        // 7 shared + 3 shared AIZ + 4 act-1 specific = 14
        assertTrue(plan.levelArt().size() > 7);
    }

    @Test
    public void aiz2PlanHasAct2SpecificEntries() {
        Sonic3kPlcArtRegistry.ZoneArtPlan plan = Sonic3kPlcArtRegistry.getPlan(0x00, 1);
        assertEquals(10, plan.standaloneArt().size());
        boolean hasAiz2Rock = plan.levelArt().stream()
                .anyMatch(e -> e.key().equals(Sonic3kObjectArtKeys.AIZ2_ROCK));
        assertTrue(hasAiz2Rock);
        // Act 2 should NOT have Act 1 tree
        boolean hasAiz1Tree = plan.levelArt().stream()
                .anyMatch(e -> e.key().equals(Sonic3kObjectArtKeys.AIZ1_TREE));
        assertFalse(hasAiz1Tree);
    }

    @Test
    public void hcz1PlanHasBlastoidNotJawz() {
        Sonic3kPlcArtRegistry.ZoneArtPlan plan = Sonic3kPlcArtRegistry.getPlan(0x01, 0);
        assertNotNull(plan);
        assertEquals(21, plan.standaloneArt().size());
        assertTrue(plan.standaloneArt().stream().anyMatch(e -> e.key().equals(Sonic3kObjectArtKeys.HCZ_LARGE_FAN)));
        assertTrue(plan.standaloneArt().stream().anyMatch(e -> e.key().equals(Sonic3kObjectArtKeys.HCZ_BLASTOID)));
        assertTrue(plan.standaloneArt().stream().anyMatch(e -> e.key().equals(Sonic3kObjectArtKeys.HCZ_TURBO_SPIKER)));
        assertFalse(plan.standaloneArt().stream().anyMatch(e -> e.key().equals(Sonic3kObjectArtKeys.HCZ_JAWZ)));
    }

    @Test
    public void hcz2PlanHasJawzNotBlastoid() {
        Sonic3kPlcArtRegistry.ZoneArtPlan plan = Sonic3kPlcArtRegistry.getPlan(0x01, 1);
        assertNotNull(plan);
        assertEquals(21, plan.standaloneArt().size());
        assertTrue(plan.standaloneArt().stream().anyMatch(e -> e.key().equals(Sonic3kObjectArtKeys.HCZ_LARGE_FAN)));
        assertTrue(plan.standaloneArt().stream().anyMatch(e -> e.key().equals(Sonic3kObjectArtKeys.HCZ_JAWZ)));
        assertFalse(plan.standaloneArt().stream().anyMatch(e -> e.key().equals(Sonic3kObjectArtKeys.HCZ_BLASTOID)));
    }

    @Test
    public void mgz1PlanHasMinibossAndDiagonalSpringOverride() {
        Sonic3kPlcArtRegistry.ZoneArtPlan plan = Sonic3kPlcArtRegistry.getPlan(0x02, 0);
        assertNotNull(plan);
        assertEquals(11, plan.standaloneArt().size());
        assertTrue(plan.standaloneArt().stream().anyMatch(e -> e.key().equals(Sonic3kObjectArtKeys.MGZ_SPIKER)));
        assertTrue(plan.standaloneArt().stream().anyMatch(e -> e.key().equals(Sonic3kObjectArtKeys.MGZ_MINIBOSS)));
        assertTrue(plan.standaloneArt().stream().anyMatch(e -> e.key().equals(Sonic3kObjectArtKeys.MGZ_MINIBOSS_SPIRE)));
        assertTrue(plan.standaloneArt().stream().anyMatch(e -> e.key().equals(Sonic3kObjectArtKeys.MGZ_MINIBOSS_DEBRIS)));
        assertFalse(plan.standaloneArt().stream().anyMatch(e -> e.key().equals(Sonic3kObjectArtKeys.MGZ_MANTIS)));

        // Diagonal spring should use MGZ/MHZ art tile
        Sonic3kPlcArtRegistry.LevelArtEntry diag = plan.levelArt().stream()
                .filter(e -> e.key().equals(Sonic3kObjectArtKeys.SPRING_DIAGONAL))
                .findFirst().orElse(null);
        assertNotNull(diag);
        assertEquals(0x0478, diag.artTileBase());
    }

    @Test
    public void mgz2PlanHasMantisNotMiniboss() {
        Sonic3kPlcArtRegistry.ZoneArtPlan plan = Sonic3kPlcArtRegistry.getPlan(0x02, 1);
        assertNotNull(plan);
        assertEquals(13, plan.standaloneArt().size());
        assertTrue(plan.standaloneArt().stream().anyMatch(e -> e.key().equals(Sonic3kObjectArtKeys.MGZ_MANTIS)));
        assertTrue(plan.standaloneArt().stream().anyMatch(e -> e.key().equals(Sonic3kObjectArtKeys.MGZ_ENDBOSS)));
        assertTrue(plan.standaloneArt().stream().anyMatch(e -> e.key().equals(Sonic3kObjectArtKeys.ROBOTNIK_SHIP)));
        assertTrue(plan.standaloneArt().stream().anyMatch(e -> e.key().equals(Sonic3kObjectArtKeys.MGZ_ENDBOSS_DEBRIS)));
        assertFalse(plan.standaloneArt().stream().anyMatch(e -> e.key().equals(Sonic3kObjectArtKeys.MGZ_MINIBOSS)));
    }

    @Test
    public void mgz2PlanLoadsEndBossScaledCueFromUncompressedArtScaledBlob() {
        Sonic3kPlcArtRegistry.ZoneArtPlan plan = Sonic3kPlcArtRegistry.getPlan(0x02, 1);

        Sonic3kPlcArtRegistry.StandaloneArtEntry scaled = plan.standaloneArt().stream()
                .filter(e -> "mgz_endboss_scaled".equals(e.key()))
                .findFirst().orElse(null);

        assertNotNull(scaled, "loc_6CFF4 uses ArtScaled_MGZEndBoss for the air-attack zoom cue");
        assertEquals(0x36C572, scaled.artAddr());
        assertEquals(CompressionType.UNCOMPRESSED, scaled.compression());
        assertEquals(0x1000, scaled.artSize(), "ArtScaled_MGZEndBoss is 128 raw 4bpp tiles");
        assertEquals(1, scaled.palette(), "ObjDat3_6D7B4 uses make_art_tile(ArtTile_MGZEndBossScaled,1,0)");
    }

    @Test
    public void cnzPlanHasBadniksAndKeepsBalloonOutOfStandaloneArt() {
        Sonic3kPlcArtRegistry.ZoneArtPlan plan = Sonic3kPlcArtRegistry.getPlan(0x03, 0);
        assertNotNull(plan);
        assertTrue(plan.standaloneArt().stream().anyMatch(e -> e.key().equals(Sonic3kObjectArtKeys.CNZ_SPARKLE)));
        assertTrue(plan.standaloneArt().stream().anyMatch(e -> e.key().equals(Sonic3kObjectArtKeys.CNZ_BATBOT)));
        assertTrue(plan.standaloneArt().stream().anyMatch(e -> e.key().equals(Sonic3kObjectArtKeys.CNZ_CLAMER)));
        assertTrue(plan.standaloneArt().stream().anyMatch(e -> e.key().equals(Sonic3kObjectArtKeys.CNZ_CLAMER_SHOT)));
        assertFalse(plan.standaloneArt().stream().anyMatch(e -> e.key().equals(Sonic3kObjectArtKeys.CNZ_BALLOON)),
                "CNZ balloon uses buildCnzBalloonSheet level-art splicing, not a standalone full mapping sheet");
    }

    @Test
    public void fbzPlanOverridesSpikes() {
        Sonic3kPlcArtRegistry.ZoneArtPlan plan = Sonic3kPlcArtRegistry.getPlan(0x04, 0);
        assertNotNull(plan);
        assertEquals(9, plan.standaloneArt().size());
        assertTrue(plan.standaloneArt().stream().anyMatch(e -> e.key().equals(Sonic3kObjectArtKeys.FBZ_BLASTER)));

        // Spikes should use FBZ art tile
        Sonic3kPlcArtRegistry.LevelArtEntry spikes = plan.levelArt().stream()
                .filter(e -> e.key().equals(Sonic3kObjectArtKeys.SPIKES))
                .findFirst().orElse(null);
        assertNotNull(spikes);
        assertEquals(0x0200, spikes.artTileBase());
    }

    @Test
    public void iczPlanHasCollapsingBridgeAndBadniks() {
        Sonic3kPlcArtRegistry.ZoneArtPlan plan = Sonic3kPlcArtRegistry.getPlan(0x05, 0);
        assertNotNull(plan);
        assertEquals(9, plan.standaloneArt().size());
        assertTrue(plan.standaloneArt().stream().anyMatch(e -> e.key().equals(Sonic3kObjectArtKeys.ICZ_SNOWDUST)));
        assertTrue(plan.standaloneArt().stream().anyMatch(e -> e.key().equals(Sonic3kObjectArtKeys.ICZ_STAR_POINTER)));
        assertTrue(plan.standaloneArt().stream().anyMatch(e -> e.key().equals(Sonic3kObjectArtKeys.ICZ_PENGUINATOR)));

        // Penguinator should have DPLC
        Sonic3kPlcArtRegistry.StandaloneArtEntry penguin = plan.standaloneArt().stream()
                .filter(e -> e.key().equals(Sonic3kObjectArtKeys.ICZ_PENGUINATOR))
                .findFirst().orElse(null);
        assertNotNull(penguin);
        assertTrue(penguin.dplcAddr() > 0);

        // Collapsing bridge level-art should still be present
        boolean hasBridge = plan.levelArt().stream()
                .anyMatch(e -> e.key().equals(Sonic3kObjectArtKeys.COLLAPSING_PLATFORM_ICZ));
        assertTrue(hasBridge);
    }

    @Test
    public void lbzPlanHasFourBadniks() {
        Sonic3kPlcArtRegistry.ZoneArtPlan plan = Sonic3kPlcArtRegistry.getPlan(0x06, 0);
        assertNotNull(plan);
        assertTrue(plan.standaloneArt().stream().anyMatch(e -> e.key().equals(Sonic3kObjectArtKeys.SNALE_BLASTER)));
        assertTrue(plan.standaloneArt().stream().anyMatch(e -> e.key().equals(Sonic3kObjectArtKeys.ORBINAUT)));
        assertTrue(plan.standaloneArt().stream().anyMatch(e -> e.key().equals(Sonic3kObjectArtKeys.RIBOT)));
        assertTrue(plan.standaloneArt().stream().anyMatch(e -> e.key().equals(Sonic3kObjectArtKeys.CORKEY)));
        assertTrue(plan.standaloneArt().stream().anyMatch(e -> e.key().equals(Sonic3kObjectArtKeys.ROBOTNIK_SHIP)),
                "Obj_LBZ1Robotnik loads the shared Robotnik ship art via PLC_60.");
        assertTrue(plan.standaloneArt().stream().anyMatch(e -> e.key().equals(Sonic3kObjectArtKeys.LBZ_MINIBOSS_BOX)),
                "Obj_LBZ1Robotnik queues the carried ArtKosM_LBZMinibossBox sheet.");
    }

    @Test
    public void lbz2PlanIncludesEndSequenceArtOnlyInAct2() {
        Sonic3kPlcArtRegistry.ZoneArtPlan act1 = Sonic3kPlcArtRegistry.getPlan(0x06, 0);
        Sonic3kPlcArtRegistry.ZoneArtPlan act2 = Sonic3kPlcArtRegistry.getPlan(0x06, 1);

        assertFalse(act1.standaloneArt().stream()
                        .anyMatch(e -> Sonic3kObjectArtKeys.LBZ_END_BOSS.equals(e.key())),
                "LBZ end-boss art is queued by the LBZ2 end sequence, not LBZ1.");
        assertFalse(act1.standaloneArt().stream()
                        .anyMatch(e -> Sonic3kObjectArtKeys.LBZ_FINAL_BOSS_1.equals(e.key())),
                "LBZ final-boss art is queued by the LBZ2 final arena, not LBZ1.");
        assertFalse(act1.standaloneArt().stream()
                        .anyMatch(e -> Sonic3kObjectArtKeys.LBZ2_DEATH_EGG_SMALL.equals(e.key())),
                "LBZ2 Death Egg miniature art must not pollute the LBZ1 plan.");
        assertFalse(act1.standaloneArt().stream()
                        .anyMatch(e -> Sonic3kObjectArtKeys.FBZ_ROBOTNIK_RUN.equals(e.key())),
                "The LBZ end-boss Robotnik runner is LBZ2-only.");
        assertFalse(act1.levelArt().stream()
                        .anyMatch(e -> Sonic3kObjectArtKeys.LBZ_KNUX_PILLAR.equals(e.key())),
                "The LBZ2 Knuckles pillar depends on the Death Egg terrain swap and is LBZ2-only.");

        Sonic3kPlcArtRegistry.StandaloneArtEntry endBoss =
                requireStandaloneArt(act2, Sonic3kObjectArtKeys.LBZ_END_BOSS);
        assertEquals(Sonic3kConstants.ART_KOSM_LBZ_END_BOSS_ADDR, endBoss.artAddr());
        assertEquals(CompressionType.KOSINSKI_MODULED, endBoss.compression());
        assertEquals(Sonic3kConstants.ART_KOSM_LBZ_END_BOSS_SIZE, endBoss.artSize());
        assertEquals(Sonic3kConstants.MAP_LBZ_END_BOSS_ADDR, endBoss.mappingAddr());
        assertEquals(1, endBoss.palette());
        assertEquals(15, endBoss.mappingFrameCount());

        Sonic3kPlcArtRegistry.StandaloneArtEntry finalBoss =
                requireStandaloneArt(act2, Sonic3kObjectArtKeys.LBZ_FINAL_BOSS_1);
        assertEquals(Sonic3kConstants.ART_NEM_LBZ_FINAL_BOSS_1_ADDR, finalBoss.artAddr());
        assertEquals(CompressionType.NEMESIS, finalBoss.compression());
        assertEquals(Sonic3kConstants.ART_NEM_LBZ_FINAL_BOSS_1_SIZE, finalBoss.artSize());
        assertEquals(Sonic3kConstants.MAP_LBZ_FINAL_BOSS_1_ADDR, finalBoss.mappingAddr());
        assertEquals(1, finalBoss.palette());
        assertEquals(46, finalBoss.mappingFrameCount());

        Sonic3kPlcArtRegistry.StandaloneArtEntry deathEgg =
                requireStandaloneArt(act2, Sonic3kObjectArtKeys.LBZ2_DEATH_EGG_SMALL);
        assertEquals(Sonic3kConstants.ART_KOSM_LBZ2_DEATH_EGG_SMALL_ADDR, deathEgg.artAddr());
        assertEquals(CompressionType.KOSINSKI_MODULED, deathEgg.compression());
        assertEquals(Sonic3kConstants.ART_KOSM_LBZ2_DEATH_EGG_SMALL_SIZE, deathEgg.artSize());
        assertEquals(Sonic3kConstants.MAP_LBZ_DEATH_EGG_SMALL_ADDR, deathEgg.mappingAddr());
        assertEquals(1, deathEgg.palette());
        assertEquals(12, deathEgg.mappingFrameCount());

        Sonic3kPlcArtRegistry.StandaloneArtEntry runner =
                requireStandaloneArt(act2, Sonic3kObjectArtKeys.FBZ_ROBOTNIK_RUN);
        assertEquals(Sonic3kConstants.ART_NEM_FBZ_ROBOTNIK_RUN_ADDR, runner.artAddr());
        assertEquals(CompressionType.NEMESIS, runner.compression());
        assertEquals(Sonic3kConstants.ART_NEM_FBZ_ROBOTNIK_RUN_SIZE, runner.artSize());
        assertEquals(Sonic3kConstants.MAP_FBZ_ROBOTNIK_RUN_ADDR, runner.mappingAddr());
        assertEquals(0, runner.palette());

        Sonic3kPlcArtRegistry.StandaloneArtEntry cutsceneKnuckles =
                requireStandaloneArt(act2, Sonic3kObjectArtKeys.CUTSCENE_KNUCKLES);
        assertEquals(Sonic3kConstants.ART_UNC_CUTSCENE_KNUX_ADDR, cutsceneKnuckles.artAddr());
        assertEquals(CompressionType.UNCOMPRESSED, cutsceneKnuckles.compression());
        assertEquals(Sonic3kConstants.ART_UNC_CUTSCENE_KNUX_SIZE, cutsceneKnuckles.artSize());
        assertEquals(Sonic3kConstants.MAP_CUTSCENE_KNUX_ADDR, cutsceneKnuckles.mappingAddr());
        assertEquals(Sonic3kConstants.DPLC_CUTSCENE_KNUX_ADDR, cutsceneKnuckles.dplcAddr());

        Sonic3kPlcArtRegistry.StandaloneArtEntry bossExplosion =
                requireStandaloneArt(act2, ObjectArtKeys.BOSS_EXPLOSION);
        assertEquals(Sonic3kConstants.ART_NEM_BOSS_EXPLOSION_ADDR, bossExplosion.artAddr());
        assertEquals(CompressionType.NEMESIS, bossExplosion.compression());
        assertEquals(Sonic3kConstants.MAP_BOSS_EXPLOSION_ADDR, bossExplosion.mappingAddr());

        Sonic3kPlcArtRegistry.LevelArtEntry pillar =
                requireLevelArt(act2, Sonic3kObjectArtKeys.LBZ_KNUX_PILLAR);
        assertEquals(Sonic3kConstants.MAP_LBZ_KNUX_PILLAR_ADDR, pillar.mappingAddr());
        assertEquals(Sonic3kConstants.ART_TILE_LBZ_KNUX_PILLAR, pillar.artTileBase());
        assertEquals(2, pillar.palette());
    }

    @Test
    public void lbz2StandaloneTileBasesAreHeldAsConstantsBecauseRegistryDoesNotCarryVramDestinations() {
        assertEquals(0x0425, Sonic3kConstants.ART_TILE_LBZ_END_BOSS);
        assertEquals(0x03AA, Sonic3kConstants.ART_TILE_LBZ_FINAL_BOSS_1);
        assertEquals(0x04AE, Sonic3kConstants.ART_TILE_LBZ2_DEATH_EGG_SMALL);
        assertEquals(0x04A9, Sonic3kConstants.ART_TILE_FBZ_ROBOTNIK_RUN);
        assertEquals(0x052E, Sonic3kConstants.ART_TILE_ROBOTNIK_SHIP);
        assertEquals(0x0500, Sonic3kConstants.ART_TILE_BOSS_EXPLOSION);
        assertEquals(0x05A0, Sonic3kConstants.ART_TILE_LBZ_KNUX_PILLAR);
        assertEquals(0x04DA, Sonic3kConstants.ARTTILE_CUTSCENE_KNUX);
    }

    @Test
    public void lbzPlanIncludesRideGrappleLevelArt() {
        Sonic3kPlcArtRegistry.ZoneArtPlan plan = Sonic3kPlcArtRegistry.getPlan(0x06, 0);

        Sonic3kPlcArtRegistry.LevelArtEntry grapple = plan.levelArt().stream()
                .filter(e -> e.key().equals(Sonic3kObjectArtKeys.LBZ_RIDE_GRAPPLE))
                .findFirst().orElse(null);

        assertNotNull(grapple, "Obj_LBZRideGrapple uses resident LBZ misc art and must be in the LBZ level-art plan");
        assertEquals(Sonic3kConstants.MAP_LBZ_RIDE_GRAPPLE_ADDR, grapple.mappingAddr());
        assertEquals(Sonic3kConstants.ARTTILE_LBZ_MISC + 0x70, grapple.artTileBase());
        assertEquals(1, grapple.palette());
    }

    @Test
    public void lbzPlanIncludesFlameThrowerLevelArt() {
        Sonic3kPlcArtRegistry.ZoneArtPlan plan = Sonic3kPlcArtRegistry.getPlan(0x06, 0);

        Sonic3kPlcArtRegistry.LevelArtEntry flameThrower = plan.levelArt().stream()
                .filter(e -> e.key().equals(Sonic3kObjectArtKeys.LBZ_FLAME_THROWER))
                .findFirst().orElse(null);

        assertNotNull(flameThrower, "Obj_LBZFlameThrower uses resident LBZ misc art and must be in the LBZ level-art plan");
        assertEquals(Sonic3kConstants.MAP_LBZ_FLAME_THROWER_ADDR, flameThrower.mappingAddr());
        assertEquals(Sonic3kConstants.ARTTILE_LBZ_MISC - 0x17, flameThrower.artTileBase());
        assertEquals(2, flameThrower.palette());
    }

    @Test
    public void lbzPlanIncludesGateLaserLevelArt() {
        Sonic3kPlcArtRegistry.ZoneArtPlan plan = Sonic3kPlcArtRegistry.getPlan(0x06, 1);

        Sonic3kPlcArtRegistry.LevelArtEntry gateLaser = plan.levelArt().stream()
                .filter(e -> e.key().equals(Sonic3kObjectArtKeys.LBZ_GATE_LASER))
                .findFirst().orElse(null);

        assertNotNull(gateLaser, "Obj_LBZGateLaser uses resident LBZ2 misc art");
        assertEquals(Sonic3kConstants.MAP_LBZ_GATE_LASER_ADDR, gateLaser.mappingAddr());
        assertEquals(Sonic3kConstants.ARTTILE_LBZ2_MISC, gateLaser.artTileBase());
        assertEquals(2, gateLaser.palette());
    }

    @Test
    public void lbz2PlanIncludesSpinLauncherLevelArt() {
        Sonic3kPlcArtRegistry.ZoneArtPlan plan = Sonic3kPlcArtRegistry.getPlan(0x06, 1);

        Sonic3kPlcArtRegistry.LevelArtEntry spinLauncher = plan.levelArt().stream()
                .filter(e -> e.key().equals(Sonic3kObjectArtKeys.LBZ_SPIN_LAUNCHER))
                .findFirst().orElse(null);

        assertNotNull(spinLauncher, "Obj_LBZSpinLauncher uses resident LBZ2 misc art");
        assertEquals(Sonic3kConstants.MAP_LBZ_SPIN_LAUNCHER_ADDR, spinLauncher.mappingAddr());
        assertEquals(Sonic3kConstants.ARTTILE_LBZ2_MISC, spinLauncher.artTileBase());
        assertEquals(2, spinLauncher.palette());
    }

    @Test
    public void lbz2PlanIncludesPipePlugLevelArtWithExplicitFrameCount() {
        Sonic3kPlcArtRegistry.ZoneArtPlan plan = Sonic3kPlcArtRegistry.getPlan(0x06, 1);

        Sonic3kPlcArtRegistry.LevelArtEntry pipePlug =
                requireLevelArt(plan, Sonic3kObjectArtKeys.LBZ_PIPE_PLUG);

        assertEquals(Sonic3kConstants.MAP_LBZ_PIPE_PLUG_ADDR, pipePlug.mappingAddr());
        assertEquals(Sonic3kConstants.ARTTILE_LBZ2_MISC - 4, pipePlug.artTileBase());
        assertEquals(2, pipePlug.palette());
        assertEquals(8, pipePlug.mappingFrameCount(),
                "Map_LBZPipePlug frame 0 is stored after frame 7, so the first offset is not the table size");
    }

    @Test
    public void lbz2PlanIncludesTunnelExhaustLevelArt() {
        Sonic3kPlcArtRegistry.ZoneArtPlan plan = Sonic3kPlcArtRegistry.getPlan(0x06, 1);

        Sonic3kPlcArtRegistry.LevelArtEntry exhaust =
                requireLevelArt(plan, Sonic3kObjectArtKeys.LBZ_TUNNEL_EXHAUST);

        assertEquals(Sonic3kConstants.MAP_TUNNEL_EXHAUST_ADDR, exhaust.mappingAddr());
        assertEquals(Sonic3kConstants.ARTTILE_LBZ2_MISC, exhaust.artTileBase());
        assertEquals(2, exhaust.palette());
        assertEquals(2, exhaust.mappingFrameCount());
    }

    @Test
    public void lbz2PlanIncludesLoweringGrappleLevelArt() {
        Sonic3kPlcArtRegistry.ZoneArtPlan plan = Sonic3kPlcArtRegistry.getPlan(0x06, 1);

        Sonic3kPlcArtRegistry.LevelArtEntry grapple =
                requireLevelArt(plan, Sonic3kObjectArtKeys.LBZ_LOWERING_GRAPPLE);

        assertEquals(Sonic3kConstants.MAP_LBZ_LOWERING_GRAPPLE_ADDR, grapple.mappingAddr());
        assertEquals(Sonic3kConstants.ARTTILE_LBZ2_MISC, grapple.artTileBase());
        assertEquals(2, grapple.palette());
    }

    @Test
    public void lbzLoweringGrappleMappingsMatchRomShape() throws IOException {
        File romFile = RomTestUtils.ensureSonic3kRomAvailable();
        assumeTrue(romFile != null && romFile.exists(), "Sonic 3K ROM not available");

        try (Rom rom = new Rom()) {
            assumeTrue(rom.open(romFile.getPath()), "Failed to open Sonic 3K ROM");
            RomByteReader reader = RomByteReader.fromRom(rom);
            List<SpriteMappingFrame> frames = S3kSpriteDataLoader.loadMappingFrames(
                    reader, Sonic3kConstants.MAP_LBZ_LOWERING_GRAPPLE_ADDR);

            assertEquals(15, frames.size(), "Map_LBZLoweringGrapple has 14 extension frames plus a duplicate max frame");
            assertEquals(3, frames.get(0).pieces().size());
            assertEquals(16, frames.get(14).pieces().size());
            assertLbz2Piece(frames.get(0), 0, 3, 2, 2, 0x3A);
            assertLbz2Piece(frames.get(0), 1, 3, 2, 2, 0x149);
            assertLbz2Piece(frames.get(14), 15, 16, 2, 2, 0x14E);
        }
    }

    @Test
    public void lbzPipePlugMappingsParseEightRomFrames() throws IOException {
        File romFile = RomTestUtils.ensureSonic3kRomAvailable();
        assumeTrue(romFile != null && romFile.exists(), "Sonic 3K ROM not available");

        try (Rom rom = new Rom()) {
            assumeTrue(rom.open(romFile.getPath()), "Failed to open Sonic 3K ROM");
            RomByteReader reader = RomByteReader.fromRom(rom);
            List<SpriteMappingFrame> frames = S3kSpriteDataLoader.loadMappingFrames(
                    reader, Sonic3kConstants.MAP_LBZ_PIPE_PLUG_ADDR, 8);

            assertEquals(8, frames.size());
            assertLbz2Piece(frames.get(0), 0, 1, 1, 2, 0x08);
            assertLbz2Piece(frames.get(6), 0, 1, 1, 2, 0x808 & 0x7FF);
            assertEquals(16, frames.get(7).pieces().size(), "LBZ PipePlug intact frame piece count");
            assertLbzPipePlugPiece(frames.get(7).pieces().getFirst(), -16, -32, 2, 2, 0x04);
        }
    }

    @Test
    public void tunnelExhaustMappingsParseTwoRomFrames() throws IOException {
        File romFile = RomTestUtils.ensureSonic3kRomAvailable();
        assumeTrue(romFile != null && romFile.exists(), "Sonic 3K ROM not available");

        try (Rom rom = new Rom()) {
            assumeTrue(rom.open(romFile.getPath()), "Failed to open Sonic 3K ROM");
            RomByteReader reader = RomByteReader.fromRom(rom);
            List<SpriteMappingFrame> frames = S3kSpriteDataLoader.loadMappingFrames(
                    reader, Sonic3kConstants.MAP_TUNNEL_EXHAUST_ADDR, 2);

            assertEquals(2, frames.size());
            assertLbz2Piece(frames.get(0), 0, 1, 4, 3, 0x2A);
            assertLbz2Piece(frames.get(1), 0, 1, 3, 4, 0x2A);
        }
    }

    @Test
    public void lbzPlanIncludesTubeElevatorLevelArt() {
        Sonic3kPlcArtRegistry.ZoneArtPlan plan = Sonic3kPlcArtRegistry.getPlan(0x06, 0);

        Sonic3kPlcArtRegistry.LevelArtEntry elevator = plan.levelArt().stream()
                .filter(e -> e.key().equals("lbz_tube_elevator"))
                .findFirst().orElse(null);

        assertNotNull(elevator, "Obj_LBZTubeElevator uses resident LBZ tube transport art");
        assertEquals(Sonic3kConstants.MAP_LBZ_TUBE_ELEVATOR_ADDR, elevator.mappingAddr());
        assertEquals(Sonic3kConstants.ARTTILE_LBZ_TUBE_TRANS, elevator.artTileBase());
        assertEquals(1, elevator.palette());
    }

    @Test
    public void lbzTubeElevatorMappingsMatchRomShape() throws IOException {
        File romFile = RomTestUtils.ensureSonic3kRomAvailable();
        assumeTrue(romFile != null && romFile.exists(), "Sonic 3K ROM not available");

        try (Rom rom = new Rom()) {
            assumeTrue(rom.open(romFile.getPath()), "Failed to open Sonic 3K ROM");
            RomByteReader reader = RomByteReader.fromRom(rom);
            var frames = S3kSpriteDataLoader.loadMappingFrames(reader, Sonic3kConstants.MAP_LBZ_TUBE_ELEVATOR_ADDR);

            assertEquals(7, frames.size(), "Map_LBZTubeElevator has six shell frames plus the side child frame");
            assertEquals(10, frames.get(0).pieces().size());
            assertEquals(10, frames.get(1).pieces().size());
            assertEquals(7, frames.get(2).pieces().size());
            assertEquals(10, frames.get(3).pieces().size());
            assertEquals(7, frames.get(4).pieces().size());
            assertEquals(10, frames.get(5).pieces().size());
            assertEquals(3, frames.get(6).pieces().size());
        }
    }

    @Test
    public void lbzGateLaserMappingsMatchRomShape() throws IOException {
        File romFile = RomTestUtils.ensureSonic3kRomAvailable();
        assumeTrue(romFile != null && romFile.exists(), "Sonic 3K ROM not available");

        try (Rom rom = new Rom()) {
            assumeTrue(rom.open(romFile.getPath()), "Failed to open Sonic 3K ROM");
            RomByteReader reader = RomByteReader.fromRom(rom);
            var frames = S3kSpriteDataLoader.loadMappingFrames(reader, Sonic3kConstants.MAP_LBZ_GATE_LASER_ADDR);

            assertEquals(3, frames.size(), "Map_LBZGateLaser has full, left-half, and right-half frames");
            assertEquals(2, frames.get(0).pieces().size());
            assertLbzGateLaserPiece(frames.get(0).pieces().get(0), -0x1C, -4, 4, 1);
            assertLbzGateLaserPiece(frames.get(0).pieces().get(1), 4, -4, 3, 1);
            assertEquals(1, frames.get(1).pieces().size());
            assertLbzGateLaserPiece(frames.get(1).pieces().get(0), -0x1C, -4, 4, 1);
            assertEquals(1, frames.get(2).pieces().size());
            assertLbzGateLaserPiece(frames.get(2).pieces().get(0), 4, -4, 3, 1);
        }
    }

    @Test
    public void lbzSpinLauncherMappingsMatchRomShape() throws IOException {
        File romFile = RomTestUtils.ensureSonic3kRomAvailable();
        assumeTrue(romFile != null && romFile.exists(), "Sonic 3K ROM not available");

        try (Rom rom = new Rom()) {
            assumeTrue(rom.open(romFile.getPath()), "Failed to open Sonic 3K ROM");
            RomByteReader reader = RomByteReader.fromRom(rom);
            var frames = S3kSpriteDataLoader.loadMappingFrames(reader, Sonic3kConstants.MAP_LBZ_SPIN_LAUNCHER_ADDR);

            assertEquals(1, frames.size(), "Map_LBZSpinLauncher has one frame");
            assertEquals(7, frames.get(0).pieces().size());
        }
    }

    @Test
    public void lbz2EndSequenceMappingsMatchRomFrameCounts() throws IOException {
        File romFile = RomTestUtils.ensureSonic3kRomAvailable();
        assumeTrue(romFile != null && romFile.exists(), "Sonic 3K ROM not available");

        try (Rom rom = new Rom()) {
            assumeTrue(rom.open(romFile.getPath()), "Failed to open Sonic 3K ROM");
            RomByteReader reader = RomByteReader.fromRom(rom);

            assertEquals(15,
                    S3kSpriteDataLoader.loadMappingFrames(reader, Sonic3kConstants.MAP_LBZ_END_BOSS_ADDR).size(),
                    "Map_LBZEndBoss has 15 frames.");
            assertEquals(46,
                    S3kSpriteDataLoader.loadMappingFrames(reader, Sonic3kConstants.MAP_LBZ_FINAL_BOSS_1_ADDR).size(),
                    "Map_LBZFinalBoss1 has 46 frames.");
            assertEquals(12,
                    S3kSpriteDataLoader.loadMappingFrames(reader, Sonic3kConstants.MAP_LBZ_DEATH_EGG_SMALL_ADDR).size(),
                    "Map_LBZDeathEggSmall has 12 frames.");
            assertEquals(2,
                    S3kSpriteDataLoader.loadMappingFrames(reader, Sonic3kConstants.MAP_LBZ_KNUX_PILLAR_ADDR).size(),
                    "Map_LBZKnuxPillar has 2 frames.");
        }
    }

    @Test
    public void lbz2EndSequenceMappingsMatchRomShape() throws IOException {
        File romFile = RomTestUtils.ensureSonic3kRomAvailable();
        assumeTrue(romFile != null && romFile.exists(), "Sonic 3K ROM not available");

        try (Rom rom = new Rom()) {
            assumeTrue(rom.open(romFile.getPath()), "Failed to open Sonic 3K ROM");
            RomByteReader reader = RomByteReader.fromRom(rom);
            List<SpriteMappingFrame> endBoss = S3kSpriteDataLoader.loadMappingFrames(
                    reader, Sonic3kConstants.MAP_LBZ_END_BOSS_ADDR);
            assertLbz2Piece(endBoss.get(0), 0, 6, 3, 1, 0x00);
            assertLbz2Piece(endBoss.get(0), 1, 6, 4, 3, 0x03);
            assertLbz2Piece(endBoss.get(1), 0, 1, 1, 1, 0x0F);
            assertLbz2Piece(endBoss.get(14), 0, 2, 1, 2, 0x31);

            List<SpriteMappingFrame> finalBoss = S3kSpriteDataLoader.loadMappingFrames(
                    reader, Sonic3kConstants.MAP_LBZ_FINAL_BOSS_1_ADDR);
            assertLbz2Piece(finalBoss.get(0), 0, 4, 4, 4, 0x00);
            assertLbz2Piece(finalBoss.get(0), 1, 4, 4, 1, 0x10);
            assertLbz2Piece(finalBoss.get(1), 0, 6, 4, 4, 0x14);
            assertLbz2Piece(finalBoss.get(3), 0, 1, 2, 2, 0x34);
            assertEquals(0, finalBoss.get(45).pieces().size(), "Map_LBZFinalBoss1 frame 0x2D is blank.");

            List<SpriteMappingFrame> deathEgg = S3kSpriteDataLoader.loadMappingFrames(
                    reader, Sonic3kConstants.MAP_LBZ_DEATH_EGG_SMALL_ADDR);
            assertLbz2Piece(deathEgg.get(0), 0, 4, 3, 4, 0x00);
            assertLbz2Piece(deathEgg.get(0), 1, 4, 3, 4, 0x0C);
            assertLbz2Piece(deathEgg.get(1), 0, 1, 1, 1, 0x24);
            assertLbz2Piece(deathEgg.get(11), 0, 1, 2, 2, 0x4E);

            List<SpriteMappingFrame> pillar = S3kSpriteDataLoader.loadMappingFrames(
                    reader, Sonic3kConstants.MAP_LBZ_KNUX_PILLAR_ADDR);
            assertLbz2Piece(pillar.get(0), 0, 1, 4, 4, 0x00);
            assertLbz2Piece(pillar.get(1), 0, 8, 4, 4, 0x00);
            assertLbz2Piece(pillar.get(1), 3, 8, 4, 4, 0x00);
        }
    }

    @Test
    public void lbz1RobotnikBoxStandaloneArtMatchesRomShape() throws IOException {
        File romFile = RomTestUtils.ensureSonic3kRomAvailable();
        assumeTrue(romFile != null && romFile.exists(), "Sonic 3K ROM not available");

        Sonic3kPlcArtRegistry.StandaloneArtEntry entry = requireStandaloneArt(
                Sonic3kPlcArtRegistry.getPlan(0x06, 0), Sonic3kObjectArtKeys.LBZ_MINIBOSS_BOX);
        assertEquals(Sonic3kConstants.ART_KOSM_LBZ_MINIBOSS_BOX_ADDR, entry.artAddr());
        assertEquals(CompressionType.KOSINSKI_MODULED, entry.compression());
        assertEquals(Sonic3kConstants.MAP_LBZ_MINIBOSS_BOX_ADDR, entry.mappingAddr());
        assertEquals(2, entry.palette(), "ObjDat3_8D23C uses make_art_tile(ArtTile_LBZMinibossBox,2,0).");

        try (Rom rom = new Rom()) {
            assumeTrue(rom.open(romFile.getPath()), "Failed to open Sonic 3K ROM");
            RomByteReader reader = RomByteReader.fromRom(rom);
            List<SpriteMappingFrame> frames = S3kSpriteDataLoader.loadMappingFrames(
                    reader, Sonic3kConstants.MAP_LBZ_MINIBOSS_BOX_ADDR);

            assertEquals(12, frames.size(), "Map_LBZMinibossBox has 12 frames.");
            assertEquals(2, frames.get(0).pieces().size());
            assertEquals(1, frames.get(6).pieces().size());
            assertEquals(1, frames.get(9).pieces().size());
            assertEquals(0, frames.get(0).pieces().getFirst().tileIndex());
            assertEquals(0x48, frames.get(6).pieces().getFirst().tileIndex());
            assertEquals(0x4B, frames.get(9).pieces().getFirst().tileIndex());

            Sonic3kObjectArt art = new Sonic3kObjectArt(null, reader);
            ObjectSpriteSheet sheet = art.loadStandaloneSheet(rom, entry);
            assertEquals(78, sheet.getPatterns().length,
                    "ArtKosM_LBZMinibossBox decompresses to 2496 bytes / 78 tiles.");
            assertEquals(12, sheet.getFrameCount());
            assertMappingTilesWithinSheet(sheet, "LBZ miniboss box mappings must fit the decompressed box art");
        }
    }

    @Test
    public void lbzMinibossStandaloneArtMatchesRomShape() throws IOException {
        File romFile = RomTestUtils.ensureSonic3kRomAvailable();
        assumeTrue(romFile != null && romFile.exists(), "Sonic 3K ROM not available");

        Sonic3kPlcArtRegistry.StandaloneArtEntry entry = requireStandaloneArt(
                Sonic3kPlcArtRegistry.getPlan(0x06, 0), Sonic3kObjectArtKeys.LBZ_MINIBOSS);
        assertEquals(Sonic3kConstants.ART_KOSM_LBZ_MINIBOSS_ADDR, entry.artAddr());
        assertEquals(CompressionType.KOSINSKI_MODULED, entry.compression());
        assertEquals(Sonic3kConstants.MAP_LBZ_MINIBOSS_ADDR, entry.mappingAddr());
        assertEquals(1, entry.palette(), "ObjDat_LBZMiniboss uses make_art_tile(ArtTile_LBZMiniboss,1,1).");

        try (Rom rom = new Rom()) {
            assumeTrue(rom.open(romFile.getPath()), "Failed to open Sonic 3K ROM");
            RomByteReader reader = RomByteReader.fromRom(rom);
            List<SpriteMappingFrame> frames = S3kSpriteDataLoader.loadMappingFrames(
                    reader, Sonic3kConstants.MAP_LBZ_MINIBOSS_ADDR);

            assertEquals(9, frames.size(), "Map_LBZMiniboss has 9 frames.");
            assertEquals(2, frames.get(0).pieces().size());
            assertEquals(4, frames.get(3).pieces().size());
            assertEquals(1, frames.get(6).pieces().size());
            assertEquals(0, frames.get(0).pieces().getFirst().tileIndex());
            assertEquals(0x15, frames.get(3).pieces().getFirst().tileIndex());
            assertEquals(0x19, frames.get(6).pieces().getFirst().tileIndex());

            Sonic3kObjectArt art = new Sonic3kObjectArt(null, reader);
            ObjectSpriteSheet sheet = art.loadStandaloneSheet(rom, entry);
            assertEquals(42, sheet.getPatterns().length,
                    "ArtKosM_LBZMiniboss decompresses to 1344 bytes / 42 tiles.");
            assertEquals(9, sheet.getFrameCount());
            assertMappingTilesWithinSheet(sheet, "LBZ miniboss mappings must fit the decompressed boss art");
        }
    }

    @Test
    public void lbzRideGrappleMappingsMatchRomShape() throws IOException {
        File romFile = RomTestUtils.ensureSonic3kRomAvailable();
        assumeTrue(romFile != null && romFile.exists(), "Sonic 3K ROM not available");

        try (Rom rom = new Rom()) {
            assumeTrue(rom.open(romFile.getPath()), "Failed to open Sonic 3K ROM");
            RomByteReader reader = RomByteReader.fromRom(rom);
            var frames = S3kSpriteDataLoader.loadMappingFrames(reader, Sonic3kConstants.MAP_LBZ_RIDE_GRAPPLE_ADDR);

            assertEquals(3, frames.size(), "Map_LBZRideGrapple has top, chain-link, and handle frames");
            assertRideGrapplePiece(frames.get(0), 2, 2, 0);
            assertRideGrapplePiece(frames.get(1), 1, 1, 4);
            assertRideGrapplePiece(frames.get(2), 2, 2, 5);
        }
    }

    @Test
    public void lbzFlameThrowerMappingsMatchRomShape() throws IOException {
        File romFile = RomTestUtils.ensureSonic3kRomAvailable();
        assumeTrue(romFile != null && romFile.exists(), "Sonic 3K ROM not available");

        try (Rom rom = new Rom()) {
            assumeTrue(rom.open(romFile.getPath()), "Failed to open Sonic 3K ROM");
            RomByteReader reader = RomByteReader.fromRom(rom);
            var frames = S3kSpriteDataLoader.loadMappingFrames(reader, Sonic3kConstants.MAP_LBZ_FLAME_THROWER_ADDR);

            assertEquals(9, frames.size(), "Map_LBZFlameThrower has the nozzle plus eight flame frames");
            assertLbzFlameThrowerFrame(frames.get(0), new int[][]{{4, 4, 0x3B}});
            assertLbzFlameThrowerFrame(frames.get(1), new int[][]{{2, 1, 0x4B}, {2, 1, 0x4D}});
            assertLbzFlameThrowerFrame(frames.get(2), new int[][]{{2, 1, 0x4D}, {2, 1, 0x4F}, {2, 1, 0x51}, {2, 2, 0x57}});
            assertLbzFlameThrowerFrame(frames.get(3), new int[][]{{2, 1, 0x4B}, {2, 1, 0x4D}, {2, 1, 0x4F}, {2, 1, 0x51}, {2, 2, 0x53}, {2, 2, 0x57}});
            assertLbzFlameThrowerFrame(frames.get(4), new int[][]{{2, 1, 0x4B}, {2, 1, 0x4D}, {2, 1, 0x4F}, {2, 1, 0x51}, {2, 2, 0x53}, {2, 2, 0x57}});
            assertLbzFlameThrowerFrame(frames.get(5), new int[][]{{2, 1, 0x4D}, {2, 1, 0x4F}, {2, 1, 0x51}, {2, 2, 0x53}, {2, 2, 0x53}, {2, 2, 0x5B}});
            assertLbzFlameThrowerFrame(frames.get(6), new int[][]{{2, 1, 0x4D}, {2, 1, 0x4F}, {2, 1, 0x51}, {2, 2, 0x53}, {2, 2, 0x53}, {2, 2, 0x5B}});
            assertLbzFlameThrowerFrame(frames.get(7), new int[][]{{2, 1, 0x4B}, {2, 1, 0x4D}, {2, 1, 0x4F}, {2, 2, 0x5B}});
            assertLbzFlameThrowerFrame(frames.get(8), new int[][]{{2, 1, 0x4B}, {2, 1, 0x5F}});
        }
    }

    @Test
    public void surfaceSplashArtMatchesRomShapeAndStaysWithinSplashSheet() throws IOException {
        File romFile = RomTestUtils.ensureSonic3kRomAvailable();
        assumeTrue(romFile != null && romFile.exists(), "Sonic 3K ROM not available");

        try (Rom rom = new Rom()) {
            assumeTrue(rom.open(romFile.getPath()), "Failed to open Sonic 3K ROM");
            RomByteReader reader = RomByteReader.fromRom(rom);

            // ArtUnc_SplashDrown is 124 tiles; shares Map_DashDust + DPLC_DashSplashDrown.
            var tiles = S3kSpriteDataLoader.loadArtTiles(reader,
                    Sonic3kConstants.ART_UNC_SPLASH_DROWN_ADDR,
                    Sonic3kConstants.ART_UNC_SPLASH_DROWN_SIZE);
            var mappings = S3kSpriteDataLoader.loadMappingFrames(reader,
                    Sonic3kConstants.MAP_DASH_DUST_ADDR);
            var dplcs = S3kSpriteDataLoader.loadDplcFrames(reader,
                    Sonic3kConstants.DPLC_DASH_DUST_ADDR);

            assertEquals(124, tiles.length, "ArtUnc_SplashDrown is 124 tiles (3968 bytes).");
            assertEquals(30, mappings.size(), "Map_DashDust has 30 frames ($00-$1D).");
            assertEquals(30, dplcs.size(), "DPLC_DashSplashDrown has 30 frames ($00-$1D).");

            // Anim 4 (Ani_DashSplashDrown byte_18DE8) plays mapping frames $16-$1D.
            // ROM source tiles: $16->0(x12), $17->12, $18->28, $19->44, $1A->60,
            // $1B->76, $1C->92, $1D->108(x16); 108+16 == 124 exactly.
            int[] expectedStart = {0, 12, 28, 44, 60, 76, 92, 108};
            for (int i = 0; i < expectedStart.length; i++) {
                int frame = 0x16 + i;
                var requests = dplcs.get(frame).requests();
                assertEquals(1, requests.size(),
                        "Splash frame 0x" + Integer.toHexString(frame) + " loads one tile run.");
                var req = requests.get(0);
                assertEquals(expectedStart[i], req.startTile(),
                        "Splash frame 0x" + Integer.toHexString(frame) + " start tile.");
                assertTrue(req.startTile() + req.count() <= tiles.length,
                        "Splash frame 0x" + Integer.toHexString(frame)
                                + " must stay within the 124-tile splash sheet (no art corruption).");
                // Mapping for these frames references bank-relative tile 0.
                for (SpriteMappingPiece piece : mappings.get(frame).pieces()) {
                    assertEquals(0, piece.tileIndex(),
                            "Splash mapping frame 0x" + Integer.toHexString(frame)
                                    + " uses bank-relative tile indices.");
                }
            }
        }
    }

    @Test
    public void lbzMovingPlatformMappingsMatchRomShape() throws IOException {
        File romFile = RomTestUtils.ensureSonic3kRomAvailable();
        assumeTrue(romFile != null && romFile.exists(), "Sonic 3K ROM not available");

        try (Rom rom = new Rom()) {
            assumeTrue(rom.open(romFile.getPath()), "Failed to open Sonic 3K ROM");
            RomByteReader reader = RomByteReader.fromRom(rom);
            var frames = S3kSpriteDataLoader.loadMappingFrames(reader, Sonic3kConstants.MAP_LBZ_MOVING_PLATFORM_ADDR);

            assertEquals(3, frames.size(), "Map_LBZMovingPlatform has two 32px-wide frames and one low-profile frame");
            assertLbzMovingPlatformPiece(frames.get(0), 0, 4, 2, 0);
            assertLbzMovingPlatformPiece(frames.get(0), 1, 4, 2, 0);
            assertLbzMovingPlatformPiece(frames.get(1), 0, 4, 2, 8);
            assertLbzMovingPlatformPiece(frames.get(1), 1, 4, 2, 0);
            assertLbzMovingPlatformPiece(frames.get(2), 0, 4, 1, 0);
            assertLbzMovingPlatformPiece(frames.get(2), 1, 4, 1, 0);
        }
    }

    @Test
    public void mhz1PlanHasFourBadniksNoArrow() {
        Sonic3kPlcArtRegistry.ZoneArtPlan plan = Sonic3kPlcArtRegistry.getPlan(0x07, 0);
        assertNotNull(plan);
        assertTrue(plan.standaloneArt().stream().anyMatch(e -> e.key().equals(Sonic3kObjectArtKeys.MADMOLE)));
        assertTrue(plan.standaloneArt().stream().anyMatch(e -> e.key().equals(Sonic3kObjectArtKeys.CLUCKOID)));
        assertFalse(plan.standaloneArt().stream().anyMatch(e -> e.key().equals(Sonic3kObjectArtKeys.CLUCKOID_ARROW)));
    }

    @Test
    public void mhz1PlanIncludesAllRenderedObjectArtKeys() {
        Sonic3kPlcArtRegistry.ZoneArtPlan plan = Sonic3kPlcArtRegistry.getPlan(0x07, 0);

        assertStandaloneArtKeys(plan,
                Sonic3kObjectArtKeys.MADMOLE,
                Sonic3kObjectArtKeys.MUSHMEANIE,
                Sonic3kObjectArtKeys.DRAGONFLY,
                Sonic3kObjectArtKeys.BUTTERDROID,
                Sonic3kObjectArtKeys.CLUCKOID,
                Sonic3kObjectArtKeys.MHZ_MINIBOSS,
                Sonic3kObjectArtKeys.MHZ_MINIBOSS_LOG,
                Sonic3kObjectArtKeys.KNUX_INTRO_LAYING,
                Sonic3kObjectArtKeys.CUTSCENE_KNUCKLES,
                Sonic3kObjectArtKeys.MHZ1_CUTSCENE_KNUCKLES_PEER);

        assertLevelArtKeys(plan,
                Sonic3kObjectArtKeys.MHZ_PULLEY_LIFT,
                Sonic3kObjectArtKeys.MHZ_CURLED_VINE,
                Sonic3kObjectArtKeys.MHZ_STICKY_VINE,
                Sonic3kObjectArtKeys.MHZ_SWING_BAR_HORIZONTAL,
                Sonic3kObjectArtKeys.MHZ_SWING_BAR_VERTICAL,
                Sonic3kObjectArtKeys.MHZ_SWING_VINE,
                Sonic3kObjectArtKeys.MHZ_MUSHROOM_CAP_LIGHT,
                Sonic3kObjectArtKeys.MHZ_MUSHROOM_CAP_DARK,
                Sonic3kObjectArtKeys.MHZ_MUSHROOM_PLATFORM,
                Sonic3kObjectArtKeys.MHZ_MUSHROOM_PARACHUTE,
                Sonic3kObjectArtKeys.MHZ_MUSHROOM_CATAPULT_CAPS,
                Sonic3kObjectArtKeys.MHZ_MUSHROOM_CATAPULT_CENTER,
                Sonic3kObjectArtKeys.BUTTON,
                Sonic3kObjectArtKeys.MHZ_MINIBOSS_TREE,
                Sonic3kObjectArtKeys.MHZ1_CUTSCENE_KNUCKLES_DOOR,
                Sonic3kObjectArtKeys.MHZ_BIG_LEAVES,
                Sonic3kObjectArtKeys.MHZ_POLLEN_SPRING,
                Sonic3kObjectArtKeys.MHZ_POLLEN_SEASONAL);
    }

    @Test
    public void mhz1CutsceneStandaloneArtMappingsStayInsideDecompressedBanks() throws IOException {
        File romFile = RomTestUtils.ensureSonic3kRomAvailable();
        assumeTrue(romFile != null && romFile.exists(), "Sonic 3K ROM not available");

        try (Rom rom = new Rom()) {
            assumeTrue(rom.open(romFile.getPath()), "Failed to open Sonic 3K ROM");
            Sonic3kObjectArt art = new Sonic3kObjectArt(null, RomByteReader.fromRom(rom));

            for (String key : new String[]{
                    Sonic3kObjectArtKeys.CUTSCENE_KNUCKLES,
                    Sonic3kObjectArtKeys.MHZ1_CUTSCENE_KNUCKLES_PEER
            }) {
                Sonic3kPlcArtRegistry.StandaloneArtEntry entry = Sonic3kPlcArtRegistry.getPlan(0x07, 0)
                        .standaloneArt().stream()
                        .filter(e -> key.equals(e.key()))
                        .findFirst()
                        .orElseThrow();

                ObjectSpriteSheet sheet = art.loadStandaloneSheet(rom, entry);

                assertNotNull(sheet);
                assertTrue(sheet.getPatterns().length > 0);
                assertMappingTilesWithinSheet(sheet,
                        key + " mappings must parse from the table boundary and fit the decompressed bank");
            }
        }
    }

    @Test
    public void mhzPollenMappingsMatchRomShape() throws IOException {
        File romFile = RomTestUtils.ensureSonic3kRomAvailable();
        assumeTrue(romFile != null && romFile.exists(), "Sonic 3K ROM not available");

        try (Rom rom = new Rom()) {
            assumeTrue(rom.open(romFile.getPath()), "Failed to open Sonic 3K ROM");
            RomByteReader reader = RomByteReader.fromRom(rom);
            var frames = S3kSpriteDataLoader.loadMappingFrames(reader, Sonic3kConstants.MAP_MHZ_POLLEN_ADDR);

            assertEquals(4, frames.size(), "MHZ pollen mapping table should have four animation frames");
            for (int i = 0; i < frames.size(); i++) {
                assertEquals(1, frames.get(i).pieces().size(), "MHZ pollen frame " + i + " should have one piece");
                SpriteMappingPiece piece = frames.get(i).pieces().get(0);
                assertEquals(1, piece.widthTiles(), "MHZ pollen frame " + i + " width");
                assertEquals(1, piece.heightTiles(), "MHZ pollen frame " + i + " height");
                assertEquals(0, piece.tileIndex(), "MHZ pollen frame " + i + " tile");
            }
        }
    }

    @Test
    public void s3kArtRegistryMappingsStayWithinSaneSpriteSheetLimits() throws IOException {
        File romFile = RomTestUtils.ensureSonic3kRomAvailable();
        assumeTrue(romFile != null && romFile.exists(), "Sonic 3K ROM not available");

        try (Rom rom = new Rom()) {
            assumeTrue(rom.open(romFile.getPath()), "Failed to open Sonic 3K ROM");
            RomByteReader reader = RomByteReader.fromRom(rom);
            Sonic3kObjectArt art = new Sonic3kObjectArt(null, reader);

            for (int zone = 0x00; zone <= 0x0D; zone++) {
                for (int act = 0; act <= 1; act++) {
                    Sonic3kPlcArtRegistry.ZoneArtPlan plan = Sonic3kPlcArtRegistry.getPlan(zone, act);
                    String context = "zone 0x" + Integer.toHexString(zone) + " act " + act;

                    for (Sonic3kPlcArtRegistry.LevelArtEntry entry : plan.levelArt()) {
                        if (entry.mappingAddr() <= 0) {
                            continue;
                        }
                        List<SpriteMappingFrame> frames = entry.mappingFrameCount() > 0
                                ? S3kSpriteDataLoader.loadMappingFrames(
                                reader, entry.mappingAddr(), entry.mappingFrameCount(), entry.mappingFormat())
                                : S3kSpriteDataLoader.loadMappingFrames(
                                reader, entry.mappingAddr(), entry.mappingFormat());
                        if (entry.frameFilter() != null) {
                            List<SpriteMappingFrame> filtered = new ArrayList<>();
                            for (int frameIndex : entry.frameFilter()) {
                                filtered.add(frames.get(frameIndex));
                            }
                            frames = filtered;
                        }
                        assertSaneMappingFrames(context + " level art " + entry.key(), frames, -1);
                    }

                    for (Sonic3kPlcArtRegistry.StandaloneArtEntry entry : plan.standaloneArt()) {
                        assertSaneStandaloneEntry(context, reader, rom, art, entry);
                    }
                }
            }
        }
    }

    @Test
    public void sklObjectArtRegistryDoesNotUseStandaloneSonic3Addresses() {
        List<String> violations = new ArrayList<>();
        for (int zone = FIRST_SKL_ZONE; zone <= 0x0D; zone++) {
            for (int act = 0; act <= 1; act++) {
                Sonic3kPlcArtRegistry.ZoneArtPlan plan = Sonic3kPlcArtRegistry.getPlan(zone, act);
                String context = "zone 0x" + Integer.toHexString(zone) + " act " + act;

                for (Sonic3kPlcArtRegistry.StandaloneArtEntry entry : plan.standaloneArt()) {
                    collectSAndKRuntimeAddressViolation(violations,
                            context + " standalone art " + entry.key() + " artAddr",
                            entry.key(), "artAddr", entry.artAddr());
                    collectSAndKRuntimeAddressViolation(violations,
                            context + " standalone art " + entry.key() + " mappingAddr",
                            entry.key(), "mappingAddr", entry.mappingAddr());
                    collectSAndKRuntimeAddressViolation(violations,
                            context + " standalone art " + entry.key() + " dplcAddr",
                            entry.key(), "dplcAddr", entry.dplcAddr());
                }

                for (Sonic3kPlcArtRegistry.LevelArtEntry entry : plan.levelArt()) {
                    collectSAndKRuntimeAddressViolation(violations,
                            context + " level art " + entry.key() + " mappingAddr",
                            entry.key(), "mappingAddr", entry.mappingAddr());
                }
            }
        }

        assertTrue(violations.isEmpty(),
                "SKL object art registry contains standalone Sonic 3 addresses. S3KL zones may legitimately reference "
                        + "the Sonic 3 half, but SKL/S&K-zone object art needs either an S&K-half address or an "
                + "explicit reviewed exception:\n" + String.join("\n", violations));
    }

    @Test
    public void hardcodedRuntimeMappingDebtDoesNotGrow() {
        Set<String> actual = new HashSet<>();
        for (int zone = 0x00; zone <= 0x0D; zone++) {
            for (int act = 0; act <= 1; act++) {
                Sonic3kPlcArtRegistry.ZoneArtPlan plan = Sonic3kPlcArtRegistry.getPlan(zone, act);
                for (Sonic3kPlcArtRegistry.LevelArtEntry entry : plan.levelArt()) {
                    if (entry.builderName() != null
                            && HARDCODED_MAPPING_BUILDERS.contains(entry.builderName())) {
                        actual.add("level:" + entry.key() + ":" + entry.builderName());
                    }
                }
                for (Sonic3kPlcArtRegistry.StandaloneArtEntry entry : plan.standaloneArt()) {
                    if (entry.mappingAddr() <= 0) {
                        actual.add("standalone:" + entry.key() + ":mappingAddr<=0");
                    }
                }
            }
        }
        actual.addAll(providerLocalHardcodedMappingDebt());

        Set<String> unexpected = new HashSet<>(actual);
        unexpected.removeAll(REVIEWED_HARDCODED_MAPPING_DEBT);
        Set<String> stale = new HashSet<>(REVIEWED_HARDCODED_MAPPING_DEBT);
        stale.removeAll(actual);

        assertTrue(unexpected.isEmpty() && stale.isEmpty(),
                "S3K runtime object mappings should be ROM-backed. Reviewed hardcoded mapping debt changed."
                        + "\nUnexpected:\n" + String.join("\n", new TreeSet<>(unexpected))
                        + "\nStale:\n" + String.join("\n", new TreeSet<>(stale)));
    }

    private static Set<String> providerLocalHardcodedMappingDebt() {
        Set<String> actual = new HashSet<>();
        try {
            String source = java.nio.file.Files.readString(java.nio.file.Path.of(
                    "src/main/java/com/openggf/game/sonic3k/Sonic3kObjectArtProvider.java"));
            if (methodBody(source, "loadExplosionArt").contains("new SpriteMappingFrame")) {
                actual.add("provider:" + ObjectArtKeys.EXPLOSION + ":loadExplosionArt");
            }
            if (methodBody(source, "buildMonitorMappingFrames").contains("new SpriteMappingFrame")) {
                actual.add("provider:" + Sonic3kObjectArtKeys.MONITOR + ":buildMonitorMappingFrames");
            }
            if (methodBody(source, "buildMonitorAnimations").contains("List.of")) {
                actual.add("provider:" + Sonic3kObjectArtKeys.MONITOR + ":buildMonitorAnimations");
            }
        } catch (IOException e) {
            throw new AssertionError("Failed to scan Sonic3kObjectArtProvider", e);
        }
        return actual;
    }

    private static String methodBody(String source, String methodName) {
        int nameIndex = source.indexOf(methodName + "(");
        if (nameIndex < 0) {
            return "";
        }
        int openBrace = source.indexOf('{', nameIndex);
        if (openBrace < 0) {
            return "";
        }
        int depth = 0;
        for (int i = openBrace; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return source.substring(openBrace, i + 1);
                }
            }
        }
        return source.substring(openBrace);
    }

    @Test
    public void hczWaterDropUsesRomBackedFilteredMappings() {
        Sonic3kPlcArtRegistry.ZoneArtPlan plan = Sonic3kPlcArtRegistry.getPlan(Sonic3kZoneIds.ZONE_HCZ, 0);
        Sonic3kPlcArtRegistry.LevelArtEntry waterDrop = plan.levelArt().stream()
                .filter(entry -> Sonic3kObjectArtKeys.HCZ_WATER_DROP.equals(entry.key()))
                .findFirst()
                .orElseThrow();

        assertEquals(Sonic3kConstants.MAP_HCZ_WATER_DROP_ADDR, waterDrop.mappingAddr());
        assertNull(waterDrop.builderName());
        assertArrayEquals(new int[] {0, 1, 2, 3, 4, 5}, waterDrop.frameFilter(),
                "Frame 6 references the ROM's static drip tile source and should not be folded into the level-art sheet");
    }

    @Test
    public void hczWaterRushBlockUsesRomBackedMappings() {
        Sonic3kPlcArtRegistry.ZoneArtPlan plan = Sonic3kPlcArtRegistry.getPlan(Sonic3kZoneIds.ZONE_HCZ, 0);
        Sonic3kPlcArtRegistry.LevelArtEntry waterRushBlock = plan.levelArt().stream()
                .filter(entry -> Sonic3kObjectArtKeys.HCZ_WATER_RUSH_BLOCK.equals(entry.key()))
                .findFirst()
                .orElseThrow();

        assertEquals(Sonic3kConstants.MAP_HCZ_WATER_RUSH_BLOCK_ADDR, waterRushBlock.mappingAddr());
        assertNull(waterRushBlock.builderName());
        assertNull(waterRushBlock.frameFilter());
    }

    @Test
    public void doorSheetsUseRomBackedFilteredMappings() {
        Sonic3kPlcArtRegistry.LevelArtEntry hczVertical =
                requireLevelArt(Sonic3kPlcArtRegistry.getPlan(Sonic3kZoneIds.ZONE_HCZ, 0),
                        Sonic3kObjectArtKeys.DOOR_VERTICAL_HCZ);
        assertEquals(Sonic3kConstants.MAP_HCZ_CNZ_DEZ_DOOR_ADDR, hczVertical.mappingAddr());
        assertNull(hczVertical.builderName());
        assertArrayEquals(new int[] {0}, hczVertical.frameFilter());

        Sonic3kPlcArtRegistry.LevelArtEntry cnzVertical =
                requireLevelArt(Sonic3kPlcArtRegistry.getPlan(Sonic3kZoneIds.ZONE_CNZ, 0),
                        Sonic3kObjectArtKeys.DOOR_VERTICAL_CNZ);
        assertEquals(Sonic3kConstants.MAP_HCZ_CNZ_DEZ_DOOR_ADDR, cnzVertical.mappingAddr());
        assertNull(cnzVertical.builderName());
        assertArrayEquals(new int[] {1}, cnzVertical.frameFilter());

        Sonic3kPlcArtRegistry.LevelArtEntry dezVertical =
                requireLevelArt(Sonic3kPlcArtRegistry.getPlan(Sonic3kZoneIds.ZONE_DEZ, 0),
                        Sonic3kObjectArtKeys.DOOR_VERTICAL_DEZ);
        assertEquals(Sonic3kConstants.MAP_HCZ_CNZ_DEZ_DOOR_ADDR, dezVertical.mappingAddr());
        assertNull(dezVertical.builderName());
        assertArrayEquals(new int[] {2}, dezVertical.frameFilter());

        Sonic3kPlcArtRegistry.LevelArtEntry cnzHorizontal =
                requireLevelArt(Sonic3kPlcArtRegistry.getPlan(Sonic3kZoneIds.ZONE_CNZ, 0),
                        Sonic3kObjectArtKeys.DOOR_HORIZONTAL);
        assertEquals(Sonic3kConstants.MAP_CNZ_DOOR_HORIZONTAL_ADDR, cnzHorizontal.mappingAddr());
        assertNull(cnzHorizontal.builderName());
        assertNull(cnzHorizontal.frameFilter());
    }

    @Test
    public void hczFanBubbleUsesRomBackedBubblerMappings() {
        Sonic3kPlcArtRegistry.StandaloneArtEntry fanBubble =
                requireStandaloneArt(Sonic3kPlcArtRegistry.getPlan(Sonic3kZoneIds.ZONE_HCZ, 0),
                        Sonic3kObjectArtKeys.HCZ_FAN_BUBBLE);

        assertEquals(Sonic3kConstants.MAP_BUBBLER_ADDR, fanBubble.mappingAddr());
        assertEquals(6, fanBubble.mappingFrameCount());
    }

    @Test
    public void sharedBubblerUsesRomBackedMappings() {
        Sonic3kPlcArtRegistry.StandaloneArtEntry bubbler =
                requireStandaloneArt(Sonic3kPlcArtRegistry.getPlan(Sonic3kZoneIds.ZONE_HCZ, 0),
                        Sonic3kObjectArtKeys.BUBBLER);

        assertEquals(Sonic3kConstants.MAP_BUBBLER_ADDR, bubbler.mappingAddr());
        assertEquals(22, bubbler.mappingFrameCount());
    }

    @Test
    public void hczWaterRushUsesRomBackedMappings() {
        Sonic3kPlcArtRegistry.StandaloneArtEntry waterRush =
                requireStandaloneArt(Sonic3kPlcArtRegistry.getPlan(Sonic3kZoneIds.ZONE_HCZ, 0),
                        Sonic3kObjectArtKeys.HCZ_WATER_RUSH);

        assertEquals(Sonic3kConstants.MAP_HCZ_WATER_RUSH_ADDR, waterRush.mappingAddr());
        assertEquals(4, waterRush.mappingFrameCount());
    }

    @Test
    public void hczWaterSplashUsesRomBackedMappings() {
        Sonic3kPlcArtRegistry.StandaloneArtEntry waterSplash =
                requireStandaloneArt(Sonic3kPlcArtRegistry.getPlan(Sonic3kZoneIds.ZONE_HCZ, 0),
                        Sonic3kObjectArtKeys.HCZ_WATER_SPLASH);

        assertEquals(Sonic3kConstants.MAP_HCZ_WATER_SPLASH_ADDR, waterSplash.mappingAddr());
        assertEquals(4, waterSplash.mappingFrameCount());
    }

    @Test
    public void hczBubblesUseRomBackedWaterWallMappings() {
        Sonic3kPlcArtRegistry.StandaloneArtEntry bubbles =
                requireStandaloneArt(Sonic3kPlcArtRegistry.getPlan(Sonic3kZoneIds.ZONE_HCZ, 0),
                        Sonic3kObjectArtKeys.HCZ_BUBBLES);

        assertEquals(Sonic3kConstants.MAP_HCZ_WATERWALL_ADDR, bubbles.mappingAddr());
        assertEquals(0, bubbles.mappingTileOffset());
    }

    @Test
    public void hczGeysersUseRomBackedMappingsWithTileOffsets() {
        Sonic3kPlcArtRegistry.ZoneArtPlan plan = Sonic3kPlcArtRegistry.getPlan(Sonic3kZoneIds.ZONE_HCZ, 0);

        Sonic3kPlcArtRegistry.StandaloneArtEntry horizontal =
                requireStandaloneArt(plan, Sonic3kObjectArtKeys.HCZ_GEYSER_HORZ);
        assertEquals(Sonic3kConstants.MAP_HCZ_WATERWALL_ADDR, horizontal.mappingAddr());
        assertEquals(0, horizontal.mappingTileOffset());

        Sonic3kPlcArtRegistry.StandaloneArtEntry vertical =
                requireStandaloneArt(plan, Sonic3kObjectArtKeys.HCZ_GEYSER_VERT);
        assertEquals(Sonic3kConstants.MAP_HCZ_WATERWALL_ADDR, vertical.mappingAddr());
        assertEquals(0, vertical.mappingTileOffset());

        Sonic3kPlcArtRegistry.StandaloneArtEntry debris =
                requireStandaloneArt(plan, Sonic3kObjectArtKeys.HCZ_GEYSER_DEBRIS);
        assertEquals(Sonic3kConstants.MAP_HCZ_WATERWALL_DEBRIS_ADDR, debris.mappingAddr());
        assertEquals(0x58, debris.mappingTileOffset());

        Sonic3kPlcArtRegistry.StandaloneArtEntry spray =
                requireStandaloneArt(plan, Sonic3kObjectArtKeys.HCZ_GEYSER_SPRAY);
        assertEquals(Sonic3kConstants.MAP_HCZ_WATERWALL_ADDR, spray.mappingAddr());
        assertEquals(0x30, spray.mappingTileOffset());
    }

    @Test
    public void mhz2PlanHasCluckoidArrowAndDiagonalSpringOverride() {
        Sonic3kPlcArtRegistry.ZoneArtPlan plan = Sonic3kPlcArtRegistry.getPlan(0x07, 1);
        assertNotNull(plan);
        assertTrue(plan.standaloneArt().stream().anyMatch(e -> e.key().equals(Sonic3kObjectArtKeys.CLUCKOID_ARROW)));

        // Diagonal spring should use MGZ/MHZ art tile
        Sonic3kPlcArtRegistry.LevelArtEntry diag = plan.levelArt().stream()
                .filter(e -> e.key().equals(Sonic3kObjectArtKeys.SPRING_DIAGONAL))
                .findFirst().orElse(null);
        assertNotNull(diag);
        assertEquals(0x0478, diag.artTileBase());
    }

    @Test
    public void mhz2PlanIncludesAct2SpecificRenderedObjectArtKeys() {
        Sonic3kPlcArtRegistry.ZoneArtPlan plan = Sonic3kPlcArtRegistry.getPlan(0x07, 1);

        assertStandaloneArtKeys(plan,
                Sonic3kObjectArtKeys.CLUCKOID_ARROW,
                Sonic3kObjectArtKeys.MHZ_END_BOSS,
                Sonic3kObjectArtKeys.MHZ_END_BOSS_SPIKES,
                Sonic3kObjectArtKeys.MHZ_END_BOSS_PILLAR,
                Sonic3kObjectArtKeys.MHZ_SHIP_PROPELLER,
                Sonic3kObjectArtKeys.MHZ2_CUTSCENE_KNUCKLES_PRESS,
                Sonic3kObjectArtKeys.MHZ2_CUTSCENE_KNUCKLES_SWITCH);

        assertLevelArtKeys(plan,
                Sonic3kObjectArtKeys.MHZ2_CUTSCENE_KNUCKLES_LEAVES);
    }

    @Test
    public void sozPlanHasThreeBadniks() {
        Sonic3kPlcArtRegistry.ZoneArtPlan plan = Sonic3kPlcArtRegistry.getPlan(0x08, 0);
        assertNotNull(plan);
        assertEquals(9, plan.standaloneArt().size());
        assertTrue(plan.standaloneArt().stream().anyMatch(e -> e.key().equals(Sonic3kObjectArtKeys.SKORP)));
        assertTrue(plan.standaloneArt().stream().anyMatch(e -> e.key().equals(Sonic3kObjectArtKeys.SANDWORM)));
        assertTrue(plan.standaloneArt().stream().anyMatch(e -> e.key().equals(Sonic3kObjectArtKeys.ROCKN)));
    }

    @Test
    public void lrzPlanHasRocksAndBadniks() {
        Sonic3kPlcArtRegistry.ZoneArtPlan plan = Sonic3kPlcArtRegistry.getPlan(0x09, 0);
        assertNotNull(plan);
        assertEquals(9, plan.standaloneArt().size());
        assertTrue(plan.standaloneArt().stream().anyMatch(e -> e.key().equals(Sonic3kObjectArtKeys.FIREWORM_SEGMENTS)));
        assertTrue(plan.standaloneArt().stream().anyMatch(e -> e.key().equals(Sonic3kObjectArtKeys.IWAMODOKI)));
        assertTrue(plan.standaloneArt().stream().anyMatch(e -> e.key().equals(Sonic3kObjectArtKeys.TOXOMISTER)));

        // Level-art rock should be act 1 variant
        boolean hasLrz1Rock = plan.levelArt().stream()
                .anyMatch(e -> e.key().equals(Sonic3kObjectArtKeys.LRZ1_ROCK));
        assertTrue(hasLrz1Rock);
    }

    @Test
    public void sszPlanHasEggRobo() {
        Sonic3kPlcArtRegistry.ZoneArtPlan plan = Sonic3kPlcArtRegistry.getPlan(0x0A, 0);
        assertNotNull(plan);
        assertEquals(7, plan.standaloneArt().size());
        assertTrue(plan.standaloneArt().stream().anyMatch(e -> e.key().equals(Sonic3kObjectArtKeys.SSZ_EGG_ROBO)));

        // EggRobo should use palette 0
        Sonic3kPlcArtRegistry.StandaloneArtEntry eggRobo = plan.standaloneArt().stream()
                .filter(e -> e.key().equals(Sonic3kObjectArtKeys.SSZ_EGG_ROBO))
                .findFirst().orElse(null);
        assertNotNull(eggRobo);
        assertEquals(0, eggRobo.palette());
    }

    @Test
    public void dezPlanHasTwoBadniks() {
        Sonic3kPlcArtRegistry.ZoneArtPlan plan = Sonic3kPlcArtRegistry.getPlan(0x0B, 0);
        assertNotNull(plan);
        assertEquals(8, plan.standaloneArt().size());
        assertTrue(plan.standaloneArt().stream().anyMatch(e -> e.key().equals(Sonic3kObjectArtKeys.SPIKEBONKER)));
        assertTrue(plan.standaloneArt().stream().anyMatch(e -> e.key().equals(Sonic3kObjectArtKeys.CHAINSPIKE)));
    }

    @Test
    public void ddzPlanHasEggRobo() {
        Sonic3kPlcArtRegistry.ZoneArtPlan plan = Sonic3kPlcArtRegistry.getPlan(0x0C, 0);
        assertNotNull(plan);
        assertEquals(7, plan.standaloneArt().size());
        assertTrue(plan.standaloneArt().stream().anyMatch(e -> e.key().equals(Sonic3kObjectArtKeys.DDZ_EGG_ROBO)));
        Sonic3kPlcArtRegistry.StandaloneArtEntry ddzEggRobo = plan.standaloneArt().stream()
                .filter(e -> e.key().equals(Sonic3kObjectArtKeys.DDZ_EGG_ROBO))
                .findFirst().orElse(null);
        assertNotNull(ddzEggRobo);
        assertEquals(0, ddzEggRobo.palette());
    }

    @Test
    public void pachinkoPlanHasBonusStageObjectArt() {
        Sonic3kPlcArtRegistry.ZoneArtPlan plan = Sonic3kPlcArtRegistry.getPlan(0x14, 0);
        assertNotNull(plan);

        assertTrue(plan.levelArt().stream()
                .anyMatch(e -> e.key().equals(Sonic3kObjectArtKeys.PACHINKO_BUMPER)));
        assertTrue(plan.levelArt().stream()
                .anyMatch(e -> e.key().equals(Sonic3kObjectArtKeys.PACHINKO_TRIANGLE_BUMPER)));
        assertTrue(plan.levelArt().stream()
                .anyMatch(e -> e.key().equals(Sonic3kObjectArtKeys.PACHINKO_FLIPPER)));
        assertTrue(plan.levelArt().stream()
                .anyMatch(e -> e.key().equals(Sonic3kObjectArtKeys.PACHINKO_ENERGY_TRAP)));
        assertTrue(plan.levelArt().stream()
                .anyMatch(e -> e.key().equals(Sonic3kObjectArtKeys.PACHINKO_INVISIBLE_UNKNOWN)));
        assertTrue(plan.levelArt().stream()
                .anyMatch(e -> e.key().equals(Sonic3kObjectArtKeys.PACHINKO_PLATFORM)));
        assertTrue(plan.levelArt().stream()
                .anyMatch(e -> e.key().equals(Sonic3kObjectArtKeys.PACHINKO_ITEM_ORB)));
        assertTrue(plan.levelArt().stream()
                .anyMatch(e -> e.key().equals(Sonic3kObjectArtKeys.PACHINKO_GUMBALLS)));

        Sonic3kPlcArtRegistry.LevelArtEntry bumper = plan.levelArt().stream()
                .filter(e -> e.key().equals(Sonic3kObjectArtKeys.PACHINKO_BUMPER))
                .findFirst().orElse(null);
        assertNotNull(bumper);
        assertEquals(Sonic3kConstants.ARTTILE_PACHINKO_MAIN, bumper.artTileBase());

        Sonic3kPlcArtRegistry.LevelArtEntry gumballs = plan.levelArt().stream()
                .filter(e -> e.key().equals(Sonic3kObjectArtKeys.PACHINKO_GUMBALLS))
                .findFirst().orElse(null);
        assertNotNull(gumballs);
        assertEquals(Sonic3kConstants.ARTTILE_PACHINKO_GUMBALLS, gumballs.artTileBase());

        Sonic3kPlcArtRegistry.LevelArtEntry invisibleBeam = plan.levelArt().stream()
                .filter(e -> e.key().equals(Sonic3kObjectArtKeys.PACHINKO_INVISIBLE_UNKNOWN))
                .findFirst().orElse(null);
        assertNotNull(invisibleBeam);
        assertEquals(Sonic3kConstants.ARTTILE_PACHINKO_MAIN + 0x97, invisibleBeam.artTileBase());
    }

    @Test
    public void slotsPlanHasBonusStageObjectArt() {
        Sonic3kPlcArtRegistry.ZoneArtPlan plan = Sonic3kPlcArtRegistry.getPlan(0x15, 0);
        assertNotNull(plan);

        assertTrue(plan.levelArt().stream()
                .anyMatch(e -> e.key().equals(Sonic3kObjectArtKeys.SLOT_COLORED_WALL)));
        assertTrue(plan.levelArt().stream()
                .anyMatch(e -> e.key().equals(Sonic3kObjectArtKeys.SLOT_GOAL)));
        assertTrue(plan.levelArt().stream()
                .anyMatch(e -> e.key().equals(Sonic3kObjectArtKeys.SLOT_BUMPER)));
        assertTrue(plan.levelArt().stream()
                .anyMatch(e -> e.key().equals(Sonic3kObjectArtKeys.SLOT_R_LABEL)));
        assertTrue(plan.levelArt().stream()
                .anyMatch(e -> e.key().equals(Sonic3kObjectArtKeys.SLOT_PEPPERMINT)));
        assertTrue(plan.levelArt().stream()
                .anyMatch(e -> e.key().equals(Sonic3kObjectArtKeys.SLOT_MACHINE_FACE)));
        assertTrue(plan.levelArt().stream()
                .anyMatch(e -> e.key().equals(Sonic3kObjectArtKeys.SLOT_BONUS_CAGE)));
        assertTrue(plan.levelArt().stream()
                .anyMatch(e -> e.key().equals(Sonic3kObjectArtKeys.SLOT_SPIKE_REWARD)));

        Sonic3kPlcArtRegistry.LevelArtEntry cage = plan.levelArt().stream()
                .filter(e -> e.key().equals(Sonic3kObjectArtKeys.SLOT_BONUS_CAGE))
                .findFirst().orElse(null);
        assertNotNull(cage);
        assertEquals(Sonic3kConstants.ARTTILE_SLOTS_BLOCKS + 0x146, cage.artTileBase());
        assertEquals(Sonic3kConstants.MAP_SLOT_BONUS_CAGE_ADDR, cage.mappingAddr());
        assertEquals(S3kSpriteDataLoader.MappingFormat.STANDARD, cage.mappingFormat());

        Sonic3kPlcArtRegistry.LevelArtEntry spike = plan.levelArt().stream()
                .filter(e -> e.key().equals(Sonic3kObjectArtKeys.SLOT_SPIKE_REWARD))
                .findFirst().orElse(null);
        assertNotNull(spike);
        assertEquals(Sonic3kConstants.ARTTILE_SLOTS_BLOCKS + 0x155, spike.artTileBase());
        assertEquals(Sonic3kConstants.MAP_SLOT_SPIKE_REWARD_ADDR, spike.mappingAddr());
        assertEquals(S3kSpriteDataLoader.MappingFormat.STANDARD, spike.mappingFormat());

        assertEquals(S3kSpriteDataLoader.MappingFormat.LEGACY_BYTE_X,
                findLevelArt(plan, Sonic3kObjectArtKeys.SLOT_COLORED_WALL).mappingFormat());
        assertEquals(S3kSpriteDataLoader.MappingFormat.LEGACY_BYTE_X,
                findLevelArt(plan, Sonic3kObjectArtKeys.SLOT_GOAL).mappingFormat());
        assertEquals(S3kSpriteDataLoader.MappingFormat.LEGACY_BYTE_X,
                findLevelArt(plan, Sonic3kObjectArtKeys.SLOT_BUMPER).mappingFormat());
        assertEquals(S3kSpriteDataLoader.MappingFormat.LEGACY_BYTE_X,
                findLevelArt(plan, Sonic3kObjectArtKeys.SLOT_R_LABEL).mappingFormat());
        assertEquals(S3kSpriteDataLoader.MappingFormat.LEGACY_BYTE_X,
                findLevelArt(plan, Sonic3kObjectArtKeys.SLOT_PEPPERMINT).mappingFormat());
        assertEquals(S3kSpriteDataLoader.MappingFormat.LEGACY_BYTE_X,
                findLevelArt(plan, Sonic3kObjectArtKeys.SLOT_MACHINE_FACE).mappingFormat());

        Sonic3kPlcArtRegistry.StandaloneArtEntry ring = plan.standaloneArt().stream()
                .filter(e -> e.key().equals(Sonic3kObjectArtKeys.SLOT_RING_STAGE))
                .findFirst().orElse(null);
        assertNotNull(ring);
        assertEquals(S3kSpriteDataLoader.MappingFormat.LEGACY_BYTE_X, ring.mappingFormat());
    }

    private static Sonic3kPlcArtRegistry.LevelArtEntry findLevelArt(
            Sonic3kPlcArtRegistry.ZoneArtPlan plan, String key) {
        return plan.levelArt().stream()
                .filter(e -> e.key().equals(key))
                .findFirst()
                .orElseThrow();
    }

    private static void assertStandaloneArtKeys(Sonic3kPlcArtRegistry.ZoneArtPlan plan, String... keys) {
        Set<String> actual = new HashSet<>();
        for (Sonic3kPlcArtRegistry.StandaloneArtEntry entry : plan.standaloneArt()) {
            actual.add(entry.key());
        }
        for (String key : keys) {
            assertTrue(actual.contains(key), "Missing standalone art key: " + key);
        }
    }

    private static void assertLevelArtKeys(Sonic3kPlcArtRegistry.ZoneArtPlan plan, String... keys) {
        Set<String> actual = new HashSet<>();
        for (Sonic3kPlcArtRegistry.LevelArtEntry entry : plan.levelArt()) {
            actual.add(entry.key());
        }
        for (String key : keys) {
            assertTrue(actual.contains(key), "Missing level art key: " + key);
        }
    }

    private static void assertMappingTilesWithinSheet(ObjectSpriteSheet sheet, String message) {
        int patternCount = sheet.getPatterns().length;
        for (int frameIndex = 0; frameIndex < sheet.getFrameCount(); frameIndex++) {
            SpriteMappingFrame frame = sheet.getFrame(frameIndex);
            for (SpriteMappingPiece piece : frame.pieces()) {
                int endExclusive = piece.tileIndex() + (piece.widthTiles() * piece.heightTiles());
                assertTrue(piece.tileIndex() >= 0 && endExclusive <= patternCount,
                        message + " frame=" + frameIndex
                                + " tileRange=0x" + Integer.toHexString(piece.tileIndex())
                                + "-0x" + Integer.toHexString(endExclusive - 1)
                                + " patterns=" + patternCount);
            }
        }
    }

    private static void assertRideGrapplePiece(SpriteMappingFrame frame, int widthTiles, int heightTiles, int tileIndex) {
        assertEquals(1, frame.pieces().size(), "LBZ ride grapple frame should have one mapping piece");
        SpriteMappingPiece piece = frame.pieces().getFirst();
        assertEquals(widthTiles, piece.widthTiles());
        assertEquals(heightTiles, piece.heightTiles());
        assertEquals(tileIndex, piece.tileIndex());
    }

    private static void assertLbzMovingPlatformPiece(SpriteMappingFrame frame, int pieceIndex,
                                                     int widthTiles, int heightTiles, int tileIndex) {
        assertEquals(2, frame.pieces().size(), "LBZ moving platform frame should have two mapping pieces");
        SpriteMappingPiece piece = frame.pieces().get(pieceIndex);
        assertEquals(widthTiles, piece.widthTiles());
        assertEquals(heightTiles, piece.heightTiles());
        assertEquals(tileIndex, piece.tileIndex());
    }

    private static void assertLbzGateLaserPiece(SpriteMappingPiece piece, int xOffset, int yOffset,
            int widthTiles, int heightTiles) {
        assertEquals(xOffset, piece.xOffset());
        assertEquals(yOffset, piece.yOffset());
        assertEquals(widthTiles, piece.widthTiles());
        assertEquals(heightTiles, piece.heightTiles());
        assertEquals(0x36, piece.tileIndex());
    }

    private static void assertLbzFlameThrowerFrame(SpriteMappingFrame frame, int[][] expectedPieces) {
        assertEquals(expectedPieces.length, frame.pieces().size(), "LBZ flame thrower frame piece count");
        for (int i = 0; i < expectedPieces.length; i++) {
            SpriteMappingPiece piece = frame.pieces().get(i);
            assertEquals(expectedPieces[i][0], piece.widthTiles(), "LBZ flame thrower piece " + i + " width");
            assertEquals(expectedPieces[i][1], piece.heightTiles(), "LBZ flame thrower piece " + i + " height");
            assertEquals(expectedPieces[i][2], piece.tileIndex(), "LBZ flame thrower piece " + i + " tile");
        }
    }

    private static void assertLbz2Piece(SpriteMappingFrame frame, int pieceIndex,
                                        int expectedPieceCount, int widthTiles,
                                        int heightTiles, int tileIndex) {
        assertEquals(expectedPieceCount, frame.pieces().size(), "LBZ2 mapping frame piece count");
        SpriteMappingPiece piece = frame.pieces().get(pieceIndex);
        assertEquals(widthTiles, piece.widthTiles(), "LBZ2 mapping piece width");
        assertEquals(heightTiles, piece.heightTiles(), "LBZ2 mapping piece height");
        assertEquals(tileIndex, piece.tileIndex(), "LBZ2 mapping piece tile");
    }

    private static void assertLbzPipePlugPiece(SpriteMappingPiece piece, int xOffset, int yOffset,
            int widthTiles, int heightTiles, int tileIndex) {
        assertEquals(xOffset, piece.xOffset(), "LBZ PipePlug piece x offset");
        assertEquals(yOffset, piece.yOffset(), "LBZ PipePlug piece y offset");
        assertEquals(widthTiles, piece.widthTiles(), "LBZ PipePlug piece width");
        assertEquals(heightTiles, piece.heightTiles(), "LBZ PipePlug piece height");
        assertEquals(tileIndex, piece.tileIndex(), "LBZ PipePlug piece tile");
    }

    private static Sonic3kPlcArtRegistry.LevelArtEntry requireLevelArt(Sonic3kPlcArtRegistry.ZoneArtPlan plan,
                                                                       String key) {
        return plan.levelArt().stream()
                .filter(entry -> key.equals(entry.key()))
                .findFirst()
                .orElseThrow();
    }

    private static Sonic3kPlcArtRegistry.StandaloneArtEntry requireStandaloneArt(
            Sonic3kPlcArtRegistry.ZoneArtPlan plan,
            String key) {
        return plan.standaloneArt().stream()
                .filter(entry -> key.equals(entry.key()))
                .findFirst()
                .orElseThrow();
    }

    private static void assertSaneObjectSpriteSheet(String context, ObjectSpriteSheet sheet) {
        assertTrue(sheet.getPatterns().length > 0, context + " should have decompressed patterns");
        List<SpriteMappingFrame> frames = new ArrayList<>();
        for (int i = 0; i < sheet.getFrameCount(); i++) {
            frames.add(sheet.getFrame(i));
        }
        assertSaneMappingFrames(context, frames, sheet.getPatterns().length);
    }

    private static void assertSaneStandaloneEntry(
            String context,
            RomByteReader reader,
            Rom rom,
            Sonic3kObjectArt art,
            Sonic3kPlcArtRegistry.StandaloneArtEntry entry) throws IOException {
        if (entry.mappingAddr() > 0) {
            List<SpriteMappingFrame> rawFrames = entry.mappingFrameCount() > 0
                    ? S3kSpriteDataLoader.loadMappingFrames(reader, entry.mappingAddr(), entry.mappingFrameCount())
                    : S3kSpriteDataLoader.loadMappingFrames(reader, entry.mappingAddr(), entry.mappingFormat());
            assertSaneMappingFrames(context + " standalone raw mappings " + entry.key(), rawFrames, -1);
        }

        try {
            ObjectSpriteSheet sheet = art.loadStandaloneSheet(rom, entry);
            assertNotNull(sheet, context + " standalone art " + entry.key() + " should build a sheet");
            assertSaneObjectSpriteSheet(context + " standalone art " + entry.key(), sheet);
        } catch (IOException e) {
            assertTrue(isKnownSplitStandaloneSheet(entry),
                    context + " standalone art " + entry.key() + " failed to build: " + e.getMessage());
        }
    }

    private static boolean isKnownSplitStandaloneSheet(Sonic3kPlcArtRegistry.StandaloneArtEntry entry) {
        return false;
    }

    private static void collectSAndKRuntimeAddressViolation(List<String> violations, String context,
            String key, String field, int address) {
        if (address < 0) {
            return;
        }
        if (address >= STANDALONE_S3_HALF_START
                && !REVIEWED_S3_SIDE_ART_REFERENCES.contains(reviewedS3SideReference(key, field, address))) {
            violations.add(context + " uses S3-side address 0x" + Integer.toHexString(address));
        }
    }

    private static String reviewedS3SideReference(String key, String field, int address) {
        return key + ":" + field + ":0x" + Integer.toHexString(address);
    }

    private static void assertSaneMappingFrames(
            String context,
            List<SpriteMappingFrame> frames,
            int patternCount) {
        assertNotNull(frames, context + " mappings should not be null");
        assertTrue(frames.size() <= MAX_SANE_FRAMES,
                context + " frame count " + frames.size() + " exceeds " + MAX_SANE_FRAMES);

        for (int frameIndex = 0; frameIndex < frames.size(); frameIndex++) {
            SpriteMappingFrame frame = frames.get(frameIndex);
            assertNotNull(frame, context + " frame " + frameIndex + " should not be null");
            List<SpriteMappingPiece> pieces = frame.pieces();
            assertNotNull(pieces, context + " frame " + frameIndex + " pieces should not be null");
            assertTrue(pieces.size() <= MAX_SANE_FRAME_PIECES,
                    context + " frame " + frameIndex + " piece count " + pieces.size()
                            + " exceeds " + MAX_SANE_FRAME_PIECES);

            int totalTiles = 0;
            boolean first = true;
            int minX = 0;
            int minY = 0;
            int maxX = 0;
            int maxY = 0;
            for (int pieceIndex = 0; pieceIndex < pieces.size(); pieceIndex++) {
                SpriteMappingPiece piece = pieces.get(pieceIndex);
                assertNotNull(piece, context + " frame " + frameIndex + " piece " + pieceIndex + " is null");
                assertTrue(piece.widthTiles() >= 1 && piece.widthTiles() <= 4,
                        context + " frame " + frameIndex + " piece " + pieceIndex + " invalid width");
                assertTrue(piece.heightTiles() >= 1 && piece.heightTiles() <= 4,
                        context + " frame " + frameIndex + " piece " + pieceIndex + " invalid height");
                assertTrue(Math.abs(piece.xOffset()) <= MAX_SANE_ABS_PIECE_OFFSET_PIXELS
                                && Math.abs(piece.yOffset()) <= MAX_SANE_ABS_PIECE_OFFSET_PIXELS,
                        context + " frame " + frameIndex + " piece " + pieceIndex + " extreme offset");

                int pieceTiles = piece.widthTiles() * piece.heightTiles();
                totalTiles += pieceTiles;
                assertTrue(totalTiles <= MAX_SANE_FRAME_TILES,
                        context + " frame " + frameIndex + " tile count exceeds " + MAX_SANE_FRAME_TILES);
                assertTrue(piece.tileIndex() >= 0,
                        context + " frame " + frameIndex + " piece " + pieceIndex + " negative tile index");
                if (patternCount >= 0) {
                    assertTrue(piece.tileIndex() + pieceTiles <= patternCount,
                            context + " frame " + frameIndex + " piece " + pieceIndex
                                    + " references tile range [" + piece.tileIndex() + ","
                                    + (piece.tileIndex() + pieceTiles) + ") outside pattern count "
                                    + patternCount);
                }

                int left = piece.xOffset();
                int top = piece.yOffset();
                int right = left + piece.widthTiles() * 8 - 1;
                int bottom = top + piece.heightTiles() * 8 - 1;
                if (first) {
                    minX = left;
                    minY = top;
                    maxX = right;
                    maxY = bottom;
                    first = false;
                } else {
                    minX = Math.min(minX, left);
                    minY = Math.min(minY, top);
                    maxX = Math.max(maxX, right);
                    maxY = Math.max(maxY, bottom);
                }
            }
            if (!first) {
                int width = maxX - minX + 1;
                int height = maxY - minY + 1;
                assertTrue(width <= MAX_SANE_FRAME_SPAN_PIXELS && height <= MAX_SANE_FRAME_SPAN_PIXELS,
                        context + " frame " + frameIndex + " bounds span " + width + "x" + height);
            }
        }
    }

    @Test
    public void allZonesReturnNonNullPlan() {
        // All 14 zone indices (0x00-0x0D) must return non-null plans for both acts
        for (int zone = 0x00; zone <= 0x0D; zone++) {
            for (int act = 0; act <= 1; act++) {
                Sonic3kPlcArtRegistry.ZoneArtPlan plan = Sonic3kPlcArtRegistry.getPlan(zone, act);
                assertNotNull(plan, "Zone 0x" + Integer.toHexString(zone) + " act " + act + " returned null");
                assertNotNull(plan.standaloneArt());
                assertNotNull(plan.levelArt());
                // All zones should have at least the 7 shared level-art entries
                assertTrue(plan.levelArt().size() >= 7, "Zone 0x" + Integer.toHexString(zone) + " act " + act
                                + " has fewer than 7 level art entries");
            }
        }
    }

    @Test
    public void allPopulatedZonesHaveStandaloneEntries() {
        // Every zone except HPZ (0x0D) should have at least one standalone entry
        int[] populatedZones = {0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08, 0x09, 0x0A, 0x0B, 0x0C};
        for (int zone : populatedZones) {
            Sonic3kPlcArtRegistry.ZoneArtPlan plan = Sonic3kPlcArtRegistry.getPlan(zone, 0);
            assertFalse(plan.standaloneArt().isEmpty(), "Zone 0x" + Integer.toHexString(zone) + " should have standalone entries");
        }
    }

    @Test
    public void noDuplicateKeysInPlan() {
        // No plan should contain duplicate keys across standalone + level art
        for (int zone = 0x00; zone <= 0x0D; zone++) {
            for (int act = 0; act <= 1; act++) {
                Sonic3kPlcArtRegistry.ZoneArtPlan plan = Sonic3kPlcArtRegistry.getPlan(zone, act);
                Set<String> keys = new HashSet<>();
                for (var e : plan.standaloneArt()) {
                    assertTrue(keys.add(e.key()), "Duplicate standalone key '" + e.key() + "' in zone 0x"
                            + Integer.toHexString(zone) + " act " + act);
                }
                for (var e : plan.levelArt()) {
                    assertTrue(keys.add(e.key()), "Duplicate level-art key '" + e.key() + "' in zone 0x"
                            + Integer.toHexString(zone) + " act " + act);
                }
            }
        }
    }

    @Test
    public void unknownZoneReturnsSharedOnlyPlan() {
        Sonic3kPlcArtRegistry.ZoneArtPlan plan = Sonic3kPlcArtRegistry.getPlan(0xFF, 0);

        assertNotNull(plan);
        assertTrue(plan.levelArt().size() >= 7, "Expected at least 7 shared level art entries");
        assertEquals(6, plan.standaloneArt().size(), "Expected shared standalone art only for unknown zone");
    }
}
