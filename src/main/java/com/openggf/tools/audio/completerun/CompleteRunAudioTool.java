package com.openggf.tools.audio.completerun;

import com.openggf.tools.audio.completerun.CompleteRunAudioReport.Kind;
import com.openggf.tools.audio.completerun.CompleteRunAudioTrace.ProducerKind;
import java.io.PrintStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.LinkOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.HexFormat;
import java.util.Map;
import java.util.Comparator;
import java.util.Objects;

/** Fixed entry point for strict validation, create-new publication, and comparison. */
public final class CompleteRunAudioTool {
    private static final int MAX_INSTALLATION_ENTRIES = 200_000;
    private static final int MAX_INSTALLATION_PATH_BYTES = 16 * 1024 * 1024;
    public static final int SUCCESS = 0;
    public static final int USAGE_OR_SECURITY = 2;
    public static final int MISMATCH = 3;
    public static final int CAPTURE_FAILURE = 4;

    private CompleteRunAudioTool() { }

    public static void main(String[] args) {
        System.exit(run(args, System.out, System.err));
    }

    static int run(String[] args, PrintStream out, PrintStream error) {
        Objects.requireNonNull(args, "arguments");
        try {
            if (args.length == 2 && "producer-status".equals(args[0])) {
                safeIdentifier(args[1], "profile ID");
                if (!CompleteRunAudioProducerRegistry.knowsProfile(args[1])) {
                    throw new UsageFailure("unknown fixed producer profile");
                }
                if (!CompleteRunAudioProducerRegistry.isAvailable(args[1])) {
                    throw new CompleteRunAudioProducerRegistry.ProducerUnavailableException(
                            "fixed producer pair is not installed", null);
                }
                return SUCCESS;
            }
            if (args.length == 3 && "producer-kind-status".equals(args[0])) {
                ProducerKind kind = producer(args[1]);
                safeIdentifier(args[2], "profile ID");
                if (!CompleteRunAudioProducerRegistry.knowsProfile(args[2])) {
                    throw new UsageFailure("unknown fixed producer profile");
                }
                CompleteRunAudioProducerRegistry.requireAvailable(args[2], kind);
                return SUCCESS;
            }
            if (args.length == 3 && "verify-reference-home".equals(args[0])) {
                Path home = existingPlainDirectory(args[1]);
                safeIdentifier(args[2], "profile ID");
                CompleteRunAudioProfile profile;
                try { profile = CompleteRunAudioProfiles.require(args[2]); }
                catch (IllegalArgumentException unknown) { throw new UsageFailure(unknown.getMessage()); }
                CompleteRunAudioProducerRegistry.requirePinned(args[2], ProducerKind.REFERENCE);
                verifyReferenceHome(home, profile);
                return SUCCESS;
            }
            if (args.length == 8 && "produce".equals(args[0])) {
                ProducerKind kind = producer(args[1]);
                safeIdentifier(args[2], "profile ID");
                if (!CompleteRunAudioProducerRegistry.knowsProfile(args[2])) {
                    throw new UsageFailure("unknown fixed producer profile");
                }
                CompleteRunAudioProducerRegistry.requireAvailable(args[2], kind);
                Path rom = existingPlainFile(args[3]);
                Path bk2 = existingPlainFile(args[4]);
                Path manifest = existingPlainFile(args[5]);
                Path referenceHome = kind == ProducerKind.REFERENCE ? existingPlainDirectory(args[6]) : null;
                if (kind == ProducerKind.OPENGGF && !"-".equals(args[6])) {
                    throw new UsageFailure("engine producer must not receive a reference home");
                }
                Path output = safeAbsolute(args[7]);
                if (Files.exists(output, LinkOption.NOFOLLOW_LINKS)) {
                    throw new UsageFailure("producer output already exists");
                }
                CompleteRunAudioProducerRegistry.capture(new CompleteRunAudioProducer.Request(
                        kind, args[2], rom, bk2, manifest, referenceHome, output));
                return SUCCESS;
            }
            if (args.length == 4 && "validate".equals(args[0])) {
                Path capture = safeAbsolute(args[1]);
                ProducerKind producer = producer(args[2]);
                safeIdentifier(args[3], "profile ID");
                CompleteRunAudioComparator.validate(capture, producer, args[3]);
                return SUCCESS;
            }
            if (args.length == 5 && "publish".equals(args[0])) {
                Path source = safeAbsolute(args[1]);
                Path target = safeAbsolute(args[2]);
                ProducerKind producer = producer(args[3]);
                safeIdentifier(args[4], "profile ID");
                if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                    throw new UsageFailure("publication target already exists");
                }
                publishSnapshot(source, target, producer, args[4]);
                return SUCCESS;
            }
            if (args.length == 3 && ("compare".equals(args[0]) || "compare-text".equals(args[0]))) {
                Path reference = safeAbsolute(args[1]);
                Path engine = safeAbsolute(args[2]);
                CompleteRunAudioReport report = CompleteRunAudioComparator.compare(reference, engine);
                out.print("compare-text".equals(args[0]) ? report.toText() : report.toJson());
                if (report.kind() == Kind.MATCH) return SUCCESS;
                return report.kind() == Kind.CAPTURE_FAILURE ? CAPTURE_FAILURE : MISMATCH;
            }
            if ((args.length == 2 || args.length == 4) && "coverage-text".equals(args[0])) {
                safeIdentifier(args[1], "profile ID");
                CompleteRunAudioProfile profile;
                try { profile = CompleteRunAudioProfiles.require(args[1]); }
                catch (IllegalArgumentException unknown) { throw new UsageFailure(unknown.getMessage()); }
                CompleteRunAudioReport report = null;
                if (args.length == 4) {
                    report = CompleteRunAudioComparator.compare(
                            safeAbsolute(args[2]), safeAbsolute(args[3]));
                    if (report.kind() == Kind.CAPTURE_FAILURE) {
                        out.print(report.toText());
                        return CAPTURE_FAILURE;
                    }
                }
                CompleteRunAudioCoverageSummary coverage;
                try {
                    coverage = CompleteRunAudioCoverageSummary.from(profile, report);
                } catch (IllegalArgumentException correlationFailure) {
                    throw new UsageFailure(correlationFailure.getMessage());
                }
                out.print(coverage.toText());
                return coverage.fullParity() ? SUCCESS : MISMATCH;
            }
            throw new UsageFailure(
                    "usage: producer-status|producer-kind-status|produce|validate|publish|compare|coverage-text");
        } catch (CompleteRunAudioProducerRegistry.ProducerUnavailableException unavailable) {
            error.println("PRODUCER_UNAVAILABLE: " + unavailable.getMessage());
            return CAPTURE_FAILURE;
        } catch (UsageFailure | FileAlreadyExistsException failure) {
            error.println(failure.getMessage());
            return USAGE_OR_SECURITY;
        } catch (CompleteRunAudioComparator.ValidationException failure) {
            if (failure.kind() == CompleteRunAudioComparator.ValidationException.Kind.PRODUCER_UNAVAILABLE) {
                error.println("PRODUCER_UNAVAILABLE: " + failure.getMessage());
                return CAPTURE_FAILURE;
            }
            error.println(failure.kind() + ": " + failure.getMessage());
            if (failure.kind() == CompleteRunAudioComparator.ValidationException.Kind.PROFILE_UNKNOWN
                    || failure.kind() == CompleteRunAudioComparator.ValidationException.Kind.METADATA_PROFILE_MISMATCH
                    || failure.kind() == CompleteRunAudioComparator.ValidationException.Kind.PRODUCER_KIND_MISMATCH) {
                return USAGE_OR_SECURITY;
            }
            return CAPTURE_FAILURE;
        } catch (Exception failure) {
            error.println("capture operation failed: " + failure.getMessage());
            return CAPTURE_FAILURE;
        }
    }

    private static ProducerKind producer(String text) {
        try { return ProducerKind.valueOf(text); }
        catch (IllegalArgumentException failure) { throw new UsageFailure("unknown producer kind"); }
    }

    private static Path safeAbsolute(String text) {
        if (text == null || text.isBlank() || text.chars().anyMatch(value -> value < 0x20 || value == 0x7f)) {
            throw new UsageFailure("path contains empty or control text");
        }
        Path path = Path.of(text);
        if (!path.isAbsolute() || !path.equals(path.normalize())) {
            throw new UsageFailure("path must be absolute and normalized");
        }
        return path;
    }

    private static void safeIdentifier(String text, String label) {
        if (text == null || text.isBlank() || text.length() > 256
                || text.chars().anyMatch(value -> value < 0x21 || value > 0x7e)) {
            throw new UsageFailure(label + " contains invalid text");
        }
    }

    private static Path existingPlainFile(String text) {
        Path path = safeAbsolute(text);
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(path)) {
            throw new UsageFailure("producer input must be a plain file");
        }
        return path;
    }

    private static Path existingPlainDirectory(String text) {
        Path path = safeAbsolute(text);
        if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(path)) {
            throw new UsageFailure("reference home must be a plain directory");
        }
        return path;
    }

    static void verifyReferenceHome(Path home, CompleteRunAudioProfile profile) throws IOException {
        Map<CompleteRunAudioTrace.RuntimeArtifact, String> expected = profile.producerRuntimeIdentities()
                .get(ProducerKind.REFERENCE).artifactSha256();
        String expectedTree = expected.get(CompleteRunAudioTrace.RuntimeArtifact.REFERENCE_INSTALLATION_TREE);
        if (expectedTree == null || !installationTreeDigest(home).equals(expectedTree)) {
            throw new UsageFailure("reference home installation tree identity mismatch");
        }
        Map<CompleteRunAudioTrace.RuntimeArtifact, String> paths = Map.ofEntries(
                Map.entry(CompleteRunAudioTrace.RuntimeArtifact.BIZHAWK_EXECUTABLE, "EmuHawk.exe"),
                Map.entry(CompleteRunAudioTrace.RuntimeArtifact.BIZHAWK_CORE_DLL,
                        "dll/BizHawk.Emulation.Cores.dll"),
                Map.entry(CompleteRunAudioTrace.RuntimeArtifact.BIZHAWK_COMMON_DLL,
                        "dll/BizHawk.Emulation.Common.dll"),
                Map.entry(CompleteRunAudioTrace.RuntimeArtifact.WATERBOX_HOST, "dll/libwaterboxhost.so"),
                Map.entry(CompleteRunAudioTrace.RuntimeArtifact.GPGX_CORE, "dll/gpgx.wbx.zst"),
                Map.entry(CompleteRunAudioTrace.RuntimeArtifact.GPGX_CORE_UNCOMPRESSED,
                        "gpgx-audio-observer-source/gpgx.wbx"),
                Map.entry(CompleteRunAudioTrace.RuntimeArtifact.GPGX_OBSERVER_PATCH,
                        "gpgx-audio-observer-source/0001-buffer-z80-audio-events.patch"),
                Map.entry(CompleteRunAudioTrace.RuntimeArtifact.GPGX_OBSERVER_SOURCE_BUNDLE,
                        "gpgx-audio-observer-source/source-bundle.tar.zst"),
                Map.entry(CompleteRunAudioTrace.RuntimeArtifact.GPGX_OBSERVER_BUILD_RECIPE,
                        "gpgx-audio-observer-source/task7-build-recipe.json"),
                Map.entry(CompleteRunAudioTrace.RuntimeArtifact.GPGX_OBSERVER_IDENTITY,
                        "gpgx-audio-observer-source/identity.json"),
                Map.entry(CompleteRunAudioTrace.RuntimeArtifact.GPGX_OBSERVER_ADAPTER_SOURCE,
                        "gpgx-audio-observer-source/GpgxAudioObserverAdapter.cs"),
                Map.entry(CompleteRunAudioTrace.RuntimeArtifact.GPGX_HOST_BRIDGE_SOURCE,
                        "gpgx-audio-observer-source/GpgxHost.AudioObserver.cs"),
                Map.entry(CompleteRunAudioTrace.RuntimeArtifact.BIZHAWK_BIZINVOKE_DLL,
                        "dll/BizHawk.BizInvoke.dll"),
                Map.entry(CompleteRunAudioTrace.RuntimeArtifact.BIZHAWK_BASE_COMMON_DLL,
                        "dll/BizHawk.Common.dll"));
        for (Map.Entry<CompleteRunAudioTrace.RuntimeArtifact, String> entry : paths.entrySet()) {
            String hash = expected.get(entry.getKey());
            if (hash == null) continue;
            Path artifact = home.resolve(entry.getValue()).normalize();
            if (!artifact.startsWith(home) || !Files.isRegularFile(artifact, LinkOption.NOFOLLOW_LINKS)
                    || Files.isSymbolicLink(artifact) || !sha256(artifact).equals(hash)) {
                throw new UsageFailure("reference home artifact identity mismatch: " + entry.getKey());
            }
        }
    }

    /** Exact plain-tree identity used by the fixed reference-home preflight. */
    static String installationTreeDigest(Path home) throws IOException {
        if (!Files.isDirectory(home, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(home)) {
            throw new UsageFailure("reference home must be a plain directory");
        }
        List<Path> entries = new ArrayList<>();
        long[] pathBytes = {0};
        try (var stream = Files.walk(home)) {
            stream.forEach(path -> {
                if (entries.size() >= MAX_INSTALLATION_ENTRIES) {
                    throw new UsageFailure("reference home exceeds the entry bound");
                }
                pathBytes[0] += portableRelative(home, path).getBytes(StandardCharsets.UTF_8).length;
                if (pathBytes[0] > MAX_INSTALLATION_PATH_BYTES) {
                    throw new UsageFailure("reference home exceeds the path-byte bound");
                }
                entries.add(path);
            });
        }
        MessageDigest digest = sha256Digest();
        List<String> typeRecords = new ArrayList<>();
        List<Path> regularFiles = new ArrayList<>();
        for (Path entry : entries) {
            String relative = portableRelative(home, entry);
            if (relative.chars().anyMatch(value -> value < 0x20 || value == 0x7f)) {
                throw new UsageFailure("reference home contains a control-character path");
            }
            char type;
            if (Files.isSymbolicLink(entry)) {
                throw new UsageFailure("reference home contains a symbolic link");
            } else if (Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS)) {
                type = 'd';
            } else if (Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS)) {
                type = 'f';
                Object links = Files.getAttribute(entry, "unix:nlink", LinkOption.NOFOLLOW_LINKS);
                if (!(links instanceof Number count) || count.longValue() != 1) {
                    throw new UsageFailure("reference home contains a multiply-linked file");
                }
                regularFiles.add(entry);
            } else {
                throw new UsageFailure("reference home contains a special filesystem entry");
            }
            int mode = ((Number) Files.getAttribute(entry, "unix:mode", LinkOption.NOFOLLOW_LINKS)).intValue()
                    & 07777;
            typeRecords.add(type + " " + Integer.toOctalString(mode) + " " + relative);
        }
        typeRecords.sort(Comparator.naturalOrder());
        for (String record : typeRecords) {
            updateUtf8(digest, record);
            digest.update((byte) 0);
        }
        regularFiles.sort(Comparator.comparing(path -> portableRelative(home, path)));
        for (Path file : regularFiles) {
            updateUtf8(digest, sha256(file) + "  ./" + portableRelative(home, file));
            digest.update((byte) 0);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static String portableRelative(Path home, Path path) {
        return home.relativize(path).toString().replace(path.getFileSystem().getSeparator(), "/");
    }

    private static void updateUtf8(MessageDigest digest, String value) {
        digest.update(value.getBytes(StandardCharsets.UTF_8));
    }

    private static MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    private static String sha256(Path path) throws IOException {
        MessageDigest digest = sha256Digest();
        try (InputStream input = Files.newInputStream(path)) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) >= 0) digest.update(buffer, 0, count);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static void publishSnapshot(Path source, Path target, ProducerKind producer, String profileId)
            throws Exception {
        Path sourceTree = source;
        if (Files.isSymbolicLink(source)) {
            Path link = Files.readSymbolicLink(source);
            if (link.isAbsolute() || link.getNameCount() != 1
                    || !link.getFileName().toString().startsWith(".audio-published-")) {
                throw new UsageFailure("publication source has a noncanonical capture link");
            }
            sourceTree = source.getParent().resolve(link).normalize();
        }
        if (!Files.isDirectory(sourceTree, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(sourceTree)) {
            throw new UsageFailure("publication source must be a plain capture directory");
        }
        CompleteRunAudioComparator.validate(sourceTree, producer, profileId);
        Path parent = target.getParent();
        if (parent == null || !Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(parent)) {
            throw new UsageFailure("publication parent must be an existing plain directory");
        }
        Path sourceReal = sourceTree.toRealPath(LinkOption.NOFOLLOW_LINKS);
        Path parentReal = parent.toRealPath(LinkOption.NOFOLLOW_LINKS);
        if (parentReal.startsWith(sourceReal)) {
            throw new UsageFailure("publication target must be outside its source capture");
        }
        Path stage = Files.createTempDirectory(parent, ".audio-publish-");
        try {
            copyPlainTree(sourceTree, stage);
            CompleteRunAudioComparator.validate(stage, producer, profileId);
            Process publish = new ProcessBuilder("/usr/bin/mv", "-T", "--no-copy", "--no-clobber",
                    "--", stage.toString(), target.toString()).redirectErrorStream(true).start();
            if (publish.waitFor() != 0 || Files.exists(stage, LinkOption.NOFOLLOW_LINKS)
                    || !Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)) {
                throw new FileAlreadyExistsException(target.toString());
            }
        } catch (Throwable failure) {
            deleteTree(stage);
            throw failure;
        }
    }

    private static void copyPlainTree(Path source, Path stage) throws IOException {
        try (var paths = Files.walk(source)) {
            for (var iterator = paths.iterator(); iterator.hasNext();) {
                Path path = iterator.next();
                if (path.equals(source)) continue;
                String relativeText = source.relativize(path).toString();
                if (relativeText.chars().anyMatch(value -> value < 0x20 || value == 0x7f)) {
                    throw new UsageFailure("capture entry contains control text");
                }
                BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class,
                        LinkOption.NOFOLLOW_LINKS);
                if (attributes.isSymbolicLink() || (!attributes.isDirectory() && !attributes.isRegularFile())) {
                    throw new UsageFailure("capture tree contains a link or special entry");
                }
                Object links;
                try { links = Files.getAttribute(path, "unix:nlink", LinkOption.NOFOLLOW_LINKS); }
                catch (UnsupportedOperationException failure) {
                    throw new UsageFailure("hard-link identity is unavailable");
                }
                if (attributes.isRegularFile() && (!(links instanceof Number count) || count.longValue() != 1)) {
                    throw new UsageFailure("capture tree contains a multiply linked file");
                }
                Path destination = stage.resolve(source.relativize(path));
                if (attributes.isDirectory()) Files.createDirectory(destination);
                else Files.copy(path, destination, LinkOption.NOFOLLOW_LINKS);
            }
        }
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return;
        try (var paths = Files.walk(root)) {
            IOException[] failure = {null};
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try { Files.deleteIfExists(path); }
                catch (IOException caught) { if (failure[0] == null) failure[0] = caught; }
            });
            if (failure[0] != null) throw failure[0];
        }
    }

    private static final class UsageFailure extends IllegalArgumentException {
        private UsageFailure(String message) { super(message); }
    }
}
