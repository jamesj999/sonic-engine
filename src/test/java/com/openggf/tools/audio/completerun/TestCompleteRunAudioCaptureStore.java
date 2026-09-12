package com.openggf.tools.audio.completerun;

import org.junit.jupiter.api.Tag;
import static com.openggf.tools.audio.completerun.CompleteRunAudioTrace.*;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.StringReader;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.IdentityHashMap;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPOutputStream;
import java.util.zip.GZIPInputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

// Minutes-long oracle sweep: excluded from the -Psmoke fast lane, still run by
// the default suite on pull requests, the nightly schedule and release validation.
@Tag("slow-suite")
class TestCompleteRunAudioCaptureStore {
    private static final Map<DriverService, ServiceEvidence> SERVICE_EVIDENCE = new IdentityHashMap<>();
    @TempDir
    Path temp;

    private final CompleteRunAudioCaptureStore store = new CompleteRunAudioCaptureStore();

    @Test
    void boundedLinesRetainReadAheadAndHandleBufferEdges() throws Exception {
        for (int length : new int[] {0, 1, 8191, 8192, 8193, 16384}) {
            String first = "x".repeat(length);
            try (var lines = new CompleteRunAudioCaptureStore.BoundedLines(
                    new StringReader(first + "\n\nlast"))) {
                assertEquals(first, lines.readLine());
                assertEquals("", lines.readLine());
                assertEquals("last", lines.readLine());
                assertNull(lines.readLine());
                assertNull(lines.readLine());
            }
            try (var lines = new CompleteRunAudioCaptureStore.BoundedLines(
                    new StringReader(first + "\r\n"))) {
                assertEquals("capture records require LF line endings",
                        assertThrows(IllegalArgumentException.class, lines::readLine).getMessage());
            }
        }
    }

    @Test
    void boundedLinesPreserveExactLimitAndErrorPrecedence() throws Exception {
        String maximum = "x".repeat(16 * 1024 * 1024);
        try (var lines = new CompleteRunAudioCaptureStore.BoundedLines(
                new StringReader(maximum + "\nnext\n"))) {
            assertEquals(maximum, lines.readLine());
            assertEquals("next", lines.readLine());
            assertNull(lines.readLine());
        }
        for (String suffix : new String[] {"x", "\r", "x\r"}) {
            try (var lines = new CompleteRunAudioCaptureStore.BoundedLines(
                    new StringReader(maximum + suffix))) {
                assertEquals(suffix.equals("\r")
                                ? "capture records require LF line endings"
                                : "capture record exceeds its byte bound",
                        assertThrows(IllegalArgumentException.class, lines::readLine).getMessage());
            }
        }
    }

    @Test
    void duplicateCapturesHaveIdenticalBytesAndFixedFrameChunking() throws Exception {
        Path first = temp.resolve("first");
        Path second = temp.resolve("second");
        List<CompleteRunAudioTrace.Record> records = records(4_097);

        store.writeNew(first, metadata(4_097), records.iterator());
        store.writeNew(second, metadata(4_097), records.iterator());

        assertArrayEquals(Files.readAllBytes(first.resolve("manifest.json")),
                Files.readAllBytes(second.resolve("manifest.json")));
        assertArrayEquals(Files.readAllBytes(first.resolve("chunks/000000.jsonl.gz")),
                Files.readAllBytes(second.resolve("chunks/000000.jsonl.gz")));
        assertArrayEquals(Files.readAllBytes(first.resolve("chunks/000001.jsonl.gz")),
                Files.readAllBytes(second.resolve("chunks/000001.jsonl.gz")));
        assertEquals(0, Files.readAllBytes(first.resolve("chunks/000000.jsonl.gz"))[4]);
        assertEquals(0, Files.readAllBytes(first.resolve("chunks/000000.jsonl.gz"))[5]);
        assertEquals(0, Files.readAllBytes(first.resolve("chunks/000000.jsonl.gz"))[6]);
        assertEquals(0, Files.readAllBytes(first.resolve("chunks/000000.jsonl.gz"))[7]);
    }

    @Test
    void failedPublicationNeverReplacesExistingCapture() throws Exception {
        Path output = temp.resolve("capture");
        Files.createDirectory(output);
        Files.writeString(output.resolve("sentinel"), "keep");

        assertThrows(java.nio.file.FileAlreadyExistsException.class,
                () -> store.writeNew(output, metadata(1), records(1).iterator()));

        assertEquals("keep", Files.readString(output.resolve("sentinel")));
    }

    @Test
    void readerRejectsUndeclaredEntriesAndLinkedChunks() throws Exception {
        Path output = temp.resolve("strict-tree");
        store.writeNew(output, metadata(1), records(1).iterator());
        Path real = output.toRealPath();

        Path topExtra = Files.writeString(real.resolve("extra"), "x");
        assertThrows(IllegalArgumentException.class, () -> store.read(output));
        Files.delete(topExtra);
        Path chunkExtra = Files.writeString(real.resolve("chunks/extra"), "x");
        assertThrows(IllegalArgumentException.class, () -> store.read(output));
        Files.delete(chunkExtra);

        Path chunk = real.resolve("chunks/000000.jsonl.gz");
        Path external = Files.copy(chunk, temp.resolve("external.gz"));
        Files.delete(chunk);
        Files.createSymbolicLink(chunk, external);
        assertThrows(IllegalArgumentException.class, () -> store.read(output));
        Files.delete(chunk);
        Files.copy(external, chunk);
        Path hardlink = real.resolve("chunks/hardlink.gz");
        Files.createLink(hardlink, chunk);
        assertThrows(IllegalArgumentException.class, () -> store.read(output));
    }

