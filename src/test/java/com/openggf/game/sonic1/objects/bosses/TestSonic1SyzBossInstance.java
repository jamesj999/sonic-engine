package com.openggf.game.sonic1.objects.bosses;

import com.openggf.camera.Camera;
import com.openggf.game.GameStateManager;
import com.openggf.game.rewind.CompositeSnapshot;
import com.openggf.game.rewind.RewindRegistry;
import com.openggf.game.sonic1.constants.Sonic1ObjectIds;
import com.openggf.game.sonic1.objects.Sonic1ObjectRegistry;
import com.openggf.graphics.GraphicsManager;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.ObjectServices;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.StubObjectServices;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reproduces the SYZ3 boss-retreat bug: Robotnik can leave his spike hanging in the
 * air (still rendering/dangerous at his last position) when he retreats after being
 * defeated.
 * <p>
 * ROM reference: docs/s1disasm/_incObj/75, 76 Boss - SYZ Main and Blocks.asm.
 * {@code BossSpringYard_SpikeMain} (routine 8) is a fully independent SST object slot
 * that reads the boss's {@code ob2ndRout}/{@code obSubtype}/{@code GenericTimer} fresh
 * every frame via its own dispatch. Before the fix, {@code Sonic1SYZBossInstance}
 * instead *pushed* that state into the spike from {@code updateBossLogic()} -- which is
 * skipped for exactly one frame during the post-hit defeat dispatch deferral
 * ({@link Sonic1SYZBossInstance#defeatDeferralAppliesToThisBoss()}, needed for correct
 * camera-boundary timing). That skip left the spike's collision/extension frozen at
 * their pre-defeat values on the very frame the boss starts retreating -- exactly the
 * "spike doesn't follow the boss into retreat" symptom.
 */
class TestSonic1SyzBossInstance {

    private static final int STATE_DEFEAT_WAIT = 6;
    private static final int STATE_ESCAPE = 10; // BossSpringYard ob2ndRout == $A

    // Sonic1SYZBossInstance.initializeBossState() unconditionally sets state.x/y to its
    // own SPAWN_X/SPAWN_Y constants (boss_syz_x+$1B0, boss_syz_y+$E) regardless of the
    // ObjectSpawn's nominal x/y, so the spawn coordinates below are placeholders -- the
    // camera mock below is centered on the boss's REAL runtime X (SPAWN_X) instead.
    private static final int SPAWN_X = 0x2C00 + 0x1B0; // boss_syz_x + $1B0

    private static final ObjectSpawn BOSS_SPAWN = new ObjectSpawn(
            0x0100,
            0x0100,
            Sonic1ObjectIds.SYZ_BOSS,
            0,
            0,
            false,
            10);

    private ObjectManager objectManager;
    private Camera camera;

    @BeforeEach
    void initHeadless() {
        GraphicsManager.getInstance().initHeadless();
    }

    @AfterEach
    void tearDown() {
        GraphicsManager.getInstance().resetState();
    }

    /**
     * Step 1 reproduction: the killing hit must make the spike harmless/retracting on
     * the SAME frame the boss enters its retreat (DEFEAT_WAIT), not one frame later.
     * ROM's spike object reads the boss's already-updated {@code ob2ndRout}/obColType
     * every frame regardless of what the boss's own routine dispatch is doing that
     * frame -- it is never gated by the boss's one-frame defeat-dispatch deferral.
     */
    @Test
    void killingHitMakesSpikeHarmlessOnTheSameFrameTheRetreatStarts() {
        Harness harness = createHarness();
        Sonic1SYZBossInstance boss = only(Sonic1SYZBossInstance.class);
        SYZBossSpike spike = only(SYZBossSpike.class);

        // Let the spike's first real update() run: at spawn (APPROACH, not holding a
        // block, not invulnerable) the spike must already be active/dangerous.
        stepFrame();
        assertTrue(readBoolean(spike, "spikeActive"), "spike must be active before the killing hit");

        // Land the killing hit exactly as ObjectManager's touch-response pass would,
        // before this object's own update() runs later the same frame (see
        // defeatDeferralAppliesToThisBoss's javadoc for the ROM dispatch shape this
        // models).
        boss.getState().hitCount = 1;
        boss.onPlayerAttack(null, null);
        assertEquals(STATE_DEFEAT_WAIT, boss.getState().routineSecondary,
                "harness sanity: onDefeatStarted must fire synchronously from the hit");
        assertTrue(readBoolean(boss, "deferDefeatRoutineDispatch"),
                "harness sanity: SYZ boss must carry the one-frame defeat-dispatch deferral");

        // This is the deferred frame: updateBossLogic() (and, pre-fix, the boss-pushed
        // spike state update nested inside it) does not run this frame.
        stepFrame();

        assertFalse(readBoolean(spike, "spikeActive"),
                "the spike must already be harmless on the same frame the boss starts its "
                        + "retreat -- if it is still active here, the spike's collision/extension "
                        + "tracking is lagging a frame behind the boss instead of following the ROM's "
                        + "independently-dispatched spike object");
    }

    /**
     * Step 1 reproduction (ASCENT/ESCAPE): drives the boss through the full retreat and
     * asserts the spike disappears from the live object set in the SAME frame the boss
     * self-destroys off-screen, matching BossSpringYard_SpikeMain's own off-screen
     * self-delete check (cmpi.b #$A,ob2ndRout(a1) / tst.b obRender(a0) / bpl.s
     * BossSpringYard_SpikeDelete) running every frame alongside the ship's own
     * (BSYZ_Escape.checkOffScreen).
     */
    @Test
    void spikeIsRemovedTheSameFrameTheBossSelfDestroysOffScreenDuringEscape() {
        Harness harness = createHarness();
        Sonic1SYZBossInstance boss = only(Sonic1SYZBossInstance.class);
        SYZBossSpike spike = only(SYZBossSpike.class);
        assertFalse(spike.isDestroyed(), "spike must start alive");

        // Fast-forward straight into the post-defeat retreat: killing hit already
        // landed (state.defeated / state.invulnerable latch permanently true, matching
        // ROM's obColType staying 0 once ob2ndRout reaches BSYZ_Explode -- see
        // BSYZ_StatusUpdate's `cmpi.b #6,ob2ndRout(a0) / bhs.s .exit`).
        boss.getState().routineSecondary = STATE_DEFEAT_WAIT;
        boss.getState().defeated = true;
        boss.getState().invulnerable = true;
        writeInt(boss, "timer", 0xB4); // DEFEAT_TIMER

        // Drive DEFEAT_WAIT (180 frames) + ASCENT (up to timer==0x2A) to reach ESCAPE.
        for (int i = 0; i < 260 && boss.getState().routineSecondary != STATE_ESCAPE; i++) {
            stepFrame();
        }
        assertEquals(STATE_ESCAPE, boss.getState().routineSecondary,
                "boss must reach ESCAPE within the DEFEAT_WAIT+ASCENT budget");
        assertFalse(boss.isDestroyed(), "boss must still be alive on entering ESCAPE");
        assertFalse(spike.isDestroyed(), "spike must still be alive on entering ESCAPE");

        // Unlock the camera boundary to boss_syz_end so runCameraExpandEscape()'s first
        // branch (off-screen check) is live, then let ESCAPE's own ESCAPE_X_VEL carry the
        // boss rightward out of both the ship's isBossOnScreen() and the spike's own
        // isOnScreenX(64) windows around the fixed camera position.
        camera.setMaxX((short) 0x2D40); // boss_syz_end
        for (int i = 0; i < 120 && !boss.isDestroyed(); i++) {
            stepFrame();
        }

        assertTrue(boss.isDestroyed(), "boss must self-destroy once escape carries it off-screen past boss_syz_end");
        assertTrue(spike.isDestroyed(),
                "spike must be marked destroyed in the same update() the boss self-destroys");
        assertFalse(objectManager.getActiveObjects().contains(spike),
                "spike must be fully removed from the live object set the SAME frame the boss "
                        + "self-destroys off-screen -- a spike left in the active set renders at the "
                        + "boss's frozen last position, i.e. \"hanging in the air\" after Robotnik flees");
    }

    /**
     * Shipped-content reachability repro for the "dynamically-spawned boss loses all
     * child rewind state" bug (see {@code TestS2DeathEggRobotGraphRewind
     * #survivingArticulatedChildrenAreExactAfterASiblingIsDestroyedBeforeCapture}):
     * {@link Sonic1SYZEvents#updateAct3Boss()} spawns {@code Sonic1SYZBossInstance}
     * DYNAMICALLY (via {@code AbstractLevelEventManager#spawnObject}, which routes to
     * {@code ObjectManager.createDynamicObject}), NOT through the zone's static object
     * layout table -- unlike every other currently known boss (e.g. {@code
     * Sonic2EHZBossInstance}, spawned from the layout table and reconstructed in
     * {@code ObjectManager.restore()}'s Phase-1 ACTIVE-object pass). The boss's
     * constructor unconditionally spawns {@link SYZBossSpike} ({@code
     * initializeBossState() -> spawnSpikeChild()}), exactly the DEZ articulated-child
     * shape.
     *
     * <p>This test spawns the boss the SAME way ({@code createDynamicObject}, no
     * static layout entry), mutates the spike's captured mutable state, forces a full
     * rewind reconstruction (dynamic objects always fully reconstruct on restore --
     * there is no in-place-reuse path for them), and asserts the spike's mutable state
     * survives with no duplicate spike and the correct restored parent link.
     */
    @Test
    void spikeCapturedStateSurvivesFullReconstructionWhenBossIsSpawnedDynamically() {
        createDynamicHarness();
        Sonic1SYZBossInstance boss =
                objectManager.createDynamicObject(() -> new Sonic1SYZBossInstance(BOSS_SPAWN));
        SYZBossSpike spike = only(SYZBossSpike.class);

        // Seed distinguishing captured mutable state on the spike (extension/collision
        // fields), matching the DEZ repro's approach of seeding state a fresh
        // reconstruction's defaults could never coincidentally match.
        writeInt(spike, "extensionDepth", 0x123);
        writeBoolean(spike, "spikeActive", true);

        RewindRegistry registry = registryFor(objectManager);
        CompositeSnapshot snapshot = registry.capture();
        registry.restore(snapshot);

        Sonic1SYZBossInstance restoredBoss = only(Sonic1SYZBossInstance.class);
        SYZBossSpike restoredSpike = only(SYZBossSpike.class); // fails if a duplicate spike exists
        assertEquals(0x123, readInt(restoredSpike, "extensionDepth"),
                "spike's captured extension state must survive a full rewind reconstruction of "
                        + "its dynamically-spawned parent, not reset to the fresh-construction default");
        assertTrue(readBoolean(restoredSpike, "spikeActive"),
                "spike's captured collision-active state must survive the same restore");
        assertSame(restoredBoss, readObjectField(restoredSpike, "parent"),
                "restored spike must point at the SAME restored boss instance, not a stale or "
                        + "duplicate one");
    }

    private void stepFrame() {
        objectManager.update(0, null, List.of(), 0);
    }

    /**
     * Like {@link #createHarness()} but with NO static layout entry -- the boss is
     * spawned dynamically by the test itself via {@code createDynamicObject}, matching
     * {@link Sonic1SYZEvents#updateAct3Boss()}'s real spawn route instead of the zone
     * layout table every other current boss test uses.
     */
    private void createDynamicHarness() {
        Camera[] cameraHolder = new Camera[1];
        cameraHolder[0] = mockCameraCenteredOnBossArena();
        camera = cameraHolder[0];
        ObjectManager[] holder = new ObjectManager[1];
        GameStateManager gameStateManager = new GameStateManager();
        ObjectServices services = new StubObjectServices() {
            @Override public ObjectManager objectManager() { return holder[0]; }
            @Override public Camera camera() { return cameraHolder[0]; }
            @Override public GameStateManager gameState() { return gameStateManager; }
        };
        objectManager = new ObjectManager(
                List.of(),
                new Sonic1ObjectRegistry(),
                0,
                null,
                null,
                GraphicsManager.getInstance(),
                camera,
                services);
        holder[0] = objectManager;
        objectManager.reset(0);
        objectManager.setRewindInPlaceRestoreEnabledForTest(false);
    }

    private static RewindRegistry registryFor(ObjectManager objectManager) {
        RewindRegistry registry = new RewindRegistry();
        registry.register(objectManager.rewindSnapshottable());
        return registry;
    }

    private static Object readObjectField(Object target, String fieldName) {
        try {
            return findField(target.getClass(), fieldName).get(target);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Unable to read " + fieldName + " from " + target.getClass(), e);
        }
    }

    private static int readInt(Object target, String fieldName) {
        try {
            return findField(target.getClass(), fieldName).getInt(target);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Unable to read " + fieldName + " from " + target.getClass(), e);
        }
    }

    private static void writeBoolean(Object target, String fieldName, boolean value) {
        try {
            findField(target.getClass(), fieldName).setBoolean(target, value);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Unable to write " + fieldName + " on " + target.getClass(), e);
        }
    }

    private Harness createHarness() {
        Camera[] cameraHolder = new Camera[1];
        cameraHolder[0] = mockCameraCenteredOnBossArena();
        camera = cameraHolder[0];
        ObjectManager[] holder = new ObjectManager[1];
        GameStateManager gameStateManager = new GameStateManager();
        ObjectServices services = new StubObjectServices() {
            @Override public ObjectManager objectManager() { return holder[0]; }
            @Override public Camera camera() { return cameraHolder[0]; }
            @Override public GameStateManager gameState() { return gameStateManager; }
        };
        objectManager = new ObjectManager(
                List.of(BOSS_SPAWN),
                new Sonic1ObjectRegistry(),
                0,
                null,
                null,
                GraphicsManager.getInstance(),
                camera,
                services);
        holder[0] = objectManager;
        objectManager.reset(0);
        return new Harness(objectManager);
    }

    private <T> T only(Class<T> type) {
        List<T> matches = objectManager.getActiveObjects().stream()
                .filter(type::isInstance)
                .map(type::cast)
                .toList();
        assertEquals(1, matches.size(), "expected exactly one live " + type.getSimpleName());
        return matches.get(0);
    }

    private static Camera mockCameraCenteredOnBossArena() {
        return new Camera() {
            // Centered on the boss's real runtime X (SPAWN_X) so it starts on-screen,
            // matching a real fight where the camera has already scrolled to the arena.
            @Override public short getX() { return (short) (SPAWN_X - 160); }
            @Override public short getY() { return 0; }
            @Override public short getWidth() { return 320; }
            @Override public short getHeight() { return 224; }
            @Override public boolean isVerticalWrapEnabled() { return false; }
        };
    }

    private static boolean readBoolean(Object target, String fieldName) {
        try {
            return findField(target.getClass(), fieldName).getBoolean(target);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Unable to read " + fieldName + " from " + target.getClass(), e);
        }
    }

    private static void writeInt(Object target, String fieldName, int value) {
        try {
            findField(target.getClass(), fieldName).setInt(target, value);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Unable to write " + fieldName + " on " + target.getClass(), e);
        }
    }

    private static Field findField(Class<?> type, String fieldName) throws NoSuchFieldException {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            try {
                Field field = current.getDeclaredField(fieldName);
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException ignored) {
                // Walk superclass chain.
            }
        }
        throw new NoSuchFieldException(fieldName);
    }

    private record Harness(ObjectManager objectManager) {
    }
}
