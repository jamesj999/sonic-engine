package com.openggf.level.objects;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * Guards against the "invisible spawn" latent bug.
 *
 * <p>{@code AbstractObjectInstance.getRenderManager()} (and the {@code getRenderer},
 * {@code getAnimalRenderer}, {@code getPointsRenderer} helpers built on it) resolve
 * through {@code tryServices()}, which returns {@code null} during construction
 * unless the {@code CONSTRUCTION_CONTEXT} ThreadLocal is set. Unlike {@code services()}
 * (which throws — see {@code TestNoServicesInObjectConstructors}), these return null
 * <em>silently</em>. So an object that caches a renderer in its constructor and is
 * then spawned via a raw {@code addDynamicObject*(...)} call (which sets services only
 * AFTER construction) captures a null renderer and renders <strong>invisibly</strong>,
 * with no error.
 *
 * <p>Safe spawn paths set the construction context: {@code spawnChild},
 * {@code spawnFreeChild}, {@code createDynamicObject}, and the {@code ObjectManager}
 * registry/placement build. This test fails if a renderer-capturing object is handed
 * directly to one of the context-less {@code addDynamicObject*} APIs.
 *
 * <p>Root cause history: DI-migration commit {@code a74ad02e5} made
 * {@code getRenderManager()} per-instance; the S2/HCZ egg-prison capsule animals went
 * invisible because they were spawned via raw {@code addDynamicObject}. Fixed by
 * routing through {@code spawnFreeChild}.
 */
public class TestNoRendererCaptureInUnsafeSpawn {

    /**
     * Inherited {@code AbstractObjectInstance} helpers that resolve through the silent
     * {@code tryServices()} path. Matched only when called UNQUALIFIED (the implicit
     * {@code this} receiver) — {@code renderManager.getRenderer(...)} /
     * {@code services.renderManager().getAnimalRenderer()} are safe because they use an
     * explicitly-held render manager, not the construction-time service lookup.
     */
    private static final Pattern RENDERER_CAPTURE = Pattern.compile(
            "(?<![.\\w])(?:getRenderManager|getRenderer)\\s*\\(");

    /** Context-less dynamic-spawn APIs (do NOT set CONSTRUCTION_CONTEXT). */
    private static final String UNSAFE_SPAWN_APIS =
            "addDynamicObject|addDynamicObjectAtSlot|addDynamicObjectNextFrame"
                    + "|addDynamicObjectAfterCurrent|addDynamicObjectAfterCurrentNextFrame"
                    + "|addDynamicObjectToReservedSlot";

    /**
     * Framework / bridge files that legitimately call the raw spawn APIs (they ARE
     * the context-setting wrappers, or they set the context explicitly).
     */
    private static final Set<String> EXCLUDED_FILES = Set.of(
            "AbstractObjectInstance.java",
            "ObjectManager.java",
            "DefaultPowerUpSpawner.java");

    @Test
    public void rendererCapturingObjects_mustNotBeSpawnedViaRawAddDynamicObject() throws IOException {
        Path srcMain = Path.of("src/main/java");
        if (!Files.isDirectory(srcMain)) {
            fail("could not locate src/main/java for guard scan");
        }

        Set<String> rendererCapturingClasses = findClassesCapturingRendererInConstructor(srcMain);
        List<String> violations = new ArrayList<>();

        Pattern inlineNew = Pattern.compile(
                "(?:" + UNSAFE_SPAWN_APIS + ")\\s*\\(\\s*new\\s+(\\w+)\\s*\\(");
        Pattern construction = Pattern.compile(
                "(?:\\w+(?:<[^>]+>)?|var)\\s+(\\w+)\\s*=\\s*new\\s+(\\w+)\\s*\\(");
        Pattern varSpawn = Pattern.compile(
                "(?:" + UNSAFE_SPAWN_APIS + ")\\s*\\(\\s*(\\w+)\\s*[,)]");

        try (Stream<Path> files = Files.walk(srcMain)) {
            files.filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> !EXCLUDED_FILES.contains(p.getFileName().toString()))
                    .filter(p -> !p.getFileName().toString().startsWith("Test"))
                    .forEach(path -> {
                        try {
                            String fileName = path.getFileName().toString();
                            String[] lines = Files.readString(path).split("\n");
                            Map<String, String> pending = new HashMap<>();
                            for (int i = 0; i < lines.length; i++) {
                                String line = lines[i];
                                String trimmed = line.trim();
                                if (trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*")) {
                                    continue;
                                }

                                Matcher ctor = construction.matcher(line);
                                while (ctor.find()) {
                                    pending.put(ctor.group(1), ctor.group(2));
                                }

                                // Inline: addDynamicObject(new X(...))
                                Matcher inline = inlineNew.matcher(line);
                                while (inline.find()) {
                                    String className = inline.group(1);
                                    if (rendererCapturingClasses.contains(className)) {
                                        violations.add(fileName + ":" + (i + 1) + ": "
                                                + "spawns " + className + " via raw addDynamicObject(new ...)");
                                    }
                                }

                                // Two-line: X v = new X(...); ... addDynamicObject(v)
                                Matcher var = varSpawn.matcher(line);
                                while (var.find()) {
                                    String cls = pending.get(var.group(1));
                                    if (cls != null && rendererCapturingClasses.contains(cls)) {
                                        violations.add(fileName + ":" + (i + 1) + ": "
                                                + "spawns " + cls + " (var " + var.group(1)
                                                + ") via raw addDynamicObject(...)");
                                    }
                                }
                            }
                        } catch (IOException ignored) {
                        }
                    });
        }

        if (!violations.isEmpty()) {
            fail("Renderer-capturing objects spawned via a context-less addDynamicObject* API.\n"
                    + "These capture a null renderer at construction and render invisibly.\n"
                    + "Spawn them via spawnChild(() -> new X(...)) / spawnFreeChild(() -> new X(...)),\n"
                    + "or fetch the renderer lazily in appendRenderCommands() instead.\n\n  "
                    + String.join("\n  ", violations));
        }
    }

    /**
     * Returns the simple names of classes whose constructor body calls one of the
     * renderer-resolving helpers (and therefore caches a renderer that is null
     * unless the construction context is set).
     */
    private static Set<String> findClassesCapturingRendererInConstructor(Path srcMain) throws IOException {
        Set<String> result = new HashSet<>();
        try (Stream<Path> files = Files.walk(srcMain)) {
            files.filter(p -> p.toString().endsWith(".java"))
                    .forEach(path -> {
                        try {
                            String content = Files.readString(path);
                            String className = path.getFileName().toString().replace(".java", "");
                            if (constructorCapturesRenderer(content, className)) {
                                result.add(className);
                            }
                        } catch (IOException ignored) {
                        }
                    });
        }
        return result;
    }

    private static boolean constructorCapturesRenderer(String content, String className) {
        Pattern ctorPattern = Pattern.compile(
                "(?:public\\s+|protected\\s+|private\\s+)?" + Pattern.quote(className)
                        + "\\s*\\([^)]*\\)\\s*(?:throws\\s+[^{]+)?\\{");
        Matcher ctor = ctorPattern.matcher(content);
        while (ctor.find()) {
            int start = ctor.end();
            int depth = 1;
            int end = start;
            while (end < content.length() && depth > 0) {
                char c = content.charAt(end);
                if (c == '{') depth++;
                else if (c == '}') depth--;
                end++;
            }
            if (RENDERER_CAPTURE.matcher(content.substring(start, end)).find()) {
                return true;
            }
        }
        return false;
    }
}
