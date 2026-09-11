package com.openggf.game.sonic3k.objects;

import com.openggf.game.GameServices;
import com.openggf.game.PlayerCharacter;
import com.openggf.game.PowerUpObject;
import com.openggf.game.ShieldType;
import com.openggf.game.rewind.CompositeSnapshot;
import com.openggf.game.rewind.DeletedDynamicRewindCodecs;
import com.openggf.game.rewind.RewindRegistry;
import com.openggf.game.sonic3k.Sonic3kObjectArtKeys;
import com.openggf.game.sonic3k.objects.bosses.HczEndBossEggCapsuleInstance;
import com.openggf.game.sonic3k.objects.bosses.HczEndBossEggCapsuleButton;
import com.openggf.game.sonic3k.objects.bosses.HczEndBossGeyserCutscene;
import com.openggf.game.sonic3k.objects.bosses.IczEndBossEggCapsuleInstance;
import com.openggf.game.sonic3k.objects.bosses.MhzEndBossEggCapsuleInstance;
import com.openggf.game.sonic3k.objects.badniks.S3kBadnikProjectileInstance;
import com.openggf.game.sonic3k.objects.badniks.CaterkillerJrBodyInstance;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.EggPrisonAnimalInstance;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.ShieldObjectInstance;
import com.openggf.level.objects.SubpixelMotion;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.tests.HeadlessTestFixture;
import com.openggf.tests.TestEnvironment;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Session-level rewind coverage for self-contained S3K transient/effect objects
 * whose handwritten codecs are being deleted in favor of Phase-2 generic recreate.
 */
@RequiresRom(SonicGame.SONIC_3K)
class TestS3kSelfContainedTransientRewind {

    private static final int ZONE_AIZ = 0;
    private static final int ACT_2 = 1;
    private static final String BLASTOID_PROJECTILE_CLASS =
            "com.openggf.game.sonic3k.objects.badniks.BlastoidBadnikInstance$BlastoidProjectile";
    private static final String SNALE_BLASTER_PROJECTILE_CLASS =
            "com.openggf.game.sonic3k.objects.badniks.SnaleBlasterBadnikInstance$SnaleBlasterProjectile";
    private static final String SPIKER_SPIKE_PROJECTILE_CLASS =
            "com.openggf.game.sonic3k.objects.badniks.SpikerBadnikInstance$SpikerSpikeProjectile";
    private static final String MGZ_HEAD_TRIGGER_STONE_CHIP_CLASS =
            "com.openggf.game.sonic3k.objects.MGZHeadTriggerObjectInstance$HeadTriggerStoneChipChild";
    private static final String MGZ_CEILING_SPIRE_CLASS =
            "com.openggf.game.sonic3k.objects.MgzMinibossInstance$CeilingSpireChild";
    private static final String ICZ_END_BOSS_ESCAPE_SHIP_CLASS =
            "com.openggf.game.sonic3k.objects.bosses.IczEndBossInstance$IczEndBossRobotnikEscapeShip";
    private static final String AIZ2_HIGH_PRIORITY_ANIMAL_CLASS =
            "com.openggf.game.sonic3k.objects.Aiz2EndEggCapsuleInstance$HighPriorityAnimal";
    private static final String AIZ2_RESULTS_SCREEN_CLASS =
            "com.openggf.game.sonic3k.objects.Aiz2EndEggCapsuleInstance$Aiz2ResultsScreenObjectInstance";

    @AfterEach
    void cleanup() {
        TestEnvironment.resetAll();
    }

    @Test
    void phase2BatchCandidatesHaveNoExplicitDynamicCodec() {
        assertNoRegisteredS3kDynamicCodec(S3kSignpostSparkleChild.class);
        assertNoRegisteredS3kDynamicCodec(S3kAirCountdownObjectInstance.class);
        assertNoRegisteredS3kDynamicCodec(CaterkillerJrBodyInstance.class);
        assertNoRegisteredS3kDynamicCodec(AizBgTreeInstance.class);
        assertNoRegisteredS3kDynamicCodec(Aiz2BossEndSequenceController.class);
        assertNoRegisteredS3kDynamicCodec(CutsceneKnucklesLbz1ThrownBomb.class);
        assertNoRegisteredS3kDynamicCodec(S3kBadnikProjectileInstance.class);
        assertNoRegisteredS3kDynamicCodec(MhzShipSequenceControllerInstance.class);
        assertNoRegisteredS3kDynamicCodec(S3kBossDefeatSignpostFlow.class);
        assertNoRegisteredS3kDynamicCodec(Aiz2EndEggCapsuleInstance.class);
        assertNoRegisteredS3kDynamicCodec(HczEndBossEggCapsuleInstance.class);
        assertNoRegisteredS3kDynamicCodec(IczEndBossEggCapsuleInstance.class);
        assertNoRegisteredS3kDynamicCodec(MhzEndBossEggCapsuleInstance.class);
        assertNoRegisteredS3kDynamicCodec(Mgz2EndEggCapsuleInstance.class);
        assertNoRegisteredS3kDynamicCodec(S3kSignpostInstance.class);
        assertNoRegisteredS3kDynamicCodec(HczEndBossGeyserCutscene.class);
        assertNoRegisteredS3kDynamicCodec(Mgz2CapsuleAnimalInstance.class);
        assertNoRegisteredS3kDynamicCodec(Mgz2ResultsScreenObjectInstance.class);
        assertNoRegisteredS3kDynamicCodec(AizHollowTreeObjectInstance.AizTreeRevealControlObjectInstance.class);
        assertNoRegisteredS3kDynamicCodec(classForName(MGZ_HEAD_TRIGGER_STONE_CHIP_CLASS));
        assertNoRegisteredS3kDynamicCodec(classForName(MGZ_CEILING_SPIRE_CLASS));
        assertNoRegisteredS3kDynamicCodec(classForName(BLASTOID_PROJECTILE_CLASS));
        assertNoRegisteredS3kDynamicCodec(classForName(SNALE_BLASTER_PROJECTILE_CLASS));
        assertNoRegisteredS3kDynamicCodec(classForName(SPIKER_SPIKE_PROJECTILE_CLASS));
        assertNoRegisteredS3kDynamicCodec(classForName(ICZ_END_BOSS_ESCAPE_SHIP_CLASS));
        assertNoRegisteredS3kDynamicCodec(FireShieldObjectInstance.class);
        assertNoRegisteredS3kDynamicCodec(LightningShieldObjectInstance.class);
        assertNoRegisteredS3kDynamicCodec(BubbleShieldObjectInstance.class);
        assertNoRegisteredS3kDynamicCodec(InstaShieldObjectInstance.class);
        assertNoRegisteredS3kDynamicCodec(Sonic3kInvincibilityStarsObjectInstance.class);
        assertNoRegisteredS3kDynamicCodec(classForName(AIZ2_HIGH_PRIORITY_ANIMAL_CLASS));
        assertNoRegisteredS3kDynamicCodec(classForName(AIZ2_RESULTS_SCREEN_CLASS));
    }

