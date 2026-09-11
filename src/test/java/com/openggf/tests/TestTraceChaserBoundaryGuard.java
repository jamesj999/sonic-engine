package com.openggf.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Guards the optional, immutable TraceChaser consumer boundary. */
class TestTraceChaserBoundaryGuard {
    private static final String PIN = "4fb6d0802cc6ad27f07dd845a1b98ea84d2c7b0e";
    private static final String LUA_BIN = System.getenv().getOrDefault("LUA_BIN", "lua");

    @Test
    void exactGitlinkAndNonFloatingConfigurationAreTracked() throws Exception {
        String modules = Files.readString(Path.of(".gitmodules"));
        assertTrue(modules.contains("[submodule \"tools/tracechaser\"]"));
        assertTrue(modules.contains("path = tools/tracechaser"));
        assertTrue(modules.contains("url = https://github.com/OpenGGF/TraceChaser.git"));
        assertFalse(modules.contains("branch ="));
        Result index = run(Path.of("."), "git", "ls-files", "-s", "--", "tools/tracechaser");
        assertEquals(0, index.exitCode());
        assertEquals("160000 " + PIN + " 0\ttools/tracechaser", index.output().strip());
    }

    @Test
    void ordinaryMavenExcludesOptInIntegrationTag() throws IOException {
        String pom = Files.readString(Path.of("pom.xml"));
        assertTrue(pom.contains("tracechaser-integration"));
        assertTrue(pom.contains("<surefire.excludedGroups>tracechaser-integration</surefire.excludedGroups>"));
        assertTrue(pom.contains("<excludedGroups>${surefire.excludedGroups}</excludedGroups>"));
        assertTrue(pom.contains("<id>tracechaser-integration</id>"));
    }

    @Test
    void ordinaryConsumerContractLivesOutsideTheLegacyTraceReplayExclusion() {
        assertTrue(Files.isRegularFile(Path.of(
                "src/test/java/com/openggf/trace/TestTraceChaserV5ConsumerContract.java")));
    }

    @Test
    void bootstrapReportsAbsentWrongAndUnsafeStates(@TempDir Path temp) throws Exception {
        Path repo = temp.resolve("repo");
        Files.createDirectories(repo.resolve("tools"));
        assertEquals(0, run(repo, "git", "init").exitCode());
        Files.copy(Path.of("tools/tracechaser-bootstrap.sh"), repo.resolve("tools/tracechaser-bootstrap.sh"));
        run(repo, "git", "update-index", "--add", "--cacheinfo", "160000," + PIN + ",tools/tracechaser");

        Result absent = run(repo, "bash", "tools/tracechaser-bootstrap.sh", "--check");
        assertEquals(2, absent.exitCode());
        assertTrue(absent.output().contains("git submodule update --init --recursive tools/tracechaser"));
        assertTrue(absent.output().contains(PIN));

        Path outside = Files.createDirectories(temp.resolve("outside"));
        Files.createSymbolicLink(repo.resolve("tools/tracechaser"), outside);
        Result unsafe = run(repo, "bash", "tools/tracechaser-bootstrap.sh", "--check");
        assertEquals(4, unsafe.exitCode());
        Files.delete(repo.resolve("tools/tracechaser"));

        Files.createDirectories(repo.resolve("tools/tracechaser"));
        assertEquals(0, run(repo.resolve("tools/tracechaser"), "git", "init").exitCode());
        assertEquals(0, run(repo.resolve("tools/tracechaser"), "git", "config", "user.email", "test@example.invalid").exitCode());
        assertEquals(0, run(repo.resolve("tools/tracechaser"), "git", "config", "user.name", "Test").exitCode());
        Files.writeString(repo.resolve("tools/tracechaser/README.md"), "wrong\n");
        assertEquals(0, run(repo.resolve("tools/tracechaser"), "git", "add", "README.md").exitCode());
        assertEquals(0, run(repo.resolve("tools/tracechaser"), "git", "commit", "-m", "wrong").exitCode());
        Result wrong = run(repo, "bash", "tools/tracechaser-bootstrap.sh", "--check");
        assertEquals(3, wrong.exitCode());
        assertTrue(wrong.output().contains(PIN));

    }

    @Test
    void onlyReviewedForwardersRemainAtMigratedRoots() throws Exception {
        Result audit = run(Path.of("."), "python3", "tools/testing/tracechaser_cutover_guard.py");
        assertEquals(0, audit.exitCode(), audit.output());
    }

    @Test
    void retainedAudioCallersUseVerifiedPinnedTraceChaserPaths() throws IOException {
        for (String path : List.of(
                "tools/audio/run_s1_audio_parity.sh",
                "tools/audio/run_s1_ghz1_gameplay_audio_timeline.sh",
                "tools/audio/build-s1-ym-busy-program.py")) {
            String source = Files.readString(Path.of(path));
            assertTrue(source.contains("tracechaser-bootstrap"), path);
            assertTrue(source.contains("--require"), path);
            assertFalse(source.contains("--check"), path);
            assertTrue(source.contains("tools/tracechaser"), path);
            assertFalse(source.contains("ROOT / \"tools/bizhawk-headless/native"), path);
            assertFalse(source.contains("tools/bizhawk/run_bizhawk_lua.sh"), path);
        }
    }

