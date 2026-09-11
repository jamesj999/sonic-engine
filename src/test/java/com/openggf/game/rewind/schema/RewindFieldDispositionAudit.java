package com.openggf.game.rewind.schema;

import com.openggf.game.PlayableEntity;
import com.openggf.game.rewind.GenericFieldCapturer;
import com.openggf.game.rewind.GenericRewindEligibility;
import com.openggf.game.rewind.RewindDeferred;
import com.openggf.game.rewind.RewindScanSupport;
import com.openggf.game.rewind.RewindTransient;
import com.openggf.level.objects.AbstractBadnikInstance;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectInstance;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Closed-world rewind field-disposition audit.
 *
 * <p>For every concrete object class on the default (reflective) rewind capture
 * path, every declared instance field must have an affirmative disposition:
 * <ul>
 *   <li>captured by the capture path the class <em>actually</em> uses — the
 *       compact schema when {@link CompactFieldCapturer#supportsDefaultObjectSubclassScalars}
 *       holds, otherwise the generic scalar fallback (which silently drops
 *       identity-keyed collections and object/player references, even
 *       policy-CAPTURED ones — the MGZ spinning-top rewind bug);</li>
 *   <li>explicitly excluded ({@code @RewindTransient}, {@code @RewindDeferred},
 *       or a TRANSIENT/DEFERRED entry in {@link DefaultObjectRewindPolicies});</li>
 *   <li>a provably structural {@code final} — an immutable value or a
 *       structural reference the constructor derives deterministically
 *       (mirrors {@code ObjectManager.isRewindReuseSafeNonCapturedField}).</li>
 * </ul>
 *
 * <p>Anything else is a gap key: state that silently fails to ride rewind
 * keyframes. New keys fail {@code TestRewindFieldDispositionGuard}; existing
 * debt lives in {@code src/test/resources/rewind/field-disposition-baseline.txt}.
 */
final class RewindFieldDispositionAudit {

    record Gap(String key, String detail) {}

    static List<Gap> auditAll() throws IOException {
        Set<Class<?>> classes = new LinkedHashSet<>();
        for (Class<?> cls : RewindScanSupport.discoverRuntimeOwnerClasses()) {
            classes.addAll(RewindScanSupport.withNestedRuntimeOwnerClasses(cls));
        }
        List<Gap> gaps = new ArrayList<>();
        for (Class<?> cls : classes) {
            if (!AbstractObjectInstance.class.isAssignableFrom(cls)
                    || cls == AbstractObjectInstance.class
                    || Modifier.isAbstract(cls.getModifiers())) {
                continue;
            }
            boolean badnik = AbstractBadnikInstance.class.isAssignableFrom(cls);
            boolean defaultCapture = badnik
                    ? GenericRewindEligibility.usesDefaultBadnikSubclassCapture(cls)
                    : GenericRewindEligibility.usesDefaultObjectSubclassCapture(cls);
            if (!defaultCapture) {
                continue; // Hand-written capture overrides own their coverage.
            }
            auditClass(cls, gaps);
        }
        gaps.sort((a, b) -> a.key().compareTo(b.key()));
        return gaps;
    }

    static Set<String> gapKeys() throws IOException {
        Set<String> keys = new TreeSet<>();
        for (Gap gap : auditAll()) {
            keys.add(gap.key());
        }
        return keys;
    }

    private static void auditClass(Class<?> cls, List<Gap> gaps) {
        boolean compact;
        try {
            compact = CompactFieldCapturer.supportsDefaultObjectSubclassScalars(cls);
        } catch (RuntimeException e) {
            gaps.add(new Gap(cls.getName() + "#<schema-error>", e.toString()));
            return;
        }
        Set<Field> captured = new HashSet<>();
        if (compact) {
            for (RewindFieldPlan plan : RewindSchemaRegistry.defaultObjectSubclassSchemaFor(cls).capturedFields()) {
                captured.add(plan.field());
            }
        } else {
            captured.addAll(GenericFieldCapturer.defaultObjectSubclassCapturedFieldsForAudit(cls));
        }

        for (Class<?> c = cls; c != null && c != AbstractObjectInstance.class; c = c.getSuperclass()) {
            if (c == AbstractBadnikInstance.class) {
                continue; // Hand-audited: movement state rides BadnikRewindExtra.
            }
            for (Field field : c.getDeclaredFields()) {
                if (isExplicitlyDisposed(field) || captured.contains(field) || isStructuralFinal(field)
                        || isStructuralHandleType(field)
                        || GenericFieldCapturer.hasDefaultObjectCaptureDecision(field)) {
                    continue;
                }
                String path = compact ? "compact" : "generic-fallback";
                gaps.add(new Gap(
                        cls.getName() + "#" + field.getName(),
                        "type=" + field.getType().getSimpleName()
                                + " path=" + path
                                + (compact ? "" : " (class falls back; field dropped silently)")));
            }
        }
    }

    private static boolean isExplicitlyDisposed(Field field) {
        int mods = field.getModifiers();
        if (Modifier.isStatic(mods) || Modifier.isTransient(mods) || field.isSynthetic()
                || field.isAnnotationPresent(RewindTransient.class)
                || field.isAnnotationPresent(RewindDeferred.class)) {
            return true;
        }
        RewindFieldPolicy policy = DefaultObjectRewindPolicies.policyFor(field);
        return policy == RewindFieldPolicy.TRANSIENT || policy == RewindFieldPolicy.DEFERRED;
    }

    /**
     * Render/art handle types that carry no gameplay state: lazily-cached
     * draw helpers the constructor (or first render) re-derives.
     */
    private static boolean isStructuralHandleType(Field field) {
        String name = field.getType().getName();
        return name.equals("com.openggf.level.render.PatternSpriteRenderer")
                || name.equals("com.openggf.sprites.render.PlayerSpriteRenderer")
                || name.endsWith(".PlayerSpriteRenderer");
    }

    /**
     * Final fields whose live value provably matches what a reconstruction
     * would compute: immutable values, or structural references derived
     * deterministically by the constructor. Final containers and final
     * object/player references are NOT structural — their content/identity is
     * mutable gameplay state.
     */
    private static boolean isStructuralFinal(Field field) {
        if (!Modifier.isFinal(field.getModifiers())) {
            return false;
        }
        Class<?> type = field.getType();
        if (type.isPrimitive() || type.isEnum() || type == String.class
                || type == Boolean.class || type == Byte.class || type == Character.class
                || type == Short.class || type == Integer.class || type == Long.class
                || type == Float.class || type == Double.class) {
            return true;
        }
        if (type.isArray()
                || Collection.class.isAssignableFrom(type)
                || Map.class.isAssignableFrom(type)
                || ObjectInstance.class.isAssignableFrom(type)
                || PlayableEntity.class.isAssignableFrom(type)) {
            return false;
        }
        return true; // Final structural reference (renderer/mapping/config/services handle).
    }

    private RewindFieldDispositionAudit() {}
}
