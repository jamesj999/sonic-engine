package com.openggf.game.sonic2.specialstage;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

class TestSonic2SpecialStageCheckpointSnapshot {
    @Test
    void checkpointSnapshotRestoresMessageRainbowAndPreservesCallbacks() throws Exception {
        Sonic2SpecialStageCheckpoint checkpoint = new Sonic2SpecialStageCheckpoint();
        AtomicInteger callbackCounter = new AtomicInteger();
        Runnable musicCallback = callbackCounter::incrementAndGet;
        Sonic2SpecialStageCheckpoint.CheckpointResolvedCallback checkpointCallback =
                (result, checkpointNumber, ringRequirement, ringsCollected, finalCheckpoint) ->
                        callbackCounter.addAndGet(checkpointNumber);
        checkpoint.setOnMusicFadeRequested(musicCallback);
        checkpoint.setOnCheckpointResolved(checkpointCallback);
        checkpoint.beginCheckpoint(2, 80, 64, false);
        checkpoint.update(true);

        set(checkpoint, "phaseTimer", 33);
        set(checkpoint, "lastResult", Sonic2SpecialStageCheckpoint.Result.FAILED);
        set(checkpoint, "ringRequirement", 80);
        set(checkpoint, "ringsCollected", 64);
        set(checkpoint, "showCheckpointHand", true);
        set(checkpoint, "handX", 123);
        set(checkpoint, "handY", 99);
        set(checkpoint, "handTargetY", 91);
        set(checkpoint, "handThumbsUp", false);
        set(checkpoint, "handMovingDown", true);

        List<Sonic2SpecialStageCheckpoint.MessageLetter> letters = checkpoint.getMessageLetters();
        letters.clear();
        letters.add(messageLetter(10, 20, 0x04, 7, 5, true));
        letters.add(messageLetter(30, 40, 0x10, 9, 6, false));

        List<Sonic2SpecialStageCheckpoint.RainbowRing> rings = checkpoint.getRainbowRings();
        seedRainbowRing(rings.get(0), 4, 0x1C, 6, 0x70, 0x52, true);
        seedRainbowRing(rings.get(1), 5, 0x2A, -1, 0x88, 0x58, false);

        Sonic2SpecialStageSnapshot.CheckpointSnapshot snapshot = checkpoint.captureRewindSnapshot();
        letters.get(0).x = 999;
        set(rings.get(0), "x", 999);
        checkpoint.reset();

        checkpoint.restoreRewindSnapshot(snapshot);

        assertEquals(Sonic2SpecialStageCheckpoint.MessagePhase.RAINBOW_RINGS, checkpoint.getPhase());
        assertEquals(33, get(checkpoint, "phaseTimer"));
        assertEquals(Sonic2SpecialStageCheckpoint.Result.FAILED, checkpoint.getLastResult());
        assertEquals(2, checkpoint.getCurrentCheckpoint());
        assertEquals(80, checkpoint.getRingRequirement());
        assertEquals(64, checkpoint.getRingsCollected());
        assertEquals(123, get(checkpoint, "handX"));
        assertEquals(91, checkpoint.getWingsY());
        assertEquals(99, checkpoint.getHandY());
        assertEquals(91, get(checkpoint, "handTargetY"));
        assertEquals(false, checkpoint.isHandThumbsUp());
        assertEquals(true, get(checkpoint, "handMovingDown"));
        assertEquals(80, get(checkpoint, "pendingRingRequirement"));
        assertEquals(64, get(checkpoint, "pendingRingsCollected"));
        assertEquals(false, get(checkpoint, "pendingFinalCheckpoint"));
        assertSame(musicCallback, get(checkpoint, "onMusicFadeRequested"));
        assertSame(checkpointCallback, get(checkpoint, "onCheckpointResolved"));

        assertMessageLetter(checkpoint.getMessageLetters().get(0), 10, 20, 0x04, 7, 5, true);
        assertMessageLetter(checkpoint.getMessageLetters().get(1), 30, 40, 0x10, 9, 6, false);
        assertRainbowRing(checkpoint.getRainbowRings().get(0), 0, 4, 0x1C, 6, 0x70, 0x52, true);
        assertRainbowRing(checkpoint.getRainbowRings().get(1), 1, 5, 0x2A, -1, 0x88, 0x58, false);
        assertNotSame(snapshot.messageLetters(), checkpoint.getMessageLetters());
        assertNotSame(snapshot.rainbowRings(), checkpoint.getRainbowRings());
    }

