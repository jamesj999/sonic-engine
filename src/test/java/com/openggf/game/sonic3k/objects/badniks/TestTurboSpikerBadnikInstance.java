package com.openggf.game.sonic3k.objects.badniks;

import com.openggf.game.session.SessionManager;
import com.openggf.game.sonic3k.audio.Sonic3kSfx;
import com.openggf.game.sonic3k.constants.Sonic3kObjectIds;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.ObjectPlayerQuery;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.StubObjectServices;
import com.openggf.level.objects.TouchResponseProvider;
import com.openggf.physics.ObjectTerrainUtils;
import com.openggf.physics.TerrainCheckResult;
import com.openggf.tests.FullReset;
import com.openggf.tests.SingletonResetExtension;
import com.openggf.tests.TestablePlayableSprite;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.api.parallel.Isolated;
import org.mockito.MockedStatic;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;

@ExtendWith(SingletonResetExtension.class)
@FullReset
@Isolated
@Execution(ExecutionMode.SAME_THREAD)
public class TestTurboSpikerBadnikInstance {

    @BeforeEach
    public void setUp() {
        SessionManager.clear();
        AbstractObjectInstance.updateCameraBounds(0, 0, 320, 224, 0);
    }

    @AfterEach
    public void tearDown() {
        SessionManager.clear();
    }

    @Test
    public void approachingPlayerTriggersShellLaunchSequence() throws Exception {
        try (MockedStatic<ObjectTerrainUtils> ignored = mockWalkableFloor()) {
            RecordingServices services = new RecordingServices();
            TurboSpikerBadnikInstance turboSpiker = new TurboSpikerBadnikInstance(
                    new ObjectSpawn(0x120, 0x100, Sonic3kObjectIds.TURBO_SPIKER, 0x20, 0, false, 0));
            turboSpiker.setServices(services);

            TestablePlayableSprite player = new TestablePlayableSprite("sonic", (short) 0xE0, (short) 0x100);

            turboSpiker.update(0, player);
            assertEquals("PATROL", readState(turboSpiker));
            assertEquals(0, services.spawnedChildren.size());

            turboSpiker.update(1, player);
            assertEquals(1, services.spawnedChildren.size());

            turboSpiker.update(2, player);
            assertEquals("LAUNCH_PREP", readState(turboSpiker));

            for (int frame = 3; frame <= 19; frame++) {
                turboSpiker.update(frame, player);
            }

            assertEquals("SHELLLESS_RUN", readState(turboSpiker));
            assertTrue(services.playedSfx.contains(Sonic3kSfx.FLOOR_LAUNCHER.id), "Expected shell launch SFX");
            assertTrue(services.spawnedChildren.size() >= 2, "Expected shell trail child after launch");

            ObjectInstance shell = services.spawnedChildren.get(0);
            int shellXBeforeMove = shell.getX();
            int shellYBeforeMove = shell.getY();
            // ROM loc_87D72 installs loc_87DA4 into the child's own (a0) and ends at
            // Sprite_CheckDeleteTouchXY, so the launch dispatch itself never moves the
            // shell (docs/skdisasm/sonic3k.asm:184042-184058).
            shell.update(20, player);
            assertEquals(shellXBeforeMove, shell.getX(),
                    "Launch dispatch installs the move routine without moving");
            assertEquals(shellYBeforeMove, shell.getY(),
                    "Launch dispatch installs the move routine without moving");
            shell.update(21, player);
            assertEquals(shellXBeforeMove - 1, shell.getX(),
                    "Detached shell launches opposite the parent's rightward retreat");
            assertEquals(shellYBeforeMove - 4, shell.getY(),
                    "Detached shell rises at the ROM's -$400 y_vel");
        }
    }

    @Test
    public void hiddenVariantSpawnsOverlayThenEmergesWithSplashBurst() throws Exception {
        try (MockedStatic<ObjectTerrainUtils> ignored = mockWalkableFloor()) {
            RecordingServices services = new RecordingServices();
            TurboSpikerBadnikInstance turboSpiker = new TurboSpikerBadnikInstance(
                    new ObjectSpawn(0x120, 0x100, Sonic3kObjectIds.TURBO_SPIKER, 0x30, 0x02, false, 0));
            turboSpiker.setServices(services);

            TestablePlayableSprite player = new TestablePlayableSprite("sonic", (short) 0x100, (short) 0x100);

            turboSpiker.update(0, player);
            assertEquals("HIDDEN_WAIT", readState(turboSpiker));
            assertEquals(0, services.spawnedChildren.size());

            turboSpiker.update(1, player);
            assertEquals(2, services.spawnedChildren.size());

            turboSpiker.update(2, player);
            assertEquals("EMERGE_DELAY", readState(turboSpiker));
            assertEquals(7, services.spawnedChildren.size());
            assertTrue(services.playedSfx.contains(Sonic3kSfx.SPLASH.id), "Expected splash SFX");

            for (int frame = 3; frame <= 6; frame++) {
                turboSpiker.update(frame, player);
            }
            assertEquals("EMERGE_WATERFALL", readState(turboSpiker));
            assertEquals(3, turboSpiker.getPriorityBucket());

            player.setCentreX((short) 0x40);
            for (int frame = 7; frame <= 23; frame++) {
                turboSpiker.update(frame, player);
            }
            assertEquals("PATROL", readState(turboSpiker));
            assertEquals(5, turboSpiker.getPriorityBucket());
        }
    }

