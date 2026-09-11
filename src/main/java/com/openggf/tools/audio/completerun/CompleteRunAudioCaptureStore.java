package com.openggf.tools.audio.completerun;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.openggf.tools.audio.completerun.CompleteRunAudioTrace.Baseline;
import com.openggf.tools.audio.completerun.CompleteRunAudioTrace.CaptureCounts;
import com.openggf.tools.audio.completerun.CompleteRunAudioTrace.CutoffFrontier;
import com.openggf.tools.audio.completerun.CompleteRunAudioTrace.Frame;
import com.openggf.tools.audio.completerun.CompleteRunAudioTrace.Lifecycle;
import com.openggf.tools.audio.completerun.CompleteRunAudioTrace.Metadata;
import com.openggf.tools.audio.completerun.CompleteRunAudioTrace.Record;
import com.openggf.tools.audio.completerun.CompleteRunAudioTrace.Terminal;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/** Strict bounded storage and create-new publication for complete-run audio captures. */
public final class CompleteRunAudioCaptureStore {
    private static final int MAX_RECORD_CHARACTERS = 16 * 1024 * 1024;
    private static final long MAX_MANIFEST_BYTES = 4L * 1024 * 1024;
    /** Largest pinned complete-run epoch (S3K) fits 107 chunks; reserve 128, never buffer more. */
    static final int MAX_CAPTURE_CHUNKS = 128;
    private final PublicationLinker publicationLinker;
    private final StagingCleaner stagingCleaner;

    public CompleteRunAudioCaptureStore() {
        this(CompleteRunAudioCaptureStore::publishNewSymlink, CompleteRunAudioCaptureStore::deleteTree);
    }

    CompleteRunAudioCaptureStore(PublicationLinker publicationLinker) {
        this(publicationLinker, CompleteRunAudioCaptureStore::deleteTree);
    }

    CompleteRunAudioCaptureStore(PublicationLinker publicationLinker, StagingCleaner stagingCleaner) {
        this.publicationLinker = Objects.requireNonNull(publicationLinker, "publication linker");
        this.stagingCleaner = Objects.requireNonNull(stagingCleaner, "staging cleaner");
    }

    public void writeNew(Path output, Metadata metadata, Iterator<Record> records) throws IOException {
        try (Writer writer = writeNew(output, metadata)) {
            while (records.hasNext()) {
                writer.append(records.next());
            }
        }
    }

