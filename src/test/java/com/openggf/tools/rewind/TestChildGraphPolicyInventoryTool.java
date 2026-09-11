package com.openggf.tools.rewind;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestChildGraphPolicyInventoryTool {
    @TempDir
    Path tempDir;

    @Test
    void scanSourceRootsFindsSpawnAndReferenceSignals() throws Exception {
        writeFixture("FixtureParentObjectInstance.java", """
                package fixture.objects;

                import com.openggf.level.objects.ObjectInstance;
                import com.openggf.level.objects.PerObjectRewindSnapshot;

                final class FixtureParentObjectInstance {
                    private ObjectInstance activeProjectile;

                    void spawnProjectile() {
                        services().objectManager().addDynamicObject(new ProjectileChild(this));
                        spawnChild(() -> new SparkleChild(this));
                    }

                    public PerObjectRewindSnapshot captureRewindState() {
                        return super.captureRewindState();
                    }
                }
                """);

        List<ChildGraphPolicyInventoryTool.Finding> findings =
                ChildGraphPolicyInventoryTool.scanSourceRoots(List.of(tempDir));

        assertEquals(1, findings.size());
        ChildGraphPolicyInventoryTool.Finding finding = findings.getFirst();
        assertEquals("fixture.objects.FixtureParentObjectInstance", finding.className());
        assertTrue(finding.signals().contains(ChildGraphPolicyInventoryTool.Signal.ADD_DYNAMIC_OBJECT));
        assertTrue(finding.signals().contains(ChildGraphPolicyInventoryTool.Signal.SPAWN_CHILD_HELPER));
        assertTrue(finding.signals().contains(ChildGraphPolicyInventoryTool.Signal.OBJECT_INSTANCE_FIELD));
        assertTrue(finding.signals().contains(ChildGraphPolicyInventoryTool.Signal.PARENT_CHILD_NAMING));
        assertTrue(finding.signals().contains(ChildGraphPolicyInventoryTool.Signal.REWIND_OVERRIDE));
        assertTrue(finding.policyPrompts().contains(ChildGraphPolicyInventoryTool.PolicyPrompt.PARENT_OWNED));
        assertTrue(finding.policyPrompts().contains(ChildGraphPolicyInventoryTool.PolicyPrompt.INDEPENDENT));
    }

    @Test
    void scanSourceRootsFlagsChildLikeClassNamesAsAuditCandidates() throws Exception {
        writeFixture("RockDebrisChild.java", """
                package fixture.objects;

                final class RockDebrisChild {
                    void update() {
                        move();
                    }
                }
                """);

        List<ChildGraphPolicyInventoryTool.Finding> findings =
                ChildGraphPolicyInventoryTool.scanSourceRoots(List.of(tempDir));

        assertEquals(1, findings.size());
        ChildGraphPolicyInventoryTool.Finding finding = findings.getFirst();
        assertEquals("fixture.objects.RockDebrisChild", finding.className());
        assertTrue(finding.signals().contains(ChildGraphPolicyInventoryTool.Signal.CHILD_LIKE_CLASS_NAME));
        assertTrue(finding.policyPrompts().contains(ChildGraphPolicyInventoryTool.PolicyPrompt.COSMETIC));
    }

    @Test
    void scanSourceRootsIgnoresPlainObjectClasses() throws Exception {
        writeFixture("PlainPlatformObjectInstance.java", """
                package fixture.objects;

                final class PlainPlatformObjectInstance {
                    private int timer;

                    void update() {
                        timer++;
                    }
                }
                """);

        List<ChildGraphPolicyInventoryTool.Finding> findings =
                ChildGraphPolicyInventoryTool.scanSourceRoots(List.of(tempDir));

        assertTrue(findings.isEmpty());
    }

    private void writeFixture(String fileName, String source) throws Exception {
        Files.writeString(tempDir.resolve(fileName), source);
    }
}
