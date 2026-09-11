package com.openggf.tools.audio.completerun;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TestCompleteRunAudioCli {
    @TempDir Path temp;

    @Test
    void coverageTextReportsFixedInventoryWithoutInventingEvidence() {
        CliResult coverage = result("coverage-text", "s2_rev01_complete_emeralds.v1");

        assertEquals(CompleteRunAudioTool.MISMATCH, coverage.status());
        assertEquals("", coverage.error());
        assertTrue(coverage.out().startsWith("scope=complete-run profiles only\n"));
        assertTrue(coverage.out().contains("profile=s2_rev01_complete_emeralds.v1\n"));
        assertTrue(coverage.out().contains("evidence=NOT_RUN"));
        assertTrue(coverage.out().endsWith("full_parity=false\n"));
        assertEquals(CompleteRunAudioTool.USAGE_OR_SECURITY,
                result("coverage-text", "unknown.profile").status());
        assertEquals(CompleteRunAudioTool.USAGE_OR_SECURITY,
                result("coverage-text").status());
    }

    @Test
    void classifiesUsageCaptureAndMismatchFailures() {
        assertEquals(2, run("unknown"));
        assertEquals(2, run("validate", "relative", "REFERENCE", "profile"));
        assertEquals(2, run("validate", temp.resolve("missing").toString(), "REFERENCE", "profile"));
        assertEquals(4, run("compare", temp.resolve("missing-a").toString(),
                temp.resolve("missing-b").toString()));
    }

    @Test
    void publishRejectsControlCharactersAndNeverOverwrites() throws Exception {
        Path source = temp.resolve("source");
        Files.createDirectory(source);
        Path existing = temp.resolve("existing");
        Files.createDirectory(existing);
        Files.writeString(existing.resolve("sentinel"), "keep");
        assertEquals(2, run("publish", source.toString(), temp.resolve("bad\nname").toString(),
                "REFERENCE", "profile"));
        assertEquals(2, run("publish", source.toString(), existing.toString(), "REFERENCE", "profile"));
        assertEquals("keep", Files.readString(existing.resolve("sentinel")));
        assertFalse(Files.exists(temp.resolve("bad\nname")));

        Path linkedSource = temp.resolve("linked-source");
        Files.createSymbolicLink(linkedSource, source);
        assertEquals(2, run("publish", linkedSource.toString(), temp.resolve("linked-output").toString(),
                "REFERENCE", "profile"));
        assertFalse(Files.exists(temp.resolve("linked-output")));

        Path dangling = temp.resolve("dangling-output");
        Files.createSymbolicLink(dangling, Path.of("missing-target"));
        assertEquals(2, run("publish", source.toString(), dangling.toString(), "REFERENCE", "profile"));
        assertTrue(Files.isSymbolicLink(dangling));
    }

    @Test
    void orchestratorPinsItsJavaClassAndRejectsAmbientInjection() throws Exception {
        Path script = Path.of("tools/audio/run_complete_audio_parity.sh");
        assertTrue(Files.isExecutable(script));
        String body = Files.readString(script);
        assertTrue(body.contains("com.openggf.tools.audio.completerun.CompleteRunAudioTool"));
        assertTrue(body.contains("status == 0 || status == 3"));
        assertTrue(body.contains("ensure_plain_child_dir"));
        assertFalse(body.contains("eval"));
        int availability = body.indexOf("run_capture run_tool producer-status");
        int callerPreflight = body.indexOf("run_tool verify-reference-home \"$reference_home\"");
        int copy = body.indexOf("/usr/bin/cp -a -- \"$reference_home\"");
        int privatePreflight = body.indexOf("run_tool verify-reference-home \"$run_stage/reference-home\"");
        assertTrue(availability >= 0 && availability < callerPreflight && callerPreflight < copy
                && copy < privatePreflight,
                "the fixed producer and bounded caller tree must be authenticated before copying");
        Process process = new ProcessBuilder("/usr/bin/bash", "-p", script.toString(), "--help")
                .directory(Path.of(".").toFile()).redirectErrorStream(true).start();
        assertEquals(0, process.waitFor(), new String(process.getInputStream().readAllBytes(),
                StandardCharsets.UTF_8));

        Path marker = temp.resolve("bash-env-ran");
        Path hostile = temp.resolve("hostile-bash-env");
        Files.writeString(hostile, "/usr/bin/touch '" + marker + "'\n");
        ProcessBuilder hostileBuilder = new ProcessBuilder("/usr/bin/bash", "-p", script.toString(), "--bad")
                .directory(Path.of(".").toFile()).redirectErrorStream(true);
        hostileBuilder.environment().put("BASH_ENV", hostile.toString());
        hostileBuilder.environment().put("PATH", temp.toString());
        Process hostileProcess = hostileBuilder.start();
        assertEquals(2, hostileProcess.waitFor());
        assertFalse(Files.exists(marker));
    }

    @Test
    void closedDispatcherReservesEveryGameAndUnavailableProducerPublishesNothing() throws Exception {
        assertEquals(java.util.List.of("S1", "S2", "S3K"),
                java.util.Arrays.stream(CompleteRunAudioProducerRegistry.Game.values()).map(Enum::name).toList());
        assertEquals(4, run("producer-status", "s2_rev01_complete_emeralds.v1"));
        assertEquals(4, run("producer-status", "s1_rev01_complete_emeralds.v1"));
        assertEquals(2, run("producer-status", "attacker.profile"));
        Path rom = Files.writeString(temp.resolve("game.rom"), "rom");
        Path bk2 = Files.writeString(temp.resolve("movie.bk2"), "movie");
        Path manifest = Files.writeString(temp.resolve("run.json"), "{}");
        Path output = temp.resolve("capture");

        assertEquals(4, run("produce", "OPENGGF", "s2_rev01_complete_emeralds.v1",
                rom.toString(), bk2.toString(), manifest.toString(), "-", output.toString()));
        assertFalse(Files.exists(output));
        CliResult unavailable = result("produce", "OPENGGF", "s1_rev01_complete_emeralds.v1",
                rom.toString(), bk2.toString(), manifest.toString(), "-", output.toString());
        assertEquals(4, unavailable.status());
        assertEquals("", unavailable.out());
        assertTrue(unavailable.error().startsWith("PRODUCER_UNAVAILABLE:"));
        assertFalse(Files.exists(output));
        assertEquals(2, run("produce", "OPENGGF", "attacker.profile",
                rom.toString(), bk2.toString(), manifest.toString(), "-", output.toString()));
        assertFalse(Files.exists(output));
    }

    @Test
    void kindSpecificStatusPreservesPairStatusAndReportsTheExactBindingReason() {
        CliResult pair = result("producer-status", "s2_rev01_complete_emeralds.v1");
        assertEquals(4, pair.status());
        assertTrue(pair.error().contains("fixed producer pair is not installed"));

        CliResult engine = result("producer-kind-status", "OPENGGF",
                "s2_rev01_complete_emeralds.v1");
        assertEquals(4, engine.status());
        assertEquals("", engine.out());
        assertTrue(engine.error().contains("artifact attestation trust root is not installed"));

        CliResult s3kEngine = result("producer-kind-status", "OPENGGF",
                "s3k_locked_on_knuckles_superemeralds.v1");
        assertEquals(4, s3kEngine.status());
        assertTrue(s3kEngine.error().contains("artifact attestation trust root is not installed"));

        CliResult reference = result("producer-kind-status", "REFERENCE",
                "s2_rev01_complete_emeralds.v1");
        assertEquals(4, reference.status());
        assertTrue(reference.error().contains("reviewed duplicate capture"));

        assertEquals(2, run("producer-kind-status", "attacker", "s2_rev01_complete_emeralds.v1"));
        assertEquals(2, run("producer-kind-status", "OPENGGF", "attacker.profile"));
        assertEquals(2, run("producer-kind-status", "OPENGGF"));
    }

    @Test
    void freshJvmBootstrapsTheS1ProfileButItsUnavailableDispatcherEmitsNoStandardOutput() throws Exception {
        Path java = Path.of(System.getProperty("java.home"), "bin", "java");
        Process process = new ProcessBuilder(java.toString(), "-cp", System.getProperty("java.class.path"),
                CompleteRunAudioTool.class.getName(), "producer-status", "s1_rev01_complete_emeralds.v1")
                .directory(Path.of(".").toFile()).start();

        assertEquals(4, process.waitFor());
        assertEquals("", new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
        assertTrue(new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8)
                .startsWith("PRODUCER_UNAVAILABLE:"));
    }

    @Test
    void freshJvmReportsOneProducerBindingWithoutClaimingPairAvailability() throws Exception {
        Path java = Path.of(System.getProperty("java.home"), "bin", "java");
        Process process = new ProcessBuilder(java.toString(), "-cp", System.getProperty("java.class.path"),
                CompleteRunAudioTool.class.getName(), "producer-kind-status", "OPENGGF",
                "s2_rev01_complete_emeralds.v1")
                .directory(Path.of(".").toFile()).start();

        assertEquals(4, process.waitFor());
        assertEquals("", new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
        assertTrue(new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8)
                .contains("artifact attestation trust root is not installed"));
    }

    @Test
    void unavailableReferenceBindingStopsReferenceHomePreflightBeforeIdentityUse() throws Exception {
        Path home = Files.createDirectory(temp.resolve("reference-home"));

        CliResult unavailable = result("verify-reference-home", home.toString(),
                "s1_rev01_complete_emeralds.v1");

        assertEquals(4, unavailable.status());
        assertEquals("", unavailable.out());
        assertTrue(unavailable.error().startsWith("PRODUCER_UNAVAILABLE:"));
    }

    @Test
    void directProduceChecksFixedAvailabilityBeforeTouchingAnyCallerPath() {
        CliResult unavailable = result("produce", "REFERENCE",
                "s2_rev01_complete_emeralds.v1", "relative-rom", "relative-bk2",
                "relative-manifest", "relative-reference-home", "relative-output");

        assertEquals(4, unavailable.status());
        assertTrue(unavailable.error().startsWith("PRODUCER_UNAVAILABLE:"));
    }

    @Test
    void callerSelectedUnavailableBindingRejectsHostileProfileBeforeValidateOrPublishReadsIt()
            throws Exception {
        Path hostile = Files.createDirectory(temp.resolve("hostile-capture"));
        Files.writeString(hostile.resolve("manifest.json"),
                "{\"profileId\":\"s3k_locked_on_knuckles_superemeralds.v1\"}");

        CliResult validate = result("validate", hostile.toString(), "REFERENCE",
                "s1_rev01_complete_emeralds.v1");
        assertEquals(4, validate.status());
        assertEquals("", validate.out());
        assertTrue(validate.error().startsWith("PRODUCER_UNAVAILABLE:"));

        Path target = temp.resolve("published");
        CliResult publish = result("publish", hostile.toString(), target.toString(), "REFERENCE",
                "s1_rev01_complete_emeralds.v1");
        assertEquals(4, publish.status());
        assertEquals("", publish.out());
        assertTrue(publish.error().startsWith("PRODUCER_UNAVAILABLE:"));
        assertFalse(Files.exists(target));

        assertEquals(2, run("validate", hostile.toString(), "REFERENCE", "unknown.profile"));
        assertEquals(2, run("publish", hostile.toString(), target.toString(), "REFERENCE",
                "unknown.profile"));
        assertFalse(Files.exists(target));
    }

    @Test
    void installationIdentityCoversTheWholePlainTreeAndRejectsLinks() throws Exception {
        Path home = Files.createDirectory(temp.resolve("home"));
        Path artifactA = Files.writeString(home.resolve("a"), "a");
        Path dll = Files.createDirectory(home.resolve("z"));
        Path artifact = Files.writeString(dll.resolve("x"), "x");
        Files.setPosixFilePermissions(home, PosixFilePermissions.fromString("rwxr-xr-x"));
        Files.setPosixFilePermissions(dll, PosixFilePermissions.fromString("rwxr-xr-x"));
        Files.setPosixFilePermissions(artifactA, PosixFilePermissions.fromString("rw-r--r--"));
        Files.setPosixFilePermissions(artifact, PosixFilePermissions.fromString("rw-r--r--"));
        assertEquals("7ef91fe1a69540f0c83b8d80ce9a81bae21f401b2ef73e507f22201f2cfd7632",
                CompleteRunAudioTool.installationTreeDigest(home));

        Files.writeString(home.resolve("unlisted-extra"), "extra");
        assertFalse("7ef91fe1a69540f0c83b8d80ce9a81bae21f401b2ef73e507f22201f2cfd7632"
                .equals(CompleteRunAudioTool.installationTreeDigest(home)));
        Files.delete(home.resolve("unlisted-extra"));
        Files.createSymbolicLink(home.resolve("escape"), Path.of("z/x"));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> CompleteRunAudioTool.installationTreeDigest(home));
    }

    private static int run(String... args) {
        return result(args).status();
    }

    private static CliResult result(String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream error = new ByteArrayOutputStream();
        int status = CompleteRunAudioTool.run(args, new PrintStream(out), new PrintStream(error));
        return new CliResult(status, out.toString(StandardCharsets.UTF_8), error.toString(StandardCharsets.UTF_8));
    }

    private record CliResult(int status, String out, String error) { }
}
