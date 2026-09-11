package com.openggf.game.rewind.coverage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Detects "level trigger" style global static state -- classes that hold
 * mutable {@code private static} fields consumed by gameplay objects across
 * frames and expose a {@code public static void reset()} for level-load
 * teardown -- that have <b>no</b> corresponding {@link
 * com.openggf.game.rewind.RewindSnapshottable} rewind-registry coverage.
 *
 * <p>This is exactly the bug class behind the MGZ dash-trigger / trigger-platform
 * rewind desync: {@code Sonic3kLevelTriggerManager}'s {@code Level_trigger_array}
 * ratchets forward when a player spindashes a dash trigger, is read by
 * rewind-recreated column/platform objects in their constructors, but was never
 * captured by the rewind registry -- so a backward seek left the array set and a
 * recreated column instantly snapped back to its post-trigger position. The S2
 * analog ({@code ButtonVineTriggerManager}) had the identical gap.
 *
 * <p><b>Detection heuristic (source-text based, mirroring {@link
 * RewindCoverageAnalyzer} / {@code ObjectClasspathScan}):</b> a class (top-level
 * or nested) is a <i>candidate</i> when its own body directly declares {@code
 * public static void reset(...)}. Empirically in this codebase every class with
 * that signature is a global gameplay-state manager (verified by manual audit
 * of the full candidate set at the time this guard was written) -- no additional
 * mutable-field heuristic is needed and would only add false-negative risk from
 * an imperfect regex.
 *
 * <p>A candidate has coverage when either:
 * <ul>
 *   <li>its own source directly {@code implements RewindSnapshottable}, or</li>
 *   <li>some other source file that {@code implements RewindSnapshottable}
 *       references the candidate's simple name followed by {@code .} (i.e. an
 *       adapter delegates capture/restore to it), matching the established
 *       {@code *StaticAdapter} pattern (see {@code OscillationStaticAdapter},
 *       {@code Sonic3kLevelTriggerStaticAdapter}, {@code
 *       ButtonVineTriggerStaticAdapter}).</li>
 * </ul>
 *
 * <p><b>A second candidate source: per-{@code GameModule} registered
 * services.</b> The {@code public static void reset()} heuristic above only
 * catches the older "global static fields" shape (e.g. {@code
 * ButtonVineTriggerManager}). A newer shape has the identical bug potential
 * but sits invisibly outside that regex: a per-{@code GameModule} service
 * object (mutable instance fields + an instance {@code reset()}, one
 * instance per active game module) exposed to gameplay/object code via
 * {@link com.openggf.game.GameModule#getGameService(Class)} /
 * {@code ObjectServices#gameService(Class)} -- e.g. {@code
 * Sonic1ConveyorState}, found manually (not by this guard) when it caused
 * exactly the same rewind-desync shape as the static-field managers above.
 * Every {@code type == X.class} reference inside a {@code getGameService(...)}
 * method body (scanned brace-matched so unrelated {@code type ==} idioms
 * elsewhere in the codebase, e.g. {@code GenericFieldCapturer}'s type
 * dispatch, are never mistaken for a service registration) names a second
 * candidate source; a referenced class is a candidate under this path when
 * its own body directly declares an instance {@code public void reset(...)}
 * (mirroring the static heuristic's shape, just without the {@code static}
 * keyword this registration style implies). Coverage is judged by the exact
 * same rule as the static-field candidates above.
 *
 * <p>Uncovered candidates (either source) are reported with a stable gap key
 * of the form {@code <fully.qualified.Outer[.Inner]>#missingRewindAdapter},
 * consumed by {@code TestStaticStateRewindCoverageGuard} via a committed
 * baseline file, exactly like {@link RewindCoverageAnalyzer}'s object-field
 * gap keys.
 */
public final class StaticStateRewindCoverageAnalyzer {

    /** Root package (relative to {@code src/main/java}) scanned for candidates. */
    private static final String SCAN_ROOT = "com/openggf/game";

    private static final Pattern CLASS_DECL = Pattern.compile("\\bclass\\s+(\\w+)");
    private static final Pattern RESET_SIG = Pattern.compile("public\\s+static\\s+void\\s+reset\\s*\\(");
    private static final Pattern INSTANCE_RESET_SIG = Pattern.compile("public\\s+void\\s+reset\\s*\\(");
    private static final Pattern IMPLEMENTS_SNAPSHOTTABLE =
            Pattern.compile("implements\\s+[^{;]*\\bRewindSnapshottable\\b");
    private static final Pattern GET_GAME_SERVICE_DECL = Pattern.compile("\\bgetGameService\\s*\\(");
    private static final Pattern SERVICE_TYPE_REFERENCE =
            Pattern.compile("\\btype\\s*==\\s*([\\w.]+)\\.class");

    private StaticStateRewindCoverageAnalyzer() {
    }

    public record Gap(String qualifiedClassName) {
        public String key() {
            return qualifiedClassName + "#missingRewindAdapter";
        }
    }

    /**
     * Scans {@code src/main/java} (resolved the same way as {@link
     * ObjectClasspathScan#findSourceRoot()}) for static-state gap classes.
     *
     * @return gap keys, sorted, one per uncovered candidate class
     */
    public static Set<String> analyzeGapKeys() {
        Path srcMain = ObjectClasspathScan.findSourceRoot();
        if (srcMain == null) {
            throw new IllegalStateException(
                    "Static-state rewind coverage scan found no source root. "
                            + "This analyzer must run from a source checkout.");
        }

        List<SourceFile> files;
        try {
            files = readSourceFiles(srcMain);
        } catch (IOException e) {
            throw new RuntimeException("Failed to scan source files for static-state rewind coverage", e);
        }
        if (files.isEmpty()) {
            throw new IllegalStateException(
                    "Static-state rewind coverage scan found no source files under " + SCAN_ROOT);
        }

        // Candidates: classes (possibly nested) whose own body declares reset().
        List<QualifiedClass> candidates = new ArrayList<>();
        for (SourceFile file : files) {
            for (String simpleOrNestedName : findClassesWithOwnStaticReset(file.content())) {
                candidates.add(new QualifiedClass(file.packageName() + "." + simpleOrNestedName, lastSegment(simpleOrNestedName)));
            }
        }
        // Second candidate source: per-GameModule registered services (see class javadoc).
        for (QualifiedClass serviceCandidate : resolveGameServiceCandidates(files)) {
            if (candidates.stream().noneMatch(c -> c.qualifiedName().equals(serviceCandidate.qualifiedName()))) {
                candidates.add(serviceCandidate);
            }
        }

        // Coverage index: simple names referenced (as "Name.") from any file
        // that implements RewindSnapshottable, plus classes covering themselves.
        Set<String> coveredSimpleNames = new LinkedHashSet<>();
        for (SourceFile file : files) {
            if (!IMPLEMENTS_SNAPSHOTTABLE.matcher(file.content()).find()) {
                continue;
            }
            for (QualifiedClass candidate : candidates) {
                if (referencesAsStaticCall(file.content(), candidate.simpleName())) {
                    coveredSimpleNames.add(candidate.qualifiedName());
                }
            }
            // A candidate class that implements RewindSnapshottable directly
            // covers itself even with no external adapter reference.
            for (String selfCoveredName : findClassesDeclaredIn(file.content(), file.packageName())) {
                coveredSimpleNames.add(selfCoveredName);
            }
        }

        Set<String> gapKeys = new TreeSet<>();
        for (QualifiedClass candidate : candidates) {
            if (!coveredSimpleNames.contains(candidate.qualifiedName())) {
                gapKeys.add(new Gap(candidate.qualifiedName()).key());
            }
        }
        return gapKeys;
    }

    private static String lastSegment(String dotted) {
        int idx = dotted.lastIndexOf('.');
        return idx < 0 ? dotted : dotted.substring(idx + 1);
    }

    private static boolean referencesAsStaticCall(String content, String simpleName) {
        return content.contains(simpleName + ".");
    }

    /**
     * Returns qualified names of classes declared in {@code content} that
     * themselves implement {@code RewindSnapshottable} directly (self-covering).
     */
    private static List<String> findClassesDeclaredIn(String content, String packageName) {
        List<String> result = new ArrayList<>();
        if (!IMPLEMENTS_SNAPSHOTTABLE.matcher(content).find()) {
            return result;
        }
        // Attribute to the nearest enclosing class at the position of the match.
        Matcher implMatcher = IMPLEMENTS_SNAPSHOTTABLE.matcher(content);
        while (implMatcher.find()) {
            String owner = classStackAt(content, implMatcher.start()).peek();
            if (owner != null) {
                result.add(packageName + "." + owner);
            }
        }
        return result;
    }

    private static List<SourceFile> readSourceFiles(Path srcMain) throws IOException {
        Path root = srcMain.resolve(SCAN_ROOT);
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        List<SourceFile> result = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path path : (Iterable<Path>) walk.filter(p -> p.toString().endsWith(".java"))::iterator) {
                String content = Files.readString(path);
                String relative = srcMain.relativize(path).toString().replace('\\', '/');
                String pkg = relative.substring(0, relative.lastIndexOf('/')).replace('/', '.');
                String fileName = path.getFileName().toString();
                String fileSimpleName = fileName.substring(0, fileName.length() - ".java".length());
                result.add(new SourceFile(pkg, content, fileSimpleName));
            }
        }
        return result;
    }

    /**
     * Returns the simple (possibly dotted "Outer.Inner") names of classes in
     * {@code content} whose own directly-declared body contains {@code public
     * static void reset(...)}. Attribution uses a brace-depth class-name stack
     * so a nested class's reset() is attributed to the nested class, not its
     * enclosing outer class.
     */
    private static List<String> findClassesWithOwnStaticReset(String content) {
        return findClassesWithOwnReset(content, RESET_SIG);
    }

    /**
     * Same attribution as {@link #findClassesWithOwnStaticReset}, but for an
     * arbitrary {@code reset(...)}-shaped signature pattern -- used to detect
     * per-{@code GameModule} service candidates, whose {@code reset()} is an
     * instance method (see {@link #INSTANCE_RESET_SIG}), not a static one.
     */
    private static List<String> findClassesWithOwnReset(String content, Pattern resetPattern) {
        Set<String> found = new LinkedHashSet<>();
        Matcher resetMatcher = resetPattern.matcher(content);
        while (resetMatcher.find()) {
            Deque<String> stack = classStackAt(content, resetMatcher.start());
            String owner = stack.peek();
            if (owner != null) {
                found.add(dottedPath(stack));
            }
        }
        return new ArrayList<>(found);
    }

    /**
     * Extracts every {@code type == X.class} reference from inside {@code
     * getGameService(...)} method bodies in {@code content} (brace-matched,
     * so the identical {@code type ==} idiom used by unrelated dispatch code
     * elsewhere -- e.g. {@code GenericFieldCapturer}'s reflective type
     * switch -- is never mistaken for a service registration). {@code X} may
     * be a bare simple name (resolved against the file-name index in {@link
     * #resolveGameServiceCandidates}) or an inline fully-qualified reference.
     */
    private static List<String> findGameServiceTypeReferences(String content) {
        List<String> refs = new ArrayList<>();
        Matcher declMatcher = GET_GAME_SERVICE_DECL.matcher(content);
        while (declMatcher.find()) {
            int braceStart = content.indexOf('{', declMatcher.end());
            if (braceStart < 0) {
                continue;
            }
            int depth = 0;
            int end = -1;
            for (int i = braceStart; i < content.length(); i++) {
                char c = content.charAt(i);
                if (c == '{') {
                    depth++;
                } else if (c == '}') {
                    depth--;
                    if (depth == 0) {
                        end = i;
                        break;
                    }
                }
            }
            if (end < 0) {
                continue; // unterminated method body (shouldn't happen in valid source); skip
            }
            String body = content.substring(braceStart + 1, end);
            Matcher typeMatcher = SERVICE_TYPE_REFERENCE.matcher(body);
            while (typeMatcher.find()) {
                refs.add(typeMatcher.group(1));
            }
        }
        return refs;
    }

    /**
     * Resolves every {@code getGameService(...)}-registered type across
     * {@code files} to a {@link QualifiedClass} candidate, keeping only
     * those whose own declaring file directly declares an instance {@code
     * reset(...)} (the per-{@code GameModule} service analog of {@link
     * #findClassesWithOwnStaticReset}). Fully-qualified references (contain
     * a {@code .}) resolve directly; bare simple names resolve via each
     * file's own top-level class name (package + file name, the Java
     * convention every file in this codebase follows), preferring a match in
     * the same package as the referencing file when a simple name is
     * ambiguous, and silently skipping references this scan cannot resolve
     * (e.g. a type outside {@link #SCAN_ROOT}).
     */
    private static List<QualifiedClass> resolveGameServiceCandidates(List<SourceFile> files) {
        Map<String, List<SourceFile>> byTopLevelSimpleName = new LinkedHashMap<>();
        Map<String, SourceFile> byFqn = new LinkedHashMap<>();
        for (SourceFile file : files) {
            byTopLevelSimpleName.computeIfAbsent(file.fileSimpleName(), k -> new ArrayList<>()).add(file);
            byFqn.put(file.packageName() + "." + file.fileSimpleName(), file);
        }

        List<QualifiedClass> result = new ArrayList<>();
        for (SourceFile referencingFile : files) {
            for (String ref : findGameServiceTypeReferences(referencingFile.content())) {
                SourceFile owner;
                String simpleName;
                if (ref.contains(".")) {
                    owner = byFqn.get(ref);
                    simpleName = lastSegment(ref);
                } else {
                    List<SourceFile> matches = byTopLevelSimpleName.get(ref);
                    owner = pickOwner(matches, referencingFile.packageName());
                    simpleName = ref;
                }
                if (owner == null) {
                    continue; // outside this scan's source root, or genuinely ambiguous
                }
                boolean hasOwnReset = findClassesWithOwnReset(owner.content(), RESET_SIG).contains(simpleName)
                        || findClassesWithOwnReset(owner.content(), INSTANCE_RESET_SIG).contains(simpleName);
                if (hasOwnReset) {
                    result.add(new QualifiedClass(owner.packageName() + "." + simpleName, simpleName));
                }
            }
        }
        return result;
    }

    private static SourceFile pickOwner(List<SourceFile> matches, String referencingPackage) {
        if (matches == null || matches.isEmpty()) {
            return null;
        }
        if (matches.size() == 1) {
            return matches.get(0);
        }
        for (SourceFile candidate : matches) {
            if (candidate.packageName().equals(referencingPackage)) {
                return candidate;
            }
        }
        return null; // ambiguous across packages with no same-package tie-break; skip rather than guess
    }

    private static String dottedPath(Deque<String> stack) {
        List<String> parts = new ArrayList<>(stack);
        java.util.Collections.reverse(parts);
        return String.join(".", parts);
    }

    /**
     * Walks {@code content} from the start up to (not including) {@code
     * uptoIndex} (or the whole file when {@code uptoIndex < 0}), maintaining a
     * brace-depth stack of enclosing class names, and returns the stack as it
     * stands at that position. The top of the stack is the innermost class
     * enclosing {@code uptoIndex}.
     */
    /** Sentinel for a brace depth opened before any class was seen (ArrayDeque rejects null). */
    private static final String NO_CLASS = "";

    private static Deque<String> classStackAt(String content, int uptoIndex) {
        Deque<String> stack = new ArrayDeque<>();
        int limit = uptoIndex < 0 ? content.length() : uptoIndex;
        Matcher classDecl = CLASS_DECL.matcher(content);
        String pendingClass = null;
        int i = 0;
        while (i < limit) {
            char c = content.charAt(i);
            if (c == '{') {
                stack.push(pendingClass != null ? pendingClass : (stack.isEmpty() ? NO_CLASS : stack.peek()));
                pendingClass = null;
                i++;
                continue;
            }
            if (c == '}') {
                if (!stack.isEmpty()) {
                    stack.pop();
                }
                i++;
                continue;
            }
            classDecl.region(i, limit);
            if (classDecl.lookingAt()) {
                pendingClass = classDecl.group(1);
                i = classDecl.end();
                continue;
            }
            i++;
        }
        // Drop placeholders for braces seen before any class was opened.
        Deque<String> cleaned = new ArrayDeque<>();
        for (String s : stack) {
            if (!NO_CLASS.equals(s)) {
                cleaned.addLast(s);
            }
        }
        return cleaned;
    }

    private record SourceFile(String packageName, String content, String fileSimpleName) {
    }

    private record QualifiedClass(String qualifiedName, String simpleName) {
    }
}
