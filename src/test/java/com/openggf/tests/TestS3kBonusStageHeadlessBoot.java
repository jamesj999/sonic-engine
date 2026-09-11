package com.openggf.tests;

import com.openggf.GameLoop;
import com.openggf.game.AbstractBonusStageCoordinator;
import com.openggf.game.BonusStageProvider;
import com.openggf.game.BonusStageState;
import com.openggf.game.BonusStageType;
import com.openggf.game.GameServices;
import com.openggf.game.session.GameplayModeContext;
import com.openggf.game.session.SessionManager;
import com.openggf.game.sonic3k.Sonic3kBonusStageCoordinator;
import com.openggf.game.sonic3k.bonusstage.slots.S3kSlotBonusStageRuntime;
import com.openggf.game.sonic3k.constants.Sonic3kZoneIds;
import com.openggf.game.sonic3k.objects.GumballMachineObjectInstance;
import com.openggf.game.sonic3k.objects.PachinkoEnergyTrapObjectInstance;
import com.openggf.level.Level;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Headless ROM-backed boot smoke coverage for the three S3K bonus-stage
 * zones that support the multi-stage trace-run bonus route (zone 0x13
 * Gumball, zone 0x14 Glowing Spheres / Pachinko, zone 0x15 Slot Machine).
 *
 * <p>No trace exists for these zones yet, so this test does not fabricate a
 * {@code TraceData}. Instead it exercises the same production seam that
 * {@link com.openggf.trace.replay.TraceReplaySessionBootstrap#applyBonusStageEntry}
 * performs -- provider resolve, {@code onEnter}, adapter registration, HUD
 * layout, ring seed, player unhide, the guarded pachinko bootstrap injection
 * via {@link GameLoop#resolveBonusStageBootstrapSpawn}, and
 * {@code onDeferredSetupComplete} -- directly against a headless fixture, in
 * the same order the production helper uses. This validates whether the
 * zones boot and step headlessly at all; it is the discovery task for that
 * question.
 */
@RequiresRom(SonicGame.SONIC_3K)
public class TestS3kBonusStageHeadlessBoot {

    private static final int ACT_1 = 0;
    private static final int IDLE_FRAMES = 60;
    private static final int FRAME0_RINGS = 25;

    /**
     * Each test builds a {@link HeadlessTestFixture} that leaves its bonus-stage
     * level (zone 0x13-0x15) registered in the gameplay session. Later classes in
     * the same reused fork that consult {@code GameServices.levelOrNull()} -- the
     * S3K object registry derives its zone set from the loaded level's ROM zone
     * id, and any id above 6 resolves to SKL -- would otherwise inherit it, so
     * tear the session down here rather than relying on the next class to reset.
     */
    @AfterEach
    void cleanup() {
        TestEnvironment.resetAll();
    }

    @Test
    public void gumballZoneBootsHeadlesslyWithMachineFromLayout() {
        HeadlessTestFixture fixture = HeadlessTestFixture.builder()
                .withZoneAndAct(Sonic3kZoneIds.ZONE_GUMBALL, ACT_1)
                .build();
        AbstractPlayableSprite sprite = fixture.sprite();

        performBonusStageEntry(BonusStageType.GUMBALL, FRAME0_RINGS);

        fixture.stepIdleFrames(IDLE_FRAMES);

        assertEquals(BonusStageType.GUMBALL,
                ((AbstractBonusStageCoordinator) GameServices.bonusStage()).getActiveType(),
                "Gumball provider must be the active bonus stage coordinator after entry");

        ObjectManager objectManager = GameServices.level().getObjectManager();
        assertTrue(objectManager.getActiveObjects().stream()
                        .anyMatch(GumballMachineObjectInstance.class::isInstance),
                "gumball machine object must load from the ROM level layout");

        assertFalse(sprite.getDead(),
                "Player should not have entered the death state after 60 idle frames in the gumball bonus stage");
        assertPlayerWithinLevelBounds(sprite);
    }

    @Test
    public void pachinkoZoneBootsHeadlesslyWithInjectedTrap() {
        HeadlessTestFixture fixture = HeadlessTestFixture.builder()
                .withZoneAndAct(Sonic3kZoneIds.ZONE_GLOWING_SPHERE, ACT_1)
                .build();
        AbstractPlayableSprite sprite = fixture.sprite();

        performBonusStageEntry(BonusStageType.GLOWING_SPHERE, FRAME0_RINGS);

        fixture.stepIdleFrames(IDLE_FRAMES);

        assertEquals(BonusStageType.GLOWING_SPHERE,
                ((AbstractBonusStageCoordinator) GameServices.bonusStage()).getActiveType(),
                "Pachinko provider must be the active bonus stage coordinator after entry");

        ObjectManager objectManager = GameServices.level().getObjectManager();
        assertTrue(objectManager.getActiveObjects().stream()
                        .anyMatch(PachinkoEnergyTrapObjectInstance.class::isInstance),
                "pachinko energy trap must be injected via GameLoop.resolveBonusStageBootstrapSpawn");

        assertFalse(sprite.getDead(),
                "Player should not have entered the death state after 60 idle frames in the pachinko bonus stage");
        assertPlayerWithinLevelBounds(sprite);
    }

    @Test
    public void slotsZoneBootsHeadlesslyWithRuntime() {
        HeadlessTestFixture fixture = HeadlessTestFixture.builder()
                .withZoneAndAct(Sonic3kZoneIds.ZONE_SLOT_MACHINE, ACT_1)
                .build();
        AbstractPlayableSprite originalSprite = fixture.sprite();

        performBonusStageEntry(BonusStageType.SLOT_MACHINE, FRAME0_RINGS);

        BonusStageProvider provider = GameServices.module().getBonusStageProvider();
        assertEquals(BonusStageType.SLOT_MACHINE,
                ((Sonic3kBonusStageCoordinator) provider).getActiveType(),
                "Slots provider must be the active bonus stage coordinator after entry");

        S3kSlotBonusStageRuntime slotRuntime =
                ((Sonic3kBonusStageCoordinator) provider).activeSlotRuntime();
        assertNotNull(slotRuntime, "Slot runtime must be created by onDeferredSetupComplete");
        assertTrue(slotRuntime.isInitialized(), "Slot runtime must report initialized after bootstrap");

        AbstractPlayableSprite focusedSprite = GameServices.camera().getFocusedSprite();
        assertNotSame(originalSprite, focusedSprite,
                "Camera focus must have swapped to the slots player, proving the sprite swap happened");

        fixture.stepIdleFrames(IDLE_FRAMES);

        assertFalse(focusedSprite.getDead(),
                "Camera-focused (swapped) player should not have entered the death state after 60 idle frames"
                        + " in the slots bonus stage");
        assertPlayerWithinLevelBounds(focusedSprite);
    }

    /**
     * Mirrors {@code TraceReplaySessionBootstrap.applyBonusStageEntry}'s
     * production ordering exactly (spec 2026-07-18, engine-side addition
     * #7): setActiveBonusStageProvider -> onEnter -> registerBonusStageAdapter
     * -> rings via LevelState -> HUD layout -> player unhide loop -> guarded
     * pachinko bootstrap injection -> onDeferredSetupComplete. This test
     * performs the same steps directly against the headless fixture rather
     * than through a {@code TraceData}, since no trace exists yet for these
     * zones. {@code onDeferredSetupComplete()} is a no-op for gumball/pachinko
     * (see {@code AbstractBonusStageCoordinator}'s default) and creates the
     * slot runtime + swaps the camera-focused player for slots.
     */
    private void performBonusStageEntry(BonusStageType type, int frame0Rings) {
        BonusStageProvider provider = GameServices.module().getBonusStageProvider();
        GameplayModeContext gameplayMode = SessionManager.getCurrentGameplayMode();
        gameplayMode.setActiveBonusStageProvider(provider);
        provider.onEnter(type, new BonusStageState(
                0, 0, frame0Rings, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                (byte) 0x0C, (byte) 0x0D, 0, 0L));
        gameplayMode.registerBonusStageAdapter(provider);

        GameServices.level().getLevelGamestate().setRings(frame0Rings);
        GameServices.level().setBonusStageHudLayout(true);
        for (var sprite : GameServices.sprites().getAllSprites()) {
            if (sprite instanceof AbstractPlayableSprite playable) {
                playable.setHidden(false);
                playable.setObjectControlled(false);
            }
        }

        ObjectSpawn bootstrapSpawn = GameLoop.resolveBonusStageBootstrapSpawn(type);
        ObjectManager objectManager = GameServices.level().getObjectManager();
        if (bootstrapSpawn != null && objectManager != null) {
            boolean present = objectManager.getActiveObjects().stream()
                    .anyMatch(PachinkoEnergyTrapObjectInstance.class::isInstance);
            if (!present) {
                objectManager.addDynamicObject(new PachinkoEnergyTrapObjectInstance(bootstrapSpawn));
            }
        }
        provider.onDeferredSetupComplete();
    }

    /**
     * A generous sanity check on player position after 60 idle frames.
     *
     * <p>Note: {@code Level.getMinY()}/{@code getMaxY()} are the ROM camera
     * scroll-clamp boundaries, not the level's physical tile extent -- the
     * gumball and pachinko bonus stages both drop the player through a
     * descent chute on entry, so it is expected (and ROM-accurate) for the
     * player to still be falling past the camera clamp toward the chute
     * floor within the first second. The real sanity bound here is the
     * level's physical map extent: the player must still be somewhere
     * inside the loaded tilemap, not off in NaN/garbage territory.
     */
    private void assertPlayerWithinLevelBounds(AbstractPlayableSprite sprite) {
        Level level = GameServices.level().getCurrentLevel();
        assertTrue(level != null, "Level must be loaded after bonus-stage entry");
        int y = sprite.getCentreY();
        int mapHeightPixels = level.getMap().getHeight() * level.getBlockPixelSize();
        assertTrue(y >= 0 && y <= mapHeightPixels,
                "Expected player centre Y within the loaded level's physical map extent after 60 idle frames."
                        + " y=" + y + " mapHeightPixels=" + mapHeightPixels);
    }
}
