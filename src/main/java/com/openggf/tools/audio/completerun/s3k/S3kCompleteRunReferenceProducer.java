package com.openggf.tools.audio.completerun.s3k;

import com.openggf.tools.audio.completerun.CompleteRunAudioProducer;
import com.openggf.tools.audio.completerun.CompleteRunAudioTrace;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/** Reserved fixed S3K reference producer; publication remains unavailable until Task 7. */
public final class S3kCompleteRunReferenceProducer implements CompleteRunAudioProducer {
    private static final Path LAUNCHER = Path.of("bizhawk-headless/run-complete-audio.sh");
    private static final Path SERVICE_MANIFEST =
            Path.of("bizhawk-headless/fixtures/gpgx-audio-service-manifests-v1.json");

    @Override public void capture(Request request) throws Exception {
        validate(request);
        var binding = S3kCompleteRunAudioProfile.profile().producerBindings()
                .get(CompleteRunAudioTrace.ProducerKind.REFERENCE);
        if (binding instanceof CompleteRunAudioTrace.UnavailableProducerBinding unavailable) {
            throw new IllegalStateException("S3K reference producer is unavailable: " + unavailable.reason());
        }
        throw new IllegalStateException("S3K reference publication is reserved for Task 7");
    }

    void capturePipelineForTesting(Request request, CompleteRunAudioTrace.Metadata syntheticMetadata)
            throws Exception {
        validate(request);
        try (var raw = new com.openggf.tools.audio.completerun.TraceChaserAudioProcess()
                .capture(request, com.openggf.tools.audio.completerun.TraceChaserAudioProcess.Game.S3K)) {
            new S3kCompleteRunReferenceProjector().project(raw.raw(), request.rom(), request.output(),
                    syntheticMetadata, null);
        }
    }

    private static void validate(Request request) throws Exception {
        Objects.requireNonNull(request, "S3K reference request");
        if (request.producerKind() != CompleteRunAudioTrace.ProducerKind.REFERENCE) {
            throw new IllegalArgumentException("S3K reference producer requires REFERENCE kind");
        }
        if (!S3kCompleteRunAudioProfile.ID.equals(request.profileId())) {
            throw new IllegalArgumentException("S3K reference profile is not fixed");
        }
        var fixture = S3kCompleteRunAudioProfile.profile().fixture();
        requireDigest(file(request.rom(), "S3K ROM"), "SHA-1", fixture.romSha1(), "S3K ROM");
        requireDigest(file(request.bk2(), "S3K BK2"), "SHA-256", fixture.bk2Sha256(), "S3K BK2");
        requireDigest(file(request.runManifest(), "S3K run manifest"), "SHA-256",
                fixture.runManifestSha256(), "S3K run manifest");
        Path root = directory(request.referenceHome(), "TraceChaser root");
        file(root.resolve(LAUNCHER), "TraceChaser complete-audio launcher");
        file(root.resolve(SERVICE_MANIFEST), "TraceChaser service manifest");
        Path output = absolute(request.output(), "S3K capture output");
        if (Files.exists(output, LinkOption.NOFOLLOW_LINKS)) {
            throw new FileAlreadyExistsException(output.toString());
        }
        if (!Files.isDirectory(output.getParent(), LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("S3K capture output parent does not exist");
        }
    }

    private static Path file(Path value, String label) {
        Path path = absolute(value, label);
        if (!canonical(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException(label + " must be an ordinary non-symlink file");
        }
        return path;
    }

    private static Path directory(Path value, String label) {
        Path path = absolute(value, label);
        if (!canonical(path) || !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException(label + " must be an ordinary non-symlink directory");
        }
        return path;
    }

    private static Path absolute(Path value, String label) {
        Objects.requireNonNull(value, label);
        if (!value.isAbsolute() || !value.equals(value.normalize())) {
            throw new IllegalArgumentException(label + " must be an absolute normalized path");
        }
        return value;
    }

    private static boolean canonical(Path path) {
        try { return path.equals(path.toRealPath()); }
        catch (IOException missing) { return false; }
    }

    private static void requireDigest(Path path, String algorithm, String expected, String label)
            throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance(algorithm);
            try (InputStream input = Files.newInputStream(path)) {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = input.read(buffer)) >= 0) digest.update(buffer, 0, count);
            }
            if (!expected.equals(HexFormat.of().formatHex(digest.digest()))) {
                throw new IllegalArgumentException(label + " identity does not match the fixed profile");
            }
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }
}
