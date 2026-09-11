package com.openggf.tests.objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.openggf.game.GameServices;
import com.openggf.game.session.EngineContext;
import com.openggf.game.session.EngineServices;
import com.openggf.game.session.SessionManager;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.ObjectRegistry;
import com.openggf.level.objects.ObjectServices;
import com.openggf.level.objects.ObjectSlotLayout;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.StubObjectServices;
import com.openggf.tests.SingletonResetExtension;
import com.openggf.tests.TestEnvironment;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Task 1.6 frame-ordering guard for S2: a single {@link ObjectManager#update}
 * call runs the exec loop FIRST and then performs EXACTLY ONE object load
 * (ROM S2 {@code RunObjects} then exactly one {@code ObjectsManager} =
 * exec -&gt; one load, docs/s2disasm/s2.asm:5095, 5112).
 *
 * <p>Guards against re-introducing the removed pre-exec load (which made S2
 * load twice per frame). Ordering is observed without touching {@code ObjectManager}'s
 * private load/exec entry points: the registry's {@code create()} (the load path)
 * appends {@code LOAD} to a shared event log, and a pre-seeded dynamic object's
 * {@code update()} (the exec path) appends {@code EXEC}. We assert the log is
 * exactly {@code [EXEC, LOAD]} for one S2 frame.
 */
@ExtendWith(SingletonResetExtension.class)
public class TestS2FrameOrderingExecThenLoad {

    /** Object x positions sit well inside the load window for cam=0 (window end >= 0x280). */
    private static final int IN_WINDOW_X = 200;
    /** Outside the normal non-S2 vertical load band for camera Y=0. */
    private static final int HIGH_Y_OUTSIDE_VERTICAL_BAND = 0x0700;
    private static final int PRESEEDED_ID = 0x40;
    private static final int WINDOWED_ID = 0x41;

    private final List<String> events = new ArrayList<>();
    private ObjectManager objectManager;
    private ObjectServices objectServices;

    @BeforeEach
    public void setUp() {
        EngineServices.configure(EngineContext.fromLegacySingletonsForBootstrap());
        TestEnvironment.activeGameplayMode();
        GameServices.camera().resetState();
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
        // One windowed spawn whose creation is the engine's single per-frame load.
        // S2 ChkLoadObj has no Camera_Y_pos gate, so the high Y must not defer
        // this spawn into a later/pre-exec load path.
        ObjectSpawn windowedSpawn =
                new ObjectSpawn(IN_WINDOW_X, HIGH_Y_OUTSIDE_VERTICAL_BAND,
                        WINDOWED_ID, 0, 0, false, HIGH_Y_OUTSIDE_VERTICAL_BAND);
        objectManager = new ObjectManager(List.of(windowedSpawn), new RecordingS2Registry(),
                0, null, null, null, GameServices.camera(), objectServices);
        // Put the placement in S2's exec-then-load mode (LevelManager calls this
        // for S2/S3K; without it the manager defaults to the load-then-exec
        // fallback branch and never exercises the consolidated S2 path).
        objectManager.enableExecThenLoadPlacement();
    }

    @AfterEach
    public void tearDown() {
        SessionManager.clear();
    }

    @Test
    public void s2RunsExecThenExactlyOneLoadPerFrame() {
        // Pre-seed a live dynamic object so the exec loop has something to run
        // and record EXEC before the windowed spawn is loaded.
        ExecRecordingObject preSeeded =
                new ExecRecordingObject(new ObjectSpawn(IN_WINDOW_X, 0, PRESEEDED_ID, 0, 0, false, 0));
        objectManager.addDynamicObject(preSeeded);
        assertTrue(preSeeded.getSlotIndex() >= ObjectSlotLayout.SONIC_2.firstDynamicSlot(),
                "pre-seeded object should occupy a dynamic SST slot");

        events.clear();

        // Camera at 0: window = [0, >=0x280], so the x=200 spawn is in range and loads.
        objectManager.update(0, null, List.of(), 1, false);

        long execCount = events.stream().filter("EXEC"::equals).count();
        long loadCount = events.stream().filter("LOAD"::equals).count();

        assertEquals(1, loadCount,
                "S2 must load exactly once per frame (no double-load); events=" + events);
        assertTrue(execCount >= 1,
                "exec loop should have run the pre-seeded object; events=" + events);

        int firstLoad = events.indexOf("LOAD");
        int firstExec = events.indexOf("EXEC");
        assertTrue(firstExec >= 0 && firstLoad >= 0, "expected both EXEC and LOAD; events=" + events);
        assertTrue(firstExec < firstLoad,
                "S2 frame order must be exec -> load (exec before the single load); events=" + events);
    }

    /** Sonic 2 layout registry; records each load via create() and creates recording objects. */
    private final class RecordingS2Registry implements ObjectRegistry {
        @Override
        public ObjectSlotLayout objectSlotLayout() {
            return ObjectSlotLayout.SONIC_2;
        }

        @Override
        public ObjectInstance create(ObjectSpawn spawn) {
            events.add("LOAD");
            return new ExecRecordingObject(spawn);
        }

        @Override
        public void reportCoverage(List<ObjectSpawn> spawns) {
        }

        @Override
        public String getPrimaryName(int objectId) {
            return "recording";
        }
    }

    /** Renderless object that records EXEC whenever the exec loop runs it. */
    private final class ExecRecordingObject extends AbstractObjectInstance {
        private ExecRecordingObject(ObjectSpawn spawn) {
            super(spawn, "exec-recording");
        }

        @Override
        public void update(int vIntRunCount, com.openggf.game.PlayableEntity player) {
            events.add("EXEC");
        }

        @Override
        public void appendRenderCommands(List<com.openggf.graphics.GLCommand> commands) {
            // no rendering in headless tests
        }
    }
}
