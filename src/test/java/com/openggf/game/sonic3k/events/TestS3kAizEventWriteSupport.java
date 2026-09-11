package com.openggf.game.sonic3k.events;

import com.openggf.game.LevelEventProvider;
import com.openggf.level.objects.TestObjectServices;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestS3kAizEventWriteSupport {

    @Test
    void helperRoutesWritesAndTransitionReadsThroughAizBridge() {
        RecordingBridge bridge = new RecordingBridge();
        RecordingServices services = new RecordingServices(bridge);

        S3kAizEventWriteSupport.setBossFlag(services, true);
        S3kAizEventWriteSupport.setEventsFg5(services, true);
        S3kAizEventWriteSupport.triggerScreenShake(services, 16);
        S3kAizEventWriteSupport.onBattleshipComplete(services);
        S3kAizEventWriteSupport.onBossSmallComplete(services);

        assertTrue(bridge.bossFlag);
        assertTrue(bridge.eventsFg5);
        assertTrue(bridge.battleshipCompleted);
        assertTrue(bridge.bossSmallCompleted);
        assertTrue(bridge.screenShakeFrames == 16);
        assertTrue(S3kAizEventWriteSupport.isFireTransitionActive(services));
        assertTrue(S3kAizEventWriteSupport.isAct2TransitionRequested(services));
    }

    @Test
    void helperFallsBackCleanlyWhenProviderDoesNotExposeAizBridge() {
        RecordingServices services = new RecordingServices(new LevelEventProvider() {
            @Override
            public void initLevel(int zone, int act) {
            }

            @Override
            public void update() {
            }
        });

        S3kAizEventWriteSupport.setBossFlag(services, true);
        S3kAizEventWriteSupport.setEventsFg5(services, true);
        S3kAizEventWriteSupport.triggerScreenShake(services, 16);
        S3kAizEventWriteSupport.onBattleshipComplete(services);
        S3kAizEventWriteSupport.onBossSmallComplete(services);

        assertFalse(S3kAizEventWriteSupport.isFireTransitionActive(services));
        assertFalse(S3kAizEventWriteSupport.isAct2TransitionRequested(services));
    }

    private static final class RecordingServices extends TestObjectServices {
        private final LevelEventProvider provider;

        private RecordingServices(LevelEventProvider provider) {
            this.provider = provider;
        }

        @Override
        public LevelEventProvider levelEventProvider() {
            return provider;
        }
    }

    private static final class RecordingBridge implements AizObjectEventBridge, LevelEventProvider {
        private boolean bossFlag;
        private boolean eventsFg5;
        private boolean battleshipCompleted;
        private boolean bossSmallCompleted;
        private int screenShakeFrames;

        @Override
        public void initLevel(int zone, int act) {
        }

        @Override
        public void update() {
        }

        @Override
        public void setBossFlag(boolean value) {
            bossFlag = value;
        }

        @Override
        public void setEventsFg5(boolean value) {
            eventsFg5 = value;
        }

        @Override
        public void triggerScreenShake(int frames) {
            screenShakeFrames = frames;
        }

        @Override
        public void onBattleshipComplete() {
            battleshipCompleted = true;
        }

        @Override
        public void onBossSmallComplete() {
            bossSmallCompleted = true;
        }

        @Override
        public boolean isFireTransitionActive() {
            return true;
        }

        @Override
        public boolean isAct2TransitionRequested() {
            return true;
        }
    }
}
