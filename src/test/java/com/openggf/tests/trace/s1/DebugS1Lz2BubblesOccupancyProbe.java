package com.openggf.tests.trace.s1;

import com.openggf.debug.DebugOverlayToggle;
import com.openggf.game.GameServices;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.ObjectSlotLayout;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.TouchResponseDebugState;
import com.openggf.level.rings.RingManager;
import com.openggf.level.rings.RingSpawn;
import com.openggf.tests.HeadlessTestFixture;
import com.openggf.tests.SharedLevel;
import com.openggf.tests.TestEnvironment;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import com.openggf.tests.trace.ObjectOccupancyOracle;
import com.openggf.trace.TraceData;
import com.openggf.trace.TraceEvent;
import com.openggf.trace.TraceExecutionPhase;
import com.openggf.trace.TraceFrame;
import com.openggf.trace.TraceMetadata;
import com.openggf.trace.TouchResponseDebugHitFormatter;
import com.openggf.trace.TraceReplayBootstrap;
import com.openggf.trace.replay.TraceReplaySessionBootstrap;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Map;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Local comparison-only probe for the S1 LZ2 Obj64 frontier. This class is not
 * picked up by the default Surefire include pattern; run it explicitly while
 * diagnosing the LZ2 complete-run trace.
 */
@RequiresRom(SonicGame.SONIC_1)
class DebugS1Lz2BubblesOccupancyProbe {
    private static final Path TRACE_DIR = Path.of(System.getProperty(
            "trace.dir", "src/test/resources/traces/s1/lz2_completerun"));
    private static final int ZONE = Integer.getInteger("trace.zone", 3);
    private static final int ACT = Integer.getInteger("trace.act", 1);
    private static final String LABEL = System.getProperty("trace.label", "s1-lz2");
    private static final int START_FRAME = Integer.getInteger("trace.startFrame", 0);
    private static final int STOP_FRAME = Integer.getInteger("trace.stopFrame", Integer.MAX_VALUE);
    private static final int TARGET_X = parseOptionalIntProperty("trace.targetX", -1);
    private static final int TARGET_Y = parseOptionalIntProperty("trace.targetY", -1);
    private static final boolean HAS_TARGET = TARGET_X >= 0 && TARGET_Y >= 0;
    private static final int WATCH_SLOT = Integer.getInteger("trace.watchSlot", -1);
    private static final int WATCH_EVERY = Math.max(1, Integer.getInteger("trace.watchEvery", 30));
    private static final int OBJ_BUBBLES = 0x64;
    /**
     * Compare-phase offset for the Obj64 streams. The v3.4+ recorder samples
     * s1_obj64_state BEFORE the frame's ExecuteObjects pass, so ROM row N holds
     * the SST as pass N-1 left it. Set to 1 to line the engine's post-pass state
     * for frame N up against the ROM row that describes the same instant.
     */
    private static final int DUMP_FROM = Integer.getInteger("trace.obj64DumpFrom", -1);
    private static final int DUMP_TO = Integer.getInteger("trace.obj64DumpTo", -1);
    private static final int OBJ64_PHASE = Integer.getInteger("trace.obj64Phase", 0);
    private static final int FIRST_DYNAMIC_SLOT =
            ObjectSlotLayout.SONIC_1.firstDynamicSlot();

