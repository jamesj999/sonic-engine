package com.openggf.tools;

import com.openggf.tools.ObjectScaffoldTool.Game;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Focused tests for {@link ObjectScaffoldTool} (Agent Workflow Support Option 4).
 * <p>
 * JUnit 5 / Jupiter only. No ROM and no OpenGL — the generator is a pure function of
 * {@code (game, className, objectId, isBadnik)}, so all assertions run against returned
 * Strings with zero file I/O.
 */
class TestObjectScaffoldTool {

    // ------------------------------------------------------------------
    // Package + base class selection.
    // ------------------------------------------------------------------

    @Test
    void s3kBadnikUsesBadniksPackageAndS3kBadnikBase() {
        String src = ObjectScaffoldTool.generateInstance(Game.S3K, "MhzSpikySpringerObjectInstance", "0x8A", true);
        // S3K badniks live next to (package-private) AbstractS3kBadnikInstance, NOT in the generic
        // objects package, and extend the S3K-specific base that carries S3K badnik conventions.
        assertTrue(src.contains("package com.openggf.game.sonic3k.objects.badniks;"),
                "S3K badnik must live in the sonic3k objects.badniks package");
        assertTrue(src.contains("extends AbstractS3kBadnikInstance"),
                "S3K badnik scaffold must extend the S3K-specific badnik base");
        assertFalse(src.contains("import com.openggf.level.objects.AbstractBadnikInstance;"),
                "S3K badnik must not import/extend the generic badnik base");
        // Subclass implements movement + animation; the base supplies collision size + rendering.
        assertTrue(src.contains("updateMovement"), "S3K badnik must override updateMovement");
        assertTrue(src.contains("updateAnimation"), "S3K badnik must override updateAnimation");
    }

    @Test
    void nonBadnikS3kUsesGenericObjectPackageAndBase() {
        String src = ObjectScaffoldTool.generateInstance(Game.S3K, "MhzGizmoObjectInstance", "0x40", false);
        assertTrue(src.contains("package com.openggf.game.sonic3k.objects;"),
                "S3K non-badnik must live in the generic sonic3k objects package");
        assertTrue(src.contains("extends AbstractObjectInstance"),
                "S3K non-badnik must extend AbstractObjectInstance");
    }

    @Test
    void s1AndS2BadniksUseGenericBadnikBase() {
        String s1 = ObjectScaffoldTool.generateInstance(Game.S1, "FooBadnikInstance", "0x10", true);
        assertTrue(s1.contains("package com.openggf.game.sonic1.objects;"), "S1 badnik package");
        assertTrue(s1.contains("extends AbstractBadnikInstance"), "S1 badnik uses the generic base");
        assertTrue(s1.contains("import com.openggf.level.objects.AbstractBadnikInstance;"),
                "S1 badnik imports the generic base");
        String s2 = ObjectScaffoldTool.generateInstance(Game.S2, "FooBadnikInstance", "0x10", true);
        assertTrue(s2.contains("package com.openggf.game.sonic2.objects;"), "S2 badnik package");
        assertTrue(s2.contains("extends AbstractBadnikInstance"), "S2 badnik uses the generic base");
    }

    @Test
    void nonBadnikUsesAbstractObjectInstanceBase() {
        String src = ObjectScaffoldTool.generateInstance(Game.S2, "ExampleObjectInstance", "0x40", false);
        assertTrue(src.contains("extends AbstractObjectInstance"),
                "non-badnik scaffold must extend AbstractObjectInstance");
        assertFalse(src.contains("extends AbstractBadnikInstance"),
                "non-badnik scaffold must not extend the badnik base");
    }

    @Test
    void generatedObjectClockParametersUseVIntRunCountTerminology() {
        String nonBadnik = ObjectScaffoldTool.generateInstance(
                Game.S2, "ClockedObjectInstance", "0x40", false);
        assertTrue(nonBadnik.contains("public void update(int vIntRunCount, PlayableEntity player)"));

        for (Game game : new Game[] {Game.S1, Game.S2, Game.S3K}) {
            String badnik = ObjectScaffoldTool.generateInstance(
                    game, "ClockedBadnikInstance", "0x40", true);
            assertTrue(badnik.contains("protected void updateMovement(int vIntRunCount, PlayableEntity player)"),
                    game + " badnik movement hook must expose the V-int run count");
            assertTrue(badnik.contains("protected void updateAnimation(int vIntRunCount)"),
                    game + " badnik animation hook must expose the V-int run count");
        }
    }