    @Test
    void playerBoundFireShieldRestoresThroughSessionSnapshot() throws Exception {
        assertElementalShieldRestoresThroughSessionSnapshot(
                ShieldType.FIRE, FireShieldObjectInstance.class, true);
    }

    @Test
    void playerBoundLightningShieldRestoresThroughSessionSnapshot() throws Exception {
        assertElementalShieldRestoresThroughSessionSnapshot(
                ShieldType.LIGHTNING, LightningShieldObjectInstance.class, true);
    }

    @Test
    void playerBoundBubbleShieldRestoresThroughSessionSnapshot() throws Exception {
        assertElementalShieldRestoresThroughSessionSnapshot(
                ShieldType.BUBBLE, BubbleShieldObjectInstance.class, false);
    }

    @Test
    void persistentInstaShieldRestoresThroughSessionSnapshot() throws Exception {
        HeadlessTestFixture fixture = HeadlessTestFixture.builder()
                .withZoneAndAct(ZONE_AIZ, ACT_2)
                .build();

        RewindRegistry registry = fixture.gameplayMode().getRewindRegistry();
        assertNotNull(registry, "RewindRegistry must be available after S3K boot");

        ObjectManager objectManager = GameServices.level().getObjectManager();
        assertNotNull(objectManager, "ObjectManager must be available for S3K");

        AbstractPlayableSprite player = fixture.sprite();
        assertNotNull(player.getPowerUpSpawner(),
                "S3K player must have a production power-up spawner");
        player.tickStatus();

        InstaShieldObjectInstance capturedShield = assertInstanceOf(
                InstaShieldObjectInstance.class,
                player.getInstaShieldObject(),
                "precondition: S3K Sonic should own a persistent insta-shield object");
        assertEquals(1, countLive(objectManager, InstaShieldObjectInstance.class),
                "precondition: exactly one persistent insta-shield object is live before capture");

        capturedShield.triggerAttack();
        for (int frame = 0; frame < 3; frame++) {
            capturedShield.update(frame, player);
        }

        int capturedSlot = capturedShield.getSlotIndex();
        int capturedAnim = intField(capturedShield, "currentAnimId");
        int capturedFrame = intField(capturedShield, "frameIndex");
        int capturedDelay = intField(capturedShield, "delayCounter");
        int capturedMapping = intField(capturedShield, "currentMappingFrame");

        CompositeSnapshot snapshot = registry.capture();
        assertNotNull(snapshot, "capture() must return a snapshot");

        objectManager.removeDynamicObject(capturedShield);
        InstaShieldObjectInstance replacement = assertInstanceOf(
                InstaShieldObjectInstance.class,
                player.getPowerUpSpawner().createInstaShield(player),
                "diverge step should create a replacement through the production spawner");
        player.setInstaShieldObject(replacement);
        setIntField(replacement, "currentAnimId", 0);
        setIntField(replacement, "frameIndex", 99);
        setIntField(replacement, "delayCounter", 98);
        setIntField(replacement, "currentMappingFrame", 97);
        assertEquals(0, countLive(objectManager, InstaShieldObjectInstance.class),
                "diverge step must remove the captured insta-shield from ObjectManager");

        registry.restore(snapshot);

        List<InstaShieldObjectInstance> restored = liveObjects(
                objectManager, InstaShieldObjectInstance.class);
        assertEquals(1, restored.size(),
                "post-restore player refresh must register exactly one persistent insta-shield");
        InstaShieldObjectInstance restoredShield = restored.get(0);
        assertSame(restoredShield, player.getInstaShieldObject(),
                "restored player insta-shield link must point at the live ObjectManager object");
        assertEquals(capturedSlot, restoredShield.getSlotIndex(),
                "restored insta-shield must consume the captured dynamic slot");
        assertEquals(capturedAnim, intField(restoredShield, "currentAnimId"),
                "restored insta-shield must keep captured animation id");
        assertEquals(capturedFrame, intField(restoredShield, "frameIndex"),
                "restored insta-shield must keep captured animation frame index");
        assertEquals(capturedDelay, intField(restoredShield, "delayCounter"),
                "restored insta-shield must keep captured animation delay counter");
        assertEquals(capturedMapping, intField(restoredShield, "currentMappingFrame"),
                "restored insta-shield must keep captured mapping frame");
        assertFalse(restoredShield.isDestroyed(), "restored insta-shield must remain live");
        assertSame(player, shieldPlayer(restoredShield),
                "restored insta-shield must remain bound to the same live player");
    }

    @Test
    void aiz2CapsulePrivateChildrenRestoreThroughSessionSnapshot() throws Exception {
        HeadlessTestFixture fixture = HeadlessTestFixture.builder()
                .withZoneAndAct(ZONE_AIZ, ACT_2)
                .build();

        RewindRegistry registry = fixture.gameplayMode().getRewindRegistry();
        assertNotNull(registry, "RewindRegistry must be available after AIZ2 boot");

        ObjectManager objectManager = GameServices.level().getObjectManager();
        assertNotNull(objectManager, "ObjectManager must be available for AIZ2");

        AbstractPlayableSprite player = fixture.sprite();
        player.setAir(false);
        player.setXSpeed((short) 0);
        player.setYSpeed((short) 0);
        player.setGSpeed((short) 0);

        Class<? extends AbstractObjectInstance> highPriorityAnimalClass =
                classForName(AIZ2_HIGH_PRIORITY_ANIMAL_CLASS);
        Class<? extends AbstractObjectInstance> aiz2ResultsScreenClass =
                classForName(AIZ2_RESULTS_SCREEN_CLASS);

        Aiz2EndEggCapsuleInstance capsule = objectManager.createDynamicObject(
                () -> new Aiz2EndEggCapsuleInstance(0x49EA, 0x0162));

        invokeOpenCapsule(capsule);
        assertEquals(9, countLive(objectManager, highPriorityAnimalClass),
                "AIZ2 capsule open must spawn the high-priority animal family");

        setIntFieldInHierarchy(capsule, "postOpenTimer", 0);
        capsule.update(0, player);
        capsule.update(1, player);
        assertEquals(1, countLive(objectManager, aiz2ResultsScreenClass),
                "AIZ2 capsule must spawn its specialized results-screen child");

        Map<Class<?>, Map<String, Object>> capturedState = new LinkedHashMap<>();
        capturedState.put(highPriorityAnimalClass,
                simpleRewindFieldValues(firstLive(objectManager, highPriorityAnimalClass)));
        capturedState.put(aiz2ResultsScreenClass,
                simpleRewindFieldValues(firstLive(objectManager, aiz2ResultsScreenClass)));

        CompositeSnapshot snapshot = registry.capture();
        assertNotNull(snapshot, "capture() must return a snapshot");

        removeLiveObjects(objectManager, highPriorityAnimalClass);
        removeLiveObjects(objectManager, aiz2ResultsScreenClass);
        objectManager.removeDynamicObject(capsule);
        assertEquals(0, countLive(objectManager, highPriorityAnimalClass),
                "diverge step must remove the AIZ2 high-priority animals");
        assertEquals(0, countLive(objectManager, aiz2ResultsScreenClass),
                "diverge step must remove the AIZ2 results child");

        registry.restore(snapshot);

        assertEquals(9, countLive(objectManager, highPriorityAnimalClass),
                "restore must recreate all AIZ2 high-priority animal children");
        assertEquals(1, countLive(objectManager, aiz2ResultsScreenClass),
                "restore must recreate the specialized AIZ2 results child");
        assertEquals(0, countLive(objectManager, S3kResultsScreenObjectInstance.class),
                "AIZ2 results child must not collapse to the base S3K results type");
        assertEquals(capturedState.get(highPriorityAnimalClass),
                simpleRewindFieldValues(firstLive(objectManager, highPriorityAnimalClass)),
                "restored high-priority animal scalar state must match the captured state");
        assertEquals(capturedState.get(aiz2ResultsScreenClass),
                simpleRewindFieldValues(firstLive(objectManager, aiz2ResultsScreenClass)),
                "restored AIZ2 results scalar state must match the captured state");
    }

