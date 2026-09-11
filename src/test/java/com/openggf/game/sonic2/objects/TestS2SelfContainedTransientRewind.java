package com.openggf.game.sonic2.objects;

import com.openggf.game.GameServices;
import com.openggf.game.rewind.CompositeSnapshot;
import com.openggf.game.rewind.DeletedDynamicRewindCodecs;
import com.openggf.game.rewind.RewindRegistry;
import com.openggf.game.sonic2.constants.Sonic2ObjectIds;
import com.openggf.game.sonic2.audio.Sonic2Sfx;
import com.openggf.game.sonic2.objects.badniks.BadnikProjectileInstance;
import com.openggf.game.sonic2.objects.bosses.CPZBossFallingPart;
import com.openggf.game.sonic2.objects.bosses.LavaBubbleObjectInstance;
import com.openggf.game.sonic2.objects.bosses.MCZFallingDebrisInstance;
import com.openggf.game.sonic2.objects.bosses.Sonic2MTZBossInstance;
import com.openggf.game.sonic2.objects.badniks.SpikerDrillObjectInstance;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.InvincibilityStarsObjectInstance;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.boss.BossExplosionObjectInstance;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.tests.HeadlessTestFixture;
import com.openggf.tests.TestEnvironment;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
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

/**
 * Session-level rewind coverage for self-contained S2 projectile/effect objects
 * whose handwritten codecs are being deleted in favor of Phase-2 generic recreate.
 */
@RequiresRom(SonicGame.SONIC_2)
class TestS2SelfContainedTransientRewind {

    private static final int ZONE_EHZ = 0;
    private static final int ACT_1 = 0;

    @AfterEach
    void cleanup() {
        TestEnvironment.resetAll();
    }

    @Test
    void phase2BatchCandidatesHaveNoExplicitDynamicCodec() {
        assertNoRegisteredS2DynamicCodec(HtzFireProjectileObjectInstance.class);
        assertNoRegisteredS2DynamicCodec(ArrowProjectileInstance.class);
        assertNoRegisteredS2DynamicCodec(SteamPuffObjectInstance.class);
        assertNoRegisteredS2DynamicCodec(LeafParticleObjectInstance.class);
        assertNoRegisteredS2DynamicCodec(SpikerDrillObjectInstance.class);
        assertNoRegisteredS2DynamicCodec(WallTurretShotInstance.class);
        assertNoRegisteredS2DynamicCodec(VerticalLaserObjectInstance.class);
        assertNoRegisteredS2DynamicCodec(LavaBubbleObjectInstance.class);
        assertNoRegisteredS2DynamicCodec(MCZFallingDebrisInstance.class);
        assertNoRegisteredS2DynamicCodec(BubbleObjectInstance.class);
        assertNoRegisteredS2DynamicCodec(DestroyedEggPrisonObjectInstance.class);
        assertNoRegisteredS2DynamicCodec(BossExplosionObjectInstance.class);
        assertNoRegisteredS2DynamicCodec(BadnikProjectileInstance.class);
        assertNoRegisteredS2DynamicCodec(CPZBossFallingPart.class);
        assertNoRegisteredS2DynamicCodec(SpikyBlockSpikeInstance.class);
        assertNoRegisteredS2DynamicCodec(MonitorContentsObjectInstance.class);
        assertNoRegisteredS2DynamicCodec(BombPrizeObjectInstance.class);
        assertNoRegisteredS2DynamicCodec(ResultsScreenObjectInstance.class);
        assertNoRegisteredS2DynamicCodec(RingPrizeObjectInstance.class);
        assertNoRegisteredS2DynamicCodec(Sonic2MTZBossInstance.MTZBossLaser.class);
    }

