package com.openggf.tests;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Guards against calling {@code services()} before {@code ObjectServices}
 * have been injected.
 * <p>
 * {@code services()} depends on injection which happens AFTER construction
 * when using {@code addDynamicObject()} or {@code spawnDynamicObject()}.
 * <p>
 * Four guards:
 * <ol>
 *   <li>Detect {@code spawnDynamicObject(new X(...))} call sites.</li>
 *   <li>Hard-fail if any object constructor calls {@code services()}.</li>
 *   <li>Detect {@code addDynamicObject(new X(...))} where X's constructor
 *       calls {@code services()}.</li>
 *   <li>Detect method calls on freshly constructed objects before
 *       registration, where the method calls {@code services()}.</li>
 * </ol>
 *
 * @see com.openggf.level.objects.AbstractObjectInstance#spawnDynamicObject
 * @see com.openggf.level.objects.AbstractObjectInstance#spawnChild
 */
public class TestNoServicesInObjectConstructors {

    /** Packages containing object instance classes to scan. */
    private static final String[] OBJECT_PACKAGES = ObjectGuardSourceScanner.OBJECT_PACKAGE_PATHS;

    /**
     * Objects whose constructors do NOT call services(), so
     * {@code spawnDynamicObject(new X(...))} is safe for them.
     * <p>
     * If a class is added here, add a comment explaining why its constructor
     * is guaranteed not to call services().
     */
    private static final Set<String> SAFE_FOR_SPAWN_DYNAMIC = Set.of(
            // Constructor only stores fields, no services() call
            "SongFadeTransitionInstance",
            "S3kSignpostInstance",
            "S3kSignpostStubChild",
            "S3kSignpostSparkleChild",
            "S3kResultsScreenObjectInstance",
            "Sonic3kSSEntryFlashObjectInstance",
            "AizTreeRevealControlObjectInstance",
            // Constructor only initializes scalar camera-boundary state; the raw
            // spawn preserves AllocateObjectAfterCurrent slot ordering.
            "AizAct2CameraResizeController"
    );

    private static final Set<String> LEGACY_CONSTRUCTOR_RAW_CHILD_REGISTRATION = Set.of(
            // Phase 2 cleanup: boss scrap setup still uses spawnDynamicObject during construction.
            "Sonic1ScrapEggmanInstance",
            // Phase 2 cleanup: bridge zone config path still reaches raw child registration.
            "CollapsingBridgeObjectInstance"
    );

    /**
     * Detects {@code spawnDynamicObject(new X(...))} patterns in source code.
     * These are dangerous when the constructed object calls services() in its
     * constructor. Flags any that aren't in the known-safe set.
     */
    @Test
    public void spawnDynamicObject_shouldNotConstructInlineUnlessConstructorIsSafe() throws IOException {
        assertEquals(Object.class,
                com.openggf.level.objects.TestNoServicesInObjectConstructors.class.getSuperclass(),
                "legacy FQN must remain loadable without inheriting and re-running this suite");
        Path srcMain = Path.of("src/main/java");
        if (!Files.isDirectory(srcMain)) {
            return;
        }

        // Pattern: spawnDynamicObject(new SomeClassName(
        Pattern inlineNew = Pattern.compile(
                "spawnDynamicObject\\(\\s*new\\s+(\\w+)\\s*\\(");

        List<String> violations = new ArrayList<>();

        for (String pkg : OBJECT_PACKAGES) {
            Path pkgDir = srcMain.resolve(pkg);
            if (!Files.isDirectory(pkgDir)) continue;

            try (Stream<Path> files = Files.walk(pkgDir)) {
                files.filter(p -> p.toString().endsWith(".java"))
                        .forEach(path -> {
                            try {
                                String content = Files.readString(path);
                                Matcher m = inlineNew.matcher(content);
                                while (m.find()) {
                                    String className = m.group(1);
                                    if (!SAFE_FOR_SPAWN_DYNAMIC.contains(className)) {
                                        String fileName = path.getFileName().toString();
                                        violations.add(fileName + ": spawnDynamicObject(new "
                                                + className + "(...)) â€” use spawnChild()/spawnFreeChild() instead, "
                                                + "or add to SAFE_FOR_SPAWN_DYNAMIC if constructor "
                                                + "does not call services()");
                                    }
                                }
                            } catch (IOException ignored) {
                            }
                        });
            }
        }

        if (!violations.isEmpty()) {
            fail("Unsafe spawnDynamicObject(new ...) patterns found.\n"
                    + "If the constructor calls services(), this will throw "
                    + "IllegalStateException at runtime.\n"
                    + "Use spawnChild(() -> new X(...)) or spawnFreeChild(() -> new X(...)) instead:\n\n  "
                    + String.join("\n  ", violations));
        }
    }

