package com.openggf.tools.audio.parity;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Assumptions;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestS1AudioParityCli {
    @TempDir
    Path temp;

    @Test
    void helpAndInvalidArgumentsHaveStableExitSemantics() {
        Invocation help = invoke("--help");
        assertEquals(S1AudioParityTool.EXIT_MATCH, help.exitCode());
        assertTrue(help.out().contains("capture|compare|validate"));

        Invocation invalid = invoke("compare", "--reference", "only-one-path");
        assertEquals(S1AudioParityTool.EXIT_USAGE, invalid.exitCode());
        assertTrue(invalid.err().contains("--repo is required"));

        Invocation injected = invoke("validate", "--repo", "safe\nOUTPUT_ROOT=/tmp/escape",
                "--rom", "rom.gen", "--movie", "movie.bk2", "--bizhawk-home", "bizhawk",
                "--output-root", "target/audio-parity/s1-ghz");
        assertEquals(S1AudioParityTool.EXIT_USAGE, injected.exitCode());
        assertTrue(injected.err().contains("control or protocol delimiter"));
    }

    @Test
    void outputRootAllowsExternalRunRootsButNeverTestResourcesOrStrayRepoPaths() throws Exception {
        Path repo = Files.createDirectories(temp.resolve("repo"));
        Files.createDirectories(repo.resolve("target/audio-parity"));
        Files.createDirectories(repo.resolve("src/test/resources"));
        Path outside = Files.createDirectories(temp.resolve("outside"));

        assertEquals(repo.resolve("target/audio-parity/s1-ghz").toAbsolutePath().normalize(),
                S1AudioParityTool.resolveSafeOutputRoot(repo,
                        repo.resolve("target/audio-parity/s1-ghz")));
        // TraceChaser's output policy forbids reference captures inside either source
        // tree, so an explicit run root outside the repository is the wrapper shape.
        assertEquals(outside.toRealPath().resolve("run"),
                S1AudioParityTool.resolveSafeOutputRoot(repo, outside.resolve("run")));
        assertUnsafe(repo, repo.resolve("src/test/resources/audio/parity-output"),
                "src/test/resources");
        assertUnsafe(repo, repo.resolve("target/audio-parity/../../src/test/resources/escape"),
                "src/test/resources");
        assertUnsafe(repo, repo.resolve("stray-output"), "target/audio-parity");

        // A symlink below target/audio-parity resolves to its real, external target,
        // which the external-run-root rule then admits explicitly.
        Files.createSymbolicLink(repo.resolve("target/audio-parity/link"), outside);
        assertEquals(outside.toRealPath().resolve("run"),
                S1AudioParityTool.resolveSafeOutputRoot(repo, repo.resolve("target/audio-parity/link/run")));
    }

    @Test
    void resolvedMachineProtocolPathsCannotIntroduceDelimiters() {
        try {
            S1AudioParityTool.machinePathValue(Path.of("resolved\nOUTPUT_ROOT=/tmp/escape"), "ROM");
        } catch (IllegalArgumentException error) {
            assertTrue(error.getMessage().contains("control or protocol delimiter"), error::getMessage);
            return;
        }
        throw new AssertionError("resolved protocol path containing a newline was accepted");
    }

    @Test
    void validationNamesEachMissingPinnedInput() throws Exception {
        Path repo = Files.createDirectories(temp.resolve("repo"));
        Path output = repo.resolve("target/audio-parity/s1-ghz");
        Invocation rom = invoke("validate", "--repo", repo.toString(), "--rom",
                repo.resolve("missing.gen").toString(), "--movie", repo.resolve("movie.bk2").toString(),
                "--bizhawk-home", repo.resolve("BizHawk-2.11-linux-x64").toString(),
                "--output-root", output.toString());
        assertEquals(S1AudioParityTool.EXIT_TOOL_FAILURE, rom.exitCode());
        assertTrue(rom.err().contains("S1 ROM"));

        Path wrongRom = repo.resolve("wrong.gen");
        Files.writeString(wrongRom, "not the pinned ROM");
        Invocation wrong = invoke("validate", "--repo", repo.toString(), "--rom", wrongRom.toString(),
                "--movie", repo.resolve("movie.bk2").toString(), "--bizhawk-home",
                repo.resolve("BizHawk-2.11-linux-x64").toString(), "--output-root", output.toString());
        assertEquals(S1AudioParityTool.EXIT_TOOL_FAILURE, wrong.exitCode());
        assertTrue(wrong.err().contains("pinned S1 World REV01 ROM"));
    }

    @Test
    void validationNamesMissingMovieAndBizHawkAfterRomIdentityPasses() {
        String configuredRom = System.getProperty("sonic1.rom.path");
        Assumptions.assumeTrue(configuredRom != null && Files.isRegularFile(Path.of(configuredRom)),
                "-Dsonic1.rom.path supplies the pinned ROM for boundary diagnostics");
        Path repo = Path.of("").toAbsolutePath();
        Path output = repo.resolve("target/audio-parity/s1-ghz");
        Path movie = repo.resolve("src/test/resources/audio/parity/s1/s1-soundtest-ghz.bk2");

        Invocation missingMovie = invoke("validate", "--repo", repo.toString(), "--rom", configuredRom,
                "--movie", temp.resolve("missing.bk2").toString(), "--bizhawk-home",
                temp.resolve("BizHawk-2.11-linux-x64").toString(), "--output-root", output.toString());
        assertEquals(S1AudioParityTool.EXIT_TOOL_FAILURE, missingMovie.exitCode());
        assertTrue(missingMovie.err().contains("pinned BK2 movie"));

        Invocation missingBizHawk = invoke("validate", "--repo", repo.toString(), "--rom", configuredRom,
                "--movie", movie.toString(), "--bizhawk-home",
                temp.resolve("BizHawk-2.11-linux-x64").toString(), "--output-root", output.toString());
        assertEquals(S1AudioParityTool.EXIT_TOOL_FAILURE, missingBizHawk.exitCode());
        assertTrue(missingBizHawk.err().contains("BizHawk 2.11 EmuHawk.exe"));
    }

    @Test
    void comparisonUsesDedicatedMismatchAndCaptureFailureCodesAndWritesBothReports() throws Exception {
        Path repo = Files.createDirectories(temp.resolve("repo"));
        Files.createDirectories(repo.resolve("target/audio-parity"));
        Path run = Files.createDirectories(repo.resolve("target/audio-parity/run.test"));
        Path reference = run.resolve("reference.jsonl");
        Path mismatch = run.resolve("mismatch.jsonl");
        Path malformed = run.resolve("malformed.jsonl");
        Path human = run.resolve("report.txt");
        Path json = run.resolve("report.json");
        AudioParityTick tick = tick(0, 1);
        write(reference, AudioParitySchema.REFERENCE_CAPTURE, tick);
        write(mismatch, AudioParitySchema.OPENGGF_CAPTURE, tick(0, 2));
        Files.writeString(malformed, "not-json\n");

        Invocation validMismatch = invoke("compare", "--repo", repo.toString(), "--run-root", run.toString(),
                "--reference", reference.toString(),
                "--openggf", mismatch.toString(), "--human-report", human.toString(),
                "--json-report", json.toString());
        assertEquals(S1AudioParityTool.EXIT_MISMATCH, validMismatch.exitCode());
        assertTrue(Files.readString(human).contains("S1 audio parity: MISMATCH"));
        assertTrue(Files.readString(json).contains("\"result\":\"mismatch\""));

        Invocation captureFailure = invoke("compare", "--repo", repo.toString(), "--run-root", run.toString(),
                "--reference", malformed.toString(), "--openggf", mismatch.toString(),
                "--human-report", run.resolve("bad.txt").toString(),
                "--json-report", run.resolve("bad.json").toString());
        assertEquals(S1AudioParityTool.EXIT_TOOL_FAILURE, captureFailure.exitCode());
        assertFalse(captureFailure.err().contains("parity mismatch"));

        Path wrongCaptureKind = run.resolve("wrong-capture-kind.jsonl");
        write(wrongCaptureKind, AudioParitySchema.REFERENCE_CAPTURE, tick);
        Invocation invalidMetadata = invoke("compare", "--repo", repo.toString(), "--run-root", run.toString(),
                "--reference", reference.toString(), "--openggf", wrongCaptureKind.toString(),
                "--human-report", run.resolve("meta.txt").toString(),
                "--json-report", run.resolve("meta.json").toString());
        assertEquals(S1AudioParityTool.EXIT_TOOL_FAILURE, invalidMetadata.exitCode(),
                "invalid capture identity is not a valid parity mismatch");
    }

    @Test
    void captureAndReportOutputsRequireValidatedRunChildrenAndNeverOverwrite() throws Exception {
        Path repo = Files.createDirectories(temp.resolve("authority-repo"));
        Files.createDirectories(repo.resolve("target/audio-parity"));
        Path run = Files.createDirectories(repo.resolve("target/audio-parity/run.authorized"));
        Path outside = temp.resolve("outside.jsonl");
        Path reference = run.resolve("reference.jsonl");
        Path openGgf = run.resolve("openggf.jsonl");
        AudioParityTick tick = tick(0, 1);
        write(reference, AudioParitySchema.REFERENCE_CAPTURE, tick);
        write(openGgf, AudioParitySchema.OPENGGF_CAPTURE, tick(0, 2));

        Invocation outsideCapture = invoke("capture", "--repo", repo.toString(), "--run-root", run.toString(),
                "--reference", reference.toString(), "--rom", "missing.gen", "--output", outside.toString());
        assertEquals(S1AudioParityTool.EXIT_TOOL_FAILURE, outsideCapture.exitCode());
        assertFalse(Files.exists(outside));

        Path resourceOutput = repo.resolve("src/test/resources/audio/parity/leak.jsonl");
        Invocation resourceCapture = invoke("capture", "--repo", repo.toString(), "--run-root", run.toString(),
                "--reference", reference.toString(), "--rom", "missing.gen",
                "--output", resourceOutput.toString());
        assertEquals(S1AudioParityTool.EXIT_TOOL_FAILURE, resourceCapture.exitCode());
        assertFalse(Files.exists(resourceOutput));

        Path existingCapture = run.resolve("existing.jsonl");
        Files.writeString(existingCapture, "preserved-capture\n");
        Invocation overwriteCapture = invoke("capture", "--repo", repo.toString(), "--run-root", run.toString(),
                "--reference", reference.toString(), "--rom", "missing.gen",
                "--output", existingCapture.toString());
        assertEquals(S1AudioParityTool.EXIT_TOOL_FAILURE, overwriteCapture.exitCode());
        assertEquals("preserved-capture\n", Files.readString(existingCapture));

        Path existingHuman = run.resolve("existing-report.txt");
        Files.writeString(existingHuman, "preserved-report\n");
        Path newJson = run.resolve("new-report.json");
        Invocation overwriteReport = invoke("compare", "--repo", repo.toString(), "--run-root", run.toString(),
                "--reference", reference.toString(), "--openggf", openGgf.toString(),
                "--human-report", existingHuman.toString(), "--json-report", newJson.toString());
        assertEquals(S1AudioParityTool.EXIT_TOOL_FAILURE, overwriteReport.exitCode());
        assertEquals("preserved-report\n", Files.readString(existingHuman));
        assertFalse(Files.exists(newJson));

        Invocation outsideReport = invoke("compare", "--repo", repo.toString(), "--run-root", run.toString(),
                "--reference", reference.toString(), "--openggf", openGgf.toString(),
                "--human-report", temp.resolve("outside.txt").toString(),
                "--json-report", run.resolve("safe.json").toString());
        assertEquals(S1AudioParityTool.EXIT_TOOL_FAILURE, outsideReport.exitCode());
        assertFalse(Files.exists(temp.resolve("outside.txt")));
    }

    @Test
    void sourceableShellProtocolParserRejectsDuplicateAndInjectedRecords() throws Exception {
        String valid = "ROM_PATH=/safe/rom.gen\nMOVIE_PATH=/safe/movie.bk2\n"
                + "BIZHAWK_HOME=/safe/bizhawk\nOUTPUT_ROOT=/safe/output";
        ShellResult duplicate = parseProtocol(valid + "\nOUTPUT_ROOT=/tmp/escape");
        assertTrue(duplicate.exitCode() != 0, duplicate.output());
        assertTrue(duplicate.output().contains("duplicate validation record"), duplicate.output());

        ShellResult injected = parseProtocol(valid + "\nEVIL=/tmp/escape");
        assertTrue(injected.exitCode() != 0, injected.output());
        assertTrue(injected.output().contains("unknown validation record"), injected.output());
    }

    @Test
    void retiredJavaOverrideCannotReplaceTheTrustedTool() throws Exception {
        Path fake = temp.resolve("fake-java");
        Path marker = temp.resolve("fake-was-run");
        Files.writeString(fake, "#!/usr/bin/env bash\ntouch \"" + marker + "\"\nexit 0\n");
        fake.toFile().setExecutable(true);

        ProcessBuilder builder = new ProcessBuilder("bash", "tools/audio/run_s1_audio_parity.sh",
                "--rom", temp.resolve("missing.gen").toString(), "--bizhawk-home", temp.toString());
        builder.directory(Path.of("").toAbsolutePath().toFile()).redirectErrorStream(true);
        builder.environment().put("OGGF_AUDIO_PARITY_JAVA_BIN", fake.toString());
        Process process = builder.start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

        assertEquals(S1AudioParityTool.EXIT_TOOL_FAILURE, process.waitFor(), output);
        assertTrue(output.contains("unsupported"), output);
        assertFalse(Files.exists(marker));
    }

    @Test
    void shellHelpDocumentsExitCodesWithoutLaunchingExternalProcesses() throws Exception {
        Process process = new ProcessBuilder("bash", "tools/audio/run_s1_audio_parity.sh", "--help")
                .directory(Path.of("").toAbsolutePath().toFile()).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, process.waitFor());
        assertTrue(output.contains("0=match"));
        assertTrue(output.contains("3=mismatch"));
        assertTrue(output.contains("4=capture/tool failure"));
    }

    private void assertUnsafe(Path repo, Path output, String diagnostic) {
        try {
            S1AudioParityTool.resolveSafeOutputRoot(repo, output);
        } catch (IllegalArgumentException error) {
            assertTrue(error.getMessage().contains(diagnostic), error.getMessage());
            return;
        }
        throw new AssertionError("unsafe output path was accepted: " + output);
    }

    private Invocation invoke(String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int exit = S1AudioParityTool.run(args, new PrintStream(out), new PrintStream(err));
        return new Invocation(exit, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    private ShellResult parseProtocol(String protocol) throws Exception {
        ProcessBuilder builder = new ProcessBuilder("bash", "-c",
                "source tools/audio/lib/s1_audio_parity_protocol.sh; "
                        + "s1_audio_parse_validation_records \"$S1_AUDIO_TEST_PROTOCOL\"");
        builder.directory(Path.of("").toAbsolutePath().toFile()).redirectErrorStream(true);
        builder.environment().put("S1_AUDIO_TEST_PROTOCOL", protocol);
        Process process = builder.start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        return new ShellResult(process.waitFor(), output);
    }

    private void write(Path path, String capture, AudioParityTick tick) {
        ObjectNode details = JsonNodeFactory.instance.objectNode();
        if (AudioParitySchema.REFERENCE_CAPTURE.equals(capture)) {
            ObjectNode callback = details.putObject("callback_contract");
            callback.putArray("arguments").add("address").add("value").add("flags");
            callback.putObject("proof").put("fm_port0_pairs", 1).put("fm_port1_pairs", 1)
                    .put("psg_writes", 1);
            callback.put("source", "memory_callback");
            ObjectNode diagnostic = details.putObject("diagnostic_fields");
            AudioParitySchema.DIAGNOSTIC_GLOBAL_FIELDS.forEach(diagnostic.putArray("global")::add);
            AudioParitySchema.DIAGNOSTIC_TRACK_FIELDS.forEach(diagnostic.putArray("track")::add);
            ObjectNode gating = details.putObject("gating_fields");
            AudioParitySchema.GATING_GLOBAL_FIELDS.forEach(gating.putArray("global")::add);
            AudioParitySchema.GATING_TRACK_FIELDS.forEach(gating.putArray("track")::add);
            details.put("launch_update_music_invocations", 514);
            details.putObject("movie")
                    .put("archive_sha256", AudioParitySchema.BK2_SHA256)
                    .put("core", AudioParitySchema.BK2_CORE)
                    .put("emulator", AudioParitySchema.BK2_EMULATOR)
                    .put("game", AudioParitySchema.BK2_GAME)
                    .put("input_rows", AudioParitySchema.BK2_INPUT_ROWS)
                    .put("opaque_header_hash", AudioParitySchema.BK2_OPAQUE_HASH);
        }
        AudioParityMetadata metadata = new AudioParityMetadata(AudioParitySchema.VERSION, capture,
                0, 1, 3, AudioParitySchema.S1_REV01_SHA1, AudioParitySchema.S1_REV01_CRC32,
                details);
        AudioParityJsonl.write(path, metadata, List.of(tick, tick.withOrdinal(1), tick.withOrdinal(2)).iterator());
    }

    private AudioParityTick tick(int ordinal, int tempoTimeout) {
        return new AudioParityTick(ordinal,
                new AudioParityTick.GlobalState(false, "none", null, null, false, 2, tempoTimeout),
                AudioParitySchema.ROLES.stream().map(AudioParityTrackState::inactive).toList(), List.of());
    }

    private record Invocation(int exitCode, String out, String err) {
    }

    private record ShellResult(int exitCode, String output) {
    }
}
