package com.openggf.tools.audio.parity;

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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/** Command-line boundary for validation, OpenGGF capture and S1 parity comparison. */
public final class S1AudioParityTool {
    public static final int EXIT_MATCH = 0;
    public static final int EXIT_USAGE = 2;
    public static final int EXIT_MISMATCH = 3;
    public static final int EXIT_TOOL_FAILURE = 4;

    private static final String EMUHAWK_SHA256 =
            "b2d4be5e2a766a5161cc26f3af2a90753c39d64c91c54a9884171aed09e21df3";

    private S1AudioParityTool() {
    }

    public static void main(String[] args) {
        System.exit(run(args, System.out, System.err));
    }

    static int run(String[] args, PrintStream out, PrintStream err) {
        try {
            if (args.length == 0 || isHelp(args[0])) {
                usage(args.length == 0 ? err : out);
                return args.length == 0 ? EXIT_USAGE : EXIT_MATCH;
            }
            return switch (args[0]) {
                case "validate" -> validate(parse(args, 1), out);
                case "capture" -> capture(parse(args, 1), out);
                case "compare" -> compare(parse(args, 1), out);
                default -> throw new UsageException("unknown command: " + args[0]);
            };
        } catch (UsageException error) {
            err.println("Argument error: " + error.getMessage());
            usage(err);
            return EXIT_USAGE;
        } catch (Exception error) {
            err.println("S1 audio parity tool failure: " + message(error));
            return EXIT_TOOL_FAILURE;
        }
    }

    static Path resolveSafeOutputRoot(Path repository, Path requested) {
        Path repo = canonicalExisting(repository, "repository");
        Path normalized = requested.toAbsolutePath().normalize();
        Path resources = repo.resolve("src/test/resources").normalize();
        if (normalized.startsWith(resources)) {
            throw new IllegalArgumentException("audio parity output must not be under src/test/resources");
        }
        Path canonical = canonicalCandidate(normalized);
        if (canonical.startsWith(resources)) {
            throw new IllegalArgumentException("audio parity output must not be under src/test/resources");
        }
        if (!canonical.startsWith(repo)) {
            // An explicit run root outside the repository is the wrapper-driven
            // shape: TraceChaser's output policy requires reference captures to
            // land outside both source trees, and the engine capture and reports
            // belong beside them in the same run directory.
            return canonical;
        }
        Path allowedRoot = repo.resolve("target/audio-parity").normalize();
        if (!canonical.startsWith(canonicalCandidate(allowedRoot))) {
            throw new IllegalArgumentException(
                    "audio parity output inside the repository must stay under target/audio-parity");
        }
        return canonical;
    }

