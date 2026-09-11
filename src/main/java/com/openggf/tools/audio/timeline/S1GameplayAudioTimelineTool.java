package com.openggf.tools.audio.timeline;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/** Trusted command-line boundary for S1 GHZ1 gameplay-audio capture publication and comparison. */
public final class S1GameplayAudioTimelineTool {
    public static final int EXIT_MATCH = 0;
    public static final int EXIT_USAGE = 2;
    public static final int EXIT_MISMATCH = 3;
    public static final int EXIT_TOOL_FAILURE = 4;
    private static final String EMUHAWK_SHA256 =
            "b2d4be5e2a766a5161cc26f3af2a90753c39d64c91c54a9884171aed09e21df3";
    static final String BIZHAWK_CORES_SHA256 =
            "0144e6e236be68ce126eb771dcb5a9ae7c153a083fa0333f345ac37b4a60acf7";
    static final String GPGX_WBX_SHA256 =
            "c4231296ec5ba59b431df22b68e234ae7bfbbfc87b6e72fa471234ac1b220d12";

    private S1GameplayAudioTimelineTool() {
    }

    public static void main(String[] args) {
        System.exit(run(args, System.out, System.err));
    }

    static int run(String[] args, PrintStream out, PrintStream err) {
        try {
            if (args.length == 0 || help(args[0])) {
                usage(args.length == 0 ? err : out);
                return args.length == 0 ? EXIT_USAGE : EXIT_MATCH;
            }
            return switch (args[0]) {
                case "validate" -> validate(parse(args, 1), out);
                case "publish-reference" -> publishReference(parse(args, 1));
                case "discard-reference" -> discardReference(parse(args, 1));
                case "compare" -> compare(parse(args, 1), out);
                default -> throw new UsageException("unknown command: " + args[0]);
            };
        } catch (UsageException error) {
            err.println("Argument error: " + error.getMessage());
            usage(err);
            return EXIT_USAGE;
        } catch (Exception error) {
            err.println("S1 GHZ1 gameplay-audio timeline tool failure: " + message(error));
            return EXIT_TOOL_FAILURE;
        }
    }

    private static int validate(Map<String, String> options, PrintStream out) {
        rejectUnknown(options, "repo", "rom", "movie", "bizhawk-home", "output-root");
        Path repository = repository(options);
        Path rom = existingFile(options, "rom", "S1 World REV01 ROM");
        Path movie = existingFile(options, "movie", "committed S1 complete BK2");
        Path bizhawk = existingDirectory(options, "bizhawk-home", "BizHawk 2.11 home");
        verifyDigest(rom, "SHA-1", S1GameplayAudioTimeline.S1_REV01_SHA1,
                "ROM does not match pinned Sonic 1 World REV01 identity");
        verifyDigest(movie, "SHA-256", S1GameplayAudioTimeline.BK2_SHA256,
                "BK2 does not match the committed S1 complete identity");
        verifyPinnedBizHawk(bizhawk);
        Path outputRoot = resolveSafeOutputRoot(repository, options.containsKey("output-root")
                ? path(options, "output-root")
                : defaultOutputRoot(repository));
        out.println("ROM_PATH=" + protocolPath(rom));
        out.println("MOVIE_PATH=" + protocolPath(movie));
        out.println("BIZHAWK_HOME=" + protocolPath(bizhawk));
        out.println("OUTPUT_ROOT=" + protocolPath(outputRoot));
        return EXIT_MATCH;
    }

