package com.openggf.tools.rewind;

import com.openggf.game.rewind.GenericFieldCapturer;
import com.openggf.game.rewind.GenericRewindEligibility;
import com.openggf.game.rewind.RewindDeferred;
import com.openggf.game.rewind.RewindScanSupport;
import com.openggf.game.rewind.RewindTransient;
import com.openggf.game.rewind.coverage.RewindCoverageAnalyzer;
import com.openggf.game.rewind.coverage.RewindCoverageReport;
import com.openggf.game.rewind.schema.RewindCodec;
import com.openggf.game.rewind.schema.RewindCodecs;
import com.openggf.game.rewind.schema.RewindFieldPolicy;
import com.openggf.game.rewind.schema.RewindPolicyRegistry;
import com.openggf.level.objects.AbstractObjectInstance;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

public final class RewindFieldInventoryTool {
    public static void main(String[] args) throws Exception {
        if (args.length == 1 && "--object-rollout-candidates".equals(args[0])) {
            List<String> candidates = objectRolloutCandidates();
            if (candidates.isEmpty()) {
                System.out.println("No default object rewind rollout candidates found.");
                return;
            }
            System.out.println("Default object rewind rollout candidates:");
            candidates.forEach(System.out::println);
            return;
        }

        if (args.length == 1 && "--annotation-density".equals(args[0])) {
            AnnotationDensity density = annotationDensity();
            printAnnotationDensity(density);
            List<RedundantTransientAnnotation> redundant = redundantTransientAnnotations();
            printRedundantTransientAnnotations(redundant);
            return;
        }

        if (args.length == 1 && "--coverage".equals(args[0])) {
            System.out.print(renderCoverageReport());
            return;
        }

        List<String> unsupported = unsupportedFields();
        if (unsupported.isEmpty()) {
            System.out.println("No unsupported rewind fields found.");
            return;
        }

        System.err.println("Unsupported rewind fields:");
        unsupported.forEach(System.err::println);
        System.exit(1);
    }

    /**
     * Runs the coverage analyzer across all games and returns the rendered report
     * with a summary line appended.
     *
     * <p>The summary line always contains the literal substrings {@code "coverage:"}
     * and {@code "gaps"}, e.g.:
     * <pre>coverage: 42/100 covered, 58 gaps</pre>
     *
     * @return the full rendered text of the coverage report plus the summary line
     */
    public static String renderCoverageReport() {
        RewindCoverageReport report = RewindCoverageAnalyzer.analyzeAll(Set.of());
        int totalCount = report.objects().size();
        int gapCount = report.gapKeys().size();
        int coveredCount = totalCount - (int) report.objects().stream()
                .filter(obj -> !obj.gapKeys().isEmpty())
                .count();
        String rendered = report.render();
        return rendered + "coverage: " + coveredCount + "/" + totalCount
                + " covered, " + gapCount + " gaps\n";
    }

    static List<String> unsupportedFields() throws Exception {
        List<String> unsupported = new ArrayList<>();
        for (Class<?> top : RewindScanSupport.discoverRuntimeOwnerClasses()) {
            for (Class<?> cls : RewindScanSupport.withNestedRuntimeOwnerClasses(top)) {
                unsupported.addAll(unsupportedFieldsForClass(cls));
            }
        }
        unsupported.sort(String::compareTo);
        return unsupported;
    }

    static List<String> objectRolloutCandidates() throws Exception {
        List<String> candidates = new ArrayList<>();
        for (Class<?> top : RewindScanSupport.discoverRuntimeOwnerClasses()) {
            for (Class<?> cls : RewindScanSupport.withNestedRuntimeOwnerClasses(top)) {
                if (!AbstractObjectInstance.class.isAssignableFrom(cls)
                        || !GenericRewindEligibility.usesDefaultObjectSubclassCapture(cls)) {
                    continue;
                }
                List<Field> fields = GenericFieldCapturer.defaultObjectSubclassCapturedFieldsForAudit(cls);
                if (!fields.isEmpty()) {
                    candidates.add(cls.getName() + " : " + fields.size() + " default fields");
                }
            }
        }
        candidates.sort(String::compareTo);
        return candidates;
    }

    static AnnotationDensity annotationDensity() throws Exception {
        return annotationDensityForClasses(runtimeOwnerClasses());
    }