    @Test
    void rejectsCaptureWithoutTerminalAndCleansItsStagingDirectory() throws Exception {
        Path output = temp.resolve("capture");
        List<CompleteRunAudioTrace.Record> incomplete = new ArrayList<>(records(1));
        incomplete.removeLast();

        assertThrows(IllegalArgumentException.class,
                () -> store.writeNew(output, metadata(1), incomplete.iterator()));

        assertEquals(false, Files.exists(output));
        try (var children = Files.list(temp)) {
            assertEquals(List.of(), children.map(path -> path.getFileName().toString())
                    .filter(name -> name.startsWith(".audio-staging-")).toList());
        }
    }

    @Test
    void rejectsInvalidMetadataBeforeCreatingAStagingDirectory() throws Exception {
        Path output = temp.resolve("oversized");

        assertThrows(IllegalArgumentException.class,
                () -> store.writeNew(output, metadata(128 * CHUNK_FRAME_ROWS + 1)));
        assertThrows(NullPointerException.class, () -> store.writeNew(output, null));

        assertEquals(false, Files.exists(output));
        try (var children = Files.list(temp)) {
            assertEquals(List.of(), children.map(path -> path.getFileName().toString())
                    .filter(name -> name.startsWith(".audio-staging-")).toList());
        }
    }

    @Test
    void requiresExactlyOneCutoffFrontierImmediatelyBeforeTerminal() {
        List<CompleteRunAudioTrace.Record> valid = records(1);
        List<CompleteRunAudioTrace.Record> missing = new ArrayList<>(valid);
        missing.remove(missing.size() - 2);
        assertThrows(IllegalArgumentException.class,
                () -> store.writeNew(temp.resolve("missing-frontier"), metadata(1), missing.iterator()));

        List<CompleteRunAudioTrace.Record> duplicate = new ArrayList<>(valid);
        duplicate.add(duplicate.size() - 1, duplicate.get(duplicate.size() - 2));
        assertThrows(IllegalArgumentException.class,
                () -> store.writeNew(temp.resolve("duplicate-frontier"), metadata(1), duplicate.iterator()));

        List<CompleteRunAudioTrace.Record> early = new ArrayList<>(valid);
        CompleteRunAudioTrace.Record frontier = early.remove(early.size() - 2);
        early.add(1, frontier);
        assertThrows(IllegalArgumentException.class,
                () -> store.writeNew(temp.resolve("early-frontier"), metadata(1), early.iterator()));
    }

    @Test
    void iteratorFailureCleansItsStagingDirectory() throws Exception {
        Path output = temp.resolve("capture");
        Iterator<CompleteRunAudioTrace.Record> failing = new Iterator<>() {
            @Override public boolean hasNext() { return true; }
            @Override public CompleteRunAudioTrace.Record next() { throw new IllegalStateException("producer failed"); }
        };

        assertThrows(IllegalStateException.class, () -> store.writeNew(output, metadata(1), failing));

        assertEquals(false, Files.exists(output));
        try (var children = Files.list(temp)) {
            assertEquals(List.of(), children.map(path -> path.getFileName().toString())
                    .filter(name -> name.startsWith(".audio-staging-")).toList());
        }
    }

    @Test
    void readerStreamsPublishedRecordsAndRejectsTamperedChunkDigest() throws Exception {
        Path output = temp.resolve("capture");
        store.writeNew(output, metadata(1), records(1).iterator());

        try (CompleteRunAudioCaptureStore.Reader reader = store.read(output)) {
            List<CompleteRunAudioTrace.Record> actual = new ArrayList<>();
            while (reader.hasNext()) actual.add(reader.next());
            assertEquals(4, actual.size());
            assertEquals(metadata(1), reader.metadata());
        }

        byte[] tampered = Files.readAllBytes(output.resolve("chunks/000000.jsonl.gz"));
        tampered[tampered.length / 2] ^= 1;
        Files.write(output.resolve("chunks/000000.jsonl.gz"), tampered);
        try (CompleteRunAudioCaptureStore.Reader reader = store.read(output)) {
            assertThrows(IllegalArgumentException.class, () -> {
                while (reader.hasNext()) reader.next();
            });
        }
    }

    @Test
    void readerRejectsTamperedManifestRootDigestAfterStreamingAllRecords() throws Exception {
        Path output = temp.resolve("capture");
        store.writeNew(output, metadata(1), records(1).iterator());
        Path manifest = output.resolve("manifest.json");
        String original = Files.readString(manifest);
        String root = original.replaceFirst(".*\"root_digest\":\"([0-9a-f]{64})\".*", "$1");
        char replacement = root.charAt(0) == 'f' ? 'e' : 'f';
        Files.writeString(manifest, original.replace(root, replacement + root.substring(1)));

        try (CompleteRunAudioCaptureStore.Reader reader = store.read(output)) {
            assertThrows(IllegalArgumentException.class, () -> {
                while (reader.hasNext()) reader.next();
            });
        }
    }

