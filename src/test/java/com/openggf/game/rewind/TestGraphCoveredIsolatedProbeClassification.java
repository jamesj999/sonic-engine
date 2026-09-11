package com.openggf.game.rewind;

import com.openggf.game.rewind.RewindRoundTripHarness.RoundTripSweepResult;
import com.openggf.graphics.GraphicsManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class TestGraphCoveredIsolatedProbeClassification {
    private static final String DER_GRAPH_TEST =
            "com.openggf.game.sonic2.objects.bosses.TestS2DeathEggRobotGraphRewind";
    private static final String DER_BOMB_GRAPH_TEST =
            "TestS2DezBombGraphRewind";
    private static final String AIZ_END_BOSS_GRAPH_TEST =
            "TestS3kAizEndBossGraphRewind";
    private static final String AIZ_INTRO_GRAPH_TEST =
            "TestS3kAizIntroGraphRewind";
    private static final String AIZ_MINIBOSS_GRAPH_TEST =
            "TestS3kAizMinibossGraphRewind";
    private static final String HCZ_END_BOSS_GRAPH_TEST =
            "TestS3kHczEndBossGraphRewind";
    private static final String HCZ_CGZ_FAN_GRAPH_TEST =
            "TestS3kHczCgzFanGraphRewind";
    private static final String CNZ_END_BOSS_GRAPH_TEST =
            "TestS3kCnzEndBossGraphRewind";
    private static final String MHZ_END_BOSS_GRAPH_TEST =
            "TestS3kMhzEndBossGraphRewind";
    private static final String MECHA_SONIC_GRAPH_TEST =
            "com.openggf.game.sonic2.objects.bosses.TestS2MechaSonicGraphRewind";
    private static final String MTZ_BOSS_GRAPH_TEST =
            "com.openggf.game.sonic2.objects.bosses.TestS2MTZBossGraphRewind";
    private static final String WFZ_BOSS_GRAPH_TEST =
            "com.openggf.game.sonic2.objects.bosses.TestS2WfzBossGraphRewind";
    private static final String S1_BADNIK_CHILD_GRAPH_TEST =
            "TestS1BadnikChildGraphRewind";
    private static final String S1_FZ_BOSS_GRAPH_TEST =
            "TestS1FzBossGraphRewind";
    private static final String S1_GHZ_BOSS_GRAPH_TEST =
            "TestS1GhzBossGraphRewind";
    private static final String S2_BADNIK_CHILD_GRAPH_TEST =
            "TestS2BadnikChildGraphRewind";
    private static final String S2_EHZ_BOSS_GRAPH_TEST =
            "TestS2EhzBossGraphRewind";
    private static final String S2_OOZ_BURNER_FLAME_GRAPH_TEST =
            "TestS2OozBurnerFlameGraphRewind";
    private static final String S2_COG_GRAPH_TEST =
            "com.openggf.game.sonic2.objects.TestS2CogGraphRewind";
    private static final String S2_SWINGING_PLATFORM_GRAPH_TEST =
            "com.openggf.game.sonic2.objects.TestS2SwingingPlatformGraphRewind";
    private static final String S2_ARZ_ARROW_GRAPH_TEST =
            "TestS2ArzArrowGraphRewind";
    private static final String S2_ARZ_ROT_PFORMS_GRAPH_TEST =
            "TestS2ArzRotPformsGraphRewind";
    private static final String S2_CPZ_BOSS_GRAPH_TEST =
            "TestS2CpzBossGraphRewind";
    private static final String S1_SYZ_BOSS_BLOCK_GRAPH_TEST =
            "TestS1SyzBossBlockGraphRewind";
    private static final String SEESAW_GRAPH_TEST =
            "TestSeesawBallGraphRewind";
    private static final String CHECKPOINT_STARPOST_GRAPH_TEST =
            "TestCheckpointStarpostGraphRewind";
    private static final String AIZ_DISAPPEARING_FLOOR_GRAPH_TEST =
            "com.openggf.game.sonic3k.objects.TestAizDisappearingFloorGraphRewind";
    private static final String LBZ1_CUTSCENE_GRAPH_TEST =
            "com.openggf.game.sonic3k.objects.TestS3kLbz1CutsceneGraphRewind";
    private static final String LBZ2_RIDE_CAMEO_TEST =
            "com.openggf.game.sonic3k.objects.TestLbz2RideCameoInstances";
    private static final String LBZ_GATE_LASER_TEST =
            "com.openggf.game.sonic3k.objects.TestLbzGateLaserObjectInstance";
    private static final String LBZ_PIPE_PLUG_TEST =
            "com.openggf.game.sonic3k.objects.TestLbzPipePlugObjectInstance";
    private static final String AUTOMATIC_TUNNEL_TEST =
            "com.openggf.game.sonic3k.objects.TestAutomaticTunnelObjectInstance";
    private static final String LBZ_END_BOSS_TEST =
            "com.openggf.game.sonic3k.objects.TestLbzEndBossInstance";
    private static final String LBZ_FINAL_BOSS_1_TEST =
            "com.openggf.game.sonic3k.objects.TestLbzFinalBoss1Instance";
    private static final String HCZ_MINIBOSS_ROCKET_TOUCH_GRAPH_TEST =
            "com.openggf.game.sonic3k.objects.TestHczMinibossRocketTouchRewind";
    private static final String ICZ_BIG_SNOW_PILE_GRAPH_TEST =
            "com.openggf.game.sonic3k.objects.TestRewindFixS3KIczBigSnowPileCodec";
    private static final String S3K_NESTED_HURTBOX_GRAPH_TEST =
            "TestS3kNestedHurtboxGraphRewind";
    private static final String MGZ2_COLLAPSE_GRAPH_TEST =
            "com.openggf.game.sonic3k.events.TestSonic3kMgz2CollapseEvents";
    private static final String S3K_BADNIK_CHILD_GRAPH_TEST =
            "TestS3kBadnikChildGraphRewind";
    private static final String ICZ_SEGMENT_COLUMN_GRAPH_TEST =
            "TestS3kIczSegmentColumnGraphRewind";
    private static final String S3K_SIGNPOST_STUB_GRAPH_TEST =
            "TestS3kSignpostStubGraphRewind";
    private static final String S3K_SLOT_BONUS_GRAPH_TEST =
            "TestS3kSlotBonusGraphRewind";
    private static final String S3K_SELF_CONTAINED_TRANSIENT_GRAPH_TEST =
            "com.openggf.game.sonic3k.objects.TestS3kSelfContainedTransientRewind";
    private static final String S2_SELF_CONTAINED_TRANSIENT_GRAPH_TEST =
            "com.openggf.game.sonic2.objects.TestS2SelfContainedTransientRewind";
    private static final String AIZ_SHIP_BOMB_GRAPH_TEST =
            "com.openggf.game.sonic3k.objects.TestAizShipBombGraphRewind";
    private static final String S3K_CUTSCENE_KNUCKLES_GRAPH_TEST =
            "TestS3kCutsceneKnucklesGraphRewind";
    private static final String MHZ_MINIBOSS_ESCAPE_SHARD_GRAPH_TEST =
            "com.openggf.game.sonic3k.objects.TestMhzMinibossEscapeShardGraphRewind";
    private static final String SHIELD_PENDING_RESTORE_TEST =
            "com.openggf.level.objects.TestShieldRewindPendingRestore";

    @BeforeEach
    void initHeadless() {
        GraphicsManager.getInstance().initHeadless();
    }

    @AfterEach
    void tearDown() {
        GraphicsManager.getInstance().resetState();
    }

    @Test
    void deathEggRobotNoProbeAndParentDependentChildrenAreReportedAsGraphCovered() {
        Map<String, String> expected = Map.of(
                "com.openggf.game.sonic2.objects.bosses.Sonic2DeathEggRobotInstance$ArticulatedChild",
                DER_GRAPH_TEST,
                "com.openggf.game.sonic2.objects.bosses.Sonic2DeathEggRobotInstance$ForearmChild",
                DER_GRAPH_TEST,
                "com.openggf.game.sonic2.objects.bosses.Sonic2DeathEggRobotInstance$HeadChild",
                DER_GRAPH_TEST,
                "com.openggf.game.sonic2.objects.bosses.Sonic2DeathEggRobotInstance$JetChild",
                DER_GRAPH_TEST,
                "com.openggf.game.sonic2.objects.bosses.Sonic2DeathEggRobotInstance$SensorChild",
                DER_GRAPH_TEST,
                "com.openggf.game.sonic2.objects.bosses.Sonic2DeathEggRobotInstance$BombChild",
                DER_BOMB_GRAPH_TEST);

        expected.forEach(this::assertGraphCovered);
    }

    @Test
    void aizEndBossNoProbeAndParentDependentChildrenAreReportedAsGraphCovered() {
        Map<String, String> expected = Map.of(
                "com.openggf.game.sonic3k.objects.AizEndBossArmChild",
                AIZ_END_BOSS_GRAPH_TEST,
                "com.openggf.game.sonic3k.objects.AizEndBossPropellerChild",
                AIZ_END_BOSS_GRAPH_TEST,
                "com.openggf.game.sonic3k.objects.AizEndBossFlameChild",
                AIZ_END_BOSS_GRAPH_TEST,
                "com.openggf.game.sonic3k.objects.AizEndBossBombChild",
                AIZ_END_BOSS_GRAPH_TEST,
                "com.openggf.game.sonic3k.objects.AizEndBossSmokeChild",
                AIZ_END_BOSS_GRAPH_TEST,
                "com.openggf.game.sonic3k.objects.AizEndBossFlameColumnChild",
                AIZ_END_BOSS_GRAPH_TEST,
                "com.openggf.game.sonic3k.objects.AizEndBossWaterfallChild",
                AIZ_END_BOSS_GRAPH_TEST,
                "com.openggf.game.sonic3k.objects.AizEndBossShipChild",
                AIZ_END_BOSS_GRAPH_TEST);

        expected.forEach(this::assertGraphCovered);
    }

    @Test
    void aizIntroNoProbeAndParentDependentChildrenAreReportedAsGraphCovered() {
        Map<String, String> expected = Map.of(
                "com.openggf.game.sonic3k.objects.AizIntroPlaneChild",
                AIZ_INTRO_GRAPH_TEST,
                "com.openggf.game.sonic3k.objects.AizIntroWaveChild",
                AIZ_INTRO_GRAPH_TEST,
                "com.openggf.game.sonic3k.objects.AizIntroEmeraldGlowChild",
                AIZ_INTRO_GRAPH_TEST);

        expected.forEach(this::assertGraphCovered);
    }

    @Test
    void aizMinibossParentDependentChildrenAreReportedAsGraphCovered() {
        Map<String, String> expected = Map.of(
                "com.openggf.game.sonic3k.objects.AizMinibossArmChild",
                AIZ_MINIBOSS_GRAPH_TEST,
                "com.openggf.game.sonic3k.objects.AizMinibossBarrelShotChild",
                AIZ_MINIBOSS_GRAPH_TEST,
                "com.openggf.game.sonic3k.objects.AizMinibossBarrelShotFlareChild",
                AIZ_MINIBOSS_GRAPH_TEST,
                "com.openggf.game.sonic3k.objects.AizMinibossBodyChild",
                AIZ_MINIBOSS_GRAPH_TEST,
                "com.openggf.game.sonic3k.objects.AizMinibossFlameBarrelChild",
                AIZ_MINIBOSS_GRAPH_TEST,
                "com.openggf.game.sonic3k.objects.AizMinibossFlameChild",
                AIZ_MINIBOSS_GRAPH_TEST,
                "com.openggf.game.sonic3k.objects.AizMinibossNapalmProjectile",
                AIZ_MINIBOSS_GRAPH_TEST);

        expected.forEach(this::assertGraphCovered);
    }

    @Test
    void hczEndBossAndFanChildrenAreReportedAsGraphCovered() {
        Map<String, String> expected = Map.of(
                "com.openggf.game.sonic3k.objects.bosses.HczEndBossBlade",
                HCZ_END_BOSS_GRAPH_TEST,
                "com.openggf.game.sonic3k.objects.bosses.HczEndBossBladeSplash",
                HCZ_END_BOSS_GRAPH_TEST,
                "com.openggf.game.sonic3k.objects.bosses.HczEndBossBladeImpactExplosion",
                HCZ_END_BOSS_GRAPH_TEST,
                "com.openggf.game.sonic3k.objects.bosses.HczEndBossBladeWaterChute",
                HCZ_END_BOSS_GRAPH_TEST,
                "com.openggf.game.sonic3k.objects.bosses.HczEndBossRobotnikShip",
                HCZ_END_BOSS_GRAPH_TEST,
                "com.openggf.game.sonic3k.objects.bosses.HczEndBossTurbine",
                HCZ_END_BOSS_GRAPH_TEST,
                "com.openggf.game.sonic3k.objects.bosses.HczEndBossWaterColumn",
                HCZ_END_BOSS_GRAPH_TEST,
                "com.openggf.game.sonic3k.objects.bosses.HczEndBossEggCapsuleButton",
                S3K_SELF_CONTAINED_TRANSIENT_GRAPH_TEST,
                "com.openggf.game.sonic3k.objects.HCZCGZFanObjectInstance$FanPlatformChild",
                HCZ_CGZ_FAN_GRAPH_TEST);

        expected.forEach(this::assertGraphCovered);
    }

    @Test
    void hczTransitionAndMgzBossChildrenAreReportedAsGraphCovered() {
        Map<String, String> expected = Map.of(
                "com.openggf.game.sonic3k.objects.HczTransitionBubbleInstance",
                "com.openggf.game.sonic3k.objects.TestHczTransitionBubbleInstance",
                "com.openggf.game.sonic3k.objects.MgzEndBossKnuxDrillChild",
                "com.openggf.game.sonic3k.objects.TestMgzEndBossKnuxInstance",
                "com.openggf.game.sonic3k.objects.MgzEndBossRenderChild",
                "com.openggf.game.sonic3k.objects.TestMgzEndBossKnuxInstance",
                "com.openggf.game.sonic3k.objects.bosses.HczEndBossBubbleParticle",
                HCZ_END_BOSS_GRAPH_TEST,
                "com.openggf.game.sonic3k.objects.bosses.HczEndBossLowerHousing",
                HCZ_END_BOSS_GRAPH_TEST,
                "com.openggf.game.sonic3k.objects.bosses.HczEndBossRobotnikHead",
                HCZ_END_BOSS_GRAPH_TEST,
                "com.openggf.game.sonic3k.objects.bosses.HczEndBossWaterSprayChild",
                HCZ_END_BOSS_GRAPH_TEST,
                "com.openggf.game.sonic3k.objects.bosses.HczEndBossWaterSurfaceChild",
                HCZ_END_BOSS_GRAPH_TEST);

        expected.forEach(this::assertGraphCovered);
    }

    @Test
    void cnzEndBossChildrenAreReportedAsGraphCovered() {
        Map<String, String> expected = Map.of(
                "com.openggf.game.sonic3k.objects.bosses.CnzEndBossArmChild", CNZ_END_BOSS_GRAPH_TEST,
                "com.openggf.game.sonic3k.objects.bosses.CnzEndBossBoundaryController", CNZ_END_BOSS_GRAPH_TEST,
                "com.openggf.game.sonic3k.objects.bosses.CnzEndBossExplosionControllerChild", CNZ_END_BOSS_GRAPH_TEST,
                "com.openggf.game.sonic3k.objects.bosses.CnzEndBossFieldChild", CNZ_END_BOSS_GRAPH_TEST,
                "com.openggf.game.sonic3k.objects.bosses.CnzEndBossMagnetChild", CNZ_END_BOSS_GRAPH_TEST,
                "com.openggf.game.sonic3k.objects.bosses.CnzEndBossRobotnikFlameChild", CNZ_END_BOSS_GRAPH_TEST,
                "com.openggf.game.sonic3k.objects.bosses.CnzEndBossRobotnikHeadChild", CNZ_END_BOSS_GRAPH_TEST,
                "com.openggf.game.sonic3k.objects.bosses.CnzEndBossRobotnikShipChild", CNZ_END_BOSS_GRAPH_TEST);

        expected.forEach(this::assertGraphCovered);
    }

    @Test
    void mhzEndBossChildrenAreReportedAsGraphCovered() {
        Map<String, String> expected = Map.of(
                "com.openggf.game.sonic3k.objects.bosses.MhzEndBossArenaHelperInstance",
                "com.openggf.game.sonic3k.objects.TestMhzEndBossArenaHelperRewind",
                "com.openggf.game.sonic3k.objects.bosses.MhzEndBossHitProxyChild",
                "com.openggf.game.sonic3k.objects.bosses.TestMhzEndBossHitProxyRewind",
                "com.openggf.game.sonic3k.objects.bosses.MhzEndBossInstance$MhzEndBossWalkoffPrepChild",
                MHZ_END_BOSS_GRAPH_TEST,
                "com.openggf.game.sonic3k.objects.bosses.MhzEndBossRobotnikHeadChild",
                "com.openggf.game.sonic3k.objects.bosses.TestMhzEndBossRobotnikHeadRewind",
                "com.openggf.game.sonic3k.objects.bosses.MhzEndBossSpikeChild",
                "com.openggf.game.sonic3k.objects.bosses.TestMhzEndBossSpikeRewind",
                "com.openggf.game.sonic3k.objects.bosses.MhzEndBossVisualChild",
                "com.openggf.game.sonic3k.objects.bosses.TestMhzEndBossVisualRewind",
                "com.openggf.game.sonic3k.objects.bosses.MhzEndBossWeatherMachineChild",
                "com.openggf.game.sonic3k.objects.bosses.TestMhzEndBossWeatherMachineRewind",
                "com.openggf.game.sonic3k.objects.bosses.MhzEndBossWeatherVisualChild",
                "com.openggf.game.sonic3k.objects.bosses.TestMhzEndBossWeatherVisualRewind");

        expected.forEach(this::assertGraphCovered);
    }

    @Test
    void sonic1CoveredParentDependentChildrenAreReportedAsGraphCovered() {
        Map<String, String> expected = Map.of(
                "com.openggf.game.sonic1.objects.badniks.Sonic1BombFuseInstance",
                S1_BADNIK_CHILD_GRAPH_TEST,
                "com.openggf.game.sonic1.objects.badniks.Sonic1CaterkillerBodyInstance",
                S1_BADNIK_CHILD_GRAPH_TEST,
                "com.openggf.game.sonic1.objects.badniks.Sonic1OrbinautBadnikInstance$OrbSpikeObjectInstance",
                S1_BADNIK_CHILD_GRAPH_TEST,
                "com.openggf.game.sonic1.objects.bosses.FZCylinder",
                S1_FZ_BOSS_GRAPH_TEST,
                "com.openggf.game.sonic1.objects.bosses.FZPlasmaLauncher",
                S1_FZ_BOSS_GRAPH_TEST,
                "com.openggf.game.sonic1.objects.bosses.Sonic1FalseFloorInstance$FalseFloorBlock",
                "com.openggf.game.sonic1.objects.TestFalseFloorBlockRewindGenericRestore",
                "com.openggf.game.sonic1.objects.Sonic1GlassReflectionInstance",
                "com.openggf.game.sonic1.objects.TestSonic1GlassReflectionGraphRewind",
                "com.openggf.game.sonic1.objects.Sonic1LamppostTwirlInstance",
                "TestCheckpointStarpostGraphRewind",
                "com.openggf.game.sonic1.objects.Sonic1SeesawBallObjectInstance",
                "TestSeesawBallGraphRewind");

        expected.forEach(this::assertGraphCovered);
    }

    @Test
    void sonic2BadnikParentDependentChildrenAreReportedAsGraphCovered() {
        Map<String, String> expected = Map.of(
                "com.openggf.game.sonic2.objects.badniks.AquisBadnikInstance$AquisWingChild",
                S2_BADNIK_CHILD_GRAPH_TEST,
                "com.openggf.game.sonic2.objects.badniks.BalkiryJetObjectInstance",
                S2_BADNIK_CHILD_GRAPH_TEST,
                "com.openggf.game.sonic2.objects.badniks.BalkiryBadnikInstance",
                S2_BADNIK_CHILD_GRAPH_TEST,
                "com.openggf.game.sonic2.objects.badniks.RexonHeadObjectInstance",
                S2_BADNIK_CHILD_GRAPH_TEST,
                "com.openggf.game.sonic2.objects.badniks.ShellcrackerClawInstance",
                S2_BADNIK_CHILD_GRAPH_TEST);

        expected.forEach(this::assertGraphCovered);
    }

    @Test
    void sonic2EhzBossParentDependentFamilyIsReportedAsGraphCovered() {
        Map<String, String> expected = Map.of(
                "com.openggf.game.sonic2.objects.bosses.EHZBossGroundVehicle",
                S2_EHZ_BOSS_GRAPH_TEST,
                "com.openggf.game.sonic2.objects.bosses.EHZBossPropeller",
                S2_EHZ_BOSS_GRAPH_TEST,
                "com.openggf.game.sonic2.objects.bosses.EHZBossSpike",
                S2_EHZ_BOSS_GRAPH_TEST,
                "com.openggf.game.sonic2.objects.bosses.EHZBossVehicleTop",
                S2_EHZ_BOSS_GRAPH_TEST,
                "com.openggf.game.sonic2.objects.bosses.EHZBossWheel",
                S2_EHZ_BOSS_GRAPH_TEST,
                "com.openggf.game.sonic2.objects.bosses.Sonic2EHZBossInstance",
                S2_EHZ_BOSS_GRAPH_TEST);

        expected.forEach(this::assertGraphCovered);
    }

    @Test
    void smallCoveredParentDependentRowsAreReportedAsGraphCovered() {
        Map<String, String> expected = Map.of(
                "com.openggf.game.sonic2.objects.ARZRotPformsObjectInstance$Obj83SlotChild",
                S2_ARZ_ROT_PFORMS_GRAPH_TEST,
                "com.openggf.game.sonic2.objects.bosses.ARZBossArrow",
                S2_ARZ_ARROW_GRAPH_TEST,
                "com.openggf.game.sonic1.objects.bosses.Sonic1SYZBossInstance",
                S1_SYZ_BOSS_BLOCK_GRAPH_TEST,
                "com.openggf.game.sonic2.objects.SeesawBallObjectInstance",
                SEESAW_GRAPH_TEST);

        expected.forEach(this::assertGraphCovered);
    }

    @Test
    void sonic2CpzBossPrimaryParentDependentRowsAreReportedAsGraphCovered() {
        Map<String, String> expected = Map.of(
                "com.openggf.game.sonic2.objects.bosses.CPZBossContainer",
                S2_CPZ_BOSS_GRAPH_TEST,
                "com.openggf.game.sonic2.objects.bosses.CPZBossFlame",
                S2_CPZ_BOSS_GRAPH_TEST,
                "com.openggf.game.sonic2.objects.bosses.CPZBossPipe",
                S2_CPZ_BOSS_GRAPH_TEST,
                "com.openggf.game.sonic2.objects.bosses.CPZBossPump",
                S2_CPZ_BOSS_GRAPH_TEST,
                "com.openggf.game.sonic2.objects.bosses.CPZBossRobotnik",
                S2_CPZ_BOSS_GRAPH_TEST,
                "com.openggf.game.sonic2.objects.bosses.CPZBossSmokePuff",
                S2_CPZ_BOSS_GRAPH_TEST,
                "com.openggf.game.sonic2.objects.bosses.Sonic2CPZBossInstance",
                S2_CPZ_BOSS_GRAPH_TEST);

        expected.forEach(this::assertGraphCovered);
    }

    @Test
    void sonic2CpzBossSecondaryParentDependentRowsAreReportedAsGraphCovered() {
        Map<String, String> expected = Map.of(
                "com.openggf.game.sonic2.objects.bosses.CPZBossContainerExtend",
                S2_CPZ_BOSS_GRAPH_TEST,
                "com.openggf.game.sonic2.objects.bosses.CPZBossContainerFloor",
                S2_CPZ_BOSS_GRAPH_TEST,
                "com.openggf.game.sonic2.objects.bosses.CPZBossDripper",
                S2_CPZ_BOSS_GRAPH_TEST,
                "com.openggf.game.sonic2.objects.bosses.CPZBossGunk",
                S2_CPZ_BOSS_GRAPH_TEST,
                "com.openggf.game.sonic2.objects.bosses.CPZBossPipePump",
                S2_CPZ_BOSS_GRAPH_TEST,
                "com.openggf.game.sonic2.objects.bosses.CPZBossPipeSegment",
                S2_CPZ_BOSS_GRAPH_TEST);

        expected.forEach(this::assertGraphCovered);
    }

    @Test
    void sonic3kMiscCoveredParentDependentRowsAreReportedAsGraphCovered() {
        Map<String, String> expected = Map.of(
                "com.openggf.game.sonic3k.objects.AizDisappearingFloorObjectInstance$BorderChild",
                AIZ_DISAPPEARING_FLOOR_GRAPH_TEST,
                "com.openggf.game.sonic3k.objects.CutsceneKnucklesLbz1CollapseChild",
                LBZ1_CUTSCENE_GRAPH_TEST,
                "com.openggf.game.sonic3k.objects.CutsceneKnucklesLbz1RangeHelper",
                LBZ1_CUTSCENE_GRAPH_TEST,
                "com.openggf.game.sonic3k.objects.HczMinibossInstance$RocketTouchChild",
                HCZ_MINIBOSS_ROCKET_TOUCH_GRAPH_TEST,
                "com.openggf.game.sonic3k.objects.IczBigSnowPileInstance",
                ICZ_BIG_SNOW_PILE_GRAPH_TEST,
                "com.openggf.game.sonic3k.objects.IczIceSpikesObjectInstance$SpikeHurtChild",
                S3K_NESTED_HURTBOX_GRAPH_TEST,
                "com.openggf.game.sonic3k.objects.Mgz2LevelCollapseSolidInstance",
                MGZ2_COLLAPSE_GRAPH_TEST,
                "com.openggf.game.sonic3k.objects.MgzMinibossInstance$DrillArmChild",
                S3K_NESTED_HURTBOX_GRAPH_TEST);

        expected.forEach(this::assertGraphCovered);
    }

    @Test
    void remainingCoveredParentDependentRowsAreReportedAsGraphCovered() {
        Map<String, String> expected = Map.of(
                "com.openggf.game.sonic3k.objects.GumballMachineObjectInstance$GumballSpringChild",
                S3K_BADNIK_CHILD_GRAPH_TEST,
                "com.openggf.game.sonic3k.objects.IczSegmentColumnObjectInstance$Segment",
                ICZ_SEGMENT_COLUMN_GRAPH_TEST,
                "com.openggf.game.sonic3k.objects.S3kSignpostStubChild",
                S3K_SIGNPOST_STUB_GRAPH_TEST,
                "com.openggf.game.sonic3k.objects.S3kSlotBonusCageObjectInstance",
                S3K_SLOT_BONUS_GRAPH_TEST,
                "com.openggf.game.sonic3k.objects.S3kSlotRingRewardObjectInstance",
                S3K_SLOT_BONUS_GRAPH_TEST,
                "com.openggf.game.sonic3k.objects.S3kSlotSpikeRewardObjectInstance",
                S3K_SLOT_BONUS_GRAPH_TEST,
                "com.openggf.game.sonic3k.objects.Sonic3kInvincibilityStarsObjectInstance",
                S3K_SELF_CONTAINED_TRANSIENT_GRAPH_TEST,
                "com.openggf.game.sonic3k.objects.Sonic3kStarPostBonusStarChild",
                CHECKPOINT_STARPOST_GRAPH_TEST,
                "com.openggf.game.sonic3k.objects.Sonic3kStarPostStarChild",
                CHECKPOINT_STARPOST_GRAPH_TEST,
                "com.openggf.level.objects.InvincibilityStarsObjectInstance",
                S2_SELF_CONTAINED_TRANSIENT_GRAPH_TEST);

        expected.forEach(this::assertGraphCovered);
    }

    @Test
    void noProbeRowsWithExistingGraphTestsAreReportedAsGraphCovered() {
        Map<String, String> expected = Map.of(
                "com.openggf.game.sonic1.objects.bosses.FZPlasmaBall",
                S1_FZ_BOSS_GRAPH_TEST,
                "com.openggf.game.sonic1.objects.bosses.GHZBossWreckingBall",
                S1_GHZ_BOSS_GRAPH_TEST,
                "com.openggf.game.sonic1.objects.bosses.SYZBossSpike",
                "TestBossChildExactStateRewind",
                "com.openggf.game.sonic2.objects.CheckpointDongleInstance",
                CHECKPOINT_STARPOST_GRAPH_TEST,
                "com.openggf.game.sonic2.objects.CheckpointStarInstance",
                CHECKPOINT_STARPOST_GRAPH_TEST,
                "com.openggf.game.sonic2.objects.CogObjectInstance$CogSlotChildInstance",
                S2_COG_GRAPH_TEST,
                "com.openggf.game.sonic2.objects.OOZBurnerFlameObjectInstance",
                S2_OOZ_BURNER_FLAME_GRAPH_TEST,
                "com.openggf.game.sonic2.objects.SwingingPlatformObjectInstance$SwingingPlatformDisplayChild",
                S2_SWINGING_PLATFORM_GRAPH_TEST,
                "com.openggf.game.sonic3k.objects.AizShipBombInstance",
                AIZ_SHIP_BOMB_GRAPH_TEST,
                "com.openggf.game.sonic3k.objects.CutsceneKnuxCnz2WallInstance",
                S3K_CUTSCENE_KNUCKLES_GRAPH_TEST);

        expected.forEach(this::assertGraphCovered);
    }

    @Test
    void mechaSonicNoProbeAndParentDependentChildrenAreReportedAsGraphCovered() {
        Map<String, String> expected = Map.of(
                "com.openggf.game.sonic2.objects.bosses.Sonic2MechaSonicInstance$MechaSonicDEZWindow",
                MECHA_SONIC_GRAPH_TEST,
                "com.openggf.game.sonic2.objects.bosses.Sonic2MechaSonicInstance$MechaSonicLEDWindow",
                MECHA_SONIC_GRAPH_TEST,
                "com.openggf.game.sonic2.objects.bosses.Sonic2MechaSonicInstance$MechaSonicTargetingSensor",
                MECHA_SONIC_GRAPH_TEST,
                "com.openggf.game.sonic2.objects.bosses.Sonic2MechaSonicInstance$MechaSonicSpikeball",
                MECHA_SONIC_GRAPH_TEST);

        expected.forEach(this::assertGraphCovered);
    }

    @Test
    void mtzBossNoProbeAndParentDependentChildrenAreReportedAsGraphCovered() {
        Map<String, String> expected = Map.of(
                "com.openggf.game.sonic2.objects.bosses.Sonic2MTZBossInstance$MTZBossOrb",
                MTZ_BOSS_GRAPH_TEST,
                "com.openggf.game.sonic2.objects.bosses.Sonic2MTZBossInstance$MTZLaserShooter",
                MTZ_BOSS_GRAPH_TEST);

        expected.forEach(this::assertGraphCovered);
    }

    @Test
    void wfzBossNoProbeAndParentDependentChildrenAreReportedAsGraphCovered() {
        Map<String, String> expected = Map.of(
                "com.openggf.game.sonic2.objects.bosses.Sonic2WFZBossInstance$WFZFloatingPlatform",
                WFZ_BOSS_GRAPH_TEST,
                "com.openggf.game.sonic2.objects.bosses.Sonic2WFZBossInstance$WFZLaser",
                WFZ_BOSS_GRAPH_TEST,
                "com.openggf.game.sonic2.objects.bosses.Sonic2WFZBossInstance$WFZLaserShooter",
                WFZ_BOSS_GRAPH_TEST,
                "com.openggf.game.sonic2.objects.bosses.Sonic2WFZBossInstance$WFZLaserWall",
                WFZ_BOSS_GRAPH_TEST,
                "com.openggf.game.sonic2.objects.bosses.Sonic2WFZBossInstance$WFZPlatformHurt",
                WFZ_BOSS_GRAPH_TEST,
                "com.openggf.game.sonic2.objects.bosses.Sonic2WFZBossInstance$WFZPlatformReleaser",
                WFZ_BOSS_GRAPH_TEST,
                "com.openggf.game.sonic2.objects.bosses.Sonic2WFZBossInstance$WFZRobotnik",
                WFZ_BOSS_GRAPH_TEST,
                "com.openggf.game.sonic2.objects.bosses.Sonic2WFZBossInstance$WFZRobotnikPlatform",
                WFZ_BOSS_GRAPH_TEST);

        expected.forEach(this::assertGraphCovered);
    }

    @Test
    void remainingNoProbeRowsWithExactOwnerGraphTestsAreReportedAsGraphCovered() {
        Map<String, String> expected = Map.of(
                "com.openggf.game.sonic3k.objects.MhzMinibossEscapeShardInstance",
                MHZ_MINIBOSS_ESCAPE_SHARD_GRAPH_TEST,
                "com.openggf.game.sonic3k.objects.badniks.TurboSpikerBadnikInstance$TurboSpikerTrailEmitter",
                S3K_BADNIK_CHILD_GRAPH_TEST);

        expected.forEach(this::assertGraphCovered);
    }

    @Test
    void playerBoundShieldNoProbeRowsAreReportedAsGraphCovered() {
        Map<String, String> expected = Map.of(
                "com.openggf.game.sonic3k.objects.BubbleShieldObjectInstance",
                S3K_SELF_CONTAINED_TRANSIENT_GRAPH_TEST,
                "com.openggf.game.sonic3k.objects.FireShieldObjectInstance",
                S3K_SELF_CONTAINED_TRANSIENT_GRAPH_TEST,
                "com.openggf.game.sonic3k.objects.InstaShieldObjectInstance",
                S3K_SELF_CONTAINED_TRANSIENT_GRAPH_TEST,
                "com.openggf.game.sonic3k.objects.LightningShieldObjectInstance",
                S3K_SELF_CONTAINED_TRANSIENT_GRAPH_TEST,
                "com.openggf.level.objects.ShieldObjectInstance",
                SHIELD_PENDING_RESTORE_TEST);

        expected.forEach(this::assertGraphCovered);
    }

    @Test
    void finalParentDependentTailRowsAreReportedAsGraphCovered() {
        Map<String, String> expected = Map.ofEntries(
                Map.entry("com.openggf.game.sonic2.objects.MTZLongPlatformCogInstance",
                        "com.openggf.game.sonic2.objects.TestS2MtzLongPlatformCogGraphRewind"),
                Map.entry("com.openggf.game.sonic3k.objects.badniks.CorkeyBadnikInstance$CorkeyNozzleChild",
                        "com.openggf.game.sonic3k.objects.badniks.TestS3kCorkeyNozzleGraphRewind"),
                Map.entry("com.openggf.game.sonic3k.objects.ClamerObjectInstance$ClamerSpringChild",
                        "TestS3kClamerGraphRewind"),
                Map.entry("com.openggf.game.sonic3k.objects.CutsceneKnucklesMhz2Instance$Mhz2KnucklesLiftChild",
                        "com.openggf.game.sonic3k.objects.TestS3kMhzCutsceneGraphRewind"),
                Map.entry("com.openggf.game.sonic3k.objects.GumballMachineObjectInstance$ContainerDisplayChild",
                        "TestS3kBadnikChildGraphRewind"),
                Map.entry("com.openggf.game.sonic3k.objects.HCZHandLauncherObjectInstance$HandLauncherArmChild",
                        "TestS3kHczHandLauncherGraphRewind"),
                Map.entry("com.openggf.game.sonic3k.objects.HCZWaterRushObjectInstance$WaterRushBlockChild",
                        "TestS3kHczWaterRushGraphRewind"),
                Map.entry("com.openggf.game.sonic3k.objects.IczFreezerObjectInstance$CaptureCloud",
                        "TestS3kIczFreezerGraphRewind"),
                Map.entry("com.openggf.game.sonic3k.objects.LbzCupElevatorInstance$AttachChild",
                        "TestS3kLbzCupElevatorGraphRewind"),
                Map.entry("com.openggf.game.sonic3k.objects.LbzCupElevatorInstance$BaseChild",
                        "TestS3kLbzCupElevatorGraphRewind"),
                Map.entry("com.openggf.game.sonic3k.objects.LbzPlayerLauncherInstance$LauncherArmChild",
                        "TestS3kLbzPlayerLauncherGraphRewind"),
                Map.entry("com.openggf.game.sonic3k.objects.LbzTubeElevatorInstance$OverlayChild",
                        "TestS3kLbzTubeElevatorGraphRewind"),
                Map.entry("com.openggf.game.sonic3k.objects.MgzMinibossInstance$KnucklesSpikePlatformChild",
                        "TestS3kNestedHurtboxGraphRewind"),
                Map.entry("com.openggf.game.sonic3k.objects.MGZPulleyObjectInstance$PulleyChainChild",
                        "TestS3kMgzPulleyGraphRewind"),
                Map.entry("com.openggf.game.sonic3k.objects.Sonic3kMonitorObjectInstance$MonitorContentsSlot",
                        "TestS3kMonitorGraphRewind"));

        expected.forEach(this::assertGraphCovered);
    }

    @Test
    void lbz2EndSequenceRowsAreReportedAsGraphCovered() {
        Map<String, String> expected = Map.ofEntries(
                Map.entry("com.openggf.game.sonic3k.objects.CutsceneKnucklesLbz2Instance",
                        LBZ2_RIDE_CAMEO_TEST),
                Map.entry("com.openggf.game.sonic3k.objects.CutsceneKnucklesLbz2Instance$SwingChild",
                        LBZ2_RIDE_CAMEO_TEST),
                Map.entry("com.openggf.game.sonic3k.objects.Lbz2RobotnikShipInstance$ExhaustFlameChild",
                        LBZ2_RIDE_CAMEO_TEST),
                Map.entry("com.openggf.game.sonic3k.objects.LbzGateLaserBeamInstance",
                        LBZ_GATE_LASER_TEST),
                Map.entry("com.openggf.game.sonic3k.objects.LbzPipePlugShardInstance",
                        LBZ_PIPE_PLUG_TEST),
                Map.entry("com.openggf.game.sonic3k.objects.TunnelExhaustControlObjectInstance",
                        AUTOMATIC_TUNNEL_TEST),
                Map.entry("com.openggf.game.sonic3k.objects.bosses.LbzEndBossInstance",
                        LBZ_END_BOSS_TEST),
                Map.entry("com.openggf.game.sonic3k.objects.bosses.LbzEndBossInstance$LbzEndBossCockpitChild",
                        LBZ_END_BOSS_TEST),
                Map.entry("com.openggf.game.sonic3k.objects.bosses.LbzEndBossInstance$LbzEndBossDebrisChild",
                        LBZ_END_BOSS_TEST),
                Map.entry("com.openggf.game.sonic3k.objects.bosses.LbzEndBossInstance$LbzEndBossExplosionControllerChild",
                        LBZ_END_BOSS_TEST),
                Map.entry("com.openggf.game.sonic3k.objects.bosses.LbzEndBossInstance$LbzEndBossGradualMaxXExtenderChild",
                        LBZ_END_BOSS_TEST),
                Map.entry("com.openggf.game.sonic3k.objects.bosses.LbzEndBossInstance$LbzEndBossPlatformChild",
                        LBZ_END_BOSS_TEST),
                Map.entry("com.openggf.game.sonic3k.objects.bosses.LbzEndBossInstance$LbzEndBossRunnerChild",
                        LBZ_END_BOSS_TEST),
                Map.entry("com.openggf.game.sonic3k.objects.bosses.LbzEndBossInstance$LbzEndBossSmokePuffChild",
                        LBZ_END_BOSS_TEST),
                Map.entry("com.openggf.game.sonic3k.objects.bosses.LbzEndBossInstance$LbzEndBossSpikeBallChild",
                        LBZ_END_BOSS_TEST),
                Map.entry("com.openggf.game.sonic3k.objects.bosses.LbzEndBossInstance$LbzEndBossTowerChild",
                        LBZ_END_BOSS_TEST),
                Map.entry("com.openggf.game.sonic3k.objects.bosses.LbzEndBossInstance$LbzEndBossTubeSegmentChild",
                        LBZ_END_BOSS_TEST),
                Map.entry("com.openggf.game.sonic3k.objects.bosses.LbzFinalBoss1Instance$DeathEggExplosionDebrisChild",
                        LBZ_FINAL_BOSS_1_TEST),
                Map.entry("com.openggf.game.sonic3k.objects.bosses.LbzFinalBoss1Instance$DeathEggMiniatureChild",
                        LBZ_FINAL_BOSS_1_TEST),
                Map.entry("com.openggf.game.sonic3k.objects.bosses.LbzFinalBoss1Instance$DeathEggSmokeChild",
                        LBZ_FINAL_BOSS_1_TEST),
                Map.entry("com.openggf.game.sonic3k.objects.bosses.LbzFinalBoss1Instance$DeathEggSmokePuffChild",
                        LBZ_FINAL_BOSS_1_TEST),
                Map.entry("com.openggf.game.sonic3k.objects.bosses.LbzFinalBoss1Instance$DebrisChild",
                        LBZ_FINAL_BOSS_1_TEST),
                Map.entry("com.openggf.game.sonic3k.objects.bosses.LbzFinalBoss1Instance$EngineFlameChild",
                        LBZ_FINAL_BOSS_1_TEST),
                Map.entry("com.openggf.game.sonic3k.objects.bosses.LbzFinalBoss1Instance$ExplosionSequencerChild",
                        LBZ_FINAL_BOSS_1_TEST),
                Map.entry("com.openggf.game.sonic3k.objects.bosses.LbzFinalBoss1Instance$ExplosionShowerChild",
                        LBZ_FINAL_BOSS_1_TEST),
                Map.entry("com.openggf.game.sonic3k.objects.bosses.LbzFinalBoss1Instance$GunPodChild",
                        LBZ_FINAL_BOSS_1_TEST),
                Map.entry("com.openggf.game.sonic3k.objects.bosses.LbzFinalBoss1Instance$HitSparkChild",
                        LBZ_FINAL_BOSS_1_TEST),
                Map.entry("com.openggf.game.sonic3k.objects.bosses.LbzFinalBoss1Instance$LaserHeadChild",
                        LBZ_FINAL_BOSS_1_TEST),
                Map.entry("com.openggf.game.sonic3k.objects.bosses.LbzFinalBoss1Instance$LaserTrailChild",
                        LBZ_FINAL_BOSS_1_TEST),
                Map.entry("com.openggf.game.sonic3k.objects.bosses.LbzFinalBoss1Instance$MuzzleLaserChild",
                        LBZ_FINAL_BOSS_1_TEST),
                Map.entry("com.openggf.game.sonic3k.objects.bosses.LbzFinalBoss1Instance$OrbitingPodChild",
                        LBZ_FINAL_BOSS_1_TEST),
                Map.entry("com.openggf.game.sonic3k.objects.bosses.LbzFinalBoss1Instance$P2EndingPoseWatcherChild",
                        LBZ_FINAL_BOSS_1_TEST),
                Map.entry("com.openggf.game.sonic3k.objects.bosses.LbzFinalBoss1Instance$RobotnikHeadChild",
                        LBZ_FINAL_BOSS_1_TEST),
                Map.entry("com.openggf.game.sonic3k.objects.bosses.LbzFinalBoss1Instance$TailsCpuReleaseChild",
                        LBZ_FINAL_BOSS_1_TEST),
                Map.entry("com.openggf.game.sonic3k.objects.bosses.LbzFinalBoss1Instance$TopAttachmentChild",
                        LBZ_FINAL_BOSS_1_TEST),
                Map.entry("com.openggf.game.sonic3k.objects.bosses.LbzFinalBoss1Instance$TurretSegmentChild",
                        LBZ_FINAL_BOSS_1_TEST));

        expected.forEach(this::assertGraphCovered);
    }

    private void assertGraphCovered(String className, String evidence) {
        RoundTripSweepResult result = RewindRoundTripHarness.probeClass(className);
        RoundTripSweepResult.GraphCovered covered =
                assertInstanceOf(RoundTripSweepResult.GraphCovered.class, result);
        assertEquals(evidence, covered.evidence());
    }
}
