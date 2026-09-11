package com.openggf.level.objects;

import com.openggf.game.rewind.identity.ObjectRefId;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.withSettings;
import static org.mockito.Mockito.when;

class TestObjectCollisionResponseList {

    @Test
    void resetClearsOnlyTheCurrentBuildAndPreservesTheFrozenPlayerReadView() {
        ObjectInstance special = publisher();
        ObjectInstance enemy = publisher();
        ObjectInstance newlyLoaded = publisher();
        ObjectCollisionResponseList list = new ObjectCollisionResponseList();
        list.captureCompletedBuild(List.of(special, enemy));
        list.freezePreviousReadView();

        list.addToCurrentBuild(newlyLoaded);
        list.resetCurrentBuild();

        assertTrue(list.usesPrevious());
        assertEquals(List.of(special, enemy), list.playerReadView());
        assertTrue(list.currentBuildView().isEmpty());
    }

    @Test
    void completedBuildBecomesTheNextOrdinaryPlayerReadViewInPublicationOrder() {
        ObjectInstance old = publisher();
        ObjectInstance dynamic = publisher();
        ObjectInstance fixed = publisher();
        ObjectCollisionResponseList list = new ObjectCollisionResponseList();
        list.captureCompletedBuild(List.of(old));
        list.freezePreviousReadView();
        list.resetCurrentBuild();

        list.addToCurrentBuild(dynamic);
        list.addToCurrentBuild(fixed);
        assertEquals(List.of(old), list.playerReadView(),
                "LOAD and this pass's publications cannot enter the already-frozen view");

        list.captureCompletedBuild();

        assertEquals(List.of(dynamic, fixed), list.playerReadView());
        assertSame(dynamic, list.playerReadView().get(0));
        assertSame(fixed, list.playerReadView().get(1));
    }

    @Test
    void ordinaryCaptureDoesNotSelectThePreviousListForSharedGameLoops() {
        ObjectInstance current = publisher();
        ObjectCollisionResponseList list = new ObjectCollisionResponseList();
        list.setUsePrevious(false);

        list.captureForNextFrame(List.of(current));

        assertFalse(list.usesPrevious(),
                "ordinary S1/S2 capture must retain their live-list selection");
        assertEquals(List.of(current), list.playerReadView(),
                "the snapshot is still available if a later semantic owner selects it");
    }

    @Test
    void rewindRestoresBothOrderedBuildsAndTheSelectedReadView() {
        ObjectInstance previousDynamic = publisher();
        ObjectInstance previousFixed = publisher();
        ObjectInstance partialCurrent = publisher();
        ObjectRefId dynamicId = ObjectRefId.dynamic(4, 1, 1);
        ObjectRefId fixedId = ObjectRefId.dynamic(100, 1, 2);
        ObjectRefId partialId = ObjectRefId.dynamic(8, 1, 3);
        Map<ObjectInstance, ObjectRefId> ids = Map.of(
                previousDynamic, dynamicId,
                previousFixed, fixedId,
                partialCurrent, partialId);
        Map<ObjectRefId, ObjectInstance> objects = Map.of(
                dynamicId, previousDynamic,
                fixedId, previousFixed,
                partialId, partialCurrent);
        ObjectCollisionResponseList list = new ObjectCollisionResponseList();
        list.captureCompletedBuild(List.of(previousDynamic, previousFixed));
        list.resetCurrentBuild();
        list.addToCurrentBuild(partialCurrent);

        var state = list.captureRewindState(ids::get);
        list.captureCompletedBuild(List.of(partialCurrent));
        list.setUsePrevious(false);

        list.restoreRewindState(state, objects::get);

        assertTrue(list.usesPrevious());
        assertEquals(List.of(previousDynamic, previousFixed), list.playerReadView());
        assertEquals(List.of(partialCurrent), list.currentBuildView());
    }

    @Test
    void rewindCaptureRejectsAResponderWithoutIdentityInsteadOfDroppingIt() {
        ObjectCollisionResponseList list = new ObjectCollisionResponseList();
        list.captureCompletedBuild(List.of(publisher()));

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> list.captureRewindState(object -> null));

        assertTrue(failure.getMessage().startsWith(
                "collision response publisher has no rewind identity:"));
    }

    private static ObjectInstance publisher() {
        ObjectInstance instance = mock(ObjectInstance.class,
                withSettings().extraInterfaces(TouchResponseProvider.class));
        when(instance.publishesTouchResponseListEntryThisFrame()).thenReturn(true);
        return instance;
    }
}