    private static int publishReference(Map<String, String> options) {
        rejectUnknown(options, "repo", "run-root", "staging", "output");
        Path runRoot = safeRunRoot(repository(options), path(options, "run-root"));
        Path staging = stagingChild(runRoot, path(options, "staging"));
        Path snapshot = null;
        Exception primary = null;
        try {
            Path output = newChild(runRoot, path(options, "output"), "reference output");
            snapshot = Files.createTempFile(runRoot, ".s1-gameplay-reference-", ".snapshot");
            String before = copyAndDigest(staging, snapshot);
            if (!before.equals(digest(staging, "SHA-256"))) {
                throw new IllegalArgumentException("reference staging changed while its immutable snapshot was created");
            }
            try (S1GameplayAudioTimelineJsonl.Reader reader = S1GameplayAudioTimelineJsonl.read(snapshot)) {
                if (!S1GameplayAudioTimeline.REFERENCE_CAPTURE.equals(reader.metadata().capture())) {
                    throw new IllegalArgumentException("staging capture is not a BizHawk reference stream");
                }
                while (reader.hasNext()) {
                    reader.next();
                }
            }
            Files.createLink(output, snapshot);
            Files.delete(snapshot);
            snapshot = null;
            return EXIT_MATCH;
        } catch (IOException | RuntimeException failure) {
            primary = failure;
            throw new IllegalArgumentException("cannot validate and atomically publish reference staging: " + message(failure), failure);
        } finally {
            IOException cleanupFailure = null;
            if (snapshot != null) {
                try { Files.deleteIfExists(snapshot); } catch (IOException failure) { cleanupFailure = failure; }
            }
            try {
                Files.deleteIfExists(staging);
            } catch (IOException failure) {
                if (cleanupFailure == null) cleanupFailure = failure; else cleanupFailure.addSuppressed(failure);
            }
            if (cleanupFailure != null) {
                if (primary != null) primary.addSuppressed(cleanupFailure);
                else throw new IllegalArgumentException("reference staging cleanup failed: " + cleanupFailure.getMessage(), cleanupFailure);
            }
        }
    }

    private static int discardReference(Map<String, String> options) throws IOException {
        rejectUnknown(options, "repo", "run-root", "staging");
        Path runRoot = safeRunRoot(repository(options), path(options, "run-root"));
        Path staging = stagingChild(runRoot, path(options, "staging"));
        Files.delete(staging);
        return EXIT_MATCH;
    }

    static void verifyPinnedBizHawk(Path bizhawk) {
        Path emuHawk = bizhawk.resolve("EmuHawk.exe");
        Path cores = bizhawk.resolve("dll/BizHawk.Emulation.Cores.dll");
        Path gpgx = bizhawk.resolve("dll/gpgx.wbx.zst");
        if (!Files.isRegularFile(emuHawk) || !Files.isRegularFile(cores) || !Files.isRegularFile(gpgx)) {
            throw new IllegalArgumentException("BizHawk 2.11 installation is incomplete");
        }
        verifyDigest(emuHawk, "SHA-256", EMUHAWK_SHA256,
                "BizHawk is not the pinned 2.11 Linux x64 build");
        verifyDigest(cores, "SHA-256", BIZHAWK_CORES_SHA256,
                "BizHawk.Emulation.Cores.dll is not the pinned installed core assembly");
        verifyDigest(gpgx, "SHA-256", GPGX_WBX_SHA256,
                "gpgx.wbx.zst is not the pinned Genesis Plus GX core binary");
    }

    private static int compare(Map<String, String> options, PrintStream out) throws IOException {
        rejectUnknown(options, "repo", "run-root", "reference", "openggf", "human-report", "json-report");
        Path runRoot = safeRunRoot(repository(options), path(options, "run-root"));
        Path reference = existingChild(runRoot, path(options, "reference"), "reference capture");
        Path openGgf = existingChild(runRoot, path(options, "openggf"), "OpenGGF capture");
        Path human = newChild(runRoot, path(options, "human-report"), "human report");
        Path json = newChild(runRoot, path(options, "json-report"), "JSON report");
        S1GameplayAudioTimelineReport report = S1GameplayAudioTimelineComparator.compare(reference, openGgf);
        writePairNew(human, report.toHumanText() + "\n", json, report.toJsonSummary() + "\n");
        out.println(report.toHumanText());
        out.println("Human report: " + human);
        out.println("JSON report: " + json);
        return report.matches() ? EXIT_MATCH : report.isParityMismatch() ? EXIT_MISMATCH : EXIT_TOOL_FAILURE;
    }

    private static Path repository(Map<String, String> options) {
        return existingDirectory(options, "repo", "repository");
    }

    static Path resolveSafeOutputRoot(Path repository, Path requested) {
        Path expected = repository.resolve("target/audio-parity/s1-ghz1-gameplay").normalize();
        if (containsSymlink(requested)) {
            throw new UsageException("output root must not traverse a symbolic link");
        }
        Path candidate = canonicalCandidate(requested);
        if (!candidate.equals(canonicalCandidate(expected))) {
            throw new UsageException("output root must be the approved S1 gameplay-audio diagnostic root");
        }
        return candidate;
    }

