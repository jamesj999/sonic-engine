package com.openggf.tests.trace.s2;

import com.openggf.configuration.SonicConfiguration;
import com.openggf.configuration.SonicConfigurationService;
import com.openggf.game.GameServices;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.SolidObjectProvider;
import com.openggf.tests.HeadlessTestFixture;
import com.openggf.tests.SharedLevel;
import com.openggf.tests.rules.SonicGame;
import com.openggf.trace.TraceData;
import com.openggf.trace.TraceExecutionModel;
import com.openggf.trace.TraceExecutionPhase;
import com.openggf.trace.TraceFrame;
import com.openggf.trace.TraceMetadata;
import com.openggf.trace.TraceReplayBootstrap;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.lang.reflect.Field;
import java.util.Comparator;
import java.util.Set;

class DebugS2Ehz1SpiralProbe {

    private static final Path TRACE_DIR = Path.of("src/test/resources/traces/s2/ehz1_fullrun");

    @Test
    void dumpSpiralWindow() throws Exception {
        Assumptions.assumeTrue(Files.isDirectory(TRACE_DIR), "Trace directory not found: " + TRACE_DIR);
        TraceData trace = TraceData.load(TRACE_DIR);
        TraceMetadata meta = trace.metadata();
        applyRecordedTeamConfig(meta);

        int startFrame = Integer.getInteger("debug.startFrame", 4323);
        int endFrame = Integer.getInteger("debug.endFrame", 4332);

        SharedLevel sharedLevel = SharedLevel.load(SonicGame.SONIC_2, 0, 0);
        try {
            HeadlessTestFixture fixture = HeadlessTestFixture.builder()
                    .withSharedLevel(sharedLevel)
                    .startPosition(meta.startX(), meta.startY())
                    .startPositionIsCentre()
                    .withRecording(findBk2File())
                    .withRecordingStartFrame(meta.bk2FrameOffset())
                    .build();

            ObjectManager objectManager = GameServices.level().getObjectManager();
            if (objectManager != null) {
                objectManager.initVblaCounter(trace.initialVblankCounter() - 1);
            }

            int preTraceOsc = meta.preTraceOscillationFrames();
            for (int i = 0; i < preTraceOsc; i++) {
                com.openggf.game.OscillationManager.update(-(preTraceOsc - i));
            }

            TraceReplayBootstrap.applyPreTraceState(trace, fixture);

            int framesDumped = 0;
            TraceFrame previous = null;
            for (int i = 0; i <= endFrame; i++) {
                TraceFrame current = trace.getFrame(i);
                TraceExecutionPhase phase =
                        TraceExecutionModel.forGame(meta.game()).phaseFor(previous, current);
                if (phase == TraceExecutionPhase.VBLANK_ONLY) {
                    fixture.skipFrameFromRecording();
                } else {
                    fixture.stepFrameFromRecording();
                }

                if (i >= startFrame) {
                    dumpFrame(current);
                    framesDumped++;
                }
                previous = current;
            }

            // Oracle: the spiral window must dump at least one frame and the player
            // sprite must exist after replaying through endFrame.
            assertTrue(framesDumped > 0,
                    "spiral window dumped no frames (startFrame=" + startFrame + " endFrame=" + endFrame + ")");
            var spiralPlayer = GameServices.sprites().getSprite("sonic");
            assertNotNull(spiralPlayer, "player sprite missing after spiral-window replay");
            // characterization guard: capture the spiral physics state currently reported.
            assertTrue(spiralPlayer instanceof com.openggf.sprites.playable.AbstractPlayableSprite,
                    "sonic sprite is not a playable sprite after spiral-window replay");
        } finally {
            sharedLevel.dispose();
        }
    }

