package com.openggf.game.rewind.schema;

import com.openggf.game.PlayableEntity;
import com.openggf.game.rewind.FieldKey;
import com.openggf.game.rewind.RewindDeferred;
import com.openggf.game.rewind.RewindStateful;
import com.openggf.game.rewind.RewindTransient;
import com.openggf.game.rewind.identity.ObjectRefId;
import com.openggf.game.rewind.identity.PlayerRefId;
import com.openggf.game.rewind.identity.RewindIdentityTable;
import com.openggf.graphics.GLCommand;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.tests.TestablePlayableSprite;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.openggf.game.rewind.schema.RewindFieldPolicy.CAPTURED;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestRewindReferenceClosureValidation {
    private static final ObjectSpawn OWNER_SPAWN =
            new ObjectSpawn(0x100, 0x120, 1, 0, 0, false, 7);
    private static final ObjectSpawn TARGET_SPAWN =
            new ObjectSpawn(0x140, 0x120, 2, 0, 0, false, 8);
    private static final ObjectRefId OWNER_ID = ObjectRefId.dynamic(12, 0, 7);
    private static final ObjectRefId TARGET_ID = ObjectRefId.dynamic(13, 0, 8);

    @BeforeEach
    void registerExplicitPoliciesBeforeSchemaCaching() throws Exception {
        for (String name : List.of("targets", "targetKeys", "targetValues", "state", "statefulValues")) {
            RewindPolicyRegistry.registerFieldPolicy(FieldKey.of(field(name)), CAPTURED);
        }
    }

    @AfterEach
    void clearSchemaAndPolicyCaches() {
        RewindSchemaRegistry.clearForTest();
    }

    @Test
    void directAndNestedObjectReferencesUseCodecIdentityMetadata() throws Exception {
        for (String fieldName : List.of(
                "direct", "array", "listStates", "mapKeyStates", "mapValueStates", "states")) {
            Field selected = field(fieldName);
            assertTrue(RewindCodecs.codecFor(selected).orElseThrow().requiresIdentityTable(), fieldName);

            TargetObject target = new TargetObject();
            ReferenceOwner owner = ownerWithOnly(fieldName, target);
            RewindCaptureContext context = contextWithOwner(owner);

            IllegalStateException full = assertThrows(IllegalStateException.class,
                    () -> CompactFieldCapturer.captureDefaultObjectSubclassScalars(owner, context));
            IllegalStateException focused = assertThrows(IllegalStateException.class,
                    () -> CompactFieldCapturer.validateDefaultObjectSubclassReferenceClosure(owner, context));

            assertDiagnostic(full, selected);
            assertDiagnostic(focused, selected);

            context.requireIdentityTable().registerObject(target, TARGET_ID);
            assertDoesNotThrow(() -> CompactFieldCapturer.captureDefaultObjectSubclassScalars(owner, context));
            assertDoesNotThrow(() ->
                    CompactFieldCapturer.validateDefaultObjectSubclassReferenceClosure(owner, context));
        }
    }

    @Test
    void scalarAndValueSerializersDoNotRequireIdentityTable() throws Exception {
        for (String name : List.of("number", "text", "mode", "value", "statefulValues")) {
            assertFalse(RewindCodecs.codecFor(field(name)).orElseThrow().requiresIdentityTable(), name);
        }
        for (String name : List.of("targets", "targetKeys", "targetValues", "state")) {
            assertTrue(RewindCodecs.codecFor(field(name)).orElseThrow().requiresIdentityTable(), name);
        }
    }

    @Test
    void transientAndDeferredReferencesAreExcluded() {
        TargetObject target = new TargetObject();
        ReferenceOwner owner = new ReferenceOwner(null);
        owner.transientTarget = target;
        owner.deferredTarget = target;

        assertDoesNotThrow(() -> CompactFieldCapturer.validateDefaultObjectSubclassReferenceClosure(
                owner, contextWithOwner(owner)));
        RewindClassSchema schema = RewindSchemaRegistry.defaultObjectSubclassSchemaFor(ReferenceOwner.class);
        assertFalse(schema.capturedFields().stream().anyMatch(plan ->
                plan.field().getName().equals("transientTarget")
                        || plan.field().getName().equals("deferredTarget")));
    }

    @Test
    void missingReferenceCannotBeBaselined() {
        TargetObject target = new TargetObject();
        ReferenceOwner owner = ownerWithOnly("direct", target);

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> CompactFieldCapturer.validateDefaultObjectSubclassReferenceClosure(
                        owner, contextWithOwner(owner)));

        assertDiagnostic(failure, uncheckedField("direct"));
    }

    @Test
    void playerCodecUsesCurrentNullContract() throws Exception {
        Field playerField = field("player");
        assertTrue(RewindCodecs.codecFor(playerField).orElseThrow().requiresIdentityTable());
        ReferenceOwner owner = new ReferenceOwner(null);
        TestablePlayableSprite player = new TestablePlayableSprite("test", (short) 0, (short) 0);
        owner.player = player;

        assertThrows(IllegalStateException.class,
                () -> CompactFieldCapturer.validateDefaultObjectSubclassReferenceClosure(
                        owner, RewindCaptureContext.none()));

        RewindCaptureContext unregistered = contextWithOwner(owner);
        assertDoesNotThrow(() -> CompactFieldCapturer.validateDefaultObjectSubclassReferenceClosure(
                owner, unregistered));

        unregistered.requireIdentityTable().registerPlayer(player, PlayerRefId.mainPlayer());
        assertDoesNotThrow(() -> CompactFieldCapturer.validateDefaultObjectSubclassReferenceClosure(
                owner, unregistered));
    }

    private static ReferenceOwner ownerWithOnly(String fieldName, ObjectInstance target) {
        ReferenceOwner owner = new ReferenceOwner(null);
        switch (fieldName) {
            case "direct" -> owner.direct = target;
            case "array" -> owner.array = new ObjectInstance[]{target};
            case "targets" -> owner.targets = new ArrayList<>(List.of(target));
            case "targetKeys" -> owner.targetKeys = new LinkedHashMap<>(Map.of(target, 1));
            case "targetValues" -> owner.targetValues = new LinkedHashMap<>(Map.of(1, target));
            case "state" -> owner.state = new ReferenceState(target);
            case "listStates" -> owner.listStates = new ReferenceListState[]{new ReferenceListState(target)};
            case "mapKeyStates" -> owner.mapKeyStates = new ReferenceMapKeyState[]{new ReferenceMapKeyState(target)};
            case "mapValueStates" -> owner.mapValueStates = new ReferenceMapValueState[]{new ReferenceMapValueState(target)};
            case "states" -> owner.states = new ReferenceState[]{new ReferenceState(target)};
            default -> throw new IllegalArgumentException(fieldName);
        }
        return owner;
    }

    private static RewindCaptureContext contextWithOwner(ReferenceOwner owner) {
        owner.setSlotIndex(12);
        RewindIdentityTable table = new RewindIdentityTable();
        table.registerObject(owner, OWNER_ID);
        return RewindCaptureContext.withIdentityTable(table);
    }

    private static void assertDiagnostic(IllegalStateException failure, Field field) {
        assertAll(
                () -> assertTrue(failure.getMessage().contains(FieldKey.of(field).toString())),
                () -> assertTrue(failure.getMessage().contains(ReferenceOwner.class.getName())),
                () -> assertTrue(failure.getMessage().contains(OWNER_ID.toString())),
                () -> assertTrue(failure.getMessage().contains("slot=12")),
                () -> assertTrue(failure.getMessage().contains(OWNER_SPAWN.toString())),
                () -> assertInstanceOf(IllegalStateException.class, failure.getCause()),
                () -> assertTrue(failure.getCause().getMessage().contains(
                        "RewindIdentityTable has no registered id for object reference")));
    }

    private static Field field(String name) throws NoSuchFieldException {
        return ReferenceOwner.class.getDeclaredField(name);
    }

    private static Field uncheckedField(String name) {
        try {
            return field(name);
        } catch (NoSuchFieldException e) {
            throw new AssertionError(e);
        }
    }

    private enum Mode { VALUE }

    private record ValueRecord(int number, String text) {}

    private static final class OpaqueValueStateful implements RewindStateful<ObjectInstance> {
        private ObjectInstance value;

        OpaqueValueStateful() {}

        OpaqueValueStateful(ObjectInstance value) {
            this.value = value;
        }

        @Override
        public ObjectInstance captureRewindStateValue() {
            return value;
        }

        @Override
        public void restoreRewindStateValue(ObjectInstance state) {
            value = state;
        }
    }

    private static final class ReferenceState {
        ObjectInstance target;

        ReferenceState() {}

        ReferenceState(ObjectInstance target) {
            this.target = target;
        }
    }

    private static final class ReferenceListState {
        List<ObjectInstance> targets;

        ReferenceListState() {}

        ReferenceListState(ObjectInstance target) {
            targets = new ArrayList<>(List.of(target));
        }
    }

    private static final class ReferenceMapKeyState {
        Map<ObjectInstance, Integer> targets;

        ReferenceMapKeyState() {}

        ReferenceMapKeyState(ObjectInstance target) {
            targets = new LinkedHashMap<>(Map.of(target, 1));
        }
    }

    private static final class ReferenceMapValueState {
        Map<Integer, ObjectInstance> targets;

        ReferenceMapValueState() {}

        ReferenceMapValueState(ObjectInstance target) {
            targets = new LinkedHashMap<>(Map.of(1, target));
        }
    }

    private static final class ReferenceOwner extends AbstractObjectInstance {
        ObjectInstance direct;
        ObjectInstance[] array;
        transient List<ObjectInstance> targets;
        transient Map<ObjectInstance, Integer> targetKeys;
        transient Map<Integer, ObjectInstance> targetValues;
        transient ReferenceState state;
        ReferenceListState[] listStates;
        ReferenceMapKeyState[] mapKeyStates;
        ReferenceMapValueState[] mapValueStates;
        ReferenceState[] states;
        PlayableEntity player;
        int number = 1;
        String text = "value";
        Mode mode = Mode.VALUE;
        ValueRecord value = new ValueRecord(1, "value");
        final List<OpaqueValueStateful> statefulValues = new ArrayList<>();
        @RewindTransient(reason = "fixture exclusion") ObjectInstance transientTarget;
        @RewindDeferred(reason = "fixture deferred relink") ObjectInstance deferredTarget;

        ReferenceOwner(ObjectInstance target) {
            super(OWNER_SPAWN, "ReferenceOwner");
            direct = target;
            array = target == null ? null : new ObjectInstance[]{target};
            targets = target == null ? null : new ArrayList<>(List.of(target));
            targetKeys = target == null ? null : new LinkedHashMap<>(Map.of(target, 1));
            targetValues = target == null ? null : new LinkedHashMap<>(Map.of(1, target));
            state = target == null ? null : new ReferenceState(target);
            if (target != null) {
                statefulValues.add(new OpaqueValueStateful(target));
            }
        }

        @Override
        public void appendRenderCommands(List<GLCommand> commands) {}
    }

    private static final class TargetObject extends AbstractObjectInstance {
        TargetObject() {
            super(TARGET_SPAWN, "TargetObject");
        }

        @Override
        public void appendRenderCommands(List<GLCommand> commands) {}
    }
}
