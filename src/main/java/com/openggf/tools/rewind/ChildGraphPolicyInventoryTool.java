package com.openggf.tools.rewind;

import com.openggf.game.rewind.RewindScanSupport;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

public final class ChildGraphPolicyInventoryTool {
    private static final Pattern PACKAGE_PATTERN =
            Pattern.compile("^\\s*package\\s+([a-zA-Z0-9_.]+)\\s*;", Pattern.MULTILINE);
    private static final Pattern CLASS_PATTERN =
            Pattern.compile("\\b(?:class|record|enum)\\s+([A-Za-z_$][A-Za-z0-9_$]*)");
    private static final Pattern OBJECT_REFERENCE_FIELD_PATTERN = Pattern.compile(
            "(?m)^\\s*(?:private|protected|public)?\\s*(?:static\\s+)?(?:final\\s+)?"
                    + "(?:[A-Za-z0-9_.$]+<[^;=]+>|[A-Za-z0-9_.$]+)\\s+"
                    + "[A-Za-z_$][A-Za-z0-9_$]*\\s*(?:=|;)");
    private static final Pattern CHILD_LIKE_NAME_PATTERN = Pattern.compile(
            "(?i).*(child|children|projectile|fragment|debris|splash|dust|sparkle|smoke|explosion|bubble).*");

    public static void main(String[] args) throws Exception {
        if (args.length == 1 && "--help".equals(args[0])) {
            System.out.println("Usage: ChildGraphPolicyInventoryTool [source-root ...]");
            System.out.println("Reports audit candidates only; it never fails the build.");
            return;
        }

        List<Path> roots = args.length == 0
                ? RewindScanSupport.SOURCE_ROOTS
                : Stream.of(args).map(Path::of).toList();
        List<Finding> findings = scanSourceRoots(roots);
        if (findings.isEmpty()) {
            System.out.println("No child/spawn graph audit candidates found.");
            return;
        }

        System.out.println("Child/spawn graph audit candidates:");
        for (Finding finding : findings) {
            System.out.println(finding.className() + " : " + finding.sourcePath());
            System.out.println("  signals: " + finding.signals());
            System.out.println("  policy prompts: " + finding.policyPrompts());
        }
    }

    public static List<Finding> scanSourceRoots(List<Path> roots) throws IOException {
        List<Finding> findings = new ArrayList<>();
        for (Path root : roots) {
            if (!Files.isDirectory(root)) {
                continue;
            }
            try (Stream<Path> stream = Files.walk(root)) {
                for (Path sourcePath : stream
                        .filter(path -> path.toString().endsWith(".java"))
                        .sorted()
                        .toList()) {
                    findingFor(sourcePath).ifPresent(findings::add);
                }
            }
        }
        findings.sort((a, b) -> a.className().compareTo(b.className()));
        return List.copyOf(findings);
    }

    static Optional<Finding> findingFor(Path sourcePath) throws IOException {
        String rawSource = Files.readString(sourcePath);
        String source = stripComments(rawSource);
        String className = qualifiedPrimaryClassName(source, sourcePath);
        EnumSet<Signal> signals = EnumSet.noneOf(Signal.class);

        if (source.contains(".addDynamicObject(")
                || source.contains(".addDynamicObjectAfterCurrent(")
                || source.contains(".addDynamicObjectAtSlot(")) {
            signals.add(Signal.ADD_DYNAMIC_OBJECT);
        }
        if (source.contains("spawnChild(")
                || source.contains("spawnFreeChild(")
                || source.contains("spawnDynamicObject(")) {
            signals.add(Signal.SPAWN_CHILD_HELPER);
        }
        if (source.contains("childSpawns")) {
            signals.add(Signal.CHILD_SPAWNS_SNAPSHOT);
        }
        if (containsObjectReferenceField(source)) {
            signals.add(Signal.OBJECT_INSTANCE_FIELD);
        }
        if (containsParentChildNaming(source, className)) {
            signals.add(Signal.PARENT_CHILD_NAMING);
        }
        if (hasChildLikeClassName(source, className)) {
            signals.add(Signal.CHILD_LIKE_CLASS_NAME);
        }
        if (source.contains("captureRewindState(")
                || source.contains("restoreRewindState(")
                || source.contains("captureRewindStateValue(")
                || source.contains("restoreRewindStateValue(")) {
            signals.add(Signal.REWIND_OVERRIDE);
        }

        if (signals.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new Finding(
                className,
                sourcePath,
                Set.copyOf(signals),
                Set.copyOf(policyPromptsFor(signals))));
    }