    @Test
    void readerRejectsManifestWithDuplicateRuntimeArtifactKey() throws Exception {
        Path output = temp.resolve("capture");
        store.writeNew(output, metadata(1), records(1).iterator());
        Path manifest = output.resolve("manifest.json");
        String original = Files.readString(manifest);
        Files.writeString(manifest, original.replace(
                "\"OPENGGF_PRODUCER\":\"4444444444444444444444444444444444444444444444444444444444444444\"",
                "\"OPENGGF_PRODUCER\":\"4444444444444444444444444444444444444444444444444444444444444444\","
                        + "\"OPENGGF_PRODUCER\":\"5555555555555555555555555555555555555555555555555555555555555555\""));

        assertThrows(IllegalArgumentException.class, () -> store.read(output));
    }

    @Test
    void failedAtomicPublicationCleansOnlyStagingAndNeverFallsBackToReplacement() throws Exception {
        Path output = temp.resolve("capture");
        CompleteRunAudioCaptureStore failingStore = new CompleteRunAudioCaptureStore((source, target) -> {
            throw new AtomicMoveNotSupportedException(source.toString(), target.toString(), "test filesystem");
        });

        assertThrows(AtomicMoveNotSupportedException.class,
                () -> failingStore.writeNew(output, metadata(1), records(1).iterator()));

        assertEquals(false, Files.exists(output));
        try (var children = Files.list(temp)) {
            assertEquals(List.of(), children.map(path -> path.getFileName().toString())
                    .filter(name -> name.startsWith(".audio-staging-")).toList());
        }
    }

    @Test
    void competingDestinationCreatedImmediatelyBeforePublicationSurvives() throws Exception {
        Path output = temp.resolve("capture");
        CompleteRunAudioCaptureStore competingStore = new CompleteRunAudioCaptureStore((source, target) -> {
            Files.createDirectory(target);
            Files.writeString(target.resolve("sentinel"), "keep");
            throw new java.nio.file.FileAlreadyExistsException(target.toString());
        });

        assertThrows(java.nio.file.FileAlreadyExistsException.class,
                () -> competingStore.writeNew(output, metadata(1), records(1).iterator()));

        assertEquals("keep", Files.readString(output.resolve("sentinel")));
        try (var children = Files.list(temp)) {
            assertEquals(List.of(), children.map(path -> path.getFileName().toString())
                    .filter(name -> name.startsWith(".audio-staging-") || name.startsWith(".audio-published-"))
                    .toList());
        }
    }

    @Test
    void cleanupFailureIsSuppressedOnThePrimaryPublicationFailure() throws Exception {
        Path output = temp.resolve("capture");
        IOException cleanupFailure = new IOException("injected staging cleanup failure");
        CompleteRunAudioCaptureStore failingStore = new CompleteRunAudioCaptureStore(
                (source, target) -> { throw new AtomicMoveNotSupportedException(source.toString(), target.toString(), "test"); },
                ignored -> { throw cleanupFailure; });

        AtomicMoveNotSupportedException primary = assertThrows(AtomicMoveNotSupportedException.class,
                () -> failingStore.writeNew(output, metadata(1), records(1).iterator()));

        assertEquals(List.of(cleanupFailure), List.of(primary.getSuppressed()));
        assertEquals(false, Files.exists(output));
    }

    @Test
    void readerRoundTripsRequestsServicesDecisionsChipEventsAndLifecycle() throws Exception {
        Path output = temp.resolve("rich-capture");
        List<CompleteRunAudioTrace.Record> records = richRecords();
        store.writeNew(output, metadata(1), records.iterator());

        try (CompleteRunAudioCaptureStore.Reader reader = store.read(output)) {
            List<CompleteRunAudioTrace.Record> actual = new ArrayList<>();
            while (reader.hasNext()) actual.add(reader.next());
            assertEquals(records.get(0), actual.get(0));
            assertEquals(records.get(1), actual.get(1));
            assertEquals(records.get(2), actual.get(2));
            assertEquals(records.get(3), actual.get(3));
        }
    }

    @Test
    void deferredConsumeChangesRawStorageRootButNotSemanticRoot() {
        NativeDeferredServiceBegin first = new NativeDeferredServiceBegin(
                13, 0, 6, 0, 4, 77, 2, 0x71b4c,
                40, 41, 12, 13, 2, true, 14, 42);
        NativeDeferredServiceBegin changed = new NativeDeferredServiceBegin(
                13, 0, 6, 0, 4, 77, 2, 0x71b4c,
                40, 41, 12, 13, 2, true, 15, 43);
        Frame left = fullFrame(860, "test", false, List.of(), List.of(), List.of(),
                new FrameNativeDiagnostics(List.of(), List.of(), List.of(), List.of(), List.of(),
                        List.of(first), List.of()));
        Frame right = fullFrame(860, "test", false, List.of(), List.of(), List.of(),
                new FrameNativeDiagnostics(List.of(), List.of(), List.of(), List.of(), List.of(),
                        List.of(changed), List.of()));

        assertNotEquals(root(List.of(left)), root(List.of(right)));
        assertEquals(semanticRoot(List.of(left)), semanticRoot(List.of(right)));
    }