    @Test
    void introSnapshotRestoresLettersBannerStateAndListOrder() throws Exception {
        Sonic2SpecialStageIntro intro = new Sonic2SpecialStageIntro();
        intro.initialize(0, 50);
        set(intro, "currentPhase", Sonic2SpecialStageIntro.Phase.MESSAGE_FLYOUT);
        set(intro, "phaseTimer", 12);
        set(intro, "frameCounter", 34);
        set(intro, "bannerX", 56);
        set(intro, "bannerY", 78);
        set(intro, "bannerVisible", false);
        set(intro, "messageX", 111);
        set(intro, "messageY", 112);
        set(intro, "messageVisible", true);
        set(intro, "lettersFlying", true);
        set(intro, "letterFlyoutProgress", 9);
        set(intro, "messageFlyoutInitialized", true);
        set(intro, "bannerFlyoutInitialized", true);

        intro.getMessageLetters().add(introMessageLetter(10, 20, 0x04, 1.25, 7, true));
        intro.getMessageLetters().add(introMessageLetter(30, 40, -3, -0.5, 9, false));
        intro.getBannerLetters().add(bannerLetter(-0x28, 3, 3, -1.0, 8, true));
        intro.getBannerLetters().add(bannerLetter(0x18, 4, 7, 0.75, 10, false));

        Sonic2SpecialStageSnapshot.IntroSnapshot snapshot = intro.captureRewindSnapshot();
        intro.getMessageLetters().get(0).x = 999;
        intro.getBannerLetters().get(0).frame = 99;
        intro.initialize(1, 90);

        intro.restoreRewindSnapshot(snapshot);

        assertEquals(Sonic2SpecialStageIntro.Phase.MESSAGE_FLYOUT, intro.getCurrentPhase());
        assertEquals(12, get(intro, "phaseTimer"));
        assertEquals(34, intro.getFrameCounter());
        assertEquals(56, intro.getBannerX());
        assertEquals(78, intro.getBannerY());
        assertEquals(false, intro.isBannerVisible());
        assertEquals(111, intro.getMessageX());
        assertEquals(112, intro.getMessageY());
        assertEquals(true, intro.isMessageVisible());
        assertEquals(50, intro.getRingRequirement());
        assertEquals(true, get(intro, "lettersFlying"));
        assertEquals(9, intro.getLetterFlyoutProgress());
        assertEquals(true, get(intro, "messageFlyoutInitialized"));
        assertEquals(true, get(intro, "bannerFlyoutInitialized"));

        assertIntroMessageLetter(intro.getMessageLetters().get(0), 10, 20, 0x04, 1.25, 7, true);
        assertIntroMessageLetter(intro.getMessageLetters().get(1), 30, 40, -3, -0.5, 9, false);
        assertBannerLetter(intro.getBannerLetters().get(0), -0x28, 3, 3, -1.0, 8, true);
        assertBannerLetter(intro.getBannerLetters().get(1), 0x18, 4, 7, 0.75, 10, false);
        assertNotSame(snapshot.messageLetters(), intro.getMessageLetters());
        assertNotSame(snapshot.bannerLetters(), intro.getBannerLetters());
    }

    private static Sonic2SpecialStageCheckpoint.MessageLetter messageLetter(
            int x, int y, int tileOffset, int flyoutAngle, int flyoutSpeed, boolean visible) {
        Sonic2SpecialStageCheckpoint.MessageLetter letter =
                new Sonic2SpecialStageCheckpoint.MessageLetter(x, y, tileOffset);
        letter.flyoutAngle = flyoutAngle;
        letter.flyoutSpeed = flyoutSpeed;
        letter.visible = visible;
        return letter;
    }