    /**
     * Hard-fails if any object constructor calls {@code services()}.
     * <p>
     * Constructors run BEFORE {@code addDynamicObject()} or
     * {@code spawnDynamicObject()} can inject {@code ObjectServices}.
     * The only safe patterns are {@code spawnChild()}, {@code spawnFreeChild()}, or
     * {@code setConstructionContext()}, but both are easy to forget at
     * call sites. The simplest universal rule: defer {@code services()}
     * to lazy init or the first {@code update()} call.
     */
    @Test
    public void constructors_mustNotCallServices() throws IOException {
        Path srcMain = Path.of("src/main/java");
        if (!Files.isDirectory(srcMain)) {
            return;
        }

        List<String> violations = new ArrayList<>();

        for (String pkg : OBJECT_PACKAGES) {
            Path pkgDir = srcMain.resolve(pkg);
            if (!Files.isDirectory(pkgDir)) continue;

            try (Stream<Path> files = Files.walk(pkgDir)) {
                files.filter(p -> p.toString().endsWith(".java"))
                        .forEach(path -> {
                            try {
                                String content = Files.readString(path);
                                String fileName = path.getFileName().toString();
                                String className = fileName.replace(".java", "");

                                findServicesInConstructors(content, className, fileName, violations);
                            } catch (IOException ignored) {
                            }
                        });
            }
        }

        if (!violations.isEmpty()) {
            fail("Object constructors must not call services(). "
                    + "Defer to lazy init (ensureInitialized pattern) or first update() call.\n\n  "
                    + String.join("\n  ", violations));
        }
    }

    /**
     * Hard-fails if an object constructor calls an overridable method whose
     * implementation reaches {@code services()}, directly or through helper
     * methods. A direct constructor-body scan misses paths such as:
     * <pre>
     *   AbstractBossInstance() -> initializeBossState()
     *   Sonic2EHZBossInstance.initializeBossState() -> spawnChildComponents()
     *   spawnChildComponents() -> services()
     * </pre>
     */
    @Test
    public void objectsWhoseConstructorsReachServices_mustUseManagedCreationContext() throws IOException {
        Path srcMain = Path.of("src/main/java");
        if (!Files.isDirectory(srcMain)) {
            return;
        }

        Map<String, ClassSource> classes = loadObjectClassSources(srcMain);
        Set<String> requiringContext = findClassesWhoseConstructorsMayReachServices(classes);
        List<String> violations = new ArrayList<>();
        findUnsafeNewCallSitesForContextRequiredClasses(srcMain, requiringContext, violations);

        if (!violations.isEmpty()) {
            fail("Object classes whose constructor chain can reach services() must be created "
                    + "through ObjectManager construction context. Direct new X(...) leaves "
                    + "ObjectServices unavailable during construction.\n\n  "
                    + String.join("\n  ", violations));
        }
    }