    @Test
    void playerBoundInvincibilityStarsRestoreThroughSessionSnapshot() throws Exception {
        HeadlessTestFixture fixture = HeadlessTestFixture.builder()
                .withZoneAndAct(ZONE_EHZ, ACT_1)
                .build();

        RewindRegistry registry = fixture.gameplayMode().getRewindRegistry();
        assertNotNull(registry, "RewindRegistry must be available after S2 boot");

        ObjectManager objectManager = GameServices.level().getObjectManager();
        assertNotNull(objectManager, "ObjectManager must be available for S2");

        AbstractPlayableSprite player = fixture.sprite();
        assertNotNull(player.getPowerUpSpawner(),
                "S2 player must have a production power-up spawner");
        player.giveInvincibility();

        InvincibilityStarsObjectInstance capturedStars = assertInstanceOf(
                InvincibilityStarsObjectInstance.class,
                player.getInvincibilityObject(),
                "precondition: S2 invincibility should create the shared stars object");
        assertEquals(1, countLive(objectManager, InvincibilityStarsObjectInstance.class),
                "precondition: exactly one shared invincibility-stars object is live before capture");

        for (int frame = 0; frame < 4; frame++) {
            capturedStars.update(frame, player);
        }

        int capturedSlot = capturedStars.getSlotIndex();
        int[] capturedAngles = intArrayField(capturedStars, "angleByte");
        int[] capturedAnimCounters = intArrayField(capturedStars, "animCounter");
        int capturedSonic1TrailPhase = intField(capturedStars, "s1TrailPhase");
        int[] capturedSonic1AnimationIndices = intArrayField(capturedStars, "s1AnimationIndices");
        int[] capturedSonic1AnimationTimers = intArrayField(capturedStars, "s1AnimationTimers");

        CompositeSnapshot snapshot = registry.capture();
        assertNotNull(snapshot, "capture() must return a snapshot");

        objectManager.removeDynamicObject(capturedStars);
        setPowerUpObjectField(player, "invincibilityObject", null);
        player.setInvincibleFrames(0);
        assertEquals(0, countLive(objectManager, InvincibilityStarsObjectInstance.class),
                "diverge step must remove the captured shared invincibility stars");

        registry.restore(snapshot);

        List<InvincibilityStarsObjectInstance> restored =
                liveObjects(objectManager, InvincibilityStarsObjectInstance.class);
        assertEquals(1, restored.size(),
                "post-restore player refresh must recreate exactly one shared invincibility-stars object");
        InvincibilityStarsObjectInstance restoredStars = restored.get(0);
        assertSame(restoredStars, player.getInvincibilityObject(),
                "restored player invincibility link must point at the live ObjectManager stars");
        assertEquals(capturedSlot, restoredStars.getSlotIndex(),
                "restored shared invincibility stars must consume the captured dynamic slot");
        assertArrayEquals(capturedAngles, intArrayField(restoredStars, "angleByte"),
                "restored shared invincibility stars must keep captured orbit angles");
        assertArrayEquals(capturedAnimCounters, intArrayField(restoredStars, "animCounter"),
                "restored shared invincibility stars must keep captured animation counters");
        assertEquals(capturedSonic1TrailPhase, intField(restoredStars, "s1TrailPhase"),
                "restored shared invincibility stars must keep captured S1 trail phase");
        assertArrayEquals(capturedSonic1AnimationIndices, intArrayField(restoredStars, "s1AnimationIndices"),
                "restored shared invincibility stars must keep captured S1 animation indices");
        assertArrayEquals(capturedSonic1AnimationTimers, intArrayField(restoredStars, "s1AnimationTimers"),
                "restored shared invincibility stars must keep captured S1 animation timers");
        assertFalse(restoredStars.isDestroyed(), "restored shared invincibility stars must remain live");
    }

