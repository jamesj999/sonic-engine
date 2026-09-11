package com.openggf.game.rewind;

import com.openggf.debug.playback.Bk2FrameInput;
import com.openggf.debug.playback.Bk2Movie;
import com.openggf.debug.playback.Bk2MovieLoader;
import com.openggf.game.rewind.snapshot.ObjectManagerSnapshot;
import com.openggf.game.session.GameplayModeContext;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.tests.HeadlessTestFixture;
import com.openggf.tests.TestEnvironment;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

@RequiresRom(SonicGame.SONIC_2)
class TestRewindTraceSeekDeterminism {
    private static final Path TRACE_DIR =
            Path.of("src/test/resources/traces/s2/ehz1_fullrun");
    private static final int KEYFRAME_INTERVAL = 60;

    @BeforeEach
    void setup() {
        cleanup();
    }

    @AfterEach
    void cleanup() {
        TestEnvironment.resetAll();
        GenericRewindEligibility.clearForTest();
    }

    @Test
    void seekReplayFromKeyframeMatchesOriginalObjectManagerOnNextFrame() throws Exception {
        assertObjectManagerMatchesAfterSeekReplay(1140);
    }

    @Test
    void longerSeekReplayMatchesOriginalObjectManagerOnNextFrame() throws Exception {
        assertObjectManagerMatchesAfterSeekReplay(1080);
    }

    @Test
    void sequentialLongTailScrubsMatchOriginalObjectManager() throws Exception {
        assertObjectManagerMatchesAfterSequentialScrubs();
    }

    @Test
    void benchmarkStyleLongTailReplayMatchesOriginalObjectManagerAtOrigin() throws Exception {
        assertObjectManagerMatchesAfterBenchmarkStyleReplay();
    }

    private static void assertObjectManagerMatchesAfterSeekReplay(int seekTarget) throws Exception {
        Assumptions.assumeTrue(Files.isDirectory(TRACE_DIR),
                "Trace directory not found: " + TRACE_DIR);
        Path bk2 = findBk2();
        Bk2Movie movie = new Bk2MovieLoader().load(bk2);

        HeadlessTestFixture fixture = HeadlessTestFixture.builder()
                .withRecording(bk2)
                .withZoneAndAct(0, 0)
                .build();
        GameplayModeContext gm = TestEnvironment.activeGameplayMode();
        RewindController rc = new RewindController(
                gm.getRewindRegistry(),
                new InMemoryKeyframeStore(),
                new MovieInputSource(movie),
                new FixtureStepper(fixture),
                KEYFRAME_INTERVAL);

        List<CompositeSnapshot> baseline = new ArrayList<>(seekTarget + 2);
        baseline.add(gm.getRewindRegistry().capture());
        for (int i = 0; i < seekTarget + 1; i++) {
            rc.step();
            baseline.add(gm.getRewindRegistry().capture());
        }

        rc.seekTo(seekTarget);
        rc.step();

        ObjectManagerSnapshot expected =
                (ObjectManagerSnapshot) baseline.get(seekTarget + 1).get("object-manager");
        ObjectManagerSnapshot actual =
                (ObjectManagerSnapshot) gm.getRewindRegistry().capture().get("object-manager");
        List<String> diffs = compareObjectManager(expected, actual);
        assertTrue(diffs.isEmpty(), String.join("\n", diffs));
    }

    private static void assertObjectManagerMatchesAfterSequentialScrubs() throws Exception {
        Assumptions.assumeTrue(Files.isDirectory(TRACE_DIR),
                "Trace directory not found: " + TRACE_DIR);
        Path bk2 = findBk2();
        Bk2Movie movie = new Bk2MovieLoader().load(bk2);

        HeadlessTestFixture fixture = HeadlessTestFixture.builder()
                .withRecording(bk2)
                .withZoneAndAct(0, 0)
                .build();
        GameplayModeContext gm = TestEnvironment.activeGameplayMode();
        RewindController rc = new RewindController(
                gm.getRewindRegistry(),
                new InMemoryKeyframeStore(),
                new MovieInputSource(movie),
                new FixtureStepper(fixture),
                KEYFRAME_INTERVAL);

        List<CompositeSnapshot> baseline = new ArrayList<>(1201);
        baseline.add(gm.getRewindRegistry().capture());
        for (int i = 0; i < 1200; i++) {
            rc.step();
            baseline.add(gm.getRewindRegistry().capture());
        }

        rc.seekTo(1140);
        while (rc.currentFrame() < 1200) {
            rc.step();
        }

        rc.seekTo(1080);
        rc.step();

        ObjectManagerSnapshot expected =
                (ObjectManagerSnapshot) baseline.get(1081).get("object-manager");
        ObjectManagerSnapshot actual =
                (ObjectManagerSnapshot) gm.getRewindRegistry().capture().get("object-manager");
        List<String> diffs = compareObjectManager(expected, actual);
        assertTrue(diffs.isEmpty(), String.join("\n", diffs));
    }