    static AnnotationDensity annotationDensityForClasses(List<Class<?>> classes) {
        Map<String, AnnotationCounts> byClass = new TreeMap<>();
        Map<String, AnnotationCounts> byDeclaredType = new TreeMap<>();
        Map<String, AnnotationCounts> byPackage = new TreeMap<>();
        List<AnnotationField> fields = new ArrayList<>();

        for (Class<?> cls : classes) {
            for (Field field : cls.getDeclaredFields()) {
                AnnotationKind annotation = annotationKind(field).orElse(null);
                if (annotation == null) {
                    continue;
                }
                String className = cls.getName();
                String declaredType = field.getType().getName();
                String packageName = packageName(cls);
                mergeCount(byClass, className, annotation);
                mergeCount(byDeclaredType, declaredType, annotation);
                mergeCount(byPackage, packageName, annotation);
                fields.add(new AnnotationField(
                        className + "#" + field.getName(),
                        declaredType,
                        packageName,
                        annotation));
            }
        }

        fields.sort(Comparator
                .comparing(AnnotationField::field)
                .thenComparing(field -> field.annotation().name()));
        return new AnnotationDensity(
                Collections.unmodifiableMap(byClass),
                Collections.unmodifiableMap(byDeclaredType),
                Collections.unmodifiableMap(byPackage),
                List.copyOf(fields));
    }

    static List<RedundantTransientAnnotation> redundantTransientAnnotations() throws Exception {
        return redundantTransientAnnotationsForClasses(runtimeOwnerClasses());
    }

    static List<RedundantTransientAnnotation> redundantTransientAnnotationsForClasses(List<Class<?>> classes) {
        List<RedundantTransientAnnotation> redundant = new ArrayList<>();
        for (Class<?> cls : classes) {
            for (Field field : cls.getDeclaredFields()) {
                if (!field.isAnnotationPresent(RewindTransient.class)) {
                    continue;
                }
                RewindFieldPolicy inferred = inferredPolicyWithoutTransientAnnotation(field);
                if (inferred != RewindFieldPolicy.TRANSIENT) {
                    continue;
                }
                redundant.add(new RedundantTransientAnnotation(
                        cls.getName() + "#" + field.getName(),
                        field.getType().getName(),
                        inferred,
                        isGenericEligible(cls)));
            }
        }
        redundant.sort(Comparator.comparing(RedundantTransientAnnotation::field));
        return List.copyOf(redundant);
    }

    static List<String> unsupportedFieldsForClass(Class<?> cls) {
        List<String> unsupported = new ArrayList<>();
        for (Field field : cls.getDeclaredFields()) {
            int mods = field.getModifiers();
            if (Modifier.isStatic(mods)
                    || Modifier.isTransient(mods)
                    || field.isSynthetic()
                    || field.isAnnotationPresent(RewindTransient.class)
                    || field.isAnnotationPresent(RewindDeferred.class)) {
                continue;
            }
            RewindFieldPolicy registeredPolicy = RewindPolicyRegistry.policyForAudit(field).orElse(null);
            if (registeredPolicy != null
                    && (registeredPolicy != RewindFieldPolicy.CAPTURED
                    || RewindCodecs.codecFor(field).isPresent())) {
                continue;
            }
            if (GenericRewindEligibility.usesDefaultObjectSubclassCapture(cls)
                    && GenericFieldCapturer.hasDefaultObjectCaptureDecision(field)) {
                continue;
            }
            if (inferredPolicyWithoutTransientAnnotation(field) != RewindFieldPolicy.UNSUPPORTED) {
                continue;
            }
            if (!GenericFieldCapturer.isSupportedDeclaredTypeForAudit(field)) {
                unsupported.add(cls.getName() + "#" + field.getName()
                        + " : " + field.getType().getName());
            }
        }
        return unsupported;
    }

    private static List<Class<?>> runtimeOwnerClasses() throws Exception {
        List<Class<?>> classes = new ArrayList<>();
        for (Class<?> top : RewindScanSupport.discoverRuntimeOwnerClasses()) {
            classes.addAll(RewindScanSupport.withNestedRuntimeOwnerClasses(top));
        }
        classes.sort(Comparator.comparing(Class::getName));
        return List.copyOf(classes);
    }

    private static Optional<AnnotationKind> annotationKind(Field field) {
        if (field.isAnnotationPresent(RewindTransient.class)) {
            return Optional.of(AnnotationKind.REWIND_TRANSIENT);
        }
        if (field.isAnnotationPresent(RewindDeferred.class)) {
            return Optional.of(AnnotationKind.REWIND_DEFERRED);
        }
        return Optional.empty();
    }

