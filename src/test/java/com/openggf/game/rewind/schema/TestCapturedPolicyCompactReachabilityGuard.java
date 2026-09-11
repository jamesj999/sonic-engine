package com.openggf.game.rewind.schema;

import com.openggf.game.rewind.FieldKey;
import com.openggf.game.rewind.GenericRewindEligibility;
import com.openggf.game.sonic3k.objects.S3kResultsScreenObjectInstance;
import com.openggf.game.sonic3k.objects.TensionBridgeObjectInstance;
import com.openggf.game.sonic3k.objects.bosses.HczEndBossBladeImpactExplosion;
import com.openggf.game.sonic3k.objects.bosses.HczEndBossEggCapsuleButton;
import com.openggf.game.sonic3k.objects.MGZTopPlatformObjectInstance;
import com.openggf.level.objects.AbstractBadnikInstance;
import com.openggf.level.objects.AbstractObjectInstance;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Guards the compact-schema reachability of explicit {@code CAPTURED} collection
 * policies. A collection/map field (e.g. a per-player grab-state map) is only
 * capturable through the compact schema path; if any other field of the class is
 * unsupported, the whole class silently falls back to the generic scalar path and
 * the policy does nothing — the MGZ spinning-top grab state was lost across
 * rewind exactly this way.
 */
class TestCapturedPolicyCompactReachabilityGuard {

    @Test
    void hczResultAndBossReferencesUseExactCapturedPolicies() {
        Map<FieldKey, RewindFieldPolicy> policies =
                DefaultObjectRewindPolicies.exactFieldPoliciesForAudit();

        assertEquals(RewindFieldPolicy.CAPTURED, policies.get(new FieldKey(
                S3kResultsScreenObjectInstance.class.getName(), "elements")));
        assertEquals(RewindFieldPolicy.CAPTURED, policies.get(new FieldKey(
                HczEndBossEggCapsuleButton.class.getName(), "parent")));
        assertEquals(RewindFieldPolicy.CAPTURED, policies.get(new FieldKey(
                HczEndBossBladeImpactExplosion.class.getName(), "boss")));
        assertFalse(policies.containsKey(new FieldKey(
                TensionBridgeObjectInstance.class.getName(), "playerAtCollapse")),
                "deleted bridge fields must not leave unreachable exact policies behind");
    }

    @Test
    void mgzTopPlatformUsesCompactCapture() {
        assertTrue(GenericRewindEligibility.usesDefaultObjectSubclassCapture(MGZTopPlatformObjectInstance.class),
                "MGZ top platform must stay on the default capture path");
        assertTrue(CompactFieldCapturer.supportsDefaultObjectSubclassScalars(MGZTopPlatformObjectInstance.class),
                "MGZ top platform must be compact-schema capturable so playerStates (grab map) rides keyframes");
    }

    @Test
    void capturedPoliciesNeedingCompactPathAreReachable() {
        List<String> failures = new ArrayList<>();
        for (Map.Entry<FieldKey, RewindFieldPolicy> entry
                : DefaultObjectRewindPolicies.exactFieldPoliciesForAudit().entrySet()) {
            if (entry.getValue() != RewindFieldPolicy.CAPTURED) {
                continue;
            }
            Class<?> declaring;
            Field field;
            try {
                declaring = Class.forName(entry.getKey().declaringClassName());
                field = declaring.getDeclaredField(entry.getKey().fieldName());
            } catch (ReflectiveOperationException e) {
                failures.add(entry.getKey() + " -> unresolvable: " + e);
                continue;
            }
            if (com.openggf.game.rewind.GenericFieldCapturer.isCapturedByDefaultObjectScalarPolicy(field)) {
                continue; // Also captured by the generic fallback path, so reachability is moot.
            }
            if (!AbstractObjectInstance.class.isAssignableFrom(declaring)
                    || AbstractBadnikInstance.class.isAssignableFrom(declaring)
                    || Modifier.isAbstract(declaring.getModifiers())
                    || !GenericRewindEligibility.usesDefaultObjectSubclassCapture(declaring)) {
                continue; // Concrete capture overrides own their coverage explicitly.
            }
            if (!CompactFieldCapturer.supportsDefaultObjectSubclassScalars(declaring)) {
                RewindClassSchema schema = RewindSchemaRegistry.defaultObjectSubclassSchemaFor(declaring);
                List<String> unsupported = schema.unsupportedFields().stream()
                        .map(plan -> plan.key().toString())
                        .toList();
                failures.add(entry.getKey() + " policy CAPTURED is unreachable: class falls back to the "
                        + "generic scalar path, which cannot capture this field. Unsupported fields: "
                        + unsupported);
            }
        }
        assertTrue(failures.isEmpty(), () -> String.join("\n", failures));
    }
}