    @Test
    public void wrappedSidekickNearestByRomXTriggersShellLaunch() throws Exception {
        try (MockedStatic<ObjectTerrainUtils> ignored = mockWalkableFloor()) {
            AbstractObjectInstance.updateCameraBounds(0xFF00, 0, 0x10080, 224, 0);
            RecordingServices services = new RecordingServices();
            TurboSpikerBadnikInstance turboSpiker = new TurboSpikerBadnikInstance(
                    new ObjectSpawn(0xFFF0, 0x100, Sonic3kObjectIds.TURBO_SPIKER, 0x20, 0, false, 0));
            turboSpiker.setServices(services);

            TestablePlayableSprite player = new TestablePlayableSprite("sonic", (short) 0x7FFF, (short) 0x100);
            TestablePlayableSprite sidekick = new TestablePlayableSprite("tails", (short) 0x0010, (short) 0x100);
            services.sidekicks = List.of(sidekick);

            turboSpiker.update(0, player);
            assertEquals("PATROL", readState(turboSpiker));

            turboSpiker.update(1, player);
            assertEquals("PATROL", readState(turboSpiker));

            turboSpiker.update(2, player);
            assertEquals("LAUNCH_PREP", readState(turboSpiker));
        }
    }

    @Test
    public void deadWrappedSidekickIsIgnoredForShellLaunch() throws Exception {
        try (MockedStatic<ObjectTerrainUtils> ignored = mockWalkableFloor()) {
            AbstractObjectInstance.updateCameraBounds(0xFF00, 0, 0x10080, 224, 0);
            RecordingServices services = new RecordingServices();
            TurboSpikerBadnikInstance turboSpiker = new TurboSpikerBadnikInstance(
                    new ObjectSpawn(0xFFF0, 0x100, Sonic3kObjectIds.TURBO_SPIKER, 0x20, 0, false, 0));
            turboSpiker.setServices(services);

            TestablePlayableSprite player = new TestablePlayableSprite("sonic", (short) 0x7FFF, (short) 0x100);
            TestablePlayableSprite sidekick = new TestablePlayableSprite("tails", (short) 0x0010, (short) 0x100);
            sidekick.setDead(true);
            services.sidekicks = List.of(sidekick);

            turboSpiker.update(0, player);
            assertEquals("PATROL", readState(turboSpiker));

            turboSpiker.update(1, player);
            assertEquals("PATROL", readState(turboSpiker));

            turboSpiker.update(2, player);
            assertEquals("PATROL", readState(turboSpiker));
        }
    }

    @Test
    public void launchedShellUsesRomCoarseAsymmetricDeleteBounds() throws Exception {
        assertShellDeleteState(0x27F, 0x180, false,
                "ROM keeps the final coarse X bucket and inclusive lower Y boundary");
        assertShellDeleteState(0x280, 0, true,
                "ROM rejects the first X bucket beyond the $280 unsigned window");
        assertShellDeleteState(0, -0x80, false,
                "ROM keeps the inclusive upper Y boundary");
        assertShellDeleteState(0, -0x81, true,
                "ROM rejects the first Y coordinate above the asymmetric window");
        assertShellDeleteState(0, 0x181, true,
                "ROM rejects the first Y coordinate below the inclusive $200 distance");
    }

    @Test
    public void launchedShellAndTrailHoldRomDeleteMarkerForOneExecution() throws Exception {
        LaunchedShellGraph graph = launchedShellAt(0x280, 0);

        graph.shell().update(20, graph.player());

        assertFalse(graph.shell().isDestroyed(),
                "Go_Delete_Sprite installs a delete operation without freeing the shell slot immediately");
        assertTrue(readBooleanField(graph.shell(), "deleteNextFrame"),
                "shell must expose the ROM status-bit-7 delete marker");
        assertEquals(0, ((TouchResponseProvider) graph.shell()).getCollisionFlags(),
                "delete-marked shell must leave the collision response list");
        assertTrue(graph.shell().isPersistent(),
                "shell lifetime must be owned by Sprite_CheckDeleteTouchXY, not generic dynamic culling");
        assertTrue(graph.trail().isPersistent(),
                "loc_87DC0 trail lifetime must be owned by the shell status marker, not generic culling");

        graph.trail().update(20, graph.player());
        assertFalse(graph.trail().isDestroyed(),
                "trail Go_Delete_Sprite marker must also retain its slot until the next execution");
        assertTrue(readBooleanField(graph.trail(), "deleteNextFrame"),
                "loc_87DC0 must observe the shell status marker in the same object pass");

        graph.shell().update(21, graph.player());
        graph.trail().update(21, graph.player());
        assertTrue(graph.shell().isDestroyed(), "shell delete operation must free on its next execution");
        assertTrue(graph.trail().isDestroyed(), "trail delete operation must free on its next execution");
    }