    @Test
    void s1AndS2InstancesUseCorrectPackages() {
        assertTrue(ObjectScaffoldTool.generateInstance(Game.S1, "FooObjectInstance", "0x10", false)
                        .contains("package com.openggf.game.sonic1.objects;"),
                "S1 instance package");
        assertTrue(ObjectScaffoldTool.generateInstance(Game.S2, "FooObjectInstance", "0x10", false)
                        .contains("package com.openggf.game.sonic2.objects;"),
                "S2 instance package");
    }

    // ------------------------------------------------------------------
    // Guard-friendly defaults: the constructor must avoid forbidden patterns.
    // ------------------------------------------------------------------

    @Test
    void constructorDoesNotCallServicesGetInstanceOrAddDynamicObject() {
        String src = ObjectScaffoldTool.generateInstance(Game.S3K, "GuardCheckObjectInstance", "0x55", false);

        // Isolate the constructor body for precise assertions.
        int ctorStart = src.indexOf("public GuardCheckObjectInstance(ObjectSpawn spawn) {");
        assertTrue(ctorStart >= 0, "scaffold should declare the (ObjectSpawn) constructor");
        int ctorEnd = src.indexOf("    }", ctorStart);
        assertTrue(ctorEnd > ctorStart, "constructor body should be delimited");
        String ctor = src.substring(ctorStart, ctorEnd);

        assertFalse(ctor.contains("services()"),
                "constructor must NOT call services() (TestNoServicesInObjectConstructors)");
        assertFalse(ctor.contains("getInstance()"),
                "constructor must NOT use singleton getInstance() access");
        assertFalse(ctor.contains("addDynamicObject"),
                "constructor must NOT insert dynamic objects directly");
        assertFalse(ctor.contains("setDestroyed(true)"),
                "constructor must NOT call setDestroyed(true)");
    }

    @Test
    void instanceNeverUsesGetInstanceSingletonAnywhere() {
        String src = ObjectScaffoldTool.generateInstance(Game.S3K, "NoSingletonObjectInstance", "0x33", true);
        assertFalse(src.contains("getInstance()"),
                "scaffold must never reference singleton getInstance() (TestObjectServicesMigrationGuard)");
    }

    @Test
    void instanceDoesNotEmitActiveSetDestroyedOrDirectAddDynamicObjectCalls() {
        String src = ObjectScaffoldTool.generateInstance(Game.S1, "LifecycleObjectInstance", "0x22", true);
        // No active setDestroyed(true) call (only documented as a thing to avoid).
        assertFalse(src.contains("setDestroyed(true);"),
                "scaffold must not emit an active setDestroyed(true) statement");
        // No active addDynamicObject(...) statement — child spawning routes through spawnChild/spawnFreeChild.
        assertFalse(src.contains(".addDynamicObject("),
                "scaffold must not emit an active addDynamicObject(...) call");
    }

    // ------------------------------------------------------------------
    // Required references: spawnChild/spawnFreeChild, lifecycle, contracts, center coords.
    // ------------------------------------------------------------------

    @Test
    void instanceShowsSpawnChildAndSpawnFreeChildUsage() {
        String src = ObjectScaffoldTool.generateInstance(Game.S3K, "ParentObjectInstance", "0x77", false);
        assertTrue(src.contains("spawnChild("), "scaffold should show spawnChild() usage");
        assertTrue(src.contains("spawnFreeChild("), "scaffold should show spawnFreeChild() usage");
    }

    @Test
    void instanceReferencesLifetimeOpsAndControlContracts() {
        String src = ObjectScaffoldTool.generateInstance(Game.S3K, "ContractObjectInstance", "0x88", false);
        assertTrue(src.contains("ObjectLifetimeOps"), "scaffold should reference ObjectLifetimeOps");
        assertTrue(src.contains("ObjectControlState"), "scaffold should reference ObjectControlState");
        assertTrue(src.contains("ObjectPlayerQuery"), "scaffold should reference ObjectPlayerQuery");
        assertTrue(src.contains("ObjectLifecycleProfile"), "scaffold should reference a canonical lifecycle profile");
    }

    @Test
    void instanceIncludesCenterCoordinateNote() {
        String src = ObjectScaffoldTool.generateInstance(Game.S3K, "CenterCoordObjectInstance", "0x12", false);
        assertTrue(src.contains("getCentreX()") && src.contains("getCentreY()"),
                "scaffold must note center-coordinate usage (getCentreX/getCentreY) for ROM x_pos/y_pos");
        assertTrue(src.contains("NativePositionOps"),
                "scaffold must point at NativePositionOps for native position writes");
    }