    @Test
    public void constructors_mustNotRegisterChildObjectsThroughRawObjectManagerCalls() throws IOException {
        Path srcMain = Path.of("src/main/java");
        if (!Files.isDirectory(srcMain)) {
            return;
        }

        Map<String, ClassSource> classes = loadObjectClassSources(srcMain);
        List<String> violations = new ArrayList<>();

        for (ClassSource source : classes.values()) {
            if (LEGACY_CONSTRUCTOR_RAW_CHILD_REGISTRATION.contains(source.className())) {
                continue;
            }
            Set<String> rawRegistrationMethods = findMethodsCallingRawObjectRegistration(
                    source.content(), source.className());
            for (ConstructorCall call : findConstructorCalls(source)) {
                if ("addDynamicObject".equals(call.methodName())
                        || "spawnDynamicObject".equals(call.methodName())
                        || rawRegistrationMethods.contains(call.methodName())) {
                    violations.add(source.fileName() + ": " + source.className()
                            + " constructor reaches " + call.methodName()
                            + "(), which registers children before the parent is fully manager-owned");
                }
            }
        }

        if (!violations.isEmpty()) {
            fail("Object constructors must not register child objects through raw ObjectManager paths. "
                    + "Defer child creation until after parent registration and use spawnChild()/spawnFreeChild() "
                    + "so construction context and child lifecycle ownership are preserved.\n\n  "
                    + String.join("\n  ", violations));
        }
    }

    /**
     * Scans ALL source for {@code addDynamicObject(new X(...))} where X's
     * constructor calls {@code services()}. This catches call sites outside
     * the object packages (e.g. event managers, level controllers).
     * <p>
     * {@code addDynamicObject()} injects services AFTER construction, so
     * if the constructor calls {@code services()}, it will crash.
     */
    @Test
    public void addDynamicObject_shouldNotConstructObjectThatNeedsServicesInConstructor() throws IOException {
        Path srcMain = Path.of("src/main/java");
        if (!Files.isDirectory(srcMain)) {
            return;
        }

        // Step 1: Build set of class names whose constructors call services()
        Set<String> classesCallingServicesInCtor = new HashSet<>();
        for (String pkg : OBJECT_PACKAGES) {
            Path pkgDir = srcMain.resolve(pkg);
            if (!Files.isDirectory(pkgDir)) continue;

            try (Stream<Path> files = Files.walk(pkgDir)) {
                files.filter(p -> p.toString().endsWith(".java"))
                        .forEach(path -> {
                            try {
                                String content = Files.readString(path);
                                String fileName = path.getFileName().toString();
                                String className = fileName.replace(".java", "");

                                List<String> dummy = new ArrayList<>();
                                findServicesInConstructors(content, className, fileName, dummy);
                                if (!dummy.isEmpty()) {
                                    classesCallingServicesInCtor.add(className);
                                }
                            } catch (IOException ignored) {
                            }
                        });
            }
        }

        // Step 2: Scan ALL source for addDynamicObject(new X(...)) where X
        // calls services() in its constructor
        Pattern inlineNew = Pattern.compile(
                "addDynamicObject\\(\\s*new\\s+(\\w+)\\s*\\(");

        List<String> violations = new ArrayList<>();

        try (Stream<Path> allFiles = Files.walk(srcMain)) {
            allFiles.filter(p -> p.toString().endsWith(".java"))
                    .forEach(path -> {
                        try {
                            String content = Files.readString(path);
                            Matcher m = inlineNew.matcher(content);
                            while (m.find()) {
                                String className = m.group(1);
                                if (classesCallingServicesInCtor.contains(className)) {
                                    String fileName = path.getFileName().toString();
                                    violations.add(fileName + ": addDynamicObject(new "
                                            + className + "(...)) â€” " + className
                                            + " calls services() in constructor. "
                                            + "Remove services() from the constructor "
                                            + "(use lazy init) or use spawnChild()/spawnFreeChild()");
                                }
                            }
                        } catch (IOException ignored) {
                        }
                    });
        }

        if (!violations.isEmpty()) {
            fail("addDynamicObject(new X(...)) where X calls services() in constructor.\n"
                    + "addDynamicObject() injects services AFTER construction, "
                    + "so services() will throw IllegalStateException.\n\n  "
                    + String.join("\n  ", violations));
        }
    }

