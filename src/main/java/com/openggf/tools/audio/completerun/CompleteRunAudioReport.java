package com.openggf.tools.audio.completerun;

import com.fasterxml.jackson.core.JsonGenerator;
import com.openggf.tools.audio.completerun.CompleteRunAudioTrace.Metadata;
import com.openggf.tools.audio.completerun.CompleteRunAudioTrace.ProducerKind;
import java.io.IOException;
import java.io.StringWriter;
import java.util.List;
import java.util.Objects;

/** Immutable, deterministic first-mismatch report for two complete-run audio captures. */
public final class CompleteRunAudioReport {
    public static final String SCHEMA = "complete_run_audio_comparison.v1";
    private static final int CONTEXT_LIMIT = 8;

    public enum Kind {
        MATCH,
        REFERENCE_LIMITATION,
        CAPTURE_FAILURE,
        METADATA_IDENTITY,
        RECORD_SHAPE,
        FRAME_MISSING,
        FRAME_EXTRA,
        FRAME_VALUE,
        REQUEST_MISSING,
        REQUEST_EXTRA,
        REQUEST_ORDER,
        REQUEST_VALUE,
        SERVICE_MISSING,
        SERVICE_EXTRA,
        SERVICE_ORDER,
        SERVICE_VALUE,
        DECISION_MISSING,
        DECISION_EXTRA,
        DECISION_ORDER,
        DECISION_VALUE,
        CHIP_EVENT_MISSING,
        CHIP_EVENT_EXTRA,
        CHIP_EVENT_ORDER,
        CHIP_EVENT_VALUE,
        STATE_FIELD_NAME,
        STATE_FIELD_VALUE,
        OWNER,
        PRIORITY,
        LIFECYCLE_MISSING,
        LIFECYCLE_EXTRA,
        LIFECYCLE_ORDER,
        LIFECYCLE_VALUE,
        CUTOFF_FRONTIER_VALUE,
        TERMINAL_COUNT,
        TERMINAL_DIGEST
    }

    public enum Side { REFERENCE, ENGINE }

    /** Stable content identity; transient paths and inode keys remain internal validation evidence. */
    public record ChunkIdentity(String file, String compressedSha256, String realPath,
            String fileKey) {
        public ChunkIdentity {
            requireText(file, "chunk file");
            requireText(compressedSha256, "compressed chunk SHA-256");
            requireText(realPath, "resolved chunk path");
            requireText(fileKey, "chunk file key");
        }
    }

    /** Capture identity remains present in every parity or source-change result. */
    public record SourceIdentity(Side side, String source, String realSource, Metadata metadata,
            String metadataSha256, String rootDigest, String captureManifestSha256,
            String publicationDigest, String sourceFileKey, List<ChunkIdentity> chunks) {
        public SourceIdentity {
            Objects.requireNonNull(side, "source side");
            requireText(source, "source path");
            requireText(realSource, "resolved source path");
            Objects.requireNonNull(metadata, "capture metadata");
            requireText(metadataSha256, "metadata SHA-256");
            requireText(rootDigest, "root digest");
            requireText(captureManifestSha256, "capture manifest SHA-256");
            requireText(publicationDigest, "publication digest");
            requireText(sourceFileKey, "source file key");
            chunks = List.copyOf(Objects.requireNonNull(chunks, "chunk identities"));
            if (chunks.isEmpty()) throw new IllegalArgumentException("chunk identities must not be empty");
        }

        public ProducerKind producerKind() { return metadata.producerKind(); }
        public String profileId() { return metadata.profileId(); }
        public String romSha1() { return metadata.fixture().romSha1(); }
        public String bk2Sha256() { return metadata.fixture().bk2Sha256(); }
        public String runManifestSha256() { return metadata.fixture().runManifestSha256(); }
    }