    @Test
    void bootstrapReturnsFourForMissingPinnedCommand(@TempDir Path temp) throws Exception {
        Path repo = temp.resolve("repo");
        Path checkout = repo.resolve("tools/tracechaser");
        Files.createDirectories(checkout);
        assertEquals(0, run(checkout, "git", "init").exitCode());
        assertEquals(0, run(checkout, "git", "config", "user.email", "test@example.invalid").exitCode());
        assertEquals(0, run(checkout, "git", "config", "user.name", "Test").exitCode());
        Files.writeString(checkout.resolve("README.md"), "pinned\n");
        assertEquals(0, run(checkout, "git", "add", "README.md").exitCode());
        assertEquals(0, run(checkout, "git", "commit", "-m", "pinned").exitCode());
        String expected = run(checkout, "git", "rev-parse", "HEAD").output().strip();

        assertEquals(0, run(repo, "git", "init").exitCode());
        assertEquals(0, run(repo, "git", "update-index", "--add", "--cacheinfo",
                "160000," + expected + ",tools/tracechaser").exitCode());
        copy("tools/tracechaser-bootstrap.sh", repo);

        Result missing = run(repo, "bash", "tools/tracechaser-bootstrap.sh",
                "--require", "missing-command");
        assertEquals(4, missing.exitCode(), missing.output());
    }

    @Test
    void shellForwarderRejectsSymlinkAncestorWithoutExecutingOutside(@TempDir Path temp) throws Exception {
        UnsafeForwarderFixture fixture = unsafeForwarderFixture(temp, "bizhawk-headless");
        copy("tools/tracechaser-bootstrap.sh", fixture.root());
        copy("tools/tracechaser.sh", fixture.root());
        copy("tools/bizhawk-headless/run.sh", fixture.root());

        Result result = run(fixture.root(), Map.of("TRACECHASER_SENTINEL", fixture.marker().toString()),
                "bash", "tools/bizhawk-headless/run.sh");

        assertEquals(4, result.exitCode(), result.output());
        assertFalse(Files.exists(fixture.marker()), "outside shell target executed");
    }

    @Test
    void luaForwarderRejectsSymlinkAncestorWithoutExecutingOutside(@TempDir Path temp) throws Exception {
        UnsafeForwarderFixture fixture = unsafeForwarderFixture(temp, "bizhawk");
        copy("tools/tracechaser-bootstrap.sh", fixture.root());
        copy("tools/tracechaser-forward.lua", fixture.root());
        copy("tools/bizhawk/s1_trace_recorder.lua", fixture.root());

        Result result = run(fixture.root(), Map.of("TRACECHASER_SENTINEL", fixture.marker().toString()),
                LUA_BIN, "tools/bizhawk/s1_trace_recorder.lua");

        assertEquals(4, result.exitCode(), result.output());
        assertFalse(Files.exists(fixture.marker()), "outside Lua target executed");
    }

    @Test
    void pythonForwarderRejectsSymlinkAncestorWithoutExecutingOutside(@TempDir Path temp) throws Exception {
        UnsafeForwarderFixture fixture = unsafeForwarderFixture(temp, "traces");
        copy("tools/tracechaser-bootstrap.sh", fixture.root());
        copy("tools/traces/validate_trace_v5.py", fixture.root());

        Result result = run(fixture.root(), Map.of("TRACECHASER_SENTINEL", fixture.marker().toString()),
                "python3", "tools/traces/validate_trace_v5.py");

        assertEquals(4, result.exitCode(), result.output());
        assertFalse(Files.exists(fixture.marker()), "outside Python target executed");
    }

    @Test
    void powershellForwarderRejectsSymlinkAncestorWithoutExecutingOutside(@TempDir Path temp) throws Exception {
        UnsafeForwarderFixture fixture = unsafeForwarderFixture(temp, "traces");
        copy("tools/tracechaser-bootstrap.sh", fixture.root());
        copy("tools/traces/compress-traces.ps1", fixture.root());

        Result result = run(fixture.root(), Map.of("TRACECHASER_SENTINEL", fixture.marker().toString()),
                "pwsh", "-NoLogo", "-NoProfile", "-File", "tools/traces/compress-traces.ps1");

        assertEquals(4, result.exitCode(), result.output());
        assertFalse(Files.exists(fixture.marker()), "outside PowerShell target executed");
    }

