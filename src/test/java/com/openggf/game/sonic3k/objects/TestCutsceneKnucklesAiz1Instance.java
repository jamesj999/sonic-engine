package com.openggf.game.sonic3k.objects;

import org.junit.jupiter.api.Test;
import com.openggf.game.rewind.GenericFieldCapturer;
import com.openggf.camera.Camera;
import com.openggf.configuration.SonicConfigurationService;
import com.openggf.level.objects.TestObjectServices;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.PerObjectRewindSnapshot;

import java.lang.reflect.Field;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class TestCutsceneKnucklesAiz1Instance {

    @Test
    public void initPositionIsCorrect() {
        var spawn = new ObjectSpawn(0x1400, 0x440, 0, 0, 0, false, 0);
        var knux = new CutsceneKnucklesAiz1Instance(spawn);
        assertEquals(0x1400, knux.getX());
        assertEquals(0x440, knux.getY());
    }

    @Test
    public void startsInWaitRoutine() {
        var spawn = new ObjectSpawn(0x1400, 0x440, 0, 0, 0, false, 0);
        var knux = new CutsceneKnucklesAiz1Instance(spawn);
        assertEquals(0, knux.getRoutine());
    }

    @Test
    public void rewindCaptureSkipsScratchMotionState() {
        var spawn = new ObjectSpawn(0x1400, 0x440, 0, 0, 0, false, 0);
        var knux = new CutsceneKnucklesAiz1Instance(spawn);

        // The captured field set must EXCLUDE the @RewindTransient scratch
        // SubpixelMotion holder (motionState) while still including the
        // authoritative scalar position/velocity fields it is rebuilt from.
        List<Field> captured = GenericFieldCapturer
                .defaultObjectSubclassCapturedFieldsForAudit(CutsceneKnucklesAiz1Instance.class);
        List<String> names = captured.stream().map(Field::getName).toList();

        assertFalse(names.contains("motionState"),
                "scratch SubpixelMotion holder must not be captured for rewind");
        assertTrue(names.contains("currentX"), "authoritative X position must be captured");
        assertTrue(names.contains("currentY"), "authoritative Y position must be captured");
        assertTrue(names.contains("xVel"), "authoritative X velocity must be captured");
        assertTrue(names.contains("yVel"), "authoritative Y velocity must be captured");
        assertTrue(names.contains("routine"), "routine state machine counter must be captured");

        // And the live capture actually produces a non-empty scalar sidecar
        // (so the field set above is genuinely persisted, not silently dropped).
        PerObjectRewindSnapshot snapshot = knux.captureRewindState();
        boolean hasScalarSidecar = snapshot.compactGenericState() != null
                || (snapshot.genericState() != null && !snapshot.genericState().keys().isEmpty());
        assertTrue(hasScalarSidecar,
                "rewind capture must persist the Knuckles scalar motion state");
    }

    @Test
    public void exitHandoffReadsPreviousFrameRenderFlag() throws Exception {
        Camera camera = new Camera(SonicConfigurationService.getInstance());
        camera.setX((short) 0x1308);
        camera.setY((short) 0x0390);

        var knux = new CutsceneKnucklesAiz1Instance(
                new ObjectSpawn(0x1461, 0x041B, 0, 0, 0, false, 0));
        knux.setServices(new TestObjectServices().withCamera(camera));
        setField(knux, "routine", 12);
        setField(knux, "currentX", 0x1461);
        setField(knux, "currentY", 0x041B);
        setField(knux, "xVel", 0x0600);
        setField(knux, "exitRenderFlagOnScreen", true);

        knux.update(0, null);

        assertEquals(0x1467, knux.getX(),
                "MoveSprite2 must carry Knuckles across the right render boundary");
        assertFalse(knux.isDestroyed(),
                "loc_61F10 consumes the previous Draw_Sprite render flag; the crossing frame only clears it");
        assertFalse((boolean) field(knux, "exitRenderFlagOnScreen").get(knux),
                "the post-move visibility result must be retained for the next dispatch");
    }

    @Test
    public void triggerDefersFirstFallMovementUntilNextDispatch() throws Exception {
        var knux = new CutsceneKnucklesAiz1Instance(
                new ObjectSpawn(0x1400, 0x0440, 0, 0, 0, false, 0));
        knux.setServices(new TestObjectServices());
        setField(knux, "routine", 2);
        knux.trigger();

        knux.update(0, null);

        assertEquals(4, knux.getRoutine());
        assertEquals(0x1400, knux.getX());
        assertEquals(0x0440, knux.getY(),
                "loc_61E02 returns through PalLoad_Line1 before loc_61E24 can call MoveSprite");
        assertEquals(CutsceneKnucklesAiz1Instance.FALL_INIT_Y_VEL, knux.getYVel());
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = field(target, name);
        field.set(target, value);
    }

    private static Field field(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

}