    @Test
    void measureObj64CountFrontier() throws Exception {
        Assumptions.assumeTrue(Files.isDirectory(TRACE_DIR),
                "Trace directory not found: " + TRACE_DIR);
        Assumptions.assumeTrue(Files.exists(TRACE_DIR.resolve("metadata.json")),
                "metadata.json not found in " + TRACE_DIR);

        TraceData trace = TraceData.load(TRACE_DIR);
        TraceMetadata meta = trace.metadata();
        Assumptions.assumeTrue("s1".equals(meta.game()),
                "Expected S1 metadata, got " + meta.game());

        Path bk2Path = resolveBk2File(TRACE_DIR, meta);
        Assumptions.assumeTrue(bk2Path != null,
                "No BK2 found for " + TRACE_DIR);

        boolean requiresFreshLevelLoad =
                TraceReplayBootstrap.requiresFreshLevelLoadForTraceReplay(trace);
        TraceReplaySessionBootstrap.prepareConfiguration(trace, meta);

        SharedLevel sharedLevel = requiresFreshLevelLoad
                ? null
                : SharedLevel.load(SonicGame.SONIC_1, ZONE, ACT);
        try {
            HeadlessTestFixture.Builder fixtureBuilder = HeadlessTestFixture.builder()
                    .withRecording(bk2Path)
                    .withRecordingStartFrame(
                            TraceReplayBootstrap.recordingStartFrameForTraceReplay(trace));
            if (sharedLevel != null) {
                fixtureBuilder.withSharedLevel(sharedLevel);
            } else {
                fixtureBuilder.withZoneAndAct(ZONE, ACT);
            }
            if (TraceReplayBootstrap.shouldApplyMetadataStartPositionForTraceReplay(trace)) {
                fixtureBuilder
                        .startPosition(meta.startX(), meta.startY())
                        .startPositionIsCentre();
            }
            HeadlessTestFixture fixture = fixtureBuilder.build();
            GameServices.debugOverlay().setEnabled(DebugOverlayToggle.TOUCH_RESPONSE, true);
            TraceReplaySessionBootstrap.applyStartPositionAndGroundSnap(trace, fixture);
            TraceReplaySessionBootstrap.BootstrapResult boot =
                    TraceReplaySessionBootstrap.applyBootstrap(trace, fixture, -1);

            ObjectManager objectManager = GameServices.level() != null
                    ? GameServices.level().getObjectManager()
                    : null;
            Assumptions.assumeTrue(objectManager != null,
                    "ObjectManager unavailable after bootstrap");
            System.out.printf("[%s-obj64-count] policy objectPreludeFrames=%d startTraceIndex=%d%n",
                    LABEL,
                    TraceReplayBootstrap.levelObjectTitleCardPreludeFramesForTraceReplay(trace),
                    boot.replayStart().startingTraceIndex());
            String postBootstrapObj64 = summarizeEngineObj64(objectManager);
            System.out.println("[" + LABEL + "-obj64-count] post-bootstrap engine Obj64: "
                    + postBootstrapObj64);

            // characterization guard: the bootstrap must produce a usable
            // occupancy scan to compare against. trace data is read-only here;
            // these only assert the probe actually loaded/bootstrapped a
            // non-empty trace and computed its reported Obj64 occupancy string.
            assertTrue(trace.frameCount() > 0, "trace loaded no frames");
            assertNotNull(postBootstrapObj64, "post-bootstrap Obj64 occupancy was not computed");

            int startTraceIndex = boot.replayStart().startingTraceIndex();
            boolean objectSlotDivergenceReported = false;
            boolean slotDivergenceReported = false;
            boolean makerStateDivergenceReported = false;
            boolean targetEngineAppearanceReported = false;
            boolean targetRomAppearanceReported = false;
            boolean ringCountDivergenceReported = false;
            int lastEngineRingCount = fixture.sprite().getRingCount();
            ObjectSpawn targetSpawn = HAS_TARGET ? findTargetSpawn(objectManager) : null;
            assertTrue(startTraceIndex < trace.frameCount(),
                    "occupancy scan window is empty (startTraceIndex past trace end)");
            for (int i = startTraceIndex; i < trace.frameCount(); i++) {
                TraceFrame expected = trace.getFrame(i);
                TraceFrame previous = i > 0 ? trace.getFrame(i - 1) : null;
                TraceExecutionPhase phase =
                        TraceReplayBootstrap.phaseForReplay(trace, previous, expected);
                if (phase == TraceExecutionPhase.VBLANK_ONLY) {
                    fixture.skipFrameFromRecording();
                } else {
                    fixture.stepFrameFromRecording();
                }
                if (!TraceReplayBootstrap.shouldCompareGameplayStateForReplay(phase)) {
                    continue;
                }
                if (i < START_FRAME) {
                    continue;
                }
                if (i == START_FRAME) {
                    int first = Integer.getInteger("trace.snapshotFirstSlot", 32);
                    int last = Integer.getInteger("trace.snapshotLastSlot", 55);
                    System.out.println("[" + LABEL + "-snapshot] frame=" + i
                            + " expected: " + summarizeExpectedSlots(trace, i, first, last));
                    System.out.println("[" + LABEL + "-snapshot] frame=" + i
                            + " engine: " + summarizeEngineSlots(objectManager, first, last));
                    int spawnMinX = Integer.getInteger("trace.spawnMinX", 0x0B80);
                    int spawnMaxX = Integer.getInteger("trace.spawnMaxX", 0x0CC0);
                    System.out.println("[" + LABEL + "-snapshot] spawns: "
                            + summarizeObjectSpawns(objectManager, spawnMinX, spawnMaxX));
                    int counterFirst = Integer.getInteger("trace.counterFirst", -1);
                    int counterLast = Integer.getInteger("trace.counterLast", -1);
                    if (counterFirst >= 0 && counterLast >= counterFirst) {
                        System.out.println("[" + LABEL + "-snapshot] objstate: "
                                + summarizeObjState(objectManager, counterFirst, counterLast));
                    }
                }
                if (WATCH_SLOT >= FIRST_DYNAMIC_SLOT
                        && (i == START_FRAME || i % WATCH_EVERY == 0 || i == STOP_FRAME)) {
                    AbstractObjectInstance watched = objectAtSlot(objectManager, WATCH_SLOT);
                    System.out.printf("[%s-watch] frame=%d slot=%d engine=%s expected=%s cam=%04X sonic=%04X,%04X%n",
                            LABEL,
                            i,
                            WATCH_SLOT,
                            watched == null
                                    ? "--"
                                    : String.format("%s@%04X,%04X{%s}",
                                    watched.getSpawn() == null
                                            ? "??"
                                            : String.format("%02X", watched.getSpawn().objectId() & 0xFF),
                                    watched.getX() & 0xFFFF,
                                    watched.getY() & 0xFFFF,
                                    watched.traceDebugDetails()),
                            summarizeExpectedSlots(trace, i, WATCH_SLOT, WATCH_SLOT),
                            fixture.camera().getX() & 0xFFFF,
                            fixture.sprite().getCentreX() & 0xFFFF,
                            fixture.sprite().getCentreY() & 0xFFFF);
                }
                if (i > STOP_FRAME) {
                    System.out.printf("[%s-obj64-count] stopped at frame %d%n", LABEL, STOP_FRAME);
                    return;
                }

                if (fixture.sprite().getRingCount() != lastEngineRingCount) {
                    System.out.printf(
                            "[%s-ring-change] frame=%d previous=%d actual=%d expected=%d "
                                    + "sonic=@%04X,%04X cam=%04X touch=%s%n",
                            LABEL,
                            i,
                            lastEngineRingCount,
                            fixture.sprite().getRingCount(),
                            expected.rings(),
                            fixture.sprite().getCentreX() & 0xFFFF,
                            fixture.sprite().getCentreY() & 0xFFFF,
                            fixture.camera().getX() & 0xFFFF,
                            summarizeTouchState(objectManager, fixture.sprite().getCentreX(),
                                    fixture.sprite().getCentreY()));
                    lastEngineRingCount = fixture.sprite().getRingCount();
                }

                if (!ringCountDivergenceReported
                        && expected.rings() >= 0
                        && fixture.sprite().getRingCount() != expected.rings()) {
                    ringCountDivergenceReported = true;
                    System.out.printf(
                            "[%s-ring-count] first divergence frame=%d expected=%d actual=%d "
                                    + "sonic=@%04X,%04X cam=%04X%n",
                            LABEL,
                            i,
                            expected.rings(),
                            fixture.sprite().getRingCount(),
                            fixture.sprite().getCentreX() & 0xFFFF,
                            fixture.sprite().getCentreY() & 0xFFFF,
                            fixture.camera().getX() & 0xFFFF);
                    System.out.println("[" + LABEL + "-ring-count] active ring objects: "
                            + summarizeLiveRingObjects(objectManager));
                    System.out.println("[" + LABEL + "-ring-count] ring spawns: "
                            + summarizeRingObjectSpawns(objectManager));
                    System.out.println("[" + LABEL + "-ring-count] touch: "
                            + summarizeTouchState(objectManager, fixture.sprite().getCentreX(),
                            fixture.sprite().getCentreY()));
                }

                if (HAS_TARGET && !targetRomAppearanceReported
                        && romTargetPresent(trace, i)) {
                    targetRomAppearanceReported = true;
                    System.out.printf(
                            "[%s-target-rom] first present frame=%d traceCam=%04X traceChunk=%04X%n",
                            LABEL,
                            i,
                            expected.cameraX() & 0xFFFF,
                            expected.cameraX() & 0xFF80);
                }

                if (HAS_TARGET && !targetEngineAppearanceReported
                        && targetSpawn != null
                        && objectManager.getActiveObjectForRewind(targetSpawn) != null) {
                    targetEngineAppearanceReported = true;
                    int[] cursor = objectManager.getPlacementCursorState();
                    System.out.printf(
                            "[%s-target-engine] first present frame=%d engineCam=%04X "
                                    + "engineChunk=%04X cursor=%s object=%s%n",
                            LABEL,
                            i,
                            fixture.camera().getX() & 0xFFFF,
                            fixture.camera().getX() & 0xFF80,
                            cursor == null ? "n/a" : String.format(
                                    "right=%d left=%d fwd=%d bwd=%d lastChunk=%04X",
                                    cursor[0], cursor[1], cursor[2], cursor[3], cursor[4] & 0xFFFF),
                            summarizeEngineTargetObj64(objectManager));
                }

                if (!HAS_TARGET && !objectSlotDivergenceReported) {
                    ObjectSlotDivergence objectSlotDivergence =
                            firstObjectSlotDivergence(trace, objectManager, i);
                    if (objectSlotDivergence != null) {
                        objectSlotDivergenceReported = true;
                        int levelMaxY = GameServices.level() != null
                                && GameServices.level().getCurrentLevel() != null
                                ? GameServices.level().getCurrentLevel().getMaxY()
                                : -1;
                        System.out.printf(
                                "[" + LABEL + "-all-slots] first divergence frame=%d slot=%d expected=%s actual=%s traceCam=%04X traceChunk=%04X engineCam=%04X engineChunk=%04X camY=%04X camMaxY=%04X camMaxYTarget=%04X camH=%04X levelMaxY=%04X%n",
                                objectSlotDivergence.frame(),
                                objectSlotDivergence.slot(),
                                objectSlotDivergence.expected(),
                                objectSlotDivergence.actual(),
                                expected.cameraX() & 0xFFFF,
                                expected.cameraX() & 0xFF80,
                                fixture.camera().getX() & 0xFFFF,
                                fixture.camera().getX() & 0xFF80,
                                fixture.camera().getY() & 0xFFFF,
                                fixture.camera().getMaxY() & 0xFFFF,
                                fixture.camera().getMaxYTarget() & 0xFFFF,
                                fixture.camera().getHeight() & 0xFFFF,
                                levelMaxY & 0xFFFF);
                        int first = Math.max(FIRST_DYNAMIC_SLOT, objectSlotDivergence.slot() - 8);
                        int last = objectSlotDivergence.slot() + 12;
                        System.out.println("[" + LABEL + "-all-slots] expected: "
                                + summarizeExpectedSlots(trace, objectSlotDivergence.frame(), first, last));
                        System.out.println("[" + LABEL + "-all-slots] engine: "
                                + summarizeEngineSlots(objectManager, first, last));
                        System.out.println("[" + LABEL + "-all-slots] slot-events: "
                                + summarizeSlotEvents(trace, objectSlotDivergence.slot(),
                                Math.max(0, objectSlotDivergence.frame() - 120),
                                objectSlotDivergence.frame() + 40));
                        System.out.println("[" + LABEL + "-all-slots] reserved: "
                                + summarizeReservedChildSlots(objectManager, first, last));
                        System.out.println("[" + LABEL + "-all-slots] allocator-only: "
                                + summarizeAllocatorOnlySlots(objectManager, first, last));
                        int[] cursor = objectManager.getPlacementCursorState();
                        System.out.println("[" + LABEL + "-all-slots] cursor: "
                                + (cursor == null ? "n/a" : String.format(
                                "right=%d left=%d fwd=%d bwd=%d lastChunk=%04X",
                                cursor[0], cursor[1], cursor[2], cursor[3], cursor[4] & 0xFFFF)));
                    }
                }

                int cmpFrame = i + OBJ64_PHASE;
                if (cmpFrame >= trace.frameCount()) {
                    continue;
                }
                if (!HAS_TARGET && !slotDivergenceReported) {
                    Obj64SlotDivergence slotDivergence =
                            firstObj64SlotDivergence(trace, objectManager, cmpFrame);
                    if (slotDivergence != null) {
                        slotDivergenceReported = true;
                        System.out.printf(
                                "[" + LABEL + "-obj64-slots] first divergence frame=%d "
                                        + "expected=%s actual=%s%n",
                                slotDivergence.frame(),
                                slotDivergence.expectedSlots(),
                                slotDivergence.actualSlots());
                        System.out.println("[" + LABEL + "-obj64-slots] expected 70-90: "
                                + summarizeExpectedSlots(trace, slotDivergence.frame(), 70, 90));
                        System.out.println("[" + LABEL + "-obj64-slots] engine 70-90: "
                                + summarizeEngineSlots(objectManager, 70, 90));
                        System.out.println("[" + LABEL + "-rings] suspects: "
                                + summarizeRingState(GameServices.level().getRingManager(),
                                List.of(
                                        new RingSpawn(0x0278, 0x0468),
                                        new RingSpawn(0x0290, 0x0468),
                                        new RingSpawn(0x02A8, 0x0468),
                                        new RingSpawn(0x0278, 0x0480),
                                        new RingSpawn(0x0290, 0x0480),
                                        new RingSpawn(0x02A8, 0x0480))));
                        System.out.println("[" + LABEL + "-ring-spawns] "
                                + summarizeRingObjectSpawns(objectManager));
                        System.out.println("[" + LABEL + "-ring-live] "
                                + summarizeLiveRingObjects(objectManager));
                    }
                }

                if (DUMP_FROM >= 0 && cmpFrame >= DUMP_FROM && cmpFrame <= DUMP_TO) {
                    System.out.printf("[%s-obj64-dump] cmpFrame=%d ROM: %s%n",
                            LABEL, cmpFrame, summarizeRomObj64(trace, cmpFrame));
                    System.out.printf("[%s-obj64-dump] cmpFrame=%d ENG: %s%n",
                            LABEL, cmpFrame, summarizeEngineObj64(objectManager));
                }

                if (!makerStateDivergenceReported) {
                    MakerStateDivergence stateDivergence =
                            firstMakerStateDivergence(trace, objectManager, cmpFrame);
                    if (stateDivergence != null) {
                        makerStateDivergenceReported = true;
                        System.out.printf(
                                "[" + LABEL + "-obj64-state] first divergence frame=%d "
                                        + "maker=@%04X,%04X field=%s expected=%s actual=%s%n",
                                stateDivergence.frame(),
                                stateDivergence.x() & 0xFFFF,
                                stateDivergence.y() & 0xFFFF,
                                stateDivergence.field(),
                                stateDivergence.expected(),
                                stateDivergence.actual());
                        System.out.println("[" + LABEL + "-obj64-state] engine Obj64: "
                                + summarizeEngineObj64(objectManager));
                        System.out.println("[" + LABEL + "-obj64-state] ROM Obj64 prev: "
                                + summarizeRomObj64(trace, stateDivergence.frame() - 1));
                        System.out.println("[" + LABEL + "-obj64-state] ROM Obj64 curr: "
                                + summarizeRomObj64(trace, stateDivergence.frame()));
                    }
                }

                if (!HAS_TARGET) {
                    ObjectOccupancyOracle.CountDivergence divergence =
                            ObjectOccupancyOracle.firstTransientCountDivergence(
                                    trace,
                                    objectManager,
                                    cmpFrame,
                                    FIRST_DYNAMIC_SLOT,
                                    Set.of(OBJ_BUBBLES),
                                    false);
                    if (divergence != null) {
                        System.out.printf(
                                "[" + LABEL + "-obj64-count] first divergence frame=%d "
                                        + "id=0x%02X romCount=%d engineCount=%d%n",
                                divergence.frame(),
                                divergence.id(),
                                divergence.romCount(),
                                divergence.engineCount());
                        System.out.println("[" + LABEL + "-obj64-count] engine Obj64: "
                                + summarizeEngineObj64(objectManager));
                        System.out.println("[" + LABEL + "-obj64-count] ROM Obj64 prev: "
                                + summarizeRomObj64(trace, divergence.frame() - 1));
                        System.out.println("[" + LABEL + "-obj64-count] ROM Obj64 curr: "
                                + summarizeRomObj64(trace, divergence.frame()));
                        return;
                    }
                }
            }

            System.out.println("[" + LABEL + "-obj64-count] no Obj64 count divergence");
        } finally {
            if (sharedLevel != null) {
                sharedLevel.dispose();
            } else {
                TestEnvironment.resetAll();
            }
        }
    }