    @Test
    void pythonForwarderRejectsFinalTargetSymlinkWithoutExecutingOutside(@TempDir Path temp) throws Exception {
        Path root = temp.resolve("repo");
        Path checkout = root.resolve("tools/tracechaser");
        Path target = checkout.resolve("traces/validate_trace_v5.py");
        Files.createDirectories(target.getParent());
        Files.writeString(target, "raise SystemExit(0)\n");
        assertEquals(0, run(checkout, "git", "init").exitCode());
        assertEquals(0, run(checkout, "git", "config", "user.email", "test@example.invalid").exitCode());
        assertEquals(0, run(checkout, "git", "config", "user.name", "Test").exitCode());
        assertEquals(0, run(checkout, "git", "add", ".").exitCode());
        assertEquals(0, run(checkout, "git", "commit", "-m", "pinned").exitCode());
        String expected = run(checkout, "git", "rev-parse", "HEAD").output().strip();
        assertEquals(0, run(root, "git", "init").exitCode());
        assertEquals(0, run(root, "git", "update-index", "--add", "--cacheinfo",
                "160000," + expected + ",tools/tracechaser").exitCode());
        copy("tools/tracechaser-bootstrap.sh", root);
        copy("tools/traces/validate_trace_v5.py", root);

        Path marker = temp.resolve("outside-executed");
        Path outside = temp.resolve("outside.py");
        Files.writeString(outside,
                "import os, pathlib\npathlib.Path(os.environ['TRACECHASER_SENTINEL']).write_text('python')\n");
        Files.delete(target);
        Files.createSymbolicLink(target, outside);

        Result result = run(root, Map.of("TRACECHASER_SENTINEL", marker.toString()),
                "python3", "tools/traces/validate_trace_v5.py");

        assertEquals(4, result.exitCode(), result.output());
        assertFalse(Files.exists(marker), "outside final Python target executed");
    }

    private static UnsafeForwarderFixture unsafeForwarderFixture(Path temp, String symlinkAncestor)
            throws Exception {
        Path root = temp.resolve("repo");
        Path checkout = root.resolve("tools/tracechaser");
        Path outside = temp.resolve("outside");
        Files.createDirectories(checkout.resolve("traces"));
        Files.createDirectories(checkout.resolve("bizhawk-headless"));
        Files.createDirectories(checkout.resolve("bizhawk"));
        Files.createDirectories(outside.resolve(symlinkAncestor));
        Files.writeString(checkout.resolve("traces/validate_trace_v5.py"), "raise SystemExit(0)\n");
        Files.writeString(checkout.resolve("traces/compress-traces.ps1"), "exit 0\n");
        Files.writeString(checkout.resolve("bizhawk-headless/run.sh"), "exit 0\n");
        Files.writeString(checkout.resolve("bizhawk/s1_trace_recorder.lua"), "return true\n");
        assertEquals(0, run(checkout, "git", "init").exitCode());
        assertEquals(0, run(checkout, "git", "config", "user.email", "test@example.invalid").exitCode());
        assertEquals(0, run(checkout, "git", "config", "user.name", "Test").exitCode());
        assertEquals(0, run(checkout, "git", "add", ".").exitCode());
        assertEquals(0, run(checkout, "git", "commit", "-m", "pinned").exitCode());
        String expected = run(checkout, "git", "rev-parse", "HEAD").output().strip();

        assertEquals(0, run(root, "git", "init").exitCode());
        assertEquals(0, run(root, "git", "update-index", "--add", "--cacheinfo",
                "160000," + expected + ",tools/tracechaser").exitCode());

        Path trackedAncestor = checkout.resolve(symlinkAncestor);
        try (var paths = Files.walk(trackedAncestor)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
        }
        Files.createSymbolicLink(trackedAncestor, outside.resolve(symlinkAncestor));

        Path marker = temp.resolve("outside-executed");
        switch (symlinkAncestor) {
            case "traces" -> {
                Files.writeString(outside.resolve("traces/validate_trace_v5.py"),
                        "import os, pathlib\npathlib.Path(os.environ['TRACECHASER_SENTINEL']).write_text('python')\n");
                Files.writeString(outside.resolve("traces/compress-traces.ps1"),
                        "Set-Content -Path $env:TRACECHASER_SENTINEL -Value powershell\n");
            }
            case "bizhawk-headless" -> {
                Path script = outside.resolve("bizhawk-headless/run.sh");
                Files.writeString(script, "printf shell > \"$TRACECHASER_SENTINEL\"\n");
                Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwxr-xr-x"));
            }
            case "bizhawk" -> Files.writeString(outside.resolve("bizhawk/s1_trace_recorder.lua"),
                    "local f=assert(io.open(os.getenv('TRACECHASER_SENTINEL'),'w')); f:write('lua'); f:close()\n");
            default -> throw new IllegalArgumentException(symlinkAncestor);
        }
        return new UnsafeForwarderFixture(root, marker);
    }

    private static void copy(String relative, Path root) throws IOException {
        Path destination = root.resolve(relative);
        Files.createDirectories(destination.getParent());
        Files.copy(Path.of(relative), destination);
    }

    private static Result run(Path cwd, String... command) throws Exception {
        return run(cwd, Map.of(), command);
    }

    private static Result run(Path cwd, Map<String, String> environment, String... command) throws Exception {
        ProcessBuilder builder = new ProcessBuilder(List.of(command))
                .directory(cwd.toFile()).redirectErrorStream(true);
        builder.environment().putAll(environment);
        Process process = builder.start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(process.waitFor(30, TimeUnit.SECONDS), "command timed out: " + List.of(command));
        return new Result(process.exitValue(), output);
    }

    private record Result(int exitCode, String output) { }

    private record UnsafeForwarderFixture(Path root, Path marker) { }
}
