package com.openggf.game.rewind.coverage;

import com.openggf.level.objects.AbstractObjectInstance;

import java.io.IOException;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Source-file-based class enumeration for the rewind coverage analyzer.
 *
 * <p>Walks {@code src/main/java} (or a caller-supplied root) under the
 * game-object packages, reads each {@code .java} file, and identifies
 * concrete (non-abstract) subclasses of {@code AbstractObjectInstance}
 * using the source-level {@code extends} chain — the same mechanism used
 * by the project's existing guard tests (e.g. {@code TestObjectServicesMigrationGuard}).
 *
 * <p>No classpath scanning library or reflection is required; this intentionally
 * mirrors the existing source-scan approach so the tool stays consistent with
 * the rest of the guard infrastructure.
 */
public final class ObjectClasspathScan {

    /** Package paths (relative to {@code src/main/java}) to scan. */
    static final String[] SCANNED_PACKAGES = {
            "com/openggf/game/sonic1/objects",
            "com/openggf/game/sonic2/objects",
            "com/openggf/game/sonic3k/objects",
            "com/openggf/level/objects",
    };

    /** Simple name of the base class every spawnable object must extend. */
    private static final String BASE_CLASS_SIMPLE_NAME = "AbstractObjectInstance";

    private static final Pattern EXTENDS_PATTERN =
            Pattern.compile("\\bclass\\s+\\w+\\s+extends\\s+(\\w+)");
    private static final Pattern ABSTRACT_PATTERN =
            Pattern.compile("\\babstract\\s+class\\b");

    private ObjectClasspathScan() {
    }

    /**
     * Returns all concrete (non-abstract) subclasses of
     * {@code AbstractObjectInstance} found under the scanned packages
     * relative to the given {@code srcMain} root.
     *
     * <p>Results are {@link SourceClass} records carrying the fully-qualified
     * class name, the package path it was found under, and whether it is
     * abstract.
     */
    public static List<SourceClass> findConcreteObjectInstances(Path srcMain) throws IOException {
        // Step 1: load all source files in the scanned packages
        Map<String, SourceEntry> bySimpleName = new HashMap<>();
        List<SourceEntry> entries = new ArrayList<>();

        for (String pkg : SCANNED_PACKAGES) {
            Path pkgDir = srcMain.resolve(pkg);
            if (!Files.isDirectory(pkgDir)) {
                continue;
            }
            try (Stream<Path> files = Files.walk(pkgDir)) {
                files.filter(p -> p.toString().endsWith(".java"))
                        .forEach(path -> {
                            try {
                                String content = Files.readString(path);
                                String fqn = srcMain.relativize(path).toString()
                                        .replace('\\', '/').replace(".java", "").replace('/', '.');
                                String simpleName = path.getFileName().toString().replace(".java", "");
                                String extendsClass = detectExtends(content);
                                boolean isAbstract = ABSTRACT_PATTERN.matcher(content).find();
                                SourceEntry entry = new SourceEntry(fqn, simpleName, pkg, extendsClass, isAbstract);
                                entries.add(entry);
                                // Last-write wins for simple-name lookup (packages don't share names
                                // in practice; this is consistent with how the guard tests work)
                                bySimpleName.put(simpleName, entry);
                            } catch (IOException ignored) {
                            }
                        });
            }
        }

        // Step 2: find concrete subclasses of AbstractObjectInstance via extends chain
        List<SourceClass> result = new ArrayList<>();
        for (SourceEntry entry : entries) {
            if (entry.isAbstract()) {
                continue;
            }
            if (isSubclassOf(entry, BASE_CLASS_SIMPLE_NAME, bySimpleName)) {
                result.add(new SourceClass(entry.fqn(), entry.packagePath()));
                // Step 2b: reflectively enumerate inner classes of this outer class.
                // Inner/nested classes (e.g. static class FooChild extends AbstractObjectInstance
                // declared inside BarObjectInstance.java) are never emitted by the file-based FQN
                // derivation above. After loading the outer class, getDeclaredClasses() yields all
                // directly-declared nested types; collectInnerObjectClasses() recurses to find all
                // non-abstract AbstractObjectInstance subclasses among them.
                collectInnerObjectClasses(entry.fqn(), entry.packagePath(), result);
            }
        }
        return result;
    }