    private record Obj64SlotDivergence(int frame, Set<Integer> expectedSlots,
                                       Set<Integer> actualSlots) {
    }

    private record ObjectSlotDivergence(int frame, int slot, String expected, String actual) {
    }

    private record MakerStateDivergence(int frame, int x, int y, String field,
                                        String expected, String actual) {
    }

    private record MakerState(int freq, int time, int prod, int type, int delay, int tableOffset) {
        String fieldValue(String field) {
            return switch (field) {
                case "freq" -> Integer.toString(freq);
                case "time" -> Integer.toString(time);
                case "prod" -> String.format("%02X", prod & 0xFF);
                case "type" -> Integer.toString(type);
                case "delay" -> Integer.toString(delay);
                case "tbl" -> String.format("%02X", tableOffset & 0xFF);
                default -> "";
            };
        }
    }

    private static MakerStateDivergence firstMakerStateDivergence(TraceData trace,
                                                                  ObjectManager objectManager,
                                                                  int frame) {
        if (!trace.metadata().hasPerFrameS1Obj64State()) {
            return null;
        }
        for (TraceEvent.S1Obj64State state : trace.s1Obj64StatesForFrame(frame)) {
            if ((state.routine() & 0xFF) != 0x0A) {
                continue;
            }
            if (HAS_TARGET
                    && (((state.x() & 0xFFFF) != (TARGET_X & 0xFFFF))
                    || ((state.y() & 0xFFFF) != (TARGET_Y & 0xFFFF)))) {
                continue;
            }
            MakerState expected = romMakerState(state);
            MakerState actual = engineMakerStateAt(objectManager, state.x(), state.y());
            if (actual == null) {
                return new MakerStateDivergence(frame, state.x(), state.y(),
                        "present", expected.toString(), "absent");
            }
            // bub_typelist (objoff_3C) is zero until the maker's first
            // .tryAgain draw writes a Bub_BblTypes pointer
            // (docs/s1disasm/_incObj/64 LZ Air Bubbles.asm:173-175). Before
            // that the "tbl" byte is a subtraction against an address the ROM
            // has not written, so it carries no comparable value.
            List<String> fields = state.objoff3c() == 0L
                    ? List.of("freq", "time", "prod", "type", "delay")
                    : List.of("freq", "time", "prod", "type", "delay", "tbl");
            for (String field : fields) {
                String want = expected.fieldValue(field);
                String got = actual.fieldValue(field);
                if (!want.equals(got)) {
                    return new MakerStateDivergence(frame, state.x(), state.y(),
                            field, want, got);
                }
            }
        }
        return null;
    }