    @Test
    void attestedDeferredCurrentOwnerIdentityChangesOnlyTheRawStorageRoot() {
        NormalizedState state = new NormalizedState(List.of(), List.of());
        NativeDeferredServiceBegin origin = new NativeDeferredServiceBegin(
                13, 0, 6, 0, 4, 77, 2, 0x71b4c,
                40, 41, 12, 13, 2, false, 0, 0);
        FrontierService firstOwner = new FrontierService(20, 0, 0, "dpcm",
                FrontierServiceState.OPEN, 859, 6, 0x77, 11, "Z80",
                null, null, null, null, List.of(), List.of());
        List<FrontierService> changedOwners = List.of(
                new FrontierService(21, 0, 0, "dpcm",
                        FrontierServiceState.OPEN, 859, 6, 0x77, 11, "Z80",
                        null, null, null, null, List.of(), List.of()),
                new FrontierService(20, 0, 0, "dpcm",
                        FrontierServiceState.OPEN, 859, 6, 0x77, 12, "Z80",
                        null, null, null, null, List.of(), List.of()),
                new FrontierService(20, 0, 0, "dpcm",
                        FrontierServiceState.OPEN, 859, 6, 0x79, 11, "Z80",
                        null, null, null, null, List.of(), List.of()),
                new FrontierService(20, 0, 0, "dpcm",
                        FrontierServiceState.OPEN, 859, 6, 0x77, 11, "M68K",
                        null, null, null, null, List.of(), List.of()));
        CutoffFrontier firstProjection = CutoffFrontier.fromNative(List.of(firstOwner), List.of(),
                List.of(), List.of(), 0, 0, 0, false, state, "f".repeat(64));
        CutoffFrontier first = new CutoffFrontier(firstProjection.activeStack(), List.of(), List.of(),
                new CutoffNativeDiagnostics(List.of(firstOwner), List.of(), List.of(), List.of(),
                        origin, 0, false, "f".repeat(64)), 0, 0, state);

        for (FrontierService changedOwner : changedOwners) {
            CutoffFrontier changedProjection = CutoffFrontier.fromNative(
                    List.of(changedOwner), List.of(), List.of(), List.of(),
                    0, 0, 0, false, state, "f".repeat(64));
            CutoffFrontier changed = new CutoffFrontier(changedProjection.activeStack(),
                    List.of(), List.of(),
                    new CutoffNativeDiagnostics(List.of(changedOwner), List.of(), List.of(),
                            List.of(), origin, 0, false, "f".repeat(64)), 0, 0, state);
            assertNotEquals(root(List.of(first)), root(List.of(changed)));
            assertEquals(semanticRoot(List.of(first)), semanticRoot(List.of(changed)));
        }

        NativeDeferredServiceBegin changedOrigin = new NativeDeferredServiceBegin(
                13, 0, 6, 0, 4, 78, 2, 0x71b4c,
                40, 41, 12, 13, 2, false, 0, 0);
        CutoffFrontier originChanged = new CutoffFrontier(firstProjection.activeStack(),
                List.of(), List.of(),
                new CutoffNativeDiagnostics(List.of(firstOwner), List.of(), List.of(), List.of(),
                        changedOrigin, 0, false, "f".repeat(64)), 0, 0, state);
        assertNotEquals(root(List.of(first)), root(List.of(originChanged)));
        assertEquals(semanticRoot(List.of(first)), semanticRoot(List.of(originChanged)));
    }

    @Test
    void strictRecordCodecRejectsUnknownDuplicateWrongTypedAndTrailingNestedJson() throws Exception {
        String json = CompleteRunAudioJson.writeRecord(richRecords().get(2));

        assertThrows(IllegalArgumentException.class,
                () -> CompleteRunAudioJson.readRecord(json.replace("\"nativeId\":192", "\"nativeId\":192,\"unknown\":0")));
        assertThrows(IllegalArgumentException.class,
                () -> CompleteRunAudioJson.readRecord(json.replace("\"nativeId\":192", "\"nativeId\":192,\"nativeId\":192")));
        assertThrows(IllegalArgumentException.class,
                () -> CompleteRunAudioJson.readRecord(json.replace("\"nativeId\":192", "\"nativeId\":256")));
        assertThrows(IllegalArgumentException.class,
                () -> CompleteRunAudioJson.readRecord(
                        json.replace(",\"carriedBoundaryOrdinal\":null", "")));
        assertThrows(IllegalArgumentException.class,
                () -> CompleteRunAudioJson.readRecord(json + " {}"));
    }