    public Writer writeNew(Path output, Metadata metadata) throws IOException {
        Objects.requireNonNull(metadata, "metadata");
        if ((long) metadata.fixture().exclusiveEnd() - metadata.fixture().firstFrame()
                > (long) MAX_CAPTURE_CHUNKS * CompleteRunAudioTrace.CHUNK_FRAME_ROWS) {
            throw new IllegalArgumentException("capture interval exceeds the pinned complete-run chunk bound");
        }
        Path destination = output.toAbsolutePath().normalize();
        Path parent = destination.getParent();
        if (parent == null) {
            throw new IllegalArgumentException("capture output must have a parent directory");
        }
        if (Files.exists(destination)) {
            throw new FileAlreadyExistsException(destination.toString());
        }
        Path staging = Files.createTempDirectory(parent, ".audio-staging-");
        try {
            return new StoreWriter(destination, staging, metadata);
        } catch (IOException | RuntimeException | Error failure) {
            try {
                stagingCleaner.delete(staging);
            } catch (IOException cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
    }

    public Reader read(Path capture) throws IOException {
        return new StoreReader(capture.toAbsolutePath().normalize());
    }

    @FunctionalInterface
    interface PublicationLinker {
        void publish(Path staging, Path target) throws IOException;
    }

    @FunctionalInterface
    interface StagingCleaner {
        void delete(Path path) throws IOException;
    }

    public interface Writer extends CompleteRunAudioRecordSink {
        /** Appends the terminal from counts and digests independently accumulated by this writer. */
        void finish(CompleteRunAudioTrace.NativeCapabilitySummary nativeCapability) throws IOException;
        /** Discards the private staging tree without attempting publication. */
        void abort() throws IOException;
    }

    public interface Reader extends Iterator<Record>, AutoCloseable {
        Metadata metadata();
        @Override void close() throws IOException;
    }

    private final class StoreWriter implements Writer {
        private final Path destination;
        private final Path staging;
        private final Path chunks;
        private final Metadata metadata;
        private final MessageDigest rootDigest = sha256();
        private final MessageDigest semanticDigest = sha256();
        private final List<Chunk> completeChunks = new ArrayList<>();
        private ChunkWriter current;
        private boolean baselineSeen;
        private boolean cutoffSeen;
        private boolean terminalSeen;
        private boolean closed;
        private String root;
        private int frames;
        private long requests;
        private long services;
        private long decisions;
        private long ym;
        private long psg;
        private long lifecycles;
        private long cutoffActive;
        private long cutoffPending;

        StoreWriter(Path destination, Path staging, Metadata metadata) throws IOException {
            this.destination = destination;
            this.staging = staging;
            this.metadata = Objects.requireNonNull(metadata, "metadata");
            this.chunks = Files.createDirectory(staging.resolve("chunks"));
        }

        @Override
        public void append(Record record) throws IOException {
            requireOpen();
            metadata.validateObservationShape(Objects.requireNonNull(record, "complete-run record"));
            if (terminalSeen) {
                throw new IllegalArgumentException("terminal must be the final complete-run record");
            }
            if (cutoffSeen && !(record instanceof Terminal)) {
                throw new IllegalArgumentException("cutoff frontier must be immediately before terminal");
            }
            if (record instanceof Baseline baseline) {
                if (baselineSeen || frames != 0) {
                    throw new IllegalArgumentException("one baseline must precede all frame rows");
                }
                validateBaselineBoundary(metadata, baseline);
                baselineSeen = true;
            } else if (record instanceof Frame frame) {
                if (!baselineSeen) {
                    throw new IllegalArgumentException("baseline is required before frames");
                }
                if (frame.absoluteFrame() != metadata.fixture().firstFrame() + frames) {
                    throw new IllegalArgumentException("frame rows must be contiguous from metadata first frame");
                }
                if (current != null && current.frameRows == CompleteRunAudioTrace.CHUNK_FRAME_ROWS) {
                    finishChunk();
                }
                ChunkWriter chunk = current();
                count(frame);
                frames++;
                chunk.frameRows++;
                current.frameEnd = frame.absoluteFrame() + 1;
            } else if (record instanceof Lifecycle) {
                if (!baselineSeen) {
                    throw new IllegalArgumentException("baseline is required before lifecycle records");
                }
                lifecycles++;
            } else if (record instanceof CutoffFrontier frontier) {
                if (!baselineSeen || cutoffSeen) {
                    throw new IllegalArgumentException("exactly one cutoff frontier is required after baseline");
                }
                cutoffActive = size(frontier.activeStack());
                cutoffPending = size(frontier.pendingDescendants());
                cutoffSeen = true;
            } else if (record instanceof Terminal terminal) {
                if (!baselineSeen || !cutoffSeen) {
                    throw new IllegalArgumentException("cutoff frontier is required immediately before terminal");
                }
                root = digest(rootDigest);
                String semantic = digest(semanticDigest);
                if (!root.equals(terminal.rootDigest()) || !semantic.equals(terminal.semanticDigest())) {
                    throw new IllegalArgumentException("terminal root digest does not match emitted canonical records");
                }
                metadata.validateTerminal(terminal, counts());
                terminalSeen = true;
            } else {
                throw new IllegalArgumentException("unsupported complete-run record type: " + record.getClass().getName());
            }
            write(record);
            if (!(record instanceof Terminal)) {
                update(rootDigest, CompleteRunAudioJson.writeRecord(record));
                update(semanticDigest, CompleteRunAudioJson.writeSemanticRecord(record));
            }
        }

        private void count(Frame frame) {
            requests = Math.addExact(requests, size(frame.requests()));
            services = Math.addExact(services, size(frame.services()));
            decisions = Math.addExact(decisions, size(frame.decisions()));
            for (var event : frame.chipEvents() == null ? List.<CompleteRunAudioTrace.ChipEvent>of()
                    : frame.chipEvents()) {
                if (event instanceof CompleteRunAudioTrace.YmWrite) ym = Math.addExact(ym, 1);
                else psg = Math.addExact(psg, 1);
            }
        }

        private CaptureCounts counts() {
            return new CaptureCounts(frames, requests, services, decisions, ym, psg, lifecycles,
                    cutoffActive, cutoffPending);
        }

        @Override
        public void finish(CompleteRunAudioTrace.NativeCapabilitySummary nativeCapability) throws IOException {
            requireOpen();
            if (!baselineSeen || !cutoffSeen || terminalSeen) {
                throw new IllegalArgumentException("capture is not ready for terminal finalization");
            }
            CaptureCounts captured = counts();
            append(new Terminal(metadata.fixture().exclusiveEnd(), captured.frameCount(),
                    captured.requestCount(), captured.serviceCount(), captured.decisionCount(),
                    captured.ymCount(), captured.psgCount(), captured.lifecycleCount(),
                    captured.cutoffActiveCount(), captured.cutoffPendingCount(), nativeCapability,
                    digestCopy(rootDigest), digestCopy(semanticDigest)));
        }

        @Override
        public void abort() throws IOException {
            if (closed) return;
            IOException failure = null;
            if (current != null) {
                try { current.gzip.close(); }
                catch (IOException problem) { failure = problem; }
                current = null;
            }
            try {
                stagingCleaner.delete(staging);
                closed = true;
            } catch (IOException cleanupFailure) {
                if (failure == null) failure = cleanupFailure;
                else failure.addSuppressed(cleanupFailure);
            }
            if (failure != null) throw failure;
        }

        private ChunkWriter current() throws IOException {
            if (current == null) {
                String file = String.format("%06d.jsonl.gz", completeChunks.size());
                Path path = chunks.resolve(file);
                MessageDigest raw = sha256();
                MessageDigest compressed = sha256();
                OutputStream rawOutput = new DigestOutputStream(Files.newOutputStream(path), compressed);
                // JDK GZIPOutputStream emits MTIME=0; the fixed default buffer and level make output repeatable.
                GZIPOutputStream gzip = new GZIPOutputStream(rawOutput, 8192, true);
                current = new ChunkWriter(file, path, rawOutput, gzip, raw, compressed,
                        metadata.fixture().firstFrame() + frames);
            }
            return current;
        }

        private void write(Record record) throws IOException {
            ChunkWriter writer = current();
            String json = CompleteRunAudioJson.writeRecord(record);
            byte[] bytes = (json + "\n").getBytes(StandardCharsets.UTF_8);
            writer.gzip.write(bytes);
            writer.raw.update(bytes);
        }

        private void finishChunk() throws IOException {
            if (current == null) return;
            ChunkWriter writer = current;
            writer.gzip.close();
            String compressed = digest(writer.compressedDigest);
            String raw = digest(writer.raw);
            completeChunks.add(new Chunk(writer.file, writer.frameRows, writer.frameStart, writer.frameEnd,
                    compressed, raw));
            if (completeChunks.size() > MAX_CAPTURE_CHUNKS) {
                throw new IllegalArgumentException("capture exceeds the fixed complete-run chunk descriptor bound");
            }
            current = null;
        }

        @Override
        public void close() throws IOException {
            if (closed) return;
            closed = true;
            try {
                if (!baselineSeen || !cutoffSeen || !terminalSeen) {
                    throw new IllegalArgumentException("capture must contain baseline, cutoff frontier, and terminal");
                }
                finishChunk();
                writeManifest(staging.resolve("manifest.json"), metadata, completeChunks, root);
                try (Reader reader = new StoreReader(staging)) {
                    while (reader.hasNext()) reader.next();
                }
                publicationLinker.publish(staging, destination);
            } catch (AtomicMoveNotSupportedException failure) {
                suppressCleanupFailure(staging, failure);
                throw failure;
            } catch (Throwable failure) {
                suppressCleanupFailure(staging, failure);
                if (failure instanceof IOException io) throw io;
                if (failure instanceof RuntimeException runtime) throw runtime;
                throw new IOException("capture publication failed", failure);
            }
        }

        private void requireOpen() throws IOException {
            if (closed) throw new IOException("capture writer is closed");
        }
    }

    /**
     * Directories have no portable create-new atomic rename operation.  Publication therefore uses
     * a create-new symbolic link to an atomically moved immutable backing directory.  A provider
     * without atomic symbolic-link creation fails closed rather than accepting replace semantics.
     */
    private static void publishNewSymlink(Path staging, Path target) throws IOException {
        Path parent = target.getParent();
        Path backing = Files.createTempDirectory(parent, ".audio-published-");
        try {
            Files.delete(backing);
            Files.move(staging, backing, StandardCopyOption.ATOMIC_MOVE);
            try {
                Files.createSymbolicLink(target, backing.getFileName());
            } catch (UnsupportedOperationException failure) {
                throw new AtomicMoveNotSupportedException(staging.toString(), target.toString(),
                        "filesystem does not support atomic create-new symbolic-link publication");
            }
        } catch (Throwable failure) {
            try {
                deleteTree(backing);
            } catch (IOException cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            if (failure instanceof IOException io) throw io;
            if (failure instanceof RuntimeException runtime) throw runtime;
            throw new IOException("atomic create-new publication failed", failure);
        }
    }

    private static final class StoreReader implements Reader {
        private final Path root;
        private final Metadata metadata;
        private final List<Chunk> chunks;
        private final String expectedRoot;
        private final MessageDigest rootDigest = sha256();
        private final MessageDigest semanticDigest = sha256();
        private final Validator validator;
        private int chunkIndex;
        private Chunk expectedChunk;
        private BufferedReader input;
        private DigestInputStream compressedInput;
        private MessageDigest rawDigest;
        private Record next;
        private boolean complete;

        StoreReader(Path root) throws IOException {
            this.root = canonicalCaptureRoot(root);
            validateCaptureRoot(this.root);
            Manifest manifest = parseManifest(this.root.resolve("manifest.json"));
            validateChunkTree(this.root.resolve("chunks"), manifest.chunks);
            metadata = manifest.metadata;
            chunks = manifest.chunks;
            expectedRoot = manifest.root;
            validator = new Validator(metadata);
        }

        @Override public Metadata metadata() { return metadata; }

        @Override
        public boolean hasNext() {
            if (next != null) return true;
            if (complete) return false;
            try {
                while (true) {
                    if (input == null) openChunk();
                    String line = boundedLine(input);
                    if (line != null) {
                        byte[] bytes = (line + "\n").getBytes(StandardCharsets.UTF_8);
                        rawDigest.update(bytes);
                        next = CompleteRunAudioJson.readRecord(line);
                        return true;
                    }
                    closeChunk();
                    if (chunkIndex == chunks.size()) {
                        validator.finish(digest(rootDigest), digest(semanticDigest), expectedRoot);
                        complete = true;
                        return false;
                    }
                }
            } catch (IOException failure) {
                throw new IllegalArgumentException("cannot read complete-run capture", failure);
            }
        }

        @Override
        public Record next() {
            if (!hasNext()) throw new NoSuchElementException();
            Record result = next;
            next = null;
            validator.accept(result);
            if (!(result instanceof Terminal)) {
                update(rootDigest, recordJson(result));
                update(semanticDigest, semanticRecordJson(result));
            }
            return result;
        }

        private void openChunk() throws IOException {
            if (chunkIndex == chunks.size()) return;
            expectedChunk = chunks.get(chunkIndex++);
            Path file = root.resolve("chunks").resolve(expectedChunk.file);
            if (!file.normalize().getParent().equals(root.resolve("chunks"))) {
                throw new IllegalArgumentException("chunk path escapes capture chunks directory");
            }
            compressedInput = new DigestInputStream(Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS), sha256());
            input = new BufferedReader(new InputStreamReader(new GZIPInputStream(compressedInput), StandardCharsets.UTF_8));
            rawDigest = sha256();
        }

        private void closeChunk() throws IOException {
            input.close();
            input = null;
            if (!digest(rawDigest).equals(expectedChunk.raw)) {
                throw new IllegalArgumentException("uncompressed chunk digest mismatch");
            }
            if (!digest(compressedInput.getMessageDigest()).equals(expectedChunk.compressed)) {
                throw new IllegalArgumentException("compressed chunk digest mismatch");
            }
            validator.completeChunk(expectedChunk);
            compressedInput = null;
            expectedChunk = null;
        }

        @Override
        public void close() throws IOException {
            complete = true;
            if (input != null) input.close();
        }
    }

    private static final class Validator {
        private final Metadata metadata;
        private boolean baseline;
        private boolean cutoff;
        private boolean terminal;
        private String terminalRoot;
        private String terminalSemantic;
        private int frames;
        private int currentChunkFrames;
        private int currentChunkFirst = -1;
        private int currentChunkEnd = -1;
        private long requests, services, decisions, ym, psg, lifecycles;
        private long cutoffActive, cutoffPending;

        Validator(Metadata metadata) { this.metadata = metadata; }

        void accept(Record record) {
            if (terminal) throw new IllegalArgumentException("records follow terminal");
            metadata.validateObservationShape(Objects.requireNonNull(record, "complete-run record"));
            if (cutoff && !(record instanceof Terminal)) {
                throw new IllegalArgumentException("cutoff frontier must be immediately before terminal");
            }
            if (record instanceof Baseline value) {
                if (baseline || frames != 0) throw new IllegalArgumentException("baseline must be first");
                validateBaselineBoundary(metadata, value);
                baseline = true;
            } else if (record instanceof Frame frame) {
                if (!baseline || frame.absoluteFrame() != metadata.fixture().firstFrame() + frames) {
                    throw new IllegalArgumentException("frame coordinates are not contiguous");
                }
                if (currentChunkFirst < 0) currentChunkFirst = frame.absoluteFrame();
                currentChunkFrames++;
                currentChunkEnd = frame.absoluteFrame() + 1;
                frames++;
                requests = Math.addExact(requests, size(frame.requests()));
                services = Math.addExact(services, size(frame.services()));
                decisions = Math.addExact(decisions, size(frame.decisions()));
                for (var event : frame.chipEvents() == null ? List.<CompleteRunAudioTrace.ChipEvent>of()
                        : frame.chipEvents()) {
                    if (event instanceof CompleteRunAudioTrace.YmWrite) ym = Math.addExact(ym, 1); else psg = Math.addExact(psg, 1);
                }
            } else if (record instanceof Lifecycle) {
                if (!baseline) throw new IllegalArgumentException("lifecycle precedes baseline");
                lifecycles = Math.addExact(lifecycles, 1);
            } else if (record instanceof CutoffFrontier value) {
                if (!baseline || cutoff) throw new IllegalArgumentException("invalid cutoff frontier position");
                cutoff = true;
                cutoffActive = size(value.activeStack());
                cutoffPending = size(value.pendingDescendants());
            } else if (record instanceof Terminal value) {
                if (!baseline || !cutoff) throw new IllegalArgumentException("terminal lacks cutoff frontier");
                metadata.validateTerminal(value, new CaptureCounts(frames, requests, services, decisions, ym, psg,
                        lifecycles, cutoffActive, cutoffPending));
                terminalRoot = value.rootDigest();
                terminalSemantic = value.semanticDigest();
                terminal = true;
            }
        }

        void completeChunk(Chunk chunk) {
            if (currentChunkFrames != chunk.frames || currentChunkFirst != chunk.first || currentChunkEnd != chunk.end) {
                throw new IllegalArgumentException("chunk frame bounds or frame-row count mismatch");
            }
            currentChunkFrames = 0;
            currentChunkFirst = -1;
            currentChunkEnd = -1;
        }

        void finish(String root, String semantic, String expectedRoot) {
            if (!baseline || !cutoff || !terminal) {
                throw new IllegalArgumentException("capture is missing baseline, cutoff frontier, or terminal");
            }
            if (!root.equals(expectedRoot)) throw new IllegalArgumentException("capture root digest mismatch");
            if (!root.equals(terminalRoot)) throw new IllegalArgumentException("terminal root digest mismatch");
            if (!semantic.equals(terminalSemantic)) {
                throw new IllegalArgumentException("terminal semantic digest mismatch");
            }
        }
    }

    private record Chunk(String file, int frames, int first, int end, String compressed, String raw) { }
    private record Manifest(Metadata metadata, List<Chunk> chunks, String root) { }

    private static void validateBaselineBoundary(Metadata metadata, Baseline baseline) {
        if (baseline.absoluteFrame() != metadata.fixture().firstFrame()) {
            throw new IllegalArgumentException("baseline does not match metadata first frame");
        }
        boolean buffered = metadata.observerRuntimeIdentity()
                instanceof CompleteRunAudioTrace.BufferedNativeObserverIdentity;
        if (!buffered && baseline.frontier().nativeDiagnostics() != null) {
            throw new IllegalArgumentException("callback baseline cannot carry native frontier proof");
        }
    }
    private static final class ChunkWriter {
        private final String file;
        private final Path path;
        private final OutputStream compressedOutput;
        private final GZIPOutputStream gzip;
        private final MessageDigest raw;
        private final MessageDigest compressedDigest;
        private final int frameStart;
        private int frameRows;
        private int frameEnd;

        private ChunkWriter(String file, Path path, OutputStream compressedOutput, GZIPOutputStream gzip,
                MessageDigest raw, MessageDigest compressedDigest, int frameStart) {
            this.file = file;
            this.path = path;
            this.compressedOutput = compressedOutput;
            this.gzip = gzip;
            this.raw = raw;
            this.compressedDigest = compressedDigest;
            this.frameStart = frameStart;
            this.frameEnd = frameStart;
        }
    }

    private static void writeManifest(Path file, Metadata metadata, List<Chunk> chunks, String root) throws IOException {
        try (JsonGenerator json = CompleteRunAudioJson.FACTORY.createGenerator(Files.newBufferedWriter(file, StandardCharsets.UTF_8))) {
            json.writeStartObject();
            json.writeStringField("schema", CompleteRunAudioTrace.SCHEMA);
            json.writeFieldName("metadata");
            CompleteRunAudioJson.writeMetadata(json, metadata);
            json.writeArrayFieldStart("chunks");
            for (Chunk chunk : chunks) {
                json.writeStartObject();
                json.writeStringField("file", chunk.file);
                json.writeNumberField("frame_rows", chunk.frames);
                json.writeNumberField("first_frame", chunk.first);
                json.writeNumberField("exclusive_end", chunk.end);
                json.writeStringField("compressed_sha256", chunk.compressed);
                json.writeStringField("uncompressed_sha256", chunk.raw);
                json.writeEndObject();
            }
            json.writeEndArray();
            json.writeStringField("root_digest", root);
            json.writeEndObject();
        }
    }

    private static Path canonicalCaptureRoot(Path root) throws IOException {
        if (Files.isSymbolicLink(root)) {
            Path target = Files.readSymbolicLink(root);
            if (target.isAbsolute() || target.getNameCount() != 1
                    || !target.getFileName().toString().startsWith(".audio-published-")) {
                throw new IllegalArgumentException("capture root has a noncanonical publication link");
            }
            root = root.getParent().resolve(target).normalize();
        }
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(root)) {
            throw new IllegalArgumentException("capture root must be a plain directory");
        }
        return root;
    }

    private static void validateCaptureRoot(Path root) throws IOException {
        Set<String> actual = new HashSet<>();
        try (var entries = Files.list(root)) {
            for (Path entry : entries.limit(3).toList()) actual.add(entry.getFileName().toString());
        }
        if (!actual.equals(Set.of("manifest.json", "chunks"))) {
            throw new IllegalArgumentException("capture root contains undeclared entries");
        }
        requirePlainSingleLinkFile(root.resolve("manifest.json"), "capture manifest");
        Path chunks = root.resolve("chunks");
        if (!Files.isDirectory(chunks, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(chunks)) {
            throw new IllegalArgumentException("capture chunks must be a plain directory");
        }
    }

    private static void validateChunkTree(Path directory, List<Chunk> chunks) throws IOException {
        Set<String> expected = new HashSet<>();
        for (Chunk chunk : chunks) {
            if (!expected.add(chunk.file)) {
                throw new IllegalArgumentException("capture manifest repeats a chunk file");
            }
        }
        Set<String> actual = new HashSet<>();
        try (var entries = Files.list(directory)) {
            for (Path entry : entries.limit(MAX_CAPTURE_CHUNKS + 1L).toList()) {
                actual.add(entry.getFileName().toString());
                requirePlainSingleLinkFile(entry, "capture chunk");
            }
        }
        if (!actual.equals(expected)) {
            throw new IllegalArgumentException("capture chunks do not match their manifest");
        }
    }

    private static void requirePlainSingleLinkFile(Path file, String label) throws IOException {
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(file)) {
            throw new IllegalArgumentException(label + " must be a plain regular file");
        }
        Object links = Files.getAttribute(file, "unix:nlink", LinkOption.NOFOLLOW_LINKS);
        if (!(links instanceof Number count) || count.longValue() != 1) {
            throw new IllegalArgumentException(label + " must have exactly one link");
        }
    }

    private static Manifest parseManifest(Path file) throws IOException {
        if (Files.size(file) > MAX_MANIFEST_BYTES) {
            throw new IllegalArgumentException("capture manifest exceeds its byte bound");
        }
        try (JsonParser parser = CompleteRunAudioJson.FACTORY.createParser(
                Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS))) {
            if (parser.nextToken() != com.fasterxml.jackson.core.JsonToken.START_OBJECT) throw new IllegalArgumentException("capture manifest must be object");
            manifestField(parser,"schema");
            if (!CompleteRunAudioTrace.SCHEMA.equals(manifestText(parser, "schema"))) {
                throw new IllegalArgumentException("unknown capture manifest schema");
            }
            manifestField(parser,"metadata"); Metadata metadata = CompleteRunAudioJson.readMetadata(parser);
            manifestField(parser,"chunks");
            if (parser.currentToken() != com.fasterxml.jackson.core.JsonToken.START_ARRAY) {
                throw new IllegalArgumentException("capture manifest chunks must be a non-empty array");
            }
            List<Chunk> chunks = new ArrayList<>();
            int expectedFirst = metadata.fixture().firstFrame();
            while(parser.nextToken()!=com.fasterxml.jackson.core.JsonToken.END_ARRAY) {
                if(parser.currentToken()!=com.fasterxml.jackson.core.JsonToken.START_OBJECT)throw new IllegalArgumentException("chunk manifest entry must be object");
                manifestField(parser,"file"); String name=manifestText(parser,"chunk file");
                manifestField(parser,"frame_rows"); int frames=manifestInt(parser,"chunk frames");
                manifestField(parser,"first_frame"); int first=manifestInt(parser,"chunk first");
                manifestField(parser,"exclusive_end"); int end=manifestInt(parser,"chunk end");
                manifestField(parser,"compressed_sha256"); String compressed=manifestHash(parser);
                manifestField(parser,"uncompressed_sha256"); String raw=manifestHash(parser);
                if(parser.nextToken()!=com.fasterxml.jackson.core.JsonToken.END_OBJECT)throw new IllegalArgumentException("chunk manifest entry contains unknown/missing fields");
                if (!name.matches("[0-9]{6}\\.jsonl\\.gz") || frames < 0 || frames > CompleteRunAudioTrace.CHUNK_FRAME_ROWS || first != expectedFirst || end != first + frames) {
                    throw new IllegalArgumentException("invalid deterministic chunk manifest bounds");
                }
                chunks.add(new Chunk(name, frames, first, end, compressed, raw));
                if (chunks.size() > MAX_CAPTURE_CHUNKS) throw new IllegalArgumentException("manifest exceeds fixed complete-run chunk bound");
                expectedFirst = end;
            }
            if(chunks.isEmpty()) throw new IllegalArgumentException("capture manifest chunks must be non-empty");
            manifestField(parser,"root_digest"); String root=manifestHash(parser);
            if(parser.nextToken()!=com.fasterxml.jackson.core.JsonToken.END_OBJECT||parser.nextToken()!=null)throw new IllegalArgumentException("trailing/unknown manifest fields");
            for (int index = 0; index < chunks.size() - 1; index++) if (chunks.get(index).frames != CompleteRunAudioTrace.CHUNK_FRAME_ROWS) throw new IllegalArgumentException("non-final chunks must contain exactly 4,096 frame rows");
            return new Manifest(metadata, List.copyOf(chunks), root);
        } catch (com.fasterxml.jackson.core.JsonParseException failure) {
            throw new IllegalArgumentException("invalid capture manifest JSON", failure);
        }
    }

