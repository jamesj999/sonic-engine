package com.openggf.game.rewind.schema;

import com.openggf.game.PlayableEntity;
import com.openggf.game.rewind.GenericFieldCapturer;
import com.openggf.game.rewind.identity.ObjectRefId;
import com.openggf.game.rewind.identity.RewindIdentityTable;
import com.openggf.graphics.GLCommand;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectSpawn;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * TDD Red→Green test for Task 3: object-reference fields on AbstractObjectInstance
 * subclasses round-trip through the compact blob using the existing
 * {@code ObjectReferenceCodec} + identity table, with no separate object-ref side channel.
 *
 * <h2>What this tests</h2>
 * <ol>
 *   <li>A child object with a non-transient {@code AbstractObjectInstance parentRef} field
 *       has that field included in the compact-schema plan (i.e.
 *       {@code RewindSchemaRegistry.defaultObjectSubclassSchemaFor} does not skip it).</li>
 *   <li>Capturing the child's compact state serialises the parent's identity to the blob.</li>
 *   <li>Restoring into a context that maps the SAME id to a NEW parent instance
 *       relinks {@code parentRef} to the new instance — identity match, not structural
 *       equality.</li>
 * </ol>
 *
 * <h2>Why this test is enough</h2>
 * The {@code ObjectReferenceCodec} encode/decode was already verified to work in
 * {@code TestRewindObjectReferenceCodecs}.  What was missing (pre-Task 3) was that the
 * compact schema skipped {@code ObjectInstance}-typed fields unconditionally, so the codec
 * was never invoked.  This test exercises the full path:
 * schema-build → compact capture → compact restore → ref resolved.
 */
class TestObjectRefCompactRoundTrip {

    @AfterEach
    void clearRegistry() {
        RewindSchemaRegistry.clearForTest();
    }

    /**
     * A parent ref field on an AbstractObjectInstance subclass must survive a
     * compact-blob round-trip: restored instance references the RESTORED parent
     * (same ObjectRefId, new Java instance), not the pre-restore parent and not null.
     */
    @Test
    void parentRefRoundTripsViaCompactBlobToRestoredInstance() {
        // --- Build fixture objects ---
        ObjectSpawn parentSpawn = new ObjectSpawn(0x100, 0x100, 1, 0, 0, false, 0, 0);
        ObjectSpawn childSpawn = new ObjectSpawn(0x120, 0x100, 2, 0, 0, false, 0, 1);

        TestParentObject priorParent = new TestParentObject(parentSpawn);
        TestChildObject child = new TestChildObject(childSpawn);
        child.parentRef = priorParent;

        // --- Assign stable ObjectRefIds ---
        ObjectRefId parentId = ObjectRefId.layout(5, 1, 0);
        ObjectRefId childId  = ObjectRefId.layout(6, 1, 1);

        // --- Capture child's compact state (parent ref encoded by id) ---
        assertNotNull(child.parentRef, "sanity: parentRef set before capture");

        RewindIdentityTable captureTable = new RewindIdentityTable();
        captureTable.registerObject(priorParent, parentId);
        captureTable.registerObject(child, childId);
        RewindCaptureContext captureCtx = RewindCaptureContext.withIdentityTable(captureTable);

        RewindObjectStateBlob blob =
                CompactFieldCapturer.captureDefaultObjectSubclassScalars(child, captureCtx);

        // --- Nullify child's ref to simulate post-recreation state ---
        child.parentRef = null;
        assertNull(child.parentRef, "sanity: parentRef nulled before restore");

        // --- Restore into a context that maps the same id to a NEW parent instance ---
        TestParentObject restoredParent = new TestParentObject(parentSpawn);
        RewindIdentityTable restoreTable = new RewindIdentityTable();
        restoreTable.registerObject(restoredParent, parentId);
        restoreTable.registerObject(child, childId);
        RewindCaptureContext restoreCtx = RewindCaptureContext.withIdentityTable(restoreTable);

        GenericFieldCapturer.restoreObjectSubclassScalarsCompact(child, blob, restoreCtx);

        // --- Assert ref resolves to the RESTORED parent, not the prior one ---
        assertSame(restoredParent, child.parentRef,
                "child.parentRef must resolve to the restored parent instance (same ObjectRefId)");
    }

    /**
     * A null parent ref must survive the round-trip as null (no NPE in encode path).
     */
    @Test
    void nullParentRefRoundTripsAsNull() {
        ObjectSpawn childSpawn = new ObjectSpawn(0x120, 0x100, 2, 0, 0, false, 0, 1);
        TestChildObject child = new TestChildObject(childSpawn);
        child.parentRef = null;  // explicitly null

        ObjectRefId childId = ObjectRefId.layout(6, 1, 1);
        RewindIdentityTable captureTable = new RewindIdentityTable();
        captureTable.registerObject(child, childId);
        RewindCaptureContext captureCtx = RewindCaptureContext.withIdentityTable(captureTable);

        // Capture with null parentRef (writeObjectRef writes false for null)
        RewindObjectStateBlob blob =
                CompactFieldCapturer.captureDefaultObjectSubclassScalars(child, captureCtx);

        // Restore: null must come back as null
        RewindIdentityTable restoreTable = new RewindIdentityTable();
        restoreTable.registerObject(child, childId);
        RewindCaptureContext restoreCtx = RewindCaptureContext.withIdentityTable(restoreTable);

        GenericFieldCapturer.restoreObjectSubclassScalarsCompact(child, blob, restoreCtx);

        assertNull(child.parentRef, "null parentRef must survive compact round-trip as null");
    }

    // =========================================================================
    // Test fixture classes
    // =========================================================================

    /**
     * Minimal parent stub — no state fields, used only as an identity anchor.
     */
    static final class TestParentObject extends AbstractObjectInstance {
        TestParentObject(ObjectSpawn spawn) {
            super(spawn, "TestParentObject");
        }

        @Override
        public void appendRenderCommands(List<GLCommand> commands) {}
    }

    /**
     * Child stub with a single {@code AbstractObjectInstance parentRef} field.
     *
     * <p>The field name {@code parentRef} is intentionally chosen to avoid
     * {@code DefaultObjectRewindPolicies.STRUCTURAL_OBJECT_FIELD_NAMES} (which contains
     * {@code "parent"} but not {@code "parentRef"}) and to avoid being classified as
     * a known structural test fixture by {@code GenericFieldCapturer.isKnownStructuralObjectField}
     * (which classifies fields ending in {@code "ForTest"} as structural only in the
     * generic non-compact path; the compact schema path uses {@code RewindSchemaRegistry}
     * which does not have a {@code ForTest} carve-out).
     */
    static final class TestChildObject extends AbstractObjectInstance {
        /** The object-reference field under test: must survive compact round-trip. */
        AbstractObjectInstance parentRef;

        TestChildObject(ObjectSpawn spawn) {
            super(spawn, "TestChildObject");
        }

        @Override
        public void appendRenderCommands(List<GLCommand> commands) {}
    }
}