    private static Obj64SlotDivergence firstObj64SlotDivergence(TraceData trace,
                                                                ObjectManager objectManager,
                                                                int frame) {
        Set<Integer> expected = new java.util.TreeSet<>();
        for (Map.Entry<Integer, Integer> entry : ObjectOccupancyOracle
                .expectedOccupancy(trace, frame, FIRST_DYNAMIC_SLOT)
                .entrySet()) {
            if ((entry.getValue() & 0xFF) == OBJ_BUBBLES) {
                expected.add(entry.getKey());
            }
        }

        Set<Integer> actual = new java.util.TreeSet<>();
        for (var instance : objectManager.getActiveObjects()) {
            if (instance instanceof AbstractObjectInstance object
                    && object.getSpawn() != null
                    && (object.getSpawn().objectId() & 0xFF) == OBJ_BUBBLES) {
                actual.add(object.getSlotIndex());
            }
        }

        return expected.equals(actual)
                ? null
                : new Obj64SlotDivergence(frame, expected, actual);
    }

    private static ObjectSlotDivergence firstObjectSlotDivergence(TraceData trace,
                                                                  ObjectManager objectManager,
                                                                  int frame) {
        Map<Integer, Integer> expected = new TreeMap<>(
                ObjectOccupancyOracle.expectedOccupancy(trace, frame, FIRST_DYNAMIC_SLOT));
        Map<Integer, Integer> actual = new TreeMap<>();
        for (var instance : objectManager.getActiveObjects()) {
            if (instance instanceof AbstractObjectInstance object
                    && object.getSpawn() != null
                    && object.getSlotIndex() >= FIRST_DYNAMIC_SLOT) {
                actual.put(object.getSlotIndex(), object.getSpawn().objectId() & 0xFF);
            }
        }
        int lastSlot = Math.max(
                expected.isEmpty() ? FIRST_DYNAMIC_SLOT : expected.keySet().stream().mapToInt(Integer::intValue).max().orElse(FIRST_DYNAMIC_SLOT),
                actual.isEmpty() ? FIRST_DYNAMIC_SLOT : actual.keySet().stream().mapToInt(Integer::intValue).max().orElse(FIRST_DYNAMIC_SLOT));
        for (int slot = FIRST_DYNAMIC_SLOT; slot <= lastSlot; slot++) {
            Integer want = expected.get(slot);
            Integer got = actual.get(slot);
            if (!java.util.Objects.equals(want, got)) {
                return new ObjectSlotDivergence(
                        frame,
                        slot,
                        want == null ? "--" : String.format("%02X", want & 0xFF),
                        got == null ? "--" : String.format("%02X", got & 0xFF));
            }
        }
        return null;
    }

