package com.openggf.game.rewind;

import com.openggf.game.GameModule;
import com.openggf.game.rewind.schema.RewindClassSchema;
import com.openggf.game.rewind.schema.RewindFieldPolicy;
import com.openggf.game.rewind.schema.RewindSchemaRegistry;
import com.openggf.graphics.GraphicsManager;
import com.openggf.level.objects.ObjectRenderManager;
import com.openggf.level.objects.ObjectServices;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.level.render.SpritePieceRenderer;
import com.openggf.sprites.render.PlayerSpriteRenderer;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.fail;

class TestRewindTransientGuard {
    private static final Set<Class<?>> DEFAULT_TRANSIENT_TYPES = Set.of(
            GameModule.class,
            GraphicsManager.class,
            ObjectRenderManager.class,
            ObjectServices.class,
            PatternSpriteRenderer.class,
            PlayerSpriteRenderer.class,
            SpritePieceRenderer.class
    );

    @Test
    void engineReferenceFieldsAreAnnotatedOrJavaTransient() throws Exception {
        List<String> violations = new ArrayList<>();
        for (Class<?> top : RewindScanSupport.discoverRuntimeOwnerClasses()) {
            for (Class<?> cls : RewindScanSupport.withNestedRuntimeOwnerClasses(top)) {
                collectEngineReferenceViolations(cls, violations);
            }
        }
        if (!violations.isEmpty()) {
            fail("Engine-ref fields need a rewind classification: central policy, schema codec, "
                    + "Java transient for non-state caches, or explicit snapshot/defer decision for gameplay refs:\n"
                    + String.join("\n", violations));
        }
    }

    @Test
    void fieldsCoveredByDefaultTransientPolicyDoNotNeedExplicitAnnotations() throws Exception {
        List<String> violations = new ArrayList<>();
        for (Class<?> top : RewindScanSupport.discoverRuntimeOwnerClasses()) {
            for (Class<?> cls : RewindScanSupport.withNestedRuntimeOwnerClasses(top)) {
                collectRedundantDefaultTransientAnnotations(cls, violations);
            }
        }
        if (!violations.isEmpty()) {
            fail("Fields whose declared type is transient by default should not carry @RewindTransient:\n"
                    + String.join("\n", violations));
        }
    }

    private static void collectEngineReferenceViolations(Class<?> cls, List<String> violations) {
        for (Field field : cls.getDeclaredFields()) {
            int mods = field.getModifiers();
            if (Modifier.isStatic(mods)) {
                continue;
            }
            if (Modifier.isTransient(mods)) {
                continue;
            }
            if (field.isSynthetic()) {
                continue;
            }
            if (field.isAnnotationPresent(RewindTransient.class)
                    || field.isAnnotationPresent(RewindDeferred.class)) {
                continue;
            }
            if (schemaPolicyFor(cls, field) != RewindFieldPolicy.UNSUPPORTED) {
                continue;
            }
            if (!EngineRefTypes.isEngineRef(field.getType())) {
                continue;
            }
            violations.add(cls.getName() + "#" + field.getName()
                    + " : " + field.getType().getName());
        }
    }

    private static RewindFieldPolicy schemaPolicyFor(Class<?> cls, Field field) {
        RewindClassSchema schema = RewindSchemaRegistry.schemaFor(cls);
        return schema.fields().stream()
                .filter(plan -> plan.field().equals(field))
                .findFirst()
                .orElseThrow()
                .policy();
    }

    private static void collectRedundantDefaultTransientAnnotations(Class<?> cls, List<String> violations) {
        for (Field field : cls.getDeclaredFields()) {
            if (!field.isAnnotationPresent(RewindTransient.class)) {
                continue;
            }
            if (DEFAULT_TRANSIENT_TYPES.contains(field.getType())) {
                violations.add(cls.getName() + "#" + field.getName()
                        + " : " + field.getType().getName());
            }
        }
    }
}
