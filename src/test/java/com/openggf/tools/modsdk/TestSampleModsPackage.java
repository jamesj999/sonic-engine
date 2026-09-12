package com.openggf.tools.modsdk;

import com.openggf.io.ModInputLimits;
import com.openggf.version.AppVersion;
import com.openggf.mods.DefaultModRepositoryScanner;
import com.openggf.mods.ModCatalogValidator;
import com.openggf.mods.ModDescriptor;
import com.openggf.mods.ModType;
import com.openggf.tests.TestSessionOutputPaths;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.ToolProvider;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestSampleModsPackage {
    private static final Path MUSIC = Path.of("docs/modding/samples/phase4-gallery-music-pack");
    private static final Path RESKIN = Path.of("src/test/resources/mods/sample-reskin-src");
    private static final Path BADNIK_ZONE = Path.of("src/test/resources/mods/sample-mod-src/project");
    private static final Path CHARACTER = Path.of("src/test/resources/mods/sample-character-src/project");
    private static final Path STANDALONE = Path.of("src/test/resources/mods/sample-standalone-src/project");
    private static final Path FLAPPY = Path.of("src/test/resources/mods/sample-flappy-src/project");
    private static final Path PLATFORMER = Path.of("src/test/resources/mods/sample-platformer-src/project");
    private static final Path ROM_ART_REMIX = Path.of(
            "src/test/resources/mods/sample-rom-art-remix-src/project");
    private static final Path ROM_ART_GUIDE = Path.of(
            "docs/modding/guides/rom-art-remix.md");
    private static final Path FLAPPY_GUIDE = Path.of(
            "docs/modding/guides/native-tails-flappy.md");
    private static final Path STANDALONE_GUIDE = Path.of(
            "docs/modding/guides/standalone-platformer.md");
    private static final Path AI_ART_GUIDE = Path.of(
            "docs/modding/guides/ai-art.md");
    private static final String RETIRED_FLAPPY_GUIDE_STEM = "flappy" + "-remix";
    private static final Set<String> EXPECTED_IDS = Set.of(
            "openggf-gallery-music-sample", "phase2-reskin", "phase2-sample",
            "phase3-character", "phase3-standalone", "sample-flappy", "sample-platformer",
            "sample-rom-art-remix");
    private static final Map<String, String> EXPECTED_API_RANGES = Map.of(
            "openggf-gallery-music-sample", ">=0.7.0 <0.8.0",
            "phase2-reskin", ">=0.7.0 <0.8.0",
            "phase2-sample", ">=0.7.0 <0.8.0",
            "phase3-character", ">=0.7.0 <0.8.0",
            "phase3-standalone", ">=0.7.0 <0.8.0",
            "sample-flappy", ">=0.7.0 <0.8.0",
            "sample-platformer", ">=0.7.0 <0.8.0",
            "sample-rom-art-remix", ">=0.7.0 <0.8.0");
    private static final Map<String, String> EXPECTED_PATCH_BASE_GAMES = Map.of(
            "openggf-gallery-music-sample", "s2",
            "phase2-reskin", "s2",
            "phase2-sample", "s2",
            "phase3-character", "s2",
            "sample-flappy", "s3k",
            "sample-rom-art-remix", "s2");
    private static final Set<String> TRUSTED_CODE_SAMPLES = Set.of(
            "phase2-sample", "phase3-character", "phase3-standalone", "sample-flappy",
            "sample-platformer", "sample-rom-art-remix");
    private static final Set<String> STANDALONE_IDS = Set.of("phase3-standalone", "sample-platformer");

    @TempDir Path temp;

    @Test
    void exactlyEightMaintainedSourcesBuildThroughRealPackageAndValidateAsOneRepository() throws Exception {
        List<Sample> samples = List.of(
                new Sample("music", this::materializeMusic),
                new Sample("reskin", this::materializeReskin),
                new Sample("badnik-zone", this::materializeBadnikZone),
                new Sample("character", this::materializeCharacter),
                new Sample("standalone", this::materializeStandalone),
                new Sample("flappy", this::materializeFlappy),
                new Sample("platformer", this::materializePlatformer),
                new Sample("rom-art-remix", this::materializeRomArtRemix));
        assertEquals(8, samples.size(), "The maintained gallery contract is exactly eight source mods");

        Path repository = temp.resolve("repository");
        Files.createDirectory(repository);
        for (Sample sample : samples) {
            Path exploded = temp.resolve(sample.name() + "-exploded");
            Files.createDirectories(exploded);
            sample.materializer().materialize(exploded);
            Path jar = repository.resolve(sample.name() + ".jar");
            assertCli("package", "--input", exploded.toString(), "--out", jar.toString());
            assertTrue(Files.isRegularFile(jar), sample.name());
            assertCleanValidation(jar);
        }

        var scanned = new DefaultModRepositoryScanner().scan(repository.toAbsolutePath().normalize());
        assertEquals(8, scanned.size());
        var validated = new ModCatalogValidator(repository.toAbsolutePath().normalize(),
                ModInputLimits.production(), (game, stockId) -> true).validate(scanned);
        assertEquals(8, validated.entries().size());
        Set<String> ids = new java.util.LinkedHashSet<>();
        for (var entry : validated.entries()) {
            ModDescriptor descriptor = assertInstanceOf(ModDescriptor.class, entry);
            assertFalse(descriptor.hasErrors(), descriptor.findings()::toString);
            String id = descriptor.manifest().id();
            ids.add(id);
            assertEquals(EXPECTED_API_RANGES.get(id), descriptor.manifest().engineApiRange().toString(), id);
            boolean trustedCode = TRUSTED_CODE_SAMPLES.contains(id);
            assertEquals(trustedCode, descriptor.containsCode(), id + " code inventory");
            assertEquals(trustedCode, descriptor.manifest().entrypoint() != null, id + " entrypoint");
            boolean standalone = STANDALONE_IDS.contains(id);
            assertEquals(standalone ? ModType.STANDALONE : ModType.PATCH,
                    descriptor.manifest().type(), id + " type");
            assertEquals(standalone ? null : EXPECTED_PATCH_BASE_GAMES.get(id),
                    descriptor.manifest().baseGame(), id + " base game");
            assertTrue(descriptor.findings().isEmpty(), id + " catalog findings: " + descriptor.findings());
        }
        assertEquals(EXPECTED_IDS, ids);
    }

    @Test
    void romArtAndNativeTailsGuidesAreLinkedWithoutStaleFlappyRemixReferences() throws Exception {
        assertTrue(Files.isRegularFile(ROM_ART_GUIDE));
        String romArt = Files.readString(ROM_ART_GUIDE);
        assertTrue(romArt.contains("## 3. Request the bounded ROM window"));
        assertTrue(romArt.contains("Sonic 2 palette line 0 is Pal_SonicTails"));
        String artifactPrefix = "target/OpenGGF-" + AppVersion.identity().baseVersion();
        assertTrue(romArt.contains(artifactPrefix + ".jar"));
        assertTrue(romArt.contains(
                artifactPrefix + "-openggf-mod-sdk.jar"));
        assertTrue(romArt.contains("inserted after EHZ2"));
        assertFalse(romArt.contains("EHZ2 replacement zone"));
        String standalone = Files.readString(STANDALONE_GUIDE);
        assertTrue(standalone.contains(
                "rom-art-remix.md#3-request-the-bounded-rom-window"));

        assertTrue(Files.isRegularFile(FLAPPY_GUIDE));
        String nativeTails = Files.readString(FLAPPY_GUIDE);
        assertTrue(nativeTails.contains("## 4. Level and camera"));
        assertTrue(nativeTails.contains("## 6. Pipes, score, death, and rewind"));
        String flappyReadme = Files.readString(FLAPPY.getParent().resolve("README.md"));
        assertTrue(flappyReadme.contains("docs/modding/guides/native-tails-flappy.md"));
        String flappyProjectReadme = Files.readString(FLAPPY.resolve("README.md"));
        assertTrue(flappyProjectReadme.contains("docs/modding/guides/native-tails-flappy.md"));
        assertTrue(standalone.contains("native-tails-flappy.md#4-level-and-camera"));
        String aiArt = Files.readString(AI_ART_GUIDE);
        assertTrue(aiArt.contains("native-tails-flappy.md#6-pipes-score-death-and-rewind"));

        String outerReadme = Files.readString(ROM_ART_REMIX.getParent().resolve("README.md"));
        String projectReadme = Files.readString(ROM_ART_REMIX.resolve("README.md"));
        assertTrue(outerReadme.contains("docs/modding/guides/rom-art-remix.md"));
        assertTrue(projectReadme.contains("docs/modding/guides/rom-art-remix.md"));

        assertNoStaleFlappyRemixReferences(Path.of("docs/modding"));
        assertNoStaleFlappyRemixReferences(Path.of(
                "src/test/resources/mods/sample-flappy-src"));
    }

    private static void assertNoStaleFlappyRemixReferences(Path root) throws Exception {
        try (var paths = Files.walk(root)) {
            for (Path path : paths.filter(Files::isRegularFile).toList()) {
                String name = path.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
                if (!name.endsWith(".md") && !name.endsWith(".java")
                        && !name.endsWith(".yaml") && !name.endsWith(".json")
                        && !name.endsWith(".properties") && !name.endsWith(".ps1")
                        && !name.endsWith(".sh") && !name.endsWith(".xml")
                        && !name.endsWith(".tmx")) {
                    continue;
                }
                String text = Files.readString(path).toLowerCase(java.util.Locale.ROOT);
                assertFalse(text.contains(RETIRED_FLAPPY_GUIDE_STEM),
                        () -> "stale retired Flappy guide reference in " + path);
            }
        }
    }

    private void materializeMusic(Path output) throws Exception {
        copyTree(MUSIC, output);
        String python = System.getProperty("os.name", "").startsWith("Windows") ? "python" : "python3";
        Process process = new ProcessBuilder(python, output.resolve("generate-assets.py").toString())
                .redirectErrorStream(true).start();
        String log = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, process.waitFor(), log);
        assertTrue(Files.isRegularFile(output.resolve("audio/gallery-theme.wav")));
    }

    private void materializeReskin(Path output) throws Exception {
        Files.createDirectories(output.resolve("META-INF"));
        Files.copy(RESKIN.resolve("META-INF/openggf-mod.yaml"),
                output.resolve("META-INF/openggf-mod.yaml"));
        Path image = temp.resolve("reskin.png");
        Files.write(image, Base64.getDecoder().decode(Files.readString(
                RESKIN.resolve("reskin.png.base64")).trim()));
        assertCli("convert", "art", "--image", image.toString(), "--sheet",
                RESKIN.resolve("reskin-sheet.yaml").toString(), "--out",
                output.resolve("art/reskin.ggfs").toString());
    }

    private void materializeBadnikZone(Path output) throws Exception {
        copyTree(BADNIK_ZONE.resolve("src/main/resources"), output);
        compileJava(BADNIK_ZONE.resolve("src/main/java"), output);
        assertCli("convert", "art", "--image", BADNIK_ZONE.resolve("src/main/mod/sample.png").toString(),
                "--sheet", BADNIK_ZONE.resolve("src/main/mod/sample-sheet.yaml").toString(),
                "--out", output.resolve("art/sample.ggfs").toString());
        Path level = materializeLevel(BADNIK_ZONE.resolve("src/main/mod/level-source"), "badnik-level");
        assertCli("convert", "level", "--from-export", level.toString(),
                "--out", output.resolve("levels/sample").toString());
    }

    private void materializeCharacter(Path output) throws Exception {
        copyTree(CHARACTER.resolve("src/main/resources"), output);
        compileJava(CHARACTER.resolve("src/main/java"), output);
        Path image = temp.resolve("character-runner.png");
        Files.write(image, Base64.getDecoder().decode(Files.readString(
                CHARACTER.resolve("src/main/mod/runner.png.base64")).trim()));
        assertCli("convert", "art", "--playable", "--image", image.toString(),
                "--sheet", CHARACTER.resolve("src/main/mod/runner-sheet.yaml").toString(),
                "--out", output.resolve("art/runner.ggfp").toString());
    }

    private void materializeStandalone(Path output) throws Exception {
        copyTree(STANDALONE.resolve("src/main/resources"), output);
        compileJava(STANDALONE.resolve("src/main/java"), output);
        Path image = temp.resolve("standalone-runner.png");
        Files.write(image, Base64.getDecoder().decode(Files.readString(
                STANDALONE.resolve("src/main/mod/runner.png.base64")).trim()));
        assertCli("convert", "art", "--playable", "--image", image.toString(),
                "--sheet", STANDALONE.resolve("src/main/mod/runner-sheet.yaml").toString(),
                "--out", output.resolve("art/runner.ggfp").toString());
        Path level = materializeLevel(STANDALONE.resolve("src/main/mod/level-source"), "standalone-level");
        assertCli("convert", "level", "--from-export", level.toString(),
                "--out", output.resolve("levels/sample").toString());
        Files.write(output.resolve("audio/sample-tone.wav"), Base64.getDecoder().decode(Files.readString(
                STANDALONE.resolve("src/main/mod/sample-tone.wav.base64")).trim()));
    }

    private void materializeFlappy(Path output) throws Exception {
        copyTree(FLAPPY.resolve("src/main/resources"), output);
        compileJava(FLAPPY.resolve("src/main/java"), output);
        assertCli("convert", "art", "--image", FLAPPY.resolve("src/main/mod/pipe.png").toString(),
                "--sheet", FLAPPY.resolve("src/main/mod/pipe-sheet.yaml").toString(),
                "--out", output.resolve("art/pipe.ggfs").toString());
        Path level = materializeLevel(FLAPPY.resolve("src/main/mod/level-source"), "flappy-level");
        assertCli("convert", "level", "--from-export", level.toString(),
                "--out", output.resolve("levels/flappy").toString());
    }

    private void materializePlatformer(Path output) throws Exception {
        copyTree(PLATFORMER.resolve("src/main/resources"), output);
        compileJava(PLATFORMER.resolve("src/main/java"), output);
        assertCli("convert", "art", "--image", PLATFORMER.resolve("src/main/mod/zapbug.png").toString(),
                "--sheet", PLATFORMER.resolve("src/main/mod/zapbug-sheet.yaml").toString(),
                "--out", output.resolve("art/zapbug.ggfs").toString());
        assertCli("convert", "art", "--image", PLATFORMER.resolve("src/main/mod/springpad.png").toString(),
                "--sheet", PLATFORMER.resolve("src/main/mod/springpad-sheet.yaml").toString(),
                "--out", output.resolve("art/springpad.ggfs").toString());
        assertCli("convert", "art", "--image", PLATFORMER.resolve("src/main/mod/ring.png").toString(),
                "--sheet", PLATFORMER.resolve("src/main/mod/ring-sheet.yaml").toString(),
                "--out", output.resolve("art/ring.ggfs").toString());
        assertCli("convert", "art", "--playable", "--image", PLATFORMER.resolve("src/main/mod/bolt.png").toString(),
                "--sheet", PLATFORMER.resolve("src/main/mod/bolt-sheet.yaml").toString(),
                "--out", output.resolve("art/bolt.ggfp").toString());
        assertCli("convert", "level", "--from-tmx", PLATFORMER.resolve("src/main/mod/level.tmx").toString(),
                "--palette", PLATFORMER.resolve("src/main/mod/palette.gpal").toString(),
                "--music", "sample-platformer:zone-theme",
                "--out", output.resolve("levels/act1").toString());
    }

    private void materializeRomArtRemix(Path output) throws Exception {
        copyTree(ROM_ART_REMIX.resolve("src/main/resources"), output);
        compileJava(ROM_ART_REMIX.resolve("src/main/java"), output);
        Path level = materializeLevel(
                ROM_ART_REMIX.resolve("src/main/mod/level-source"), "rom-art-remix-level");
        assertCli("convert", "level", "--from-export", level.toString(),
                "--out", output.resolve("levels/rom-art-gallery").toString());
    }

    private Path materializeLevel(Path source, String name) throws Exception {
        Path output = temp.resolve(name);
        copyTree(source, output);
        Path encoded = output.resolve("binary-assets.properties");
        Properties assets = new Properties();
        try (var input = Files.newInputStream(encoded)) {
            assets.load(input);
        }
        Files.delete(encoded);
        for (String file : assets.stringPropertyNames()) {
            Files.write(output.resolve(file), Base64.getDecoder().decode(assets.getProperty(file)));
        }
        return output;
    }

    private static void compileJava(Path source, Path output) throws Exception {
        List<String> arguments = new ArrayList<>(List.of("--release", "21", "-classpath",
                TestSessionOutputPaths.compiledClasses().toAbsolutePath().toString(),
                "-d", output.toString()));
        try (var files = Files.walk(source)) {
            files.filter(path -> path.toString().endsWith(".java")).sorted()
                    .map(Path::toString).forEach(arguments::add);
        }
        int exit = ToolProvider.getSystemJavaCompiler().run(null, null, null,
                arguments.toArray(String[]::new));
        assertEquals(0, exit, source.toString());
    }

    private static void copyTree(Path source, Path output) throws Exception {
        try (var files = Files.walk(source)) {
            for (Path file : files.toList()) {
                Path destination = output.resolve(source.relativize(file).toString());
                if (Files.isDirectory(file)) Files.createDirectories(destination);
                else {
                    Files.createDirectories(destination.getParent());
                    Files.copy(file, destination);
                }
            }
        }
    }

    private static void assertCli(String... arguments) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        int exit = GgfModCli.run(arguments, new PrintStream(bytes));
        assertEquals(0, exit, bytes.toString(StandardCharsets.UTF_8));
    }

    private static void assertCleanValidation(Path jar) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        int exit = GgfModCli.run(new String[]{"validate", jar.toString()}, new PrintStream(bytes));
        String output = bytes.toString(StandardCharsets.UTF_8);
        assertEquals(0, exit, output);
        assertTrue(output.contains("Validation passed: 0 findings"), output);
    }

    private record Sample(String name, Materializer materializer) { }

    @FunctionalInterface
    private interface Materializer {
        void materialize(Path output) throws Exception;
    }
}