    static Path resolveSafeRunRoot(Path repository, Path requested) {
        Path safe = resolveSafeOutputRoot(repository, requested);
        try {
            if (!Files.isDirectory(requested, LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalArgumentException("run root must be an existing directory: " + requested);
            }
            Path actual = requested.toRealPath();
            if (!actual.equals(safe)) {
                throw new IllegalArgumentException("run root did not resolve to its validated canonical path");
            }
            return actual;
        } catch (IOException error) {
            throw new IllegalArgumentException("cannot resolve run root: " + requested, error);
        }
    }

    private static int validate(Map<String, String> options, PrintStream out) {
        rejectUnknown(options, "repo", "rom", "rom-search-root", "movie", "bizhawk-home", "output-root",
                "capture");
        Path repo = canonicalExisting(Path.of(required(options, "repo")), "repository");
        Path rom = options.containsKey("rom")
                ? Path.of(options.get("rom")).toAbsolutePath().normalize()
                : discoverRom(options.containsKey("rom-search-root")
                        ? canonicalExisting(Path.of(options.get("rom-search-root")), "ROM search root") : repo);
        verifyRegular(rom, "S1 ROM");
        verifyDigest(rom, "SHA-1", AudioParitySchema.S1_REV01_SHA1,
                "audio parity requires the pinned S1 World REV01 ROM");

        CaptureKind kind = captureKind(options);
        Path movie = normalizedRequired(options, "movie");
        verifyRegular(movie, "pinned BK2 movie");
        if (kind == CaptureKind.GAMEPLAY || kind == CaptureKind.RUN_WINDOW) {
            // The gameplay and run-window kinds accept more than one pinned
            // complete run, so the check is membership rather than a single
            // digest. Both read the same pinned movies; they differ only in
            // how much of one they cover.
            String actual = digest(movie, "SHA-256");
            if (!AudioParitySchema.GAMEPLAY_MOVIES.containsKey(actual)) {
                throw new IllegalArgumentException(
                        "BK2 movie is not one of the pinned S1 gameplay movies (observed " + actual + ")");
            }
        } else {
            verifyDigest(movie, "SHA-256", expectedMovieSha256(kind), switch (kind) {
                case SFX -> "BK2 movie does not match the pinned s1-soundtest-sfx.bk2 identity";
                case MUSIC -> "BK2 movie does not match the pinned s1-soundtest-ghz.bk2 identity";
                case GAMEPLAY, RUN_WINDOW ->
                        throw new IllegalStateException("complete-run kinds are handled above");
            });
        }

        Path bizhawk = normalizedRequired(options, "bizhawk-home");
        Path emuHawk = bizhawk.resolve("EmuHawk.exe");
        verifyRegular(emuHawk, "BizHawk 2.11 EmuHawk.exe");
        verifyRegular(bizhawk.resolve("dll/BizHawk.Emulation.Cores.dll"),
                "BizHawk 2.11 Genesis core assembly");
        verifyDigest(emuHawk, "SHA-256", EMUHAWK_SHA256,
                "BizHawk home is not the pinned 2.11 Linux x64 distribution");

        Path output = resolveSafeOutputRoot(repo, options.containsKey("output-root")
                ? normalizedRequired(options, "output-root")
                : defaultOutputRoot(repo));
        out.println("ROM_PATH=" + machinePathValue(rom, "ROM_PATH"));
        out.println("MOVIE_PATH=" + machinePathValue(movie, "MOVIE_PATH"));
        out.println("BIZHAWK_HOME=" + machinePathValue(bizhawk, "BIZHAWK_HOME"));
        out.println("OUTPUT_ROOT=" + machinePathValue(output, "OUTPUT_ROOT"));
        return EXIT_MATCH;
    }

    private static int capture(Map<String, String> options, PrintStream out) {
        rejectUnknown(options, "repo", "run-root", "reference", "rom", "output", "capture");
        Path repo = canonicalExisting(Path.of(required(options, "repo")), "repository");
        Path runRoot = resolveSafeRunRoot(repo, normalizedRequired(options, "run-root"));
        Path reference = existingRunChild(runRoot, normalizedRequired(options, "reference"), "reference");
        Path rom = normalizedRequired(options, "rom");
        Path output = newRunChild(runRoot, normalizedRequired(options, "output"), "capture output");
        CaptureKind kind = captureKind(options);
        if (kind == CaptureKind.SFX || kind == CaptureKind.GAMEPLAY
                || kind == CaptureKind.RUN_WINDOW) {
            S1OpenGgfSfxAudioCapture.CaptureResult result =
                    S1OpenGgfSfxAudioCapture.capture(reference, rom, output);
            out.println("OpenGGF " + kind + " capture: " + output + " (" + result.recordCount()
                    + " ticks, " + result.dispatchCount() + " dispatches)");
            return EXIT_MATCH;
        }
        S1OpenGgfAudioCapture.CaptureResult result =
                S1OpenGgfAudioCapture.capture(reference, rom, output);
        out.println("OpenGGF capture: " + output + " (" + result.recordCount() + " ticks)");
        return EXIT_MATCH;
    }

    private enum CaptureKind { MUSIC, SFX, GAMEPLAY, RUN_WINDOW }

    private static CaptureKind captureKind(Map<String, String> options) {
        String kind = options.getOrDefault("capture", "music");
        return switch (kind) {
            case "music" -> CaptureKind.MUSIC;
            case "sfx" -> CaptureKind.SFX;
            case "gameplay" -> CaptureKind.GAMEPLAY;
            case "run-window" -> CaptureKind.RUN_WINDOW;
            default -> throw new UsageException(
                    "--capture must be music, sfx, gameplay, or run-window");
        };
    }

    /**
     * Single-digest kinds only; GAMEPLAY and RUN_WINDOW accept any movie in
     * {@code GAMEPLAY_MOVIES}.
     */
    private static String expectedMovieSha256(CaptureKind kind) {
        return switch (kind) {
            case MUSIC -> AudioParitySchema.BK2_SHA256;
            case SFX -> AudioParitySchema.SFX_BK2_SHA256;
            case GAMEPLAY, RUN_WINDOW ->
                    throw new IllegalStateException("complete-run kinds accept several pinned movies");
        };
    }

    private static int compare(Map<String, String> options, PrintStream out) throws IOException {
        rejectUnknown(options, "repo", "run-root", "reference", "openggf", "human-report", "json-report");
        Path repo = canonicalExisting(Path.of(required(options, "repo")), "repository");
        Path runRoot = resolveSafeRunRoot(repo, normalizedRequired(options, "run-root"));
        Path reference = existingRunChild(runRoot, normalizedRequired(options, "reference"), "reference");
        Path openGgf = existingRunChild(runRoot, normalizedRequired(options, "openggf"), "OpenGGF capture");
        Path human = newRunChild(runRoot, normalizedRequired(options, "human-report"), "human report");
        Path json = newRunChild(runRoot, normalizedRequired(options, "json-report"), "JSON report");
        AudioParityReport report = AudioParityComparator.compare(reference, openGgf);
        writeReportPairNew(human, report.toHumanText() + "\n", json, report.toJsonSummary() + "\n");
        out.println(report.toHumanText());
        out.println("Human report: " + human);
        out.println("JSON report: " + json);
        if (report.matches()) {
            return EXIT_MATCH;
        }
        return isParityDifference(report.kind()) ? EXIT_MISMATCH : EXIT_TOOL_FAILURE;
    }

    private static boolean isParityDifference(AudioParityReport.Kind kind) {
        return switch (kind) {
            case GLOBAL_STATE_MISMATCH, DISPATCH_MISMATCH, TRACK_STATE_MISMATCH, EVENT_MISSING,
                    EVENT_EXTRA, EVENT_REORDERED, EVENT_VALUE_DIFFERENT -> true;
            case MATCH, CAPTURE_FAILURE, METADATA_MISMATCH, TICK_COUNT_MISMATCH,
                    ORDINAL_MISMATCH -> false;
        };
    }

    private static Map<String, String> parse(String[] args, int start) {
        Map<String, String> values = new HashMap<>();
        for (int index = start; index < args.length; index += 2) {
            String option = args[index];
            if (isHelp(option)) {
                throw new UsageException("--help must be used without a subcommand");
            }
            if (!option.startsWith("--") || option.length() == 2) {
                throw new UsageException("expected an option, found: " + option);
            }
            if (index + 1 >= args.length) {
                throw new UsageException(option + " requires a value");
            }
            String name = option.substring(2);
            String value = args[index + 1];
            if (containsProtocolDelimiter(value)) {
                throw new UsageException(option + " contains a control or protocol delimiter character");
            }
            if (values.putIfAbsent(name, value) != null) {
                throw new UsageException("duplicate option: " + option);
            }
        }
        return values;
    }

    private static void rejectUnknown(Map<String, String> values, String... allowed) {
        List<String> accepted = List.of(allowed);
        values.keySet().stream().filter(key -> !accepted.contains(key)).findFirst()
                .ifPresent(key -> { throw new UsageException("unknown option: --" + key); });
    }

    private static String required(Map<String, String> values, String name) {
        String value = values.get(name);
        if (value == null || value.isBlank()) {
            throw new UsageException("--" + name + " is required");
        }
        return value;
    }

    private static Path normalizedRequired(Map<String, String> values, String name) {
        return Path.of(required(values, name)).toAbsolutePath().normalize();
    }

    private static Path defaultOutputRoot(Path repository) {
        return repository.resolve("target/audio-parity/s1-ghz");
    }

    private static Path discoverRom(Path repo) {
        List<Path> matches = new ArrayList<>();
        try (var files = Files.list(repo)) {
            files.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().toLowerCase().endsWith(".gen"))
                    .forEach(path -> {
                        if (digest(path, "SHA-1").equals(AudioParitySchema.S1_REV01_SHA1)) {
                            matches.add(path.toAbsolutePath().normalize());
                        }
                    });
        } catch (IOException error) {
            throw new IllegalArgumentException("cannot search repository root for the S1 ROM", error);
        }
        if (matches.isEmpty()) {
            throw new IllegalArgumentException(
                    "no pinned S1 World REV01 .gen ROM found at repository root; pass --rom");
        }
        return matches.stream().sorted().findFirst().orElseThrow();
    }

