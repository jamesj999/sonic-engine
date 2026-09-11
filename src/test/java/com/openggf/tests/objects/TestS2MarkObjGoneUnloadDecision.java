package com.openggf.tests.objects;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.openggf.game.GameServices;
import com.openggf.game.session.EngineContext;
import com.openggf.game.session.EngineServices;
import com.openggf.game.session.SessionManager;
import com.openggf.game.sonic2.objects.S2ObjectWindowing;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.ObjectRegistry;
import com.openggf.level.objects.ObjectServices;
import com.openggf.level.objects.ObjectSlotLayout;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.StubObjectServices;
import com.openggf.tests.FullReset;
import com.openggf.tests.SingletonResetExtension;
import com.openggf.tests.TestEnvironment;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Task 1.5 coordinate-semantics guard for the S2 object-side unload.
 *
 * <p>S2's per-instance off-screen unload now routes through
 * {@link S2ObjectWindowing#markObjGone(int, int)} (ROM {@code MarkObjGone},
 * docs/s2disasm/s2.asm) instead of the S1 {@code out_of_range} distance limit.
 * ROM {@code MarkObjGone} reads {@code x_pos(a0)} — the object's ROM <b>centre</b>
 * X — so the engine must feed the object's ROM out-of-range reference X (the
 * centre-aligned value), never a sprite top-left bound. A top-left X would shift
 * the unload boundary by the object's half-width.
 *
 * <p>The test builds a stub whose top-left {@code getX()} and ROM reference
 * (centre) {@code getOutOfRangeReferenceX()} differ by a known half-width, then
 * drives one engine {@code update()} and asserts the survive/delete outcome
 * matches {@code markObjGone(centreX, cameraX)} — i.e. the decision is fed the
 * centre, not the top-left.
 */
@ExtendWith(SingletonResetExtension.class)
@FullReset
public class TestS2MarkObjGoneUnloadDecision {

    /** Camera X used for all cases; unloadCoarse(cam) = (0x1500-0x80)&0xFF80 = 0x1480. */
    private static final int CAMERA_X = 0x1500;
    private static final int HALF_WIDTH = 0x100;

    private ObjectManager objectManager;
    private ObjectServices objectServices;

    @BeforeEach
    public void setUp() {
        EngineServices.configure(EngineContext.fromLegacySingletonsForBootstrap());
        TestEnvironment.activeGameplayMode();
        GameServices.camera().resetState();
        GameServices.camera().setX((short) CAMERA_X);
        GameServices.camera().setY((short) 0);
        objectServices = new StubObjectServices() {
            @Override
            public ObjectManager objectManager() {
                return objectManager;
            }

            @Override
            public com.openggf.camera.Camera camera() {
                return GameServices.camera();
            }
        };
        objectManager = new ObjectManager(List.of(), new Sonic2LayoutRegistry(), 0, null, null,
                null, GameServices.camera(), objectServices);
    }

    @AfterEach
    public void tearDown() {
        SessionManager.clear();
    }

    @Test
    public void centreInsideWindowSurvives_eventhoughTopLeftWouldBeDeleted() {
        // Centre exactly at the unload base → markObjGone false → object survives.
        int centreX = S2ObjectWindowing.unloadCoarse(CAMERA_X); // 0x1480
        // Top-left sits a half-width left of centre. If the engine wrongly used
        // top-left, (topLeft & 0xFF80) - base underflows to a huge unsigned
        // distance > $280 → markObjGone TRUE → wrong delete.
        int topLeftX = centreX - HALF_WIDTH;

        assertFalse(S2ObjectWindowing.markObjGone(centreX, CAMERA_X),
                "precondition: centre is inside the live window");
        assertTrue(S2ObjectWindowing.markObjGone(topLeftX, CAMERA_X),
                "precondition: top-left would be (wrongly) deleted");

        SemanticsObject obj = spawn(centreX, topLeftX);
        driveOneFrame();

        assertTrue(isLive(obj),
                "S2 unload must use the centre reference X (in-window → survives), "
                        + "not the top-left X (which would delete)");
    }

    @Test
    public void centreOutsideWindowDeletes_eventhoughTopLeftWouldSurvive() {
        // Centre two $80 buckets past the base (dist $300 > $280) → delete.
        int base = S2ObjectWindowing.unloadCoarse(CAMERA_X); // 0x1480
        int centreX = base + 0x300;
        // Top-left a half-width nearer the camera lands at dist $200 ≤ $280 → would survive.
        int topLeftX = centreX - HALF_WIDTH;

        assertTrue(S2ObjectWindowing.markObjGone(centreX, CAMERA_X),
                "precondition: centre is outside the live window");
        assertFalse(S2ObjectWindowing.markObjGone(topLeftX, CAMERA_X),
                "precondition: top-left would (wrongly) survive");

        SemanticsObject obj = spawn(centreX, topLeftX);
        driveOneFrame();

        assertFalse(isLive(obj),
                "S2 unload must use the centre reference X (out-of-window → deletes), "
                        + "not the top-left X (which would survive)");
    }

    private SemanticsObject spawn(int centreX, int topLeftX) {
        SemanticsObject obj = new SemanticsObject(
                new ObjectSpawn(centreX, 0, 0x4B, 0, 0, false, 0), centreX, topLeftX);
        objectManager.addDynamicObject(obj);
        return obj;
    }

    private void driveOneFrame() {
        // Touch responses disabled: the stub debug overlay / touch manager are null.
        objectManager.update(CAMERA_X, null, List.of(), 0, false);
    }

    private boolean isLive(ObjectInstance obj) {
        return objectManager.getActiveObjects().contains(obj) && !obj.isDestroyed();
    }

    /** Registry whose layout is Sonic 2 (S2 unload path); creates no objects. */
    private static final class Sonic2LayoutRegistry implements ObjectRegistry {
        @Override
        public ObjectSlotLayout objectSlotLayout() {
            return ObjectSlotLayout.SONIC_2;
        }

        @Override
        public ObjectInstance create(ObjectSpawn spawn) {
            return null;
        }

        @Override
        public void reportCoverage(List<ObjectSpawn> spawns) {
        }

        @Override
        public String getPrimaryName(int objectId) {
            return "noop";
        }
    }

    /**
     * Renderless dynamic object whose top-left {@link #getX()} and ROM reference
     * (centre) {@link #getOutOfRangeReferenceX()} differ by a known half-width.
     */
    private static final class SemanticsObject extends AbstractObjectInstance {
        private final int centreX;
        private final int topLeftX;

        private SemanticsObject(ObjectSpawn spawn, int centreX, int topLeftX) {
            super(spawn, "s2-mark-obj-gone-semantics");
            this.centreX = centreX;
            this.topLeftX = topLeftX;
        }

        @Override
        public int getX() {
            return topLeftX;
        }

        @Override
        public int getOutOfRangeReferenceX() {
            return centreX;
        }

        @Override
        public void appendRenderCommands(List<com.openggf.graphics.GLCommand> commands) {
            // no rendering in headless tests
        }
    }
}