    private static String summarizeExpectedSlots(TraceData trace, int frame,
                                                 int firstSlot, int lastSlotInclusive) {
        Map<Integer, Integer> expected = new TreeMap<>(
                ObjectOccupancyOracle.expectedOccupancy(trace, frame, FIRST_DYNAMIC_SLOT));
        Map<Integer, TraceEvent.ObjectAppeared> appearances =
                latestObjectAppearances(trace, frame, FIRST_DYNAMIC_SLOT);
        List<String> parts = new ArrayList<>();
        for (Map.Entry<Integer, Integer> entry : expected.entrySet()) {
            int slot = entry.getKey();
            if (slot >= firstSlot && slot <= lastSlotInclusive) {
                TraceEvent.ObjectAppeared appeared = appearances.get(slot);
                String details = appeared == null
                        ? ""
                        : String.format("@%04X,%04X/f%d",
                        appeared.x() & 0xFFFF,
                        appeared.y() & 0xFFFF,
                        appeared.frame());
                parts.add(String.format("s%02d=%02X%s",
                        slot, entry.getValue() & 0xFF, details));
            }
        }
        return String.join(" ", parts);
    }

    private static Map<Integer, TraceEvent.ObjectAppeared> latestObjectAppearances(
            TraceData trace, int frame, int firstSlot) {
        Map<Integer, TraceEvent.ObjectAppeared> appearances = new TreeMap<>();
        for (TraceEvent event : trace.getEventsInRange(-1, frame)) {
            if (event instanceof TraceEvent.ObjectRemoved removed) {
                if (removed.slot() >= firstSlot) {
                    appearances.remove(removed.slot());
                }
            } else if (event instanceof TraceEvent.ObjectAppeared appeared) {
                if (appeared.slot() >= firstSlot) {
                    appearances.put(appeared.slot(), appeared);
                }
            }
        }
        return appearances;
    }

    private static String summarizeSlotEvents(TraceData trace, int slot, int firstFrame, int lastFrame) {
        List<String> parts = new ArrayList<>();
        for (TraceEvent event : trace.getEventsInRange(firstFrame, lastFrame)) {
            if (event instanceof TraceEvent.ObjectAppeared appeared && appeared.slot() == slot) {
                parts.add(String.format("f%d appear %s@%04X,%04X",
                        appeared.frame(),
                        appeared.objectType(),
                        appeared.x() & 0xFFFF,
                        appeared.y() & 0xFFFF));
            } else if (event instanceof TraceEvent.ObjectRemoved removed && removed.slot() == slot) {
                parts.add(String.format("f%d remove %s",
                        removed.frame(),
                        removed.objectType()));
            }
        }
        return parts.isEmpty() ? "<none>" : String.join(" | ", parts);
    }