    @Test
    void playerBoundInvincibilityStarsRestoreThroughSessionSnapshot() throws Exception {
        HeadlessTestFixture fixture = HeadlessTestFixture.builder()
                .withZoneAndAct(ZONE_AIZ, ACT_2)
                .build();

        RewindRegistry registry = fixture.gameplayMode().getRewindRegistry();
        assertNotNull(registry, "RewindRegistry must be available after S3K boot");

        ObjectManager objectManager = GameServices.level().getObjectManager();
        assertNotNull(objectManager, "ObjectManager must be available for S3K");

        AbstractPlayableSprite player = fixture.sprite();
        assertNotNull(player.getPowerUpSpawner(),
                "S3K player must have a production power-up spawner");
        player.giveInvincibility();

        Sonic3kInvincibilityStarsObjectInstance capturedStars = assertInstanceOf(
                Sonic3kInvincibilityStarsObjectInstance.class,
                player.getInvincibilityObject(),
                "precondition: S3K invincibility should create the concrete stars object");
        assertEquals(1, countLive(objectManager, Sonic3kInvincibilityStarsObjectInstance.class),
                "precondition: exactly one S3K invincibility-stars object is live before capture");

        for (int frame = 0; frame < 4; frame++) {
            capturedStars.update(frame, player);
        }

        int capturedSlot = capturedStars.getSlotIndex();
        int capturedParentAngle = intField(capturedStars, "parentAngle");
        int capturedParentAnimIndex = intField(capturedStars, "parentAnimIndex");
        int[] capturedChildAngles = intArrayField(capturedStars, "childAngles");
        int[] capturedChildAnimIndices = intArrayField(capturedStars, "childAnimIndices");

        CompositeSnapshot snapshot = registry.capture();
        assertNotNull(snapshot, "capture() must return a snapshot");

        objectManager.removeDynamicObject(capturedStars);
        setPowerUpObjectField(player, "invincibilityObject", null);
        player.setInvincibleFrames(0);
        assertEquals(0, countLive(objectManager, Sonic3kInvincibilityStarsObjectInstance.class),
                "diverge step must remove the captured S3K invincibility stars");

        registry.restore(snapshot);

        List<Sonic3kInvincibilityStarsObjectInstance> restored =
                liveObjects(objectManager, Sonic3kInvincibilityStarsObjectInstance.class);
        assertEquals(1, restored.size(),
                "post-restore player refresh must recreate exactly one S3K invincibility-stars object");
        Sonic3kInvincibilityStarsObjectInstance restoredStars = restored.get(0);
        assertSame(restoredStars, player.getInvincibilityObject(),
                "restored player invincibility link must point at the live ObjectManager stars");
        assertEquals(capturedSlot, restoredStars.getSlotIndex(),
                "restored S3K invincibility stars must consume the captured dynamic slot");
        assertEquals(capturedParentAngle, intField(restoredStars, "parentAngle"),
                "restored S3K invincibility stars must keep captured parent orbit angle");
        assertEquals(capturedParentAnimIndex, intField(restoredStars, "parentAnimIndex"),
                "restored S3K invincibility stars must keep captured parent animation index");
        assertArrayEquals(capturedChildAngles, intArrayField(restoredStars, "childAngles"),
                "restored S3K invincibility stars must keep captured child orbit angles");
        assertArrayEquals(capturedChildAnimIndices, intArrayField(restoredStars, "childAnimIndices"),
                "restored S3K invincibility stars must keep captured child animation indices");
        assertFalse(restoredStars.isDestroyed(), "restored S3K invincibility stars must remain live");
    }

    private static <T extends ShieldObjectInstance> void assertElementalShieldRestoresThroughSessionSnapshot(
            ShieldType shieldType, Class<T> shieldClass, boolean canSetAnimation) throws Exception {
        HeadlessTestFixture fixture = HeadlessTestFixture.builder()
                .withZoneAndAct(ZONE_AIZ, ACT_2)
                .build();

        RewindRegistry registry = fixture.gameplayMode().getRewindRegistry();
        assertNotNull(registry, "RewindRegistry must be available after S3K boot");

        ObjectManager objectManager = GameServices.level().getObjectManager();
        assertNotNull(objectManager, "ObjectManager must be available for S3K");

        AbstractPlayableSprite player = fixture.sprite();
        player.giveShield(shieldType);
        T capturedShield = assertInstanceOf(shieldClass, player.getShieldObject(),
                        "precondition: S3K " + shieldType
                                + " shield should create the concrete shield object");
        if (canSetAnimation) {
            capturedShield.getClass().getMethod("setAnimation", int.class)
                    .invoke(capturedShield, 1);
        }

        int capturedSlot = capturedShield.getSlotIndex();
        int capturedAnim = intField(capturedShield, "currentAnimId");
        assertEquals(1, countLive(objectManager, shieldClass),
                "precondition: exactly one " + shieldType + " shield fixture is live before capture");

        CompositeSnapshot snapshot = registry.capture();
        assertNotNull(snapshot, "capture() must return a snapshot");

        objectManager.removeDynamicObject(capturedShield);
        player.removeShield();
        assertEquals(0, countLive(objectManager, shieldClass),
                "diverge step must remove the " + shieldType + " shield object");

        registry.restore(snapshot);

        List<T> restored = liveObjects(objectManager, shieldClass);
        assertEquals(1, restored.size(),
                "post-restore player refresh must recreate exactly one " + shieldType + " shield");
        T restoredShield = restored.get(0);
        PowerUpObject playerShield = player.getShieldObject();
        assertSame(restoredShield, playerShield,
                "restored player shield link must point at the live ObjectManager shield");
        assertEquals(capturedSlot, restoredShield.getSlotIndex(),
                "restored shield must consume the captured dynamic slot");
        assertEquals(capturedAnim, intField(restoredShield, "currentAnimId"),
                "restored shield must keep captured animation state");
        assertEquals(shieldType, player.getShieldType(),
                "restored player state must still request the captured shield type");
        assertFalse(restoredShield.isDestroyed(), "restored shield must remain live");
        assertTrue(restoredShield.isShieldFor(player, shieldType),
                "restored concrete shield must be bound to the same live player");
    }

