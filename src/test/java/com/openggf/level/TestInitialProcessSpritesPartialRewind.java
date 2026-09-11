package com.openggf.level;

import com.openggf.game.InitialProcessSpritesLifecycle;
import com.openggf.game.rewind.snapshot.ObjectManagerSnapshot;
import com.openggf.level.objects.InitialObjectDispatchScope;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.ObjectRegistry;
import com.openggf.level.objects.ObjectSlotLayout;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.sprites.managers.ProcessSpritesEpoch;
import com.openggf.tests.TestEnvironment;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TestInitialProcessSpritesPartialRewind {

    @BeforeEach
    void setUp() {
        TestEnvironment.resetAll();
    }

    @AfterEach
    void tearDown() {
        TestEnvironment.resetAll();
    }

    @Test
    void beforeSetupRestorePreservesAuthorityWithoutInference() {
        InitialProcessSpritesLifecycleCoordinator lifecycle =
                new InitialProcessSpritesLifecycleCoordinator();
        lifecycle.publish(InitialProcessSpritesLifecycle.LOAD_THEN_PROCESS_ONCE);

        InitialProcessSpritesLifecycle captured = lifecycle.captureForRewind();
        lifecycle.discard();
        lifecycle.restoreForRewind(captured);

        assertEquals(InitialProcessSpritesLifecycle.LOAD_THEN_PROCESS_ONCE,
                lifecycle.captureForRewind());
    }

    @Test
    void partialFailureAfterPlayablesClosesScopeAndRestoresConsumedState() {
        assertPartialRoundTrip(
                InitialProcessSpritesCoordinator.Checkpoint.AFTER_PLAYABLES_BEFORE_RESET,
                ObjectManagerSnapshot.CollisionBuildStage.PREVIOUS_READ_FROZEN);
    }

    @Test
    void partialFailureAfterDynamicClosesScopeAndRestoresConsumedState() {
        assertPartialRoundTrip(
                InitialProcessSpritesCoordinator.Checkpoint.AFTER_DYNAMIC_BEFORE_FIXED,
                ObjectManagerSnapshot.CollisionBuildStage.DYNAMIC_BUILD_COMPLETE);
    }

    @Test
    void successfulSetupAndFirstOrdinaryStateRemainConsumed() {
        InitialProcessSpritesLifecycleCoordinator lifecycle =
                new InitialProcessSpritesLifecycleCoordinator();
        ObjectManager objects = new ObjectManager(List.of(), s3kRegistry(), 0, null, null);
        lifecycle.publish(InitialProcessSpritesLifecycle.LOAD_THEN_PROCESS_ONCE);

        lifecycle.consume(() -> coordinator(ignored -> { }).execute(
                context(objects)));
        ObjectManagerSnapshot afterSetup = objects.rewindSnapshottable().capture();
        InitialProcessSpritesLifecycle afterSetupAuthority = lifecycle.captureForRewind();

        assertEquals(InitialProcessSpritesLifecycle.NONE, afterSetupAuthority);
        assertEquals(ObjectManagerSnapshot.CollisionBuildStage.COMPLETED,
                afterSetup.collisionResponseState().collisionBuildStage());

        int setupFrame = afterSetup.frameCounter();
        objects.update(0, null, List.of(), 0, false);
        ObjectManagerSnapshot afterFirstOrdinary = objects.rewindSnapshottable().capture();
        InitialProcessSpritesLifecycle afterFirstOrdinaryAuthority =
                lifecycle.captureForRewind();
        assertEquals(setupFrame + 1, afterFirstOrdinary.frameCounter());

        lifecycle.publish(InitialProcessSpritesLifecycle.LOAD_THEN_PROCESS_ONCE);
        objects.update(0, null, List.of(), 1, false);
        lifecycle.restoreForRewind(afterFirstOrdinaryAuthority);
        objects.rewindSnapshottable().restore(afterFirstOrdinary);

        assertEquals(InitialProcessSpritesLifecycle.NONE,
                lifecycle.captureForRewind());
        assertFalse(lifecycle.consume(() -> {
            throw new AssertionError("restored first ordinary frame re-armed setup");
        }));
        assertEquals(afterFirstOrdinary.frameCounter(),
                objects.rewindSnapshottable().capture().frameCounter());
    }

    private static void assertPartialRoundTrip(
            InitialProcessSpritesCoordinator.Checkpoint failurePoint,
            ObjectManagerSnapshot.CollisionBuildStage expectedStage) {
        InitialProcessSpritesLifecycleCoordinator lifecycle =
                new InitialProcessSpritesLifecycleCoordinator();
        ObjectManager objects = new ObjectManager(List.of(), s3kRegistry(), 0, null, null);
        lifecycle.publish(InitialProcessSpritesLifecycle.LOAD_THEN_PROCESS_ONCE);

        assertThrows(IllegalStateException.class,
                () -> lifecycle.consume(() -> coordinator(checkpoint -> {
                    if (checkpoint == failurePoint) {
                        throw new IllegalStateException("injected " + checkpoint);
                    }
                }).execute(context(objects))));

        assertEquals(InitialProcessSpritesLifecycle.NONE,
                lifecycle.captureForRewind(),
                "authority clears before either partial dispatch seam");
        assertFalse(objects.hasActiveInitialProcessSpritesDispatch(),
                "failure must close the object-dispatch scope");

        ObjectManagerSnapshot partial = objects.rewindSnapshottable().capture();
        InitialProcessSpritesLifecycle partialAuthority = lifecycle.captureForRewind();
        assertEquals(expectedStage,
                partial.collisionResponseState().collisionBuildStage());
        ObjectManagerSnapshot.CollisionResponseState collision =
                partial.collisionResponseState();

        lifecycle.publish(InitialProcessSpritesLifecycle.LOAD_THEN_PROCESS_ONCE);
        lifecycle.restoreForRewind(partialAuthority);
        objects.rewindSnapshottable().restore(partial);
        ObjectManagerSnapshot restored = objects.rewindSnapshottable().capture();

        assertEquals(collision, restored.collisionResponseState());
        assertEquals(InitialProcessSpritesLifecycle.NONE,
                lifecycle.captureForRewind());
        assertFalse(objects.hasActiveInitialProcessSpritesDispatch());
    }

    private static InitialProcessSpritesCoordinator coordinator(
            java.util.function.Consumer<InitialProcessSpritesCoordinator.Checkpoint> checkpoint) {
        return new InitialProcessSpritesCoordinator(checkpoint);
    }

    private static InitialProcessSpritesContext context(ObjectManager objects) {
        InitialDynamicSstDispatcher dynamic = new InitialDynamicSstDispatcher() {
            private InitialObjectDispatchScope scope;

            @Override
            public InitialObjectDispatchScope begin(ProcessSpritesEpoch epoch) {
                scope = objects.beginInitialProcessSprites(0, null, List.of());
                return scope;
            }

            @Override public void loadSprites() {
            }

            @Override public void processAbsoluteDynamicSlot3() {
                objects.processInitialAbsoluteDynamicSlot3(scope);
            }

            @Override public void processManagedDynamicSlots4Through93() {
                objects.processInitialDynamicSlots(scope);
            }
        };
        CollisionListSstDispatcher collision = new CollisionListSstDispatcher() {
            @Override public void freezePreviousReadView() {
                objects.freezeInitialCollisionResponseReadView();
            }

            @Override public void resetCurrentBuild() {
                objects.resetInitialCollisionResponseBuild();
            }

            @Override public void markDynamicBuildComplete() {
                objects.markInitialDynamicCollisionBuildComplete();
            }

            @Override public void captureCompletedBuild() {
                objects.captureInitialCollisionResponseBuild();
            }
        };
        return new InitialProcessSpritesContext(
                new InitialProcessSpritesStages(
                        dynamic,
                        (epoch, input) -> { },
                        collision,
                        epoch -> { }),
                new ProcessSpritesEpoch(0, 1, false));
    }

    private static ObjectRegistry s3kRegistry() {
        return new ObjectRegistry() {
            @Override public com.openggf.level.objects.ObjectInstance create(ObjectSpawn spawn) {
                return null;
            }

            @Override public void reportCoverage(List<ObjectSpawn> spawns) {
            }

            @Override public String getPrimaryName(int objectId) {
                return "test";
            }

            @Override public ObjectSlotLayout objectSlotLayout() {
                return ObjectSlotLayout.SONIC_3K;
            }
        };
    }
}
