package com.openggf.game.sonic2.objects;

import com.openggf.camera.Camera;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.TestObjectServices;
import com.openggf.sprites.playable.SidekickCpuController;
import com.openggf.tests.TestablePlayableSprite;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestLauncherBallObjectInstance {

    @Test
    void launcherProcessesUniqueEngineParticipantsOncePerFrame() {
        LauncherBallObjectInstance launcher = newLauncher();
        TestablePlayableSprite main = playerAtLauncher("sonic");
        TestablePlayableSprite tails = playerAtLauncher("tails");
        TestablePlayableSprite knuckles = playerAtLauncher("knuckles");
        Camera camera = new Camera();
        camera.setFocusedSprite(main);
        launcher.setServices(new TestObjectServices()
                .withCamera(camera)
                .withSidekicks(List.of(tails, knuckles, tails)));

        launcher.update(0, main);

        assertTrue(main.isObjectControlled(), "Main player should be captured");
        assertTrue(tails.isObjectControlled(), "First sidekick should be captured");
        assertTrue(knuckles.isObjectControlled(), "Additional engine sidekick should be captured");
        assertEquals(0, mappingFrame(launcher),
                "Duplicate sidekick entries must not advance shared launcher animation in the capture frame");
    }

    @Test
    void launcherCapturesAirborneCpuTailsInNormalCpuRoutine() {
        LauncherBallObjectInstance launcher = newLauncher();
        TestablePlayableSprite sonic = playerAtLauncher("sonic");
        TestablePlayableSprite tails = airborneCpuTails(SidekickCpuController.State.NORMAL);
        launcher.setServices(new TestObjectServices()
                .withCamera(cameraFocusedOn(sonic))
                .withSidekicks(List.of(tails)));

        launcher.update(0, sonic);

        assertTrue(tails.isObjectControlled(),
                "Obj48 only rejects Sidekick when Tails_CPU_routine == 4; routine 6 must capture");
        assertEquals(0, tails.getYSpeed(),
                "Obj48 capture clears y_vel after snapping the player into the launcher");
        assertTrue(tails.isOnObject(),
                "Obj48 capture sets the on_object bit while the launcher owns the player");
        assertEquals(0x48, tails.getLatchedSolidObjectId(),
                "Obj48 capture writes interact/latched object to the launcher object id");
        assertEquals(launcher, tails.getLatchedSolidObjectInstance(),
                "Obj48 capture must latch the live launcher instance for sidekick CPU interact");
    }

    @Test
    void launcherSkipsCpuTailsOnlyInFlyingCpuRoutine() {
        LauncherBallObjectInstance launcher = newLauncher();
        TestablePlayableSprite sonic = playerAtLauncher("sonic");
        TestablePlayableSprite tails = airborneCpuTails(SidekickCpuController.State.FLIGHT_AUTO_RECOVERY);
        launcher.setServices(new TestObjectServices()
                .withCamera(cameraFocusedOn(sonic))
                .withSidekicks(List.of(tails)));

        launcher.update(0, sonic);

        assertEquals(-0x0AB8, tails.getYSpeed(),
                "Tails_CPU_routine == 4 follows the ROM skip and leaves velocity untouched");
    }

    private static LauncherBallObjectInstance newLauncher() {
        return new LauncherBallObjectInstance(
                new ObjectSpawn(0x1000, 0x1000, 0x48, 0, 0, false, 0),
                "LauncherBall");
    }

    private static TestablePlayableSprite playerAtLauncher(String code) {
        TestablePlayableSprite player = new TestablePlayableSprite(code, (short) 0x1000, (short) 0x1000);
        player.setCentreX((short) 0x1000);
        player.setCentreY((short) 0x1000);
        player.setAir(false);
        return player;
    }

    private static TestablePlayableSprite airborneCpuTails(SidekickCpuController.State state) {
        TestablePlayableSprite tails = playerAtLauncher("tails");
        tails.setCpuControlled(true);
        tails.setAir(true);
        tails.setRolling(false);
        tails.setYSpeed((short) -0x0AB8);
        SidekickCpuController controller = new SidekickCpuController(tails, null);
        forceState(controller, state);
        return tails;
    }

    private static Camera cameraFocusedOn(TestablePlayableSprite player) {
        Camera camera = new Camera();
        camera.setFocusedSprite(player);
        return camera;
    }

    private static int mappingFrame(LauncherBallObjectInstance launcher) {
        try {
            Field field = LauncherBallObjectInstance.class.getDeclaredField("mappingFrame");
            field.setAccessible(true);
            return field.getInt(launcher);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Failed to read launcher mappingFrame", e);
        }
    }

    private static void forceState(SidekickCpuController controller, SidekickCpuController.State state) {
        try {
            Field field = SidekickCpuController.class.getDeclaredField("state");
            field.setAccessible(true);
            field.set(controller, state);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Failed to set sidekick CPU state", e);
        }
    }
}
