package com.openggf.game.rewind;

import com.openggf.control.InputActionMasks;
import com.openggf.debug.playback.Bk2FrameInput;
import com.openggf.game.GameId;
import com.openggf.game.rewind.snapshot.ObjectManagerSnapshot;
import com.openggf.game.sonic2.constants.Sonic2AnimationIds;
import com.openggf.game.sonic2.constants.Sonic2ObjectIds;
import com.openggf.game.sonic2.objects.MonitorContentsObjectInstance;
import com.openggf.game.sonic2.objects.MonitorObjectInstance;
import com.openggf.game.sonic2.objects.Sonic2ObjectRegistry;
import com.openggf.game.sonic2.objects.badniks.BadnikProjectileInstance;
import com.openggf.game.sonic2.objects.badniks.BuzzerBadnikInstance;
import com.openggf.game.sonic2.objects.badniks.CoconutsBadnikInstance;
import com.openggf.game.sonic2.objects.badniks.MasherBadnikInstance;
import com.openggf.level.objects.AbstractBadnikInstance;
import com.openggf.level.objects.ExplosionObjectInstance;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.PerObjectRewindSnapshot;
import com.openggf.level.objects.TestObjectServices;
import com.openggf.level.objects.TouchCategory;
import com.openggf.level.objects.TouchResponseResult;
import com.openggf.physics.Sensor;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Green-only inventory scaffold for Sonic 2 live-object rewind regressions.
 *
 * <p>The Sonic 2 badnik package currently has four legacy owners that override the
 * no-context {@code captureRewindState()} / {@code restoreRewindState()} pair:
 *
 * <ul>
 *   <li>{@link MasherBadnikInstance}: concrete badnik. It owns a second movement
 *       representation, a {@code SubpixelMotion.State}, plus the jump origin
 *       {@code initialYPos}; both are separate from the inherited
 *       {@code currentX/currentY/xVelocity/yVelocity} view.</li>
 *   <li>{@link BuzzerBadnikInstance}: concrete badnik. Its body has no second
 *       movement representation; it uses only the inherited
 *       {@code currentX/currentY/xVelocity/yVelocity} fields. Its nested flame child
 *       has child-local coordinates, but that is a separate rewind owner.</li>
 *   <li>{@link CoconutsBadnikInstance}: concrete badnik. It has no subpixel or
 *       anchor/origin position copy and uses inherited {@code currentX/currentY}; it
 *       does intentionally shadow the inherited {@code yVelocity} with a local field.</li>
 *   <li>{@link BadnikProjectileInstance}: projectile base for concrete children
 *       nested under their owning badniks; the concrete projectile kinds are selected
 *       by its {@code ProjectileType} enum. It owns
 *       projectile-local {@code currentX/currentY/xVelocity/yVelocity} and a second
 *       {@code SubpixelMotion.State} representation. Badniks create these concrete
 *       projectile children through this common class rather than Java subclasses.</li>
 * </ul>
 *
 * <p>The reported "Snapper fish" maps to Masher in this repository: a repository
 * search contains no Snapper object or class, while EHZ object ID {@code 0x5C} is
 * {@link Sonic2ObjectIds#MASHER} and {@link Sonic2ObjectRegistry} registers that ID as
 * {@link MasherBadnikInstance}. Masher is therefore the concrete Snapper-report
 * reproduction target.
 */
class TestSonic2LiveObjectRewindRegressions {

    private static final TouchResponseResult MONITOR_TOUCH =
            new TouchResponseResult(0, 0x0E, 0x0E, TouchCategory.SPECIAL);

    @Test
    void monitorBreakRoundTripRestoresIntactStateByForcedReconstruction() {
        verifyMonitorBreakRoundTrip(false);
    }

    @Test
    void monitorBreakRoundTripWithWarmedReuseEnabledPreservesRequiredReconstruction() {
        verifyMonitorBreakRoundTrip(true);
    }

    @Test
    void rewindControllerReplaysMonitorBreakAtFrameBoundary() {
        RewindRoundTripHarness harness = RewindRoundTripHarness.buildPlaced(GameId.S2, 0x26);
        monitor(harness).update(0, null);
        var adapter = harness.objectManager().rewindSnapshottable();
        ObjectManagerSnapshot intact = adapter.capture();
        MonitorFixture fixture = monitorController(harness, List.of(
                input(0, 0), input(1, InputActionMasks.ACTION_A), input(2, 0)));
        RewindController controller = fixture.controller();
        assertEquals(0, controller.currentFrame());
        assertIntactOracle(harness, monitor(harness));

        controller.step();
        assertEquals(1, controller.currentFrame());
        assertBrokenOracle(harness);
        controller.step();
        assertEquals(2, controller.currentFrame());
        assertBrokenOracle(harness);

        controller.seekTo(1);
        assertEquals(1, controller.currentFrame());
        assertBrokenOracle(harness);
        assertBrokenRewardTargetsRegisteredPlayer(harness, fixture.player());

        controller.seekTo(0);
        assertEquals(0, controller.currentFrame());
        assertIntactOracle(harness, monitor(harness));
        ObjectManagerSnapshot restored = adapter.capture();
        assertTrue(RewindSnapshotDiff.diffKey("object-manager", intact, restored).isEmpty(),
                () -> RewindSnapshotDiff.diffKey("object-manager", intact, restored).toString());
    }

    @Test
    void rewindControllerStepsBackwardAcrossSixtyFrameMonitorBoundary() {
        RewindRoundTripHarness harness = RewindRoundTripHarness.buildPlaced(GameId.S2, 0x26);
        monitor(harness).update(0, null);
        List<Bk2FrameInput> rows = new ArrayList<>();
        for (int frame = 0; frame <= 62; frame++) {
            rows.add(input(frame, frame == 61 ? InputActionMasks.ACTION_A : 0));
        }
        MonitorFixture fixture = monitorController(harness, rows);
        RewindController controller = fixture.controller();
        assertEquals(0, controller.currentFrame());
        assertIntactOracle(harness, monitor(harness));

        for (int frame = 1; frame <= 62; frame++) {
            controller.step();
        }
        controller.seekTo(61);
        assertBrokenOracle(harness);
        assertTrue(controller.stepBackward());
        assertEquals(60, controller.currentFrame());
        assertIntactOracle(harness, monitor(harness));
        assertTrue(controller.stepBackward());
        assertEquals(59, controller.currentFrame());
        assertIntactOracle(harness, monitor(harness));
    }

    @Test
    void buildPlacedUsesCoherentSonic2PlacementIdentity() {
        RewindRoundTripHarness harness = RewindRoundTripHarness.buildPlaced(GameId.S2, 0x26);
        ObjectSpawn spawn = harness.objectManager().rewindSnapshottable()
                .capture().slots().getFirst().spawn();

        assertEquals(160, spawn.x());
        assertEquals(240, spawn.y());
        assertTrue(spawn.respawnTracked());
        assertEquals(0, spawn.layoutIndex());
        assertEquals((240 & 0x0FFF) | 0x8000, spawn.rawYWord());
    }

    @Test
    void legacyOwnersAndSnapperMappingRemainConcrete() {
        List<Class<?>> legacyOwners = List.of(
                MasherBadnikInstance.class,
                BuzzerBadnikInstance.class,
                CoconutsBadnikInstance.class,
                BadnikProjectileInstance.class);

        assertEquals(0x5C, Sonic2ObjectIds.MASHER);
        ObjectSpawn masherSpawn = new ObjectSpawn(0x578, 0x2D0, Sonic2ObjectIds.MASHER,
                0, 0, false, 0);
        assertInstanceOf(MasherBadnikInstance.class, new Sonic2ObjectRegistry().create(masherSpawn));

        assertTrue(legacyOwners.subList(0, 3).stream()
                .allMatch(AbstractBadnikInstance.class::isAssignableFrom));
        assertTrue(legacyOwners.stream().allMatch(owner -> {
            try {
                owner.getDeclaredMethod("captureRewindState");
                owner.getDeclaredMethod("restoreRewindState", PerObjectRewindSnapshot.class);
                return true;
            } catch (NoSuchMethodException exception) {
                return false;
            }
        }));
    }

    @Test
    void masherForcedReconstructionRestoresAuthoritativeSubpixelMovement() {
        RewindRoundTripHarness harness = RewindRoundTripHarness.build(GameId.S2);
        var adapter = harness.objectManager().rewindSnapshottable();
        ObjectSpawn spawn = new ObjectSpawn(160, 240, Sonic2ObjectIds.MASHER,
                0, 0, false, 0);
        MasherBadnikInstance masher = new MasherBadnikInstance(spawn);
        masher.setServices(new TestObjectServices());
        harness.objectManager().addDynamicObject(masher);

        PerObjectRewindSnapshot.MasherRewindExtra capturedMotion;
        int frame = 0;
        do {
            masher.update(frame++, null);
            capturedMotion = assertInstanceOf(PerObjectRewindSnapshot.MasherRewindExtra.class,
                    masher.captureRewindState().badnikSubclassExtra());
        } while (capturedMotion.motionYSub() == 0 && frame < 32);
        assertTrue(capturedMotion.motionYSub() != 0, "precondition: capture a fractional Y phase");

        PerObjectRewindSnapshot authoritative = masher.captureRewindState();
        PerObjectRewindSnapshot contextual = masher.captureRewindState(harness.captureContext());
        assertEquals(authoritative.badnikExtra(), contextual.badnikExtra());
        assertEquals(authoritative.badnikSubclassExtra(), contextual.badnikSubclassExtra());
        ObjectManagerSnapshot capturedManager = adapter.capture();
        ObjectManagerSnapshot.DynamicObjectEntry capturedEntry = capturedManager.dynamicObjects()
                .stream().filter(entry -> entry.className().equals(MasherBadnikInstance.class.getName()))
                .findFirst().orElseThrow();
        PerObjectRewindSnapshot captured = capturedEntry.state();
        assertEquals(authoritative.badnikExtra(), captured.badnikExtra());
        assertEquals(authoritative.badnikSubclassExtra(), captured.badnikSubclassExtra());
        var capturedBase = authoritative.badnikExtra();
        capturedMotion = assertInstanceOf(PerObjectRewindSnapshot.MasherRewindExtra.class,
                authoritative.badnikSubclassExtra());
        MasherBadnikInstance control = controlFromCaptured(spawn, capturedBase, capturedMotion);

        for (int i = 0; i < 7; i++) {
            masher.update(frame++, null);
        }
        harness.objectManager().setRewindInPlaceRestoreEnabledForTest(false);
        adapter.restore(capturedManager);
        MasherBadnikInstance restored = harness.objectManager().getActiveObjects().stream()
                .filter(MasherBadnikInstance.class::isInstance)
                .map(MasherBadnikInstance.class::cast)
                .findFirst().orElseThrow();
        assertNotSame(masher, restored);

        PerObjectRewindSnapshot immediate = restored.captureRewindState();
        assertEquals(authoritative.badnikExtra(), immediate.badnikExtra());
        assertEquals(authoritative.badnikSubclassExtra(), immediate.badnikSubclassExtra());
        assertEquals(capturedBase.currentX(), restored.getX());
        assertEquals(capturedBase.currentY(), restored.getY());

        for (int i = 0; i < 8; i++) {
            restored.update(frame + i, null);
            control.update(frame + i, null);
            PerObjectRewindSnapshot restoredFrame = restored.captureRewindState();
            PerObjectRewindSnapshot controlFrame = control.captureRewindState();
            assertEquals(controlFrame.badnikExtra(), restoredFrame.badnikExtra(),
                    "resumed base state at frame " + i);
            assertEquals(controlFrame.badnikSubclassExtra(), restoredFrame.badnikSubclassExtra(),
                    "resumed fixed-point state at frame " + i);
            assertEquals(control.getX(), restored.getX(), "resumed X at frame " + i);
            assertEquals(control.getY(), restored.getY(), "resumed Y at frame " + i);
        }
    }

    @Test
    void buzzerForcedReconstructionMatchesIndependentlyAdvancedControl() {
        RewindRoundTripHarness harness = RewindRoundTripHarness.build(GameId.S2);
        var adapter = harness.objectManager().rewindSnapshottable();
        ObjectSpawn spawn = new ObjectSpawn(160, 240, Sonic2ObjectIds.BUZZER,
                0, 0, false, 0);
        BuzzerBadnikInstance buzzer = new BuzzerBadnikInstance(spawn);
        buzzer.setServices(new TestObjectServices());
        harness.objectManager().addDynamicObject(buzzer);

        BuzzerBadnikInstance control = new BuzzerBadnikInstance(spawn);
        control.setServices(new TestObjectServices());
        DummyPlayer player = rollingPlayer();
        int frame = 0;
        for (; frame < 5; frame++) {
            buzzer.update(0, player);
            control.update(0, player);
        }

        PerObjectRewindSnapshot authoritative = buzzer.captureRewindState();
        PerObjectRewindSnapshot.BuzzerRewindExtra capturedSubclass = assertInstanceOf(
                PerObjectRewindSnapshot.BuzzerRewindExtra.class,
                authoritative.badnikSubclassExtra());
        assertFalse(capturedSubclass.initPending(), "precondition: init routine ran");
        assertTrue(capturedSubclass.moveTimer() < 0x100, "precondition: roaming timer advanced");
        assertTrue(authoritative.badnikExtra().xVelocity() != 0,
                "precondition: inherited movement state is non-default");

        ObjectManagerSnapshot capturedManager = adapter.capture();
        ObjectManagerSnapshot.DynamicObjectEntry capturedEntry = capturedManager.dynamicObjects()
                .stream().filter(entry -> entry.className().equals(BuzzerBadnikInstance.class.getName()))
                .findFirst().orElseThrow();
        assertEquals(authoritative.badnikExtra(), capturedEntry.state().badnikExtra());
        assertEquals(authoritative.badnikSubclassExtra(),
                capturedEntry.state().badnikSubclassExtra());
        List<String> capturedDynamicClasses = capturedManager.dynamicObjects().stream()
                .map(ObjectManagerSnapshot.DynamicObjectEntry::className).toList();
        List<String> capturedActiveClasses = harness.objectManager().getActiveObjects().stream()
                .map(object -> object.getClass().getName()).toList();

        for (int i = 0; i < 7; i++) {
            buzzer.update(0, player);
            frame++;
        }
        harness.objectManager().setRewindInPlaceRestoreEnabledForTest(false);
        adapter.restore(capturedManager);
        BuzzerBadnikInstance restored = harness.objectManager().getActiveObjects().stream()
                .filter(BuzzerBadnikInstance.class::isInstance)
                .map(BuzzerBadnikInstance.class::cast)
                .findFirst().orElseThrow();
        assertNotSame(buzzer, restored);

        ObjectManagerSnapshot recapturedManager = adapter.capture();
        PerObjectRewindSnapshot immediate = restored.captureRewindState();
        assertEquals(authoritative.badnikExtra(), immediate.badnikExtra());
        assertEquals(authoritative.badnikSubclassExtra(), immediate.badnikSubclassExtra());
        assertArrayEquals(capturedManager.usedSlotsBits(), recapturedManager.usedSlotsBits());
        assertEquals(capturedDynamicClasses, recapturedManager.dynamicObjects().stream()
                .map(ObjectManagerSnapshot.DynamicObjectEntry::className).toList());
        assertEquals(capturedActiveClasses, harness.objectManager().getActiveObjects().stream()
                .map(object -> object.getClass().getName()).toList());

        for (int i = 0; i < 8; i++) {
            restored.update(0, player);
            control.update(0, player);
            PerObjectRewindSnapshot restoredFrame = restored.captureRewindState();
            PerObjectRewindSnapshot controlFrame = control.captureRewindState();
            assertEquals(controlFrame.badnikExtra(), restoredFrame.badnikExtra(),
                    "complete base state at resumed frame " + i);
            assertEquals(controlFrame.badnikSubclassExtra(), restoredFrame.badnikSubclassExtra(),
                    "complete subclass state at resumed frame " + i);
            assertEquals(control.getX(), restored.getX(),
                    "position X at resumed frame " + i);
            assertEquals(control.getY(), restored.getY(),
                    "position Y at resumed frame " + i);
            assertEquals(controlFrame.badnikExtra().xVelocity(),
                    restoredFrame.badnikExtra().xVelocity(), "velocity X at resumed frame " + i);
            assertEquals(controlFrame.badnikExtra().yVelocity(),
                    restoredFrame.badnikExtra().yVelocity(), "velocity Y at resumed frame " + i);
            assertEquals(controlFrame.badnikExtra().animFrame(),
                    restoredFrame.badnikExtra().animFrame(), "animation at resumed frame " + i);
            assertEquals(controlFrame.badnikExtra().facingLeft(),
                    restoredFrame.badnikExtra().facingLeft(), "facing at resumed frame " + i);
        }
    }

    private static MasherBadnikInstance controlFromCaptured(ObjectSpawn spawn,
            PerObjectRewindSnapshot.BadnikRewindExtra base,
            PerObjectRewindSnapshot.MasherRewindExtra motion) {
        MasherBadnikInstance control = new MasherBadnikInstance(spawn);
        control.setServices(new TestObjectServices());
        setField(control, "currentX", base.currentX());
        setField(control, "currentY", base.currentY());
        setField(control, "xVelocity", base.xVelocity());
        setField(control, "yVelocity", base.yVelocity());
        setField(control, "animTimer", base.animTimer());
        setField(control, "animFrame", base.animFrame());
        setBooleanField(control, "facingLeft", base.facingLeft());
        setField(control, "initialYPos", motion.initialYPos());
        Object state = field(control, "motionState");
        setField(state, "x", motion.motionX());
        setField(state, "y", motion.motionY());
        setField(state, "xSub", motion.motionXSub());
        setField(state, "ySub", motion.motionYSub());
        setField(state, "xVel", motion.motionXVel());
        setField(state, "yVel", motion.motionYVel());
        return control;
    }

    private static Object field(Object target, String name) {
        for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(target);
            } catch (NoSuchFieldException ignored) {
                // Continue through inherited rewind state.
            } catch (IllegalAccessException exception) {
                throw new RuntimeException(exception);
            }
        }
        throw new IllegalArgumentException("Missing field " + name);
    }

    private static void setBooleanField(Object target, String name, boolean value) {
        for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                field.setBoolean(target, value);
                return;
            } catch (NoSuchFieldException ignored) {
                // Continue through inherited rewind state.
            } catch (IllegalAccessException exception) {
                throw new RuntimeException(exception);
            }
        }
        throw new IllegalArgumentException("Missing field " + name);
    }

    private static void setField(Object target, String name, int value) {
        for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                field.setInt(target, value);
                return;
            } catch (NoSuchFieldException ignored) {
                // Continue through inherited rewind state.
            } catch (IllegalAccessException exception) {
                throw new RuntimeException(exception);
            }
        }
        throw new IllegalArgumentException("Missing field " + name);
    }

    private static void verifyMonitorBreakRoundTrip(boolean warmedReuse) {
        RewindRoundTripHarness harness = RewindRoundTripHarness.buildPlaced(GameId.S2, 0x26);
        var adapter = harness.objectManager().rewindSnapshottable();
        MonitorObjectInstance monitor = monitor(harness);
        monitor.update(0, null);

        if (warmedReuse) {
            ObjectManagerSnapshot preliminary = adapter.capture();
            adapter.restore(preliminary);
            monitor = monitor(harness);
            monitor.update(0, null);
        }

        ObjectManagerSnapshot intact = adapter.capture();
        MonitorObjectInstance intactIdentity = monitor;
        DummyPlayer player = rollingPlayer();
        monitor.onTouchResponse(player, MONITOR_TOUCH, 1);

        assertEquals(0, monitor.getCollisionFlags());
        assertTrue(isBroken(monitor));
        ObjectManagerSnapshot broken = adapter.capture();
        assertFalse(Arrays.equals(intact.placement().rememberedBits(),
                broken.placement().rememberedBits()));
        assertHasLive(harness, MonitorContentsObjectInstance.class);
        assertHasLive(harness, ExplosionObjectInstance.class);

        harness.objectManager().setRewindInPlaceRestoreEnabledForTest(warmedReuse);
        adapter.restore(intact);
        MonitorObjectInstance restored = monitor(harness);
        if (warmedReuse) {
            assertFalse(com.openggf.level.objects.ObjectManager
                    .isRewindInPlaceReuseSafeClass(MonitorObjectInstance.class),
                    "AbstractMonitorObjectInstance's custom effectTarget rewind override "
                            + "requires reconstruction even after side-effect warm-up");
            assertNotSame(intactIdentity, restored,
                    "enabling reuse must preserve reconstruction for an ineligible monitor");
        } else {
            assertNotSame(intactIdentity, restored, "forced restore should reconstruct the monitor");
        }

        assertIntactOracle(harness, restored);
        ObjectManagerSnapshot recaptured = adapter.capture();
        assertPlacementEquals(intact.placement(), recaptured.placement());
        assertArrayEquals(intact.usedSlotsBits(), recaptured.usedSlotsBits());
        assertEquals(intact.slots().stream().map(ObjectManagerSnapshot.PerSlotEntry::slotIndex).toList(),
                recaptured.slots().stream().map(ObjectManagerSnapshot.PerSlotEntry::slotIndex).toList());
        assertEquals(intact.dynamicObjects().stream()
                        .map(ObjectManagerSnapshot.DynamicObjectEntry::className).toList(),
                recaptured.dynamicObjects().stream()
                        .map(ObjectManagerSnapshot.DynamicObjectEntry::className).toList());
        assertTrue(RewindSnapshotDiff.diffKey("object-manager", intact, recaptured).isEmpty(),
                () -> RewindSnapshotDiff.diffKey("object-manager", intact, recaptured).toString());

        harness.objectManager().update(0, player, null, 2, false);
        assertIntactOracle(harness, monitor(harness));
    }

    private static MonitorFixture monitorController(RewindRoundTripHarness harness,
            List<Bk2FrameInput> rows) {
        DummyPlayer player = rollingPlayer();
        harness.spriteManager().addSprite(player);
        RewindRegistry registry = new RewindRegistry();
        registry.register(new RewindSnapshottable<PlayerBoundarySnapshot>() {
            @Override public String key() { return "monitor-player"; }
            @Override public PlayerBoundarySnapshot capture() {
                return new PlayerBoundarySnapshot(player.getAnimationId(), player.getRolling(),
                        player.getYSpeed(), player.getAir(), player.isHurt());
            }
            @Override public void restore(PlayerBoundarySnapshot state) {
                player.setAnimationId(state.animationId());
                player.setRolling(state.rolling());
                player.setYSpeed(state.ySpeed());
                player.setAir(state.air());
                player.setHurt(state.hurt());
            }
        });
        registry.register(harness.objectManager().rewindSnapshottable());
        EngineStepper stepper = input -> {
            harness.objectManager().update(0, player, List.of(), input.frameIndex(), false);
            if ((input.p1ActionMask() & InputActionMasks.ACTION_A) != 0) {
                // Restoration reconstructs placed objects, so resolve the live monitor on every tick.
                monitor(harness).onTouchResponse(player, MONITOR_TOUCH, input.frameIndex());
            }
            return com.openggf.LevelFrameResult.GAMEPLAY_FRAME;
        };
        return new MonitorFixture(new RewindController(registry, new InMemoryKeyframeStore(),
                new ListInputSource(rows), stepper, 60), player);
    }

    private static Bk2FrameInput input(int frame, int actionMask) {
        return new Bk2FrameInput(frame, 0, actionMask, false, "monitor-boundary");
    }

    private record ListInputSource(List<Bk2FrameInput> rows) implements InputSource {
        private ListInputSource {
            rows = List.copyOf(rows);
        }

        @Override public int frameCount() { return rows.size(); }
        @Override public Bk2FrameInput read(int frame) { return rows.get(frame); }
    }

    private record MonitorFixture(RewindController controller, DummyPlayer player) {
    }

    private record PlayerBoundarySnapshot(int animationId, boolean rolling, short ySpeed,
            boolean air, boolean hurt) {
    }

    private static void assertBrokenOracle(RewindRoundTripHarness harness) {
        assertTrue(isBroken(monitor(harness)));
        assertHasLive(harness, MonitorContentsObjectInstance.class);
        assertHasLive(harness, ExplosionObjectInstance.class);
    }

    private static void assertBrokenRewardTargetsRegisteredPlayer(RewindRoundTripHarness harness,
            DummyPlayer player) {
        assertSame(player, harness.spriteManager().getSprite(player.getCode()));
        assertFalse(player.isHurt(), "static-monitor reward has not fired at the break boundary");
        MonitorContentsObjectInstance contents = harness.objectManager().getActiveObjects().stream()
                .filter(MonitorContentsObjectInstance.class::isInstance)
                .map(MonitorContentsObjectInstance.class::cast)
                .filter(object -> !object.isDestroyed())
                .findFirst().orElseThrow();
        for (int frame = 0; frame < 40 && !player.isHurt(); frame++) {
            contents.update(frame, player);
        }
        assertTrue(player.isHurt(),
                "restored monitor contents must resolve its effect target through SpriteManager");
    }

    private static DummyPlayer rollingPlayer() {
        DummyPlayer player = new DummyPlayer();
        player.setAnimationId(Sonic2AnimationIds.ROLL);
        player.setRolling(true);
        player.setYSpeed((short) 0x0120);
        return player;
    }

    private static MonitorObjectInstance monitor(RewindRoundTripHarness harness) {
        return harness.objectManager().getActiveObjects().stream()
                .filter(MonitorObjectInstance.class::isInstance)
                .map(MonitorObjectInstance.class::cast)
                .filter(object -> !object.isDestroyed())
                .findFirst().orElseThrow();
    }

    private static void assertIntactOracle(RewindRoundTripHarness harness,
            MonitorObjectInstance monitor) {
        assertFalse(isBroken(monitor));
        assertEquals(0x46, monitor.getCollisionFlags());
        assertNoLive(harness, MonitorContentsObjectInstance.class);
        assertNoLive(harness, ExplosionObjectInstance.class);
    }

    private static void assertHasLive(RewindRoundTripHarness harness, Class<?> type) {
        assertTrue(harness.objectManager().getActiveObjects().stream()
                .anyMatch(object -> type.isInstance(object) && !object.isDestroyed()));
    }

    private static void assertNoLive(RewindRoundTripHarness harness, Class<?> type) {
        assertFalse(harness.objectManager().getActiveObjects().stream()
                .anyMatch(object -> type.isInstance(object) && !object.isDestroyed()));
    }

    private static void assertPlacementEquals(ObjectManagerSnapshot.PlacementSnapshot expected,
            ObjectManagerSnapshot.PlacementSnapshot actual) {
        assertArrayEquals(expected.activeSpawnIndices(), actual.activeSpawnIndices());
        assertArrayEquals(expected.rememberedBits(), actual.rememberedBits());
        assertArrayEquals(expected.stayActiveBits(), actual.stayActiveBits());
        assertArrayEquals(expected.destroyedInWindowBits(), actual.destroyedInWindowBits());
        assertArrayEquals(expected.dormantBits(), actual.dormantBits());
        assertEquals(expected.cursorIndex(), actual.cursorIndex());
        assertEquals(expected.lastCameraX(), actual.lastCameraX());
        assertEquals(expected.lastCameraChunk(), actual.lastCameraChunk());
        assertEquals(expected.counterBasedRespawn(), actual.counterBasedRespawn());
        assertEquals(expected.execThenLoadPlacement(), actual.execThenLoadPlacement());
        assertEquals(expected.permanentDestroyLatch(), actual.permanentDestroyLatch());
        assertEquals(expected.maxDynamicSlots(), actual.maxDynamicSlots());
        assertEquals(expected.lastScrollBackward(), actual.lastScrollBackward());
        assertEquals(expected.leftCursorIndex(), actual.leftCursorIndex());
        assertEquals(expected.fwdCounter(), actual.fwdCounter());
        assertEquals(expected.bwdCounter(), actual.bwdCounter());
        assertArrayEquals(expected.objState(), actual.objState());
        assertEquals(expected.spawnCounters(), actual.spawnCounters());
        assertArrayEquals(expected.pendingCursorLoadBits(), actual.pendingCursorLoadBits());
        assertArrayEquals(expected.pendingCursorLoadOrder(), actual.pendingCursorLoadOrder());
        assertArrayEquals(expected.deferredVerticalLoadBits(), actual.deferredVerticalLoadBits());
        assertEquals(expected.twoAxisCameraYCoarse(), actual.twoAxisCameraYCoarse());
        assertEquals(expected.s2LatchedCameraX(), actual.s2LatchedCameraX());
    }

    private static boolean isBroken(MonitorObjectInstance monitor) {
        try {
            Field field = MonitorObjectInstance.class.getDeclaredField("broken");
            field.setAccessible(true);
            return field.getBoolean(monitor);
        } catch (ReflectiveOperationException exception) {
            throw new RuntimeException(exception);
        }
    }

    private static final class DummyPlayer extends AbstractPlayableSprite {
        private DummyPlayer() {
            super("sonic", (short) 0x0100, (short) 0x0100);
        }

        @Override protected void defineSpeeds() {
            runAccel = 0;
            runDecel = 0;
            friction = 0;
            max = 0;
            jump = 0;
            angle = 0;
            slopeRunning = 0;
            slopeRollingDown = 0;
            slopeRollingUp = 0;
            rollDecel = 0;
            minStartRollSpeed = 0;
            minRollSpeed = 0;
            maxRoll = 0;
            rollHeight = 0;
            runHeight = 0;
        }

        @Override protected void createSensorLines() {
            groundSensors = new Sensor[0];
            ceilingSensors = new Sensor[0];
            pushSensors = new Sensor[0];
        }

        @Override public void draw() {
        }
    }
}