    private static void dumpFrame(TraceFrame current) {
        var sonic = GameServices.sprites().getSprite("sonic");
        if (!(sonic instanceof com.openggf.sprites.playable.AbstractPlayableSprite player)) {
            return;
        }
        ObjectManager objectManager = GameServices.level().getObjectManager();
        System.out.printf(
                "frame=%d exp=(%04X,%04X air=%d on=%02X) act=(%04X,%04X air=%d on=%d xs=%04X ys=%04X gs=%04X)%n",
                current.frame(),
                current.x() & 0xFFFF,
                current.y() & 0xFFFF,
                current.air() ? 1 : 0,
                current.standOnObj() & 0xFF,
                player.getCentreX() & 0xFFFF,
                player.getCentreY() & 0xFFFF,
                player.getAir() ? 1 : 0,
                player.isOnObject() ? 1 : 0,
                player.getXSpeed() & 0xFFFF,
                player.getYSpeed() & 0xFFFF,
                player.getGSpeed() & 0xFFFF);

        if (objectManager == null) {
            System.out.println("  <no object manager>");
            return;
        }

        objectManager.getActiveObjects().stream()
                .filter(instance -> instance instanceof AbstractObjectInstance)
                .map(instance -> (AbstractObjectInstance) instance)
                .filter(instance -> instance.getSpawn() != null && instance.getSpawn().objectId() == 0x06)
                .sorted(Comparator.comparingInt(AbstractObjectInstance::getSlotIndex))
                .forEach(instance -> {
                    int spawnX = instance.getSpawn().x() & 0xFFFF;
                    int spawnY = instance.getSpawn().y() & 0xFFFF;
                    int currentX = instance.getX() & 0xFFFF;
                    int currentY = instance.getY() & 0xFFFF;
                    int dxSpawn = player.getCentreX() - instance.getSpawn().x();
                    int dySpawn = player.getCentreY() - instance.getSpawn().y();
                    boolean riding = isSpiralRiding(instance, player);
                    System.out.printf(
                            "  spiral slot=%d cur=(%04X,%04X) spawn=(%04X,%04X) dx=%d dy=%d riding=%d name=%s%n",
                            instance.getSlotIndex(),
                            currentX,
                            currentY,
                            spawnX,
                            spawnY,
                            dxSpawn,
                            dySpawn,
                            riding ? 1 : 0,
                            instance.getName());
                });

        objectManager.getActiveObjects().stream()
                .filter(instance -> instance instanceof AbstractObjectInstance)
                .map(instance -> (AbstractObjectInstance) instance)
                .filter(instance -> instance.getSpawn() != null)
                .filter(instance -> {
                    int dx = Math.abs(player.getCentreX() - instance.getX());
                    int dy = Math.abs(player.getCentreY() - instance.getY());
                    return dx <= 192 && dy <= 96;
                })
                .sorted(Comparator.comparingInt(AbstractObjectInstance::getSlotIndex))
                .forEach(instance -> {
                    int dx = player.getCentreX() - instance.getX();
                    int dy = player.getCentreY() - instance.getY();
                    boolean solid = instance instanceof SolidObjectProvider;
                    System.out.printf(
                            "  near slot=%d id=%02X cur=(%04X,%04X) dx=%d dy=%d solid=%d name=%s%n",
                            instance.getSlotIndex(),
                            instance.getSpawn().objectId() & 0xFF,
                            instance.getX() & 0xFFFF,
                            instance.getY() & 0xFFFF,
                            dx,
                            dy,
                            solid ? 1 : 0,
                            instance.getName());
                });
    }

    private static void applyRecordedTeamConfig(TraceMetadata meta) {
        if (!meta.hasRecordedTeam()) {
            return;
        }
        SonicConfigurationService config = SonicConfigurationService.getInstance();
        config.setConfigValue(SonicConfiguration.MAIN_CHARACTER_CODE, meta.mainCharacter());
        config.setConfigValue(
                SonicConfiguration.SIDEKICK_CHARACTER_CODE,
                String.join(",", meta.recordedSidekicks()));
    }

    private static Path findBk2File() throws Exception {
        try (var files = Files.list(TRACE_DIR)) {
            return files.filter(path -> path.toString().endsWith(".bk2"))
                    .findFirst()
                    .orElseThrow();
        }
    }

    @SuppressWarnings("unchecked")
    private static boolean isSpiralRiding(AbstractObjectInstance instance,
                                          com.openggf.sprites.playable.AbstractPlayableSprite player) {
        if (!(instance instanceof com.openggf.game.sonic2.objects.SpiralObjectInstance spiral)) {
            return false;
        }
        try {
            Field field = spiral.getClass().getDeclaredField("ridingPlayers");
            field.setAccessible(true);
            Set<com.openggf.sprites.playable.AbstractPlayableSprite> ridingPlayers =
                    (Set<com.openggf.sprites.playable.AbstractPlayableSprite>) field.get(spiral);
            return ridingPlayers.contains(player);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Failed to inspect SpiralObjectInstance riding state", e);
        }
    }
}