    /**
     * Detects the pattern where a method is called on a freshly constructed
     * object BEFORE it is passed to {@code addDynamicObject()} or
     * {@code spawnDynamicObject()}, and that method calls {@code services()}.
     * <p>
     * Example of the dangerous pattern:
     * <pre>
     *   X obj = new X(...);
     *   obj.initialize();              // CRASH â€” services not injected yet
     *   manager.addDynamicObject(obj);
     * </pre>
     * <p>
     * Field assignments ({@code obj.field = value}) are safe and ignored.
     */
    @Test
    public void methodCallsBeforeRegistration_mustNotCallServices() throws IOException {
        Path srcMain = Path.of("src/main/java");
        if (!Files.isDirectory(srcMain)) {
            return;
        }

        // Step 1: Build a map of className -> set of methods that call services()
        Map<String, Set<String>> methodsCallingServices = new HashMap<>();
        for (String pkg : OBJECT_PACKAGES) {
            Path pkgDir = srcMain.resolve(pkg);
            if (!Files.isDirectory(pkgDir)) continue;

            try (Stream<Path> files = Files.walk(pkgDir)) {
                files.filter(p -> p.toString().endsWith(".java"))
                        .forEach(path -> {
                            try {
                                String content = Files.readString(path);
                                String className = path.getFileName().toString().replace(".java", "");
                                Set<String> methods = findMethodsCallingServices(content, className);
                                if (!methods.isEmpty()) {
                                    methodsCallingServices.put(className, methods);
                                }
                            } catch (IOException ignored) {
                            }
                        });
            }
        }

        // Step 2: Scan ALL source for the pattern:
        //   varName = new ClassName(...);
        //   varName.method(...);
        //   ... addDynamicObject(varName) or spawnDynamicObject(varName)
        //
        // We look for method calls (not field assignments) on a variable
        // between its construction and registration.

        // Match: ClassName varName = new ClassName(...)
        // or:    var varName = new ClassName(...)
        Pattern construction = Pattern.compile(
                "(?:\\w+(?:<[^>]+>)?|var)\\s+(\\w+)\\s*=\\s*new\\s+(\\w+)\\s*\\(");
        // Match: varName.methodName(
        Pattern methodCall = Pattern.compile(
                "(\\w+)\\.(\\w+)\\s*\\(");
        // Match: addDynamicObject(varName) or spawnDynamicObject(varName)
        Pattern registration = Pattern.compile(
                "(?:addDynamicObject|spawnDynamicObject)\\s*\\(\\s*(\\w+)\\s*\\)");

        List<String> violations = new ArrayList<>();

        try (Stream<Path> allFiles = Files.walk(srcMain)) {
            allFiles.filter(p -> p.toString().endsWith(".java"))
                    .forEach(path -> {
                        try {
                            String content = Files.readString(path);
                            String fileName = path.getFileName().toString();
                            String[] lines = content.split("\n");

                            // Track constructed-but-not-yet-registered variables
                            // Key: varName, Value: className
                            Map<String, String> pending = new HashMap<>();

                            for (int i = 0; i < lines.length; i++) {
                                String line = lines[i];

                                // Check for construction
                                Matcher ctorMatch = construction.matcher(line);
                                while (ctorMatch.find()) {
                                    pending.put(ctorMatch.group(1), ctorMatch.group(2));
                                }

                                // Check for registration â€” removes from pending
                                Matcher regMatch = registration.matcher(line);
                                while (regMatch.find()) {
                                    pending.remove(regMatch.group(1));
                                }

                                // Check for method calls on pending objects
                                Matcher callMatch = methodCall.matcher(line);
                                while (callMatch.find()) {
                                    String varName = callMatch.group(1);
                                    String method = callMatch.group(2);
                                    String className = pending.get(varName);
                                    if (className != null) {
                                        Set<String> dangerous = methodsCallingServices.get(className);
                                        if (dangerous != null && dangerous.contains(method)) {
                                            violations.add(fileName + ":" + (i + 1)
                                                    + ": " + varName + "." + method
                                                    + "() called before addDynamicObject â€” "
                                                    + method + "() calls services() in "
                                                    + className);
                                        }
                                    }
                                }
                            }
                        } catch (IOException ignored) {
                        }
                    });
        }

        if (!violations.isEmpty()) {
            fail("Method calling services() invoked on object before registration.\n"
                    + "addDynamicObject/spawnDynamicObject injects services AFTER "
                    + "the call, so services() will throw IllegalStateException.\n"
                    + "Move the method call after registration, or defer to "
                    + "ensureInitialized() in update().\n\n  "
                    + String.join("\n  ", violations));
        }
    }

