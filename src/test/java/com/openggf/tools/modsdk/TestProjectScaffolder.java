package com.openggf.tools.modsdk;

import com.openggf.version.AppVersion;

import com.openggf.tests.TestSessionOutputPaths;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.ToolProvider;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class TestProjectScaffolder {
    @TempDir Path temp;

    @Test void createsExactCompilableUneditedProjectAndRefusesOverwrite() throws Exception {
        Path project = temp.resolve("sample");
        new ProjectScaffolder().scaffold(project, "my-sample", "example.mods.sample");

        String pom = Files.readString(project.resolve("pom.xml"));
        String dependencyVersion = "<version>" + AppVersion.identity().baseVersion() + "</version>";
        assertEquals(2, pom.split(java.util.regex.Pattern.quote(dependencyVersion), -1).length - 1,
                "engine and SDK dependencies must match the running build's Maven version");
        assertFalse(pom.contains("{{ENGINE_VERSION}}"));

        Set<String> files;
        try (var paths = Files.walk(project)) {
            files = paths.filter(Files::isRegularFile).map(project::relativize)
                    .map(Path::toString).map(s -> s.replace('\\', '/')).collect(Collectors.toSet());
        }
        assertEquals(Set.of("pom.xml", "README.md",
                "src/main/java/example/mods/sample/MySampleMod.java",
                "src/main/java/example/mods/sample/SampleBadnik.java",
                "src/main/java/example/mods/sample/SampleCharacter.java",
                "src/main/resources/META-INF/openggf-mod.yaml",
                "src/main/mod/sample.png", "src/main/mod/sample-sheet.yaml",
                "src/main/mod/level-source/level.json",
                "src/main/mod/level-source/patterns.bin",
                "src/main/mod/level-source/chunks.bin",
                "src/main/mod/level-source/blocks.bin",
                "src/main/mod/level-source/fg-map.bin",
                "src/main/mod/level-source/solid-heights.bin",
                "src/main/mod/level-source/solid-widths.bin",
                "src/main/mod/level-source/solid-angles.bin",
                "src/main/mod/level-source/collision-primary.bin",
                "src/main/mod/level-source/collision-secondary.bin",
                "src/main/mod/level-source/palettes.bin"), files);

        ByteBuffer chunks = ByteBuffer.wrap(Files.readAllBytes(project.resolve(
                "src/main/mod/level-source/chunks.bin"))).order(ByteOrder.BIG_ENDIAN);
        for (int piece = 0; piece < 4; piece++) {
            assertEquals(0x2001, Short.toUnsignedInt(chunks.getShort(20 + piece * 2)),
                    "generated floor must use creator-owned palette line 1");
        }
        ByteBuffer palettes = ByteBuffer.wrap(Files.readAllBytes(project.resolve(
                "src/main/mod/level-source/palettes.bin"))).order(ByteOrder.BIG_ENDIAN);
        assertEquals(0, Short.toUnsignedInt(palettes.getShort(12 + 2)),
                "host-owned line 0 must remain a placeholder");
        assertEquals(0x0EEE, Short.toUnsignedInt(palettes.getShort(12 + 32 + 2)));
        assertEquals(0x0EEE, Short.toUnsignedInt(palettes.getShort(12 + 32 + 30)));
        assertTrue(Files.readString(project.resolve("src/main/mod/sample-sheet.yaml"))
                .contains("paletteLine: 1"),
                "generated object art must share creator-owned line 1");

        var compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler);
        Path classes = temp.resolve("classes"); Files.createDirectories(classes);
        List<String> sources;
        try (var paths = Files.walk(project.resolve("src/main/java"))) {
            sources = paths.filter(p -> p.toString().endsWith(".java"))
                    .map(Path::toString).toList();
        }
        var args = new java.util.ArrayList<>(List.of("--release", "21", "-cp",
                TestSessionOutputPaths.compiledClasses().toAbsolutePath().toString(),
                "-d", classes.toString()));
        args.addAll(sources);
        assertEquals(0, compiler.run(null, null, null, args.toArray(String[]::new)));
        Path convertedArt = classes.resolve("art/sample.ggfs"); Files.createDirectories(convertedArt.getParent());
        new ArtConverter().convert(project.resolve("src/main/mod/sample.png"),
                project.resolve("src/main/mod/sample-sheet.yaml"), convertedArt);
        new LevelConverter().convert(project.resolve("src/main/mod/level-source"),
                classes.resolve("levels/sample"));
        Path manifestSource = project.resolve("src/main/resources/META-INF/openggf-mod.yaml");
        Path manifestTarget = classes.resolve("META-INF/openggf-mod.yaml");
        Files.createDirectories(manifestTarget.getParent()); Files.copy(manifestSource, manifestTarget);
        Path jar = temp.resolve("my-sample.jar");
        JarPackager.packageDirectory(classes, jar);
        assertTrue(Files.isRegularFile(jar));
        assertTrue(new ModJarValidator().validate(jar).valid());

        Path localEngine=temp.resolve("local-engine.jar");
        createJar(TestSessionOutputPaths.compiledClasses(),localEngine);
        String mavenExecutable=System.getProperty("os.name","").startsWith("Windows")?"mvn.cmd":"mvn";
        Process maven=new ProcessBuilder(mavenExecutable,"-q","package",
                "-Dopenggf.engine.jar="+localEngine.toAbsolutePath(),
                "-Dopenggf.sdk.jar="+localEngine.toAbsolutePath())
                .directory(project.toFile()).redirectErrorStream(true).start();
        String buildOutput=new String(maven.getInputStream().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
        assertEquals(0,maven.waitFor(),buildOutput);
        Path mavenMod=project.resolve("target/my-sample-mod.jar");
        assertTrue(Files.isRegularFile(mavenMod),buildOutput);
        assertTrue(new ModJarValidator().validate(mavenMod).valid());
        assertThrows(Exception.class,
                () -> new ProjectScaffolder().scaffold(project, "my-sample", "example.mods.sample"));
    }

    private static void createJar(Path root,Path jar)throws Exception{
        try(JarOutputStream output=new JarOutputStream(Files.newOutputStream(jar));var paths=Files.walk(root)){
            for(Path file:paths.filter(Files::isRegularFile).sorted().toList()){
                output.putNextEntry(new JarEntry(root.relativize(file).toString().replace('\\','/')));
                Files.copy(file,output);output.closeEntry();
            }
        }
    }

    @Test void rejectsInvalidIdentityBeforeCreatingOutput() {
        Path output = temp.resolve("bad");
        assertThrows(IllegalArgumentException.class,
                () -> new ProjectScaffolder().scaffold(output, "Upper", "not-a-package"));
        assertFalse(Files.exists(output));
    }
}