    @Test
    void handAuthoredCanonicalBaselineVectorHasItsPinnedBytesAndDigest() throws Exception {
        String canonical = "{\"type\":\"baseline\",\"value\":{\"absoluteFrame\":860,\"state\":{\"fields\":[{\"name\":\"tempo\",\"value\":1}],\"roles\":[{\"role\":\"FM1\",\"active\":false,\"fields\":[]}]},\"roleOwners\":[{\"role\":\"FM1\",\"owner\":{\"ownerClass\":\"NONE\",\"contentKey\":\"none\",\"nativeId\":0,\"origin\":\"NONE\",\"originOrdinal\":-1}}],\"frontier\":{\"activeStack\":[],\"pendingDescendants\":[],\"rawChipEvents\":[],\"nativeDiagnostics\":null,\"ymPort0Latch\":0,\"ymPort1Latch\":0}}}";
        Baseline baseline = baseline(new NormalizedState(List.of(new StateField("tempo", 1)),
                List.of(new RoleState(HardwareRole.FM1, false, List.of()))));

        assertEquals(canonical, CompleteRunAudioJson.writeRecord(baseline));
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        assertEquals(canonical.replace("\"nativeDiagnostics\":null,", ""),
                CompleteRunAudioJson.writeSemanticRecord(baseline));
        assertThrows(IllegalArgumentException.class, () -> CompleteRunAudioJson.readRecord(
                canonical.replace(",\"frontier\":{\"activeStack\":[],\"pendingDescendants\":[],"
                        + "\"rawChipEvents\":[],\"nativeDiagnostics\":null,\"ymPort0Latch\":0,"
                        + "\"ymPort1Latch\":0}", "")));
        assertEquals(64, HexFormat.of().formatHex(
                digest.digest((canonical + "\n").getBytes(StandardCharsets.UTF_8))).length());
    }

    @Test
    void handAuthoredCanonicalLifecycleOwnershipVectorPinsItsExactSchema() throws Exception {
        String canonical = "{\"type\":\"lifecycle\",\"value\":{\"ordinal\":0,\"absoluteFrame\":860,\"kind\":\"save\",\"details\":{\"slot\":1},\"ownershipTransitions\":[{\"role\":\"FM1\",\"displacedOwner\":{\"ownerClass\":\"MUSIC\",\"contentKey\":\"music.81\",\"nativeId\":129,\"origin\":\"BASELINE\",\"originOrdinal\":0},\"finalOwner\":{\"ownerClass\":\"MUSIC\",\"contentKey\":\"music.81\",\"nativeId\":129,\"origin\":\"BASELINE\",\"originOrdinal\":0}}]}}";
        OwnerRef music = new OwnerRef(OwnerClass.MUSIC, "music.81", 0x81,
                OwnerOrigin.BASELINE, 0);
        Lifecycle lifecycle = new Lifecycle(0, 860, "save", Map.of("slot", 1),
                List.of(new LifecycleOwnership(HardwareRole.FM1, music, music)));

        assertEquals(canonical, CompleteRunAudioJson.writeRecord(lifecycle));
        assertEquals(lifecycle, CompleteRunAudioJson.readRecord(canonical));
    }

