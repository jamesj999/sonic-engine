package com.openggf.tools.audio.completerun;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Retained filesystem identity for a private temporary directory and all of its ancestors. */
final class OwnedTemporaryDirectory {
    private final Path path;
    private final Object fileKey;
    private final List<Identity> ancestors;
    private boolean deleted;

    private OwnedTemporaryDirectory(Path path, Object fileKey, List<Identity> ancestors) {
        this.path = path;
        this.fileKey = fileKey;
        this.ancestors = ancestors;
    }

    static OwnedTemporaryDirectory create(Path parent, String prefix) throws IOException {
        List<Identity> ancestors = identities(parent);
        Path path = Files.createTempDirectory(parent, prefix);
        Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rwx------"));
        BasicFileAttributes attributes = directory(path);
        verify(ancestors);
        return new OwnedTemporaryDirectory(path, key(attributes), ancestors);
    }

    Path path() { return path; }

    void verifyOwned() throws IOException {
        verify(ancestors);
        BasicFileAttributes attributes = directory(path);
        if (!fileKey.equals(key(attributes))) {
            throw new IOException("owned temporary directory identity changed: " + path);
        }
    }

    void delete() throws IOException {
        if (deleted) return;
        verify(ancestors);
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("owned temporary directory disappeared: " + path);
        }
        verifyOwned();
        try (var paths = Files.walk(path)) {
            try {
                for (Path entry : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(entry);
            } catch (UncheckedIOException failure) {
                throw failure.getCause();
            }
        }
        deleted = true;
    }

    private static List<Identity> identities(Path leaf) throws IOException {
        List<Path> paths = new ArrayList<>();
        for (Path cursor = leaf; cursor != null; cursor = cursor.getParent()) paths.add(cursor);
        List<Identity> result = new ArrayList<>(paths.size());
        for (int index = paths.size() - 1; index >= 0; index--) {
            Path path = paths.get(index);
            result.add(new Identity(path, key(directory(path))));
        }
        return List.copyOf(result);
    }

    private static void verify(List<Identity> identities) throws IOException {
        for (Identity identity : identities) {
            if (!identity.fileKey().equals(key(directory(identity.path())))) {
                throw new IOException("owned temporary directory ancestor changed: " + identity.path());
            }
        }
    }

    private static BasicFileAttributes directory(Path path) throws IOException {
        BasicFileAttributes attributes = Files.readAttributes(
                path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isDirectory() || Files.isSymbolicLink(path)) {
            throw new IOException("owned temporary path is not an ordinary directory: " + path);
        }
        return attributes;
    }

    private static Object key(BasicFileAttributes attributes) throws IOException {
        Object key = attributes.fileKey();
        if (key == null) throw new IOException("filesystem does not expose stable directory identity");
        return key;
    }

    private record Identity(Path path, Object fileKey) {
        private Identity {
            Objects.requireNonNull(path, "owned path");
            Objects.requireNonNull(fileKey, "owned file key");
        }
    }
}