    /** One complete canonical top-level record retained in a bounded context window. */
    public record RecordView(long streamIndex, Integer frame, String type, String canonicalJson) {
        public RecordView {
            if (streamIndex < 0) throw new IllegalArgumentException("stream index must be non-negative");
            requireText(type, "record type");
            requireText(canonicalJson, "canonical record JSON");
        }
    }

    public record Context(List<RecordView> before, RecordView current, List<RecordView> after) {
        public Context {
            before = bounded(before, "before context");
            after = bounded(after, "after context");
        }

        private static List<RecordView> bounded(List<RecordView> values, String name) {
            values = List.copyOf(Objects.requireNonNull(values, name));
            if (values.size() > CONTEXT_LIMIT) {
                throw new IllegalArgumentException(name + " exceeds the fixed eight-record bound");
            }
            return values;
        }
    }

    private final Kind kind;
    private final SourceIdentity reference;
    private final SourceIdentity engine;
    private final int frame;
    private final String location;
    private final String referenceValue;
    private final String engineValue;
    private final Context referenceContext;
    private final Context engineContext;
    private final Side failureSide;
    private final String failureSource;
    private final CompleteRunAudioComparator.ValidationException.Kind validationKind;
    private final String validationDetail;

    CompleteRunAudioReport(Kind kind, SourceIdentity reference, SourceIdentity engine, int frame,
            String location, String referenceValue, String engineValue, Context referenceContext,
            Context engineContext, Side failureSide,
            String failureSource,
            CompleteRunAudioComparator.ValidationException.Kind validationKind,
            String validationDetail) {
        this.kind = Objects.requireNonNull(kind, "report kind");
        this.reference = reference;
        this.engine = engine;
        this.frame = frame;
        this.location = location;
        this.referenceValue = referenceValue;
        this.engineValue = engineValue;
        this.referenceContext = Objects.requireNonNull(referenceContext, "reference context");
        this.engineContext = Objects.requireNonNull(engineContext, "engine context");
        this.failureSide = failureSide;
        this.failureSource = failureSource;
        this.validationKind = validationKind;
        this.validationDetail = validationDetail;
        if (kind == Kind.CAPTURE_FAILURE) {
            Objects.requireNonNull(failureSide, "capture failure side");
            requireText(failureSource, "capture failure source");
            Objects.requireNonNull(validationKind, "validation failure kind");
        } else if (failureSide != null || failureSource != null || validationKind != null
                || validationDetail != null) {
            throw new IllegalArgumentException("only capture failures carry validation fields");
        }
        if ((kind == Kind.MATCH || kind == Kind.REFERENCE_LIMITATION) && (frame != -1 || location != null || referenceValue != null
                || engineValue != null || referenceContext.current() != null
                || engineContext.current() != null || !referenceContext.before().isEmpty()
                || !referenceContext.after().isEmpty() || !engineContext.before().isEmpty()
                || !engineContext.after().isEmpty())) {
            throw new IllegalArgumentException("successful comparison report must not carry mismatch data");
        }
    }

    public Kind kind() { return kind; }
    public SourceIdentity reference() { return reference; }
    public SourceIdentity engine() { return engine; }
    public int frame() { return frame; }
    public String location() { return location; }
    public String referenceValue() { return referenceValue; }
    public String engineValue() { return engineValue; }
    public Context referenceContext() { return referenceContext; }
    public Context engineContext() { return engineContext; }
    public Side failureSide() { return failureSide; }
    public String failureSource() { return failureSource; }
    public CompleteRunAudioComparator.ValidationException.Kind validationKind() { return validationKind; }
    public String validationDetail() { return validationDetail; }

