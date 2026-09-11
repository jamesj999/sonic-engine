package com.openggf.game.rewind;

import com.openggf.debug.playback.Bk2FrameInput;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class TestPlaybackController {

    private static CompositeSnapshot snap(int marker) {
        var e = new LinkedHashMap<String, Object>();
        e.put("marker", marker);
        return new CompositeSnapshot(e);
    }

    @Test
    void initialStateIsPlaying() {
        RewindController rc = createTestController();
        PlaybackController pc = new PlaybackController(rc);
        assertEquals(PlaybackController.State.PLAYING, pc.state());
    }

    @Test
    void playPauseSwitches() {
        RewindController rc = createTestController();
        PlaybackController pc = new PlaybackController(rc);
        pc.pause();
        assertEquals(PlaybackController.State.PAUSED, pc.state());
        pc.play();
        assertEquals(PlaybackController.State.PLAYING, pc.state());
    }

    @Test
    void tickPlaysOrRewindsBasedOnState() {
        RewindRegistry reg = new RewindRegistry();
        InMemoryKeyframeStore keyframes = new InMemoryKeyframeStore();
        InputSource inputs = new FakeInputSource(100);
        AtomicInteger stepCount = new AtomicInteger();

        RewindController rc = new RewindController(reg, keyframes, inputs, (in) -> {
            stepCount.incrementAndGet();
            return com.openggf.LevelFrameResult.GAMEPLAY_FRAME;
        }, 5);
        PlaybackController pc = new PlaybackController(rc);

        // PLAYING -> tick should step forward
        pc.tick();
        assertEquals(1, stepCount.get());

        // PAUSED -> tick should do nothing
        pc.pause();
        int oldCount = stepCount.get();
        pc.tick();
        assertEquals(oldCount, stepCount.get());

        // REWINDING -> tick should step backward (once at least)
        pc.rewind();
        // Step forward to frame 5 first so we can rewind
        for (int i = 0; i < 5; i++) rc.step();
        int beforeRewind = stepCount.get();
        pc.tick();
        // stepBackward doesn't call engineStepper, just manages position
        // so step count remains same
        assertEquals(beforeRewind, stepCount.get());
    }

    @Test
    void stepForwardOnceThenPauses() {
        RewindRegistry reg = new RewindRegistry();
        InMemoryKeyframeStore keyframes = new InMemoryKeyframeStore();
        InputSource inputs = new FakeInputSource(100);
        AtomicInteger stepCount = new AtomicInteger();

        RewindController rc = new RewindController(reg, keyframes, inputs, (in) -> {
            stepCount.incrementAndGet();
            return com.openggf.LevelFrameResult.GAMEPLAY_FRAME;
        }, 5);
        PlaybackController pc = new PlaybackController(rc);

        pc.stepForwardOnce();
        assertEquals(1, stepCount.get());
        assertEquals(PlaybackController.State.PAUSED, pc.state());
    }

    @Test
    void stepBackwardOnceThenPauses() {
        RewindRegistry reg = new RewindRegistry();
        InMemoryKeyframeStore keyframes = new InMemoryKeyframeStore();
        InputSource inputs = new FakeInputSource(100);
        AtomicInteger stepCount = new AtomicInteger();

        RewindController rc = new RewindController(reg, keyframes, inputs, (in) -> {
            stepCount.incrementAndGet();
            return com.openggf.LevelFrameResult.GAMEPLAY_FRAME;
        }, 5);
        PlaybackController pc = new PlaybackController(rc);

        // Step forward to frame 5 first
        for (int i = 0; i < 5; i++) rc.step();
        pc.rewind();

        // Step backward once
        pc.stepBackwardOnce();
        assertEquals(PlaybackController.State.PAUSED, pc.state());
    }

    @Test
    void rewindAutoPausesAtBuffer() {
        RewindRegistry reg = new RewindRegistry();
        InMemoryKeyframeStore keyframes = new InMemoryKeyframeStore();
        InputSource inputs = new FakeInputSource(100);
        AtomicInteger stepCount = new AtomicInteger();

        RewindController rc = new RewindController(reg, keyframes, inputs, (in) -> {
            stepCount.incrementAndGet();
            return com.openggf.LevelFrameResult.GAMEPLAY_FRAME;
        }, 5);
        PlaybackController pc = new PlaybackController(rc);

        pc.rewind();
        // At frame 0, rewinding should auto-pause
        pc.tick();
        assertEquals(PlaybackController.State.PAUSED, pc.state());
    }

    @Test
    void stateObserverIsCalled() {
        RewindController rc = createTestController();
        List<PlaybackController.State> observedStates = new ArrayList<>();
        PlaybackController pc = new PlaybackController(rc, observedStates::add);

        pc.pause();
        assertTrue(observedStates.contains(PlaybackController.State.PAUSED));

        pc.play();
        assertTrue(observedStates.contains(PlaybackController.State.PLAYING));
    }

    private static RewindController createTestController() {
        RewindRegistry reg = new RewindRegistry();
        InMemoryKeyframeStore keyframes = new InMemoryKeyframeStore();
        InputSource inputs = new FakeInputSource(100);
        return new RewindController(
                reg,
                keyframes,
                inputs,
                (in) -> com.openggf.LevelFrameResult.GAMEPLAY_FRAME,
                5);
    }

    private static class FakeInputSource implements InputSource {
        private final int count;

        FakeInputSource(int count) {
            this.count = count;
        }

        @Override
        public int frameCount() {
            return count;
        }

        @Override
        public Bk2FrameInput read(int frame) {
            return new Bk2FrameInput(frame, 0, 0, false, "fake");
        }
    }
}