    @Test
    void iczEndBossEscapeShipRestoresThroughSessionSnapshot() throws Exception {
        HeadlessTestFixture fixture = HeadlessTestFixture.builder()
                .withZoneAndAct(ZONE_AIZ, ACT_2)
                .build();

        RewindRegistry registry = fixture.gameplayMode().getRewindRegistry();
        assertNotNull(registry, "RewindRegistry must be available after S3K boot");

        ObjectManager objectManager = GameServices.level().getObjectManager();
        assertNotNull(objectManager, "ObjectManager must be available for S3K");

        int baseX = fixture.camera().getX() + 0x120;
        int baseY = fixture.camera().getY() + 0x20;
        AbstractObjectInstance.updateCameraBounds(
                fixture.camera().getX(),
                fixture.camera().getY(),
                fixture.camera().getX() + fixture.camera().getWidth(),
                fixture.camera().getY() + fixture.camera().getHeight(),
                0);

        Class<? extends AbstractObjectInstance> escapeShipType =
                classForName(ICZ_END_BOSS_ESCAPE_SHIP_CLASS);
        AbstractObjectInstance escapeShip = objectManager.createDynamicObject(
                () -> instantiatePrivateDynamic(
                        ICZ_END_BOSS_ESCAPE_SHIP_CLASS,
                        new Class<?>[]{int.class, int.class},
                        baseX, baseY));

        for (int frame = 0; frame < 3; frame++) {
            escapeShip.update(frame, fixture.sprite());
            assertFalse(escapeShip.isDestroyed(),
                    "ICZ end-boss escape ship should survive setup frame " + frame);
        }
        assertEquals(1, countLive(objectManager, escapeShipType),
                "precondition: exactly one ICZ end-boss escape ship fixture is live");

        Map<Class<?>, Map<String, Object>> capturedState = new LinkedHashMap<>();
        capturedState.put(escapeShip.getClass(), simpleRewindFieldValues(escapeShip));

        CompositeSnapshot snapshot = registry.capture();
        assertNotNull(snapshot, "capture() must return a snapshot");

        objectManager.removeDynamicObject(escapeShip);
        assertEquals(0, countLive(objectManager, escapeShipType),
                "diverge step must remove the ICZ end-boss escape ship");

        registry.restore(snapshot);

        assertSimpleStateRoundTrip(objectManager, escapeShipType, capturedState);
    }