    private static void mergeCount(Map<String, AnnotationCounts> counts, String key, AnnotationKind annotation) {
        counts.merge(key, AnnotationCounts.of(annotation), AnnotationCounts::plus);
    }

    private static RewindFieldPolicy inferredPolicyWithoutTransientAnnotation(Field field) {
        int mods = field.getModifiers();
        if (Modifier.isStatic(mods)
                || Modifier.isTransient(mods)
                || field.isSynthetic()) {
            return RewindFieldPolicy.TRANSIENT;
        }
        if (field.isAnnotationPresent(RewindDeferred.class)) {
            return RewindFieldPolicy.DEFERRED;
        }

        RewindCodec codec = RewindCodecs.codecFor(field).orElse(null);
        RewindFieldPolicy registeredPolicy = registeredPolicyFor(field).orElse(null);
        if (registeredPolicy != null) {
            if (registeredPolicy == RewindFieldPolicy.CAPTURED && codec == null) {
                return RewindFieldPolicy.UNSUPPORTED;
            }
            return registeredPolicy;
        }
        if (Modifier.isFinal(mods) && codec != null && codec.capturesFinalFields()) {
            return RewindFieldPolicy.CAPTURED;
        }
        if (Modifier.isFinal(mods) && codec != null) {
            return RewindFieldPolicy.STRUCTURAL;
        }
        if (codec != null) {
            return RewindFieldPolicy.CAPTURED;
        }
        return RewindFieldPolicy.UNSUPPORTED;
    }

    private static Optional<RewindFieldPolicy> registeredPolicyFor(Field field) {
        return RewindPolicyRegistry.policyForAudit(field);
    }

    private static boolean isGenericEligible(Class<?> cls) {
        return GenericRewindEligibility.isEligible(cls)
                || GenericRewindEligibility.usesDefaultObjectSubclassCapture(cls);
    }

    private static String packageName(Class<?> cls) {
        String packageName = cls.getPackageName();
        return packageName.isEmpty() ? "(default)" : packageName;
    }

    private static void printAnnotationDensity(AnnotationDensity density) {
        System.out.println("Rewind annotation density:");
        printCounts("By declaring class:", density.byClass());
        printCounts("By declared type:", density.byDeclaredType());
        printCounts("By declaring package:", density.byPackage());
    }

    private static void printCounts(String title, Map<String, AnnotationCounts> counts) {
        System.out.println(title);
        if (counts.isEmpty()) {
            System.out.println("  none");
            return;
        }
        counts.forEach((key, value) -> System.out.println("  " + key
                + " : total=" + value.total()
                + " transient=" + value.transientCount()
                + " deferred=" + value.deferredCount()));
    }

    private static void printRedundantTransientAnnotations(List<RedundantTransientAnnotation> redundant) {
        System.out.println("Redundant @RewindTransient annotations:");
        if (redundant.isEmpty()) {
            System.out.println("  none");
            return;
        }
        for (RedundantTransientAnnotation annotation : redundant) {
            String prefix = annotation.genericEligibleClass() ? "  WARN " : "  ";
            System.out.println(prefix + annotation.field()
                    + " : declaredType=" + annotation.declaredType()
                    + " inferredPolicy=" + annotation.inferredPolicy());
        }
    }

    enum AnnotationKind {
        REWIND_TRANSIENT,
        REWIND_DEFERRED
    }

    record AnnotationCounts(int transientCount, int deferredCount) {
        static AnnotationCounts of(AnnotationKind annotation) {
            return switch (annotation) {
                case REWIND_TRANSIENT -> new AnnotationCounts(1, 0);
                case REWIND_DEFERRED -> new AnnotationCounts(0, 1);
            };
        }

        AnnotationCounts plus(AnnotationCounts other) {
            return new AnnotationCounts(
                    transientCount + other.transientCount,
                    deferredCount + other.deferredCount);
        }

        int total() {
            return transientCount + deferredCount;
        }
    }

    record AnnotationField(
            String field,
            String declaredType,
            String declaringPackage,
            AnnotationKind annotation) {
    }

    record AnnotationDensity(
            Map<String, AnnotationCounts> byClass,
            Map<String, AnnotationCounts> byDeclaredType,
            Map<String, AnnotationCounts> byPackage,
            List<AnnotationField> fields) {
    }

    record RedundantTransientAnnotation(
            String field,
            String declaredType,
            RewindFieldPolicy inferredPolicy,
            boolean genericEligibleClass) {
    }

    private RewindFieldInventoryTool() {
    }
}
