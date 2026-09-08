package com.openggf.io;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static java.security.MessageDigest.getInstance;

/** A validated, bounded source of files owned by one mod. */
@com.openggf.game.ModApi
public sealed interface ModAssetRoot extends AutoCloseable
        permits AbstractModAssetRoot, PackedModAssetRoot, SnapshotModAssetRoot {
    byte[] readBounded(String normalizedEntry, long maxBytes) throws IOException;
    String describe();
    ModInputLimits limits();

    @Override
    void close() throws IOException;

    static PackedModAssetRoot jar(Path declaredRoot, Path jar, ModInputLimits limits) throws IOException {
        return new JarModAssetRoot(declaredRoot, jar, limits);
    }

    /** Opens a packed snapshot whose source size must equal the repository's reserved size. */
    static PackedModAssetRoot jar(Path declaredRoot, Path jar, ModInputLimits limits,
                                  long expectedSourceBytes) throws IOException {
        return new JarModAssetRoot(declaredRoot, jar, limits, expectedSourceBytes);
    }

    static PackedModAssetRoot jar(Path declaredRoot, Path jar) throws IOException {
        return jar(declaredRoot, jar, ModInputLimits.production());
    }

    static ModAssetRoot directory(Path declaredRoot, Path directory, ModInputLimits limits,
                                  DirectoryAccess access) throws IOException {
        return snapshotDirectory(declaredRoot, directory, limits, access);
    }

    static SnapshotModAssetRoot snapshotDirectory(Path declaredRoot, Path directory,
                                                   ModInputLimits limits,
                                                   DirectoryAccess access) throws IOException {
        Objects.requireNonNull(access, "access").requireDirectoryAllowed();
        return new DirectoryModAssetRoot(declaredRoot, directory, limits, access);
    }

    static ModAssetRoot forTests(String description, ModInputLimits limits) {
        return new InMemoryModAssetRoot(description, limits);
    }

    static ModAssetRoot forTests(String description) {
        return forTests(description, ModInputLimits.production());
    }

    /** Non-owning creator view; closing it cannot close the runtime-owned snapshot. */
    static ModAssetRoot nonClosingView(ModAssetRoot delegate) {
        return new NonClosingModAssetRoot(delegate);
    }

    static String requireNormalizedEntry(String entry) {
        Objects.requireNonNull(entry, "entry");
        if (entry.isEmpty() || entry.indexOf('\0') >= 0 || entry.indexOf('\\') >= 0
                || entry.startsWith("/") || hasWindowsDrivePrefix(entry) || Path.of(entry).isAbsolute()) {
            throw new IllegalArgumentException("Entry must be a normalized relative forward-slash path: " + entry);
        }
        for (String segment : entry.split("/", -1)) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) {
                throw new IllegalArgumentException("Invalid entry path segment: " + entry);
            }
        }
        return entry;
    }

    private static boolean hasWindowsDrivePrefix(String entry) {
        return entry.length() >= 2 && entry.charAt(1) == ':'
                && ((entry.charAt(0) >= 'A' && entry.charAt(0) <= 'Z')
                || (entry.charAt(0) >= 'a' && entry.charAt(0) <= 'z'));
    }
}