    private static Sonic2SpecialStageIntro.MessageLetter introMessageLetter(
            int x, int y, int tileOffset, double flyoutAngle, int flyoutSpeed, boolean visible) {
        Sonic2SpecialStageIntro.MessageLetter letter =
                new Sonic2SpecialStageIntro.MessageLetter(x, y, tileOffset);
        letter.flyoutAngle = flyoutAngle;
        letter.flyoutSpeed = flyoutSpeed;
        letter.visible = visible;
        return letter;
    }

    private static Sonic2SpecialStageIntro.BannerLetter bannerLetter(
            int x, int y, int frame, double flyoutAngle, int flyoutSpeed, boolean visible) {
        Sonic2SpecialStageIntro.BannerLetter letter =
                new Sonic2SpecialStageIntro.BannerLetter(x, y, frame);
        letter.flyoutAngle = flyoutAngle;
        letter.flyoutSpeed = flyoutSpeed;
        letter.visible = visible;
        return letter;
    }

    private static void seedRainbowRing(
            Sonic2SpecialStageCheckpoint.RainbowRing ring,
            int frameIndex,
            int positionOffset,
            int mappingFrame,
            int x,
            int y,
            boolean active) throws Exception {
        set(ring, "frameIndex", frameIndex);
        set(ring, "positionOffset", positionOffset);
        set(ring, "mappingFrame", mappingFrame);
        set(ring, "x", x);
        set(ring, "y", y);
        set(ring, "active", active);
    }

    private static void assertMessageLetter(
            Sonic2SpecialStageCheckpoint.MessageLetter letter,
            int x,
            int y,
            int tileOffset,
            int flyoutAngle,
            int flyoutSpeed,
            boolean visible) {
        assertEquals(x, letter.x);
        assertEquals(y, letter.y);
        assertEquals(tileOffset, letter.tileOffset);
        assertEquals(flyoutAngle, letter.flyoutAngle);
        assertEquals(flyoutSpeed, letter.flyoutSpeed);
        assertEquals(visible, letter.visible);
    }

    private static void assertIntroMessageLetter(
            Sonic2SpecialStageIntro.MessageLetter letter,
            int x,
            int y,
            int tileOffset,
            double flyoutAngle,
            int flyoutSpeed,
            boolean visible) {
        assertEquals(x, letter.x);
        assertEquals(y, letter.y);
        assertEquals(tileOffset, letter.tileOffset);
        assertEquals(flyoutAngle, letter.flyoutAngle);
        assertEquals(flyoutSpeed, letter.flyoutSpeed);
        assertEquals(visible, letter.visible);
    }

    private static void assertBannerLetter(
            Sonic2SpecialStageIntro.BannerLetter letter,
            int x,
            int y,
            int frame,
            double flyoutAngle,
            int flyoutSpeed,
            boolean visible) {
        assertEquals(x, letter.x);
        assertEquals(y, letter.y);
        assertEquals(frame, letter.frame);
        assertEquals(flyoutAngle, letter.flyoutAngle);
        assertEquals(flyoutSpeed, letter.flyoutSpeed);
        assertEquals(visible, letter.visible);
    }

    private static void assertRainbowRing(
            Sonic2SpecialStageCheckpoint.RainbowRing ring,
            int baseIndex,
            int frameIndex,
            int positionOffset,
            int mappingFrame,
            int x,
            int y,
            boolean active) throws Exception {
        assertEquals(baseIndex, get(ring, "baseIndex"));
        assertEquals(frameIndex, get(ring, "frameIndex"));
        assertEquals(positionOffset, get(ring, "positionOffset"));
        assertEquals(mappingFrame, get(ring, "mappingFrame"));
        assertEquals(x, ring.getX());
        assertEquals(y, ring.getY());
        assertEquals(active, get(ring, "active"));
    }

    private static void set(Object target, String field, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(field);
        f.setAccessible(true);
        f.set(target, value);
    }

    private static Object get(Object target, String field) throws Exception {
        Field f = target.getClass().getDeclaredField(field);
        f.setAccessible(true);
        return f.get(target);
    }
}