    private static String summarizeEngineSlots(ObjectManager objectManager,
                                               int firstSlot, int lastSlotInclusive) {
        Map<Integer, Integer> actual = new TreeMap<>();
        for (var instance : objectManager.getActiveObjects()) {
            if (instance instanceof AbstractObjectInstance object
                    && object.getSpawn() != null
                    && object.getSlotIndex() >= firstSlot
                    && object.getSlotIndex() <= lastSlotInclusive) {
                actual.put(object.getSlotIndex(), object.getSpawn().objectId() & 0xFF);
            }
        }
        List<String> parts = new ArrayList<>();
        for (int slot : actual.keySet()) {
            AbstractObjectInstance object = objectAtSlot(objectManager, slot);
            parts.add(String.format("s%02d=%02X@%04X,%04X{%s}",
                    slot,
                    actual.get(slot) & 0xFF,
                    object != null ? object.getX() & 0xFFFF : 0,
                    object != null ? object.getY() & 0xFFFF : 0,
                    object != null ? object.traceDebugDetails() : ""));
        }
        return String.join(" ", parts);
    }

    private static String summarizeObjectSpawns(ObjectManager objectManager, int minX, int maxX) {
        Set<ObjectSpawn> activeSpawns = Set.copyOf(objectManager.getActiveSpawns());
        List<String> parts = new ArrayList<>();
        for (ObjectSpawn spawn : objectManager.getAllSpawns()) {
            if (spawn.x() < minX || spawn.x() > maxX) {
                continue;
            }
            var instance = objectManager.getActiveObjectForRewind(spawn);
            int guessedCounter = guessedS1Counter(objectManager, spawn);
            parts.add(String.format(
                    "i%03d %02X@%04X,%04X raw=%04X sub=%02X tracked=%s remembered=%s dormant=%s ctr=%d guess=%d rawB7=%s b7=%s b0=%s active=%s live=%s slot=%d",
                    spawn.layoutIndex(),
                    spawn.objectId() & 0xFF,
                    spawn.x() & 0xFFFF,
                    spawn.y() & 0xFFFF,
                    spawn.rawYWord() & 0xFFFF,
                    spawn.subtype() & 0xFF,
                    spawn.respawnTracked(),
                    objectManager.isRemembered(spawn),
                    objectManager.isDormant(spawn),
                    objectManager.getSpawnCounter(spawn),
                    guessedCounter,
                    guessedCounter >= 0 && rawObjStateBit(objectManager, guessedCounter, 7),
                    objectManager.isSpawnStateBitSet(spawn, 7),
                    objectManager.isSpawnStateBitSet(spawn, 0),
                    activeSpawns.contains(spawn),
                    instance != null,
                    instance instanceof AbstractObjectInstance object ? object.getSlotIndex() : -1));
        }
        return parts.isEmpty() ? "<none>" : String.join(" | ", parts);
    }

    private static int guessedS1Counter(ObjectManager objectManager, ObjectSpawn target) {
        int counter = 1;
        for (ObjectSpawn spawn : objectManager.getAllSpawns()) {
            if (spawn == target) {
                return spawn.respawnTracked() ? counter & 0xFF : -1;
            }
            if (spawn.respawnTracked()) {
                counter = (counter + 1) & 0xFF;
            }
        }
        return -1;
    }

    private static boolean rawObjStateBit(ObjectManager objectManager, int counter, int bit) {
        try {
            java.lang.reflect.Field placementField = ObjectManager.class.getDeclaredField("placement");
            placementField.setAccessible(true);
            Object placement = placementField.get(objectManager);
            java.lang.reflect.Field stateField = placement.getClass().getDeclaredField("objState");
            stateField.setAccessible(true);
            int[] state = (int[]) stateField.get(placement);
            return (state[counter & 0xFF] & (1 << bit)) != 0;
        } catch (ReflectiveOperationException e) {
            return false;
        }
    }

    private static String summarizeObjState(ObjectManager objectManager, int first, int last) {
        try {
            java.lang.reflect.Field placementField = ObjectManager.class.getDeclaredField("placement");
            placementField.setAccessible(true);
            Object placement = placementField.get(objectManager);
            java.lang.reflect.Field stateField = placement.getClass().getDeclaredField("objState");
            stateField.setAccessible(true);
            int[] state = (int[]) stateField.get(placement);
            List<String> parts = new ArrayList<>();
            for (int counter = first; counter <= last; counter++) {
                parts.add(String.format("%02X=%02X", counter & 0xFF, state[counter & 0xFF] & 0xFF));
            }
            return String.join(" ", parts);
        } catch (ReflectiveOperationException e) {
            return "<unavailable>";
        }
    }

    @SuppressWarnings("unchecked")
    private static String summarizeReservedChildSlots(ObjectManager objectManager,
                                                     int firstSlot,
                                                     int lastSlotInclusive) {
        try {
            java.lang.reflect.Field field = ObjectManager.class.getDeclaredField("reservedChildSlots");
            field.setAccessible(true);
            Map<ObjectSpawn, int[]> reserved = (Map<ObjectSpawn, int[]>) field.get(objectManager);
            List<String> parts = new ArrayList<>();
            for (Map.Entry<ObjectSpawn, int[]> entry : reserved.entrySet()) {
                ObjectSpawn spawn = entry.getKey();
                int[] slots = entry.getValue();
                if (spawn == null || slots == null) {
                    continue;
                }
                boolean inRange = false;
                for (int slot : slots) {
                    if (slot >= firstSlot && slot <= lastSlotInclusive) {
                        inRange = true;
                        break;
                    }
                }
                if (inRange) {
                    parts.add(String.format("%02X@%04X,%04X:%s",
                            spawn.objectId() & 0xFF,
                            spawn.x() & 0xFFFF,
                            spawn.y() & 0xFFFF,
                            java.util.Arrays.toString(slots)));
                }
            }
            return parts.isEmpty() ? "<none>" : String.join(" | ", parts);
        } catch (ReflectiveOperationException e) {
            return "<error " + e.getClass().getSimpleName() + ">";
        }
    }