    private static boolean containsObjectReferenceField(String source) {
        Matcher matcher = OBJECT_REFERENCE_FIELD_PATTERN.matcher(source);
        while (matcher.find()) {
            String line = matcher.group();
            if (line.startsWith("import ") || line.contains(" class ") || line.contains(" record ")) {
                continue;
            }
            if (line.contains("ObjectInstance")
                    || line.contains("AbstractObjectInstance")
                    || line.matches(".*\\b[A-Za-z0-9_$]+Child\\b.*")
                    || line.matches(".*\\b[A-Za-z0-9_$]*(Projectile|Fragment|Debris|Splash|Dust)\\b.*")) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsParentChildNaming(String source, String className) {
        String lowerSource = source.toLowerCase(Locale.ROOT);
        String lowerClassName = className.toLowerCase(Locale.ROOT);
        return (lowerSource.contains("parent") && lowerSource.contains("child"))
                || lowerClassName.contains("child")
                || lowerSource.contains("childcomponents")
                || lowerSource.contains("children");
    }

    private static boolean hasChildLikeClassName(String source, String primaryClassName) {
        if (CHILD_LIKE_NAME_PATTERN.matcher(primaryClassName).matches()) {
            return true;
        }
        Matcher matcher = CLASS_PATTERN.matcher(source);
        while (matcher.find()) {
            if (CHILD_LIKE_NAME_PATTERN.matcher(matcher.group(1)).matches()) {
                return true;
            }
        }
        return false;
    }

    private static EnumSet<PolicyPrompt> policyPromptsFor(EnumSet<Signal> signals) {
        EnumSet<PolicyPrompt> prompts = EnumSet.noneOf(PolicyPrompt.class);
        if (signals.contains(Signal.SPAWN_CHILD_HELPER)
                || signals.contains(Signal.OBJECT_INSTANCE_FIELD)
                || signals.contains(Signal.PARENT_CHILD_NAMING)
                || signals.contains(Signal.CHILD_SPAWNS_SNAPSHOT)) {
            prompts.add(PolicyPrompt.PARENT_OWNED);
        }
        if (signals.contains(Signal.ADD_DYNAMIC_OBJECT)
                || signals.contains(Signal.SPAWN_CHILD_HELPER)) {
            prompts.add(PolicyPrompt.INDEPENDENT);
            prompts.add(PolicyPrompt.DETERMINISTIC);
        }
        if (signals.contains(Signal.CHILD_SPAWNS_SNAPSHOT)) {
            prompts.add(PolicyPrompt.DETERMINISTIC);
        }
        if (signals.contains(Signal.CHILD_LIKE_CLASS_NAME)) {
            prompts.add(PolicyPrompt.COSMETIC);
        }
        return prompts;
    }

    private static String qualifiedPrimaryClassName(String source, Path sourcePath) {
        String packageName = "";
        Matcher packageMatcher = PACKAGE_PATTERN.matcher(source);
        if (packageMatcher.find()) {
            packageName = packageMatcher.group(1);
        }
        String simpleName = sourcePath.getFileName().toString().replaceFirst("\\.java$", "");
        Matcher classMatcher = CLASS_PATTERN.matcher(source);
        if (classMatcher.find()) {
            simpleName = classMatcher.group(1);
        }
        return packageName.isEmpty() ? simpleName : packageName + "." + simpleName;
    }

    private static String stripComments(String source) {
        String withoutBlocks = source.replaceAll("(?s)/\\*.*?\\*/", "");
        return withoutBlocks.replaceAll("(?m)//.*$", "");
    }

    public record Finding(
            String className,
            Path sourcePath,
            Set<Signal> signals,
            Set<PolicyPrompt> policyPrompts) {
    }

    public enum Signal {
        ADD_DYNAMIC_OBJECT,
        SPAWN_CHILD_HELPER,
        CHILD_SPAWNS_SNAPSHOT,
        OBJECT_INSTANCE_FIELD,
        PARENT_CHILD_NAMING,
        CHILD_LIKE_CLASS_NAME,
        REWIND_OVERRIDE
    }

    public enum PolicyPrompt {
        PARENT_OWNED,
        INDEPENDENT,
        DETERMINISTIC,
        COSMETIC
    }

    private ChildGraphPolicyInventoryTool() {
    }
}