    private static Path canonicalExisting(Path path, String label) {
        try {
            if (!Files.isDirectory(path)) {
                throw new IllegalArgumentException(label + " does not exist or is not a directory: " + path);
            }
            return path.toRealPath();
        } catch (IOException error) {
            throw new IllegalArgumentException("cannot resolve " + label + ": " + path, error);
        }
    }

    private static Path canonicalCandidate(Path path) {
        Path existing = path;
        List<Path> suffix = new ArrayList<>();
        while (existing != null && !Files.exists(existing, LinkOption.NOFOLLOW_LINKS)) {
            suffix.add(existing.getFileName());
            existing = existing.getParent();
        }
        if (existing == null) {
            throw new IllegalArgumentException("output path has no existing ancestor: " + path);
        }
        try {
            Path result = existing.toRealPath();
            for (int index = suffix.size() - 1; index >= 0; index--) {
                result = result.resolve(suffix.get(index));
            }
            return result.normalize();
        } catch (IOException error) {
            throw new IllegalArgumentException("cannot resolve output path: " + path, error);
        }
    }

    static String machinePathValue(Path path, String label) {
        String value = path.toString();
        if (containsProtocolDelimiter(value)) {
            throw new IllegalArgumentException(label + " contains a control or protocol delimiter character");
        }
        return value;
    }

