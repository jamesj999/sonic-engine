package com.openggf.level.objects;

import com.openggf.tests.ObjectGuardSourceScanner;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * Guard test: detects object classes whose constructors call {@code services()},
 * then verifies that every {@code new} call site for those classes in non-constructor
 * methods is wrapped with {@code setConstructionContext}/{@code clearConstructionContext}.
 * <p>
 * The managed creation path (ObjectManager → ObjectRegistry factory → constructor) always
 * has CONSTRUCTION_CONTEXT set. But when an object's runtime method (update, spawner, etc.)
 * creates a child object via {@code new}, the context is NOT automatically set.
 * <p>
 * This prevents the runtime error: "services not available — object must be created
 * through ObjectManager".
 */
class TestConstructionContextGuard {

    /** Files that are the managed creation path or handle their own context. */
    private static final Set<String> ALLOWED_CREATORS = Set.of(
            "ObjectManager.java",
            "DefaultPowerUpSpawner.java"
    );

    /**
     * Known violations in non-object files that create objects requiring services.
     * Each entry is "SimpleFileName.java:ClassName" (the created class).
     * These are pre-existing bugs — remove entries as they are fixed.
     */
    private static final Set<String> KNOWN_NON_OBJECT_VIOLATIONS = Set.of(
            // (empty — all known violations fixed)
    );

    /** File name patterns for registry/factory files (invoked under CONSTRUCTION_CONTEXT). */
    private static final Set<String> REGISTRY_FILES = Set.of(
            "Sonic1ObjectRegistry.java",
            "Sonic2ObjectRegistry.java",
            "Sonic3kObjectRegistry.java"
    );

    // Matches: services() call
    private static final Pattern SERVICES_CALL = Pattern.compile("\\bservices\\(\\)");

    // Matches: new FooObjectInstance( or new Foo.Fireball(
    private static final Pattern NEW_OBJECT = Pattern.compile(
            "\\bnew\\s+([A-Z][\\w.]*(?:ObjectInstance|Instance|Fireball))\\s*\\(");

    // Matches: setConstructionContext( or spawn*Child( (which sets context internally)
    private static final Pattern SET_CONTEXT =
            Pattern.compile("\\b(?:setConstructionContext|spawnChild|spawnFreeChild)\\(");
    private static final Pattern CLEAR_CONTEXT =
            Pattern.compile("\\b(?:clearConstructionContext|spawnChild|spawnFreeChild)\\(");

    // Matches constructor declaration: [access] ClassName(
    private static final Pattern CONSTRUCTOR_DECL = Pattern.compile(
            "^\\s*(?:public|protected|private)?\\s*([A-Z]\\w+)\\s*\\(");

    // Matches method declaration: [access] [modifiers] ReturnType methodName(
    private static final Pattern METHOD_DECL = Pattern.compile(
            "^\\s*(?:public|protected|private)?\\s*(?:static\\s+)?(?:final\\s+)?(?:synchronized\\s+)?" +
            "(?:void|int|boolean|[A-Z]\\w*(?:<[^>]*>)?)\\s+(\\w+)\\s*\\(");

    @Test
    void runtimeChildCreation_mustSetConstructionContext() throws IOException {
        Path srcMain = findSourceRoot();
        if (srcMain == null) {
            fail("could not locate src/main/java for guard scan");
        }

        // Phase 1: Find all object classes whose constructors call services()
        Set<String> servicesInConstructor = findClassesWithServicesInConstructor(srcMain);

        // Phase 2: Find unsafe `new` call sites in non-constructor methods
        List<String> violations = new ArrayList<>();
        findUnsafeNewCallSites(srcMain, servicesInConstructor, violations);

        if (!violations.isEmpty()) {
            StringBuilder msg = new StringBuilder();
            msg.append("\n=== MISSING CONSTRUCTION CONTEXT ===\n");
            msg.append("These non-constructor methods create objects whose constructors call services(),\n");
            msg.append("but do not wrap with setConstructionContext()/clearConstructionContext().\n");
            msg.append("The child's services() call will throw at runtime.\n\n");
            msg.append("Fix: wrap the `new` call:\n");
            msg.append("    setConstructionContext(services());\n");
            msg.append("    try { child = new Foo(...); } finally { clearConstructionContext(); }\n\n");
            for (String v : violations) {
                msg.append("  ").append(v).append("\n");
            }
            fail(msg.toString());
        }
    }

