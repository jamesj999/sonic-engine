package com.openggf.audio;

import com.openggf.audio.presentation.AudioPresentationProducer;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Replaces the deleted deterministic-runtime boundary test. After the split
 * runtime was removed there is exactly one presentation architecture, so the
 * boundary worth pinning is no longer "manager vs runtime" but "manager is the
 * only production entry point into the presentation producer".
 */
class TestAudioPresentationBoundary {

    /**
     * Production code outside {@link AudioManager} must reach presentation only
     * through the manager. {@code AudioPresentationProducer} owns the
     * presentation clock, final PCM, history, reverse cursor and every capture
     * lease; a second production caller would be a second presentation owner.
     */
    @Test
    void audioManagerIsTheOnlyPresentationProducerEntryPoint() {
        var production = new ClassFileImporter()
                .withImportOption(new ImportOption.DoNotIncludeTests())
                .importPackages("com.openggf");

        List<String> callers = production.stream()
                .flatMap(owner -> owner.getMethodCallsFromSelf().stream())
                .filter(call -> call.getTargetOwner()
                        .isAssignableTo(AudioPresentationProducer.class))
                .filter(call -> !isApprovedProducerOwner(call.getOriginOwner()))
                .map(JavaMethodCall::getDescription)
                .sorted()
                .distinct()
                .toList();

        assertEquals(List.of(), callers,
                "only AudioManager may drive the presentation producer");
    }

    private static boolean isApprovedProducerOwner(JavaClass owner) {
        // AudioManager is the production boundary; the producer's own nested
        // capture handles legitimately call back into it.
        return owner.isEquivalentTo(AudioManager.class)
                || owner.isAssignableTo(AudioPresentationProducer.class)
                || APPROVED_NESTED_OWNERS.contains(
                        owner.getName().split("\\$")[0]);
    }

    private static final Set<String> APPROVED_NESTED_OWNERS = Set.of(
            AudioManager.class.getName(),
            AudioPresentationProducer.class.getName());
}
