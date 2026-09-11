package com.openggf.tools.rewind;

import com.openggf.game.rewind.GenericRewindEligibility;
import com.openggf.game.rewind.RewindDeferred;
import com.openggf.game.rewind.RewindTransient;
import com.openggf.graphics.GLCommand;
import com.openggf.game.sonic1.objects.Sonic1TryAgainEggmanObjectInstance;
import com.openggf.level.Pattern;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectRenderManager;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.SolidObjectParams;
import com.openggf.level.objects.SubpixelMotion;
import com.openggf.level.render.SpriteMappingPiece;
import com.openggf.level.rings.RingSpawn;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestRewindFieldInventoryTool {
    private static class DeferredStructuralFixture {
        @RewindDeferred(reason = "fixture structural dependency")
        private final Object structural = new Object();
    }

    private static class CentralPolicyFixture {
        private ObjectRenderManager renderer;
    }

    private static class ConstructorMetadataFixture {
        private final String name = "fixture";
        private final int baseX = 0x120;
        private final SubpixelMotion.State motionState =
                new SubpixelMotion.State(0, 0, 0, 0, 0x100, 0);
    }

    private static class StructuralArtFixture {
        private final Pattern blank = new Pattern();
        private final Pattern[] tiles = {new Pattern(), new Pattern()};
    }

    private static class StructuralCallbackFixture {
        private Sonic1TryAgainEggmanObjectInstance.Sonic1CreditsTextRendererRef textRenderer;
    }

    private static class StructuralListFixture {
        private final List<SpriteMappingPiece> pieces = List.of();
        private final List<RingSpawn> ringSpawns = List.of(new RingSpawn(1, 2));
    }

    private static class AnnotationDensityFixture {
        @RewindTransient(reason = "covered by default declared-type policy")
        private ObjectRenderManager renderer;

        @RewindTransient(reason = "fixture unsupported field")
        private Object structural;

        @RewindDeferred(reason = "fixture deferred dependency")
        private Object deferredStructural;
    }

    private static final class DefaultObjectCaptureFixture extends AbstractObjectInstance {
        private final int subtype = 3;
        private final boolean flipped = true;
        private final SolidObjectParams solidObjectParams = new SolidObjectParams(16, 8, 9);
        private int phase;
        private boolean armed;

        private DefaultObjectCaptureFixture() {
            super(new ObjectSpawn(0, 0, 0, 0, 0, false, 0), "DefaultObjectCaptureFixture");
        }

        @Override
        public void appendRenderCommands(List<GLCommand> commands) {
            // no-op fixture
        }
    }

    @Test
    void unsupportedInventorySkipsDeferredFields() {
        List<String> unsupported = RewindFieldInventoryTool.unsupportedFieldsForClass(DeferredStructuralFixture.class);

        assertTrue(unsupported.isEmpty());
    }

    @Test
    void unsupportedInventorySkipsCentralPolicyFields() {
        List<String> unsupported = RewindFieldInventoryTool.unsupportedFieldsForClass(CentralPolicyFixture.class);

        assertTrue(unsupported.isEmpty(), String.join(System.lineSeparator(), unsupported));
    }

    @Test
    void unsupportedInventorySkipsSchemaClassifiedFinalMetadata() {
        List<String> unsupported = RewindFieldInventoryTool.unsupportedFieldsForClass(ConstructorMetadataFixture.class);

        assertTrue(unsupported.isEmpty(), String.join(System.lineSeparator(), unsupported));
    }

    @Test
    void unsupportedInventorySkipsStructuralArtFields() {
        List<String> unsupported = RewindFieldInventoryTool.unsupportedFieldsForClass(StructuralArtFixture.class);

        assertTrue(unsupported.isEmpty(), String.join(System.lineSeparator(), unsupported));
    }

    @Test
    void unsupportedInventorySkipsStructuralRendererCallbacks() {
        List<String> unsupported = RewindFieldInventoryTool.unsupportedFieldsForClass(StructuralCallbackFixture.class);

        assertTrue(unsupported.isEmpty(), String.join(System.lineSeparator(), unsupported));
    }

    @Test
    void unsupportedInventorySkipsFinalStructuralLists() {
        List<String> unsupported = RewindFieldInventoryTool.unsupportedFieldsForClass(StructuralListFixture.class);

        assertTrue(unsupported.isEmpty(), String.join(System.lineSeparator(), unsupported));
    }

    @Test
    void unsupportedInventorySkipsDefaultObjectCapturedFields() {
        List<String> unsupported = RewindFieldInventoryTool.unsupportedFieldsForClass(DefaultObjectCaptureFixture.class);

        assertTrue(unsupported.isEmpty(), String.join(System.lineSeparator(), unsupported));
    }

    @Test
    void objectRolloutCandidatesAreDefaultCapturedObjects() throws Exception {
        List<String> candidates = RewindFieldInventoryTool.objectRolloutCandidates();

        assertFalse(candidates.isEmpty());
        for (String candidate : candidates) {
            String className = candidate.substring(0, candidate.indexOf(" : "));
            Class<?> type = Class.forName(className, false, Thread.currentThread().getContextClassLoader());
            assertTrue(GenericRewindEligibility.usesDefaultObjectSubclassCapture(type), candidate);
        }
    }

    @Test
    void annotationDensityCountsAnnotationsByClassDeclaredTypeAndPackage() {
        RewindFieldInventoryTool.AnnotationDensity density =
                RewindFieldInventoryTool.annotationDensityForClasses(List.of(AnnotationDensityFixture.class));

        RewindFieldInventoryTool.AnnotationCounts classCounts =
                density.byClass().get(AnnotationDensityFixture.class.getName());
        assertEquals(2, classCounts.transientCount());
        assertEquals(1, classCounts.deferredCount());

        RewindFieldInventoryTool.AnnotationCounts rendererTypeCounts =
                density.byDeclaredType().get(ObjectRenderManager.class.getName());
        assertEquals(1, rendererTypeCounts.transientCount());
        assertEquals(0, rendererTypeCounts.deferredCount());

        RewindFieldInventoryTool.AnnotationCounts objectTypeCounts =
                density.byDeclaredType().get(Object.class.getName());
        assertEquals(1, objectTypeCounts.transientCount());
        assertEquals(1, objectTypeCounts.deferredCount());

        RewindFieldInventoryTool.AnnotationCounts packageCounts =
                density.byPackage().get(AnnotationDensityFixture.class.getPackageName());
        assertEquals(2, packageCounts.transientCount());
        assertEquals(1, packageCounts.deferredCount());
    }

    @Test
    void redundantTransientAnnotationsReportOnlyMatchingInferredTransientPolicy() {
        List<RewindFieldInventoryTool.RedundantTransientAnnotation> redundant =
                RewindFieldInventoryTool.redundantTransientAnnotationsForClasses(
                        List.of(AnnotationDensityFixture.class));

        assertEquals(1, redundant.size());
        RewindFieldInventoryTool.RedundantTransientAnnotation annotation = redundant.get(0);
        assertEquals(AnnotationDensityFixture.class.getName() + "#renderer", annotation.field());
        assertEquals(ObjectRenderManager.class.getName(), annotation.declaredType());
    }

    @Test
    void coverageModeRendersSummary() {
        String out = RewindFieldInventoryTool.renderCoverageReport();
        assertTrue(out.contains("coverage:"), "must include a coverage summary line");
        assertTrue(out.contains("gaps"), "must report gap count");
    }

}
