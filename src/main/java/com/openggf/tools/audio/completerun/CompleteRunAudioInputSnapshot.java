package com.openggf.tools.audio.completerun;

import com.openggf.tools.audio.completerun.CompleteRunAudioTrace.CompleteRunFixture;
import com.openggf.tools.audio.completerun.CompleteRunAudioTrace.ProducerKind;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Private snapshot binding for one producer invocation. */
final class CompleteRunAudioInputSnapshot implements AutoCloseable {
    private final OwnedTemporaryDirectory inputs;
    private final OwnedTemporaryDirectory publicationStage;
    private final CompleteRunAudioProducer.Request request;
    private final Path output;
    private final List<PathIdentity> outputAncestors;
    private boolean closed;

    @FunctionalInterface
    interface Consumer {
        void capture(CompleteRunAudioProducer.Request request) throws Exception;
    }

    private CompleteRunAudioInputSnapshot(OwnedTemporaryDirectory inputs,
            OwnedTemporaryDirectory publicationStage, CompleteRunAudioProducer.Request request,
            Path output, List<PathIdentity> outputAncestors) {
        this.inputs = inputs;
        this.publicationStage = publicationStage;
        this.request = request;
        this.output = output;
        this.outputAncestors = outputAncestors;
    }

    static CompleteRunAudioInputSnapshot bind(CompleteRunAudioProducer.Request source,
            CompleteRunAudioProfile profile) throws Exception {
        Objects.requireNonNull(source, "producer request");
        Objects.requireNonNull(profile, "producer profile");
        Path output = absolute(source.output(), "producer output");
        if (Files.exists(output, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("producer output already exists");
        }
        Path parent = plainDirectory(output.getParent(), "producer output parent");

        if (source.producerKind() == ProducerKind.REFERENCE) {
            Path referenceHome = plainDirectory(source.referenceHome(), "reference home");
            if (parent.startsWith(referenceHome)) {
                throw new IllegalArgumentException("producer output must not overlap the reference home");
            }
            CompleteRunAudioTool.verifyReferenceHome(referenceHome, profile);
        } else if (source.referenceHome() != null) {
            throw new IllegalArgumentException("engine producer must not receive a reference home");
        }

        List<PathIdentity> outputAncestors = directoryIdentities(parent, "producer output ancestor");
        OwnedTemporaryDirectory inputs = OwnedTemporaryDirectory.create(parent, ".audio-inputs-");
        OwnedTemporaryDirectory publicationStage = null;
        try {
            verifyDirectoryIdentities(outputAncestors, "producer output ancestor");
            Path root = inputs.path();
            Path rom = copyFile(source.rom(), root.resolve("rom"), "ROM");
            Path bk2 = copyFile(source.bk2(), root.resolve("movie.bk2"), "BK2 movie");
            Path manifest = copyFile(source.runManifest(), root.resolve("run-manifest.json"), "run manifest");
            Path referenceHome = null;
            if (source.producerKind() == ProducerKind.REFERENCE) {
                referenceHome = copyTree(source.referenceHome(), root.resolve("reference-home"));
            }

            CompleteRunFixture fixture = profile.fixture();
            requireDigest(rom, "SHA-1", fixture.romSha1(), "ROM");
            requireDigest(bk2, "SHA-256", fixture.bk2Sha256(), "BK2 movie");
            requireDigest(manifest, "SHA-256", fixture.runManifestSha256(), "run manifest");
            if (referenceHome != null) CompleteRunAudioTool.verifyReferenceHome(referenceHome, profile);

            publicationStage = OwnedTemporaryDirectory.create(parent, ".audio-publication-");
            Path stagedOutput = publicationStage.path().resolve("capture");
            var bound = new CompleteRunAudioProducer.Request(source.producerKind(), source.profileId(),
                    rom, bk2, manifest, referenceHome, stagedOutput);
            return new CompleteRunAudioInputSnapshot(
                    inputs, publicationStage, bound, output, outputAncestors);
        } catch (Exception | Error failure) {
            if (publicationStage != null) try { publicationStage.delete(); }
            catch (IOException cleanup) { failure.addSuppressed(cleanup); }
            try { inputs.delete(); }
            catch (IOException cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }

    static void withBoundRequest(CompleteRunAudioProducer.Request source, CompleteRunAudioProfile profile,
            Consumer consumer) throws Exception {
        Objects.requireNonNull(consumer, "producer consumer");
        CompleteRunAudioInputSnapshot snapshot = bind(source, profile);
        try {
            consumer.capture(snapshot.request);
            snapshot.close();
            snapshot.publish();
            snapshot = null;
        } catch (Exception | Error failure) {
            if (snapshot != null) {
                try { snapshot.close(); }
                catch (IOException cleanup) { failure.addSuppressed(cleanup); }
            }
            if (snapshot != null) try { snapshot.publicationStage.delete(); }
            catch (IOException cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }

    CompleteRunAudioProducer.Request request() { return request; }
    Path root() { return inputs.path(); }

    private void publish() throws IOException {
        Path stagedOutput = request.output();
        if (!stagedOutput.getParent().equals(publicationStage.path())) {
            throw new IllegalArgumentException("private publication output changed parents");
        }
        verifyDirectoryIdentities(outputAncestors, "producer output ancestor");
        publish(stagedOutput, output, publicationStage, outputAncestors);
    }

    @Override public void close() throws IOException {
        if (closed) return;
        inputs.delete();
        closed = true;
    }

    private static Path copyTree(Path sourceValue, Path target) throws IOException {
        Path source = plainDirectory(sourceValue, "reference home");
        Map<Path, Object> directoryKeys = new java.util.HashMap<>();
        Files.walkFileTree(source, new SimpleFileVisitor<>() {
            @Override public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes)
                    throws IOException {
                requireDirectory(directory, attributes, "reference home directory");
                if (attributes.fileKey() == null) {
                    throw new IllegalArgumentException(
                            "reference home directory has no stable filesystem identity");
                }
                Path relative = source.relativize(directory);
                Path destination = target.resolve(relative.toString());
                Files.createDirectory(destination);
                setPermissions(destination, permissions(directory));
                directoryKeys.put(directory, attributes.fileKey());
                return FileVisitResult.CONTINUE;
            }

            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attributes)
                    throws IOException {
                if (!attributes.isRegularFile() || Files.isSymbolicLink(file) || linkCount(file) != 1) {
                    throw new IllegalArgumentException("reference home contains a linked or special entry");
                }
                copyFile(file, target.resolve(source.relativize(file).toString()), "reference home file");
                return FileVisitResult.CONTINUE;
            }

            @Override public FileVisitResult postVisitDirectory(Path directory, IOException failure)
                    throws IOException {
                if (failure != null) throw failure;
                BasicFileAttributes after = Files.readAttributes(
                        directory, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                requireDirectory(directory, after, "reference home directory");
                if (!Objects.equals(directoryKeys.get(directory), after.fileKey())) {
                    throw new IllegalArgumentException("reference home changed while it was copied");
                }
                return FileVisitResult.CONTINUE;
            }
        });
        return target;
    }

    private static Path copyFile(Path sourceValue, Path target, String label) throws IOException {
        Path source = absolute(sourceValue, label);
        List<PathIdentity> ancestors = directoryIdentities(source.getParent(), label + " ancestor");
        BasicFileAttributes before = plainFileAttributes(source, label);
        Set<PosixFilePermission> permissions = permissions(source);
        try (FileChannel input = FileChannel.open(source, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
             FileChannel output = FileChannel.open(target, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            ByteBuffer buffer = ByteBuffer.allocateDirect(64 * 1024);
            int count;
            while ((count = input.read(buffer)) != -1) {
                if (count == 0) continue;
                buffer.flip();
                while (buffer.hasRemaining()) output.write(buffer);
                buffer.clear();
            }
            output.force(true);
        }
        setPermissions(target, permissions);
        BasicFileAttributes after = plainFileAttributes(source, label);
        if (!Objects.equals(before.fileKey(), after.fileKey()) || before.size() != after.size()
                || !before.lastModifiedTime().equals(after.lastModifiedTime())) {
            throw new IllegalArgumentException(label + " changed while it was copied");
        }
        verifyDirectoryIdentities(ancestors, label + " ancestor");
        plainFileAttributes(target, "private " + label);
        return target;
    }

    private static BasicFileAttributes plainFileAttributes(Path path, String label) throws IOException {
        BasicFileAttributes attributes = Files.readAttributes(
                path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isRegularFile() || Files.isSymbolicLink(path) || linkCount(path) != 1
                || attributes.fileKey() == null || !path.equals(path.toRealPath())) {
            throw new IllegalArgumentException(label + " must be an ordinary singly-linked file");
        }
        return attributes;
    }

    private static void requireDirectory(Path path, BasicFileAttributes attributes, String label) {
        if (!attributes.isDirectory() || Files.isSymbolicLink(path)) {
            throw new IllegalArgumentException(label + " must be an ordinary non-symlink directory");
        }
    }

    private static Path plainDirectory(Path value, String label) throws IOException {
        Path path = absolute(value, label);
        BasicFileAttributes attributes = Files.readAttributes(
                path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        requireDirectory(path, attributes, label);
        if (!path.equals(path.toRealPath())) {
            throw new IllegalArgumentException(label + " must be canonical");
        }
        verifyDirectoryIdentities(directoryIdentities(path, label), label);
        return path;
    }

    private static List<PathIdentity> directoryIdentities(Path leaf, String label) throws IOException {
        List<Path> paths = new ArrayList<>();
        for (Path cursor = leaf; cursor != null; cursor = cursor.getParent()) paths.add(cursor);
        List<PathIdentity> identities = new ArrayList<>(paths.size());
        for (int index = paths.size() - 1; index >= 0; index--) {
            Path path = paths.get(index);
            BasicFileAttributes attributes = Files.readAttributes(
                    path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            requireDirectory(path, attributes, label);
            if (attributes.fileKey() == null) {
                throw new IllegalArgumentException(label + " has no stable filesystem identity");
            }
            identities.add(new PathIdentity(path, attributes.fileKey()));
        }
        return List.copyOf(identities);
    }

    private static void verifyDirectoryIdentities(List<PathIdentity> identities, String label) throws IOException {
        for (PathIdentity identity : identities) {
            BasicFileAttributes attributes = Files.readAttributes(
                    identity.path(), BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            requireDirectory(identity.path(), attributes, label);
            if (!identity.fileKey().equals(attributes.fileKey())) {
                throw new IllegalArgumentException(label + " changed while inputs were copied");
            }
        }
    }

    private static Path absolute(Path value, String label) {
        Objects.requireNonNull(value, label);
        if (!value.isAbsolute() || !value.equals(value.normalize())) {
            throw new IllegalArgumentException(label + " must be an absolute normalized path");
        }
        return value;
    }

    private static long linkCount(Path path) throws IOException {
        Object links = Files.getAttribute(path, "unix:nlink", LinkOption.NOFOLLOW_LINKS);
        return ((Number) links).longValue();
    }

    private static Set<PosixFilePermission> permissions(Path path) throws IOException {
        return Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS);
    }

    private static void setPermissions(Path path, Set<PosixFilePermission> permissions) throws IOException {
        Files.setPosixFilePermissions(path, permissions);
    }

    private static void requireDigest(Path path, String algorithm, String expected, String label)
            throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance(algorithm);
            try (var input = Files.newInputStream(path)) {
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

    private static void publish(Path staged, Path target, OwnedTemporaryDirectory publicationStage,
            List<PathIdentity> outputAncestors) throws IOException {
        Publication publication = publication(staged);
        OwnedTemporaryDirectory finalBacking =
                OwnedTemporaryDirectory.create(target.getParent(), ".audio-published-");
        try {
            verifyDirectoryIdentities(outputAncestors, "producer output ancestor");
            try (var entries = Files.list(publication.backing())) {
                for (Path entry : entries.toList()) {
                    Files.move(entry, finalBacking.path().resolve(entry.getFileName()),
                            StandardCopyOption.ATOMIC_MOVE);
                }
            }
            Files.delete(publication.backing());
            Files.delete(staged);
            publicationStage.delete();
            verifyDirectoryIdentities(outputAncestors, "producer output ancestor");
            Files.createSymbolicLink(target, finalBacking.path().getFileName());
        } catch (Throwable failure) {
            try { finalBacking.delete(); }
            catch (IOException cleanup) { failure.addSuppressed(cleanup); }
            if (failure instanceof IOException io) throw io;
            if (failure instanceof RuntimeException runtime) throw runtime;
            throw new IOException("atomic capture publication failed", failure);
        }
    }

    private static Publication publication(Path staged) throws IOException {
        if (!Files.isSymbolicLink(staged)) {
            throw new IOException("producer did not create a canonical capture-store link");
        }
        Path relative = Files.readSymbolicLink(staged);
        if (relative.isAbsolute() || !relative.equals(relative.normalize()) || relative.getNameCount() != 1
                || !relative.getFileName().toString().startsWith(".audio-published-")) {
            throw new IOException("producer capture-store link has an invalid backing target");
        }
        Path backing = staged.getParent().resolve(relative).normalize();
        if (!backing.getParent().equals(staged.getParent())
                || !Files.isDirectory(backing, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(backing) || !backing.equals(backing.toRealPath())) {
            throw new IOException("producer capture-store backing is not an ordinary private directory");
        }
        return new Publication(backing);
    }

    private record PathIdentity(Path path, Object fileKey) { }
    private record Publication(Path backing) { }
}