    private static String recordJson(Record record) {
        try { return CompleteRunAudioJson.writeRecord(record); }
        catch (IOException failure) { throw new IllegalArgumentException("cannot canonicalize complete-run record", failure); }
    }

    private static String boundedLine(BufferedReader input) throws IOException {
        StringBuilder line = new StringBuilder(Math.min(8192, MAX_RECORD_CHARACTERS));
        int value;
        while ((value = input.read()) >= 0) {
            if (value == '\n') return line.toString();
            if (value == '\r') throw new IllegalArgumentException("capture records require LF line endings");
            if (line.length() == MAX_RECORD_CHARACTERS) {
                throw new IllegalArgumentException("capture record exceeds its byte bound");
            }
            line.append((char) value);
        }
        return line.isEmpty() ? null : line.toString();
    }

    private static String semanticRecordJson(Record record) {
        try { return CompleteRunAudioJson.writeSemanticRecord(record); }
        catch (IOException failure) { throw new IllegalArgumentException("cannot canonicalize record", failure); }
    }

    private static int size(List<?> values) {
        return values == null ? 0 : values.size();
    }

    private static void update(MessageDigest digest, String record) { digest.update((record + "\n").getBytes(StandardCharsets.UTF_8)); }
    private static MessageDigest sha256() { try { return MessageDigest.getInstance("SHA-256"); } catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); } }
    private static String digest(MessageDigest digest) { return HexFormat.of().formatHex(digest.digest()); }
    private static String digestCopy(MessageDigest digest) {
        try { return HexFormat.of().formatHex(((MessageDigest) digest.clone()).digest()); }
        catch (CloneNotSupportedException impossible) { throw new AssertionError(impossible); }
    }
    private static void manifestField(JsonParser parser,String name)throws IOException{if(parser.nextToken()!=com.fasterxml.jackson.core.JsonToken.FIELD_NAME||!name.equals(parser.currentName())||parser.nextToken()==null)throw new IllegalArgumentException("expected manifest field: "+name);} private static String manifestText(JsonParser parser,String label)throws IOException{if(parser.currentToken()!=com.fasterxml.jackson.core.JsonToken.VALUE_STRING)throw new IllegalArgumentException(label+" must be text");return parser.getText();} private static int manifestInt(JsonParser parser,String label)throws IOException{if(parser.currentToken()!=com.fasterxml.jackson.core.JsonToken.VALUE_NUMBER_INT)throw new IllegalArgumentException(label+" must be int");return parser.getIntValue();} private static String manifestHash(JsonParser parser)throws IOException{String value=manifestText(parser,"manifest hash");if(!value.matches("[0-9a-f]{64}"))throw new IllegalArgumentException("manifest hash must be canonical SHA-256");return value;}
    private void suppressCleanupFailure(Path root, Throwable primary) {
        try {
            stagingCleaner.delete(root);
        } catch (IOException cleanupFailure) {
            primary.addSuppressed(cleanupFailure);
        }
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) return;
        IOException failure = null;
        List<Path> paths;
        try (var walk = Files.walk(root)) {
            paths = walk.sorted(Comparator.reverseOrder()).toList();
        }
        for (Path path : paths) {
            try {
                Files.deleteIfExists(path);
            } catch (IOException deletionFailure) {
                if (failure == null) failure = deletionFailure;
                else failure.addSuppressed(deletionFailure);
            }
        }
        if (failure != null) throw failure;
    }
}