    /** Canonical JSON with fixed field order and no platform-specific line separators. */
    public String toJson() {
        StringWriter out = new StringWriter();
        try (JsonGenerator json = CompleteRunAudioJson.FACTORY.createGenerator(out)) {
            json.writeStartObject();
            json.writeStringField("schema", SCHEMA);
            json.writeStringField("kind", kind.name());
            writeSource(json, "reference", reference);
            writeSource(json, "engine", engine);
            json.writeNumberField("frame", frame);
            nullable(json, "location", location);
            nullable(json, "reference_value", referenceValue);
            nullable(json, "engine_value", engineValue);
            writeContext(json, "reference_context", referenceContext);
            writeContext(json, "engine_context", engineContext);
            nullable(json, "failure_side", failureSide == null ? null : failureSide.name());
            nullable(json, "failure_source", failureSource);
            nullable(json, "validation_kind", validationKind == null ? null : validationKind.name());
            nullable(json, "validation_detail", validationDetail);
            json.writeEndObject();
        } catch (IOException impossible) {
            throw new AssertionError("in-memory comparison report JSON failed", impossible);
        }
        return out.toString();
    }

    /** Stable human-readable view; canonical JSON contexts remain single escaped lines. */
    public String toText() {
        StringBuilder out = new StringBuilder();
        out.append(kind).append('\n');
        appendSource(out, reference);
        appendSource(out, engine);
        if (kind == Kind.CAPTURE_FAILURE) {
            out.append("failure_side=").append(failureSide).append('\n');
            out.append("failure_source=").append(escape(failureSource)).append('\n');
            out.append("validation_kind=").append(validationKind).append('\n');
            if (validationDetail != null) out.append("validation_detail=").append(escape(validationDetail)).append('\n');
        } else if (kind != Kind.MATCH && kind != Kind.REFERENCE_LIMITATION) {
            out.append("frame=").append(frame).append('\n');
            out.append("location=").append(escape(location)).append('\n');
            out.append("reference_value=").append(escape(referenceValue)).append('\n');
            out.append("engine_value=").append(escape(engineValue)).append('\n');
            appendContext(out, "reference", referenceContext);
            appendContext(out, "engine", engineContext);
        }
        return out.toString();
    }

    private static void writeSource(JsonGenerator json, String name, SourceIdentity source) throws IOException {
        json.writeObjectFieldStart(name);
        if (source == null) {
            json.writeNullField("side");
            json.writeNullField("source");
            json.writeNullField("real_source");
            json.writeNullField("producer_kind");
            json.writeNullField("profile_id");
            json.writeNullField("rom_sha1");
            json.writeNullField("bk2_sha256");
            json.writeNullField("run_manifest_sha256");
            json.writeNullField("metadata_sha256");
            json.writeNullField("metadata");
            json.writeNullField("root_digest");
            json.writeNullField("capture_manifest_sha256");
            json.writeNullField("publication_digest");
            json.writeNullField("source_file_key");
            json.writeNullField("chunks");
        } else {
            json.writeStringField("side", source.side().name());
            json.writeStringField("source", source.source());
            json.writeStringField("real_source", source.realSource());
            json.writeStringField("producer_kind", source.producerKind().name());
            json.writeStringField("profile_id", source.profileId());
            json.writeStringField("rom_sha1", source.romSha1());
            json.writeStringField("bk2_sha256", source.bk2Sha256());
            json.writeStringField("run_manifest_sha256", source.runManifestSha256());
            json.writeStringField("metadata_sha256", source.metadataSha256());
            json.writeFieldName("metadata");
            json.writeRawValue(canonicalMetadata(source.metadata()));
            json.writeStringField("root_digest", source.rootDigest());
            json.writeStringField("capture_manifest_sha256", source.captureManifestSha256());
            json.writeStringField("publication_digest", source.publicationDigest());
            json.writeStringField("source_file_key", source.sourceFileKey());
            json.writeArrayFieldStart("chunks");
            for (ChunkIdentity chunk : source.chunks()) {
                json.writeStartObject();
                json.writeStringField("file", chunk.file());
                json.writeStringField("compressed_sha256", chunk.compressedSha256());
                json.writeStringField("real_path", chunk.realPath());
                json.writeStringField("file_key", chunk.fileKey());
                json.writeEndObject();
            }
            json.writeEndArray();
        }
        json.writeEndObject();
    }

