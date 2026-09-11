package com.openggf.tools.audio.parity.s3k;

import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Command-line boundary for the S3K sound-driver oracle.
 *
 * <pre>
 *   validate --reference &lt;jsonl[.gz]&gt;
 *   compare  --reference &lt;jsonl[.gz]&gt; --rom &lt;s3k.gen&gt; [--ticks N]
 *            [--format text|json] [--corrupt-engine-write-tick N]
 * </pre>
 *
 * <p>{@code validate} streams the reference, checking schema, ROM identity,
 * per-row shape, tick count and the terminal body digest. {@code compare}
 * additionally drives the engine's S3K SMPS driver with the reference's
 * request timeline and reports the first divergence (tick + field +
 * expected/actual), with no realignment. Exit codes: 0 match, 2 usage,
 * 3 mismatch, 4 invalid capture/tool failure, 5 authenticated reference
 * limitation.
 */
public final class S3kAudioParityTool {
    public static final int EXIT_MATCH = 0;
    public static final int EXIT_USAGE = 2;
    public static final int EXIT_MISMATCH = 3;
    public static final int EXIT_TOOL_FAILURE = 4;
    public static final int EXIT_REFERENCE_LIMITATION = 5;

    private S3kAudioParityTool() {
    }

    public static void main(String[] args) {
        System.exit(run(args, System.out, System.err));
    }

    static int run(String[] args, PrintStream out, PrintStream err) {
        try {
            if (args.length == 0) {
                usage(err);
                return EXIT_USAGE;
            }
            Map<String, String> options = parse(args, 1);
            return switch (args[0]) {
                case "validate" -> validate(options, out);
                case "compare" -> compare(options, out);
                default -> {
                    err.println("unknown command: " + args[0]);
                    usage(err);
                    yield EXIT_USAGE;
                }
            };
        } catch (UsageException error) {
            err.println("Argument error: " + error.getMessage());
            usage(err);
            return EXIT_USAGE;
        } catch (Exception error) {
            err.println("S3K audio oracle tool failure: "
                    + (error.getMessage() == null ? error.getClass().getSimpleName()
                            : error.getMessage()));
            return EXIT_TOOL_FAILURE;
        }
    }

    private static int validate(Map<String, String> options, PrintStream out) {
        Path reference = requiredPath(options, "reference");
        int[] ticks = {0};
        S3kAudioReferenceReader.Metadata metadata =
                S3kAudioReferenceReader.read(reference, tick -> ticks[0]++);
        out.println("S3K audio oracle reference: VALID");
        out.println("schema: " + metadata.schema());
        out.println("movie: " + metadata.movieName() + " sha256 " + metadata.movieSha256());
        out.println("ticks: " + ticks[0]);
        out.println("observer_core_zst_sha256: " + metadata.observerCoreSha256());
        return EXIT_MATCH;
    }

    private static int compare(Map<String, String> options, PrintStream out) {
        Path referencePath = requiredPath(options, "reference");
        Path rom = requiredPath(options, "rom");
        String format = options.getOrDefault("format", "text");
        Integer corrupt = options.containsKey("corrupt-engine-write-tick")
                ? Integer.parseInt(options.get("corrupt-engine-write-tick")) : null;
        S3kRequestObservationSidecar requests = options.containsKey("requests")
                ? S3kRequestObservationSidecar.read(requiredPath(options, "requests"))
                : S3kRequestObservationSidecar.absent();
        List<S3kAudioTick> reference = new ArrayList<>();
        S3kAudioReferenceReader.readDriverServices(referencePath, requests, reference::add);
        if (options.containsKey("ticks")) {
            int limit = Integer.parseInt(options.get("ticks"));
            if (limit < 1 || limit > reference.size()) {
                throw new UsageException("--ticks must be within the reference tick count");
            }
            reference = reference.subList(0, limit);
        }
        S3kOpenGgfAudioCapture.CaptureResult engine =
                S3kOpenGgfAudioCapture.capture(rom, reference, corrupt);
        if ("text".equals(format)) {
            for (String message : engine.unsupportedRequests()) {
                out.println("unsupported request: " + message);
            }
        }
        S3kAudioParityComparator.Report report =
                S3kAudioParityComparator.compare(reference, engine.ticks());
        out.println(renderReport(report, format));
        // The DAC byte stream is compared unpartitioned and reported on its
        // own line; see S3kAudioParityComparator's class comment.
        S3kAudioParityComparator.DacStreamReport dacStream =
                S3kAudioParityComparator.compareDacStream(
                        reference, engine.ticks());
        out.println("json".equals(format)
                ? dacStream.toMachineText() : dacStream.toHumanText());
        int serviceExit = exitCode(report);
        return serviceExit != EXIT_MATCH || dacStream.matches()
                ? serviceExit : EXIT_MISMATCH;
    }

    static String renderReport(
            S3kAudioParityComparator.Report report, String format) {
        return switch (format) {
            case "text" -> report.toHumanText();
            case "json" -> report.toMachineText();
            default -> throw new UsageException(
                    "--format must be text or json");
        };
    }

    static int exitCode(S3kAudioParityComparator.Report report) {
        return switch (report.kind()) {
            case MATCH -> EXIT_MATCH;
            case REFERENCE_LIMITATION -> EXIT_REFERENCE_LIMITATION;
            default -> EXIT_MISMATCH;
        };
    }

    private static Path requiredPath(Map<String, String> options, String name) {
        String value = options.get(name);
        if (value == null) {
            throw new UsageException("--" + name + " is required");
        }
        Path path = Path.of(value).toAbsolutePath().normalize();
        if (!Files.isRegularFile(path)) {
            throw new UsageException("--" + name + " does not name a regular file: " + path);
        }
        return path;
    }

    private static Map<String, String> parse(String[] args, int start) {
        Map<String, String> options = new HashMap<>();
        for (int index = start; index < args.length; index += 2) {
            String key = args[index];
            if (!key.startsWith("--") || index + 1 >= args.length) {
                throw new UsageException("options are --name value pairs; got " + key);
            }
            options.put(key.substring(2), args[index + 1]);
        }
        return options;
    }

    private static void usage(PrintStream stream) {
        stream.println("usage: S3kAudioParityTool validate --reference <jsonl[.gz]>");
        stream.println("       S3kAudioParityTool compare --reference <jsonl[.gz]> --rom <s3k.gen>");
        stream.println("           [--requests <observations.json>] [--ticks N] [--format text|json]");
        stream.println("           [--corrupt-engine-write-tick N]");
    }

    private static final class UsageException extends RuntimeException {
        UsageException(String message) {
            super(message);
        }
    }
}
