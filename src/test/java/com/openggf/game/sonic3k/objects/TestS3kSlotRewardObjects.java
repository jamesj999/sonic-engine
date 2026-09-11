package com.openggf.game.sonic3k.objects;

import com.openggf.game.LevelState;
import com.openggf.game.ObjectArtProvider;
import com.openggf.game.sonic3k.Sonic3kObjectArtKeys;
import com.openggf.game.sonic3k.bonusstage.slots.S3kSlotStageController;
import com.openggf.graphics.GraphicsManager;
import com.openggf.level.LevelManager;
import com.openggf.level.Pattern;
import com.openggf.level.objects.ObjectRenderManager;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.ObjectSpriteSheet;
import com.openggf.level.objects.TestObjectServices;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.level.render.SpriteMappingFrame;
import com.openggf.level.render.SpriteMappingPiece;
import com.openggf.level.rings.RingManager;
import com.openggf.sprites.playable.Sonic;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestS3kSlotRewardObjects {

    @Test
    void spikeSoundsConsumePayoutTicksRatherThanCountingImpacts() {
        TrackingBonusStageServices services = new TrackingBonusStageServices();
        S3kSlotStageController controller = new S3kSlotStageController();
        controller.bootstrap();
        // Four payout updates are insufficient, even after several impacts.
        for (int i = 0; i < 4; i++) controller.advanceSpikePayout();
        expireSpike(controller, services);
        expireSpike(controller, services);
        assertEquals(0, services.spikeSounds);
        controller.advanceSpikePayout();
        expireSpike(controller, services);
        assertEquals(1, services.spikeSounds);
        expireSpike(controller, services);
        assertEquals(1, services.spikeSounds, "another same-tick impact cannot retrigger");
        for (int i = 0; i < 5; i++) controller.advanceSpikePayout();
        expireSpike(controller, services);
        assertEquals(2, services.spikeSounds, "sound still plays with no rings left");
    }

    private static void expireSpike(S3kSlotStageController controller, TrackingBonusStageServices services) {
        var reward = new S3kSlotSpikeRewardObjectInstance(
                new ObjectSpawn(0x460, 0x430, 0, 0, 0, false, 0), controller);
        reward.setServices(services);
        reward.activate();
        stepFrames(reward, 0x1E, null);
        assertTrue(reward.isDestroyed());
    }

    @Test
    void ringRewardAddsOneBonusStageRingToPlayableOnExpiry() {
        TrackingBonusStageServices services = new TrackingBonusStageServices();
        S3kSlotStageController controller = new S3kSlotStageController();
        controller.bootstrap();

        TrackingPlayableSprite player = new TrackingPlayableSprite(0);
        S3kSlotRingRewardObjectInstance reward = new S3kSlotRingRewardObjectInstance(
                new ObjectSpawn(0x460, 0x430, 0x00, 0x00, 0x00, false, 0), controller);
        reward.setServices(services);
        reward.activate();

        stepFrames(reward, 0x19, player);

        assertFalse(reward.isDestroyed());
        assertEquals(0, services.totalBonusStageRingDelta);
        assertEquals(0, player.liveRingDelta);
        assertEquals(0, controller.rewardCount());

        reward.update(0x19, player);

        // Ring is granted on expiry frame, but sparkle phase keeps it alive
        assertFalse(reward.isDestroyed());
        assertTrue(reward.isInSparkle());
        assertEquals(1, services.totalBonusStageRingDelta);
        assertEquals(1, player.liveRingDelta);
        assertEquals(1, controller.rewardCount());

        // Sparkle phase (8 frames) then destroyed
        stepFrames(reward, 8, player);
        assertTrue(reward.isDestroyed());
    }

    @Test
    void spikeRewardConsumesOneAvailableBonusStageRingAndUpdatesPlayableOnExpiry() {
        TrackingBonusStageServices services = new TrackingBonusStageServices();
        S3kSlotStageController controller = new S3kSlotStageController();
        controller.bootstrap();
        controller.addRewardRing();

        TrackingPlayableSprite player = new TrackingPlayableSprite(5);
        S3kSlotSpikeRewardObjectInstance reward = new S3kSlotSpikeRewardObjectInstance(
                new ObjectSpawn(0x460, 0x430, 0x00, 0x00, 0x00, false, 0), controller);
        reward.setServices(services);
        reward.activate();

        stepFrames(reward, 0x1D, player);

        assertFalse(reward.isDestroyed());
        assertEquals(1, controller.rewardCount());
        assertEquals(0, services.totalBonusStageRingDelta);
        assertEquals(0, player.liveRingDelta);

        reward.update(0x1D, player);

        assertTrue(reward.isDestroyed());
        assertEquals(0, controller.rewardCount());
        assertEquals(-1, services.totalBonusStageRingDelta);
        assertEquals(-1, player.liveRingDelta);
    }

    @Test
    void spikeRewardConsumesOneCarriedRingWhenNoRewardCounterExists() {
        TrackingBonusStageServices services = new TrackingBonusStageServices(5);
        S3kSlotStageController controller = new S3kSlotStageController();
        controller.bootstrap();

        S3kSlotSpikeRewardObjectInstance reward = new S3kSlotSpikeRewardObjectInstance(
                new ObjectSpawn(0x460, 0x430, 0x00, 0x00, 0x00, false, 0), controller);
        reward.setServices(services);
        reward.activate();

        stepFrames(reward, 0x1E, null);

        assertTrue(reward.isDestroyed());
        assertEquals(-1, controller.rewardCount());
        assertEquals(-1, services.totalBonusStageRingDelta);
        assertEquals(4, services.levelState.getRings());
    }

    @Test
    void spikeRewardDoesNotUnderflowWhenNoBonusStageRingIsAvailable() {
        TrackingBonusStageServices services = new TrackingBonusStageServices();
        S3kSlotStageController controller = new S3kSlotStageController();
        controller.bootstrap();

        S3kSlotSpikeRewardObjectInstance reward = new S3kSlotSpikeRewardObjectInstance(
                new ObjectSpawn(0x460, 0x430, 0x00, 0x00, 0x00, false, 0), controller);
        reward.setServices(services);
        reward.activate();

        stepFrames(reward, 0x1E, null);

        assertTrue(reward.isDestroyed());
        assertEquals(0, controller.rewardCount());
        assertEquals(0, services.totalBonusStageRingDelta);
        assertEquals(0, services.levelState.getRings());
    }

    // -------------------------------------------------------------------------
    // Interpolated movement tests (ROM Obj_SlotRing / Obj_SlotSpike)
    // -------------------------------------------------------------------------

    @Test
    void ringRewardInterpolatesPositionTowardCenterEachFrame() {
        TrackingBonusStageServices services = new TrackingBonusStageServices();
        S3kSlotStageController controller = new S3kSlotStageController();
        controller.bootstrap();

        int spawnX = 0x500, spawnY = 0x500;
        int centerX = 0x460, centerY = 0x430;

        S3kSlotRingRewardObjectInstance reward = new S3kSlotRingRewardObjectInstance(
                new ObjectSpawn(centerX, centerY, 0x00, 0x00, 0x00, false, 0), controller);
        reward.setServices(services);
        reward.activate(spawnX, spawnY, centerX, centerY);

        assertEquals(spawnX, reward.getInterpolatedX());
        assertEquals(spawnY, reward.getInterpolatedY());

        reward.update(0, null);

        // After one frame: moved 1/16th of distance toward center
        // dx = (centerX - spawnX) = -0xA0; current += dx >> 4 = -0xA
        int expectedX = spawnX + ((centerX - spawnX) >> 4);
        int expectedY = spawnY + ((centerY - spawnY) >> 4);
        assertEquals(expectedX, reward.getInterpolatedX(),
                "X should move 1/16th toward center after one frame");
        assertEquals(expectedY, reward.getInterpolatedY(),
                "Y should move 1/16th toward center after one frame");

        // Position should be between spawn and center (closer to spawn after 1 frame)
        assertTrue(reward.getInterpolatedX() < spawnX,
                "X should have moved toward center (decreased)");
        assertTrue(reward.getInterpolatedY() < spawnY,
                "Y should have moved toward center (decreased)");
    }

    @Test
    void ringRewardConvergesOnCenterOverMultipleFrames() {
        TrackingBonusStageServices services = new TrackingBonusStageServices();
        S3kSlotStageController controller = new S3kSlotStageController();
        controller.bootstrap();

        int spawnX = 0x500, spawnY = 0x500;
        int centerX = 0x460, centerY = 0x430;

        S3kSlotRingRewardObjectInstance reward = new S3kSlotRingRewardObjectInstance(
                new ObjectSpawn(centerX, centerY, 0x00, 0x00, 0x00, false, 0), controller);
        reward.setServices(services);
        reward.activate(spawnX, spawnY, centerX, centerY);

        int prevX = reward.getInterpolatedX();
        int prevY = reward.getInterpolatedY();

        // Step several frames (fewer than EXPIRY_FRAMES = 0x1A)
        for (int frame = 0; frame < 5; frame++) {
            reward.update(frame, null);
            assertTrue(reward.getInterpolatedX() <= prevX,
                    "X should not move away from center at frame " + frame);
            assertTrue(reward.getInterpolatedY() <= prevY,
                    "Y should not move away from center at frame " + frame);
            prevX = reward.getInterpolatedX();
            prevY = reward.getInterpolatedY();
        }
        // After several frames, still converging (not yet at center due to exponential decay)
        assertTrue(reward.getInterpolatedX() < spawnX,
                "X should be closer to center than spawn");
        assertTrue(reward.getInterpolatedY() < spawnY,
                "Y should be closer to center than spawn");
    }

    @Test
    void ringRewardNoArgActivateDoesNotChangePosition() {
        TrackingBonusStageServices services = new TrackingBonusStageServices();
        S3kSlotStageController controller = new S3kSlotStageController();
        controller.bootstrap();

        int spawnX = 0x460, spawnY = 0x430;

        S3kSlotRingRewardObjectInstance reward = new S3kSlotRingRewardObjectInstance(
                new ObjectSpawn(spawnX, spawnY, 0x00, 0x00, 0x00, false, 0), controller);
        reward.setServices(services);
        reward.activate();

        // No-arg activate sets target = spawn, so no movement
        assertEquals(spawnX, reward.getInterpolatedX());
        assertEquals(spawnY, reward.getInterpolatedY());

        reward.update(0, null);

        // After one frame: dx = 0, so position stays the same
        assertEquals(spawnX, reward.getInterpolatedX(),
                "X should not move when target equals spawn");
        assertEquals(spawnY, reward.getInterpolatedY(),
                "Y should not move when target equals spawn");
    }

    @Test
    void spikeRewardInterpolatesPositionTowardCenterEachFrame() {
        TrackingBonusStageServices services = new TrackingBonusStageServices();
        S3kSlotStageController controller = new S3kSlotStageController();
        controller.bootstrap();
        controller.addRewardRing();

        int spawnX = 0x3C0, spawnY = 0x3C0;
        int centerX = 0x460, centerY = 0x430;

        S3kSlotSpikeRewardObjectInstance reward = new S3kSlotSpikeRewardObjectInstance(
                new ObjectSpawn(centerX, centerY, 0x00, 0x00, 0x00, false, 0), controller);
        reward.setServices(services);
        reward.activate(spawnX, spawnY, centerX, centerY);

        assertEquals(spawnX, reward.getInterpolatedX());
        assertEquals(spawnY, reward.getInterpolatedY());

        reward.update(0, null);

        int expectedX = spawnX + ((centerX - spawnX) >> 4);
        int expectedY = spawnY + ((centerY - spawnY) >> 4);
        assertEquals(expectedX, reward.getInterpolatedX(),
                "X should move 1/16th toward center after one frame");
        assertEquals(expectedY, reward.getInterpolatedY(),
                "Y should move 1/16th toward center after one frame");

        // Position moved toward center (increased from spawn which was < center)
        assertTrue(reward.getInterpolatedX() > spawnX,
                "X should have moved toward center (increased)");
        assertTrue(reward.getInterpolatedY() > spawnY,
                "Y should have moved toward center (increased)");
    }

    @Test
    void ringRewardAppendRenderCommandsUsesRingManagerAtInterpolatedPosition() {
        RingManager ringManager = mock(RingManager.class);
        TrackingBonusStageServices services = new TrackingBonusStageServices().withRingManager(ringManager);
        S3kSlotStageController controller = new S3kSlotStageController();
        controller.bootstrap();

        S3kSlotRingRewardObjectInstance reward = new S3kSlotRingRewardObjectInstance(
                new ObjectSpawn(0x460, 0x430, 0x00, 0x00, 0x00, false, 0), controller);
        reward.setServices(services);
        reward.activate(0x500, 0x500, 0x460, 0x430);
        reward.update(7, null);

        reward.appendRenderCommands(new ArrayList<>());

        verify(ringManager).drawRingAt(reward.getInterpolatedX(), reward.getInterpolatedY(), 7);
    }

    @Test
    void spikeRewardAppendRenderCommandsUsesSpikeRenderer() throws Exception {
        RecordingRenderer renderer = new RecordingRenderer();
        TrackingBonusStageServices services = installRenderer(renderer, Sonic3kObjectArtKeys.SLOT_SPIKE_REWARD);

        S3kSlotStageController controller = new S3kSlotStageController();
        controller.bootstrap();

        S3kSlotSpikeRewardObjectInstance reward = new S3kSlotSpikeRewardObjectInstance(
                new ObjectSpawn(0x460, 0x430, 0x00, 0x00, 0x00, false, 0), controller);
        reward.setServices(services);
        reward.activate(0x3C0, 0x3C0, 0x460, 0x430);
        reward.update(5, null);

        reward.appendRenderCommands(new ArrayList<>());

        assertEquals(1, renderer.drawCount);
        assertEquals(0, renderer.lastFrameIndex);
        assertEquals(reward.getInterpolatedX(), renderer.lastX);
        assertEquals(reward.getInterpolatedY(), renderer.lastY);
    }

    private static void stepFrames(S3kSlotRingRewardObjectInstance reward, int frames, Sonic player) {
        for (int frame = 0; frame < frames; frame++) {
            reward.update(frame, player);
        }
    }

    private static void stepFrames(S3kSlotSpikeRewardObjectInstance reward, int frames, Sonic player) {
        for (int frame = 0; frame < frames; frame++) {
            reward.update(frame, player);
        }
    }

    private static final class TrackingBonusStageServices extends TestObjectServices {
        private int totalBonusStageRingDelta;
        private int spikeSounds;

        @Override
        public void playSfx(int soundId) {
            if (soundId == com.openggf.game.sonic3k.audio.Sonic3kSfx.SPIKE_HIT.id) {
                spikeSounds++;
            }
        }

        private final LevelState levelState;
        private RingManager ringManager;

        private TrackingBonusStageServices() {
            this(0);
        }

        private TrackingBonusStageServices(int rings) {
            this.levelState = new FixedRingLevelState(rings);
        }

        @Override
        public TrackingBonusStageServices withLevelManager(LevelManager levelManager) {
            super.withLevelManager(levelManager);
            return this;
        }

        @Override
        public LevelState levelGamestate() {
            return levelState;
        }

        @Override
        public void addBonusStageRings(int count) {
            totalBonusStageRingDelta += count;
        }

        private TrackingBonusStageServices withRingManager(RingManager ringManager) {
            this.ringManager = ringManager;
            return this;
        }

        @Override
        public RingManager ringManager() {
            return ringManager != null ? ringManager : super.ringManager();
        }
    }

    private static final class TrackingPlayableSprite extends Sonic {
        private int ringCount;
        private int liveRingDelta;

        private TrackingPlayableSprite(int ringCount) {
            super("sonic", (short) 0x460, (short) 0x430);
            this.ringCount = ringCount;
        }

        @Override
        public void addRings(int delta) {
            liveRingDelta += delta;
            ringCount += delta;
        }

        @Override
        public int getRingCount() {
            return ringCount;
        }
    }

    private static final class FixedRingLevelState implements LevelState {
        private int rings;

        private FixedRingLevelState(int rings) {
            this.rings = rings;
        }

        @Override public void update() { }
        @Override public int getRings() { return rings; }
        @Override public void addRings(int amount) { rings += amount; }
        @Override public void setRings(int rings) { this.rings = rings; }
        @Override public boolean isTimeOver() { return false; }
        @Override public String getDisplayTime() { return "0:00"; }
        @Override public boolean shouldFlashTimer() { return false; }
        @Override public boolean getFlashCycle() { return false; }
        @Override public void pauseTimer() { }
        @Override public void resumeTimer() { }
        @Override public boolean isTimerPaused() { return false; }
        @Override public int getElapsedSeconds() { return 0; }
        @Override public long getTimerFrames() { return 0; }
        @Override public void setTimerFrames(long frames) { }
    }

    private TrackingBonusStageServices installRenderer(RecordingRenderer renderer, String artKey) {
        LevelManager levelManager = mock(LevelManager.class);
        ObjectRenderManager renderManager = new ObjectRenderManager(new StubObjectArtProvider(renderer, artKey));
        when(levelManager.getObjectRenderManager()).thenReturn(renderManager);
        return new TrackingBonusStageServices().withLevelManager(levelManager);
    }

    private static final class StubObjectArtProvider implements ObjectArtProvider {
        private final PatternSpriteRenderer renderer;
        private final String artKey;

        private StubObjectArtProvider(PatternSpriteRenderer renderer, String artKey) {
            this.renderer = renderer;
            this.artKey = artKey;
        }

        @Override
        public void loadArtForZone(int zoneIndex) {
        }

        @Override
        public PatternSpriteRenderer getRenderer(String key) {
            return artKey.equals(key) ? renderer : null;
        }

        @Override
        public ObjectSpriteSheet getSheet(String key) {
            return null;
        }

        @Override
        public com.openggf.sprites.animation.SpriteAnimationSet getAnimations(String key) {
            return null;
        }

        @Override
        public int getZoneData(String key, int zoneIndex) {
            return -1;
        }

        @Override
        public Pattern[] getHudDigitPatterns() {
            return new Pattern[0];
        }

        @Override
        public Pattern[] getHudTextPatterns() {
            return new Pattern[0];
        }

        @Override
        public Pattern[] getHudLivesPatterns() {
            return new Pattern[0];
        }

        @Override
        public Pattern[] getHudLivesNumbers() {
            return new Pattern[0];
        }

        @Override
        public List<String> getRendererKeys() {
            return List.of(artKey);
        }

        @Override
        public int ensurePatternsCached(GraphicsManager graphicsManager, int baseIndex) {
            return baseIndex;
        }

        @Override
        public boolean isReady() {
            return true;
        }
    }

    private static final class RecordingRenderer extends PatternSpriteRenderer {
        private int drawCount;
        private int lastFrameIndex = -1;
        private int lastX;
        private int lastY;

        private RecordingRenderer() {
            super(dummySheet());
        }

        @Override
        public boolean isReady() {
            return true;
        }

        @Override
        public void drawFrameIndex(int frameIndex, int originX, int originY, boolean hFlip, boolean vFlip) {
            drawCount++;
            lastFrameIndex = frameIndex;
            lastX = originX;
            lastY = originY;
        }

        private static ObjectSpriteSheet dummySheet() {
            Pattern[] patterns = {new Pattern()};
            SpriteMappingPiece piece = new SpriteMappingPiece(0, 0, 1, 1, 0, false, false, 0, false);
            return new ObjectSpriteSheet(patterns, List.of(new SpriteMappingFrame(List.of(piece))), 0, 1);
        }
    }
}