    private static void writeContext(JsonGenerator json, String name, Context context) throws IOException {
        json.writeObjectFieldStart(name);
        writeViews(json, "before", context.before());
        json.writeFieldName("current");
        if (context.current() == null) json.writeNull(); else writeView(json, context.current());
        writeViews(json, "after", context.after());
        json.writeEndObject();
    }

    private static void writeViews(JsonGenerator json, String name, List<RecordView> views) throws IOException {
        json.writeArrayFieldStart(name);
        for (RecordView view : views) writeView(json, view);
        json.writeEndArray();
    }

    private static void writeView(JsonGenerator json, RecordView view) throws IOException {
        json.writeStartObject();
        json.writeNumberField("stream_index", view.streamIndex());
        if (view.frame() == null) json.writeNullField("frame"); else json.writeNumberField("frame", view.frame());
        json.writeStringField("type", view.type());
        json.writeStringField("canonical_json", view.canonicalJson());
        json.writeEndObject();
    }

    private static void nullable(JsonGenerator json, String name, String value) throws IOException {
        if (value == null) json.writeNullField(name); else json.writeStringField(name, value);
    }

    private static void appendSource(StringBuilder out, SourceIdentity source) {
        if (source == null) return;
        String prefix = source.side().name().toLowerCase();
        out.append(prefix).append("_source=").append(escape(source.source())).append('\n');
        out.append(prefix).append("_real_source=").append(escape(source.realSource())).append('\n');
        out.append(prefix).append("_producer_kind=").append(source.producerKind()).append('\n');
        out.append(prefix).append("_profile=").append(source.profileId()).append('\n');
        out.append(prefix).append("_rom_sha1=").append(source.romSha1()).append('\n');
        out.append(prefix).append("_bk2_sha256=").append(source.bk2Sha256()).append('\n');
        out.append(prefix).append("_run_manifest_sha256=").append(source.runManifestSha256()).append('\n');
        out.append(prefix).append("_metadata_sha256=").append(source.metadataSha256()).append('\n');
        out.append(prefix).append("_metadata_json=").append(escape(canonicalMetadata(source.metadata()))).append('\n');
        out.append(prefix).append("_root=").append(source.rootDigest()).append('\n');
        out.append(prefix).append("_capture_manifest_sha256=")
                .append(source.captureManifestSha256()).append('\n');
        out.append(prefix).append("_publication_digest=").append(source.publicationDigest()).append('\n');
        out.append(prefix).append("_source_file_key=").append(escape(source.sourceFileKey())).append('\n');
        for (ChunkIdentity chunk : source.chunks()) {
            out.append(prefix).append("_chunk[").append(chunk.file()).append("]=")
                    .append(chunk.compressedSha256()).append(',').append(escape(chunk.realPath()))
                    .append(',').append(escape(chunk.fileKey())).append('\n');
        }
    }

    private static String canonicalMetadata(Metadata metadata) {
        try {
            return CompleteRunAudioJson.writeMetadata(metadata);
        } catch (IOException impossible) {
            throw new AssertionError("validated metadata could not be canonicalized", impossible);
        }
    }

    private static void appendContext(StringBuilder out, String side, Context context) {
        for (RecordView view : context.before()) appendView(out, side + "_before", view);
        if (context.current() != null) appendView(out, side + "_current", context.current());
        for (RecordView view : context.after()) appendView(out, side + "_after", view);
    }

    private static void appendView(StringBuilder out, String label, RecordView view) {
        out.append(label).append('[').append(view.streamIndex()).append("]=")
                .append(escape(view.canonicalJson())).append('\n');
    }

    private static String escape(String value) {
        if (value == null) return "null";
        return value.replace("\\", "\\\\").replace("\r", "\\r").replace("\n", "\\n");
    }

    private static void requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) throw new IllegalArgumentException(name + " must be non-blank");
    }
}