    /**
     * Returns the set of non-constructor method names in a class that call
     * {@code services()}, either directly or transitively through other
     * methods in the same class.
     */
    private static Set<String> findMethodsCallingServices(String content, String className) {
        // Parse all methods: name -> body
        Map<String, String> methodBodies = new HashMap<>();
        Pattern methodDecl = Pattern.compile(
                "(?:public|protected|private|)\\s+"
                        + "(?:static\\s+)?(?:final\\s+)?(?:synchronized\\s+)?"
                        + "\\S+\\s+(\\w+)\\s*\\([^)]*\\)\\s*"
                        + "(?:throws\\s+[^{]+)?\\{");

        Matcher m = methodDecl.matcher(content);
        while (m.find()) {
            String methodName = m.group(1);
            if (methodName.equals(className)) continue; // skip constructors

            int start = m.end();
            int braceDepth = 1;
            int end = start;
            while (end < content.length() && braceDepth > 0) {
                char c = content.charAt(end);
                if (c == '{') braceDepth++;
                else if (c == '}') braceDepth--;
                end++;
            }
            methodBodies.put(methodName, content.substring(start, end));
        }

        // Seed: methods that directly call services()
        Pattern servicesCall = Pattern.compile("(?<![\\w])(?:\\w+\\.)?services\\(\\)");
        Set<String> callers = new HashSet<>();
        for (var entry : methodBodies.entrySet()) {
            if (servicesCall.matcher(entry.getValue()).find()) {
                callers.add(entry.getKey());
            }
        }

        // Transitive closure: methods that call methods already in the set
        boolean changed = true;
        while (changed) {
            changed = false;
            for (var entry : methodBodies.entrySet()) {
                if (callers.contains(entry.getKey())) continue;
                for (String caller : callers) {
                    // Check if this method's body calls a known services-calling method
                    if (Pattern.compile("(?<![.\\w])" + Pattern.quote(caller) + "\\s*\\(")
                            .matcher(entry.getValue()).find()) {
                        callers.add(entry.getKey());
                        changed = true;
                        break;
                    }
                }
            }
        }

        return callers;
    }

