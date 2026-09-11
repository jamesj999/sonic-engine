package com.openggf.tools.audio.completerun;

import static com.openggf.tools.audio.completerun.CompleteRunAudioTrace.*;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class TestCompleteRunAudioTrace {
    private static final Map<DriverService, ServiceEvidence> SERVICE_EVIDENCE = new IdentityHashMap<>();
    private static final String ALL_COMPARED_LAYER_JSON = "\"comparisonLayerInventory\":[{\"layer\":\"ROW_LAG\",\"status\":\"COMPARED\",\"reason\":null},{\"layer\":\"REQUESTS\",\"status\":\"COMPARED\",\"reason\":null},{\"layer\":\"DECISIONS\",\"status\":\"COMPARED\",\"reason\":null},{\"layer\":\"SERVICES\",\"status\":\"COMPARED\",\"reason\":null},{\"layer\":\"STATE\",\"status\":\"COMPARED\",\"reason\":null},{\"layer\":\"OWNERSHIP\",\"status\":\"COMPARED\",\"reason\":null},{\"layer\":\"LIFECYCLE\",\"status\":\"COMPARED\",\"reason\":null},{\"layer\":\"FRAME_CHIP_EVENTS\",\"status\":\"COMPARED\",\"reason\":null},{\"layer\":\"BOUNDARY_CHIP_STATE\",\"status\":\"COMPARED\",\"reason\":null},{\"layer\":\"CUTOFF_FRONTIER\",\"status\":\"COMPARED\",\"reason\":null}],\"producerObservationInventory\":[{\"layer\":\"ROW_LAG\",\"status\":\"OBSERVED\",\"reason\":null},{\"layer\":\"REQUESTS\",\"status\":\"OBSERVED\",\"reason\":null},{\"layer\":\"DECISIONS\",\"status\":\"OBSERVED\",\"reason\":null},{\"layer\":\"SERVICES\",\"status\":\"OBSERVED\",\"reason\":null},{\"layer\":\"STATE\",\"status\":\"OBSERVED\",\"reason\":null},{\"layer\":\"OWNERSHIP\",\"status\":\"OBSERVED\",\"reason\":null},{\"layer\":\"LIFECYCLE\",\"status\":\"OBSERVED\",\"reason\":null},{\"layer\":\"FRAME_CHIP_EVENTS\",\"status\":\"OBSERVED\",\"reason\":null},{\"layer\":\"BOUNDARY_CHIP_STATE\",\"status\":\"OBSERVED\",\"reason\":null},{\"layer\":\"CUTOFF_FRONTIER\",\"status\":\"OBSERVED\",\"reason\":null}]";
    @Test
    void nativeDeferredServiceBeginRetainsExactImmutableManagedEvidence() {
        NativeDeferredServiceBegin pending = new NativeDeferredServiceBegin(
                13, 0, 6, 0, 4, 77, 2, 0x71b4c,
                40, 41, 12, 13, 2, false, 0, 0);
        FrameNativeDiagnostics diagnostics = new FrameNativeDiagnostics(
                List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(pending), List.of());

        assertEquals(List.of(pending), diagnostics.deferredServiceBegins());
        assertEquals(13, pending.blockerToken());
        assertEquals(4, pending.targetKind());
        assertEquals(41, pending.latestCoordinate());
        assertEquals(2, pending.observationCount());
        assertThrows(UnsupportedOperationException.class,
                () -> diagnostics.deferredServiceBegins().clear());

        Frame withDiagnostic = fullFrame(3, "deferred", false, List.of(), List.of(), List.of(), diagnostics);
        Frame withoutDiagnostic = fullFrame(3, "deferred", false, List.of(), List.of(), List.of(),
                new FrameNativeDiagnostics(List.of(), List.of(), List.of()));
        NativeDeferredServiceBegin changed = new NativeDeferredServiceBegin(
                13, 0, 6, 0, 4, 77, 2, 0x71b4c,
                40, 42, 12, 14, 2, false, 0, 0);
        Frame changedRaw = fullFrame(3, "deferred", false, List.of(), List.of(), List.of(),
                new FrameNativeDiagnostics(List.of(), List.of(), List.of(), List.of(), List.of(),
                        List.of(changed), List.of()));
        assertNotEquals(assertDoesNotThrow(() -> CompleteRunAudioJson.writeRecord(withDiagnostic)),
                assertDoesNotThrow(() -> CompleteRunAudioJson.writeRecord(changedRaw)));
        assertEquals(assertDoesNotThrow(() -> CompleteRunAudioJson.writeSemanticRecord(withDiagnostic)),
                assertDoesNotThrow(() -> CompleteRunAudioJson.writeSemanticRecord(withoutDiagnostic)));
        assertEquals(withDiagnostic, CompleteRunAudioJson.readRecord(assertDoesNotThrow(
                () -> CompleteRunAudioJson.writeRecord(withDiagnostic))));
        assertThrows(IllegalArgumentException.class, () -> new FrameNativeDiagnostics(
                List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(pending, changed), List.of()));
        NativeManagedCorrelation deferredMarker = new NativeManagedCorrelation(0, List.of(
                new NativeManagedEvent(40, 12, "M68K", 0x71b4c,
                        10, 4, 13, 0, 6, 0, 77, true)));
        assertThrows(IllegalArgumentException.class, () -> new FrameNativeDiagnostics(
                List.of(), List.of(), List.of(), List.of(),
                List.of(deferredMarker, deferredMarker), List.of(pending), List.of()));
        assertThrows(IllegalArgumentException.class, () -> new NativeDeferredServiceBegin(
                13, 0, 6, 0, 4, 77, 2, 0x71b4c,
                40, 41, 12, 13, 1, false, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new NativeDeferredServiceBegin(
                13, 0, 6, 0, 4, 77, 2, 0x71b4c,
                40, 41, 12, 13, 2, true, 13, 42));

        NativeDeferredServiceBegin consumed = new NativeDeferredServiceBegin(
                13, 0, 6, 0, 4, 77, 2, 0x71b4c,
                40, 41, 12, 13, 2, true, 14, 42);
        ArrayList<NativeDeferredServiceBegin> callerDeferred = new ArrayList<>(List.of(consumed));
        Frame consumedFrame = fullFrame(3, "deferred", false, List.of(), List.of(), List.of(),
                new FrameNativeDiagnostics(List.of(), List.of(), List.of(), List.of(), List.of(),
                        callerDeferred, List.of()));
        String consumedJson = assertDoesNotThrow(() -> CompleteRunAudioJson.writeRecord(consumedFrame));
        callerDeferred.clear();
        assertTrue(consumedJson.contains("\"consumedToken\":14"));
        assertTrue(consumedJson.contains("\"consumeCoordinate\":42"));
        assertEquals(consumedFrame, CompleteRunAudioJson.readRecord(consumedJson));
        assertThrows(IllegalArgumentException.class, () -> CompleteRunAudioJson.readRecord(
                consumedJson.replace("\"consumedToken\"", "\"releasedToken\"")));
        assertThrows(IllegalArgumentException.class, () -> CompleteRunAudioJson.readRecord(
                consumedJson.replace("\"consumeCoordinate\"", "\"releaseCoordinate\"")));
        assertEquals(14, consumed.consumedToken());
        assertEquals(42, consumed.consumeCoordinate());
        assertEquals(List.of(consumed), consumedFrame.nativeDiagnostics().deferredServiceBegins());
    }

    @Test
    void nativeDeferredConsumeCannotForgeChildAncestryOrReuseRawSlots() {
        NativeManagedCorrelation marker = new NativeManagedCorrelation(0, List.of(
                new NativeManagedEvent(40, 1, "M68K", 0x71b4c,
                        10, 4, 13, 0, 6, 0, 77, true)));
        NativeManagedEvent begin = new NativeManagedEvent(41, 2, "M68K", 0x71b82,
                1, 0, 14, 13, 4, 1, 78, true);
        NativeManagedCorrelation consume = new NativeManagedCorrelation(1, List.of(begin));
        NativeManagedCorrelation end = new NativeManagedCorrelation(2, List.of(
                new NativeManagedEvent(43, 4, "M68K", 0x71c4c,
                        2, 0, 14, 13, 4, 1, 79, true)));
        NativeDeferredServiceBegin consumed = new NativeDeferredServiceBegin(
                13, 0, 6, 0, 4, 77, 2, 0x71b4c,
                40, 40, 1, 1, 1, true, 14, 41);
        FrontierService blocker = new FrontierService(13, 0, 0, "blocker",
                FrontierServiceState.COMPLETED, 3, 0, 0x3a, 10, "Z80",
                3, 5L, 0x77, 11, List.of(), List.of());
        FrontierService child = new FrontierService(14, 13, 1, "child",
                FrontierServiceState.COMPLETED, 3, 2, 0x71b82, 78, "M68K",
                3, 4L, 0x71c4c, 79, List.of(), List.of());

        assertDoesNotThrow(() -> new FrameNativeDiagnostics(List.of(blocker, child),
                List.of(), List.of(), List.of(), List.of(marker, consume, end),
                List.of(consumed), List.of()));

        NativeManagedCorrelation wrongParent = new NativeManagedCorrelation(1, List.of(
                new NativeManagedEvent(41, 2, "M68K", 0x71b82,
                        1, 0, 14, 0, 4, 0, 78, true)));
        assertThrows(IllegalArgumentException.class, () -> new FrameNativeDiagnostics(
                List.of(blocker, child), List.of(), List.of(), List.of(),
                List.of(marker, wrongParent, end), List.of(consumed), List.of()));

        NativeManagedCorrelation duplicateConsume = new NativeManagedCorrelation(2, List.of(
                new NativeManagedEvent(42, 3, "M68K", 0x71b82,
                        1, 0, 14, 13, 4, 1, 78, true)));
        NativeManagedCorrelation shiftedEnd = new NativeManagedCorrelation(3, end.events());
        assertThrows(IllegalArgumentException.class, () -> new FrameNativeDiagnostics(
                List.of(blocker, child), List.of(), List.of(), List.of(),
                List.of(marker, consume, duplicateConsume, shiftedEnd),
                List.of(consumed), List.of()));

        FrontierChipEvent collision = new FrontierChipEvent(41, 2, "M68K", 0x71b84,
                4, 0, 0x90, true, null, null);
        FrontierService childWithCollision = new FrontierService(14, 13, 1, "child",
                FrontierServiceState.COMPLETED, 3, 2, 0x71b82, 78, "M68K",
                3, 4L, 0x71c4c, 79, List.of(), List.of(collision));
        assertThrows(IllegalArgumentException.class, () -> new FrameNativeDiagnostics(
                List.of(blocker, childWithCollision),
                List.of(new FrontierOwnedChip(14, collision)), List.of(), List.of(),
                List.of(marker, consume, end), List.of(consumed), List.of()));
    }

    @Test
    void frameRawChipInventoryPreservesNestedGlobalOrderWithoutDuplicatingOwnership() {
        NormalizedState state = new NormalizedState(List.of(), List.of());
        DriverService parent = testService(0, "parent", ServiceCompletion.COMPLETED,
                null, null, null, ServiceAncestry.root());
        DriverService child = testService(1, "child", ServiceCompletion.COMPLETED,
                null, null, null, ServiceAncestry.root());
        Frame frame = fullFrame(1, "nested", false, List.of(), List.of(), List.of(parent, child), state,
                List.of(new PsgWrite(0, 0x90), new PsgWrite(1, 0x91), new PsgWrite(2, 0x92)), null);

        assertEquals(List.of(new PsgWrite(0, 0x90), new PsgWrite(1, 0x91), new PsgWrite(2, 0x92)),
                frame.chipEvents());
        assertEquals(frame, CompleteRunAudioJson.readRecord(assertDoesNotThrow(
                () -> CompleteRunAudioJson.writeRecord(frame))));
        assertThrows(IllegalArgumentException.class, () -> fullFrame(1, "nested", false, List.of(), List.of(),
                List.of(parent, child), state,
                List.of(new PsgWrite(0, 0x90), new PsgWrite(2, 0x92), new PsgWrite(1, 0x91)), null));
    }

    @Test
    void nativeFrameDiagnosticsRetainTokenTreePcSourceAndExclusiveRawOrder() {
        NormalizedState state = new NormalizedState(List.of(), List.of());
        DriverService parent = testService(0, "parent", ServiceCompletion.COMPLETED,
                null, null, null, ServiceAncestry.root());
        DriverService child = testService(1, "child", ServiceCompletion.COMPLETED, null,
                null, null,
                new ServiceAncestry(new ServiceCoordinate(1, 0), 1,
                        new ServiceCoordinate(1, 0), 1, List.of()));
        FrontierChipEvent first = new FrontierChipEvent(1, 1, "Z80", 0x100, 4, 0, 0x90,
                true, null, null);
        FrontierChipEvent nested = new FrontierChipEvent(2, 2, "Z80", 0x200, 4, 0, 0x91,
                true, null, null);
        FrontierChipEvent resumed = new FrontierChipEvent(3, 3, "Z80", 0x101, 4, 0, 0x92,
                true, null, null);
        FrontierService rawParent = new FrontierService(1, 0, 0, "parent", FrontierServiceState.COMPLETED,
                1, 1, 0x80, 10, "Z80", 3, 8L, 0x90, 11, List.of(), List.of(first, resumed));
        FrontierService rawChild = new FrontierService(2, 1, 1, "child", FrontierServiceState.COMPLETED,
                2, 2, 0x180, 12, "Z80", 2, 7L, 0x190, 13, List.of(), List.of(nested));
        FrameNativeDiagnostics diagnostics = new FrameNativeDiagnostics(List.of(rawParent, rawChild),
                List.of(new FrontierOwnedChip(1, 0, first), new FrontierOwnedChip(2, 1, nested),
                        new FrontierOwnedChip(1, 0, resumed)), List.of());
        Frame frame = fullFrame(3, "nested", false, List.of(), List.of(parent, child),
                List.of(new PsgWrite(0, 0x90), new PsgWrite(1, 0x91), new PsgWrite(2, 0x92)), diagnostics);

        assertEquals(frame, CompleteRunAudioJson.readRecord(assertDoesNotThrow(
                () -> CompleteRunAudioJson.writeRecord(frame))));
        assertThrows(IllegalArgumentException.class, () -> new FrameNativeDiagnostics(
                List.of(rawChild, rawParent), diagnostics.rawChipInventory(), List.of()));
        FrontierChipEvent duplicateCoordinate = new FrontierChipEvent(1, 4, "Z80", 0x102,
                4, 0, 0x93, true, null, null);
        FrontierService duplicateOwner = new FrontierService(3, 0, 0, "duplicate",
                FrontierServiceState.COMPLETED, 3, 9, 0x82, 14, "Z80", 3, 10L,
                0x92, 15, List.of(), List.of(duplicateCoordinate));
        assertThrows(IllegalArgumentException.class, () -> new FrameNativeDiagnostics(
                List.of(rawParent, rawChild, duplicateOwner),
                List.of(new FrontierOwnedChip(1, 0, first),
                        new FrontierOwnedChip(3, 2, duplicateCoordinate),
                        new FrontierOwnedChip(2, 1, nested),
                        new FrontierOwnedChip(1, 0, resumed)), List.of()));
    }

    @Test
    void nativeManagedCorrelationsRetainAllBoundariesInRawOrderOnly() {
        NativeManagedEvent begin = new NativeManagedEvent(
                40, 7, "M68K", 0x71b4c, 1, 0, 9, 0, 4, 0, 100, true);
        NativeManagedEvent observed = new NativeManagedEvent(
                41, 11, "M68K", 0x138e, 10, 3, 9, 0, 4, 0, 101, true);
        NativeManagedEvent conditionalMarker = new NativeManagedEvent(
                42, 13, "M68K", 0x72c24, 10, 1, 9, 0, 4, 0, 102, false);
        NativeManagedEvent conditionalEnd = new NativeManagedEvent(
                43, 17, "M68K", 0x72c24, 2, 0, 9, 0, 4, 0, 102, true);
        NativeManagedCorrelation beginCorrelation = new NativeManagedCorrelation(0, List.of(begin));
        NativeManagedCorrelation observationCorrelation = new NativeManagedCorrelation(1, List.of(observed));
        NativeManagedCorrelation conditionalCorrelation = new NativeManagedCorrelation(
                2, List.of(conditionalMarker, conditionalEnd));
        FrameNativeDiagnostics diagnostics = new FrameNativeDiagnostics(
                List.of(), List.of(), List.of(), List.of(),
                List.of(beginCorrelation, observationCorrelation, conditionalCorrelation));
        Frame withDiagnostics = fullFrame(3, "observed", false, List.of(), List.of(),
                List.of(), diagnostics);
        Frame withoutDiagnostics = fullFrame(3, "observed", false, List.of(), List.of(),
                List.of(), new FrameNativeDiagnostics(List.of(), List.of(), List.of(), List.of()));

        assertEquals(withDiagnostics, CompleteRunAudioJson.readRecord(assertDoesNotThrow(
                () -> CompleteRunAudioJson.writeRecord(withDiagnostics))));
        assertNotEquals(assertDoesNotThrow(() -> CompleteRunAudioJson.writeRecord(withDiagnostics)),
                assertDoesNotThrow(() -> CompleteRunAudioJson.writeRecord(withoutDiagnostics)));
        assertEquals(assertDoesNotThrow(() -> CompleteRunAudioJson.writeSemanticRecord(withDiagnostics)),
                assertDoesNotThrow(() -> CompleteRunAudioJson.writeSemanticRecord(withoutDiagnostics)));

        FrameNativeDiagnostics duplicateCallbackRecords = new FrameNativeDiagnostics(
                List.of(), List.of(), List.of(), List.of(),
                List.of(beginCorrelation, beginCorrelation, observationCorrelation,
                        conditionalCorrelation, conditionalCorrelation));
        assertEquals(List.of(beginCorrelation, observationCorrelation, conditionalCorrelation),
                duplicateCallbackRecords.managedCorrelations());

        assertThrows(IllegalArgumentException.class, () -> new FrameNativeDiagnostics(
                List.of(), List.of(), List.of(), List.of(),
                List.of(observationCorrelation, beginCorrelation)));
        assertThrows(IllegalArgumentException.class, () -> new FrameNativeDiagnostics(
                List.of(), List.of(), List.of(), List.of(),
                List.of(beginCorrelation, new NativeManagedCorrelation(0, List.of(observed)))));
        assertThrows(IllegalArgumentException.class, () -> new FrameNativeDiagnostics(
                List.of(), List.of(), List.of(), List.of(),
                List.of(beginCorrelation, new NativeManagedCorrelation(2, List.of(observed)))));
        assertThrows(IllegalArgumentException.class, () -> new FrameNativeDiagnostics(
                List.of(), List.of(), List.of(), List.of(),
                List.of(beginCorrelation, observationCorrelation, beginCorrelation)));
        assertThrows(IllegalArgumentException.class, () -> new FrameNativeDiagnostics(
                List.of(), List.of(), List.of(), List.of(),
                List.of(beginCorrelation, new NativeManagedCorrelation(0, List.of(observed)))));
        assertThrows(IllegalArgumentException.class, () -> new NativeManagedCorrelation(0,
                List.of(conditionalMarker)));
        assertThrows(IllegalArgumentException.class, () -> new NativeManagedCorrelation(0,
                List.of(conditionalMarker, new NativeManagedEvent(
                        43, 17, "M68K", 0x138e, 10, 3, 9, 0, 4, 0, 101, true))));
        assertThrows(IllegalArgumentException.class, () -> new NativeManagedCorrelation(0,
                List.of(new NativeManagedEvent(42, 13, "M68K", 0x72c24,
                                10, 1, 9, 0, 4, 0, 102, true), conditionalEnd)));
    }

    @Test
    void nativeAncestryTransitionsRetainPromotionWithoutChangingSemanticBytes() {
        ServiceCoordinate parentBegin = new ServiceCoordinate(5, 0);
        ServiceAncestryTransition semanticPromotion = new ServiceAncestryTransition(
                parentBegin, 1, null, 0, 5, 3);
        ServiceAncestry childAncestry = new ServiceAncestry(
                parentBegin, 1, null, 0, List.of(semanticPromotion));
        NormalizedState state = new NormalizedState(List.of(), List.of());
        DriverService semanticParent = testService(0, "dpcm", ServiceCompletion.COMPLETED,
                null, null, null, ServiceAncestry.root());
        DriverService semanticChild = testService(1, "update", ServiceCompletion.COMPLETED,
                null,
                new ServiceCoordinate(5, 0), new ServiceCoordinate(6, 0), childAncestry);
        FrontierService parent = new FrontierService(1, 0, 0, "dpcm",
                FrontierServiceState.COMPLETED, 5, 0, 0x77, 1, "Z80",
                5, 5L, 0xac, 4, List.of(), List.of());
        NativeAncestryTransition rawPromotion = new NativeAncestryTransition(
                6, 5, 6, 1, 1, 0, 0, 4, "Z80", 0xac);
        FrontierService child = new FrontierService(2, 1, 1, "update",
                FrontierServiceState.COMPLETED, 5, 1, 0x71b4c, 2, "M68K",
                6, 2L, 0x71c4c, 3, List.of(), List.of(), 0, 0,
                List.of(rawPromotion), childAncestry);
        FrameNativeDiagnostics parentDiagnostics = new FrameNativeDiagnostics(
                List.of(parent), List.of(), List.of(), List.of(), List.of(),
                List.of(new FrontierOwnedAncestryTransition(2, rawPromotion)));
        FrameNativeDiagnostics childDiagnostics = new FrameNativeDiagnostics(
                List.of(child), List.of(), List.of(), List.of(), List.of(), List.of());
        Frame rawParent = fullFrame(5, "crossing", false, List.of(), List.of(),
                List.of(semanticParent), state, List.of(), parentDiagnostics);
        Frame raw = fullFrame(6, "crossing", false, List.of(), List.of(),
                List.of(semanticChild), state, List.of(), childDiagnostics);
        Frame without = fullFrame(6, "crossing", false, List.of(), List.of(),
                List.of(semanticChild), state, List.of(), null);
        DriverService forgedLifetime = testService(1, "update", ServiceCompletion.COMPLETED,
                null,
                new ServiceCoordinate(5, 1), new ServiceCoordinate(6, 1), childAncestry);
        assertThrows(IllegalArgumentException.class, () -> fullFrame(6, "crossing", false,
                List.of(), List.of(), List.of(forgedLifetime), state, List.of(), childDiagnostics));

        assertEquals(rawParent, CompleteRunAudioJson.readRecord(assertDoesNotThrow(
                () -> CompleteRunAudioJson.writeRecord(rawParent))));
        assertEquals(raw, CompleteRunAudioJson.readRecord(assertDoesNotThrow(
                () -> CompleteRunAudioJson.writeRecord(raw))));
        assertNotEquals(assertDoesNotThrow(() -> CompleteRunAudioJson.writeRecord(raw)),
                assertDoesNotThrow(() -> CompleteRunAudioJson.writeRecord(without)));
        assertEquals(assertDoesNotThrow(() -> CompleteRunAudioJson.writeSemanticRecord(raw)),
                assertDoesNotThrow(() -> CompleteRunAudioJson.writeSemanticRecord(without)));
        DriverService changedSemanticChild = testService(1, "update",
                ServiceCompletion.COMPLETED, null, null, null,
                new ServiceAncestry(parentBegin, 1, parentBegin, 1, List.of()));
        Frame changedSemantic = fullFrame(5, "crossing", false, List.of(), List.of(),
                List.of(semanticParent, changedSemanticChild), state, List.of(), null);
        assertNotEquals(assertDoesNotThrow(() -> CompleteRunAudioJson.writeSemanticRecord(raw)),
                assertDoesNotThrow(() -> CompleteRunAudioJson.writeSemanticRecord(changedSemantic)));
        assertThrows(IllegalArgumentException.class, () -> new FrontierService(2, 1, 1, "update",
                FrontierServiceState.COMPLETED, 5, 1, 0x71b4c, 2, "M68K",
                5, 8L, 0x71c4c, 3, List.of(), List.of(), 0, 0,
                List.of(new NativeAncestryTransition(6, 5, 6, 9, 1, 0, 0, 4, "Z80", 0xac))));
        assertThrows(IllegalArgumentException.class, () -> new FrontierService(2, 1, 1, "update",
                FrontierServiceState.COMPLETED, 5, 1, 0x71b4c, 2, "M68K",
                6, 2L, 0x71c4c, 3, List.of(), List.of(), 0, 0,
                List.of(new NativeAncestryTransition(6, 5, 1, 1, 1, 0, 0, 4, "Z80", 0xac)),
                childAncestry));
        assertThrows(IllegalArgumentException.class, () -> new FrontierService(2, 1, 1, "update",
                FrontierServiceState.COMPLETED, 5, 1, 0x71b4c, 2, "M68K",
                6, 2L, 0x71c4c, 3, List.of(), List.of(), 0, 0,
                List.of(new NativeAncestryTransition(6, 6, 2, 1, 1, 0, 0, 4, "Z80", 0xac)),
                childAncestry));
        assertThrows(IllegalArgumentException.class, () -> testService(1, "update",
                ServiceCompletion.COMPLETED, null,
                new ServiceCoordinate(5, 3), new ServiceCoordinate(6, 1), childAncestry));
        assertThrows(IllegalArgumentException.class, () -> testService(1, "update",
                ServiceCompletion.COMPLETED, null,
                new ServiceCoordinate(5, 1), new ServiceCoordinate(5, 3), childAncestry));
    }

    @Test
    void nativeManagedEventsRejectMalformedBoundaryIdentity() {
        assertThrows(IllegalArgumentException.class, () -> new NativeManagedEvent(
                0, 0, "Z80", 0x38, 10, 3, 0, 0, 0, 0, 1, true));
        assertThrows(IllegalArgumentException.class, () -> new NativeManagedEvent(
                0, 0, "M68K", 0x139f, 10, 3, 0, 0, 0, 0, 1, true));
        assertThrows(IllegalArgumentException.class, () -> new NativeManagedEvent(
                0, 0, "M68K", 0x138e, 9, 0, 0, 0, 0, 0, 1, true));
        assertThrows(IllegalArgumentException.class, () -> new NativeManagedEvent(
                0, 0, "M68K", 0x138e, 10, 4, 0, 0, 0, 0, 1, true));
        assertThrows(IllegalArgumentException.class, () -> new NativeManagedEvent(
                0, 0, "M68K", 0x138e, 10, 3, 0, 1, 0, 0, 1, true));
        assertThrows(IllegalArgumentException.class, () -> new NativeManagedEvent(
                0, 0, "M68K", 0x138e, 1, 0, 1, 0, 4, 8, 1, true));
        assertThrows(IllegalArgumentException.class, () -> new NativeManagedEvent(
                0, MAX_NATIVE_FRAME_EVENTS, "M68K", 0x138e,
                10, 3, 0, 0, 0, 0, 1, true));
        assertThrows(IllegalArgumentException.class, () -> new NativeManagedEvent(
                0, 0, "M68K", 0x138e, 10, 3, 0, 0, 0, 0, 1, 1, true));
    }

    @Test
    void nativeFrameDiagnosticsRepresentResetRootExactlyWithoutARegularHook() {
        NormalizedState state = new NormalizedState(List.of(), List.of());
        DriverService reset = testService(0, "reset", ServiceCompletion.COMPLETED,
                null, null, null, ServiceAncestry.root());
        FrontierChipEvent rawWrite = new FrontierChipEvent(1, 1, "RESET", 0, 4, 0, 0x9f,
                true, null, null);
        FrontierService rawReset = new FrontierService(1, 0, 0, "reset", FrontierServiceState.COMPLETED,
                3, 1, 0, 0, "RESET", 3, 3L, 0, 0, List.of(), List.of(rawWrite));
        FrameNativeDiagnostics diagnostics = new FrameNativeDiagnostics(List.of(rawReset),
                List.of(new FrontierOwnedChip(1, rawWrite)), List.of(),
                List.of(new NativeResetDiagnostic(1, false)));

        Frame frame = fullFrame(3, "reset", false, List.of(), List.of(reset),
                List.of(new PsgWrite(0, 0x9f)), diagnostics);
        assertEquals(frame, CompleteRunAudioJson.readRecord(assertDoesNotThrow(
                () -> CompleteRunAudioJson.writeRecord(frame))));
        assertEquals(false, diagnostics.resets().getFirst().power());
        assertThrows(IllegalArgumentException.class, () -> new FrameNativeDiagnostics(List.of(rawReset),
                List.of(new FrontierOwnedChip(1, rawWrite)), List.of()));
        assertThrows(IllegalArgumentException.class, () -> new FrontierService(1, 0, 0, "ordinary",
                FrontierServiceState.COMPLETED, 3, 1, 0x38, 0, "Z80", 3, 3L, 0, 0,
                List.of(), List.of()));
        assertThrows(IllegalArgumentException.class, () -> new FrontierService(1, 0, 0, "ordinary",
                FrontierServiceState.COMPLETED, 3, 1, 0x38, 1, "Z80", 4, -1L, 0x40, 2,
                List.of(), List.of()));
        assertThrows(IllegalArgumentException.class, () -> new FrontierService(1, 0, 0, "ordinary",
                FrontierServiceState.COMPLETED, 3, 1, 0x38, 1, "Z80", 4, 2L, 0x10000, 2,
                List.of(), List.of()));
        assertThrows(IllegalArgumentException.class, () -> new FrontierServiceRule("ordinary",
                FrontierServiceState.COMPLETED, 1, "Z80", 0x38, 2, 0x10000, List.of()));
        assertThrows(IllegalArgumentException.class, () -> new FrontierService(1, 0, 0, "ordinary",
                FrontierServiceState.OPEN, 3, MAX_NATIVE_FRAME_EVENTS, 0x38, 1, "Z80",
                null, null, null, null, List.of(), List.of()));
        assertThrows(IllegalArgumentException.class, () -> new FrontierService(1, 0, 0, "ordinary",
                FrontierServiceState.COMPLETED, 3, 1, 0x38, 1, "Z80", 3,
                (long) MAX_NATIVE_FRAME_EVENTS, 0x40, 2, List.of(), List.of()));
        assertThrows(IllegalArgumentException.class, () -> new FrontierChipEvent(1,
                MAX_NATIVE_FRAME_EVENTS, "Z80", 0x38, 4, 0, 0x9f, true, null, null));
        assertThrows(IllegalArgumentException.class, () -> new FrontierSnapshot(0x1_0000,
                "Z80", 0x38, List.of(0)));
    }

    private static final String TEST_ABI_NAME = CompleteRunAudioProfiles.GPGX_AUDIO_TRACE_ABI_NAME;
    private static final int TEST_ABI_VERSION = CompleteRunAudioProfiles.GPGX_AUDIO_TRACE_ABI_VERSION;
    private static final int TEST_EVENT_SIZE = CompleteRunAudioProfiles.GPGX_AUDIO_TRACE_EVENT_SIZE;
    private static final int TEST_CONFIG_SIZE = CompleteRunAudioProfiles.GPGX_AUDIO_TRACE_CONFIG_SIZE;
    private static final int TEST_KIND_SIZE = CompleteRunAudioProfiles.GPGX_AUDIO_TRACE_KIND_SIZE;
    private static final int TEST_HOOK_SIZE = CompleteRunAudioProfiles.GPGX_AUDIO_TRACE_HOOK_SIZE;
    private static final int TEST_RANGE_SIZE = CompleteRunAudioProfiles.GPGX_AUDIO_TRACE_RANGE_SIZE;
    private static final int TEST_CAPACITY = CompleteRunAudioProfiles.GPGX_AUDIO_TRACE_CAPACITY;
    private final Fixture fixture = new Fixture();

    @Test
    void oneFrameCanContainZeroOrMultipleOrderedServices() {
        var empty = fixture.frame(860, List.of(), List.of());
        var busy = fixture.frame(861, List.of(fixture.request(1)), List.of(
                fixture.service(0), fixture.service(1)));

        assertEquals(List.of(), empty.services());
        assertEquals(List.of(0L, 1L),
                busy.services().stream().map(DriverService::ordinal).toList());
    }

    @Test
    void sameIdOwnersRemainDistinctByRequestOrdinal() {
        assertNotEquals(new OwnerRef(OwnerClass.SFX, "sfx.explosion", 0xC0,
                        OwnerOrigin.REQUEST, 7),
                new OwnerRef(OwnerClass.SFX, "sfx.explosion", 0xC0,
                        OwnerOrigin.REQUEST, 8));
    }

    @Test
    void baselineAndRequestOriginsCannotCollideAtTheSameNumericOrdinal() {
        OwnerRef baseline = new OwnerRef(OwnerClass.MUSIC, "music.81", 0x81,
                OwnerOrigin.BASELINE, 0);
        OwnerRef request = new OwnerRef(OwnerClass.MUSIC, "music.81", 0x81,
                OwnerOrigin.REQUEST, 0);

        assertNotEquals(baseline, request);
    }

    @Test
    void ownerOriginAndIdentityShapeMustAgree() {
        assertThrows(IllegalArgumentException.class, () -> new OwnerRef(
                OwnerClass.NONE, "none", 0, OwnerOrigin.REQUEST, 0));
        assertThrows(IllegalArgumentException.class, () -> new OwnerRef(
                OwnerClass.SFX, "sfx.explosion", 0xc0, OwnerOrigin.NONE, -1));
        assertThrows(IllegalArgumentException.class, () -> new OwnerRef(
                OwnerClass.SFX, "sfx.explosion", 0xc0, OwnerOrigin.REQUEST, -1));
    }

    @Test
    void baselineCarriesAnExplicitOwnerForEveryHardwareRole() {
        OwnerRef music = new OwnerRef(OwnerClass.MUSIC, "music.81", 0x81,
                OwnerOrigin.BASELINE, 0);
        Baseline baseline = new Baseline(860,
                new NormalizedState(List.of(new StateField("tempo", 1)), List.of(
                        new RoleState(HardwareRole.FM1, true,
                                List.of(new StateField("cursor", 4))),
                        new RoleState(HardwareRole.PSG1, false, List.of()))),
                List.of(new RoleOwner(HardwareRole.FM1, music),
                        new RoleOwner(HardwareRole.PSG1,
                                new OwnerRef(OwnerClass.NONE, "none", 0,
                                        OwnerOrigin.NONE, -1))));

        assertEquals(music, baseline.roleOwners().getFirst().owner());
    }

    @Test
    void baselineRequiresExplicitCarriedInBoundaryFrontierAtItsOwnCoordinate() {
        NormalizedState state = new NormalizedState(List.of(), List.of());
        CutoffService carried = new CutoffService(null, -1, 0, "UpdateMusic",
                FrontierServiceState.CARRIED_IN_OPEN, 860, 0, null, null, List.of());
        BoundaryFrontier frontier = new BoundaryFrontier(
                List.of(carried), List.of(), List.of(), null, 0, 0);

        List<RoleOwner> owners = List.of(new RoleOwner(HardwareRole.FM1,
                new OwnerRef(OwnerClass.NONE, "none", 0, OwnerOrigin.NONE, -1)));
        Baseline baseline = new Baseline(860, state, owners, frontier);

        assertEquals(frontier, baseline.frontier());
        assertThrows(IllegalArgumentException.class, () -> new Baseline(861, state, owners, frontier));
        CutoffService ordinaryOpen = new CutoffService(null, -1, 0, "UpdateMusic",
                FrontierServiceState.OPEN, 860, 0, null, null, List.of());
        assertThrows(IllegalArgumentException.class, () -> new Baseline(860, state, owners,
                new BoundaryFrontier(List.of(ordinaryOpen), List.of(), List.of(), null, 0, 0)));
    }

    @Test
    void cutoffUsesTheSameBoundaryShapeButRejectsCarriedInState() {
        NormalizedState state = new NormalizedState(List.of(), List.of());
        CutoffService open = new CutoffService(null, -1, 0, "UpdateMusic",
                FrontierServiceState.OPEN, 900, 0, null, null, List.of());
        BoundaryFrontier frontier = new BoundaryFrontier(List.of(open), List.of(), List.of(), null, 0, 0);

        assertEquals(frontier, new CutoffFrontier(frontier, state).frontier());

        CutoffService carried = new CutoffService(null, -1, 0, "UpdateMusic",
                FrontierServiceState.CARRIED_IN_OPEN, 900, 0, null, null, List.of());
        assertThrows(IllegalArgumentException.class, () -> new CutoffFrontier(
                new BoundaryFrontier(List.of(carried), List.of(), List.of(), null, 0, 0), state));
    }

    @Test
    void cutoffCanKeepACarriedBaselineCoordinateWhileNativeProofKeepsItsTrueBegin() {
        NormalizedState state = new NormalizedState(List.of(), List.of());
        CutoffService semanticOpen = new CutoffService(null, -1, 0, "UpdateMusic",
                FrontierServiceState.OPEN, 860, 0, null, null, List.of());
        FrontierService nativeOpen = new FrontierService(1, 0, 0, "UpdateMusic",
                FrontierServiceState.OPEN, 859, 0, 0x71b4c, 1, "M68K",
                null, null, null, null, List.of(), List.of());
        CutoffNativeDiagnostics nativeProof = new CutoffNativeDiagnostics(
                List.of(nativeOpen), List.of(), List.of(), List.of(), 0, false,
                "f".repeat(64));

        CutoffFrontier cutoff = new CutoffFrontier(List.of(semanticOpen), List.of(),
                List.of(), nativeProof, 0, 0, state);

        assertEquals(860, cutoff.activeStack().getFirst().beginFrame());
        assertEquals(859, cutoff.nativeDiagnostics().activeStack().getFirst().beginFrame());
    }

    @Test
    void baselineRetainsCompletedPendingDescendantsWithoutReemittingThem() {
        NormalizedState state = new NormalizedState(List.of(), List.of());
        CutoffService carried = new CutoffService(null, -1, 0, "outer",
                FrontierServiceState.CARRIED_IN_OPEN, 860, 0, null, null, List.of());
        CutoffService pending = new CutoffService(860, 0, 1, "child",
                FrontierServiceState.COMPLETED, 860, 1, 860, 2L, List.of());
        BoundaryFrontier frontier = new BoundaryFrontier(
                List.of(carried), List.of(pending), List.of(), null, 0, 0);
        List<RoleOwner> owners = List.of(new RoleOwner(HardwareRole.FM1,
                new OwnerRef(OwnerClass.NONE, "none", 0, OwnerOrigin.NONE, -1)));

        Baseline baseline = new Baseline(860, state, owners, frontier);

        assertEquals(List.of(pending), baseline.frontier().pendingDescendants());
    }

    @Test
    void baselineNativeProofMustProjectTheSameSemanticChipInventory() {
        NormalizedState state = new NormalizedState(List.of(), List.of());
        FrontierChipEvent nativeWrite = new FrontierChipEvent(1, 0, "Z80", 0x100,
                4, 0, 0x90, true, null, null);
        FrontierService nativeOpen = new FrontierService(1, 0, 0, "driver",
                FrontierServiceState.OPEN, 859, 0, 0x38, 1, "Z80",
                null, null, null, null, List.of(), List.of(nativeWrite));
        CutoffNativeDiagnostics nativeProof = new CutoffNativeDiagnostics(List.of(nativeOpen),
                List.of(), List.of(new FrontierOwnedChip(1, nativeWrite)), List.of(),
                0, false, "f".repeat(64));
        PsgWrite semanticWrite = new PsgWrite(0, 0x90);
        CutoffService carriedWithWrite = new CutoffService(null, -1, 0, "driver",
                FrontierServiceState.CARRIED_IN_OPEN, 860, 0, null, null,
                List.of(semanticWrite));
        List<RoleOwner> owners = List.of(new RoleOwner(HardwareRole.FM1,
                new OwnerRef(OwnerClass.NONE, "none", 0, OwnerOrigin.NONE, -1)));

        new Baseline(860, state, owners, new BoundaryFrontier(List.of(carriedWithWrite),
                List.of(), List.of(semanticWrite), nativeProof, 0, 0));

        CutoffService carriedWithoutWrite = new CutoffService(null, -1, 0, "driver",
                FrontierServiceState.CARRIED_IN_OPEN, 860, 0, null, null, List.of());
        assertThrows(IllegalArgumentException.class, () -> new Baseline(860, state, owners,
                new BoundaryFrontier(List.of(carriedWithoutWrite), List.of(), List.of(),
                        nativeProof, 0, 0)));
    }

    @Test
    void nativeBoundaryTopologyIsAuthenticatedWhenBoundaryChipsAreUnobserved() {
        NormalizedState state = new NormalizedState(List.of(), List.of());
        FrontierService nativeOpen = new FrontierService(1, 0, 0, "driver",
                FrontierServiceState.OPEN, 859, 0, 0x38, 1, "Z80",
                null, null, null, null, List.of(), List.of());
        CutoffNativeDiagnostics nativeProof = new CutoffNativeDiagnostics(List.of(nativeOpen),
                List.of(), List.of(), List.of(), 0, false, "f".repeat(64));
        CutoffService forgedBaseline = new CutoffService(null, -1, 0, "forged",
                FrontierServiceState.CARRIED_IN_OPEN, 860, 0, null, null, List.of());
        CutoffService forgedCutoff = new CutoffService(null, -1, 0, "forged",
                FrontierServiceState.OPEN, 860, 0, null, null, List.of());

        assertThrows(IllegalArgumentException.class, () -> new Baseline(860, state, List.of(),
                new BoundaryFrontier(List.of(forgedBaseline), List.of(), null,
                        nativeProof, null, null)));
        assertThrows(IllegalArgumentException.class, () -> new CutoffFrontier(
                List.of(forgedCutoff), List.of(), null, nativeProof, null, null, state));
    }

    @Test
    void nativeBoundaryChipsAreAuthenticatedWhenServiceTopologyIsUnobserved() {
        NormalizedState state = new NormalizedState(List.of(), List.of());
        FrontierChipEvent nativeWrite = new FrontierChipEvent(1, 0, "Z80", 0x100,
                4, 0, 0x90, true, null, null);
        FrontierService nativeOpen = new FrontierService(1, 0, 0, "driver",
                FrontierServiceState.OPEN, 859, 0, 0x38, 1, "Z80",
                null, null, null, null, List.of(), List.of(nativeWrite));
        CutoffNativeDiagnostics nativeProof = new CutoffNativeDiagnostics(List.of(nativeOpen),
                List.of(), List.of(new FrontierOwnedChip(1, nativeWrite)), List.of(),
                0, false, "f".repeat(64));

        assertThrows(IllegalArgumentException.class, () -> new Baseline(860, state, List.of(),
                new BoundaryFrontier(null, null, List.of(new PsgWrite(0, 0x91)),
                        nativeProof, 0, 0)));
        assertThrows(IllegalArgumentException.class, () -> new CutoffFrontier(
                null, null, List.of(new PsgWrite(0, 0x91)), nativeProof, 0, 0, state));
    }

    @Test
    void rejectsSignedOrOutOfRangeChipBytes() {
        assertThrows(IllegalArgumentException.class, () -> new YmWrite(0, -1, 0x22, 0));
        assertThrows(IllegalArgumentException.class, () -> new YmWrite(0, 0, 0x100, 0));
        assertThrows(IllegalArgumentException.class, () -> new PsgWrite(0, 0x100));
    }

    @Test
    void profileRejectsUnorderedOrDuplicateRoles() {
        assertThrows(IllegalArgumentException.class,
                () -> fixture.profile.validateState(fixture.state(List.of(HardwareRole.PSG1, HardwareRole.FM1))));
        assertThrows(IllegalArgumentException.class,
                () -> fixture.profile.validateState(fixture.state(List.of(HardwareRole.FM1, HardwareRole.FM1))));
    }

    @Test
    void comparisonLayerInventoryRequiresOneCanonicalClaimForEveryLayer() {
        List<ComparisonLayerClaim> claims = new ArrayList<>(ComparisonLayerInventory.allCompared().claims());

        claims.add(claims.getFirst());
        List<ComparisonLayerClaim> duplicate = List.copyOf(claims);
        assertThrows(IllegalArgumentException.class, () -> new ComparisonLayerInventory(duplicate));

        claims = new ArrayList<>(ComparisonLayerInventory.allCompared().claims());
        claims.removeLast();
        List<ComparisonLayerClaim> missing = List.copyOf(claims);
        assertThrows(IllegalArgumentException.class, () -> new ComparisonLayerInventory(missing));

        claims = new ArrayList<>(ComparisonLayerInventory.allCompared().claims());
        java.util.Collections.swap(claims, 0, 1);
        List<ComparisonLayerClaim> reordered = List.copyOf(claims);
        assertThrows(IllegalArgumentException.class, () -> new ComparisonLayerInventory(reordered));
    }

    @Test
    void comparisonLayerInventoryRejectsDependentClaimsWithoutTheirAuthority() {
        List<ComparisonLayerClaim> claims = new ArrayList<>(ComparisonLayerInventory.allCompared().claims());
        claims.set(ComparisonLayer.REQUESTS.ordinal(), new ComparisonLayerClaim(ComparisonLayer.REQUESTS,
                ComparisonLayerStatus.UNAVAILABLE, "no pre-consumption request observer"));

        assertThrows(IllegalArgumentException.class, () -> new ComparisonLayerInventory(claims));
    }

    @Test
    void stateRejectsDuplicateFieldNames() {
        assertThrows(IllegalArgumentException.class, () -> new NormalizedState(List.of(
                new StateField("tempo", 1), new StateField("tempo", 2)), List.of()));
    }

    @Test
    void rejectsEmptyContentKeys() {
        assertThrows(IllegalArgumentException.class,
                () -> new OwnerRef(OwnerClass.SFX, "", 0xC0, OwnerOrigin.REQUEST, 7));
        assertThrows(IllegalArgumentException.class,
                () -> new Request(1, OwnerClass.SFX, " ", 0xC0, "mailbox", 0));
    }

    @Test
    void registryRejectsUnknownProfile() {
        assertThrows(IllegalArgumentException.class,
                () -> CompleteRunAudioProfiles.require("unknown.complete-run.profile"));
    }

    @Test
    void producerBindingsRejectBlankUnavailableReasonsAndZeroArtifactHashes() {
        assertThrows(IllegalArgumentException.class, () -> new UnavailableProducerBinding(" "));
        assertThrows(NullPointerException.class, () -> new PinnedProducerBinding(null));
        assertThrows(IllegalArgumentException.class, () -> new ProducerRuntimeIdentity(
                "openggf", "1", "jvm", "21", "openggf", "1",
                Map.of(RuntimeArtifact.OPENGGF_PRODUCER, "0".repeat(64))));
    }

    @Test
    void pinnedProducerValidationDoesNotRequireTheOtherProducerToBeInstalled() {
        TestProfile partial = new TestProfile("partial.profile", fixture.fixture,
                List.of(HardwareRole.FM1, HardwareRole.PSG1), List.of("tempo"), List.of("cursor"));
        partial.bindings.put(ProducerKind.OPENGGF, new UnavailableProducerBinding("engine task pending"));
        partial.producerIdentities.remove(ProducerKind.OPENGGF);
        partial.observerRuntimeIdentities.remove(ProducerKind.OPENGGF);
        partial.observerProofs.remove(ProducerKind.OPENGGF);
        Metadata reference = testMetadata(SCHEMA, partial.id(), partial.fixture(), ProducerKind.REFERENCE,
                partial.producerIdentities.get(ProducerKind.REFERENCE),
                partial.observerRuntimeIdentities.get(ProducerKind.REFERENCE),
                partial.observerProofs.get(ProducerKind.REFERENCE),
                new ChunkPolicy(CHUNK_FRAME_ROWS, "gzip", 0), partial.hardwareRoles(), partial.stateInventory());

        assertDoesNotThrow(() -> reference.validateRuntimeProfile(partial));
        assertThrows(IllegalArgumentException.class, () -> fixture.metadata.validateRuntimeProfile(partial));
    }

    @Test
    void unavailableBindingRejectsBeforeHostileFixtureMetadataIsCompared() {
        TestProfile unavailable = new TestProfile("hostile.unavailable.profile", fixture.fixture,
                List.of(HardwareRole.FM1, HardwareRole.PSG1), List.of("tempo"), List.of("cursor"));
        ProducerKind capturedKind = fixture.metadata.producerKind();
        unavailable.bindings.put(capturedKind,
                new UnavailableProducerBinding("captured producer task pending"));
        unavailable.producerIdentities.remove(capturedKind);
        unavailable.observerRuntimeIdentities.remove(capturedKind);
        unavailable.observerProofs.remove(capturedKind);

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> fixture.metadata.validateProfile(unavailable));

        assertTrue(failure.getMessage().startsWith("profile producer is unavailable:"));
    }

    @Test
    void profileRejectsInactiveRolesWithStaleFields() {
        var stale = new NormalizedState(List.of(new StateField("tempo", 1)), List.of(
                new RoleState(HardwareRole.FM1, false, List.of(new StateField("cursor", 4))),
                new RoleState(HardwareRole.PSG1, false, List.of())));

        assertThrows(IllegalArgumentException.class, () -> fixture.profile.validateState(stale));
    }

    @Test
    void profileAcceptsItsCompleteActiveRoleInventory() {
        assertDoesNotThrow(() -> fixture.profile.validateState(new NormalizedState(
                List.of(new StateField("tempo", 1)), List.of(
                        new RoleState(HardwareRole.FM1, true, List.of(new StateField("cursor", 4))),
                        new RoleState(HardwareRole.PSG1, false, List.of())))));
    }

    @Test
    void profileRejectsWrongActiveRoleInventory() {
        var incomplete = new NormalizedState(List.of(new StateField("tempo", 1)), List.of(
                new RoleState(HardwareRole.FM1, true, List.of(new StateField("wrong", 4))),
                new RoleState(HardwareRole.PSG1, false, List.of())));

        assertThrows(IllegalArgumentException.class, () -> fixture.profile.validateState(incomplete));
    }

    @Test
    void metadataBindsThePinnedProfileFixtureAndInventories() {
        assertDoesNotThrow(() -> fixture.metadata.validateProfile(fixture.profile));
    }

    @Test
    void metadataRejectsAnOtherwiseMatchingProfileWithWrongFixture() {
        CompleteRunFixture wrongFixture = new CompleteRunFixture(
                "1123456789abcdef0123456789abcdef01234567", "89abcdef",
                "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef", 862,
                "fedcba9876543210fedcba9876543210fedcba9876543210fedcba9876543210",
                List.of(new ManifestSegment("green-hill", 860, 862)), 860, 862);
        CompleteRunAudioProfile wrongFixtureProfile = new TestProfile("test.profile", wrongFixture,
                List.of(HardwareRole.FM1, HardwareRole.PSG1), List.of("tempo"), List.of("cursor"));

        assertThrows(IllegalArgumentException.class, () -> fixture.metadata.validateProfile(wrongFixtureProfile));
    }

    @Test
    void metadataRejectsAnOtherwiseMatchingProfileWithWrongStateInventory() {
        CompleteRunAudioProfile wrongInventory = new TestProfile("test.profile", fixture.fixture,
                List.of(HardwareRole.FM1, HardwareRole.PSG1), List.of("tempo_changed"), List.of("cursor"));

        assertThrows(IllegalArgumentException.class, () -> fixture.metadata.validateProfile(wrongInventory));
    }

    @Test
    void metadataRejectsAnOtherwiseMatchingProfileWithWrongHardwareRoles() {
        CompleteRunAudioProfile wrongRoles = new TestProfile("test.profile", fixture.fixture,
                List.of(HardwareRole.FM1), List.of("tempo"), List.of("cursor"));

        assertThrows(IllegalArgumentException.class, () -> fixture.metadata.validateProfile(wrongRoles));
    }

    @Test
    void metadataRejectsAnOtherwiseMatchingProfileWithWrongProducerRuntimeIdentity() {
        TestProfile wrongRuntime = new TestProfile("test.profile", fixture.fixture,
                List.of(HardwareRole.FM1, HardwareRole.PSG1), List.of("tempo"), List.of("cursor"));
        wrongRuntime.producerIdentities.put(ProducerKind.OPENGGF, new ProducerRuntimeIdentity(
                "OpenGGF", "different", "OpenGGF", "0.6", "SMPS", "1",
                Map.of(RuntimeArtifact.OPENGGF_PRODUCER, "4".repeat(64))));

        assertThrows(IllegalArgumentException.class, () -> fixture.metadata.validateProfile(wrongRuntime));
    }

    @Test
    void metadataRejectsObserverProofOutsideTheExactProducerSpecificProfileContract() {
        TestProfile wrongObserver = new TestProfile("test.profile", fixture.fixture,
                List.of(HardwareRole.FM1, HardwareRole.PSG1), List.of("tempo"), List.of("cursor"));
        wrongObserver.observerProofs.put(ProducerKind.OPENGGF,
                new ObserverProof("different.observer.v2", "java.different-domain",
                        List.of(new CallbackProof("different.site", 2))));

        assertThrows(IllegalArgumentException.class,
                () -> fixture.metadata.validateProfile(wrongObserver));
    }

    @Test
    void metadataPinsAProducerSpecificTypedObserverRuntimeIdentity() {
        assertDoesNotThrow(() -> fixture.metadata.validateProfile(fixture.profile));
        TestProfile wrongIdentity = new TestProfile("test.profile", fixture.fixture,
                List.of(HardwareRole.FM1, HardwareRole.PSG1), List.of("tempo"), List.of("cursor"));
        wrongIdentity.observerRuntimeIdentities.put(ProducerKind.OPENGGF,
                new CallbackObserverIdentity("different.callback.v1"));

        assertThrows(IllegalArgumentException.class,
                () -> fixture.metadata.validateProfile(wrongIdentity));
    }

    @Test
    void bufferedObserverIdentityFailsClosedOnEveryRuntimeBound() {
        String digest = "a".repeat(64);
        assertThrows(IllegalArgumentException.class, () -> new BufferedNativeObserverIdentity(
                TEST_ABI_NAME, TEST_ABI_VERSION, TEST_EVENT_SIZE, TEST_CONFIG_SIZE, TEST_KIND_SIZE,
                TEST_HOOK_SIZE, TEST_RANGE_SIZE, TEST_CAPACITY,
                "bizhawk-2.11-gpgx-audio-observer-v3", "gpgx-audio-observer-v3", "9f0e01c17bf47019",
                digest, digest, false, 1, 0));
        assertThrows(IllegalArgumentException.class, () -> new BufferedNativeObserverIdentity(
                TEST_ABI_NAME, TEST_ABI_VERSION, TEST_EVENT_SIZE, TEST_CONFIG_SIZE, TEST_KIND_SIZE,
                TEST_HOOK_SIZE, TEST_RANGE_SIZE, TEST_CAPACITY,
                "bizhawk-2.11-gpgx-audio-observer-v3", "gpgx-audio-observer-v3", "9f0e01c17bf47019",
                digest, digest, true, TEST_CAPACITY + 1, 0));
        assertThrows(IllegalArgumentException.class, () -> new BufferedNativeObserverIdentity(
                TEST_ABI_NAME, TEST_ABI_VERSION, TEST_EVENT_SIZE, TEST_CONFIG_SIZE, TEST_KIND_SIZE,
                TEST_HOOK_SIZE, TEST_RANGE_SIZE, TEST_CAPACITY,
                "bizhawk-2.11-gpgx-audio-observer-v3", "gpgx-audio-observer-v3", "9f0e01c17bf47019",
                digest, digest, true, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new BufferedNativeObserverIdentity(
                TEST_ABI_NAME, TEST_ABI_VERSION, TEST_EVENT_SIZE, TEST_CONFIG_SIZE, TEST_KIND_SIZE,
                TEST_HOOK_SIZE, TEST_RANGE_SIZE, TEST_CAPACITY,
                "/tmp/observer", "gpgx-audio-observer-v3", "9f0e01c17bf47019",
                digest, digest, true, 1, 0));
    }

    @Test
    void managedAdapterAndObserverArtifactsMustMatchExactly() {
        String digest = "a".repeat(64);
        Map<RuntimeArtifact, String> nativeArtifacts = new LinkedHashMap<>();
        for (RuntimeArtifact artifact : List.of(RuntimeArtifact.BIZHAWK_EXECUTABLE,
                RuntimeArtifact.BIZHAWK_CORE_DLL, RuntimeArtifact.GPGX_CORE,
                RuntimeArtifact.BIZHAWK_COMMON_DLL, RuntimeArtifact.WATERBOX_HOST,
                RuntimeArtifact.GPGX_CORE_UNCOMPRESSED, RuntimeArtifact.GPGX_OBSERVER_PATCH,
                RuntimeArtifact.GPGX_OBSERVER_SOURCE_BUNDLE, RuntimeArtifact.GPGX_OBSERVER_TOOLCHAIN,
                RuntimeArtifact.GPGX_OBSERVER_BUILD_RECIPE, RuntimeArtifact.GPGX_OBSERVER_IDENTITY,
                RuntimeArtifact.GPGX_OBSERVER_ADAPTER_SOURCE, RuntimeArtifact.GPGX_HOST_BRIDGE_SOURCE,
                RuntimeArtifact.BIZHAWK_BIZINVOKE_DLL, RuntimeArtifact.BIZHAWK_BASE_COMMON_DLL,
                RuntimeArtifact.TASK8_HARNESS_EXECUTABLE, RuntimeArtifact.TASK8_COLLECTOR_SOURCE,
                RuntimeArtifact.TASK8_HOST_SOURCE, RuntimeArtifact.GPGX_OBSERVER_CAPABILITY,
                RuntimeArtifact.REFERENCE_INSTALLATION_TREE)) {
            nativeArtifacts.put(artifact, digest);
        }
        BufferedNativeObserverIdentity nativeIdentity = new BufferedNativeObserverIdentity(
                TEST_ABI_NAME, TEST_ABI_VERSION, TEST_EVENT_SIZE, TEST_CONFIG_SIZE, TEST_KIND_SIZE,
                TEST_HOOK_SIZE, TEST_RANGE_SIZE, TEST_CAPACITY,
                "bizhawk-2.11-gpgx-audio-observer-v3", "gpgx-audio-observer-v3", "9f0e01c17bf47019",
                digest, digest, true, 1, 0);
        ProducerRuntimeIdentity reflection = new ProducerRuntimeIdentity(
                "BizHawk", "2.11", "BizHawk", "2.11", "GPGX", "1.0",
                ManagedObserverAdapter.REFLECTION, nativeArtifacts);
        assertDoesNotThrow(() -> reflection.validateFor(ProducerKind.REFERENCE, nativeIdentity));

        nativeArtifacts.put(RuntimeArtifact.BIZHAWK_OBSERVER_MANAGED_PATCH, digest);
        assertThrows(IllegalArgumentException.class, () -> new ProducerRuntimeIdentity(
                "BizHawk", "2.11", "BizHawk", "2.11", "GPGX", "1.0",
                ManagedObserverAdapter.REFLECTION, nativeArtifacts));
        nativeArtifacts.put(RuntimeArtifact.BIZHAWK_OBSERVER_CORES_DLL, digest);
        ProducerRuntimeIdentity firstClass = new ProducerRuntimeIdentity(
                "BizHawk", "2.11", "BizHawk", "2.11", "GPGX", "1.0",
                ManagedObserverAdapter.FIRST_CLASS, nativeArtifacts);
        assertDoesNotThrow(() -> firstClass.validateFor(ProducerKind.REFERENCE, nativeIdentity));
    }

    @Test
    void callbackObserverMetadataHasIndependentCanonicalJsonAndStrictParserGates() throws Exception {
        String canonical = """
                {"schema":"complete_run_audio.v1","profileId":"test.profile","fixture":{"romSha1":"0123456789abcdef0123456789abcdef01234567","romCrc32":"89abcdef","bk2Sha256":"0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef","bk2RowCount":862,"runManifestSha256":"fedcba9876543210fedcba9876543210fedcba9876543210fedcba9876543210","segments":[{"id":"green-hill","firstFrame":860,"exclusiveEnd":862}],"firstFrame":860,"exclusiveEnd":862},"producerKind":"OPENGGF","producerRuntimeIdentity":{"producerName":"OpenGGF","producerVersion":"0.6","emulatorName":"OpenGGF","emulatorVersion":"0.6","coreName":"SMPS","coreVersion":"1","observerAdapter":"CALLBACK_ONLY","artifactSha256":{"OPENGGF_PRODUCER":"4444444444444444444444444444444444444444444444444444444444444444"}},"observerRuntimeIdentity":{"kind":"CALLBACK","id":"openggf.callback.v1"},"observerProof":{"observerProfile":"test.observer.v1","callbackSource":"m68k.execute","callbacks":[{"callback":"driver.service","observations":1}]},"chunkPolicy":{"frameRows":4096,"compression":"gzip","gzipTimestamp":0},"hardwareRoles":["FM1","PSG1"],"stateInventory":{"globalFields":["tempo"],"activeRoleFields":["cursor"]}}""";
        canonical = canonical.replace("complete_run_audio.v1", CompleteRunAudioTrace.SCHEMA);
        canonical = canonical.substring(0, canonical.length() - 1) + "," + ALL_COMPARED_LAYER_JSON + "}";
        final String canonicalJson = canonical;

        assertEquals(canonical, CompleteRunAudioJson.writeMetadata(fixture.metadata));
        assertEquals(fixture.metadata, readMetadata(canonical));
        assertThrows(IllegalArgumentException.class,
                () -> readMetadata(canonicalJson.replace("," + ALL_COMPARED_LAYER_JSON, "")));
        assertThrows(IllegalArgumentException.class,
                () -> readMetadata(canonicalJson.replace("\"kind\":\"CALLBACK\"", "\"kind\":\"UNKNOWN\"")));
        assertThrows(IllegalArgumentException.class,
                () -> readMetadata(canonicalJson.replace("\"observerRuntimeIdentity\":{\"kind\":\"CALLBACK\",\"id\":\"openggf.callback.v1\"},", "")));
        assertThrows(IllegalArgumentException.class,
                () -> readMetadata(canonicalJson.replace("\"id\":\"openggf.callback.v1\"",
                        "\"id\":\"openggf.callback.v1\",\"id\":\"duplicate\"")));
        assertThrows(IllegalArgumentException.class,
                () -> readMetadata(canonicalJson.replace("\"observerAdapter\":\"CALLBACK_ONLY\"",
                        "\"observerAdapter\":\"REFLECTION\"")));
        assertThrows(IllegalArgumentException.class,
                () -> readMetadata(canonicalJson.replace("\"id\":\"openggf.callback.v1\"",
                        "\"id\":\"openggf.callback.v1\",\"unknown\":0")));
    }

    @Test
    void metadataReaderRejectsDuplicateRuntimeArtifactKeysWithoutParserDuplicateDetection() throws Exception {
        String canonical = CompleteRunAudioJson.writeMetadata(fixture.metadata);
        String duplicate = canonical.replace(
                "\"OPENGGF_PRODUCER\":\"4444444444444444444444444444444444444444444444444444444444444444\"",
                "\"OPENGGF_PRODUCER\":\"4444444444444444444444444444444444444444444444444444444444444444\","
                        + "\"OPENGGF_PRODUCER\":\"5555555555555555555555555555555555555555555555555555555555555555\"");

        try (var parser = new com.fasterxml.jackson.core.JsonFactory().createParser(duplicate)) {
            parser.nextToken();
            assertThrows(IllegalArgumentException.class, () -> CompleteRunAudioJson.readMetadata(parser));
        }
    }

    @Test
    void bufferedObserverMetadataHasIndependentCanonicalJsonAndStrictParserGates() throws Exception {
        String digest = "a".repeat(64);
        Map<RuntimeArtifact, String> artifacts = new EnumMap<>(RuntimeArtifact.class);
        artifacts.put(RuntimeArtifact.BIZHAWK_EXECUTABLE, digest);
        artifacts.put(RuntimeArtifact.BIZHAWK_CORE_DLL, digest);
        artifacts.put(RuntimeArtifact.BIZHAWK_COMMON_DLL, digest);
        artifacts.put(RuntimeArtifact.WATERBOX_HOST, digest);
        artifacts.put(RuntimeArtifact.GPGX_CORE, CompleteRunAudioProfiles.GPGX_AUDIO_TRACE_CORE_SHA256);
        artifacts.put(RuntimeArtifact.GPGX_CORE_UNCOMPRESSED,
                CompleteRunAudioProfiles.GPGX_AUDIO_TRACE_CORE_UNCOMPRESSED_SHA256);
        artifacts.put(RuntimeArtifact.GPGX_OBSERVER_PATCH,
                CompleteRunAudioProfiles.GPGX_AUDIO_TRACE_PATCH_SHA256);
        artifacts.put(RuntimeArtifact.GPGX_OBSERVER_SOURCE_BUNDLE,
                CompleteRunAudioProfiles.GPGX_AUDIO_TRACE_SOURCE_BUNDLE_SHA256);
        artifacts.put(RuntimeArtifact.GPGX_OBSERVER_TOOLCHAIN,
                CompleteRunAudioProfiles.GPGX_AUDIO_TRACE_TOOLCHAIN_SHA256);
        artifacts.put(RuntimeArtifact.GPGX_OBSERVER_BUILD_RECIPE,
                CompleteRunAudioProfiles.GPGX_AUDIO_TRACE_BUILD_RECIPE_SHA256);
        artifacts.put(RuntimeArtifact.GPGX_OBSERVER_IDENTITY,
                CompleteRunAudioProfiles.GPGX_AUDIO_TRACE_IDENTITY_SHA256);
        artifacts.put(RuntimeArtifact.GPGX_OBSERVER_ADAPTER_SOURCE,
                CompleteRunAudioProfiles.GPGX_AUDIO_TRACE_ADAPTER_SOURCE_SHA256);
        artifacts.put(RuntimeArtifact.GPGX_HOST_BRIDGE_SOURCE,
                CompleteRunAudioProfiles.GPGX_AUDIO_TRACE_HOST_BRIDGE_SOURCE_SHA256);
        artifacts.put(RuntimeArtifact.BIZHAWK_BIZINVOKE_DLL,
                CompleteRunAudioProfiles.BIZHAWK_BIZINVOKE_SHA256);
        artifacts.put(RuntimeArtifact.BIZHAWK_BASE_COMMON_DLL,
                CompleteRunAudioProfiles.BIZHAWK_BASE_COMMON_SHA256);
        artifacts.put(RuntimeArtifact.TASK8_HARNESS_EXECUTABLE,
                CompleteRunAudioProfiles.TASK8_HARNESS_EXECUTABLE_SHA256);
        artifacts.put(RuntimeArtifact.TASK8_COLLECTOR_SOURCE,
                CompleteRunAudioProfiles.TASK8_COLLECTOR_SOURCE_SHA256);
        artifacts.put(RuntimeArtifact.TASK8_HOST_SOURCE,
                CompleteRunAudioProfiles.TASK8_HOST_SOURCE_SHA256);
        artifacts.put(RuntimeArtifact.GPGX_OBSERVER_CAPABILITY,
                CompleteRunAudioProfiles.GPGX_AUDIO_CAPABILITY_SHA256);
        artifacts.put(RuntimeArtifact.REFERENCE_INSTALLATION_TREE,
                CompleteRunAudioProfiles.REFERENCE_INSTALLATION_TREE_SHA256);
        Metadata metadata = testMetadata(SCHEMA, "test.profile", fixture.fixture, ProducerKind.REFERENCE,
                new ProducerRuntimeIdentity("BizHawk", "2.11", "BizHawk", "2.11", "GPGX", "1.0",
                        ManagedObserverAdapter.REFLECTION, artifacts),
                new BufferedNativeObserverIdentity(TEST_ABI_NAME, TEST_ABI_VERSION,
                        TEST_EVENT_SIZE, TEST_CONFIG_SIZE, TEST_KIND_SIZE, TEST_HOOK_SIZE, TEST_RANGE_SIZE,
                        TEST_CAPACITY,
                        "bizhawk-2.11-gpgx-audio-observer-v3", "gpgx-audio-observer-v3",
                        CompleteRunAudioProfiles.GPGX_AUDIO_TRACE_CORE_BUILD_ID,
                        "b".repeat(64), "c".repeat(64), true, 1, 0),
                new ObserverProof("reference.observer.v1", "native.buffer",
                        List.of(new CallbackProof("driver.service", 1))),
                new ChunkPolicy(4096, "gzip", 0), List.of(HardwareRole.FM1, HardwareRole.PSG1),
                new StateInventory(List.of("tempo"), List.of("cursor")));
        assertThrows(IllegalArgumentException.class,
                () -> metadata.validateObservationShape(fixture.frame(860, List.of(), List.of())));
        String baseCanonical = """
{"schema":"complete_run_audio.v1","profileId":"test.profile","fixture":{"romSha1":"0123456789abcdef0123456789abcdef01234567","romCrc32":"89abcdef","bk2Sha256":"0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef","bk2RowCount":862,"runManifestSha256":"fedcba9876543210fedcba9876543210fedcba9876543210fedcba9876543210","segments":[{"id":"green-hill","firstFrame":860,"exclusiveEnd":862}],"firstFrame":860,"exclusiveEnd":862},"producerKind":"REFERENCE","producerRuntimeIdentity":{"producerName":"BizHawk","producerVersion":"2.11","emulatorName":"BizHawk","emulatorVersion":"2.11","coreName":"GPGX","coreVersion":"1.0","observerAdapter":"REFLECTION","artifactSha256":{"BIZHAWK_EXECUTABLE":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","BIZHAWK_CORE_DLL":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","BIZHAWK_COMMON_DLL":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","WATERBOX_HOST":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","GPGX_CORE":"e65315743a6a122843907a85314e380eee03fdc06bf0885b44c3dbc3bab88c6d","GPGX_CORE_UNCOMPRESSED":"f57b7a94237653879fb99af197937500a8b591f801f56284b4d2f53ca7ea6b0c","GPGX_OBSERVER_PATCH":"9f49e334ec8a8f73e878b8c1b6b207baabc054e085e7af95e3dd07e77df9280c","GPGX_OBSERVER_SOURCE_BUNDLE":"de73c512b2120f63f064f5e8fd59dee230f0ff50d0debbd648a9112efe18b83b","GPGX_OBSERVER_TOOLCHAIN":"9caa5c02dcd2d9c01e5d0196956787a0f31760195c6544a2ceafcb771f469521","GPGX_OBSERVER_BUILD_RECIPE":"f419cc73426f1356c30577c04231a0cc3356bdd99bc4760dfba55abecefdf748","GPGX_OBSERVER_IDENTITY":"815bfde02d78fd6caa1b127ddefe7be28cc84d6fdeef5a75cecc31f186f84d86","GPGX_OBSERVER_ADAPTER_SOURCE":"046ab11f4ffaf100651dda49625e14f3b08e54a33f61ed415d039a0d27b9bb93","GPGX_HOST_BRIDGE_SOURCE":"af9da7ed2f08d27c663176f4f1c852504c4a515e437655abb0fd5d20a3364bf1","BIZHAWK_BIZINVOKE_DLL":"8d05389bf0e02be1244bdc7a2adcd93b4cff95acf199fc927987ca699760a1b7","BIZHAWK_BASE_COMMON_DLL":"438a49d6a45d9fcac17016240ae205d1af7a4632865f6f70468b684b82323f33"}},"observerRuntimeIdentity":{"kind":"BUFFERED_NATIVE","abiName":"gpgx.audio-trace.v1","abiVersion":4,"eventSize":32,"capacity":65536,"installationId":"bizhawk-2.11-gpgx-audio-observer-v3","coreId":"gpgx-audio-observer-v3","coreBuildId":"cba4d8c88cf968a9","watchMaskSha256":"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb","serviceManifestSha256":"cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc","enabled":true,"maximumFrameOccupancy":1,"overflowCount":0},"observerProof":{"observerProfile":"reference.observer.v1","callbackSource":"native.buffer","callbacks":[{"callback":"driver.service","observations":1}]},"chunkPolicy":{"frameRows":4096,"compression":"gzip","gzipTimestamp":0},"hardwareRoles":["FM1","PSG1"],"stateInventory":{"globalFields":["tempo"],"activeRoleFields":["cursor"]}}""";

        String canonical = baseCanonical.replace("complete_run_audio.v1", CompleteRunAudioTrace.SCHEMA)
                .replace("\"eventSize\":32,",
                "\"eventSize\":32,\"configSize\":64,\"kindSize\":16,\"hookSize\":32,\"rangeSize\":16,")
                .replace(
                "\"BIZHAWK_BASE_COMMON_DLL\":\"438a49d6a45d9fcac17016240ae205d1af7a4632865f6f70468b684b82323f33\"",
                "\"BIZHAWK_BASE_COMMON_DLL\":\"438a49d6a45d9fcac17016240ae205d1af7a4632865f6f70468b684b82323f33\""
                        + ",\"TASK8_HARNESS_EXECUTABLE\":\"0b96b3dfb0ce8a246533ebd2b5d197833bce1024c1ef221113792d5ffd0ed679\""
                        + ",\"TASK8_COLLECTOR_SOURCE\":\"e0be4819556cf74b82273cba7b748ddd13529f2dcc61029a302f4b0f8acfda89\""
                        + ",\"TASK8_HOST_SOURCE\":\"c45d7de53bd29101d896fadb0a69eda1ae206d1fac43a5733afb3f4bd7f86be7\""
                        + ",\"GPGX_OBSERVER_CAPABILITY\":\"845323d7fa50af24b4f516c8937fa231737d3e66df648a90e035fdd56072f326\""
                        + ",\"REFERENCE_INSTALLATION_TREE\":\"830351fbda507637719647ffe283a542fcb319b0b2609dc53d675fe553e31c87\"");
        canonical = canonical.substring(0, canonical.length() - 1) + "," + ALL_COMPARED_LAYER_JSON + "}";
        final String canonicalJson = canonical;
        assertEquals(canonical, CompleteRunAudioJson.writeMetadata(metadata));
        assertEquals(metadata, readMetadata(canonical));
        assertThrows(IllegalArgumentException.class,
                () -> readMetadata(canonicalJson.replace("\"eventSize\":32,", "")));
        assertThrows(IllegalArgumentException.class,
                () -> readMetadata(canonicalJson.replace("\"capacity\":65536",
                        "\"capacity\":65536,\"capacity\":65536")));
        assertThrows(IllegalArgumentException.class,
                () -> readMetadata(canonicalJson.replace("\"enabled\":true", "\"enabled\":false")));
        assertThrows(IllegalArgumentException.class,
                () -> readMetadata(canonicalJson.replace("\"maximumFrameOccupancy\":1",
                        "\"maximumFrameOccupancy\":2000009")));
        assertThrows(IllegalArgumentException.class,
                () -> readMetadata(canonicalJson.replace("\"overflowCount\":0", "\"overflowCount\":1")));
        assertThrows(IllegalArgumentException.class,
                () -> readMetadata(canonicalJson.replace("\"overflowCount\":0}",
                        "\"overflowCount\":0,\"unknown\":0}")));
    }

    @Test
    void metadataRejectsTerminalWithWrongFrameCountExclusiveEndOrDerivedCounts() {
        assertThrows(IllegalArgumentException.class,
                () -> fixture.metadata.validateTerminal(fixture.terminal(1), fixture.counts(1)));
        assertThrows(IllegalArgumentException.class,
                () -> fixture.metadata.validateTerminal(fixture.terminal(2, 863), fixture.counts(2)));
        assertThrows(IllegalArgumentException.class,
                () -> fixture.metadata.validateTerminal(fixture.terminal(2), fixture.counts(3)));
        assertDoesNotThrow(() -> fixture.metadata.validateTerminal(
                fixture.terminal(2), fixture.counts(2)));
    }

    @Test
    void terminalRequiresCanonicalSha256DigestAndOverflowSafeCountTotal() {
        assertThrows(IllegalArgumentException.class,
                () -> new Terminal(862, 2, 0, 0, 0, 0, 0, 0, "A".repeat(64)));
        assertThrows(IllegalArgumentException.class,
                () -> new Terminal(862, 2, 0, 0, 0, 0, 0, 0, "a"));
        assertThrows(ArithmeticException.class,
                () -> new CaptureCounts(Long.MAX_VALUE, 1, 0, 0, 0, 0, 0).total());
    }

    @Test
    void producerRuntimeIdentityRequiresKindSpecificArtifactsAndCanonicalHashes() {
        assertDoesNotThrow(() -> fixture.referenceRuntimeIdentity());
        assertDoesNotThrow(() -> fixture.openGgfRuntimeIdentity());
        ProducerRuntimeIdentity missingGpgx = new ProducerRuntimeIdentity(
                "BizHawk", "2.11", "Genesis Plus GX", "1.0", "GPGX", "1.0",
                Map.of(RuntimeArtifact.BIZHAWK_EXECUTABLE, "a".repeat(64),
                        RuntimeArtifact.BIZHAWK_CORE_DLL, "b".repeat(64)));
        assertThrows(IllegalArgumentException.class, () -> missingGpgx.validateFor(ProducerKind.REFERENCE));
        assertThrows(IllegalArgumentException.class, () -> new ProducerRuntimeIdentity(
                "OpenGGF", "0.6", "OpenGGF", "0.6", "SMPS", "1",
                Map.of(RuntimeArtifact.OPENGGF_PRODUCER, "A".repeat(64))));
    }

    @Test
    void registryRejectsProfileThatOmitsAnAllowedProducerKindIdentity() {
        TestProfile missingReference = new TestProfile("missing.reference.runtime", fixture.fixture,
                List.of(HardwareRole.FM1, HardwareRole.PSG1), List.of("tempo"), List.of("cursor"));
        missingReference.producerIdentities.remove(ProducerKind.REFERENCE);

        assertThrows(IllegalArgumentException.class, () -> CompleteRunAudioProfiles.register(missingReference));
    }

    @Test
    void registryRejectsProfileThatOmitsAnObserverRuntimeIdentity() {
        TestProfile missingReference = new TestProfile("missing.reference.observer", fixture.fixture,
                List.of(HardwareRole.FM1, HardwareRole.PSG1), List.of("tempo"), List.of("cursor"));
        missingReference.observerRuntimeIdentities.remove(ProducerKind.REFERENCE);

        assertThrows(IllegalArgumentException.class, () -> CompleteRunAudioProfiles.register(missingReference));
    }

    @Test
    void registrySnapshotsProfileIdentityResolutionAndInventories() {
        String id = "registry.snapshot.profile";
        var mutable = new TestProfile(id, fixture.fixture, new ArrayList<>(List.of(HardwareRole.FM1, HardwareRole.PSG1)),
                new ArrayList<>(List.of("tempo")), new ArrayList<>(List.of("cursor")));
        CompleteRunAudioProfiles.register(mutable);
        mutable.roles.clear();
        mutable.globalFields.clear();
        mutable.identities.clear();
        mutable.producerIdentities.clear();
        mutable.observerRuntimeIdentities.clear();

        CompleteRunAudioProfile frozen = CompleteRunAudioProfiles.require(id);
        assertEquals(List.of(HardwareRole.FM1, HardwareRole.PSG1), frozen.hardwareRoles());
        assertEquals(List.of("tempo"), frozen.stateInventory().globalFields());
        assertEquals(new NativeSoundIdentity(OwnerClass.SFX, "sfx.explosion", 0xC0),
                frozen.resolveRequest(new RawAudioRequest(OwnerClass.SFX, 0xC0, "mailbox", 0)));
        assertEquals(fixture.openGgfRuntimeIdentity(),
                frozen.producerRuntimeIdentities().get(ProducerKind.OPENGGF));
        assertEquals(new CallbackObserverIdentity("openggf.callback.v1"),
                frozen.observerRuntimeIdentities().get(ProducerKind.OPENGGF));
        assertThrows(IllegalArgumentException.class, () -> CompleteRunAudioProfiles.register(
                new TestProfile(id, fixture.fixture, List.of(HardwareRole.FM1, HardwareRole.PSG1),
                        List.of("tempo"), List.of("cursor"))));
    }

    @Test
    void lifecycleMapsHaveCanonicalKeyOrder() {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("z", 1);
        details.put("a", 2);

        assertEquals(List.of("a", "z"), new Lifecycle(0, 860, "reset", details, List.of())
                .details().keySet().stream().toList());
    }

    @Test
    void lifecycleOwnershipTransitionsRequireCanonicalUniqueRoleOrder() {
        OwnerRef none = new OwnerRef(OwnerClass.NONE, "none", 0, OwnerOrigin.NONE, -1);
        LifecycleOwnership fm1 = new LifecycleOwnership(HardwareRole.FM1, none, none);
        LifecycleOwnership psg1 = new LifecycleOwnership(HardwareRole.PSG1, none, none);

        assertThrows(IllegalArgumentException.class,
                () -> new Lifecycle(0, 860, "reset", Map.of(), List.of(psg1, fm1)));
        assertThrows(IllegalArgumentException.class,
                () -> new Lifecycle(0, 860, "reset", Map.of(), List.of(fm1, fm1)));
    }

    private static final class Fixture {
        private final CompleteRunFixture fixture = new CompleteRunFixture(
                "0123456789abcdef0123456789abcdef01234567", "89abcdef",
                "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef", 862,
                "fedcba9876543210fedcba9876543210fedcba9876543210fedcba9876543210",
                List.of(new ManifestSegment("green-hill", 860, 862)), 860, 862);
        private final TestProfile profile = new TestProfile("test.profile", fixture,
                List.of(HardwareRole.FM1, HardwareRole.PSG1), List.of("tempo"), List.of("cursor"));
        private final Metadata metadata = testMetadata(CompleteRunAudioTrace.SCHEMA, "test.profile", fixture,
                ProducerKind.OPENGGF, openGgfRuntimeIdentity(), new CallbackObserverIdentity("openggf.callback.v1"),
                new ObserverProof("test.observer.v1", "m68k.execute",
                        List.of(new CallbackProof("driver.service", 1))),
                new ChunkPolicy(4096, "gzip", 0), List.of(HardwareRole.FM1, HardwareRole.PSG1),
                new StateInventory(List.of("tempo"), List.of("cursor")));

        private Frame frame(int row, List<Request> requests, List<DriverService> services) {
            return fullFrame(row, null, false, requests, services);
        }

        private Request request(long ordinal) {
            return new Request(ordinal, OwnerClass.SFX, "sfx.explosion", 0xC0, "mailbox", 0);
        }

        private DriverService service(long ordinal) {
            return testService(ordinal, "driver", ServiceCompletion.COMPLETED, List.of(),
                    state(List.of(HardwareRole.FM1, HardwareRole.PSG1)), List.of());
        }

        private NormalizedState state(List<HardwareRole> roles) {
            return new NormalizedState(List.of(new StateField("tempo", 1)), roles.stream()
                    .map(role -> new RoleState(role, false, List.of()))
                    .toList());
        }

        private Terminal terminal(long frameCount) {
            return terminal(frameCount, 862);
        }

        private Terminal terminal(long frameCount, int exclusiveEnd) {
            return new Terminal(exclusiveEnd, frameCount, 1, 2, 3, 4, 5, 6, "a".repeat(64));
        }

        private CaptureCounts counts(long frameCount) {
            return new CaptureCounts(frameCount, 1, 2, 3, 4, 5, 6);
        }

        private ProducerRuntimeIdentity referenceRuntimeIdentity() {
            return new ProducerRuntimeIdentity("BizHawk", "2.11", "BizHawk", "2.11", "GPGX", "1.0",
                    ManagedObserverAdapter.CALLBACK_ONLY,
                    Map.of(RuntimeArtifact.BIZHAWK_EXECUTABLE, "1".repeat(64),
                            RuntimeArtifact.BIZHAWK_CORE_DLL, "2".repeat(64),
                            RuntimeArtifact.GPGX_CORE, "3".repeat(64)));
        }

        private ProducerRuntimeIdentity openGgfRuntimeIdentity() {
            return new ProducerRuntimeIdentity("OpenGGF", "0.6", "OpenGGF", "0.6", "SMPS", "1",
                    ManagedObserverAdapter.CALLBACK_ONLY,
                    Map.of(RuntimeArtifact.OPENGGF_PRODUCER, "4".repeat(64)));
        }
    }

    private static Metadata readMetadata(String json) {
        try (var parser = CompleteRunAudioJson.FACTORY.createParser(json)) {
            parser.nextToken();
            Metadata metadata = CompleteRunAudioJson.readMetadata(parser);
            if (parser.nextToken() != null) {
                throw new IllegalArgumentException("metadata JSON contains trailing tokens");
            }
            return metadata;
        } catch (IllegalArgumentException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new IllegalArgumentException("invalid metadata JSON", failure);
        }
    }

    private static Frame fullFrame(int absoluteFrame, String segment, boolean lag, List<Request> requests,
            List<DriverService> services) {
        List<Decision> decisions = services.stream().flatMap(service -> evidence(service).decisions().stream())
                .toList();
        NormalizedState state = services.isEmpty() ? new NormalizedState(List.of(), List.of())
                : evidence(services.getLast()).state();
        List<ChipEvent> chips = services.stream().flatMap(service -> evidence(service).chips().stream())
                .sorted(java.util.Comparator.comparingLong(ChipEvent::ordinal)).toList();
        return new Frame(absoluteFrame, segment, lag, requests, decisions, services, state, chips, null);
    }

    private static Frame fullFrame(int absoluteFrame, String segment, boolean lag, List<Request> requests,
            List<DriverService> services, List<ChipEvent> chips, FrameNativeDiagnostics diagnostics) {
        List<Decision> decisions = services.stream().flatMap(service -> evidence(service).decisions().stream())
                .toList();
        NormalizedState state = services.isEmpty() ? new NormalizedState(List.of(), List.of())
                : evidence(services.getLast()).state();
        return new Frame(absoluteFrame, segment, lag, requests, decisions, services, state, chips, diagnostics);
    }

    private static Frame fullFrame(int absoluteFrame, String segment, Boolean lag, List<Request> requests,
            List<Decision> decisions, List<DriverService> services, NormalizedState state,
            List<ChipEvent> chips, FrameNativeDiagnostics diagnostics) {
        return new Frame(absoluteFrame, segment, lag, requests, decisions, services, state, chips, diagnostics);
    }

    private static ServiceEvidence evidence(DriverService service) {
        return SERVICE_EVIDENCE.getOrDefault(service,
                new ServiceEvidence(List.of(), new NormalizedState(List.of(), List.of()), List.of()));
    }

    private static DriverService testService(long ordinal, String kind, ServiceCompletion completion,
            List<Decision> decisions, NormalizedState state, List<ChipEvent> chips) {
        return testService(ordinal, kind, completion, decisions, state, chips, null, null, null,
                ServiceAncestry.root());
    }

    private static DriverService testService(long ordinal, String kind, ServiceCompletion completion,
            List<Decision> decisions, NormalizedState state, List<ChipEvent> chips, Long carried,
            ServiceAncestry ancestry) {
        return testService(ordinal, kind, completion, decisions, state, chips, carried, null, null, ancestry);
    }

    private static DriverService testService(long ordinal, String kind, ServiceCompletion completion,
            List<Decision> decisions, NormalizedState state, List<ChipEvent> chips, Long carried,
            ServiceCoordinate begin, ServiceCoordinate end, ServiceAncestry ancestry) {
        DriverService service = new DriverService(ordinal, kind, completion, carried, begin, end, ancestry);
        SERVICE_EVIDENCE.put(service, new ServiceEvidence(List.copyOf(decisions), state, List.copyOf(chips)));
        return service;
    }

    private static DriverService testService(long ordinal, String kind, ServiceCompletion completion,
            Long carried, ServiceCoordinate begin, ServiceCoordinate end, ServiceAncestry ancestry) {
        return new DriverService(ordinal, kind, completion, carried, begin, end, ancestry);
    }

    private record ServiceEvidence(List<Decision> decisions, NormalizedState state, List<ChipEvent> chips) { }

    private static Metadata testMetadata(String schema, String profileId, CompleteRunFixture fixture,
            ProducerKind producerKind, ProducerRuntimeIdentity runtime, ObserverRuntimeIdentity observer,
            ObserverProof proof, ChunkPolicy chunks, List<HardwareRole> roles, StateInventory stateInventory) {
        return testMetadata(schema, profileId, fixture, producerKind, runtime, observer, proof, chunks, roles,
                stateInventory, ComparisonLayerInventory.allCompared(), ProducerObservationInventory.allObserved());
    }

    private static Metadata testMetadata(String schema, String profileId, CompleteRunFixture fixture,
            ProducerKind producerKind, ProducerRuntimeIdentity runtime, ObserverRuntimeIdentity observer,
            ObserverProof proof, ChunkPolicy chunks, List<HardwareRole> roles, StateInventory stateInventory,
            ComparisonLayerInventory comparisons, ProducerObservationInventory observations) {
        return new Metadata(schema, profileId, fixture, producerKind, runtime, observer, proof, chunks, roles,
                stateInventory, comparisons, observations);
    }

    private static final class TestProfile implements CompleteRunAudioProfile {
        private final String id;
        private final CompleteRunFixture fixture;
        private final List<HardwareRole> roles;
        private final List<String> globalFields;
        private final List<String> activeRoleFields;
        private final Map<RawAudioRequest, NativeSoundIdentity> identities = new LinkedHashMap<>();
        private final Map<ProducerKind, ProducerRuntimeIdentity> producerIdentities = new LinkedHashMap<>();
        private final Map<ProducerKind, ProducerBinding> bindings = new LinkedHashMap<>();
        private final Map<ProducerKind, ObserverProof> observerProofs = new LinkedHashMap<>();
        private final Map<ProducerKind, ObserverRuntimeIdentity> observerRuntimeIdentities = new LinkedHashMap<>();

        private TestProfile(String id, CompleteRunFixture fixture, List<HardwareRole> roles,
                List<String> globalFields, List<String> activeRoleFields) {
            this.id = id;
            this.fixture = fixture;
            this.roles = roles;
            this.globalFields = globalFields;
            this.activeRoleFields = activeRoleFields;
            identities.put(new RawAudioRequest(OwnerClass.SFX, 0xC0, "mailbox", 0),
                    new NativeSoundIdentity(OwnerClass.SFX, "sfx.explosion", 0xC0));
            producerIdentities.put(ProducerKind.REFERENCE, new ProducerRuntimeIdentity(
                    "BizHawk", "2.11", "BizHawk", "2.11", "GPGX", "1.0",
                    ManagedObserverAdapter.CALLBACK_ONLY,
                    Map.of(RuntimeArtifact.BIZHAWK_EXECUTABLE, "1".repeat(64),
                            RuntimeArtifact.BIZHAWK_CORE_DLL, "2".repeat(64),
                            RuntimeArtifact.GPGX_CORE, "3".repeat(64))));
            producerIdentities.put(ProducerKind.OPENGGF, new ProducerRuntimeIdentity(
                    "OpenGGF", "0.6", "OpenGGF", "0.6", "SMPS", "1",
                    ManagedObserverAdapter.CALLBACK_ONLY,
                    Map.of(RuntimeArtifact.OPENGGF_PRODUCER, "4".repeat(64))));
            producerIdentities.forEach((kind, identity) -> bindings.put(kind,
                    new PinnedProducerBinding(identity)));
            observerRuntimeIdentities.put(ProducerKind.REFERENCE,
                    new CallbackObserverIdentity("bizhawk-s1-callback.v1"));
            observerRuntimeIdentities.put(ProducerKind.OPENGGF,
                    new CallbackObserverIdentity("openggf.callback.v1"));
            observerProofs.put(ProducerKind.REFERENCE,
                    new ObserverProof("reference.observer.v1", "m68k.execute",
                            List.of(new CallbackProof("driver.service", 1))));
            observerProofs.put(ProducerKind.OPENGGF,
                    new ObserverProof("test.observer.v1", "m68k.execute",
                            List.of(new CallbackProof("driver.service", 1))));
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public CompleteRunFixture fixture() {
            return fixture;
        }

        @Override
        public List<HardwareRole> hardwareRoles() {
            return roles;
        }

        @Override
        public StateInventory stateInventory() {
            return new StateInventory(globalFields, activeRoleFields);
        }

        @Override public ComparisonLayerInventory comparisonLayerInventory() {
            return ComparisonLayerInventory.allCompared();
        }

        @Override public Map<ProducerKind, ProducerObservationInventory> producerObservationInventories() {
            var all = ProducerObservationInventory.allObserved();
            return Map.of(ProducerKind.REFERENCE, all, ProducerKind.OPENGGF, all);
        }

        @Override
        public Map<RawAudioRequest, NativeSoundIdentity> nativeSoundIdentities() {
            return identities;
        }

        @Override
        public Map<ProducerKind, ProducerRuntimeIdentity> producerRuntimeIdentities() {
            return producerIdentities;
        }

        @Override
        public Map<ProducerKind, ProducerBinding> producerBindings() {
            return bindings;
        }

        @Override
        public Map<ProducerKind, ObserverProof> observerProofs() {
            return observerProofs;
        }

        @Override
        public Map<ProducerKind, ObserverRuntimeIdentity> observerRuntimeIdentities() {
            return observerRuntimeIdentities;
        }

        @Override
        public CutoffFrontierPolicy cutoffFrontierPolicy() {
            return new CutoffFrontierPolicy(List.of(), 0, 0, 0, 0, 0, 0, 0, 0, false,
                    "f".repeat(64), CutoffFrontierPolicy.capabilityDigest(CutoffFrontier.empty(
                            new NormalizedState(List.of(), List.of()))), null);
        }

        @Override
        public Map<ProducerKind, NativeCapabilitySummary> completeRunCapabilities() {
            return Map.of();
        }

        @Override
        public Map<NativeSoundIdentity, List<NativeSoundIdentity>> decisionResolutions() {
            NativeSoundIdentity identity = new NativeSoundIdentity(OwnerClass.SFX, "sfx.explosion", 0xC0);
            return Map.of(identity, List.of(identity));
        }

        @Override
        public List<RoleOwner> baselineRoleOwners() {
            OwnerRef none = new OwnerRef(OwnerClass.NONE, "none", 0, OwnerOrigin.NONE, -1);
            return roles.stream().map(role -> new RoleOwner(role, none)).toList();
        }

        @Override
        public Map<String, OwnershipTransition> ownershipTransitions() {
            return Map.of("accepted", OwnershipTransition.ACQUIRE_REQUEST,
                    "rejected", OwnershipTransition.REJECT_PRESERVE);
        }

        @Override
        public PendingRequestPolicy pendingRequestPolicy() {
            return new PendingRequestPolicy(4, 0, null);
        }

        @Override
        public RestoreStackPolicy restoreStackPolicy() {
            return new RestoreStackPolicy(0, List.of(), null);
        }

        @Override
        public Map<String, LifecycleRule> lifecycleRules() {
            return Map.of("pulse", new LifecycleRule("pulse", List.of("payload"),
                    LifecycleOwnershipAction.NONE));
        }
    }
}
