package com.openggf.game.rewind.schema;

import com.openggf.game.rewind.FieldKey;
import com.openggf.game.rewind.RewindDeferred;
import com.openggf.game.rewind.RewindTransient;
import com.openggf.game.sonic1.objects.Sonic1SwingingPlatformObjectInstance;
import com.openggf.game.sonic2.objects.badniks.GrabberBadnikInstance;
import com.openggf.game.sonic2.objects.FallingPillarObjectInstance;
import com.openggf.game.sonic2.objects.FlipperObjectInstance;
import com.openggf.game.sonic2.objects.LauncherBallObjectInstance;
import com.openggf.game.sonic2.objects.LauncherSpringObjectInstance;
import com.openggf.game.sonic2.objects.MCZRotPformsObjectInstance;
import com.openggf.game.sonic2.objects.OOZLauncherObjectInstance;
import com.openggf.game.sonic2.objects.SidewaysPformObjectInstance;
import com.openggf.game.sonic2.objects.SpiralObjectInstance;
import com.openggf.game.sonic2.objects.SwingingPlatformObjectInstance;
import com.openggf.game.sonic2.objects.TornadoObjectInstance;
import com.openggf.game.sonic3k.objects.ClamerObjectInstance;
import com.openggf.game.sonic3k.objects.Cnz2CutsceneButtonInstance;
import com.openggf.game.sonic3k.objects.CnzCannonInstance;
import com.openggf.game.sonic3k.objects.CnzCylinderInstance;
import com.openggf.game.sonic3k.objects.CnzWaterLevelCorkFloorInstance;
import com.openggf.game.sonic3k.objects.CutsceneKnucklesCnz2AInstance;
import com.openggf.game.sonic3k.objects.CutsceneKnucklesMhz1Instance;
import com.openggf.game.sonic3k.objects.CutsceneKnucklesMhz1PeerInstance;
import com.openggf.game.sonic3k.objects.IczFreezerObjectInstance;
import com.openggf.game.sonic3k.objects.MGZPulleyObjectInstance;
import com.openggf.game.sonic3k.objects.Mhz1CutsceneButtonInstance;
import com.openggf.game.sonic3k.objects.MhzMushroomParachuteObjectInstance;
import com.openggf.game.sonic3k.objects.MhzStickyVineObjectInstance;
import com.openggf.game.sonic3k.objects.Sonic3kMonitorObjectInstance;
import com.openggf.game.sonic3k.objects.bosses.IczEndBossInstance;
import com.openggf.game.sonic3k.objects.badniks.DragonflyBadnikInstance.LinkedBodyChild;
import com.openggf.game.sonic3k.objects.badniks.DragonflyBadnikInstance.WingChild;
import com.openggf.game.sonic3k.objects.badniks.SpikerBadnikInstance;
import com.openggf.game.sonic3k.objects.badniks.TurboSpikerBadnikInstance;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestRewindSchemaRegistry {
    private static final String MHZ2_LIFT_CHILD_CLASS =
            "com.openggf.game.sonic3k.objects.CutsceneKnucklesMhz2Instance$Mhz2KnucklesLiftChild";
    private static final String MADMOLE_SIDE_DRILL_CLASS =
            "com.openggf.game.sonic3k.objects.badniks.MadmoleBadnikInstance$SideDrillChild";
    private static final String SPIKER_SIDE_LAUNCHER_CLASS =
            "com.openggf.game.sonic3k.objects.badniks.SpikerBadnikInstance$SpikerSideLauncherChild";
    private static final String TURBO_SPIKER_SHELL_CLASS =
            "com.openggf.game.sonic3k.objects.badniks.TurboSpikerBadnikInstance$TurboSpikerShellChild";
    private static final String TURBO_SPIKER_TRAIL_EMITTER_CLASS =
            "com.openggf.game.sonic3k.objects.badniks.TurboSpikerBadnikInstance$TurboSpikerTrailEmitter";
    private static final String TURBO_SPIKER_WATERFALL_OVERLAY_CLASS =
            "com.openggf.game.sonic3k.objects.badniks.TurboSpikerBadnikInstance$TurboSpikerWaterfallOverlayChild";

    @AfterEach
    void clearRegistry() {
        RewindSchemaRegistry.clearForTest();
    }

    @Test
    void sameClassReturnsSameSchemaAndId() {
        RewindClassSchema first = RewindSchemaRegistry.schemaFor(PolicyFixture.class);
        RewindClassSchema second = RewindSchemaRegistry.schemaFor(PolicyFixture.class);

        assertSame(first, second);
        assertEquals(first.schemaId(), second.schemaId());
    }

    @Test
    void clearForTestRestartsSchemaIds() {
        RewindClassSchema first = RewindSchemaRegistry.schemaFor(PolicyFixture.class);

        RewindSchemaRegistry.clearForTest();
        RewindClassSchema afterClear = RewindSchemaRegistry.schemaFor(PolicyFixture.class);

        assertEquals(1, first.schemaId());
        assertEquals(1, afterClear.schemaId());
    }

    @Test
    void fieldsAreOrderedSuperclassFirstThenDeclaredNameAndType() {
        RewindClassSchema schema = RewindSchemaRegistry.schemaFor(ChildFixture.class);

        assertEquals(List.of(
                        new FieldKey(ParentFixture.class.getName(), "alphaParent"),
                        new FieldKey(ParentFixture.class.getName(), "zetaParent"),
                        new FieldKey(ChildFixture.class.getName(), "alphaChild"),
                        new FieldKey(ChildFixture.class.getName(), "betaChild"),
                        new FieldKey(ChildFixture.class.getName(), "zetaChild")),
                schema.capturedFields().stream().map(RewindFieldPlan::key).toList());
    }

    @Test
    void classifiesCapturedStructuralTransientAndDeferredFields() {
        RewindClassSchema schema = RewindSchemaRegistry.schemaFor(PolicyFixture.class);

        assertPolicy(schema, "capturedInt", RewindFieldPolicy.CAPTURED);
        assertPolicy(schema, "finalStructuralInt", RewindFieldPolicy.STRUCTURAL);
        assertPolicy(schema, "staticInt", RewindFieldPolicy.TRANSIENT);
        assertPolicy(schema, "javaTransientInt", RewindFieldPolicy.TRANSIENT);
        assertPolicy(schema, "rewindTransientInt", RewindFieldPolicy.TRANSIENT);
        assertPolicy(schema, "deferredInt", RewindFieldPolicy.DEFERRED);

        assertEquals(List.of(new FieldKey(PolicyFixture.class.getName(), "capturedInt")),
                schema.capturedFields().stream().map(RewindFieldPlan::key).toList());
        assertTrue(schema.capturedFields().stream().allMatch(RewindFieldPlan::captured));
    }

    @Test
    void unsupportedMutableObjectFieldAppearsInUnsupportedFields() {
        RewindClassSchema schema = RewindSchemaRegistry.schemaFor(UnsupportedFixture.class);

        assertEquals(List.of(new FieldKey(UnsupportedFixture.class.getName(), "mutableObject")),
                schema.unsupportedFields().stream().map(RewindFieldPlan::key).toList());
        assertPolicy(schema, "mutableObject", RewindFieldPolicy.UNSUPPORTED);
    }

    @Test
    void capturedFieldsAreMadeAccessible() {
        RewindClassSchema schema = RewindSchemaRegistry.schemaFor(PrivateFieldFixture.class);
        PrivateFieldFixture fixture = new PrivateFieldFixture();

        assertTrue(schema.capturedFields().getFirst().field().canAccess(fixture));
    }

    @Test
    void defaultObjectSubclassSchemaCapturesDeferredPlayerReferences() {
        RewindClassSchema cannonSchema = RewindSchemaRegistry.defaultObjectSubclassSchemaFor(CnzCannonInstance.class);
        RewindClassSchema cylinderSchema = RewindSchemaRegistry.defaultObjectSubclassSchemaFor(CnzCylinderInstance.class);
        RewindClassSchema pulleySchema = RewindSchemaRegistry.defaultObjectSubclassSchemaFor(MGZPulleyObjectInstance.class);

        assertPolicy(cannonSchema, "capturedPlayer", RewindFieldPolicy.CAPTURED);
        assertPolicy(cannonSchema, "releasedPlayer", RewindFieldPolicy.CAPTURED);
        assertPolicy(cylinderSchema, "releasedJumpSolidSkipPlayer", RewindFieldPolicy.CAPTURED);
        assertPolicy(pulleySchema, "grabbedPlayers", RewindFieldPolicy.CAPTURED);
    }

    @Test
    void exactDefaultObjectPolicyOverridesFinalStructuralListFallback() {
        RewindClassSchema schema =
                RewindSchemaRegistry.defaultObjectSubclassSchemaFor(MCZRotPformsObjectInstance.class);

        assertPolicy(schema, "children", RewindFieldPolicy.CAPTURED);
        assertTrue(schema.unsupportedFields().isEmpty(),
                "MCZ rotating-platform compact schema must not fall back: "
                        + schema.unsupportedFields().stream().map(RewindFieldPlan::key).toList());
    }

    @Test
    void exactDefaultObjectPolicyCapturesSidewaysPlatformLink() {
        RewindClassSchema schema =
                RewindSchemaRegistry.defaultObjectSubclassSchemaFor(SidewaysPformObjectInstance.class);

        assertPolicy(schema, "linkedPlatform", RewindFieldPolicy.CAPTURED);
        assertTrue(schema.unsupportedFields().isEmpty(),
                "Sideways platform compact schema must capture the sibling link without fallback: "
                        + schema.unsupportedFields().stream().map(RewindFieldPlan::key).toList());
    }

    @Test
    void exactDefaultObjectPolicyCapturesFallingPillarChildLink() {
        RewindClassSchema schema =
                RewindSchemaRegistry.defaultObjectSubclassSchemaFor(FallingPillarObjectInstance.class);

        assertPolicy(schema, "childInstance", RewindFieldPolicy.CAPTURED);
        assertTrue(schema.unsupportedFields().isEmpty(),
                "Falling Pillar compact schema must capture the child link without fallback: "
                        + schema.unsupportedFields().stream().map(RewindFieldPlan::key).toList());
    }

    @Test
    void exactDefaultObjectPolicyCapturesSwingingPlatformDisplayChildLink() {
        RewindClassSchema schema =
                RewindSchemaRegistry.defaultObjectSubclassSchemaFor(SwingingPlatformObjectInstance.class);

        assertPolicy(schema, "displayChild", RewindFieldPolicy.CAPTURED);
        assertTrue(schema.unsupportedFields().isEmpty(),
                "Swinging Platform compact schema must capture the display child link without fallback: "
                        + schema.unsupportedFields().stream().map(RewindFieldPlan::key).toList());
    }

    @Test
    void exactDefaultObjectPolicyCapturesSonic1SwingingPlatformChainLinkArray() {
        RewindClassSchema schema =
                RewindSchemaRegistry.defaultObjectSubclassSchemaFor(Sonic1SwingingPlatformObjectInstance.class);

        assertPolicy(schema, "chainLinkChildren", RewindFieldPolicy.CAPTURED);
    }

    @Test
    void exactDefaultObjectPolicyCapturesTornadoThrusterFollowerLink() {
        RewindClassSchema schema =
                RewindSchemaRegistry.defaultObjectSubclassSchemaFor(TornadoObjectInstance.class);

        assertPolicy(schema, "thrusterFollowerChild", RewindFieldPolicy.CAPTURED);
        assertTrue(schema.unsupportedFields().isEmpty(),
                "Tornado compact schema must capture the thruster follower link without fallback: "
                        + schema.unsupportedFields().stream().map(RewindFieldPlan::key).toList());
    }

    @Test
    void exactDefaultObjectPolicyCapturesS3kMonitorContentsSlot() {
        RewindClassSchema schema =
                RewindSchemaRegistry.defaultObjectSubclassSchemaFor(Sonic3kMonitorObjectInstance.class);

        assertPolicy(schema, "monitorContentsSlot", RewindFieldPolicy.CAPTURED);
        assertTrue(schema.unsupportedFields().isEmpty(),
                "S3K monitor compact schema must capture the contents slot without fallback: "
                        + schema.unsupportedFields().stream().map(RewindFieldPlan::key).toList());
    }

    @Test
    void exactDefaultObjectPolicyCapturesCnz2CutsceneButtonFlashLink() {
        RewindClassSchema schema =
                RewindSchemaRegistry.defaultObjectSubclassSchemaFor(Cnz2CutsceneButtonInstance.class);

        assertPolicy(schema, "spawnedFlash", RewindFieldPolicy.CAPTURED);
        assertTrue(schema.unsupportedFields().isEmpty(),
                "CNZ2 cutscene button compact schema must capture the spawned flash link without fallback: "
                        + schema.unsupportedFields().stream().map(RewindFieldPlan::key).toList());
    }

    @Test
    void exactDefaultObjectPolicyCapturesCnzWaterLevelCorkFloorChildLink() {
        RewindClassSchema schema =
                RewindSchemaRegistry.defaultObjectSubclassSchemaFor(CnzWaterLevelCorkFloorInstance.class);

        assertPolicy(schema, "corkFloor", RewindFieldPolicy.CAPTURED);
        assertTrue(schema.unsupportedFields().isEmpty(),
                "CNZ water-level cork helper compact schema must capture the cork-floor link without fallback: "
                        + schema.unsupportedFields().stream().map(RewindFieldPlan::key).toList());
    }

    @Test
    void exactDefaultObjectPolicyCapturesCutsceneKnucklesCnz2BlockingWallLink() {
        RewindClassSchema schema =
                RewindSchemaRegistry.defaultObjectSubclassSchemaFor(CutsceneKnucklesCnz2AInstance.class);

        assertPolicy(schema, "blockingWall", RewindFieldPolicy.CAPTURED);
        assertTrue(schema.unsupportedFields().isEmpty(),
                "CNZ2 Knuckles cutscene compact schema must capture the blocking wall link without fallback: "
                        + schema.unsupportedFields().stream().map(RewindFieldPlan::key).toList());
    }

    @Test
    void exactDefaultObjectPolicyCapturesClamerSpringChildSlot() {
        RewindClassSchema schema =
                RewindSchemaRegistry.defaultObjectSubclassSchemaFor(ClamerObjectInstance.class);

        assertPolicy(schema, "springChildSlot", RewindFieldPolicy.CAPTURED);
        assertTrue(schema.unsupportedFields().isEmpty(),
                "Clamer compact schema must capture springChildSlot without fallback: "
                        + schema.unsupportedFields().stream().map(RewindFieldPlan::key).toList());
    }

    @Test
    void exactDefaultObjectPolicyCapturesDragonflyLinkedBodyFollowAnchor() {
        RewindClassSchema schema =
                RewindSchemaRegistry.defaultObjectSubclassSchemaFor(LinkedBodyChild.class);

        assertPolicy(schema, "parent", RewindFieldPolicy.CAPTURED);
        assertPolicy(schema, "followAnchor", RewindFieldPolicy.CAPTURED);
        assertTrue(schema.unsupportedFields().isEmpty(),
                "Dragonfly linked body compact schema must capture parent/follow-anchor links without fallback: "
                        + schema.unsupportedFields().stream().map(RewindFieldPlan::key).toList());
    }

    @Test
    void exactDefaultObjectPolicyCapturesDragonflyWingParent() {
        RewindClassSchema schema =
                RewindSchemaRegistry.defaultObjectSubclassSchemaFor(WingChild.class);

        assertPolicy(schema, "parent", RewindFieldPolicy.CAPTURED);
        assertTrue(schema.unsupportedFields().isEmpty(),
                "Dragonfly wing compact schema must capture parent link without fallback: "
                        + schema.unsupportedFields().stream().map(RewindFieldPlan::key).toList());
    }

    @Test
    void exactDefaultObjectPolicyCapturesSpikerChildSlotsAndSideLauncherParent()
            throws ClassNotFoundException {
        RewindClassSchema parentSchema =
                RewindSchemaRegistry.defaultObjectSubclassSchemaFor(SpikerBadnikInstance.class);
        RewindClassSchema launcherSchema =
                RewindSchemaRegistry.defaultObjectSubclassSchemaFor(Class.forName(SPIKER_SIDE_LAUNCHER_CLASS));

        assertPolicy(parentSchema, "leftLauncher", RewindFieldPolicy.CAPTURED);
        assertPolicy(parentSchema, "rightLauncher", RewindFieldPolicy.CAPTURED);
        assertPolicy(parentSchema, "topSpike", RewindFieldPolicy.CAPTURED);
        assertPolicy(launcherSchema, "parent", RewindFieldPolicy.CAPTURED);
        assertTrue(parentSchema.unsupportedFields().isEmpty(),
                "Spiker parent compact schema must capture child slots without fallback: "
                        + parentSchema.unsupportedFields().stream().map(RewindFieldPlan::key).toList());
        assertTrue(launcherSchema.unsupportedFields().isEmpty(),
                "Spiker side launcher compact schema must capture parent link without fallback: "
                        + launcherSchema.unsupportedFields().stream().map(RewindFieldPlan::key).toList());
    }

    @Test
    void exactDefaultObjectPolicyCapturesTurboSpikerGraphLinks()
            throws ClassNotFoundException {
        RewindClassSchema parentSchema =
                RewindSchemaRegistry.defaultObjectSubclassSchemaFor(TurboSpikerBadnikInstance.class);
        RewindClassSchema shellSchema =
                RewindSchemaRegistry.defaultObjectSubclassSchemaFor(Class.forName(TURBO_SPIKER_SHELL_CLASS));
        RewindClassSchema trailSchema =
                RewindSchemaRegistry.defaultObjectSubclassSchemaFor(Class.forName(TURBO_SPIKER_TRAIL_EMITTER_CLASS));
        RewindClassSchema overlaySchema =
                RewindSchemaRegistry.defaultObjectSubclassSchemaFor(Class.forName(TURBO_SPIKER_WATERFALL_OVERLAY_CLASS));

        assertPolicy(parentSchema, "shellChild", RewindFieldPolicy.CAPTURED);
        assertPolicy(shellSchema, "parent", RewindFieldPolicy.CAPTURED);
        assertPolicy(shellSchema, "trailEmitter", RewindFieldPolicy.CAPTURED);
        assertPolicy(trailSchema, "shell", RewindFieldPolicy.CAPTURED);
        assertPolicy(overlaySchema, "parent", RewindFieldPolicy.CAPTURED);
        assertTrue(parentSchema.unsupportedFields().isEmpty(),
                "Turbo Spiker parent compact schema must capture shell slot without fallback: "
                        + parentSchema.unsupportedFields().stream().map(RewindFieldPlan::key).toList());
        assertTrue(shellSchema.unsupportedFields().isEmpty(),
                "Turbo Spiker shell compact schema must capture parent/trail links without fallback: "
                        + shellSchema.unsupportedFields().stream().map(RewindFieldPlan::key).toList());
        assertTrue(trailSchema.unsupportedFields().isEmpty(),
                "Turbo Spiker trail compact schema must capture shell link without fallback: "
                        + trailSchema.unsupportedFields().stream().map(RewindFieldPlan::key).toList());
        assertTrue(overlaySchema.unsupportedFields().isEmpty(),
                "Turbo Spiker overlay compact schema must capture parent link without fallback: "
                        + overlaySchema.unsupportedFields().stream().map(RewindFieldPlan::key).toList());
    }

    @Test
    void exactDefaultObjectPolicyCapturesPlayerReferenceLinks() {
        RewindClassSchema grabberSchema =
                RewindSchemaRegistry.defaultObjectSubclassSchemaFor(GrabberBadnikInstance.class);
        RewindClassSchema launcherBallSchema =
                RewindSchemaRegistry.defaultObjectSubclassSchemaFor(LauncherBallObjectInstance.class);
        RewindClassSchema launcherSpringSchema =
                RewindSchemaRegistry.defaultObjectSubclassSchemaFor(LauncherSpringObjectInstance.class);
        RewindClassSchema oozLauncherSchema =
                RewindSchemaRegistry.defaultObjectSubclassSchemaFor(OOZLauncherObjectInstance.class);
        RewindClassSchema flipperSchema =
                RewindSchemaRegistry.defaultObjectSubclassSchemaFor(FlipperObjectInstance.class);
        RewindClassSchema spiralSchema =
                RewindSchemaRegistry.defaultObjectSubclassSchemaFor(SpiralObjectInstance.class);
        RewindClassSchema parachuteSchema =
                RewindSchemaRegistry.defaultObjectSubclassSchemaFor(MhzMushroomParachuteObjectInstance.class);
        RewindClassSchema stickyVineSchema =
                RewindSchemaRegistry.defaultObjectSubclassSchemaFor(MhzStickyVineObjectInstance.class);
        RewindClassSchema mhz2LiftSchema =
                RewindSchemaRegistry.defaultObjectSubclassSchemaFor(classForName(MHZ2_LIFT_CHILD_CLASS));
        RewindClassSchema sideDrillSchema =
                RewindSchemaRegistry.defaultObjectSubclassSchemaFor(classForName(MADMOLE_SIDE_DRILL_CLASS));
        RewindClassSchema iczFrozenBlockSchema =
                RewindSchemaRegistry.defaultObjectSubclassSchemaFor(
                        IczFreezerObjectInstance.FrozenPlayerBlock.class);

        assertPolicy(grabberSchema, "grabbedPlayer", RewindFieldPolicy.CAPTURED);
        assertPolicy(grabberSchema, "pendingGrabPlayer", RewindFieldPolicy.CAPTURED);
        assertPolicy(launcherBallSchema, "playerCooldowns", RewindFieldPolicy.CAPTURED);
        assertPolicy(launcherBallSchema, "playerStates", RewindFieldPolicy.CAPTURED);
        assertPolicy(launcherBallSchema, "playerVelocities", RewindFieldPolicy.CAPTURED);
        assertPolicy(launcherSpringSchema, "playerStates", RewindFieldPolicy.CAPTURED);
        assertPolicy(oozLauncherSchema, "playerStates", RewindFieldPolicy.CAPTURED);
        assertPolicy(flipperSchema, "launchCooldown", RewindFieldPolicy.CAPTURED);
        assertPolicy(flipperSchema, "lockedPlayerPrevSuppressed", RewindFieldPolicy.CAPTURED);
        assertPolicy(flipperSchema, "playerFlipperState", RewindFieldPolicy.CAPTURED);
        assertPolicy(spiralSchema, "cylinderAngles", RewindFieldPolicy.CAPTURED);
        assertPolicy(spiralSchema, "ridingPlayers", RewindFieldPolicy.CAPTURED);
        assertPolicy(parachuteSchema, "grabbedPlayer", RewindFieldPolicy.CAPTURED);
        assertPolicy(parachuteSchema, "nativeP2GrabbedPlayer", RewindFieldPolicy.CAPTURED);
        assertPolicy(stickyVineSchema, "capturedPlayer", RewindFieldPolicy.CAPTURED);
        assertPolicy(mhz2LiftSchema, "player", RewindFieldPolicy.CAPTURED);
        assertPolicy(sideDrillSchema, "capturedPlayer", RewindFieldPolicy.CAPTURED);
        assertPolicy(iczFrozenBlockSchema, "capturedPlayer", RewindFieldPolicy.CAPTURED);
        assertTrue(grabberSchema.unsupportedFields().isEmpty(),
                "Grabber compact schema must capture player refs without fallback: "
                        + grabberSchema.unsupportedFields().stream().map(RewindFieldPlan::key).toList());
        assertTrue(launcherBallSchema.unsupportedFields().isEmpty(),
                "Launcher ball compact schema must capture player refs without fallback: "
                        + launcherBallSchema.unsupportedFields().stream().map(RewindFieldPlan::key).toList());
        assertTrue(launcherSpringSchema.unsupportedFields().isEmpty(),
                "Launcher spring compact schema must capture player refs without fallback: "
                        + launcherSpringSchema.unsupportedFields().stream().map(RewindFieldPlan::key).toList());
        assertTrue(oozLauncherSchema.unsupportedFields().isEmpty(),
                "OOZ launcher compact schema must capture player refs without fallback: "
                        + oozLauncherSchema.unsupportedFields().stream().map(RewindFieldPlan::key).toList());
        assertTrue(flipperSchema.unsupportedFields().isEmpty(),
                "Flipper compact schema must capture player refs without fallback: "
                        + flipperSchema.unsupportedFields().stream().map(RewindFieldPlan::key).toList());
        assertTrue(spiralSchema.unsupportedFields().isEmpty(),
                "Spiral compact schema must capture player refs without fallback: "
                        + spiralSchema.unsupportedFields().stream().map(RewindFieldPlan::key).toList());
        assertTrue(parachuteSchema.unsupportedFields().isEmpty(),
                "MHZ mushroom parachute compact schema must capture player refs without fallback: "
                        + parachuteSchema.unsupportedFields().stream().map(RewindFieldPlan::key).toList());
        assertTrue(stickyVineSchema.unsupportedFields().isEmpty(),
                "MHZ sticky vine compact schema must capture player refs without fallback: "
                        + stickyVineSchema.unsupportedFields().stream().map(RewindFieldPlan::key).toList());
        assertTrue(mhz2LiftSchema.unsupportedFields().isEmpty(),
                "MHZ2 lift compact schema must capture player refs without fallback: "
                        + mhz2LiftSchema.unsupportedFields().stream().map(RewindFieldPlan::key).toList());
        assertTrue(sideDrillSchema.unsupportedFields().isEmpty(),
                "Madmole side drill compact schema must capture player refs without fallback: "
                        + sideDrillSchema.unsupportedFields().stream().map(RewindFieldPlan::key).toList());
        assertTrue(iczFrozenBlockSchema.unsupportedFields().isEmpty(),
                "ICZ frozen block compact schema must capture player refs without fallback: "
                        + iczFrozenBlockSchema.unsupportedFields().stream().map(RewindFieldPlan::key).toList());
    }

    @Test
    void exactDefaultObjectPolicyClassifiesIczFreezerGraphLinks() {
        RewindClassSchema freezerSchema =
                RewindSchemaRegistry.defaultObjectSubclassSchemaFor(IczFreezerObjectInstance.class);
        RewindClassSchema cloudSchema =
                RewindSchemaRegistry.defaultObjectSubclassSchemaFor(
                        IczFreezerObjectInstance.CaptureCloud.class);

        assertFalse(freezerSchema.fields().stream()
                        .anyMatch(field -> field.key().fieldName().equals("lastCaptureCloud")),
                "transient freezer diagnostic handle must be omitted from the compact schema");
        assertPolicy(cloudSchema, "frozenBlock", RewindFieldPolicy.CAPTURED);
        assertTrue(freezerSchema.unsupportedFields().isEmpty(),
                "ICZ freezer compact schema must classify the diagnostic cloud handle without fallback: "
                        + freezerSchema.unsupportedFields().stream().map(RewindFieldPlan::key).toList());
        assertTrue(cloudSchema.unsupportedFields().isEmpty(),
                "ICZ capture cloud compact schema must capture the frozen block link without fallback: "
                        + cloudSchema.unsupportedFields().stream().map(RewindFieldPlan::key).toList());
    }

    @Test
    void exactDefaultObjectPolicyCapturesMhz1CutsceneButtonKnucklesLinks() {
        RewindClassSchema buttonSchema =
                RewindSchemaRegistry.defaultObjectSubclassSchemaFor(Mhz1CutsceneButtonInstance.class);
        RewindClassSchema knucklesSchema =
                RewindSchemaRegistry.defaultObjectSubclassSchemaFor(CutsceneKnucklesMhz1Instance.class);
        RewindClassSchema peerSchema =
                RewindSchemaRegistry.defaultObjectSubclassSchemaFor(CutsceneKnucklesMhz1PeerInstance.class);

        assertPolicy(buttonSchema, "spawnedDoor", RewindFieldPolicy.CAPTURED);
        assertPolicy(buttonSchema, "spawnedKnuckles", RewindFieldPolicy.CAPTURED);
        assertPolicy(knucklesSchema, "parentButton", RewindFieldPolicy.CAPTURED);
        assertPolicy(peerSchema, "parent", RewindFieldPolicy.CAPTURED);
        assertTrue(buttonSchema.unsupportedFields().isEmpty(),
                "MHZ1 cutscene button compact schema must capture spawned children without fallback: "
                        + buttonSchema.unsupportedFields().stream().map(RewindFieldPlan::key).toList());
        assertTrue(knucklesSchema.unsupportedFields().isEmpty(),
                "MHZ1 cutscene Knuckles compact schema must capture the parent button without fallback: "
                        + knucklesSchema.unsupportedFields().stream().map(RewindFieldPlan::key).toList());
        assertTrue(peerSchema.unsupportedFields().isEmpty(),
                "MHZ1 cutscene peer compact schema must capture the parent Knuckles actor without fallback: "
                        + peerSchema.unsupportedFields().stream().map(RewindFieldPlan::key).toList());
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

    private static class ParentFixture {
        int zetaParent;
        int alphaParent;
    }

    private static class ChildFixture extends ParentFixture {
        int zetaChild;
        int betaChild;
        int alphaChild;
    }

    private static class PolicyFixture {
        static int staticInt;
        transient int javaTransientInt;
        int capturedInt;
        final int finalStructuralInt = 1;
        @RewindTransient(reason = "test")
        int rewindTransientInt;
        @RewindDeferred(reason = "test")
        int deferredInt;
    }

    private static class MutableObject {
        int value;
    }

    private static class UnsupportedFixture {
        MutableObject mutableObject = new MutableObject();
    }

    private static class PrivateFieldFixture {
        private int privateCapturedInt;
    }
}
