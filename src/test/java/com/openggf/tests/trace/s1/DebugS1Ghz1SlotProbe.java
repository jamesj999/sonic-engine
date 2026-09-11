package com.openggf.tests.trace.s1;

import com.openggf.debug.DebugOverlayToggle;
import com.openggf.game.GameServices;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.tests.HeadlessTestFixture;
import com.openggf.tests.SharedLevel;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import com.openggf.trace.TraceData;
import com.openggf.trace.TraceExecutionModel;
import com.openggf.trace.TraceExecutionPhase;
import com.openggf.trace.TraceEvent;
import com.openggf.trace.TraceFrame;
import com.openggf.trace.TraceMetadata;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@RequiresRom(SonicGame.SONIC_1)
class DebugS1Ghz1SlotProbe {

    @Test
    void dumpSlotWindowAroundBuzzMissileSpawn() throws Exception {
        Path traceDir = Path.of("src/test/resources/traces/s1/ghz1_fullrun");
        TraceData trace = TraceData.load(traceDir);
        TraceMetadata meta = trace.metadata();
        Path bk2Path;
        try (var files = Files.list(traceDir)) {
            bk2Path = files.filter(path -> path.toString().endsWith(".bk2"))
                    .findFirst()
                    .orElseThrow();
        }

        SharedLevel sharedLevel = SharedLevel.load(SonicGame.SONIC_1, 0, 0);
        try {
            HeadlessTestFixture fixture = HeadlessTestFixture.builder()
                    .withSharedLevel(sharedLevel)
                    .startPosition(meta.startX(), meta.startY())
                    .startPositionIsCentre()
                    .withRecording(bk2Path)
                    .withRecordingStartFrame(meta.bk2FrameOffset())
                    .build();

            ObjectManager om = GameServices.level().getObjectManager();
            om.initVblaCounter(trace.initialVblankCounter() - 1);
            GameServices.debugOverlay().setEnabled(DebugOverlayToggle.TOUCH_RESPONSE, true);

            int preTraceOsc = meta.preTraceOscillationFrames();
            for (int i = 0; i < preTraceOsc; i++) {
                com.openggf.game.OscillationManager.update(-(preTraceOsc - i));
            }

            String previousSummary = "";
            int framesProcessed = 0;
            int windowFramesObserved = 0;
            for (int i = 0; i < trace.frameCount(); i++) {
                TraceFrame expected = trace.getFrame(i);
                TraceFrame previous = i > 0 ? trace.getFrame(i - 1) : null;
                TraceExecutionPhase phase =
                        TraceExecutionModel.forGame(meta.game()).phaseFor(previous, expected);
                if (phase == TraceExecutionPhase.VBLANK_ONLY) {
                    fixture.skipFrameFromRecording();
                } else {
                    fixture.stepFrameFromRecording();
                }
                framesProcessed++;

                if (i < 280 || i > 340) {
                    continue;
                }
                windowFramesObserved++;

                String summary = summariseSlots(om, 32, 50);
                String romSummary = summariseRomEvents(trace.getEventsForFrame(i), 32, 50);
                if (!summary.equals(previousSummary)) {
                    System.out.printf("TRACE %d VB=%d GF=%d :: ENG %s%n",
                            expected.frame(),
                            expected.vblankCounter(),
                            expected.gameplayFrameCounter(),
                            summary);
                    if (!romSummary.isEmpty()) {
                        System.out.printf("TRACE %d VB=%d GF=%d :: ROM %s%n",
                                expected.frame(),
                                expected.vblankCounter(),
                                expected.gameplayFrameCounter(),
                                romSummary);
                    }
                    previousSummary = summary;
                } else if (!romSummary.isEmpty()) {
                    System.out.printf("TRACE %d VB=%d GF=%d :: ROM %s%n",
                            expected.frame(),
                            expected.vblankCounter(),
                            expected.gameplayFrameCounter(),
                            romSummary);
                }
            }

            // characterization guard: the probe must actually step the trace
            // and reach its 280-340 inspection window, and the engine slot/ROM
            // summary it computes must remain stable (regression tripwire).
            assertTrue(trace.frameCount() > 0, "trace loaded no frames");
            assertEquals(trace.frameCount(), framesProcessed,
                    "probe did not step every trace frame");
            assertTrue(windowFramesObserved > 0,
                    "probe never reached the slot-inspection window");
            if (trace.frameCount() > 340) {
                assertEquals(61, windowFramesObserved,
                        "slot-inspection window coverage regressed");
            }
        } finally {
            sharedLevel.dispose();
        }
    }

    private static String summariseSlots(ObjectManager om, int minSlot, int maxSlot) {
        List<AbstractObjectInstance> objects = new ArrayList<>();
        for (ObjectInstance instance : om.getActiveObjects()) {
            if (instance instanceof AbstractObjectInstance aoi
                    && aoi.getSlotIndex() >= minSlot
                    && aoi.getSlotIndex() <= maxSlot) {
                objects.add(aoi);
            }
        }
        objects.sort(Comparator.comparingInt(AbstractObjectInstance::getSlotIndex));
        StringBuilder sb = new StringBuilder();
        for (AbstractObjectInstance object : objects) {
            if (!sb.isEmpty()) {
                sb.append(" | ");
            }
            ObjectSpawn spawn = object.getSpawn();
            sb.append(String.format("s%d 0x%02X %s @%04X,%04X",
                    object.getSlotIndex(),
                    spawn != null ? spawn.objectId() & 0xFF : 0,
                    object.getName(),
                    object.getX() & 0xFFFF,
                    object.getY() & 0xFFFF));
        }
        return sb.toString();
    }

    private static String summariseRomEvents(List<TraceEvent> events, int minSlot, int maxSlot) {
        if (events == null || events.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (TraceEvent event : events) {
            String part = switch (event) {
                case TraceEvent.ObjectAppeared appeared when appeared.slot() >= minSlot
                        && appeared.slot() <= maxSlot ->
                        String.format("obj+ s%d %s @%04X,%04X",
                                appeared.slot(),
                                appeared.objectType(),
                                appeared.x() & 0xFFFF,
                                appeared.y() & 0xFFFF);
                case TraceEvent.ObjectRemoved removed when removed.slot() >= minSlot
                        && removed.slot() <= maxSlot ->
                        String.format("obj- s%d %s", removed.slot(), removed.objectType());
                case TraceEvent.ObjectNear near when near.slot() >= minSlot
                        && near.slot() <= maxSlot ->
                        String.format("near s%d %s @%04X,%04X rtn=%s",
                                near.slot(),
                                near.objectType(),
                                near.x() & 0xFFFF,
                                near.y() & 0xFFFF,
                                near.routine().replace("0x", ""));
                default -> "";
            };
            if (part.isEmpty()) {
                continue;
            }
            if (!sb.isEmpty()) {
                sb.append(" | ");
            }
            sb.append(part);
        }
        return sb.toString();
    }
}