    @Test
    void selfContainedTransientChildrenRestoreThroughSessionSnapshot() throws Exception {
        HeadlessTestFixture fixture = HeadlessTestFixture.builder()
                .withZoneAndAct(ZONE_AIZ, ACT_2)
                .build();

        RewindRegistry registry = fixture.gameplayMode().getRewindRegistry();
        assertNotNull(registry, "RewindRegistry must be available after S3K boot");

        ObjectManager objectManager = GameServices.level().getObjectManager();
        assertNotNull(objectManager, "ObjectManager must be available for S3K");

        int baseX = fixture.camera().getX() + 160;
        int baseY = fixture.camera().getY() + 112;
        AbstractObjectInstance.updateCameraBounds(
                fixture.camera().getX(),
                fixture.camera().getY(),
                fixture.camera().getX() + fixture.camera().getWidth(),
                fixture.camera().getY() + fixture.camera().getHeight(),
                0);

        AizRockFragmentChild aizRockFragment = objectManager.createDynamicObject(
                () -> new AizRockFragmentChild(
                        new ObjectSpawn(baseX - 0x60, baseY - 0x20, 0, 0, 0, false, 0),
                        0x80, -0x100, 1, 2));
        CnzMinibossDebrisChild cnzDebris = objectManager.createDynamicObject(
                () -> new CnzMinibossDebrisChild(baseX - 0x20, baseY - 0x30, 3));
        S3kBossExplosionChild bossExplosion = objectManager.createDynamicObject(
                () -> new S3kBossExplosionChild(baseX + 0x20, baseY - 0x20));
        MhzPollenParticleInstance pollen = objectManager.createDynamicObject(
                () -> new MhzPollenParticleInstance(
                        baseX + 0x30, baseY - 0x10, 0x80, -0x100, 2, 0x30,
                        MhzPollenParticleInstance.ArtMode.BIG_LEAF, true));
        LightningSparkObjectInstance lightningSpark = objectManager.createDynamicObject(
                () -> new LightningSparkObjectInstance(baseX + 0x40, baseY, 0x100, -0x100, null, null));
        RockDebrisChild rockDebris = objectManager.createDynamicObject(
                () -> new RockDebrisChild(
                        new ObjectSpawn(baseX + 0x50, baseY + 0x10, 0, 0, 0, false, 0),
                        -0x80, -0x100, 2, Sonic3kObjectArtKeys.AIZ2_ROCK));
        MGZHeadTriggerProjectileInstance mgzHeadProjectile = objectManager.createDynamicObject(
                () -> new MGZHeadTriggerProjectileInstance(baseX + 0x60, baseY + 0x18, -0x200, true));
        SongFadeTransitionInstance songFade = objectManager.createDynamicObject(
                () -> new SongFadeTransitionInstance(30, 0x8A));
        EggPrisonAnimalInstance eggPrisonAnimal = objectManager.createDynamicObject(
                () -> new EggPrisonAnimalInstance(
                        new ObjectSpawn(baseX + 0x90, baseY + 0x30, 0x28, 0, 0, false, 0),
                        8, 1));
        S3kSignpostSparkleChild signpostSparkle = objectManager.createDynamicObject(
                () -> new S3kSignpostSparkleChild(baseX + 0xA0, baseY + 0x38));
        S3kAirCountdownObjectInstance airCountdown = objectManager.createDynamicObject(
                () -> new S3kAirCountdownObjectInstance(baseX + 0x10, baseY, 0x06, 0x20, 0x10));
        CaterkillerJrBodyInstance caterkillerBody = objectManager.createDynamicObject(
                () -> CaterkillerJrBodyInstance.forRewindRecreate(
                        new ObjectSpawn(baseX + 0xC0, baseY + 0x48, 0, 0, 0, false, 0)));
        AizBgTreeInstance bgTree = objectManager.createDynamicObject(
                () -> new AizBgTreeInstance(0x1200));
        Aiz2BossEndSequenceController endSequence = objectManager.createDynamicObject(
                () -> new Aiz2BossEndSequenceController(baseX + 0xD0, baseY + 0x50));
        CutsceneKnucklesLbz1ThrownBomb thrownBomb = objectManager.createDynamicObject(
                () -> new CutsceneKnucklesLbz1ThrownBomb(baseX + 0xE0, baseY + 0x58));
        S3kBadnikProjectileInstance badnikProjectile = objectManager.createDynamicObject(
                () -> S3kBadnikProjectileInstance.forRewindRecreate(
                        new ObjectSpawn(baseX + 0x30, baseY + 0x60, 0, 0, 0, false, 0)));
        MhzShipSequenceControllerInstance mhzShipController = objectManager.createDynamicObject(
                () -> new MhzShipSequenceControllerInstance(0x04C0, 0x4000));
        S3kBossDefeatSignpostFlow signpostFlow = objectManager.createDynamicObject(
                () -> new S3kBossDefeatSignpostFlow(
                        baseX + 0xF0, 0, S3kBossDefeatSignpostFlow.CleanupAction.NONE));
        Aiz2EndEggCapsuleInstance aiz2EggCapsule = objectManager.createDynamicObject(
                () -> new Aiz2EndEggCapsuleInstance(baseX + 0x100, baseY + 0x68));
        HczEndBossEggCapsuleInstance hczEggCapsule = objectManager.createDynamicObject(
                () -> new HczEndBossEggCapsuleInstance(baseX + 0x120, baseY + 0x70));
        IczEndBossEggCapsuleInstance iczEggCapsule = objectManager.createDynamicObject(
                () -> new IczEndBossEggCapsuleInstance(baseX + 0x140, baseY + 0x78));
        MhzEndBossEggCapsuleInstance mhzEggCapsule = objectManager.createDynamicObject(
                () -> new MhzEndBossEggCapsuleInstance(baseX + 0x160, baseY + 0x80));
        Mgz2EndEggCapsuleInstance mgz2EggCapsule = objectManager.createDynamicObject(
                () -> new Mgz2EndEggCapsuleInstance(baseX + 0x180, baseY + 0x88));
        S3kSignpostInstance signpost = objectManager.createDynamicObject(
                () -> new S3kSignpostInstance(baseX + 0x1A0, 1));
        HczEndBossGeyserCutscene hczGeyserCutscene = objectManager.createDynamicObject(
                () -> new HczEndBossGeyserCutscene(baseX + 0x1C0, baseY + 0xA0));
        Mgz2CapsuleAnimalInstance mgz2CapsuleAnimal = objectManager.createDynamicObject(
                () -> new Mgz2CapsuleAnimalInstance(
                        new ObjectSpawn(baseX + 0x1E0, baseY + 0xA8, 0, 0, 0, false, 0),
                        6, 1, 0x18));
        Mgz2ResultsScreenObjectInstance mgz2Results = objectManager.createDynamicObject(
                () -> new Mgz2ResultsScreenObjectInstance(PlayerCharacter.KNUCKLES, 1));
        AizHollowTreeObjectInstance.AizTreeRevealControlObjectInstance aizTreeRevealControl =
                objectManager.createDynamicObject(
                        () -> new AizHollowTreeObjectInstance.AizTreeRevealControlObjectInstance(
                                baseX + 0x200, baseY + 0xB0));
        AbstractObjectInstance mgzHeadTriggerStoneChip = objectManager.createDynamicObject(
                () -> instantiatePrivateDynamic(
                        MGZ_HEAD_TRIGGER_STONE_CHIP_CLASS,
                        new Class<?>[]{int.class, int.class, boolean.class},
                        baseX + 0x220, baseY + 0xB8, true));
        AbstractObjectInstance mgzCeilingSpire = objectManager.createDynamicObject(
                () -> instantiatePrivateDynamic(
                        MGZ_CEILING_SPIRE_CLASS,
                        new Class<?>[]{int.class, int.class, int.class},
                        baseX + 0x240, baseY + 0xC0, 0));
        AbstractObjectInstance blastoidProjectile = objectManager.createDynamicObject(
                () -> instantiatePrivateDynamic(
                        BLASTOID_PROJECTILE_CLASS,
                        new Class<?>[]{ObjectSpawn.class, int.class, int.class, int.class, int.class},
                        new ObjectSpawn(baseX + 0x20, baseY + 0x18, 0, 0, 0, false, 0),
                        baseX + 0x20, baseY + 0x18, 0x180, -0x40));
        AbstractObjectInstance snaleBlasterProjectile = objectManager.createDynamicObject(
                () -> instantiatePrivateDynamic(
                        SNALE_BLASTER_PROJECTILE_CLASS,
                        new Class<?>[]{
                                ObjectSpawn.class, int.class, int.class, int.class, int.class, boolean.class},
                        new ObjectSpawn(baseX + 0x40, baseY + 0x20, 0, 0, 1, false, 0),
                        baseX + 0x40, baseY + 0x20, -0x140, 0x20, true));
        AbstractObjectInstance spikerSpikeProjectile = objectManager.createDynamicObject(
                () -> instantiatePrivateDynamic(
                        SPIKER_SPIKE_PROJECTILE_CLASS,
                        new Class<?>[]{
                                classForName("com.openggf.game.sonic3k.objects.badniks.SpikerBadnikInstance"),
                                int.class, int.class, int.class, int.class, boolean.class},
                        new com.openggf.game.sonic3k.objects.badniks.SpikerBadnikInstance(
                                new ObjectSpawn(baseX + 0x60, baseY + 0x28, 0, 0, 0, false, 0)),
                        baseX + 0x60, baseY + 0x28, 0x100, -0x180, false));

        List<AbstractObjectInstance> tracked = List.of(
                aizRockFragment,
                cnzDebris,
                bossExplosion,
                pollen,
                lightningSpark,
                rockDebris,
                mgzHeadProjectile,
                songFade,
                eggPrisonAnimal,
                signpostSparkle,
                airCountdown,
                caterkillerBody,
                bgTree,
                endSequence,
                thrownBomb,
                badnikProjectile,
                mhzShipController,
                signpostFlow,
                aiz2EggCapsule,
                hczEggCapsule,
                iczEggCapsule,
                mhzEggCapsule,
                mgz2EggCapsule,
                signpost,
                hczGeyserCutscene,
                mgz2CapsuleAnimal,
                mgz2Results,
                aizTreeRevealControl,
                mgzHeadTriggerStoneChip,
                mgzCeilingSpire,
                blastoidProjectile,
                snaleBlasterProjectile,
                spikerSpikeProjectile);

        for (int frame = 0; frame < 3; frame++) {
            for (AbstractObjectInstance instance : tracked) {
                if (instance != airCountdown
                        && instance != endSequence
                        && instance != signpostFlow
                        && instance != signpost
                        && instance != hczGeyserCutscene
                        && instance != mgz2CapsuleAnimal
                        && instance != mgz2Results) {
                    instance.update(frame, fixture.sprite());
                }
                assertFalse(instance.isDestroyed(),
                        instance.getClass().getSimpleName() + " should survive setup frame " + frame);
            }
        }

        assertEquals(1, countLive(objectManager, AizRockFragmentChild.class),
                "precondition: exactly one AIZ rock fragment fixture is live");
        assertEquals(1, countLive(objectManager, CnzMinibossDebrisChild.class),
                "precondition: exactly one CNZ miniboss debris fixture is live");
        assertEquals(1, countLive(objectManager, S3kBossExplosionChild.class),
                "precondition: exactly one boss explosion fixture is live");
        assertEquals(1, countLive(objectManager, MhzPollenParticleInstance.class),
                "precondition: exactly one MHZ pollen fixture is live");
        assertEquals(1, countLive(objectManager, LightningSparkObjectInstance.class),
                "precondition: exactly one lightning spark fixture is live");
        assertEquals(1, countLive(objectManager, RockDebrisChild.class),
                "precondition: exactly one rock debris fixture is live");
        assertEquals(1, countLive(objectManager, MGZHeadTriggerProjectileInstance.class),
                "precondition: exactly one MGZ head projectile fixture is live");
        assertEquals(1, countLive(objectManager, SongFadeTransitionInstance.class),
                "precondition: exactly one song fade fixture is live");
        assertEquals(1, countLive(objectManager, EggPrisonAnimalInstance.class),
                "precondition: exactly one egg-prison animal fixture is live");
        assertEquals(1, countLive(objectManager, S3kSignpostSparkleChild.class),
                "precondition: exactly one signpost sparkle fixture is live");
        assertEquals(1, countLive(objectManager, S3kAirCountdownObjectInstance.class),
                "precondition: exactly one air countdown fixture is live");
        assertEquals(1, countLive(objectManager, CaterkillerJrBodyInstance.class),
                "precondition: exactly one Caterkiller Jr body fixture is live");
        assertEquals(1, countLive(objectManager, AizBgTreeInstance.class),
                "precondition: exactly one AIZ background tree fixture is live");
        assertEquals(1, countLive(objectManager, Aiz2BossEndSequenceController.class),
                "precondition: exactly one AIZ2 end-sequence fixture is live");
        assertEquals(1, countLive(objectManager, CutsceneKnucklesLbz1ThrownBomb.class),
                "precondition: exactly one LBZ1 thrown bomb fixture is live");
        assertEquals(1, countLive(objectManager, S3kBadnikProjectileInstance.class),
                "precondition: exactly one S3K badnik projectile fixture is live");
        assertEquals(1, countLive(objectManager, MhzShipSequenceControllerInstance.class),
                "precondition: exactly one MHZ ship controller fixture is live");
        assertEquals(1, countLive(objectManager, S3kBossDefeatSignpostFlow.class),
                "precondition: exactly one S3K boss-defeat signpost flow fixture is live");
        assertEquals(1, countLive(objectManager, Aiz2EndEggCapsuleInstance.class),
                "precondition: exactly one AIZ2 egg capsule fixture is live");
        assertEquals(1, countLive(objectManager, HczEndBossEggCapsuleInstance.class),
                "precondition: exactly one HCZ egg capsule fixture is live");
        assertEquals(1, countLive(objectManager, HczEndBossEggCapsuleButton.class),
                "precondition: the HCZ egg capsule must own one separate button slot");
        assertEquals(1, countLive(objectManager, IczEndBossEggCapsuleInstance.class),
                "precondition: exactly one ICZ egg capsule fixture is live");
        assertEquals(1, countLive(objectManager, MhzEndBossEggCapsuleInstance.class),
                "precondition: exactly one MHZ egg capsule fixture is live");
        assertEquals(1, countLive(objectManager, Mgz2EndEggCapsuleInstance.class),
                "precondition: exactly one MGZ2 egg capsule fixture is live");
        assertEquals(1, countLive(objectManager, S3kSignpostInstance.class),
                "precondition: exactly one S3K signpost fixture is live");
        assertEquals(1, countLive(objectManager, HczEndBossGeyserCutscene.class),
                "precondition: exactly one HCZ geyser cutscene fixture is live");
        assertEquals(1, countLive(objectManager, Mgz2CapsuleAnimalInstance.class),
                "precondition: exactly one MGZ2 capsule animal fixture is live");
        assertEquals(1, countLive(objectManager, Mgz2ResultsScreenObjectInstance.class),
                "precondition: exactly one MGZ2 results screen fixture is live");
        assertEquals(1, countLive(
                        objectManager, AizHollowTreeObjectInstance.AizTreeRevealControlObjectInstance.class),
                "precondition: exactly one AIZ tree-reveal control fixture is live");
        assertEquals(1, countLive(objectManager, classForName(MGZ_HEAD_TRIGGER_STONE_CHIP_CLASS)),
                "precondition: exactly one MGZ head-trigger stone chip fixture is live");
        assertEquals(1, countLive(objectManager, classForName(MGZ_CEILING_SPIRE_CLASS)),
                "precondition: exactly one MGZ ceiling spire fixture is live");
        assertEquals(1, countLive(objectManager, classForName(BLASTOID_PROJECTILE_CLASS)),
                "precondition: exactly one Blastoid projectile fixture is live");
        assertEquals(1, countLive(objectManager, classForName(SNALE_BLASTER_PROJECTILE_CLASS)),
                "precondition: exactly one SnaleBlaster projectile fixture is live");
        assertEquals(1, countLive(objectManager, classForName(SPIKER_SPIKE_PROJECTILE_CLASS)),
                "precondition: exactly one Spiker spike projectile fixture is live");

        Map<Class<?>, Map<String, Object>> capturedState = new LinkedHashMap<>();
        for (AbstractObjectInstance instance : tracked) {
            capturedState.put(instance.getClass(), simpleRewindFieldValues(instance));
        }

        CompositeSnapshot snapshot = registry.capture();
        assertNotNull(snapshot, "capture() must return a snapshot");

        for (AbstractObjectInstance instance : tracked) {
            objectManager.removeDynamicObject(instance);
        }
        assertEquals(0, countLive(objectManager, AizRockFragmentChild.class),
                "diverge step must remove the AIZ rock fragment");
        assertEquals(0, countLive(objectManager, CnzMinibossDebrisChild.class),
                "diverge step must remove the CNZ miniboss debris");
        assertEquals(0, countLive(objectManager, S3kBossExplosionChild.class),
                "diverge step must remove the boss explosion");
        assertEquals(0, countLive(objectManager, MhzPollenParticleInstance.class),
                "diverge step must remove the MHZ pollen particle");
        assertEquals(0, countLive(objectManager, LightningSparkObjectInstance.class),
                "diverge step must remove the lightning spark");
        assertEquals(0, countLive(objectManager, RockDebrisChild.class),
                "diverge step must remove the rock debris");
        assertEquals(0, countLive(objectManager, MGZHeadTriggerProjectileInstance.class),
                "diverge step must remove the MGZ head projectile");
        assertEquals(0, countLive(objectManager, SongFadeTransitionInstance.class),
                "diverge step must remove the song fade");
        assertEquals(0, countLive(objectManager, EggPrisonAnimalInstance.class),
                "diverge step must remove the egg-prison animal");
        assertEquals(0, countLive(objectManager, S3kSignpostSparkleChild.class),
                "diverge step must remove the signpost sparkle");
        assertEquals(0, countLive(objectManager, S3kAirCountdownObjectInstance.class),
                "diverge step must remove the air countdown");
        assertEquals(0, countLive(objectManager, CaterkillerJrBodyInstance.class),
                "diverge step must remove the Caterkiller Jr body");
        assertEquals(0, countLive(objectManager, AizBgTreeInstance.class),
                "diverge step must remove the AIZ background tree");
        assertEquals(0, countLive(objectManager, Aiz2BossEndSequenceController.class),
                "diverge step must remove the AIZ2 end-sequence controller");
        assertEquals(0, countLive(objectManager, CutsceneKnucklesLbz1ThrownBomb.class),
                "diverge step must remove the LBZ1 thrown bomb");
        assertEquals(0, countLive(objectManager, S3kBadnikProjectileInstance.class),
                "diverge step must remove the S3K badnik projectile");
        assertEquals(0, countLive(objectManager, MhzShipSequenceControllerInstance.class),
                "diverge step must remove the MHZ ship controller");
        assertEquals(0, countLive(objectManager, S3kBossDefeatSignpostFlow.class),
                "diverge step must remove the S3K boss-defeat signpost flow");
        assertEquals(0, countLive(objectManager, Aiz2EndEggCapsuleInstance.class),
                "diverge step must remove the AIZ2 egg capsule");
        assertEquals(0, countLive(objectManager, HczEndBossEggCapsuleInstance.class),
                "diverge step must remove the HCZ egg capsule");
        assertEquals(0, countLive(objectManager, IczEndBossEggCapsuleInstance.class),
                "diverge step must remove the ICZ egg capsule");
        assertEquals(0, countLive(objectManager, MhzEndBossEggCapsuleInstance.class),
                "diverge step must remove the MHZ egg capsule");
        assertEquals(0, countLive(objectManager, Mgz2EndEggCapsuleInstance.class),
                "diverge step must remove the MGZ2 egg capsule");
        assertEquals(0, countLive(objectManager, S3kSignpostInstance.class),
                "diverge step must remove the S3K signpost");
        assertEquals(0, countLive(objectManager, HczEndBossGeyserCutscene.class),
                "diverge step must remove the HCZ geyser cutscene");
        assertEquals(0, countLive(objectManager, Mgz2CapsuleAnimalInstance.class),
                "diverge step must remove the MGZ2 capsule animal");
        assertEquals(0, countLive(objectManager, Mgz2ResultsScreenObjectInstance.class),
                "diverge step must remove the MGZ2 results screen");
        assertEquals(0, countLive(
                        objectManager, AizHollowTreeObjectInstance.AizTreeRevealControlObjectInstance.class),
                "diverge step must remove the AIZ tree-reveal control");
        assertEquals(0, countLive(objectManager, classForName(MGZ_HEAD_TRIGGER_STONE_CHIP_CLASS)),
                "diverge step must remove the MGZ head-trigger stone chip");
        assertEquals(0, countLive(objectManager, classForName(MGZ_CEILING_SPIRE_CLASS)),
                "diverge step must remove the MGZ ceiling spire");
        assertEquals(0, countLive(objectManager, classForName(BLASTOID_PROJECTILE_CLASS)),
                "diverge step must remove the Blastoid projectile");
        assertEquals(0, countLive(objectManager, classForName(SNALE_BLASTER_PROJECTILE_CLASS)),
                "diverge step must remove the SnaleBlaster projectile");
        assertEquals(0, countLive(objectManager, classForName(SPIKER_SPIKE_PROJECTILE_CLASS)),
                "diverge step must remove the Spiker spike projectile");

        registry.restore(snapshot);

        assertSimpleStateRoundTrip(objectManager, AizRockFragmentChild.class, capturedState);
        assertSimpleStateRoundTrip(objectManager, CnzMinibossDebrisChild.class, capturedState);
        assertSimpleStateRoundTrip(objectManager, S3kBossExplosionChild.class, capturedState);
        assertSimpleStateRoundTrip(objectManager, MhzPollenParticleInstance.class, capturedState);
        assertSimpleStateRoundTrip(objectManager, LightningSparkObjectInstance.class, capturedState);
        assertSimpleStateRoundTrip(objectManager, RockDebrisChild.class, capturedState);
        assertSimpleStateRoundTrip(objectManager, MGZHeadTriggerProjectileInstance.class, capturedState);
        assertSimpleStateRoundTrip(objectManager, SongFadeTransitionInstance.class, capturedState);
        assertSimpleStateRoundTrip(objectManager, EggPrisonAnimalInstance.class, capturedState);
        assertSimpleStateRoundTrip(objectManager, S3kSignpostSparkleChild.class, capturedState);
        assertSimpleStateRoundTrip(objectManager, S3kAirCountdownObjectInstance.class, capturedState);
        assertSimpleStateRoundTrip(objectManager, CaterkillerJrBodyInstance.class, capturedState);
        assertSimpleStateRoundTrip(objectManager, AizBgTreeInstance.class, capturedState);
        assertSimpleStateRoundTrip(objectManager, Aiz2BossEndSequenceController.class, capturedState);
        assertSimpleStateRoundTrip(objectManager, CutsceneKnucklesLbz1ThrownBomb.class, capturedState);
        assertSimpleStateRoundTrip(objectManager, S3kBadnikProjectileInstance.class, capturedState);
        assertSimpleStateRoundTrip(objectManager, MhzShipSequenceControllerInstance.class, capturedState);
        assertSimpleStateRoundTrip(objectManager, S3kBossDefeatSignpostFlow.class, capturedState);
        assertSimpleStateRoundTrip(objectManager, Aiz2EndEggCapsuleInstance.class, capturedState);
        assertSimpleStateRoundTrip(objectManager, HczEndBossEggCapsuleInstance.class, capturedState);
        HczEndBossEggCapsuleInstance restoredHczCapsule =
                liveObjects(objectManager, HczEndBossEggCapsuleInstance.class).getFirst();
        HczEndBossEggCapsuleButton restoredHczButton =
                liveObjects(objectManager, HczEndBossEggCapsuleButton.class).getFirst();
        assertSame(restoredHczCapsule, readObjectField(restoredHczButton, "parent"),
                "the restored capsule button must resolve its captured parent identity");
        assertSimpleStateRoundTrip(objectManager, IczEndBossEggCapsuleInstance.class, capturedState);
        assertSimpleStateRoundTrip(objectManager, MhzEndBossEggCapsuleInstance.class, capturedState);
        assertSimpleStateRoundTrip(objectManager, Mgz2EndEggCapsuleInstance.class, capturedState);
        assertSimpleStateRoundTrip(objectManager, S3kSignpostInstance.class, capturedState);
        assertSimpleStateRoundTrip(objectManager, HczEndBossGeyserCutscene.class, capturedState);
        assertSimpleStateRoundTrip(objectManager, Mgz2CapsuleAnimalInstance.class, capturedState);
        assertSimpleStateRoundTrip(objectManager, Mgz2ResultsScreenObjectInstance.class, capturedState);
        assertSimpleStateRoundTrip(
                objectManager, AizHollowTreeObjectInstance.AizTreeRevealControlObjectInstance.class, capturedState);
        assertSimpleStateRoundTrip(objectManager, classForName(MGZ_HEAD_TRIGGER_STONE_CHIP_CLASS), capturedState);
        assertSimpleStateRoundTrip(objectManager, classForName(MGZ_CEILING_SPIRE_CLASS), capturedState);
        assertSimpleStateRoundTrip(objectManager, classForName(BLASTOID_PROJECTILE_CLASS), capturedState);
        assertSimpleStateRoundTrip(objectManager, classForName(SNALE_BLASTER_PROJECTILE_CLASS), capturedState);
        assertSimpleStateRoundTrip(objectManager, classForName(SPIKER_SPIKE_PROJECTILE_CLASS), capturedState);
    }

