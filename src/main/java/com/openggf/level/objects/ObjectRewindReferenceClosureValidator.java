package com.openggf.level.objects;

import com.openggf.game.rewind.GenericRewindEligibility;
import com.openggf.game.rewind.schema.CompactFieldCapturer;
import com.openggf.game.rewind.schema.RewindCaptureContext;

import java.util.Collection;
import java.util.Set;

/** Validates compact-capture reference closure across the live object graph. */
final class ObjectRewindReferenceClosureValidator {
    private ObjectRewindReferenceClosureValidator() {
    }

    static void validate(Collection<ObjectInstance> placedObjects,
            Collection<ObjectInstance> dynamicObjects,
            Set<ObjectInstance> auxiliaryDynamicObjects,
            RewindCaptureContext context) {
        placedObjects.forEach(instance -> validateOwner(instance, context));
        dynamicObjects.stream()
                .filter(instance -> !auxiliaryDynamicObjects.contains(instance))
                .forEach(instance -> validateOwner(instance, context));
    }

    private static void validateOwner(ObjectInstance instance, RewindCaptureContext context) {
        if (instance instanceof AbstractObjectInstance object
                && GenericRewindEligibility.usesCompactDefaultSubclassCapture(object.getClass())) {
            CompactFieldCapturer.validateDefaultObjectSubclassReferenceClosure(object, context);
        }
    }
}
