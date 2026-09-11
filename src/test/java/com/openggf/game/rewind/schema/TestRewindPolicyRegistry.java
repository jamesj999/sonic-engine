package com.openggf.game.rewind.schema;

import com.openggf.game.rewind.FieldKey;
import com.openggf.game.rewind.RewindTransient;
import com.openggf.game.GameModule;
import com.openggf.game.InstaShieldHandle;
import com.openggf.game.PowerUpObject;
import com.openggf.game.PowerUpSpawner;
import com.openggf.configuration.SonicConfigurationService;
import com.openggf.game.AbstractLevelEventManager;
import com.openggf.game.sonic2.objects.TornadoObjectInstance;
import com.openggf.game.sonic2.objects.badniks.GrabberBadnikInstance;
import com.openggf.game.sonic3k.objects.CnzCannonInstance;
import com.openggf.game.sonic3k.objects.CnzCylinderInstance;
import com.openggf.game.sonic3k.objects.CnzLightsFlashChildInstance;
import com.openggf.game.sonic3k.objects.IczFreezerObjectInstance;
import com.openggf.game.sonic3k.objects.LbzMinibossInstance;
import com.openggf.game.sonic3k.objects.MhzMushroomParachuteObjectInstance;
import com.openggf.game.sonic3k.objects.MhzStickyVineObjectInstance;
import com.openggf.game.sonic3k.objects.bosses.CnzEndBossInstance;
import com.openggf.game.sonic3k.objects.bosses.IczEndBossInstance;
import com.openggf.game.sonic3k.objects.badniks.SnaleBlasterBadnikInstance;
import com.openggf.graphics.GraphicsManager;
import com.openggf.level.PatternDesc;
import com.openggf.level.objects.ObjectRenderManager;
import com.openggf.level.objects.ObjectServices;
import com.openggf.level.objects.SolidObjectParams;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.level.render.SpriteMappingPiece;
import com.openggf.level.render.SpritePieceRenderer;
import com.openggf.sprites.animation.SpriteAnimationSet;
import com.openggf.sprites.animation.SpriteAnimationProfile;
import com.openggf.sprites.render.PlayerSpriteRenderer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.BitSet;
import java.util.function.BooleanSupplier;
import java.util.function.IntSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestRewindPolicyRegistry {
    private static final String MHZ2_LIFT_CHILD_CLASS =
            "com.openggf.game.sonic3k.objects.CutsceneKnucklesMhz2Instance$Mhz2KnucklesLiftChild";
    private static final String MADMOLE_SIDE_DRILL_CLASS =
            "com.openggf.game.sonic3k.objects.badniks.MadmoleBadnikInstance$SideDrillChild";

    @AfterEach
    void clearRegistry() {
        RewindSchemaRegistry.clearForTest();
    }

    @Test
    void exactFieldPolicyTakesPrecedenceOverTypeAndPackageRules() {
        RewindPolicyRegistry.registerPackagePolicy(
                ExactFieldFixture.class.getPackageName(),
                RewindFieldPolicy.TRANSIENT);
        RewindPolicyRegistry.registerDeclaredTypePolicy(
                UnsupportedValue.class,
                RewindFieldPolicy.STRUCTURAL);
        RewindPolicyRegistry.registerFieldPolicy(
                new FieldKey(ExactFieldFixture.class.getName(), "value"),
                RewindFieldPolicy.DEFERRED);

        RewindClassSchema schema = RewindSchemaRegistry.schemaFor(ExactFieldFixture.class);

        assertPolicy(schema, "value", RewindFieldPolicy.DEFERRED);
    }

    @Test
    void declaredTypePolicyCanMarkUnsupportedMutableTypeAsStructural() {
        RewindPolicyRegistry.registerDeclaredTypePolicy(
                UnsupportedValue.class,
                RewindFieldPolicy.STRUCTURAL);

        RewindClassSchema schema = RewindSchemaRegistry.schemaFor(ExactFieldFixture.class);

        assertPolicy(schema, "value", RewindFieldPolicy.STRUCTURAL);
        assertTrue(schema.unsupportedFields().isEmpty());
    }

    @Test
    void assignableTypePolicyAppliesToSubclasses() {
        RewindPolicyRegistry.registerAssignableTypePolicy(
                BaseUnsupportedValue.class,
                RewindFieldPolicy.TRANSIENT);

        RewindClassSchema schema = RewindSchemaRegistry.schemaFor(AssignableFixture.class);

        assertPolicy(schema, "value", RewindFieldPolicy.TRANSIENT);
    }

    @Test
    void packagePolicyAppliesToDeclaredTypePackage() {
        RewindPolicyRegistry.registerPackagePolicy(
                UnsupportedValue.class.getPackageName(),
                RewindFieldPolicy.DEFERRED);

        RewindClassSchema schema = RewindSchemaRegistry.schemaFor(ExactFieldFixture.class);

        assertPolicy(schema, "value", RewindFieldPolicy.DEFERRED);
    }

    @Test
    void annotationsRemainStrongerThanRegistryRules() {
        RewindPolicyRegistry.registerDeclaredTypePolicy(
                int.class,
                RewindFieldPolicy.CAPTURED);

        RewindClassSchema schema = RewindSchemaRegistry.schemaFor(AnnotatedFixture.class);

        assertPolicy(schema, "value", RewindFieldPolicy.TRANSIENT);
    }

    @Test
    void forcedCapturedPolicyRequiresAnAvailableCodec() {
        RewindPolicyRegistry.registerDeclaredTypePolicy(
                UnsupportedValue.class,
                RewindFieldPolicy.CAPTURED);

        RewindClassSchema schema = RewindSchemaRegistry.schemaFor(ExactFieldFixture.class);

        assertPolicy(schema, "value", RewindFieldPolicy.UNSUPPORTED);
        assertEquals(1, schema.unsupportedFields().size());
    }

    @Test
    void forcedCapturedPolicyUsesDefaultCodecWhenAvailable() {
        RewindPolicyRegistry.registerDeclaredTypePolicy(
                BitSet.class,
                RewindFieldPolicy.CAPTURED);

        RewindClassSchema schema = RewindSchemaRegistry.schemaFor(SupportedCapturedFixture.class);

        assertPolicy(schema, "value", RewindFieldPolicy.CAPTURED);
        assertTrue(schema.unsupportedFields().isEmpty());
    }

    @Test
    void defaultExactPoliciesDoNotRetainRemovedIczBossEmitterField() {
        assertFalse(DefaultObjectRewindPolicies.exactFieldPoliciesForAudit().containsKey(
                new FieldKey(IczEndBossInstance.class.getName(), "bossSnowdustEmitter")));
    }

    @Test
    void runtimeRendererAndServiceTypesAreTransientByDefaultWithoutAnnotations() {
        RewindClassSchema schema = RewindSchemaRegistry.schemaFor(DefaultRuntimePolicyFixture.class);

        assertPolicy(schema, "graphics", RewindFieldPolicy.TRANSIENT);
        assertPolicy(schema, "gameModule", RewindFieldPolicy.TRANSIENT);
        assertPolicy(schema, "objectRenderManager", RewindFieldPolicy.TRANSIENT);
        assertPolicy(schema, "objectServices", RewindFieldPolicy.TRANSIENT);
        assertPolicy(schema, "patternDesc", RewindFieldPolicy.TRANSIENT);
        assertPolicy(schema, "patternRenderer", RewindFieldPolicy.TRANSIENT);
        assertPolicy(schema, "playerRenderer", RewindFieldPolicy.TRANSIENT);
        assertPolicy(schema, "configService", RewindFieldPolicy.TRANSIENT);
        assertPolicy(schema, "events", RewindFieldPolicy.TRANSIENT);
        assertPolicy(schema, "deleteSupplier", RewindFieldPolicy.TRANSIENT);
        assertPolicy(schema, "scrollSupplier", RewindFieldPolicy.TRANSIENT);
        assertPolicy(schema, "spriteAnimationSet", RewindFieldPolicy.TRANSIENT);
        assertPolicy(schema, "spriteMappingPiece", RewindFieldPolicy.TRANSIENT);
        assertPolicy(schema, "spritePieceRenderer", RewindFieldPolicy.TRANSIENT);
        assertTrue(schema.unsupportedFields().isEmpty());
    }

    @Test
    void structuralSolidObjectParamsAreStructuralByDefaultWithoutAnnotations() {
        RewindClassSchema schema = RewindSchemaRegistry.schemaFor(DefaultStructuralPolicyFixture.class);

        assertPolicy(schema, "solidObjectParams", RewindFieldPolicy.STRUCTURAL);
        assertTrue(schema.unsupportedFields().isEmpty());
    }

    @Test
    void playerPowerUpHandlesAreTransientByDefaultWithoutAnnotations() {
        RewindClassSchema schema = RewindSchemaRegistry.schemaFor(DefaultPlayerHandlePolicyFixture.class);

        assertPolicy(schema, "animationProfile", RewindFieldPolicy.TRANSIENT);
        assertPolicy(schema, "powerUpSpawner", RewindFieldPolicy.TRANSIENT);
        assertPolicy(schema, "shieldObject", RewindFieldPolicy.TRANSIENT);
        assertPolicy(schema, "instaShieldObject", RewindFieldPolicy.TRANSIENT);
        assertPolicy(schema, "invincibilityObject", RewindFieldPolicy.TRANSIENT);
        assertTrue(schema.unsupportedFields().isEmpty());
    }

    @Test
    void defaultObjectPolicyTreatsSnaleBlasterCoverCacheAsTransient() throws NoSuchFieldException {
        Field cover = SnaleBlasterBadnikInstance.class.getDeclaredField("cover");

        assertEquals(RewindFieldPolicy.TRANSIENT, RewindPolicyRegistry.policyForAudit(cover).orElse(null));
    }

    @Test
    void defaultObjectPolicyCapturesLbzMinibossKnucklesParentReference() throws NoSuchFieldException {
        Field parent = LbzMinibossInstance.class.getDeclaredField("knucklesFightParent");

        assertEquals(RewindFieldPolicy.CAPTURED, RewindPolicyRegistry.policyForAudit(parent).orElse(null));
    }

    @Test
    void defaultObjectPolicyCapturesCnzEndBossCannonReference() throws NoSuchFieldException {
        Field endCannon = CnzEndBossInstance.class.getDeclaredField("endCannon");

        assertEquals(RewindFieldPolicy.CAPTURED, RewindPolicyRegistry.policyForAudit(endCannon).orElse(null));
    }

    @Test
    void defaultObjectPolicyCapturesCnzEndBossMagnetReference() throws NoSuchFieldException {
        Field magnet = CnzEndBossInstance.class.getDeclaredField("magnetChild");

        assertEquals(RewindFieldPolicy.CAPTURED, RewindPolicyRegistry.policyForAudit(magnet).orElse(null));
    }

    @Test
    void defaultObjectPolicyCentralizesCnzDerivedAndStructuralLinks() throws NoSuchFieldException {
        String bossPackage = "com.openggf.game.sonic3k.objects.bosses.";
        for (String className : new String[] {
                "CnzEndBossArmChild",
                "CnzEndBossFieldChild",
                "CnzEndBossMagnetChild",
                "CnzEndBossRobotnikShipChild"
        }) {
            assertEquals(RewindFieldPolicy.TRANSIENT,
                    RewindPolicyRegistry.policyForAudit(
                            classForName(bossPackage + className).getDeclaredField("boss")).orElse(null),
                    className + ".boss");
        }
        for (String className : new String[] {
                "CnzEndBossExplosionControllerChild",
                "CnzEndBossRobotnikFlameChild",
                "CnzEndBossRobotnikHeadChild"
        }) {
            assertEquals(RewindFieldPolicy.TRANSIENT,
                    RewindPolicyRegistry.policyForAudit(
                            classForName(bossPackage + className).getDeclaredField("ship")).orElse(null),
                    className + ".ship");
        }
        assertEquals(RewindFieldPolicy.TRANSIENT,
                RewindPolicyRegistry.policyForAudit(
                        CnzLightsFlashChildInstance.class.getDeclaredField("owner")).orElse(null),
                "CnzLightsFlashChildInstance.owner");
        assertEquals(RewindFieldPolicy.TRANSIENT,
                RewindPolicyRegistry.policyForAudit(
                        classForName(bossPackage + "CnzEndBossFieldChild")
                                .getDeclaredField("xOffset")).orElse(null),
                "CnzEndBossFieldChild.xOffset");
    }

    @Test
    void defaultObjectPolicyCapturesCnzTraversalReleasedPlayerReferences() throws NoSuchFieldException {
        Field cannonReleasedPlayer = CnzCannonInstance.class.getDeclaredField("releasedPlayer");
        Field cylinderReleasedPlayer = CnzCylinderInstance.class.getDeclaredField("releasedJumpSolidSkipPlayer");

        assertEquals(RewindFieldPolicy.CAPTURED,
                RewindPolicyRegistry.policyForAudit(cannonReleasedPlayer).orElse(null));
        assertEquals(RewindFieldPolicy.CAPTURED,
                RewindPolicyRegistry.policyForAudit(cylinderReleasedPlayer).orElse(null));
    }

    @Test
    void defaultObjectPolicyCapturesTornadoThrusterFollowerReference() throws NoSuchFieldException {
        Field thrusterFollowerChild = TornadoObjectInstance.class.getDeclaredField("thrusterFollowerChild");

        assertEquals(RewindFieldPolicy.CAPTURED,
                RewindPolicyRegistry.policyForAudit(thrusterFollowerChild).orElse(null));
    }

    @Test
    void defaultObjectPolicyCapturesPlayerReferenceFields() throws NoSuchFieldException {
        Field grabberPendingPlayer = GrabberBadnikInstance.class.getDeclaredField("pendingGrabPlayer");
        Field parachuteGrabbedPlayer = MhzMushroomParachuteObjectInstance.class.getDeclaredField("grabbedPlayer");
        Field parachuteNativeP2Player =
                MhzMushroomParachuteObjectInstance.class.getDeclaredField("nativeP2GrabbedPlayer");
        Field stickyVineCapturedPlayer = MhzStickyVineObjectInstance.class.getDeclaredField("capturedPlayer");
        Field mhz2LiftPlayer = classForName(MHZ2_LIFT_CHILD_CLASS).getDeclaredField("player");
        Field sideDrillCapturedPlayer = classForName(MADMOLE_SIDE_DRILL_CLASS).getDeclaredField("capturedPlayer");
        Field iczFrozenBlockCapturedPlayer =
                IczFreezerObjectInstance.FrozenPlayerBlock.class.getDeclaredField("capturedPlayer");

        assertEquals(RewindFieldPolicy.CAPTURED,
                RewindPolicyRegistry.policyForAudit(grabberPendingPlayer).orElse(null));
        assertEquals(RewindFieldPolicy.CAPTURED,
                RewindPolicyRegistry.policyForAudit(parachuteGrabbedPlayer).orElse(null));
        assertEquals(RewindFieldPolicy.CAPTURED,
                RewindPolicyRegistry.policyForAudit(parachuteNativeP2Player).orElse(null));
        assertEquals(RewindFieldPolicy.CAPTURED,
                RewindPolicyRegistry.policyForAudit(stickyVineCapturedPlayer).orElse(null));
        assertEquals(RewindFieldPolicy.CAPTURED,
                RewindPolicyRegistry.policyForAudit(mhz2LiftPlayer).orElse(null));
        assertEquals(RewindFieldPolicy.CAPTURED,
                RewindPolicyRegistry.policyForAudit(sideDrillCapturedPlayer).orElse(null));
        assertEquals(RewindFieldPolicy.CAPTURED,
                RewindPolicyRegistry.policyForAudit(iczFrozenBlockCapturedPlayer).orElse(null));
    }

    private static void assertPolicy(RewindClassSchema schema, String fieldName, RewindFieldPolicy policy) {
        RewindFieldPlan plan = schema.fields().stream()
                .filter(field -> field.key().fieldName().equals(fieldName))
                .findFirst()
                .orElseThrow();
        assertEquals(policy, plan.policy());
    }

    private static Class<?> classForName(String className) {
        try {
            return Class.forName(className);
        } catch (ClassNotFoundException e) {
            throw new AssertionError(e);
        }
    }

    private static class BaseUnsupportedValue {
        int state;
    }

    private static final class UnsupportedValue extends BaseUnsupportedValue {
    }

    private static final class ExactFieldFixture {
        UnsupportedValue value = new UnsupportedValue();
    }

    private static final class AssignableFixture {
        UnsupportedValue value = new UnsupportedValue();
    }

    private static final class AnnotatedFixture {
        @RewindTransient(reason = "test")
        int value;
    }

    private static final class SupportedCapturedFixture {
        BitSet value = new BitSet();
    }

    private static final class DefaultRuntimePolicyFixture {
        GraphicsManager graphics;
        GameModule gameModule;
        ObjectRenderManager objectRenderManager;
        ObjectServices objectServices;
        PatternDesc patternDesc;
        PatternSpriteRenderer patternRenderer;
        PlayerSpriteRenderer playerRenderer;
        SonicConfigurationService configService;
        AbstractLevelEventManager events;
        BooleanSupplier deleteSupplier;
        IntSupplier scrollSupplier;
        SpriteAnimationSet spriteAnimationSet;
        SpriteMappingPiece spriteMappingPiece;
        SpritePieceRenderer spritePieceRenderer;
    }

    private static final class DefaultStructuralPolicyFixture {
        final SolidObjectParams solidObjectParams = new SolidObjectParams(16, 8, 9);
    }

    private static final class DefaultPlayerHandlePolicyFixture {
        SpriteAnimationProfile animationProfile;
        PowerUpSpawner powerUpSpawner;
        PowerUpObject shieldObject;
        InstaShieldHandle instaShieldObject;
        PowerUpObject invincibilityObject;
    }
}
