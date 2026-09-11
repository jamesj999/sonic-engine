package com.openggf.game.rewind.encounter;

import com.openggf.debug.playback.Bk2FrameInput;
import com.openggf.debug.playback.Bk2Movie;
import com.openggf.debug.playback.Bk2MovieLoader;
import com.openggf.game.rewind.CompositeSnapshot;
import com.openggf.game.rewind.GenericRewindEligibility;
import com.openggf.game.rewind.InMemoryKeyframeStore;
import com.openggf.game.rewind.InputSource;
import com.openggf.game.rewind.RewindController;
import com.openggf.game.rewind.RewindRegistry;
import com.openggf.game.rewind.RewindSeekAwareEngineStepper;
import com.openggf.game.rewind.RewindSnapshotDiff;
import com.openggf.game.session.GameplayModeContext;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.tests.HeadlessTestFixture;
import com.openggf.tests.TestEnvironment;
import org.junit.jupiter.api.Assumptions;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.fail;

public final class RewindEncounterValidator {
    private static final int KEYFRAME_INTERVAL = 60;

    private RewindEncounterValidator() {}

    public static void assertRewindReplayMatchesForwardRun(RewindEncounterScenario scenario)
            throws Exception {
        Assumptions.assumeTrue(Files.isDirectory(scenario.traceDirectory()),
                "Trace directory not found: " + scenario.traceDirectory());
        Path bk2Path = findBk2(scenario.traceDirectory());
        Assumptions.assumeTrue(bk2Path != null,
                "No .bk2 file found in " + scenario.traceDirectory());

        try {
            Bk2Movie movie = new Bk2MovieLoader().load(bk2Path);
            Assumptions.assumeTrue(scenario.compareFrame() < movie.getFrames().size(),
                    "Scenario compareFrame exceeds BK2 length: " + scenario);

            HeadlessTestFixture fixture = HeadlessTestFixture.builder()
                    .withRecording(bk2Path)
                    .withZoneAndAct(scenario.zoneIndex(), scenario.actIndex())
                    .build();
            GameplayModeContext gameplayMode = TestEnvironment.activeGameplayMode();
            assertNotNull(gameplayMode, "GameplayModeContext must be available after fixture build");
            RewindRegistry registry = gameplayMode.getRewindRegistry();
            assertNotNull(registry, "RewindRegistry must be available after fixture build");

            RewindController controller = new RewindController(
                    registry,
                    new InMemoryKeyframeStore(),
                    new MovieInputSource(movie),
                    new FixtureStepper(fixture),
                    KEYFRAME_INTERVAL);

            CompositeSnapshot forwardReference =
                    captureForwardReference(scenario, controller, registry);
            controller.seekTo(scenario.rewindStartFrame());
            while (controller.currentFrame() < scenario.compareFrame()) {
                controller.step();
            }
            assertEquals(scenario.compareFrame(), controller.currentFrame(),
                    "rewind+replay must land on the requested comparison frame");

            CompositeSnapshot rewindReplay = registry.capture();
            List<String> failures = compareSelectedKeys(scenario, forwardReference, rewindReplay);
            if (!failures.isEmpty()) {
                fail(formatFailure(scenario, failures));
            }
        } finally {
            TestEnvironment.resetAll();
            GenericRewindEligibility.clearForTest();
        }
    }

    private static CompositeSnapshot captureForwardReference(
            RewindEncounterScenario scenario,
            RewindController controller,
            RewindRegistry registry) {
        while (controller.currentFrame() < scenario.compareFrame()) {
            controller.step();
        }
        assertEquals(scenario.compareFrame(), controller.currentFrame(),
                "forward-only pass must land on the requested comparison frame");
        return registry.capture();
    }

    private static List<String> compareSelectedKeys(
            RewindEncounterScenario scenario,
            CompositeSnapshot expected,
            CompositeSnapshot actual) {
        List<String> failures = new ArrayList<>();
        for (String key : scenario.snapshotKeys()) {
            Object expectedValue = expected.get(key);
            Object actualValue = actual.get(key);
            List<String> diffs = RewindSnapshotDiff.diffKey(key, expectedValue, actualValue);
            if (!diffs.isEmpty()) {
                failures.add("[" + key + "]");
                for (String diff : diffs) {
                    failures.add("  " + diff);
                }
            }
        }
        return failures;
    }

    private static String formatFailure(RewindEncounterScenario scenario, List<String> failures) {
        return "Rewind encounter diverged: " + scenario.id()
                + " (" + scenario.game() + " " + scenario.zone() + scenario.act()
                + ", family=" + scenario.objectFamily()
                + ", mechanic=" + scenario.mechanic()
                + ", rewindStart=" + scenario.rewindStartFrame()
                + ", compareFrame=" + scenario.compareFrame() + ")\n"
                + String.join("\n", failures);
    }

    private static Path findBk2(Path dir) throws Exception {
        try (var stream = Files.list(dir)) {
            return stream
                    .filter(p -> p.toString().endsWith(".bk2"))
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
                return new Bk2FrameInput(frame, 0, 0, false, "encounter:oor:" + frame);
            }
            return movie.getFrame(frame);
        }
    }
}