    /**
     * Resolves the source root, checking the current working directory first
     * then its parent (consistent with {@code ObjectGuardSourceScanner.findSourceRoot()}).
     */
    public static Path findSourceRoot() {
        Path cwd = Path.of(System.getProperty("user.dir"));
        Path srcMain = cwd.resolve("src/main/java");
        if (Files.isDirectory(srcMain)) {
            return srcMain;
        }
        Path parent = cwd.getParent();
        if (parent != null) {
            srcMain = parent.resolve("src/main/java");
            if (Files.isDirectory(srcMain)) {
                return srcMain;
            }
        }
        return null;
    }

    /**
     * Reflectively enumerates all declared inner/nested classes of the given outer
     * class FQN, and adds any that are concrete (non-abstract) subclasses of
     * {@link AbstractObjectInstance} as additional {@link SourceClass} entries.
     *
     * <p>This is the correct approach for inner-class discovery because:
     * <ol>
     *   <li>The source-file walk derives one FQN per {@code .java} file; inner classes
     *       nested inside an outer class file are invisible to that file-path mapping.</li>
     *   <li>The analyzer already loads the outer class via {@code Class.forName} to inspect
     *       fields — so the classpath cost is already paid.</li>
     *   <li>{@code Class.getDeclaredClasses()} returns all directly-declared member types.
     *       Recursing into each yields deeply nested types as well.</li>
     *   <li>{@code Class.getName()} returns the JVM binary name (e.g. {@code Outer$Inner}),
     *       which is exactly what codec lookups and snapshot keys use.</li>
     * </ol>
     *
     * <p>If the outer class cannot be loaded (e.g., not on the test classpath), the method
     * silently returns without adding any inner-class entries — consistent with the field-gap
     * fallback in {@code RewindCoverageAnalyzer.findUncapturedFinalScalarFields}.
     *
     * @param outerFqn    the source-derived FQN of the outer class (dot-separated)
     * @param packagePath the package path of the outer class (propagated to inner entries)
     * @param result      the list to append newly-discovered inner-class entries to
     */
    private static void collectInnerObjectClasses(String outerFqn, String packagePath,
                                                  List<SourceClass> result) {
        Class<?> outerClass;
        try {
            outerClass = Class.forName(outerFqn);
        } catch (ClassNotFoundException e) {
            // Outer class not on classpath; skip inner-class scan for it.
            return;
        }
        collectDeclaredInnerObjectClasses(outerClass, packagePath, result);
    }

    /**
     * Recursively collects concrete {@link AbstractObjectInstance} subclasses declared
     * inside {@code enclosing}, adding them to {@code result}.
     */
    private static void collectDeclaredInnerObjectClasses(Class<?> enclosing, String packagePath,
                                                          List<SourceClass> result) {
        for (Class<?> inner : enclosing.getDeclaredClasses()) {
            // Skip abstract classes — they cannot be spawned directly.
            if (Modifier.isAbstract(inner.getModifiers())) {
                // Still recurse: an abstract inner class may contain concrete grandchildren.
                collectDeclaredInnerObjectClasses(inner, packagePath, result);
                continue;
            }
            // Interfaces, annotations, enums — not spawnable objects.
            if (!inner.isInterface() && !inner.isAnnotation() && !inner.isEnum()
                    && AbstractObjectInstance.class.isAssignableFrom(inner)) {
                // Class.getName() yields the binary name e.g. "com.openggf...Outer$Inner".
                result.add(new SourceClass(inner.getName(), packagePath));
            }
            // Recurse regardless (may hold grandchildren).
            collectDeclaredInnerObjectClasses(inner, packagePath, result);
        }
    }

    private static String detectExtends(String content) {
        Matcher m = EXTENDS_PATTERN.matcher(content);
        return m.find() ? m.group(1) : null;
    }

    private static boolean isSubclassOf(SourceEntry entry, String targetSimpleName,
                                        Map<String, SourceEntry> bySimpleName) {
        String parent = entry.extendsClass();
        int depth = 0;
        while (parent != null && depth < 20) {
            if (parent.equals(targetSimpleName)) {
                return true;
            }
            SourceEntry parentEntry = bySimpleName.get(parent);
            parent = (parentEntry != null) ? parentEntry.extendsClass() : null;
            depth++;
        }
        return false;
    }

    /** A discovered concrete object instance class. */
    public record SourceClass(String fqn, String packagePath) {
    }

    private record SourceEntry(
            String fqn,
            String simpleName,
            String packagePath,
            String extendsClass,
            boolean isAbstract) {
    }
}
