package com.openggf.audio.session;

import com.openggf.audio.driver.SmpsDriver;
import com.openggf.audio.AudioStream;
import com.openggf.audio.presentation.PcmPresentationVoice;
import com.openggf.audio.rewind.SmpsDriverSnapshot;
import com.openggf.audio.smps.SmpsSequencer;
import com.openggf.audio.synth.Synthesizer;
import com.openggf.audio.synth.VirtualSynthesizer;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TestSmpsSessionArchitectureGuard {
    private static final Set<String> PHYSICAL_FIELD_OWNERS = Set.of(
            SmpsDriverSession.class.getName(),
            SmpsDriverSessionSnapshot.class.getName(),
            SmpsPhysicalDevice.class.getName());

    @Test
    void oneSessionCompositionRootOwnsEveryProductionPhysicalType() {
        JavaClasses production = production();
        List<String> foreignFields = production.stream()
                .flatMap(owner -> owner.getAllFields().stream())
                .filter(field -> isPhysical(field.getRawType()))
                .filter(field -> !isAllowedPhysicalOwner(
                        field.getOwner().getFullName()))
                .map(field -> field.getOwner().getFullName() + "."
                        + field.getName() + ":"
                        + field.getRawType().getFullName())
                .sorted()
                .toList();
        assertEquals(List.of(), foreignFields,
                "only the session composition root may store physical state");

        List<String> foreignVirtualSynthConstruction = production.stream()
                .flatMap(owner -> owner.getConstructorCallsFromSelf().stream())
                .filter(call -> call.getTargetOwner()
                        .isEquivalentTo(VirtualSynthesizer.class))
                .filter(call -> !call.getOriginOwner()
                        .isEquivalentTo(SmpsPhysicalDevice.class))
                .filter(call -> !call.getOriginOwner()
                        .isEquivalentTo(VirtualSynthesizer.class))
                .map(Object::toString)
                .sorted()
                .toList();
        assertEquals(List.of(), foreignVirtualSynthConstruction,
                "only SmpsPhysicalDevice may construct chip pairs");

        List<String> interfaceTypedSynthFields = production.stream()
                .flatMap(owner -> owner.getAllFields().stream())
                .filter(field -> field.getRawType()
                        .isEquivalentTo(Synthesizer.class))
                .map(field -> field.getOwner().getFullName() + "."
                        + field.getName())
                .sorted()
                .toList();
        assertEquals(List.of(), interfaceTypedSynthFields,
                "a Synthesizer-typed field can hide physical ownership");

        List<String> foreignSessionConstruction = production.stream()
                .flatMap(owner -> owner.getConstructorCallsFromSelf().stream())
                .filter(call -> call.getTargetOwner()
                        .isEquivalentTo(SmpsDriverSession.class))
                .filter(call -> !Set.of(
                                "com.openggf.audio.AudioManager",
                                OwnedSmpsAudioStream.class.getName())
                        .contains(call.getOriginOwner().getFullName()))
                .map(Object::toString)
                .sorted()
                .toList();
        assertEquals(List.of(), foreignSessionConstruction,
                "only the authoritative manager and isolated stream adapter "
                        + "may create sessions");
    }

    @Test
    void logicalDriverSequencerAndSnapshotGraphHasNoPhysicalOwnership() {
        for (Class<?> logical : List.of(
                SmpsDriver.class, SmpsSequencer.class,
                SmpsDriverSnapshot.class)) {
            assertEquals(List.of(), Arrays.stream(logical.getDeclaredFields())
                    .filter(field -> isPhysical(field.getType()))
                    .map(TestSmpsSessionArchitectureGuard::describe)
                    .toList(), logical.getName());
            if (logical.isRecord()) {
                assertEquals(List.of(), Arrays.stream(
                                logical.getRecordComponents())
                        .filter(component -> isPhysical(
                                component.getType()))
                        .map(TestSmpsSessionArchitectureGuard::describe)
                        .toList(), logical.getName());
            }
        }
        assertFalse(AudioStream.class.isAssignableFrom(SmpsSequencer.class),
                "the sequencer advances logical state but never renders PCM");
        assertFalse(Arrays.stream(SmpsSequencer.class.getDeclaredFields())
                .anyMatch(field -> field.getType() == Synthesizer.class));
        assertFalse(Arrays.stream(SmpsSequencer.class.getDeclaredConstructors())
                .flatMap(constructor -> Arrays.stream(
                        constructor.getParameterTypes()))
                .anyMatch(type -> type == Synthesizer.class));
        assertTrue(production().get(SmpsSequencer.class)
                        .getDirectDependenciesFromSelf().stream()
                        .noneMatch(dependency -> dependency.getTargetClass()
                                .isEquivalentTo(VirtualSynthesizer.class)),
                "logical sequencers cannot depend on the physical synth");
        assertTrue(production().get(SmpsSequencer.class)
                        .getMethodCallsFromSelf().stream()
                        .noneMatch(call -> call.getTargetOwner()
                                        .isEquivalentTo(
                                                VirtualSynthesizer.class)
                                && call.getName().equals("render")),
                "logical sequencers cannot render PCM through a cast");
        assertThrows(ClassNotFoundException.class, () -> Class.forName(
                "com.openggf.audio.presentation.SmpsCompositeVoice"));
        assertThrows(ClassNotFoundException.class, () -> Class.forName(
                "com.openggf.audio.presentation.PresentationVoiceSnapshot$Smps"));
        assertThrows(ClassNotFoundException.class, () -> Class.forName(
                "com.openggf.audio.rewind.LegacySmpsDriverSnapshot"));
    }

    @Test
    void presentationConstructsNoPrivateDriverOrChipPair() {
        JavaClasses production = production();
        List<String> constructions = production.stream()
                .filter(owner -> owner.getPackageName().startsWith(
                        "com.openggf.audio.presentation"))
                .flatMap(owner -> owner.getConstructorCallsFromSelf().stream())
                .filter(call -> call.getTargetOwner()
                                .isEquivalentTo(SmpsDriver.class)
                        || call.getTargetOwner()
                                .isEquivalentTo(VirtualSynthesizer.class)
                        || call.getTargetOwner()
                                .isEquivalentTo(SmpsPhysicalDevice.class))
                .map(Object::toString)
                .sorted()
                .toList();
        assertEquals(List.of(), constructions,
                "presentation factories and voices cannot create private SMPS hardware");
    }

    @Test
    void physicalOwnerAllowlistMatchesOnlyTheExactSessionOrItsNestedTypes()
            throws Exception {
        Method method = TestSmpsSessionArchitectureGuard.class
                .getDeclaredMethod("isAllowedPhysicalOwner", String.class);
        method.setAccessible(true);

        assertTrue((boolean) method.invoke(null,
                SmpsDriverSession.class.getName()));
        assertTrue((boolean) method.invoke(null,
                SmpsDriverSession.class.getName() + "$PreparedRestore"));
        assertFalse((boolean) method.invoke(null,
                SmpsDriverSession.class.getName() + "Impostor"));
    }

    private static JavaClasses production() {
        return new ClassFileImporter()
                .withImportOption(new ImportOption.DoNotIncludeTests())
                .importPackages("com.openggf");
    }

    private static boolean isAllowedPhysicalOwner(String ownerName) {
        return PHYSICAL_FIELD_OWNERS.stream().anyMatch(allowed ->
                ownerName.equals(allowed) || ownerName.startsWith(allowed + "$"))
                || ownerName.equals(SmpsSessionProfileFingerprint.class.getName())
                || isPhysicalName(ownerName);
    }

    private static boolean isPhysical(JavaClass type) {
        return isPhysicalName(type.getFullName());
    }

    private static boolean isPhysical(Class<?> type) {
        return isPhysicalName(type.getName());
    }

    private static boolean isPhysicalName(String name) {
        return name.equals(VirtualSynthesizer.class.getName())
                || name.startsWith(VirtualSynthesizer.class.getName() + "$")
                || name.equals(SmpsPhysicalDevice.class.getName())
                || name.startsWith(SmpsPhysicalDevice.class.getName() + "$")
                || name.equals(SmpsPhysicalPort.class.getName())
                || name.startsWith(SmpsPhysicalPort.class.getName() + "$");
    }

    private static String describe(Field field) {
        return field.getName() + ":" + field.getType().getName();
    }

    private static String describe(RecordComponent component) {
        return component.getName() + ":" + component.getType().getName();
    }
}