@com.openggf.game.ModApi
abstract sealed class AbstractModAssetRoot implements ModAssetRoot
        permits JarModAssetRoot, DirectoryModAssetRoot, InMemoryModAssetRoot,
        NonClosingModAssetRoot {
    private final ModInputLimits limits;
    private final AtomicLong cumulativeReadBytes = new AtomicLong();
    private boolean closed;

    AbstractModAssetRoot(ModInputLimits limits) {
        this.limits = Objects.requireNonNull(limits, "limits");
    }

    @Override
    public final ModInputLimits limits() {
        return limits;
    }

    final long checkedCap(long requestedMax) throws IOException {
        ensureOpen();
        if (requestedMax <= 0) {
            throw new IllegalArgumentException("maxBytes must be positive");
        }
        if (requestedMax > limits.maxAssetBytes()) {
            throw new IllegalArgumentException("Requested cap exceeds root asset limit");
        }
        return Math.min(requestedMax, limits.maxAssetBytes());
    }

    final void ensureOpen() throws IOException {
        if (closed) {
            throw new IOException("Mod asset root is closed: " + describe());
        }
    }

    final void markClosed() {
        closed = true;
    }

    // Reservations and rollback form one per-root transaction. Interleaving
    // partial reservations can reject every competing reader even when complete
    // reads fit, or reject readers using bytes an unsuccessful read will release.
    final synchronized byte[] readFullyBounded(InputStream input, long declaredSize, long cap) throws IOException {
        if (declaredSize > cap) {
            throw new IOException("Asset declared size " + declaredSize + " exceeds limit " + cap);
        }
        int initial = declaredSize >= 0 ? Math.toIntExact(Math.min(declaredSize, 8192)) : 8192;
        ByteArrayOutputStream output = new ByteArrayOutputStream(initial);
        byte[] buffer = new byte[8192];
        long count = 0;
        long reserved = 0;
        try {
            int read;
            while ((read = input.read(buffer)) != -1) {
                count += read;
                if (count > cap) {
                    throw new IOException("Asset stream exceeds limit " + cap);
                }
                reserveValidationBytes(read);
                reserved += read;
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        } catch (IOException | RuntimeException e) {
            if (reserved != 0) {
                cumulativeReadBytes.addAndGet(-reserved);
            }
            throw e;
        }
    }

    final long validateFullyBounded(InputStream input, long cap) throws IOException {
        ensureOpen();
        byte[] buffer = new byte[8192];
        long count = 0;
        int read;
        while ((read = input.read(buffer)) != -1) {
            count = Math.addExact(count, read);
            if (count > cap) {
                throw new IOException("Asset stream exceeds limit " + cap);
            }
            reserveValidationBytes(read);
        }
        return count;
    }

    private void reserveValidationBytes(int bytes) throws IOException {
        while (true) {
            long current = cumulativeReadBytes.get();
            long updated;
            try {
                updated = Math.addExact(current, bytes);
            } catch (ArithmeticException e) {
                throw new IOException("Mod validation read budget overflow", e);
            }
            if (updated > limits.maxModValidationBytes()) {
                throw new IOException("Cumulative mod reads exceed validation budget "
                        + limits.maxModValidationBytes());
            }
            if (cumulativeReadBytes.compareAndSet(current, updated)) {
                return;
            }
        }
    }

    static Path containedRealPath(Path declaredRoot, Path target) throws IOException {
        Objects.requireNonNull(declaredRoot, "declaredRoot");
        Objects.requireNonNull(target, "target");
        Path rootReal = declaredRoot.toRealPath();
        Path targetReal = target.toRealPath();
        if (!targetReal.startsWith(rootReal)) {
            throw new IOException("Path escapes declared mod root: " + target);
        }
        return targetReal;
    }

    static void registerName(String name, ModInputLimits limits, Set<String> exact,
                             Set<String> folded, long[] aggregate) throws IOException {
        registerName(name, name, limits, exact, folded, aggregate);
    }

    static void registerName(String normalizedName, String budgetedName, ModInputLimits limits,
                             Set<String> exact, Set<String> folded, long[] aggregate) throws IOException {
        try {
            ModAssetRoot.requireNormalizedEntry(normalizedName);
        } catch (IllegalArgumentException e) {
            throw new IOException("Invalid mod entry name: " + budgetedName, e);
        }
        int bytes = budgetedName.getBytes(StandardCharsets.UTF_8).length;
        if (bytes > limits.maxEntryNameBytes()) {
            throw new IOException("Entry name exceeds byte limit: " + budgetedName);
        }
        aggregate[0] = Math.addExact(aggregate[0], bytes);
        if (aggregate[0] > limits.maxAggregateEntryNameBytes()) {
            throw new IOException("Aggregate entry-name bytes exceed limit");
        }
        if (!exact.add(normalizedName)) {
            throw new IOException("Duplicate entry name: " + budgetedName);
        }
        if (!folded.add(normalizedName.toLowerCase(Locale.ROOT))) {
            throw new IOException("Case-folding entry collision: " + budgetedName);
        }
    }
}

@com.openggf.game.ModApi
final class NonClosingModAssetRoot extends AbstractModAssetRoot {
    private final ModAssetRoot delegate;

    NonClosingModAssetRoot(ModAssetRoot delegate) {
        super(Objects.requireNonNull(delegate, "delegate").limits());
        this.delegate = delegate;
    }

    @Override public byte[] readBounded(String normalizedEntry, long maxBytes) throws IOException {
        ensureOpen();
        return delegate.readBounded(normalizedEntry, maxBytes);
    }

    @Override public String describe() { return delegate.describe(); }

    /** Closes only this view; the boot runtime retains ownership of the snapshot. */
    @Override public void close() { markClosed(); }
}

@com.openggf.game.ModApi
final class JarModAssetRoot extends AbstractModAssetRoot implements PackedModAssetRoot {
    private final String sourceDescription;
    private final ModAssetSnapshot snapshot;
    private final Path jarPath;
    private final ZipFile zip;
    private final java.util.List<String> entryNames;

    JarModAssetRoot(Path declaredRoot, Path jar, ModInputLimits limits) throws IOException {
        this(declaredRoot, jar, limits, null, SnapshotHook.NONE);
    }

    JarModAssetRoot(Path declaredRoot, Path jar, ModInputLimits limits,
                    long expectedSourceBytes) throws IOException {
        this(declaredRoot, jar, limits, expectedSourceBytes, SnapshotHook.NONE);
    }

    JarModAssetRoot(Path declaredRoot, Path jar, ModInputLimits limits, SnapshotHook hook) throws IOException {
        this(declaredRoot, jar, limits, null, hook);
    }

    private JarModAssetRoot(Path declaredRoot, Path jar, ModInputLimits limits,
                            Long expectedSourceBytes, SnapshotHook hook) throws IOException {
        super(limits);
        Path source = containedRealPath(declaredRoot, jar);
        this.sourceDescription = source.toString();
        if (expectedSourceBytes != null && expectedSourceBytes < 0) {
            throw new IllegalArgumentException("expectedSourceBytes must not be negative");
        }
        long sourceBytes = Files.size(source);
        if (Files.isSymbolicLink(jar.toAbsolutePath())
                || !Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)
                || sourceBytes > limits.maxJarBytes()
                || (expectedSourceBytes != null && sourceBytes != expectedSourceBytes)) {
            throw new IOException("Invalid, symlinked, resized, or oversized mod jar: " + source);
        }
        long snapshotCap = expectedSourceBytes == null ? limits.maxJarBytes() : expectedSourceBytes;
        ModAssetSnapshot created = ModAssetSnapshot.file(source, snapshotCap, hook);
        ZipFile opened = null;
        try {
            if (expectedSourceBytes != null && Files.size(created.content()) != expectedSourceBytes) {
                throw new IOException("Mod jar snapshot size differs from reserved source size: " + source);
            }
            opened = new ZipFile(created.content().toFile());
            this.entryNames = validateCentralDirectory(opened);
            validateInflatedEntries(opened);
        } catch (IOException | RuntimeException e) {
            if (opened != null) {
                try {
                    opened.close();
                } catch (IOException cleanupFailure) {
                    e.addSuppressed(cleanupFailure);
                }
            }
            try {
                created.close();
            } catch (IOException cleanupFailure) {
                e.addSuppressed(cleanupFailure);
            }
            throw e;
        }
        this.snapshot = created;
        this.jarPath = created.content();
        this.zip = opened;
    }

    private java.util.List<String> validateCentralDirectory(ZipFile opened) throws IOException {
        java.util.ArrayList<String> validatedNames = new java.util.ArrayList<>();
        Set<String> exact = new HashSet<>();
        Set<String> folded = new HashSet<>();
        long[] nameBytes = {0};
        long totalDeclared = 0;
        int count = 0;
        Enumeration<? extends ZipEntry> entries = opened.entries();
        while (entries.hasMoreElements()) {
            ZipEntry entry = entries.nextElement();
            if (++count > limits().maxJarEntries()) {
                throw new IOException("Jar entry count exceeds limit");
            }
            String rawName = entry.getName();
            if (entry.isDirectory()) {
                if (!rawName.endsWith("/") || rawName.length() == 1
                        || rawName.charAt(rawName.length() - 2) == '/') {
                    throw new IOException("Jar directory entry must have exactly one terminal slash: " + rawName);
                }
                registerName(rawName.substring(0, rawName.length() - 1), rawName,
                        limits(), exact, folded, nameBytes);
            } else {
                registerName(rawName, limits(), exact, folded, nameBytes);
                validatedNames.add(rawName);
            }
            long size = entry.getSize();
            if (size > limits().maxAssetBytes()) {
                throw new IOException("Jar entry exceeds asset limit: " + entry.getName());
            }
            if (size >= 0) {
                totalDeclared = Math.addExact(totalDeclared, size);
                if (totalDeclared > limits().maxModValidationBytes()) {
                    throw new IOException("Jar declared bytes exceed validation budget");
                }
            }
        }
        return java.util.List.copyOf(validatedNames);
    }

    private void validateInflatedEntries(ZipFile opened) throws IOException {
        Enumeration<? extends ZipEntry> entries = opened.entries();
        while (entries.hasMoreElements()) {
            ZipEntry entry = entries.nextElement();
            if (entry.isDirectory()) {
                continue;
            }
            try (InputStream input = opened.getInputStream(entry)) {
                validateFullyBounded(input, limits().maxAssetBytes());
            }
        }
    }

    @Override
    public byte[] readBounded(String normalizedEntry, long maxBytes) throws IOException {
        long cap = checkedCap(maxBytes);
        String name = ModAssetRoot.requireNormalizedEntry(normalizedEntry);
        ZipEntry entry = zip.getEntry(name);
        if (entry == null || entry.isDirectory()) {
            throw new IOException("Missing mod asset: " + name);
        }
        try (InputStream input = zip.getInputStream(entry)) {
            return readFullyBounded(input, entry.getSize(), cap);
        }
    }

    @Override
    public String describe() { return sourceDescription; }

    @Override
    public java.util.List<String> validatedEntryNames() throws IOException {
        ensureOpen();
        return entryNames;
    }

    @Override
    public String immutableSha256() throws IOException {
        ensureOpen();
        return snapshot.sha256();
    }

    @Override
    public Path immutableContentPath() throws IOException {
        ensureOpen();
        return jarPath;
    }

    @Override
    public void close() throws IOException {
        IOException failure = null;
        try {
            zip.close();
        } catch (IOException e) {
            failure = e;
        } finally {
            markClosed();
        }
        try {
            snapshot.close();
        } catch (IOException e) {
            if (failure != null) {
                failure.addSuppressed(e);
            } else {
                failure = e;
            }
        }
        if (failure != null) {
            throw failure;
        }
    }
}