    private static void assertObjectManagerMatchesAfterBenchmarkStyleReplay() throws Exception {
        Assumptions.assumeTrue(Files.isDirectory(TRACE_DIR),
                "Trace directory not found: " + TRACE_DIR);
        Path bk2 = findBk2();
        Bk2Movie movie = new Bk2MovieLoader().load(bk2);

        HeadlessTestFixture fixture = HeadlessTestFixture.builder()
                .withRecording(bk2)
                .withZoneAndAct(0, 0)
                .build();
        GameplayModeContext gm = TestEnvironment.activeGameplayMode();
        gm.installPlaybackController(
                new MovieInputSource(movie),
                new FixtureStepper(fixture),
                KEYFRAME_INTERVAL);
        RewindController rc = gm.getRewindController();

        List<CompositeSnapshot> baseline = new ArrayList<>(1201);
        baseline.add(gm.getRewindRegistry().capture());
        for (int i = 0; i < 1200; i++) {
            rc.step();
            baseline.add(gm.getRewindRegistry().capture());
        }

        rc.seekTo(1140);
        while (rc.currentFrame() < 1200) {
            rc.step();
        }

        rc.seekTo(1080);
        while (rc.currentFrame() < 1200) {
            rc.step();
        }

        ObjectManagerSnapshot expected =
                (ObjectManagerSnapshot) baseline.get(1200).get("object-manager");
        ObjectManagerSnapshot actual =
                (ObjectManagerSnapshot) gm.getRewindRegistry().capture().get("object-manager");
        List<String> diffs = compareObjectManager(expected, actual);
        assertTrue(diffs.isEmpty(), String.join("\n", diffs));
    }

    private static List<String> compareObjectManager(ObjectManagerSnapshot a, ObjectManagerSnapshot b) {
        List<String> diffs = new ArrayList<>();
        if (!Arrays.equals(a.usedSlotsBits(), b.usedSlotsBits())) {
            diffs.add("usedSlotsBits A=" + Arrays.toString(a.usedSlotsBits())
                    + " B=" + Arrays.toString(b.usedSlotsBits()));
        }
        if (a.frameCounter() != b.frameCounter()) {
            diffs.add("frameCounter A=" + a.frameCounter() + " B=" + b.frameCounter());
        }
        if (a.vblaCounter() != b.vblaCounter()) {
            diffs.add("vblaCounter A=" + a.vblaCounter() + " B=" + b.vblaCounter());
        }
        if (a.slots().size() != b.slots().size()) {
            diffs.add("slot count A=" + a.slots().size() + " B=" + b.slots().size());
            return diffs;
        }
        for (int i = 0; i < a.slots().size(); i++) {
            var ea = a.slots().get(i);
            var eb = b.slots().get(i);
            if (ea.slotIndex() != eb.slotIndex()) {
                diffs.add("slot[" + i + "].slotIndex A=" + ea.slotIndex() + " B=" + eb.slotIndex());
            }
            if (!ea.state().equals(eb.state())) {
                diffs.add("slot[" + i + "] spawn=" + ea.spawn()
                        + " state A=" + ea.state() + " B=" + eb.state());
            }
        }
        if (!a.dynamicObjects().equals(b.dynamicObjects())) {
            diffs.add("dynamicObjects A=" + a.dynamicObjects() + " B=" + b.dynamicObjects());
        }
        return diffs;
    }

    private static Path findBk2() throws Exception {
        try (var stream = Files.list(TRACE_DIR)) {
            return stream.filter(p -> p.toString().endsWith(".bk2"))
                    .findFirst()
                    .orElseThrow();
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