    private static String summarizeAllocatorOnlySlots(ObjectManager objectManager,
                                                     int firstSlot,
                                                     int lastSlotInclusive) {
        List<String> parts = new ArrayList<>();
        for (int slot = firstSlot; slot <= lastSlotInclusive; slot++) {
            if (isDynamicSlotEmpty(objectManager, slot) || objectAtSlot(objectManager, slot) != null) {
                continue;
            }
            parts.add("s" + slot);
        }
        return parts.isEmpty() ? "<none>" : String.join(" ", parts);
    }

    private static boolean isDynamicSlotEmpty(ObjectManager objectManager, int slot) {
        try {
            java.lang.reflect.Field field = ObjectManager.class.getDeclaredField("slotAllocator");
            field.setAccessible(true);
            Object allocator = field.get(objectManager);
            java.lang.reflect.Method method = allocator.getClass().getDeclaredMethod("isEmpty", int.class);
            method.setAccessible(true);
            return (boolean) method.invoke(allocator, slot);
        } catch (ReflectiveOperationException e) {
            return false;
        }
    }

    private static String summarizeRingState(RingManager ringManager, List<RingSpawn> rings) {
        if (ringManager == null) {
            return "ringManager=null";
        }
        List<String> parts = new ArrayList<>();
        for (RingSpawn ring : rings) {
            parts.add(String.format("@%04X,%04X collected=%s sparkle=%d",
                    ring.x() & 0xFFFF,
                    ring.y() & 0xFFFF,
                    ringManager.isCollected(ring),
                    ringManager.getSparkleStartFrame(ring)));
        }
        return String.join(" ", parts);
    }

    private static String summarizeRingObjectSpawns(ObjectManager objectManager) {
        Set<ObjectSpawn> activeSpawns = Set.copyOf(objectManager.getActiveSpawns());
        List<String> parts = new ArrayList<>();
        for (ObjectSpawn spawn : objectManager.getAllSpawns()) {
            if ((spawn.objectId() & 0xFF) != 0x25) {
                continue;
            }
            var instance = objectManager.getActiveObjectForRewind(spawn);
            int slot = instance instanceof AbstractObjectInstance object ? object.getSlotIndex() : -1;
            parts.add(String.format("@%04X,%04X sub=%02X active=%s inst=%s slot=%d dorm=%s rem=%s ctr=%d",
                    spawn.x() & 0xFFFF,
                    spawn.y() & 0xFFFF,
                    spawn.subtype() & 0xFF,
                    activeSpawns.contains(spawn),
                    instance != null,
                    slot,
                    objectManager.isDormant(spawn),
                    objectManager.isRemembered(spawn),
                    objectManager.getSpawnCounter(spawn)));
        }
        return String.join(" | ", parts);
    }

    private static String summarizeLiveRingObjects(ObjectManager objectManager) {
        List<String> parts = new ArrayList<>();
        for (var instance : objectManager.getActiveObjects()) {
            if (!(instance instanceof AbstractObjectInstance object)
                    || object.getSpawn() == null
                    || (object.getSpawn().objectId() & 0xFF) != 0x25) {
                continue;
            }
            parts.add(String.format("s%02d @%04X,%04X spawn=@%04X,%04X %s",
                    object.getSlotIndex(),
                    object.getX() & 0xFFFF,
                    object.getY() & 0xFFFF,
                    object.getSpawn().x() & 0xFFFF,
                    object.getSpawn().y() & 0xFFFF,
                    object.traceDebugDetails()));
        }
        return String.join(" | ", parts);
    }

    private static String summarizeTouchState(ObjectManager objectManager, int centreX, int centreY) {
        TouchResponseDebugState touchState = objectManager.getTouchResponseDebugState();
        if (touchState == null) {
            return "<no touch state>";
        }
        String box = String.format("box=@%04X,%04X h=%d yr=%d crouch=%s",
                touchState.getPlayerX() & 0xFFFF,
                touchState.getPlayerY() & 0xFFFF,
                touchState.getPlayerHeight(),
                touchState.getPlayerYRadius(),
                touchState.isCrouching());
        String overlaps = TouchResponseDebugHitFormatter.summariseOverlaps(touchState.getHits());
        String scans = TouchResponseDebugHitFormatter.summariseNearbyScans(
                touchState.getHits(), centreX, centreY);
        return box + " overlaps=[" + overlaps + "] scans=[" + scans + "]";
    }

    private static AbstractObjectInstance objectAtSlot(ObjectManager objectManager, int slot) {
        for (var instance : objectManager.getActiveObjects()) {
            if (instance instanceof AbstractObjectInstance object && object.getSlotIndex() == slot) {
                return object;
            }
        }
        return null;
    }

    private static ObjectSpawn findTargetSpawn(ObjectManager objectManager) {
        for (ObjectSpawn spawn : objectManager.getAllSpawns()) {
            if ((spawn.objectId() & 0xFF) == OBJ_BUBBLES
                    && (spawn.x() & 0xFFFF) == (TARGET_X & 0xFFFF)
                    && (spawn.y() & 0xFFFF) == (TARGET_Y & 0xFFFF)) {
                return spawn;
            }
        }
        return null;
    }

    private static boolean romTargetPresent(TraceData trace, int frame) {
        if (!trace.metadata().hasPerFrameS1Obj64State()) {
            return false;
        }
        for (TraceEvent.S1Obj64State state : trace.s1Obj64StatesForFrame(frame)) {
            if ((state.x() & 0xFFFF) == (TARGET_X & 0xFFFF)
                    && (state.y() & 0xFFFF) == (TARGET_Y & 0xFFFF)) {
                return true;
            }
        }
        return false;
    }

