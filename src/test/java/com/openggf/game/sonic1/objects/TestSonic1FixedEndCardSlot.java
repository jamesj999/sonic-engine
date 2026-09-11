package com.openggf.game.sonic1.objects;

import com.openggf.camera.Camera;
import com.openggf.game.PlayableEntity;
import com.openggf.game.rewind.snapshot.ObjectManagerSnapshot;
import com.openggf.graphics.GLCommand;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.FixedRuntimeObjectInstance;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.ObjectServices;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.objects.StubObjectServices;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class TestSonic1FixedEndCardSlot {

    @Test
    void fixedCardUsesSlot23WithoutConsumingTheFullDynamicPool() {
        Harness harness = new Harness();
        for (int i = 0; i < 96; i++) {
            harness.objects.addDynamicObject(new DummyObject(
                    new ObjectSpawn(i, 0, 1, 0, 0, false, 0)));
        }
        assertEquals(96, harness.objects.getActiveObjectSlotCount());

        Sonic1FixedEndCardSlot.ClaimResult claim = Sonic1FixedEndCardSlot.claim(
                harness.services,
                new Sonic1FixedEndCardSlot.ResultsData(45, 50, 1, true));

        assertEquals(Sonic1FixedEndCardSlot.ClaimState.NEW_UNCOMMITTED, claim.state());
        assertEquals(Sonic1FixedEndCardSlot.SLOT, claim.requireCard().getSlotIndex());
        assertEquals(96, harness.objects.getActiveObjectSlotCount(),
                "the fixed results card must not consume FindFreeObj capacity");
    }

    @Test
    void repeatedClaimDistinguishesUncommittedAndCommittedOwnership() {
        Harness harness = new Harness();
        Sonic1FixedEndCardSlot.ResultsData data =
                new Sonic1FixedEndCardSlot.ResultsData(45, 50, 1, false);

        Sonic1FixedEndCardSlot.ClaimResult first =
                Sonic1FixedEndCardSlot.claim(harness.services, data);
        Sonic1FixedEndCardSlot.ClaimResult pending =
                Sonic1FixedEndCardSlot.claim(harness.services, data);
        first.requireCard().markResultsPlcCommitted();
        Sonic1FixedEndCardSlot.ClaimResult committed =
                Sonic1FixedEndCardSlot.claim(harness.services, data);

        assertEquals(Sonic1FixedEndCardSlot.ClaimState.EXISTING_UNCOMMITTED,
                pending.state());
        assertSame(first.requireCard(), pending.requireCard());
        assertEquals(Sonic1FixedEndCardSlot.ClaimState.EXISTING_COMMITTED,
                committed.state());
        assertSame(first.requireCard(), committed.requireCard());
    }

    @Test
    void wrongLiveOccupantFailsClosed() {
        Harness harness = new Harness();
        harness.objects.addDynamicObjectAtSlot(
                new DummyObject(null), Sonic1FixedEndCardSlot.SLOT);

        Sonic1FixedEndCardSlot.ClaimResult claim = Sonic1FixedEndCardSlot.claim(
                harness.services,
                new Sonic1FixedEndCardSlot.ResultsData(0, 0, 1, false));

        assertEquals(Sonic1FixedEndCardSlot.ClaimState.INVALID_OCCUPANT, claim.state());
        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalStateException.class, claim::requireCard);
    }

    @Test
    void rewindRestoresOneFixedCardIdentityAndFixedPassProgress() throws Exception {
        Harness harness = new Harness();
        Sonic1ResultsScreenObjectInstance card = Sonic1FixedEndCardSlot.claim(
                harness.services,
                new Sonic1FixedEndCardSlot.ResultsData(45, 50, 1, false))
                .requireCard();
        card.markResultsPlcCommitted();
        ObjectManagerSnapshot before = harness.objects.rewindSnapshottable().capture();
        var beforeEntry = before.dynamicObjects().stream()
                .filter(entry -> entry.slotIndex() == Sonic1FixedEndCardSlot.SLOT)
                .findFirst().orElseThrow();

        harness.objects.removeDynamicObject(card);
        harness.objects.rewindSnapshottable().restore(before);

        List<Sonic1ResultsScreenObjectInstance> restoredCards = harness.objects
                .getActiveObjects().stream()
                .filter(Sonic1ResultsScreenObjectInstance.class::isInstance)
                .map(Sonic1ResultsScreenObjectInstance.class::cast)
                .toList();
        assertEquals(1, restoredCards.size());
        Sonic1ResultsScreenObjectInstance restored = restoredCards.getFirst();
        assertEquals(Sonic1FixedEndCardSlot.SLOT, restored.getSlotIndex());
        ObjectManagerSnapshot after = harness.objects.rewindSnapshottable().capture();
        var afterEntry = after.dynamicObjects().stream()
                .filter(entry -> entry.slotIndex() == Sonic1FixedEndCardSlot.SLOT)
                .findFirst().orElseThrow();
        assertEquals(beforeEntry.objectId(), afterEntry.objectId());

        int totalFramesBefore = intField(restored, "totalFrames");
        Sonic1FixedEndCardSlot.updateFixedPass(harness.services, 123, null);
        assertNotEquals(totalFramesBefore, intField(restored, "totalFrames"));
    }

    @Test
    void ordinaryDynamicPassDoesNotAdvanceFixedCardASecondTime() throws Exception {
        Harness harness = new Harness();
        Sonic1ResultsScreenObjectInstance card = Sonic1FixedEndCardSlot.claim(
                harness.services,
                new Sonic1FixedEndCardSlot.ResultsData(45, 50, 1, false))
                .requireCard();
        card.markResultsPlcCommitted();

        Sonic1FixedEndCardSlot.updateFixedPass(harness.services, 123, null);
        int afterFixedPass = intField(card, "totalFrames");

        harness.objects.update(0, null, List.of(), 124, false);

        assertEquals(afterFixedPass, intField(card, "totalFrames"),
                "the fixed SST owner, not ObjectManager's dynamic fallback, executes slot 23");
    }

    private static int intField(Object target, String fieldName) throws Exception {
        Class<?> type = target.getClass();
        while (type != null) {
            try {
                Field field = type.getDeclaredField(fieldName);
                field.setAccessible(true);
                return field.getInt(target);
            } catch (NoSuchFieldException ignored) {
                type = type.getSuperclass();
            }
        }
        throw new NoSuchFieldException(fieldName);
    }

    private static final class Harness {
        private final ObjectManager[] ref = new ObjectManager[1];
        private final ObjectServices services = new StubObjectServices() {
            @Override
            public ObjectManager objectManager() {
                return ref[0];
            }
        };
        private final ObjectManager objects;

        private Harness() {
            objects = new ObjectManager(
                    List.of(), null, 0, null, null, null, new Camera(), services);
            ref[0] = objects;
        }
    }

    private static final class DummyObject extends AbstractObjectInstance
            implements FixedRuntimeObjectInstance, RewindRecreatable {
        private DummyObject(ObjectSpawn spawn) {
            super(spawn, "fixed-card-test-dummy");
        }

        @Override
        public void update(int vIntRunCount, PlayableEntity player) {
        }

        @Override
        public void appendRenderCommands(List<GLCommand> commands) {
        }

        @Override
        public AbstractObjectInstance recreateForRewind(RewindRecreateContext context) {
            return new DummyObject(context.spawn());
        }
    }
}