/** Immutable snapshot root for explicitly trusted development and test directories only. */
@com.openggf.game.ModApi
final class DirectoryModAssetRoot extends AbstractModAssetRoot implements SnapshotModAssetRoot {
    private final String sourceDescription;
    private final ModAssetSnapshot snapshot;
    private final Path root;
    private java.util.List<String> entryNames = java.util.List.of();
    private boolean directoryClosed;

    DirectoryModAssetRoot(Path declaredRoot, Path directory, ModInputLimits limits,
                          DirectoryAccess access) throws IOException {
        this(declaredRoot, directory, limits, access, SnapshotHook.NONE);
    }

    DirectoryModAssetRoot(Path declaredRoot, Path directory, ModInputLimits limits,
                          DirectoryAccess access, SnapshotHook hook) throws IOException {
        super(limits);
        Objects.requireNonNull(access, "access").requireDirectoryAllowed();
        Path source = containedRealPath(declaredRoot, directory);
        this.sourceDescription = source.toString();
        if (Files.isSymbolicLink(directory.toAbsolutePath())
                || !Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Dev mod asset root must be a non-symlink directory: " + source);
        }
        ModAssetSnapshot created = ModAssetSnapshot.directory(source, limits, hook);
        this.snapshot = created;
        this.root = created.content();
        try {
            validateTree();
        } catch (IOException | RuntimeException e) {
            try {
                created.close();
            } catch (IOException cleanupFailure) {
                e.addSuppressed(cleanupFailure);
            }
            throw e;
        }
    }