    private static void assertNoRegisteredS3kDynamicCodec(Class<?> type) {
        boolean hasCodec = DeletedDynamicRewindCodecs.hasRegisteredDynamicCodec(type.getName());
        assertFalse(hasCodec, type.getSimpleName()
                + " must restore through RewindRecreatable generic recreate, not an explicit S3K dynamic codec");
    }

    private static <T extends AbstractObjectInstance> void assertSimpleStateRoundTrip(
            ObjectManager objectManager,
            Class<T> type,
            Map<Class<?>, Map<String, Object>> expectedState) throws Exception {
        List<T> restored = liveObjects(objectManager, type);
        assertEquals(1, restored.size(),
                "restore must recreate exactly one live " + type.getSimpleName());
        assertEquals(expectedState.get(type), simpleRewindFieldValues(restored.get(0)),
                "restored simple rewind fields must match captured state for " + type.getSimpleName());
    }

    private static int countLive(ObjectManager objectManager, Class<?> type) {
        return liveObjects(objectManager, type).size();
    }

    private static AbstractObjectInstance firstLive(
            ObjectManager objectManager, Class<? extends AbstractObjectInstance> type) {
        return liveObjects(objectManager, type).get(0);
    }

    private static void removeLiveObjects(ObjectManager objectManager, Class<?> type) {
        List<?> live = List.copyOf(liveObjects(objectManager, type));
        for (Object object : live) {
            objectManager.removeDynamicObject((ObjectInstance) object);
        }
    }

