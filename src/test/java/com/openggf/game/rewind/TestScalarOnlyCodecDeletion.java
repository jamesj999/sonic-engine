package com.openggf.game.rewind;

import com.openggf.camera.Camera;
import com.openggf.configuration.SonicConfigurationService;
import com.openggf.game.GameId;
import com.openggf.game.ObjectArtProvider;
import com.openggf.game.PlayerCharacter;
import com.openggf.game.rewind.RewindRoundTripHarness.RoundTripSweepResult;
import com.openggf.game.rewind.identity.ObjectRefId;
import com.openggf.game.rewind.identity.PlayerRefId;
import com.openggf.game.rewind.identity.RewindIdentityTable;
import com.openggf.game.rewind.schema.RewindCaptureContext;
import com.openggf.tests.TestSessionOutputPaths;
import com.openggf.game.rewind.snapshot.ObjectManagerSnapshot;
import com.openggf.game.sonic1.objects.Sonic1EggPrisonButtonObjectInstance;
import com.openggf.game.sonic1.objects.Sonic1EggPrisonObjectInstance;
import com.openggf.game.sonic1.objects.Sonic1EndingSonicObjectInstance;
import com.openggf.game.sonic1.objects.Sonic1GlassBlockObjectInstance;
import com.openggf.game.sonic1.objects.Sonic1GlassReflectionInstance;
import com.openggf.game.sonic1.objects.Sonic1GrassFireObjectInstance;
import com.openggf.game.sonic1.objects.Sonic1JunctionObjectInstance;
import com.openggf.game.sonic1.objects.Sonic1LamppostTwirlInstance;
import com.openggf.game.sonic1.objects.Sonic1LargeGrassyPlatformObjectInstance;
import com.openggf.game.sonic1.objects.Sonic1LavaGeyserMakerObjectInstance;
import com.openggf.game.sonic1.objects.Sonic1LavaGeyserObjectInstance;
import com.openggf.game.sonic1.objects.Sonic1LavaWallObjectInstance;
import com.openggf.game.sonic1.objects.Sonic1ObjectRegistry;
import com.openggf.game.sonic1.objects.Sonic1PointsObjectInstance;
import com.openggf.game.sonic1.objects.Sonic1RingFlashObjectInstance;
import com.openggf.game.sonic1.objects.badniks.Sonic1BombFuseInstance;
import com.openggf.game.sonic1.objects.badniks.Sonic1CaterkillerBadnikInstance;
import com.openggf.game.sonic1.objects.badniks.Sonic1CaterkillerBodyInstance;
import com.openggf.game.sonic1.objects.badniks.Sonic1OrbinautBadnikInstance;
import com.openggf.game.sonic1.objects.bosses.FZCylinder;
import com.openggf.game.sonic1.objects.bosses.FZPlasmaBall;
import com.openggf.game.sonic1.objects.bosses.FZPlasmaLauncher;
import com.openggf.game.sonic1.objects.bosses.GHZBossWreckingBall;
import com.openggf.game.sonic1.objects.bosses.Sonic1BossBlockInstance;
import com.openggf.game.sonic1.objects.bosses.Sonic1FZBossInstance;
import com.openggf.game.sonic1.objects.bosses.Sonic1GHZBossInstance;
import com.openggf.game.sonic1.objects.bosses.Sonic1SLZBossInstance;
import com.openggf.game.sonic1.objects.bosses.Sonic1SLZBossSpikeball;
import com.openggf.game.sonic1.objects.bosses.Sonic1ScrapEggmanInstance;
import com.openggf.game.sonic1.objects.bosses.Sonic1SYZBossInstance;
import com.openggf.game.sonic2.objects.BombPrizeObjectInstance;
import com.openggf.game.sonic2.objects.CheckpointDongleInstance;
import com.openggf.game.sonic2.objects.CheckpointStarInstance;
import com.openggf.game.sonic2.constants.Sonic2ObjectIds;
import com.openggf.game.sonic2.objects.ConveyorObjectInstance;
import com.openggf.game.sonic2.objects.EggPrisonButtonObjectInstance;
import com.openggf.game.sonic2.objects.EggPrisonObjectInstance;
import com.openggf.game.sonic2.objects.OOZBurnerFlameObjectInstance;
import com.openggf.game.sonic2.objects.OOZPoppingPlatformObjectInstance;
import com.openggf.game.sonic2.objects.PointsObjectInstance;
import com.openggf.game.sonic2.objects.Sonic2ObjectRegistry;
import com.openggf.game.sonic2.objects.bosses.ARZBossArrow;
import com.openggf.game.sonic2.objects.bosses.ARZBossEyes;
import com.openggf.game.sonic2.objects.bosses.ARZBossPillar;
import com.openggf.game.sonic2.objects.bosses.CNZBossElectricBall;
import com.openggf.game.sonic2.objects.bosses.CPZBossContainer;
import com.openggf.game.sonic2.objects.bosses.CPZBossContainerExtend;
import com.openggf.game.sonic2.objects.bosses.CPZBossContainerFloor;
import com.openggf.game.sonic2.objects.bosses.CPZBossDripper;
import com.openggf.game.sonic2.objects.bosses.CPZBossFlame;
import com.openggf.game.sonic2.objects.bosses.CPZBossGunk;
import com.openggf.game.sonic2.objects.bosses.CPZBossPipe;
import com.openggf.game.sonic2.objects.bosses.CPZBossPipePump;
import com.openggf.game.sonic2.objects.bosses.CPZBossPipeSegment;
import com.openggf.game.sonic2.objects.bosses.CPZBossPump;
import com.openggf.game.sonic2.objects.bosses.CPZBossRobotnik;
import com.openggf.game.sonic2.objects.bosses.HTZBossFlamethrower;
import com.openggf.game.sonic2.objects.bosses.HTZBossLavaBall;
import com.openggf.game.sonic2.objects.bosses.Sonic2ARZBossInstance;
import com.openggf.game.sonic2.objects.bosses.Sonic2CNZBossInstance;
import com.openggf.game.sonic2.objects.bosses.Sonic2CPZBossInstance;
import com.openggf.game.sonic2.objects.bosses.Sonic2EHZBossInstance;
import com.openggf.game.sonic2.objects.bosses.Sonic2HTZBossInstance;
import com.openggf.game.sonic2.objects.bosses.Sonic2MCZBossInstance;
import com.openggf.game.sonic2.objects.bosses.Sonic2WFZBossInstance;
import com.openggf.game.sonic2.objects.GrounderRockProjectile;
import com.openggf.game.sonic2.objects.GrounderWallInstance;
import com.openggf.game.sonic2.objects.NutObjectInstance;
import com.openggf.game.sonic2.objects.badniks.BalkiryJetObjectInstance;
import com.openggf.game.sonic2.objects.badniks.RexonHeadObjectInstance;
import com.openggf.game.sonic2.objects.badniks.ShellcrackerClawInstance;
import com.openggf.game.sonic2.objects.badniks.SlicerPincerInstance;
import com.openggf.game.sonic2.objects.badniks.SolFireballObjectInstance;
import com.openggf.game.sonic3k.objects.AizEndBossArmChild;
import com.openggf.game.sonic3k.objects.AizEndBossBombChild;
import com.openggf.game.sonic3k.objects.AizEndBossFlameChild;
import com.openggf.game.sonic3k.objects.AizEndBossFlameColumnChild;
import com.openggf.game.sonic3k.objects.AizEndBossInstance;
import com.openggf.game.sonic3k.objects.AizEndBossPropellerChild;
import com.openggf.game.sonic3k.objects.AizEndBossShipChild;
import com.openggf.game.sonic3k.objects.AizEndBossSmokeChild;
import com.openggf.game.sonic3k.objects.AizCollapsingLogBridgeObjectInstance;
import com.openggf.game.sonic3k.objects.AizDisappearingFloorObjectInstance;
import com.openggf.game.sonic3k.objects.AizEmeraldScatterInstance;
import com.openggf.game.sonic3k.objects.AizFallingLogObjectInstance;
import com.openggf.game.sonic3k.objects.AizFlippingBridgeObjectInstance;
import com.openggf.game.sonic3k.objects.Aiz1TreeObjectInstance;
import com.openggf.game.sonic3k.objects.Aiz1ZiplinePegObjectInstance;
import com.openggf.game.sonic3k.objects.AizForegroundPlantInstance;
import com.openggf.game.sonic3k.objects.AizIntroEmeraldGlowChild;
import com.openggf.game.sonic3k.objects.AizIntroPlaneChild;
import com.openggf.game.sonic3k.objects.AizIntroWaveChild;
import com.openggf.game.sonic3k.constants.Sonic3kObjectIds;
import com.openggf.game.sonic3k.objects.AizMinibossArmChild;
import com.openggf.game.sonic3k.objects.AizMinibossBarrelShotChild;
import com.openggf.game.sonic3k.objects.AizMinibossBarrelShotFlareChild;
import com.openggf.game.sonic3k.objects.AizMinibossBodyChild;
import com.openggf.game.sonic3k.objects.AizMinibossFlameBarrelChild;
import com.openggf.game.sonic3k.objects.AizMinibossFlameChild;
import com.openggf.game.sonic3k.objects.AizMinibossInstance;
import com.openggf.game.sonic3k.objects.AizPlaneIntroInstance;
import com.openggf.game.sonic3k.objects.AizShipBombInstance;
import com.openggf.game.sonic3k.objects.AizSpikedLogObjectInstance;
import com.openggf.game.sonic3k.objects.AnimatedStillSpriteInstance;
import com.openggf.game.sonic3k.objects.AutoSpinObjectInstance;
import com.openggf.game.sonic3k.objects.AutomaticTunnelObjectInstance;
import com.openggf.game.sonic3k.objects.BubblerObjectInstance;
import com.openggf.game.sonic3k.objects.Cnz2CutsceneButtonInstance;
import com.openggf.game.sonic3k.objects.CnzBalloonInstance;
import com.openggf.game.sonic3k.objects.CnzBarberPoleObjectInstance;
import com.openggf.game.sonic3k.objects.CnzWaterLevelCorkFloorInstance;
import com.openggf.game.sonic3k.objects.CorkFloorObjectInstance;
import com.openggf.game.sonic3k.objects.CnzGiantWheelInstance;
import com.openggf.game.sonic3k.objects.CnzHoverFanInstance;
import com.openggf.game.sonic3k.objects.CnzLightBulbInstance;
import com.openggf.game.sonic3k.objects.CnzMinibossCoilInstance;
import com.openggf.game.sonic3k.objects.CnzMinibossInstance;
import com.openggf.game.sonic3k.objects.CnzMinibossSparkInstance;
import com.openggf.game.sonic3k.objects.CnzMinibossTopInstance;
import com.openggf.game.sonic3k.objects.HczMinibossInstance;
import com.openggf.game.sonic3k.objects.IczMinibossInstance;
import com.openggf.game.sonic3k.objects.CnzRisingPlatformInstance;
import com.openggf.game.sonic3k.objects.CnzSpiralTubeInstance;
import com.openggf.game.sonic3k.objects.CnzTeleporterBeamInstance;
import com.openggf.game.sonic3k.objects.CnzTeleporterInstance;
import com.openggf.game.sonic3k.objects.CnzTrapDoorInstance;
import com.openggf.game.sonic3k.objects.CnzTriangleBumperObjectInstance;
import com.openggf.game.sonic3k.objects.CnzVacuumTubeInstance;
import com.openggf.game.sonic3k.objects.CnzWaterLevelButtonInstance;
import com.openggf.game.sonic3k.objects.DoorObjectInstance;
import com.openggf.game.sonic3k.objects.FloatingPlatformObjectInstance;
import com.openggf.game.sonic3k.objects.GumballTriangleBumperObjectInstance;
import com.openggf.game.sonic3k.objects.HCZBlockObjectInstance;
import com.openggf.game.sonic3k.objects.HCZConveyorSpikeObjectInstance;
import com.openggf.game.sonic3k.objects.HCZLargeFanObjectInstance;
import com.openggf.game.sonic3k.objects.HCZSpinningColumnObjectInstance;
import com.openggf.game.sonic3k.objects.HCZTwistingLoopObjectInstance;
import com.openggf.game.sonic3k.objects.HCZWaterDropObjectInstance;
import com.openggf.game.sonic3k.objects.HCZWaterSplashObjectInstance;
import com.openggf.game.sonic3k.objects.IczBreakableWallObjectInstance;
import com.openggf.game.sonic3k.objects.IczHarmfulIceObjectInstance;
import com.openggf.game.sonic3k.objects.IczIceBlockObjectInstance;
import com.openggf.game.sonic3k.objects.IczIceCubeObjectInstance;
import com.openggf.game.sonic3k.objects.IczPathFollowPlatformObjectInstance;
import com.openggf.game.sonic3k.objects.IczSnowPileObjectInstance;
import com.openggf.game.sonic3k.objects.IczStalagtiteObjectInstance;
import com.openggf.game.sonic3k.objects.IczSwingingPlatformObjectInstance;
import com.openggf.game.sonic3k.objects.MGZTwistingLoopObjectInstance;
import com.openggf.game.sonic3k.objects.LbzAlarmObjectInstance;
import com.openggf.game.sonic3k.objects.LbzInvisibleBarrierInstance;
import com.openggf.game.sonic3k.objects.Mgz2ResultsScreenObjectInstance;
import com.openggf.game.sonic3k.objects.Mgz2PostBossPaletteFadeController;
import com.openggf.game.sonic3k.objects.Mgz2PostBossSequenceController;
import com.openggf.game.sonic3k.objects.MgzMinibossInstance;
import com.openggf.game.sonic3k.objects.MhzMinibossInstance;
import com.openggf.game.sonic3k.objects.MhzTwistedVineObjectInstance;
import com.openggf.game.sonic3k.objects.MhzPollenSpawnerInstance;
import com.openggf.game.sonic3k.objects.PachinkoBumperObjectInstance;
import com.openggf.game.sonic3k.objects.PachinkoMagnetOrbObjectInstance;
import com.openggf.game.sonic3k.objects.PachinkoPlatformObjectInstance;
import com.openggf.game.sonic3k.objects.PachinkoTriangleBumperObjectInstance;
import com.openggf.game.sonic3k.objects.S3kSignpostStubChild;
import com.openggf.game.sonic3k.objects.S3kResultsScreenObjectInstance;
import com.openggf.game.sonic3k.objects.S3kHiddenMonitorInstance;
import com.openggf.game.sonic3k.objects.Sonic3kButtonObjectInstance;
import com.openggf.game.sonic3k.objects.Sonic3kObjectRegistry;
import com.openggf.game.sonic3k.constants.Sonic3kConstants;
import com.openggf.game.sonic3k.objects.Sonic3kPathSwapObjectInstance;
import com.openggf.game.sonic3k.objects.Sonic3kPointsObjectInstance;
import com.openggf.game.sonic3k.objects.Sonic3kSSEntryRingObjectInstance;
import com.openggf.game.sonic3k.objects.Sonic3kSpikeObjectInstance;
import com.openggf.game.sonic3k.objects.Sonic3kSSEntryFlashObjectInstance;
import com.openggf.game.sonic3k.objects.Sonic3kStarPostBonusStarChild;
import com.openggf.game.sonic3k.objects.Sonic3kStarPostStarChild;
import com.openggf.game.sonic3k.objects.Sonic3kTwistedRampObjectInstance;
import com.openggf.game.sonic3k.objects.SinkingMudObjectInstance;
import com.openggf.game.sonic3k.objects.StillSpriteInstance;
import com.openggf.game.sonic3k.objects.UpdraftObjectInstance;
import com.openggf.game.sonic3k.objects.badniks.BuggernautBabyInstance;
import com.openggf.game.sonic3k.objects.badniks.BuggernautBadnikInstance;
import com.openggf.game.sonic3k.objects.bosses.HczEndBossBlade;
import com.openggf.game.sonic3k.objects.bosses.HczEndBossBladeSplash;
import com.openggf.game.sonic3k.objects.bosses.HczEndBossBladeWaterChute;
import com.openggf.game.sonic3k.objects.bosses.HczEndBossRobotnikShip;
import com.openggf.game.sonic3k.objects.bosses.HczEndBossTurbine;
import com.openggf.game.sonic3k.objects.bosses.HczEndBossWaterColumn;
import com.openggf.game.sonic3k.objects.bosses.MhzEndBossDefeatFragmentChild;
import com.openggf.game.sonic3k.objects.bosses.MhzEndBossInstance;
import com.openggf.game.sonic3k.objects.bosses.MhzEndBossRobotnikShipFlameInstance;
import com.openggf.graphics.GraphicsManager;
import com.openggf.level.Pattern;
import com.openggf.data.RomByteReader;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.AnimalObjectInstance;
import com.openggf.level.objects.DynamicObjectRecreateContext;
import com.openggf.level.objects.ExplosionObjectInstance;
import com.openggf.level.objects.ObjectConstructionContext;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.ObjectRenderManager;
import com.openggf.level.objects.ObjectRewindDynamicCodecs;
import com.openggf.level.objects.ObjectRegistry;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.ObjectSpriteSheet;
import com.openggf.level.objects.PerObjectRewindSnapshot;
import com.openggf.level.objects.PlaceholderObjectInstance;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.objects.ResultsHardwareTimingFixture;
import com.openggf.level.objects.SignpostSparkleObjectInstance;
import com.openggf.level.objects.SkidDustObjectInstance;
import com.openggf.level.objects.StubObjectServices;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.sprites.animation.SpriteAnimationSet;
import com.openggf.tests.TestablePlayableSprite;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Modifier;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** RED-GREEN test for Phase-2 codec-deletion batch. */
public class TestScalarOnlyCodecDeletion {

    private static final String HTZ_GROUND_FIRE_FQN =
            "com.openggf.game.sonic2.objects.HtzGroundFireObjectInstance";
    private static final String AIZ_BG_TREE_SPAWNER_FQN =
            "com.openggf.game.sonic3k.objects.AizBgTreeSpawnerInstance";
    private static final String AIZ_MINIBOSS_NAPALM_FQN =
            "com.openggf.game.sonic3k.objects.AizMinibossNapalmProjectile";
    /**
     * Batch-2 scalar-only classes whose dynamic rewind codec was deleted in favour
     * of the {@link RewindRecreatable} {@code genericRecreate} Path 1. Each must:
     * (a) implement {@link RewindRecreatable}, (b) NOT have a registered dynamic codec,
     * and (c) still round-trip {@code Passed} through the real ObjectManager harness.
     *
     * <p>Every class here is scalar-only (no {@code AbstractObjectInstance}/
     * {@code ObjectInstance}-typed instance fields, dodging the {@code required=true}
     * resolve-throw invariant) and was harness-PASSED via its codec before deletion.
     */
    private static final List<String> BATCH2_DELETED_CODEC_FQNS = List.of(
            "com.openggf.game.sonic3k.objects.AizBossSmallInstance",
            "com.openggf.game.sonic3k.objects.CnzMinibossScrollControlInstance",
            "com.openggf.game.sonic3k.objects.HCZConveyorBeltObjectInstance",
            "com.openggf.game.sonic3k.objects.MhzPulleyLiftObjectInstance",
            "com.openggf.game.sonic3k.objects.MhzSwingVineObjectInstance",
            "com.openggf.game.sonic3k.objects.CnzCannonInstance",
            "com.openggf.game.sonic3k.objects.CnzCylinderInstance",
            "com.openggf.game.sonic3k.objects.CnzBumperObjectInstance",
            "com.openggf.game.sonic3k.objects.PachinkoFlipperObjectInstance",
            "com.openggf.game.sonic3k.objects.PachinkoEnergyTrapObjectInstance");

    private record CodecDeletionCandidate(String fqn, GameId gameId) {}
    private record MutableFieldCoverageCandidate(String fqn, String... fieldNames) {}

    private static final List<CodecDeletionCandidate> BATCH3_DELETED_CODECS = List.of(
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic2.objects.SmallMetalPformObjectInstance$SmallMetalPformChildInstance",
                    GameId.S2),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic3k.objects.CnzLightsFlashChildInstance",
                    GameId.S3K),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic3k.objects.HCZWaterDropObjectInstance$WaterDropChild",
                    GameId.S3K));

    private static final List<CodecDeletionCandidate> AUDITED_LIVE_REFERENCE_DELETED_CODECS = List.of(
            new CodecDeletionCandidate(Sonic1EggPrisonObjectInstance.class.getName(), GameId.S1),
            new CodecDeletionCandidate(Sonic1BossBlockInstance.class.getName(), GameId.S1));

    private static final List<CodecDeletionCandidate> BATCH4_DELETED_CODECS = List.of(
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic3k.objects.CutsceneKnucklesAiz1Instance",
                    GameId.S3K),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic3k.objects.bosses.MhzEndBossInstance",
                    GameId.S3K));

    private static final List<CodecDeletionCandidate> BATCH5_DELETED_CODECS = List.of(
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic1.objects.Sonic1TeleporterObjectInstance",
                    GameId.S1),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic3k.objects.GumballMachineObjectInstance$ExitTriggerChild",
                    GameId.S3K),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic3k.objects.MgzEndBossInstance",
                    GameId.S3K));

    private static final List<CodecDeletionCandidate> BATCH6_DELETED_CODECS = List.of(
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic3k.objects.MgzDrillingRobotnikInstance",
                    GameId.S3K));

    private static final List<CodecDeletionCandidate> BATCH7_DELETED_CODECS = List.of(
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic3k.objects.bosses.HczEndBossInstance",
                    GameId.S3K));

    private static final List<CodecDeletionCandidate> BATCH8_DELETED_CODECS = List.of(
            new CodecDeletionCandidate(Sonic1PointsObjectInstance.class.getName(), GameId.S1),
            new CodecDeletionCandidate(PointsObjectInstance.class.getName(), GameId.S2),
            new CodecDeletionCandidate(Sonic3kPointsObjectInstance.class.getName(), GameId.S3K));

    private static final List<CodecDeletionCandidate> BATCH9_DELETED_CODECS = List.of(
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic2.objects.badniks.GrounderBadnikInstance",
                    GameId.S2));

    private static final List<CodecDeletionCandidate> BATCH10_DELETED_CODECS = List.of(
            new CodecDeletionCandidate(ConveyorObjectInstance.class.getName(), GameId.S2));

    private static final List<MutableFieldCoverageCandidate> BATCH10_MUTABLE_FIELDS =
            List.of(new MutableFieldCoverageCandidate(
                    ConveyorObjectInstance.class.getName(),
                    "baseX", "baseY", "xFlip"));

    private static final List<CodecDeletionCandidate> BATCH11_DELETED_CODECS = List.of(
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic3k.objects.AizBombExplosionInstance",
                    GameId.S3K),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic3k.objects.AizEndBossDebrisChild",
                    GameId.S3K),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic3k.objects.AizMinibossImpactFlameChild",
                    GameId.S3K),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic3k.objects.AizMinibossDebrisChild",
                    GameId.S3K));

    private static final List<CodecDeletionCandidate> BATCH12_DELETED_CODECS = List.of(
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic3k.objects.AizRockFragmentChild",
                    GameId.S3K),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic3k.objects.CnzMinibossDebrisChild",
                    GameId.S3K),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic3k.objects.S3kBossExplosionChild",
                    GameId.S3K),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic3k.objects.MhzPollenParticleInstance",
                    GameId.S3K),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic3k.objects.LightningSparkObjectInstance",
                    GameId.S3K),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic3k.objects.RockDebrisChild",
                    GameId.S3K));

    private static final List<CodecDeletionCandidate> BATCH13_DELETED_CODECS = List.of(
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic3k.objects.MGZHeadTriggerProjectileInstance",
                    GameId.S3K),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic3k.objects.SongFadeTransitionInstance",
                    GameId.S3K),
            new CodecDeletionCandidate(
                    "com.openggf.level.objects.EggPrisonAnimalInstance",
                    GameId.S3K));

    private static final List<CodecDeletionCandidate> BATCH14_DELETED_CODECS = List.of(
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic3k.objects.S3kSignpostSparkleChild",
                    GameId.S3K),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic3k.objects.S3kAirCountdownObjectInstance",
                    GameId.S3K),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic3k.objects.badniks.CaterkillerJrBodyInstance",
                    GameId.S3K));

    private static final List<CodecDeletionCandidate> BATCH15_DELETED_CODECS = List.of(
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic3k.objects.AizBgTreeInstance",
                    GameId.S3K),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic3k.objects.Aiz2BossEndSequenceController",
                    GameId.S3K),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic3k.objects.CutsceneKnucklesLbz1ThrownBomb",
                    GameId.S3K),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic3k.objects.badniks.S3kBadnikProjectileInstance",
                    GameId.S3K));

    private static final List<CodecDeletionCandidate> BATCH16_DELETED_CODECS = List.of(
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic3k.objects.MhzShipSequenceControllerInstance",
                    GameId.S3K),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic3k.objects.S3kBossDefeatSignpostFlow",
                    GameId.S3K));

    private static final List<CodecDeletionCandidate> BATCH17_DELETED_CODECS = List.of(
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic3k.objects.Aiz2EndEggCapsuleInstance",
                    GameId.S3K),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic3k.objects.bosses.HczEndBossEggCapsuleInstance",
                    GameId.S3K),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic3k.objects.bosses.IczEndBossEggCapsuleInstance",
                    GameId.S3K));

    private static final List<CodecDeletionCandidate> BATCH18_DELETED_CODECS = List.of(
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic3k.objects.bosses.MhzEndBossEggCapsuleInstance",
                    GameId.S3K),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic3k.objects.Mgz2EndEggCapsuleInstance",
                    GameId.S3K));

    private static final List<CodecDeletionCandidate> BATCH19_DELETED_CODECS = List.of(
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic3k.objects.badniks.BlastoidBadnikInstance$BlastoidProjectile",
                    GameId.S3K),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic3k.objects.badniks.SnaleBlasterBadnikInstance$SnaleBlasterProjectile",
                    GameId.S3K),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic3k.objects.badniks.SpikerBadnikInstance$SpikerSpikeProjectile",
                    GameId.S3K));

    private static final List<CodecDeletionCandidate> BATCH20_DELETED_CODECS = List.of(
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic3k.objects.S3kSignpostInstance",
                    GameId.S3K),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic3k.objects.bosses.HczEndBossGeyserCutscene",
                    GameId.S3K),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic3k.objects.Mgz2CapsuleAnimalInstance",
                    GameId.S3K));

    private static final List<CodecDeletionCandidate> BATCH21_DELETED_CODECS = List.of(
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic3k.objects.AizHollowTreeObjectInstance$AizTreeRevealControlObjectInstance",
                    GameId.S3K),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic3k.objects.MGZHeadTriggerObjectInstance$HeadTriggerStoneChipChild",
                    GameId.S3K),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic3k.objects.MgzMinibossInstance$CeilingSpireChild",
                    GameId.S3K));

    private static final List<CodecDeletionCandidate> BATCH22_DELETED_CODECS = List.of(
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic2.objects.HtzFireProjectileObjectInstance",
                    GameId.S2),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic2.objects.ArrowProjectileInstance",
                    GameId.S2),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic2.objects.SteamPuffObjectInstance",
                    GameId.S2),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic2.objects.LeafParticleObjectInstance",
                    GameId.S2));

    private static final List<CodecDeletionCandidate> BATCH23_DELETED_CODECS = List.of(
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic2.objects.badniks.SpikerDrillObjectInstance",
                    GameId.S2),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic2.objects.WallTurretShotInstance",
                    GameId.S2),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic2.objects.VerticalLaserObjectInstance",
                    GameId.S2));

    private static final List<CodecDeletionCandidate> BATCH24_DELETED_CODECS = List.of(
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic2.objects.bosses.LavaBubbleObjectInstance",
                    GameId.S2),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic2.objects.bosses.MCZFallingDebrisInstance",
                    GameId.S2));

    private static final List<CodecDeletionCandidate> BATCH25_DELETED_CODECS = List.of(
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic2.objects.BubbleObjectInstance",
                    GameId.S2),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic2.objects.DestroyedEggPrisonObjectInstance",
                    GameId.S2));

    private static final List<MutableFieldCoverageCandidate> BATCH25_MUTABLE_FIELDS =
            List.of(new MutableFieldCoverageCandidate(
                    "com.openggf.game.sonic2.objects.DestroyedEggPrisonObjectInstance",
                    "positionX", "positionY"));

    private static final List<CodecDeletionCandidate> BATCH26_DELETED_CODECS = List.of(
            new CodecDeletionCandidate(
                    "com.openggf.level.objects.boss.BossExplosionObjectInstance",
                    GameId.S2));

    private static final List<CodecDeletionCandidate> BATCH27_DELETED_CODECS = List.of(
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic2.objects.badniks.BadnikProjectileInstance",
                    GameId.S2));

    private static final List<CodecDeletionCandidate> BATCH28_DELETED_CODECS = List.of(
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic2.objects.bosses.CPZBossFallingPart",
                    GameId.S2));

    private static final List<CodecDeletionCandidate> BATCH29_DELETED_CODECS = List.of(
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic2.objects.SpikyBlockSpikeInstance",
                    GameId.S2));

    private static final List<CodecDeletionCandidate> BATCH30_DELETED_CODECS = List.of(
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic2.objects.MonitorContentsObjectInstance",
                    GameId.S2));

    private static final List<MutableFieldCoverageCandidate> BATCH33_MUTABLE_FIELDS =
            List.of(
                    new MutableFieldCoverageCandidate(
                            "com.openggf.game.sonic2.objects.MTZSpinTubeObjectInstance",
                            "xFlipped"),
                    new MutableFieldCoverageCandidate(
                            "com.openggf.game.sonic2.objects.MonitorContentsObjectInstance",
                            "subtype"),
                    new MutableFieldCoverageCandidate(
                            "com.openggf.game.sonic2.objects.SteamPuffObjectInstance",
                            "xFlipped"),
                    new MutableFieldCoverageCandidate(
                            "com.openggf.game.sonic2.objects.VerticalLaserObjectInstance",
                            "posX", "posY"),
                    new MutableFieldCoverageCandidate(
                            "com.openggf.game.sonic2.objects.badniks.BadnikProjectileInstance",
                            "type"),
                    new MutableFieldCoverageCandidate(
                            "com.openggf.game.sonic2.objects.badniks.SpikerDrillObjectInstance",
                            "vFlip"));

    private static final List<MutableFieldCoverageCandidate> BATCH134_MUTABLE_FIELDS =
            List.of(
                    new MutableFieldCoverageCandidate(
                            "com.openggf.game.sonic1.objects.Sonic1EggPrisonButtonObjectInstance",
                            "baseY"),
                    new MutableFieldCoverageCandidate(
                            "com.openggf.game.sonic1.objects.Sonic1GlassBlockObjectInstance",
                            "blockFrame", "fullSubtype", "isTall", "moveType"),
                    new MutableFieldCoverageCandidate(
                            "com.openggf.game.sonic1.objects.Sonic1GlassReflectionInstance",
                            "isTall", "reflectSubtype"),
                    new MutableFieldCoverageCandidate(
                            "com.openggf.game.sonic1.objects.Sonic1JunctionObjectInstance",
                            "switchIndex"),
                    new MutableFieldCoverageCandidate(
                            "com.openggf.game.sonic1.objects.Sonic1LavaGeyserMakerObjectInstance",
                            "subtype", "timerReload"),
                    new MutableFieldCoverageCandidate(
                            "com.openggf.game.sonic1.objects.Sonic1LavaGeyserObjectInstance",
                            "behindPriority", "role"),
                    new MutableFieldCoverageCandidate(
                            "com.openggf.game.sonic1.objects.Sonic1LavaWallObjectInstance",
                            "currentY", "role"));

    private static final List<MutableFieldCoverageCandidate> BATCH135_MUTABLE_FIELDS =
            List.of(
                    new MutableFieldCoverageCandidate(
                            "com.openggf.game.sonic2.objects.SpeedLauncherObjectInstance",
                            "accel", "destX", "homeX", "xFlipped"),
                    new MutableFieldCoverageCandidate(
                            "com.openggf.game.sonic2.objects.SmallMetalPformObjectInstance$SmallMetalPformChildInstance",
                            "xFlipped"));

    private static final List<MutableFieldCoverageCandidate> BATCH136_MUTABLE_FIELDS =
            List.of(
                    new MutableFieldCoverageCandidate(
                            "com.openggf.game.sonic3k.objects.AizBombExplosionInstance",
                            "posY"),
                    new MutableFieldCoverageCandidate(
                            AizCollapsingLogBridgeObjectInstance.class.getName(),
                            "hFlip", "halfWidth", "isFireBridge", "subtypeBase", "totalTimer", "x", "y"),
                    new MutableFieldCoverageCandidate(
                            "com.openggf.game.sonic3k.objects.AizCollapsingLogBridgeObjectInstance$CollapsingLogSegment",
                            "fixedX", "isFireVariant"),
                    new MutableFieldCoverageCandidate(
                            AizDisappearingFloorObjectInstance.class.getName(),
                            "periodMask", "phaseOffset", "x", "y"),
                    new MutableFieldCoverageCandidate(
                            "com.openggf.game.sonic3k.objects.AizDisappearingFloorObjectInstance$BorderChild",
                            "x", "y"),
                    new MutableFieldCoverageCandidate(
                            AizFallingLogObjectInstance.class.getName(),
                            "phaseOffset", "spawnX", "spawnY", "timingMask"),
                    new MutableFieldCoverageCandidate(
                            AizFlippingBridgeObjectInstance.class.getName(),
                            "animDirection", "animPeriod", "hFlip", "maxFrame", "x", "y"));

    private static final List<MutableFieldCoverageCandidate> BATCH137_MUTABLE_FIELDS =
            List.of(
                    new MutableFieldCoverageCandidate(
                            "com.openggf.game.sonic3k.objects.AizForegroundPlantInstance",
                            "mappingFrame", "origX", "origY", "scrollRate"),
                    new MutableFieldCoverageCandidate(
                            "com.openggf.game.sonic3k.objects.AizGiantRideVineObjectInstance",
                            "currentX", "currentY", "phaseOffset", "segmentCount"),
                    new MutableFieldCoverageCandidate(
                            "com.openggf.game.sonic3k.objects.AizHollowTreeObjectInstance",
                            "treeX", "treeY"),
                    new MutableFieldCoverageCandidate(
                            "com.openggf.game.sonic3k.objects.AizMinibossImpactFlameChild",
                            "worldX", "worldY"),
                    new MutableFieldCoverageCandidate(
                            "com.openggf.game.sonic3k.objects.AizRideVineObjectInstance",
                            "subtype", "targetX"),
                    new MutableFieldCoverageCandidate(
                            "com.openggf.game.sonic3k.objects.AizShipBombInstance",
                            "initialWorldY"),
                    new MutableFieldCoverageCandidate(
                            "com.openggf.game.sonic3k.objects.AizSpikedLogObjectInstance",
                            "baseY"));

    private static final List<MutableFieldCoverageCandidate> BATCH138_MUTABLE_FIELDS =
            List.of(
                    new MutableFieldCoverageCandidate(
                            "com.openggf.game.sonic3k.objects.AnimatedStillSpriteInstance",
                            "animDelay"),
                    new MutableFieldCoverageCandidate(
                            "com.openggf.game.sonic3k.objects.AutoSpinObjectInstance",
                            "groundOnly", "lockControls", "noSpinLock", "snapToWall", "verticalMode", "xFlipped"),
                    new MutableFieldCoverageCandidate(
                            "com.openggf.game.sonic3k.objects.AutomaticTunnelObjectInstance",
                            "lbz2Mode", "maintainVelocity", "pathId", "reversePath", "subtype"),
                    new MutableFieldCoverageCandidate(
                            "com.openggf.game.sonic3k.objects.BreakableWallObjectInstance",
                            "triggerControlled", "x", "y"),
                    new MutableFieldCoverageCandidate(
                            "com.openggf.game.sonic3k.objects.BubblerObjectInstance",
                            "maker", "originalX", "originalY"));

    private static final List<MutableFieldCoverageCandidate> BATCH139_MUTABLE_FIELDS =
            List.of(
                    new MutableFieldCoverageCandidate(
                            "com.openggf.game.sonic3k.objects.CnzBalloonInstance",
                            "subtype"),
                    new MutableFieldCoverageCandidate(
                            "com.openggf.game.sonic3k.objects.CnzBarberPoleObjectInstance",
                            "mirrored"),
                    new MutableFieldCoverageCandidate(
                            "com.openggf.game.sonic3k.objects.CnzBumperObjectInstance",
                            "initialAngle", "originX", "originY", "reverseOrbit"),
                    new MutableFieldCoverageCandidate(
                            "com.openggf.game.sonic3k.objects.CnzGiantWheelInstance",
                            "flipped"),
                    new MutableFieldCoverageCandidate(
                            "com.openggf.game.sonic3k.objects.CnzHoverFanInstance",
                            "activeVariant", "baseX", "baseY", "initialFrame", "liftWindowMax",
                            "liftWindowMin", "subtype", "xFlipped", "xWindowMax", "xWindowMin"),
                    new MutableFieldCoverageCandidate(
                            "com.openggf.game.sonic3k.objects.CnzTeleporterBeamInstance",
                            "centreX", "centreY"),
                    new MutableFieldCoverageCandidate(
                            "com.openggf.game.sonic3k.objects.CnzTriangleBumperObjectInstance",
                            "fullWidth", "halfWidth", "launchDown", "launchLeft"),
                    new MutableFieldCoverageCandidate(
                            "com.openggf.game.sonic3k.objects.CnzVacuumTubeInstance",
                            "configuredLiftFrames", "facingRight", "liftMode"),
                    new MutableFieldCoverageCandidate(
                            "com.openggf.game.sonic3k.objects.CnzWaterLevelButtonInstance",
                            "x", "y"),
                    new MutableFieldCoverageCandidate(
                            "com.openggf.game.sonic3k.objects.CnzWireCageObjectInstance",
                            "verticalOffset", "verticalRange", "verticalVelocity"));

    private static final List<MutableFieldCoverageCandidate> BATCH140_MUTABLE_FIELDS =
            List.of(
                    new MutableFieldCoverageCandidate(
                            "com.openggf.game.sonic3k.objects.DoorObjectInstance",
                            "baseX", "baseY", "halfHeight", "halfWidth", "horizontal", "priority",
                            "triggerMax", "triggerMin", "xFlipped", "yFlipped"),
                    new MutableFieldCoverageCandidate(
                            "com.openggf.game.sonic3k.objects.FloatingPlatformObjectInstance",
                            "baseX", "baseY", "halfHeight", "halfWidth", "mappingFrame", "moveType",
                            "outOfRangeLimit", "outOfRangeReferenceX", "xFlip"),
                    new MutableFieldCoverageCandidate(
                            "com.openggf.game.sonic3k.objects.GumballItemObjectInstance",
                            "mappingFrame", "motionMode", "rewardMode", "subtype"),
                    new MutableFieldCoverageCandidate(
                            "com.openggf.game.sonic3k.objects.HCZBlockObjectInstance",
                            "halfHeight", "halfWidth", "mappingFrame", "x", "y"));

    private static final List<MutableFieldCoverageCandidate> BATCH141_MUTABLE_FIELDS =
            List.of(
                    new MutableFieldCoverageCandidate(
                            "com.openggf.game.sonic3k.objects.AizDrawBridgeObjectInstance",
                            "cutsceneOverride", "pivotX", "pivotY", "reverseVertical", "settledAngle", "xFlip"),
                    new MutableFieldCoverageCandidate(
                            "com.openggf.game.sonic3k.objects.AizEmeraldScatterInstance",
                            "mappingFrame"),
                    new MutableFieldCoverageCandidate(
                            "com.openggf.game.sonic3k.objects.AizLrzRockObjectInstance",
                            "baseX", "baseY", "behaviorBits", "displayFrame", "knucklesOnly",
                            "knucklesOnlyStanding", "sizeIndex", "variant"));

    private static final List<MutableFieldCoverageCandidate> BATCH142_MUTABLE_FIELDS =
            List.of(
                    new MutableFieldCoverageCandidate(
                            "com.openggf.game.sonic3k.objects.HCZBreakableBarObjectInstance",
                            "halfExtent", "hasTimer", "heightPixels", "isHorizontal", "mappingFrame",
                            "nonDestructiveRelease", "sizeIndex", "subtype", "totalExtent",
                            "widthOrHeight", "widthPixels", "x", "y"),
                    new MutableFieldCoverageCandidate(
                            "com.openggf.game.sonic3k.objects.HCZBreakableBarObjectInstance$BreakableBarDebris",
                            "debrisFrame"));

    private static final List<MutableFieldCoverageCandidate> BATCH143_MUTABLE_FIELDS =
            List.of(
                    new MutableFieldCoverageCandidate(
                            "com.openggf.game.sonic3k.objects.HCZConveyorBeltObjectInstance",
                            "activeLeftBound", "activeRightBound", "flipped", "leftBound", "objY",
                            "rawSubtype", "rightBound", "subtypeIndex"),
                    new MutableFieldCoverageCandidate(
                            "com.openggf.game.sonic3k.objects.HCZConveyorSpikeObjectInstance",
                            "centerY", "leftBound", "rightBound"),
                    new MutableFieldCoverageCandidate(
                            "com.openggf.game.sonic3k.objects.HCZLargeFanObjectInstance",
                            "x"),
                    new MutableFieldCoverageCandidate(
                            "com.openggf.game.sonic3k.objects.HCZSnakeBlocksObjectInstance",
                            "baseX", "baseY", "direction"));

    private static final List<MutableFieldCoverageCandidate> BATCH144_MUTABLE_FIELDS =
            List.of(
                    new MutableFieldCoverageCandidate(
                            "com.openggf.game.sonic3k.objects.badniks.AbstractS3kBadnikInstance",
                            "collisionSizeIndex", "priorityBucket", "planePriority"),
                    new MutableFieldCoverageCandidate(
                            "com.openggf.game.sonic3k.objects.badniks.TurboSpikerBadnikInstance$TurboSpikerAnimatedParticle",
                            "priorityBucket"));

    private static final List<MutableFieldCoverageCandidate> BATCH145_MUTABLE_FIELDS =
            List.of(
                    new MutableFieldCoverageCandidate(
                            "com.openggf.game.sonic3k.objects.badniks.CluckoidBadnikInstance$BreathDebrisChild",
                            "bigLeaf", "hFlip"),
                    new MutableFieldCoverageCandidate(
                            "com.openggf.game.sonic3k.objects.badniks.PenguinatorBadnikInstance$PenguinatorSnowdustInstance",
                            "x", "y"),
                    new MutableFieldCoverageCandidate(
                            "com.openggf.game.sonic3k.objects.badniks.RibotBadnikInstance$RibotVisualChild",
                            "originX", "originY", "visualIndex"),
                    new MutableFieldCoverageCandidate(
                            "com.openggf.game.sonic3k.objects.badniks.SnaleBlasterBadnikInstance$SnaleBlasterCoverChild",
                            "xOffset", "yOffset"),
                    new MutableFieldCoverageCandidate(
                            "com.openggf.game.sonic3k.objects.badniks.SnaleBlasterBadnikInstance$SnaleBlasterShooterChild",
                            "verticalFlipShot", "xOffset", "yOffset"),
                    new MutableFieldCoverageCandidate(
                            "com.openggf.game.sonic3k.objects.badniks.SpikerBadnikInstance$SpikerSideLauncherChild",
                            "leftSide"),
                    new MutableFieldCoverageCandidate(
                            "com.openggf.game.sonic3k.objects.badniks.TunnelbotBadnikInstance$TunnelbotArm",
                            "xOffset", "yOffset"),
                    new MutableFieldCoverageCandidate(
                            "com.openggf.game.sonic3k.objects.badniks.TunnelbotBadnikInstance$TunnelbotDebris",
                            "frame"),
                    new MutableFieldCoverageCandidate(
                            "com.openggf.game.sonic3k.objects.badniks.TurboSpikerBadnikInstance",
                            "hiddenVariant", "turnResetTimer"),
                    new MutableFieldCoverageCandidate(
                            "com.openggf.game.sonic3k.objects.badniks.TurboSpikerBadnikInstance$TurboSpikerAnimatedParticle",
                            "currentX", "currentY", "frameDelay", "playSound"));

    private static final List<MutableFieldCoverageCandidate> BATCH146_MUTABLE_FIELDS =
            List.of(
                    new MutableFieldCoverageCandidate(
                            "com.openggf.game.sonic3k.objects.Cnz2CutsceneButtonInstance",
                            "subtype", "x", "y"),
                    new MutableFieldCoverageCandidate(
                            "com.openggf.game.sonic3k.objects.CnzCannonInstance$CannonLaunchPuff",
                            "xVelocity", "yVelocity"),
                    new MutableFieldCoverageCandidate(
                            "com.openggf.game.sonic3k.objects.CnzCylinderInstance",
                            "angleStep", "baseX", "baseY", "circularRoute", "motionSelector",
                            "speedCap"),
                    new MutableFieldCoverageCandidate(
                            "com.openggf.game.sonic3k.objects.CnzEggCapsuleInstance",
                            "centreX", "centreY", "completionContinuation"),
                    new MutableFieldCoverageCandidate(
                            "com.openggf.game.sonic3k.objects.bosses.IczEndBossEggCapsuleInstance",
                            "centreX", "centreY"),
                    new MutableFieldCoverageCandidate(
                            "com.openggf.game.sonic3k.objects.bosses.MhzEndBossEggCapsuleInstance",
                            "centreX", "centreY"),
                    new MutableFieldCoverageCandidate(
                            "com.openggf.game.sonic3k.objects.CnzMinibossDebrisChild",
                            "mappingFrame"),
                    new MutableFieldCoverageCandidate(
                            "com.openggf.game.sonic3k.objects.CnzTeleporterInstance",
                            "centreY"));

    private static final List<MutableFieldCoverageCandidate> BATCH147_MUTABLE_FIELDS =
            List.of(
                    new MutableFieldCoverageCandidate(
                            "com.openggf.game.sonic3k.objects.AizRockFragmentChild",
                            "gravity"),
                    new MutableFieldCoverageCandidate(
                            "com.openggf.game.sonic3k.objects.CnzMinibossDebrisChild",
                            "gravity"),
                    new MutableFieldCoverageCandidate(
                            "com.openggf.game.sonic3k.objects.CollapsingBridgeObjectInstance$MgzStompDebris",
                            "gravity"),
                    new MutableFieldCoverageCandidate(
                            "com.openggf.game.sonic3k.objects.IczBreakableWallObjectInstance$IczBreakableWallDebris",
                            "gravity"),
                    new MutableFieldCoverageCandidate(
                            "com.openggf.game.sonic3k.objects.IczFreezerObjectInstance$IceDebris",
                            "gravity"),
                    new MutableFieldCoverageCandidate(
                            "com.openggf.game.sonic3k.objects.IczHarmfulIceObjectInstance$IceDebris",
                            "gravity"),
                    new MutableFieldCoverageCandidate(
                            "com.openggf.game.sonic3k.objects.IczIceCubeObjectInstance$IceCubeDebris",
                            "gravity"),
                    new MutableFieldCoverageCandidate(
                            "com.openggf.game.sonic3k.objects.IczSegmentColumnObjectInstance$BreakDebris",
                            "gravity"),
                    new MutableFieldCoverageCandidate(
                            "com.openggf.game.sonic3k.objects.IczStalagtiteObjectInstance$StalagtiteDebris",
                            "gravity"),
                    new MutableFieldCoverageCandidate(
                            "com.openggf.game.sonic3k.objects.RockDebrisChild",
                            "gravity"),
                    new MutableFieldCoverageCandidate(
                            "com.openggf.game.sonic3k.objects.TensionBridgeObjectInstance$BridgeFragment",
                            "gravity"),
                    new MutableFieldCoverageCandidate(
                            "com.openggf.game.sonic3k.objects.bosses.IczEndBossInstance$IczEndBossDefeatDebrisChild",
                            "gravity"));

    private static final List<MutableFieldCoverageCandidate> BATCH148_MUTABLE_FIELDS =
            List.of(
                    new MutableFieldCoverageCandidate(
                            "com.openggf.game.sonic3k.objects.HCZCGZFanObjectInstance",
                            "innerRange", "isUnderwater", "originalX", "outerRange", "y"),
                    new MutableFieldCoverageCandidate(
                            "com.openggf.game.sonic3k.objects.HCZCGZFanObjectInstance$FanBubbleChild",
                            "x", "yVelocity"),
                    new MutableFieldCoverageCandidate(
                            "com.openggf.game.sonic3k.objects.HCZCGZFanObjectInstance$FanPlatformChild",
                            "facingLeft", "maxSlideDistance", "originalX", "y"));

    private static final List<MutableFieldCoverageCandidate> BATCH149_MUTABLE_FIELDS =
            List.of(
                    new MutableFieldCoverageCandidate(
                            "com.openggf.game.sonic3k.objects.IczFreezerObjectInstance",
                            "hFlip", "verticalFlip", "x", "y"),
                    new MutableFieldCoverageCandidate(
                            "com.openggf.game.sonic3k.objects.IczFreezerObjectInstance$CaptureCloud",
                            "hFlip", "x", "y"),
                    new MutableFieldCoverageCandidate(
                            "com.openggf.game.sonic3k.objects.IczFreezerObjectInstance$FrostPuff",
                            "originX", "originY", "verticalFlip"));

    private static final List<MutableFieldCoverageCandidate> BATCH150_MUTABLE_FIELDS =
            List.of(
                    new MutableFieldCoverageCandidate(
                            "com.openggf.game.sonic3k.objects.CorkFloorObjectInstance",
                            "hFlip", "mode", "subtype", "x", "y"),
                    new MutableFieldCoverageCandidate(
                            "com.openggf.game.sonic3k.objects.CorkFloorObjectInstance$CorkFloorFragment",
                            "fragmentFrameIndex", "hFlip", "pieceIndex"));

    private static final List<MutableFieldCoverageCandidate> BATCH151_MUTABLE_FIELDS =
            List.of(
                    new MutableFieldCoverageCandidate(
                            "com.openggf.game.sonic3k.objects.CollapsingBridgeObjectInstance$BridgeFragment",
                            "fragmentFrameIndex", "hFlip", "highPriority", "pieceIndex"),
                    new MutableFieldCoverageCandidate(
                            "com.openggf.game.sonic3k.objects.CollapsingBridgeObjectInstance$MgzStompDebris",
                            "frameIndex", "hFlip", "pieceIndex"));

    private static final List<MutableFieldCoverageCandidate> BATCH152_MUTABLE_FIELDS =
            List.of(
                    new MutableFieldCoverageCandidate(
                            "com.openggf.game.sonic3k.objects.MGZTriggerPlatformObjectInstance",
                            "direction", "frameIndex", "heightPixels", "mode",
                            "stepPerFrame", "totalFrames", "triggerIndex", "widthPixels"));

    private static final List<MutableFieldCoverageCandidate> BATCH153_MUTABLE_FIELDS =
            List.of(
                    new MutableFieldCoverageCandidate(
                            "com.openggf.game.sonic3k.objects.LbzMovingPlatformObjectInstance",
                            "baseY", "hFlip", "halfHeight", "halfWidth",
                            "liftDistance", "mappingFrame", "originalBaseX"));

    private static final List<MutableFieldCoverageCandidate> BATCH154_MUTABLE_FIELDS =
            List.of(
                    new MutableFieldCoverageCandidate(
                            "com.openggf.game.sonic3k.objects.IczSwingingPlatformObjectInstance",
                            "anchorX", "anchorY", "releaseOnSwing",
                            "spawnX", "spawnY", "xFlip"));

    private static final List<MutableFieldCoverageCandidate> BATCH155_MUTABLE_FIELDS =
            List.of(
                    new MutableFieldCoverageCandidate(
                            "com.openggf.game.sonic3k.objects.MGZSwingingSpikeBallObjectInstance",
                            "baseX", "baseY", "hFlip", "vFlip", "verticalMode"));

    private static final List<MutableFieldCoverageCandidate> BATCH156_MUTABLE_FIELDS =
            List.of(
                    new MutableFieldCoverageCandidate(
                            "com.openggf.game.sonic3k.objects.Sonic3kButtonObjectInstance",
                            "adjustedY", "toggleMode", "topSolid", "triggerBit", "triggerIndex"));

    private static final List<MutableFieldCoverageCandidate> BATCH157_MUTABLE_FIELDS =
            List.of(
                    new MutableFieldCoverageCandidate(
                            "com.openggf.game.sonic3k.objects.HCZSpinningColumnObjectInstance",
                            "baseX", "baseY", "motionMode", "xFlipped"));

    private static final List<MutableFieldCoverageCandidate> BATCH158_MUTABLE_FIELDS =
            List.of(
                    new MutableFieldCoverageCandidate(
                            "com.openggf.game.sonic3k.objects.HCZTwistingLoopObjectInstance",
                            "objectFlippedX", "reverseEntry", "subtype"));

    private static final List<MutableFieldCoverageCandidate> BATCH159_MUTABLE_FIELDS =
            List.of(
                    new MutableFieldCoverageCandidate(
                            "com.openggf.game.sonic3k.objects.HCZWaterDropObjectInstance",
                            "spawnInterval", "spawnX", "spawnY"),
                    new MutableFieldCoverageCandidate(
                            "com.openggf.game.sonic3k.objects.HCZWaterSplashObjectInstance",
                            "x", "y"));

    private static final List<MutableFieldCoverageCandidate> BATCH160_MUTABLE_FIELDS =
            List.of(
                    new MutableFieldCoverageCandidate(
                            "com.openggf.game.sonic3k.objects.HCZWaterWallObjectInstance",
                            "isHorizontal"),
                    new MutableFieldCoverageCandidate(
                            "com.openggf.game.sonic3k.objects.HCZWaterWallObjectInstance$WaterWallSplashChild",
                            "x", "y"),
                    new MutableFieldCoverageCandidate(
                            "com.openggf.game.sonic3k.objects.HCZWaterWallObjectInstance$WaterWallSprayChild",
                            "useBubbleArt"));

    private static final List<MutableFieldCoverageCandidate> BATCH161_MUTABLE_FIELDS =
            List.of(
                    new MutableFieldCoverageCandidate(
                            "com.openggf.game.sonic3k.objects.Hcz2CutsceneButtonInstance",
                            "x", "y"));

    private static final List<MutableFieldCoverageCandidate> BATCH162_MUTABLE_FIELDS =
            List.of(
                    new MutableFieldCoverageCandidate(
                            "com.openggf.game.sonic3k.objects.IczBreakableWallObjectInstance",
                            "hFlip", "x", "y"),
                    new MutableFieldCoverageCandidate(
                            "com.openggf.game.sonic3k.objects.IczHarmfulIceObjectInstance",
                            "breakOnTouch", "hFlip", "x", "y"),
                    new MutableFieldCoverageCandidate(
                            "com.openggf.game.sonic3k.objects.IczIceBlockObjectInstance",
                            "hFlip", "x", "y"),
                    new MutableFieldCoverageCandidate(
                            "com.openggf.game.sonic3k.objects.IczIceCubeObjectInstance",
                            "hFlip", "x", "y"));

    private static final List<MutableFieldCoverageCandidate> BATCH163_MUTABLE_FIELDS =
            List.of(
                    new MutableFieldCoverageCandidate(
                            "com.openggf.game.sonic3k.objects.IczCrushingColumnObjectInstance",
                            "hasBottomDecoration", "spawnY", "subtype", "xFlip", "yFlip"),
                    new MutableFieldCoverageCandidate(
                            "com.openggf.game.sonic3k.objects.IczPathFollowPlatformObjectInstance",
                            "hFlip", "spawnX", "spawnY"),
                    new MutableFieldCoverageCandidate(
                            "com.openggf.game.sonic3k.objects.IczStalagtiteObjectInstance",
                            "hFlip"));

    private static final List<MutableFieldCoverageCandidate> BATCH164_MUTABLE_FIELDS =
            List.of(
                    new MutableFieldCoverageCandidate(
                            "com.openggf.game.sonic3k.objects.IczSnowPileObjectInstance",
                            "hFlip", "startsNextLevel", "variant"),
                    new MutableFieldCoverageCandidate(
                            "com.openggf.game.sonic3k.objects.IczSnowPileObjectInstance$SnowPileDebris",
                            "hFlip"),
                    new MutableFieldCoverageCandidate(
                            "com.openggf.game.sonic3k.objects.IczSnowboardIntroInstance$SnowboardDustInstance",
                            "xVel", "yVel"));

    private static final List<MutableFieldCoverageCandidate> BATCH165_MUTABLE_FIELDS =
            List.of(
                    new MutableFieldCoverageCandidate(
                            "com.openggf.game.sonic3k.objects.IczTensionPlatformObjectInstance",
                            "baseY", "hFlip", "x"),
                    new MutableFieldCoverageCandidate(
                            "com.openggf.game.sonic3k.objects.LbzAlarmObjectInstance",
                            "subtype"),
                    new MutableFieldCoverageCandidate(
                            "com.openggf.game.sonic3k.objects.LbzCupElevatorPoleInstance",
                            "halfHeight", "mappingFrame"),
                    new MutableFieldCoverageCandidate(
                            "com.openggf.game.sonic3k.objects.LbzExplodingTriggerInstance",
                            "triggerIndex"),
                    new MutableFieldCoverageCandidate(
                            "com.openggf.game.sonic3k.objects.LbzFlameThrowerFlameInstance",
                            "hFlip", "x", "y"),
                    new MutableFieldCoverageCandidate(
                            "com.openggf.game.sonic3k.objects.LbzFlameThrowerObjectInstance",
                            "hFlip", "subtype"));

    private static final List<MutableFieldCoverageCandidate> BATCH166_MUTABLE_FIELDS =
            List.of(
                    new MutableFieldCoverageCandidate(
                            "com.openggf.game.sonic3k.objects.LbzPlayerLauncherInstance",
                            "facingLeft", "launchSpeed"),
                    new MutableFieldCoverageCandidate(
                            "com.openggf.game.sonic3k.objects.LbzRideGrappleInstance",
                            "ejectAtPathEnd", "pathLeft", "pathRight"),
                    new MutableFieldCoverageCandidate(
                            "com.openggf.game.sonic3k.objects.LbzRollingDrumInstance",
                            "leftBound", "rightBound"));

    private static final List<MutableFieldCoverageCandidate> BATCH167_MUTABLE_FIELDS =
            List.of(
                    new MutableFieldCoverageCandidate(
                            "com.openggf.game.sonic3k.objects.MGZDashTriggerObjectInstance",
                            "facingLeft", "triggerIndex"),
                    new MutableFieldCoverageCandidate(
                            "com.openggf.game.sonic3k.objects.MGZHeadTriggerObjectInstance",
                            "flipped", "triggerIndex"),
                    new MutableFieldCoverageCandidate(
                            "com.openggf.game.sonic3k.objects.MGZHeadTriggerProjectileInstance",
                            "worldY"),
                    new MutableFieldCoverageCandidate(
                            "com.openggf.game.sonic3k.objects.MGZLBZSmashingPillarObjectInstance",
                            "baseY", "targetDistance"),
                    new MutableFieldCoverageCandidate(
                            "com.openggf.game.sonic3k.objects.MGZMovingSpikePlatformObjectInstance",
                            "baseX", "baseY"));

    private static final List<MutableFieldCoverageCandidate> BATCH168_MUTABLE_FIELDS =
            List.of(
                    new MutableFieldCoverageCandidate(
                            "com.openggf.game.sonic3k.objects.MGZSwingingPlatformObjectInstance",
                            "angleStep", "pivotFrame", "pivotX", "pivotY"),
                    new MutableFieldCoverageCandidate(
                            "com.openggf.game.sonic3k.objects.MGZTwistingLoopObjectInstance",
                            "captureThreshold", "centerX", "centerY", "flipped"),
                    new MutableFieldCoverageCandidate(
                            "com.openggf.game.sonic3k.objects.MgzEndBossDefeatDebrisChild",
                            "flipX", "index", "xVel", "yVel"));

    private static final List<MutableFieldCoverageCandidate> BATCH169_MUTABLE_FIELDS =
            List.of(
                    new MutableFieldCoverageCandidate(
                            "com.openggf.game.sonic3k.objects.MhzCurledVineObjectInstance",
                            "hFlip"),
                    new MutableFieldCoverageCandidate(
                            "com.openggf.game.sonic3k.objects.MhzMushroomCapObjectInstance",
                            "animationPhaseOffset", "baseX", "baseY", "planePriority", "priorityBucket"),
                    new MutableFieldCoverageCandidate(
                            "com.openggf.game.sonic3k.objects.MhzMushroomCatapultObjectInstance",
                            "baseX", "baseY", "childX"),
                    new MutableFieldCoverageCandidate(
                            "com.openggf.game.sonic3k.objects.MhzMushroomPlatformObjectInstance",
                            "fallingSubtype", "hFlip", "vFlip"),
                    new MutableFieldCoverageCandidate(
                            "com.openggf.game.sonic3k.objects.MhzShipPropellerInstance",
                            "propellerIndex"),
                    new MutableFieldCoverageCandidate(
                            "com.openggf.game.sonic3k.objects.MhzTwistedVineObjectInstance",
                            "upperVariant"));

    private static final List<MutableFieldCoverageCandidate> BATCH170_MUTABLE_FIELDS =
            List.of(
                    new MutableFieldCoverageCandidate(
                            "com.openggf.game.sonic3k.objects.badniks.BlastoidBadnikInstance",
                            "triggerIndex"),
                    new MutableFieldCoverageCandidate(
                            "com.openggf.game.sonic3k.objects.badniks.MadmoleBadnikInstance",
                            "homeY"),
                    new MutableFieldCoverageCandidate(
                            "com.openggf.game.sonic3k.objects.badniks.MonkeyDudeBadnikInstance",
                            "activeStepCount", "firstStepCount", "initialFacingLeft", "treeAnchorX"),
                    new MutableFieldCoverageCandidate(
                            "com.openggf.game.sonic3k.objects.CutsceneKnucklesMhz2Instance$Mhz2KnucklesLeafParticle",
                            "yVelocity"));

    private static final List<MutableFieldCoverageCandidate> BATCH171_MUTABLE_FIELDS =
            List.of(
                    new MutableFieldCoverageCandidate(
                            "com.openggf.game.sonic3k.objects.MhzShipSequenceControllerInstance",
                            "initialShipMotion", "initialSwingSpeed"),
                    new MutableFieldCoverageCandidate(
                            "com.openggf.game.sonic3k.objects.S3kHiddenMonitorInstance",
                            "monitorSubtype", "monitorX", "monitorY"),
                    new MutableFieldCoverageCandidate(
                            "com.openggf.game.sonic3k.objects.SinkingMudObjectInstance",
                            "halfWidth"),
                    new MutableFieldCoverageCandidate(
                            "com.openggf.game.sonic3k.objects.Sonic3kSSEntryRingObjectInstance",
                            "bitIndex", "hiddenPalaceRoute"),
                    new MutableFieldCoverageCandidate(
                            "com.openggf.game.sonic3k.objects.Sonic3kSpringObjectInstance",
                            "redSpring"),
                    new MutableFieldCoverageCandidate(
                            "com.openggf.game.sonic3k.objects.Sonic3kTwistedRampObjectInstance",
                            "facingLeft"),
                    new MutableFieldCoverageCandidate(
                            "com.openggf.game.sonic3k.objects.UpdraftObjectInstance",
                            "innerRange", "negativeSubtype", "outerRange"));

    private static final List<MutableFieldCoverageCandidate> BATCH172_MUTABLE_FIELDS =
            List.of(
                    new MutableFieldCoverageCandidate(
                            "com.openggf.game.sonic3k.objects.Mhz1CutsceneKnucklesInstance",
                            "x", "y"),
                    new MutableFieldCoverageCandidate(
                            "com.openggf.game.sonic3k.objects.MhzMinibossTreeInstance$MhzMinibossTreeChipInstance",
                            "bounceEnabled"),
                    new MutableFieldCoverageCandidate(
                            "com.openggf.game.sonic3k.objects.S3kBossDefeatSignpostFlow",
                            "signpostX"),
                    new MutableFieldCoverageCandidate(
                            "com.openggf.game.sonic3k.objects.S3kCutsceneButtonObjectInstance",
                            "cutsceneOverride", "x", "y"),
                    new MutableFieldCoverageCandidate(
                            "com.openggf.game.sonic3k.objects.Sonic3kCollapsingPlatformObjectInstance",
                            "hFlip", "mappingFrame"),
                    new MutableFieldCoverageCandidate(
                            "com.openggf.game.sonic3k.objects.Sonic3kStarPostObjectInstance",
                            "cameraLockFlag", "checkpointIndex"),
                    new MutableFieldCoverageCandidate(
                            "com.openggf.level.objects.SkidDustObjectInstance",
                            "facingLeft"));

    private static final List<MutableFieldCoverageCandidate> BATCH173_MUTABLE_FIELDS =
            List.of(
                    new MutableFieldCoverageCandidate(
                            "com.openggf.game.sonic1.objects.badniks.Sonic1BombFuseInstance",
                            "origY"),
                    new MutableFieldCoverageCandidate(
                            "com.openggf.game.sonic2.objects.FlipperObjectInstance",
                            "idleAnimId"),
                    new MutableFieldCoverageCandidate(
                            "com.openggf.game.sonic2.objects.LauncherBallObjectInstance",
                            "renderXFlip", "renderYFlip", "reverseAnim", "startFrame"),
                    new MutableFieldCoverageCandidate(
                            "com.openggf.game.sonic2.objects.OOZLauncherObjectInstance",
                            "isVertical"));

    private static final List<MutableFieldCoverageCandidate> BATCH174_MUTABLE_FIELDS =
            List.of(
                    new MutableFieldCoverageCandidate(
                            "com.openggf.game.sonic3k.objects.HCZHandLauncherObjectInstance",
                            "baseX", "baseY", "facingLeft"),
                    new MutableFieldCoverageCandidate(
                            "com.openggf.game.sonic3k.objects.IczIceSpikesObjectInstance",
                            "hFlip", "originalX", "originalY", "subtypeZero", "vFlip"),
                    new MutableFieldCoverageCandidate(
                            "com.openggf.game.sonic3k.objects.LbzCupElevatorInstance",
                            "flingAtEnd", "hFlip"));

    private static final List<MutableFieldCoverageCandidate> BATCH175_MUTABLE_FIELDS =
            List.of(
                    new MutableFieldCoverageCandidate(
                            "com.openggf.level.objects.BreathingBubbleInstance",
                            "maxBubbleFrame", "riseVelocity"),
                    new MutableFieldCoverageCandidate(
                            "com.openggf.level.objects.SplashObjectInstance",
                            "facingLeft"),
                    new MutableFieldCoverageCandidate(
                            "com.openggf.game.sonic3k.objects.BreakableWallObjectInstance$BreakableWallFragment",
                            "fragmentFrameIndex", "pieceIndex"),
                    new MutableFieldCoverageCandidate(
                            "com.openggf.game.sonic3k.objects.bosses.IczEndBossInstance$IczEndBossDefeatDebrisChild",
                            "flipX", "frame"),
                    new MutableFieldCoverageCandidate(
                            "com.openggf.game.sonic3k.objects.bosses.HczEndBossEggCapsuleInstance",
                            "fixedX", "fixedY"));

    private static final List<MutableFieldCoverageCandidate> BATCH176_MUTABLE_FIELDS =
            List.of(
                    new MutableFieldCoverageCandidate(
                            "com.openggf.game.sonic3k.objects.LbzTriggerBridgeInstance",
                            "baseX", "baseY", "heightPixels", "triggerIndex", "widthPixels"),
                    new MutableFieldCoverageCandidate(
                            "com.openggf.game.sonic3k.objects.HczMinibossInstance$VortexBubbleChild",
                            "frame", "vortexX", "vortexY"),
                    new MutableFieldCoverageCandidate(
                            "com.openggf.game.sonic3k.objects.IczSnowPileObjectInstance$SnowdustParticle",
                            "mappingFrame", "priorityBucket"),
                    new MutableFieldCoverageCandidate(
                            "com.openggf.game.sonic3k.objects.Sonic3kCollapsingPlatformObjectInstance$CollapsingPlatformFragment",
                            "fragmentFrameIndex", "hFlip", "pieceIndex"),
                    new MutableFieldCoverageCandidate(
                            "com.openggf.game.sonic3k.objects.TensionBridgeObjectInstance$BridgeFragment",
                            "frameIndex", "highPri"));

    private static final List<MutableFieldCoverageCandidate> BATCH177_MUTABLE_FIELDS =
            List.of(
                    new MutableFieldCoverageCandidate(
                            "com.openggf.game.sonic3k.objects.GumballMachineObjectInstance$EjectionEffectChild",
                            "drawX", "drawY"),
                    new MutableFieldCoverageCandidate(
                            "com.openggf.game.sonic3k.objects.GumballMachineObjectInstance$PlatformChild",
                            "mappingFrame", "offsetFromMachine"),
                    new MutableFieldCoverageCandidate(
                            "com.openggf.game.sonic3k.objects.GumballMachineObjectInstance$BodyOverlayChild",
                            "offsetFromMachine"),
                    new MutableFieldCoverageCandidate(
                            "com.openggf.game.sonic3k.objects.MgzMinibossInstance$DefeatFragmentChild",
                            "mappingFrame"),
                    new MutableFieldCoverageCandidate(
                            "com.openggf.game.sonic3k.objects.MgzMinibossInstance$MgzBossCameraScrollHelper",
                            "targetX"));

    private static final List<MutableFieldCoverageCandidate> BATCH178_MUTABLE_FIELDS =
            List.of(
                    new MutableFieldCoverageCandidate(
                            "com.openggf.game.sonic3k.objects.CutsceneKnucklesLbz1CollapseChild",
                            "subtype"),
                    new MutableFieldCoverageCandidate(
                            "com.openggf.game.sonic3k.objects.CutsceneKnucklesLbz1RangeHelper",
                            "x", "y"),
                    new MutableFieldCoverageCandidate(
                            "com.openggf.game.sonic3k.objects.Sonic3kStarPostBonusStarChild",
                            "centerX", "centerY"),
                    new MutableFieldCoverageCandidate(
                            "com.openggf.game.sonic3k.objects.Sonic3kStarPostStarChild",
                            "centerX", "centerY"),
                    new MutableFieldCoverageCandidate(
                            "com.openggf.game.sonic3k.objects.bosses.MhzEndBossWeatherVisualChild",
                            "spark", "subtype"));

    private static final List<MutableFieldCoverageCandidate> BATCH179_MUTABLE_FIELDS =
            List.of(
                    new MutableFieldCoverageCandidate(
                            "com.openggf.game.sonic3k.objects.IczSegmentColumnObjectInstance",
                            "segmentCount", "x", "y"),
                    new MutableFieldCoverageCandidate(
                            "com.openggf.game.sonic3k.objects.IczTensionPlatformObjectInstance$SupportChild",
                            "hFlip", "x", "y"),
                    new MutableFieldCoverageCandidate(
                            "com.openggf.game.sonic3k.objects.Mgz2LevelCollapseSolidInstance",
                            "anchorX", "baseY"),
                    new MutableFieldCoverageCandidate(
                            "com.openggf.game.sonic3k.objects.bosses.HczEndBossTurbine",
                            "xOffset", "yOffset"));

    private static final List<MutableFieldCoverageCandidate> BATCH180_MUTABLE_FIELDS =
            List.of(
                    new MutableFieldCoverageCandidate(
                            "com.openggf.game.sonic3k.objects.MGZTopLauncherObjectInstance",
                            "hFlip"),
                    new MutableFieldCoverageCandidate(
                            "com.openggf.game.sonic3k.objects.TensionBridgeObjectInstance",
                            "baseY", "negativeSubtype", "segmentCount", "triggerIndex"));

    private static final List<MutableFieldCoverageCandidate> BATCH181_MUTABLE_FIELDS =
            List.of(new MutableFieldCoverageCandidate(
                    "com.openggf.game.sonic3k.objects.MGZPulleyObjectInstance",
                    "anchorX", "anchorY", "flipped", "targetExtension"));

    private static final List<MutableFieldCoverageCandidate> BATCH182_MUTABLE_FIELDS =
            List.of(new MutableFieldCoverageCandidate(
                    "com.openggf.game.sonic3k.objects.LbzPlayerLauncherInstance$LauncherArmChild",
                    "baseX", "baseY", "facingLeft", "nativeP1"));

    private static final List<MutableFieldCoverageCandidate> BATCH183_MUTABLE_FIELDS =
            List.of(new MutableFieldCoverageCandidate(
                    "com.openggf.game.sonic3k.objects.LbzTubeElevatorInstance",
                    "closedOnly"));

    private static final List<CodecDeletionCandidate> BATCH31_DELETED_CODECS = List.of(
            new CodecDeletionCandidate(BombPrizeObjectInstance.class.getName(), GameId.S2));

    private static final List<CodecDeletionCandidate> BATCH32_DELETED_CODECS = List.of(
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic2.objects.ResultsScreenObjectInstance",
                    GameId.S2),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic2.objects.RingPrizeObjectInstance",
                    GameId.S2),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic2.objects.bosses.Sonic2MTZBossInstance$MTZBossLaser",
                    GameId.S2));

    private static final List<CodecDeletionCandidate> BATCH33_DELETED_CODECS = List.of(
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic1.objects.badniks.Sonic1BombShrapnelInstance",
                    GameId.S1),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic1.objects.badniks.Sonic1CannonballInstance",
                    GameId.S1),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic1.objects.badniks.Sonic1NewtronMissileInstance",
                    GameId.S1));

    private static final List<CodecDeletionCandidate> BATCH34_DELETED_CODECS = List.of(
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic1.objects.badniks.Sonic1BuzzBomberMissileInstance",
                    GameId.S1),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic1.objects.badniks.Sonic1BuzzBomberMissileDissolveInstance",
                    GameId.S1),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic1.objects.badniks.Sonic1CrabmeatProjectileInstance",
                    GameId.S1));

    private static final List<CodecDeletionCandidate> BATCH35_DELETED_CODECS = List.of(
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic1.objects.Sonic1ResultsScreenObjectInstance",
                    GameId.S1),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic1.objects.Sonic1StomperDoorObjectInstance",
                    GameId.S1));

    private static final List<CodecDeletionCandidate> BATCH36_DELETED_CODECS = List.of(
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic1.objects.Sonic1FloatingBlockObjectInstance",
                    GameId.S1));

    private static final List<CodecDeletionCandidate> BATCH37_DELETED_CODECS = List.of(
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic3k.objects.bosses.IczEndBossInstance$IczEndBossRobotnikEscapeShip",
                    GameId.S3K));

    private static final List<CodecDeletionCandidate> BATCH38_DELETED_CODECS = List.of(
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic1.objects.Sonic1CollapsingFloorObjectInstance",
                    GameId.S1),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic1.objects.Sonic1EndingEmeraldsObjectInstance",
                    GameId.S1),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic1.objects.Sonic1ExplosionItemObjectInstance",
                    GameId.S1),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic1.objects.Sonic1MonitorPowerUpObjectInstance",
                    GameId.S1),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic1.objects.Sonic1RingInstance",
                    GameId.S1),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic1.objects.Sonic1SpikedBallChainObjectInstance",
                    GameId.S1));

    private static final List<CodecDeletionCandidate> BATCH39_DELETED_CODECS = List.of(
            new CodecDeletionCandidate(
                    "com.openggf.level.objects.boss.BossExplosionObjectInstance",
                    GameId.S1));

    private static final List<CodecDeletionCandidate> BATCH40_DELETED_CODECS = List.of(
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic3k.objects.bosses.MhzEndBossPaletteFadeController",
                    GameId.S3K));

    private static final List<CodecDeletionCandidate> BATCH41_DELETED_CODECS = List.of(
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic3k.objects.badniks.CorkeyBadnikInstance$CorkeyShotChild",
                    GameId.S3K));

    private static final List<CodecDeletionCandidate> BATCH42_DELETED_CODECS = List.of(
            new CodecDeletionCandidate(
                    MhzEndBossDefeatFragmentChild.class.getName(),
                    GameId.S3K));

    private static final List<CodecDeletionCandidate> BATCH43_DELETED_CODECS = List.of(
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic3k.objects.badniks.MadmoleBadnikInstance$SideDrillChild",
                    GameId.S3K));

    private static final List<CodecDeletionCandidate> BATCH44_DELETED_CODECS = List.of(
            new CodecDeletionCandidate(S3kResultsScreenObjectInstance.class.getName(), GameId.S3K),
            new CodecDeletionCandidate(Mgz2ResultsScreenObjectInstance.class.getName(), GameId.S3K));

    private static final List<CodecDeletionCandidate> BATCH45_DELETED_CODECS = List.of(
            new CodecDeletionCandidate(MhzEndBossRobotnikShipFlameInstance.class.getName(), GameId.S3K));

    private static final List<CodecDeletionCandidate> BATCH46_DELETED_CODECS = List.of(
            new CodecDeletionCandidate(CnzMinibossCoilInstance.class.getName(), GameId.S3K));

    private static final List<CodecDeletionCandidate> BATCH47_DELETED_CODECS = List.of(
            new CodecDeletionCandidate(CnzMinibossSparkInstance.class.getName(), GameId.S3K));

    private static final List<CodecDeletionCandidate> BATCH48_DELETED_CODECS = List.of(
            new CodecDeletionCandidate(CnzMinibossTopInstance.class.getName(), GameId.S3K));

    private static final List<CodecDeletionCandidate> BATCH49_DELETED_CODECS = List.of(
            new CodecDeletionCandidate(BuggernautBabyInstance.class.getName(), GameId.S3K));

    private static final List<CodecDeletionCandidate> BATCH50_DELETED_CODECS = List.of(
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic3k.objects.badniks.OrbinautBadnikInstance$OrbinautOrbInstance",
                    GameId.S3K),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic3k.objects.badniks.RibotBadnikInstance$RibotActiveChild",
                    GameId.S3K),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic3k.objects.badniks.StarPointerBadnikInstance$OrbitingPointInstance",
                    GameId.S3K));

    private static final List<CodecDeletionCandidate> BATCH51_DELETED_CODECS = List.of(
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic2.objects.badniks.BuzzerBadnikInstance$BuzzerFlameChild",
                    GameId.S2),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic2.objects.badniks.TurtloidRiderInstance",
                    GameId.S2),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic2.objects.badniks.TurtloidJetInstance",
                    GameId.S2));

    private static final List<CodecDeletionCandidate> BATCH52_DELETED_CODECS = List.of(
            new CodecDeletionCandidate(ARZBossPillar.class.getName(), GameId.S2),
            new CodecDeletionCandidate(CNZBossElectricBall.class.getName(), GameId.S2));

    private static final List<CodecDeletionCandidate> SHARED_HELPER_DELETED_CODECS = List.of(
            new CodecDeletionCandidate(AnimalObjectInstance.class.getName(), GameId.S2),
            new CodecDeletionCandidate(ExplosionObjectInstance.class.getName(), GameId.S2),
            new CodecDeletionCandidate(SkidDustObjectInstance.class.getName(), GameId.S2));

    private static final List<CodecDeletionCandidate> SHARED_SPARKLE_DELETED_CODECS = List.of(
            new CodecDeletionCandidate(SignpostSparkleObjectInstance.class.getName(), GameId.S2));

    private static final List<CodecDeletionCandidate> SHARED_PLACEHOLDER_RECREATE_CLASSES = List.of(
            new CodecDeletionCandidate(PlaceholderObjectInstance.class.getName(), GameId.S2));

    private static final List<CodecDeletionCandidate> SHARED_WATER_EFFECT_RECREATE_CLASSES = List.of(
            new CodecDeletionCandidate(
                    "com.openggf.level.objects.BreathingBubbleInstance", GameId.S2),
            new CodecDeletionCandidate(
                    "com.openggf.level.objects.SplashObjectInstance", GameId.S2));

    private static final List<CodecDeletionCandidate> S2_SPEED_LAUNCHER_RECREATE_CLASSES = List.of(
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic2.objects.SpeedLauncherObjectInstance", GameId.S2));

    private static final List<CodecDeletionCandidate> S2_MTZ_SPIN_TUBE_RECREATE_CLASSES = List.of(
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic2.objects.MTZSpinTubeObjectInstance", GameId.S2));

    private static final List<CodecDeletionCandidate> S2_CPZ_SPIN_TUBE_RECREATE_CLASSES = List.of(
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic2.objects.CPZSpinTubeObjectInstance", GameId.S2));

    private static final List<MutableFieldCoverageCandidate> S2_CPZ_SPIN_TUBE_RECREATE_MUTABLE_FIELDS =
            List.of(new MutableFieldCoverageCandidate(
                    "com.openggf.game.sonic2.objects.CPZSpinTubeObjectInstance",
                    "collisionDistance"));

    private static final List<CodecDeletionCandidate> S2_MTZ_LONG_PLATFORM_RECREATE_CLASSES = List.of(
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic2.objects.MTZLongPlatformObjectInstance", GameId.S2));

    private static final List<CodecDeletionCandidate> S2_COLLAPSING_PLATFORM_RECREATE_CLASSES = List.of(
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic2.objects.CollapsingPlatformObjectInstance", GameId.S2));

    private static final List<MutableFieldCoverageCandidate> S2_COLLAPSING_PLATFORM_RECREATE_MUTABLE_FIELDS =
            List.of(new MutableFieldCoverageCandidate(
                    "com.openggf.game.sonic2.objects.CollapsingPlatformObjectInstance",
                    "hFlip", "vFlip"));

    private static final List<CodecDeletionCandidate> S2_COLLAPSING_PLATFORM_FRAGMENT_RECREATE_CLASSES = List.of(
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic2.objects.CollapsingPlatformObjectInstance$CollapsingPlatformFragmentInstance",
                    GameId.S2));

    private static final List<MutableFieldCoverageCandidate> S2_COLLAPSING_PLATFORM_FRAGMENT_RECREATE_MUTABLE_FIELDS =
            List.of(new MutableFieldCoverageCandidate(
                    "com.openggf.game.sonic2.objects.CollapsingPlatformObjectInstance$CollapsingPlatformFragmentInstance",
                    "fragmentIndex", "hFlip", "vFlip", "priority", "x"));

    private static final List<CodecDeletionCandidate> CPZ_GRAPH_BATCH_A_DELETED_CODECS = List.of(
            new CodecDeletionCandidate(CPZBossContainer.class.getName(), GameId.S2),
            new CodecDeletionCandidate(CPZBossFlame.class.getName(), GameId.S2),
            new CodecDeletionCandidate(CPZBossGunk.class.getName(), GameId.S2),
            new CodecDeletionCandidate(CPZBossPipe.class.getName(), GameId.S2),
            new CodecDeletionCandidate(CPZBossPump.class.getName(), GameId.S2),
            new CodecDeletionCandidate(CPZBossRobotnik.class.getName(), GameId.S2));

    private static final List<CodecDeletionCandidate> CPZ_GRAPH_BATCH_B_DELETED_CODECS = List.of(
            new CodecDeletionCandidate(CPZBossContainerExtend.class.getName(), GameId.S2),
            new CodecDeletionCandidate(CPZBossContainerFloor.class.getName(), GameId.S2),
            new CodecDeletionCandidate(CPZBossDripper.class.getName(), GameId.S2),
            new CodecDeletionCandidate(CPZBossPipePump.class.getName(), GameId.S2),
            new CodecDeletionCandidate(CPZBossPipeSegment.class.getName(), GameId.S2));

    private static final List<CodecDeletionCandidate> S2_BOSS_GRAPH_PARENT_DELETED_CODECS = List.of(
            new CodecDeletionCandidate(Sonic2CPZBossInstance.class.getName(), GameId.S2),
            new CodecDeletionCandidate(Sonic2EHZBossInstance.class.getName(), GameId.S2),
            new CodecDeletionCandidate(Sonic2HTZBossInstance.class.getName(), GameId.S2));

    private static final List<CodecDeletionCandidate> S2_BOSS_CONTROLLER_RECREATE_CLASSES = List.of(
            new CodecDeletionCandidate(Sonic2CNZBossInstance.class.getName(), GameId.S2),
            new CodecDeletionCandidate(Sonic2MCZBossInstance.class.getName(), GameId.S2));

    private static final List<CodecDeletionCandidate> AIZ_MINIBOSS_GRAPH_DELETED_CODECS = List.of(
            new CodecDeletionCandidate(AizMinibossBodyChild.class.getName(), GameId.S3K),
            new CodecDeletionCandidate(AizMinibossArmChild.class.getName(), GameId.S3K),
            new CodecDeletionCandidate(AizMinibossFlameBarrelChild.class.getName(), GameId.S3K),
            new CodecDeletionCandidate(AizMinibossFlameChild.class.getName(), GameId.S3K),
            new CodecDeletionCandidate(AizMinibossBarrelShotChild.class.getName(), GameId.S3K),
            new CodecDeletionCandidate(AizMinibossBarrelShotFlareChild.class.getName(), GameId.S3K));

    private static final List<CodecDeletionCandidate> AIZ_MINIBOSS_PARENT_DELETED_CODECS = List.of(
            new CodecDeletionCandidate(AizMinibossInstance.class.getName(), GameId.S3K));

    private static final List<CodecDeletionCandidate> AIZ_END_BOSS_GRAPH_DELETED_CODECS = List.of(
            new CodecDeletionCandidate(AizEndBossInstance.class.getName(), GameId.S3K),
            new CodecDeletionCandidate(AizEndBossShipChild.class.getName(), GameId.S3K),
            new CodecDeletionCandidate(AizEndBossFlameColumnChild.class.getName(), GameId.S3K),
            new CodecDeletionCandidate(AizEndBossArmChild.class.getName(), GameId.S3K),
            new CodecDeletionCandidate(AizEndBossPropellerChild.class.getName(), GameId.S3K),
            new CodecDeletionCandidate(AizEndBossFlameChild.class.getName(), GameId.S3K),
            new CodecDeletionCandidate(AizEndBossBombChild.class.getName(), GameId.S3K),
            new CodecDeletionCandidate(AizEndBossSmokeChild.class.getName(), GameId.S3K));

    private static final List<CodecDeletionCandidate> AIZ_SHIP_BOMB_GRAPH_DELETED_CODECS = List.of(
            new CodecDeletionCandidate(AizShipBombInstance.class.getName(), GameId.S3K));

    private static final List<CodecDeletionCandidate> AIZ_SPIKED_LOG_GRAPH_DELETED_CODECS = List.of(
            new CodecDeletionCandidate(AizSpikedLogObjectInstance.class.getName(), GameId.S3K),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic3k.objects.AizSpikedLogObjectInstance$SpikedLogCollisionChild",
                    GameId.S3K));

    private static final List<CodecDeletionCandidate> AIZ_FALLING_LOG_GRAPH_DELETED_CODECS = List.of(
            new CodecDeletionCandidate(AizFallingLogObjectInstance.class.getName(), GameId.S3K),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic3k.objects.AizFallingLogObjectInstance$FallingLogChild",
                    GameId.S3K),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic3k.objects.AizFallingLogObjectInstance$SplashChild",
                    GameId.S3K));

    private static final List<CodecDeletionCandidate> AIZ_DISAPPEARING_FLOOR_GRAPH_DELETED_CODECS = List.of(
            new CodecDeletionCandidate(AizDisappearingFloorObjectInstance.class.getName(), GameId.S3K),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic3k.objects.AizDisappearingFloorObjectInstance$BorderChild",
                    GameId.S3K));

    private static final List<CodecDeletionCandidate> AIZ_COLLAPSING_LOG_BRIDGE_GRAPH_DELETED_CODECS = List.of(
            new CodecDeletionCandidate(AizCollapsingLogBridgeObjectInstance.class.getName(), GameId.S3K),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic3k.objects.AizCollapsingLogBridgeObjectInstance$CollapsingLogSegment",
                    GameId.S3K));

    private static final List<CodecDeletionCandidate> AIZ_FLIPPING_BRIDGE_RECREATE_CLASSES = List.of(
            new CodecDeletionCandidate(AizFlippingBridgeObjectInstance.class.getName(), GameId.S3K));

    private static final List<CodecDeletionCandidate> AIZ1_STATIC_SCENERY_RECREATE_CLASSES = List.of(
            new CodecDeletionCandidate(Aiz1TreeObjectInstance.class.getName(), GameId.S3K),
            new CodecDeletionCandidate(Aiz1ZiplinePegObjectInstance.class.getName(), GameId.S3K));

    private static final List<CodecDeletionCandidate> S3K_DECORATIVE_RECREATE_CLASSES = List.of(
            new CodecDeletionCandidate(AizForegroundPlantInstance.class.getName(), GameId.S3K),
            new CodecDeletionCandidate(AnimatedStillSpriteInstance.class.getName(), GameId.S3K));

    private static final List<CodecDeletionCandidate> S3K_STATIC_HAZARD_RECREATE_CLASSES = List.of(
            new CodecDeletionCandidate(StillSpriteInstance.class.getName(), GameId.S3K),
            new CodecDeletionCandidate(Sonic3kSpikeObjectInstance.class.getName(), GameId.S3K));

    private static final List<CodecDeletionCandidate> S3K_BUTTON_PATH_SWAP_RECREATE_CLASSES = List.of(
            new CodecDeletionCandidate(Sonic3kButtonObjectInstance.class.getName(), GameId.S3K),
            new CodecDeletionCandidate(Sonic3kPathSwapObjectInstance.class.getName(), GameId.S3K));

    private static final List<CodecDeletionCandidate> S3K_UTILITY_RECREATE_CLASSES = List.of(
            new CodecDeletionCandidate(S3kHiddenMonitorInstance.class.getName(), GameId.S3K),
            new CodecDeletionCandidate(SinkingMudObjectInstance.class.getName(), GameId.S3K),
            new CodecDeletionCandidate(Sonic3kSSEntryRingObjectInstance.class.getName(), GameId.S3K));

    private static final List<CodecDeletionCandidate> S3K_CNZ_LOCAL_MECHANICS_RECREATE_CLASSES = List.of(
            new CodecDeletionCandidate(CnzBalloonInstance.class.getName(), GameId.S3K),
            new CodecDeletionCandidate(CnzRisingPlatformInstance.class.getName(), GameId.S3K),
            new CodecDeletionCandidate(CnzLightBulbInstance.class.getName(), GameId.S3K),
            new CodecDeletionCandidate(CnzBarberPoleObjectInstance.class.getName(), GameId.S3K));

    private static final List<CodecDeletionCandidate> S3K_CNZ_MECHANISM_RECREATE_CLASSES = List.of(
            new CodecDeletionCandidate(CnzGiantWheelInstance.class.getName(), GameId.S3K),
            new CodecDeletionCandidate(CnzHoverFanInstance.class.getName(), GameId.S3K),
            new CodecDeletionCandidate(CnzSpiralTubeInstance.class.getName(), GameId.S3K),
            new CodecDeletionCandidate(CnzTeleporterBeamInstance.class.getName(), GameId.S3K),
            new CodecDeletionCandidate(CnzTeleporterInstance.class.getName(), GameId.S3K),
            new CodecDeletionCandidate(CnzTrapDoorInstance.class.getName(), GameId.S3K),
            new CodecDeletionCandidate(CnzTriangleBumperObjectInstance.class.getName(), GameId.S3K),
            new CodecDeletionCandidate(CnzVacuumTubeInstance.class.getName(), GameId.S3K),
            new CodecDeletionCandidate(CnzWaterLevelButtonInstance.class.getName(), GameId.S3K));

    private static final List<CodecDeletionCandidate> S3K_ICZ_ICE_OBJECT_RECREATE_CLASSES = List.of(
            new CodecDeletionCandidate(IczBreakableWallObjectInstance.class.getName(), GameId.S3K),
            new CodecDeletionCandidate(IczHarmfulIceObjectInstance.class.getName(), GameId.S3K),
            new CodecDeletionCandidate(IczIceBlockObjectInstance.class.getName(), GameId.S3K),
            new CodecDeletionCandidate(IczIceCubeObjectInstance.class.getName(), GameId.S3K));

    private static final List<CodecDeletionCandidate> S3K_HCZ_MECHANISM_RECREATE_CLASSES = List.of(
            new CodecDeletionCandidate(HCZBlockObjectInstance.class.getName(), GameId.S3K),
            new CodecDeletionCandidate(HCZConveyorSpikeObjectInstance.class.getName(), GameId.S3K),
            new CodecDeletionCandidate(HCZLargeFanObjectInstance.class.getName(), GameId.S3K),
            new CodecDeletionCandidate(HCZSpinningColumnObjectInstance.class.getName(), GameId.S3K),
            new CodecDeletionCandidate(HCZWaterSplashObjectInstance.class.getName(), GameId.S3K));

    private static final List<CodecDeletionCandidate> S3K_ICZ_PLATFORM_HAZARD_RECREATE_CLASSES = List.of(
            new CodecDeletionCandidate(IczPathFollowPlatformObjectInstance.class.getName(), GameId.S3K),
            new CodecDeletionCandidate(IczSwingingPlatformObjectInstance.class.getName(), GameId.S3K),
            new CodecDeletionCandidate(IczStalagtiteObjectInstance.class.getName(), GameId.S3K),
            new CodecDeletionCandidate(IczSnowPileObjectInstance.class.getName(), GameId.S3K));

    private static final List<CodecDeletionCandidate> S3K_UTILITY_MOTION_RECREATE_CLASSES = List.of(
            new CodecDeletionCandidate(AutomaticTunnelObjectInstance.class.getName(), GameId.S3K),
            new CodecDeletionCandidate(AutoSpinObjectInstance.class.getName(), GameId.S3K),
            new CodecDeletionCandidate(BubblerObjectInstance.class.getName(), GameId.S3K),
            new CodecDeletionCandidate(DoorObjectInstance.class.getName(), GameId.S3K));

    private static final List<CodecDeletionCandidate> S3K_PACHINKO_STANDALONE_RECREATE_CLASSES = List.of(
            new CodecDeletionCandidate(FloatingPlatformObjectInstance.class.getName(), GameId.S3K),
            new CodecDeletionCandidate(GumballTriangleBumperObjectInstance.class.getName(), GameId.S3K),
            new CodecDeletionCandidate(PachinkoBumperObjectInstance.class.getName(), GameId.S3K),
            new CodecDeletionCandidate(PachinkoMagnetOrbObjectInstance.class.getName(), GameId.S3K),
            new CodecDeletionCandidate(PachinkoPlatformObjectInstance.class.getName(), GameId.S3K),
            new CodecDeletionCandidate(PachinkoTriangleBumperObjectInstance.class.getName(), GameId.S3K));

    private static final List<CodecDeletionCandidate> S3K_CONTROLLER_RECREATE_CLASSES = List.of(
            new CodecDeletionCandidate(Sonic3kTwistedRampObjectInstance.class.getName(), GameId.S3K),
            new CodecDeletionCandidate(UpdraftObjectInstance.class.getName(), GameId.S3K),
            new CodecDeletionCandidate(HCZTwistingLoopObjectInstance.class.getName(), GameId.S3K),
            new CodecDeletionCandidate(MGZTwistingLoopObjectInstance.class.getName(), GameId.S3K),
            new CodecDeletionCandidate(MhzTwistedVineObjectInstance.class.getName(), GameId.S3K));

    private static final List<CodecDeletionCandidate> STANDALONE_CONTROLLER_RECREATE_CLASSES = List.of(
            new CodecDeletionCandidate(NutObjectInstance.class.getName(), GameId.S2),
            new CodecDeletionCandidate(LbzInvisibleBarrierInstance.class.getName(), GameId.S3K),
            new CodecDeletionCandidate(LbzAlarmObjectInstance.class.getName(), GameId.S3K),
            new CodecDeletionCandidate(HCZWaterDropObjectInstance.class.getName(), GameId.S3K),
            new CodecDeletionCandidate(MhzPollenSpawnerInstance.class.getName(), GameId.S3K),
            new CodecDeletionCandidate(Mgz2PostBossPaletteFadeController.class.getName(), GameId.S3K),
            new CodecDeletionCandidate(Mgz2PostBossSequenceController.class.getName(), GameId.S3K));

    private static final List<CodecDeletionCandidate> HCZ_END_BOSS_GRAPH_DELETED_CODECS = List.of(
            new CodecDeletionCandidate(HczEndBossRobotnikShip.class.getName(), GameId.S3K),
            new CodecDeletionCandidate(HczEndBossTurbine.class.getName(), GameId.S3K),
            new CodecDeletionCandidate(HczEndBossBlade.class.getName(), GameId.S3K),
            new CodecDeletionCandidate(HczEndBossBladeWaterChute.class.getName(), GameId.S3K),
            new CodecDeletionCandidate(HczEndBossBladeSplash.class.getName(), GameId.S3K),
            new CodecDeletionCandidate(HczEndBossWaterColumn.class.getName(), GameId.S3K));

    private static final List<CodecDeletionCandidate> AIZ_INTRO_GRAPH_DELETED_CODECS = List.of(
            new CodecDeletionCandidate(AizIntroPlaneChild.class.getName(), GameId.S3K),
            new CodecDeletionCandidate(AizIntroWaveChild.class.getName(), GameId.S3K),
            new CodecDeletionCandidate(AizIntroEmeraldGlowChild.class.getName(), GameId.S3K));

    private static final List<CodecDeletionCandidate> AIZ_INTRO_PARENT_DELETED_CODECS = List.of(
            new CodecDeletionCandidate(AizPlaneIntroInstance.class.getName(), GameId.S3K));

    private static final List<CodecDeletionCandidate> S2_BADNIK_CHILD_GRAPH_DELETED_CODECS = List.of(
            new CodecDeletionCandidate(GrounderRockProjectile.class.getName(), GameId.S2),
            new CodecDeletionCandidate(GrounderWallInstance.class.getName(), GameId.S2),
            new CodecDeletionCandidate(BalkiryJetObjectInstance.class.getName(), GameId.S2),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic2.objects.badniks.AquisBadnikInstance$AquisWingChild",
                    GameId.S2),
            new CodecDeletionCandidate(RexonHeadObjectInstance.class.getName(), GameId.S2),
            new CodecDeletionCandidate(ShellcrackerClawInstance.class.getName(), GameId.S2),
            new CodecDeletionCandidate(SlicerPincerInstance.class.getName(), GameId.S2),
            new CodecDeletionCandidate(SolFireballObjectInstance.class.getName(), GameId.S2));

    private static final List<CodecDeletionCandidate> S2_BADNIK_PARENT_GRAPH_DELETED_CODECS = List.of(
            new CodecDeletionCandidate("com.openggf.game.sonic2.objects.badniks.AquisBadnikInstance", GameId.S2),
            new CodecDeletionCandidate("com.openggf.game.sonic2.objects.badniks.BalkiryBadnikInstance", GameId.S2),
            new CodecDeletionCandidate("com.openggf.game.sonic2.objects.badniks.RexonBadnikInstance", GameId.S2),
            new CodecDeletionCandidate("com.openggf.game.sonic2.objects.badniks.ShellcrackerBadnikInstance", GameId.S2),
            new CodecDeletionCandidate("com.openggf.game.sonic2.objects.badniks.SlicerBadnikInstance", GameId.S2),
            new CodecDeletionCandidate("com.openggf.game.sonic2.objects.badniks.SolBadnikInstance", GameId.S2),
            new CodecDeletionCandidate("com.openggf.game.sonic2.objects.badniks.TurtloidBadnikInstance", GameId.S2));

    private static final List<CodecDeletionCandidate> CHECKPOINT_STARPOST_GRAPH_DELETED_CODECS = List.of(
            new CodecDeletionCandidate(Sonic1LamppostTwirlInstance.class.getName(), GameId.S1),
            new CodecDeletionCandidate(CheckpointDongleInstance.class.getName(), GameId.S2),
            new CodecDeletionCandidate(CheckpointStarInstance.class.getName(), GameId.S2),
            new CodecDeletionCandidate(Sonic3kStarPostStarChild.class.getName(), GameId.S3K),
            new CodecDeletionCandidate(Sonic3kStarPostBonusStarChild.class.getName(), GameId.S3K));

    private static final List<CodecDeletionCandidate> S1_MZ_GLASS_REFLECTION_GRAPH_DELETED_CODECS = List.of(
            new CodecDeletionCandidate(Sonic1GlassBlockObjectInstance.class.getName(), GameId.S1),
            new CodecDeletionCandidate(Sonic1GlassReflectionInstance.class.getName(), GameId.S1));

    private static final List<CodecDeletionCandidate> S1_RING_FLASH_GRAPH_DELETED_CODECS = List.of(
            new CodecDeletionCandidate(Sonic1RingFlashObjectInstance.class.getName(), GameId.S1));

    private static final List<CodecDeletionCandidate> S1_BOSS_GRAPH_PARENT_DELETED_CODECS = List.of(
            new CodecDeletionCandidate(Sonic1FZBossInstance.class.getName(), GameId.S1),
            new CodecDeletionCandidate(Sonic1GHZBossInstance.class.getName(), GameId.S1),
            new CodecDeletionCandidate(Sonic1SLZBossInstance.class.getName(), GameId.S1),
            new CodecDeletionCandidate(Sonic1SYZBossInstance.class.getName(), GameId.S1));

    private static final List<CodecDeletionCandidate> S1_SYZ_BOSS_SPIKE_GRAPH_BATCH246_RECREATE_CLASSES =
            List.of(new CodecDeletionCandidate(
                    "com.openggf.game.sonic1.objects.bosses.SYZBossSpike",
                    GameId.S1));

    private static final List<CodecDeletionCandidate> S1_FZ_BOSS_GRAPH_DELETED_CODECS = List.of(
            new CodecDeletionCandidate(FZCylinder.class.getName(), GameId.S1),
            new CodecDeletionCandidate(FZPlasmaLauncher.class.getName(), GameId.S1),
            new CodecDeletionCandidate(FZPlasmaBall.class.getName(), GameId.S1));

    private static final List<CodecDeletionCandidate> S1_GHZ_BOSS_GRAPH_DELETED_CODECS = List.of(
            new CodecDeletionCandidate(GHZBossWreckingBall.class.getName(), GameId.S1));

    private static final List<CodecDeletionCandidate> S1_SLZ_BOSS_SPIKEBALL_GRAPH_DELETED_CODECS = List.of(
            new CodecDeletionCandidate(Sonic1SLZBossSpikeball.class.getName(), GameId.S1));

    private static final List<CodecDeletionCandidate> S1_ENDING_SONIC_GRAPH_DELETED_CODECS = List.of(
            new CodecDeletionCandidate(Sonic1EndingSonicObjectInstance.class.getName(), GameId.S1));

    private static final List<CodecDeletionCandidate> S1_GRASS_FIRE_GRAPH_DELETED_CODECS = List.of(
            new CodecDeletionCandidate(Sonic1LargeGrassyPlatformObjectInstance.class.getName(), GameId.S1),
            new CodecDeletionCandidate(Sonic1GrassFireObjectInstance.class.getName(), GameId.S1));

    private static final List<CodecDeletionCandidate> S1_BADNIK_GRAPH_DELETED_CODECS = List.of(
            new CodecDeletionCandidate(Sonic1CaterkillerBadnikInstance.class.getName(), GameId.S1),
            new CodecDeletionCandidate(Sonic1OrbinautBadnikInstance.class.getName(), GameId.S1),
            new CodecDeletionCandidate(Sonic1BombFuseInstance.class.getName(), GameId.S1),
            new CodecDeletionCandidate(Sonic1CaterkillerBodyInstance.class.getName(), GameId.S1),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic1.objects.badniks.Sonic1OrbinautBadnikInstance"
                            + "$OrbSpikeObjectInstance",
                    GameId.S1));

    private static final List<CodecDeletionCandidate> S2_WFZ_BOSS_GRAPH_DELETED_CODECS = List.of(
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic2.objects.bosses.Sonic2WFZBossInstance$WFZFloatingPlatform",
                    GameId.S2),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic2.objects.bosses.Sonic2WFZBossInstance$WFZLaserWall",
                    GameId.S2),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic2.objects.bosses.Sonic2WFZBossInstance$WFZPlatformHurt",
                    GameId.S2),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic2.objects.bosses.Sonic2WFZBossInstance$WFZPlatformReleaser",
                    GameId.S2),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic2.objects.bosses.Sonic2WFZBossInstance$WFZLaserShooter",
                    GameId.S2),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic2.objects.bosses.Sonic2WFZBossInstance$WFZLaser",
                    GameId.S2),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic2.objects.bosses.Sonic2WFZBossInstance$WFZRobotnik",
                    GameId.S2),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic2.objects.bosses.Sonic2WFZBossInstance$WFZRobotnikPlatform",
                    GameId.S2));

    private static final List<CodecDeletionCandidate> S2_WFZ_BOSS_PARENT_DELETED_CODECS = List.of(
            new CodecDeletionCandidate(Sonic2WFZBossInstance.class.getName(), GameId.S2));

    private static final List<CodecDeletionCandidate> S2_HTZ_BOSS_GRAPH_DELETED_CODECS = List.of(
            new CodecDeletionCandidate(HTZBossFlamethrower.class.getName(), GameId.S2),
            new CodecDeletionCandidate(HTZBossLavaBall.class.getName(), GameId.S2),
            new CodecDeletionCandidate("com.openggf.game.sonic2.objects.bosses.HTZBossSmokeParticle", GameId.S2));

    private static final List<CodecDeletionCandidate> S2_DEZ_BOMB_GRAPH_DELETED_CODECS = List.of(
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic2.objects.bosses.Sonic2DeathEggRobotInstance$BombChild",
                    GameId.S2));

    private static final List<CodecDeletionCandidate> S2_DEZ_EGGMAN_GRAPH_COVERED_CLASSES = List.of(
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic2.objects.bosses.Sonic2DEZEggmanInstance",
                    GameId.S2),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic2.objects.bosses.Sonic2DEZEggmanInstance$BarrierWall",
                    GameId.S2),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic2.objects.bosses.Sonic2DEZEggmanInstance$ExhaustPuff",
                    GameId.S2));

    private static final List<CodecDeletionCandidate> S1_SCRAP_EGGMAN_GRAPH_COVERED_CLASSES = List.of(
            new CodecDeletionCandidate(Sonic1ScrapEggmanInstance.class.getName(), GameId.S1),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic1.objects.bosses.Sonic1ScrapEggmanInstance$ScrapEggmanButton",
                    GameId.S1));

    private static final List<CodecDeletionCandidate> S1_GARGOYLE_FIREBALL_GRAPH_COVERED_CLASSES = List.of(
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic1.objects.Sonic1GargoyleObjectInstance$Fireball",
                    GameId.S1));

    private static final List<CodecDeletionCandidate> S1_JUNCTION_CHILD_RECREATE_CLASSES = List.of(
            new CodecDeletionCandidate(Sonic1JunctionObjectInstance.class.getName(), GameId.S1),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic1.objects.Sonic1JunctionObjectInstance$Sonic1JunctionChildInstance",
                    GameId.S1));

    private static final List<CodecDeletionCandidate> S1_LAVA_WALL_GRAPH_RECREATE_CLASSES = List.of(
            new CodecDeletionCandidate(Sonic1LavaWallObjectInstance.class.getName(), GameId.S1));

    private static final List<CodecDeletionCandidate> S1_LAVA_GEYSER_GRAPH_RECREATE_CLASSES = List.of(
            new CodecDeletionCandidate(Sonic1LavaGeyserMakerObjectInstance.class.getName(), GameId.S1),
            new CodecDeletionCandidate(Sonic1LavaGeyserObjectInstance.class.getName(), GameId.S1));

    private static final List<CodecDeletionCandidate> S1_RUNTIME_SPAWN_RECREATE_CLASSES = List.of(
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic1.objects.Sonic1BubblesObjectInstance",
                    GameId.S1),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic1.objects.Sonic1BumperObjectInstance",
                    GameId.S1),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic1.objects.Sonic1RunningDiscObjectInstance",
                    GameId.S1),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic1.objects.Sonic1TeleporterObjectInstance",
                    GameId.S1));

    private static final List<CodecDeletionCandidate> S1_SCALAR_SPAWN_RECREATE_CLASSES = List.of(
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic1.objects.Sonic1AnimalsObjectInstance",
                    GameId.S1),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic1.objects.Sonic1BigSpikedBallObjectInstance",
                    GameId.S1),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic1.objects.Sonic1ButtonObjectInstance",
                    GameId.S1),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic1.objects.Sonic1ConveyorBeltObjectInstance",
                    GameId.S1),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic1.objects.Sonic1EdgeWallObjectInstance",
                    GameId.S1),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic1.objects.Sonic1ElectrocuterObjectInstance",
                    GameId.S1),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic1.objects.Sonic1FanObjectInstance",
                    GameId.S1),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic1.objects.Sonic1FlamethrowerObjectInstance",
                    GameId.S1),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic1.objects.Sonic1GargoyleObjectInstance",
                    GameId.S1),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic1.objects.Sonic1GirderBlockObjectInstance",
                    GameId.S1),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic1.objects.Sonic1LZConveyorObjectInstance",
                    GameId.S1),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic1.objects.Sonic1LabyrinthBlockObjectInstance",
                    GameId.S1),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic1.objects.Sonic1ElevatorObjectInstance",
                    GameId.S1),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic1.objects.Sonic1FloatingBlockObjectInstance",
                    GameId.S1),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic1.objects.Sonic1LamppostObjectInstance",
                    GameId.S1),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic1.objects.Sonic1LavaBallMakerObjectInstance",
                    GameId.S1),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic1.objects.Sonic1LavaTagObjectInstance",
                    GameId.S1),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic1.objects.Sonic1MovingBlockObjectInstance",
                    GameId.S1),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic1.objects.Sonic1LavaBallObjectInstance",
                    GameId.S1),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic1.objects.Sonic1PlatformObjectInstance",
                    GameId.S1),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic1.objects.Sonic1SawObjectInstance",
                    GameId.S1),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic1.objects.Sonic1SceneryObjectInstance",
                    GameId.S1),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic1.objects.Sonic1SmallDoorObjectInstance",
                    GameId.S1),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic1.objects.Sonic1SpikeObjectInstance",
                    GameId.S1),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic1.objects.Sonic1SpringObjectInstance",
                    GameId.S1),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic1.objects.Sonic1SpinConveyorObjectInstance",
                    GameId.S1),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic1.objects.Sonic1SpinPlatformObjectInstance",
                    GameId.S1),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic1.objects.Sonic1StaircaseObjectInstance",
                    GameId.S1),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic1.objects.Sonic1VanishingPlatformObjectInstance",
                    GameId.S1),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic1.objects.Sonic1BreakableWallObjectInstance",
                    GameId.S1),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic1.objects.Sonic1ChainedStomperObjectInstance",
                    GameId.S1),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic1.objects.Sonic1CirclingPlatformObjectInstance",
                    GameId.S1),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic1.objects.Sonic1CollapsingLedgeObjectInstance",
                    GameId.S1),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic1.objects.Sonic1MonitorObjectInstance",
                    GameId.S1),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic1.objects.Sonic1SmashBlockObjectInstance",
                    GameId.S1),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic1.objects.Sonic1PushBlockObjectInstance",
                    GameId.S1),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic1.objects.Sonic1SpikedPoleHelixObjectInstance",
                    GameId.S1),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic1.objects.badniks.Sonic1ChopperBadnikInstance",
                    GameId.S1),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic1.objects.badniks.Sonic1JawsBadnikInstance",
                    GameId.S1),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic1.objects.badniks.Sonic1NewtronBadnikInstance",
                    GameId.S1));

    private static final List<CodecDeletionCandidate> S1_COLLAPSING_FRAGMENT_RECREATE_CLASSES = List.of(
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic1.objects.Sonic1CollapsingFloorObjectInstance$CollapsingFloorFragmentInstance",
                    GameId.S1),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic1.objects.Sonic1CollapsingLedgeObjectInstance$CollapsingLedgeFragmentInstance",
                    GameId.S1));

    private static final List<CodecDeletionCandidate> S1_DESTRUCTION_FRAGMENT_RECREATE_CLASSES = List.of(
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic1.objects.Sonic1BreakableWallObjectInstance$WallFragmentInstance",
                    GameId.S1),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic1.objects.Sonic1SmashBlockObjectInstance$SmashBlockFragmentInstance",
                    GameId.S1));

    private static final List<CodecDeletionCandidate> S1_SPIKED_BALL_CHAIN_RECREATE_CLASSES = List.of(
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic1.objects.Sonic1SpikedBallChainObjectInstance$ChainChild",
                    GameId.S1));

    private static final List<CodecDeletionCandidate> S1_FALSE_FLOOR_FRAGMENT_RECREATE_CLASSES = List.of(
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic1.objects.bosses.Sonic1FalseFloorInstance$FalseFloorFragment",
                    GameId.S1));

    private static final List<CodecDeletionCandidate> S1_BOSS_CONTROLLER_RECREATE_CLASSES = List.of(
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic1.objects.bosses.Sonic1FalseFloorInstance",
                    GameId.S1),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic1.objects.bosses.Sonic1LZBossInstance",
                    GameId.S1),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic1.objects.bosses.Sonic1MZBossInstance",
                    GameId.S1));

    private static final List<CodecDeletionCandidate> S1_EFFECT_SCALAR_RECREATE_CLASSES = List.of(
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic1.objects.Sonic1SplashObjectInstance",
                    GameId.S1),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic1.objects.badniks.Sonic1MotobugSmokeInstance",
                    GameId.S1));

    private static final List<MutableFieldCoverageCandidate> S1_EFFECT_SCALAR_RECREATE_MUTABLE_FIELDS =
            List.of(
                    new MutableFieldCoverageCandidate(
                            "com.openggf.game.sonic1.objects.Sonic1SplashObjectInstance",
                            "posX"),
                    new MutableFieldCoverageCandidate(
                            "com.openggf.game.sonic1.objects.badniks.Sonic1MotobugSmokeInstance",
                            "facingLeft", "posX", "posY"));

    private static final List<CodecDeletionCandidate> S1_BOSS_FIRE_SCALAR_RECREATE_CLASSES = List.of(
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic1.objects.bosses.Sonic1BossFireInstance",
                    GameId.S1));

    private static final List<CodecDeletionCandidate> S2_SCALAR_NAMED_RECREATE_CLASSES = List.of(
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic2.objects.ArrowShooterObjectInstance",
                    GameId.S2),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic2.objects.BarrierObjectInstance",
                    GameId.S2));

    private static final List<CodecDeletionCandidate> S2_CNZ_SCALAR_RECREATE_CLASSES = List.of(
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic2.objects.BumperObjectInstance",
                    GameId.S2),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic2.objects.HexBumperObjectInstance",
                    GameId.S2),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic2.objects.BonusBlockObjectInstance",
                    GameId.S2),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic2.objects.BubbleGeneratorObjectInstance",
                    GameId.S2),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic2.objects.CloudObjectInstance",
                    GameId.S2));

    private static final List<MutableFieldCoverageCandidate> S2_CNZ_SCALAR_RECREATE_MUTABLE_FIELDS =
            List.of(new MutableFieldCoverageCandidate(
                    "com.openggf.game.sonic2.objects.CloudObjectInstance",
                    "mappingFrame", "xVelocity"));

    private static final List<CodecDeletionCandidate> S2_UTILITY_SCALAR_RECREATE_CLASSES = List.of(
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic2.objects.ButtonObjectInstance",
                    GameId.S2),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic2.objects.CluckerBaseObjectInstance",
                    GameId.S2),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic2.objects.FanObjectInstance",
                    GameId.S2),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic2.objects.LavaMarkerObjectInstance",
                    GameId.S2),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic2.objects.BridgeStakeObjectInstance",
                    GameId.S2),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic2.objects.EHZWaterfallObjectInstance",
                    GameId.S2));

    private static final List<MutableFieldCoverageCandidate> S2_UTILITY_SCALAR_RECREATE_MUTABLE_FIELDS =
            List.of(
                    new MutableFieldCoverageCandidate(
                            "com.openggf.game.sonic2.objects.ButtonObjectInstance",
                            "adjustedY", "switchId", "triggerBit"),
                    new MutableFieldCoverageCandidate(
                            "com.openggf.game.sonic2.objects.CluckerBaseObjectInstance",
                            "xFlipped"),
                    new MutableFieldCoverageCandidate(
                            "com.openggf.game.sonic2.objects.FanObjectInstance",
                            "alwaysOn", "isVertical", "reverseDirection", "xFlipped"),
                    new MutableFieldCoverageCandidate(
                            "com.openggf.game.sonic2.objects.LavaMarkerObjectInstance",
                            "collisionFlags", "subtypeIndex"));

    private static final List<CodecDeletionCandidate> S2_PLATFORM_VISUAL_RECREATE_CLASSES = List.of(
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic2.objects.CPZPylonObjectInstance",
                    GameId.S2),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic2.objects.LaserObjectInstance",
                    GameId.S2),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic2.objects.WallTurretObjectInstance",
                    GameId.S2),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic2.objects.MTZLavaBubbleObjectInstance",
                    GameId.S2),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic2.objects.CPZPlatformObjectInstance",
                    GameId.S2),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic2.objects.MTZPlatformObjectInstance",
                    GameId.S2));

    private static final List<CodecDeletionCandidate> S2_MECHANISM_SCALAR_RECREATE_CLASSES = List.of(
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic2.objects.ARZPlatformObjectInstance",
                    GameId.S2),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic2.objects.ARZRotPformsObjectInstance",
                    GameId.S2),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic2.objects.BlueBallsObjectInstance",
                    GameId.S2),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic2.objects.CPZStaircaseObjectInstance",
                    GameId.S2),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic2.objects.HPropellerObjectInstance",
                    GameId.S2),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic2.objects.RisingPillarObjectInstance",
                    GameId.S2),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic2.objects.SmallMetalPformObjectInstance",
                    GameId.S2),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic2.objects.Sonic2LayerSwitcherObjectInstance",
                    GameId.S2));

    private static final List<MutableFieldCoverageCandidate> S2_MECHANISM_SCALAR_RECREATE_MUTABLE_FIELDS =
            List.of(
                    new MutableFieldCoverageCandidate(
                            "com.openggf.game.sonic2.objects.ARZRotPformsObjectInstance",
                            "initialX", "initialY", "speed"),
                    new MutableFieldCoverageCandidate(
                            "com.openggf.game.sonic2.objects.BlueBallsObjectInstance",
                            "arcMotion", "initialX", "initialY", "siblingCount", "xFlipped"),
                    new MutableFieldCoverageCandidate(
                            "com.openggf.game.sonic2.objects.CPZStaircaseObjectInstance",
                            "baseX", "baseY", "xFlip"),
                    new MutableFieldCoverageCandidate(
                            "com.openggf.game.sonic2.objects.HPropellerObjectInstance",
                            "routineMode"));

    private static final List<CodecDeletionCandidate> S2_BOX_SOLID_SCALAR_RECREATE_CLASSES = List.of(
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic2.objects.BridgeObjectInstance",
                    GameId.S2),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic2.objects.CheckpointObjectInstance",
                    GameId.S2),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic2.objects.ForcedSpinObjectInstance",
                    GameId.S2),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic2.objects.PipeExitSpringObjectInstance",
                    GameId.S2),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic2.objects.PlatformObjectInstance",
                    GameId.S2),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic2.objects.SpringObjectInstance",
                    GameId.S2),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic2.objects.BreakableBlockObjectInstance",
                    GameId.S2),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic2.objects.CNZBigBlockObjectInstance",
                    GameId.S2),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic2.objects.CNZRectBlocksObjectInstance",
                    GameId.S2),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic2.objects.ElevatorObjectInstance",
                    GameId.S2));

    private static final List<MutableFieldCoverageCandidate> S2_BOX_SOLID_SCALAR_RECREATE_MUTABLE_FIELDS =
            List.of(
                    new MutableFieldCoverageCandidate(
                            "com.openggf.game.sonic2.objects.BridgeObjectInstance",
                            "halfWidth", "halfHeight", "r", "g", "b", "highPriority", "logCount"),
                    new MutableFieldCoverageCandidate(
                            "com.openggf.game.sonic2.objects.CheckpointObjectInstance",
                            "halfWidth", "halfHeight", "r", "g", "b", "highPriority",
                            "checkpointIndex", "cameraLockFlag"),
                    new MutableFieldCoverageCandidate(
                            "com.openggf.game.sonic2.objects.ForcedSpinObjectInstance",
                            "halfWidth", "halfHeight", "r", "g", "b", "highPriority",
                            "verticalMode", "triggerWidth", "xFlipped"),
                    new MutableFieldCoverageCandidate(
                            "com.openggf.game.sonic2.objects.PipeExitSpringObjectInstance",
                            "halfWidth", "halfHeight", "r", "g", "b", "highPriority", "fullStrength"),
                    new MutableFieldCoverageCandidate(
                            "com.openggf.game.sonic2.objects.PlatformObjectInstance",
                            "halfWidth", "halfHeight", "r", "g", "b", "highPriority"),
                    new MutableFieldCoverageCandidate(
                            "com.openggf.game.sonic2.objects.SpringObjectInstance",
                            "halfWidth", "halfHeight", "r", "g", "b", "highPriority",
                            "redSpring", "idleAnimId", "triggeredAnimId"),
                    new MutableFieldCoverageCandidate(
                            "com.openggf.game.sonic2.objects.BreakableBlockObjectInstance",
                            "halfWidth", "halfHeight", "r", "g", "b", "highPriority"),
                    new MutableFieldCoverageCandidate(
                            "com.openggf.game.sonic2.objects.CNZBigBlockObjectInstance",
                            "halfWidth", "halfHeight", "r", "g", "b", "highPriority",
                            "targetX", "targetY", "moveType"),
                    new MutableFieldCoverageCandidate(
                            "com.openggf.game.sonic2.objects.CNZRectBlocksObjectInstance",
                            "halfWidth", "halfHeight", "r", "g", "b", "highPriority", "baseX", "baseY"),
                    new MutableFieldCoverageCandidate(
                            "com.openggf.game.sonic2.objects.ElevatorObjectInstance",
                            "halfWidth", "halfHeight", "r", "g", "b", "highPriority"));

    private static final List<CodecDeletionCandidate> S2_TRIGGER_MOTION_SCALAR_RECREATE_CLASSES = List.of(
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic2.objects.CNZConveyorBeltObjectInstance",
                    GameId.S2),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic2.objects.FloorSpikeObjectInstance",
                    GameId.S2),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic2.objects.HTZLiftObjectInstance",
                    GameId.S2),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic2.objects.LargeRotPformObjectInstance",
                    GameId.S2),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic2.objects.LateralCannonObjectInstance",
                    GameId.S2),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic2.objects.LeavesGeneratorObjectInstance",
                    GameId.S2),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic2.objects.MCZBrickObjectInstance",
                    GameId.S2),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic2.objects.MCZBridgeObjectInstance",
                    GameId.S2),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic2.objects.MCZDrawbridgeObjectInstance",
                    GameId.S2),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic2.objects.MTZSpringWallObjectInstance",
                    GameId.S2),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic2.objects.MTZTwinStompersObjectInstance",
                    GameId.S2),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic2.objects.RisingLavaObjectInstance",
                    GameId.S2),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic2.objects.SlidingSpikesObjectInstance",
                    GameId.S2),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic2.objects.SpeedBoosterObjectInstance",
                    GameId.S2),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic2.objects.SpikeObjectInstance",
                    GameId.S2),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic2.objects.StomperObjectInstance",
                    GameId.S2),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic2.objects.TippingFloorObjectInstance",
                    GameId.S2),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic2.objects.VineSwitchObjectInstance",
                    GameId.S2),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic2.objects.VPropellerObjectInstance",
                    GameId.S2),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic2.objects.WFZPalSwitcherObjectInstance",
                    GameId.S2),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic2.objects.WFZShipFireObjectInstance",
                    GameId.S2));

    private static final List<MutableFieldCoverageCandidate> S2_TRIGGER_MOTION_SCALAR_RECREATE_MUTABLE_FIELDS =
            List.of(
                    new MutableFieldCoverageCandidate(
                            "com.openggf.game.sonic2.objects.CNZConveyorBeltObjectInstance",
                            "widthPixels", "heightPixels", "velocityX"),
                    new MutableFieldCoverageCandidate(
                            "com.openggf.game.sonic2.objects.FloorSpikeObjectInstance",
                            "initialX", "initialY"),
                    new MutableFieldCoverageCandidate(
                            "com.openggf.game.sonic2.objects.HTZLiftObjectInstance",
                            "baseX", "baseY", "flippedX"),
                    new MutableFieldCoverageCandidate(
                            "com.openggf.game.sonic2.objects.LargeRotPformObjectInstance",
                            "baseX", "baseY", "widthPixels", "yRadius", "mappingFrame",
                            "isIndent", "mirrorMotion", "rotateMotion", "priority"),
                    new MutableFieldCoverageCandidate(
                            "com.openggf.game.sonic2.objects.LateralCannonObjectInstance",
                            "x", "y", "phaseMask"),
                    new MutableFieldCoverageCandidate(
                            "com.openggf.game.sonic2.objects.LeavesGeneratorObjectInstance",
                            "collisionFlags", "collisionProperty", "touchPhaseFrame"),
                    new MutableFieldCoverageCandidate(
                            "com.openggf.game.sonic2.objects.MCZBrickObjectInstance",
                            "mode", "initialX", "initialY", "chainCount", "speed"),
                    new MutableFieldCoverageCandidate(
                            "com.openggf.game.sonic2.objects.MCZBridgeObjectInstance",
                            "switchId"),
                    new MutableFieldCoverageCandidate(
                            "com.openggf.game.sonic2.objects.MCZDrawbridgeObjectInstance",
                            "switchId", "originalX", "originalY", "direction", "xFlipped", "yFlipped"),
                    new MutableFieldCoverageCandidate(
                            "com.openggf.game.sonic2.objects.MTZSpringWallObjectInstance",
                            "yRadius", "xFlip"),
                    new MutableFieldCoverageCandidate(
                            "com.openggf.game.sonic2.objects.MTZTwinStompersObjectInstance",
                            "widthPixels", "collisionYRadius", "maxTravel", "mappingFrame", "xFlip",
                            "renderYRadius", "baseX", "baseY", "moveMode"),
                    new MutableFieldCoverageCandidate(
                            "com.openggf.game.sonic2.objects.RisingLavaObjectInstance",
                            "subtype", "widthPixels", "baseY", "baseX"),
                    new MutableFieldCoverageCandidate(
                            "com.openggf.game.sonic2.objects.SlidingSpikesObjectInstance",
                            "baseX", "hFlip"),
                    new MutableFieldCoverageCandidate(
                            "com.openggf.game.sonic2.objects.SpeedBoosterObjectInstance",
                            "boostSpeed"),
                    new MutableFieldCoverageCandidate(
                            "com.openggf.game.sonic2.objects.SpikeObjectInstance",
                            "baseX", "baseY"),
                    new MutableFieldCoverageCandidate(
                            "com.openggf.game.sonic2.objects.StomperObjectInstance",
                            "baseY"),
                    new MutableFieldCoverageCandidate(
                            "com.openggf.game.sonic2.objects.TippingFloorObjectInstance",
                            "delay", "durationInitial"),
                    new MutableFieldCoverageCandidate(
                            "com.openggf.game.sonic2.objects.VineSwitchObjectInstance",
                            "switchId"),
                    new MutableFieldCoverageCandidate(
                            "com.openggf.game.sonic2.objects.VPropellerObjectInstance",
                            "currentX", "currentY", "collisionFlags", "yFlipped"),
                    new MutableFieldCoverageCandidate(
                            "com.openggf.game.sonic2.objects.WFZPalSwitcherObjectInstance",
                            "halfWidth", "halfHeight", "r", "g", "b", "highPriority",
                            "triggerHalfHeight", "xFlipped"),
                    new MutableFieldCoverageCandidate(
                            "com.openggf.game.sonic2.objects.WFZShipFireObjectInstance",
                            "initialX", "currentY"));

    private static final List<CodecDeletionCandidate> S2_BADNIK_SCALAR_RECREATE_CLASSES = List.of(
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic2.objects.badniks.AsteronBadnikInstance",
                    GameId.S2),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic2.objects.badniks.ChopChopBadnikInstance",
                    GameId.S2),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic2.objects.badniks.CluckerBadnikInstance",
                    GameId.S2),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic2.objects.badniks.CoconutsBadnikInstance",
                    GameId.S2),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic2.objects.badniks.CrawltonBadnikInstance",
                    GameId.S2),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic2.objects.badniks.CrawlBadnikInstance",
                    GameId.S2),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic2.objects.badniks.FlasherBadnikInstance",
                    GameId.S2),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic2.objects.badniks.MasherBadnikInstance",
                    GameId.S2),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic2.objects.badniks.NebulaBadnikInstance",
                    GameId.S2),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic2.objects.badniks.OctusBadnikInstance",
                    GameId.S2),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic2.objects.badniks.SpikerBadnikInstance",
                    GameId.S2),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic2.objects.badniks.SpinyBadnikInstance",
                    GameId.S2),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic2.objects.badniks.SpinyOnWallBadnikInstance",
                    GameId.S2),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic2.objects.badniks.WFZStickBadnikInstance",
                    GameId.S2),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic2.objects.badniks.WFZUnknownBadnikInstance",
                    GameId.S2),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic2.objects.badniks.WhispBadnikInstance",
                    GameId.S2));

    private static final List<MutableFieldCoverageCandidate> S2_BADNIK_SCALAR_RECREATE_MUTABLE_FIELDS =
            List.of(
                    new MutableFieldCoverageCandidate(
                            "com.openggf.game.sonic2.objects.badniks.ChopChopBadnikInstance",
                            "startX"),
                    new MutableFieldCoverageCandidate(
                            "com.openggf.game.sonic2.objects.badniks.OctusBadnikInstance",
                            "startY", "xFlip"),
                    new MutableFieldCoverageCandidate(
                            "com.openggf.game.sonic2.objects.badniks.SpikerBadnikInstance",
                            "yFlipFlag"));

    private static final List<CodecDeletionCandidate> S2_MISC_SCALAR_RECREATE_CLASSES = List.of(
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic2.objects.HtzFireShooterObjectInstance",
                    GameId.S2),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic2.objects.SignpostObjectInstance",
                    GameId.S2),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic2.objects.SteamSpringObjectInstance",
                    GameId.S2),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic2.objects.TornadoSmokeObjectInstance",
                    GameId.S2),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic2.objects.WFZWheelObjectInstance",
                    GameId.S2));

    private static final List<MutableFieldCoverageCandidate> S2_MISC_SCALAR_RECREATE_MUTABLE_FIELDS =
            List.of(
                    new MutableFieldCoverageCandidate(
                            "com.openggf.game.sonic2.objects.HtzFireShooterObjectInstance",
                            "currentX", "currentY", "projectileVel", "reloadTime"),
                    new MutableFieldCoverageCandidate(
                            "com.openggf.game.sonic2.objects.SteamSpringObjectInstance",
                            "baseY"));

    private static final List<CodecDeletionCandidate> S2_MECHANISM_TAIL_RECREATE_CLASSES = List.of(
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic2.objects.MonitorObjectInstance",
                    GameId.S2),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic2.objects.SpikyBlockObjectInstance",
                    GameId.S2),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic2.objects.SpringboardObjectInstance",
                    GameId.S2),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic2.objects.badniks.BuzzerBadnikInstance",
                    GameId.S2));

    private static final List<MutableFieldCoverageCandidate> S2_MECHANISM_TAIL_RECREATE_MUTABLE_FIELDS =
            List.of(
                    new MutableFieldCoverageCandidate(
                            "com.openggf.game.sonic2.objects.MonitorObjectInstance",
                            "type"));

    private static final List<CodecDeletionCandidate> S2_MECHANISM_FRAGMENT_PARENT_RECREATE_CLASSES = List.of(
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic2.objects.BreakablePlatingObjectInstance",
                    GameId.S2),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic2.objects.SmashableGroundObjectInstance",
                    GameId.S2),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic2.objects.TiltingPlatformObjectInstance",
                    GameId.S2));

    private static final List<MutableFieldCoverageCandidate> S2_MECHANISM_FRAGMENT_PARENT_RECREATE_MUTABLE_FIELDS =
            List.of(
                    new MutableFieldCoverageCandidate(
                            "com.openggf.game.sonic2.objects.BreakablePlatingObjectInstance",
                            "x", "y"),
                    new MutableFieldCoverageCandidate(
                            "com.openggf.game.sonic2.objects.TiltingPlatformObjectInstance",
                            "behaviorType"));

    private static final List<CodecDeletionCandidate> S2_DEBRIS_FRAGMENT_RECREATE_CLASSES = List.of(
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic2.objects.RisingPillarObjectInstance$RisingPillarDebrisInstance",
                    GameId.S2),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic2.objects.SmashableGroundObjectInstance$SmashableGroundFragmentInstance",
                    GameId.S2));

    private static final List<MutableFieldCoverageCandidate> S2_DEBRIS_FRAGMENT_RECREATE_MUTABLE_FIELDS =
            List.of(
                    new MutableFieldCoverageCandidate(
                            "com.openggf.game.sonic2.objects.RisingPillarObjectInstance$RisingPillarDebrisInstance",
                            "currentX", "currentY", "subX", "subY", "velX", "velY", "delay",
                            "mappingFrame", "pieceIndex"),
                    new MutableFieldCoverageCandidate(
                            "com.openggf.game.sonic2.objects.SmashableGroundObjectInstance$SmashableGroundFragmentInstance",
                            "currentX", "currentY", "subX", "subY", "velX", "velY",
                            "frameIndex", "pieceIndex"));

    private static final List<CodecDeletionCandidate> S2_INTERACTION_SCALAR_RECREATE_CLASSES = List.of(
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic2.objects.GrabObjectInstance",
                    GameId.S2),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic2.objects.SwingingPformObjectInstance",
                    GameId.S2),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic2.objects.MovingVineObjectInstance",
                    GameId.S2),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic2.objects.RivetObjectInstance",
                    GameId.S2),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic2.objects.PointPokeyObjectInstance",
                    GameId.S2));

    private static final List<MutableFieldCoverageCandidate> S2_INTERACTION_SCALAR_RECREATE_MUTABLE_FIELDS =
            List.of();

    private static final List<CodecDeletionCandidate> S2_MCZ_ROT_PFORMS_GRAPH_RECREATE_CLASSES = List.of(
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic2.objects.MCZRotPformsObjectInstance",
                    GameId.S2));

    private static final List<CodecDeletionCandidate> S2_SIDEWAYS_PFORM_GRAPH_RECREATE_CLASSES = List.of(
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic2.objects.SidewaysPformObjectInstance",
                    GameId.S2));

    private static final List<CodecDeletionCandidate> S2_FALLING_PILLAR_GRAPH_RECREATE_CLASSES = List.of(
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic2.objects.FallingPillarObjectInstance",
                    GameId.S2));

    private static final List<CodecDeletionCandidate> S2_SWINGING_PLATFORM_GRAPH_RECREATE_CLASSES = List.of(
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic2.objects.SwingingPlatformObjectInstance",
                    GameId.S2),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic2.objects.SwingingPlatformObjectInstance$SwingingPlatformDisplayChild",
                    GameId.S2));

    private static final List<CodecDeletionCandidate> S2_SWINGING_PLATFORM_GRAPH_RECREATE_ROUND_TRIP_CLASSES =
            List.of(
                    new CodecDeletionCandidate(
                            "com.openggf.game.sonic2.objects.SwingingPlatformObjectInstance",
                            GameId.S2));

    private static final List<CodecDeletionCandidate> S2_COG_GRAPH_RECREATE_CLASSES = List.of(
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic2.objects.CogObjectInstance",
                    GameId.S2),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic2.objects.CogObjectInstance$CogSlotChildInstance",
                    GameId.S2));

    private static final List<CodecDeletionCandidate> S2_COG_GRAPH_RECREATE_ROUND_TRIP_CLASSES =
            List.of(
                    new CodecDeletionCandidate(
                            "com.openggf.game.sonic2.objects.CogObjectInstance",
                            GameId.S2));

    private static final List<CodecDeletionCandidate> S2_LAUNCHER_GRAPH_RECREATE_CLASSES = List.of(
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic2.objects.LauncherBallObjectInstance",
                    GameId.S2),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic2.objects.LauncherSpringObjectInstance",
                    GameId.S2),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic2.objects.OOZLauncherObjectInstance",
                    GameId.S2),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic2.objects.OOZLauncherObjectInstance$LauncherFragmentInstance",
                    GameId.S2));

    private static final List<CodecDeletionCandidate> S2_FLIPPER_GRAPH_RECREATE_CLASSES = List.of(
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic2.objects.FlipperObjectInstance",
                    GameId.S2));

    private static final List<CodecDeletionCandidate> S2_SPIRAL_GRAPH_RECREATE_CLASSES = List.of(
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic2.objects.SpiralObjectInstance",
                    GameId.S2));

    private static final List<MutableFieldCoverageCandidate> S2_TORNADO_GRAPH_RECREATE_MUTABLE_FIELDS =
            List.of(
                    new MutableFieldCoverageCandidate(
                            "com.openggf.game.sonic2.objects.TornadoObjectInstance",
                            "subtype"));

    private static final List<MutableFieldCoverageCandidate> S2_MCZ_ROT_PFORMS_GRAPH_RECREATE_MUTABLE_FIELDS =
            List.of(
                    new MutableFieldCoverageCandidate(
                            "com.openggf.game.sonic2.objects.MCZRotPformsObjectInstance",
                            "baseX", "baseY", "xFlip", "yFlip"));

    private static final List<MutableFieldCoverageCandidate> S2_FALLING_PILLAR_GRAPH_RECREATE_MUTABLE_FIELDS =
            List.of(
                    new MutableFieldCoverageCandidate(
                            "com.openggf.game.sonic2.objects.FallingPillarObjectInstance",
                            "isChild"));

    private static final List<MutableFieldCoverageCandidate> S2_SWINGING_PLATFORM_GRAPH_RECREATE_MUTABLE_FIELDS =
            List.of(
                    new MutableFieldCoverageCandidate(
                            "com.openggf.game.sonic2.objects.SwingingPlatformObjectInstance",
                            "baseX", "baseY", "zoneConfig", "behaviorMode", "chainCount", "displayOnly"));

    private static final List<MutableFieldCoverageCandidate> S2_COG_GRAPH_RECREATE_MUTABLE_FIELDS =
            List.of(
                    new MutableFieldCoverageCandidate(
                            "com.openggf.game.sonic2.objects.CogObjectInstance",
                            "baseX", "baseY", "ccw"));

    private static final List<MutableFieldCoverageCandidate> S1_SCALAR_SPAWN_RECREATE_MUTABLE_FIELDS =
            List.of(
                    new MutableFieldCoverageCandidate(
                            "com.openggf.game.sonic1.objects.Sonic1AnimalsObjectInstance",
                            "pointsValue", "subtype"),
                    new MutableFieldCoverageCandidate(
                            "com.openggf.game.sonic1.objects.Sonic1BigSpikedBallObjectInstance",
                            "flipped", "moveType", "origX", "origY", "speed"),
                    new MutableFieldCoverageCandidate(
                            "com.openggf.game.sonic1.objects.Sonic1ButtonObjectInstance",
                            "adjustedY", "blockPressable", "flashMode", "switchBit", "switchIndex"),
                    new MutableFieldCoverageCandidate(
                            "com.openggf.game.sonic1.objects.Sonic1ConveyorBeltObjectInstance",
                            "convSpeed", "convWidth"),
                    new MutableFieldCoverageCandidate(
                            "com.openggf.game.sonic1.objects.Sonic1EdgeWallObjectInstance",
                            "frameIndex", "solid"),
                    new MutableFieldCoverageCandidate(
                            "com.openggf.game.sonic1.objects.Sonic1ElectrocuterObjectInstance",
                            "frequencyMask"),
                    new MutableFieldCoverageCandidate(
                            "com.openggf.game.sonic1.objects.Sonic1FanObjectInstance",
                            "alwaysOn", "facingRight", "reverseDirection"),
                    new MutableFieldCoverageCandidate(
                            "com.openggf.game.sonic1.objects.Sonic1FlamethrowerObjectInstance",
                            "dangerFrame", "flamingDuration", "isValve", "pauseDuration"),
                    new MutableFieldCoverageCandidate(
                            "com.openggf.game.sonic1.objects.Sonic1GargoyleObjectInstance",
                            "facingRight", "spitDelay"),
                    new MutableFieldCoverageCandidate(
                            "com.openggf.game.sonic1.objects.Sonic1GirderBlockObjectInstance",
                            "origX", "origY"),
                    new MutableFieldCoverageCandidate(
                            "com.openggf.game.sonic1.objects.Sonic1LargeGrassyPlatformObjectInstance",
                            "baseX", "baseY", "invertOscillation", "mappingFrame", "moveType", "platformWidth"),
                    new MutableFieldCoverageCandidate(
                            "com.openggf.game.sonic1.objects.Sonic1LZConveyorObjectInstance",
                            "mode"),
                    new MutableFieldCoverageCandidate(
                            "com.openggf.game.sonic1.objects.Sonic1LabyrinthBlockObjectInstance",
                            "halfHeight", "halfWidth", "mappingFrame", "origX"),
                    new MutableFieldCoverageCandidate(
                            "com.openggf.game.sonic1.objects.Sonic1ElevatorObjectInstance",
                            "halfWidth", "isSpawner", "origX", "origY"),
                    new MutableFieldCoverageCandidate(
                            "com.openggf.game.sonic1.objects.Sonic1FloatingBlockObjectInstance",
                            "halfHeight", "halfWidth", "isLZ", "mappingFrame", "origX", "origY", "zoneIndex"),
                    new MutableFieldCoverageCandidate(
                            "com.openggf.game.sonic1.objects.Sonic1LamppostObjectInstance",
                            "cameraLockFlag", "checkpointIndex"),
                    new MutableFieldCoverageCandidate(
                            "com.openggf.game.sonic1.objects.Sonic1LavaBallMakerObjectInstance",
                            "ballSubtype", "rateIndex", "spawnDelay"),
                    new MutableFieldCoverageCandidate(
                            "com.openggf.game.sonic1.objects.Sonic1LavaTagObjectInstance",
                            "collisionFlags", "subtypeIndex"),
                    new MutableFieldCoverageCandidate(
                            "com.openggf.game.sonic1.objects.Sonic1MovingBlockObjectInstance",
                            "activeWidth", "mappingFrame"),
                    new MutableFieldCoverageCandidate(
                            "com.openggf.game.sonic1.objects.Sonic1LavaBallObjectInstance",
                            "bossDroppedVariant", "isHorizontal", "originY", "priorityBucket"),
                    new MutableFieldCoverageCandidate(
                            "com.openggf.game.sonic1.objects.Sonic1PlatformObjectInstance",
                            "baseX", "baseY"),
                    new MutableFieldCoverageCandidate(
                            "com.openggf.game.sonic1.objects.Sonic1SawObjectInstance",
                            "sawType", "xFlipped"),
                    new MutableFieldCoverageCandidate(
                            "com.openggf.game.sonic1.objects.Sonic1SceneryObjectInstance",
                            "frameIndex"),
                    new MutableFieldCoverageCandidate(
                            "com.openggf.game.sonic1.objects.Sonic1SmallDoorObjectInstance",
                            "openFromRight"),
                    new MutableFieldCoverageCandidate(
                            "com.openggf.game.sonic1.objects.Sonic1SpikeObjectInstance",
                            "actWidth", "baseX", "baseY", "frameIndex", "movementType"),
                    new MutableFieldCoverageCandidate(
                            "com.openggf.game.sonic1.objects.Sonic1SpringObjectInstance",
                            "springType", "strength", "yellow"),
                    new MutableFieldCoverageCandidate(
                            "com.openggf.game.sonic1.objects.Sonic1SpinConveyorObjectInstance",
                            "mode"),
                    new MutableFieldCoverageCandidate(
                            "com.openggf.game.sonic1.objects.Sonic1SpinPlatformObjectInstance",
                            "frameCounterMask", "isSpinner", "spinTimelen"),
                    new MutableFieldCoverageCandidate(
                            "com.openggf.game.sonic1.objects.Sonic1StaircaseObjectInstance",
                            "baseX", "baseY", "xFlip"),
                    new MutableFieldCoverageCandidate(
                            "com.openggf.game.sonic1.objects.Sonic1VanishingPlatformObjectInstance",
                            "phaseMask", "phaseOffset", "timerLength"),
                    new MutableFieldCoverageCandidate(
                            "com.openggf.game.sonic1.objects.Sonic1BreakableWallObjectInstance",
                            "frameIndex"),
                    new MutableFieldCoverageCandidate(
                            "com.openggf.game.sonic1.objects.Sonic1ChainedStomperObjectInstance",
                            "blockActiveWidth", "blockFrame", "ceilingY", "chainBaseY",
                            "maxFallDistance", "origY", "spikesHaveCollision"),
                    new MutableFieldCoverageCandidate(
                            "com.openggf.game.sonic1.objects.Sonic1CirclingPlatformObjectInstance",
                            "negateBoth", "origX", "origY", "rotated", "type04"),
                    new MutableFieldCoverageCandidate(
                            "com.openggf.game.sonic1.objects.Sonic1CollapsingFloorObjectInstance",
                            "subtype"),
                    new MutableFieldCoverageCandidate(
                            "com.openggf.game.sonic1.objects.Sonic1CollapsingLedgeObjectInstance",
                            "mappingFrame", "subtype"),
                    new MutableFieldCoverageCandidate(
                            "com.openggf.game.sonic1.objects.Sonic1CollapsingFloorObjectInstance$CollapsingFloorFragmentInstance",
                            "artKey", "hFlip", "pieceIndex", "priority", "smashFrameIndex", "x"),
                    new MutableFieldCoverageCandidate(
                            "com.openggf.game.sonic1.objects.Sonic1CollapsingLedgeObjectInstance$CollapsingLedgeFragmentInstance",
                            "hFlip", "pieceIndex", "priority", "smashFrameIndex", "x"),
                    new MutableFieldCoverageCandidate(
                            "com.openggf.game.sonic1.objects.Sonic1MonitorObjectInstance",
                            "type"),
                    new MutableFieldCoverageCandidate(
                            "com.openggf.game.sonic1.objects.Sonic1MonitorPowerUpObjectInstance",
                            "subtype"),
                    new MutableFieldCoverageCandidate(
                            "com.openggf.game.sonic1.objects.Sonic1SmashBlockObjectInstance",
                            "frameIndex"),
                    new MutableFieldCoverageCandidate(
                            "com.openggf.game.sonic1.objects.Sonic1PushBlockObjectInstance",
                            "activeWidth", "frameIndex", "spawnX", "spawnY"),
                    new MutableFieldCoverageCandidate(
                            "com.openggf.game.sonic1.objects.Sonic1SpikedBallChainObjectInstance",
                            "elementCount", "isLZ", "origX", "origY", "speed"),
                    new MutableFieldCoverageCandidate(
                            "com.openggf.game.sonic1.objects.Sonic1SpikedPoleHelixObjectInstance",
                            "parentIndex", "spikeCount", "spikeY"),
                    new MutableFieldCoverageCandidate(
                            "com.openggf.game.sonic1.objects.Sonic1StomperDoorObjectInstance",
                            "actWidth", "bgRender", "height", "isSbz3", "mappingFrame",
                            "moveDistance", "origY", "switchIndex", "xFlipped"),
                    new MutableFieldCoverageCandidate(
                            "com.openggf.game.sonic1.objects.Sonic1ExplosionItemObjectInstance",
                            "pointsAllocatedBeforeAnimal", "pointsValue"),
                    new MutableFieldCoverageCandidate(
                            "com.openggf.level.objects.ExplosionObjectInstance",
                            "pointsAllocatedBeforeAnimal", "pointsValue"),
                    new MutableFieldCoverageCandidate(
                            "com.openggf.game.sonic1.objects.Sonic1SwingingPlatformObjectInstance",
                            "baseX", "baseY", "chainCount"),
                    new MutableFieldCoverageCandidate(
                            Sonic1LamppostTwirlInstance.class.getName(),
                            "centerX", "centerY"),
                    new MutableFieldCoverageCandidate(
                            "com.openggf.game.sonic1.objects.badniks.Sonic1BuzzBomberMissileDissolveInstance",
                            "currentX", "currentY"),
                    new MutableFieldCoverageCandidate(
                            "com.openggf.game.sonic1.objects.badniks.Sonic1CrabmeatProjectileInstance",
                            "collisionSizeIndex", "gravity", "offScreenMargin"),
                    new MutableFieldCoverageCandidate(
                            "com.openggf.game.sonic1.objects.badniks.Sonic1NewtronMissileInstance",
                            "collisionSizeIndex", "gravity", "offScreenMargin"),
                    new MutableFieldCoverageCandidate(
                            "com.openggf.level.objects.AbstractProjectileInstance",
                            "collisionSizeIndex", "gravity", "offScreenMargin"),
                    new MutableFieldCoverageCandidate(
                            "com.openggf.game.sonic1.objects.badniks.Sonic1ChopperBadnikInstance",
                            "origY"),
                    new MutableFieldCoverageCandidate(
                            "com.openggf.game.sonic1.objects.badniks.Sonic1JawsBadnikInstance",
                            "turnTimeDelay"),
                    new MutableFieldCoverageCandidate(
                            "com.openggf.game.sonic1.objects.badniks.Sonic1NewtronBadnikInstance",
                            "isType1"));

    private static final List<CodecDeletionCandidate> SEESAW_GRAPH_DELETED_CODECS = List.of(
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic1.objects.Sonic1SeesawObjectInstance",
                    GameId.S1),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic1.objects.Sonic1SeesawBallObjectInstance",
                    GameId.S1),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic2.objects.SeesawObjectInstance",
                    GameId.S2),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic2.objects.SeesawBallObjectInstance",
                    GameId.S2));

    private static final List<CodecDeletionCandidate> S3K_BADNIK_CHILD_GRAPH_DELETED_CODECS = List.of(
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic3k.objects.badniks.DragonflyBadnikInstance$LinkedBodyChild",
                    GameId.S3K),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic3k.objects.badniks.SpikerBadnikInstance$SpikerTopSpikeChild",
                    GameId.S3K),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic3k.objects.badniks.TurboSpikerBadnikInstance$TurboSpikerShellChild",
                    GameId.S3K));

    private static final List<CodecDeletionCandidate> S3K_DRAGONFLY_GRAPH_BATCH219_RECREATE_CLASSES = List.of(
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic3k.objects.badniks.DragonflyBadnikInstance",
                    GameId.S3K),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic3k.objects.badniks.DragonflyBadnikInstance$WingChild",
                    GameId.S3K));

    private static final List<CodecDeletionCandidate> S3K_SPIKER_GRAPH_BATCH220_RECREATE_CLASSES = List.of(
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic3k.objects.badniks.SpikerBadnikInstance",
                    GameId.S3K),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic3k.objects.badniks.SpikerBadnikInstance$SpikerSideLauncherChild",
                    GameId.S3K));

    private static final List<CodecDeletionCandidate> S3K_TURBO_SPIKER_GRAPH_BATCH221_RECREATE_CLASSES = List.of(
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic3k.objects.badniks.TurboSpikerBadnikInstance",
                    GameId.S3K),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic3k.objects.badniks.TurboSpikerBadnikInstance$TurboSpikerTrailEmitter",
                    GameId.S3K),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic3k.objects.badniks.TurboSpikerBadnikInstance$TurboSpikerWaterfallOverlayChild",
                    GameId.S3K));

    private static final List<CodecDeletionCandidate> S3K_MEGA_CHOPPER_BATCH222_RECREATE_CLASSES = List.of(
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic3k.objects.badniks.MegaChopperBadnikInstance",
                    GameId.S3K));

    private static final List<CodecDeletionCandidate> S3K_CLUCKOID_ARROW_BATCH223_RECREATE_CLASSES = List.of(
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic3k.objects.badniks.CluckoidBadnikInstance$ArrowChild",
                    GameId.S3K));

    private static final List<CodecDeletionCandidate> S3K_MUSHMEANIE_GRAPH_BATCH224_RECREATE_CLASSES = List.of(
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic3k.objects.badniks.MushmeanieBadnikInstance",
                    GameId.S3K),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic3k.objects.badniks.MushmeanieBadnikInstance$ShellChild",
                    GameId.S3K));

    private static final List<CodecDeletionCandidate> S3K_MANTIS_GRAPH_BATCH225_RECREATE_CLASSES = List.of(
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic3k.objects.badniks.MantisBadnikInstance",
                    GameId.S3K),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic3k.objects.badniks.MantisBadnikInstance$MantisChild",
                    GameId.S3K));

    private static final List<CodecDeletionCandidate> S3K_RIBOT_VISUAL_GRAPH_BATCH226_RECREATE_CLASSES = List.of(
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic3k.objects.badniks.RibotBadnikInstance$RibotVisualChild",
                    GameId.S3K));

    private static final List<CodecDeletionCandidate> S3K_SNALE_BLASTER_GRAPH_BATCH227_RECREATE_CLASSES = List.of(
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic3k.objects.badniks.SnaleBlasterBadnikInstance",
                    GameId.S3K),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic3k.objects.badniks.SnaleBlasterBadnikInstance$SnaleBlasterCoverChild",
                    GameId.S3K),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic3k.objects.badniks.SnaleBlasterBadnikInstance$SnaleBlasterShooterChild",
                    GameId.S3K));

    private static final List<CodecDeletionCandidate> S3K_CATERKILLER_JR_GRAPH_BATCH228_RECREATE_CLASSES = List.of(
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic3k.objects.badniks.CaterkillerJrHeadInstance",
                    GameId.S3K));

    private static final List<CodecDeletionCandidate> S3K_TUNNELBOT_GRAPH_BATCH229_RECREATE_CLASSES = List.of(
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic3k.objects.badniks.TunnelbotBadnikInstance",
                    GameId.S3K),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic3k.objects.badniks.TunnelbotBadnikInstance$TunnelbotArm",
                    GameId.S3K),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic3k.objects.badniks.TunnelbotBadnikInstance$TunnelbotDebris",
                    GameId.S3K));

    private static final List<CodecDeletionCandidate> S3K_COLLAPSING_BRIDGE_BATCH230_RECREATE_CLASSES = List.of(
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic3k.objects.CollapsingBridgeObjectInstance",
                    GameId.S3K),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic3k.objects.CollapsingBridgeObjectInstance$BridgeFragment",
                    GameId.S3K),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic3k.objects.CollapsingBridgeObjectInstance$MgzStompDebris",
                    GameId.S3K));

    private static final List<CodecDeletionCandidate> S3K_TENSION_BRIDGE_BATCH231_RECREATE_CLASSES = List.of(
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic3k.objects.TensionBridgeObjectInstance",
                    GameId.S3K),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic3k.objects.TensionBridgeObjectInstance$BridgeFragment",
                    GameId.S3K));

    private static final List<CodecDeletionCandidate> S3K_DESTRUCTIBLE_FRAGMENT_BATCH232_RECREATE_CLASSES = List.of(
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic3k.objects.BreakableWallObjectInstance$BreakableWallFragment",
                    GameId.S3K),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic3k.objects.CorkFloorObjectInstance$CorkFloorFragment",
                    GameId.S3K),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic3k.objects.Sonic3kCollapsingPlatformObjectInstance$CollapsingPlatformFragment",
                    GameId.S3K));

    private static final List<CodecDeletionCandidate> S3K_GUMBALL_GRAPH_BATCH233_RECREATE_CLASSES = List.of(
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic3k.objects.GumballMachineObjectInstance",
                    GameId.S3K),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic3k.objects.GumballMachineObjectInstance$DispenserChild",
                    GameId.S3K),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic3k.objects.GumballMachineObjectInstance$ContainerDisplayChild",
                    GameId.S3K),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic3k.objects.GumballMachineObjectInstance$EjectionEffectChild",
                    GameId.S3K),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic3k.objects.GumballMachineObjectInstance$PlatformChild",
                    GameId.S3K),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic3k.objects.GumballMachineObjectInstance$BodyOverlayChild",
                    GameId.S3K),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic3k.objects.GumballMachineObjectInstance$GumballSpringChild",
                    GameId.S3K));

    private static final List<CodecDeletionCandidate> S3K_PACHINKO_TRAP_GRAPH_BATCH234_RECREATE_CLASSES = List.of(
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic3k.objects.PachinkoEnergyTrapObjectInstance$EnergyTrapBeamChild",
                    GameId.S3K),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic3k.objects.PachinkoEnergyTrapObjectInstance$EnergyTrapColumnChild",
                    GameId.S3K));

    private static final List<CodecDeletionCandidate> S3K_ICZ_SUPPORT_GRAPH_BATCH235_RECREATE_CLASSES = List.of(
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic3k.objects.IczCrushingColumnObjectInstance$BottomDecoration",
                    GameId.S3K),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic3k.objects.IczIceSpikesObjectInstance",
                    GameId.S3K),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic3k.objects.IczTensionPlatformObjectInstance$SupportChild",
                    GameId.S3K));

    private static final List<CodecDeletionCandidate> S3K_ICZ_SNOW_GRAPH_BATCH236_RECREATE_CLASSES = List.of(
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic3k.objects.IczSnowPileObjectInstance$SnowdustParticle",
                    GameId.S3K),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic3k.objects.IczSnowboardIntroInstance",
                    GameId.S3K));

    private static final List<CodecDeletionCandidate> S3K_MGZ_MECHANISM_GRAPH_BATCH245_RECREATE_CLASSES =
            List.of(
                    new CodecDeletionCandidate(
                            "com.openggf.game.sonic3k.objects.MGZTopLauncherObjectInstance",
                            GameId.S3K),
                    new CodecDeletionCandidate(
                            "com.openggf.game.sonic3k.objects.MGZTopPlatformObjectInstance",
                            GameId.S3K),
                    new CodecDeletionCandidate(
                            "com.openggf.game.sonic3k.objects.PachinkoItemOrbObjectInstance",
                            GameId.S3K));

    private static final List<CodecDeletionCandidate> S3K_MINIBOSS_ROOT_GRAPH_BATCH247_RECREATE_CLASSES =
            List.of(
                    new CodecDeletionCandidate(CnzMinibossInstance.class.getName(), GameId.S3K),
                    new CodecDeletionCandidate(HczMinibossInstance.class.getName(), GameId.S3K),
                    new CodecDeletionCandidate(IczMinibossInstance.class.getName(), GameId.S3K),
                    new CodecDeletionCandidate(MgzMinibossInstance.class.getName(), GameId.S3K),
                    new CodecDeletionCandidate(MhzMinibossInstance.class.getName(), GameId.S3K));

    private static final List<CodecDeletionCandidate> SHARED_BOX_BATCH237_RECREATE_CLASSES = List.of(
            new CodecDeletionCandidate("com.openggf.level.objects.BoxObjectInstance", GameId.S2));

    private static final List<CodecDeletionCandidate> S3K_LBZ_TRIGGER_BRIDGE_BATCH238_RECREATE_CLASSES = List.of(
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic3k.objects.LbzTriggerBridgeInstance",
                    GameId.S3K));

    private static final List<CodecDeletionCandidate> S3K_AIZ_EMERALD_SCATTER_BATCH240_RECREATE_CLASSES = List.of(
            new CodecDeletionCandidate(AizEmeraldScatterInstance.class.getName(), GameId.S3K));

    private static final List<CodecDeletionCandidate> S3K_BADNIK_PARENT_RECREATE_CLASSES = List.of(
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic3k.objects.badniks.BatbotBadnikInstance",
                    GameId.S3K),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic3k.objects.badniks.BlastoidBadnikInstance",
                    GameId.S3K),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic3k.objects.badniks.BloominatorBadnikInstance",
                    GameId.S3K),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic3k.objects.badniks.BubblesBadnikInstance",
                    GameId.S3K),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic3k.objects.badniks.ButterdroidBadnikInstance",
                    GameId.S3K));

    private static final List<CodecDeletionCandidate> S3K_BADNIK_PARENT_BATCH87_RECREATE_CLASSES = List.of(
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic3k.objects.badniks.BuggernautBadnikInstance",
                    GameId.S3K),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic3k.objects.badniks.CluckoidBadnikInstance",
                    GameId.S3K),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic3k.objects.badniks.CorkeyBadnikInstance",
                    GameId.S3K),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic3k.objects.badniks.Flybot767BadnikInstance",
                    GameId.S3K),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic3k.objects.badniks.JawzBadnikInstance",
                    GameId.S3K),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic3k.objects.badniks.MadmoleBadnikInstance",
                    GameId.S3K));

    private static final List<CodecDeletionCandidate> S3K_BADNIK_PARENT_BATCH88_RECREATE_CLASSES = List.of(
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic3k.objects.badniks.MonkeyDudeBadnikInstance",
                    GameId.S3K),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic3k.objects.badniks.OrbinautBadnikInstance",
                    GameId.S3K),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic3k.objects.badniks.PenguinatorBadnikInstance",
                    GameId.S3K),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic3k.objects.badniks.PoindexterBadnikInstance",
                    GameId.S3K),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic3k.objects.badniks.RhinobotBadnikInstance",
                    GameId.S3K),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic3k.objects.badniks.RibotBadnikInstance",
                    GameId.S3K),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic3k.objects.badniks.SparkleBadnikInstance",
                    GameId.S3K),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic3k.objects.badniks.StarPointerBadnikInstance",
                    GameId.S3K));

    private static final List<CodecDeletionCandidate> S3K_OBJECT_PARENT_BATCH89_RECREATE_CLASSES = List.of(
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic3k.objects.LbzExplodingTriggerInstance",
                    GameId.S3K),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic3k.objects.LbzFlameThrowerObjectInstance",
                    GameId.S3K),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic3k.objects.LbzMovingPlatformObjectInstance",
                    GameId.S3K),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic3k.objects.LbzRollingDrumInstance",
                    GameId.S3K),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic3k.objects.MGZDashTriggerObjectInstance",
                    GameId.S3K),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic3k.objects.MGZLBZSmashingPillarObjectInstance",
                    GameId.S3K));

    private static final List<CodecDeletionCandidate> S3K_OBJECT_PARENT_BATCH90_RECREATE_CLASSES = List.of(
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic3k.objects.HCZSnakeBlocksObjectInstance",
                    GameId.S3K),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic3k.objects.HCZWaterWallObjectInstance",
                    GameId.S3K),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic3k.objects.LbzCupElevatorPoleInstance",
                    GameId.S3K),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic3k.objects.LbzFlameThrowerFlameInstance",
                    GameId.S3K),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic3k.objects.LbzPlayerLauncherInstance",
                    GameId.S3K),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic3k.objects.LbzRideGrappleInstance",
                    GameId.S3K),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic3k.objects.MGZMovingSpikePlatformObjectInstance",
                    GameId.S3K),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic3k.objects.MGZTriggerPlatformObjectInstance",
                    GameId.S3K));

    private static final List<CodecDeletionCandidate> S3K_OBJECT_PARENT_BATCH91_RECREATE_CLASSES = List.of(
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic3k.objects.AizGiantRideVineObjectInstance",
                    GameId.S3K),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic3k.objects.AizHollowTreeObjectInstance",
                    GameId.S3K),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic3k.objects.AizRideVineObjectInstance",
                    GameId.S3K),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic3k.objects.BreakableWallObjectInstance",
                    GameId.S3K),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic3k.objects.HCZBreakableBarObjectInstance",
                    GameId.S3K),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic3k.objects.MGZHeadTriggerObjectInstance",
                    GameId.S3K),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic3k.objects.MGZSwingingPlatformObjectInstance",
                    GameId.S3K),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic3k.objects.MGZSwingingSpikeBallObjectInstance",
                    GameId.S3K));

    private static final List<CodecDeletionCandidate> S3K_OBJECT_PARENT_BATCH92_RECREATE_CLASSES = List.of(
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic3k.objects.IczCrushingColumnObjectInstance",
                    GameId.S3K),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic3k.objects.IczSegmentColumnObjectInstance",
                    GameId.S3K),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic3k.objects.IczTensionPlatformObjectInstance",
                    GameId.S3K),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic3k.objects.Sonic3kCollapsingPlatformObjectInstance",
                    GameId.S3K),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic3k.objects.Sonic3kStarPostObjectInstance",
                    GameId.S3K));

    private static final List<CodecDeletionCandidate> S3K_ICZ_DEBRIS_BATCH93_RECREATE_CLASSES = List.of(
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic3k.objects.IczBreakableWallObjectInstance$IczBreakableWallDebris",
                    GameId.S3K),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic3k.objects.IczHarmfulIceObjectInstance$IceDebris",
                    GameId.S3K),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic3k.objects.IczIceCubeObjectInstance$IceCubeDebris",
                    GameId.S3K),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic3k.objects.IczSegmentColumnObjectInstance$BreakDebris",
                    GameId.S3K),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic3k.objects.IczStalagtiteObjectInstance$StalagtiteDebris",
                    GameId.S3K));

    private static final List<CodecDeletionCandidate> S3K_DYNAMIC_CHILD_BATCH94_RECREATE_CLASSES = List.of(
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic3k.objects.IczSnowPileObjectInstance$SnowPileDebris",
                    GameId.S3K),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic3k.objects.IczFreezerObjectInstance$IceDebris",
                    GameId.S3K),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic3k.objects.ClamerObjectInstance$ClamerAutoCloseProjectile",
                    GameId.S3K));

    private static final List<CodecDeletionCandidate> S3K_DYNAMIC_CHILD_BATCH95_RECREATE_CLASSES = List.of(
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic3k.objects.HCZBreakableBarObjectInstance$BreakableBarDebris",
                    GameId.S3K),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic3k.objects.badniks.CluckoidBadnikInstance$BreathDebrisChild",
                    GameId.S3K),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic3k.objects.badniks.PenguinatorBadnikInstance$PenguinatorSnowdustInstance",
                    GameId.S3K));

    private static final List<CodecDeletionCandidate> S3K_WATER_WALL_CHILD_BATCH96_RECREATE_CLASSES = List.of(
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic3k.objects.HCZWaterWallObjectInstance$WaterWallDebrisChild",
                    GameId.S3K),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic3k.objects.HCZWaterWallObjectInstance$WaterWallSprayChild",
                    GameId.S3K),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic3k.objects.HCZWaterWallObjectInstance$WaterWallSplashChild",
                    GameId.S3K));

    private static final List<CodecDeletionCandidate> S3K_PARTICLE_CHILD_BATCH97_RECREATE_CLASSES = List.of(
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic3k.objects.CnzCannonInstance$CannonLaunchPuff",
                    GameId.S3K),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic3k.objects.IczSnowboardIntroInstance$SnowboardDustInstance",
                    GameId.S3K),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic3k.objects.bosses.HczEndBossGeyserCutscene$GeyserDebrisChild",
                    GameId.S3K));

    private static final List<CodecDeletionCandidate> S3K_TURBO_SPIKER_PARTICLE_BATCH98_RECREATE_CLASSES = List.of(
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic3k.objects.badniks.TurboSpikerBadnikInstance$TurboSpikerAnimatedParticle",
                    GameId.S3K),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic3k.objects.badniks.TurboSpikerBadnikInstance$TurboSpikerShellDripParticle",
                    GameId.S3K),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic3k.objects.badniks.TurboSpikerBadnikInstance$TurboSpikerWaterSplashParticle",
                    GameId.S3K));

    private static final List<CodecDeletionCandidate> S3K_MHZ_TREE_CHIP_BATCH99_RECREATE_CLASSES = List.of(
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic3k.objects.MhzMinibossTreeInstance$MhzMinibossTreeChipInstance",
                    GameId.S3K));

    private static final List<CodecDeletionCandidate> S3K_MHZ2_LEAF_PARTICLE_BATCH100_RECREATE_CLASSES = List.of(
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic3k.objects.CutsceneKnucklesMhz2Instance$Mhz2KnucklesLeafParticle",
                    GameId.S3K));

    private static final List<CodecDeletionCandidate> S3K_ICZ_FROST_PUFF_BATCH101_RECREATE_CLASSES = List.of(
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic3k.objects.IczFreezerObjectInstance$FrostPuff",
                    GameId.S3K));

    private static final List<CodecDeletionCandidate> S3K_AIZ_DRAW_BRIDGE_BATCH102_RECREATE_CLASSES = List.of(
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic3k.objects.AizDrawBridgeObjectInstance",
                    GameId.S3K),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic3k.objects.AizDrawBridgeObjectInstance$FallingBridgeSegment",
                    GameId.S3K));

    private static final List<CodecDeletionCandidate> S3K_AIZ_LRZ_ROCK_BATCH218_RECREATE_CLASSES = List.of(
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic3k.objects.AizLrzRockObjectInstance",
                    GameId.S3K));

    private static final List<CodecDeletionCandidate> S3K_SPARKLE_CHILD_BATCH103_RECREATE_CLASSES = List.of(
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic3k.objects.badniks.SparkleBadnikInstance$SparkleLightningWarningChild",
                    GameId.S3K),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic3k.objects.badniks.SparkleBadnikInstance$SparkleProjectileChild",
                    GameId.S3K));

    private static final List<CodecDeletionCandidate> S3K_MHZ_SWING_BAR_BATCH104_RECREATE_CLASSES = List.of(
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic3k.objects.MhzSwingBarHorizontalObjectInstance",
                    GameId.S3K),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic3k.objects.MhzSwingBarVerticalObjectInstance",
                    GameId.S3K));

    private static final List<CodecDeletionCandidate> S3K_MHZ_MECHANISM_BATCH105_RECREATE_CLASSES = List.of(
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic3k.objects.MhzCurledVineObjectInstance",
                    GameId.S3K),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic3k.objects.MhzMushroomCapObjectInstance",
                    GameId.S3K),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic3k.objects.MhzMushroomPlatformObjectInstance",
                    GameId.S3K));

    private static final List<CodecDeletionCandidate> S3K_MECHANISM_BATCH106_RECREATE_CLASSES = List.of(
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic3k.objects.CnzWireCageObjectInstance",
                    GameId.S3K),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic3k.objects.GumballItemObjectInstance",
                    GameId.S3K),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic3k.objects.MhzMushroomCatapultObjectInstance",
                    GameId.S3K),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic3k.objects.Sonic3kSpringObjectInstance",
                    GameId.S3K));

    private static final List<CodecDeletionCandidate> S3K_LBZ_MECHANISM_BATCH107_RECREATE_CLASSES = List.of(
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic3k.objects.LbzMinibossBoxInstance",
                    GameId.S3K),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic3k.objects.LbzMinibossBoxKnuxInstance",
                    GameId.S3K));

    private static final List<CodecDeletionCandidate> S3K_MHZ_MGZ_MECHANISM_BATCH108_RECREATE_CLASSES = List.of(
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic3k.objects.MhzMinibossTreeInstance",
                    GameId.S3K),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic3k.objects.MhzShipPropellerInstance",
                    GameId.S3K),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic3k.objects.MgzEndBossDefeatDebrisChild",
                    GameId.S3K));

    private static final List<CodecDeletionCandidate> S3K_CORKEY_NOZZLE_GRAPH_BATCH109_RECREATE_CLASSES = List.of(
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic3k.objects.badniks.CorkeyBadnikInstance$CorkeyNozzleChild",
                    GameId.S3K));

    private static final List<CodecDeletionCandidate> S3K_SIGNPOST_STUB_GRAPH_DELETED_CODECS = List.of(
            new CodecDeletionCandidate(S3kSignpostStubChild.class.getName(), GameId.S3K));

    private static final List<CodecDeletionCandidate> S3K_LBZ1_CUTSCENE_GRAPH_DELETED_CODECS = List.of(
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic3k.objects.CutsceneKnucklesLbz1CollapseChild",
                    GameId.S3K),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic3k.objects.CutsceneKnucklesLbz1RangeHelper",
                    GameId.S3K));

    private static final List<CodecDeletionCandidate> S3K_MHZ_CUTSCENE_GRAPH_DELETED_CODECS = List.of(
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic3k.objects.Mhz1CutsceneButtonInstance",
                    GameId.S3K),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic3k.objects.CutsceneKnucklesMhz1Instance",
                    GameId.S3K),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic3k.objects.CutsceneKnucklesMhz1PeerInstance",
                    GameId.S3K),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic3k.objects.Mhz1CutsceneDoorInstance",
                    GameId.S3K),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic3k.objects.Mhz1CutsceneKnucklesInstance$Mhz1CutscenePlayerTwoStopper",
                    GameId.S3K),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic3k.objects.CutsceneKnucklesMhz2Instance$Mhz2KnucklesRouteSwitchChild",
                    GameId.S3K));

    private static final List<CodecDeletionCandidate> S3K_MHZ_MINIBOSS_FLAME_GRAPH_DELETED_CODECS = List.of(
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic3k.objects.MhzMinibossFlameInstance",
                    GameId.S3K));

    private static final List<CodecDeletionCandidate> S3K_MHZ_MINIBOSS_ESCAPE_SHARD_GRAPH_DELETED_CODECS = List.of(
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic3k.objects.MhzMinibossEscapeShardInstance",
                    GameId.S3K));

    private static final List<CodecDeletionCandidate> S3K_MHZ_END_BOSS_CONTROLLER_GRAPH_DELETED_CODECS = List.of(
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic3k.objects.bosses.MhzEndBossInstance$MhzEndBossSidekickLockChild",
                    GameId.S3K),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic3k.objects.bosses.MhzEndBossInstance$MhzEndBossWalkoffPrepChild",
                    GameId.S3K),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic3k.objects.bosses.MhzEndBossInstance$MhzEndBossPlayerTwoCarryChild",
                    GameId.S3K));

    private static final List<CodecDeletionCandidate> S3K_NESTED_HURTBOX_GRAPH_DELETED_CODECS = List.of(
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic3k.objects.MgzMinibossInstance$DrillArmChild",
                    GameId.S3K),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic3k.objects.MgzMinibossInstance$CeilingDebrisChild",
                    GameId.S3K),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic3k.objects.MgzMinibossInstance$DefeatFragmentChild",
                    GameId.S3K),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic3k.objects.MgzMinibossInstance$KnucklesSpikePlatformChild",
                    GameId.S3K),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic3k.objects.MgzMinibossInstance$MgzBossCameraScrollHelper",
                    GameId.S3K),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic3k.objects.HczMinibossInstance$VortexBubbleChild",
                    GameId.S3K),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic3k.objects.bosses.IczEndBossInstance$IczEndBossDefeatDebrisChild",
                    GameId.S3K),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic3k.objects.IczIceSpikesObjectInstance$SpikeHurtChild",
                    GameId.S3K));

    private static final List<CodecDeletionCandidate> S3K_CUTSCENE_KNUCKLES_GRAPH_DELETED_CODECS = List.of(
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic3k.objects.CutsceneKnucklesCnz2AInstance",
                    GameId.S3K),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic3k.objects.CutsceneKnucklesRockChild",
                    GameId.S3K),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic3k.objects.CutsceneKnuxCnz2WallInstance",
                    GameId.S3K));

    private static final List<CodecDeletionCandidate> S3K_KNUCKLES_CUTSCENE_CONTROLLER_RECREATE_CLASSES = List.of(
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic3k.objects.CutsceneKnucklesAiz2Instance",
                    GameId.S3K),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic3k.objects.CutsceneKnucklesCnz2BInstance",
                    GameId.S3K),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic3k.objects.CutsceneKnucklesHcz2Instance",
                    GameId.S3K),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic3k.objects.CutsceneKnucklesLbz1Instance",
                    GameId.S3K),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic3k.objects.CutsceneKnucklesMhz2Instance",
                    GameId.S3K));

    private static final List<CodecDeletionCandidate> S3K_STANDALONE_CUTSCENE_CONTROLLER_RECREATE_CLASSES = List.of(
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic3k.objects.AizMinibossCutsceneInstance",
                    GameId.S3K),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic3k.objects.Hcz2CutsceneButtonInstance",
                    GameId.S3K),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic3k.objects.Lbz1GroundLaunchIntroInstance",
                    GameId.S3K),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic3k.objects.Lbz1RobotnikEventController",
                    GameId.S3K),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic3k.objects.Mhz1CutsceneKnucklesInstance",
                    GameId.S3K),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic3k.objects.S3kCutsceneButtonObjectInstance",
                    GameId.S3K),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic3k.objects.CutsceneKnucklesSkIntroInstance",
                    GameId.S3K));

    private static final List<CodecDeletionCandidate> S3K_STAGE_CONTROLLER_RECREATE_CLASSES = List.of(
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic3k.objects.AizTransitionFloorObjectInstance",
                    GameId.S3K),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic3k.objects.CnzEggCapsuleInstance",
                    GameId.S3K),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic3k.objects.HCZ2WallObjectInstance",
                    GameId.S3K),
            new CodecDeletionCandidate(
                    "com.openggf.game.sonic3k.objects.IczMinibossPostBossPaletteController",
                    GameId.S3K));

    private static final List<CodecDeletionCandidate> S1_EGG_PRISON_BUTTON_GRAPH_RECREATE_CLASSES = List.of(
            new CodecDeletionCandidate(Sonic1EggPrisonButtonObjectInstance.class.getName(), GameId.S1));

    private static final List<CodecDeletionCandidate> S2_EGG_PRISON_BUTTON_GRAPH_DELETED_CODECS = List.of(
            new CodecDeletionCandidate(EggPrisonObjectInstance.class.getName(), GameId.S2),
            new CodecDeletionCandidate(EggPrisonButtonObjectInstance.class.getName(), GameId.S2));

    private static final List<CodecDeletionCandidate> S2_OOZ_BURNER_FLAME_GRAPH_DELETED_CODECS = List.of(
            new CodecDeletionCandidate(OOZPoppingPlatformObjectInstance.class.getName(), GameId.S2),
            new CodecDeletionCandidate(OOZBurnerFlameObjectInstance.class.getName(), GameId.S2));

    private static final List<CodecDeletionCandidate> S2_ARZ_ARROW_GRAPH_SUPPORT = List.of(
            new CodecDeletionCandidate(Sonic2ARZBossInstance.class.getName(), GameId.S2),
            new CodecDeletionCandidate(ARZBossArrow.class.getName(), GameId.S2),
            new CodecDeletionCandidate(ARZBossEyes.class.getName(), GameId.S2));

    private static final List<CodecDeletionCandidate> S3K_SS_ENTRY_FLASH_GRAPH_DELETED_CODECS = List.of(
            new CodecDeletionCandidate(Sonic3kSSEntryFlashObjectInstance.class.getName(), GameId.S3K));

    private static final List<CodecDeletionCandidate> S3K_CNZ2_CUTSCENE_BUTTON_GRAPH_DELETED_CODECS = List.of(
            new CodecDeletionCandidate(Cnz2CutsceneButtonInstance.class.getName(), GameId.S3K));

    private static final List<CodecDeletionCandidate> S3K_CNZ_WATER_LEVEL_CORK_FLOOR_GRAPH_DELETED_CODECS =
            List.of(
                    new CodecDeletionCandidate(CnzWaterLevelCorkFloorInstance.class.getName(), GameId.S3K),
                    new CodecDeletionCandidate(CorkFloorObjectInstance.class.getName(), GameId.S3K));

    private static final SonicConfigurationService DEFAULT_CONFIGURATION =
            createDefaultConfiguration();
    private static final ObjectRenderManager INERT_RENDER_MANAGER =
            new ObjectRenderManager(new InertObjectArtProvider());

    @BeforeEach
    void initHeadless() { GraphicsManager.getInstance().initHeadless(); }

    @AfterEach
    void tearDown() { GraphicsManager.getInstance().resetState(); }

    // HTZ (S2) - already implements RewindRecreatable - should pass before AND after deletion

    @Test
    void htzGroundFireRoundTripsPassed() {
        RoundTripSweepResult result = RewindRoundTripHarness.probeClass(HTZ_GROUND_FIRE_FQN);
        assertInstanceOf(RoundTripSweepResult.Passed.class, result,
                "HtzGroundFireObjectInstance must round-trip as Passed; got: " + result);
    }

    @Test
    void htzGroundFireGenericRecreateProducesInstance() {
        ObjectInstance result = invokeGenericRecreate(HTZ_GROUND_FIRE_FQN, 0x100, 0x200, GameId.S2);
        assertNotNull(result, "genericRecreate must return non-null for HtzGroundFireObjectInstance");
        assertEquals(HTZ_GROUND_FIRE_FQN, result.getClass().getName());
    }

    // AIZ BG Tree Spawner (S3K) - RED until RewindRecreatable is added

    @Test
    void aizBgTreeSpawnerIsRewindRecreatable() {
        Class<?> cls;
        try { cls = Class.forName(AIZ_BG_TREE_SPAWNER_FQN); }
        catch (ClassNotFoundException e) { throw new AssertionError(e); }
        assertTrue(RewindRecreatable.class.isAssignableFrom(cls),
                AIZ_BG_TREE_SPAWNER_FQN + " must implement RewindRecreatable");
    }

    @Test
    void aizBgTreeSpawnerGenericRecreateProducesInstance() {
        ObjectInstance result = invokeGenericRecreate(AIZ_BG_TREE_SPAWNER_FQN, 0, 0, GameId.S3K);
        assertNotNull(result, "genericRecreate must return non-null for AizBgTreeSpawnerInstance");
        assertEquals(AIZ_BG_TREE_SPAWNER_FQN, result.getClass().getName());
    }

    // AIZ Miniboss Napalm Projectile (S3K) is parent/sibling-linked. Its real
    // rewind roundtrip is covered by TestS3kAizMinibossGraphRewind rather than
    // the isolated scalar-only probe used for the standalone classes here.

    @Test
    void aizMinibossNapalmIsRewindRecreatable() {
        Class<?> cls;
        try { cls = Class.forName(AIZ_MINIBOSS_NAPALM_FQN); }
        catch (ClassNotFoundException e) { throw new AssertionError(e); }
        assertTrue(RewindRecreatable.class.isAssignableFrom(cls),
                AIZ_MINIBOSS_NAPALM_FQN + " must implement RewindRecreatable");
    }

    // Integration anchor: the two standalone classes pass the isolated harness;
    // the graph-linked AIZ projectile is asserted by the graph classification and
    // real ObjectManager roundtrip tests in the AIZ miniboss rewind suite.

    @Test
    void standaloneClassesRoundTripPassedAfterCodecDeletion() {
        for (String fqn : List.of(HTZ_GROUND_FIRE_FQN, AIZ_BG_TREE_SPAWNER_FQN)) {
            RoundTripSweepResult result = RewindRoundTripHarness.probeClass(fqn);
            assertInstanceOf(RoundTripSweepResult.Passed.class, result,
                    fqn + " must round-trip as Passed via RewindRecreatable path; got: " + result);
        }
    }

    // =====================================================================
    // Batch 2: ten scalar-only S3K classes — codec deleted, RewindRecreatable added
    // =====================================================================

    @Test
    void batch2ClassesAllImplementRewindRecreatable() {
        for (String fqn : BATCH2_DELETED_CODEC_FQNS) {
            Class<?> cls;
            try { cls = Class.forName(fqn); }
            catch (ClassNotFoundException e) { throw new AssertionError(e); }
            assertTrue(RewindRecreatable.class.isAssignableFrom(cls),
                    fqn + " must implement RewindRecreatable (codec deleted in batch 2)");
        }
    }

    @Test
    void batch2ClassesHaveNoRegisteredCodec() {
        for (String fqn : BATCH2_DELETED_CODEC_FQNS) {
            assertFalse(hasRegisteredDynamicCodec(fqn),
                    fqn + " must have NO registered dynamic rewind codec after batch-2 deletion; "
                            + "it should round-trip purely via genericRecreate Path 1");
        }
    }

    @Test
    void batch2ClassesGenericRecreateProducesInstance() {
        for (String fqn : BATCH2_DELETED_CODEC_FQNS) {
            ObjectInstance result = invokeGenericRecreate(fqn, 0x120, 0x240, GameId.S3K);
            assertNotNull(result, "genericRecreate must return non-null for " + fqn);
            assertEquals(fqn, result.getClass().getName(),
                    "genericRecreate must return the same concrete class for " + fqn);
        }
    }

    @Test
    void batch2ClassesRoundTripPassedWithoutCodec() {
        for (String fqn : BATCH2_DELETED_CODEC_FQNS) {
            RoundTripSweepResult result = RewindRoundTripHarness.probeClass(fqn);
            assertInstanceOf(RoundTripSweepResult.Passed.class, result,
                    fqn + " must round-trip as Passed via RewindRecreatable path (no codec); got: " + result);
        }
    }

    // =====================================================================
    // Batch 3: deleted-codec candidates covered by generic recreate
    // =====================================================================

    @Test
    void batch3ClassesAllImplementRewindRecreatable() {
        for (CodecDeletionCandidate candidate : BATCH3_DELETED_CODECS) {
            Class<?> cls;
            try { cls = Class.forName(candidate.fqn()); }
            catch (ClassNotFoundException e) { throw new AssertionError(e); }
            assertTrue(RewindRecreatable.class.isAssignableFrom(cls),
                    candidate.fqn() + " must implement RewindRecreatable (codec deleted in batch 3)");
        }
    }

    @Test
    void batch3ClassesHaveNoRegisteredCodec() {
        for (CodecDeletionCandidate candidate : BATCH3_DELETED_CODECS) {
            assertFalse(hasRegisteredDynamicCodec(candidate.fqn()),
                    candidate.fqn() + " must have NO registered dynamic rewind codec after batch-3 deletion; "
                            + "it should round-trip purely via genericRecreate Path 1");
        }
    }

    @Test
    void batch3ClassesGenericRecreateProducesInstance() {
        for (CodecDeletionCandidate candidate : BATCH3_DELETED_CODECS) {
            ObjectInstance result = invokeGenericRecreate(candidate.fqn(), 0x120, 0x240, candidate.gameId());
            assertNotNull(result, "genericRecreate must return non-null for " + candidate.fqn());
            assertEquals(candidate.fqn(), result.getClass().getName(),
                    "genericRecreate must return the same concrete class for " + candidate.fqn());
        }
    }

    @Test
    void batch3ClassesRoundTripPassedWithoutCodec() {
        for (CodecDeletionCandidate candidate : BATCH3_DELETED_CODECS) {
            RoundTripSweepResult result = RewindRoundTripHarness.probeClass(candidate.fqn());
            assertInstanceOf(RoundTripSweepResult.Passed.class, result,
                    candidate.fqn()
                            + " must round-trip as Passed via RewindRecreatable path (no codec); got: "
                            + result);
        }
    }

    // =====================================================================
    // Audited live-reference deletions: object refs are policy-covered
    // =====================================================================

    @Test
    void auditedLiveReferenceClassesAllImplementRewindRecreatable() {
        for (CodecDeletionCandidate candidate : AUDITED_LIVE_REFERENCE_DELETED_CODECS) {
            Class<?> cls;
            try { cls = Class.forName(candidate.fqn()); }
            catch (ClassNotFoundException e) { throw new AssertionError(e); }
            assertTrue(RewindRecreatable.class.isAssignableFrom(cls),
                    candidate.fqn() + " must implement RewindRecreatable (audited live-reference deletion)");
        }
    }

    @Test
    void auditedLiveReferenceClassesHaveNoRegisteredCodec() {
        for (CodecDeletionCandidate candidate : AUDITED_LIVE_REFERENCE_DELETED_CODECS) {
            assertFalse(hasRegisteredDynamicCodec(candidate.fqn(), candidate.gameId()),
                    candidate.fqn() + " must have NO registered dynamic rewind codec after deletion; "
                            + "it should round-trip via genericRecreate Path 1 plus compact field restore");
        }
    }

    @Test
    void auditedLiveReferenceClassesGenericRecreateProducesInstance() {
        for (CodecDeletionCandidate candidate : AUDITED_LIVE_REFERENCE_DELETED_CODECS) {
            ObjectInstance result = invokeGenericRecreate(candidate.fqn(), 0x120, 0x240, candidate.gameId());
            assertNotNull(result, "genericRecreate must return non-null for " + candidate.fqn());
            assertEquals(candidate.fqn(), result.getClass().getName(),
                    "genericRecreate must return the same concrete class for " + candidate.fqn());
        }
    }

    @Test
    void auditedLiveReferenceClassesRoundTripPassedWithoutCodec() {
        for (CodecDeletionCandidate candidate : AUDITED_LIVE_REFERENCE_DELETED_CODECS) {
            RoundTripSweepResult result = RewindRoundTripHarness.probeClass(candidate.fqn());
            assertInstanceOf(RoundTripSweepResult.Passed.class, result,
                    candidate.fqn()
                            + " must round-trip as Passed via generic recreate and compact restore; got: "
                            + result);
        }
    }

    // =====================================================================
    // Batch 4: two scalar-only self-contained dynamics - codec deleted
    // =====================================================================

    @Test
    void batch4ClassesAllImplementRewindRecreatable() {
        for (CodecDeletionCandidate candidate : BATCH4_DELETED_CODECS) {
            Class<?> cls;
            try { cls = Class.forName(candidate.fqn()); }
            catch (ClassNotFoundException e) { throw new AssertionError(e); }
            assertTrue(RewindRecreatable.class.isAssignableFrom(cls),
                    candidate.fqn() + " must implement RewindRecreatable (codec deleted in batch 4)");
        }
    }

    @Test
    void batch4ClassesHaveNoRegisteredCodec() {
        for (CodecDeletionCandidate candidate : BATCH4_DELETED_CODECS) {
            assertFalse(hasRegisteredDynamicCodec(candidate.fqn()),
                    candidate.fqn() + " must have NO registered dynamic rewind codec after batch-4 deletion; "
                            + "it should round-trip purely via genericRecreate Path 1");
        }
    }

    @Test
    void batch4ClassesGenericRecreateProducesInstance() {
        for (CodecDeletionCandidate candidate : BATCH4_DELETED_CODECS) {
            ObjectInstance result = invokeGenericRecreate(candidate.fqn(), 0x120, 0x240, candidate.gameId());
            assertNotNull(result, "genericRecreate must return non-null for " + candidate.fqn());
            assertEquals(candidate.fqn(), result.getClass().getName(),
                    "genericRecreate must return the same concrete class for " + candidate.fqn());
        }
    }

    @Test
    void batch4ClassesRoundTripPassedWithoutCodec() {
        for (CodecDeletionCandidate candidate : BATCH4_DELETED_CODECS) {
            RoundTripSweepResult result = RewindRoundTripHarness.probeClass(candidate.fqn());
            assertInstanceOf(RoundTripSweepResult.Passed.class, result,
                    candidate.fqn()
                            + " must round-trip as Passed via RewindRecreatable path (no codec); got: "
                            + result);
        }
    }

    // =====================================================================
    // Batch 5: gumball bonus-stage exit trigger - codec deleted
    // =====================================================================

    @Test
    void batch5ClassesAllImplementRewindRecreatable() {
        for (CodecDeletionCandidate candidate : BATCH5_DELETED_CODECS) {
            Class<?> cls;
            try { cls = Class.forName(candidate.fqn()); }
            catch (ClassNotFoundException e) { throw new AssertionError(e); }
            assertTrue(RewindRecreatable.class.isAssignableFrom(cls),
                    candidate.fqn() + " must implement RewindRecreatable (codec deleted in batch 5)");
        }
    }

    @Test
    void batch5ClassesHaveNoRegisteredCodec() {
        for (CodecDeletionCandidate candidate : BATCH5_DELETED_CODECS) {
            assertFalse(hasRegisteredDynamicCodec(candidate.fqn()),
                    candidate.fqn() + " must have NO registered dynamic rewind codec after batch-5 deletion; "
                            + "it should round-trip purely via genericRecreate Path 1");
        }
    }

    @Test
    void batch5ClassesGenericRecreateProducesInstance() {
        for (CodecDeletionCandidate candidate : BATCH5_DELETED_CODECS) {
            ObjectInstance result = invokeGenericRecreate(candidate.fqn(), 0x120, 0x240, candidate.gameId());
            assertNotNull(result, "genericRecreate must return non-null for " + candidate.fqn());
            assertEquals(candidate.fqn(), result.getClass().getName(),
                    "genericRecreate must return the same concrete class for " + candidate.fqn());
        }
    }

    @Test
    void batch5ClassesRoundTripPassedWithoutCodec() {
        for (CodecDeletionCandidate candidate : BATCH5_DELETED_CODECS) {
            RoundTripSweepResult result = RewindRoundTripHarness.probeClass(candidate.fqn());
            assertInstanceOf(RoundTripSweepResult.Passed.class, result,
                    candidate.fqn()
                            + " must round-trip as Passed via RewindRecreatable path (no codec); got: "
                            + result);
        }
    }

    // =====================================================================
    // Batch 6: MGZ drilling Robotnik - codec deleted
    // =====================================================================

    @Test
    void batch6ClassesAllImplementRewindRecreatable() {
        for (CodecDeletionCandidate candidate : BATCH6_DELETED_CODECS) {
            Class<?> cls;
            try { cls = Class.forName(candidate.fqn()); }
            catch (ClassNotFoundException e) { throw new AssertionError(e); }
            assertTrue(RewindRecreatable.class.isAssignableFrom(cls),
                    candidate.fqn() + " must implement RewindRecreatable (codec deleted in batch 6)");
        }
    }

    @Test
    void batch6ClassesHaveNoRegisteredCodec() {
        for (CodecDeletionCandidate candidate : BATCH6_DELETED_CODECS) {
            assertFalse(hasRegisteredDynamicCodec(candidate.fqn()),
                    candidate.fqn() + " must have NO registered dynamic rewind codec after batch-6 deletion; "
                            + "it should round-trip purely via genericRecreate Path 1");
        }
    }

    @Test
    void batch6ClassesGenericRecreateProducesInstance() {
        for (CodecDeletionCandidate candidate : BATCH6_DELETED_CODECS) {
            ObjectInstance result = invokeGenericRecreate(candidate.fqn(), 0x120, 0x240, candidate.gameId());
            assertNotNull(result, "genericRecreate must return non-null for " + candidate.fqn());
            assertEquals(candidate.fqn(), result.getClass().getName(),
                    "genericRecreate must return the same concrete class for " + candidate.fqn());
        }
    }

    @Test
    void batch6ClassesRoundTripPassedWithoutCodec() {
        for (CodecDeletionCandidate candidate : BATCH6_DELETED_CODECS) {
            RoundTripSweepResult result = RewindRoundTripHarness.probeClass(candidate.fqn());
            assertInstanceOf(RoundTripSweepResult.Passed.class, result,
                    candidate.fqn()
                            + " must round-trip as Passed via RewindRecreatable path (no codec); got: "
                            + result);
        }
    }

    // =====================================================================
    // Batch 7: HCZ end boss - codec deleted
    // =====================================================================

    @Test
    void batch7ClassesAllImplementRewindRecreatable() {
        for (CodecDeletionCandidate candidate : BATCH7_DELETED_CODECS) {
            Class<?> cls;
            try { cls = Class.forName(candidate.fqn()); }
            catch (ClassNotFoundException e) { throw new AssertionError(e); }
            assertTrue(RewindRecreatable.class.isAssignableFrom(cls),
                    candidate.fqn() + " must implement RewindRecreatable (codec deleted in batch 7)");
        }
    }

    @Test
    void batch7ClassesHaveNoRegisteredCodec() {
        for (CodecDeletionCandidate candidate : BATCH7_DELETED_CODECS) {
            assertFalse(hasRegisteredDynamicCodec(candidate.fqn()),
                    candidate.fqn() + " must have NO registered dynamic rewind codec after batch-7 deletion; "
                            + "it should round-trip purely via genericRecreate Path 1");
        }
    }

    @Test
    void batch7ClassesGenericRecreateProducesInstance() {
        for (CodecDeletionCandidate candidate : BATCH7_DELETED_CODECS) {
            ObjectInstance result = invokeGenericRecreate(candidate.fqn(), 0x120, 0x240, candidate.gameId());
            assertNotNull(result, "genericRecreate must return non-null for " + candidate.fqn());
            assertEquals(candidate.fqn(), result.getClass().getName(),
                    "genericRecreate must return the same concrete class for " + candidate.fqn());
        }
    }

    @Test
    void batch7ClassesRoundTripPassedWithoutCodec() {
        for (CodecDeletionCandidate candidate : BATCH7_DELETED_CODECS) {
            RoundTripSweepResult result = RewindRoundTripHarness.probeClass(candidate.fqn());
            assertInstanceOf(RoundTripSweepResult.Passed.class, result,
                    candidate.fqn()
                            + " must round-trip as Passed via RewindRecreatable path (no codec); got: "
                            + result);
        }
    }

    // =====================================================================
    // Batch 8: points popups - codec deleted
    // =====================================================================

    @Test
    void batch8ClassesAllImplementRewindRecreatable() {
        for (CodecDeletionCandidate candidate : BATCH8_DELETED_CODECS) {
            Class<?> cls;
            try { cls = Class.forName(candidate.fqn()); }
            catch (ClassNotFoundException e) { throw new AssertionError(e); }
            assertTrue(RewindRecreatable.class.isAssignableFrom(cls),
                    candidate.fqn() + " must implement RewindRecreatable (codec deleted in batch 8)");
        }
    }

    @Test
    void batch8ClassesHaveNoRegisteredCodec() {
        for (CodecDeletionCandidate candidate : BATCH8_DELETED_CODECS) {
            assertFalse(hasRegisteredDynamicCodec(candidate.fqn()),
                    candidate.fqn() + " must have NO registered dynamic rewind codec after batch-8 deletion; "
                            + "it should round-trip purely via genericRecreate Path 1");
        }
    }

    @Test
    void batch8ClassesGenericRecreateProducesInstance() {
        for (CodecDeletionCandidate candidate : BATCH8_DELETED_CODECS) {
            ObjectInstance result = invokeGenericRecreate(candidate.fqn(), 0x120, 0x240, candidate.gameId());
            assertNotNull(result, "genericRecreate must return non-null for " + candidate.fqn());
            assertEquals(candidate.fqn(), result.getClass().getName(),
                    "genericRecreate must return the same concrete class for " + candidate.fqn());
        }
    }

    @Test
    void batch8ClassesRoundTripPassedWithoutCodec() {
        for (CodecDeletionCandidate candidate : BATCH8_DELETED_CODECS) {
            RoundTripSweepResult result = RewindRoundTripHarness.probeClass(candidate.fqn());
            assertInstanceOf(RoundTripSweepResult.Passed.class, result,
                    candidate.fqn()
                            + " must round-trip as Passed via RewindRecreatable path (no codec); got: "
                            + result);
        }
    }

    // =====================================================================
    // Batch 9: S2 Grounder badnik - codec deleted
    // =====================================================================

    @Test
    void batch9ClassesAllImplementRewindRecreatable() {
        for (CodecDeletionCandidate candidate : BATCH9_DELETED_CODECS) {
            Class<?> cls;
            try {
                cls = Class.forName(candidate.fqn());
            } catch (ClassNotFoundException e) {
                throw new AssertionError(e);
            }
            assertTrue(RewindRecreatable.class.isAssignableFrom(cls),
                    candidate.fqn() + " must implement RewindRecreatable (codec deleted in batch 9)");
        }
    }

    @Test
    void batch9ClassesHaveNoRegisteredCodec() {
        for (CodecDeletionCandidate candidate : BATCH9_DELETED_CODECS) {
            assertFalse(hasRegisteredDynamicCodec(candidate.fqn()),
                    candidate.fqn() + " must have NO registered dynamic rewind codec after batch-9 deletion; "
                            + "it should round-trip purely via genericRecreate Path 1");
        }
    }

    @Test
    void batch9ClassesGenericRecreateProducesInstance() {
        for (CodecDeletionCandidate candidate : BATCH9_DELETED_CODECS) {
            ObjectInstance result = invokeGenericRecreate(candidate.fqn(), 0x120, 0x240, candidate.gameId());
            assertNotNull(result, "genericRecreate must return non-null for " + candidate.fqn());
            assertEquals(candidate.fqn(), result.getClass().getName(),
                    "genericRecreate must return the same concrete class for " + candidate.fqn());
        }
    }

    @Test
    void batch9ClassesRoundTripPassedWithoutCodec() {
        for (CodecDeletionCandidate candidate : BATCH9_DELETED_CODECS) {
            RoundTripSweepResult result = RewindRoundTripHarness.probeClass(candidate.fqn());
            assertInstanceOf(RoundTripSweepResult.Passed.class, result,
                    candidate.fqn()
                            + " must round-trip as Passed via RewindRecreatable path (no codec); got: "
                            + result);
        }
    }

    // =====================================================================
    // Batch 10: S2 MTZ Conveyor - codec deleted, captured constructor base preserved
    // =====================================================================

    @Test
    void batch10ClassesAllImplementRewindRecreatable() {
        for (CodecDeletionCandidate candidate : BATCH10_DELETED_CODECS) {
            Class<?> cls;
            try {
                cls = Class.forName(candidate.fqn());
            } catch (ClassNotFoundException e) {
                throw new AssertionError(e);
            }
            assertTrue(RewindRecreatable.class.isAssignableFrom(cls),
                    candidate.fqn() + " must implement RewindRecreatable (codec deleted in batch 10)");
        }
    }

    @Test
    void batch10ClassesHaveNoRegisteredCodec() {
        for (CodecDeletionCandidate candidate : BATCH10_DELETED_CODECS) {
            assertFalse(hasRegisteredDynamicCodec(candidate.fqn()),
                    candidate.fqn() + " must have NO registered dynamic rewind codec after batch-10 deletion; "
                            + "it should round-trip purely via genericRecreate Path 1");
        }
    }

    @Test
    void batch10ClassesGenericRecreateProducesInstance() {
        for (CodecDeletionCandidate candidate : BATCH10_DELETED_CODECS) {
            ObjectInstance result = invokeGenericRecreate(candidate.fqn(), 0x120, 0x240, candidate.gameId());
            assertNotNull(result, "genericRecreate must return non-null for " + candidate.fqn());
            assertEquals(candidate.fqn(), result.getClass().getName(),
                    "genericRecreate must return the same concrete class for " + candidate.fqn());
        }
    }

    @Test
    void batch10ClassesRoundTripPassedWithoutCodec() {
        for (CodecDeletionCandidate candidate : BATCH10_DELETED_CODECS) {
            RoundTripSweepResult result = RewindRoundTripHarness.probeClass(candidate.fqn());
            assertInstanceOf(RoundTripSweepResult.Passed.class, result,
                    candidate.fqn()
                            + " must round-trip as Passed via RewindRecreatable path (no codec); got: "
                            + result);
        }
    }

    @Test
    void conveyorGenericRecreatePreservesCapturedConstructorBase() {
        ObjectSpawn spawn = new ObjectSpawn(
                0x340, 0x280, Sonic2ObjectIds.CONVEYOR, 0x01, 0, false, 0);
        int capturedBaseX = 0x300;
        int capturedBaseY = 0x240;
        ConveyorObjectInstance source =
                new ConveyorObjectInstance(spawn, "Conveyor", capturedBaseX, capturedBaseY);
        PerObjectRewindSnapshot state = source.captureRewindState();

        ObjectInstance recreated = invokeGenericRecreateWithState(
                ConveyorObjectInstance.class.getName(), spawn, state, GameId.S2);

        assertNotNull(recreated, "genericRecreate must return non-null for ConveyorObjectInstance");
        assertEquals(ConveyorObjectInstance.class, recreated.getClass());
        PerObjectRewindSnapshot recreatedState =
                ((AbstractObjectInstance) recreated).captureRewindState();
        Object extra = recreatedState.objectSubclassExtra();
        assertNotNull(extra, "Conveyor recreate must preserve subclass rewind extra");
        assertEquals(capturedBaseX, readIntRecordComponent(extra, "baseX"),
                "Conveyor generic recreate must preserve captured baseX, not derive it from spawn.x");
        assertEquals(capturedBaseY, readIntRecordComponent(extra, "baseY"),
                "Conveyor generic recreate must preserve captured baseY, not derive it from spawn.y");
    }

    @Test
    void batch10ConstructorScalarsAreMutableForCompactRestore() {
        for (MutableFieldCoverageCandidate candidate : BATCH10_MUTABLE_FIELDS) {
            Class<?> cls = loadClass(candidate.fqn());
            for (String fieldName : candidate.fieldNames()) {
                try {
                    var field = findField(cls, fieldName);
                    assertFalse(Modifier.isFinal(field.getModifiers()),
                            cls.getName() + "#" + fieldName
                                    + " must be mutable so compact restore can replay captured scalars");
                } catch (NoSuchFieldException e) {
                    throw new AssertionError("Missing scalar field " + cls.getName() + "#" + fieldName, e);
                }
            }
        }
    }

    // =====================================================================
    // Batch 11: AIZ2 self-contained transient children - codecs deleted
    // =====================================================================

    @Test
    void batch11ClassesAllImplementRewindRecreatable() {
        for (CodecDeletionCandidate candidate : BATCH11_DELETED_CODECS) {
            Class<?> cls;
            try {
                cls = Class.forName(candidate.fqn());
            } catch (ClassNotFoundException e) {
                throw new AssertionError(e);
            }
            assertTrue(RewindRecreatable.class.isAssignableFrom(cls),
                    candidate.fqn() + " must implement RewindRecreatable (codec deleted in batch 11)");
        }
    }

    @Test
    void batch11ClassesHaveNoRegisteredCodec() {
        for (CodecDeletionCandidate candidate : BATCH11_DELETED_CODECS) {
            assertFalse(hasRegisteredDynamicCodec(candidate.fqn()),
                    candidate.fqn() + " must have NO registered dynamic rewind codec after batch-11 deletion; "
                            + "it should round-trip purely via genericRecreate Path 1");
        }
    }

    @Test
    void batch11ClassesGenericRecreateProducesInstance() {
        for (CodecDeletionCandidate candidate : BATCH11_DELETED_CODECS) {
            ObjectInstance result = invokeGenericRecreate(candidate.fqn(), 0x120, 0x240, candidate.gameId());
            assertNotNull(result, "genericRecreate must return non-null for " + candidate.fqn());
            assertEquals(candidate.fqn(), result.getClass().getName(),
                    "genericRecreate must return the same concrete class for " + candidate.fqn());
        }
    }

    @Test
    void batch11ClassesRoundTripPassedWithoutCodec() {
        for (CodecDeletionCandidate candidate : BATCH11_DELETED_CODECS) {
            RoundTripSweepResult result = RewindRoundTripHarness.probeClass(candidate.fqn());
            assertInstanceOf(RoundTripSweepResult.Passed.class, result,
                    candidate.fqn()
                            + " must round-trip as Passed via RewindRecreatable path (no codec); got: "
                            + result);
        }
    }

    // =====================================================================
    // Batch 12: self-contained S3K transient/effect/reward children
    // =====================================================================

    @Test
    void batch12ClassesAllImplementRewindRecreatable() {
        for (CodecDeletionCandidate candidate : BATCH12_DELETED_CODECS) {
            Class<?> cls;
            try {
                cls = Class.forName(candidate.fqn());
            } catch (ClassNotFoundException e) {
                throw new AssertionError(e);
            }
            assertTrue(RewindRecreatable.class.isAssignableFrom(cls),
                    candidate.fqn() + " must implement RewindRecreatable (codec deleted in batch 12)");
        }
    }

    @Test
    void batch12ClassesHaveNoRegisteredCodec() {
        for (CodecDeletionCandidate candidate : BATCH12_DELETED_CODECS) {
            assertFalse(hasRegisteredDynamicCodec(candidate.fqn()),
                    candidate.fqn() + " must have NO registered dynamic rewind codec after batch-12 deletion; "
                            + "it should round-trip purely via genericRecreate Path 1");
        }
    }

    @Test
    void batch12ClassesGenericRecreateProducesInstance() {
        for (CodecDeletionCandidate candidate : BATCH12_DELETED_CODECS) {
            ObjectInstance result = invokeGenericRecreate(candidate.fqn(), 0x120, 0x240, candidate.gameId());
            assertNotNull(result, "genericRecreate must return non-null for " + candidate.fqn());
            assertEquals(candidate.fqn(), result.getClass().getName(),
                    "genericRecreate must return the same concrete class for " + candidate.fqn());
        }
    }

    @Test
    void batch12ClassesRoundTripPassedWithoutCodec() {
        for (CodecDeletionCandidate candidate : BATCH12_DELETED_CODECS) {
            RoundTripSweepResult result = RewindRoundTripHarness.probeClass(candidate.fqn());
            assertInstanceOf(RoundTripSweepResult.Passed.class, result,
                    candidate.fqn()
                            + " must round-trip as Passed via RewindRecreatable path (no codec); got: "
                            + result);
        }
    }

    // =====================================================================
    // Batch 13: self-contained S3K release-sequence/effect dynamics
    // =====================================================================

    @Test
    void batch13ClassesAllImplementRewindRecreatable() {
        for (CodecDeletionCandidate candidate : BATCH13_DELETED_CODECS) {
            Class<?> cls;
            try {
                cls = Class.forName(candidate.fqn());
            } catch (ClassNotFoundException e) {
                throw new AssertionError(e);
            }
            assertTrue(RewindRecreatable.class.isAssignableFrom(cls),
                    candidate.fqn() + " must implement RewindRecreatable (codec deleted in batch 13)");
        }
    }

    @Test
    void batch13ClassesHaveNoRegisteredCodec() {
        for (CodecDeletionCandidate candidate : BATCH13_DELETED_CODECS) {
            assertFalse(hasRegisteredDynamicCodec(candidate.fqn()),
                    candidate.fqn() + " must have NO registered dynamic rewind codec after batch-13 deletion; "
                            + "it should round-trip purely via genericRecreate Path 1");
        }
    }

    @Test
    void batch13ClassesGenericRecreateProducesInstance() {
        for (CodecDeletionCandidate candidate : BATCH13_DELETED_CODECS) {
            ObjectInstance result = invokeGenericRecreate(candidate.fqn(), 0x120, 0x240, candidate.gameId());
            assertNotNull(result, "genericRecreate must return non-null for " + candidate.fqn());
            assertEquals(candidate.fqn(), result.getClass().getName(),
                    "genericRecreate must return the same concrete class for " + candidate.fqn());
        }
    }

    @Test
    void batch13ClassesRoundTripPassedWithoutCodec() {
        for (CodecDeletionCandidate candidate : BATCH13_DELETED_CODECS) {
            RoundTripSweepResult result = RewindRoundTripHarness.probeClass(candidate.fqn());
            assertInstanceOf(RoundTripSweepResult.Passed.class, result,
                    candidate.fqn()
                            + " must round-trip as Passed via RewindRecreatable path (no codec); got: "
                            + result);
        }
    }

    // =====================================================================
    // Batch 14: self-contained S3K transient/countdown/badnik-child dynamics
    // =====================================================================

    @Test
    void batch14ClassesAllImplementRewindRecreatable() {
        for (CodecDeletionCandidate candidate : BATCH14_DELETED_CODECS) {
            Class<?> cls;
            try {
                cls = Class.forName(candidate.fqn());
            } catch (ClassNotFoundException e) {
                throw new AssertionError(e);
            }
            assertTrue(RewindRecreatable.class.isAssignableFrom(cls),
                    candidate.fqn() + " must implement RewindRecreatable (codec deleted in batch 14)");
        }
    }

    @Test
    void batch14ClassesHaveNoRegisteredCodec() {
        for (CodecDeletionCandidate candidate : BATCH14_DELETED_CODECS) {
            assertFalse(hasRegisteredDynamicCodec(candidate.fqn()),
                    candidate.fqn() + " must have NO registered dynamic rewind codec after batch-14 deletion; "
                            + "it should round-trip purely via genericRecreate Path 1");
        }
    }

    @Test
    void batch14ClassesGenericRecreateProducesInstance() {
        for (CodecDeletionCandidate candidate : BATCH14_DELETED_CODECS) {
            ObjectInstance result = invokeGenericRecreate(candidate.fqn(), 0x120, 0x240, candidate.gameId());
            assertNotNull(result, "genericRecreate must return non-null for " + candidate.fqn());
            assertEquals(candidate.fqn(), result.getClass().getName(),
                    "genericRecreate must return the same concrete class for " + candidate.fqn());
        }
    }

    @Test
    void batch14ClassesRoundTripPassedWithoutCodec() {
        for (CodecDeletionCandidate candidate : BATCH14_DELETED_CODECS) {
            RoundTripSweepResult result = RewindRoundTripHarness.probeClass(candidate.fqn());
            assertInstanceOf(RoundTripSweepResult.Passed.class, result,
                    candidate.fqn()
                            + " must round-trip as Passed via RewindRecreatable path (no codec); got: "
                            + result);
        }
    }

    // =====================================================================
    // Batch 15: self-contained S3K exact-spawn dynamics
    // =====================================================================

    @Test
    void batch15ClassesAllImplementRewindRecreatable() {
        for (CodecDeletionCandidate candidate : BATCH15_DELETED_CODECS) {
            Class<?> cls;
            try {
                cls = Class.forName(candidate.fqn());
            } catch (ClassNotFoundException e) {
                throw new AssertionError(e);
            }
            assertTrue(RewindRecreatable.class.isAssignableFrom(cls),
                    candidate.fqn() + " must implement RewindRecreatable (codec deleted in batch 15)");
        }
    }

    @Test
    void batch15ClassesHaveNoRegisteredCodec() {
        for (CodecDeletionCandidate candidate : BATCH15_DELETED_CODECS) {
            assertFalse(hasRegisteredDynamicCodec(candidate.fqn()),
                    candidate.fqn() + " must have NO registered dynamic rewind codec after batch-15 deletion; "
                            + "it should round-trip purely via genericRecreate Path 1");
        }
    }

    @Test
    void batch15ClassesGenericRecreateProducesInstance() {
        for (CodecDeletionCandidate candidate : BATCH15_DELETED_CODECS) {
            ObjectInstance result = invokeGenericRecreate(candidate.fqn(), 0x120, 0x240, candidate.gameId());
            assertNotNull(result, "genericRecreate must return non-null for " + candidate.fqn());
            assertEquals(candidate.fqn(), result.getClass().getName(),
                    "genericRecreate must return the same concrete class for " + candidate.fqn());
        }
    }

    @Test
    void batch15ClassesRoundTripPassedWithoutCodec() {
        for (CodecDeletionCandidate candidate : BATCH15_DELETED_CODECS) {
            RoundTripSweepResult result = RewindRoundTripHarness.probeClass(candidate.fqn());
            assertInstanceOf(RoundTripSweepResult.Passed.class, result,
                    candidate.fqn()
                            + " must round-trip as Passed via RewindRecreatable path (no codec); got: "
                            + result);
        }
    }

    // =====================================================================
    // Batch 16: session-level/no-ref exact-spawn S3K dynamics
    // =====================================================================

    @Test
    void batch16ClassesAllImplementRewindRecreatable() {
        for (CodecDeletionCandidate candidate : BATCH16_DELETED_CODECS) {
            Class<?> cls;
            try {
                cls = Class.forName(candidate.fqn());
            } catch (ClassNotFoundException e) {
                throw new AssertionError(e);
            }
            assertTrue(RewindRecreatable.class.isAssignableFrom(cls),
                    candidate.fqn() + " must implement RewindRecreatable (codec deleted in batch 16)");
        }
    }

    @Test
    void batch16ClassesHaveNoRegisteredCodec() {
        for (CodecDeletionCandidate candidate : BATCH16_DELETED_CODECS) {
            assertFalse(hasRegisteredDynamicCodec(candidate.fqn()),
                    candidate.fqn() + " must have NO registered dynamic rewind codec after batch-16 deletion; "
                            + "it should round-trip purely via genericRecreate Path 1");
        }
    }

    @Test
    void batch16ClassesGenericRecreateProducesInstance() {
        for (CodecDeletionCandidate candidate : BATCH16_DELETED_CODECS) {
            ObjectInstance result = invokeGenericRecreate(candidate.fqn(), 0x120, 0x240, candidate.gameId());
            assertNotNull(result, "genericRecreate must return non-null for " + candidate.fqn());
            assertEquals(candidate.fqn(), result.getClass().getName(),
                    "genericRecreate must return the same concrete class for " + candidate.fqn());
        }
    }

    @Test
    void batch16ClassesRoundTripPassedWithoutCodec() {
        for (CodecDeletionCandidate candidate : BATCH16_DELETED_CODECS) {
            RoundTripSweepResult result = RewindRoundTripHarness.probeClass(candidate.fqn());
            assertInstanceOf(RoundTripSweepResult.Passed.class, result,
                    candidate.fqn()
                            + " must round-trip as Passed via RewindRecreatable path (no codec); got: "
                            + result);
        }
    }

    // =====================================================================
    // Batch 17: S3K exact-spawn end egg capsules
    // =====================================================================

    @Test
    void batch17ClassesAllImplementRewindRecreatable() {
        for (CodecDeletionCandidate candidate : BATCH17_DELETED_CODECS) {
            Class<?> cls;
            try {
                cls = Class.forName(candidate.fqn());
            } catch (ClassNotFoundException e) {
                throw new AssertionError(e);
            }
            assertTrue(RewindRecreatable.class.isAssignableFrom(cls),
                    candidate.fqn() + " must implement RewindRecreatable (codec deleted in batch 17)");
        }
    }

    @Test
    void batch17ClassesHaveNoRegisteredCodec() {
        for (CodecDeletionCandidate candidate : BATCH17_DELETED_CODECS) {
            assertFalse(hasRegisteredDynamicCodec(candidate.fqn()),
                    candidate.fqn() + " must have NO registered dynamic rewind codec after batch-17 deletion; "
                            + "it should round-trip purely via genericRecreate Path 1");
        }
    }

    @Test
    void batch17ClassesGenericRecreateProducesInstance() {
        for (CodecDeletionCandidate candidate : BATCH17_DELETED_CODECS) {
            ObjectInstance result = invokeGenericRecreate(candidate.fqn(), 0x120, 0x240, candidate.gameId());
            assertNotNull(result, "genericRecreate must return non-null for " + candidate.fqn());
            assertEquals(candidate.fqn(), result.getClass().getName(),
                    "genericRecreate must return the same concrete class for " + candidate.fqn());
        }
    }

    @Test
    void batch17ClassesRoundTripPassedWithoutCodec() {
        for (CodecDeletionCandidate candidate : BATCH17_DELETED_CODECS) {
            RoundTripSweepResult result = RewindRoundTripHarness.probeClass(candidate.fqn());
            assertInstanceOf(RoundTripSweepResult.Passed.class, result,
                    candidate.fqn()
                            + " must round-trip as Passed via RewindRecreatable path (no codec); got: "
                            + result);
        }
    }

    // =====================================================================
    // Batch 18: remaining no-ref S3K exact-spawn end capsules
    // =====================================================================

    @Test
    void batch18ClassesAllImplementRewindRecreatable() {
        for (CodecDeletionCandidate candidate : BATCH18_DELETED_CODECS) {
            Class<?> cls;
            try {
                cls = Class.forName(candidate.fqn());
            } catch (ClassNotFoundException e) {
                throw new AssertionError(e);
            }
            assertTrue(RewindRecreatable.class.isAssignableFrom(cls),
                    candidate.fqn() + " must implement RewindRecreatable (codec deleted in batch 18)");
        }
    }

    @Test
    void batch18ClassesHaveNoRegisteredCodec() {
        for (CodecDeletionCandidate candidate : BATCH18_DELETED_CODECS) {
            assertFalse(hasRegisteredDynamicCodec(candidate.fqn()),
                    candidate.fqn() + " must have NO registered dynamic rewind codec after batch-18 deletion; "
                            + "it should round-trip purely via genericRecreate Path 1");
        }
    }

    @Test
    void batch18ClassesGenericRecreateProducesInstance() {
        for (CodecDeletionCandidate candidate : BATCH18_DELETED_CODECS) {
            ObjectInstance result = invokeGenericRecreate(candidate.fqn(), 0x120, 0x240, candidate.gameId());
            assertNotNull(result, "genericRecreate must return non-null for " + candidate.fqn());
            assertEquals(candidate.fqn(), result.getClass().getName(),
                    "genericRecreate must return the same concrete class for " + candidate.fqn());
        }
    }

    @Test
    void batch18ClassesRoundTripPassedWithoutCodec() {
        for (CodecDeletionCandidate candidate : BATCH18_DELETED_CODECS) {
            RoundTripSweepResult result = RewindRoundTripHarness.probeClass(candidate.fqn());
            assertInstanceOf(RoundTripSweepResult.Passed.class, result,
                    candidate.fqn()
                            + " must round-trip as Passed via RewindRecreatable path (no codec); got: "
                            + result);
        }
    }

    // =====================================================================
    // Batch 19: private no-ref S3K badnik projectiles
    // =====================================================================

    @Test
    void batch19ClassesAllImplementRewindRecreatable() {
        for (CodecDeletionCandidate candidate : BATCH19_DELETED_CODECS) {
            Class<?> cls;
            try {
                cls = Class.forName(candidate.fqn());
            } catch (ClassNotFoundException e) {
                throw new AssertionError(e);
            }
            assertTrue(RewindRecreatable.class.isAssignableFrom(cls),
                    candidate.fqn() + " must implement RewindRecreatable (codec deleted in batch 19)");
        }
    }

    @Test
    void batch19ClassesHaveNoRegisteredCodec() {
        for (CodecDeletionCandidate candidate : BATCH19_DELETED_CODECS) {
            assertFalse(hasRegisteredDynamicCodec(candidate.fqn()),
                    candidate.fqn() + " must have NO registered dynamic rewind codec after batch-19 deletion; "
                            + "it should round-trip purely via genericRecreate Path 1");
        }
    }

    @Test
    void batch19ClassesGenericRecreateProducesInstance() {
        for (CodecDeletionCandidate candidate : BATCH19_DELETED_CODECS) {
            ObjectInstance result = invokeGenericRecreate(candidate.fqn(), 0x120, 0x240, candidate.gameId());
            assertNotNull(result, "genericRecreate must return non-null for " + candidate.fqn());
            assertEquals(candidate.fqn(), result.getClass().getName(),
                    "genericRecreate must return the same concrete class for " + candidate.fqn());
        }
    }

    @Test
    void batch19ClassesRoundTripPassedWithoutCodec() {
        for (CodecDeletionCandidate candidate : BATCH19_DELETED_CODECS) {
            RoundTripSweepResult result = RewindRoundTripHarness.probeClass(candidate.fqn());
            assertInstanceOf(RoundTripSweepResult.Passed.class, result,
                    candidate.fqn()
                            + " must round-trip as Passed via RewindRecreatable path (no codec); got: "
                            + result);
        }
    }

    // =====================================================================
    // Batch 20: session-verified no-ref S3K exact-spawn dynamics
    // =====================================================================

    @Test
    void batch20ClassesAllImplementRewindRecreatable() {
        for (CodecDeletionCandidate candidate : BATCH20_DELETED_CODECS) {
            Class<?> cls;
            try {
                cls = Class.forName(candidate.fqn());
            } catch (ClassNotFoundException e) {
                throw new AssertionError(e);
            }
            assertTrue(RewindRecreatable.class.isAssignableFrom(cls),
                    candidate.fqn() + " must implement RewindRecreatable (codec deleted in batch 20)");
        }
    }

    @Test
    void batch20ClassesHaveNoRegisteredCodec() {
        for (CodecDeletionCandidate candidate : BATCH20_DELETED_CODECS) {
            assertFalse(hasRegisteredDynamicCodec(candidate.fqn()),
                    candidate.fqn() + " must have NO registered dynamic rewind codec after batch-20 deletion; "
                            + "it should round-trip purely via genericRecreate Path 1");
        }
    }

    @Test
    void batch20ClassesGenericRecreateProducesInstance() {
        for (CodecDeletionCandidate candidate : BATCH20_DELETED_CODECS) {
            ObjectInstance result = invokeGenericRecreate(candidate.fqn(), 0x120, 0x240, candidate.gameId());
            assertNotNull(result, "genericRecreate must return non-null for " + candidate.fqn());
            assertEquals(candidate.fqn(), result.getClass().getName(),
                    "genericRecreate must return the same concrete class for " + candidate.fqn());
        }
    }

    @Test
    void batch20ClassesRoundTripPassedWithoutCodec() {
        for (CodecDeletionCandidate candidate : BATCH20_DELETED_CODECS) {
            RoundTripSweepResult result = RewindRoundTripHarness.probeClass(candidate.fqn());
            assertInstanceOf(RoundTripSweepResult.Passed.class, result,
                    candidate.fqn()
                            + " must round-trip as Passed via RewindRecreatable path (no codec); got: "
                            + result);
        }
    }

    // =====================================================================
    // Batch 21: session-verified no-ref S3K nested transient children
    // =====================================================================

    @Test
    void batch21ClassesAllImplementRewindRecreatable() {
        for (CodecDeletionCandidate candidate : BATCH21_DELETED_CODECS) {
            Class<?> cls;
            try {
                cls = Class.forName(candidate.fqn());
            } catch (ClassNotFoundException e) {
                throw new AssertionError(e);
            }
            assertTrue(RewindRecreatable.class.isAssignableFrom(cls),
                    candidate.fqn() + " must implement RewindRecreatable (codec deleted in batch 21)");
        }
    }

    @Test
    void batch21ClassesHaveNoRegisteredCodec() {
        for (CodecDeletionCandidate candidate : BATCH21_DELETED_CODECS) {
            assertFalse(hasRegisteredDynamicCodec(candidate.fqn()),
                    candidate.fqn() + " must have NO registered dynamic rewind codec after batch-21 deletion; "
                            + "it should round-trip purely via genericRecreate Path 1");
        }
    }

    @Test
    void batch21ClassesGenericRecreateProducesInstance() {
        for (CodecDeletionCandidate candidate : BATCH21_DELETED_CODECS) {
            ObjectInstance result = invokeGenericRecreate(candidate.fqn(), 0x120, 0x240, candidate.gameId());
            assertNotNull(result, "genericRecreate must return non-null for " + candidate.fqn());
            assertEquals(candidate.fqn(), result.getClass().getName(),
                    "genericRecreate must return the same concrete class for " + candidate.fqn());
        }
    }

    @Test
    void batch21ClassesRoundTripPassedWithoutCodec() {
        for (CodecDeletionCandidate candidate : BATCH21_DELETED_CODECS) {
            RoundTripSweepResult result = RewindRoundTripHarness.probeClass(candidate.fqn());
            assertInstanceOf(RoundTripSweepResult.Passed.class, result,
                    candidate.fqn()
                            + " must round-trip as Passed via RewindRecreatable path (no codec); got: "
                            + result);
        }
    }

    // =====================================================================
    // Batch 22: S2 self-contained projectile/effect dynamics
    // =====================================================================

    @Test
    void batch22ClassesAllImplementRewindRecreatable() {
        for (CodecDeletionCandidate candidate : BATCH22_DELETED_CODECS) {
            Class<?> cls;
            try {
                cls = Class.forName(candidate.fqn());
            } catch (ClassNotFoundException e) {
                throw new AssertionError(e);
            }
            assertTrue(RewindRecreatable.class.isAssignableFrom(cls),
                    candidate.fqn() + " must implement RewindRecreatable (codec deleted in batch 22)");
        }
    }

    @Test
    void batch22ClassesHaveNoRegisteredCodec() {
        for (CodecDeletionCandidate candidate : BATCH22_DELETED_CODECS) {
            assertFalse(hasRegisteredDynamicCodec(candidate.fqn()),
                    candidate.fqn() + " must have NO registered dynamic rewind codec after batch-22 deletion; "
                            + "it should round-trip purely via genericRecreate Path 1");
        }
    }

    @Test
    void batch22ClassesGenericRecreateProducesInstance() {
        for (CodecDeletionCandidate candidate : BATCH22_DELETED_CODECS) {
            ObjectInstance result = invokeGenericRecreate(candidate.fqn(), 0x120, 0x240, candidate.gameId());
            assertNotNull(result, "genericRecreate must return non-null for " + candidate.fqn());
            assertEquals(candidate.fqn(), result.getClass().getName(),
                    "genericRecreate must return the same concrete class for " + candidate.fqn());
        }
    }

    @Test
    void batch22ClassesRoundTripPassedWithoutCodec() {
        for (CodecDeletionCandidate candidate : BATCH22_DELETED_CODECS) {
            RoundTripSweepResult result = RewindRoundTripHarness.probeClass(candidate.fqn());
            assertInstanceOf(RoundTripSweepResult.Passed.class, result,
                    candidate.fqn()
                            + " must round-trip as Passed via RewindRecreatable path (no codec); got: "
                            + result);
        }
    }

    // =====================================================================
    // Batch 23: S2 scalar-only child projectiles/hazards
    // =====================================================================

    @Test
    void batch23ClassesAllImplementRewindRecreatable() {
        for (CodecDeletionCandidate candidate : BATCH23_DELETED_CODECS) {
            Class<?> cls;
            try {
                cls = Class.forName(candidate.fqn());
            } catch (ClassNotFoundException e) {
                throw new AssertionError(e);
            }
            assertTrue(RewindRecreatable.class.isAssignableFrom(cls),
                    candidate.fqn() + " must implement RewindRecreatable (codec deleted in batch 23)");
        }
    }

    @Test
    void batch23ClassesHaveNoRegisteredCodec() {
        for (CodecDeletionCandidate candidate : BATCH23_DELETED_CODECS) {
            assertFalse(hasRegisteredDynamicCodec(candidate.fqn()),
                    candidate.fqn() + " must have NO registered dynamic rewind codec after batch-23 deletion; "
                            + "it should round-trip purely via genericRecreate Path 1");
        }
    }

    @Test
    void batch23ClassesGenericRecreateProducesInstance() {
        for (CodecDeletionCandidate candidate : BATCH23_DELETED_CODECS) {
            ObjectInstance result = invokeGenericRecreate(candidate.fqn(), 0x120, 0x240, candidate.gameId());
            assertNotNull(result, "genericRecreate must return non-null for " + candidate.fqn());
            assertEquals(candidate.fqn(), result.getClass().getName(),
                    "genericRecreate must return the same concrete class for " + candidate.fqn());
        }
    }

    @Test
    void batch23ClassesRoundTripPassedWithoutCodec() {
        for (CodecDeletionCandidate candidate : BATCH23_DELETED_CODECS) {
            RoundTripSweepResult result = RewindRoundTripHarness.probeClass(candidate.fqn());
            assertInstanceOf(RoundTripSweepResult.Passed.class, result,
                    candidate.fqn()
                            + " must round-trip as Passed via RewindRecreatable path (no codec); got: "
                            + result);
        }
    }

    // =====================================================================
    // Batch 24: S2 scalar-only boss hazards
    // =====================================================================

    @Test
    void batch24ClassesAllImplementRewindRecreatable() {
        for (CodecDeletionCandidate candidate : BATCH24_DELETED_CODECS) {
            Class<?> cls;
            try {
                cls = Class.forName(candidate.fqn());
            } catch (ClassNotFoundException e) {
                throw new AssertionError(e);
            }
            assertTrue(RewindRecreatable.class.isAssignableFrom(cls),
                    candidate.fqn() + " must implement RewindRecreatable (codec deleted in batch 24)");
        }
    }

    @Test
    void batch24ClassesHaveNoRegisteredCodec() {
        for (CodecDeletionCandidate candidate : BATCH24_DELETED_CODECS) {
            assertFalse(hasRegisteredDynamicCodec(candidate.fqn()),
                    candidate.fqn() + " must have NO registered dynamic rewind codec after batch-24 deletion; "
                            + "it should round-trip purely via genericRecreate Path 1");
        }
    }

    @Test
    void batch24ClassesGenericRecreateProducesInstance() {
        for (CodecDeletionCandidate candidate : BATCH24_DELETED_CODECS) {
            ObjectInstance result = invokeGenericRecreate(candidate.fqn(), 0x120, 0x240, candidate.gameId());
            assertNotNull(result, "genericRecreate must return non-null for " + candidate.fqn());
            assertEquals(candidate.fqn(), result.getClass().getName(),
                    "genericRecreate must return the same concrete class for " + candidate.fqn());
        }
    }

    @Test
    void batch24ClassesRoundTripPassedWithoutCodec() {
        for (CodecDeletionCandidate candidate : BATCH24_DELETED_CODECS) {
            RoundTripSweepResult result = RewindRoundTripHarness.probeClass(candidate.fqn());
            assertInstanceOf(RoundTripSweepResult.Passed.class, result,
                    candidate.fqn()
                            + " must round-trip as Passed via RewindRecreatable path (no codec); got: "
                            + result);
        }
    }

    // =====================================================================
    // Batch 25: S2 scalar-only no-ref dynamics
    // =====================================================================

    @Test
    void batch25ClassesAllImplementRewindRecreatable() {
        for (CodecDeletionCandidate candidate : BATCH25_DELETED_CODECS) {
            Class<?> cls;
            try {
                cls = Class.forName(candidate.fqn());
            } catch (ClassNotFoundException e) {
                throw new AssertionError(e);
            }
            assertTrue(RewindRecreatable.class.isAssignableFrom(cls),
                    candidate.fqn() + " must implement RewindRecreatable (codec deleted in batch 25)");
        }
    }

    @Test
    void batch25ClassesHaveNoRegisteredCodec() {
        for (CodecDeletionCandidate candidate : BATCH25_DELETED_CODECS) {
            assertFalse(hasRegisteredDynamicCodec(candidate.fqn()),
                    candidate.fqn() + " must have NO registered dynamic rewind codec after batch-25 deletion; "
                            + "it should round-trip purely via genericRecreate Path 1");
        }
    }

    @Test
    void batch25ClassesGenericRecreateProducesInstance() {
        for (CodecDeletionCandidate candidate : BATCH25_DELETED_CODECS) {
            ObjectInstance result = invokeGenericRecreate(candidate.fqn(), 0x120, 0x240, candidate.gameId());
            assertNotNull(result, "genericRecreate must return non-null for " + candidate.fqn());
            assertEquals(candidate.fqn(), result.getClass().getName(),
                    "genericRecreate must return the same concrete class for " + candidate.fqn());
        }
    }

    @Test
    void batch25ClassesRoundTripPassedWithoutCodec() {
        for (CodecDeletionCandidate candidate : BATCH25_DELETED_CODECS) {
            RoundTripSweepResult result = RewindRoundTripHarness.probeClass(candidate.fqn());
            assertInstanceOf(RoundTripSweepResult.Passed.class, result,
                    candidate.fqn()
                            + " must round-trip as Passed via RewindRecreatable path (no codec); got: "
                            + result);
        }
    }

    @Test
    void batch25ConstructorScalarsAreMutableForCompactRestore() {
        for (MutableFieldCoverageCandidate candidate : BATCH25_MUTABLE_FIELDS) {
            Class<?> cls = loadClass(candidate.fqn());
            for (String fieldName : candidate.fieldNames()) {
                try {
                    var field = findField(cls, fieldName);
                    assertFalse(Modifier.isFinal(field.getModifiers()),
                            cls.getName() + "#" + fieldName
                                    + " must be mutable so compact restore can replay captured scalars");
                } catch (NoSuchFieldException e) {
                    throw new AssertionError("Missing scalar field " + cls.getName() + "#" + fieldName, e);
                }
            }
        }
    }

    @Test
    void batch33ConstructorScalarsAreMutableForCompactRestore() {
        for (MutableFieldCoverageCandidate candidate : BATCH33_MUTABLE_FIELDS) {
            Class<?> cls = loadClass(candidate.fqn());
            for (String fieldName : candidate.fieldNames()) {
                try {
                    var field = findField(cls, fieldName);
                    assertFalse(Modifier.isFinal(field.getModifiers()),
                            cls.getName() + "#" + fieldName
                                    + " must be mutable so compact restore can replay captured scalars");
                } catch (NoSuchFieldException e) {
                    throw new AssertionError("Missing scalar field " + cls.getName() + "#" + fieldName, e);
                }
            }
        }
    }

    @Test
    void batch134S1GraphConstructorScalarsAreMutableForCompactRestore() {
        for (MutableFieldCoverageCandidate candidate : BATCH134_MUTABLE_FIELDS) {
            Class<?> cls = loadClass(candidate.fqn());
            for (String fieldName : candidate.fieldNames()) {
                try {
                    var field = findField(cls, fieldName);
                    assertFalse(Modifier.isFinal(field.getModifiers()),
                            cls.getName() + "#" + fieldName
                                    + " must be mutable so compact restore can replay captured scalars");
                } catch (NoSuchFieldException e) {
                    throw new AssertionError("Missing scalar field " + cls.getName() + "#" + fieldName, e);
                }
            }
        }
    }

    @Test
    void batch135S2LauncherConstructorScalarsAreMutableForCompactRestore() {
        for (MutableFieldCoverageCandidate candidate : BATCH135_MUTABLE_FIELDS) {
            Class<?> cls = loadClass(candidate.fqn());
            for (String fieldName : candidate.fieldNames()) {
                try {
                    var field = findField(cls, fieldName);
                    assertFalse(Modifier.isFinal(field.getModifiers()),
                            cls.getName() + "#" + fieldName
                                    + " must be mutable so compact restore can replay captured scalars");
                } catch (NoSuchFieldException e) {
                    throw new AssertionError("Missing scalar field " + cls.getName() + "#" + fieldName, e);
                }
            }
        }
    }

    @Test
    void batch136S3kAizConstructorScalarsAreMutableForCompactRestore() {
        for (MutableFieldCoverageCandidate candidate : BATCH136_MUTABLE_FIELDS) {
            Class<?> cls = loadClass(candidate.fqn());
            for (String fieldName : candidate.fieldNames()) {
                try {
                    var field = findField(cls, fieldName);
                    assertFalse(Modifier.isFinal(field.getModifiers()),
                            cls.getName() + "#" + fieldName
                                    + " must be mutable so compact restore can replay captured scalars");
                } catch (NoSuchFieldException e) {
                    throw new AssertionError("Missing scalar field " + cls.getName() + "#" + fieldName, e);
                }
            }
        }
    }

    @Test
    void batch137S3kAizConstructorScalarsAreMutableForCompactRestore() {
        for (MutableFieldCoverageCandidate candidate : BATCH137_MUTABLE_FIELDS) {
            Class<?> cls = loadClass(candidate.fqn());
            for (String fieldName : candidate.fieldNames()) {
                try {
                    var field = findField(cls, fieldName);
                    assertFalse(Modifier.isFinal(field.getModifiers()),
                            cls.getName() + "#" + fieldName
                                    + " must be mutable so compact restore can replay captured scalars");
                } catch (NoSuchFieldException e) {
                    throw new AssertionError("Missing scalar field " + cls.getName() + "#" + fieldName, e);
                }
            }
        }
    }

    @Test
    void batch138S3kMechanismConstructorScalarsAreMutableForCompactRestore() {
        for (MutableFieldCoverageCandidate candidate : BATCH138_MUTABLE_FIELDS) {
            Class<?> cls = loadClass(candidate.fqn());
            for (String fieldName : candidate.fieldNames()) {
                try {
                    var field = findField(cls, fieldName);
                    assertFalse(Modifier.isFinal(field.getModifiers()),
                            cls.getName() + "#" + fieldName
                                    + " must be mutable so compact restore can replay captured scalars");
                } catch (NoSuchFieldException e) {
                    throw new AssertionError("Missing scalar field " + cls.getName() + "#" + fieldName, e);
                }
            }
        }
    }

    @Test
    void batch139S3kCnzMechanismConstructorScalarsAreMutableForCompactRestore() {
        for (MutableFieldCoverageCandidate candidate : BATCH139_MUTABLE_FIELDS) {
            Class<?> cls = loadClass(candidate.fqn());
            for (String fieldName : candidate.fieldNames()) {
                try {
                    var field = findField(cls, fieldName);
                    assertFalse(Modifier.isFinal(field.getModifiers()),
                            cls.getName() + "#" + fieldName
                                    + " must be mutable so compact restore can replay captured scalars");
                } catch (NoSuchFieldException e) {
                    throw new AssertionError("Missing scalar field " + cls.getName() + "#" + fieldName, e);
                }
            }
        }
    }

    @Test
    void batch140S3kSharedMechanismConstructorScalarsAreMutableForCompactRestore() {
        for (MutableFieldCoverageCandidate candidate : BATCH140_MUTABLE_FIELDS) {
            Class<?> cls = loadClass(candidate.fqn());
            for (String fieldName : candidate.fieldNames()) {
                try {
                    var field = findField(cls, fieldName);
                    assertFalse(Modifier.isFinal(field.getModifiers()),
                            cls.getName() + "#" + fieldName
                                    + " must be mutable so compact restore can replay captured scalars");
                } catch (NoSuchFieldException e) {
                    throw new AssertionError("Missing scalar field " + cls.getName() + "#" + fieldName, e);
                }
            }
        }
    }

    @Test
    void batch141S3kAizLrzMechanismConstructorScalarsAreMutableForCompactRestore() {
        for (MutableFieldCoverageCandidate candidate : BATCH141_MUTABLE_FIELDS) {
            Class<?> cls = loadClass(candidate.fqn());
            for (String fieldName : candidate.fieldNames()) {
                try {
                    var field = findField(cls, fieldName);
                    assertFalse(Modifier.isFinal(field.getModifiers()),
                            cls.getName() + "#" + fieldName
                                    + " must be mutable so compact restore can replay captured scalars");
                } catch (NoSuchFieldException e) {
                    throw new AssertionError("Missing scalar field " + cls.getName() + "#" + fieldName, e);
                }
            }
        }
    }

    @Test
    void batch142S3kHczBreakableBarConstructorScalarsAreMutableForCompactRestore() {
        for (MutableFieldCoverageCandidate candidate : BATCH142_MUTABLE_FIELDS) {
            Class<?> cls = loadClass(candidate.fqn());
            for (String fieldName : candidate.fieldNames()) {
                try {
                    var field = findField(cls, fieldName);
                    assertFalse(Modifier.isFinal(field.getModifiers()),
                            cls.getName() + "#" + fieldName
                                    + " must be mutable so compact restore can replay captured scalars");
                } catch (NoSuchFieldException e) {
                    throw new AssertionError("Missing scalar field " + cls.getName() + "#" + fieldName, e);
                }
            }
        }
    }

    @Test
    void batch143S3kHczConveyorMechanismConstructorScalarsAreMutableForCompactRestore() {
        for (MutableFieldCoverageCandidate candidate : BATCH143_MUTABLE_FIELDS) {
            Class<?> cls = loadClass(candidate.fqn());
            for (String fieldName : candidate.fieldNames()) {
                try {
                    var field = findField(cls, fieldName);
                    assertFalse(Modifier.isFinal(field.getModifiers()),
                            cls.getName() + "#" + fieldName
                                    + " must be mutable so compact restore can replay captured scalars");
                } catch (NoSuchFieldException e) {
                    throw new AssertionError("Missing scalar field " + cls.getName() + "#" + fieldName, e);
                }
            }
        }
    }

    @Test
    void batch144S3kBadnikInheritedConstructorScalarsAreMutableForCompactRestore() {
        for (MutableFieldCoverageCandidate candidate : BATCH144_MUTABLE_FIELDS) {
            Class<?> cls = loadClass(candidate.fqn());
            for (String fieldName : candidate.fieldNames()) {
                try {
                    var field = findField(cls, fieldName);
                    assertFalse(Modifier.isFinal(field.getModifiers()),
                            cls.getName() + "#" + fieldName
                                    + " must be mutable so compact restore can replay captured scalars");
                } catch (NoSuchFieldException e) {
                    throw new AssertionError("Missing scalar field " + cls.getName() + "#" + fieldName, e);
                }
            }
        }
    }

    @Test
    void batch145S3kBadnikChildConstructorScalarsAreMutableForCompactRestore() {
        for (MutableFieldCoverageCandidate candidate : BATCH145_MUTABLE_FIELDS) {
            Class<?> cls = loadClass(candidate.fqn());
            for (String fieldName : candidate.fieldNames()) {
                try {
                    var field = findField(cls, fieldName);
                    assertFalse(Modifier.isFinal(field.getModifiers()),
                            cls.getName() + "#" + fieldName
                                    + " must be mutable so compact restore can replay captured scalars");
                } catch (NoSuchFieldException e) {
                    throw new AssertionError("Missing scalar field " + cls.getName() + "#" + fieldName, e);
                }
            }
        }
    }

    @Test
    void batch146S3kCnzConstructorScalarsAreMutableForCompactRestore() {
        for (MutableFieldCoverageCandidate candidate : BATCH146_MUTABLE_FIELDS) {
            Class<?> cls = loadClass(candidate.fqn());
            for (String fieldName : candidate.fieldNames()) {
                try {
                    var field = findField(cls, fieldName);
                    assertFalse(Modifier.isFinal(field.getModifiers()),
                            cls.getName() + "#" + fieldName
                                    + " must be mutable so compact restore can replay captured scalars");
                } catch (NoSuchFieldException e) {
                    throw new AssertionError("Missing scalar field " + cls.getName() + "#" + fieldName, e);
                }
            }
        }
    }

    @Test
    void batch147S3kGravityDebrisConstructorScalarsAreMutableForCompactRestore() {
        for (MutableFieldCoverageCandidate candidate : BATCH147_MUTABLE_FIELDS) {
            Class<?> cls = loadClass(candidate.fqn());
            for (String fieldName : candidate.fieldNames()) {
                try {
                    var field = findField(cls, fieldName);
                    assertFalse(Modifier.isFinal(field.getModifiers()),
                            cls.getName() + "#" + fieldName
                                    + " must be mutable so compact restore can replay captured scalars");
                } catch (NoSuchFieldException e) {
                    throw new AssertionError("Missing scalar field " + cls.getName() + "#" + fieldName, e);
                }
            }
        }
    }

    @Test
    void batch148S3kHczCgzFanConstructorScalarsAreMutableForCompactRestore() {
        for (MutableFieldCoverageCandidate candidate : BATCH148_MUTABLE_FIELDS) {
            Class<?> cls = loadClass(candidate.fqn());
            for (String fieldName : candidate.fieldNames()) {
                try {
                    var field = findField(cls, fieldName);
                    assertFalse(Modifier.isFinal(field.getModifiers()),
                            cls.getName() + "#" + fieldName
                                    + " must be mutable so compact restore can replay captured scalars");
                } catch (NoSuchFieldException e) {
                    throw new AssertionError("Missing scalar field " + cls.getName() + "#" + fieldName, e);
                }
            }
        }
    }

    @Test
    void batch149S3kIczFreezerConstructorScalarsAreMutableForCompactRestore() {
        for (MutableFieldCoverageCandidate candidate : BATCH149_MUTABLE_FIELDS) {
            Class<?> cls = loadClass(candidate.fqn());
            for (String fieldName : candidate.fieldNames()) {
                try {
                    var field = findField(cls, fieldName);
                    assertFalse(Modifier.isFinal(field.getModifiers()),
                            cls.getName() + "#" + fieldName
                                    + " must be mutable so compact restore can replay captured scalars");
                } catch (NoSuchFieldException e) {
                    throw new AssertionError("Missing scalar field " + cls.getName() + "#" + fieldName, e);
                }
            }
        }
    }

    @Test
    void batch150S3kCorkFloorConstructorScalarsAreMutableForCompactRestore() {
        for (MutableFieldCoverageCandidate candidate : BATCH150_MUTABLE_FIELDS) {
            Class<?> cls = loadClass(candidate.fqn());
            for (String fieldName : candidate.fieldNames()) {
                try {
                    var field = findField(cls, fieldName);
                    assertFalse(Modifier.isFinal(field.getModifiers()),
                            cls.getName() + "#" + fieldName
                                    + " must be mutable so compact restore can replay captured scalars");
                } catch (NoSuchFieldException e) {
                    throw new AssertionError("Missing scalar field " + cls.getName() + "#" + fieldName, e);
                }
            }
        }
    }

    @Test
    void batch151S3kCollapsingBridgeChildScalarsAreMutableForCompactRestore() {
        for (MutableFieldCoverageCandidate candidate : BATCH151_MUTABLE_FIELDS) {
            Class<?> cls = loadClass(candidate.fqn());
            for (String fieldName : candidate.fieldNames()) {
                try {
                    var field = findField(cls, fieldName);
                    assertFalse(Modifier.isFinal(field.getModifiers()),
                            cls.getName() + "#" + fieldName
                                    + " must be mutable so compact restore can replay captured scalars");
                } catch (NoSuchFieldException e) {
                    throw new AssertionError("Missing scalar field " + cls.getName() + "#" + fieldName, e);
                }
            }
        }
    }

    @Test
    void batch152S3kMgzTriggerPlatformConstructorScalarsAreMutableForCompactRestore() {
        for (MutableFieldCoverageCandidate candidate : BATCH152_MUTABLE_FIELDS) {
            Class<?> cls = loadClass(candidate.fqn());
            for (String fieldName : candidate.fieldNames()) {
                try {
                    var field = findField(cls, fieldName);
                    assertFalse(Modifier.isFinal(field.getModifiers()),
                            cls.getName() + "#" + fieldName
                                    + " must be mutable so compact restore can replay captured scalars");
                } catch (NoSuchFieldException e) {
                    throw new AssertionError("Missing scalar field " + cls.getName() + "#" + fieldName, e);
                }
            }
        }
    }

    @Test
    void batch153S3kLbzMovingPlatformConstructorScalarsAreMutableForCompactRestore() {
        for (MutableFieldCoverageCandidate candidate : BATCH153_MUTABLE_FIELDS) {
            Class<?> cls = loadClass(candidate.fqn());
            for (String fieldName : candidate.fieldNames()) {
                try {
                    var field = findField(cls, fieldName);
                    assertFalse(Modifier.isFinal(field.getModifiers()),
                            cls.getName() + "#" + fieldName
                                    + " must be mutable so compact restore can replay captured scalars");
                } catch (NoSuchFieldException e) {
                    throw new AssertionError("Missing scalar field " + cls.getName() + "#" + fieldName, e);
                }
            }
        }
    }

    @Test
    void batch154S3kIczSwingingPlatformConstructorScalarsAreMutableForCompactRestore() {
        for (MutableFieldCoverageCandidate candidate : BATCH154_MUTABLE_FIELDS) {
            Class<?> cls = loadClass(candidate.fqn());
            for (String fieldName : candidate.fieldNames()) {
                try {
                    var field = findField(cls, fieldName);
                    assertFalse(Modifier.isFinal(field.getModifiers()),
                            cls.getName() + "#" + fieldName
                                    + " must be mutable so compact restore can replay captured scalars");
                } catch (NoSuchFieldException e) {
                    throw new AssertionError("Missing scalar field " + cls.getName() + "#" + fieldName, e);
                }
            }
        }
    }

    @Test
    void batch155S3kMgzSwingingSpikeBallConstructorScalarsAreMutableForCompactRestore() {
        for (MutableFieldCoverageCandidate candidate : BATCH155_MUTABLE_FIELDS) {
            Class<?> cls = loadClass(candidate.fqn());
            for (String fieldName : candidate.fieldNames()) {
                try {
                    var field = findField(cls, fieldName);
                    assertFalse(Modifier.isFinal(field.getModifiers()),
                            cls.getName() + "#" + fieldName
                                    + " must be mutable so compact restore can replay captured scalars");
                } catch (NoSuchFieldException e) {
                    throw new AssertionError("Missing scalar field " + cls.getName() + "#" + fieldName, e);
                }
            }
        }
    }

    @Test
    void batch156S3kButtonConstructorScalarsAreMutableForCompactRestore() {
        for (MutableFieldCoverageCandidate candidate : BATCH156_MUTABLE_FIELDS) {
            Class<?> cls = loadClass(candidate.fqn());
            for (String fieldName : candidate.fieldNames()) {
                try {
                    var field = findField(cls, fieldName);
                    assertFalse(Modifier.isFinal(field.getModifiers()),
                            cls.getName() + "#" + fieldName
                                    + " must be mutable so compact restore can replay captured scalars");
                } catch (NoSuchFieldException e) {
                    throw new AssertionError("Missing scalar field " + cls.getName() + "#" + fieldName, e);
                }
            }
        }
    }

    @Test
    void batch157S3kHczSpinningColumnConstructorScalarsAreMutableForCompactRestore() {
        for (MutableFieldCoverageCandidate candidate : BATCH157_MUTABLE_FIELDS) {
            Class<?> cls = loadClass(candidate.fqn());
            for (String fieldName : candidate.fieldNames()) {
                try {
                    var field = findField(cls, fieldName);
                    assertFalse(Modifier.isFinal(field.getModifiers()),
                            cls.getName() + "#" + fieldName
                                    + " must be mutable so compact restore can replay captured scalars");
                } catch (NoSuchFieldException e) {
                    throw new AssertionError("Missing scalar field " + cls.getName() + "#" + fieldName, e);
                }
            }
        }
    }

    @Test
    void batch158S3kHczTwistingLoopConstructorScalarsAreMutableForCompactRestore() {
        for (MutableFieldCoverageCandidate candidate : BATCH158_MUTABLE_FIELDS) {
            Class<?> cls = loadClass(candidate.fqn());
            for (String fieldName : candidate.fieldNames()) {
                try {
                    var field = findField(cls, fieldName);
                    assertFalse(Modifier.isFinal(field.getModifiers()),
                            cls.getName() + "#" + fieldName
                                    + " must be mutable so compact restore can replay captured scalars");
                } catch (NoSuchFieldException e) {
                    throw new AssertionError("Missing scalar field " + cls.getName() + "#" + fieldName, e);
                }
            }
        }
    }

    @Test
    void batch159S3kHczWaterEffectConstructorScalarsAreMutableForCompactRestore() {
        for (MutableFieldCoverageCandidate candidate : BATCH159_MUTABLE_FIELDS) {
            Class<?> cls = loadClass(candidate.fqn());
            for (String fieldName : candidate.fieldNames()) {
                try {
                    var field = findField(cls, fieldName);
                    assertFalse(Modifier.isFinal(field.getModifiers()),
                            cls.getName() + "#" + fieldName
                                    + " must be mutable so compact restore can replay captured scalars");
                } catch (NoSuchFieldException e) {
                    throw new AssertionError("Missing scalar field " + cls.getName() + "#" + fieldName, e);
                }
            }
        }
    }

    @Test
    void batch160S3kHczWaterWallConstructorScalarsAreMutableForCompactRestore() {
        for (MutableFieldCoverageCandidate candidate : BATCH160_MUTABLE_FIELDS) {
            Class<?> cls = loadClass(candidate.fqn());
            for (String fieldName : candidate.fieldNames()) {
                try {
                    var field = findField(cls, fieldName);
                    assertFalse(Modifier.isFinal(field.getModifiers()),
                            cls.getName() + "#" + fieldName
                                    + " must be mutable so compact restore can replay captured scalars");
                } catch (NoSuchFieldException e) {
                    throw new AssertionError("Missing scalar field " + cls.getName() + "#" + fieldName, e);
                }
            }
        }
    }

    @Test
    void batch161S3kHcz2CutsceneButtonConstructorScalarsAreMutableForCompactRestore() {
        for (MutableFieldCoverageCandidate candidate : BATCH161_MUTABLE_FIELDS) {
            Class<?> cls = loadClass(candidate.fqn());
            for (String fieldName : candidate.fieldNames()) {
                try {
                    var field = findField(cls, fieldName);
                    assertFalse(Modifier.isFinal(field.getModifiers()),
                            cls.getName() + "#" + fieldName
                                    + " must be mutable so compact restore can replay captured scalars");
                } catch (NoSuchFieldException e) {
                    throw new AssertionError("Missing scalar field " + cls.getName() + "#" + fieldName, e);
                }
            }
        }
    }

    @Test
    void batch162S3kIczIceObjectConstructorScalarsAreMutableForCompactRestore() {
        for (MutableFieldCoverageCandidate candidate : BATCH162_MUTABLE_FIELDS) {
            Class<?> cls = loadClass(candidate.fqn());
            for (String fieldName : candidate.fieldNames()) {
                try {
                    var field = findField(cls, fieldName);
                    assertFalse(Modifier.isFinal(field.getModifiers()),
                            cls.getName() + "#" + fieldName
                                    + " must be mutable so compact restore can replay captured scalars");
                } catch (NoSuchFieldException e) {
                    throw new AssertionError("Missing scalar field " + cls.getName() + "#" + fieldName, e);
                }
            }
        }
    }

    @Test
    void batch163S3kIczPlatformHazardConstructorScalarsAreMutableForCompactRestore() {
        for (MutableFieldCoverageCandidate candidate : BATCH163_MUTABLE_FIELDS) {
            Class<?> cls = loadClass(candidate.fqn());
            for (String fieldName : candidate.fieldNames()) {
                try {
                    var field = findField(cls, fieldName);
                    assertFalse(Modifier.isFinal(field.getModifiers()),
                            cls.getName() + "#" + fieldName
                                    + " must be mutable so compact restore can replay captured scalars");
                } catch (NoSuchFieldException e) {
                    throw new AssertionError("Missing scalar field " + cls.getName() + "#" + fieldName, e);
                }
            }
        }
    }

    @Test
    void batch164S3kIczSnowConstructorScalarsAreMutableForCompactRestore() {
        for (MutableFieldCoverageCandidate candidate : BATCH164_MUTABLE_FIELDS) {
            Class<?> cls = loadClass(candidate.fqn());
            for (String fieldName : candidate.fieldNames()) {
                try {
                    var field = findField(cls, fieldName);
                    assertFalse(Modifier.isFinal(field.getModifiers()),
                            cls.getName() + "#" + fieldName
                                    + " must be mutable so compact restore can replay captured scalars");
                } catch (NoSuchFieldException e) {
                    throw new AssertionError("Missing scalar field " + cls.getName() + "#" + fieldName, e);
                }
            }
        }
    }

    @Test
    void batch165S3kIczLbzConstructorScalarsAreMutableForCompactRestore() {
        for (MutableFieldCoverageCandidate candidate : BATCH165_MUTABLE_FIELDS) {
            Class<?> cls = loadClass(candidate.fqn());
            for (String fieldName : candidate.fieldNames()) {
                try {
                    var field = findField(cls, fieldName);
                    assertFalse(Modifier.isFinal(field.getModifiers()),
                            cls.getName() + "#" + fieldName
                                    + " must be mutable so compact restore can replay captured scalars");
                } catch (NoSuchFieldException e) {
                    throw new AssertionError("Missing scalar field " + cls.getName() + "#" + fieldName, e);
                }
            }
        }
    }

    @Test
    void batch166S3kLbzLauncherGrappleDrumScalarsAreMutableForCompactRestore() {
        for (MutableFieldCoverageCandidate candidate : BATCH166_MUTABLE_FIELDS) {
            Class<?> cls = loadClass(candidate.fqn());
            for (String fieldName : candidate.fieldNames()) {
                try {
                    var field = findField(cls, fieldName);
                    assertFalse(Modifier.isFinal(field.getModifiers()),
                            cls.getName() + "#" + fieldName
                                    + " must be mutable so compact restore can replay captured scalars");
                } catch (NoSuchFieldException e) {
                    throw new AssertionError("Missing scalar field " + cls.getName() + "#" + fieldName, e);
                }
            }
        }
    }

    @Test
    void batch167S3kMgzTriggerPillarPlatformScalarsAreMutableForCompactRestore() {
        for (MutableFieldCoverageCandidate candidate : BATCH167_MUTABLE_FIELDS) {
            Class<?> cls = loadClass(candidate.fqn());
            for (String fieldName : candidate.fieldNames()) {
                try {
                    var field = findField(cls, fieldName);
                    assertFalse(Modifier.isFinal(field.getModifiers()),
                            cls.getName() + "#" + fieldName
                                    + " must be mutable so compact restore can replay captured scalars");
                } catch (NoSuchFieldException e) {
                    throw new AssertionError("Missing scalar field " + cls.getName() + "#" + fieldName, e);
                }
            }
        }
    }

    @Test
    void batch168S3kMgzSwingLoopDebrisScalarsAreMutableForCompactRestore() {
        for (MutableFieldCoverageCandidate candidate : BATCH168_MUTABLE_FIELDS) {
            Class<?> cls = loadClass(candidate.fqn());
            for (String fieldName : candidate.fieldNames()) {
                try {
                    var field = findField(cls, fieldName);
                    assertFalse(Modifier.isFinal(field.getModifiers()),
                            cls.getName() + "#" + fieldName
                                    + " must be mutable so compact restore can replay captured scalars");
                } catch (NoSuchFieldException e) {
                    throw new AssertionError("Missing scalar field " + cls.getName() + "#" + fieldName, e);
                }
            }
        }
    }

    @Test
    void batch169S3kMhzMechanismScalarsAreMutableForCompactRestore() {
        for (MutableFieldCoverageCandidate candidate : BATCH169_MUTABLE_FIELDS) {
            Class<?> cls = loadClass(candidate.fqn());
            for (String fieldName : candidate.fieldNames()) {
                try {
                    var field = findField(cls, fieldName);
                    assertFalse(Modifier.isFinal(field.getModifiers()),
                            cls.getName() + "#" + fieldName
                                    + " must be mutable so compact restore can replay captured scalars");
                } catch (NoSuchFieldException e) {
                    throw new AssertionError("Missing scalar field " + cls.getName() + "#" + fieldName, e);
                }
            }
        }
    }

    @Test
    void batch170S3kBadnikCutsceneScalarsAreMutableForCompactRestore() {
        for (MutableFieldCoverageCandidate candidate : BATCH170_MUTABLE_FIELDS) {
            Class<?> cls = loadClass(candidate.fqn());
            for (String fieldName : candidate.fieldNames()) {
                try {
                    var field = findField(cls, fieldName);
                    assertFalse(Modifier.isFinal(field.getModifiers()),
                            cls.getName() + "#" + fieldName
                                    + " must be mutable so compact restore can replay captured scalars");
                } catch (NoSuchFieldException e) {
                    throw new AssertionError("Missing scalar field " + cls.getName() + "#" + fieldName, e);
                }
            }
        }
    }

    @Test
    void batch171S3kObjectScalarsAreMutableForCompactRestore() {
        for (MutableFieldCoverageCandidate candidate : BATCH171_MUTABLE_FIELDS) {
            Class<?> cls = loadClass(candidate.fqn());
            for (String fieldName : candidate.fieldNames()) {
                try {
                    var field = findField(cls, fieldName);
                    assertFalse(Modifier.isFinal(field.getModifiers()),
                            cls.getName() + "#" + fieldName
                                    + " must be mutable so compact restore can replay captured scalars");
                } catch (NoSuchFieldException e) {
                    throw new AssertionError("Missing scalar field " + cls.getName() + "#" + fieldName, e);
                }
            }
        }
    }

    @Test
    void batch172S3kControllerPlatformStarpostScalarsAreMutableForCompactRestore() {
        for (MutableFieldCoverageCandidate candidate : BATCH172_MUTABLE_FIELDS) {
            Class<?> cls = loadClass(candidate.fqn());
            for (String fieldName : candidate.fieldNames()) {
                try {
                    var field = findField(cls, fieldName);
                    assertFalse(Modifier.isFinal(field.getModifiers()),
                            cls.getName() + "#" + fieldName
                                    + " must be mutable so compact restore can replay captured scalars");
                } catch (NoSuchFieldException e) {
                    throw new AssertionError("Missing scalar field " + cls.getName() + "#" + fieldName, e);
                }
            }
        }
    }

    @Test
    void batch173S1S2ObjectScalarsAreMutableForCompactRestore() {
        for (MutableFieldCoverageCandidate candidate : BATCH173_MUTABLE_FIELDS) {
            Class<?> cls = loadClass(candidate.fqn());
            for (String fieldName : candidate.fieldNames()) {
                try {
                    var field = findField(cls, fieldName);
                    assertFalse(Modifier.isFinal(field.getModifiers()),
                            cls.getName() + "#" + fieldName
                                    + " must be mutable so compact restore can replay captured scalars");
                } catch (NoSuchFieldException e) {
                    throw new AssertionError("Missing scalar field " + cls.getName() + "#" + fieldName, e);
                }
            }
        }
    }

    @Test
    void batch174S3kMechanismScalarsAreMutableForCompactRestore() {
        for (MutableFieldCoverageCandidate candidate : BATCH174_MUTABLE_FIELDS) {
            Class<?> cls = loadClass(candidate.fqn());
            for (String fieldName : candidate.fieldNames()) {
                try {
                    var field = findField(cls, fieldName);
                    assertFalse(Modifier.isFinal(field.getModifiers()),
                            cls.getName() + "#" + fieldName
                                    + " must be mutable so compact restore can replay captured scalars");
                } catch (NoSuchFieldException e) {
                    throw new AssertionError("Missing scalar field " + cls.getName() + "#" + fieldName, e);
                }
            }
        }
    }

    @Test
    void batch175EffectFragmentAndCapsuleScalarsAreMutableForCompactRestore() {
        for (MutableFieldCoverageCandidate candidate : BATCH175_MUTABLE_FIELDS) {
            Class<?> cls = loadClass(candidate.fqn());
            for (String fieldName : candidate.fieldNames()) {
                try {
                    var field = findField(cls, fieldName);
                    assertFalse(Modifier.isFinal(field.getModifiers()),
                            cls.getName() + "#" + fieldName
                                    + " must be mutable so compact restore can replay captured scalars");
                } catch (NoSuchFieldException e) {
                    throw new AssertionError("Missing scalar field " + cls.getName() + "#" + fieldName, e);
                }
            }
        }
    }

    @Test
    void batch176BridgeAndParticleScalarsAreMutableForCompactRestore() {
        for (MutableFieldCoverageCandidate candidate : BATCH176_MUTABLE_FIELDS) {
            Class<?> cls = loadClass(candidate.fqn());
            for (String fieldName : candidate.fieldNames()) {
                try {
                    var field = findField(cls, fieldName);
                    assertFalse(Modifier.isFinal(field.getModifiers()),
                            cls.getName() + "#" + fieldName
                                    + " must be mutable so compact restore can replay captured scalars");
                } catch (NoSuchFieldException e) {
                    throw new AssertionError("Missing scalar field " + cls.getName() + "#" + fieldName, e);
                }
            }
        }
    }

    @Test
    void batch177GumballAndMgzHelperScalarsAreMutableForCompactRestore() {
        for (MutableFieldCoverageCandidate candidate : BATCH177_MUTABLE_FIELDS) {
            Class<?> cls = loadClass(candidate.fqn());
            for (String fieldName : candidate.fieldNames()) {
                try {
                    var field = findField(cls, fieldName);
                    assertFalse(Modifier.isFinal(field.getModifiers()),
                            cls.getName() + "#" + fieldName
                                    + " must be mutable so compact restore can replay captured scalars");
                } catch (NoSuchFieldException e) {
                    throw new AssertionError("Missing scalar field " + cls.getName() + "#" + fieldName, e);
                }
            }
        }
    }

    @Test
    void batch178GraphHelperScalarsAreMutableForCompactRestore() {
        for (MutableFieldCoverageCandidate candidate : BATCH178_MUTABLE_FIELDS) {
            Class<?> cls = loadClass(candidate.fqn());
            for (String fieldName : candidate.fieldNames()) {
                try {
                    var field = findField(cls, fieldName);
                    assertFalse(Modifier.isFinal(field.getModifiers()),
                            cls.getName() + "#" + fieldName
                                    + " must be mutable so compact restore can replay captured scalars");
                } catch (NoSuchFieldException e) {
                    throw new AssertionError("Missing scalar field " + cls.getName() + "#" + fieldName, e);
                }
            }
        }
    }

    @Test
    void batch179MechanismGraphScalarsAreMutableForCompactRestore() {
        for (MutableFieldCoverageCandidate candidate : BATCH179_MUTABLE_FIELDS) {
            Class<?> cls = loadClass(candidate.fqn());
            for (String fieldName : candidate.fieldNames()) {
                try {
                    var field = findField(cls, fieldName);
                    assertFalse(Modifier.isFinal(field.getModifiers()),
                            cls.getName() + "#" + fieldName
                                    + " must be mutable so compact restore can replay captured scalars");
                } catch (NoSuchFieldException e) {
                    throw new AssertionError("Missing scalar field " + cls.getName() + "#" + fieldName, e);
                }
            }
        }
    }

    @Test
    void batch180MechanismRootScalarsAreMutableForCompactRestore() {
        for (MutableFieldCoverageCandidate candidate : BATCH180_MUTABLE_FIELDS) {
            Class<?> cls = loadClass(candidate.fqn());
            for (String fieldName : candidate.fieldNames()) {
                try {
                    var field = findField(cls, fieldName);
                    assertFalse(Modifier.isFinal(field.getModifiers()),
                            cls.getName() + "#" + fieldName
                                    + " must be mutable so compact restore can replay captured scalars");
                } catch (NoSuchFieldException e) {
                    throw new AssertionError("Missing scalar field " + cls.getName() + "#" + fieldName, e);
                }
            }
        }
    }

    @Test
    void batch181PulleyRootScalarsAreMutableForCompactRestore() {
        for (MutableFieldCoverageCandidate candidate : BATCH181_MUTABLE_FIELDS) {
            Class<?> cls = loadClass(candidate.fqn());
            for (String fieldName : candidate.fieldNames()) {
                try {
                    var field = findField(cls, fieldName);
                    assertFalse(Modifier.isFinal(field.getModifiers()),
                            cls.getName() + "#" + fieldName
                                    + " must be mutable so compact restore can replay captured scalars");
                } catch (NoSuchFieldException e) {
                    throw new AssertionError("Missing scalar field " + cls.getName() + "#" + fieldName, e);
                }
            }
        }
    }

    @Test
    void batch182LbzLauncherArmScalarsAreMutableForCompactRestore() {
        for (MutableFieldCoverageCandidate candidate : BATCH182_MUTABLE_FIELDS) {
            Class<?> cls = loadClass(candidate.fqn());
            for (String fieldName : candidate.fieldNames()) {
                try {
                    var field = findField(cls, fieldName);
                    assertFalse(Modifier.isFinal(field.getModifiers()),
                            cls.getName() + "#" + fieldName
                                    + " must be mutable so compact restore can replay captured scalars");
                } catch (NoSuchFieldException e) {
                    throw new AssertionError("Missing scalar field " + cls.getName() + "#" + fieldName, e);
                }
            }
        }
    }

    @Test
    void batch183LbzTubeElevatorScalarsAreMutableForCompactRestore() {
        for (MutableFieldCoverageCandidate candidate : BATCH183_MUTABLE_FIELDS) {
            Class<?> cls = loadClass(candidate.fqn());
            for (String fieldName : candidate.fieldNames()) {
                try {
                    var field = findField(cls, fieldName);
                    assertFalse(Modifier.isFinal(field.getModifiers()),
                            cls.getName() + "#" + fieldName
                                    + " must be mutable so compact restore can replay captured scalars");
                } catch (NoSuchFieldException e) {
                    throw new AssertionError("Missing scalar field " + cls.getName() + "#" + fieldName, e);
                }
            }
        }
    }

    // =====================================================================
    // Batch 26: shared S2 boss explosion dynamic
    // =====================================================================

    @Test
    void batch26ClassesAllImplementRewindRecreatable() {
        for (CodecDeletionCandidate candidate : BATCH26_DELETED_CODECS) {
            Class<?> cls;
            try {
                cls = Class.forName(candidate.fqn());
            } catch (ClassNotFoundException e) {
                throw new AssertionError(e);
            }
            assertTrue(RewindRecreatable.class.isAssignableFrom(cls),
                    candidate.fqn() + " must implement RewindRecreatable (codec deleted in batch 26)");
        }
    }

    @Test
    void batch26ClassesHaveNoRegisteredCodec() {
        for (CodecDeletionCandidate candidate : BATCH26_DELETED_CODECS) {
            assertFalse(hasRegisteredDynamicCodec(candidate.fqn(), candidate.gameId()),
                    candidate.fqn() + " must have NO registered dynamic rewind codec after batch-26 deletion; "
                            + "it should round-trip purely via genericRecreate Path 1");
        }
    }

    @Test
    void batch26ClassesGenericRecreateProducesInstance() {
        for (CodecDeletionCandidate candidate : BATCH26_DELETED_CODECS) {
            ObjectInstance result = invokeGenericRecreate(candidate.fqn(), 0x120, 0x240, candidate.gameId());
            assertNotNull(result, "genericRecreate must return non-null for " + candidate.fqn());
            assertEquals(candidate.fqn(), result.getClass().getName(),
                    "genericRecreate must return the same concrete class for " + candidate.fqn());
        }
    }

    @Test
    void batch26ClassesRoundTripPassedWithoutCodec() {
        for (CodecDeletionCandidate candidate : BATCH26_DELETED_CODECS) {
            RoundTripSweepResult result = RewindRoundTripHarness.probeClass(candidate.fqn());
            assertInstanceOf(RoundTripSweepResult.Passed.class, result,
                    candidate.fqn()
                            + " must round-trip as Passed via RewindRecreatable path (no codec); got: "
                            + result);
        }
    }

    // =====================================================================
    // Batch 27: S2 shared badnik projectile dynamic
    // =====================================================================

    @Test
    void batch27ClassesAllImplementRewindRecreatable() {
        for (CodecDeletionCandidate candidate : BATCH27_DELETED_CODECS) {
            Class<?> cls;
            try {
                cls = Class.forName(candidate.fqn());
            } catch (ClassNotFoundException e) {
                throw new AssertionError(e);
            }
            assertTrue(RewindRecreatable.class.isAssignableFrom(cls),
                    candidate.fqn() + " must implement RewindRecreatable (codec deleted in batch 27)");
        }
    }

    @Test
    void batch27ClassesHaveNoRegisteredCodec() {
        for (CodecDeletionCandidate candidate : BATCH27_DELETED_CODECS) {
            assertFalse(hasRegisteredDynamicCodec(candidate.fqn(), candidate.gameId()),
                    candidate.fqn() + " must have NO registered dynamic rewind codec after batch-27 deletion; "
                            + "it should round-trip purely via genericRecreate Path 1");
        }
    }

    @Test
    void batch27ClassesGenericRecreateProducesInstance() {
        for (CodecDeletionCandidate candidate : BATCH27_DELETED_CODECS) {
            ObjectInstance result = invokeGenericRecreate(candidate.fqn(), 0x120, 0x240, candidate.gameId());
            assertNotNull(result, "genericRecreate must return non-null for " + candidate.fqn());
            assertEquals(candidate.fqn(), result.getClass().getName(),
                    "genericRecreate must return the same concrete class for " + candidate.fqn());
        }
    }

    @Test
    void batch27ClassesRoundTripPassedWithoutCodec() {
        for (CodecDeletionCandidate candidate : BATCH27_DELETED_CODECS) {
            RoundTripSweepResult result = RewindRoundTripHarness.probeClass(candidate.fqn());
            assertInstanceOf(RoundTripSweepResult.Passed.class, result,
                    candidate.fqn()
                            + " must round-trip as Passed via RewindRecreatable path (no codec); got: "
                            + result);
        }
    }

    // =====================================================================
    // Batch 28: S2 CPZ boss falling-part dynamic, session-tail verified
    // =====================================================================

    @Test
    void batch28ClassesAllImplementRewindRecreatable() {
        for (CodecDeletionCandidate candidate : BATCH28_DELETED_CODECS) {
            Class<?> cls;
            try {
                cls = Class.forName(candidate.fqn());
            } catch (ClassNotFoundException e) {
                throw new AssertionError(e);
            }
            assertTrue(RewindRecreatable.class.isAssignableFrom(cls),
                    candidate.fqn() + " must implement RewindRecreatable (codec deleted in batch 28)");
        }
    }

    @Test
    void batch28ClassesHaveNoRegisteredCodec() {
        for (CodecDeletionCandidate candidate : BATCH28_DELETED_CODECS) {
            assertFalse(hasRegisteredDynamicCodec(candidate.fqn(), candidate.gameId()),
                    candidate.fqn() + " must have NO registered dynamic rewind codec after batch-28 deletion; "
                            + "it should round-trip purely via genericRecreate Path 1");
        }
    }

    @Test
    void batch28ClassesGenericRecreateProducesInstance() {
        for (CodecDeletionCandidate candidate : BATCH28_DELETED_CODECS) {
            ObjectInstance result = invokeGenericRecreate(candidate.fqn(), 0x120, 0x240, candidate.gameId());
            assertNotNull(result, "genericRecreate must return non-null for " + candidate.fqn());
            assertEquals(candidate.fqn(), result.getClass().getName(),
                    "genericRecreate must return the same concrete class for " + candidate.fqn());
        }
    }

    @Test
    void batch28ClassesRoundTripPassedWithoutCodec() {
        for (CodecDeletionCandidate candidate : BATCH28_DELETED_CODECS) {
            RoundTripSweepResult result = RewindRoundTripHarness.probeClass(candidate.fqn());
            assertInstanceOf(RoundTripSweepResult.Passed.class, result,
                    candidate.fqn()
                            + " must round-trip as Passed via RewindRecreatable path (no codec); got: "
                            + result);
        }
    }

    // =====================================================================
    // Batch 29: S2 SpikyBlock spike dynamic, session-tail verified
    // =====================================================================

    @Test
    void batch29ClassesAllImplementRewindRecreatable() {
        for (CodecDeletionCandidate candidate : BATCH29_DELETED_CODECS) {
            Class<?> cls;
            try {
                cls = Class.forName(candidate.fqn());
            } catch (ClassNotFoundException e) {
                throw new AssertionError(e);
            }
            assertTrue(RewindRecreatable.class.isAssignableFrom(cls),
                    candidate.fqn() + " must implement RewindRecreatable (codec deleted in batch 29)");
        }
    }

    @Test
    void batch29ClassesHaveNoRegisteredCodec() {
        for (CodecDeletionCandidate candidate : BATCH29_DELETED_CODECS) {
            assertFalse(hasRegisteredDynamicCodec(candidate.fqn(), candidate.gameId()),
                    candidate.fqn() + " must have NO registered dynamic rewind codec after batch-29 deletion; "
                            + "it should round-trip purely via genericRecreate Path 1");
        }
    }

    @Test
    void batch29ClassesGenericRecreateProducesInstance() {
        for (CodecDeletionCandidate candidate : BATCH29_DELETED_CODECS) {
            ObjectInstance result = invokeGenericRecreate(candidate.fqn(), 0x120, 0x240, candidate.gameId());
            assertNotNull(result, "genericRecreate must return non-null for " + candidate.fqn());
            assertEquals(candidate.fqn(), result.getClass().getName(),
                    "genericRecreate must return the same concrete class for " + candidate.fqn());
        }
    }

    @Test
    void batch29ClassesRoundTripPassedWithoutCodec() {
        for (CodecDeletionCandidate candidate : BATCH29_DELETED_CODECS) {
            RoundTripSweepResult result = RewindRoundTripHarness.probeClass(candidate.fqn());
            assertInstanceOf(RoundTripSweepResult.Passed.class, result,
                    candidate.fqn()
                            + " must round-trip as Passed via RewindRecreatable path (no codec); got: "
                            + result);
        }
    }

    // =====================================================================
    // Batch 30: S2 monitor contents dynamic, session-tail verified
    // =====================================================================

    @Test
    void batch30ClassesAllImplementRewindRecreatable() {
        for (CodecDeletionCandidate candidate : BATCH30_DELETED_CODECS) {
            Class<?> cls;
            try {
                cls = Class.forName(candidate.fqn());
            } catch (ClassNotFoundException e) {
                throw new AssertionError(e);
            }
            assertTrue(RewindRecreatable.class.isAssignableFrom(cls),
                    candidate.fqn() + " must implement RewindRecreatable (codec deleted in batch 30)");
        }
    }

    @Test
    void batch30ClassesHaveNoRegisteredCodec() {
        for (CodecDeletionCandidate candidate : BATCH30_DELETED_CODECS) {
            assertFalse(hasRegisteredDynamicCodec(candidate.fqn(), candidate.gameId()),
                    candidate.fqn() + " must have NO registered dynamic rewind codec after batch-30 deletion; "
                            + "it should round-trip purely via genericRecreate Path 1");
        }
    }

    @Test
    void batch30ClassesGenericRecreateProducesInstance() {
        for (CodecDeletionCandidate candidate : BATCH30_DELETED_CODECS) {
            ObjectInstance result = invokeGenericRecreate(candidate.fqn(), 0x120, 0x240, candidate.gameId());
            assertNotNull(result, "genericRecreate must return non-null for " + candidate.fqn());
            assertEquals(candidate.fqn(), result.getClass().getName(),
                    "genericRecreate must return the same concrete class for " + candidate.fqn());
        }
    }

    @Test
    void batch30ClassesRoundTripPassedWithoutCodec() {
        for (CodecDeletionCandidate candidate : BATCH30_DELETED_CODECS) {
            RoundTripSweepResult result = RewindRoundTripHarness.probeClass(candidate.fqn());
            assertInstanceOf(RoundTripSweepResult.Passed.class, result,
                    candidate.fqn()
                            + " must round-trip as Passed via RewindRecreatable path (no codec); got: "
                            + result);
        }
    }

    // =====================================================================
    // Batch 31: S2 bomb prize dynamic
    // =====================================================================

    @Test
    void batch31ClassesAllImplementRewindRecreatable() {
        for (CodecDeletionCandidate candidate : BATCH31_DELETED_CODECS) {
            Class<?> cls;
            try {
                cls = Class.forName(candidate.fqn());
            } catch (ClassNotFoundException e) {
                throw new AssertionError(e);
            }
            assertTrue(RewindRecreatable.class.isAssignableFrom(cls),
                    candidate.fqn() + " must implement RewindRecreatable (codec deleted in batch 31)");
        }
    }

    @Test
    void batch31ClassesHaveNoRegisteredCodec() {
        for (CodecDeletionCandidate candidate : BATCH31_DELETED_CODECS) {
            assertFalse(hasRegisteredDynamicCodec(candidate.fqn(), candidate.gameId()),
                    candidate.fqn() + " must have NO registered dynamic rewind codec after batch-31 deletion; "
                            + "it should round-trip purely via genericRecreate Path 1");
        }
    }

    @Test
    void batch31ClassesGenericRecreateProducesInstance() {
        for (CodecDeletionCandidate candidate : BATCH31_DELETED_CODECS) {
            ObjectInstance result = invokeGenericRecreate(candidate.fqn(), 0x120, 0x240, candidate.gameId());
            assertNotNull(result, "genericRecreate must return non-null for " + candidate.fqn());
            assertEquals(candidate.fqn(), result.getClass().getName(),
                    "genericRecreate must return the same concrete class for " + candidate.fqn());
        }
    }

    @Test
    void batch31ClassesRoundTripPassedWithoutCodec() {
        for (CodecDeletionCandidate candidate : BATCH31_DELETED_CODECS) {
            RoundTripSweepResult result = RewindRoundTripHarness.probeClass(candidate.fqn());
            assertInstanceOf(RoundTripSweepResult.Passed.class, result,
                    candidate.fqn()
                            + " must round-trip as Passed via RewindRecreatable path (no codec); got: "
                            + result);
        }
    }

    // =====================================================================
    // Batch 32: remaining S2 scalar/no-reference session-only dynamics
    // =====================================================================

    @Test
    void batch32ClassesAllImplementRewindRecreatable() {
        for (CodecDeletionCandidate candidate : BATCH32_DELETED_CODECS) {
            Class<?> cls;
            try {
                cls = Class.forName(candidate.fqn());
            } catch (ClassNotFoundException e) {
                throw new AssertionError(e);
            }
            assertTrue(RewindRecreatable.class.isAssignableFrom(cls),
                    candidate.fqn() + " must implement RewindRecreatable (codec deleted in batch 32)");
        }
    }

    @Test
    void batch32ClassesHaveNoRegisteredCodec() {
        for (CodecDeletionCandidate candidate : BATCH32_DELETED_CODECS) {
            assertFalse(hasRegisteredDynamicCodec(candidate.fqn(), candidate.gameId()),
                    candidate.fqn() + " must have NO registered dynamic rewind codec after batch-32 deletion; "
                            + "session restore must use genericRecreate Path 1");
        }
    }

    @Test
    void batch32ClassesGenericRecreateProducesInstance() {
        for (CodecDeletionCandidate candidate : BATCH32_DELETED_CODECS) {
            ObjectInstance result = invokeGenericRecreate(candidate.fqn(), 0x120, 0x240, candidate.gameId());
            assertNotNull(result, "genericRecreate must return non-null for " + candidate.fqn());
            assertEquals(candidate.fqn(), result.getClass().getName(),
                    "genericRecreate must return the same concrete class for " + candidate.fqn());
        }
    }

    @Test
    void batch32ClassesRoundTripDoesNotFailWhenProbeable() {
        for (CodecDeletionCandidate candidate : BATCH32_DELETED_CODECS) {
            RoundTripSweepResult result = RewindRoundTripHarness.probeClass(candidate.fqn());
            if (result instanceof RoundTripSweepResult.Unprobed unprobed) {
                assertTrue(unprobed.reason().contains("No probe-compatible constructor"),
                        candidate.fqn() + " may remain session-verified only, but not fail hard; got: " + result);
                continue;
            }
            assertInstanceOf(RoundTripSweepResult.Passed.class, result,
                    candidate.fqn()
                            + " must round-trip as Passed when the headless harness can construct it; got: "
                            + result);
        }
    }

    // =====================================================================
    // Batch 33: S1 session-verified scalar/no-reference projectile dynamics
    // =====================================================================

    @Test
    void batch33ClassesAllImplementRewindRecreatable() {
        for (CodecDeletionCandidate candidate : BATCH33_DELETED_CODECS) {
            Class<?> cls;
            try {
                cls = Class.forName(candidate.fqn());
            } catch (ClassNotFoundException e) {
                throw new AssertionError(e);
            }
            assertTrue(RewindRecreatable.class.isAssignableFrom(cls),
                    candidate.fqn() + " must implement RewindRecreatable (codec deleted in batch 33)");
        }
    }

    @Test
    void batch33ClassesHaveNoRegisteredCodec() {
        for (CodecDeletionCandidate candidate : BATCH33_DELETED_CODECS) {
            assertFalse(hasRegisteredDynamicCodec(candidate.fqn(), candidate.gameId()),
                    candidate.fqn() + " must have NO registered dynamic rewind codec after batch-33 deletion; "
                            + "session restore must use genericRecreate Path 1");
        }
    }

    @Test
    void batch33ClassesGenericRecreateProducesInstance() {
        for (CodecDeletionCandidate candidate : BATCH33_DELETED_CODECS) {
            ObjectInstance result = invokeGenericRecreate(candidate.fqn(), 0x120, 0x240, candidate.gameId());
            assertNotNull(result, "genericRecreate must return non-null for " + candidate.fqn());
            assertEquals(candidate.fqn(), result.getClass().getName(),
                    "genericRecreate must return the same concrete class for " + candidate.fqn());
        }
    }

    @Test
    void batch33ClassesRoundTripPassedThroughObjectManagerSessionPath() {
        for (CodecDeletionCandidate candidate : BATCH33_DELETED_CODECS) {
            RoundTripSweepResult result = RewindRoundTripHarness.probeClass(candidate.fqn());
            assertInstanceOf(RoundTripSweepResult.Passed.class, result,
                    candidate.fqn()
                            + " must round-trip as Passed through the ObjectManager session path; got: "
                            + result);
        }
    }

    // =====================================================================
    // Batch 34: S1 session-verified scalar/no-reference projectile dynamics
    // =====================================================================

    @Test
    void batch34ClassesAllImplementRewindRecreatable() {
        for (CodecDeletionCandidate candidate : BATCH34_DELETED_CODECS) {
            Class<?> cls;
            try {
                cls = Class.forName(candidate.fqn());
            } catch (ClassNotFoundException e) {
                throw new AssertionError(e);
            }
            assertTrue(RewindRecreatable.class.isAssignableFrom(cls),
                    candidate.fqn() + " must implement RewindRecreatable (codec deleted in batch 34)");
        }
    }

    @Test
    void batch34ClassesHaveNoRegisteredCodec() {
        for (CodecDeletionCandidate candidate : BATCH34_DELETED_CODECS) {
            assertFalse(hasRegisteredDynamicCodec(candidate.fqn(), candidate.gameId()),
                    candidate.fqn() + " must have NO registered dynamic rewind codec after batch-34 deletion; "
                            + "session restore must use genericRecreate Path 1");
        }
    }

    @Test
    void batch34ClassesGenericRecreateProducesInstance() {
        for (CodecDeletionCandidate candidate : BATCH34_DELETED_CODECS) {
            ObjectInstance result = invokeGenericRecreate(candidate.fqn(), 0x120, 0x240, candidate.gameId());
            assertNotNull(result, "genericRecreate must return non-null for " + candidate.fqn());
            assertEquals(candidate.fqn(), result.getClass().getName(),
                    "genericRecreate must return the same concrete class for " + candidate.fqn());
        }
    }

    @Test
    void batch34ClassesRoundTripPassedThroughObjectManagerSessionPath() {
        for (CodecDeletionCandidate candidate : BATCH34_DELETED_CODECS) {
            RoundTripSweepResult result = RewindRoundTripHarness.probeClass(candidate.fqn());
            if (result instanceof RoundTripSweepResult.Unprobed unprobed) {
                assertTrue(unprobed.reason().contains("No probe-compatible constructor"),
                        candidate.fqn()
                                + " may remain session-verified only, but not fail hard; got: "
                                + result);
                continue;
            }
            assertInstanceOf(RoundTripSweepResult.Passed.class, result,
                    candidate.fqn()
                            + " must round-trip as Passed through the ObjectManager session path; got: "
                            + result);
        }
    }

    // =====================================================================
    // Batch 35: S1 session-verified scalar/no-reference dynamics
    // =====================================================================

    @Test
    void batch35ClassesAllImplementRewindRecreatable() {
        for (CodecDeletionCandidate candidate : BATCH35_DELETED_CODECS) {
            Class<?> cls;
            try {
                cls = Class.forName(candidate.fqn());
            } catch (ClassNotFoundException e) {
                throw new AssertionError(e);
            }
            assertTrue(RewindRecreatable.class.isAssignableFrom(cls),
                    candidate.fqn() + " must implement RewindRecreatable (codec deleted in batch 35)");
        }
    }

    @Test
    void batch35ClassesHaveNoRegisteredCodec() {
        for (CodecDeletionCandidate candidate : BATCH35_DELETED_CODECS) {
            assertFalse(hasRegisteredDynamicCodec(candidate.fqn(), candidate.gameId()),
                    candidate.fqn() + " must have NO registered dynamic rewind codec after batch-35 deletion; "
                            + "session restore must use genericRecreate Path 1");
        }
    }

    @Test
    void batch35ClassesGenericRecreateProducesInstance() {
        for (CodecDeletionCandidate candidate : BATCH35_DELETED_CODECS) {
            ObjectInstance result = invokeGenericRecreate(candidate.fqn(), 0x120, 0x240, candidate.gameId());
            assertNotNull(result, "genericRecreate must return non-null for " + candidate.fqn());
            assertEquals(candidate.fqn(), result.getClass().getName(),
                    "genericRecreate must return the same concrete class for " + candidate.fqn());
        }
    }

    @Test
    void batch35ClassesRoundTripDoesNotFailWhenProbeable() {
        for (CodecDeletionCandidate candidate : BATCH35_DELETED_CODECS) {
            RoundTripSweepResult result = RewindRoundTripHarness.probeClass(candidate.fqn());
            if (result instanceof RoundTripSweepResult.Unprobed unprobed) {
                assertTrue(unprobed.reason().contains("No probe-compatible constructor"),
                        candidate.fqn()
                                + " may remain session-verified only, but not fail hard; got: "
                                + result);
                continue;
            }
            assertInstanceOf(RoundTripSweepResult.Passed.class, result,
                    candidate.fqn()
                            + " must round-trip as Passed through the ObjectManager session path; got: "
                            + result);
        }
    }

    // =====================================================================
    // Batch 36: S1 session-verified scalar/no-reference dynamics
    // =====================================================================

    @Test
    void batch36ClassesAllImplementRewindRecreatable() {
        for (CodecDeletionCandidate candidate : BATCH36_DELETED_CODECS) {
            Class<?> cls;
            try {
                cls = Class.forName(candidate.fqn());
            } catch (ClassNotFoundException e) {
                throw new AssertionError(e);
            }
            assertTrue(RewindRecreatable.class.isAssignableFrom(cls),
                    candidate.fqn() + " must implement RewindRecreatable (codec deleted in batch 36)");
        }
    }

    @Test
    void batch36ClassesHaveNoRegisteredCodec() {
        for (CodecDeletionCandidate candidate : BATCH36_DELETED_CODECS) {
            assertFalse(hasRegisteredDynamicCodec(candidate.fqn(), candidate.gameId()),
                    candidate.fqn() + " must have NO registered dynamic rewind codec after batch-36 deletion; "
                            + "session restore must use genericRecreate Path 1");
        }
    }

    @Test
    void batch36ClassesGenericRecreateProducesInstance() {
        for (CodecDeletionCandidate candidate : BATCH36_DELETED_CODECS) {
            ObjectInstance result = invokeGenericRecreate(candidate.fqn(), 0x120, 0x240, candidate.gameId());
            assertNotNull(result, "genericRecreate must return non-null for " + candidate.fqn());
            assertEquals(candidate.fqn(), result.getClass().getName(),
                    "genericRecreate must return the same concrete class for " + candidate.fqn());
        }
    }

    @Test
    void batch36ClassesRoundTripDoesNotFailWhenProbeable() {
        for (CodecDeletionCandidate candidate : BATCH36_DELETED_CODECS) {
            RoundTripSweepResult result = RewindRoundTripHarness.probeClass(candidate.fqn());
            if (result instanceof RoundTripSweepResult.Unprobed unprobed) {
                assertTrue(unprobed.reason().contains("No probe-compatible constructor"),
                        candidate.fqn()
                                + " may remain session-verified only, but not fail hard; got: "
                                + result);
                continue;
            }
            assertInstanceOf(RoundTripSweepResult.Passed.class, result,
                    candidate.fqn()
                            + " must round-trip as Passed through the ObjectManager session path; got: "
                            + result);
        }
    }

    // =====================================================================
    // Batch 37: S3K session-verified scalar/no-reference nested dynamic
    // =====================================================================

    @Test
    void batch37ClassesAllImplementRewindRecreatable() {
        for (CodecDeletionCandidate candidate : BATCH37_DELETED_CODECS) {
            Class<?> cls;
            try {
                cls = Class.forName(candidate.fqn());
            } catch (ClassNotFoundException e) {
                throw new AssertionError(e);
            }
            assertTrue(RewindRecreatable.class.isAssignableFrom(cls),
                    candidate.fqn() + " must implement RewindRecreatable (codec deleted in batch 37)");
        }
    }

    @Test
    void batch37ClassesHaveNoRegisteredCodec() {
        for (CodecDeletionCandidate candidate : BATCH37_DELETED_CODECS) {
            assertFalse(hasRegisteredDynamicCodec(candidate.fqn(), candidate.gameId()),
                    candidate.fqn() + " must have NO registered dynamic rewind codec after batch-37 deletion; "
                            + "session restore must use genericRecreate Path 1");
        }
    }

    @Test
    void batch37ClassesGenericRecreateProducesInstance() {
        for (CodecDeletionCandidate candidate : BATCH37_DELETED_CODECS) {
            ObjectInstance result = invokeGenericRecreate(candidate.fqn(), 0x120, 0x240, candidate.gameId());
            assertNotNull(result, "genericRecreate must return non-null for " + candidate.fqn());
            assertEquals(candidate.fqn(), result.getClass().getName(),
                    "genericRecreate must return the same concrete class for " + candidate.fqn());
        }
    }

    @Test
    void batch37ClassesRoundTripDoesNotFailWhenProbeable() {
        for (CodecDeletionCandidate candidate : BATCH37_DELETED_CODECS) {
            RoundTripSweepResult result = RewindRoundTripHarness.probeClass(candidate.fqn());
            if (result instanceof RoundTripSweepResult.Unprobed unprobed) {
                assertTrue(unprobed.reason().contains("No probe-compatible constructor"),
                        candidate.fqn()
                                + " may remain session-verified only, but not fail hard; got: "
                                + result);
                continue;
            }
            assertInstanceOf(RoundTripSweepResult.Passed.class, result,
                    candidate.fqn()
                            + " must round-trip as Passed through the ObjectManager session path; got: "
                            + result);
        }
    }

    // =====================================================================
    // Batch 38: S1 scalar/no-reference session-only exact-spawn dynamics
    // =====================================================================

    @Test
    void batch38ClassesAllImplementRewindRecreatable() {
        for (CodecDeletionCandidate candidate : BATCH38_DELETED_CODECS) {
            Class<?> cls;
            try {
                cls = Class.forName(candidate.fqn());
            } catch (ClassNotFoundException e) {
                throw new AssertionError(e);
            }
            assertTrue(RewindRecreatable.class.isAssignableFrom(cls),
                    candidate.fqn() + " must implement RewindRecreatable (codec deleted in batch 38)");
        }
    }

    @Test
    void batch38ClassesHaveNoRegisteredCodec() {
        for (CodecDeletionCandidate candidate : BATCH38_DELETED_CODECS) {
            assertFalse(hasRegisteredDynamicCodec(candidate.fqn(), candidate.gameId()),
                    candidate.fqn() + " must have NO registered dynamic rewind codec after batch-38 deletion; "
                            + "session restore must use genericRecreate Path 1");
        }
    }

    @Test
    void batch38ClassesGenericRecreateProducesInstance() {
        for (CodecDeletionCandidate candidate : BATCH38_DELETED_CODECS) {
            ObjectInstance result = invokeGenericRecreate(candidate.fqn(), 0x120, 0x240, candidate.gameId());
            assertNotNull(result, "genericRecreate must return non-null for " + candidate.fqn());
            assertEquals(candidate.fqn(), result.getClass().getName(),
                    "genericRecreate must return the same concrete class for " + candidate.fqn());
        }
    }

    @Test
    void batch38ClassesRoundTripDoesNotFailWhenProbeable() {
        for (CodecDeletionCandidate candidate : BATCH38_DELETED_CODECS) {
            RoundTripSweepResult result = RewindRoundTripHarness.probeClass(candidate.fqn());
            if (result instanceof RoundTripSweepResult.Unprobed unprobed) {
                assertTrue(unprobed.reason().contains("No probe-compatible constructor"),
                        candidate.fqn()
                                + " may remain session-verified only, but not fail hard; got: "
                                + result);
                continue;
            }
            assertInstanceOf(RoundTripSweepResult.Passed.class, result,
                    candidate.fqn()
                            + " must round-trip as Passed through the ObjectManager session path; got: "
                            + result);
        }
    }

    // =====================================================================
    // Batch 39: shared S1 boss explosion dynamic
    // =====================================================================

    @Test
    void batch39ClassesAllImplementRewindRecreatable() {
        for (CodecDeletionCandidate candidate : BATCH39_DELETED_CODECS) {
            Class<?> cls;
            try {
                cls = Class.forName(candidate.fqn());
            } catch (ClassNotFoundException e) {
                throw new AssertionError(e);
            }
            assertTrue(RewindRecreatable.class.isAssignableFrom(cls),
                    candidate.fqn() + " must implement RewindRecreatable (codec deleted in batch 39)");
        }
    }

    @Test
    void batch39ClassesHaveNoRegisteredCodec() {
        for (CodecDeletionCandidate candidate : BATCH39_DELETED_CODECS) {
            assertFalse(hasRegisteredDynamicCodec(candidate.fqn(), candidate.gameId()),
                    candidate.fqn() + " must have NO registered dynamic rewind codec after batch-39 deletion; "
                            + "session restore must use genericRecreate Path 1");
        }
    }

    @Test
    void batch39ClassesGenericRecreateProducesInstance() {
        for (CodecDeletionCandidate candidate : BATCH39_DELETED_CODECS) {
            ObjectInstance result = invokeGenericRecreate(candidate.fqn(), 0x120, 0x240, candidate.gameId());
            assertNotNull(result, "genericRecreate must return non-null for " + candidate.fqn());
            assertEquals(candidate.fqn(), result.getClass().getName(),
                    "genericRecreate must return the same concrete class for " + candidate.fqn());
        }
    }

    @Test
    void batch39ClassesRoundTripPassedThroughObjectManagerSessionPath() {
        for (CodecDeletionCandidate candidate : BATCH39_DELETED_CODECS) {
            RoundTripSweepResult result = RewindRoundTripHarness.probeClass(candidate.fqn());
            assertInstanceOf(RoundTripSweepResult.Passed.class, result,
                    candidate.fqn()
                            + " must round-trip as Passed through the ObjectManager session path; got: "
                            + result);
        }
    }

    // =====================================================================
    // Batch 40: S3K MHZ end-boss palette fade controller
    // =====================================================================

    @Test
    void batch40ClassesAllImplementRewindRecreatable() {
        for (CodecDeletionCandidate candidate : BATCH40_DELETED_CODECS) {
            Class<?> cls;
            try {
                cls = Class.forName(candidate.fqn());
            } catch (ClassNotFoundException e) {
                throw new AssertionError(e);
            }
            assertTrue(RewindRecreatable.class.isAssignableFrom(cls),
                    candidate.fqn() + " must implement RewindRecreatable (codec deleted in batch 40)");
        }
    }

    @Test
    void batch40ClassesHaveNoRegisteredCodec() {
        for (CodecDeletionCandidate candidate : BATCH40_DELETED_CODECS) {
            assertFalse(hasRegisteredDynamicCodec(candidate.fqn(), candidate.gameId()),
                    candidate.fqn() + " must have NO registered dynamic rewind codec after batch-40 deletion; "
                            + "session restore must use genericRecreate Path 1");
        }
    }

    @Test
    void batch40ClassesGenericRecreateProducesInstance() {
        for (CodecDeletionCandidate candidate : BATCH40_DELETED_CODECS) {
            ObjectInstance result = invokeGenericRecreate(candidate.fqn(), 0x120, 0x240, candidate.gameId());
            assertNotNull(result, "genericRecreate must return non-null for " + candidate.fqn());
            assertEquals(candidate.fqn(), result.getClass().getName(),
                    "genericRecreate must return the same concrete class for " + candidate.fqn());
        }
    }

    @Test
    void batch40ClassesRoundTripDoesNotFailWhenProbeable() {
        for (CodecDeletionCandidate candidate : BATCH40_DELETED_CODECS) {
            RoundTripSweepResult result = RewindRoundTripHarness.probeClass(candidate.fqn());
            if (result instanceof RoundTripSweepResult.Unprobed unprobed) {
                assertTrue(unprobed.reason().contains("No probe-compatible constructor"),
                        candidate.fqn()
                                + " may remain session-verified only, but not fail hard; got: "
                                + result);
                continue;
            }
            assertInstanceOf(RoundTripSweepResult.Passed.class, result,
                    candidate.fqn()
                            + " must round-trip as Passed through the ObjectManager session path; got: "
                            + result);
        }
    }

    // =====================================================================
    // Batch 41: S3K Corkey shot child, no live-nozzle dependency
    // =====================================================================

    @Test
    void batch41ClassesAllImplementRewindRecreatable() {
        for (CodecDeletionCandidate candidate : BATCH41_DELETED_CODECS) {
            Class<?> cls;
            try {
                cls = Class.forName(candidate.fqn());
            } catch (ClassNotFoundException e) {
                throw new AssertionError(e);
            }
            assertTrue(RewindRecreatable.class.isAssignableFrom(cls),
                    candidate.fqn() + " must implement RewindRecreatable (codec deleted in batch 41)");
        }
    }

    @Test
    void batch41ClassesHaveNoRegisteredCodec() {
        for (CodecDeletionCandidate candidate : BATCH41_DELETED_CODECS) {
            assertFalse(hasRegisteredDynamicCodec(candidate.fqn(), candidate.gameId()),
                    candidate.fqn() + " must have NO registered dynamic rewind codec after batch-41 deletion; "
                            + "session restore must use genericRecreate Path 1");
        }
    }

    @Test
    void batch41ClassesGenericRecreateProducesInstance() {
        for (CodecDeletionCandidate candidate : BATCH41_DELETED_CODECS) {
            ObjectInstance result = invokeGenericRecreate(candidate.fqn(), 0x220, 0x1B4, candidate.gameId());
            assertNotNull(result, "genericRecreate must return non-null for " + candidate.fqn());
            assertEquals(candidate.fqn(), result.getClass().getName(),
                    "genericRecreate must return the same concrete class for " + candidate.fqn());
        }
    }

    @Test
    void corkeyShotGenericRecreateRestoresCapturedScriptWithoutLiveNozzle() {
        String fqn = BATCH41_DELETED_CODECS.getFirst().fqn();
        ObjectSpawn spawn = new ObjectSpawn(0x220, 0x1B4, 0, 0, 0, false, 0);
        int[] capturedScript = {9, 1, 8, 2, 7, 3, -0x0C};
        ObjectInstance source = new com.openggf.game.sonic3k.objects.badniks.CorkeyBadnikInstance.CorkeyShotChild(
                spawn, spawn.x(), spawn.y(), capturedScript);
        PerObjectRewindSnapshot state = ((AbstractObjectInstance) source).captureRewindState();

        ObjectInstance recreated = invokeGenericRecreateWithState(fqn, spawn, state, GameId.S3K);
        assertNotNull(recreated, "genericRecreate must not depend on a live Corkey nozzle");
        ((AbstractObjectInstance) recreated).restoreRewindState(state);

        assertArrayEquals(capturedScript, readIntArrayField(recreated, "script"),
                "standard scalar restore must reapply the exact captured Corkey shot script");
    }

    @Test
    void batch41ClassesRoundTripPassedThroughObjectManagerSessionPath() {
        for (CodecDeletionCandidate candidate : BATCH41_DELETED_CODECS) {
            RoundTripSweepResult result = RewindRoundTripHarness.probeClass(candidate.fqn());
            assertInstanceOf(RoundTripSweepResult.Passed.class, result,
                    candidate.fqn()
                            + " must round-trip as Passed through the ObjectManager session path; got: "
                            + result);
        }
    }

    // =====================================================================
    // Batch 42: S3K MHZ end-boss defeat fragment, no live-boss dependency
    // =====================================================================

    @Test
    void batch42ClassesAllImplementRewindRecreatable() {
        for (CodecDeletionCandidate candidate : BATCH42_DELETED_CODECS) {
            Class<?> cls;
            try {
                cls = Class.forName(candidate.fqn());
            } catch (ClassNotFoundException e) {
                throw new AssertionError(e);
            }
            assertTrue(RewindRecreatable.class.isAssignableFrom(cls),
                    candidate.fqn() + " must implement RewindRecreatable (codec deleted in batch 42)");
        }
    }

    @Test
    void batch42ClassesHaveNoRegisteredCodec() {
        for (CodecDeletionCandidate candidate : BATCH42_DELETED_CODECS) {
            assertFalse(hasRegisteredDynamicCodec(candidate.fqn(), candidate.gameId()),
                    candidate.fqn() + " must have NO registered dynamic rewind codec after batch-42 deletion; "
                            + "session restore must use genericRecreate Path 1");
        }
    }

    @Test
    void batch42ClassesGenericRecreateProducesInstance() {
        for (CodecDeletionCandidate candidate : BATCH42_DELETED_CODECS) {
            ObjectInstance result = invokeGenericRecreate(candidate.fqn(), 0x3CA0, 0x320, candidate.gameId());
            assertNotNull(result, "genericRecreate must return non-null for " + candidate.fqn());
            assertEquals(candidate.fqn(), result.getClass().getName(),
                    "genericRecreate must return the same concrete class for " + candidate.fqn());
        }
    }

    @Test
    void mhzEndBossDefeatFragmentGenericRecreateRestoresCapturedDerivedVelocityWithoutLiveBoss() {
        String fqn = BATCH42_DELETED_CODECS.getFirst().fqn();
        StubObjectServices stub = new StubObjectServices() {
            @Override public ObjectRenderManager renderManager() { return INERT_RENDER_MANAGER; }
            @Override public SonicConfigurationService configuration() { return DEFAULT_CONFIGURATION; }
        };
        ObjectSpawn bossSpawn = new ObjectSpawn(0x3D40, 0x2F0, 0x93, 0, 0, false, 0);
        MhzEndBossInstance parent = ObjectConstructionContext.construct(stub,
                () -> new MhzEndBossInstance(bossSpawn));
        parent.getState().renderFlags = 1;
        int capturedSubtype = 4;
        MhzEndBossDefeatFragmentChild source = ObjectConstructionContext.construct(stub,
                () -> MhzEndBossDefeatFragmentChild.forRewindRecreate(parent, capturedSubtype));
        PerObjectRewindSnapshot state = source.captureRewindState();
        ObjectSpawn capturedSpawn = source.getSpawn();

        ObjectInstance recreated = invokeGenericRecreateWithState(fqn, capturedSpawn, state, GameId.S3K);
        assertNotNull(recreated, "genericRecreate must not depend on a live MHZ end boss");
        assertEquals(fqn, recreated.getClass().getName(),
                "genericRecreate must return the same concrete fragment class before restore");
        ((AbstractObjectInstance) recreated).restoreRewindState(state);

        assertEquals(capturedSubtype, readIntField(recreated, "subtype"),
                "standard scalar restore must reapply the exact captured fragment subtype");
        assertEquals(readIntField(source, "xVel"), readIntField(recreated, "xVel"),
                "standard scalar restore must reapply xVel derived from the captured parent render flags");
        assertEquals(readIntField(source, "xFixed"), readIntField(recreated, "xFixed"),
                "standard scalar restore must reapply exact fixed-point X");
        assertEquals(readIntField(source, "yFixed"), readIntField(recreated, "yFixed"),
                "standard scalar restore must reapply exact fixed-point Y");
    }

    @Test
    void batch42ClassesRoundTripPassedThroughObjectManagerSessionPath() {
        for (CodecDeletionCandidate candidate : BATCH42_DELETED_CODECS) {
            RoundTripSweepResult result = RewindRoundTripHarness.probeClass(candidate.fqn());
            assertInstanceOf(RoundTripSweepResult.Passed.class, result,
                    candidate.fqn()
                            + " must round-trip as Passed through the ObjectManager session path; got: "
                            + result);
        }
    }

    // =====================================================================
    // Batch 43: S3K Madmole side-drill child, no live-parent dependency
    // =====================================================================

    @Test
    void batch43ClassesAllImplementRewindRecreatable() {
        for (CodecDeletionCandidate candidate : BATCH43_DELETED_CODECS) {
            Class<?> cls;
            try {
                cls = Class.forName(candidate.fqn());
            } catch (ClassNotFoundException e) {
                throw new AssertionError(e);
            }
            assertTrue(RewindRecreatable.class.isAssignableFrom(cls),
                    candidate.fqn() + " must implement RewindRecreatable (codec deleted in batch 43)");
        }
    }

    @Test
    void batch43ClassesHaveNoRegisteredCodec() {
        for (CodecDeletionCandidate candidate : BATCH43_DELETED_CODECS) {
            assertFalse(hasRegisteredDynamicCodec(candidate.fqn(), candidate.gameId()),
                    candidate.fqn() + " must have NO registered dynamic rewind codec after batch-43 deletion; "
                            + "session restore must use genericRecreate Path 1");
        }
    }

    @Test
    void batch43ClassesGenericRecreateProducesInstance() {
        for (CodecDeletionCandidate candidate : BATCH43_DELETED_CODECS) {
            ObjectInstance result = invokeGenericRecreate(candidate.fqn(), 0x180, 0x140, candidate.gameId());
            assertNotNull(result, "genericRecreate must return non-null for " + candidate.fqn());
            assertEquals(candidate.fqn(), result.getClass().getName(),
                    "genericRecreate must return the same concrete class for " + candidate.fqn());
        }
    }

    @Test
    void madmoleSideDrillGenericRecreateRestoresCapturedMotionWithoutLiveParent() {
        String fqn = BATCH43_DELETED_CODECS.getFirst().fqn();
        ObjectInstance source = instantiateMadmoleSideDrill(0x180, 0x140, false);
        setBooleanField(source, "initialized", true);
        setBooleanField(source, "arcing", true);
        setBooleanField(source, "postCaptureDrift", true);
        setIntField(source, "currentX", 0x188);
        setIntField(source, "currentY", 0x14A);
        setIntField(source, "xVelocity", 0x380);
        setIntField(source, "yVelocity", -0x200);
        setIntField(source, "xSubpixel", 0x40);
        setIntField(source, "ySubpixel", 0x80);
        setIntField(source, "mappingFrame", 9);
        TestablePlayableSprite capturedPlayer =
                new TestablePlayableSprite("sonic", (short) 0x190, (short) 0x150);
        setObjectField(source, "capturedPlayer", capturedPlayer);
        RewindIdentityTable captureTable = new RewindIdentityTable();
        captureTable.registerPlayer(capturedPlayer, PlayerRefId.mainPlayer());
        RewindCaptureContext rewindContext =
                RewindCaptureContext.withIdentityTable(captureTable);
        PerObjectRewindSnapshot state = ((AbstractObjectInstance) source).captureRewindState(rewindContext);
        ObjectSpawn capturedSpawn = source.getSpawn();

        ObjectInstance unresolved = invokeGenericRecreateWithState(fqn, capturedSpawn, state, GameId.S3K);
        RewindCaptureContext missingPlayerContext =
                RewindCaptureContext.withIdentityTable(new RewindIdentityTable());
        assertThrows(IllegalStateException.class,
                () -> ((AbstractObjectInstance) unresolved).restoreRewindState(state, missingPlayerContext),
                "non-null capturedPlayer must use required player-ref resolve during compact restore");

        ObjectInstance recreated = invokeGenericRecreateWithState(fqn, capturedSpawn, state, GameId.S3K);
        TestablePlayableSprite restoredPlayer =
                new TestablePlayableSprite("sonic", (short) 0x190, (short) 0x150);
        RewindIdentityTable restoreTable = new RewindIdentityTable();
        restoreTable.registerPlayer(restoredPlayer, PlayerRefId.mainPlayer());
        RewindCaptureContext restoreContext = RewindCaptureContext.withIdentityTable(restoreTable);
        assertNotNull(recreated, "genericRecreate must not depend on a live Madmole parent");
        assertEquals(fqn, recreated.getClass().getName(),
                "genericRecreate must return the same concrete side-drill class before restore");
        ((AbstractObjectInstance) recreated).restoreRewindState(state, restoreContext);

        assertFalse(readBooleanField(recreated, "facingLeft"),
                "generic recreate must recover facingLeft from the captured spawn render flag");
        assertTrue(readBooleanField(recreated, "initialized"),
                "standard scalar restore must reapply initialized");
        assertTrue(readBooleanField(recreated, "arcing"),
                "standard scalar restore must reapply arcing");
        assertTrue(readBooleanField(recreated, "postCaptureDrift"),
                "standard scalar restore must reapply postCaptureDrift");
        assertEquals(readIntField(source, "currentX"), readIntField(recreated, "currentX"),
                "standard scalar restore must reapply exact X");
        assertEquals(readIntField(source, "currentY"), readIntField(recreated, "currentY"),
                "standard scalar restore must reapply exact Y");
        assertEquals(readIntField(source, "xVelocity"), readIntField(recreated, "xVelocity"),
                "standard scalar restore must reapply xVelocity");
        assertEquals(readIntField(source, "yVelocity"), readIntField(recreated, "yVelocity"),
                "standard scalar restore must reapply yVelocity");
        assertEquals(readIntField(source, "mappingFrame"), readIntField(recreated, "mappingFrame"),
                "standard scalar restore must reapply mappingFrame");
        assertSame(restoredPlayer, readObjectField(recreated, "capturedPlayer"),
                "compact restore must resolve capturedPlayer through the restore identity table");
    }

    @Test
    void batch43ClassesRoundTripPassedThroughObjectManagerSessionPath() {
        for (CodecDeletionCandidate candidate : BATCH43_DELETED_CODECS) {
            RoundTripSweepResult result = RewindRoundTripHarness.probeClass(candidate.fqn());
            assertInstanceOf(RoundTripSweepResult.Passed.class, result,
                    candidate.fqn()
                            + " must round-trip as Passed through the ObjectManager session path; got: "
                            + result);
        }
    }

    // =====================================================================
    // Batch 44: S3K results screen - exact-spawn codec deleted
    // =====================================================================

    @Test
    void batch44ClassesAllImplementRewindRecreatable() {
        for (CodecDeletionCandidate candidate : BATCH44_DELETED_CODECS) {
            Class<?> cls;
            try {
                cls = Class.forName(candidate.fqn());
            } catch (ClassNotFoundException e) {
                throw new AssertionError(e);
            }
            assertTrue(RewindRecreatable.class.isAssignableFrom(cls),
                    candidate.fqn() + " must implement RewindRecreatable (codec deleted in batch 44)");
        }
    }

    @Test
    void batch44ClassesHaveNoRegisteredCodec() {
        for (CodecDeletionCandidate candidate : BATCH44_DELETED_CODECS) {
            assertFalse(hasRegisteredDynamicCodec(candidate.fqn(), candidate.gameId()),
                    candidate.fqn() + " must have NO registered dynamic rewind codec after batch-44 deletion; "
                            + "session restore must use genericRecreate Path 1");
        }
    }

    @Test
    void batch44ClassesGenericRecreateProducesInstance() {
        for (CodecDeletionCandidate candidate : BATCH44_DELETED_CODECS) {
            ObjectInstance result = invokeGenericRecreate(candidate.fqn(), 0, 0, candidate.gameId());
            assertNotNull(result, "genericRecreate must return non-null for " + candidate.fqn());
            assertEquals(candidate.fqn(), result.getClass().getName(),
                    "genericRecreate must return the same concrete class for " + candidate.fqn());
        }
    }

    @Test
    void s3kResultsGenericRecreateRestoresCapturedConstructorStateAndPlayerRef() {
        String fqn = BATCH44_DELETED_CODECS.getFirst().fqn();
        ResultsHardwareTimingFixture resultsTiming = new ResultsHardwareTimingFixture();
        StubObjectServices stub = new StubObjectServices() {
            @Override public ObjectRenderManager renderManager() { return INERT_RENDER_MANAGER; }
            @Override public SonicConfigurationService configuration() { return DEFAULT_CONFIGURATION; }
            @Override public com.openggf.game.timing.HardwareTimingService hardwareTiming() {
                return resultsTiming.hardwareTiming();
            }
            @Override public RomByteReader romReader() {
                return emptyResultsMappingReader();
            }
        };
        S3kResultsScreenObjectInstance source = newResultsRestoreShell(stub);
        setObjectField(source, "character", PlayerCharacter.TAILS_ALONE);
        setIntField(source, "act", 1);
        invokeNoArgMethod(source, "createElements");
        setIntField(source, "timeBonus", 4321);
        setIntField(source, "ringBonus", 210);
        setIntField(source, "totalBonusCountUp", 1234);
        setIntField(source, "exitQueueCounter", 7);
        setBooleanField(source, "musicPlayed", true);
        setBooleanField(source, "actTransitionSignaled", true);
        Object sourceFirstElement = ((Object[]) readObjectField(source, "elements"))[0];
        setIntField(sourceFirstElement, "currentX", 0x0123);
        setBooleanField(sourceFirstElement, "exitStarted", true);
        setBooleanField(sourceFirstElement, "offScreen", true);
        TestablePlayableSprite capturedPlayer =
                new TestablePlayableSprite("tails", (short) 0x200, (short) 0x160);
        setObjectField(source, "playerRef", capturedPlayer);
        RewindIdentityTable captureTable = new RewindIdentityTable();
        captureTable.registerPlayer(capturedPlayer, PlayerRefId.mainPlayer());
        RewindCaptureContext rewindContext =
                RewindCaptureContext.withIdentityTable(captureTable);
        PerObjectRewindSnapshot state = source.captureRewindState(rewindContext);

        ObjectInstance unresolved = invokeGenericRecreateWithState(
                fqn, source.getSpawn(), state, GameId.S3K);
        RewindCaptureContext missingPlayerContext =
                RewindCaptureContext.withIdentityTable(new RewindIdentityTable());
        assertThrows(IllegalStateException.class,
                () -> ((AbstractObjectInstance) unresolved).restoreRewindState(state, missingPlayerContext),
                "non-null playerRef must use required player-ref resolve during compact restore");

        ObjectInstance recreated = invokeGenericRecreateWithState(
                fqn, source.getSpawn(), state, GameId.S3K);
        TestablePlayableSprite restoredPlayer =
                new TestablePlayableSprite("tails", (short) 0x200, (short) 0x160);
        RewindIdentityTable restoreTable = new RewindIdentityTable();
        restoreTable.registerPlayer(restoredPlayer, PlayerRefId.mainPlayer());
        RewindCaptureContext restoreContext = RewindCaptureContext.withIdentityTable(restoreTable);
        assertNotNull(recreated, "genericRecreate must return an S3K results screen");
        assertEquals(fqn, recreated.getClass().getName(),
                "genericRecreate must return the same concrete results class before restore");
        ((AbstractObjectInstance) recreated).restoreRewindState(state, restoreContext);

        assertEquals(PlayerCharacter.TAILS_ALONE, readObjectField(recreated, "character"),
                "standard restore must reapply the captured character constructor arg");
        assertEquals(1, readIntField(recreated, "act"),
                "standard restore must reapply the captured act constructor arg");
        assertEquals(4321, readIntField(recreated, "timeBonus"),
                "standard restore must reapply the exact time bonus");
        assertEquals(210, readIntField(recreated, "ringBonus"),
                "standard restore must reapply the exact ring bonus");
        assertEquals(1234, readIntField(recreated, "totalBonusCountUp"),
                "standard restore must reapply the exact tally count");
        assertEquals(7, readIntField(recreated, "exitQueueCounter"),
                "standard restore must reapply the exit queue counter");
        assertTrue(readBooleanField(recreated, "musicPlayed"),
                "standard restore must reapply musicPlayed");
        assertTrue(readBooleanField(recreated, "actTransitionSignaled"),
                "standard restore must reapply actTransitionSignaled");
        assertSame(restoredPlayer, readObjectField(recreated, "playerRef"),
                "compact restore must resolve playerRef through the restore identity table");
        Object restoredFirstElement = ((Object[]) readObjectField(recreated, "elements"))[0];
        assertNotSame(sourceFirstElement, restoredFirstElement,
                "results element state must be reconstructed rather than retaining a stale object");
        assertEquals(0x0123, readIntField(restoredFirstElement, "currentX"),
                "compact restore must preserve the element's live slide coordinate");
        assertTrue(readBooleanField(restoredFirstElement, "exitStarted"),
                "compact restore must preserve the element's exit latch");
        assertTrue(readBooleanField(restoredFirstElement, "offScreen"),
                "compact restore must preserve the element's retirement state");
    }

    @Test
    void batch44ClassesRoundTripPassedThroughObjectManagerSessionPath() {
        for (CodecDeletionCandidate candidate : BATCH44_DELETED_CODECS) {
            RoundTripSweepResult result = RewindRoundTripHarness.probeClass(candidate.fqn());
            assertInstanceOf(RoundTripSweepResult.Passed.class, result,
                    candidate.fqn()
                            + " must round-trip as Passed through the ObjectManager session path; got: "
                            + result);
        }
    }

    // =====================================================================
    // Batch 45: S3K MHZ Robotnik ship flame, parent ref restored by identity
    // =====================================================================

    @Test
    void batch45ClassesAllImplementRewindRecreatable() {
        for (CodecDeletionCandidate candidate : BATCH45_DELETED_CODECS) {
            Class<?> cls;
            try {
                cls = Class.forName(candidate.fqn());
            } catch (ClassNotFoundException e) {
                throw new AssertionError(e);
            }
            assertTrue(RewindRecreatable.class.isAssignableFrom(cls),
                    candidate.fqn() + " must implement RewindRecreatable (codec deleted in batch 45)");
        }
    }

    @Test
    void batch45ClassesHaveNoRegisteredCodec() {
        for (CodecDeletionCandidate candidate : BATCH45_DELETED_CODECS) {
            assertFalse(hasRegisteredDynamicCodec(candidate.fqn(), candidate.gameId()),
                    candidate.fqn() + " must have NO registered dynamic rewind codec after batch-45 deletion; "
                            + "session restore must use genericRecreate Path 1");
        }
    }

    @Test
    void batch45ClassesGenericRecreateProducesInstance() {
        for (CodecDeletionCandidate candidate : BATCH45_DELETED_CODECS) {
            ObjectInstance result = invokeGenericRecreate(candidate.fqn(), 0x3D00, 0x300, candidate.gameId());
            assertNotNull(result, "genericRecreate must return non-null for " + candidate.fqn());
            assertEquals(candidate.fqn(), result.getClass().getName(),
                    "genericRecreate must return the same concrete class for " + candidate.fqn());
        }
    }

    @Test
    void mhzEndBossRobotnikShipFlameGenericRecreateRestoresParentByIdentity() {
        String fqn = BATCH45_DELETED_CODECS.getFirst().fqn();
        StubObjectServices stub = new StubObjectServices() {
            @Override public ObjectRenderManager renderManager() { return INERT_RENDER_MANAGER; }
            @Override public SonicConfigurationService configuration() { return DEFAULT_CONFIGURATION; }
        };
        ObjectSpawn parentSpawn = new ObjectSpawn(0x3C40, 0x300, 0x93, 0, 0, false, 7);
        MhzEndBossInstance sourceParent = ObjectConstructionContext.construct(stub,
                () -> new MhzEndBossInstance(parentSpawn));
        MhzEndBossRobotnikShipFlameInstance source = ObjectConstructionContext.construct(stub,
                () -> new MhzEndBossRobotnikShipFlameInstance(sourceParent));
        setBooleanField(source, "visibleThisFrame", true);
        setBooleanField(source, "flipX", true);
        ObjectRefId parentId = ObjectRefId.dynamic(4, 1, 44);
        RewindIdentityTable captureTable = new RewindIdentityTable();
        captureTable.registerObject(sourceParent, parentId);
        RewindCaptureContext captureContext = RewindCaptureContext.withIdentityTable(captureTable);
        PerObjectRewindSnapshot state = source.captureRewindState(captureContext);
        ObjectSpawn capturedSpawn = source.getSpawn();

        ObjectInstance unresolved = invokeGenericRecreateWithState(fqn, capturedSpawn, state, GameId.S3K);
        RewindCaptureContext missingParentContext =
                RewindCaptureContext.withIdentityTable(new RewindIdentityTable());
        assertThrows(IllegalStateException.class,
                () -> ((AbstractObjectInstance) unresolved).restoreRewindState(state, missingParentContext),
                "non-null parent must use required object-ref resolve during compact restore");

        ObjectInstance recreated = invokeGenericRecreateWithState(fqn, capturedSpawn, state, GameId.S3K);
        MhzEndBossInstance restoredParent = ObjectConstructionContext.construct(stub,
                () -> new MhzEndBossInstance(parentSpawn));
        RewindIdentityTable restoreTable = new RewindIdentityTable();
        restoreTable.registerObject(restoredParent, parentId);
        RewindCaptureContext restoreContext = RewindCaptureContext.withIdentityTable(restoreTable);
        assertNotNull(recreated, "genericRecreate must return an MHZ Robotnik ship flame");
        assertEquals(fqn, recreated.getClass().getName(),
                "genericRecreate must return the same concrete flame class before restore");
        ((AbstractObjectInstance) recreated).restoreRewindState(state, restoreContext);

        assertSame(restoredParent, readObjectField(recreated, "parent"),
                "compact restore must resolve parent through the restore identity table");
        assertTrue(readBooleanField(recreated, "visibleThisFrame"),
                "standard restore must reapply visibleThisFrame");
        assertTrue(readBooleanField(recreated, "flipX"),
                "standard restore must reapply flipX");
    }

    @Test
    void mhzEndBossRobotnikShipFlameRestoresThroughObjectManagerIdentityPath() {
        ObjectManager[] holder = new ObjectManager[1];
        Camera camera = mockCamera();
        StubObjectServices services = new StubObjectServices() {
            @Override public ObjectManager objectManager() { return holder[0]; }
            @Override public Camera camera() { return camera; }
            @Override public SonicConfigurationService configuration() { return DEFAULT_CONFIGURATION; }
            @Override public ObjectRenderManager renderManager() { return INERT_RENDER_MANAGER; }
        };
        ObjectSpawn parentSpawn = new ObjectSpawn(
                0x3C40, 0x300, 0x93, 0, 0, false, 45);
        ObjectManager objectManager = new ObjectManager(
                List.of(),
                new Sonic3kObjectRegistry(),
                0,
                null,
                null,
                GraphicsManager.getInstance(),
                camera,
                services);
        holder[0] = objectManager;
        objectManager.reset(0);

        MhzEndBossInstance sourceParent = objectManager.createDynamicObject(
                () -> new MhzEndBossInstance(parentSpawn));
        MhzEndBossRobotnikShipFlameInstance sourceFlame = objectManager.createDynamicObject(
                () -> new MhzEndBossRobotnikShipFlameInstance(sourceParent));
        setBooleanField(sourceFlame, "visibleThisFrame", true);
        setBooleanField(sourceFlame, "flipX", true);
        assertEquals(1, liveObjects(objectManager, MhzEndBossInstance.class).size(),
                "precondition: exactly one captured MHZ end boss parent is live before snapshot");
        assertEquals(1, liveObjects(objectManager, MhzEndBossRobotnikShipFlameInstance.class).size(),
                "precondition: exactly one captured flame is live before snapshot");

        RewindIdentityTable captureTable = objectManager.captureIdentityContext().requireIdentityTable();
        ObjectRefId capturedParentId = captureTable.idFor(sourceParent);
        ObjectRefId capturedFlameId = captureTable.idFor(sourceFlame);
        assertNotNull(capturedParentId, "ObjectManager capture identity table must register the live boss");
        assertNotNull(capturedFlameId, "ObjectManager capture identity table must register the dynamic flame");

        RewindRegistry registry = new RewindRegistry();
        registry.register(objectManager.rewindSnapshottable());
        CompositeSnapshot snapshot = registry.capture();

        objectManager.removeDynamicObject(sourceFlame);
        objectManager.removeDynamicObject(sourceParent);
        MhzEndBossInstance divergentParent = objectManager.createDynamicObject(
                () -> new MhzEndBossInstance(new ObjectSpawn(0x3D00, 0x340, 0x93, 0, 0, false, 46)));
        MhzEndBossRobotnikShipFlameInstance divergentFlame = objectManager.createDynamicObject(
                () -> new MhzEndBossRobotnikShipFlameInstance(divergentParent));
        assertEquals(1, liveObjects(objectManager, MhzEndBossInstance.class).size(),
                "diverge step should leave one unrelated live parent before restore");
        assertEquals(1, liveObjects(objectManager, MhzEndBossRobotnikShipFlameInstance.class).size(),
                "diverge step should leave one unrelated live flame before restore");

        registry.restore(snapshot);

        MhzEndBossInstance restoredParent = singleLiveObject(objectManager, MhzEndBossInstance.class);
        MhzEndBossRobotnikShipFlameInstance restoredFlame =
                singleLiveObject(objectManager, MhzEndBossRobotnikShipFlameInstance.class);
        assertNotNull(restoredParent, "restore must keep the MHZ end boss live");
        assertNotNull(restoredFlame, "restore must recreate exactly one MHZ Robotnik ship flame");
        assertFalse(restoredParent == sourceParent,
                "restore should not retain the removed captured parent instance");
        assertFalse(restoredParent == divergentParent,
                "restore should replace divergent live dynamics with the captured parent snapshot entry");
        assertFalse(restoredFlame == sourceFlame,
                "restore should not retain the removed captured flame instance");
        assertFalse(restoredFlame == divergentFlame,
                "restore should replace divergent live dynamics with the captured snapshot entry");

        RewindIdentityTable restoredTable = objectManager.captureIdentityContext().requireIdentityTable();
        assertEquals(capturedParentId, restoredTable.idFor(restoredParent),
                "restored boss must retain the captured ObjectManager rewind identity");
        assertEquals(capturedFlameId, restoredTable.idFor(restoredFlame),
                "restored flame must retain the captured ObjectManager rewind identity");
        assertSame(restoredParent, readObjectField(restoredFlame, "parent"),
                "restored flame parent must resolve through ObjectManager's restore identity table");
        assertTrue(readBooleanField(restoredFlame, "visibleThisFrame"),
                "ObjectManager restore must reapply visibleThisFrame");
        assertTrue(readBooleanField(restoredFlame, "flipX"),
                "ObjectManager restore must reapply flipX");
    }

    @Test
    void batch45ClassRoundTripPassedThroughHarnessParentSeedPath() {
        CodecDeletionCandidate candidate = BATCH45_DELETED_CODECS.getFirst();
        RoundTripSweepResult result = RewindRoundTripHarness.probeClass(candidate.fqn());
        assertInstanceOf(RoundTripSweepResult.Passed.class, result,
                candidate.fqn()
                        + " must round-trip as Passed through the harness parent-seed path; got: "
                        + result);
    }

    // =====================================================================
    // Batch 46: S3K CNZ miniboss coil, parent ref restored by identity
    // =====================================================================

    @Test
    void batch46ClassesAllImplementRewindRecreatable() {
        for (CodecDeletionCandidate candidate : BATCH46_DELETED_CODECS) {
            Class<?> cls;
            try {
                cls = Class.forName(candidate.fqn());
            } catch (ClassNotFoundException e) {
                throw new AssertionError(e);
            }
            assertTrue(RewindRecreatable.class.isAssignableFrom(cls),
                    candidate.fqn() + " must implement RewindRecreatable (codec deleted in batch 46)");
        }
    }

    @Test
    void batch46ClassesHaveNoRegisteredCodec() {
        for (CodecDeletionCandidate candidate : BATCH46_DELETED_CODECS) {
            assertFalse(hasRegisteredDynamicCodec(candidate.fqn(), candidate.gameId()),
                    candidate.fqn() + " must have NO registered dynamic rewind codec after batch-46 deletion; "
                            + "session restore must use genericRecreate Path 1");
        }
    }

    @Test
    void batch46ClassesGenericRecreateProducesInstance() {
        for (CodecDeletionCandidate candidate : BATCH46_DELETED_CODECS) {
            ObjectInstance result = invokeGenericRecreate(candidate.fqn(), 0x3220, 0x280, candidate.gameId());
            assertNotNull(result, "genericRecreate must return non-null for " + candidate.fqn());
            assertEquals(candidate.fqn(), result.getClass().getName(),
                    "genericRecreate must return the same concrete class for " + candidate.fqn());
        }
    }

    @Test
    void cnzMinibossCoilGenericRecreateRestoresParentByIdentity() {
        ObjectSpawn parentSpawn =
                new ObjectSpawn(160, 240, Sonic3kObjectIds.CNZ_MINIBOSS, 0, 0, false, 46);
        CnzMinibossInstance sourceParent = new CnzMinibossInstance(parentSpawn);
        ObjectSpawn coilSpawn = new ObjectSpawn(152, 260, 0, 0, 0, false, 47);
        CnzMinibossCoilInstance source = new CnzMinibossCoilInstance(coilSpawn);
        source.attachBossForTest(sourceParent);

        RewindCaptureContext missingCaptureContext =
                RewindCaptureContext.withIdentityTable(new RewindIdentityTable());
        assertThrows(IllegalStateException.class,
                () -> source.captureRewindState(missingCaptureContext),
                "non-null CNZ miniboss parent must be registered before object-ref capture");

        ObjectRefId parentId = ObjectRefId.dynamic(4, 1, 46);
        RewindIdentityTable captureTable = new RewindIdentityTable();
        captureTable.registerObject(sourceParent, parentId);
        RewindCaptureContext captureContext = RewindCaptureContext.withIdentityTable(captureTable);
        PerObjectRewindSnapshot state = source.captureRewindState(captureContext);

        ObjectInstance unresolved = invokeGenericRecreateWithState(
                CnzMinibossCoilInstance.class.getName(), coilSpawn, state, GameId.S3K);
        RewindCaptureContext missingParentContext =
                RewindCaptureContext.withIdentityTable(new RewindIdentityTable());
        assertThrows(IllegalStateException.class,
                () -> ((AbstractObjectInstance) unresolved).restoreRewindState(state, missingParentContext),
                "non-null CNZ miniboss parent must use required object-ref resolve during compact restore");

        ObjectInstance recreated = invokeGenericRecreateWithState(
                CnzMinibossCoilInstance.class.getName(), coilSpawn, state, GameId.S3K);
        CnzMinibossInstance restoredParent = new CnzMinibossInstance(parentSpawn);
        RewindIdentityTable restoreTable = new RewindIdentityTable();
        restoreTable.registerObject(restoredParent, parentId);
        RewindCaptureContext restoreContext = RewindCaptureContext.withIdentityTable(restoreTable);
        assertNotNull(recreated, "genericRecreate must return a CNZ miniboss coil child");
        assertEquals(CnzMinibossCoilInstance.class, recreated.getClass(),
                "genericRecreate must return the same concrete coil class before restore");
        ((AbstractObjectInstance) recreated).restoreRewindState(state, restoreContext);

        assertSame(restoredParent, readObjectField(recreated, "boss"),
                "compact restore must resolve the CNZ miniboss parent through the restore identity table");
        assertEquals(readIntField(source, "parentOffsetX"), readIntField(recreated, "parentOffsetX"),
                "standard scalar restore must reapply the captured parent X offset");
        assertEquals(readIntField(source, "parentOffsetY"), readIntField(recreated, "parentOffsetY"),
                "standard scalar restore must reapply the captured parent Y offset");
    }

    @Test
    void cnzMinibossCoilRestoresThroughObjectManagerIdentityPath() {
        ObjectManager[] holder = new ObjectManager[1];
        Camera camera = mockCamera();
        StubObjectServices services = new StubObjectServices() {
            @Override public ObjectManager objectManager() { return holder[0]; }
            @Override public Camera camera() { return camera; }
            @Override public SonicConfigurationService configuration() { return DEFAULT_CONFIGURATION; }
            @Override public ObjectRenderManager renderManager() { return INERT_RENDER_MANAGER; }
        };
        ObjectSpawn parentSpawn =
                new ObjectSpawn(160, 240, Sonic3kObjectIds.CNZ_MINIBOSS, 0, 0, false, 46);
        ObjectManager objectManager = new ObjectManager(
                List.of(parentSpawn),
                cnzMinibossParentTestRegistry(),
                0,
                null,
                null,
                GraphicsManager.getInstance(),
                camera,
                services);
        holder[0] = objectManager;
        objectManager.reset(0);

        CnzMinibossInstance sourceParent = singleLiveObject(objectManager, CnzMinibossInstance.class);
        ObjectSpawn coilSpawn = new ObjectSpawn(152, 260, 0, 0, 0, false, 47);
        CnzMinibossCoilInstance sourceCoil = objectManager.createDynamicObject(
                () -> new CnzMinibossCoilInstance(coilSpawn));
        sourceCoil.attachBossForTest(sourceParent);
        assertEquals(1, liveObjects(objectManager, CnzMinibossInstance.class).size(),
                "precondition: exactly one captured CNZ miniboss parent is live before snapshot");
        assertEquals(1, liveObjects(objectManager, CnzMinibossCoilInstance.class).size(),
                "precondition: exactly one captured coil child is live before snapshot");

        RewindIdentityTable captureTable = objectManager.captureIdentityContext().requireIdentityTable();
        ObjectRefId capturedParentId = captureTable.idFor(sourceParent);
        ObjectRefId capturedCoilId = captureTable.idFor(sourceCoil);
        assertNotNull(capturedParentId, "ObjectManager capture identity table must register the CNZ miniboss");
        assertNotNull(capturedCoilId, "ObjectManager capture identity table must register the coil child");

        RewindRegistry registry = new RewindRegistry();
        registry.register(objectManager.rewindSnapshottable());
        CompositeSnapshot snapshot = registry.capture();

        objectManager.removeDynamicObject(sourceCoil);
        objectManager.createDynamicObject(() -> new CnzMinibossCoilInstance(
                new ObjectSpawn(180, 268, 0, 0, 0, false, 48)));
        assertEquals(1, liveObjects(objectManager, CnzMinibossCoilInstance.class).size(),
                "diverge step should leave one unrelated live coil before restore");

        registry.restore(snapshot);

        CnzMinibossInstance restoredParent = singleLiveObject(objectManager, CnzMinibossInstance.class);
        CnzMinibossCoilInstance restoredCoil =
                singleLiveObject(objectManager, CnzMinibossCoilInstance.class);
        assertFalse(restoredCoil == sourceCoil,
                "restore should not retain the removed captured coil instance");

        RewindIdentityTable restoredTable = objectManager.captureIdentityContext().requireIdentityTable();
        assertEquals(capturedParentId, restoredTable.idFor(restoredParent),
                "restored CNZ miniboss parent must retain the captured ObjectManager rewind identity");
        assertEquals(capturedCoilId, restoredTable.idFor(restoredCoil),
                "restored coil child must retain the captured ObjectManager rewind identity");
        assertSame(restoredParent, readObjectField(restoredCoil, "boss"),
                "restored coil parent must resolve through ObjectManager's restore identity table");
        assertEquals(readIntField(sourceCoil, "parentOffsetX"), readIntField(restoredCoil, "parentOffsetX"),
                "ObjectManager restore must reapply the captured parent X offset");
        assertEquals(readIntField(sourceCoil, "parentOffsetY"), readIntField(restoredCoil, "parentOffsetY"),
                "ObjectManager restore must reapply the captured parent Y offset");
    }

    @Test
    void batch46ClassRoundTripPassedThroughHarnessParentSeedPath() {
        CodecDeletionCandidate candidate = BATCH46_DELETED_CODECS.getFirst();
        RoundTripSweepResult result = RewindRoundTripHarness.probeClass(candidate.fqn());
        assertInstanceOf(RoundTripSweepResult.Passed.class, result,
                candidate.fqn()
                        + " must round-trip as Passed through the harness parent-seed path; got: "
                        + result);
    }

    // =====================================================================
    // Batch 47: S3K CNZ miniboss spark, parent ref restored by identity
    // =====================================================================

    @Test
    void batch47ClassesAllImplementRewindRecreatable() {
        for (CodecDeletionCandidate candidate : BATCH47_DELETED_CODECS) {
            Class<?> cls;
            try {
                cls = Class.forName(candidate.fqn());
            } catch (ClassNotFoundException e) {
                throw new AssertionError(e);
            }
            assertTrue(RewindRecreatable.class.isAssignableFrom(cls),
                    candidate.fqn() + " must implement RewindRecreatable (codec deleted in batch 47)");
        }
    }

    @Test
    void batch47ClassesHaveNoRegisteredCodec() {
        for (CodecDeletionCandidate candidate : BATCH47_DELETED_CODECS) {
            assertFalse(hasRegisteredDynamicCodec(candidate.fqn(), candidate.gameId()),
                    candidate.fqn() + " must have NO registered dynamic rewind codec after batch-47 deletion; "
                            + "session restore must use genericRecreate Path 1");
        }
    }

    @Test
    void batch47ClassesGenericRecreateProducesInstance() {
        for (CodecDeletionCandidate candidate : BATCH47_DELETED_CODECS) {
            ObjectInstance result = invokeGenericRecreate(candidate.fqn(), 0x3220, 0x280, candidate.gameId());
            assertNotNull(result, "genericRecreate must return non-null for " + candidate.fqn());
            assertEquals(candidate.fqn(), result.getClass().getName(),
                    "genericRecreate must return the same concrete class for " + candidate.fqn());
        }
    }

    @Test
    void cnzMinibossSparkGenericRecreateRestoresParentByIdentity() {
        ObjectSpawn parentSpawn =
                new ObjectSpawn(160, 240, Sonic3kObjectIds.CNZ_MINIBOSS, 0, 0, false, 46);
        CnzMinibossInstance sourceParent = new CnzMinibossInstance(parentSpawn);
        ObjectSpawn sparkSpawn = new ObjectSpawn(156, 280, Sonic3kObjectIds.CNZ_MINIBOSS, 2, 0, false, 47);
        CnzMinibossSparkInstance source = new CnzMinibossSparkInstance(sparkSpawn);
        source.attachBossForTest(sourceParent);
        setIntField(source, "mappingFrame", 0x0F);
        setIntField(source, "rawAnimPairIndex", 2);
        setIntField(source, "rawAnimTimer", 5);
        setBooleanField(source, "firstAnimationTick", false);

        RewindCaptureContext missingCaptureContext =
                RewindCaptureContext.withIdentityTable(new RewindIdentityTable());
        assertThrows(IllegalStateException.class,
                () -> source.captureRewindState(missingCaptureContext),
                "non-null CNZ miniboss parent must be registered before spark object-ref capture");

        ObjectRefId parentId = ObjectRefId.dynamic(4, 1, 46);
        RewindIdentityTable captureTable = new RewindIdentityTable();
        captureTable.registerObject(sourceParent, parentId);
        RewindCaptureContext captureContext = RewindCaptureContext.withIdentityTable(captureTable);
        PerObjectRewindSnapshot state = source.captureRewindState(captureContext);

        ObjectInstance unresolved = invokeGenericRecreateWithState(
                CnzMinibossSparkInstance.class.getName(), sparkSpawn, state, GameId.S3K);
        assertNotNull(unresolved, "genericRecreate must return a CNZ miniboss spark child");
        RewindCaptureContext missingParentContext =
                RewindCaptureContext.withIdentityTable(new RewindIdentityTable());
        assertThrows(IllegalStateException.class,
                () -> ((AbstractObjectInstance) unresolved).restoreRewindState(state, missingParentContext),
                "non-null CNZ miniboss spark parent must use required object-ref resolve during compact restore");

        ObjectInstance recreated = invokeGenericRecreateWithState(
                CnzMinibossSparkInstance.class.getName(), sparkSpawn, state, GameId.S3K);
        CnzMinibossInstance restoredParent = new CnzMinibossInstance(parentSpawn);
        RewindIdentityTable restoreTable = new RewindIdentityTable();
        restoreTable.registerObject(restoredParent, parentId);
        RewindCaptureContext restoreContext = RewindCaptureContext.withIdentityTable(restoreTable);
        assertEquals(CnzMinibossSparkInstance.class, recreated.getClass(),
                "genericRecreate must return the same concrete spark class before restore");
        ((AbstractObjectInstance) recreated).restoreRewindState(state, restoreContext);

        assertSame(restoredParent, readObjectField(recreated, "boss"),
                "compact restore must resolve the CNZ miniboss spark parent through the restore identity table");
        assertEquals(readIntField(source, "parentOffsetX"), readIntField(recreated, "parentOffsetX"),
                "standard scalar restore must reapply the captured spark parent X offset");
        assertEquals(readIntField(source, "parentOffsetY"), readIntField(recreated, "parentOffsetY"),
                "standard scalar restore must reapply the captured spark parent Y offset");
        assertEquals(readIntField(source, "mappingFrame"), readIntField(recreated, "mappingFrame"),
                "standard scalar restore must reapply the captured spark mapping frame");
        assertEquals(readIntField(source, "rawAnimPairIndex"), readIntField(recreated, "rawAnimPairIndex"),
                "standard scalar restore must reapply the captured spark animation index");
        assertEquals(readIntField(source, "rawAnimTimer"), readIntField(recreated, "rawAnimTimer"),
                "standard scalar restore must reapply the captured spark animation timer");
        assertEquals(readBooleanField(source, "firstAnimationTick"),
                readBooleanField(recreated, "firstAnimationTick"),
                "standard scalar restore must reapply the captured spark first-tick latch");
    }

    @Test
    void cnzMinibossSparkRestoresThroughObjectManagerIdentityPath() {
        ObjectManager[] holder = new ObjectManager[1];
        Camera camera = mockCamera();
        StubObjectServices services = new StubObjectServices() {
            @Override public ObjectManager objectManager() { return holder[0]; }
            @Override public Camera camera() { return camera; }
            @Override public SonicConfigurationService configuration() { return DEFAULT_CONFIGURATION; }
            @Override public ObjectRenderManager renderManager() { return INERT_RENDER_MANAGER; }
        };
        ObjectSpawn parentSpawn =
                new ObjectSpawn(160, 240, Sonic3kObjectIds.CNZ_MINIBOSS, 0, 0, false, 46);
        ObjectManager objectManager = new ObjectManager(
                List.of(parentSpawn),
                cnzMinibossParentTestRegistry(),
                0,
                null,
                null,
                GraphicsManager.getInstance(),
                camera,
                services);
        holder[0] = objectManager;
        objectManager.reset(0);

        CnzMinibossInstance sourceParent = singleLiveObject(objectManager, CnzMinibossInstance.class);
        ObjectSpawn sparkSpawn = new ObjectSpawn(156, 280, Sonic3kObjectIds.CNZ_MINIBOSS, 2, 0, false, 47);
        CnzMinibossSparkInstance sourceSpark = objectManager.createDynamicObject(
                () -> new CnzMinibossSparkInstance(sparkSpawn));
        sourceSpark.attachBossForTest(sourceParent);
        setIntField(sourceSpark, "mappingFrame", 0x0F);
        setIntField(sourceSpark, "rawAnimPairIndex", 2);
        setIntField(sourceSpark, "rawAnimTimer", 5);
        setBooleanField(sourceSpark, "firstAnimationTick", false);
        assertEquals(1, liveObjects(objectManager, CnzMinibossInstance.class).size(),
                "precondition: exactly one captured CNZ miniboss parent is live before snapshot");
        assertEquals(1, liveObjects(objectManager, CnzMinibossSparkInstance.class).size(),
                "precondition: exactly one captured spark child is live before snapshot");

        RewindIdentityTable captureTable = objectManager.captureIdentityContext().requireIdentityTable();
        ObjectRefId capturedParentId = captureTable.idFor(sourceParent);
        ObjectRefId capturedSparkId = captureTable.idFor(sourceSpark);
        assertNotNull(capturedParentId, "ObjectManager capture identity table must register the CNZ miniboss");
        assertNotNull(capturedSparkId, "ObjectManager capture identity table must register the spark child");

        RewindRegistry registry = new RewindRegistry();
        registry.register(objectManager.rewindSnapshottable());
        CompositeSnapshot snapshot = registry.capture();

        objectManager.removeDynamicObject(sourceSpark);
        objectManager.createDynamicObject(() -> new CnzMinibossSparkInstance(
                new ObjectSpawn(180, 284, Sonic3kObjectIds.CNZ_MINIBOSS, 4, 0, false, 48)));
        assertEquals(1, liveObjects(objectManager, CnzMinibossSparkInstance.class).size(),
                "diverge step should leave one unrelated live spark before restore");

        registry.restore(snapshot);

        CnzMinibossInstance restoredParent = singleLiveObject(objectManager, CnzMinibossInstance.class);
        CnzMinibossSparkInstance restoredSpark =
                singleLiveObject(objectManager, CnzMinibossSparkInstance.class);
        assertFalse(restoredSpark == sourceSpark,
                "restore should not retain the removed captured spark instance");

        RewindIdentityTable restoredTable = objectManager.captureIdentityContext().requireIdentityTable();
        assertEquals(capturedParentId, restoredTable.idFor(restoredParent),
                "restored CNZ miniboss parent must retain the captured ObjectManager rewind identity");
        assertEquals(capturedSparkId, restoredTable.idFor(restoredSpark),
                "restored spark child must retain the captured ObjectManager rewind identity");
        assertSame(restoredParent, readObjectField(restoredSpark, "boss"),
                "restored spark parent must resolve through ObjectManager's restore identity table");
        assertEquals(readIntField(sourceSpark, "parentOffsetX"), readIntField(restoredSpark, "parentOffsetX"),
                "ObjectManager restore must reapply the captured spark parent X offset");
        assertEquals(readIntField(sourceSpark, "parentOffsetY"), readIntField(restoredSpark, "parentOffsetY"),
                "ObjectManager restore must reapply the captured spark parent Y offset");
        assertEquals(readIntField(sourceSpark, "mappingFrame"), readIntField(restoredSpark, "mappingFrame"),
                "ObjectManager restore must reapply the captured spark mapping frame");
        assertEquals(readIntField(sourceSpark, "rawAnimPairIndex"),
                readIntField(restoredSpark, "rawAnimPairIndex"),
                "ObjectManager restore must reapply the captured spark animation index");
        assertEquals(readIntField(sourceSpark, "rawAnimTimer"), readIntField(restoredSpark, "rawAnimTimer"),
                "ObjectManager restore must reapply the captured spark animation timer");
        assertEquals(readBooleanField(sourceSpark, "firstAnimationTick"),
                readBooleanField(restoredSpark, "firstAnimationTick"),
                "ObjectManager restore must reapply the captured spark first-tick latch");
    }

    @Test
    void batch47ClassRoundTripPassedThroughHarnessParentSeedPath() {
        CodecDeletionCandidate candidate = BATCH47_DELETED_CODECS.getFirst();
        RoundTripSweepResult result = RewindRoundTripHarness.probeClass(candidate.fqn());
        assertInstanceOf(RoundTripSweepResult.Passed.class, result,
                candidate.fqn()
                        + " must round-trip as Passed through the harness parent-seed path; got: "
                        + result);
    }

    // =====================================================================
    // Batch 48: S3K CNZ miniboss top, parent relink during generic recreate
    // =====================================================================

    @Test
    void batch48ClassesAllImplementRewindRecreatable() {
        for (CodecDeletionCandidate candidate : BATCH48_DELETED_CODECS) {
            Class<?> cls;
            try {
                cls = Class.forName(candidate.fqn());
            } catch (ClassNotFoundException e) {
                throw new AssertionError(e);
            }
            assertTrue(RewindRecreatable.class.isAssignableFrom(cls),
                    candidate.fqn() + " must implement RewindRecreatable (codec deleted in batch 48)");
        }
    }

    @Test
    void batch48ClassesHaveNoRegisteredCodec() {
        for (CodecDeletionCandidate candidate : BATCH48_DELETED_CODECS) {
            assertFalse(hasRegisteredDynamicCodec(candidate.fqn(), candidate.gameId()),
                    candidate.fqn() + " must have NO registered dynamic rewind codec after batch-48 deletion; "
                            + "session restore must use genericRecreate Path 1 with restore-time parent relink");
        }
    }

    @Test
    void batch48ClassesGenericRecreateProducesInstanceAndRelinksParent() {
        ObjectManager[] holder = new ObjectManager[1];
        Camera camera = mockCamera();
        StubObjectServices services = new StubObjectServices() {
            @Override public ObjectManager objectManager() { return holder[0]; }
            @Override public Camera camera() { return camera; }
            @Override public SonicConfigurationService configuration() { return DEFAULT_CONFIGURATION; }
            @Override public ObjectRenderManager renderManager() { return INERT_RENDER_MANAGER; }
        };
        ObjectSpawn parentSpawn =
                new ObjectSpawn(160, 240, Sonic3kObjectIds.CNZ_MINIBOSS, 0, 0, false, 48);
        ObjectManager objectManager = new ObjectManager(
                List.of(parentSpawn),
                cnzMinibossParentTestRegistry(),
                0,
                null,
                null,
                GraphicsManager.getInstance(),
                camera,
                services);
        holder[0] = objectManager;
        objectManager.reset(0);

        CnzMinibossInstance liveParent = singleLiveObject(objectManager, CnzMinibossInstance.class);
        ObjectSpawn topSpawn = new ObjectSpawn(168, 224, Sonic3kObjectIds.CNZ_MINIBOSS, 0, 0, false, 49);
        PerObjectRewindSnapshot state = new PerObjectRewindSnapshot(
                false, false, false, 0, 0, 0, 0, false, 0, false, false, 0, -1, null, null, null);
        ObjectManagerSnapshot.DynamicObjectEntry entry =
                new ObjectManagerSnapshot.DynamicObjectEntry(
                        CnzMinibossTopInstance.class.getName(), topSpawn, 0, state);

        ObjectInstance result = ObjectRewindDynamicCodecs.genericRecreate(
                entry, new DynamicObjectRecreateContext(objectManager));

        assertNotNull(result, "genericRecreate must return a CNZ miniboss top child");
        assertEquals(CnzMinibossTopInstance.class, result.getClass(),
                "genericRecreate must return the same concrete top class");
        assertSame(liveParent, readObjectField(result, "boss"),
                "genericRecreate must relink the top to the restore-time CNZ miniboss parent");
    }

    @Test
    void batch48ClassRoundTripPassedThroughHarnessParentSeedPath() {
        CodecDeletionCandidate candidate = BATCH48_DELETED_CODECS.getFirst();
        RoundTripSweepResult result = RewindRoundTripHarness.probeClass(candidate.fqn());
        assertInstanceOf(RoundTripSweepResult.Passed.class, result,
                candidate.fqn()
                        + " must round-trip as Passed through the harness parent-seed path; got: "
                        + result);
    }

    // =====================================================================
    // Batch 49: S3K Buggernaut baby, transient parent relink during generic recreate
    // =====================================================================

    @Test
    void batch49ClassesAllImplementRewindRecreatable() {
        for (CodecDeletionCandidate candidate : BATCH49_DELETED_CODECS) {
            Class<?> cls;
            try {
                cls = Class.forName(candidate.fqn());
            } catch (ClassNotFoundException e) {
                throw new AssertionError(e);
            }
            assertTrue(RewindRecreatable.class.isAssignableFrom(cls),
                    candidate.fqn() + " must implement RewindRecreatable (codec deleted in batch 49)");
        }
    }

    @Test
    void batch49ClassesHaveNoRegisteredCodec() {
        for (CodecDeletionCandidate candidate : BATCH49_DELETED_CODECS) {
            assertFalse(hasRegisteredDynamicCodec(candidate.fqn(), candidate.gameId()),
                    candidate.fqn() + " must have NO registered dynamic rewind codec after batch-49 deletion; "
                            + "session restore must use genericRecreate Path 1 with live Buggernaut relink");
        }
    }

    @Test
    void batch49ClassesGenericRecreateProducesInstanceAndRelinksParent() {
        ObjectManager[] holder = new ObjectManager[1];
        Camera camera = mockCamera();
        StubObjectServices services = new StubObjectServices() {
            @Override public ObjectManager objectManager() { return holder[0]; }
            @Override public Camera camera() { return camera; }
            @Override public SonicConfigurationService configuration() { return DEFAULT_CONFIGURATION; }
            @Override public ObjectRenderManager renderManager() { return INERT_RENDER_MANAGER; }
        };
        ObjectSpawn parentSpawn =
                new ObjectSpawn(160, 160, Sonic3kObjectIds.BUGGERNAUT, 0, 0, false, 49);
        ObjectManager objectManager = new ObjectManager(
                List.of(parentSpawn),
                new Sonic3kObjectRegistry(),
                0,
                null,
                null,
                GraphicsManager.getInstance(),
                camera,
                services);
        holder[0] = objectManager;
        objectManager.reset(0);

        BuggernautBadnikInstance liveParent = singleLiveObject(objectManager, BuggernautBadnikInstance.class);
        setIntField(liveParent, "childCount", 4);
        ObjectSpawn babySpawn = new ObjectSpawn(192, 160, Sonic3kObjectIds.BUGGERNAUT, 0, 0, false, 50);
        PerObjectRewindSnapshot state = new PerObjectRewindSnapshot(
                false, false, false, 0, 0, 0, 0, false, 0, false, false, 0, -1, null, null, null);
        ObjectManagerSnapshot.DynamicObjectEntry entry =
                new ObjectManagerSnapshot.DynamicObjectEntry(
                        BuggernautBabyInstance.class.getName(), babySpawn, 0, state);

        ObjectInstance result = ObjectRewindDynamicCodecs.genericRecreate(
                entry, new DynamicObjectRecreateContext(objectManager));

        assertNotNull(result, "genericRecreate must return a Buggernaut baby child");
        assertEquals(BuggernautBabyInstance.class, result.getClass(),
                "genericRecreate must return the same concrete baby class");
        assertSame(liveParent, readObjectField(result, "parent"),
                "genericRecreate must relink the baby to the restore-time Buggernaut parent "
                        + "even when the captured parent child-count state is full");
    }

    @Test
    void batch49ClassRoundTripPassedThroughHarnessParentSeedPath() {
        CodecDeletionCandidate candidate = BATCH49_DELETED_CODECS.getFirst();
        RoundTripSweepResult result = RewindRoundTripHarness.probeClass(candidate.fqn());
        assertInstanceOf(RoundTripSweepResult.Passed.class, result,
                candidate.fqn()
                        + " must round-trip as Passed through the harness parent-seed path; got: "
                        + result);
    }

    // =====================================================================
    // Batch 50: S3K transient-parent badnik children, generic recreate relinks live parent
    // =====================================================================

    @Test
    void batch50ClassesAllImplementRewindRecreatable() {
        for (CodecDeletionCandidate candidate : BATCH50_DELETED_CODECS) {
            Class<?> cls;
            try {
                cls = Class.forName(candidate.fqn());
            } catch (ClassNotFoundException e) {
                throw new AssertionError(e);
            }
            assertTrue(RewindRecreatable.class.isAssignableFrom(cls),
                    candidate.fqn() + " must implement RewindRecreatable (codec deleted in batch 50)");
        }
    }

    @Test
    void batch50ClassesHaveNoRegisteredCodec() {
        for (CodecDeletionCandidate candidate : BATCH50_DELETED_CODECS) {
            assertFalse(hasRegisteredDynamicCodec(candidate.fqn(), candidate.gameId()),
                    candidate.fqn() + " must have NO registered dynamic rewind codec after batch-50 deletion; "
                            + "session restore must use genericRecreate Path 1 with live parent relink");
        }
    }

    @Test
    void batch50ClassesGenericRecreateProducesInstanceAndRelinksParent() {
        for (CodecDeletionCandidate candidate : BATCH50_DELETED_CODECS) {
            ObjectManager[] holder = new ObjectManager[1];
            Camera camera = mockCamera();
            StubObjectServices services = new StubObjectServices() {
                @Override public ObjectManager objectManager() { return holder[0]; }
                @Override public Camera camera() { return camera; }
                @Override public SonicConfigurationService configuration() { return DEFAULT_CONFIGURATION; }
                @Override public ObjectRenderManager renderManager() { return INERT_RENDER_MANAGER; }
            };
            Batch50ParentSpec parentSpec = batch50ParentSpec(candidate.fqn());
            ObjectSpawn parentSpawn =
                    new ObjectSpawn(160, 160, parentSpec.objectId(), 0, 0, false, 50);
            ObjectManager objectManager = new ObjectManager(
                    List.of(parentSpawn),
                    new Sonic3kObjectRegistry(),
                    0,
                    null,
                    null,
                    GraphicsManager.getInstance(),
                    camera,
                    services);
            holder[0] = objectManager;
            objectManager.reset(0);

            Object liveParent = singleLiveObject(objectManager, parentSpec.parentClass());
            ObjectSpawn childSpawn = new ObjectSpawn(176, 176, parentSpec.objectId(), 0, 0, false, 51);
            PerObjectRewindSnapshot state = new PerObjectRewindSnapshot(
                    false, false, false, 0, 0, 0, 0, false, 0, false, false, 0, -1, null, null, null);
            ObjectManagerSnapshot.DynamicObjectEntry entry =
                    new ObjectManagerSnapshot.DynamicObjectEntry(candidate.fqn(), childSpawn, 0, state);

            ObjectInstance result = ObjectRewindDynamicCodecs.genericRecreate(
                    entry, new DynamicObjectRecreateContext(objectManager));

            assertNotNull(result, "genericRecreate must return a transient-parent child for " + candidate.fqn());
            assertEquals(candidate.fqn(), result.getClass().getName(),
                    "genericRecreate must return the same concrete child class");
            assertSame(liveParent, readObjectField(result, "parent"),
                    "genericRecreate must relink the child to the restore-time parent for "
                            + candidate.fqn());
        }
    }

    @Test
    void batch50ClassesRoundTripPassedThroughHarnessParentSeedPath() {
        for (CodecDeletionCandidate candidate : BATCH50_DELETED_CODECS) {
            RoundTripSweepResult result = RewindRoundTripHarness.probeClass(candidate.fqn());
            assertInstanceOf(RoundTripSweepResult.Passed.class, result,
                    candidate.fqn()
                            + " must round-trip as Passed through the harness parent-seed path; got: "
                            + result);
        }
    }

    // =====================================================================
    // Batch 51: S2 parent-seeded badnik children, generic recreate relinks live parent
    // =====================================================================

    @Test
    void batch51ClassesAllImplementRewindRecreatable() {
        for (CodecDeletionCandidate candidate : BATCH51_DELETED_CODECS) {
            Class<?> cls = loadClass(candidate.fqn());
            assertTrue(RewindRecreatable.class.isAssignableFrom(cls),
                    candidate.fqn() + " must implement RewindRecreatable (codec deleted in batch 51)");
        }
    }

    @Test
    void batch51ClassesHaveNoRegisteredCodec() {
        for (CodecDeletionCandidate candidate : BATCH51_DELETED_CODECS) {
            assertFalse(hasRegisteredDynamicCodec(candidate.fqn(), candidate.gameId()),
                    candidate.fqn() + " must have NO registered dynamic rewind codec after batch-51 deletion; "
                            + "session restore must use genericRecreate Path 1 with live parent relink");
        }
    }

    @Test
    void batch51ClassesGenericRecreateProducesInstanceAndRelinksParent() {
        for (CodecDeletionCandidate candidate : BATCH51_DELETED_CODECS) {
            ObjectManager[] holder = new ObjectManager[1];
            Camera camera = mockCamera();
            StubObjectServices services = new StubObjectServices() {
                @Override public ObjectManager objectManager() { return holder[0]; }
                @Override public Camera camera() { return camera; }
                @Override public SonicConfigurationService configuration() { return DEFAULT_CONFIGURATION; }
                @Override public ObjectRenderManager renderManager() { return INERT_RENDER_MANAGER; }
            };
            Batch51ParentSpec parentSpec = batch51ParentSpec(candidate.fqn());
            ObjectSpawn parentSpawn =
                    new ObjectSpawn(160, 160, parentSpec.objectId(), 0, 0, false, 50);
            ObjectManager objectManager = new ObjectManager(
                    List.of(parentSpawn),
                    new Sonic2ObjectRegistry(),
                    0,
                    null,
                    null,
                    GraphicsManager.getInstance(),
                    camera,
                    services);
            holder[0] = objectManager;
            objectManager.reset(0);

            Object liveParent = singleLiveObject(objectManager, parentSpec.parentClass());
            ObjectSpawn childSpawn = new ObjectSpawn(176, 176, parentSpec.objectId(), 0, 0, false, 51);
            PerObjectRewindSnapshot state = batch51State(candidate.fqn(), liveParent);
            ObjectManagerSnapshot.DynamicObjectEntry entry =
                    new ObjectManagerSnapshot.DynamicObjectEntry(candidate.fqn(), childSpawn, 0, state);

            ObjectInstance result = ObjectRewindDynamicCodecs.genericRecreate(
                    entry, new DynamicObjectRecreateContext(objectManager));

            assertNotNull(result, "genericRecreate must return a parent-linked S2 child for " + candidate.fqn());
            assertEquals(candidate.fqn(), result.getClass().getName(),
                    "genericRecreate must return the same concrete child class");
            assertSame(liveParent, readObjectField(result, "parent"),
                    "genericRecreate must relink the child to the restore-time parent for "
                            + candidate.fqn());
        }
    }

    @Test
    void batch51ClassesRoundTripPassedThroughHarnessParentSeedPath() {
        for (CodecDeletionCandidate candidate : BATCH51_DELETED_CODECS) {
            RoundTripSweepResult result = RewindRoundTripHarness.probeClass(candidate.fqn());
            assertInstanceOf(RoundTripSweepResult.Passed.class, result,
                    candidate.fqn()
                            + " must round-trip as Passed through parent-seeded harness coverage; got: "
                            + result);
        }
    }

    // =====================================================================
    // Batch 52: S2 boss children, generic recreate relinks live parent
    // =====================================================================

    @Test
    void batch52ClassesAllImplementRewindRecreatable() {
        for (CodecDeletionCandidate candidate : BATCH52_DELETED_CODECS) {
            Class<?> cls = loadClass(candidate.fqn());
            assertTrue(RewindRecreatable.class.isAssignableFrom(cls),
                    candidate.fqn() + " must implement RewindRecreatable (codec deleted in batch 52)");
        }
    }

    @Test
    void batch52ClassesHaveNoRegisteredCodec() {
        for (CodecDeletionCandidate candidate : BATCH52_DELETED_CODECS) {
            assertFalse(hasRegisteredDynamicCodec(candidate.fqn(), candidate.gameId()),
                    candidate.fqn() + " must have NO registered dynamic rewind codec after batch-52 deletion; "
                            + "session restore must use genericRecreate Path 1 with live parent relink");
        }
    }

    @Test
    void batch52ClassesGenericRecreateProducesInstanceAndRelinksClosestLiveParent() {
        for (CodecDeletionCandidate candidate : BATCH52_DELETED_CODECS) {
            ObjectManager[] holder = new ObjectManager[1];
            Camera camera = mockCamera();
            StubObjectServices services = new StubObjectServices() {
                @Override public ObjectManager objectManager() { return holder[0]; }
                @Override public Camera camera() { return camera; }
                @Override public SonicConfigurationService configuration() { return DEFAULT_CONFIGURATION; }
                @Override public ObjectRenderManager renderManager() { return INERT_RENDER_MANAGER; }
            };
            ObjectManager objectManager = new ObjectManager(
                    List.of(),
                    new Sonic2ObjectRegistry(),
                    0,
                    null,
                    null,
                    GraphicsManager.getInstance(),
                    camera,
                    services);
            holder[0] = objectManager;
            objectManager.reset(0);

            Batch52ParentSpec parentSpec = batch52ParentSpec(candidate.fqn());
            AbstractObjectInstance farParent = objectManager.createDynamicObject(
                    () -> parentSpec.createParent(
                            new ObjectSpawn(0x100, 0x100, parentSpec.objectId(), 0, 0, false, 52)));
            AbstractObjectInstance nearParent = objectManager.createDynamicObject(
                    () -> parentSpec.createParent(
                            new ObjectSpawn(0x220, 0x220, parentSpec.objectId(), 0, 0, false, 53)));
            setBossPosition(farParent, 0x100, 0x100);
            setBossPosition(nearParent, 0x220, 0x220);
            ObjectSpawn childSpawn = new ObjectSpawn(0x224, 0x224, parentSpec.objectId(), 4, 0, false, 54);
            PerObjectRewindSnapshot state = new PerObjectRewindSnapshot(
                    false, false, false, 0, 0, 0, 0, false, 0, false, false, 0, -1, null, null, null);
            ObjectManagerSnapshot.DynamicObjectEntry entry =
                    new ObjectManagerSnapshot.DynamicObjectEntry(candidate.fqn(), childSpawn, 0, state);

            ObjectInstance result = ObjectRewindDynamicCodecs.genericRecreate(
                    entry, new DynamicObjectRecreateContext(objectManager));

            assertNotNull(result, "genericRecreate must return a parent-linked S2 boss child for "
                    + candidate.fqn());
            assertEquals(candidate.fqn(), result.getClass().getName(),
                    "genericRecreate must return the same concrete child class");
            assertSame(nearParent, readObjectField(result, "mainBoss"),
                    "genericRecreate must relink the child to the closest restore-time live parent for "
                            + candidate.fqn());
        }
    }

    @Test
    void batch52ClassesRoundTripPassedThroughHarnessParentSeedPath() {
        for (CodecDeletionCandidate candidate : BATCH52_DELETED_CODECS) {
            RoundTripSweepResult result = RewindRoundTripHarness.probeClass(candidate.fqn());
            assertInstanceOf(RoundTripSweepResult.Passed.class, result,
                    candidate.fqn()
                            + " must round-trip as Passed through parent-seeded harness coverage; got: "
                            + result);
        }
    }

    // =====================================================================
    // Shared helper codec deletion: animal/explosion/skid dust
    // =====================================================================

    @Test
    void sharedHelperClassesAllImplementRewindRecreatable() {
        for (CodecDeletionCandidate candidate : SHARED_HELPER_DELETED_CODECS) {
            Class<?> cls = loadClass(candidate.fqn());
            assertTrue(RewindRecreatable.class.isAssignableFrom(cls),
                    candidate.fqn()
                            + " must implement RewindRecreatable after shared helper codec deletion");
        }
    }

    @Test
    void sharedHelperClassesHaveNoRegisteredCodec() {
        for (CodecDeletionCandidate candidate : SHARED_HELPER_DELETED_CODECS) {
            assertFalse(hasRegisteredDynamicCodec(candidate.fqn(), candidate.gameId()),
                    candidate.fqn()
                            + " must restore through shared generic recreate, not a dynamic codec");
        }
    }

    @Test
    void sharedHelperClassesRoundTripThroughGenericRecreate() {
        for (CodecDeletionCandidate candidate : SHARED_HELPER_DELETED_CODECS) {
            RoundTripSweepResult result = RewindRoundTripHarness.probeClass(candidate.fqn());
            assertInstanceOf(RoundTripSweepResult.Passed.class, result,
                    candidate.fqn() + " should round-trip through generic recreate");
        }
    }

    @Test
    void animalRoundTripProbeRecognizesDeferredPublicConstructor() {
        RoundTripSweepResult result =
                RewindRoundTripHarness.probeClass(AnimalObjectInstance.class.getName());

        assertInstanceOf(RoundTripSweepResult.Passed.class, result,
                "AnimalObjectInstance must remain probeable without restoring eager constructor RNG");
    }

    // =====================================================================
    // Shared sparkle codec deletion: signpost sparkle
    // =====================================================================

    @Test
    void sharedSparkleClassesAllImplementRewindRecreatable() {
        for (CodecDeletionCandidate candidate : SHARED_SPARKLE_DELETED_CODECS) {
            Class<?> cls = loadClass(candidate.fqn());
            assertTrue(RewindRecreatable.class.isAssignableFrom(cls),
                    candidate.fqn()
                            + " must implement RewindRecreatable after shared sparkle codec deletion");
        }
    }

    @Test
    void sharedSparkleClassesHaveNoRegisteredCodec() {
        for (CodecDeletionCandidate candidate : SHARED_SPARKLE_DELETED_CODECS) {
            assertFalse(hasRegisteredDynamicCodec(candidate.fqn(), candidate.gameId()),
                    candidate.fqn()
                            + " must restore through shared generic recreate, not a dynamic codec");
        }
    }

    @Test
    void sharedSparkleClassesRoundTripThroughGenericRecreate() {
        for (CodecDeletionCandidate candidate : SHARED_SPARKLE_DELETED_CODECS) {
            RoundTripSweepResult result = RewindRoundTripHarness.probeClass(candidate.fqn());
            assertInstanceOf(RoundTripSweepResult.Passed.class, result,
                    candidate.fqn() + " should round-trip through generic recreate");
        }
    }

    // =====================================================================
    // Shared placeholder generic recreate: unmapped object fallback
    // =====================================================================

    @Test
    void sharedPlaceholderClassesAllImplementRewindRecreatable() {
        for (CodecDeletionCandidate candidate : SHARED_PLACEHOLDER_RECREATE_CLASSES) {
            Class<?> cls = loadClass(candidate.fqn());
            assertTrue(RewindRecreatable.class.isAssignableFrom(cls),
                    candidate.fqn()
                            + " must implement RewindRecreatable after shared placeholder coverage");
        }
    }

    @Test
    void sharedPlaceholderClassesHaveNoRegisteredCodec() {
        for (CodecDeletionCandidate candidate : SHARED_PLACEHOLDER_RECREATE_CLASSES) {
            assertFalse(hasRegisteredDynamicCodec(candidate.fqn(), candidate.gameId()),
                    candidate.fqn()
                            + " must restore through shared placeholder generic recreate, not a dynamic codec");
        }
    }

    @Test
    void sharedPlaceholderClassesRoundTripThroughGenericRecreate() {
        for (CodecDeletionCandidate candidate : SHARED_PLACEHOLDER_RECREATE_CLASSES) {
            RoundTripSweepResult result = RewindRoundTripHarness.probeClass(candidate.fqn());
            assertInstanceOf(RoundTripSweepResult.Passed.class, result,
                    candidate.fqn() + " should round-trip through shared placeholder generic recreate");
        }
    }

    // =====================================================================
    // Shared water-effect generic recreate
    // =====================================================================

    @Test
    void sharedWaterEffectClassesAllImplementRewindRecreatable() {
        for (CodecDeletionCandidate candidate : SHARED_WATER_EFFECT_RECREATE_CLASSES) {
            Class<?> cls = loadClass(candidate.fqn());
            assertTrue(RewindRecreatable.class.isAssignableFrom(cls),
                    candidate.fqn() + " must implement RewindRecreatable after shared water-effect coverage");
        }
    }

    @Test
    void sharedWaterEffectClassesHaveNoRegisteredCodec() {
        for (CodecDeletionCandidate candidate : SHARED_WATER_EFFECT_RECREATE_CLASSES) {
            assertFalse(hasRegisteredDynamicCodec(candidate.fqn(), candidate.gameId()),
                    candidate.fqn()
                            + " must restore through shared water-effect generic recreate, not a dynamic codec");
        }
    }

    @Test
    void sharedWaterEffectClassesRoundTripThroughGenericRecreate() {
        for (CodecDeletionCandidate candidate : SHARED_WATER_EFFECT_RECREATE_CLASSES) {
            RoundTripSweepResult result = RewindRoundTripHarness.probeClass(candidate.fqn());
            assertInstanceOf(RoundTripSweepResult.Passed.class, result,
                    candidate.fqn() + " should round-trip through shared water-effect generic recreate");
        }
    }

    // =====================================================================
    // S2 Speed Launcher generic recreate
    // =====================================================================

    @Test
    void s2SpeedLauncherClassesAllImplementRewindRecreatable() {
        for (CodecDeletionCandidate candidate : S2_SPEED_LAUNCHER_RECREATE_CLASSES) {
            Class<?> cls = loadClass(candidate.fqn());
            assertTrue(RewindRecreatable.class.isAssignableFrom(cls),
                    candidate.fqn() + " must implement RewindRecreatable after Speed Launcher coverage");
        }
    }

    @Test
    void s2SpeedLauncherClassesHaveNoRegisteredCodec() {
        for (CodecDeletionCandidate candidate : S2_SPEED_LAUNCHER_RECREATE_CLASSES) {
            assertFalse(hasRegisteredDynamicCodec(candidate.fqn(), candidate.gameId()),
                    candidate.fqn()
                            + " must restore through Speed Launcher generic recreate, not a dynamic codec");
        }
    }

    @Test
    void s2SpeedLauncherClassesRoundTripThroughGenericRecreate() {
        for (CodecDeletionCandidate candidate : S2_SPEED_LAUNCHER_RECREATE_CLASSES) {
            RoundTripSweepResult result = RewindRoundTripHarness.probeClass(candidate.fqn());
            assertInstanceOf(RoundTripSweepResult.Passed.class, result,
                    candidate.fqn() + " should round-trip through Speed Launcher generic recreate");
        }
    }

    // =====================================================================
    // S2 MTZ Spin Tube generic recreate
    // =====================================================================

    @Test
    void s2MtzSpinTubeClassesAllImplementRewindRecreatable() {
        for (CodecDeletionCandidate candidate : S2_MTZ_SPIN_TUBE_RECREATE_CLASSES) {
            Class<?> cls = loadClass(candidate.fqn());
            assertTrue(RewindRecreatable.class.isAssignableFrom(cls),
                    candidate.fqn() + " must implement RewindRecreatable after MTZ Spin Tube coverage");
        }
    }

    @Test
    void s2MtzSpinTubeClassesHaveNoRegisteredCodec() {
        for (CodecDeletionCandidate candidate : S2_MTZ_SPIN_TUBE_RECREATE_CLASSES) {
            assertFalse(hasRegisteredDynamicCodec(candidate.fqn(), candidate.gameId()),
                    candidate.fqn()
                            + " must restore through MTZ Spin Tube generic recreate, not a dynamic codec");
        }
    }

    @Test
    void s2MtzSpinTubeClassesRoundTripThroughGenericRecreate() {
        for (CodecDeletionCandidate candidate : S2_MTZ_SPIN_TUBE_RECREATE_CLASSES) {
            RoundTripSweepResult result = RewindRoundTripHarness.probeClass(candidate.fqn());
            assertInstanceOf(RoundTripSweepResult.Passed.class, result,
                    candidate.fqn() + " should round-trip through MTZ Spin Tube generic recreate");
        }
    }

    // =====================================================================
    // S2 CPZ Spin Tube generic recreate
    // =====================================================================

    @Test
    void s2CpzSpinTubeClassesAllImplementRewindRecreatable() {
        for (CodecDeletionCandidate candidate : S2_CPZ_SPIN_TUBE_RECREATE_CLASSES) {
            Class<?> cls = loadClass(candidate.fqn());
            assertTrue(RewindRecreatable.class.isAssignableFrom(cls),
                    candidate.fqn() + " must implement RewindRecreatable after CPZ Spin Tube coverage");
        }
    }

    @Test
    void s2CpzSpinTubeClassesHaveNoRegisteredCodec() {
        for (CodecDeletionCandidate candidate : S2_CPZ_SPIN_TUBE_RECREATE_CLASSES) {
            assertFalse(hasRegisteredDynamicCodec(candidate.fqn(), candidate.gameId()),
                    candidate.fqn()
                            + " must restore through CPZ Spin Tube generic recreate, not a dynamic codec");
        }
    }

    @Test
    void s2CpzSpinTubeClassesRoundTripThroughGenericRecreate() {
        for (CodecDeletionCandidate candidate : S2_CPZ_SPIN_TUBE_RECREATE_CLASSES) {
            RoundTripSweepResult result = RewindRoundTripHarness.probeClass(candidate.fqn());
            assertInstanceOf(RoundTripSweepResult.Passed.class, result,
                    candidate.fqn() + " should round-trip through CPZ Spin Tube generic recreate");
        }
    }

    @Test
    void s2CpzSpinTubeFieldsAreMutableForCompactRestore() {
        for (MutableFieldCoverageCandidate candidate : S2_CPZ_SPIN_TUBE_RECREATE_MUTABLE_FIELDS) {
            Class<?> cls = loadClass(candidate.fqn());
            for (String fieldName : candidate.fieldNames()) {
                try {
                    var field = findField(cls, fieldName);
                    assertFalse(Modifier.isFinal(field.getModifiers()),
                            cls.getName() + "#" + fieldName
                                    + " must be mutable so compact restore can replay captured scalars");
                } catch (NoSuchFieldException e) {
                    throw new AssertionError("Missing scalar field " + cls.getName() + "#" + fieldName, e);
                }
            }
        }
    }

    // =====================================================================
    // S2 MTZ Long Platform generic recreate
    // =====================================================================

    @Test
    void s2MtzLongPlatformClassesAllImplementRewindRecreatable() {
        for (CodecDeletionCandidate candidate : S2_MTZ_LONG_PLATFORM_RECREATE_CLASSES) {
            Class<?> cls = loadClass(candidate.fqn());
            assertTrue(RewindRecreatable.class.isAssignableFrom(cls),
                    candidate.fqn() + " must implement RewindRecreatable after MTZ Long Platform coverage");
        }
    }

    @Test
    void s2MtzLongPlatformClassesHaveNoRegisteredCodec() {
        for (CodecDeletionCandidate candidate : S2_MTZ_LONG_PLATFORM_RECREATE_CLASSES) {
            assertFalse(hasRegisteredDynamicCodec(candidate.fqn(), candidate.gameId()),
                    candidate.fqn()
                            + " must restore through MTZ Long Platform generic recreate, not a dynamic codec");
        }
    }

    @Test
    void s2MtzLongPlatformClassesRoundTripThroughGenericRecreate() {
        for (CodecDeletionCandidate candidate : S2_MTZ_LONG_PLATFORM_RECREATE_CLASSES) {
            RoundTripSweepResult result = RewindRoundTripHarness.probeClass(candidate.fqn());
            assertInstanceOf(RoundTripSweepResult.Passed.class, result,
                    candidate.fqn() + " should round-trip through MTZ Long Platform generic recreate");
        }
    }

    // =====================================================================
    // S2 collapsing platform generic recreate
    // =====================================================================

    @Test
    void s2CollapsingPlatformClassesAllImplementRewindRecreatable() {
        for (CodecDeletionCandidate candidate : S2_COLLAPSING_PLATFORM_RECREATE_CLASSES) {
            Class<?> cls = loadClass(candidate.fqn());
            assertTrue(RewindRecreatable.class.isAssignableFrom(cls),
                    candidate.fqn() + " must implement RewindRecreatable after collapsing-platform coverage");
        }
    }

    @Test
    void s2CollapsingPlatformClassesHaveNoRegisteredCodec() {
        for (CodecDeletionCandidate candidate : S2_COLLAPSING_PLATFORM_RECREATE_CLASSES) {
            assertFalse(hasRegisteredDynamicCodec(candidate.fqn(), candidate.gameId()),
                    candidate.fqn()
                            + " must restore through collapsing-platform generic recreate, not a dynamic codec");
        }
    }

    @Test
    void s2CollapsingPlatformClassesRoundTripThroughGenericRecreate() {
        for (CodecDeletionCandidate candidate : S2_COLLAPSING_PLATFORM_RECREATE_CLASSES) {
            RoundTripSweepResult result = RewindRoundTripHarness.probeClass(candidate.fqn());
            assertInstanceOf(RoundTripSweepResult.Passed.class, result,
                    candidate.fqn() + " should round-trip through collapsing-platform generic recreate");
        }
    }

    @Test
    void s2CollapsingPlatformFieldsAreMutableForCompactRestore() {
        for (MutableFieldCoverageCandidate candidate : S2_COLLAPSING_PLATFORM_RECREATE_MUTABLE_FIELDS) {
            Class<?> cls = loadClass(candidate.fqn());
            for (String fieldName : candidate.fieldNames()) {
                try {
                    var field = findField(cls, fieldName);
                    assertFalse(Modifier.isFinal(field.getModifiers()),
                            cls.getName() + "#" + fieldName
                                    + " must be mutable so compact restore can replay captured scalars");
                } catch (NoSuchFieldException e) {
                    throw new AssertionError("Missing scalar field " + cls.getName() + "#" + fieldName, e);
                }
            }
        }
    }

    @Test
    void s2CollapsingPlatformFragmentClassesAllImplementRewindRecreatable() {
        for (CodecDeletionCandidate candidate : S2_COLLAPSING_PLATFORM_FRAGMENT_RECREATE_CLASSES) {
            Class<?> cls = loadClass(candidate.fqn());
            assertTrue(RewindRecreatable.class.isAssignableFrom(cls),
                    candidate.fqn() + " must implement RewindRecreatable after collapsing-platform fragment coverage");
        }
    }

    @Test
    void s2CollapsingPlatformFragmentClassesHaveNoRegisteredCodec() {
        for (CodecDeletionCandidate candidate : S2_COLLAPSING_PLATFORM_FRAGMENT_RECREATE_CLASSES) {
            assertFalse(hasRegisteredDynamicCodec(candidate.fqn(), candidate.gameId()),
                    candidate.fqn()
                            + " must restore through collapsing-platform fragment generic recreate, not a dynamic codec");
        }
    }

    @Test
    void s2CollapsingPlatformFragmentClassesRoundTripThroughGenericRecreate() {
        for (CodecDeletionCandidate candidate : S2_COLLAPSING_PLATFORM_FRAGMENT_RECREATE_CLASSES) {
            RoundTripSweepResult result = RewindRoundTripHarness.probeClass(candidate.fqn());
            assertInstanceOf(RoundTripSweepResult.Passed.class, result,
                    candidate.fqn() + " should round-trip through collapsing-platform fragment generic recreate");
        }
    }

    @Test
    void s2CollapsingPlatformFragmentFieldsAreMutableForCompactRestore() {
        for (MutableFieldCoverageCandidate candidate : S2_COLLAPSING_PLATFORM_FRAGMENT_RECREATE_MUTABLE_FIELDS) {
            Class<?> cls = loadClass(candidate.fqn());
            for (String fieldName : candidate.fieldNames()) {
                try {
                    var field = findField(cls, fieldName);
                    assertFalse(Modifier.isFinal(field.getModifiers()),
                            cls.getName() + "#" + fieldName
                                    + " must be mutable so compact restore can replay captured scalars");
                } catch (NoSuchFieldException e) {
                    throw new AssertionError("Missing scalar field " + cls.getName() + "#" + fieldName, e);
                }
            }
        }
    }

    // =====================================================================
    // CPZ graph batch A: main-boss-linked children, graph harness covers restore
    // =====================================================================

    @Test
    void cpzGraphBatchAClassesAllImplementRewindRecreatable() {
        for (CodecDeletionCandidate candidate : CPZ_GRAPH_BATCH_A_DELETED_CODECS) {
            Class<?> cls = loadClass(candidate.fqn());
            assertTrue(RewindRecreatable.class.isAssignableFrom(cls),
                    candidate.fqn() + " must implement RewindRecreatable after CPZ graph batch A");
        }
    }

    @Test
    void cpzGraphBatchAClassesHaveNoRegisteredCodec() {
        for (CodecDeletionCandidate candidate : CPZ_GRAPH_BATCH_A_DELETED_CODECS) {
            assertFalse(hasRegisteredDynamicCodec(candidate.fqn(), candidate.gameId()),
                    candidate.fqn()
                            + " must restore through CPZ graph generic recreate, not a dynamic codec");
        }
    }

    @Test
    void cpzGraphBatchBClassesAllImplementRewindRecreatable() {
        for (CodecDeletionCandidate candidate : CPZ_GRAPH_BATCH_B_DELETED_CODECS) {
            Class<?> cls = loadClass(candidate.fqn());
            assertTrue(RewindRecreatable.class.isAssignableFrom(cls),
                    candidate.fqn() + " must implement RewindRecreatable after CPZ graph batch B");
        }
    }

    @Test
    void cpzGraphBatchBClassesHaveNoRegisteredCodec() {
        for (CodecDeletionCandidate candidate : CPZ_GRAPH_BATCH_B_DELETED_CODECS) {
            assertFalse(hasRegisteredDynamicCodec(candidate.fqn(), candidate.gameId()),
                    candidate.fqn()
                            + " must restore through CPZ graph generic recreate, not a dynamic codec");
        }
    }

    @Test
    void s2BossGraphParentClassesAllImplementRewindRecreatable() {
        for (CodecDeletionCandidate candidate : S2_BOSS_GRAPH_PARENT_DELETED_CODECS) {
            Class<?> cls = loadClass(candidate.fqn());
            assertTrue(RewindRecreatable.class.isAssignableFrom(cls),
                    candidate.fqn() + " must implement RewindRecreatable after S2 boss graph parent batch");
        }
    }

    @Test
    void s2BossGraphParentClassesHaveNoRegisteredCodec() {
        for (CodecDeletionCandidate candidate : S2_BOSS_GRAPH_PARENT_DELETED_CODECS) {
            assertFalse(hasRegisteredDynamicCodec(candidate.fqn(), candidate.gameId()),
                    candidate.fqn()
                            + " must restore through S2 boss graph parent generic recreate, not a dynamic codec");
        }
    }

    // =====================================================================
    // AIZ miniboss graph batch: parent/sibling-linked children, graph harness covers restore
    // =====================================================================

    @Test
    void aizMinibossGraphClassesAllImplementRewindRecreatable() {
        for (CodecDeletionCandidate candidate : AIZ_MINIBOSS_GRAPH_DELETED_CODECS) {
            Class<?> cls = loadClass(candidate.fqn());
            assertTrue(RewindRecreatable.class.isAssignableFrom(cls),
                    candidate.fqn() + " must implement RewindRecreatable after AIZ miniboss graph batch");
        }
    }

    @Test
    void aizMinibossGraphClassesHaveNoRegisteredCodec() {
        for (CodecDeletionCandidate candidate : AIZ_MINIBOSS_GRAPH_DELETED_CODECS) {
            assertFalse(hasRegisteredDynamicCodec(candidate.fqn(), candidate.gameId()),
                    candidate.fqn()
                            + " must restore through AIZ miniboss graph generic recreate, not a dynamic codec");
        }
    }

    @Test
    void aizMinibossParentClassesAllImplementRewindRecreatable() {
        for (CodecDeletionCandidate candidate : AIZ_MINIBOSS_PARENT_DELETED_CODECS) {
            Class<?> cls = loadClass(candidate.fqn());
            assertTrue(RewindRecreatable.class.isAssignableFrom(cls),
                    candidate.fqn() + " must implement RewindRecreatable after AIZ miniboss parent batch");
        }
    }

    @Test
    void aizMinibossParentClassesHaveNoRegisteredCodec() {
        for (CodecDeletionCandidate candidate : AIZ_MINIBOSS_PARENT_DELETED_CODECS) {
            assertFalse(hasRegisteredDynamicCodec(candidate.fqn(), candidate.gameId()),
                    candidate.fqn()
                            + " must restore through AIZ miniboss parent generic recreate, not a dynamic codec");
        }
    }

    // =====================================================================
    // AIZ end-boss graph batch: boss/parent/sibling-linked children, graph harness covers restore
    // =====================================================================

    @Test
    void aizEndBossGraphClassesAllImplementRewindRecreatable() {
        for (CodecDeletionCandidate candidate : AIZ_END_BOSS_GRAPH_DELETED_CODECS) {
            Class<?> cls = loadClass(candidate.fqn());
            assertTrue(RewindRecreatable.class.isAssignableFrom(cls),
                    candidate.fqn() + " must implement RewindRecreatable after AIZ end-boss graph batch");
        }
    }

    @Test
    void aizEndBossGraphClassesHaveNoRegisteredCodec() {
        for (CodecDeletionCandidate candidate : AIZ_END_BOSS_GRAPH_DELETED_CODECS) {
            assertFalse(hasRegisteredDynamicCodec(candidate.fqn(), candidate.gameId()),
                    candidate.fqn()
                            + " must restore through AIZ end-boss graph generic recreate, not a dynamic codec");
        }
    }

    // =====================================================================
    // AIZ ship-bomb graph batch: battleship-linked dynamic bomb
    // =====================================================================

    @Test
    void aizShipBombGraphClassesAllImplementRewindRecreatable() {
        for (CodecDeletionCandidate candidate : AIZ_SHIP_BOMB_GRAPH_DELETED_CODECS) {
            Class<?> cls = loadClass(candidate.fqn());
            assertTrue(RewindRecreatable.class.isAssignableFrom(cls),
                    candidate.fqn() + " must implement RewindRecreatable after AIZ ship-bomb graph batch");
        }
    }

    @Test
    void aizShipBombGraphClassesHaveNoRegisteredCodec() {
        for (CodecDeletionCandidate candidate : AIZ_SHIP_BOMB_GRAPH_DELETED_CODECS) {
            assertFalse(hasRegisteredDynamicCodec(candidate.fqn(), candidate.gameId()),
                    candidate.fqn()
                            + " must restore through AIZ ship-bomb graph generic recreate, not a dynamic codec");
        }
    }

    // =====================================================================
    // AIZ spiked-log graph batch: parent-linked spike collision child
    // =====================================================================

    @Test
    void aizSpikedLogGraphClassesAllImplementRewindRecreatable() {
        for (CodecDeletionCandidate candidate : AIZ_SPIKED_LOG_GRAPH_DELETED_CODECS) {
            Class<?> cls = loadClass(candidate.fqn());
            assertTrue(RewindRecreatable.class.isAssignableFrom(cls),
                    candidate.fqn() + " must implement RewindRecreatable after AIZ spiked-log graph batch");
        }
    }

    @Test
    void aizSpikedLogGraphClassesHaveNoRegisteredCodec() {
        for (CodecDeletionCandidate candidate : AIZ_SPIKED_LOG_GRAPH_DELETED_CODECS) {
            assertFalse(hasRegisteredDynamicCodec(candidate.fqn(), candidate.gameId()),
                    candidate.fqn()
                            + " must restore through AIZ spiked-log graph generic recreate, not a dynamic codec");
        }
    }

    // =====================================================================
    // AIZ falling-log graph batch: bidirectional log/splash pair
    // =====================================================================

    @Test
    void aizFallingLogGraphClassesAllImplementRewindRecreatable() {
        for (CodecDeletionCandidate candidate : AIZ_FALLING_LOG_GRAPH_DELETED_CODECS) {
            Class<?> cls = loadClass(candidate.fqn());
            assertTrue(RewindRecreatable.class.isAssignableFrom(cls),
                    candidate.fqn() + " must implement RewindRecreatable after AIZ falling-log graph batch");
        }
    }

    @Test
    void aizFallingLogGraphClassesHaveNoRegisteredCodec() {
        for (CodecDeletionCandidate candidate : AIZ_FALLING_LOG_GRAPH_DELETED_CODECS) {
            assertFalse(hasRegisteredDynamicCodec(candidate.fqn(), candidate.gameId()),
                    candidate.fqn()
                            + " must restore through AIZ falling-log graph generic recreate, not a dynamic codec");
        }
    }

    // =====================================================================
    // AIZ disappearing-floor graph batch: parent-linked border child
    // =====================================================================

    @Test
    void aizDisappearingFloorGraphClassesAllImplementRewindRecreatable() {
        for (CodecDeletionCandidate candidate : AIZ_DISAPPEARING_FLOOR_GRAPH_DELETED_CODECS) {
            Class<?> cls = loadClass(candidate.fqn());
            assertTrue(RewindRecreatable.class.isAssignableFrom(cls),
                    candidate.fqn()
                            + " must implement RewindRecreatable after AIZ disappearing-floor graph batch");
        }
    }

    @Test
    void aizDisappearingFloorGraphClassesHaveNoRegisteredCodec() {
        for (CodecDeletionCandidate candidate : AIZ_DISAPPEARING_FLOOR_GRAPH_DELETED_CODECS) {
            assertFalse(hasRegisteredDynamicCodec(candidate.fqn(), candidate.gameId()),
                    candidate.fqn()
                            + " must restore through AIZ disappearing-floor graph generic recreate, "
                            + "not a dynamic codec");
        }
    }

    // =====================================================================
    // AIZ collapsing-log bridge graph batch: parent and falling segment
    // =====================================================================

    @Test
    void aizCollapsingLogBridgeGraphClassesAllImplementRewindRecreatable() {
        for (CodecDeletionCandidate candidate : AIZ_COLLAPSING_LOG_BRIDGE_GRAPH_DELETED_CODECS) {
            Class<?> cls = loadClass(candidate.fqn());
            assertTrue(RewindRecreatable.class.isAssignableFrom(cls),
                    candidate.fqn()
                            + " must implement RewindRecreatable after AIZ collapsing-log bridge graph batch");
        }
    }

    @Test
    void aizCollapsingLogBridgeGraphClassesHaveNoRegisteredCodec() {
        for (CodecDeletionCandidate candidate : AIZ_COLLAPSING_LOG_BRIDGE_GRAPH_DELETED_CODECS) {
            assertFalse(hasRegisteredDynamicCodec(candidate.fqn(), candidate.gameId()),
                    candidate.fqn()
                            + " must restore through AIZ collapsing-log bridge graph generic recreate, "
                            + "not a dynamic codec");
        }
    }

    // =====================================================================
    // AIZ flipping bridge scalar batch: parent object
    // =====================================================================

    @Test
    void aizFlippingBridgeClassImplementsRewindRecreatable() {
        for (CodecDeletionCandidate candidate : AIZ_FLIPPING_BRIDGE_RECREATE_CLASSES) {
            Class<?> cls = loadClass(candidate.fqn());
            assertTrue(RewindRecreatable.class.isAssignableFrom(cls),
                    candidate.fqn()
                            + " must implement RewindRecreatable after AIZ flipping-bridge batch");
        }
    }

    @Test
    void aizFlippingBridgeClassHasNoRegisteredCodec() {
        for (CodecDeletionCandidate candidate : AIZ_FLIPPING_BRIDGE_RECREATE_CLASSES) {
            assertFalse(hasRegisteredDynamicCodec(candidate.fqn(), candidate.gameId()),
                    candidate.fqn()
                            + " must restore through AIZ flipping-bridge generic recreate, "
                            + "not a dynamic codec");
        }
    }

    // =====================================================================
    // AIZ1 static scenery scalar batch: tree and zipline peg
    // =====================================================================

    @Test
    void aiz1StaticSceneryClassesImplementRewindRecreatable() {
        for (CodecDeletionCandidate candidate : AIZ1_STATIC_SCENERY_RECREATE_CLASSES) {
            Class<?> cls = loadClass(candidate.fqn());
            assertTrue(RewindRecreatable.class.isAssignableFrom(cls),
                    candidate.fqn()
                            + " must implement RewindRecreatable after AIZ1 static scenery batch");
        }
    }

    @Test
    void aiz1StaticSceneryClassesHaveNoRegisteredCodec() {
        for (CodecDeletionCandidate candidate : AIZ1_STATIC_SCENERY_RECREATE_CLASSES) {
            assertFalse(hasRegisteredDynamicCodec(candidate.fqn(), candidate.gameId()),
                    candidate.fqn()
                            + " must restore through AIZ1 static scenery generic recreate, "
                            + "not a dynamic codec");
        }
    }

    // =====================================================================
    // S3K decorative scalar batch: AIZ foreground plant and animated still sprite
    // =====================================================================

    @Test
    void s3kDecorativeClassesImplementRewindRecreatable() {
        for (CodecDeletionCandidate candidate : S3K_DECORATIVE_RECREATE_CLASSES) {
            Class<?> cls = loadClass(candidate.fqn());
            assertTrue(RewindRecreatable.class.isAssignableFrom(cls),
                    candidate.fqn()
                            + " must implement RewindRecreatable after S3K decorative batch");
        }
    }

    @Test
    void s3kDecorativeClassesHaveNoRegisteredCodec() {
        for (CodecDeletionCandidate candidate : S3K_DECORATIVE_RECREATE_CLASSES) {
            assertFalse(hasRegisteredDynamicCodec(candidate.fqn(), candidate.gameId()),
                    candidate.fqn()
                            + " must restore through S3K decorative generic recreate, "
                            + "not a dynamic codec");
        }
    }

    // =====================================================================
    // S3K static/hazard scalar batch: still sprite and S3K spike object
    // =====================================================================

    @Test
    void s3kStaticHazardClassesImplementRewindRecreatable() {
        for (CodecDeletionCandidate candidate : S3K_STATIC_HAZARD_RECREATE_CLASSES) {
            Class<?> cls = loadClass(candidate.fqn());
            assertTrue(RewindRecreatable.class.isAssignableFrom(cls),
                    candidate.fqn()
                            + " must implement RewindRecreatable after S3K static hazard batch");
        }
    }

    @Test
    void s3kStaticHazardClassesHaveNoRegisteredCodec() {
        for (CodecDeletionCandidate candidate : S3K_STATIC_HAZARD_RECREATE_CLASSES) {
            assertFalse(hasRegisteredDynamicCodec(candidate.fqn(), candidate.gameId()),
                    candidate.fqn()
                            + " must restore through S3K static hazard generic recreate, "
                            + "not a dynamic codec");
        }
    }

    @Test
    void s3kStaticHazardClassesRoundTripPassedWithoutCodec() {
        for (CodecDeletionCandidate candidate : S3K_STATIC_HAZARD_RECREATE_CLASSES) {
            RoundTripSweepResult result = RewindRoundTripHarness.probeClass(candidate.fqn());
            assertInstanceOf(RoundTripSweepResult.Passed.class, result,
                    candidate.fqn()
                            + " must round-trip as Passed via RewindRecreatable path (no codec); got: "
                            + result);
        }
    }

    // =====================================================================
    // S3K button/path-swap scalar batch: trigger buttons and path-swap markers
    // =====================================================================

    @Test
    void s3kButtonPathSwapClassesImplementRewindRecreatable() {
        for (CodecDeletionCandidate candidate : S3K_BUTTON_PATH_SWAP_RECREATE_CLASSES) {
            Class<?> cls = loadClass(candidate.fqn());
            assertTrue(RewindRecreatable.class.isAssignableFrom(cls),
                    candidate.fqn()
                            + " must implement RewindRecreatable after S3K button/path-swap batch");
        }
    }

    @Test
    void s3kButtonPathSwapClassesHaveNoRegisteredCodec() {
        for (CodecDeletionCandidate candidate : S3K_BUTTON_PATH_SWAP_RECREATE_CLASSES) {
            assertFalse(hasRegisteredDynamicCodec(candidate.fqn(), candidate.gameId()),
                    candidate.fqn()
                            + " must restore through S3K button/path-swap generic recreate, "
                            + "not a dynamic codec");
        }
    }

    @Test
    void s3kButtonPathSwapClassesRoundTripPassedWithoutCodec() {
        for (CodecDeletionCandidate candidate : S3K_BUTTON_PATH_SWAP_RECREATE_CLASSES) {
            RoundTripSweepResult result = RewindRoundTripHarness.probeClass(candidate.fqn());
            assertInstanceOf(RoundTripSweepResult.Passed.class, result,
                    candidate.fqn()
                            + " must round-trip as Passed via RewindRecreatable path (no codec); got: "
                            + result);
        }
    }

    // =====================================================================
    // S3K utility scalar batch: hidden monitor, sinking mud, and SS-entry ring
    // =====================================================================

    @Test
    void s3kUtilityClassesImplementRewindRecreatable() {
        for (CodecDeletionCandidate candidate : S3K_UTILITY_RECREATE_CLASSES) {
            Class<?> cls = loadClass(candidate.fqn());
            assertTrue(RewindRecreatable.class.isAssignableFrom(cls),
                    candidate.fqn()
                            + " must implement RewindRecreatable after S3K utility batch");
        }
    }

    @Test
    void s3kUtilityClassesHaveNoRegisteredCodec() {
        for (CodecDeletionCandidate candidate : S3K_UTILITY_RECREATE_CLASSES) {
            assertFalse(hasRegisteredDynamicCodec(candidate.fqn(), candidate.gameId()),
                    candidate.fqn()
                            + " must restore through S3K utility generic recreate, "
                            + "not a dynamic codec");
        }
    }

    @Test
    void s3kUtilityClassesRoundTripPassedWithoutCodec() {
        for (CodecDeletionCandidate candidate : S3K_UTILITY_RECREATE_CLASSES) {
            RoundTripSweepResult result = RewindRoundTripHarness.probeClass(candidate.fqn());
            assertInstanceOf(RoundTripSweepResult.Passed.class, result,
                    candidate.fqn()
                            + " must round-trip as Passed via RewindRecreatable path (no codec); got: "
                            + result);
        }
    }

    // =====================================================================
    // S3K CNZ local mechanics batch: balloon, rising platform, light bulb, barber pole
    // =====================================================================

    @Test
    void s3kCnzLocalMechanicsClassesImplementRewindRecreatable() {
        for (CodecDeletionCandidate candidate : S3K_CNZ_LOCAL_MECHANICS_RECREATE_CLASSES) {
            Class<?> cls = loadClass(candidate.fqn());
            assertTrue(RewindRecreatable.class.isAssignableFrom(cls),
                    candidate.fqn()
                            + " must implement RewindRecreatable after S3K CNZ local mechanics batch");
        }
    }

    @Test
    void s3kCnzLocalMechanicsClassesHaveNoRegisteredCodec() {
        for (CodecDeletionCandidate candidate : S3K_CNZ_LOCAL_MECHANICS_RECREATE_CLASSES) {
            assertFalse(hasRegisteredDynamicCodec(candidate.fqn(), candidate.gameId()),
                    candidate.fqn()
                            + " must restore through S3K CNZ local mechanics generic recreate, "
                            + "not a dynamic codec");
        }
    }

    @Test
    void s3kCnzLocalMechanicsClassesRoundTripPassedWithoutCodec() {
        for (CodecDeletionCandidate candidate : S3K_CNZ_LOCAL_MECHANICS_RECREATE_CLASSES) {
            RoundTripSweepResult result = RewindRoundTripHarness.probeClass(candidate.fqn());
            assertInstanceOf(RoundTripSweepResult.Passed.class, result,
                    candidate.fqn()
                            + " must round-trip as Passed via RewindRecreatable path (no codec); got: "
                            + result);
        }
    }

    // =====================================================================
    // S3K CNZ mechanism batch: controller and local mechanism restores
    // =====================================================================

    @Test
    void s3kCnzMechanismClassesImplementRewindRecreatable() {
        for (CodecDeletionCandidate candidate : S3K_CNZ_MECHANISM_RECREATE_CLASSES) {
            Class<?> cls = loadClass(candidate.fqn());
            assertTrue(RewindRecreatable.class.isAssignableFrom(cls),
                    candidate.fqn() + " must implement RewindRecreatable after S3K CNZ mechanism batch");
        }
    }

    @Test
    void s3kCnzMechanismClassesHaveNoRegisteredCodec() {
        for (CodecDeletionCandidate candidate : S3K_CNZ_MECHANISM_RECREATE_CLASSES) {
            assertFalse(hasRegisteredDynamicCodec(candidate.fqn(), candidate.gameId()),
                    candidate.fqn()
                            + " must restore through S3K CNZ mechanism generic recreate, not a dynamic codec");
        }
    }

    @Test
    void s3kCnzMechanismClassesRoundTripPassedWithoutCodec() {
        for (CodecDeletionCandidate candidate : S3K_CNZ_MECHANISM_RECREATE_CLASSES) {
            RoundTripSweepResult result = RewindRoundTripHarness.probeClass(candidate.fqn());
            assertInstanceOf(RoundTripSweepResult.Passed.class, result,
                    candidate.fqn()
                            + " must round-trip as Passed via RewindRecreatable path (no codec); got: "
                            + result);
        }
    }

    // =====================================================================
    // S3K ICZ ice-object batch: top-level ice/wall object-manager restores
    // =====================================================================

    @Test
    void s3kIczIceObjectClassesImplementRewindRecreatable() {
        for (CodecDeletionCandidate candidate : S3K_ICZ_ICE_OBJECT_RECREATE_CLASSES) {
            Class<?> cls = loadClass(candidate.fqn());
            assertTrue(RewindRecreatable.class.isAssignableFrom(cls),
                    candidate.fqn() + " must implement RewindRecreatable after S3K ICZ ice-object batch");
        }
    }

    @Test
    void s3kIczIceObjectClassesHaveNoRegisteredCodec() {
        for (CodecDeletionCandidate candidate : S3K_ICZ_ICE_OBJECT_RECREATE_CLASSES) {
            assertFalse(hasRegisteredDynamicCodec(candidate.fqn(), candidate.gameId()),
                    candidate.fqn()
                            + " must restore through S3K ICZ ice-object generic recreate, not a dynamic codec");
        }
    }

    @Test
    void s3kIczIceObjectClassesRoundTripPassedWithoutCodec() {
        for (CodecDeletionCandidate candidate : S3K_ICZ_ICE_OBJECT_RECREATE_CLASSES) {
            RoundTripSweepResult result = RewindRoundTripHarness.probeClass(candidate.fqn());
            assertInstanceOf(RoundTripSweepResult.Passed.class, result,
                    candidate.fqn()
                            + " must round-trip as Passed via RewindRecreatable path (no codec); got: "
                            + result);
        }
    }

    // =====================================================================
    // S3K HCZ mechanism batch: top-level block/hazard/effect object-manager restores
    // =====================================================================

    @Test
    void s3kHczMechanismClassesImplementRewindRecreatable() {
        for (CodecDeletionCandidate candidate : S3K_HCZ_MECHANISM_RECREATE_CLASSES) {
            Class<?> cls = loadClass(candidate.fqn());
            assertTrue(RewindRecreatable.class.isAssignableFrom(cls),
                    candidate.fqn() + " must implement RewindRecreatable after S3K HCZ mechanism batch");
        }
    }

    @Test
    void s3kHczMechanismClassesHaveNoRegisteredCodec() {
        for (CodecDeletionCandidate candidate : S3K_HCZ_MECHANISM_RECREATE_CLASSES) {
            assertFalse(hasRegisteredDynamicCodec(candidate.fqn(), candidate.gameId()),
                    candidate.fqn()
                            + " must restore through S3K HCZ mechanism generic recreate, not a dynamic codec");
        }
    }

    @Test
    void s3kHczMechanismClassesRoundTripPassedWithoutCodec() {
        for (CodecDeletionCandidate candidate : S3K_HCZ_MECHANISM_RECREATE_CLASSES) {
            RoundTripSweepResult result = RewindRoundTripHarness.probeClass(candidate.fqn());
            assertInstanceOf(RoundTripSweepResult.Passed.class, result,
                    candidate.fqn()
                            + " must round-trip as Passed via RewindRecreatable path (no codec); got: "
                            + result);
        }
    }

    // =====================================================================
    // S3K ICZ platform/hazard batch: top-level platform/hazard object-manager restores
    // =====================================================================

    @Test
    void s3kIczPlatformHazardClassesImplementRewindRecreatable() {
        for (CodecDeletionCandidate candidate : S3K_ICZ_PLATFORM_HAZARD_RECREATE_CLASSES) {
            Class<?> cls = loadClass(candidate.fqn());
            assertTrue(RewindRecreatable.class.isAssignableFrom(cls),
                    candidate.fqn() + " must implement RewindRecreatable after S3K ICZ platform/hazard batch");
        }
    }

    @Test
    void s3kIczPlatformHazardClassesHaveNoRegisteredCodec() {
        for (CodecDeletionCandidate candidate : S3K_ICZ_PLATFORM_HAZARD_RECREATE_CLASSES) {
            assertFalse(hasRegisteredDynamicCodec(candidate.fqn(), candidate.gameId()),
                    candidate.fqn()
                            + " must restore through S3K ICZ platform/hazard generic recreate, not a dynamic codec");
        }
    }

    @Test
    void s3kIczPlatformHazardClassesRoundTripPassedWithoutCodec() {
        for (CodecDeletionCandidate candidate : S3K_ICZ_PLATFORM_HAZARD_RECREATE_CLASSES) {
            RoundTripSweepResult result = RewindRoundTripHarness.probeClass(candidate.fqn());
            assertInstanceOf(RoundTripSweepResult.Passed.class, result,
                    candidate.fqn()
                            + " must round-trip as Passed via RewindRecreatable path (no codec); got: "
                            + result);
        }
    }

    // =====================================================================
    // S3K utility/motion batch: top-level invisible/utility object-manager restores
    // =====================================================================

    @Test
    void s3kUtilityMotionClassesImplementRewindRecreatable() {
        for (CodecDeletionCandidate candidate : S3K_UTILITY_MOTION_RECREATE_CLASSES) {
            Class<?> cls = loadClass(candidate.fqn());
            assertTrue(RewindRecreatable.class.isAssignableFrom(cls),
                    candidate.fqn() + " must implement RewindRecreatable after S3K utility/motion batch");
        }
    }

    @Test
    void s3kUtilityMotionClassesHaveNoRegisteredCodec() {
        for (CodecDeletionCandidate candidate : S3K_UTILITY_MOTION_RECREATE_CLASSES) {
            assertFalse(hasRegisteredDynamicCodec(candidate.fqn(), candidate.gameId()),
                    candidate.fqn()
                            + " must restore through S3K utility/motion generic recreate, not a dynamic codec");
        }
    }

    @Test
    void s3kUtilityMotionClassesRoundTripPassedWithoutCodec() {
        for (CodecDeletionCandidate candidate : S3K_UTILITY_MOTION_RECREATE_CLASSES) {
            RoundTripSweepResult result = RewindRoundTripHarness.probeClass(candidate.fqn());
            assertInstanceOf(RoundTripSweepResult.Passed.class, result,
                    candidate.fqn()
                            + " must round-trip as Passed via RewindRecreatable path (no codec); got: "
                            + result);
        }
    }

    // =====================================================================
    // S3K pachinko/standalone batch: scalar object-manager restores
    // =====================================================================

    @Test
    void s3kPachinkoStandaloneClassesImplementRewindRecreatable() {
        for (CodecDeletionCandidate candidate : S3K_PACHINKO_STANDALONE_RECREATE_CLASSES) {
            Class<?> cls = loadClass(candidate.fqn());
            assertTrue(RewindRecreatable.class.isAssignableFrom(cls),
                    candidate.fqn() + " must implement RewindRecreatable after S3K pachinko standalone batch");
        }
    }

    @Test
    void s3kPachinkoStandaloneClassesHaveNoRegisteredCodec() {
        for (CodecDeletionCandidate candidate : S3K_PACHINKO_STANDALONE_RECREATE_CLASSES) {
            assertFalse(hasRegisteredDynamicCodec(candidate.fqn(), candidate.gameId()),
                    candidate.fqn()
                            + " must restore through S3K pachinko standalone generic recreate, not a dynamic codec");
        }
    }

    @Test
    void s3kPachinkoStandaloneClassesRoundTripPassedWithoutCodec() {
        for (CodecDeletionCandidate candidate : S3K_PACHINKO_STANDALONE_RECREATE_CLASSES) {
            RoundTripSweepResult result = RewindRoundTripHarness.probeClass(candidate.fqn());
            assertInstanceOf(RoundTripSweepResult.Passed.class, result,
                    candidate.fqn()
                            + " must round-trip as Passed via RewindRecreatable path (no codec); got: "
                            + result);
        }
    }

    // =====================================================================
    // S3K controller batch: invisible/route controller object-manager restores
    // =====================================================================

    @Test
    void s3kControllerClassesImplementRewindRecreatable() {
        for (CodecDeletionCandidate candidate : S3K_CONTROLLER_RECREATE_CLASSES) {
            Class<?> cls = loadClass(candidate.fqn());
            assertTrue(RewindRecreatable.class.isAssignableFrom(cls),
                    candidate.fqn() + " must implement RewindRecreatable after S3K controller batch");
        }
    }

    @Test
    void s3kControllerClassesHaveNoRegisteredCodec() {
        for (CodecDeletionCandidate candidate : S3K_CONTROLLER_RECREATE_CLASSES) {
            assertFalse(hasRegisteredDynamicCodec(candidate.fqn(), candidate.gameId()),
                    candidate.fqn()
                            + " must restore through S3K controller generic recreate, not a dynamic codec");
        }
    }

    @Test
    void s3kControllerClassesRoundTripPassedWithoutCodec() {
        for (CodecDeletionCandidate candidate : S3K_CONTROLLER_RECREATE_CLASSES) {
            RoundTripSweepResult result = RewindRoundTripHarness.probeClass(candidate.fqn());
            assertInstanceOf(RoundTripSweepResult.Passed.class, result,
                    candidate.fqn()
                            + " must round-trip as Passed via RewindRecreatable path (no codec); got: "
                            + result);
        }
    }

    // =====================================================================
    // Standalone controller batch: no live object refs, object-manager restores covered
    // =====================================================================

    @Test
    void standaloneControllerClassesImplementRewindRecreatable() {
        for (CodecDeletionCandidate candidate : STANDALONE_CONTROLLER_RECREATE_CLASSES) {
            Class<?> cls = loadClass(candidate.fqn());
            assertTrue(RewindRecreatable.class.isAssignableFrom(cls),
                    candidate.fqn() + " must implement RewindRecreatable after standalone controller batch");
        }
    }

    @Test
    void standaloneControllerClassesHaveNoRegisteredCodec() {
        for (CodecDeletionCandidate candidate : STANDALONE_CONTROLLER_RECREATE_CLASSES) {
            assertFalse(hasRegisteredDynamicCodec(candidate.fqn(), candidate.gameId()),
                    candidate.fqn()
                            + " must restore through standalone controller generic recreate, not a dynamic codec");
        }
    }

    @Test
    void standaloneControllerClassesRoundTripPassedWithoutCodec() {
        for (CodecDeletionCandidate candidate : STANDALONE_CONTROLLER_RECREATE_CLASSES) {
            RoundTripSweepResult result = RewindRoundTripHarness.probeClass(candidate.fqn());
            assertInstanceOf(RoundTripSweepResult.Passed.class, result,
                    candidate.fqn()
                            + " must round-trip as Passed via RewindRecreatable path (no codec); got: "
                            + result);
        }
    }

    // =====================================================================
    // HCZ end-boss graph batch: parent/sibling-linked children, graph harness covers restore
    // =====================================================================

    @Test
    void hczEndBossGraphClassesAllImplementRewindRecreatable() {
        for (CodecDeletionCandidate candidate : HCZ_END_BOSS_GRAPH_DELETED_CODECS) {
            Class<?> cls = loadClass(candidate.fqn());
            assertTrue(RewindRecreatable.class.isAssignableFrom(cls),
                    candidate.fqn() + " must implement RewindRecreatable after HCZ end-boss graph batch");
        }
    }

    @Test
    void hczEndBossGraphClassesHaveNoRegisteredCodec() {
        for (CodecDeletionCandidate candidate : HCZ_END_BOSS_GRAPH_DELETED_CODECS) {
            assertFalse(hasRegisteredDynamicCodec(candidate.fqn(), candidate.gameId()),
                    candidate.fqn()
                            + " must restore through HCZ end-boss graph generic recreate, not a dynamic codec");
        }
    }

    // =====================================================================
    // AIZ intro graph batch: biplane parent-linked dynamic children
    // =====================================================================

    @Test
    void aizIntroGraphClassesAllImplementRewindRecreatable() {
        for (CodecDeletionCandidate candidate : AIZ_INTRO_GRAPH_DELETED_CODECS) {
            Class<?> cls = loadClass(candidate.fqn());
            assertTrue(RewindRecreatable.class.isAssignableFrom(cls),
                    candidate.fqn() + " must implement RewindRecreatable after AIZ intro graph batch");
        }
    }

    @Test
    void aizIntroGraphClassesHaveNoRegisteredCodec() {
        for (CodecDeletionCandidate candidate : AIZ_INTRO_GRAPH_DELETED_CODECS) {
            assertFalse(hasRegisteredDynamicCodec(candidate.fqn(), candidate.gameId()),
                    candidate.fqn()
                            + " must restore through AIZ intro graph generic recreate, not a dynamic codec");
        }
    }

    @Test
    void aizIntroParentClassesAllImplementRewindRecreatable() {
        for (CodecDeletionCandidate candidate : AIZ_INTRO_PARENT_DELETED_CODECS) {
            Class<?> cls = loadClass(candidate.fqn());
            assertTrue(RewindRecreatable.class.isAssignableFrom(cls),
                    candidate.fqn() + " must implement RewindRecreatable after AIZ intro parent batch");
        }
    }

    @Test
    void aizIntroParentClassesHaveNoRegisteredCodec() {
        for (CodecDeletionCandidate candidate : AIZ_INTRO_PARENT_DELETED_CODECS) {
            assertFalse(hasRegisteredDynamicCodec(candidate.fqn(), candidate.gameId()),
                    candidate.fqn()
                            + " must restore through AIZ intro parent generic recreate, not a dynamic codec");
        }
    }

    // =====================================================================
    // S2 badnik child graph batch: parent/sibling-linked dynamics, graph harness covers restore
    // =====================================================================

    @Test
    void s2BadnikChildGraphClassesAllImplementRewindRecreatable() {
        for (CodecDeletionCandidate candidate : S2_BADNIK_CHILD_GRAPH_DELETED_CODECS) {
            Class<?> cls = loadClass(candidate.fqn());
            assertTrue(RewindRecreatable.class.isAssignableFrom(cls),
                    candidate.fqn() + " must implement RewindRecreatable after S2 badnik child graph batch");
        }
    }

    @Test
    void s2BadnikChildGraphClassesHaveNoRegisteredCodec() {
        for (CodecDeletionCandidate candidate : S2_BADNIK_CHILD_GRAPH_DELETED_CODECS) {
            assertFalse(hasRegisteredDynamicCodec(candidate.fqn(), candidate.gameId()),
                    candidate.fqn()
                            + " must restore through S2 badnik child graph generic recreate, not a dynamic codec");
        }
    }

    @Test
    void s2BadnikParentGraphClassesImplementRewindRecreatable() {
        for (CodecDeletionCandidate candidate : S2_BADNIK_PARENT_GRAPH_DELETED_CODECS) {
            Class<?> cls = loadClass(candidate.fqn());
            assertTrue(RewindRecreatable.class.isAssignableFrom(cls),
                    candidate.fqn() + " must implement RewindRecreatable after S2 badnik parent graph batch");
        }
    }

    @Test
    void s2BadnikParentGraphClassesHaveNoRegisteredCodec() {
        for (CodecDeletionCandidate candidate : S2_BADNIK_PARENT_GRAPH_DELETED_CODECS) {
            assertFalse(hasRegisteredDynamicCodec(candidate.fqn(), candidate.gameId()),
                    candidate.fqn()
                            + " must restore through S2 badnik parent graph generic recreate, not a dynamic codec");
        }
    }

    // =====================================================================
    // Checkpoint/starpost graph batch: parent-required orbit children
    // =====================================================================

    @Test
    void checkpointStarpostGraphClassesAllImplementRewindRecreatable() {
        for (CodecDeletionCandidate candidate : CHECKPOINT_STARPOST_GRAPH_DELETED_CODECS) {
            Class<?> cls = loadClass(candidate.fqn());
            assertTrue(RewindRecreatable.class.isAssignableFrom(cls),
                    candidate.fqn() + " must implement RewindRecreatable after checkpoint/starpost graph batch");
        }
    }

    @Test
    void checkpointStarpostGraphClassesHaveNoRegisteredCodec() {
        for (CodecDeletionCandidate candidate : CHECKPOINT_STARPOST_GRAPH_DELETED_CODECS) {
            assertFalse(hasRegisteredDynamicCodec(candidate.fqn(), candidate.gameId()),
                    candidate.fqn()
                            + " must restore through checkpoint/starpost graph generic recreate, not a dynamic codec");
        }
    }

    // =====================================================================
    // S1 MZ glass reflection graph batch: layout-parent-linked dynamic shine
    // =====================================================================

    @Test
    void s1MzGlassReflectionGraphClassesAllImplementRewindRecreatable() {
        for (CodecDeletionCandidate candidate : S1_MZ_GLASS_REFLECTION_GRAPH_DELETED_CODECS) {
            Class<?> cls = loadClass(candidate.fqn());
            assertTrue(RewindRecreatable.class.isAssignableFrom(cls),
                    candidate.fqn() + " must implement RewindRecreatable after S1 MZ glass graph batch");
        }
    }

    @Test
    void s1MzGlassReflectionGraphClassesHaveNoRegisteredS1Codec() {
        for (CodecDeletionCandidate candidate : S1_MZ_GLASS_REFLECTION_GRAPH_DELETED_CODECS) {
            assertFalse(hasRegisteredDynamicCodec(candidate.fqn(), candidate.gameId()),
                    candidate.fqn()
                            + " must restore through S1 MZ glass graph generic recreate, not a dynamic codec");
        }
    }

    // =====================================================================
    // S1 ring flash graph batch: optional live-parent-linked special-stage flash
    // =====================================================================

    @Test
    void s1RingFlashGraphClassesAllImplementRewindRecreatable() {
        for (CodecDeletionCandidate candidate : S1_RING_FLASH_GRAPH_DELETED_CODECS) {
            Class<?> cls = loadClass(candidate.fqn());
            assertTrue(RewindRecreatable.class.isAssignableFrom(cls),
                    candidate.fqn() + " must implement RewindRecreatable after S1 ring flash graph batch");
        }
    }

    @Test
    void s1RingFlashGraphClassesHaveNoRegisteredS1Codec() {
        for (CodecDeletionCandidate candidate : S1_RING_FLASH_GRAPH_DELETED_CODECS) {
            assertFalse(hasRegisteredDynamicCodec(candidate.fqn(), candidate.gameId()),
                    candidate.fqn()
                            + " must restore through S1 ring flash graph generic recreate, not a dynamic codec");
        }
    }

    // =====================================================================
    // S1 boss graph parent batch: boss roots whose graph harnesses cover child relinks
    // =====================================================================

    @Test
    void s1BossGraphParentClassesAllImplementRewindRecreatable() {
        for (CodecDeletionCandidate candidate : S1_BOSS_GRAPH_PARENT_DELETED_CODECS) {
            Class<?> cls = loadClass(candidate.fqn());
            assertTrue(RewindRecreatable.class.isAssignableFrom(cls),
                    candidate.fqn() + " must implement RewindRecreatable after S1 boss graph parent batch");
        }
    }

    @Test
    void s1BossGraphParentClassesHaveNoRegisteredS1Codec() {
        for (CodecDeletionCandidate candidate : S1_BOSS_GRAPH_PARENT_DELETED_CODECS) {
            assertFalse(hasRegisteredDynamicCodec(candidate.fqn(), candidate.gameId()),
                    candidate.fqn()
                            + " must restore through S1 boss graph parent generic recreate, not a dynamic codec");
        }
    }

    @Test
    void s1SyzBossSpikeGraphBatch246ClassesAllImplementRewindRecreatable() {
        for (CodecDeletionCandidate candidate : S1_SYZ_BOSS_SPIKE_GRAPH_BATCH246_RECREATE_CLASSES) {
            Class<?> cls = loadClass(candidate.fqn());
            assertTrue(RewindRecreatable.class.isAssignableFrom(cls),
                    candidate.fqn() + " must implement RewindRecreatable after S1 SYZ spike batch 246");
        }
    }

    @Test
    void s1SyzBossSpikeGraphBatch246ClassesHaveNoRegisteredS1Codec() {
        for (CodecDeletionCandidate candidate : S1_SYZ_BOSS_SPIKE_GRAPH_BATCH246_RECREATE_CLASSES) {
            assertFalse(hasRegisteredDynamicCodec(candidate.fqn(), candidate.gameId()),
                    candidate.fqn()
                            + " must restore through S1 SYZ spike batch 246 generic recreate, not a dynamic codec");
        }
    }

    @Test
    void s3kMinibossRootGraphBatch247ClassesAllImplementRewindRecreatable() {
        for (CodecDeletionCandidate candidate : S3K_MINIBOSS_ROOT_GRAPH_BATCH247_RECREATE_CLASSES) {
            Class<?> cls = loadClass(candidate.fqn());
            assertTrue(RewindRecreatable.class.isAssignableFrom(cls),
                    candidate.fqn() + " must implement RewindRecreatable after S3K miniboss root batch 247");
        }
    }

    @Test
    void s3kMinibossRootGraphBatch247ClassesHaveNoRegisteredCodec() {
        for (CodecDeletionCandidate candidate : S3K_MINIBOSS_ROOT_GRAPH_BATCH247_RECREATE_CLASSES) {
            assertFalse(hasRegisteredDynamicCodec(candidate.fqn(), candidate.gameId()),
                    candidate.fqn()
                            + " must restore through S3K miniboss root batch 247 generic recreate, not a dynamic codec");
        }
    }

    // =====================================================================
    // S1 FZ boss graph batch: boss/launcher-linked cylinders and plasma balls
    // =====================================================================

    @Test
    void s1FzBossGraphClassesAllImplementRewindRecreatable() {
        for (CodecDeletionCandidate candidate : S1_FZ_BOSS_GRAPH_DELETED_CODECS) {
            Class<?> cls = loadClass(candidate.fqn());
            assertTrue(RewindRecreatable.class.isAssignableFrom(cls),
                    candidate.fqn() + " must implement RewindRecreatable after S1 FZ boss graph batch");
        }
    }

    @Test
    void s1FzBossGraphClassesHaveNoRegisteredS1Codec() {
        for (CodecDeletionCandidate candidate : S1_FZ_BOSS_GRAPH_DELETED_CODECS) {
            assertFalse(hasRegisteredDynamicCodec(candidate.fqn(), candidate.gameId()),
                    candidate.fqn()
                            + " must restore through S1 FZ boss graph generic recreate, not a dynamic codec");
        }
    }

    // =====================================================================
    // S1 GHZ boss graph batch: boss-linked wrecking ball
    // =====================================================================

    @Test
    void s1GhzBossGraphClassesAllImplementRewindRecreatable() {
        for (CodecDeletionCandidate candidate : S1_GHZ_BOSS_GRAPH_DELETED_CODECS) {
            Class<?> cls = loadClass(candidate.fqn());
            assertTrue(RewindRecreatable.class.isAssignableFrom(cls),
                    candidate.fqn() + " must implement RewindRecreatable after S1 GHZ boss graph batch");
        }
    }

    @Test
    void s1GhzBossGraphClassesHaveNoRegisteredS1Codec() {
        for (CodecDeletionCandidate candidate : S1_GHZ_BOSS_GRAPH_DELETED_CODECS) {
            assertFalse(hasRegisteredDynamicCodec(candidate.fqn(), candidate.gameId()),
                    candidate.fqn()
                            + " must restore through S1 GHZ boss graph generic recreate, not a dynamic codec");
        }
    }

    // =====================================================================
    // S1 SLZ boss graph batch: boss/seesaw-linked spikeball and fragments
    // =====================================================================

    @Test
    void s1SlzBossSpikeballGraphClassesAllImplementRewindRecreatable() {
        for (CodecDeletionCandidate candidate : S1_SLZ_BOSS_SPIKEBALL_GRAPH_DELETED_CODECS) {
            Class<?> cls = loadClass(candidate.fqn());
            assertTrue(RewindRecreatable.class.isAssignableFrom(cls),
                    candidate.fqn() + " must implement RewindRecreatable after S1 SLZ boss spikeball graph batch");
        }
    }

    @Test
    void s1SlzBossSpikeballGraphClassesHaveNoRegisteredS1Codec() {
        for (CodecDeletionCandidate candidate : S1_SLZ_BOSS_SPIKEBALL_GRAPH_DELETED_CODECS) {
            assertFalse(hasRegisteredDynamicCodec(candidate.fqn(), candidate.gameId()),
                    candidate.fqn()
                            + " must restore through S1 SLZ boss spikeball graph generic recreate, "
                            + "not a dynamic codec");
        }
    }

    // =====================================================================
    // S1 ending Sonic graph batch: ending Sonic plus emerald family refs
    // =====================================================================

    @Test
    void s1EndingSonicGraphClassesAllImplementRewindRecreatable() {
        for (CodecDeletionCandidate candidate : S1_ENDING_SONIC_GRAPH_DELETED_CODECS) {
            Class<?> cls = loadClass(candidate.fqn());
            assertTrue(RewindRecreatable.class.isAssignableFrom(cls),
                    candidate.fqn() + " must implement RewindRecreatable after S1 ending Sonic graph batch");
        }
    }

    @Test
    void s1EndingSonicGraphClassesHaveNoRegisteredS1Codec() {
        for (CodecDeletionCandidate candidate : S1_ENDING_SONIC_GRAPH_DELETED_CODECS) {
            assertFalse(hasRegisteredDynamicCodec(candidate.fqn(), candidate.gameId()),
                    candidate.fqn()
                            + " must restore through S1 ending Sonic graph generic recreate, "
                            + "not a dynamic codec");
        }
    }

    // =====================================================================
    // S1 Grass Fire graph batch: platform/fire bidirectional refs
    // =====================================================================

    @Test
    void s1GrassFireGraphClassesAllImplementRewindRecreatable() {
        for (CodecDeletionCandidate candidate : S1_GRASS_FIRE_GRAPH_DELETED_CODECS) {
            Class<?> cls = loadClass(candidate.fqn());
            assertTrue(RewindRecreatable.class.isAssignableFrom(cls),
                    candidate.fqn() + " must implement RewindRecreatable after S1 Grass Fire graph batch");
        }
    }

    @Test
    void s1GrassFireGraphClassesHaveNoRegisteredS1Codec() {
        for (CodecDeletionCandidate candidate : S1_GRASS_FIRE_GRAPH_DELETED_CODECS) {
            assertFalse(hasRegisteredDynamicCodec(candidate.fqn(), candidate.gameId()),
                    candidate.fqn()
                            + " must restore through S1 Grass Fire graph generic recreate, "
                            + "not a dynamic codec");
        }
    }

    // =====================================================================
    // S1 badnik child graph batch: live-parent-linked dynamic children
    // =====================================================================

    @Test
    void s1BadnikChildGraphClassesAllImplementRewindRecreatable() {
        for (CodecDeletionCandidate candidate : S1_BADNIK_GRAPH_DELETED_CODECS) {
            Class<?> cls = loadClass(candidate.fqn());
            assertTrue(RewindRecreatable.class.isAssignableFrom(cls),
                    candidate.fqn() + " must implement RewindRecreatable after S1 badnik graph batch");
        }
    }

    @Test
    void s1BadnikChildGraphClassesHaveNoRegisteredS1Codec() {
        for (CodecDeletionCandidate candidate : S1_BADNIK_GRAPH_DELETED_CODECS) {
            assertFalse(hasRegisteredDynamicCodec(candidate.fqn(), candidate.gameId()),
                    candidate.fqn()
                            + " must restore through S1 badnik graph generic recreate, not a dynamic codec");
        }
    }

    // =====================================================================
    // S2 WFZ boss graph batch: boss/platform/hurt-linked dynamics
    // =====================================================================

    @Test
    void s2WfzBossGraphClassesAllImplementRewindRecreatable() {
        for (CodecDeletionCandidate candidate : S2_WFZ_BOSS_GRAPH_DELETED_CODECS) {
            Class<?> cls = loadClass(candidate.fqn());
            assertTrue(RewindRecreatable.class.isAssignableFrom(cls),
                    candidate.fqn() + " must implement RewindRecreatable after S2 WFZ boss graph batch");
        }
    }

    @Test
    void s2WfzBossGraphClassesHaveNoRegisteredCodec() {
        for (CodecDeletionCandidate candidate : S2_WFZ_BOSS_GRAPH_DELETED_CODECS) {
            assertFalse(hasRegisteredDynamicCodec(candidate.fqn(), candidate.gameId()),
                    candidate.fqn()
                            + " must restore through S2 WFZ boss graph generic recreate, not a dynamic codec");
        }
    }

    @Test
    void s2WfzBossParentClassesAllImplementRewindRecreatable() {
        for (CodecDeletionCandidate candidate : S2_WFZ_BOSS_PARENT_DELETED_CODECS) {
            Class<?> cls = loadClass(candidate.fqn());
            assertTrue(RewindRecreatable.class.isAssignableFrom(cls),
                    candidate.fqn() + " must implement RewindRecreatable after S2 WFZ boss parent batch");
        }
    }

    @Test
    void s2WfzBossParentClassesHaveNoRegisteredCodec() {
        for (CodecDeletionCandidate candidate : S2_WFZ_BOSS_PARENT_DELETED_CODECS) {
            assertFalse(hasRegisteredDynamicCodec(candidate.fqn(), candidate.gameId()),
                    candidate.fqn()
                            + " must restore through S2 WFZ boss parent generic recreate, not a dynamic codec");
        }
    }

    // =====================================================================
    // S2 HTZ boss graph batch: boss-linked hazard children
    // =====================================================================

    @Test
    void s2HtzBossGraphClassesAllImplementRewindRecreatable() {
        for (CodecDeletionCandidate candidate : S2_HTZ_BOSS_GRAPH_DELETED_CODECS) {
            Class<?> cls = loadClass(candidate.fqn());
            assertTrue(RewindRecreatable.class.isAssignableFrom(cls),
                    candidate.fqn() + " must implement RewindRecreatable after S2 HTZ boss graph batch");
        }
    }

    @Test
    void s2HtzBossGraphClassesHaveNoRegisteredCodec() {
        for (CodecDeletionCandidate candidate : S2_HTZ_BOSS_GRAPH_DELETED_CODECS) {
            assertFalse(hasRegisteredDynamicCodec(candidate.fqn(), candidate.gameId()),
                    candidate.fqn()
                            + " must restore through S2 HTZ boss graph generic recreate, not a dynamic codec");
        }
    }

    // =====================================================================
    // S1/S2 seesaw graph batch: parent-linked child dynamics
    // =====================================================================

    @Test
    void seesawGraphClassesAllImplementRewindRecreatable() {
        for (CodecDeletionCandidate candidate : SEESAW_GRAPH_DELETED_CODECS) {
            Class<?> cls = loadClass(candidate.fqn());
            assertTrue(RewindRecreatable.class.isAssignableFrom(cls),
                    candidate.fqn() + " must implement RewindRecreatable after seesaw graph batch");
        }
    }

    @Test
    void seesawGraphClassesHaveNoRegisteredCodec() {
        for (CodecDeletionCandidate candidate : SEESAW_GRAPH_DELETED_CODECS) {
            assertFalse(hasRegisteredDynamicCodec(candidate.fqn(), candidate.gameId()),
                    candidate.fqn()
                            + " must restore through seesaw graph generic recreate, not a dynamic codec");
        }
    }

    // =====================================================================
    // S3K badnik child graph batch: parent/sibling-linked dynamic children
    // =====================================================================

    @Test
    void s3kBadnikChildGraphClassesAllImplementRewindRecreatable() {
        for (CodecDeletionCandidate candidate : S3K_BADNIK_CHILD_GRAPH_DELETED_CODECS) {
            Class<?> cls = loadClass(candidate.fqn());
            assertTrue(RewindRecreatable.class.isAssignableFrom(cls),
                    candidate.fqn() + " must implement RewindRecreatable after S3K badnik child graph batch");
        }
    }

    @Test
    void s3kBadnikChildGraphClassesHaveNoRegisteredCodec() {
        for (CodecDeletionCandidate candidate : S3K_BADNIK_CHILD_GRAPH_DELETED_CODECS) {
            assertFalse(hasRegisteredDynamicCodec(candidate.fqn(), candidate.gameId()),
                    candidate.fqn()
                            + " must restore through S3K badnik child graph generic recreate, not a dynamic codec");
        }
    }

    @Test
    void s3kDragonflyGraphBatch219ClassesImplementRewindRecreatable() {
        for (CodecDeletionCandidate candidate : S3K_DRAGONFLY_GRAPH_BATCH219_RECREATE_CLASSES) {
            Class<?> cls = loadClass(candidate.fqn());
            assertTrue(RewindRecreatable.class.isAssignableFrom(cls),
                    candidate.fqn() + " must implement RewindRecreatable after S3K Dragonfly graph batch 219");
        }
    }

    @Test
    void s3kDragonflyGraphBatch219ClassesHaveNoRegisteredCodec() {
        for (CodecDeletionCandidate candidate : S3K_DRAGONFLY_GRAPH_BATCH219_RECREATE_CLASSES) {
            assertFalse(hasRegisteredDynamicCodec(candidate.fqn(), candidate.gameId()),
                    candidate.fqn()
                            + " must restore through S3K Dragonfly graph batch 219 generic recreate, not a dynamic codec");
        }
    }

    @Test
    void s3kDragonflyGraphBatch219ClassesRoundTripPassedWithoutCodec() {
        for (CodecDeletionCandidate candidate : S3K_DRAGONFLY_GRAPH_BATCH219_RECREATE_CLASSES) {
            RoundTripSweepResult result = RewindRoundTripHarness.probeClass(candidate.fqn());
            assertInstanceOf(RoundTripSweepResult.Passed.class, result,
                    candidate.fqn()
                            + " must round-trip as Passed via RewindRecreatable path (no codec); got: "
                            + result);
        }
    }

    @Test
    void s3kSpikerGraphBatch220ClassesImplementRewindRecreatable() {
        for (CodecDeletionCandidate candidate : S3K_SPIKER_GRAPH_BATCH220_RECREATE_CLASSES) {
            Class<?> cls = loadClass(candidate.fqn());
            assertTrue(RewindRecreatable.class.isAssignableFrom(cls),
                    candidate.fqn() + " must implement RewindRecreatable after S3K Spiker graph batch 220");
        }
    }

    @Test
    void s3kSpikerGraphBatch220ClassesHaveNoRegisteredCodec() {
        for (CodecDeletionCandidate candidate : S3K_SPIKER_GRAPH_BATCH220_RECREATE_CLASSES) {
            assertFalse(hasRegisteredDynamicCodec(candidate.fqn(), candidate.gameId()),
                    candidate.fqn()
                            + " must restore through S3K Spiker graph batch 220 generic recreate, not a dynamic codec");
        }
    }

    @Test
    void s3kSpikerGraphBatch220ClassesRoundTripPassedWithoutCodec() {
        for (CodecDeletionCandidate candidate : S3K_SPIKER_GRAPH_BATCH220_RECREATE_CLASSES) {
            RoundTripSweepResult result = RewindRoundTripHarness.probeClass(candidate.fqn());
            assertInstanceOf(RoundTripSweepResult.Passed.class, result,
                    candidate.fqn()
                            + " must round-trip as Passed via RewindRecreatable path (no codec); got: "
                            + result);
        }
    }

    @Test
    void s3kTurboSpikerGraphBatch221ClassesImplementRewindRecreatable() {
        for (CodecDeletionCandidate candidate : S3K_TURBO_SPIKER_GRAPH_BATCH221_RECREATE_CLASSES) {
            Class<?> cls = loadClass(candidate.fqn());
            assertTrue(RewindRecreatable.class.isAssignableFrom(cls),
                    candidate.fqn() + " must implement RewindRecreatable after S3K Turbo Spiker graph batch 221");
        }
    }

    @Test
    void s3kTurboSpikerGraphBatch221ClassesHaveNoRegisteredCodec() {
        for (CodecDeletionCandidate candidate : S3K_TURBO_SPIKER_GRAPH_BATCH221_RECREATE_CLASSES) {
            assertFalse(hasRegisteredDynamicCodec(candidate.fqn(), candidate.gameId()),
                    candidate.fqn()
                            + " must restore through S3K Turbo Spiker graph batch 221 generic recreate, not a dynamic codec");
        }
    }

    @Test
    void s3kTurboSpikerGraphBatch221ParentRoundTripsPassedWithoutCodec() {
        CodecDeletionCandidate candidate = S3K_TURBO_SPIKER_GRAPH_BATCH221_RECREATE_CLASSES.getFirst();
        RoundTripSweepResult result = RewindRoundTripHarness.probeClass(candidate.fqn());
        assertInstanceOf(RoundTripSweepResult.Passed.class, result,
                candidate.fqn()
                        + " must round-trip as Passed via RewindRecreatable path (no codec); got: "
                        + result);
    }

    @Test
    void s3kMegaChopperBatch222ClassesImplementRewindRecreatable() {
        for (CodecDeletionCandidate candidate : S3K_MEGA_CHOPPER_BATCH222_RECREATE_CLASSES) {
            Class<?> cls = loadClass(candidate.fqn());
            assertTrue(RewindRecreatable.class.isAssignableFrom(cls),
                    candidate.fqn() + " must implement RewindRecreatable after S3K MegaChopper batch 222");
        }
    }

    @Test
    void s3kMegaChopperBatch222ClassesHaveNoRegisteredCodec() {
        for (CodecDeletionCandidate candidate : S3K_MEGA_CHOPPER_BATCH222_RECREATE_CLASSES) {
            assertFalse(hasRegisteredDynamicCodec(candidate.fqn(), candidate.gameId()),
                    candidate.fqn()
                            + " must restore through S3K MegaChopper batch 222 generic recreate, not a dynamic codec");
        }
    }

    @Test
    void s3kMegaChopperBatch222ClassesRoundTripPassedWithoutCodec() {
        for (CodecDeletionCandidate candidate : S3K_MEGA_CHOPPER_BATCH222_RECREATE_CLASSES) {
            RoundTripSweepResult result = RewindRoundTripHarness.probeClass(candidate.fqn());
            assertInstanceOf(RoundTripSweepResult.Passed.class, result,
                    candidate.fqn()
                            + " must round-trip as Passed via RewindRecreatable path (no codec); got: "
                            + result);
        }
    }

    @Test
    void s3kCluckoidArrowBatch223ClassesImplementRewindRecreatable() {
        for (CodecDeletionCandidate candidate : S3K_CLUCKOID_ARROW_BATCH223_RECREATE_CLASSES) {
            Class<?> cls = loadClass(candidate.fqn());
            assertTrue(RewindRecreatable.class.isAssignableFrom(cls),
                    candidate.fqn() + " must implement RewindRecreatable after S3K Cluckoid arrow batch 223");
        }
    }

    @Test
    void s3kCluckoidArrowBatch223ClassesHaveNoRegisteredCodec() {
        for (CodecDeletionCandidate candidate : S3K_CLUCKOID_ARROW_BATCH223_RECREATE_CLASSES) {
            assertFalse(hasRegisteredDynamicCodec(candidate.fqn(), candidate.gameId()),
                    candidate.fqn()
                            + " must restore through S3K Cluckoid arrow batch 223 generic recreate, not a dynamic codec");
        }
    }

    @Test
    void s3kCluckoidArrowBatch223ClassesRoundTripPassedWithoutCodec() {
        for (CodecDeletionCandidate candidate : S3K_CLUCKOID_ARROW_BATCH223_RECREATE_CLASSES) {
            RoundTripSweepResult result = RewindRoundTripHarness.probeClass(candidate.fqn());
            assertInstanceOf(RoundTripSweepResult.Passed.class, result,
                    candidate.fqn()
                            + " must round-trip as Passed via RewindRecreatable path (no codec); got: "
                            + result);
        }
    }

    @Test
    void s3kMushmeanieGraphBatch224ClassesImplementRewindRecreatable() {
        for (CodecDeletionCandidate candidate : S3K_MUSHMEANIE_GRAPH_BATCH224_RECREATE_CLASSES) {
            Class<?> cls = loadClass(candidate.fqn());
            assertTrue(RewindRecreatable.class.isAssignableFrom(cls),
                    candidate.fqn() + " must implement RewindRecreatable after S3K Mushmeanie graph batch 224");
        }
    }

    @Test
    void s3kMushmeanieGraphBatch224ClassesHaveNoRegisteredCodec() {
        for (CodecDeletionCandidate candidate : S3K_MUSHMEANIE_GRAPH_BATCH224_RECREATE_CLASSES) {
            assertFalse(hasRegisteredDynamicCodec(candidate.fqn(), candidate.gameId()),
                    candidate.fqn()
                            + " must restore through S3K Mushmeanie graph batch 224 generic recreate, not a dynamic codec");
        }
    }

    @Test
    void s3kMushmeanieGraphBatch224ClassesRoundTripPassedWithoutCodec() {
        for (CodecDeletionCandidate candidate : S3K_MUSHMEANIE_GRAPH_BATCH224_RECREATE_CLASSES) {
            RoundTripSweepResult result = RewindRoundTripHarness.probeClass(candidate.fqn());
            assertInstanceOf(RoundTripSweepResult.Passed.class, result,
                    candidate.fqn()
                            + " must round-trip as Passed via RewindRecreatable path (no codec); got: "
                            + result);
        }
    }

    @Test
    void s3kMantisGraphBatch225ClassesImplementRewindRecreatable() {
        for (CodecDeletionCandidate candidate : S3K_MANTIS_GRAPH_BATCH225_RECREATE_CLASSES) {
            Class<?> cls = loadClass(candidate.fqn());
            assertTrue(RewindRecreatable.class.isAssignableFrom(cls),
                    candidate.fqn() + " must implement RewindRecreatable after S3K Mantis graph batch 225");
        }
    }

    @Test
    void s3kMantisGraphBatch225ClassesHaveNoRegisteredCodec() {
        for (CodecDeletionCandidate candidate : S3K_MANTIS_GRAPH_BATCH225_RECREATE_CLASSES) {
            assertFalse(hasRegisteredDynamicCodec(candidate.fqn(), candidate.gameId()),
                    candidate.fqn()
                            + " must restore through S3K Mantis graph batch 225 generic recreate, not a dynamic codec");
        }
    }

    @Test
    void s3kMantisGraphBatch225ClassesRoundTripPassedWithoutCodec() {
        for (CodecDeletionCandidate candidate : S3K_MANTIS_GRAPH_BATCH225_RECREATE_CLASSES) {
            RoundTripSweepResult result = RewindRoundTripHarness.probeClass(candidate.fqn());
            assertInstanceOf(RoundTripSweepResult.Passed.class, result,
                    candidate.fqn()
                            + " must round-trip as Passed via RewindRecreatable path (no codec); got: "
                            + result);
        }
    }

    @Test
    void s3kRibotVisualGraphBatch226ClassesImplementRewindRecreatable() {
        for (CodecDeletionCandidate candidate : S3K_RIBOT_VISUAL_GRAPH_BATCH226_RECREATE_CLASSES) {
            Class<?> cls = loadClass(candidate.fqn());
            assertTrue(RewindRecreatable.class.isAssignableFrom(cls),
                    candidate.fqn() + " must implement RewindRecreatable after S3K Ribot visual graph batch 226");
        }
    }

    @Test
    void s3kRibotVisualGraphBatch226ClassesHaveNoRegisteredCodec() {
        for (CodecDeletionCandidate candidate : S3K_RIBOT_VISUAL_GRAPH_BATCH226_RECREATE_CLASSES) {
            assertFalse(hasRegisteredDynamicCodec(candidate.fqn(), candidate.gameId()),
                    candidate.fqn()
                            + " must restore through S3K Ribot visual graph batch 226 generic recreate, not a dynamic codec");
        }
    }

    @Test
    void s3kRibotVisualGraphBatch226ClassesRoundTripPassedWithoutCodec() {
        for (CodecDeletionCandidate candidate : S3K_RIBOT_VISUAL_GRAPH_BATCH226_RECREATE_CLASSES) {
            RoundTripSweepResult result = RewindRoundTripHarness.probeClass(candidate.fqn());
            assertInstanceOf(RoundTripSweepResult.Passed.class, result,
                    candidate.fqn()
                            + " must round-trip as Passed via RewindRecreatable path (no codec); got: "
                            + result);
        }
    }

    @Test
    void s3kSnaleBlasterGraphBatch227ClassesImplementRewindRecreatable() {
        for (CodecDeletionCandidate candidate : S3K_SNALE_BLASTER_GRAPH_BATCH227_RECREATE_CLASSES) {
            Class<?> cls = loadClass(candidate.fqn());
            assertTrue(RewindRecreatable.class.isAssignableFrom(cls),
                    candidate.fqn() + " must implement RewindRecreatable after S3K SnaleBlaster graph batch 227");
        }
    }

    @Test
    void s3kSnaleBlasterGraphBatch227ClassesHaveNoRegisteredCodec() {
        for (CodecDeletionCandidate candidate : S3K_SNALE_BLASTER_GRAPH_BATCH227_RECREATE_CLASSES) {
            assertFalse(hasRegisteredDynamicCodec(candidate.fqn(), candidate.gameId()),
                    candidate.fqn()
                            + " must restore through S3K SnaleBlaster graph batch 227 generic recreate, not a dynamic codec");
        }
    }

    @Test
    void s3kSnaleBlasterGraphBatch227ClassesRoundTripPassedWithoutCodec() {
        for (CodecDeletionCandidate candidate : S3K_SNALE_BLASTER_GRAPH_BATCH227_RECREATE_CLASSES) {
            RoundTripSweepResult result = RewindRoundTripHarness.probeClass(candidate.fqn());
            assertInstanceOf(RoundTripSweepResult.Passed.class, result,
                    candidate.fqn()
                            + " must round-trip as Passed via RewindRecreatable path (no codec); got: "
                            + result);
        }
    }

    @Test
    void s3kCaterkillerJrGraphBatch228ClassesImplementRewindRecreatable() {
        for (CodecDeletionCandidate candidate : S3K_CATERKILLER_JR_GRAPH_BATCH228_RECREATE_CLASSES) {
            Class<?> cls = loadClass(candidate.fqn());
            assertTrue(RewindRecreatable.class.isAssignableFrom(cls),
                    candidate.fqn() + " must implement RewindRecreatable after S3K Caterkiller Jr graph batch 228");
        }
    }

    @Test
    void s3kCaterkillerJrGraphBatch228ClassesHaveNoRegisteredCodec() {
        for (CodecDeletionCandidate candidate : S3K_CATERKILLER_JR_GRAPH_BATCH228_RECREATE_CLASSES) {
            assertFalse(hasRegisteredDynamicCodec(candidate.fqn(), candidate.gameId()),
                    candidate.fqn()
                            + " must restore through S3K Caterkiller Jr graph batch 228 generic recreate, not a dynamic codec");
        }
    }

    @Test
    void s3kCaterkillerJrGraphBatch228ClassesRoundTripPassedWithoutCodec() {
        for (CodecDeletionCandidate candidate : S3K_CATERKILLER_JR_GRAPH_BATCH228_RECREATE_CLASSES) {
            RoundTripSweepResult result = RewindRoundTripHarness.probeClass(candidate.fqn());
            assertInstanceOf(RoundTripSweepResult.Passed.class, result,
                    candidate.fqn()
                            + " must round-trip as Passed via RewindRecreatable path (no codec); got: "
                            + result);
        }
    }

    @Test
    void s3kTunnelbotGraphBatch229ClassesImplementRewindRecreatable() {
        for (CodecDeletionCandidate candidate : S3K_TUNNELBOT_GRAPH_BATCH229_RECREATE_CLASSES) {
            Class<?> cls = loadClass(candidate.fqn());
            assertTrue(RewindRecreatable.class.isAssignableFrom(cls),
                    candidate.fqn() + " must implement RewindRecreatable after S3K Tunnelbot graph batch 229");
        }
    }

    @Test
    void s3kTunnelbotGraphBatch229ClassesHaveNoRegisteredCodec() {
        for (CodecDeletionCandidate candidate : S3K_TUNNELBOT_GRAPH_BATCH229_RECREATE_CLASSES) {
            assertFalse(hasRegisteredDynamicCodec(candidate.fqn(), candidate.gameId()),
                    candidate.fqn()
                            + " must restore through S3K Tunnelbot graph batch 229 generic recreate, not a dynamic codec");
        }
    }

    @Test
    void s3kTunnelbotGraphBatch229ClassesRoundTripPassedWithoutCodec() {
        for (CodecDeletionCandidate candidate : S3K_TUNNELBOT_GRAPH_BATCH229_RECREATE_CLASSES) {
            RoundTripSweepResult result = RewindRoundTripHarness.probeClass(candidate.fqn());
            assertInstanceOf(RoundTripSweepResult.Passed.class, result,
                    candidate.fqn()
                            + " must round-trip as Passed via RewindRecreatable path (no codec); got: "
                            + result);
        }
    }

    @Test
    void s3kCollapsingBridgeBatch230ClassesImplementRewindRecreatable() {
        for (CodecDeletionCandidate candidate : S3K_COLLAPSING_BRIDGE_BATCH230_RECREATE_CLASSES) {
            Class<?> cls = loadClass(candidate.fqn());
            assertTrue(RewindRecreatable.class.isAssignableFrom(cls),
                    candidate.fqn() + " must implement RewindRecreatable after S3K collapsing bridge batch 230");
        }
    }

    @Test
    void s3kCollapsingBridgeBatch230ClassesHaveNoRegisteredCodec() {
        for (CodecDeletionCandidate candidate : S3K_COLLAPSING_BRIDGE_BATCH230_RECREATE_CLASSES) {
            assertFalse(hasRegisteredDynamicCodec(candidate.fqn(), candidate.gameId()),
                    candidate.fqn()
                            + " must restore through S3K collapsing bridge batch 230 generic recreate, not a dynamic codec");
        }
    }

    @Test
    void s3kCollapsingBridgeBatch230ClassesRoundTripPassedWithoutCodec() {
        for (CodecDeletionCandidate candidate : S3K_COLLAPSING_BRIDGE_BATCH230_RECREATE_CLASSES) {
            RoundTripSweepResult result = RewindRoundTripHarness.probeClass(candidate.fqn());
            assertInstanceOf(RoundTripSweepResult.Passed.class, result,
                    candidate.fqn()
                            + " must round-trip as Passed via RewindRecreatable path (no codec); got: "
                            + result);
        }
    }

    @Test
    void s3kTensionBridgeBatch231ClassesImplementRewindRecreatable() {
        for (CodecDeletionCandidate candidate : S3K_TENSION_BRIDGE_BATCH231_RECREATE_CLASSES) {
            Class<?> cls = loadClass(candidate.fqn());
            assertTrue(RewindRecreatable.class.isAssignableFrom(cls),
                    candidate.fqn() + " must implement RewindRecreatable after S3K tension bridge batch 231");
        }
    }

    @Test
    void s3kTensionBridgeBatch231ClassesHaveNoRegisteredCodec() {
        for (CodecDeletionCandidate candidate : S3K_TENSION_BRIDGE_BATCH231_RECREATE_CLASSES) {
            assertFalse(hasRegisteredDynamicCodec(candidate.fqn(), candidate.gameId()),
                    candidate.fqn()
                            + " must restore through S3K tension bridge batch 231 generic recreate, not a dynamic codec");
        }
    }

    @Test
    void s3kTensionBridgeBatch231ClassesRoundTripPassedWithoutCodec() {
        for (CodecDeletionCandidate candidate : S3K_TENSION_BRIDGE_BATCH231_RECREATE_CLASSES) {
            RoundTripSweepResult result = RewindRoundTripHarness.probeClass(candidate.fqn());
            assertInstanceOf(RoundTripSweepResult.Passed.class, result,
                    candidate.fqn()
                            + " must round-trip as Passed via RewindRecreatable path (no codec); got: "
                            + result);
        }
    }

    @Test
    void s3kDestructibleFragmentBatch232ClassesImplementRewindRecreatable() {
        for (CodecDeletionCandidate candidate : S3K_DESTRUCTIBLE_FRAGMENT_BATCH232_RECREATE_CLASSES) {
            Class<?> cls = loadClass(candidate.fqn());
            assertTrue(RewindRecreatable.class.isAssignableFrom(cls),
                    candidate.fqn() + " must implement RewindRecreatable after S3K fragment batch 232");
        }
    }

    @Test
    void s3kDestructibleFragmentBatch232ClassesHaveNoRegisteredCodec() {
        for (CodecDeletionCandidate candidate : S3K_DESTRUCTIBLE_FRAGMENT_BATCH232_RECREATE_CLASSES) {
            assertFalse(hasRegisteredDynamicCodec(candidate.fqn(), candidate.gameId()),
                    candidate.fqn()
                            + " must restore through S3K fragment batch 232 generic recreate, not a dynamic codec");
        }
    }

    @Test
    void s3kDestructibleFragmentBatch232ClassesRoundTripPassedWithoutCodec() {
        for (CodecDeletionCandidate candidate : S3K_DESTRUCTIBLE_FRAGMENT_BATCH232_RECREATE_CLASSES) {
            RoundTripSweepResult result = RewindRoundTripHarness.probeClass(candidate.fqn());
            assertInstanceOf(RoundTripSweepResult.Passed.class, result,
                    candidate.fqn()
                            + " must round-trip as Passed via RewindRecreatable path (no codec); got: "
                            + result);
        }
    }

    @Test
    void s3kGumballGraphBatch233ClassesImplementRewindRecreatable() {
        for (CodecDeletionCandidate candidate : S3K_GUMBALL_GRAPH_BATCH233_RECREATE_CLASSES) {
            Class<?> cls = loadClass(candidate.fqn());
            assertTrue(RewindRecreatable.class.isAssignableFrom(cls),
                    candidate.fqn() + " must implement RewindRecreatable after S3K gumball graph batch 233");
        }
    }

    @Test
    void s3kGumballGraphBatch233ClassesHaveNoRegisteredCodec() {
        for (CodecDeletionCandidate candidate : S3K_GUMBALL_GRAPH_BATCH233_RECREATE_CLASSES) {
            assertFalse(hasRegisteredDynamicCodec(candidate.fqn(), candidate.gameId()),
                    candidate.fqn()
                            + " must restore through S3K gumball graph batch 233 generic recreate, not a dynamic codec");
        }
    }

    @Test
    void s3kPachinkoTrapGraphBatch234ClassesImplementRewindRecreatable() {
        for (CodecDeletionCandidate candidate : S3K_PACHINKO_TRAP_GRAPH_BATCH234_RECREATE_CLASSES) {
            Class<?> cls = loadClass(candidate.fqn());
            assertTrue(RewindRecreatable.class.isAssignableFrom(cls),
                    candidate.fqn() + " must implement RewindRecreatable after S3K pachinko trap graph batch 234");
        }
    }

    @Test
    void s3kPachinkoTrapGraphBatch234ClassesHaveNoRegisteredCodec() {
        for (CodecDeletionCandidate candidate : S3K_PACHINKO_TRAP_GRAPH_BATCH234_RECREATE_CLASSES) {
            assertFalse(hasRegisteredDynamicCodec(candidate.fqn(), candidate.gameId()),
                    candidate.fqn()
                            + " must restore through S3K pachinko trap graph batch 234 generic recreate, not a dynamic codec");
        }
    }

    @Test
    void s3kIczSupportGraphBatch235ClassesImplementRewindRecreatable() {
        for (CodecDeletionCandidate candidate : S3K_ICZ_SUPPORT_GRAPH_BATCH235_RECREATE_CLASSES) {
            Class<?> cls = loadClass(candidate.fqn());
            assertTrue(RewindRecreatable.class.isAssignableFrom(cls),
                    candidate.fqn() + " must implement RewindRecreatable after S3K ICZ support graph batch 235");
        }
    }

    @Test
    void s3kIczSupportGraphBatch235ClassesHaveNoRegisteredCodec() {
        for (CodecDeletionCandidate candidate : S3K_ICZ_SUPPORT_GRAPH_BATCH235_RECREATE_CLASSES) {
            assertFalse(hasRegisteredDynamicCodec(candidate.fqn(), candidate.gameId()),
                    candidate.fqn()
                            + " must restore through S3K ICZ support graph batch 235 generic recreate, not a dynamic codec");
        }
    }

    @Test
    void s3kIczSnowGraphBatch236ClassesImplementRewindRecreatable() {
        for (CodecDeletionCandidate candidate : S3K_ICZ_SNOW_GRAPH_BATCH236_RECREATE_CLASSES) {
            Class<?> cls = loadClass(candidate.fqn());
            assertTrue(RewindRecreatable.class.isAssignableFrom(cls),
                    candidate.fqn() + " must implement RewindRecreatable after S3K ICZ snow graph batch 236");
        }
    }

    @Test
    void s3kIczSnowGraphBatch236ClassesHaveNoRegisteredCodec() {
        for (CodecDeletionCandidate candidate : S3K_ICZ_SNOW_GRAPH_BATCH236_RECREATE_CLASSES) {
            assertFalse(hasRegisteredDynamicCodec(candidate.fqn(), candidate.gameId()),
                    candidate.fqn()
                            + " must restore through S3K ICZ snow graph batch 236 generic recreate, not a dynamic codec");
        }
    }

    @Test
    void s3kMgzMechanismGraphBatch245ClassesImplementRewindRecreatable() {
        for (CodecDeletionCandidate candidate : S3K_MGZ_MECHANISM_GRAPH_BATCH245_RECREATE_CLASSES) {
            Class<?> cls = loadClass(candidate.fqn());
            assertTrue(RewindRecreatable.class.isAssignableFrom(cls),
                    candidate.fqn() + " must implement RewindRecreatable after S3K MGZ mechanism batch 245");
        }
    }

    @Test
    void s3kMgzMechanismGraphBatch245ClassesHaveNoRegisteredCodec() {
        for (CodecDeletionCandidate candidate : S3K_MGZ_MECHANISM_GRAPH_BATCH245_RECREATE_CLASSES) {
            assertFalse(hasRegisteredDynamicCodec(candidate.fqn(), candidate.gameId()),
                    candidate.fqn()
                            + " must restore through S3K MGZ mechanism batch 245 generic recreate, not a dynamic codec");
        }
    }

    @Test
    void sharedBoxBatch237ClassesImplementRewindRecreatable() {
        for (CodecDeletionCandidate candidate : SHARED_BOX_BATCH237_RECREATE_CLASSES) {
            Class<?> cls = loadClass(candidate.fqn());
            assertTrue(RewindRecreatable.class.isAssignableFrom(cls),
                    candidate.fqn() + " must implement RewindRecreatable after shared box batch 237");
        }
    }

    @Test
    void sharedBoxBatch237ClassesHaveNoRegisteredCodec() {
        for (CodecDeletionCandidate candidate : SHARED_BOX_BATCH237_RECREATE_CLASSES) {
            assertFalse(hasRegisteredDynamicCodec(candidate.fqn(), candidate.gameId()),
                    candidate.fqn()
                            + " must restore through shared box batch 237 generic recreate, not a dynamic codec");
        }
    }

    @Test
    void sharedBoxBatch237ClassesRoundTripPassedWithoutCodec() {
        for (CodecDeletionCandidate candidate : SHARED_BOX_BATCH237_RECREATE_CLASSES) {
            RoundTripSweepResult result = RewindRoundTripHarness.probeClass(candidate.fqn());
            assertInstanceOf(RoundTripSweepResult.Passed.class, result,
                    candidate.fqn()
                            + " must round-trip as Passed via RewindRecreatable path (no codec); got: "
                            + result);
        }
    }

    @Test
    void s3kLbzTriggerBridgeBatch238ClassesImplementRewindRecreatable() {
        for (CodecDeletionCandidate candidate : S3K_LBZ_TRIGGER_BRIDGE_BATCH238_RECREATE_CLASSES) {
            Class<?> cls = loadClass(candidate.fqn());
            assertTrue(RewindRecreatable.class.isAssignableFrom(cls),
                    candidate.fqn() + " must implement RewindRecreatable after LBZ trigger bridge batch 238");
        }
    }

    @Test
    void s3kLbzTriggerBridgeBatch238ClassesHaveNoRegisteredCodec() {
        for (CodecDeletionCandidate candidate : S3K_LBZ_TRIGGER_BRIDGE_BATCH238_RECREATE_CLASSES) {
            assertFalse(hasRegisteredDynamicCodec(candidate.fqn(), candidate.gameId()),
                    candidate.fqn()
                            + " must restore through LBZ trigger bridge batch 238 generic recreate, not a dynamic codec");
        }
    }

    @Test
    void s3kLbzTriggerBridgeBatch238ClassesRoundTripPassedWithoutCodec() {
        for (CodecDeletionCandidate candidate : S3K_LBZ_TRIGGER_BRIDGE_BATCH238_RECREATE_CLASSES) {
            RoundTripSweepResult result = RewindRoundTripHarness.probeClass(candidate.fqn());
            assertInstanceOf(RoundTripSweepResult.Passed.class, result,
                    candidate.fqn()
                            + " must round-trip as Passed via RewindRecreatable path (no codec); got: "
                            + result);
        }
    }

    @Test
    void s3kAizEmeraldScatterBatch240ClassesImplementRewindRecreatable() {
        for (CodecDeletionCandidate candidate : S3K_AIZ_EMERALD_SCATTER_BATCH240_RECREATE_CLASSES) {
            Class<?> cls = loadClass(candidate.fqn());
            assertTrue(RewindRecreatable.class.isAssignableFrom(cls),
                    candidate.fqn() + " must implement RewindRecreatable after AIZ emerald scatter batch 240");
        }
    }

    @Test
    void s3kAizEmeraldScatterBatch240ClassesHaveNoRegisteredCodec() {
        for (CodecDeletionCandidate candidate : S3K_AIZ_EMERALD_SCATTER_BATCH240_RECREATE_CLASSES) {
            assertFalse(hasRegisteredDynamicCodec(candidate.fqn(), candidate.gameId()),
                    candidate.fqn()
                            + " must restore through AIZ emerald scatter batch 240 generic recreate, not a dynamic codec");
        }
    }

    @Test
    void s3kAizEmeraldScatterBatch240ClassesRoundTripPassedWithoutCodec() {
        for (CodecDeletionCandidate candidate : S3K_AIZ_EMERALD_SCATTER_BATCH240_RECREATE_CLASSES) {
            RoundTripSweepResult result = RewindRoundTripHarness.probeClass(candidate.fqn());
            assertInstanceOf(RoundTripSweepResult.Passed.class, result,
                    candidate.fqn()
                            + " must round-trip as Passed via RewindRecreatable path (no codec); got: "
                            + result);
        }
    }

    // =====================================================================
    // S3K badnik parent batch: standalone spawn-constructible badnik heads
    // =====================================================================

    @Test
    void s3kBadnikParentClassesImplementRewindRecreatable() {
        for (CodecDeletionCandidate candidate : S3K_BADNIK_PARENT_RECREATE_CLASSES) {
            Class<?> cls = loadClass(candidate.fqn());
            assertTrue(RewindRecreatable.class.isAssignableFrom(cls),
                    candidate.fqn() + " must implement RewindRecreatable after S3K badnik parent coverage");
        }
    }

    @Test
    void s3kBadnikParentClassesHaveNoRegisteredCodec() {
        for (CodecDeletionCandidate candidate : S3K_BADNIK_PARENT_RECREATE_CLASSES) {
            assertFalse(hasRegisteredDynamicCodec(candidate.fqn(), candidate.gameId()),
                    candidate.fqn()
                            + " must restore through S3K badnik parent generic recreate, not a dynamic codec");
        }
    }

    @Test
    void s3kBadnikParentClassesRoundTripPassedWithoutCodec() {
        for (CodecDeletionCandidate candidate : S3K_BADNIK_PARENT_RECREATE_CLASSES) {
            RoundTripSweepResult result = RewindRoundTripHarness.probeClass(candidate.fqn());
            assertInstanceOf(RoundTripSweepResult.Passed.class, result,
                    candidate.fqn()
                            + " must round-trip as Passed via RewindRecreatable path (no codec); got: "
                            + result);
        }
    }

    @Test
    void s3kBadnikParentBatch87ClassesImplementRewindRecreatable() {
        for (CodecDeletionCandidate candidate : S3K_BADNIK_PARENT_BATCH87_RECREATE_CLASSES) {
            Class<?> cls = loadClass(candidate.fqn());
            assertTrue(RewindRecreatable.class.isAssignableFrom(cls),
                    candidate.fqn() + " must implement RewindRecreatable after S3K badnik parent batch 87");
        }
    }

    @Test
    void s3kBadnikParentBatch87ClassesHaveNoRegisteredCodec() {
        for (CodecDeletionCandidate candidate : S3K_BADNIK_PARENT_BATCH87_RECREATE_CLASSES) {
            assertFalse(hasRegisteredDynamicCodec(candidate.fqn(), candidate.gameId()),
                    candidate.fqn()
                            + " must restore through S3K badnik parent batch 87 generic recreate, not a dynamic codec");
        }
    }

    @Test
    void s3kBadnikParentBatch87ClassesRoundTripPassedWithoutCodec() {
        for (CodecDeletionCandidate candidate : S3K_BADNIK_PARENT_BATCH87_RECREATE_CLASSES) {
            RoundTripSweepResult result = RewindRoundTripHarness.probeClass(candidate.fqn());
            assertInstanceOf(RoundTripSweepResult.Passed.class, result,
                    candidate.fqn()
                            + " must round-trip as Passed via RewindRecreatable path (no codec); got: "
                            + result);
        }
    }

    @Test
    void s3kBadnikParentBatch88ClassesImplementRewindRecreatable() {
        for (CodecDeletionCandidate candidate : S3K_BADNIK_PARENT_BATCH88_RECREATE_CLASSES) {
            Class<?> cls = loadClass(candidate.fqn());
            assertTrue(RewindRecreatable.class.isAssignableFrom(cls),
                    candidate.fqn() + " must implement RewindRecreatable after S3K badnik parent batch 88");
        }
    }

    @Test
    void s3kBadnikParentBatch88ClassesHaveNoRegisteredCodec() {
        for (CodecDeletionCandidate candidate : S3K_BADNIK_PARENT_BATCH88_RECREATE_CLASSES) {
            assertFalse(hasRegisteredDynamicCodec(candidate.fqn(), candidate.gameId()),
                    candidate.fqn()
                            + " must restore through S3K badnik parent batch 88 generic recreate, not a dynamic codec");
        }
    }

    @Test
    void s3kBadnikParentBatch88ClassesRoundTripPassedWithoutCodec() {
        for (CodecDeletionCandidate candidate : S3K_BADNIK_PARENT_BATCH88_RECREATE_CLASSES) {
            RoundTripSweepResult result = RewindRoundTripHarness.probeClass(candidate.fqn());
            assertInstanceOf(RoundTripSweepResult.Passed.class, result,
                    candidate.fqn()
                            + " must round-trip as Passed via RewindRecreatable path (no codec); got: "
                            + result);
        }
    }

    @Test
    void s3kObjectParentBatch89ClassesImplementRewindRecreatable() {
        for (CodecDeletionCandidate candidate : S3K_OBJECT_PARENT_BATCH89_RECREATE_CLASSES) {
            Class<?> cls = loadClass(candidate.fqn());
            assertTrue(RewindRecreatable.class.isAssignableFrom(cls),
                    candidate.fqn() + " must implement RewindRecreatable after S3K object parent batch 89");
        }
    }

    @Test
    void s3kObjectParentBatch89ClassesHaveNoRegisteredCodec() {
        for (CodecDeletionCandidate candidate : S3K_OBJECT_PARENT_BATCH89_RECREATE_CLASSES) {
            assertFalse(hasRegisteredDynamicCodec(candidate.fqn(), candidate.gameId()),
                    candidate.fqn()
                            + " must restore through S3K object parent batch 89 generic recreate, not a dynamic codec");
        }
    }

    @Test
    void s3kObjectParentBatch89ClassesRoundTripPassedWithoutCodec() {
        for (CodecDeletionCandidate candidate : S3K_OBJECT_PARENT_BATCH89_RECREATE_CLASSES) {
            RoundTripSweepResult result = RewindRoundTripHarness.probeClass(candidate.fqn());
            assertInstanceOf(RoundTripSweepResult.Passed.class, result,
                    candidate.fqn()
                            + " must round-trip as Passed via RewindRecreatable path (no codec); got: "
                            + result);
        }
    }

    @Test
    void s3kObjectParentBatch90ClassesImplementRewindRecreatable() {
        for (CodecDeletionCandidate candidate : S3K_OBJECT_PARENT_BATCH90_RECREATE_CLASSES) {
            Class<?> cls = loadClass(candidate.fqn());
            assertTrue(RewindRecreatable.class.isAssignableFrom(cls),
                    candidate.fqn() + " must implement RewindRecreatable after S3K object parent batch 90");
        }
    }

    @Test
    void s3kObjectParentBatch90ClassesHaveNoRegisteredCodec() {
        for (CodecDeletionCandidate candidate : S3K_OBJECT_PARENT_BATCH90_RECREATE_CLASSES) {
            assertFalse(hasRegisteredDynamicCodec(candidate.fqn(), candidate.gameId()),
                    candidate.fqn()
                            + " must restore through S3K object parent batch 90 generic recreate, not a dynamic codec");
        }
    }

    @Test
    void s3kObjectParentBatch90ClassesRoundTripPassedWithoutCodec() {
        for (CodecDeletionCandidate candidate : S3K_OBJECT_PARENT_BATCH90_RECREATE_CLASSES) {
            RoundTripSweepResult result = RewindRoundTripHarness.probeClass(candidate.fqn());
            assertInstanceOf(RoundTripSweepResult.Passed.class, result,
                    candidate.fqn()
                            + " must round-trip as Passed via RewindRecreatable path (no codec); got: "
                            + result);
        }
    }

    @Test
    void s3kObjectParentBatch91ClassesImplementRewindRecreatable() {
        for (CodecDeletionCandidate candidate : S3K_OBJECT_PARENT_BATCH91_RECREATE_CLASSES) {
            Class<?> cls = loadClass(candidate.fqn());
            assertTrue(RewindRecreatable.class.isAssignableFrom(cls),
                    candidate.fqn() + " must implement RewindRecreatable after S3K object parent batch 91");
        }
    }

    @Test
    void s3kObjectParentBatch91ClassesHaveNoRegisteredCodec() {
        for (CodecDeletionCandidate candidate : S3K_OBJECT_PARENT_BATCH91_RECREATE_CLASSES) {
            assertFalse(hasRegisteredDynamicCodec(candidate.fqn(), candidate.gameId()),
                    candidate.fqn()
                            + " must restore through S3K object parent batch 91 generic recreate, not a dynamic codec");
        }
    }

    @Test
    void s3kObjectParentBatch91ClassesRoundTripPassedWithoutCodec() {
        for (CodecDeletionCandidate candidate : S3K_OBJECT_PARENT_BATCH91_RECREATE_CLASSES) {
            RoundTripSweepResult result = RewindRoundTripHarness.probeClass(candidate.fqn());
            assertInstanceOf(RoundTripSweepResult.Passed.class, result,
                    candidate.fqn()
                            + " must round-trip as Passed via RewindRecreatable path (no codec); got: "
                            + result);
        }
    }

    @Test
    void s3kObjectParentBatch92ClassesImplementRewindRecreatable() {
        for (CodecDeletionCandidate candidate : S3K_OBJECT_PARENT_BATCH92_RECREATE_CLASSES) {
            Class<?> cls = loadClass(candidate.fqn());
            assertTrue(RewindRecreatable.class.isAssignableFrom(cls),
                    candidate.fqn() + " must implement RewindRecreatable after S3K object parent batch 92");
        }
    }

    @Test
    void s3kObjectParentBatch92ClassesHaveNoRegisteredCodec() {
        for (CodecDeletionCandidate candidate : S3K_OBJECT_PARENT_BATCH92_RECREATE_CLASSES) {
            assertFalse(hasRegisteredDynamicCodec(candidate.fqn(), candidate.gameId()),
                    candidate.fqn()
                            + " must restore through S3K object parent batch 92 generic recreate, not a dynamic codec");
        }
    }

    @Test
    void s3kObjectParentBatch92ClassesRoundTripPassedWithoutCodec() {
        for (CodecDeletionCandidate candidate : S3K_OBJECT_PARENT_BATCH92_RECREATE_CLASSES) {
            RoundTripSweepResult result = RewindRoundTripHarness.probeClass(candidate.fqn());
            assertInstanceOf(RoundTripSweepResult.Passed.class, result,
                    candidate.fqn()
                            + " must round-trip as Passed via RewindRecreatable path (no codec); got: "
                            + result);
        }
    }

    @Test
    void s3kIczDebrisBatch93ClassesImplementRewindRecreatable() {
        for (CodecDeletionCandidate candidate : S3K_ICZ_DEBRIS_BATCH93_RECREATE_CLASSES) {
            Class<?> cls = loadClass(candidate.fqn());
            assertTrue(RewindRecreatable.class.isAssignableFrom(cls),
                    candidate.fqn() + " must implement RewindRecreatable after S3K ICZ debris batch 93");
        }
    }

    @Test
    void s3kIczDebrisBatch93ClassesHaveNoRegisteredCodec() {
        for (CodecDeletionCandidate candidate : S3K_ICZ_DEBRIS_BATCH93_RECREATE_CLASSES) {
            assertFalse(hasRegisteredDynamicCodec(candidate.fqn(), candidate.gameId()),
                    candidate.fqn()
                            + " must restore through S3K ICZ debris batch 93 generic recreate, not a dynamic codec");
        }
    }

    @Test
    void s3kIczDebrisBatch93ClassesRoundTripPassedWithoutCodec() {
        for (CodecDeletionCandidate candidate : S3K_ICZ_DEBRIS_BATCH93_RECREATE_CLASSES) {
            RoundTripSweepResult result = RewindRoundTripHarness.probeClass(candidate.fqn());
            assertInstanceOf(RoundTripSweepResult.Passed.class, result,
                    candidate.fqn()
                            + " must round-trip as Passed via RewindRecreatable path (no codec); got: "
                            + result);
        }
    }

    @Test
    void s3kDynamicChildBatch94ClassesImplementRewindRecreatable() {
        for (CodecDeletionCandidate candidate : S3K_DYNAMIC_CHILD_BATCH94_RECREATE_CLASSES) {
            Class<?> cls = loadClass(candidate.fqn());
            assertTrue(RewindRecreatable.class.isAssignableFrom(cls),
                    candidate.fqn() + " must implement RewindRecreatable after S3K dynamic child batch 94");
        }
    }

    @Test
    void s3kDynamicChildBatch94ClassesHaveNoRegisteredCodec() {
        for (CodecDeletionCandidate candidate : S3K_DYNAMIC_CHILD_BATCH94_RECREATE_CLASSES) {
            assertFalse(hasRegisteredDynamicCodec(candidate.fqn(), candidate.gameId()),
                    candidate.fqn()
                            + " must restore through S3K dynamic child batch 94 generic recreate, not a dynamic codec");
        }
    }

    @Test
    void s3kDynamicChildBatch94ClassesRoundTripPassedWithoutCodec() {
        for (CodecDeletionCandidate candidate : S3K_DYNAMIC_CHILD_BATCH94_RECREATE_CLASSES) {
            RoundTripSweepResult result = RewindRoundTripHarness.probeClass(candidate.fqn());
            assertInstanceOf(RoundTripSweepResult.Passed.class, result,
                    candidate.fqn()
                            + " must round-trip as Passed via RewindRecreatable path (no codec); got: "
                            + result);
        }
    }

    @Test
    void s3kDynamicChildBatch95ClassesImplementRewindRecreatable() {
        for (CodecDeletionCandidate candidate : S3K_DYNAMIC_CHILD_BATCH95_RECREATE_CLASSES) {
            Class<?> cls = loadClass(candidate.fqn());
            assertTrue(RewindRecreatable.class.isAssignableFrom(cls),
                    candidate.fqn() + " must implement RewindRecreatable after S3K dynamic child batch 95");
        }
    }

    @Test
    void s3kDynamicChildBatch95ClassesHaveNoRegisteredCodec() {
        for (CodecDeletionCandidate candidate : S3K_DYNAMIC_CHILD_BATCH95_RECREATE_CLASSES) {
            assertFalse(hasRegisteredDynamicCodec(candidate.fqn(), candidate.gameId()),
                    candidate.fqn()
                            + " must restore through S3K dynamic child batch 95 generic recreate, not a dynamic codec");
        }
    }

    @Test
    void s3kDynamicChildBatch95ClassesRoundTripPassedWithoutCodec() {
        for (CodecDeletionCandidate candidate : S3K_DYNAMIC_CHILD_BATCH95_RECREATE_CLASSES) {
            RoundTripSweepResult result = RewindRoundTripHarness.probeClass(candidate.fqn());
            assertInstanceOf(RoundTripSweepResult.Passed.class, result,
                    candidate.fqn()
                            + " must round-trip as Passed via RewindRecreatable path (no codec); got: "
                            + result);
        }
    }

    @Test
    void s3kWaterWallChildBatch96ClassesImplementRewindRecreatable() {
        for (CodecDeletionCandidate candidate : S3K_WATER_WALL_CHILD_BATCH96_RECREATE_CLASSES) {
            Class<?> cls = loadClass(candidate.fqn());
            assertTrue(RewindRecreatable.class.isAssignableFrom(cls),
                    candidate.fqn() + " must implement RewindRecreatable after S3K water-wall child batch 96");
        }
    }

    @Test
    void s3kWaterWallChildBatch96ClassesHaveNoRegisteredCodec() {
        for (CodecDeletionCandidate candidate : S3K_WATER_WALL_CHILD_BATCH96_RECREATE_CLASSES) {
            assertFalse(hasRegisteredDynamicCodec(candidate.fqn(), candidate.gameId()),
                    candidate.fqn()
                            + " must restore through S3K water-wall child batch 96 generic recreate, not a dynamic codec");
        }
    }

    @Test
    void s3kWaterWallChildBatch96ClassesRoundTripPassedWithoutCodec() {
        for (CodecDeletionCandidate candidate : S3K_WATER_WALL_CHILD_BATCH96_RECREATE_CLASSES) {
            RoundTripSweepResult result = RewindRoundTripHarness.probeClass(candidate.fqn());
            assertInstanceOf(RoundTripSweepResult.Passed.class, result,
                    candidate.fqn()
                            + " must round-trip as Passed via RewindRecreatable path (no codec); got: "
                            + result);
        }
    }

    @Test
    void s3kParticleChildBatch97ClassesImplementRewindRecreatable() {
        for (CodecDeletionCandidate candidate : S3K_PARTICLE_CHILD_BATCH97_RECREATE_CLASSES) {
            Class<?> cls = loadClass(candidate.fqn());
            assertTrue(RewindRecreatable.class.isAssignableFrom(cls),
                    candidate.fqn() + " must implement RewindRecreatable after S3K particle child batch 97");
        }
    }

    @Test
    void s3kParticleChildBatch97ClassesHaveNoRegisteredCodec() {
        for (CodecDeletionCandidate candidate : S3K_PARTICLE_CHILD_BATCH97_RECREATE_CLASSES) {
            assertFalse(hasRegisteredDynamicCodec(candidate.fqn(), candidate.gameId()),
                    candidate.fqn()
                            + " must restore through S3K particle child batch 97 generic recreate, not a dynamic codec");
        }
    }

    @Test
    void s3kParticleChildBatch97ClassesRoundTripPassedWithoutCodec() {
        for (CodecDeletionCandidate candidate : S3K_PARTICLE_CHILD_BATCH97_RECREATE_CLASSES) {
            RoundTripSweepResult result = RewindRoundTripHarness.probeClass(candidate.fqn());
            assertInstanceOf(RoundTripSweepResult.Passed.class, result,
                    candidate.fqn()
                            + " must round-trip as Passed via RewindRecreatable path (no codec); got: "
                            + result);
        }
    }

    @Test
    void s3kTurboSpikerParticleBatch98ClassesImplementRewindRecreatable() {
        for (CodecDeletionCandidate candidate : S3K_TURBO_SPIKER_PARTICLE_BATCH98_RECREATE_CLASSES) {
            Class<?> cls = loadClass(candidate.fqn());
            assertTrue(RewindRecreatable.class.isAssignableFrom(cls),
                    candidate.fqn() + " must implement RewindRecreatable after S3K Turbo Spiker batch 98");
        }
    }

    @Test
    void s3kTurboSpikerParticleBatch98ClassesHaveNoRegisteredCodec() {
        for (CodecDeletionCandidate candidate : S3K_TURBO_SPIKER_PARTICLE_BATCH98_RECREATE_CLASSES) {
            assertFalse(hasRegisteredDynamicCodec(candidate.fqn(), candidate.gameId()),
                    candidate.fqn()
                            + " must restore through S3K Turbo Spiker particle batch 98 generic recreate, not a dynamic codec");
        }
    }

    @Test
    void s3kTurboSpikerParticleBatch98ClassesRoundTripPassedWithoutCodec() {
        for (CodecDeletionCandidate candidate : S3K_TURBO_SPIKER_PARTICLE_BATCH98_RECREATE_CLASSES) {
            RoundTripSweepResult result = RewindRoundTripHarness.probeClass(candidate.fqn());
            assertInstanceOf(RoundTripSweepResult.Passed.class, result,
                    candidate.fqn()
                            + " must round-trip as Passed via RewindRecreatable path (no codec); got: "
                            + result);
        }
    }

    @Test
    void s3kMhzTreeChipBatch99ClassesImplementRewindRecreatable() {
        for (CodecDeletionCandidate candidate : S3K_MHZ_TREE_CHIP_BATCH99_RECREATE_CLASSES) {
            Class<?> cls = loadClass(candidate.fqn());
            assertTrue(RewindRecreatable.class.isAssignableFrom(cls),
                    candidate.fqn() + " must implement RewindRecreatable after S3K MHZ tree-chip batch 99");
        }
    }

    @Test
    void s3kMhzTreeChipBatch99ClassesHaveNoRegisteredCodec() {
        for (CodecDeletionCandidate candidate : S3K_MHZ_TREE_CHIP_BATCH99_RECREATE_CLASSES) {
            assertFalse(hasRegisteredDynamicCodec(candidate.fqn(), candidate.gameId()),
                    candidate.fqn()
                            + " must restore through S3K MHZ tree-chip batch 99 generic recreate, not a dynamic codec");
        }
    }

    @Test
    void s3kMhzTreeChipBatch99ClassesRoundTripPassedWithoutCodec() {
        for (CodecDeletionCandidate candidate : S3K_MHZ_TREE_CHIP_BATCH99_RECREATE_CLASSES) {
            RoundTripSweepResult result = RewindRoundTripHarness.probeClass(candidate.fqn());
            assertInstanceOf(RoundTripSweepResult.Passed.class, result,
                    candidate.fqn()
                            + " must round-trip as Passed via RewindRecreatable path (no codec); got: "
                            + result);
        }
    }

    @Test
    void s3kMhz2LeafParticleBatch100ClassesImplementRewindRecreatable() {
        for (CodecDeletionCandidate candidate : S3K_MHZ2_LEAF_PARTICLE_BATCH100_RECREATE_CLASSES) {
            Class<?> cls = loadClass(candidate.fqn());
            assertTrue(RewindRecreatable.class.isAssignableFrom(cls),
                    candidate.fqn() + " must implement RewindRecreatable after S3K MHZ2 leaf batch 100");
        }
    }

    @Test
    void s3kMhz2LeafParticleBatch100ClassesHaveNoRegisteredCodec() {
        for (CodecDeletionCandidate candidate : S3K_MHZ2_LEAF_PARTICLE_BATCH100_RECREATE_CLASSES) {
            assertFalse(hasRegisteredDynamicCodec(candidate.fqn(), candidate.gameId()),
                    candidate.fqn()
                            + " must restore through S3K MHZ2 leaf batch 100 generic recreate, not a dynamic codec");
        }
    }

    @Test
    void s3kMhz2LeafParticleBatch100ClassesRoundTripPassedWithoutCodec() {
        for (CodecDeletionCandidate candidate : S3K_MHZ2_LEAF_PARTICLE_BATCH100_RECREATE_CLASSES) {
            RoundTripSweepResult result = RewindRoundTripHarness.probeClass(candidate.fqn());
            assertInstanceOf(RoundTripSweepResult.Passed.class, result,
                    candidate.fqn()
                            + " must round-trip as Passed via RewindRecreatable path (no codec); got: "
                            + result);
        }
    }

    @Test
    void s3kIczFrostPuffBatch101ClassesImplementRewindRecreatable() {
        for (CodecDeletionCandidate candidate : S3K_ICZ_FROST_PUFF_BATCH101_RECREATE_CLASSES) {
            Class<?> cls = loadClass(candidate.fqn());
            assertTrue(RewindRecreatable.class.isAssignableFrom(cls),
                    candidate.fqn() + " must implement RewindRecreatable after S3K ICZ frost puff batch 101");
        }
    }

    @Test
    void s3kIczFrostPuffBatch101ClassesHaveNoRegisteredCodec() {
        for (CodecDeletionCandidate candidate : S3K_ICZ_FROST_PUFF_BATCH101_RECREATE_CLASSES) {
            assertFalse(hasRegisteredDynamicCodec(candidate.fqn(), candidate.gameId()),
                    candidate.fqn()
                            + " must restore through S3K ICZ frost puff batch 101 generic recreate, not a dynamic codec");
        }
    }

    @Test
    void s3kIczFrostPuffBatch101ClassesRoundTripPassedWithoutCodec() {
        for (CodecDeletionCandidate candidate : S3K_ICZ_FROST_PUFF_BATCH101_RECREATE_CLASSES) {
            RoundTripSweepResult result = RewindRoundTripHarness.probeClass(candidate.fqn());
            assertInstanceOf(RoundTripSweepResult.Passed.class, result,
                    candidate.fqn()
                            + " must round-trip as Passed via RewindRecreatable path (no codec); got: "
                            + result);
        }
    }

    @Test
    void s3kAizDrawBridgeBatch102ClassesImplementRewindRecreatable() {
        for (CodecDeletionCandidate candidate : S3K_AIZ_DRAW_BRIDGE_BATCH102_RECREATE_CLASSES) {
            Class<?> cls = loadClass(candidate.fqn());
            assertTrue(RewindRecreatable.class.isAssignableFrom(cls),
                    candidate.fqn() + " must implement RewindRecreatable after S3K AIZ draw bridge batch 102");
        }
    }

    @Test
    void s3kAizDrawBridgeBatch102ClassesHaveNoRegisteredCodec() {
        for (CodecDeletionCandidate candidate : S3K_AIZ_DRAW_BRIDGE_BATCH102_RECREATE_CLASSES) {
            assertFalse(hasRegisteredDynamicCodec(candidate.fqn(), candidate.gameId()),
                    candidate.fqn()
                            + " must restore through S3K AIZ draw bridge batch 102 generic recreate, not a dynamic codec");
        }
    }

    @Test
    void s3kAizDrawBridgeBatch102ClassesRoundTripPassedWithoutCodec() {
        for (CodecDeletionCandidate candidate : S3K_AIZ_DRAW_BRIDGE_BATCH102_RECREATE_CLASSES) {
            RoundTripSweepResult result = RewindRoundTripHarness.probeClass(candidate.fqn());
            assertInstanceOf(RoundTripSweepResult.Passed.class, result,
                    candidate.fqn()
                            + " must round-trip as Passed via RewindRecreatable path (no codec); got: "
                            + result);
        }
    }

    @Test
    void s3kAizLrzRockBatch218ClassesImplementRewindRecreatable() {
        for (CodecDeletionCandidate candidate : S3K_AIZ_LRZ_ROCK_BATCH218_RECREATE_CLASSES) {
            Class<?> cls = loadClass(candidate.fqn());
            assertTrue(RewindRecreatable.class.isAssignableFrom(cls),
                    candidate.fqn() + " must implement RewindRecreatable after S3K AIZ/LRZ rock batch 218");
        }
    }

    @Test
    void s3kAizLrzRockBatch218ClassesHaveNoRegisteredCodec() {
        for (CodecDeletionCandidate candidate : S3K_AIZ_LRZ_ROCK_BATCH218_RECREATE_CLASSES) {
            assertFalse(hasRegisteredDynamicCodec(candidate.fqn(), candidate.gameId()),
                    candidate.fqn()
                            + " must restore through S3K AIZ/LRZ rock batch 218 generic recreate, not a dynamic codec");
        }
    }

    @Test
    void s3kAizLrzRockBatch218ClassesRoundTripPassedWithoutCodec() {
        for (CodecDeletionCandidate candidate : S3K_AIZ_LRZ_ROCK_BATCH218_RECREATE_CLASSES) {
            RoundTripSweepResult result = RewindRoundTripHarness.probeClass(candidate.fqn());
            assertInstanceOf(RoundTripSweepResult.Passed.class, result,
                    candidate.fqn()
                            + " must round-trip as Passed via RewindRecreatable path (no codec); got: "
                            + result);
        }
    }

    @Test
    void s3kSparkleChildBatch103ClassesImplementRewindRecreatable() {
        for (CodecDeletionCandidate candidate : S3K_SPARKLE_CHILD_BATCH103_RECREATE_CLASSES) {
            Class<?> cls = loadClass(candidate.fqn());
            assertTrue(RewindRecreatable.class.isAssignableFrom(cls),
                    candidate.fqn() + " must implement RewindRecreatable after S3K Sparkle child batch 103");
        }
    }

    @Test
    void s3kSparkleChildBatch103ClassesHaveNoRegisteredCodec() {
        for (CodecDeletionCandidate candidate : S3K_SPARKLE_CHILD_BATCH103_RECREATE_CLASSES) {
            assertFalse(hasRegisteredDynamicCodec(candidate.fqn(), candidate.gameId()),
                    candidate.fqn()
                            + " must restore through S3K Sparkle child batch 103 generic recreate, not a dynamic codec");
        }
    }

    @Test
    void s3kSparkleChildBatch103ClassesRoundTripPassedWithoutCodec() {
        for (CodecDeletionCandidate candidate : S3K_SPARKLE_CHILD_BATCH103_RECREATE_CLASSES) {
            RoundTripSweepResult result = RewindRoundTripHarness.probeClass(candidate.fqn());
            assertInstanceOf(RoundTripSweepResult.Passed.class, result,
                    candidate.fqn()
                            + " must round-trip as Passed via RewindRecreatable path (no codec); got: "
                            + result);
        }
    }

    @Test
    void s3kMhzSwingBarBatch104ClassesImplementRewindRecreatable() {
        for (CodecDeletionCandidate candidate : S3K_MHZ_SWING_BAR_BATCH104_RECREATE_CLASSES) {
            Class<?> cls = loadClass(candidate.fqn());
            assertTrue(RewindRecreatable.class.isAssignableFrom(cls),
                    candidate.fqn() + " must implement RewindRecreatable after S3K MHZ swing-bar batch 104");
        }
    }

    @Test
    void s3kMhzSwingBarBatch104ClassesHaveNoRegisteredCodec() {
        for (CodecDeletionCandidate candidate : S3K_MHZ_SWING_BAR_BATCH104_RECREATE_CLASSES) {
            assertFalse(hasRegisteredDynamicCodec(candidate.fqn(), candidate.gameId()),
                    candidate.fqn()
                            + " must restore through S3K MHZ swing-bar batch 104 generic recreate, not a dynamic codec");
        }
    }

    @Test
    void s3kMhzSwingBarBatch104ClassesRoundTripPassedWithoutCodec() {
        for (CodecDeletionCandidate candidate : S3K_MHZ_SWING_BAR_BATCH104_RECREATE_CLASSES) {
            RoundTripSweepResult result = RewindRoundTripHarness.probeClass(candidate.fqn());
            assertInstanceOf(RoundTripSweepResult.Passed.class, result,
                    candidate.fqn()
                            + " must round-trip as Passed via RewindRecreatable path (no codec); got: "
                            + result);
        }
    }

    @Test
    void s3kMhzMechanismBatch105ClassesImplementRewindRecreatable() {
        for (CodecDeletionCandidate candidate : S3K_MHZ_MECHANISM_BATCH105_RECREATE_CLASSES) {
            Class<?> cls = loadClass(candidate.fqn());
            assertTrue(RewindRecreatable.class.isAssignableFrom(cls),
                    candidate.fqn() + " must implement RewindRecreatable after S3K MHZ mechanism batch 105");
        }
    }

    @Test
    void s3kMhzMechanismBatch105ClassesHaveNoRegisteredCodec() {
        for (CodecDeletionCandidate candidate : S3K_MHZ_MECHANISM_BATCH105_RECREATE_CLASSES) {
            assertFalse(hasRegisteredDynamicCodec(candidate.fqn(), candidate.gameId()),
                    candidate.fqn()
                            + " must restore through S3K MHZ mechanism batch 105 generic recreate, not a dynamic codec");
        }
    }

    @Test
    void s3kMhzMechanismBatch105ClassesRoundTripPassedWithoutCodec() {
        for (CodecDeletionCandidate candidate : S3K_MHZ_MECHANISM_BATCH105_RECREATE_CLASSES) {
            RoundTripSweepResult result = RewindRoundTripHarness.probeClass(candidate.fqn());
            assertInstanceOf(RoundTripSweepResult.Passed.class, result,
                    candidate.fqn()
                            + " must round-trip as Passed via RewindRecreatable path (no codec); got: "
                            + result);
        }
    }

    @Test
    void s3kMechanismBatch106ClassesImplementRewindRecreatable() {
        for (CodecDeletionCandidate candidate : S3K_MECHANISM_BATCH106_RECREATE_CLASSES) {
            Class<?> cls = loadClass(candidate.fqn());
            assertTrue(RewindRecreatable.class.isAssignableFrom(cls),
                    candidate.fqn() + " must implement RewindRecreatable after S3K mechanism batch 106");
        }
    }

    @Test
    void s3kMechanismBatch106ClassesHaveNoRegisteredCodec() {
        for (CodecDeletionCandidate candidate : S3K_MECHANISM_BATCH106_RECREATE_CLASSES) {
            assertFalse(hasRegisteredDynamicCodec(candidate.fqn(), candidate.gameId()),
                    candidate.fqn()
                            + " must restore through S3K mechanism batch 106 generic recreate, not a dynamic codec");
        }
    }

    @Test
    void s3kMechanismBatch106ClassesRoundTripPassedWithoutCodec() {
        for (CodecDeletionCandidate candidate : S3K_MECHANISM_BATCH106_RECREATE_CLASSES) {
            RoundTripSweepResult result = RewindRoundTripHarness.probeClass(candidate.fqn());
            assertInstanceOf(RoundTripSweepResult.Passed.class, result,
                    candidate.fqn()
                            + " must round-trip as Passed via RewindRecreatable path (no codec); got: "
                            + result);
        }
    }

    @Test
    void s3kLbzMechanismBatch107ClassesImplementRewindRecreatable() {
        for (CodecDeletionCandidate candidate : S3K_LBZ_MECHANISM_BATCH107_RECREATE_CLASSES) {
            Class<?> cls = loadClass(candidate.fqn());
            assertTrue(RewindRecreatable.class.isAssignableFrom(cls),
                    candidate.fqn() + " must implement RewindRecreatable after S3K LBZ mechanism batch 107");
        }
    }

    @Test
    void s3kLbzMechanismBatch107ClassesHaveNoRegisteredCodec() {
        for (CodecDeletionCandidate candidate : S3K_LBZ_MECHANISM_BATCH107_RECREATE_CLASSES) {
            assertFalse(hasRegisteredDynamicCodec(candidate.fqn(), candidate.gameId()),
                    candidate.fqn()
                            + " must restore through S3K LBZ mechanism batch 107 generic recreate, not a dynamic codec");
        }
    }

    @Test
    void s3kLbzMechanismBatch107ClassesRoundTripPassedWithoutCodec() {
        for (CodecDeletionCandidate candidate : S3K_LBZ_MECHANISM_BATCH107_RECREATE_CLASSES) {
            RoundTripSweepResult result = RewindRoundTripHarness.probeClass(candidate.fqn());
            assertInstanceOf(RoundTripSweepResult.Passed.class, result,
                    candidate.fqn()
                            + " must round-trip as Passed via RewindRecreatable path (no codec); got: "
                            + result);
        }
    }

    @Test
    void s3kMhzMgzMechanismBatch108ClassesImplementRewindRecreatable() {
        for (CodecDeletionCandidate candidate : S3K_MHZ_MGZ_MECHANISM_BATCH108_RECREATE_CLASSES) {
            Class<?> cls = loadClass(candidate.fqn());
            assertTrue(RewindRecreatable.class.isAssignableFrom(cls),
                    candidate.fqn() + " must implement RewindRecreatable after S3K batch 108");
        }
    }

    @Test
    void s3kMhzMgzMechanismBatch108ClassesHaveNoRegisteredCodec() {
        for (CodecDeletionCandidate candidate : S3K_MHZ_MGZ_MECHANISM_BATCH108_RECREATE_CLASSES) {
            assertFalse(hasRegisteredDynamicCodec(candidate.fqn(), candidate.gameId()),
                    candidate.fqn()
                            + " must restore through S3K batch 108 generic recreate, not a dynamic codec");
        }
    }

    @Test
    void s3kMhzMgzMechanismBatch108ClassesRoundTripPassedWithoutCodec() {
        for (CodecDeletionCandidate candidate : S3K_MHZ_MGZ_MECHANISM_BATCH108_RECREATE_CLASSES) {
            RoundTripSweepResult result = RewindRoundTripHarness.probeClass(candidate.fqn());
            assertInstanceOf(RoundTripSweepResult.Passed.class, result,
                    candidate.fqn()
                            + " must round-trip as Passed via RewindRecreatable path (no codec); got: "
                            + result);
        }
    }

    @Test
    void s3kCorkeyNozzleGraphBatch109ClassesImplementRewindRecreatable() {
        for (CodecDeletionCandidate candidate : S3K_CORKEY_NOZZLE_GRAPH_BATCH109_RECREATE_CLASSES) {
            Class<?> cls = loadClass(candidate.fqn());
            assertTrue(RewindRecreatable.class.isAssignableFrom(cls),
                    candidate.fqn() + " must implement RewindRecreatable after S3K batch 109");
        }
    }

    @Test
    void s3kCorkeyNozzleGraphBatch109ClassesHaveNoRegisteredCodec() {
        for (CodecDeletionCandidate candidate : S3K_CORKEY_NOZZLE_GRAPH_BATCH109_RECREATE_CLASSES) {
            assertFalse(hasRegisteredDynamicCodec(candidate.fqn(), candidate.gameId()),
                    candidate.fqn()
                            + " must restore through S3K Corkey nozzle graph generic recreate, not a dynamic codec");
        }
    }

    // =====================================================================
    // S3K signpost stub graph batch: signpost-linked dynamic child
    // =====================================================================

    @Test
    void s3kSignpostStubGraphClassesAllImplementRewindRecreatable() {
        for (CodecDeletionCandidate candidate : S3K_SIGNPOST_STUB_GRAPH_DELETED_CODECS) {
            Class<?> cls = loadClass(candidate.fqn());
            assertTrue(RewindRecreatable.class.isAssignableFrom(cls),
                    candidate.fqn() + " must implement RewindRecreatable after S3K signpost stub graph batch");
        }
    }

    @Test
    void s3kSignpostStubGraphClassesHaveNoRegisteredCodec() {
        for (CodecDeletionCandidate candidate : S3K_SIGNPOST_STUB_GRAPH_DELETED_CODECS) {
            assertFalse(hasRegisteredDynamicCodec(candidate.fqn(), candidate.gameId()),
                    candidate.fqn()
                            + " must restore through S3K signpost stub graph generic recreate, not a dynamic codec");
        }
    }

    // =====================================================================
    // S3K LBZ1 cutscene graph batch: cutscene-parent-linked helpers
    // =====================================================================

    @Test
    void s3kLbz1CutsceneGraphClassesAllImplementRewindRecreatable() {
        for (CodecDeletionCandidate candidate : S3K_LBZ1_CUTSCENE_GRAPH_DELETED_CODECS) {
            Class<?> cls = loadClass(candidate.fqn());
            assertTrue(RewindRecreatable.class.isAssignableFrom(cls),
                    candidate.fqn() + " must implement RewindRecreatable after S3K LBZ1 cutscene graph batch");
        }
    }

    @Test
    void s3kLbz1CutsceneGraphClassesHaveNoRegisteredCodec() {
        for (CodecDeletionCandidate candidate : S3K_LBZ1_CUTSCENE_GRAPH_DELETED_CODECS) {
            assertFalse(hasRegisteredDynamicCodec(candidate.fqn(), candidate.gameId()),
                    candidate.fqn()
                            + " must restore through S3K LBZ1 cutscene graph generic recreate, not a dynamic codec");
        }
    }

    // =====================================================================
    // S3K MHZ cutscene graph batch: cutscene-parent-linked helpers
    // =====================================================================

    @Test
    void s3kMhzCutsceneGraphClassesAllImplementRewindRecreatable() {
        for (CodecDeletionCandidate candidate : S3K_MHZ_CUTSCENE_GRAPH_DELETED_CODECS) {
            Class<?> cls = loadClass(candidate.fqn());
            assertTrue(RewindRecreatable.class.isAssignableFrom(cls),
                    candidate.fqn() + " must implement RewindRecreatable after S3K MHZ cutscene graph batch");
        }
    }

    @Test
    void s3kMhzCutsceneGraphClassesHaveNoRegisteredCodec() {
        for (CodecDeletionCandidate candidate : S3K_MHZ_CUTSCENE_GRAPH_DELETED_CODECS) {
            assertFalse(hasRegisteredDynamicCodec(candidate.fqn(), candidate.gameId()),
                    candidate.fqn()
                            + " must restore through S3K MHZ cutscene graph generic recreate, not a dynamic codec");
        }
    }

    // =====================================================================
    // S3K MHZ miniboss flame graph batch: boss-parent-linked flame children
    // =====================================================================

    @Test
    void s3kMhzMinibossFlameGraphClassesAllImplementRewindRecreatable() {
        for (CodecDeletionCandidate candidate : S3K_MHZ_MINIBOSS_FLAME_GRAPH_DELETED_CODECS) {
            Class<?> cls = loadClass(candidate.fqn());
            assertTrue(RewindRecreatable.class.isAssignableFrom(cls),
                    candidate.fqn() + " must implement RewindRecreatable after S3K MHZ flame graph batch");
        }
    }

    @Test
    void s3kMhzMinibossFlameGraphClassesHaveNoRegisteredCodec() {
        for (CodecDeletionCandidate candidate : S3K_MHZ_MINIBOSS_FLAME_GRAPH_DELETED_CODECS) {
            assertFalse(hasRegisteredDynamicCodec(candidate.fqn(), candidate.gameId()),
                    candidate.fqn()
                            + " must restore through S3K MHZ flame graph generic recreate, not a dynamic codec");
        }
    }

    // =====================================================================
    // S3K MHZ miniboss escape-shard graph batch: boss-parent-linked shard children
    // =====================================================================

    @Test
    void s3kMhzMinibossEscapeShardGraphClassesAllImplementRewindRecreatable() {
        for (CodecDeletionCandidate candidate : S3K_MHZ_MINIBOSS_ESCAPE_SHARD_GRAPH_DELETED_CODECS) {
            Class<?> cls = loadClass(candidate.fqn());
            assertTrue(RewindRecreatable.class.isAssignableFrom(cls),
                    candidate.fqn()
                            + " must implement RewindRecreatable after S3K MHZ escape-shard graph batch");
        }
    }

    @Test
    void s3kMhzMinibossEscapeShardGraphClassesHaveNoRegisteredCodec() {
        for (CodecDeletionCandidate candidate : S3K_MHZ_MINIBOSS_ESCAPE_SHARD_GRAPH_DELETED_CODECS) {
            assertFalse(hasRegisteredDynamicCodec(candidate.fqn(), candidate.gameId()),
                    candidate.fqn()
                            + " must restore through S3K MHZ escape-shard graph generic recreate, not a dynamic codec");
        }
    }

    // =====================================================================
    // S3K MHZ end-boss controller graph batch: invisible control helpers
    // =====================================================================

    @Test
    void s3kMhzEndBossControllerGraphClassesAllImplementRewindRecreatable() {
        for (CodecDeletionCandidate candidate : S3K_MHZ_END_BOSS_CONTROLLER_GRAPH_DELETED_CODECS) {
            Class<?> cls = loadClass(candidate.fqn());
            assertTrue(RewindRecreatable.class.isAssignableFrom(cls),
                    candidate.fqn()
                            + " must implement RewindRecreatable after S3K MHZ end-boss controller graph batch");
        }
    }

    @Test
    void s3kMhzEndBossControllerGraphClassesHaveNoRegisteredCodec() {
        for (CodecDeletionCandidate candidate : S3K_MHZ_END_BOSS_CONTROLLER_GRAPH_DELETED_CODECS) {
            assertFalse(hasRegisteredDynamicCodec(candidate.fqn(), candidate.gameId()),
                    candidate.fqn()
                            + " must restore through S3K MHZ end-boss controller generic recreate, not a dynamic codec");
        }
    }

    // =====================================================================
    // S3K nested hurtbox graph batch: parent-linked MGZ/ICZ hurt children
    // =====================================================================

    @Test
    void s3kNestedHurtboxGraphClassesAllImplementRewindRecreatable() {
        for (CodecDeletionCandidate candidate : S3K_NESTED_HURTBOX_GRAPH_DELETED_CODECS) {
            Class<?> cls = loadClass(candidate.fqn());
            assertTrue(RewindRecreatable.class.isAssignableFrom(cls),
                    candidate.fqn() + " must implement RewindRecreatable after S3K nested hurtbox graph batch");
        }
    }

    @Test
    void s3kNestedHurtboxGraphClassesHaveNoRegisteredCodec() {
        for (CodecDeletionCandidate candidate : S3K_NESTED_HURTBOX_GRAPH_DELETED_CODECS) {
            assertFalse(hasRegisteredDynamicCodec(candidate.fqn(), candidate.gameId()),
                    candidate.fqn()
                            + " must restore through S3K nested hurtbox graph generic recreate, not a dynamic codec");
        }
    }

    // =====================================================================
    // S3K cutscene Knuckles graph batch: AIZ rock and CNZ blocking wall
    // =====================================================================

    @Test
    void s3kCutsceneKnucklesGraphClassesAllImplementRewindRecreatable() {
        for (CodecDeletionCandidate candidate : S3K_CUTSCENE_KNUCKLES_GRAPH_DELETED_CODECS) {
            Class<?> cls = loadClass(candidate.fqn());
            assertTrue(RewindRecreatable.class.isAssignableFrom(cls),
                    candidate.fqn() + " must implement RewindRecreatable after S3K cutscene Knuckles graph batch");
        }
    }

    @Test
    void s3kCutsceneKnucklesGraphClassesHaveNoRegisteredCodec() {
        for (CodecDeletionCandidate candidate : S3K_CUTSCENE_KNUCKLES_GRAPH_DELETED_CODECS) {
            assertFalse(hasRegisteredDynamicCodec(candidate.fqn(), candidate.gameId()),
                    candidate.fqn()
                            + " must restore through S3K cutscene Knuckles graph generic recreate, not a dynamic codec");
        }
    }

    // =====================================================================
    // S3K Knuckles cutscene controller batch: standalone ObjectSpawn controllers
    // =====================================================================

    @Test
    void s3kKnucklesCutsceneControllersAllImplementRewindRecreatable() {
        for (CodecDeletionCandidate candidate : S3K_KNUCKLES_CUTSCENE_CONTROLLER_RECREATE_CLASSES) {
            Class<?> cls = loadClass(candidate.fqn());
            assertTrue(RewindRecreatable.class.isAssignableFrom(cls),
                    candidate.fqn()
                            + " must implement RewindRecreatable after S3K Knuckles cutscene controller coverage");
        }
    }

    @Test
    void s3kKnucklesCutsceneControllersHaveNoRegisteredCodec() {
        for (CodecDeletionCandidate candidate : S3K_KNUCKLES_CUTSCENE_CONTROLLER_RECREATE_CLASSES) {
            assertFalse(hasRegisteredDynamicCodec(candidate.fqn(), candidate.gameId()),
                    candidate.fqn()
                            + " must restore through S3K Knuckles cutscene controller generic recreate, "
                            + "not a dynamic codec");
        }
    }

    @Test
    void s3kKnucklesCutsceneControllersRoundTripPassedWithoutCodec() {
        for (CodecDeletionCandidate candidate : S3K_KNUCKLES_CUTSCENE_CONTROLLER_RECREATE_CLASSES) {
            RoundTripSweepResult result = RewindRoundTripHarness.probeClass(candidate.fqn());
            assertInstanceOf(RoundTripSweepResult.Passed.class, result,
                    candidate.fqn()
                            + " must round-trip as Passed via RewindRecreatable path (no codec); got: "
                            + result);
        }
    }

    // =====================================================================
    // S3K standalone cutscene controller batch: scalar-only cutscene controllers
    // =====================================================================

    @Test
    void s3kStandaloneCutsceneControllersAllImplementRewindRecreatable() {
        for (CodecDeletionCandidate candidate : S3K_STANDALONE_CUTSCENE_CONTROLLER_RECREATE_CLASSES) {
            Class<?> cls = loadClass(candidate.fqn());
            assertTrue(RewindRecreatable.class.isAssignableFrom(cls),
                    candidate.fqn()
                            + " must implement RewindRecreatable after S3K standalone cutscene coverage");
        }
    }

    @Test
    void s3kStandaloneCutsceneControllersHaveNoRegisteredCodec() {
        for (CodecDeletionCandidate candidate : S3K_STANDALONE_CUTSCENE_CONTROLLER_RECREATE_CLASSES) {
            assertFalse(hasRegisteredDynamicCodec(candidate.fqn(), candidate.gameId()),
                    candidate.fqn()
                            + " must restore through S3K standalone cutscene generic recreate, "
                            + "not a dynamic codec");
        }
    }

    @Test
    void s3kStandaloneCutsceneControllersRoundTripPassedWithoutCodec() {
        for (CodecDeletionCandidate candidate : S3K_STANDALONE_CUTSCENE_CONTROLLER_RECREATE_CLASSES) {
            RoundTripSweepResult result = RewindRoundTripHarness.probeClass(candidate.fqn());
            assertInstanceOf(RoundTripSweepResult.Passed.class, result,
                    candidate.fqn()
                            + " must round-trip as Passed via RewindRecreatable path (no codec); got: "
                            + result);
        }
    }

    // =====================================================================
    // S3K stage controller batch: zero-arg/spawn controller recreate coverage
    // =====================================================================

    @Test
    void s3kStageControllersAllImplementRewindRecreatable() {
        for (CodecDeletionCandidate candidate : S3K_STAGE_CONTROLLER_RECREATE_CLASSES) {
            Class<?> cls = loadClass(candidate.fqn());
            assertTrue(RewindRecreatable.class.isAssignableFrom(cls),
                    candidate.fqn()
                            + " must implement RewindRecreatable after S3K stage controller coverage");
        }
    }

    @Test
    void s3kStageControllersHaveNoRegisteredCodec() {
        for (CodecDeletionCandidate candidate : S3K_STAGE_CONTROLLER_RECREATE_CLASSES) {
            assertFalse(hasRegisteredDynamicCodec(candidate.fqn(), candidate.gameId()),
                    candidate.fqn()
                            + " must restore through S3K stage controller generic recreate, "
                            + "not a dynamic codec");
        }
    }

    @Test
    void s3kStageControllersRoundTripPassedWithoutCodec() {
        for (CodecDeletionCandidate candidate : S3K_STAGE_CONTROLLER_RECREATE_CLASSES) {
            RoundTripSweepResult result = RewindRoundTripHarness.probeClass(candidate.fqn());
            assertInstanceOf(RoundTripSweepResult.Passed.class, result,
                    candidate.fqn()
                            + " must round-trip as Passed via RewindRecreatable path (no codec); got: "
                            + result);
        }
    }

    // =====================================================================
    // S2 boss controller batch: scalar parent-controller recreate coverage
    // =====================================================================

    @Test
    void s2BossControllersImplementRewindRecreatable() {
        for (CodecDeletionCandidate candidate : S2_BOSS_CONTROLLER_RECREATE_CLASSES) {
            Class<?> cls = loadClass(candidate.fqn());
            assertTrue(RewindRecreatable.class.isAssignableFrom(cls),
                    candidate.fqn()
                            + " must implement RewindRecreatable after S2 boss controller coverage");
        }
    }

    @Test
    void s2BossControllersHaveNoRegisteredCodec() {
        for (CodecDeletionCandidate candidate : S2_BOSS_CONTROLLER_RECREATE_CLASSES) {
            assertFalse(hasRegisteredDynamicCodec(candidate.fqn(), candidate.gameId()),
                    candidate.fqn()
                            + " must restore through S2 boss controller generic recreate, not a dynamic codec");
        }
    }

    @Test
    void s2BossControllersRoundTripPassedWithoutCodec() {
        for (CodecDeletionCandidate candidate : S2_BOSS_CONTROLLER_RECREATE_CLASSES) {
            RoundTripSweepResult result = RewindRoundTripHarness.probeClass(candidate.fqn());
            assertInstanceOf(RoundTripSweepResult.Passed.class, result,
                    candidate.fqn()
                            + " must round-trip as Passed via RewindRecreatable path (no codec); got: "
                            + result);
        }
    }

    // =====================================================================
    // S1 Egg Prison button graph batch: placed button/body back-reference relink
    // =====================================================================

    @Test
    void s1EggPrisonButtonGraphClassesAllImplementRewindRecreatable() {
        for (CodecDeletionCandidate candidate : S1_EGG_PRISON_BUTTON_GRAPH_RECREATE_CLASSES) {
            Class<?> cls = loadClass(candidate.fqn());
            assertTrue(RewindRecreatable.class.isAssignableFrom(cls),
                    candidate.fqn() + " must implement RewindRecreatable after S1 Egg Prison button graph batch");
        }
    }

    @Test
    void s1EggPrisonButtonGraphClassesHaveNoRegisteredCodec() {
        for (CodecDeletionCandidate candidate : S1_EGG_PRISON_BUTTON_GRAPH_RECREATE_CLASSES) {
            assertFalse(hasRegisteredDynamicCodec(candidate.fqn(), candidate.gameId()),
                    candidate.fqn()
                            + " must restore through S1 Egg Prison button graph generic recreate, not a dynamic codec");
        }
    }

    @Test
    void s1EggPrisonButtonGraphClassesRoundTripPassedWithoutCodec() {
        for (CodecDeletionCandidate candidate : S1_EGG_PRISON_BUTTON_GRAPH_RECREATE_CLASSES) {
            RoundTripSweepResult result = RewindRoundTripHarness.probeClass(candidate.fqn());
            assertInstanceOf(RoundTripSweepResult.Passed.class, result,
                    candidate.fqn()
                            + " must round-trip as Passed via S1 Egg Prison button graph recreate; got: "
                            + result);
        }
    }

    // =====================================================================
    // S2 Egg Prison button graph batch: parent/button linked capsule graph
    // =====================================================================

    @Test
    void s2EggPrisonButtonGraphClassesAllImplementRewindRecreatable() {
        for (CodecDeletionCandidate candidate : S2_EGG_PRISON_BUTTON_GRAPH_DELETED_CODECS) {
            Class<?> cls = loadClass(candidate.fqn());
            assertTrue(RewindRecreatable.class.isAssignableFrom(cls),
                    candidate.fqn() + " must implement RewindRecreatable after S2 Egg Prison button graph batch");
        }
    }

    @Test
    void s2EggPrisonButtonGraphClassesHaveNoRegisteredCodec() {
        for (CodecDeletionCandidate candidate : S2_EGG_PRISON_BUTTON_GRAPH_DELETED_CODECS) {
            assertFalse(hasRegisteredDynamicCodec(candidate.fqn(), candidate.gameId()),
                    candidate.fqn()
                            + " must restore through S2 Egg Prison button graph generic recreate, not a dynamic codec");
        }
    }

    // =====================================================================
    // S2 OOZ burner flame graph batch: constructor-required platform relink
    // =====================================================================

    @Test
    void s2OozBurnerFlameGraphClassesAllImplementRewindRecreatable() {
        for (CodecDeletionCandidate candidate : S2_OOZ_BURNER_FLAME_GRAPH_DELETED_CODECS) {
            Class<?> cls = loadClass(candidate.fqn());
            assertTrue(RewindRecreatable.class.isAssignableFrom(cls),
                    candidate.fqn() + " must implement RewindRecreatable after S2 OOZ burner flame graph batch");
        }
    }

    @Test
    void s2OozBurnerFlameGraphClassesHaveNoRegisteredCodec() {
        for (CodecDeletionCandidate candidate : S2_OOZ_BURNER_FLAME_GRAPH_DELETED_CODECS) {
            assertFalse(hasRegisteredDynamicCodec(candidate.fqn(), candidate.gameId()),
                    candidate.fqn()
                            + " must restore through S2 OOZ burner flame graph generic recreate, not a dynamic codec");
        }
    }

    // =====================================================================
    // S2 ARZ arrow graph support: boss/eyes-linked arrow dynamic
    // =====================================================================

    @Test
    void s2ArzArrowGraphClassesImplementRewindRecreatable() {
        for (CodecDeletionCandidate candidate : S2_ARZ_ARROW_GRAPH_SUPPORT) {
            Class<?> cls = loadClass(candidate.fqn());
            assertTrue(RewindRecreatable.class.isAssignableFrom(cls),
                    candidate.fqn() + " must implement RewindRecreatable for ARZ arrow graph support");
        }
    }

    @Test
    void s2ArzArrowGraphClassesHaveNoRegisteredCodec() {
        assertFalse(hasRegisteredDynamicCodec(ARZBossArrow.class.getName(), GameId.S2),
                "ARZBossArrow must restore through S2 ARZ graph generic recreate, not a dynamic codec");
        assertFalse(hasRegisteredDynamicCodec(ARZBossEyes.class.getName(), GameId.S2),
                "ARZBossEyes graph support must not add a dynamic codec");
    }

    // =====================================================================
    // S2 DEZ bomb graph support: parent-dependent Death Egg Robot bomb dynamic
    // =====================================================================

    @Test
    void s2DezBombGraphClassesImplementRewindRecreatable() {
        for (CodecDeletionCandidate candidate : S2_DEZ_BOMB_GRAPH_DELETED_CODECS) {
            Class<?> cls = loadClass(candidate.fqn());
            assertTrue(RewindRecreatable.class.isAssignableFrom(cls),
                    candidate.fqn() + " must implement RewindRecreatable after S2 DEZ bomb graph deletion");
        }
    }

    @Test
    void s2DezBombGraphClassesHaveNoRegisteredCodec() {
        for (CodecDeletionCandidate candidate : S2_DEZ_BOMB_GRAPH_DELETED_CODECS) {
            assertFalse(hasRegisteredDynamicCodec(candidate.fqn(), candidate.gameId()),
                    candidate.fqn()
                            + " must restore through S2 DEZ bomb graph generic recreate, not a dynamic codec");
        }
    }

    @Test
    void s2DezEggmanGraphClassesImplementRewindRecreatable() {
        for (CodecDeletionCandidate candidate : S2_DEZ_EGGMAN_GRAPH_COVERED_CLASSES) {
            Class<?> cls = loadClass(candidate.fqn());
            assertTrue(RewindRecreatable.class.isAssignableFrom(cls),
                    candidate.fqn() + " must implement RewindRecreatable after S2 DEZ Eggman graph coverage");
        }
    }

    @Test
    void s2DezEggmanGraphClassesHaveNoRegisteredCodec() {
        for (CodecDeletionCandidate candidate : S2_DEZ_EGGMAN_GRAPH_COVERED_CLASSES) {
            assertFalse(hasRegisteredDynamicCodec(candidate.fqn(), candidate.gameId()),
                    candidate.fqn()
                            + " must restore through S2 DEZ Eggman graph generic recreate, not a dynamic codec");
        }
    }

    @Test
    void s1ScrapEggmanGraphClassesImplementRewindRecreatable() {
        for (CodecDeletionCandidate candidate : S1_SCRAP_EGGMAN_GRAPH_COVERED_CLASSES) {
            Class<?> cls = loadClass(candidate.fqn());
            assertTrue(RewindRecreatable.class.isAssignableFrom(cls),
                    candidate.fqn() + " must implement RewindRecreatable after S1 Scrap Eggman graph coverage");
        }
    }

    @Test
    void s1ScrapEggmanGraphClassesHaveNoRegisteredCodec() {
        for (CodecDeletionCandidate candidate : S1_SCRAP_EGGMAN_GRAPH_COVERED_CLASSES) {
            assertFalse(hasRegisteredDynamicCodec(candidate.fqn(), candidate.gameId()),
                    candidate.fqn()
                            + " must restore through S1 Scrap Eggman graph generic recreate, not a dynamic codec");
        }
    }

    @Test
    void s1ScrapEggmanGraphClassesRoundTripPassedWithoutCodec() {
        for (CodecDeletionCandidate candidate : S1_SCRAP_EGGMAN_GRAPH_COVERED_CLASSES) {
            RoundTripSweepResult result = RewindRoundTripHarness.probeClass(candidate.fqn());
            assertInstanceOf(RoundTripSweepResult.Passed.class, result,
                    candidate.fqn()
                            + " must round-trip as Passed via S1 Scrap Eggman graph recreate; got: "
                            + result);
        }
    }

    @Test
    void s1GargoyleFireballGraphClassesImplementRewindRecreatable() {
        for (CodecDeletionCandidate candidate : S1_GARGOYLE_FIREBALL_GRAPH_COVERED_CLASSES) {
            Class<?> cls = loadClass(candidate.fqn());
            assertTrue(RewindRecreatable.class.isAssignableFrom(cls),
                    candidate.fqn() + " must implement RewindRecreatable after S1 Gargoyle fireball graph coverage");
        }
    }

    @Test
    void s1GargoyleFireballGraphClassesHaveNoRegisteredCodec() {
        for (CodecDeletionCandidate candidate : S1_GARGOYLE_FIREBALL_GRAPH_COVERED_CLASSES) {
            assertFalse(hasRegisteredDynamicCodec(candidate.fqn(), candidate.gameId()),
                    candidate.fqn()
                            + " must restore through S1 Gargoyle fireball graph generic recreate, not a dynamic codec");
        }
    }

    @Test
    void s1JunctionChildClassImplementsRewindRecreatable() {
        for (CodecDeletionCandidate candidate : S1_JUNCTION_CHILD_RECREATE_CLASSES) {
            Class<?> cls = loadClass(candidate.fqn());
            assertTrue(RewindRecreatable.class.isAssignableFrom(cls),
                    candidate.fqn() + " must implement RewindRecreatable after S1 junction child coverage");
        }
    }

    @Test
    void s1JunctionChildClassHasNoRegisteredCodec() {
        for (CodecDeletionCandidate candidate : S1_JUNCTION_CHILD_RECREATE_CLASSES) {
            assertFalse(hasRegisteredDynamicCodec(candidate.fqn(), candidate.gameId()),
                    candidate.fqn()
                            + " must restore through S1 junction child generic recreate, not a dynamic codec");
        }
    }

    @Test
    void s1JunctionChildClassRoundTripsWithoutCodec() {
        for (CodecDeletionCandidate candidate : S1_JUNCTION_CHILD_RECREATE_CLASSES) {
            RoundTripSweepResult result = RewindRoundTripHarness.probeClass(candidate.fqn());
            assertInstanceOf(RoundTripSweepResult.Passed.class, result,
                    candidate.fqn()
                            + " must round-trip as Passed via RewindRecreatable path (no codec); got: "
                            + result);
        }
    }

    @Test
    void s1LavaWallGraphClassImplementsRewindRecreatable() {
        for (CodecDeletionCandidate candidate : S1_LAVA_WALL_GRAPH_RECREATE_CLASSES) {
            Class<?> cls = loadClass(candidate.fqn());
            assertTrue(RewindRecreatable.class.isAssignableFrom(cls),
                    candidate.fqn() + " must implement RewindRecreatable after S1 lava wall graph coverage");
        }
    }

    @Test
    void s1LavaWallGraphClassHasNoRegisteredCodec() {
        for (CodecDeletionCandidate candidate : S1_LAVA_WALL_GRAPH_RECREATE_CLASSES) {
            assertFalse(hasRegisteredDynamicCodec(candidate.fqn(), candidate.gameId()),
                    candidate.fqn()
                            + " must restore through S1 lava wall graph generic recreate, not a dynamic codec");
        }
    }

    @Test
    void s1LavaWallGraphClassRoundTripsWithoutCodec() {
        for (CodecDeletionCandidate candidate : S1_LAVA_WALL_GRAPH_RECREATE_CLASSES) {
            RoundTripSweepResult result = RewindRoundTripHarness.probeClass(candidate.fqn());
            assertInstanceOf(RoundTripSweepResult.Passed.class, result,
                    candidate.fqn()
                            + " must round-trip as Passed via RewindRecreatable path (no codec); got: "
                            + result);
        }
    }

    @Test
    void s1LavaGeyserGraphClassesImplementRewindRecreatable() {
        for (CodecDeletionCandidate candidate : S1_LAVA_GEYSER_GRAPH_RECREATE_CLASSES) {
            Class<?> cls = loadClass(candidate.fqn());
            assertTrue(RewindRecreatable.class.isAssignableFrom(cls),
                    candidate.fqn() + " must implement RewindRecreatable after S1 lava geyser graph coverage");
        }
    }

    @Test
    void s1LavaGeyserGraphClassesHaveNoRegisteredCodec() {
        for (CodecDeletionCandidate candidate : S1_LAVA_GEYSER_GRAPH_RECREATE_CLASSES) {
            assertFalse(hasRegisteredDynamicCodec(candidate.fqn(), candidate.gameId()),
                    candidate.fqn()
                            + " must restore through S1 lava geyser graph generic recreate, not a dynamic codec");
        }
    }

    @Test
    void s1LavaGeyserGraphClassesRoundTripWithoutCodec() {
        for (CodecDeletionCandidate candidate : S1_LAVA_GEYSER_GRAPH_RECREATE_CLASSES) {
            RoundTripSweepResult result = RewindRoundTripHarness.probeClass(candidate.fqn());
            assertInstanceOf(RoundTripSweepResult.Passed.class, result,
                    candidate.fqn()
                            + " must round-trip as Passed via RewindRecreatable path (no codec); got: "
                            + result);
        }
    }

    @Test
    void s1RuntimeSpawnRecreateClassesImplementRewindRecreatable() {
        for (CodecDeletionCandidate candidate : S1_RUNTIME_SPAWN_RECREATE_CLASSES) {
            Class<?> cls = loadClass(candidate.fqn());
            assertTrue(RewindRecreatable.class.isAssignableFrom(cls),
                    candidate.fqn() + " must implement RewindRecreatable after S1 runtime spawn recreate coverage");
        }
    }

    @Test
    void s1RuntimeSpawnRecreateClassesHaveNoRegisteredCodec() {
        for (CodecDeletionCandidate candidate : S1_RUNTIME_SPAWN_RECREATE_CLASSES) {
            assertFalse(hasRegisteredDynamicCodec(candidate.fqn(), candidate.gameId()),
                    candidate.fqn()
                            + " must restore through S1 runtime spawn generic recreate, not a dynamic codec");
        }
    }

    @Test
    void s1ScalarSpawnRecreateClassesImplementRewindRecreatable() {
        for (CodecDeletionCandidate candidate : S1_SCALAR_SPAWN_RECREATE_CLASSES) {
            Class<?> cls = loadClass(candidate.fqn());
            assertTrue(RewindRecreatable.class.isAssignableFrom(cls),
                    candidate.fqn() + " must implement RewindRecreatable after S1 scalar spawn coverage");
        }
    }

    @Test
    void s1ScalarSpawnRecreateClassesHaveNoRegisteredCodec() {
        for (CodecDeletionCandidate candidate : S1_SCALAR_SPAWN_RECREATE_CLASSES) {
            assertFalse(hasRegisteredDynamicCodec(candidate.fqn(), candidate.gameId()),
                    candidate.fqn()
                            + " must restore through S1 scalar spawn generic recreate, not a dynamic codec");
        }
    }

    @Test
    void s1EffectScalarRecreateClassesImplementRewindRecreatable() {
        for (CodecDeletionCandidate candidate : S1_EFFECT_SCALAR_RECREATE_CLASSES) {
            Class<?> cls = loadClass(candidate.fqn());
            assertTrue(RewindRecreatable.class.isAssignableFrom(cls),
                    candidate.fqn() + " must implement RewindRecreatable after S1 effect scalar coverage");
        }
    }

    @Test
    void s1EffectScalarRecreateClassesHaveNoRegisteredCodec() {
        for (CodecDeletionCandidate candidate : S1_EFFECT_SCALAR_RECREATE_CLASSES) {
            assertFalse(hasRegisteredDynamicCodec(candidate.fqn(), candidate.gameId()),
                    candidate.fqn()
                            + " must restore through S1 effect scalar generic recreate, not a dynamic codec");
        }
    }

    @Test
    void s1EffectScalarRecreateClassesRoundTripPassedWithoutCodec() {
        for (CodecDeletionCandidate candidate : S1_EFFECT_SCALAR_RECREATE_CLASSES) {
            RoundTripSweepResult result = RewindRoundTripHarness.probeClass(candidate.fqn());
            assertInstanceOf(RoundTripSweepResult.Passed.class, result,
                    candidate.fqn()
                            + " must round-trip as Passed via RewindRecreatable path (no codec); got: "
                            + result);
        }
    }

    @Test
    void s1BossFireScalarRecreateClassesImplementRewindRecreatable() {
        for (CodecDeletionCandidate candidate : S1_BOSS_FIRE_SCALAR_RECREATE_CLASSES) {
            Class<?> cls = loadClass(candidate.fqn());
            assertTrue(RewindRecreatable.class.isAssignableFrom(cls),
                    candidate.fqn() + " must implement RewindRecreatable after S1 boss-fire scalar coverage");
        }
    }

    @Test
    void s1BossFireScalarRecreateClassesHaveNoRegisteredCodec() {
        for (CodecDeletionCandidate candidate : S1_BOSS_FIRE_SCALAR_RECREATE_CLASSES) {
            assertFalse(hasRegisteredDynamicCodec(candidate.fqn(), candidate.gameId()),
                    candidate.fqn()
                            + " must restore through S1 boss-fire scalar generic recreate, not a dynamic codec");
        }
    }

    @Test
    void s1BossFireScalarRecreateClassesRoundTripPassedWithoutCodec() {
        for (CodecDeletionCandidate candidate : S1_BOSS_FIRE_SCALAR_RECREATE_CLASSES) {
            RoundTripSweepResult result = RewindRoundTripHarness.probeClass(candidate.fqn());
            assertInstanceOf(RoundTripSweepResult.Passed.class, result,
                    candidate.fqn()
                            + " must round-trip as Passed via RewindRecreatable path (no codec); got: "
                            + result);
        }
    }

    @Test
    void s1CollapsingFragmentsImplementRewindRecreatable() {
        for (CodecDeletionCandidate candidate : S1_COLLAPSING_FRAGMENT_RECREATE_CLASSES) {
            Class<?> cls = loadClass(candidate.fqn());
            assertTrue(RewindRecreatable.class.isAssignableFrom(cls),
                    candidate.fqn() + " must implement RewindRecreatable after S1 collapsing fragment coverage");
        }
    }

    @Test
    void s1CollapsingFragmentsHaveNoRegisteredCodec() {
        for (CodecDeletionCandidate candidate : S1_COLLAPSING_FRAGMENT_RECREATE_CLASSES) {
            assertFalse(hasRegisteredDynamicCodec(candidate.fqn(), candidate.gameId()),
                    candidate.fqn()
                            + " must restore through S1 collapsing fragment generic recreate, not a dynamic codec");
        }
    }

    @Test
    void s1CollapsingFragmentsRoundTripPassedWithoutCodec() {
        for (CodecDeletionCandidate candidate : S1_COLLAPSING_FRAGMENT_RECREATE_CLASSES) {
            RoundTripSweepResult result = RewindRoundTripHarness.probeClass(candidate.fqn());
            assertInstanceOf(RoundTripSweepResult.Passed.class, result,
                    candidate.fqn()
                            + " must round-trip as Passed via S1 collapsing fragment generic recreate; got: "
                            + result);
        }
    }

    @Test
    void s1CollapsingFragmentFieldsAreMutableForCompactRestore() {
        for (MutableFieldCoverageCandidate candidate : S1_SCALAR_SPAWN_RECREATE_MUTABLE_FIELDS) {
            if (!candidate.fqn().contains("CollapsingFloorFragmentInstance")
                    && !candidate.fqn().contains("CollapsingLedgeFragmentInstance")) {
                continue;
            }
            Class<?> cls = loadClass(candidate.fqn());
            for (String fieldName : candidate.fieldNames()) {
                try {
                    var field = findField(cls, fieldName);
                    assertFalse(Modifier.isFinal(field.getModifiers()),
                            cls.getName() + "#" + fieldName
                                    + " must be mutable so compact restore can replay captured scalars");
                } catch (NoSuchFieldException e) {
                    throw new AssertionError("Missing scalar field " + cls.getName() + "#" + fieldName, e);
                }
            }
        }
    }

    @Test
    void s1DestructionFragmentsImplementRewindRecreatable() {
        for (CodecDeletionCandidate candidate : S1_DESTRUCTION_FRAGMENT_RECREATE_CLASSES) {
            Class<?> cls = loadClass(candidate.fqn());
            assertTrue(RewindRecreatable.class.isAssignableFrom(cls),
                    candidate.fqn() + " must implement RewindRecreatable after S1 destruction fragment coverage");
        }
    }

    @Test
    void s1DestructionFragmentsHaveNoRegisteredCodec() {
        for (CodecDeletionCandidate candidate : S1_DESTRUCTION_FRAGMENT_RECREATE_CLASSES) {
            assertFalse(hasRegisteredDynamicCodec(candidate.fqn(), candidate.gameId()),
                    candidate.fqn()
                            + " must restore through S1 destruction fragment generic recreate, not a dynamic codec");
        }
    }

    @Test
    void s1DestructionFragmentsRoundTripPassedWithoutCodec() {
        for (CodecDeletionCandidate candidate : S1_DESTRUCTION_FRAGMENT_RECREATE_CLASSES) {
            RoundTripSweepResult result = RewindRoundTripHarness.probeClass(candidate.fqn());
            assertInstanceOf(RoundTripSweepResult.Passed.class, result,
                    candidate.fqn()
                            + " must round-trip as Passed via RewindRecreatable path (no codec); got: "
                            + result);
        }
    }

    @Test
    void s1SpikedBallChainChildrenImplementRewindRecreatable() {
        for (CodecDeletionCandidate candidate : S1_SPIKED_BALL_CHAIN_RECREATE_CLASSES) {
            Class<?> cls = loadClass(candidate.fqn());
            assertTrue(RewindRecreatable.class.isAssignableFrom(cls),
                    candidate.fqn() + " must implement RewindRecreatable after S1 chain graph coverage");
        }
    }

    @Test
    void s1SpikedBallChainChildrenHaveNoRegisteredCodec() {
        for (CodecDeletionCandidate candidate : S1_SPIKED_BALL_CHAIN_RECREATE_CLASSES) {
            assertFalse(hasRegisteredDynamicCodec(candidate.fqn(), candidate.gameId()),
                    candidate.fqn()
                            + " must restore through S1 chain graph generic recreate, not a dynamic codec");
        }
    }

    @Test
    void s1SpikedBallChainChildrenRoundTripPassedWithoutCodec() {
        for (CodecDeletionCandidate candidate : S1_SPIKED_BALL_CHAIN_RECREATE_CLASSES) {
            RoundTripSweepResult result = RewindRoundTripHarness.probeClass(candidate.fqn());
            assertInstanceOf(RoundTripSweepResult.Passed.class, result,
                    candidate.fqn()
                            + " must round-trip as Passed via RewindRecreatable path (no codec); got: "
                            + result);
        }
    }

    @Test
    void s1FalseFloorFragmentImplementsRewindRecreatable() {
        for (CodecDeletionCandidate candidate : S1_FALSE_FLOOR_FRAGMENT_RECREATE_CLASSES) {
            Class<?> cls = loadClass(candidate.fqn());
            assertTrue(RewindRecreatable.class.isAssignableFrom(cls),
                    candidate.fqn() + " must implement RewindRecreatable after S1 false-floor fragment coverage");
        }
    }

    @Test
    void s1FalseFloorFragmentHasNoRegisteredCodec() {
        for (CodecDeletionCandidate candidate : S1_FALSE_FLOOR_FRAGMENT_RECREATE_CLASSES) {
            assertFalse(hasRegisteredDynamicCodec(candidate.fqn(), candidate.gameId()),
                    candidate.fqn()
                            + " must restore through S1 false-floor fragment generic recreate, not a dynamic codec");
        }
    }

    @Test
    void s1FalseFloorFragmentRoundTripPassedWithoutCodec() {
        for (CodecDeletionCandidate candidate : S1_FALSE_FLOOR_FRAGMENT_RECREATE_CLASSES) {
            RoundTripSweepResult result = RewindRoundTripHarness.probeClass(candidate.fqn());
            assertInstanceOf(RoundTripSweepResult.Passed.class, result,
                    candidate.fqn()
                            + " must round-trip as Passed via RewindRecreatable path (no codec); got: "
                            + result);
        }
    }

    @Test
    void s1BossControllersImplementRewindRecreatable() {
        for (CodecDeletionCandidate candidate : S1_BOSS_CONTROLLER_RECREATE_CLASSES) {
            Class<?> cls = loadClass(candidate.fqn());
            assertTrue(RewindRecreatable.class.isAssignableFrom(cls),
                    candidate.fqn() + " must implement RewindRecreatable after S1 boss controller coverage");
        }
    }

    @Test
    void s1BossControllersHaveNoRegisteredCodec() {
        for (CodecDeletionCandidate candidate : S1_BOSS_CONTROLLER_RECREATE_CLASSES) {
            assertFalse(hasRegisteredDynamicCodec(candidate.fqn(), candidate.gameId()),
                    candidate.fqn()
                            + " must restore through S1 boss controller generic recreate, not a dynamic codec");
        }
    }

    @Test
    void s1BossControllersRoundTripPassedWithoutCodec() {
        for (CodecDeletionCandidate candidate : S1_BOSS_CONTROLLER_RECREATE_CLASSES) {
            RoundTripSweepResult result = RewindRoundTripHarness.probeClass(candidate.fqn());
            assertInstanceOf(RoundTripSweepResult.Passed.class, result,
                    candidate.fqn()
                            + " must round-trip as Passed via RewindRecreatable path (no codec); got: "
                            + result);
        }
    }

    @Test
    void s2ScalarNamedRecreateClassesImplementRewindRecreatable() {
        for (CodecDeletionCandidate candidate : S2_SCALAR_NAMED_RECREATE_CLASSES) {
            Class<?> cls = loadClass(candidate.fqn());
            assertTrue(RewindRecreatable.class.isAssignableFrom(cls),
                    candidate.fqn() + " must implement RewindRecreatable after S2 scalar named coverage");
        }
    }

    @Test
    void s2ScalarNamedRecreateClassesHaveNoRegisteredCodec() {
        for (CodecDeletionCandidate candidate : S2_SCALAR_NAMED_RECREATE_CLASSES) {
            assertFalse(hasRegisteredDynamicCodec(candidate.fqn(), candidate.gameId()),
                    candidate.fqn()
                            + " must restore through S2 scalar named generic recreate, not a dynamic codec");
        }
    }

    @Test
    void s2CnzScalarRecreateClassesImplementRewindRecreatable() {
        for (CodecDeletionCandidate candidate : S2_CNZ_SCALAR_RECREATE_CLASSES) {
            Class<?> cls = loadClass(candidate.fqn());
            assertTrue(RewindRecreatable.class.isAssignableFrom(cls),
                    candidate.fqn() + " must implement RewindRecreatable after S2 CNZ scalar coverage");
        }
    }

    @Test
    void s2CnzScalarRecreateClassesHaveNoRegisteredCodec() {
        for (CodecDeletionCandidate candidate : S2_CNZ_SCALAR_RECREATE_CLASSES) {
            assertFalse(hasRegisteredDynamicCodec(candidate.fqn(), candidate.gameId()),
                    candidate.fqn()
                            + " must restore through S2 CNZ scalar generic recreate, not a dynamic codec");
        }
    }

    @Test
    void s2CnzScalarRecreateClassesRoundTripPassedWithoutCodec() {
        for (CodecDeletionCandidate candidate : S2_CNZ_SCALAR_RECREATE_CLASSES) {
            RoundTripSweepResult result = RewindRoundTripHarness.probeClass(candidate.fqn());
            assertInstanceOf(RoundTripSweepResult.Passed.class, result,
                    candidate.fqn()
                            + " must round-trip as Passed via RewindRecreatable path (no codec); got: "
                            + result);
        }
    }

    @Test
    void s2CnzScalarRecreateFieldsAreMutableForCompactRestore() {
        for (MutableFieldCoverageCandidate candidate : S2_CNZ_SCALAR_RECREATE_MUTABLE_FIELDS) {
            Class<?> cls = loadClass(candidate.fqn());
            for (String fieldName : candidate.fieldNames()) {
                try {
                    var field = findField(cls, fieldName);
                    assertFalse(Modifier.isFinal(field.getModifiers()),
                            cls.getName() + "#" + fieldName
                                    + " must be mutable so compact restore can replay captured scalars");
                } catch (NoSuchFieldException e) {
                    throw new AssertionError("Missing scalar field " + cls.getName() + "#" + fieldName, e);
                }
            }
        }
    }

    @Test
    void s2UtilityScalarRecreateClassesImplementRewindRecreatable() {
        for (CodecDeletionCandidate candidate : S2_UTILITY_SCALAR_RECREATE_CLASSES) {
            Class<?> cls = loadClass(candidate.fqn());
            assertTrue(RewindRecreatable.class.isAssignableFrom(cls),
                    candidate.fqn() + " must implement RewindRecreatable after S2 utility scalar coverage");
        }
    }

    @Test
    void s2UtilityScalarRecreateClassesHaveNoRegisteredCodec() {
        for (CodecDeletionCandidate candidate : S2_UTILITY_SCALAR_RECREATE_CLASSES) {
            assertFalse(hasRegisteredDynamicCodec(candidate.fqn(), candidate.gameId()),
                    candidate.fqn()
                            + " must restore through S2 utility scalar generic recreate, not a dynamic codec");
        }
    }

    @Test
    void s2UtilityScalarRecreateClassesRoundTripPassedWithoutCodec() {
        for (CodecDeletionCandidate candidate : S2_UTILITY_SCALAR_RECREATE_CLASSES) {
            RoundTripSweepResult result = RewindRoundTripHarness.probeClass(candidate.fqn());
            assertInstanceOf(RoundTripSweepResult.Passed.class, result,
                    candidate.fqn()
                            + " must round-trip as Passed via RewindRecreatable path (no codec); got: "
                            + result);
        }
    }

    @Test
    void s2UtilityScalarRecreateFieldsAreMutableForCompactRestore() {
        for (MutableFieldCoverageCandidate candidate : S2_UTILITY_SCALAR_RECREATE_MUTABLE_FIELDS) {
            Class<?> cls = loadClass(candidate.fqn());
            for (String fieldName : candidate.fieldNames()) {
                try {
                    var field = findField(cls, fieldName);
                    assertFalse(Modifier.isFinal(field.getModifiers()),
                            cls.getName() + "#" + fieldName
                                    + " must be mutable so compact restore can replay captured scalars");
                } catch (NoSuchFieldException e) {
                    throw new AssertionError("Missing scalar field " + cls.getName() + "#" + fieldName, e);
                }
            }
        }
    }

    @Test
    void s2PlatformVisualRecreateClassesImplementRewindRecreatable() {
        for (CodecDeletionCandidate candidate : S2_PLATFORM_VISUAL_RECREATE_CLASSES) {
            Class<?> cls = loadClass(candidate.fqn());
            assertTrue(RewindRecreatable.class.isAssignableFrom(cls),
                    candidate.fqn() + " must implement RewindRecreatable after S2 platform/visual coverage");
        }
    }

    @Test
    void s2PlatformVisualRecreateClassesHaveNoRegisteredCodec() {
        for (CodecDeletionCandidate candidate : S2_PLATFORM_VISUAL_RECREATE_CLASSES) {
            assertFalse(hasRegisteredDynamicCodec(candidate.fqn(), candidate.gameId()),
                    candidate.fqn()
                            + " must restore through S2 platform/visual generic recreate, not a dynamic codec");
        }
    }

    @Test
    void s2PlatformVisualRecreateClassesRoundTripPassedWithoutCodec() {
        for (CodecDeletionCandidate candidate : S2_PLATFORM_VISUAL_RECREATE_CLASSES) {
            RoundTripSweepResult result = RewindRoundTripHarness.probeClass(candidate.fqn());
            assertInstanceOf(RoundTripSweepResult.Passed.class, result,
                    candidate.fqn()
                            + " must round-trip as Passed via RewindRecreatable path (no codec); got: "
                            + result);
        }
    }

    @Test
    void s2MechanismScalarRecreateClassesImplementRewindRecreatable() {
        for (CodecDeletionCandidate candidate : S2_MECHANISM_SCALAR_RECREATE_CLASSES) {
            Class<?> cls = loadClass(candidate.fqn());
            assertTrue(RewindRecreatable.class.isAssignableFrom(cls),
                    candidate.fqn() + " must implement RewindRecreatable after S2 mechanism scalar coverage");
        }
    }

    @Test
    void s2MechanismScalarRecreateClassesHaveNoRegisteredCodec() {
        for (CodecDeletionCandidate candidate : S2_MECHANISM_SCALAR_RECREATE_CLASSES) {
            assertFalse(hasRegisteredDynamicCodec(candidate.fqn(), candidate.gameId()),
                    candidate.fqn()
                            + " must restore through S2 mechanism scalar generic recreate, not a dynamic codec");
        }
    }

    @Test
    void s2MechanismScalarRecreateClassesRoundTripPassedWithoutCodec() {
        for (CodecDeletionCandidate candidate : S2_MECHANISM_SCALAR_RECREATE_CLASSES) {
            RoundTripSweepResult result = RewindRoundTripHarness.probeClass(candidate.fqn());
            assertInstanceOf(RoundTripSweepResult.Passed.class, result,
                    candidate.fqn()
                            + " must round-trip as Passed via RewindRecreatable path (no codec); got: "
                            + result);
        }
    }

    @Test
    void s2MechanismScalarRecreateFieldsAreMutableForCompactRestore() {
        for (MutableFieldCoverageCandidate candidate : S2_MECHANISM_SCALAR_RECREATE_MUTABLE_FIELDS) {
            Class<?> cls = loadClass(candidate.fqn());
            for (String fieldName : candidate.fieldNames()) {
                try {
                    var field = findField(cls, fieldName);
                    assertFalse(Modifier.isFinal(field.getModifiers()),
                            cls.getName() + "#" + fieldName
                                    + " must be mutable so compact restore can replay captured scalars");
                } catch (NoSuchFieldException e) {
                    throw new AssertionError("Missing scalar field " + cls.getName() + "#" + fieldName, e);
                }
            }
        }
    }

    @Test
    void s2BoxSolidScalarRecreateClassesImplementRewindRecreatable() {
        for (CodecDeletionCandidate candidate : S2_BOX_SOLID_SCALAR_RECREATE_CLASSES) {
            Class<?> cls = loadClass(candidate.fqn());
            assertTrue(RewindRecreatable.class.isAssignableFrom(cls),
                    candidate.fqn() + " must implement RewindRecreatable after S2 box/solid scalar coverage");
        }
    }

    @Test
    void s2BoxSolidScalarRecreateClassesHaveNoRegisteredCodec() {
        for (CodecDeletionCandidate candidate : S2_BOX_SOLID_SCALAR_RECREATE_CLASSES) {
            assertFalse(hasRegisteredDynamicCodec(candidate.fqn(), candidate.gameId()),
                    candidate.fqn()
                            + " must restore through S2 box/solid scalar generic recreate, not a dynamic codec");
        }
    }

    @Test
    void s2BoxSolidScalarRecreateClassesRoundTripPassedWithoutCodec() {
        for (CodecDeletionCandidate candidate : S2_BOX_SOLID_SCALAR_RECREATE_CLASSES) {
            RoundTripSweepResult result = RewindRoundTripHarness.probeClass(candidate.fqn());
            assertInstanceOf(RoundTripSweepResult.Passed.class, result,
                    candidate.fqn()
                            + " must round-trip as Passed via RewindRecreatable path (no codec); got: "
                            + result);
        }
    }

    @Test
    void s2BoxSolidScalarRecreateFieldsAreMutableForCompactRestore() {
        for (MutableFieldCoverageCandidate candidate : S2_BOX_SOLID_SCALAR_RECREATE_MUTABLE_FIELDS) {
            Class<?> cls = loadClass(candidate.fqn());
            for (String fieldName : candidate.fieldNames()) {
                try {
                    var field = findField(cls, fieldName);
                    assertFalse(Modifier.isFinal(field.getModifiers()),
                            cls.getName() + "#" + fieldName
                                    + " must be mutable so compact restore can replay captured scalars");
                } catch (NoSuchFieldException e) {
                    throw new AssertionError("Missing scalar field " + cls.getName() + "#" + fieldName, e);
                }
            }
        }
    }

    @Test
    void s2TriggerMotionScalarRecreateClassesImplementRewindRecreatable() {
        for (CodecDeletionCandidate candidate : S2_TRIGGER_MOTION_SCALAR_RECREATE_CLASSES) {
            Class<?> cls = loadClass(candidate.fqn());
            assertTrue(RewindRecreatable.class.isAssignableFrom(cls),
                    candidate.fqn() + " must implement RewindRecreatable after S2 trigger/motion scalar coverage");
        }
    }

    @Test
    void s2TriggerMotionScalarRecreateClassesHaveNoRegisteredCodec() {
        for (CodecDeletionCandidate candidate : S2_TRIGGER_MOTION_SCALAR_RECREATE_CLASSES) {
            assertFalse(hasRegisteredDynamicCodec(candidate.fqn(), candidate.gameId()),
                    candidate.fqn()
                            + " must restore through S2 trigger/motion scalar generic recreate, not a dynamic codec");
        }
    }

    @Test
    void s2TriggerMotionScalarRecreateClassesRoundTripPassedWithoutCodec() {
        for (CodecDeletionCandidate candidate : S2_TRIGGER_MOTION_SCALAR_RECREATE_CLASSES) {
            RoundTripSweepResult result = RewindRoundTripHarness.probeClass(candidate.fqn());
            assertInstanceOf(RoundTripSweepResult.Passed.class, result,
                    candidate.fqn()
                            + " must round-trip as Passed via RewindRecreatable path (no codec); got: "
                            + result);
        }
    }

    @Test
    void s2TriggerMotionScalarRecreateFieldsAreMutableForCompactRestore() {
        for (MutableFieldCoverageCandidate candidate : S2_TRIGGER_MOTION_SCALAR_RECREATE_MUTABLE_FIELDS) {
            Class<?> cls = loadClass(candidate.fqn());
            for (String fieldName : candidate.fieldNames()) {
                try {
                    var field = findField(cls, fieldName);
                    assertFalse(Modifier.isFinal(field.getModifiers()),
                            cls.getName() + "#" + fieldName
                                    + " must be mutable so compact restore can replay captured scalars");
                } catch (NoSuchFieldException e) {
                    throw new AssertionError("Missing scalar field " + cls.getName() + "#" + fieldName, e);
                }
            }
        }
    }

    @Test
    void s2BadnikScalarRecreateClassesImplementRewindRecreatable() {
        for (CodecDeletionCandidate candidate : S2_BADNIK_SCALAR_RECREATE_CLASSES) {
            Class<?> cls = loadClass(candidate.fqn());
            assertTrue(RewindRecreatable.class.isAssignableFrom(cls),
                    candidate.fqn() + " must implement RewindRecreatable after S2 badnik scalar coverage");
        }
    }

    @Test
    void s2BadnikScalarRecreateClassesHaveNoRegisteredCodec() {
        for (CodecDeletionCandidate candidate : S2_BADNIK_SCALAR_RECREATE_CLASSES) {
            assertFalse(hasRegisteredDynamicCodec(candidate.fqn(), candidate.gameId()),
                    candidate.fqn()
                            + " must restore through S2 badnik scalar generic recreate, not a dynamic codec");
        }
    }

    @Test
    void s2BadnikScalarRecreateClassesRoundTripPassedWithoutCodec() {
        for (CodecDeletionCandidate candidate : S2_BADNIK_SCALAR_RECREATE_CLASSES) {
            RoundTripSweepResult result = RewindRoundTripHarness.probeClass(candidate.fqn());
            assertInstanceOf(RoundTripSweepResult.Passed.class, result,
                    candidate.fqn()
                            + " must round-trip as Passed via RewindRecreatable path (no codec); got: "
                            + result);
        }
    }

    @Test
    void s2BadnikScalarRecreateFieldsAreMutableForCompactRestore() {
        for (MutableFieldCoverageCandidate candidate : S2_BADNIK_SCALAR_RECREATE_MUTABLE_FIELDS) {
            Class<?> cls = loadClass(candidate.fqn());
            for (String fieldName : candidate.fieldNames()) {
                try {
                    var field = findField(cls, fieldName);
                    assertFalse(Modifier.isFinal(field.getModifiers()),
                            cls.getName() + "#" + fieldName
                                    + " must be mutable so compact restore can replay captured scalars");
                } catch (NoSuchFieldException e) {
                    throw new AssertionError("Missing scalar field " + cls.getName() + "#" + fieldName, e);
                }
            }
        }
    }

    @Test
    void s2MiscScalarRecreateClassesImplementRewindRecreatable() {
        for (CodecDeletionCandidate candidate : S2_MISC_SCALAR_RECREATE_CLASSES) {
            Class<?> cls = loadClass(candidate.fqn());
            assertTrue(RewindRecreatable.class.isAssignableFrom(cls),
                    candidate.fqn() + " must implement RewindRecreatable after S2 misc scalar coverage");
        }
    }

    @Test
    void s2MiscScalarRecreateClassesHaveNoRegisteredCodec() {
        for (CodecDeletionCandidate candidate : S2_MISC_SCALAR_RECREATE_CLASSES) {
            assertFalse(hasRegisteredDynamicCodec(candidate.fqn(), candidate.gameId()),
                    candidate.fqn()
                            + " must restore through S2 misc scalar generic recreate, not a dynamic codec");
        }
    }

    @Test
    void s2MiscScalarRecreateClassesRoundTripPassedWithoutCodec() {
        for (CodecDeletionCandidate candidate : S2_MISC_SCALAR_RECREATE_CLASSES) {
            RoundTripSweepResult result = RewindRoundTripHarness.probeClass(candidate.fqn());
            assertInstanceOf(RoundTripSweepResult.Passed.class, result,
                    candidate.fqn()
                            + " must round-trip as Passed via RewindRecreatable path (no codec); got: "
                            + result);
        }
    }

    @Test
    void s2MiscScalarRecreateFieldsAreMutableForCompactRestore() {
        for (MutableFieldCoverageCandidate candidate : S2_MISC_SCALAR_RECREATE_MUTABLE_FIELDS) {
            Class<?> cls = loadClass(candidate.fqn());
            for (String fieldName : candidate.fieldNames()) {
                try {
                    var field = findField(cls, fieldName);
                    assertFalse(Modifier.isFinal(field.getModifiers()),
                            cls.getName() + "#" + fieldName
                                    + " must be mutable so compact restore can replay captured scalars");
                } catch (NoSuchFieldException e) {
                    throw new AssertionError("Missing scalar field " + cls.getName() + "#" + fieldName, e);
                }
            }
        }
    }

    @Test
    void s2MechanismTailRecreateClassesImplementRewindRecreatable() {
        for (CodecDeletionCandidate candidate : S2_MECHANISM_TAIL_RECREATE_CLASSES) {
            Class<?> cls = loadClass(candidate.fqn());
            assertTrue(RewindRecreatable.class.isAssignableFrom(cls),
                    candidate.fqn() + " must implement RewindRecreatable after S2 mechanism tail coverage");
        }
    }

    @Test
    void s2MechanismTailRecreateClassesHaveNoRegisteredCodec() {
        for (CodecDeletionCandidate candidate : S2_MECHANISM_TAIL_RECREATE_CLASSES) {
            assertFalse(hasRegisteredDynamicCodec(candidate.fqn(), candidate.gameId()),
                    candidate.fqn()
                            + " must restore through S2 mechanism tail generic recreate, not a dynamic codec");
        }
    }

    @Test
    void s2MechanismTailRecreateClassesRoundTripPassedWithoutCodec() {
        for (CodecDeletionCandidate candidate : S2_MECHANISM_TAIL_RECREATE_CLASSES) {
            RoundTripSweepResult result = RewindRoundTripHarness.probeClass(candidate.fqn());
            assertInstanceOf(RoundTripSweepResult.Passed.class, result,
                    candidate.fqn()
                            + " must round-trip as Passed via RewindRecreatable path (no codec); got: "
                            + result);
        }
    }

    @Test
    void s2MechanismTailRecreateFieldsAreMutableForCompactRestore() {
        for (MutableFieldCoverageCandidate candidate : S2_MECHANISM_TAIL_RECREATE_MUTABLE_FIELDS) {
            Class<?> cls = loadClass(candidate.fqn());
            for (String fieldName : candidate.fieldNames()) {
                try {
                    var field = findField(cls, fieldName);
                    assertFalse(Modifier.isFinal(field.getModifiers()),
                            cls.getName() + "#" + fieldName
                                    + " must be mutable so compact restore can replay captured scalars");
                } catch (NoSuchFieldException e) {
                    throw new AssertionError("Missing scalar field " + cls.getName() + "#" + fieldName, e);
                }
            }
        }
    }

    @Test
    void s2MechanismFragmentParentRecreateClassesImplementRewindRecreatable() {
        for (CodecDeletionCandidate candidate : S2_MECHANISM_FRAGMENT_PARENT_RECREATE_CLASSES) {
            Class<?> cls = loadClass(candidate.fqn());
            assertTrue(RewindRecreatable.class.isAssignableFrom(cls),
                    candidate.fqn()
                            + " must implement RewindRecreatable after S2 mechanism fragment-parent coverage");
        }
    }

    @Test
    void s2MechanismFragmentParentRecreateClassesHaveNoRegisteredCodec() {
        for (CodecDeletionCandidate candidate : S2_MECHANISM_FRAGMENT_PARENT_RECREATE_CLASSES) {
            assertFalse(hasRegisteredDynamicCodec(candidate.fqn(), candidate.gameId()),
                    candidate.fqn()
                            + " must restore through S2 mechanism fragment-parent generic recreate, not a dynamic codec");
        }
    }

    @Test
    void s2MechanismFragmentParentRecreateClassesRoundTripPassedWithoutCodec() {
        for (CodecDeletionCandidate candidate : S2_MECHANISM_FRAGMENT_PARENT_RECREATE_CLASSES) {
            RoundTripSweepResult result = RewindRoundTripHarness.probeClass(candidate.fqn());
            assertInstanceOf(RoundTripSweepResult.Passed.class, result,
                    candidate.fqn()
                            + " must round-trip as Passed via RewindRecreatable path (no codec); got: "
                            + result);
        }
    }

    @Test
    void s2MechanismFragmentParentRecreateFieldsAreMutableForCompactRestore() {
        for (MutableFieldCoverageCandidate candidate : S2_MECHANISM_FRAGMENT_PARENT_RECREATE_MUTABLE_FIELDS) {
            Class<?> cls = loadClass(candidate.fqn());
            for (String fieldName : candidate.fieldNames()) {
                try {
                    var field = findField(cls, fieldName);
                    assertFalse(Modifier.isFinal(field.getModifiers()),
                            cls.getName() + "#" + fieldName
                                    + " must be mutable so compact restore can replay captured scalars");
                } catch (NoSuchFieldException e) {
                    throw new AssertionError("Missing scalar field " + cls.getName() + "#" + fieldName, e);
                }
            }
        }
    }

    @Test
    void s2DebrisFragmentRecreateClassesImplementRewindRecreatable() {
        for (CodecDeletionCandidate candidate : S2_DEBRIS_FRAGMENT_RECREATE_CLASSES) {
            Class<?> cls = loadClass(candidate.fqn());
            assertTrue(RewindRecreatable.class.isAssignableFrom(cls),
                    candidate.fqn() + " must implement RewindRecreatable after S2 debris fragment coverage");
        }
    }

    @Test
    void s2DebrisFragmentRecreateClassesHaveNoRegisteredCodec() {
        for (CodecDeletionCandidate candidate : S2_DEBRIS_FRAGMENT_RECREATE_CLASSES) {
            assertFalse(hasRegisteredDynamicCodec(candidate.fqn(), candidate.gameId()),
                    candidate.fqn()
                            + " must restore through S2 debris fragment generic recreate, not a dynamic codec");
        }
    }

    @Test
    void s2DebrisFragmentRecreateClassesRoundTripPassedWithoutCodec() {
        for (CodecDeletionCandidate candidate : S2_DEBRIS_FRAGMENT_RECREATE_CLASSES) {
            RoundTripSweepResult result = RewindRoundTripHarness.probeClass(candidate.fqn());
            assertInstanceOf(RoundTripSweepResult.Passed.class, result,
                    candidate.fqn()
                            + " must round-trip as Passed via RewindRecreatable path (no codec); got: "
                            + result);
        }
    }

    @Test
    void s2DebrisFragmentRecreateFieldsAreMutableForCompactRestore() {
        for (MutableFieldCoverageCandidate candidate : S2_DEBRIS_FRAGMENT_RECREATE_MUTABLE_FIELDS) {
            Class<?> cls = loadClass(candidate.fqn());
            for (String fieldName : candidate.fieldNames()) {
                try {
                    var field = findField(cls, fieldName);
                    assertFalse(Modifier.isFinal(field.getModifiers()),
                            cls.getName() + "#" + fieldName
                                    + " must be mutable so compact restore can replay captured scalars");
                } catch (NoSuchFieldException e) {
                    throw new AssertionError("Missing scalar field " + cls.getName() + "#" + fieldName, e);
                }
            }
        }
    }

    @Test
    void s2InteractionScalarRecreateClassesImplementRewindRecreatable() {
        for (CodecDeletionCandidate candidate : S2_INTERACTION_SCALAR_RECREATE_CLASSES) {
            Class<?> cls = loadClass(candidate.fqn());
            assertTrue(RewindRecreatable.class.isAssignableFrom(cls),
                    candidate.fqn() + " must implement RewindRecreatable after S2 interaction scalar coverage");
        }
    }

    @Test
    void s2InteractionScalarRecreateClassesHaveNoRegisteredCodec() {
        for (CodecDeletionCandidate candidate : S2_INTERACTION_SCALAR_RECREATE_CLASSES) {
            assertFalse(hasRegisteredDynamicCodec(candidate.fqn(), candidate.gameId()),
                    candidate.fqn()
                            + " must restore through S2 interaction scalar generic recreate, not a dynamic codec");
        }
    }

    @Test
    void s2InteractionScalarRecreateClassesRoundTripPassedWithoutCodec() {
        for (CodecDeletionCandidate candidate : S2_INTERACTION_SCALAR_RECREATE_CLASSES) {
            RoundTripSweepResult result = RewindRoundTripHarness.probeClass(candidate.fqn());
            assertInstanceOf(RoundTripSweepResult.Passed.class, result,
                    candidate.fqn()
                            + " must round-trip as Passed via RewindRecreatable path (no codec); got: "
                            + result);
        }
    }

    @Test
    void s2InteractionScalarRecreateFieldsAreMutableForCompactRestore() {
        for (MutableFieldCoverageCandidate candidate : S2_INTERACTION_SCALAR_RECREATE_MUTABLE_FIELDS) {
            Class<?> cls = loadClass(candidate.fqn());
            for (String fieldName : candidate.fieldNames()) {
                try {
                    var field = findField(cls, fieldName);
                    assertFalse(Modifier.isFinal(field.getModifiers()),
                            cls.getName() + "#" + fieldName
                                    + " must be mutable so compact restore can replay captured scalars");
                } catch (NoSuchFieldException e) {
                    throw new AssertionError("Missing scalar field " + cls.getName() + "#" + fieldName, e);
                }
            }
        }
    }

    @Test
    void s2MczRotPformsGraphRecreateClassesImplementRewindRecreatable() {
        for (CodecDeletionCandidate candidate : S2_MCZ_ROT_PFORMS_GRAPH_RECREATE_CLASSES) {
            Class<?> cls = loadClass(candidate.fqn());
            assertTrue(RewindRecreatable.class.isAssignableFrom(cls),
                    candidate.fqn() + " must implement RewindRecreatable after S2 MCZ graph coverage");
        }
    }

    @Test
    void s2MczRotPformsGraphRecreateClassesHaveNoRegisteredCodec() {
        for (CodecDeletionCandidate candidate : S2_MCZ_ROT_PFORMS_GRAPH_RECREATE_CLASSES) {
            assertFalse(hasRegisteredDynamicCodec(candidate.fqn(), candidate.gameId()),
                    candidate.fqn()
                            + " must restore through S2 MCZ graph generic recreate, not a dynamic codec");
        }
    }

    @Test
    void s2MczRotPformsGraphRecreateClassesRoundTripPassedWithoutCodec() {
        for (CodecDeletionCandidate candidate : S2_MCZ_ROT_PFORMS_GRAPH_RECREATE_CLASSES) {
            RoundTripSweepResult result = RewindRoundTripHarness.probeClass(candidate.fqn());
            assertInstanceOf(RoundTripSweepResult.Passed.class, result,
                    candidate.fqn()
                            + " must round-trip as Passed via RewindRecreatable path (no codec); got: "
                            + result);
        }
    }

    @Test
    void s2MczRotPformsGraphRecreateFieldsAreMutableForCompactRestore() {
        for (MutableFieldCoverageCandidate candidate : S2_MCZ_ROT_PFORMS_GRAPH_RECREATE_MUTABLE_FIELDS) {
            Class<?> cls = loadClass(candidate.fqn());
            for (String fieldName : candidate.fieldNames()) {
                try {
                    var field = findField(cls, fieldName);
                    assertFalse(Modifier.isFinal(field.getModifiers()),
                            cls.getName() + "#" + fieldName
                                    + " must be mutable so compact restore can replay captured scalars");
                } catch (NoSuchFieldException e) {
                    throw new AssertionError("Missing scalar field " + cls.getName() + "#" + fieldName, e);
                }
            }
        }
    }

    @Test
    void s2SidewaysPformGraphRecreateClassesImplementRewindRecreatable() {
        for (CodecDeletionCandidate candidate : S2_SIDEWAYS_PFORM_GRAPH_RECREATE_CLASSES) {
            Class<?> cls = loadClass(candidate.fqn());
            assertTrue(RewindRecreatable.class.isAssignableFrom(cls),
                    candidate.fqn() + " must implement RewindRecreatable after S2 Sideways graph coverage");
        }
    }

    @Test
    void s2SidewaysPformGraphRecreateClassesHaveNoRegisteredCodec() {
        for (CodecDeletionCandidate candidate : S2_SIDEWAYS_PFORM_GRAPH_RECREATE_CLASSES) {
            assertFalse(hasRegisteredDynamicCodec(candidate.fqn(), candidate.gameId()),
                    candidate.fqn()
                            + " must restore through S2 Sideways graph generic recreate, not a dynamic codec");
        }
    }

    @Test
    void s2SidewaysPformGraphRecreateClassesRoundTripPassedWithoutCodec() {
        for (CodecDeletionCandidate candidate : S2_SIDEWAYS_PFORM_GRAPH_RECREATE_CLASSES) {
            RoundTripSweepResult result = RewindRoundTripHarness.probeClass(candidate.fqn());
            assertInstanceOf(RoundTripSweepResult.Passed.class, result,
                    candidate.fqn()
                            + " must round-trip as Passed via RewindRecreatable path (no codec); got: "
                            + result);
        }
    }

    @Test
    void s2FallingPillarGraphRecreateClassesImplementRewindRecreatable() {
        for (CodecDeletionCandidate candidate : S2_FALLING_PILLAR_GRAPH_RECREATE_CLASSES) {
            Class<?> cls = loadClass(candidate.fqn());
            assertTrue(RewindRecreatable.class.isAssignableFrom(cls),
                    candidate.fqn() + " must implement RewindRecreatable after S2 Falling Pillar graph coverage");
        }
    }

    @Test
    void s2FallingPillarGraphRecreateClassesHaveNoRegisteredCodec() {
        for (CodecDeletionCandidate candidate : S2_FALLING_PILLAR_GRAPH_RECREATE_CLASSES) {
            assertFalse(hasRegisteredDynamicCodec(candidate.fqn(), candidate.gameId()),
                    candidate.fqn()
                            + " must restore through S2 Falling Pillar graph generic recreate, not a dynamic codec");
        }
    }

    @Test
    void s2FallingPillarGraphRecreateClassesRoundTripPassedWithoutCodec() {
        for (CodecDeletionCandidate candidate : S2_FALLING_PILLAR_GRAPH_RECREATE_CLASSES) {
            RoundTripSweepResult result = RewindRoundTripHarness.probeClass(candidate.fqn());
            assertInstanceOf(RoundTripSweepResult.Passed.class, result,
                    candidate.fqn()
                            + " must round-trip as Passed via RewindRecreatable path (no codec); got: "
                            + result);
        }
    }

    @Test
    void s2FallingPillarGraphRecreateFieldsAreMutableForCompactRestore() {
        for (MutableFieldCoverageCandidate candidate : S2_FALLING_PILLAR_GRAPH_RECREATE_MUTABLE_FIELDS) {
            Class<?> cls = loadClass(candidate.fqn());
            for (String fieldName : candidate.fieldNames()) {
                try {
                    var field = findField(cls, fieldName);
                    assertFalse(Modifier.isFinal(field.getModifiers()),
                            cls.getName() + "#" + fieldName
                                    + " must be mutable so compact restore can replay captured scalars");
                } catch (NoSuchFieldException e) {
                    throw new AssertionError("Missing scalar field " + cls.getName() + "#" + fieldName, e);
                }
            }
        }
    }

    @Test
    void s2SwingingPlatformGraphRecreateClassesImplementRewindRecreatable() {
        for (CodecDeletionCandidate candidate : S2_SWINGING_PLATFORM_GRAPH_RECREATE_CLASSES) {
            Class<?> cls = loadClass(candidate.fqn());
            assertTrue(RewindRecreatable.class.isAssignableFrom(cls),
                    candidate.fqn() + " must implement RewindRecreatable after S2 Swinging Platform graph coverage");
        }
    }

    @Test
    void s2SwingingPlatformGraphRecreateClassesHaveNoRegisteredCodec() {
        for (CodecDeletionCandidate candidate : S2_SWINGING_PLATFORM_GRAPH_RECREATE_CLASSES) {
            assertFalse(hasRegisteredDynamicCodec(candidate.fqn(), candidate.gameId()),
                    candidate.fqn()
                            + " must restore through S2 Swinging Platform graph generic recreate, not a dynamic codec");
        }
    }

    @Test
    void s2SwingingPlatformGraphRecreateClassesRoundTripPassedWithoutCodec() {
        for (CodecDeletionCandidate candidate : S2_SWINGING_PLATFORM_GRAPH_RECREATE_ROUND_TRIP_CLASSES) {
            RoundTripSweepResult result = RewindRoundTripHarness.probeClass(candidate.fqn());
            assertInstanceOf(RoundTripSweepResult.Passed.class, result,
                    candidate.fqn()
                            + " must round-trip as Passed via RewindRecreatable path (no codec); got: "
                            + result);
        }
    }

    @Test
    void s2SwingingPlatformGraphRecreateFieldsAreMutableForCompactRestore() {
        for (MutableFieldCoverageCandidate candidate : S2_SWINGING_PLATFORM_GRAPH_RECREATE_MUTABLE_FIELDS) {
            Class<?> cls = loadClass(candidate.fqn());
            for (String fieldName : candidate.fieldNames()) {
                try {
                    var field = findField(cls, fieldName);
                    assertFalse(Modifier.isFinal(field.getModifiers()),
                            cls.getName() + "#" + fieldName
                                    + " must be mutable so compact restore can replay captured scalars");
                } catch (NoSuchFieldException e) {
                    throw new AssertionError("Missing scalar field " + cls.getName() + "#" + fieldName, e);
                }
            }
        }
    }

    @Test
    void s2CogGraphRecreateClassesImplementRewindRecreatable() {
        for (CodecDeletionCandidate candidate : S2_COG_GRAPH_RECREATE_CLASSES) {
            Class<?> cls = loadClass(candidate.fqn());
            assertTrue(RewindRecreatable.class.isAssignableFrom(cls),
                    candidate.fqn() + " must implement RewindRecreatable after S2 Cog graph coverage");
        }
    }

    @Test
    void s2CogGraphRecreateClassesHaveNoRegisteredCodec() {
        for (CodecDeletionCandidate candidate : S2_COG_GRAPH_RECREATE_CLASSES) {
            assertFalse(hasRegisteredDynamicCodec(candidate.fqn(), candidate.gameId()),
                    candidate.fqn()
                            + " must restore through S2 Cog graph generic recreate, not a dynamic codec");
        }
    }

    @Test
    void s2CogGraphRecreateClassesRoundTripPassedWithoutCodec() {
        for (CodecDeletionCandidate candidate : S2_COG_GRAPH_RECREATE_ROUND_TRIP_CLASSES) {
            RoundTripSweepResult result = RewindRoundTripHarness.probeClass(candidate.fqn());
            assertInstanceOf(RoundTripSweepResult.Passed.class, result,
                    candidate.fqn()
                            + " must round-trip as Passed via RewindRecreatable path (no codec); got: "
                            + result);
        }
    }

    @Test
    void s2CogGraphRecreateFieldsAreMutableForCompactRestore() {
        for (MutableFieldCoverageCandidate candidate : S2_COG_GRAPH_RECREATE_MUTABLE_FIELDS) {
            Class<?> cls = loadClass(candidate.fqn());
            for (String fieldName : candidate.fieldNames()) {
                try {
                    var field = findField(cls, fieldName);
                    assertFalse(Modifier.isFinal(field.getModifiers()),
                            cls.getName() + "#" + fieldName
                                    + " must be mutable so compact restore can replay captured scalars");
                } catch (NoSuchFieldException e) {
                    throw new AssertionError("Missing scalar field " + cls.getName() + "#" + fieldName, e);
                }
            }
        }
    }

    @Test
    void s2LauncherGraphRecreateClassesImplementRewindRecreatable() {
        for (CodecDeletionCandidate candidate : S2_LAUNCHER_GRAPH_RECREATE_CLASSES) {
            Class<?> cls = loadClass(candidate.fqn());
            assertTrue(RewindRecreatable.class.isAssignableFrom(cls),
                    candidate.fqn() + " must implement RewindRecreatable after S2 launcher graph coverage");
        }
    }

    @Test
    void s2LauncherGraphRecreateClassesHaveNoRegisteredCodec() {
        for (CodecDeletionCandidate candidate : S2_LAUNCHER_GRAPH_RECREATE_CLASSES) {
            assertFalse(hasRegisteredDynamicCodec(candidate.fqn(), candidate.gameId()),
                    candidate.fqn()
                            + " must restore through S2 launcher graph generic recreate, not a dynamic codec");
        }
    }

    @Test
    void s2LauncherGraphRecreateClassesRoundTripPassedWithoutCodec() {
        for (CodecDeletionCandidate candidate : S2_LAUNCHER_GRAPH_RECREATE_CLASSES) {
            RoundTripSweepResult result = RewindRoundTripHarness.probeClass(candidate.fqn());
            assertInstanceOf(RoundTripSweepResult.Passed.class, result,
                    candidate.fqn()
                            + " must round-trip as Passed via RewindRecreatable path (no codec); got: "
                            + result);
        }
    }

    @Test
    void s2FlipperGraphRecreateClassesImplementRewindRecreatable() {
        for (CodecDeletionCandidate candidate : S2_FLIPPER_GRAPH_RECREATE_CLASSES) {
            Class<?> cls = loadClass(candidate.fqn());
            assertTrue(RewindRecreatable.class.isAssignableFrom(cls),
                    candidate.fqn() + " must implement RewindRecreatable after S2 flipper graph coverage");
        }
    }

    @Test
    void s2FlipperGraphRecreateClassesHaveNoRegisteredCodec() {
        for (CodecDeletionCandidate candidate : S2_FLIPPER_GRAPH_RECREATE_CLASSES) {
            assertFalse(hasRegisteredDynamicCodec(candidate.fqn(), candidate.gameId()),
                    candidate.fqn()
                            + " must restore through S2 flipper graph generic recreate, not a dynamic codec");
        }
    }

    @Test
    void s2FlipperGraphRecreateClassesRoundTripPassedWithoutCodec() {
        for (CodecDeletionCandidate candidate : S2_FLIPPER_GRAPH_RECREATE_CLASSES) {
            RoundTripSweepResult result = RewindRoundTripHarness.probeClass(candidate.fqn());
            assertInstanceOf(RoundTripSweepResult.Passed.class, result,
                    candidate.fqn()
                            + " must round-trip as Passed via RewindRecreatable path (no codec); got: "
                            + result);
        }
    }

    @Test
    void s2SpiralGraphRecreateClassesImplementRewindRecreatable() {
        for (CodecDeletionCandidate candidate : S2_SPIRAL_GRAPH_RECREATE_CLASSES) {
            Class<?> cls = loadClass(candidate.fqn());
            assertTrue(RewindRecreatable.class.isAssignableFrom(cls),
                    candidate.fqn() + " must implement RewindRecreatable after S2 spiral graph coverage");
        }
    }

    @Test
    void s2SpiralGraphRecreateClassesHaveNoRegisteredCodec() {
        for (CodecDeletionCandidate candidate : S2_SPIRAL_GRAPH_RECREATE_CLASSES) {
            assertFalse(hasRegisteredDynamicCodec(candidate.fqn(), candidate.gameId()),
                    candidate.fqn()
                            + " must restore through S2 spiral graph generic recreate, not a dynamic codec");
        }
    }

    @Test
    void s2SpiralGraphRecreateClassesRoundTripPassedWithoutCodec() {
        for (CodecDeletionCandidate candidate : S2_SPIRAL_GRAPH_RECREATE_CLASSES) {
            RoundTripSweepResult result = RewindRoundTripHarness.probeClass(candidate.fqn());
            assertInstanceOf(RoundTripSweepResult.Passed.class, result,
                    candidate.fqn()
                            + " must round-trip as Passed via RewindRecreatable path (no codec); got: "
                            + result);
        }
    }

    @Test
    void s2TornadoGraphRecreateFieldsAreMutableForCompactRestore() {
        for (MutableFieldCoverageCandidate candidate : S2_TORNADO_GRAPH_RECREATE_MUTABLE_FIELDS) {
            Class<?> cls = loadClass(candidate.fqn());
            for (String fieldName : candidate.fieldNames()) {
                try {
                    var field = findField(cls, fieldName);
                    assertFalse(Modifier.isFinal(field.getModifiers()),
                            cls.getName() + "#" + fieldName
                                    + " must be mutable so compact restore can replay captured scalars");
                } catch (NoSuchFieldException e) {
                    throw new AssertionError("Missing scalar field " + cls.getName() + "#" + fieldName, e);
                }
            }
        }
    }

    @Test
    void s1ScalarSpawnRecreateFieldsAreMutableForCompactRestore() {
        for (MutableFieldCoverageCandidate candidate : S1_SCALAR_SPAWN_RECREATE_MUTABLE_FIELDS) {
            Class<?> cls = loadClass(candidate.fqn());
            for (String fieldName : candidate.fieldNames()) {
                try {
                    var field = findField(cls, fieldName);
                    assertFalse(Modifier.isFinal(field.getModifiers()),
                            cls.getName() + "#" + fieldName
                                    + " must be mutable so compact restore can replay captured scalars");
                } catch (NoSuchFieldException e) {
                    throw new AssertionError("Missing scalar field " + cls.getName() + "#" + fieldName, e);
                }
            }
        }
    }

    @Test
    void s1EffectScalarRecreateFieldsAreMutableForCompactRestore() {
        for (MutableFieldCoverageCandidate candidate : S1_EFFECT_SCALAR_RECREATE_MUTABLE_FIELDS) {
            Class<?> cls = loadClass(candidate.fqn());
            for (String fieldName : candidate.fieldNames()) {
                try {
                    var field = findField(cls, fieldName);
                    assertFalse(Modifier.isFinal(field.getModifiers()),
                            cls.getName() + "#" + fieldName
                                    + " must be mutable so compact restore can replay captured scalars");
                } catch (NoSuchFieldException e) {
                    throw new AssertionError("Missing scalar field " + cls.getName() + "#" + fieldName, e);
                }
            }
        }
    }

    // =====================================================================
    // S3K SS-entry flash graph support: layout-ring-linked special-stage flash
    // =====================================================================

    @Test
    void s3kSsEntryFlashGraphClassesImplementRewindRecreatable() {
        for (CodecDeletionCandidate candidate : S3K_SS_ENTRY_FLASH_GRAPH_DELETED_CODECS) {
            Class<?> cls = loadClass(candidate.fqn());
            assertTrue(RewindRecreatable.class.isAssignableFrom(cls),
                    candidate.fqn() + " must implement RewindRecreatable after S3K SS-entry flash deletion");
        }
    }

    @Test
    void s3kSsEntryFlashGraphClassesHaveNoRegisteredCodec() {
        for (CodecDeletionCandidate candidate : S3K_SS_ENTRY_FLASH_GRAPH_DELETED_CODECS) {
            assertFalse(hasRegisteredDynamicCodec(candidate.fqn(), candidate.gameId()),
                    candidate.fqn()
                            + " must restore through S3K SS-entry flash graph generic recreate, not a dynamic codec");
        }
    }

    // =====================================================================
    // S3K CNZ2 cutscene button graph support: parent-side spawned-flash link
    // =====================================================================

    @Test
    void s3kCnz2CutsceneButtonGraphClassesImplementRewindRecreatable() {
        for (CodecDeletionCandidate candidate : S3K_CNZ2_CUTSCENE_BUTTON_GRAPH_DELETED_CODECS) {
            Class<?> cls = loadClass(candidate.fqn());
            assertTrue(RewindRecreatable.class.isAssignableFrom(cls),
                    candidate.fqn() + " must implement RewindRecreatable after S3K CNZ2 button graph deletion");
        }
    }

    @Test
    void s3kCnz2CutsceneButtonGraphClassesHaveNoRegisteredCodec() {
        for (CodecDeletionCandidate candidate : S3K_CNZ2_CUTSCENE_BUTTON_GRAPH_DELETED_CODECS) {
            assertFalse(hasRegisteredDynamicCodec(candidate.fqn(), candidate.gameId()),
                    candidate.fqn()
                            + " must restore through S3K CNZ2 button graph generic recreate, not a dynamic codec");
        }
    }

    // =========================================================================
    // S3K CNZ water-level cork-floor graph support: helper-side cork-floor link
    // =========================================================================

    @Test
    void s3kCnzWaterLevelCorkFloorGraphClassesImplementRewindRecreatable() {
        for (CodecDeletionCandidate candidate : S3K_CNZ_WATER_LEVEL_CORK_FLOOR_GRAPH_DELETED_CODECS) {
            Class<?> cls = loadClass(candidate.fqn());
            assertTrue(RewindRecreatable.class.isAssignableFrom(cls),
                    candidate.fqn()
                            + " must implement RewindRecreatable after S3K CNZ cork-floor graph deletion");
        }
    }

    @Test
    void s3kCnzWaterLevelCorkFloorGraphClassesHaveNoRegisteredCodec() {
        for (CodecDeletionCandidate candidate : S3K_CNZ_WATER_LEVEL_CORK_FLOOR_GRAPH_DELETED_CODECS) {
            assertFalse(hasRegisteredDynamicCodec(candidate.fqn(), candidate.gameId()),
                    candidate.fqn()
                            + " must restore through S3K CNZ cork-floor graph generic recreate, not a dynamic codec");
        }
    }

    /**
     * Returns true if the given FQN has an explicit game-registry dynamic rewind
     * codec. Game registries no longer expose dynamic rewind codecs, so this
     * stays false while the architecture guard prevents the deleted API from
     * returning.
     */
    private static boolean hasRegisteredDynamicCodec(String fqn) {
        return false;
    }

    private static boolean hasRegisteredDynamicCodec(String fqn, GameId gameId) {
        return false;
    }

    // Private helpers

    private static ObjectInstance invokeGenericRecreate(String fqn, int x, int y, GameId gameId) {
        ObjectSpawn spawn = new ObjectSpawn(x, y, 0, 0, 0, false, 0);
        PerObjectRewindSnapshot state = new PerObjectRewindSnapshot(
                false, false, false, 0, 0, 0, 0, false, 0, false, false, 0, -1, null, null, null);
        return invokeGenericRecreateWithState(fqn, spawn, state, gameId);
    }

    private static ObjectInstance invokeGenericRecreateWithState(
            String fqn, ObjectSpawn spawn, PerObjectRewindSnapshot state, GameId gameId) {
        Camera camera = mockCamera();
        ObjectManager[] holder = new ObjectManager[1];
        StubObjectServices stub = new StubObjectServices() {
            @Override public ObjectManager objectManager() { return holder[0]; }
            @Override public Camera camera() { return camera; }
            @Override public SonicConfigurationService configuration() { return DEFAULT_CONFIGURATION; }
            @Override public ObjectRenderManager renderManager() { return INERT_RENDER_MANAGER; }
            @Override public RomByteReader romReader() { return emptyResultsMappingReader(); }
        };
        ObjectRegistry registry = switch (gameId) {
            case S1 -> new Sonic1ObjectRegistry();
            case S2 -> new Sonic2ObjectRegistry();
            case S3K -> new Sonic3kObjectRegistry();
        };
        ObjectManager om = new ObjectManager(
                List.of(), registry, 0, null, null, GraphicsManager.getInstance(), camera, stub);
        holder[0] = om;
        om.reset(0);
        ObjectManagerSnapshot.DynamicObjectEntry entry =
                new ObjectManagerSnapshot.DynamicObjectEntry(fqn, spawn, 0, state);
        DynamicObjectRecreateContext ctx = new DynamicObjectRecreateContext(om);
        ObjectInstance recreated = ObjectRewindDynamicCodecs.genericRecreate(entry, ctx);
        if (recreated instanceof AbstractObjectInstance object) {
            object.setServices(stub);
        }
        return recreated;
    }

    private static S3kResultsScreenObjectInstance newResultsRestoreShell(StubObjectServices services) {
        try {
            var ctor = S3kResultsScreenObjectInstance.class.getDeclaredConstructor();
            ctor.setAccessible(true);
            return ObjectConstructionContext.construct(services, () -> {
                try {
                    return ctor.newInstance();
                } catch (ReflectiveOperationException e) {
                    throw new IllegalStateException(e);
                }
            });
        } catch (NoSuchMethodException e) {
            throw new AssertionError(e);
        }
    }

    private static void invokeNoArgMethod(Object target, String methodName) {
        try {
            var method = target.getClass().getDeclaredMethod(methodName);
            method.setAccessible(true);
            method.invoke(target);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static RomByteReader emptyResultsMappingReader() {
        return new RomByteReader(new byte[Sonic3kConstants.MAP_RESULTS_ADDR + 2]);
    }

    private static ObjectRegistry cnzMinibossParentTestRegistry() {
        Sonic3kObjectRegistry delegate = new Sonic3kObjectRegistry();
        return new ObjectRegistry() {
            @Override
            public ObjectInstance create(ObjectSpawn spawn) {
                if (spawn.objectId() == Sonic3kObjectIds.CNZ_MINIBOSS) {
                    return new CnzMinibossInstance(spawn);
                }
                return delegate.create(spawn);
            }

            @Override
            public void reportCoverage(List<ObjectSpawn> spawns) {
                delegate.reportCoverage(spawns);
            }

            @Override
            public String getPrimaryName(int objectId) {
                return delegate.getPrimaryName(objectId);
            }

            @Override
            public com.openggf.level.objects.ObjectSlotLayout objectSlotLayout() {
                return delegate.objectSlotLayout();
            }

            @Override
            public com.openggf.level.objects.ObjectWindowingStrategy objectWindowingStrategy() {
                return delegate.objectWindowingStrategy();
            }

            @Override
            public List<String> getAliases(int objectId) {
                return delegate.getAliases(objectId);
            }
        };
    }

    private static int readIntRecordComponent(Object record, String componentName) {
        try {
            var method = record.getClass().getDeclaredMethod(componentName);
            method.setAccessible(true);
            return (Integer) method.invoke(record);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Unable to read " + componentName + " from " + record.getClass(), e);
        }
    }

    private static int[] readIntArrayField(Object target, String fieldName) {
        try {
            var field = target.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            return (int[]) field.get(target);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Unable to read " + fieldName + " from " + target.getClass(), e);
        }
    }

    private static int readIntField(Object target, String fieldName) {
        try {
            var field = findField(target.getClass(), fieldName);
            return field.getInt(target);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Unable to read " + fieldName + " from " + target.getClass(), e);
        }
    }

    private static boolean readBooleanField(Object target, String fieldName) {
        try {
            var field = findField(target.getClass(), fieldName);
            return field.getBoolean(target);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Unable to read " + fieldName + " from " + target.getClass(), e);
        }
    }

    private static Object readObjectField(Object target, String fieldName) {
        try {
            var field = findField(target.getClass(), fieldName);
            return field.get(target);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Unable to read " + fieldName + " from " + target.getClass(), e);
        }
    }

    private static <T> T singleLiveObject(ObjectManager objectManager, Class<T> type) {
        List<T> live = liveObjects(objectManager, type);
        assertEquals(1, live.size(), "expected exactly one live " + type.getSimpleName());
        return live.getFirst();
    }

    private static <T> List<T> liveObjects(ObjectManager objectManager, Class<T> type) {
        return objectManager.getActiveObjects().stream()
                .filter(object -> object.getClass() == type && !object.isDestroyed())
                .map(type::cast)
                .toList();
    }

    private record Batch50ParentSpec(Class<?> parentClass, int objectId) {}

    private static Batch50ParentSpec batch50ParentSpec(String childFqn) {
        return switch (childFqn) {
            case "com.openggf.game.sonic3k.objects.badniks.OrbinautBadnikInstance$OrbinautOrbInstance" ->
                    new Batch50ParentSpec(
                            loadClass("com.openggf.game.sonic3k.objects.badniks.OrbinautBadnikInstance"),
                            Sonic3kObjectIds.ORBINAUT);
            case "com.openggf.game.sonic3k.objects.badniks.RibotBadnikInstance$RibotActiveChild" ->
                    new Batch50ParentSpec(
                            loadClass("com.openggf.game.sonic3k.objects.badniks.RibotBadnikInstance"),
                            Sonic3kObjectIds.RIBOT);
            case "com.openggf.game.sonic3k.objects.badniks.StarPointerBadnikInstance$OrbitingPointInstance" ->
                    new Batch50ParentSpec(
                            loadClass("com.openggf.game.sonic3k.objects.badniks.StarPointerBadnikInstance"),
                            Sonic3kObjectIds.STAR_POINTER);
            default -> throw new AssertionError("Unexpected batch-50 child: " + childFqn);
        };
    }

    private record Batch51ParentSpec(Class<?> parentClass, int objectId) {}

    private static Batch51ParentSpec batch51ParentSpec(String childFqn) {
        return switch (childFqn) {
            case "com.openggf.game.sonic2.objects.badniks.BuzzerBadnikInstance$BuzzerFlameChild" ->
                    new Batch51ParentSpec(
                            loadClass("com.openggf.game.sonic2.objects.badniks.BuzzerBadnikInstance"),
                            Sonic2ObjectIds.BUZZER);
            case "com.openggf.game.sonic2.objects.badniks.TurtloidRiderInstance",
                 "com.openggf.game.sonic2.objects.badniks.TurtloidJetInstance" ->
                    new Batch51ParentSpec(
                            loadClass("com.openggf.game.sonic2.objects.badniks.TurtloidBadnikInstance"),
                            Sonic2ObjectIds.TURTLOID);
            default -> throw new AssertionError("Unexpected batch-51 child: " + childFqn);
        };
    }

    private static PerObjectRewindSnapshot batch51State(String childFqn, Object liveParent) {
        PerObjectRewindSnapshot base = new PerObjectRewindSnapshot(
                false, false, false, 0, 0, 0, 0, false, 0, false, false, 0, -1, null, null, null);
        if ("com.openggf.game.sonic2.objects.badniks.BuzzerBadnikInstance$BuzzerFlameChild".equals(childFqn)) {
            assertInstanceOf(AbstractObjectInstance.class, liveParent,
                    "Buzzer flame parent must be an object instance with a slot");
            int parentSlotIndex = ((AbstractObjectInstance) liveParent).getSlotIndex();
            return base.withObjectSubclassExtra(new PerObjectRewindSnapshot.BuzzerFlameRewindExtra(
                    parentSlotIndex, 176, 176, false, 3));
        }
        return base;
    }

    private interface Batch52ParentFactory {
        AbstractObjectInstance create(ObjectSpawn spawn);
    }

    private record Batch52ParentSpec(int objectId, Batch52ParentFactory factory) {
        AbstractObjectInstance createParent(ObjectSpawn spawn) {
            return factory.create(spawn);
        }
    }

    private static Batch52ParentSpec batch52ParentSpec(String childFqn) {
        if (ARZBossPillar.class.getName().equals(childFqn)) {
            return new Batch52ParentSpec(Sonic2ObjectIds.ARZ_BOSS, Sonic2ARZBossInstance::new);
        }
        if (CNZBossElectricBall.class.getName().equals(childFqn)) {
            return new Batch52ParentSpec(Sonic2ObjectIds.CNZ_BOSS, Sonic2CNZBossInstance::new);
        }
        throw new AssertionError("Unexpected batch-52 child: " + childFqn);
    }

    private static void setBossPosition(AbstractObjectInstance boss, int x, int y) {
        Object state = readObjectField(boss, "state");
        setIntField(state, "x", x);
        setIntField(state, "y", y);
        setIntField(state, "xFixed", x << 16);
        setIntField(state, "yFixed", y << 16);
    }

    private static Class<?> loadClass(String fqn) {
        try {
            return Class.forName(fqn);
        } catch (ClassNotFoundException e) {
            throw new AssertionError(e);
        }
    }

    private static void setIntField(Object target, String fieldName, int value) {
        try {
            var field = findField(target.getClass(), fieldName);
            field.setInt(target, value);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Unable to write " + fieldName + " on " + target.getClass(), e);
        }
    }

    private static void setObjectField(Object target, String fieldName, Object value) {
        try {
            var field = findField(target.getClass(), fieldName);
            field.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Unable to write " + fieldName + " on " + target.getClass(), e);
        }
    }

    private static void setBooleanField(Object target, String fieldName, boolean value) {
        try {
            var field = findField(target.getClass(), fieldName);
            field.setBoolean(target, value);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Unable to write " + fieldName + " on " + target.getClass(), e);
        }
    }

    private static ObjectInstance instantiateMadmoleSideDrill(int x, int y, boolean facingLeft) {
        try {
            Class<?> cls = Class.forName(BATCH43_DELETED_CODECS.getFirst().fqn());
            var ctor = cls.getDeclaredConstructor(int.class, int.class, boolean.class);
            ctor.setAccessible(true);
            return (ObjectInstance) ctor.newInstance(x, y, facingLeft);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Unable to construct Madmole side-drill child", e);
        }
    }

    private static java.lang.reflect.Field findField(Class<?> cls, String fieldName)
            throws NoSuchFieldException {
        Class<?> current = cls;
        while (current != null) {
            try {
                var field = current.getDeclaredField(fieldName);
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException ignored) {
                current = current.getSuperclass();
            }
        }
        throw new NoSuchFieldException(fieldName);
    }

    private static Camera mockCamera() {
        return new Camera() {
            @Override public short getX() { return 0; }
            @Override public short getY() { return 0; }
            @Override public short getWidth() { return 320; }
            @Override public short getHeight() { return 224; }
            @Override public boolean isVerticalWrapEnabled() { return false; }
        };
    }

    private static SonicConfigurationService createDefaultConfiguration() {
        SonicConfigurationService config = SonicConfigurationService.createStandalone(
                TestSessionOutputPaths.diagnostics("rewind")
                        .resolve("rewind-scalar-codec-deletion-config"));
        config.resetToDefaults();
        return config;
    }

    private static final class InertObjectArtProvider implements ObjectArtProvider {
        @Override public void loadArtForZone(int zoneIndex) {}
        @Override public PatternSpriteRenderer getRenderer(String key) { return null; }
        @Override public ObjectSpriteSheet getSheet(String key) { return null; }
        @Override public SpriteAnimationSet getAnimations(String key) { return null; }
        @Override public int getZoneData(String key, int zoneIndex) { return -1; }
        @Override public Pattern[] getHudDigitPatterns() { return new Pattern[0]; }
        @Override public Pattern[] getHudTextPatterns() { return new Pattern[0]; }
        @Override public Pattern[] getHudLivesPatterns() { return new Pattern[0]; }
        @Override public Pattern[] getHudLivesNumbers() { return new Pattern[0]; }
        @Override public List<String> getRendererKeys() { return List.of(); }
        @Override public int ensurePatternsCached(GraphicsManager graphicsManager, int baseIndex) {
            return baseIndex;
        }
        @Override public boolean isReady() { return true; }
    }
}