    @Test
    void selfContainedTransientChildrenRestoreThroughSessionSnapshot() throws Exception {
        HeadlessTestFixture fixture = HeadlessTestFixture.builder()
                .withZoneAndAct(ZONE_EHZ, ACT_1)
                .build();

        RewindRegistry registry = fixture.gameplayMode().getRewindRegistry();
        assertNotNull(registry, "RewindRegistry must be available after S2 boot");

        ObjectManager objectManager = GameServices.level().getObjectManager();
        assertNotNull(objectManager, "ObjectManager must be available for S2");

        int baseX = fixture.camera().getX() + 160;
        int baseY = fixture.camera().getY() + 112;
        AbstractObjectInstance.updateCameraBounds(
                fixture.camera().getX(),
                fixture.camera().getY(),
                fixture.camera().getX() + fixture.camera().getWidth(),
                fixture.camera().getY() + fixture.camera().getHeight(),
                0);

        HtzFireProjectileObjectInstance htzFireProjectile = objectManager.createDynamicObject(
                () -> new HtzFireProjectileObjectInstance(baseX - 0x30, baseY - 0x20, 0x180, -0x200, true));
        ArrowProjectileInstance arrowProjectile = objectManager.createDynamicObject(
                () -> new ArrowProjectileInstance(
                        new ObjectSpawn(baseX, baseY - 0x18, 0x22, 0, 1, false, 0),
                        baseX, baseY - 0x18, true));
        SteamPuffObjectInstance steamPuff = objectManager.createDynamicObject(
                () -> new SteamPuffObjectInstance(baseX + 0x30, baseY, true));
        LeafParticleObjectInstance leafParticle = objectManager.createDynamicObject(
                () -> new LeafParticleObjectInstance(baseX + 0x60, baseY + 0x10, 0x80, -0x100, 1, 0x30));
        SpikerDrillObjectInstance spikerDrill = objectManager.createDynamicObject(
                () -> new SpikerDrillObjectInstance(
                        new ObjectSpawn(baseX + 0x90, baseY + 0x18, 0x93, 0, 0x02, false, 0),
                        baseX + 0x90, baseY + 0x18, false, true));
        WallTurretShotInstance wallTurretShot = objectManager.createDynamicObject(
                () -> new WallTurretShotInstance(
                        new ObjectSpawn(baseX + 0xC0, baseY + 0x20, 0xB8, 0, 0, false, 0),
                        baseX + 0xC0, baseY + 0x20, -0x180, 0x80));
        VerticalLaserObjectInstance verticalLaser = objectManager.createDynamicObject(
                () -> new VerticalLaserObjectInstance(
                        new ObjectSpawn(baseX + 0xE0, baseY, 0xB6, 0x72, 0, false, 0),
                        baseX + 0xE0, baseY));
        LavaBubbleObjectInstance lavaBubble = objectManager.createDynamicObject(
                () -> new LavaBubbleObjectInstance(baseX + 0x110, baseY + 0x08));
        MCZFallingDebrisInstance mczFallingDebris = objectManager.createDynamicObject(
                () -> new MCZFallingDebrisInstance(baseX + 0x130, baseY - 0x40, true));
        BubbleObjectInstance bubble = objectManager.createDynamicObject(
                // Keep the bubble inside the camera window: Obj24 self-deletes once
                // render_flags bit 7 clears off-screen (ROM Obj24 DeleteObject path).
                () -> new BubbleObjectInstance(baseX - 0x60, baseY + 0x30, 1, 0x20));
        DestroyedEggPrisonObjectInstance destroyedEggPrison = objectManager.createDynamicObject(
                () -> new DestroyedEggPrisonObjectInstance(
                        new ObjectSpawn(baseX + 0x180, baseY + 0x20, 0x3E, 0, 0, false, 0),
                        baseX + 0x180,
                        baseY + 0x20));
        BossExplosionObjectInstance bossExplosion = objectManager.createDynamicObject(
                () -> new BossExplosionObjectInstance(
                        baseX + 0x1B0,
                        baseY,
                        Sonic2ObjectIds.BOSS_EXPLOSION,
                        Sonic2Sfx.BOSS_EXPLOSION.id));
        assertEquals(Sonic2ObjectIds.BOSS_EXPLOSION, bossExplosion.getSpawn().objectId(),
                "S2 Boss_LoadExplosion creates ROM Obj58, not an anonymous transient object");
        BadnikProjectileInstance badnikProjectile = objectManager.createDynamicObject(
                () -> new BadnikProjectileInstance(
                        new ObjectSpawn(baseX + 0x1E0, baseY - 0x28, 0x98, 0, 0, true, 0),
                        BadnikProjectileInstance.ProjectileType.ASTERON_SPIKE,
                        baseX + 0x1E0,
                        baseY - 0x28,
                        -0x180,
                        0x120,
                        false,
                        true,
                        4,
                        3));
        CPZBossFallingPart cpzBossFallingPart = objectManager.createDynamicObject(
                () -> new CPZBossFallingPart(
                        new ObjectSpawn(baseX + 0x210, baseY - 0x30, 0x5D, 0, 1, false, 0),
                        0x22,
                        0x140,
                        0x50));
        SpikyBlockSpikeInstance spikyBlockSpike = objectManager.createDynamicObject(
                () -> new SpikyBlockSpikeInstance(
                        new ObjectSpawn(baseX + 0x240, baseY + 0x28, 0x68, 0, 0, false, 0),
                        "SpikyBlock-Spike",
                        2,
                        0));
        MonitorContentsObjectInstance monitorContents = objectManager.createDynamicObject(
                () -> new MonitorContentsObjectInstance(
                        new ObjectSpawn(
                                baseX + 0x270,
                                baseY + 0x20,
                                Sonic2ObjectIds.MONITOR_CONTENTS,
                                1,
                                0,
                                false,
                                0),
                        null));
        BombPrizeObjectInstance bombPrize = objectManager.createDynamicObject(
                () -> new BombPrizeObjectInstance(
                        baseX,
                        baseY,
                        baseX + 0x08,
                        baseY + 0x08,
                        0x20,
                        new int[]{1}));
        ResultsScreenObjectInstance resultsScreen = objectManager.createDynamicObject(
                () -> new ResultsScreenObjectInstance(42, 17, 1, false));
        RingPrizeObjectInstance ringPrize = objectManager.createDynamicObject(
                () -> new RingPrizeObjectInstance(
                        baseX + 0x70,
                        baseY + 0x20,
                        baseX + 0x50,
                        baseY + 0x10,
                        0x20,
                        new int[]{1}));
        Sonic2MTZBossInstance.MTZBossLaser mtzBossLaser = objectManager.createDynamicObject(
                () -> new Sonic2MTZBossInstance.MTZBossLaser(null, 0x2B80, baseY, false));

        List<AbstractObjectInstance> tracked = List.of(
                htzFireProjectile,
                arrowProjectile,
                steamPuff,
                leafParticle,
                spikerDrill,
                wallTurretShot,
                verticalLaser,
                lavaBubble,
                mczFallingDebris,
                bubble,
                destroyedEggPrison,
                bossExplosion,
                badnikProjectile,
                cpzBossFallingPart,
                spikyBlockSpike,
                monitorContents,
                bombPrize,
                resultsScreen,
                ringPrize,
                mtzBossLaser);

        for (int frame = 0; frame < 3; frame++) {
            for (AbstractObjectInstance instance : tracked) {
                instance.update(frame, fixture.sprite());
                assertFalse(instance.isDestroyed(),
                        instance.getClass().getSimpleName() + " should survive setup frame " + frame);
            }
        }

        for (AbstractObjectInstance instance : tracked) {
            assertFalse(instance.isDestroyed(),
                    instance.getClass().getSimpleName() + " should be live before capture");
        }

        assertEquals(1, countLive(objectManager, HtzFireProjectileObjectInstance.class),
                "precondition: exactly one HTZ fire projectile fixture is live");
        assertEquals(1, countLive(objectManager, ArrowProjectileInstance.class),
                "precondition: exactly one arrow projectile fixture is live");
        assertEquals(1, countLive(objectManager, SteamPuffObjectInstance.class),
                "precondition: exactly one steam puff fixture is live");
        assertEquals(1, countLive(objectManager, LeafParticleObjectInstance.class),
                "precondition: exactly one leaf particle fixture is live");
        assertEquals(1, countLive(objectManager, SpikerDrillObjectInstance.class),
                "precondition: exactly one Spiker drill fixture is live");
        assertEquals(1, countLive(objectManager, WallTurretShotInstance.class),
                "precondition: exactly one wall-turret shot fixture is live");
        assertEquals(1, countLive(objectManager, VerticalLaserObjectInstance.class),
                "precondition: exactly one vertical laser fixture is live");
        assertEquals(1, countLive(objectManager, LavaBubbleObjectInstance.class),
                "precondition: exactly one lava bubble fixture is live");
        assertEquals(1, countLive(objectManager, MCZFallingDebrisInstance.class),
                "precondition: exactly one MCZ falling debris fixture is live");
        assertEquals(1, countLive(objectManager, BubbleObjectInstance.class),
                "precondition: exactly one bubble fixture is live");
        assertEquals(1, countLive(objectManager, DestroyedEggPrisonObjectInstance.class),
                "precondition: exactly one destroyed Egg Prison fixture is live");
        assertEquals(1, countLive(objectManager, BossExplosionObjectInstance.class),
                "precondition: exactly one boss explosion fixture is live");
        assertEquals(1, countLive(objectManager, BadnikProjectileInstance.class),
                "precondition: exactly one badnik projectile fixture is live");
        assertEquals(1, countLive(objectManager, CPZBossFallingPart.class),
                "precondition: exactly one CPZ boss falling-part fixture is live");
        assertEquals(1, countLive(objectManager, SpikyBlockSpikeInstance.class),
                "precondition: exactly one SpikyBlock spike fixture is live");
        assertEquals(1, countLive(objectManager, MonitorContentsObjectInstance.class),
                "precondition: exactly one monitor contents fixture is live");
        assertEquals(1, countLive(objectManager, BombPrizeObjectInstance.class),
                "precondition: exactly one bomb prize fixture is live");
        assertEquals(1, countLive(objectManager, ResultsScreenObjectInstance.class),
                "precondition: exactly one results-screen fixture is live");
        assertEquals(1, countLive(objectManager, RingPrizeObjectInstance.class),
                "precondition: exactly one ring-prize fixture is live");
        assertEquals(1, countLive(objectManager, Sonic2MTZBossInstance.MTZBossLaser.class),
                "precondition: exactly one MTZ boss laser fixture is live");

        Map<Class<?>, Map<String, Object>> capturedState = new LinkedHashMap<>();
        for (AbstractObjectInstance instance : tracked) {
            capturedState.put(instance.getClass(), simpleRewindFieldValues(instance));
        }

        CompositeSnapshot snapshot = registry.capture();
        assertNotNull(snapshot, "capture() must return a snapshot");

        for (AbstractObjectInstance instance : tracked) {
            objectManager.removeDynamicObject(instance);
        }
        assertEquals(0, countLive(objectManager, HtzFireProjectileObjectInstance.class),
                "diverge step must remove the HTZ fire projectile");
        assertEquals(0, countLive(objectManager, ArrowProjectileInstance.class),
                "diverge step must remove the arrow projectile");
        assertEquals(0, countLive(objectManager, SteamPuffObjectInstance.class),
                "diverge step must remove the steam puff");
        assertEquals(0, countLive(objectManager, LeafParticleObjectInstance.class),
                "diverge step must remove the leaf particle");
        assertEquals(0, countLive(objectManager, SpikerDrillObjectInstance.class),
                "diverge step must remove the Spiker drill");
        assertEquals(0, countLive(objectManager, WallTurretShotInstance.class),
                "diverge step must remove the wall-turret shot");
        assertEquals(0, countLive(objectManager, VerticalLaserObjectInstance.class),
                "diverge step must remove the vertical laser");
        assertEquals(0, countLive(objectManager, LavaBubbleObjectInstance.class),
                "diverge step must remove the lava bubble");
        assertEquals(0, countLive(objectManager, MCZFallingDebrisInstance.class),
                "diverge step must remove the MCZ falling debris");
        assertEquals(0, countLive(objectManager, BubbleObjectInstance.class),
                "diverge step must remove the bubble");
        assertEquals(0, countLive(objectManager, DestroyedEggPrisonObjectInstance.class),
                "diverge step must remove the destroyed Egg Prison");
        assertEquals(0, countLive(objectManager, BossExplosionObjectInstance.class),
                "diverge step must remove the boss explosion");
        assertEquals(0, countLive(objectManager, BadnikProjectileInstance.class),
                "diverge step must remove the badnik projectile");
        assertEquals(0, countLive(objectManager, CPZBossFallingPart.class),
                "diverge step must remove the CPZ boss falling part");
        assertEquals(0, countLive(objectManager, SpikyBlockSpikeInstance.class),
                "diverge step must remove the SpikyBlock spike");
        assertEquals(0, countLive(objectManager, MonitorContentsObjectInstance.class),
                "diverge step must remove the monitor contents object");
        assertEquals(0, countLive(objectManager, BombPrizeObjectInstance.class),
                "diverge step must remove the bomb prize object");
        assertEquals(0, countLive(objectManager, ResultsScreenObjectInstance.class),
                "diverge step must remove the results-screen object");
        assertEquals(0, countLive(objectManager, RingPrizeObjectInstance.class),
                "diverge step must remove the ring-prize object");
        assertEquals(0, countLive(objectManager, Sonic2MTZBossInstance.MTZBossLaser.class),
                "diverge step must remove the MTZ boss laser object");

        registry.restore(snapshot);

        assertSimpleStateRoundTrip(objectManager, HtzFireProjectileObjectInstance.class, capturedState);
        assertSimpleStateRoundTrip(objectManager, ArrowProjectileInstance.class, capturedState);
        assertSimpleStateRoundTrip(objectManager, SteamPuffObjectInstance.class, capturedState);
        assertSimpleStateRoundTrip(objectManager, LeafParticleObjectInstance.class, capturedState);
        assertSimpleStateRoundTrip(objectManager, SpikerDrillObjectInstance.class, capturedState);
        assertSimpleStateRoundTrip(objectManager, WallTurretShotInstance.class, capturedState);
        assertSimpleStateRoundTrip(objectManager, VerticalLaserObjectInstance.class, capturedState);
        assertSimpleStateRoundTrip(objectManager, LavaBubbleObjectInstance.class, capturedState);
        assertSimpleStateRoundTrip(objectManager, MCZFallingDebrisInstance.class, capturedState);
        assertSimpleStateRoundTrip(objectManager, BubbleObjectInstance.class, capturedState);
        assertSimpleStateRoundTrip(objectManager, DestroyedEggPrisonObjectInstance.class, capturedState);
        assertSimpleStateRoundTrip(objectManager, BossExplosionObjectInstance.class, capturedState);
        assertSimpleStateRoundTrip(objectManager, BadnikProjectileInstance.class, capturedState);
        assertSimpleStateRoundTrip(objectManager, CPZBossFallingPart.class, capturedState);
        assertSimpleStateRoundTrip(objectManager, SpikyBlockSpikeInstance.class, capturedState);
        assertSimpleStateRoundTrip(objectManager, MonitorContentsObjectInstance.class, capturedState);
        assertSimpleStateRoundTrip(objectManager, BombPrizeObjectInstance.class, capturedState);
        assertSimpleStateRoundTrip(objectManager, ResultsScreenObjectInstance.class, capturedState);
        assertSimpleStateRoundTrip(objectManager, RingPrizeObjectInstance.class, capturedState);
        assertSimpleStateRoundTrip(objectManager, Sonic2MTZBossInstance.MTZBossLaser.class, capturedState);
    }

    private static void assertNoRegisteredS2DynamicCodec(Class<?> type) {
        boolean hasCodec = DeletedDynamicRewindCodecs.hasRegisteredDynamicCodec(type.getName());
        assertFalse(hasCodec, type.getSimpleName()
                + " must restore through RewindRecreatable generic recreate, not an explicit S2 dynamic codec");
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

    private static <T> List<T> liveObjects(ObjectManager objectManager, Class<T> type) {
        return objectManager.getActiveObjects().stream()
                .filter(o -> o.getClass() == type && !o.isDestroyed())
                .map(type::cast)
                .toList();
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
                }
            }
        }
        return values;
    }

    private static int intField(Object instance, String fieldName) throws Exception {
        Field field = instance.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        return field.getInt(instance);
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
}
