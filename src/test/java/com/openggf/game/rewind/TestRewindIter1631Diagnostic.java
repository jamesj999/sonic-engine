package com.openggf.game.rewind;

import com.openggf.debug.playback.Bk2FrameInput;
import com.openggf.debug.playback.Bk2Movie;
import com.openggf.debug.playback.Bk2MovieLoader;
import com.openggf.game.session.GameplayModeContext;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.tests.HeadlessTestFixture;
import com.openggf.tests.TestEnvironment;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Focused regression for the original iter-1631 rewind divergence:
 * replaying frames 1620..1631 from the keyframe captured during a forward run
 * must match the original forward snapshots frame by frame.
 */
@RequiresRom(SonicGame.SONIC_2)
class TestRewindIter1631Diagnostic {

    private static final Path EHZ1_TRACE = Path.of("src/test/resources/traces/s2/ehz1_fullrun");
    private static final int KEYFRAME_INTERVAL = 60;
    private static final int KEYFRAME_TARGET = 1620;
    private static final int REPLAY_END = 1631;

    @AfterEach
    void tearDown() {
        TestEnvironment.resetAll();
        GenericRewindEligibility.clearForTest();
    }

    @Test
    void replayFromKeyframe1620MatchesForwardRunFrameByFrame() throws Exception {
        Assumptions.assumeTrue(Files.isDirectory(EHZ1_TRACE),
                "EHZ1 trace directory not found: " + EHZ1_TRACE);
        Path bk2Path = findBk2(EHZ1_TRACE);
        Assumptions.assumeTrue(bk2Path != null, "No .bk2 file found in " + EHZ1_TRACE);

        Bk2Movie movie = new Bk2MovieLoader().load(bk2Path);
        HeadlessTestFixture fixture = HeadlessTestFixture.builder()
                .withRecording(bk2Path)
                .withZoneAndAct(0, 0)
                .build();
        GameplayModeContext gm = TestEnvironment.activeGameplayMode();
        RewindRegistry registry = gm.getRewindRegistry();
        RewindController controller = new RewindController(
                registry,
                new InMemoryKeyframeStore(),
                new MovieInputSource(movie),
                new FixtureStepper(fixture),
                KEYFRAME_INTERVAL);

        Map<Integer, CompositeSnapshot> forwardSnaps = new LinkedHashMap<>();
        forwardSnaps.put(0, registry.capture());
        for (int f = 1; f <= REPLAY_END; f++) {
            controller.step();
            forwardSnaps.put(f, registry.capture());
        }

        controller.seekTo(KEYFRAME_TARGET);
        Map<Integer, CompositeSnapshot> rewindSnaps = new LinkedHashMap<>();
        rewindSnaps.put(KEYFRAME_TARGET, registry.capture());
        for (int f = KEYFRAME_TARGET + 1; f <= REPLAY_END; f++) {
            controller.step();
            rewindSnaps.put(f, registry.capture());
        }

        List<String> diffs = new ArrayList<>();
        for (int f = KEYFRAME_TARGET; f <= REPLAY_END; f++) {
            CompositeSnapshot expected = forwardSnaps.get(f);
            CompositeSnapshot actual = rewindSnaps.get(f);
            for (Map.Entry<String, Object> entry : expected.entries().entrySet()) {
                List<String> keyDiffs =
                        RewindSnapshotDiff.diffKey(entry.getKey(), entry.getValue(), actual.get(entry.getKey()));
                for (String diff : keyDiffs) {
                    diffs.add("frame " + f + " [" + entry.getKey() + "] " + diff);
                }
            }
        }

        assertTrue(diffs.isEmpty(), String.join(System.lineSeparator(), diffs));
    }

    private static Path findBk2(Path traceDir) throws Exception {
        try (var stream = Files.list(traceDir)) {
            return stream.filter(p -> p.toString().endsWith(".bk2"))
                    .findFirst()
                    .orElse(null);
        }
    }

    private static final class FixtureStepper implements RewindSeekAwareEngineStepper {
        private final HeadlessTestFixture fixture;

        private FixtureStepper(HeadlessTestFixture fixture) {
            this.fixture = fixture;
        }

        @Override
        public com.openggf.LevelFrameResult step(Bk2FrameInput inputs) {
            int p1 = inputs.p1InputMask();
            return fixture.runner().stepFrame(
                    (p1 & AbstractPlayableSprite.INPUT_UP) != 0,
                    (p1 & AbstractPlayableSprite.INPUT_DOWN) != 0,
                    (p1 & AbstractPlayableSprite.INPUT_LEFT) != 0,
                    (p1 & AbstractPlayableSprite.INPUT_RIGHT) != 0,
                    (p1 & AbstractPlayableSprite.INPUT_JUMP) != 0,
                    inputs.p2InputMask(),
                    inputs.p2StartPressed());
        }

        @Override
        public void restoreToFrame(int frame, Bk2FrameInput inputAtFrame) {
            fixture.runner().primeInputState(inputAtFrame);
        }
    }

    private static final class MovieInputSource implements InputSource {
        private final Bk2Movie movie;

        private MovieInputSource(Bk2Movie movie) {
            this.movie = movie;
        }

        @Override
        public int frameCount() {
            return movie.getFrames().size();
        }

        @Override
        public Bk2FrameInput read(int frame) {
            if (frame < 0 || frame >= frameCount()) {
                return new Bk2FrameInput(frame, 0, 0, false, "test:oor:" + frame);
            }
            return movie.getFrame(frame);
        }
    }
}