    @Test
    void standardGzipNoNameVectorPinsMtimeHeaderAndCompressedBytes() throws Exception {
        // RFC 1952: ID1/ID2/CM/FLG + zero MTIME + XFL=0 + OS=255, then raw DEFLATE of abc\n.
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(bytes)) {
            gzip.write("abc\n".getBytes(StandardCharsets.US_ASCII));
        }
        byte[] expected = HexFormat.of().parseHex("1f8b08000000000000ff4b4c4ae602004e81884704000000");
        assertArrayEquals(expected, bytes.toByteArray());
        assertEquals("01f016583b2723fb8f04a590e4ba5528e0da39376471fc22f167f7b9d4cd7998",
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray())));
    }

    @Test
    void handAuthoredStoreCaptureVectorPinsRecordsGzipChunkAndManifest() throws Exception {
        Path output = temp.resolve("vector-capture");
        store.writeNew(output, metadata(1), records(1).iterator());
        // Generated once with RFC 1952/JDK 21 gzip and this literal JSON fixture, then pasted.
        String canonical = """
                {"type":"baseline","value":{"absoluteFrame":860,"state":{"fields":[{"name":"tempo","value":1}],"roles":[{"role":"FM1","active":false,"fields":[]}]},"roleOwners":[{"role":"FM1","owner":{"ownerClass":"NONE","contentKey":"none","nativeId":0,"origin":"NONE","originOrdinal":-1}}]}}
                {"type":"frame","value":{"absoluteFrame":860,"segment":"test","lag":false,"requests":[],"services":[]}}
                {"type":"terminal","value":{"exclusiveEnd":861,"frameCount":1,"requestCount":0,"serviceCount":0,"decisionCount":0,"ymCount":0,"psgCount":0,"lifecycleCount":0,"rootDigest":"b1e8d623113c86c6661b86981adb17bf85608472f5adeb369e9c1c3343d4cde9"}}
                """;
        String gzip = "1f8b08000000000000ff8552416ec32010bcf7199c5d29d409b17d4d13a9aa9a3ca0ca01c3da42c290024e6b59fe7b17dcc63954ea8d9d9ddd99593192305c8054a4e61eb432403272e5ba476824bcf656f7010e8e7708146c95111f7848cd4681969e54ef2331a94d027417bbccd3e99c116735cca4f842d2e18d22858ba0ae58365c7bc8965de7e93ccd43a74f03ee8f491bf1a89f1e3bcd3d92c8f174dc63535813c0845718103336a5313c4abd4852a17beb54abcc3230d7272795e19a548f744203d3c3f87b952625ffef24d076a89a2ee003b2356f6fd11c7cf488a67091eaae4ac01cf54e2780eb92853b29f812baf7e87d6f6454a2d96c6767fb28466fbb7f80d56dfd024810ca2b6b1664e896f7c5b74ba155036210fa6eda591b9e551b43e107a15048f694539a8b8209c618ad0b561694cb9a6eeba6d8b055b1de3e351b2ea1ce5909a5a022cfd7b95c0b0925c1bcdf3fc0b10e6e020000";
        String manifest = """
                {"schema":"complete_run_audio.v1","metadata":{"schema":"complete_run_audio.v1","profileId":"store.test.1","fixture":{"romSha1":"0000000000000000000000000000000000000000","romCrc32":"11111111","bk2Sha256":"2222222222222222222222222222222222222222222222222222222222222222","bk2RowCount":861,"runManifestSha256":"3333333333333333333333333333333333333333333333333333333333333333","segments":[{"id":"test","firstFrame":860,"exclusiveEnd":861}],"firstFrame":860,"exclusiveEnd":861},"producerKind":"OPENGGF","producerRuntimeIdentity":{"producerName":"OpenGGF","producerVersion":"test","emulatorName":"OpenGGF","emulatorVersion":"test","coreName":"SMPS","coreVersion":"test","observerAdapter":"CALLBACK_ONLY","artifactSha256":{"OPENGGF_PRODUCER":"4444444444444444444444444444444444444444444444444444444444444444"}},"observerRuntimeIdentity":{"kind":"CALLBACK","id":"openggf.store.callback.v1"},"observerProof":{"observerProfile":"test","callbackSource":"test","callbacks":[{"callback":"service","observations":1}]},"chunkPolicy":{"frameRows":4096,"compression":"gzip","gzipTimestamp":0},"hardwareRoles":["FM1"],"stateInventory":{"globalFields":["tempo"],"activeRoleFields":["cursor"]}},"chunks":[{"file":"000000.jsonl.gz","frame_rows":1,"first_frame":860,"exclusive_end":861,"compressed_sha256":"332dc171a308bea5b321e61bb194206ca1f1d105612e93e3d6a7416d98860932","uncompressed_sha256":"6a7620780a2777027e612bb7c9791541511a45bff6e77f06febe9f0260b19da2"}],"root_digest":"b1e8d623113c86c6661b86981adb17bf85608472f5adeb369e9c1c3343d4cde9"}""";
        byte[] actual = Files.readAllBytes(output.resolve("chunks/000000.jsonl.gz"));
        try (var input = new GZIPInputStream(new java.io.ByteArrayInputStream(actual))) {
            String expected = records(1).stream().map(record -> {
                try { return CompleteRunAudioJson.writeRecord(record); }
                catch (IOException failure) { throw new AssertionError(failure); }
            }).reduce("", (left, right) -> left + right + "\n");
            assertEquals(expected, new String(input.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    @Test
    void readerUsesBoundedMemoryForTwentyThousandFrameCapture() throws Exception {
        Path output = temp.resolve("large-capture");
        store.writeNew(output, metadata(20_000), records(20_000).iterator());
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        Process process = new ProcessBuilder(java, "-Xmx16m", "-cp", System.getProperty("java.class.path"),
                TestCompleteRunAudioCaptureStore.class.getName(), "read-probe", output.toString())
                .redirectErrorStream(true).start();
        int status = process.waitFor();
        assertEquals(0, status, new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
    }

    @Test
    void readerStreamsOneHundredTwentyEightHighEntropyChunksWithinSixteenMiB() throws Exception {
        int frames = CompleteRunAudioCaptureStore.MAX_CAPTURE_CHUNKS * CHUNK_FRAME_ROWS;
        Path output = temp.resolve("hostile-capture");
        store.writeNew(output, metadata(frames), hostileRecords(frames));
        long compressedBytes;
        try (var chunks = Files.list(output.resolve("chunks"))) {
            compressedBytes = chunks.mapToLong(path -> {
                try { return Files.size(path); } catch (IOException failure) { throw new java.io.UncheckedIOException(failure); }
            }).sum();
        }
        assertTrue(compressedBytes > 32L * 1024 * 1024,
                () -> "hostile compressed payload was only " + compressedBytes + " bytes");
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        Process process = new ProcessBuilder(java, "-Xmx16m", "-cp", System.getProperty("java.class.path"),
                TestCompleteRunAudioCaptureStore.class.getName(), "hostile-read-probe", output.toString())
                .redirectErrorStream(true).start();
        int status = process.waitFor();
        assertEquals(0, status, new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 2 || !("read-probe".equals(args[0]) || "hostile-read-probe".equals(args[0]))) throw new IllegalArgumentException("read-probe <capture>");
        long count = 0;
        try (CompleteRunAudioCaptureStore.Reader reader = new CompleteRunAudioCaptureStore().read(Path.of(args[1]))) {
            while (reader.hasNext()) {
                reader.next();
                count++;
            }
        }
        long expected = "hostile-read-probe".equals(args[0])
                ? (long) CompleteRunAudioCaptureStore.MAX_CAPTURE_CHUNKS * CHUNK_FRAME_ROWS + 3
                : 20_003;
        if (count != expected) throw new IllegalStateException("unexpected record count: " + count);
    }

    static Metadata metadata(int frames) {
        int end = 860 + frames;
        CompleteRunFixture fixture = new CompleteRunFixture("0".repeat(40), "1".repeat(8), "2".repeat(64),
                end, "3".repeat(64), List.of(new ManifestSegment("test", 860, end)), 860, end);
        return testMetadata(SCHEMA, "store.test." + frames, fixture, ProducerKind.OPENGGF,
                new ProducerRuntimeIdentity("OpenGGF", "test", "OpenGGF", "test", "SMPS", "test",
                        Map.of(RuntimeArtifact.OPENGGF_PRODUCER, "4".repeat(64))),
                new CallbackObserverIdentity("openggf.store.callback.v1"),
                new ObserverProof("test", "test", List.of(new CallbackProof("service", 1))),
                new ChunkPolicy(4096, "gzip", 0), List.of(HardwareRole.FM1),
                new StateInventory(List.of("tempo"), List.of("cursor")));
    }

    private static Metadata testMetadata(String schema, String profileId, CompleteRunFixture fixture,
            ProducerKind producerKind, ProducerRuntimeIdentity runtime, ObserverRuntimeIdentity observer,
            ObserverProof proof, ChunkPolicy chunks, List<HardwareRole> roles, StateInventory stateInventory) {
        return new Metadata(schema, profileId, fixture, producerKind, runtime, observer, proof, chunks, roles,
                stateInventory, ComparisonLayerInventory.allCompared(),
                ProducerObservationInventory.allObserved());
    }

    static List<CompleteRunAudioTrace.Record> records(int frames) {
        List<CompleteRunAudioTrace.Record> records = new ArrayList<>();
        NormalizedState state = new NormalizedState(List.of(new StateField("tempo", 1)),
                List.of(new RoleState(HardwareRole.FM1, false, List.of())));
        records.add(baseline(state));
        for (int index = 0; index < frames; index++) {
            records.add(fullFrame(860 + index, "test", false, List.of(), List.of()));
        }
        records.add(frontier(state));
        records.add(new Terminal(860 + frames, frames, 0, 0, 0, 0, 0, 0, 0, 0,
                root(records), semanticRoot(records)));
        return records;
    }

    private static List<CompleteRunAudioTrace.Record> richRecords() {
        NormalizedState state = new NormalizedState(List.of(new StateField("tempo", 1)),
                List.of(new RoleState(HardwareRole.FM1, true, List.of(new StateField("cursor", 4)))));
        Request request = new Request(0, OwnerClass.SFX, "sfx.explosion", 0xc0, "mailbox", 0);
        OwnerRef none = noneOwner();
        OwnerRef owner = new OwnerRef(OwnerClass.SFX, "sfx.explosion", 0xc0,
                OwnerOrigin.REQUEST, 0);
        Decision decision = new Decision(0, 0xc0, "sfx.explosion", true, "accepted", 1, 2,
                List.of(HardwareRole.FM1), List.of(new RoleDecision(HardwareRole.FM1, none, owner)));
        DriverService service = testService(0, "driver", ServiceCompletion.COMPLETED,
                List.of(decision), state,
                List.of(new YmWrite(0, 0, 0x22, 0x33), new PsgWrite(1, 0x44)));
        List<CompleteRunAudioTrace.Record> records = new ArrayList<>();
        records.add(baseline(state));
        records.add(new Lifecycle(0, 860, "reset", Map.of("reason", "test"), List.of()));
        records.add(fullFrame(860, "test", false, List.of(request), List.of(service)));
        records.add(frontier(state));
        records.add(new Terminal(861, 1, 1, 1, 1, 1, 1, 1, 0, 0,
                root(records), semanticRoot(records)));
        return records;
    }

    /** Emits the same high-entropy stream twice; neither pass retains capture rows. */
    private static Iterator<CompleteRunAudioTrace.Record> hostileRecords(int frames) {
        NormalizedState baselineState = new NormalizedState(List.of(new StateField("tempo", 1)),
                List.of(new RoleState(HardwareRole.FM1, false, List.of())));
        String digest = hostileRoot(frames, baselineState, false);
        String semanticDigest = hostileRoot(frames, baselineState, true);
        return new Iterator<>() {
            private int cursor = -1;
            @Override public boolean hasNext() { return cursor <= frames + 1; }
            @Override public CompleteRunAudioTrace.Record next() {
                if (!hasNext()) throw new java.util.NoSuchElementException();
                if (cursor++ == -1) return baseline(baselineState);
                int row = cursor - 1;
                if (row == frames) return frontier(baselineState);
                if (row == frames + 1) return new Terminal(860 + frames, frames, frames / 64, frames / 64,
                        frames / 64, frames / 64, frames / 64, 0, 0, 0, digest, semanticDigest);
                return hostileFrame(row);
            }
        };
    }

    private static String hostileRoot(int frames, NormalizedState baselineState, boolean semantic) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(((semantic ? CompleteRunAudioJson.writeSemanticRecord(baseline(baselineState))
                    : CompleteRunAudioJson.writeRecord(baseline(baselineState))) + "\n")
                    .getBytes(StandardCharsets.UTF_8));
            for (int row = 0; row < frames; row++) {
                digest.update(((semantic ? CompleteRunAudioJson.writeSemanticRecord(hostileFrame(row))
                        : CompleteRunAudioJson.writeRecord(hostileFrame(row))) + "\n")
                        .getBytes(StandardCharsets.UTF_8));
            }
            digest.update(((semantic ? CompleteRunAudioJson.writeSemanticRecord(frontier(baselineState))
                    : CompleteRunAudioJson.writeRecord(frontier(baselineState))) + "\n")
                    .getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest.digest());
        } catch (Exception failure) {
            throw new AssertionError(failure);
        }
    }

    private static CutoffFrontier frontier(NormalizedState state) {
        return CutoffFrontier.empty(state);
    }

    private static Frame fullFrame(int absoluteFrame, String segment, boolean lag, List<Request> requests,
            List<DriverService> services) {
        List<Decision> decisions = services.stream().flatMap(service -> evidence(service).decisions().stream())
                .toList();
        NormalizedState state = services.isEmpty()
                ? new NormalizedState(List.of(new StateField("tempo", 1)),
                        List.of(new RoleState(HardwareRole.FM1, false, List.of())))
                : evidence(services.getLast()).state();
        List<ChipEvent> chips = services.stream().flatMap(service -> evidence(service).chips().stream())
                .sorted(java.util.Comparator.comparingLong(ChipEvent::ordinal)).toList();
        return new Frame(absoluteFrame, segment, lag, requests, decisions, services, state, chips, null);
    }

    private static Frame fullFrame(int absoluteFrame, String segment, boolean lag, List<Request> requests,
            List<DriverService> services, List<ChipEvent> chips, FrameNativeDiagnostics diagnostics) {
        List<Decision> decisions = services.stream().flatMap(service -> evidence(service).decisions().stream())
                .toList();
        NormalizedState state = services.isEmpty()
                ? new NormalizedState(List.of(new StateField("tempo", 1)),
                        List.of(new RoleState(HardwareRole.FM1, false, List.of())))
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
        DriverService service = new DriverService(ordinal, kind, completion, null, null, null,
                ServiceAncestry.root());
        SERVICE_EVIDENCE.put(service, new ServiceEvidence(List.copyOf(decisions), state, List.copyOf(chips)));
        return service;
    }

    private record ServiceEvidence(List<Decision> decisions, NormalizedState state, List<ChipEvent> chips) { }

    private static Frame hostileFrame(int row) {
        String segment = entropy(row) + entropy(row ^ 0x5a5a5a5a);
        if (row % 64 != 0) return fullFrame(860 + row, segment, (row & 1) == 0, List.of(), List.of());
        NormalizedState state = new NormalizedState(List.of(new StateField("tempo", row)),
                List.of(new RoleState(HardwareRole.FM1, true, List.of(new StateField("cursor", row)))));
        Request request = new Request(row, OwnerClass.SFX, "sfx." + segment, row & 0xff, "hostile", row);
        OwnerRef none = noneOwner();
        OwnerRef owner = new OwnerRef(OwnerClass.SFX, "sfx." + segment, row & 0xff,
                OwnerOrigin.REQUEST, row);
        Decision decision = new Decision(row, row & 0xff, "sfx." + segment, true, "accepted", row, row + 1,
                List.of(HardwareRole.FM1), List.of(new RoleDecision(HardwareRole.FM1, none, owner)));
        DriverService service = testService(row, "hostile." + segment, ServiceCompletion.COMPLETED,
                List.of(decision), state,
                List.of(new YmWrite(row * 2L, 0, row & 0xff, (row * 31) & 0xff),
                        new PsgWrite(row * 2L + 1, (row * 17) & 0xff)));
        return fullFrame(860 + row, segment, false, List.of(request), List.of(service));
    }

    private static String entropy(int row) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(("complete-run-audio-hostile-v1:" + row).getBytes(StandardCharsets.US_ASCII)));
        } catch (java.security.NoSuchAlgorithmException failure) {
            throw new AssertionError(failure);
        }
    }

    private static Baseline baseline(NormalizedState state) {
        return new Baseline(860, state,
                List.of(new RoleOwner(HardwareRole.FM1, noneOwner())));
    }

    private static OwnerRef noneOwner() {
        return new OwnerRef(OwnerClass.NONE, "none", 0, OwnerOrigin.NONE, -1);
    }

    private static String root(List<CompleteRunAudioTrace.Record> records) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (CompleteRunAudioTrace.Record record : records) {
                digest.update((CompleteRunAudioJson.writeRecord(record) + "\n")
                        .getBytes(StandardCharsets.UTF_8));
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (Exception failure) {
            throw new AssertionError(failure);
        }
    }

    private static String semanticRoot(List<CompleteRunAudioTrace.Record> records) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (CompleteRunAudioTrace.Record record : records) {
                digest.update((CompleteRunAudioJson.writeSemanticRecord(record) + "\n")
                        .getBytes(StandardCharsets.UTF_8));
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (Exception failure) {
            throw new AssertionError(failure);
        }
    }
}