    private static boolean containsProtocolDelimiter(String value) {
        return value.chars().anyMatch(character -> character == '=' || Character.isISOControl(character));
    }

    private static Path existingRunChild(Path runRoot, Path requested, String label) {
        Path candidate = canonicalCandidate(requested);
        if (!candidate.getParent().equals(runRoot) || !Files.isRegularFile(candidate)) {
            throw new IllegalArgumentException(label + " must be an existing direct child of the validated run root");
        }
        return candidate;
    }

    private static Path newRunChild(Path runRoot, Path requested, String label) {
        Path candidate = canonicalCandidate(requested);
        if (!candidate.getParent().equals(runRoot)) {
            throw new IllegalArgumentException(label + " must be a direct child of the validated run root");
        }
        if (Files.exists(candidate, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException(label + " already exists and will not be overwritten: " + candidate);
        }
        return candidate;
    }

    private static void writeReportPairNew(Path human, String humanText, Path json, String jsonText)
            throws IOException {
        boolean humanCreated = false;
        boolean jsonCreated = false;
        try {
            Files.writeString(human, humanText, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE);
            humanCreated = true;
            Files.writeString(json, jsonText, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE);
            jsonCreated = true;
        } catch (IOException error) {
            if (jsonCreated) {
                Files.deleteIfExists(json);
            }
            if (humanCreated) {
                Files.deleteIfExists(human);
            }
            throw error;
        }
    }

    private static void verifyRegular(Path path, String label) {
        if (!Files.isRegularFile(path)) {
            throw new IllegalArgumentException(label + " is missing or not a regular file: " + path);
        }
    }

    private static void verifyDigest(Path path, String algorithm, String expected, String diagnostic) {
        String actual = digest(path, algorithm);
        if (!actual.equals(expected)) {
            throw new IllegalArgumentException(diagnostic + " (expected " + expected + ", observed " + actual + ")");
        }
    }

    private static String digest(Path path, String algorithm) {
        try (InputStream input = Files.newInputStream(path)) {
            MessageDigest digest = MessageDigest.getInstance(algorithm);
            byte[] buffer = new byte[64 * 1024];
            int count;
            while ((count = input.read(buffer)) >= 0) {
                digest.update(buffer, 0, count);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (IOException | NoSuchAlgorithmException error) {
            throw new IllegalArgumentException("cannot compute " + algorithm + " for " + path, error);
        }
    }

    private static String message(Throwable error) {
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    }

    private static boolean isHelp(String value) {
        return "--help".equals(value) || "-h".equals(value);
    }

    private static void usage(PrintStream output) {
        output.println("Usage: S1AudioParityTool <capture|compare|validate> [options]");
        output.println("Exit codes: 0=match/success, 2=usage, 3=valid parity mismatch, 4=capture/tool failure");
    }

    private static final class UsageException extends IllegalArgumentException {
        private UsageException(String message) {
            super(message);
        }
    }
}