    private static Set<String> findMethodsCallingRawObjectRegistration(String content, String className) {
        Map<String, String> methodBodies = new HashMap<>();
        Pattern methodDecl = Pattern.compile(
                "(?:public|protected|private|)\\s+"
                        + "(?:static\\s+)?(?:final\\s+)?(?:synchronized\\s+)?"
                        + "\\S+\\s+(\\w+)\\s*\\([^)]*\\)\\s*"
                        + "(?:throws\\s+[^{]+)?\\{");

        Matcher m = methodDecl.matcher(content);
        while (m.find()) {
            String methodName = m.group(1);
            if (methodName.equals(className)) continue;

            int start = m.end();
            int braceDepth = 1;
            int end = start;
            while (end < content.length() && braceDepth > 0) {
                char c = content.charAt(end);
                if (c == '{') braceDepth++;
                else if (c == '}') braceDepth--;
                end++;
            }
            methodBodies.put(methodName, content.substring(start, end));
        }

        Pattern rawRegistration = Pattern.compile(
                "(?<![\\w])(?:\\w+\\.)?(?:addDynamicObject|spawnDynamicObject)\\s*\\(");
        Set<String> callers = new HashSet<>();
        for (var entry : methodBodies.entrySet()) {
            if (rawRegistration.matcher(entry.getValue()).find()) {
                callers.add(entry.getKey());
            }
        }

        boolean changed = true;
        while (changed) {
            changed = false;
            for (var entry : methodBodies.entrySet()) {
                if (callers.contains(entry.getKey())) continue;
                for (String caller : callers) {
                    if (Pattern.compile("(?<![.\\w])" + Pattern.quote(caller) + "\\s*\\(")
                            .matcher(entry.getValue()).find()) {
                        callers.add(entry.getKey());
                        changed = true;
                        break;
                    }
                }
            }
        }

        return callers;
    }

    private static Map<String, ClassSource> loadObjectClassSources(Path srcMain) throws IOException {
        Map<String, ClassSource> classes = new HashMap<>();

        for (String pkg : OBJECT_PACKAGES) {
            Path pkgDir = srcMain.resolve(pkg);
            if (!Files.isDirectory(pkgDir)) continue;

            try (Stream<Path> files = Files.walk(pkgDir)) {
                files.filter(p -> p.toString().endsWith(".java"))
                        .forEach(path -> {
                            try {
                                String content = Files.readString(path);
                                String fileName = path.getFileName().toString();
                                String className = fileName.replace(".java", "");
                                classes.put(className, new ClassSource(
                                        fileName,
                                        className,
                                        findExtendsClass(content),
                                        content));
                            } catch (IOException ignored) {
                            }
                        });
            }
        }

        return classes;
    }

    private static Set<String> findClassesWhoseConstructorsMayReachServices(Map<String, ClassSource> classes) {
        Set<String> result = new HashSet<>();

        for (ClassSource source : classes.values()) {
            for (ClassSource candidate : subclassesIncludingSelf(classes, source.className())) {
                Set<String> dangerousMethods = findMethodsCallingServices(candidate.content(), candidate.className());
                for (ConstructorCall call : findConstructorCalls(source)) {
                    if (call.methodName().equals("services") || dangerousMethods.contains(call.methodName())) {
                        result.add(candidate.className());
                    }
                }
            }
        }

        return result;
    }

    private static void findUnsafeNewCallSitesForContextRequiredClasses(Path srcMain,
                                                                        Set<String> requiringContext,
                                                                        List<String> violations) throws IOException {
        if (requiringContext.isEmpty()) {
            return;
        }

        Pattern directNew = Pattern.compile("\\bnew\\s+(\\w+)\\s*\\(");

        try (Stream<Path> allFiles = Files.walk(srcMain)) {
            allFiles.filter(path -> path.toString().endsWith(".java"))
                    .forEach(path -> {
                        String fileName = path.getFileName().toString();
                        if (fileName.startsWith("Test")
                                || fileName.equals("ObjectManager.java")
                                || fileName.equals("DefaultPowerUpSpawner.java")
                                || fileName.endsWith("ObjectRegistry.java")) {
                            return;
                        }

                        try {
                            String[] lines = Files.readString(path).split("\n");
                            for (int i = 0; i < lines.length; i++) {
                                String trimmed = lines[i].trim();
                                if (trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*")) {
                                    continue;
                                }

                                Matcher matcher = directNew.matcher(trimmed);
                                while (matcher.find()) {
                                    String className = matcher.group(1);
                                    if (!requiringContext.contains(className)) {
                                        continue;
                                    }
                                    if (isContextWrapped(lines, i)) {
                                        continue;
                                    }
                                    String relative = srcMain.relativize(path).toString().replace('\\', '/');
                                    violations.add(relative + ":" + (i + 1)
                                            + " - new " + className
                                            + "(...) without ObjectManager construction context");
                                }
                            }
                        } catch (IOException ignored) {
                        }
                    });
        }
    }