    private static String summarizeEngineTargetObj64(ObjectManager objectManager) {
        List<String> parts = new ArrayList<>();
        for (var instance : objectManager.getActiveObjects()) {
            if (!(instance instanceof AbstractObjectInstance object)
                    || object.getSpawn() == null
                    || (object.getSpawn().objectId() & 0xFF) != OBJ_BUBBLES
                    || (object.getSpawn().x() & 0xFFFF) != (TARGET_X & 0xFFFF)
                    || (object.getSpawn().y() & 0xFFFF) != (TARGET_Y & 0xFFFF)) {
                continue;
            }
            String details = object.traceDebugDetails();
            parts.add(String.format(
                    "s%02d @%04X,%04X %s",
                    object.getSlotIndex(),
                    object.getX() & 0xFFFF,
                    object.getY() & 0xFFFF,
                    details == null || details.isBlank() ? "" : details));
        }
        return String.join(" | ", parts);
    }

    private static MakerState romMakerState(TraceEvent.S1Obj64State state) {
        int obj34Byte = (state.objoff34() >>> 8) & 0xFF;
        int type = obj34Byte >= 0x80 ? obj34Byte - 0x100 : obj34Byte;
        int prodWord = state.objoff36() & 0xFFFF;
        int prod = ((prodWord >>> 8) & 0xC0) | (prodWord & 0x3F);
        int tableOffset = (int) ((state.objoff3c() & 0xFFFFFFFFL) - 0x00013020L);
        return new MakerState(
                state.objoff33() & 0xFF,
                state.objoff32() & 0xFF,
                prod,
                type,
                state.objoff38() & 0xFFFF,
                tableOffset);
    }

    private static MakerState engineMakerStateAt(ObjectManager objectManager, int x, int y) {
        for (var instance : objectManager.getActiveObjects()) {
            if (!(instance instanceof AbstractObjectInstance object)
                    || object.getSpawn() == null
                    || (object.getSpawn().objectId() & 0xFF) != OBJ_BUBBLES
                    || (object.getSpawn().x() & 0xFFFF) != (x & 0xFFFF)
                    || (object.getSpawn().y() & 0xFFFF) != (y & 0xFFFF)) {
                continue;
            }
            String details = object.traceDebugDetails();
            if (details == null || !details.startsWith("r=0A ")) {
                continue;
            }
            return new MakerState(
                    parseDetailInt(details, "freq", false),
                    parseDetailInt(details, "time", false),
                    parseDetailInt(details, "prod", true),
                    parseDetailInt(details, "type", false),
                    parseDetailInt(details, "delay", false),
                    parseDetailInt(details, "tbl", true));
        }
        return null;
    }

    private static int parseDetailInt(String details, String key, boolean hex) {
        String prefix = key + "=";
        for (String token : details.split(" ")) {
            if (token.startsWith(prefix)) {
                String value = token.substring(prefix.length());
                return Integer.parseInt(value, hex ? 16 : 10);
            }
        }
        throw new IllegalArgumentException("Missing " + key + " in " + details);
    }

    private static int parseOptionalIntProperty(String key, int defaultValue) {
        String value = System.getProperty(key);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        return Integer.decode(value);
    }

    private static Path resolveBk2File(Path traceDir, TraceMetadata meta) throws Exception {
        if (meta.sourceBk2() != null && !meta.sourceBk2().isBlank()) {
            Path shared = traceDir.getParent().resolve("_movies").resolve(meta.sourceBk2());
            if (Files.exists(shared)) {
                return shared;
            }
        }
        try (var files = Files.list(traceDir)) {
            return files
                    .filter(p -> p.toString().endsWith(".bk2"))
                    .findFirst()
                    .orElse(null);
        }
    }

    private static String summarizeEngineObj64(ObjectManager objectManager) {
        List<String> parts = new ArrayList<>();
        for (var instance : objectManager.getActiveObjects()) {
            if (!(instance instanceof AbstractObjectInstance object)
                    || object.getSpawn() == null
                    || (object.getSpawn().objectId() & 0xFF) != OBJ_BUBBLES) {
                continue;
            }
            String details = object.traceDebugDetails();
            parts.add(String.format(
                    "s%02d @%04X,%04X spawn=@%04X,%04X%s",
                    object.getSlotIndex(),
                    object.getX() & 0xFFFF,
                    object.getY() & 0xFFFF,
                    object.getSpawn().x() & 0xFFFF,
                    object.getSpawn().y() & 0xFFFF,
                    details == null || details.isBlank() ? "" : " " + details));
        }
        return String.join(" | ", parts);
    }

    private static String summarizeRomObj64(TraceData trace, int frame) {
        if (frame < 0 || !trace.metadata().hasPerFrameS1Obj64State()) {
            return "";
        }
        List<String> parts = new ArrayList<>();
        for (TraceEvent.S1Obj64State state : trace.s1Obj64StatesForFrame(frame)) {
            parts.add(String.format(
                    "f%d s%02d @%04X,%04X r=%02X status=%02X render=%02X "
                            + "sub=%02X anim=%02X obj32=%02X obj33=%02X "
                            + "obj34=%04X obj36=%04X obj38=%04X obj3c=%08X",
                    frame,
                    state.slot(),
                    state.x() & 0xFFFF,
                    state.y() & 0xFFFF,
                    state.routine() & 0xFF,
                    state.status() & 0xFF,
                    state.renderFlags() & 0xFF,
                    state.subtype() & 0xFF,
                    state.anim() & 0xFF,
                    state.objoff32() & 0xFF,
                    state.objoff33() & 0xFF,
                    state.objoff34() & 0xFFFF,
                    state.objoff36() & 0xFFFF,
                    state.objoff38() & 0xFFFF,
                    state.objoff3c() & 0xFFFFFFFFL));
        }
        return String.join(" | ", parts);
    }
}