    private static <T> List<T> liveObjects(ObjectManager objectManager, Class<T> type) {
        return objectManager.getActiveObjects().stream()
                .filter(o -> o.getClass() == type && !o.isDestroyed())
                .map(type::cast)
                .toList();
    }

    @SuppressWarnings("unchecked")
    private static Class<? extends AbstractObjectInstance> classForName(String className) {
        try {
            return (Class<? extends AbstractObjectInstance>) Class.forName(className);
        } catch (ClassNotFoundException e) {
            throw new AssertionError("Missing class " + className, e);
        }
    }

    private static AbstractObjectInstance instantiatePrivateDynamic(
            String className, Class<?>[] parameterTypes, Object... args) {
        try {
            Constructor<?> ctor = Class.forName(className).getDeclaredConstructor(parameterTypes);
            ctor.setAccessible(true);
            return (AbstractObjectInstance) ctor.newInstance(args);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Unable to instantiate " + className, e);
        }
    }

    private static Map<String, Object> simpleRewindFieldValues(ObjectInstance instance) throws Exception {
        Map<String, Object> values = new LinkedHashMap<>();
        for (Class<?> type = instance.getClass();
                type != null && type != Object.class && type != AbstractObjectInstance.class;
                type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || field.isSynthetic()) {
                    continue;
                }
                field.setAccessible(true);
                Object value = field.get(instance);
                if (field.getType().isPrimitive()
                        || field.getType().isEnum()
                        || field.getType() == String.class) {
                    values.put(type.getSimpleName() + "." + field.getName(), value);
                } else if (field.getType() == SubpixelMotion.State.class) {
                    values.put(type.getSimpleName() + "." + field.getName(), subpixelStateValues(value));
                }
            }
        }
        return values;
    }

    private static Map<String, Integer> subpixelStateValues(Object value) throws Exception {
        SubpixelMotion.State state = (SubpixelMotion.State) value;
        Map<String, Integer> values = new LinkedHashMap<>();
        for (String fieldName : List.of("x", "y", "xSub", "ySub", "xVel", "yVel")) {
            Field field = SubpixelMotion.State.class.getDeclaredField(fieldName);
            field.setAccessible(true);
            values.put(fieldName, field.getInt(state));
        }
        return values;
    }

    private static int intField(Object instance, String fieldName) throws Exception {
        Field field = instance.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        return field.getInt(instance);
    }

    private static Object readObjectField(Object instance, String fieldName) throws Exception {
        Field field = fieldInHierarchy(instance.getClass(), fieldName);
        field.setAccessible(true);
        return field.get(instance);
    }

    private static void setIntField(Object instance, String fieldName, int value) throws Exception {
        Field field = instance.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.setInt(instance, value);
    }

    private static void setIntFieldInHierarchy(Object instance, String fieldName, int value) throws Exception {
        Field field = fieldInHierarchy(instance.getClass(), fieldName);
        field.setAccessible(true);
        field.setInt(instance, value);
    }

    private static int[] intArrayField(Object instance, String fieldName) throws Exception {
        Field field = instance.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        return ((int[]) field.get(instance)).clone();
    }

    private static void setPowerUpObjectField(AbstractPlayableSprite player, String fieldName, Object value)
            throws Exception {
        Field field = AbstractPlayableSprite.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(player, value);
    }

    private static Object shieldPlayer(ShieldObjectInstance shield) throws Exception {
        var method = ShieldObjectInstance.class.getDeclaredMethod("getPlayer");
        method.setAccessible(true);
        return method.invoke(shield);
    }

    private static void invokeOpenCapsule(Aiz2EndEggCapsuleInstance capsule) throws Exception {
        Method openCapsule = AbstractS3kFloatingEndEggCapsuleInstance.class.getDeclaredMethod("openCapsule");
        openCapsule.setAccessible(true);
        openCapsule.invoke(capsule);
    }

    private static Field fieldInHierarchy(Class<?> type, String fieldName) throws NoSuchFieldException {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            try {
                return current.getDeclaredField(fieldName);
            } catch (NoSuchFieldException ignored) {
                // Continue searching superclasses.
            }
        }
        throw new NoSuchFieldException(fieldName);
    }
}