    private void validateTree() throws IOException {
        Set<String> exact = new HashSet<>();
        Set<String> folded = new HashSet<>();
        long[] nameBytes = {0};
        long[] total = {0};
        int[] count = {0};
        java.util.ArrayList<String> validatedNames = new java.util.ArrayList<>();
        try (var paths = Files.walk(root)) {
            paths.filter(path -> !path.equals(root)).forEach(path -> {
                try {
                    if (Files.isSymbolicLink(path)
                            || (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)
                            && !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))) {
                        throw new IOException("Symbolic links and special files are not allowed in directory roots: "
                                + path);
                    }
                    Path real = path.toRealPath();
                    if (!real.startsWith(root)) {
                        throw new IOException("Directory entry escapes root: " + path);
                    }
                    if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                        return;
                    }
                    if (++count[0] > limits().maxJarEntries()) {
                        throw new IOException("Directory entry count exceeds limit");
                    }
                    String name = ModAssetSnapshot.normalizedRelativeName(root.relativize(path));
                    registerName(name, limits(), exact, folded, nameBytes);
                    validatedNames.add(name);
                    long size = Files.size(real);
                    if (size > limits().maxAssetBytes()) {
                        throw new IOException("Directory asset exceeds limit: " + name);
                    }
                    total[0] = Math.addExact(total[0], size);
                    if (total[0] > limits().maxModValidationBytes()) {
                        throw new IOException("Directory bytes exceed validation budget");
                    }
                } catch (IOException e) {
                    throw new DirectoryValidationException(e);
                }
            });
        } catch (DirectoryValidationException e) {
            throw e.ioCause;
        }
        validatedNames.sort(String::compareTo);
        entryNames = java.util.List.copyOf(validatedNames);
    }

    @Override
    public byte[] readBounded(String normalizedEntry, long maxBytes) throws IOException {
        long cap = checkedCap(maxBytes);
        String name = ModAssetRoot.requireNormalizedEntry(normalizedEntry);
        Path unresolved = rejectSymlinkPath(name);
        Path candidate = unresolved.toRealPath();
        if (!candidate.startsWith(root) || !Files.isRegularFile(candidate)) {
            throw new IOException("Missing or escaping mod asset: " + name);
        }
        long size = Files.size(candidate);
        try (InputStream input = Files.newInputStream(candidate)) {
            return readFullyBounded(input, size, cap);
        }
    }

    private Path rejectSymlinkPath(String normalizedEntry) throws IOException {
        Path relative = Path.of(normalizedEntry);
        Path current = root;
        for (int i = 0; i < relative.getNameCount(); i++) {
            current = current.resolve(relative.getName(i));
            if (Files.isSymbolicLink(current)) {
                throw new IOException("Symbolic-link paths are not readable from directory roots: "
                        + normalizedEntry);
            }
            boolean last = i == relative.getNameCount() - 1;
            if (!last && !Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Missing directory in mod asset path: " + normalizedEntry);
            }
            if (last && !Files.isRegularFile(current, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Missing mod asset: " + normalizedEntry);
            }
        }
        return current;
    }

    @Override
    public String describe() { return sourceDescription; }

    @Override public java.util.List<String> validatedEntryNames() throws IOException {
        ensureOpen(); return entryNames;
    }

    @Override public String immutableSha256() throws IOException {
        ensureOpen();
        try {
            java.security.MessageDigest digest = getInstance("SHA-256");
            for (String name : entryNames) {
                byte[] nameBytes = name.getBytes(StandardCharsets.UTF_8);
                Path entry = root.resolve(name);
                updateInt(digest, nameBytes.length);
                digest.update(nameBytes);
                updateLong(digest, Files.size(entry));
                try (InputStream input = Files.newInputStream(entry)) {
                    byte[] buffer = new byte[8192];
                    int read;
                    while ((read = input.read(buffer)) != -1) {
                        digest.update(buffer, 0, read);
                    }
                }
            }
            return java.util.HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static void updateInt(java.security.MessageDigest digest, int value) {
        digest.update((byte) (value >>> 24));
        digest.update((byte) (value >>> 16));
        digest.update((byte) (value >>> 8));
        digest.update((byte) value);
    }

    private static void updateLong(java.security.MessageDigest digest, long value) {
        updateInt(digest, (int) (value >>> 32));
        updateInt(digest, (int) value);
    }

    @Override public Path immutableContentPath() throws IOException { ensureOpen(); return root; }

    @Override
    public void close() throws IOException {
        if (directoryClosed) return;
        directoryClosed=true;
        markClosed();
        snapshot.close();
    }

    private static final class DirectoryValidationException extends RuntimeException {
        private final IOException ioCause;
        private DirectoryValidationException(IOException cause) { super(cause); this.ioCause = cause; }
    }
}

@com.openggf.game.ModApi
final class InMemoryModAssetRoot extends AbstractModAssetRoot {
    private final String description;

    InMemoryModAssetRoot(String description, ModInputLimits limits) {
        super(limits);
        this.description = Objects.requireNonNull(description, "description");
    }

    @Override
    public byte[] readBounded(String normalizedEntry, long maxBytes) throws IOException {
        checkedCap(maxBytes);
        ModAssetRoot.requireNormalizedEntry(normalizedEntry);
        throw new IOException("No in-memory asset registered: " + normalizedEntry);
    }

    @Override
    public String describe() { return description; }

    @Override
    public void close() { markClosed(); }
}
