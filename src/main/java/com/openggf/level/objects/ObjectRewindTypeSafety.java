package com.openggf.level.objects;

import com.openggf.game.PlayableEntity;
import java.lang.reflect.Modifier;
import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Reflective proof used by the in-place object rewind restore fast path. */
final class ObjectRewindTypeSafety {
    private static final Map<Class<?>, Boolean> CACHE = new ConcurrentHashMap<>();
    private static final ClassValue<DispatchRoute> DISPATCH_ROUTES =
            new ClassValue<>() {
                @Override
                protected DispatchRoute computeValue(Class<?> type) {
                    return new DispatchRoute(
                            hasLegacyOverride(type, "captureRewindState"),
                            hasLegacyOverride(
                                    type,
                                    "restoreRewindState",
                                    PerObjectRewindSnapshot.class));
                }
            };
    private static final Set<Class<?>> IMMUTABLE_VALUES = Set.of(
            Boolean.class, Byte.class, Character.class, Short.class,
            Integer.class, Long.class, Float.class, Double.class, String.class);

    private ObjectRewindTypeSafety() {
    }

    static boolean isSafe(Class<?> type) {
        return CACHE.computeIfAbsent(type, ObjectRewindTypeSafety::compute);
    }

    static PerObjectRewindSnapshot capture(
            AbstractObjectInstance object,
            com.openggf.game.rewind.schema.RewindCaptureContext context) {
        return dispatchRoute(object.getClass()).legacyCapture()
                ? object.captureRewindState()
                : object.captureRewindState(context);
    }

    static void restore(
            AbstractObjectInstance object,
            PerObjectRewindSnapshot snapshot,
            com.openggf.game.rewind.schema.RewindCaptureContext context) {
        if (dispatchRoute(object.getClass()).legacyRestore()) {
            object.restoreRewindState(snapshot);
        } else {
            object.restoreRewindState(snapshot, context);
        }
    }

    static DispatchRoute dispatchRoute(Class<?> type) {
        return DISPATCH_ROUTES.get(type);
    }

    private static boolean hasLegacyOverride(
            Class<?> type, String name, Class<?>... parameterTypes) {
        for (Class<?> current = type;
                current != null && current != AbstractObjectInstance.class;
                current = current.getSuperclass()) {
            if (current == AbstractBadnikInstance.class) {
                continue;
            }
            try {
                var method = current.getDeclaredMethod(name, parameterTypes);
                if (!Modifier.isAbstract(method.getModifiers())
                        && !method.isSynthetic()
                        && !method.isBridge()) {
                    return true;
                }
            } catch (NoSuchMethodException ignored) {
                // Keep walking toward AbstractObjectInstance.
            }
        }
        return false;
    }

    record DispatchRoute(boolean legacyCapture, boolean legacyRestore) {
    }

    private static boolean compute(Class<?> type) {
        if (!AbstractObjectInstance.class.isAssignableFrom(type)
                || Modifier.isAbstract(type.getModifiers())
                || type.isAnnotationPresent(
                        com.openggf.game.rewind.RewindRecreateOnRestore.class)) {
            return false;
        }
        boolean badnik = AbstractBadnikInstance.class.isAssignableFrom(type);
        boolean defaultCapture = badnik
                ? com.openggf.game.rewind.GenericRewindEligibility
                        .usesDefaultBadnikSubclassCapture(type)
                : com.openggf.game.rewind.GenericRewindEligibility
                        .usesDefaultObjectSubclassCapture(type);
        if (!defaultCapture) {
            return false;
        }
        Set<java.lang.reflect.Field> captured = new HashSet<>(
                com.openggf.game.rewind.GenericFieldCapturer
                        .defaultObjectSubclassCapturedFieldsForAudit(type));
        for (Class<?> current = type;
                current != null && current != AbstractObjectInstance.class;
                current = current.getSuperclass()) {
            if (current == AbstractBadnikInstance.class) {
                continue;
            }
            for (java.lang.reflect.Field field : current.getDeclaredFields()) {
                if (!Modifier.isStatic(field.getModifiers())
                        && !field.isSynthetic()
                        && !captured.contains(field)
                        && !isSafeNonCapturedField(field)) {
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean isSafeNonCapturedField(java.lang.reflect.Field field) {
        if (!Modifier.isFinal(field.getModifiers())) {
            return false;
        }
        Class<?> type = field.getType();
        if (type.isPrimitive() || type.isEnum() || IMMUTABLE_VALUES.contains(type)) {
            return true;
        }
        if (type.isArray()
                || Collection.class.isAssignableFrom(type)
                || Map.class.isAssignableFrom(type)) {
            return false;
        }
        return !ObjectInstance.class.isAssignableFrom(type)
                && !PlayableEntity.class.isAssignableFrom(type);
    }
}