    @Test
    void headerStatesBehaviorMustComeFromDisassembly() {
        String src = ObjectScaffoldTool.generateInstance(Game.S2, "DisasmNoteObjectInstance", "0x01", false);
        assertTrue(src.contains("behavior must be filled in from the disassembly, not guessed"),
                "header comment must state behavior must be filled in from the disassembly, not guessed");
    }

    @Test
    void s3kHeaderWarnsAboutSandKAddressesAndZoneSet() {
        String src = ObjectScaffoldTool.generateInstance(Game.S3K, "SkAddrObjectInstance", "0x8C", true);
        assertTrue(src.contains("sonic3k.asm"), "S3K scaffold must mention S&K-side sonic3k.asm addresses");
        assertTrue(src.contains("S3kZoneSet"), "S3K scaffold must mention zone-set resolution");
    }

    // ------------------------------------------------------------------
    // Test shell emission.
    // ------------------------------------------------------------------

    @Test
    void testShellIsEmittedWithJUnit5Only() {
        String test = ObjectScaffoldTool.generateTest(Game.S3K, "ShellObjectInstance", "0x8A", true);
        assertTrue(test.contains("class TestShellObjectInstance"), "a test shell class must be emitted");
        assertTrue(test.contains("import org.junit.jupiter.api.Test;"), "test shell must use JUnit 5 Jupiter");
        assertTrue(test.contains("@Test"), "test shell must contain at least one @Test method");
        assertFalse(test.contains("import org.junit." + "Test;"), "test shell must NOT use JUnit4 imports");
        assertFalse(test.contains("org.junit." + "runner"), "test shell must NOT use JUnit4 runners");
        assertFalse(test.contains("org.junit.Rule"), "test shell must NOT use JUnit4 rules");
    }

    @Test
    void testShellLivesInSameObjectPackage() {
        String test = ObjectScaffoldTool.generateTest(Game.S3K, "PkgShellObjectInstance", "0x10", false);
        assertTrue(test.contains("package com.openggf.game.sonic3k.objects;"),
                "test shell must live in the matching object package");
    }

    @Test
    void testShellDoesNotRequireRomOrOpenGl() {
        String test = ObjectScaffoldTool.generateTest(Game.S2, "PlainShellObjectInstance", "0x05", false);
        assertFalse(test.contains("import com.openggf.data.Rom"),
                "test shell must not import a ROM dependency");
        assertFalse(test.contains("import com.openggf.data.RomManager"),
                "test shell must not import RomManager");
        assertFalse(test.contains("GLCommand") || test.contains("org.lwjgl"),
                "test shell must not pull in an OpenGL dependency");
        assertFalse(test.contains("import com.openggf.testutil.HeadlessTestRunner"),
                "test shell must stay a pure construction test (no headless runner wiring by default)");
    }

    // ------------------------------------------------------------------
    // Determinism + validation.
    // ------------------------------------------------------------------

    @Test
    void generationIsDeterministic() {
        String a = ObjectScaffoldTool.generateInstance(Game.S3K, "DetObjectInstance", "0x8A", true);
        String b = ObjectScaffoldTool.generateInstance(Game.S3K, "DetObjectInstance", "0x8A", true);
        assertEquals(a, b, "generation must be a deterministic pure function of its inputs");
    }

    @Test
    void instanceAndTestSourcesEndWithNewline() {
        String src = ObjectScaffoldTool.generateInstance(Game.S1, "NewlineObjectInstance", "0x02", false);
        String test = ObjectScaffoldTool.generateTest(Game.S1, "NewlineObjectInstance", "0x02", false);
        assertTrue(src.endsWith("\n"), "instance source must end with a trailing newline");
        assertTrue(test.endsWith("\n"), "test source must end with a trailing newline");
    }

    @Test
    void invalidClassNameIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> ObjectScaffoldTool.generateInstance(Game.S1, "bad name with spaces", "0x01", false));
        assertThrows(IllegalArgumentException.class,
                () -> ObjectScaffoldTool.generateInstance(Game.S1, "", "0x01", false));
    }

    @Test
    void gameParseAcceptsAliasesAndRejectsUnknown() {
        assertEquals(Game.S1, Game.parse("sonic1"));
        assertEquals(Game.S2, Game.parse("S2"));
        assertEquals(Game.S3K, Game.parse("sonic3k"));
        assertThrows(IllegalArgumentException.class, () -> Game.parse("megadrive"));
    }
}