    /**
     * Whether a {@code new X(...)} at {@code lineIndex} is deferred into a factory
     * (a {@code Supplier} lambda or a factory method referenced with {@code this::})
     * that a construction-context wrapper later invokes.
     *
     * <p>This is the codebase's normal shape for the wrappers that take a supplier:
     * the {@code new} is written before the {@code spawnChild(...)} /
     * {@code spawnFreeChild(...)} call that runs it, so a backwards-only scan for the
     * wrapper cannot see it. The construction context is established by the wrapper
     * when it invokes the factory, so these sites do get a context — the deferral is
     * exactly what makes them safe.
     */
    private static boolean isDeferredFactoryConstruction(String[] lines, int lineIndex) {
        Pattern wrapper = Pattern.compile(
                "\\b(?:spawnChild|spawnFreeChild|spawnObject|createDynamicObject)\\s*\\(");
        int searchEnd = Math.min(lines.length, lineIndex + 20);

        // Supplier-lambda form: `... = () -> new X(...)` (possibly wrapped over lines),
        // with the wrapper call following within the same window.
        boolean lambda = false;
        for (int i = lineIndex; i >= Math.max(0, lineIndex - 3) && !lambda; i--) {
            lambda = lines[i].contains("->");
        }
        if (lambda) {
            for (int i = lineIndex + 1; i < searchEnd; i++) {
                if (wrapper.matcher(lines[i]).find()) {
                    return true;
                }
            }
        }

        // Factory-method form: `return new X(...)` inside a method that some wrapper
        // call in this file passes as a `this::name` method reference.
        if (!lines[lineIndex].trim().startsWith("return new ")) {
            return false;
        }
        Pattern declaration = Pattern.compile(
                "\\b(?:public|private|protected)\\b[^;{}()]*\\b(\\w+)\\s*\\(");
        for (int i = lineIndex; i >= 0; i--) {
            Matcher declared = declaration.matcher(lines[i]);
            if (!declared.find()) {
                continue;
            }
            Pattern reference = Pattern.compile(
                    "\\b(?:spawnChild|spawnFreeChild|spawnObject|createDynamicObject)\\s*\\("
                            + "\\s*this\\s*::\\s*" + Pattern.quote(declared.group(1)) + "\\b");
            for (String line : lines) {
                if (reference.matcher(line).find()) {
                    return true;
                }
            }
            return false;
        }
        return false;
    }

    private static boolean isContextWrapped(String[] lines, int lineIndex) {
        if (isDeferredFactoryConstruction(lines, lineIndex)) {
            return true;
        }
        if (Pattern.compile("\\b(?:setConstructionContext|spawnChild|spawnFreeChild|spawnObject|createDynamicObject)\\s*\\(")
                .matcher(lines[lineIndex]).find()) {
            return true;
        }
        if (Pattern.compile("\\bObjectConstructionContext\\s*\\.\\s*construct\\s*\\(")
                .matcher(lines[lineIndex]).find()) {
            return true;
        }

        int searchStart = Math.max(0, lineIndex - 20);
        int searchEnd = Math.min(lines.length, lineIndex + 20);
        for (int i = lineIndex - 1; i >= searchStart; i--) {
            if (Pattern.compile("\\b(?:spawnChild|spawnFreeChild|spawnObject|createDynamicObject)\\s*\\(")
                    .matcher(lines[i]).find()) {
                return true;
            }
            if (Pattern.compile("\\bObjectConstructionContext\\s*\\.\\s*construct\\s*\\(")
                    .matcher(lines[i]).find()) {
                return true;
            }
            if (!Pattern.compile("\\bsetConstructionContext\\s*\\(").matcher(lines[i]).find()) {
                continue;
            }
            for (int j = lineIndex + 1; j < searchEnd; j++) {
                if (Pattern.compile("\\bclearConstructionContext\\s*\\(").matcher(lines[j]).find()) {
                    return true;
                }
            }
        }
        return false;
    }