    /**
     * Finds simple class names of AbstractObjectInstance subclasses whose constructors
     * contain a call to {@code services()}.
     */
    private Set<String> findClassesWithServicesInConstructor(Path srcRoot) throws IOException {
        Set<String> result = new HashSet<>();

        Files.walkFileTree(srcRoot, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (!file.toString().endsWith(".java")) return FileVisitResult.CONTINUE;

                String content = Files.readString(file);
                if (!extendsObjectBase(content)) return FileVisitResult.CONTINUE;

                String simpleClassName = file.getFileName().toString().replace(".java", "");

                if (constructorCallsServices(content, simpleClassName)) {
                    result.add(simpleClassName);
                    // Also check inner classes
                    Pattern innerClass = Pattern.compile(
                            "(?:public|private|protected)?\\s+(?:static\\s+)?class\\s+(\\w+)");
                    Matcher m = innerClass.matcher(content);
                    while (m.find()) {
                        String inner = m.group(1);
                        if (constructorCallsServices(content, inner)) {
                            result.add(inner);
                        }
                    }
                }

                return FileVisitResult.CONTINUE;
            }
        });

        return result;
    }

    private boolean extendsObjectBase(String content) {
        return content.contains("extends AbstractObjectInstance")
                || content.contains("extends BoxObjectInstance")
                || content.contains("extends ShieldObjectInstance");
    }

    /**
     * Checks if any constructor of the given class calls services().
     */
    private boolean constructorCallsServices(String content, String className) {
        // Match constructor declarations but NOT `new ClassName(` call sites.
        // Require: line-start whitespace + optional access modifier + className(
        Pattern ctorPattern = Pattern.compile(
                "(?m)^\\s*(?:public|protected|private)?\\s*" + Pattern.quote(className) + "\\s*\\(");
        Matcher m = ctorPattern.matcher(content);

        while (m.find()) {
            int braceStart = content.indexOf('{', m.end());
            if (braceStart < 0) continue;

            String body = extractBraceBlock(content, braceStart);
            if (body != null && SERVICES_CALL.matcher(body).find()) {
                return true;
            }
        }
        return false;
    }

    private String extractBraceBlock(String content, int openBrace) {
        int depth = 0;
        for (int i = openBrace; i < content.length(); i++) {
            char c = content.charAt(i);
            if (c == '{') depth++;
            else if (c == '}') {
                depth--;
                if (depth == 0) return content.substring(openBrace, i + 1);
            }
        }
        return null;
    }

    /**
     * Scans for {@code new FooInstance(...)} in non-constructor methods,
     * where Foo's constructor calls services(), and the call site lacks context wrapping.
     * <p>
     * Scans both the object packages (where objects live) AND their parent game packages
     * (where providers/managers may create objects outside the ObjectManager path).
     */
    private void findUnsafeNewCallSites(Path srcRoot, Set<String> servicesInConstructor,
                                         List<String> violations) throws IOException {
        // Object packages: scan object-extending files for child creation
        String[] objectPackages = ObjectGuardSourceScanner.GAME_OBJECT_PACKAGE_PATHS;

        for (String pkg : objectPackages) {
            Path pkgDir = srcRoot.resolve(pkg);
            if (!Files.isDirectory(pkgDir)) continue;

            try (var stream = Files.walk(pkgDir)) {
                stream.filter(p -> p.toString().endsWith(".java"))
                      .forEach(file -> {
                          try {
                              scanFileForUnsafeNew(file, srcRoot, servicesInConstructor, violations);
                          } catch (IOException e) {
                              // skip
                          }
                      });
            }
        }

        // Parent game packages: scan ANY file that creates objects requiring services.
        // This catches providers/managers that bypass ObjectManager.
        String[] gamePackages = {
                "com/openggf/game/sonic1",
                "com/openggf/game/sonic2",
                "com/openggf/game/sonic3k",
                "com/openggf/game"
        };

        for (String pkg : gamePackages) {
            Path pkgDir = srcRoot.resolve(pkg);
            if (!Files.isDirectory(pkgDir)) continue;

            // Only scan direct children (not sub-packages, which are covered above)
            try (var stream = Files.list(pkgDir)) {
                stream.filter(p -> p.toString().endsWith(".java"))
                      .forEach(file -> {
                          try {
                              scanNonObjectFileForUnsafeNew(file, srcRoot, servicesInConstructor, violations);
                          } catch (IOException e) {
                              // skip
                          }
                      });
            }
        }
    }

    private void scanFileForUnsafeNew(Path file, Path srcRoot, Set<String> servicesInConstructor,
                                       List<String> violations) throws IOException {
        String fileName = file.getFileName().toString();

        if (ALLOWED_CREATORS.contains(fileName) || REGISTRY_FILES.contains(fileName)) return;
        if (fileName.startsWith("Test")) return;

        String content = Files.readString(file);
        if (!extendsObjectBase(content)) return;

        String[] lines = content.split("\n");
        String currentClassName = fileName.replace(".java", "");
        boolean inConstructor = false;
        int braceDepth = 0;
        int methodBraceStart = -1;

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            String trimmed = line.trim();

            // Skip comments
            if (trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*")) continue;

            // Detect constructor start
            Matcher ctorMatch = CONSTRUCTOR_DECL.matcher(line);
            if (ctorMatch.find()) {
                String name = ctorMatch.group(1);
                if (name.equals(currentClassName) || isInnerClassName(content, name)) {
                    inConstructor = true;
                    methodBraceStart = -1;
                }
            }

            // Detect method start (non-constructor)
            if (!inConstructor) {
                Matcher methodMatch = METHOD_DECL.matcher(line);
                if (methodMatch.find()) {
                    // It's a method, not a constructor
                    inConstructor = false;
                    methodBraceStart = i;
                }
            }

            // Track brace depth for method/constructor boundary detection
            for (char c : line.toCharArray()) {
                if (c == '{') braceDepth++;
                else if (c == '}') {
                    braceDepth--;
                    if (braceDepth <= 1) {
                        // Exiting a method/constructor
                        inConstructor = false;
                        methodBraceStart = -1;
                    }
                }
            }

            // Skip if we're inside a constructor
            if (inConstructor) continue;

            // Check for `new FooInstance(...)` in non-constructor code
            Matcher newMatch = NEW_OBJECT.matcher(trimmed);
            while (newMatch.find()) {
                String createdClass = newMatch.group(1);
                if (createdClass.contains(".")) {
                    createdClass = createdClass.substring(createdClass.lastIndexOf('.') + 1);
                }

                if (!servicesInConstructor.contains(createdClass)) continue;

                // Check for context wrapping
                if (!hasContextWrapping(lines, i)) {
                    String relativePath = srcRoot.relativize(file).toString().replace('\\', '/');
                    violations.add(String.format("%s:%d — new %s() in runtime method requires construction context",
                            relativePath, i + 1, newMatch.group(1)));
                }
            }
        }
    }

    /**
     * Checks if a name is an inner class declared in the file.
     */
    private boolean isInnerClassName(String content, String name) {
        return content.contains("class " + name + " ") || content.contains("class " + name + "\n");
    }

    /**
     * Checks if the given line is inside a setConstructionContext/clearConstructionContext block.
     */
    private boolean hasContextWrapping(String[] lines, int lineIndex) {
        // Check if `new` is inside a spawnChild() call on the same line
        if (SET_CONTEXT.matcher(lines[lineIndex]).find()) {
            return true;
        }

        // Check if surrounded by setConstructionContext / clearConstructionContext
        int searchStart = Math.max(0, lineIndex - 20);
        for (int i = lineIndex - 1; i >= searchStart; i--) {
            if (SET_CONTEXT.matcher(lines[i]).find()) {
                int searchEnd = Math.min(lines.length, lineIndex + 20);
                for (int j = lineIndex + 1; j < searchEnd; j++) {
                    if (CLEAR_CONTEXT.matcher(lines[j]).find()) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * Scans a non-object file (provider, manager, etc.) for {@code new FooInstance(...)}
     * where Foo's constructor calls services(). These files don't extend AbstractObjectInstance,
     * so they can't use setConstructionContext/spawnChild — any such creation is a bug
     * unless the class has been refactored to not need services().
     */
    private void scanNonObjectFileForUnsafeNew(Path file, Path srcRoot,
                                                Set<String> servicesInConstructor,
                                                List<String> violations) throws IOException {
        String fileName = file.getFileName().toString();
        if (ALLOWED_CREATORS.contains(fileName) || REGISTRY_FILES.contains(fileName)) return;
        if (fileName.startsWith("Test")) return;

        String content = Files.readString(file);

        // Skip files that extend object base — those are handled by scanFileForUnsafeNew
        if (extendsObjectBase(content)) return;

        String[] lines = content.split("\n");
        for (int i = 0; i < lines.length; i++) {
            String trimmed = lines[i].trim();
            if (trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*")) continue;

            Matcher newMatch = NEW_OBJECT.matcher(trimmed);
            while (newMatch.find()) {
                String createdClass = newMatch.group(1);
                if (createdClass.contains(".")) {
                    createdClass = createdClass.substring(createdClass.lastIndexOf('.') + 1);
                }

                if (!servicesInConstructor.contains(createdClass)) continue;

                // Check known violations
                String key = fileName + ":" + createdClass;
                if (KNOWN_NON_OBJECT_VIOLATIONS.contains(key)) continue;

                // Non-object files have no setConstructionContext available,
                // so any creation of a services-requiring object is a violation.
                String relativePath = srcRoot.relativize(file).toString().replace('\\', '/');
                violations.add(String.format(
                        "%s:%d — new %s() created outside object system; class constructor calls services() " +
                        "but creator has no ObjectServices to inject",
                        relativePath, i + 1, newMatch.group(1)));
            }
        }
    }

    private Path findSourceRoot() {
        return ObjectGuardSourceScanner.findSourceRoot();
    }
}