    @Test
    public void attachedShellDoesNotClaimIndependentLifetime() {
        RecordingServices services = new RecordingServices();
        TurboSpikerBadnikInstance turboSpiker = new TurboSpikerBadnikInstance(
                new ObjectSpawn(0x120, 0x100, Sonic3kObjectIds.TURBO_SPIKER, 0x20, 0, false, 0));
        turboSpiker.setServices(services);
        TestablePlayableSprite player = new TestablePlayableSprite("sonic", (short) 0x300, (short) 0x100);
        turboSpiker.update(0, player);
        turboSpiker.update(1, player);

        ObjectInstance attachedShell = services.spawnedChildren.getFirst();

        assertFalse(attachedShell.isPersistent(),
                "loc_87D5E attached shell must remain owned by the placed badnik and its load window");
    }

    private void assertShellDeleteState(int x, int y, boolean expectedDeletePending, String message)
            throws Exception {
        LaunchedShellGraph graph = launchedShellAt(x, y);

        graph.shell().update(20, graph.player());

        assertFalse(graph.shell().isDestroyed(), message + ": slot must not be freed in the marking frame");
        assertEquals(expectedDeletePending, readBooleanField(graph.shell(), "deleteNextFrame"), message);
    }

    private LaunchedShellGraph launchedShellAt(int x, int y) throws Exception {
        RecordingServices services = new RecordingServices();
        TurboSpikerBadnikInstance turboSpiker = new TurboSpikerBadnikInstance(
                new ObjectSpawn(0x120, 0x100, Sonic3kObjectIds.TURBO_SPIKER, 0x20, 0, false, 0));
        turboSpiker.setServices(services);
        TestablePlayableSprite player = new TestablePlayableSprite("sonic", (short) 0x300, (short) 0x100);
        turboSpiker.update(0, player);
        turboSpiker.update(1, player);

        ObjectInstance shell = services.spawnedChildren.getFirst();
        invokeNoArg(shell, "launch");
        ObjectInstance trail = services.spawnedChildren.getLast();
        writeIntField(shell, "currentX", x);
        writeIntField(shell, "currentY", y);
        writeIntField(shell, "xVelocity", 0);
        writeIntField(shell, "yVelocity", 0);
        writeIntField(shell, "xSubpixel", 0);
        writeIntField(shell, "ySubpixel", 0);
        return new LaunchedShellGraph(shell, trail, player);
    }

    private record LaunchedShellGraph(
            ObjectInstance shell,
            ObjectInstance trail,
            TestablePlayableSprite player) {
    }

    private static String readState(TurboSpikerBadnikInstance turboSpiker) throws Exception {
        Field field = TurboSpikerBadnikInstance.class.getDeclaredField("state");
        field.setAccessible(true);
        return String.valueOf(field.get(turboSpiker));
    }

    private static boolean readBooleanField(Object target, String fieldName) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        return field.getBoolean(target);
    }

    private static void writeIntField(Object target, String fieldName, int value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.setInt(target, value);
    }

    private static void invokeNoArg(Object target, String methodName) throws Exception {
        Method method = target.getClass().getDeclaredMethod(methodName);
        method.setAccessible(true);
        method.invoke(target);
    }

    private static MockedStatic<ObjectTerrainUtils> mockWalkableFloor() {
        MockedStatic<ObjectTerrainUtils> terrain = mockStatic(ObjectTerrainUtils.class);
        terrain.when(() -> ObjectTerrainUtils.checkFloorDist(anyInt(), anyInt(), anyInt()))
                .thenReturn(new TerrainCheckResult(0, (byte) 0, 0));
        return terrain;
    }

    private static final class RecordingServices extends StubObjectServices {
        private final List<Integer> playedSfx = new ArrayList<>();
        private final List<ObjectInstance> spawnedChildren = new ArrayList<>();
        private final ObjectManager objectManager;
        private List<com.openggf.game.PlayableEntity> sidekicks = List.of();

        private RecordingServices() {
            objectManager = mock(ObjectManager.class);
            withPlayerQuery(new ObjectPlayerQuery(() -> null, this::sidekicks));
            doAnswer(invocation -> {
                ObjectInstance child = invocation.getArgument(0);
                if (child instanceof AbstractObjectInstance instance) {
                    instance.setServices(this);
                }
                spawnedChildren.add(child);
                return null;
            }).when(objectManager).addDynamicObjectAfterCurrent(any());
        }

        @Override
        public ObjectManager objectManager() {
            return objectManager;
        }

        @Override
        public void playSfx(int soundId) {
            playedSfx.add(soundId);
        }

        @Override
        public List<com.openggf.game.PlayableEntity> sidekicks() {
            return sidekicks;
        }
    }
}