    private static Path safeRunRoot(Path repository, Path requested) {
        Path root = repository.resolve("target/audio-parity/s1-ghz1-gameplay");
        Path requestedCandidate = canonicalCandidate(requested);
        if (!requestedCandidate.startsWith(canonicalCandidate(root))) {
            throw new UsageException("run root is outside the approved S1 gameplay-audio diagnostic root");
        }
        root = resolveSafeOutputRoot(repository, root);
        if (containsSymlink(requested) || !Files.isDirectory(requested, LinkOption.NOFOLLOW_LINKS)) {
            throw new UsageException("run root must be an existing non-symlink directory");
        }
        try {
            Path actual = requested.toRealPath();
            if (!actual.getParent().equals(root)) {
                throw new UsageException("run root must be a direct child of the safe output root");
            }
            return actual;
        } catch (IOException error) {
            throw new UsageException("cannot resolve run root: " + error.getMessage());
        }
    }

    private static Path defaultOutputRoot(Path repository) {
        return repository.resolve("target/audio-parity/s1-ghz1-gameplay");
    }

    private static Path stagingChild(Path runRoot, Path requested) {
        Path child = directChild(runRoot, requested, "reference staging");
        if (!child.getFileName().toString().endsWith(".staging") || !Files.isRegularFile(child, LinkOption.NOFOLLOW_LINKS)) {
            throw new UsageException("reference staging must be a fresh regular .staging child of the run root");
        }
        return child;
    }

    private static Path existingChild(Path runRoot, Path requested, String label) {
        Path child = directChild(runRoot, requested, label);
        if (!Files.isRegularFile(child, LinkOption.NOFOLLOW_LINKS)) {
            throw new UsageException(label + " must be an existing non-symlink direct child of the run root");
        }
        return child;
    }

    private static Path newChild(Path runRoot, Path requested, String label) {
        Path child = directChild(runRoot, requested, label);
        if (Files.exists(child, LinkOption.NOFOLLOW_LINKS)) {
            throw new UsageException(label + " already exists and will not be overwritten");
        }
        return child;
    }

    private static Path directChild(Path runRoot, Path requested, String label) {
        Path candidate = requested.toAbsolutePath().normalize();
        if (!candidate.getParent().equals(runRoot)) {
            throw new UsageException(label + " must be a direct child of the run root");
        }
        return candidate;
    }

    private static boolean containsSymlink(Path path) {
        Path absolute = path.toAbsolutePath().normalize();
        Path current = absolute.getRoot();
        for (Path part : absolute) {
            current = current.resolve(part);
            if (Files.isSymbolicLink(current)) {
                return true;
            }
        }
        return false;
    }

    private static Path existingFile(Map<String, String> options, String option, String label) {
        Path value = path(options, option);
        if (!Files.isRegularFile(value)) {
            throw new IllegalArgumentException(label + " is missing or not a regular file");
        }
        return value;
    }

    private static Path existingDirectory(Map<String, String> options, String option, String label) {
        Path value = path(options, option);
        if (!Files.isDirectory(value)) {
            throw new UsageException(label + " is missing or not a directory");
        }
        try {
            return value.toRealPath();
        } catch (IOException error) {
            throw new UsageException("cannot resolve " + label + ": " + error.getMessage());
        }
    }

    private static Path path(Map<String, String> options, String name) {
        return Path.of(required(options, name)).toAbsolutePath().normalize();
    }

    private static Path canonicalCandidate(Path path) {
        Path existing = path.toAbsolutePath().normalize();
        java.util.ArrayList<Path> suffix = new java.util.ArrayList<>();
        while (existing != null && !Files.exists(existing, LinkOption.NOFOLLOW_LINKS)) {
            suffix.add(existing.getFileName());
            existing = existing.getParent();
        }
        if (existing == null) {
            throw new UsageException("path has no existing ancestor: " + path);
        }
        try {
            Path result = existing.toRealPath();
            for (int index = suffix.size() - 1; index >= 0; index--) {
                result = result.resolve(suffix.get(index));
            }
            return result.normalize();
        } catch (IOException error) {
            throw new UsageException("cannot resolve path: " + path);
        }
    }

