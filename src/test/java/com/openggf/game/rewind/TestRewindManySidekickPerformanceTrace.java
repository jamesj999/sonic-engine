package com.openggf.game.rewind;

import com.openggf.tests.TestSessionOutputPaths;

import com.openggf.debug.playback.Bk2FrameInput;
import com.openggf.game.sonic2.Sonic2GameModule;
import com.openggf.game.rewind.snapshot.SpriteManagerSnapshot;
import com.openggf.level.objects.PerObjectRewindSnapshot.PlayerRewindExtra;
import com.openggf.sprites.managers.SpriteManager;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.sprites.playable.SidekickCpuController;
import com.openggf.sprites.playable.Sonic;
import com.openggf.sprites.playable.Tails;
import com.openggf.tests.TestEnvironment;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LongSummaryStatistics;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestRewindManySidekickPerformanceTrace {

    private static final int SIDEKICK_COUNT = 20;
    private static final int SPRITE_COUNT = SIDEKICK_COUNT + 1;
    private static final int FRAMES = 240;
    private static final int KEYFRAME_INTERVAL = 15;
    private static final String TERMINAL_SIDEKICK_CODE = "tails_p" + (SIDEKICK_COUNT + 1);

    @BeforeEach
    void setUp() {
        TestEnvironment.configureGameModuleFixture(new Sonic2GameModule());
    }

    @AfterEach
    void tearDown() {
        TestEnvironment.resetAll();
    }

    @Test
    void tracesTwentySidekickRewindCaptureAndReplayCost() throws IOException {
        SpriteManager spriteManager = new SpriteManager();
        Sonic sonic = new Sonic("sonic", (short) 0x100, (short) 0x200);
        spriteManager.addSprite(sonic);

        AbstractPlayableSprite leader = sonic;
        for (int i = 0; i < SIDEKICK_COUNT; i++) {
            Tails sidekick = new Tails("tails_p" + (i + 2), (short) (0x0E0 - i * 0x10), (short) 0x200);
            sidekick.setCpuControlled(true);
            sidekick.setCpuController(new SidekickCpuController(sidekick, leader));
            spriteManager.addSprite(sidekick, "tails");
            leader = sidekick;
        }

        TimedSpriteManagerSnapshottable timedSprites =
                new TimedSpriteManagerSnapshottable(spriteManager.rewindSnapshottable());
        RewindRegistry registry = new RewindRegistry();
        registry.register(timedSprites);

        RewindController controller = new RewindController(
                registry,
                new InMemoryKeyframeStore(),
                new DeterministicInputSource(FRAMES + 1),
                new SyntheticManySidekickStepper(sonic, spriteManager),
                KEYFRAME_INTERVAL);

        for (int i = 0; i < FRAMES; i++) {
            controller.step();
        }

        LongSummaryStatistics seekStats = new LongSummaryStatistics();
        for (int target : new int[]{180, 90, 150, 45, 210, 120, 239}) {
            long start = System.nanoTime();
            controller.seekTo(target);
            seekStats.accept(System.nanoTime() - start);
        }

        assertEquals(SPRITE_COUNT, timedSprites.maxSpriteEntries(),
                "performance trace must exercise the full 20-sidekick chain");
        assertEquals(SIDEKICK_COUNT, timedSprites.maxHistoryBearingEntries(),
                "terminal sidekick should not carry unused follow-history arrays");
        assertTrue(timedSprites.terminalSidekickSeen(),
                "performance trace should include the terminal sidekick entry");
        assertTrue(timedSprites.terminalSidekickAlwaysCompact(),
                "terminal sidekick gained follow-history in at least one keyframe");
        assertTrue(timedSprites.captureCount() >= FRAMES / KEYFRAME_INTERVAL + 1,
                "trace should include initial capture plus regular rewind keyframes");
        assertTrue(seekStats.getCount() > 0, "trace should include replay seeks");

        Path tracePath = writeTrace(timedSprites, seekStats);
        System.out.printf(Locale.ROOT,
                "rewind-many-sidekick-trace sidekicks=%d frames=%d keyframeInterval=%d "
                        + "captures=%d captureMeanMs=%.3f captureMaxMs=%.3f "
                        + "seekMeanMs=%.3f seekMaxMs=%.3f maxSnapshotBytes=%d historyEntries=%d trace=%s%n",
                SIDEKICK_COUNT,
                FRAMES,
                KEYFRAME_INTERVAL,
                timedSprites.captureCount(),
                nsToMs(timedSprites.captureNs().stream().mapToLong(Long::longValue).average().orElse(0.0)),
                nsToMs(timedSprites.captureNs().stream().mapToLong(Long::longValue).max().orElse(0L)),
                nsToMs(seekStats.getAverage()),
                nsToMs(seekStats.getMax()),
                timedSprites.captureBytes().stream().mapToLong(Long::longValue).max().orElse(0L),
                timedSprites.maxHistoryBearingEntries(),
                tracePath);
    }

    private static Path writeTrace(
            TimedSpriteManagerSnapshottable timedSprites,
            LongSummaryStatistics seekStats) throws IOException {
        Path targetDir = TestSessionOutputPaths.diagnostics("rewind");
        Files.createDirectories(targetDir);
        Path tracePath = targetDir.resolve("rewind-many-sidekick-performance-trace.json");
        String json = String.format(Locale.ROOT, """
                {
                  "sidekickCount": %d,
                  "frames": %d,
                  "keyframeInterval": %d,
                  "captureCount": %d,
                  "captureMeanNs": %.0f,
                  "captureMaxNs": %d,
                  "seekCount": %d,
                  "seekMeanNs": %.0f,
                  "seekMaxNs": %d,
                  "maxSnapshotBytes": %d,
                  "maxSpriteEntries": %d,
                  "maxHistoryBearingEntries": %d,
                  "terminalSidekickSeen": %s,
                  "terminalSidekickAlwaysCompact": %s
                }
                """,
                SIDEKICK_COUNT,
                FRAMES,
                KEYFRAME_INTERVAL,
                timedSprites.captureCount(),
                timedSprites.captureNs().stream().mapToLong(Long::longValue).average().orElse(0.0),
                timedSprites.captureNs().stream().mapToLong(Long::longValue).max().orElse(0L),
                seekStats.getCount(),
                seekStats.getAverage(),
                seekStats.getMax(),
                timedSprites.captureBytes().stream().mapToLong(Long::longValue).max().orElse(0L),
                timedSprites.maxSpriteEntries(),
                timedSprites.maxHistoryBearingEntries(),
                timedSprites.terminalSidekickSeen(),
                timedSprites.terminalSidekickAlwaysCompact());
        Files.writeString(tracePath, json);
        return tracePath;
    }

    private static double nsToMs(double ns) {
        return ns / 1_000_000.0;
    }

    private static final class TimedSpriteManagerSnapshottable
            implements RewindSnapshottable<SpriteManagerSnapshot> {

        private final RewindSnapshottable<SpriteManagerSnapshot> delegate;
        private final List<Long> captureNs = new ArrayList<>();
        private final List<Long> captureBytes = new ArrayList<>();
        private int maxSpriteEntries;
        private int maxHistoryBearingEntries;
        private boolean terminalSidekickSeen;
        private boolean terminalSidekickAlwaysCompact = true;

        private TimedSpriteManagerSnapshottable(RewindSnapshottable<SpriteManagerSnapshot> delegate) {
            this.delegate = delegate;
        }

        @Override
        public String key() {
            return delegate.key();
        }

        @Override
        public SpriteManagerSnapshot capture() {
            long start = System.nanoTime();
            SpriteManagerSnapshot snapshot = delegate.capture();
            captureNs.add(System.nanoTime() - start);
            captureBytes.add(RewindBenchmark.estimateStructuralSize(snapshot));
            inspect(snapshot);
            return snapshot;
        }

        @Override
        public void restore(SpriteManagerSnapshot snapshot) {
            delegate.restore(snapshot);
        }

        @Override
        public void resetForMissingSnapshot() {
            delegate.resetForMissingSnapshot();
        }

        private void inspect(SpriteManagerSnapshot snapshot) {
            maxSpriteEntries = Math.max(maxSpriteEntries, snapshot.sprites().length);
            int historyBearingEntries = 0;
            for (SpriteManagerSnapshot.SpriteEntry entry : snapshot.sprites()) {
                PlayerRewindExtra extra = entry.state().playerExtra();
                boolean hasFollowHistory = extra != null && extra.xHistory() != null;
                if (hasFollowHistory) {
                    historyBearingEntries++;
                }
                if (TERMINAL_SIDEKICK_CODE.equals(entry.code())) {
                    terminalSidekickSeen = true;
                    terminalSidekickAlwaysCompact &= !hasFollowHistory;
                }
            }
            maxHistoryBearingEntries = Math.max(maxHistoryBearingEntries, historyBearingEntries);
        }

        private int captureCount() {
            return captureNs.size();
        }

        private List<Long> captureNs() {
            return captureNs;
        }

        private List<Long> captureBytes() {
            return captureBytes;
        }

        private int maxSpriteEntries() {
            return maxSpriteEntries;
        }

        private int maxHistoryBearingEntries() {
            return maxHistoryBearingEntries;
        }

        private boolean terminalSidekickSeen() {
            return terminalSidekickSeen;
        }

        private boolean terminalSidekickAlwaysCompact() {
            return terminalSidekickAlwaysCompact;
        }
    }

    private static final class DeterministicInputSource implements InputSource {

        private final int frameCount;

        private DeterministicInputSource(int frameCount) {
            this.frameCount = frameCount;
        }

        @Override
        public int frameCount() {
            return frameCount;
        }

        @Override
        public Bk2FrameInput read(int frame) {
            int mask = inputMaskForFrame(frame);
            int actionMask = (mask & AbstractPlayableSprite.INPUT_JUMP) != 0 ? 0x01 : 0;
            return new Bk2FrameInput(frame, mask, actionMask, false, "synthetic-random-" + frame);
        }

        private static int inputMaskForFrame(int frame) {
            int mixed = mix(frame);
            int mask = 0;
            if (((frame / 30) & 1) == 0) {
                mask |= AbstractPlayableSprite.INPUT_RIGHT;
            } else {
                mask |= AbstractPlayableSprite.INPUT_LEFT;
            }
            if ((mixed & 0x0F) == 0) {
                mask |= AbstractPlayableSprite.INPUT_JUMP;
            }
            if ((mixed & 0x30) == 0x10) {
                mask |= AbstractPlayableSprite.INPUT_UP;
            } else if ((mixed & 0x30) == 0x20) {
                mask |= AbstractPlayableSprite.INPUT_DOWN;
            }
            return mask;
        }

        private static int mix(int value) {
            int x = value + 0x9E3779B9;
            x ^= x >>> 16;
            x *= 0x85EBCA6B;
            x ^= x >>> 13;
            x *= 0xC2B2AE35;
            x ^= x >>> 16;
            return x;
        }
    }

    private static final class SyntheticManySidekickStepper implements RewindSeekAwareEngineStepper {

        private static final int GROUND_Y = 0x200;

        private final Sonic sonic;
        private final SpriteManager spriteManager;
        private int yVelocity;
        private boolean previousJump;

        private SyntheticManySidekickStepper(Sonic sonic, SpriteManager spriteManager) {
            this.sonic = sonic;
            this.spriteManager = spriteManager;
        }

        @Override
        public com.openggf.LevelFrameResult step(Bk2FrameInput inputs) {
            int mask = inputs.p1InputMask();
            boolean jump = (mask & AbstractPlayableSprite.INPUT_JUMP) != 0;
            int dx = ((mask & AbstractPlayableSprite.INPUT_RIGHT) != 0 ? 2 : 0)
                    - ((mask & AbstractPlayableSprite.INPUT_LEFT) != 0 ? 2 : 0);
            moveMainSprite(mask, jump, dx);
            moveSidekicks(mask, jump);
            previousJump = jump;
            return com.openggf.LevelFrameResult.GAMEPLAY_FRAME;
        }

        @Override
        public void restoreToFrame(int frame, Bk2FrameInput inputAtFrame) {
            previousJump = inputAtFrame != null
                    && (inputAtFrame.p1InputMask() & AbstractPlayableSprite.INPUT_JUMP) != 0;
            yVelocity = sonic.getYSpeed() >> 8;
        }

        private void moveMainSprite(int mask, boolean jump, int dx) {
            if (jump && !previousJump && !sonic.getAir()) {
                yVelocity = -8;
                sonic.setAir(true);
            }
            if (sonic.getAir()) {
                yVelocity++;
                int nextY = sonic.getY() + yVelocity;
                if (nextY >= GROUND_Y) {
                    nextY = GROUND_Y;
                    yVelocity = 0;
                    sonic.setAir(false);
                }
                sonic.setY((short) nextY);
            }

            sonic.setX((short) (sonic.getX() + dx));
            sonic.setXSpeed((short) (dx << 8));
            sonic.setYSpeed((short) (yVelocity << 8));
            sonic.setGSpeed((short) (dx << 8));
            recordFollowerHistory(sonic, mask, jump);
        }

        private void moveSidekicks(int mask, boolean jump) {
            AbstractPlayableSprite leader = sonic;
            int index = 0;
            for (AbstractPlayableSprite sidekick : spriteManager.getSidekicks()) {
                sidekick.setX((short) (leader.getX() - 12));
                sidekick.setY((short) (leader.getY() + ((index % 3) - 1)));
                sidekick.setXSpeed((short) (leader.getXSpeed() - (index & 1)));
                sidekick.setYSpeed(leader.getYSpeed());
                sidekick.setGSpeed(leader.getGSpeed());
                sidekick.setAir(leader.getAir());
                recordFollowerHistory(sidekick, mask, jump);
                leader = sidekick;
                index++;
            }
        }

        private static void recordFollowerHistory(AbstractPlayableSprite sprite, int mask, boolean jump) {
            sprite.writeLogicalInputAndCurrentFollowerHistory(mask, jump);
            sprite.recordFollowerHistoryForTick();
            sprite.clearFollowerHistoryRecordedFlag();
        }
    }
}