    private static String findExtendsClass(String content) {
        Matcher matcher = Pattern.compile("\\bclass\\s+\\w+\\s+extends\\s+(\\w+)").matcher(content);
        return matcher.find() ? matcher.group(1) : null;
    }

    private static List<ConstructorCall> findConstructorCalls(ClassSource source) {
        List<ConstructorCall> calls = new ArrayList<>();
        Pattern ctorPattern = Pattern.compile(
                "(?:public\\s+|protected\\s+|private\\s+)?" + Pattern.quote(source.className())
                        + "\\s*\\([^)]*\\)\\s*\\{");
        Matcher ctorMatcher = ctorPattern.matcher(source.content());

        while (ctorMatcher.find()) {
            int start = ctorMatcher.end();
            int braceDepth = 1;
            int end = start;
            while (end < source.content().length() && braceDepth > 0) {
                char c = source.content().charAt(end);
                if (c == '{') braceDepth++;
                else if (c == '}') braceDepth--;
                end++;
            }

            String ctorBody = source.content().substring(start, end);
            Matcher callMatcher = Pattern.compile("(?<![.\\w])(\\w+)\\s*\\(").matcher(ctorBody);
            while (callMatcher.find()) {
                String methodName = callMatcher.group(1);
                if (isConstructorCallAllowed(methodName)) {
                    continue;
                }
                calls.add(new ConstructorCall(methodName));
            }
        }

        return calls;
    }

    private static boolean isConstructorCallAllowed(String methodName) {
        return Set.of(
                "super",
                "this",
                "new",
                "if",
                "for",
                "while",
                "switch",
                "catch",
                "return",
                "throw").contains(methodName);
    }

    private static List<ClassSource> subclassesIncludingSelf(Map<String, ClassSource> classes, String baseClassName) {
        List<ClassSource> result = new ArrayList<>();
        for (ClassSource candidate : classes.values()) {
            if (candidate.className().equals(baseClassName) || isSubclassOf(classes, candidate, baseClassName)) {
                result.add(candidate);
            }
        }
        return result;
    }

    private static boolean isSubclassOf(Map<String, ClassSource> classes,
                                        ClassSource candidate,
                                        String baseClassName) {
        String parent = candidate.extendsClass();
        while (parent != null) {
            if (parent.equals(baseClassName)) {
                return true;
            }
            ClassSource parentSource = classes.get(parent);
            parent = parentSource != null ? parentSource.extendsClass() : null;
        }
        return false;
    }

    private record ClassSource(String fileName, String className, String extendsClass, String content) {
    }

    private record ConstructorCall(String methodName) {
    }

    /**
     * Finds {@code services()} calls inside constructors of the given class.
     * Appends human-readable descriptions to the violations list.
     */
    private static void findServicesInConstructors(
            String content, String className, String fileName,
            List<String> violations) {
        Pattern ctorPattern = Pattern.compile(
                "(?:public\\s+|protected\\s+|private\\s+)?" + Pattern.quote(className)
                        + "\\s*\\([^)]*\\)\\s*\\{");
        Matcher ctorMatcher = ctorPattern.matcher(content);

        while (ctorMatcher.find()) {
            int start = ctorMatcher.end();
            int braceDepth = 1;
            int end = start;
            while (end < content.length() && braceDepth > 0) {
                char c = content.charAt(end);
                if (c == '{') braceDepth++;
                else if (c == '}') braceDepth--;
                end++;
            }

            String ctorBody = content.substring(start, end);
            // Match unqualified services() â€” not obj.services()
            if (Pattern.compile("(?<![\\w])(?:\\w+\\.)?services\\(\\)").matcher(ctorBody).find()) {
                violations.add(fileName + ": " + className
                        + " calls services() in constructor");
            }
        }
    }
}