    private static void writePairNew(Path human, String humanText, Path json, String jsonText) throws IOException {
        boolean humanCreated = false;
        boolean jsonCreated = false;
        try {
            Files.writeString(human, humanText, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            humanCreated = true;
            Files.writeString(json, jsonText, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            jsonCreated = true;
        } catch (IOException failure) {
            if (jsonCreated) {
                Files.deleteIfExists(json);
            }
            if (humanCreated) {
                Files.deleteIfExists(human);
            }
            throw failure;
        }
    }

    private static Map<String, String> parse(String[] args, int start) {
        Map<String, String> values = new HashMap<>();
        for (int index = start; index < args.length; index += 2) {
            String option = args[index];
            if (!option.startsWith("--") || option.length() == 2 || help(option)) {
                throw new UsageException("expected an option, found: " + option);
            }
            if (index + 1 >= args.length) {
                throw new UsageException(option + " requires a value");
            }
            String value = args[index + 1];
            if (containsControl(value)) {
                throw new UsageException(option + " contains a control character");
            }
            if (values.putIfAbsent(option.substring(2), value) != null) {
                throw new UsageException("duplicate option: " + option);
            }
        }
        return values;
    }

    private static void rejectUnknown(Map<String, String> options, String... accepted) {
        List<String> allowed = List.of(accepted);
        options.keySet().stream().filter(option -> !allowed.contains(option)).findFirst()
                .ifPresent(option -> { throw new UsageException("unknown option: --" + option); });
    }

    private static String required(Map<String, String> options, String name) {
        String value = options.get(name);
        if (value == null || value.isBlank()) {
            throw new UsageException("--" + name + " is required");
        }
        return value;
    }

    private static String protocolPath(Path path) {
        String value = path.toString();
        if (value.indexOf('=') >= 0 || containsControl(value)) {
            throw new IllegalArgumentException("validated path contains a protocol delimiter");
        }
        return value;
    }

    static void verifyDigest(Path path, String algorithm, String expected, String error) {
        try (InputStream input = Files.newInputStream(path)) {
            MessageDigest digest = MessageDigest.getInstance(algorithm);
            byte[] bytes = new byte[64 * 1024];
            for (int count; (count = input.read(bytes)) >= 0;) {
                digest.update(bytes, 0, count);
            }
            if (!expected.equals(HexFormat.of().formatHex(digest.digest()))) {
                throw new IllegalArgumentException(error);
            }
        } catch (IOException | NoSuchAlgorithmException failure) {
            throw new IllegalArgumentException("cannot verify pinned identity: " + failure.getMessage(), failure);
        }
    }

    private static String copyAndDigest(Path source, Path destination) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = Files.newInputStream(source);
                    var output = Files.newOutputStream(destination, StandardOpenOption.TRUNCATE_EXISTING)) {
                byte[] bytes = new byte[64 * 1024];
                for (int count; (count = input.read(bytes)) >= 0;) {
                    digest.update(bytes, 0, count);
                    output.write(bytes, 0, count);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 is unavailable", failure);
        }
    }

    private static String digest(Path path, String algorithm) {
        try (InputStream input = Files.newInputStream(path)) {
            MessageDigest digest = MessageDigest.getInstance(algorithm);
            byte[] bytes = new byte[64 * 1024];
            for (int count; (count = input.read(bytes)) >= 0;) digest.update(bytes, 0, count);
            return HexFormat.of().formatHex(digest.digest());
        } catch (IOException | NoSuchAlgorithmException failure) {
            throw new IllegalArgumentException("cannot fingerprint reference staging: " + failure.getMessage(), failure);
        }
    }

    private static boolean containsControl(String value) {
        return value.chars().anyMatch(Character::isISOControl);
    }

    private static boolean help(String value) {
        return "--help".equals(value) || "-h".equals(value);
    }

    private static String message(Throwable error) {
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    }

    private static void usage(PrintStream output) {
        output.println("Usage: S1GameplayAudioTimelineTool <validate|publish-reference|discard-reference|compare> [options]");
        output.println("Exit codes: 0=semantic match, 2=usage, 3=parity mismatch, 4=capture/tool failure.");
    }

    private static final class UsageException extends IllegalArgumentException {
        private UsageException(String message) {
            super(message);
        }
    }
}
