package com.openggf.game.rewind;

import com.openggf.game.rewind.schema.RewindClassSchema;
import com.openggf.game.rewind.schema.RewindFieldPolicy;
import com.openggf.game.rewind.schema.RewindSchemaRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class TestRewindFieldAudit {
    static class WithUnannotatedFinal {
        final Object value = new Object();
    }

    static class WithAnnotatedFinal {
        @RewindTransient(reason = "structural final fixture")
        final Object value = new Object();
    }

    @BeforeEach
    void clearEligibilityBeforeTest() {
        GenericRewindEligibility.clearForTest();
    }

    @AfterEach
    void clearEligibilityAfterTest() {
        GenericRewindEligibility.clearForTest();
    }

    @Test
    void eligibleClassesHaveOnlySupportedGenericFields() {
        List<String> unsupported = unsupportedEligibleFields();
        if (!unsupported.isEmpty()) {
            fail("Generic-eligible classes contain unsupported fields:\n"
                    + String.join("\n", unsupported));
        }
    }

    @Test
    void auditReportsUnannotatedFinalFieldsInEligibleClasses() {
        GenericRewindEligibility.registerForTestOrMigration(WithUnannotatedFinal.class);

        List<String> unsupported = unsupportedEligibleFields();

        assertFalse(unsupported.isEmpty());
        assertTrue(unsupported.stream()
                .anyMatch(line -> line.contains(WithUnannotatedFinal.class.getName() + "#value")));
    }

    @Test
    void auditSkipsAnnotatedFinalFieldsInEligibleClasses() {
        GenericRewindEligibility.registerForTestOrMigration(WithAnnotatedFinal.class);

        List<String> unsupported = unsupportedEligibleFields();

        if (!unsupported.isEmpty()) {
            fail("Annotated final fixture should be skipped:\n" + String.join("\n", unsupported));
        }
    }

    @Test
    void objectSubclassMutableFieldsHaveCaptureDecision() throws Exception {
        List<String> uncovered = uncoveredObjectSubclassFields();
        Path baselinePath = Path.of("src/test/resources/rewind/object-field-coverage-baseline.txt");
        if (Boolean.getBoolean("rewind.audit.writeBaseline")) {
            Files.createDirectories(baselinePath.getParent());
            Files.write(baselinePath, uncovered);
            return;
        }

        List<String> baseline = Files.exists(baselinePath)
                ? Files.readAllLines(baselinePath)
                : List.of();
        List<String> unexpected = new ArrayList<>(uncovered);
        unexpected.removeAll(baseline);
        List<String> stale = new ArrayList<>(baseline);
        stale.removeAll(uncovered);
        if (!unexpected.isEmpty() || !stale.isEmpty()) {
            List<String> sections = new ArrayList<>();
            if (!unexpected.isEmpty()) {
                sections.add("New uncovered object fields:\n" + String.join("\n", unexpected));
            }
            if (!stale.isEmpty()) {
                sections.add("Baseline entries no longer present:\n" + String.join("\n", stale));
            }
            fail(String.join("\n\n", sections)
                    + "\n\nUpdate the object rewind implementation or regenerate the baseline only after triage.");
        }
    }

    @Test
    void objectSubclassesDoNotStoreRunnableContinuations() throws Exception {
        List<String> runnableFields = objectSubclassRunnableFields();
        if (!runnableFields.isEmpty()) {
            fail("Object subclasses should use rewindable enum continuation tokens instead of Runnable fields:\n"
                    + String.join("\n", runnableFields));
        }
    }

    private static List<String> unsupportedEligibleFields() {
        List<String> unsupported = new ArrayList<>();
        for (Class<?> cls : GenericRewindEligibility.eligibleClassesForAudit()) {
            for (Class<?> owner : RewindScanSupport.withNestedRuntimeOwnerClasses(cls)) {
                collectUnsupportedFieldsForCapture(owner, unsupported);
            }
        }
        return unsupported;
    }

    private static void collectUnsupportedFieldsForCapture(Class<?> cls, List<String> unsupported) {
        for (Field field : GenericFieldCapturer.inspectedFieldsForAudit(cls)) {
            if (!GenericFieldCapturer.isSupportedDeclaredTypeForAudit(field)) {
                unsupported.add(field.getDeclaringClass().getName() + "#" + field.getName()
                        + " : " + field.getType().getName());
            }
        }
    }

    private static List<String> uncoveredObjectSubclassFields() throws Exception {
        List<String> uncovered = new ArrayList<>();
        for (Class<?> top : RewindScanSupport.discoverRuntimeOwnerClasses()) {
            for (Class<?> cls : RewindScanSupport.withNestedRuntimeOwnerClasses(top)) {
                collectUncoveredObjectSubclassFields(cls, uncovered);
            }
        }
        uncovered.sort(String::compareTo);
        return uncovered;
    }

    private static List<String> objectSubclassRunnableFields() throws Exception {
        List<String> runnableFields = new ArrayList<>();
        for (Class<?> top : RewindScanSupport.discoverRuntimeOwnerClasses()) {
            for (Class<?> cls : RewindScanSupport.withNestedRuntimeOwnerClasses(top)) {
                if (!com.openggf.level.objects.AbstractObjectInstance.class.isAssignableFrom(cls)
                        || cls == com.openggf.level.objects.AbstractObjectInstance.class
                        || Modifier.isAbstract(cls.getModifiers())) {
                    continue;
                }
                for (Field field : cls.getDeclaredFields()) {
                    int mods = field.getModifiers();
                    if (Modifier.isStatic(mods)
                            || field.isSynthetic()
                            || field.isAnnotationPresent(RewindTransient.class)) {
                        continue;
                    }
                    if (field.getType() == Runnable.class) {
                        runnableFields.add(cls.getName() + "#" + field.getName());
                    }
                }
            }
        }
        runnableFields.sort(String::compareTo);
        return runnableFields;
    }

    private static void collectUncoveredObjectSubclassFields(Class<?> cls, List<String> uncovered) {
        if (!com.openggf.level.objects.AbstractObjectInstance.class.isAssignableFrom(cls)) {
            return;
        }
        if (cls == com.openggf.level.objects.AbstractObjectInstance.class
                || cls == com.openggf.level.objects.AbstractBadnikInstance.class) {
            return;
        }
        if (Modifier.isAbstract(cls.getModifiers())) {
            return;
        }
        boolean classHasCaptureDecision = GenericRewindEligibility.isEligible(cls)
                || declaresRewindOverride(cls);
        if (classHasCaptureDecision) {
            return;
        }
        for (Field field : cls.getDeclaredFields()) {
            int mods = field.getModifiers();
            if (Modifier.isStatic(mods)
                    || Modifier.isTransient(mods)
                    || Modifier.isFinal(mods)
                    || field.isSynthetic()
                    || field.isAnnotationPresent(RewindTransient.class)
                    || field.isAnnotationPresent(RewindDeferred.class)) {
                continue;
            }
            if (GenericFieldCapturer.hasDefaultObjectCaptureDecision(field)) {
                continue;
            }
            if (schemaPolicyFor(cls, field) != RewindFieldPolicy.UNSUPPORTED) {
                continue;
            }
            uncovered.add(cls.getName() + "#" + field.getName()
                    + " : " + field.getType().getName());
        }
    }

    private static boolean declaresRewindOverride(Class<?> cls) {
        return GenericRewindEligibility.declaresConcreteObjectRewindOverride(cls);
    }

    private static RewindFieldPolicy schemaPolicyFor(Class<?> cls, Field field) {
        RewindClassSchema schema = RewindSchemaRegistry.schemaFor(cls);
        return schema.fields().stream()
                .filter(plan -> plan.field().equals(field))
                .findFirst()
                .orElseThrow()
                .policy();
    }
}
