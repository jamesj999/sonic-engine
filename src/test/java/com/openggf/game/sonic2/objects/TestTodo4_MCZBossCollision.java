package com.openggf.game.sonic2.objects;

import com.openggf.game.session.SessionManager;
import com.openggf.tests.TestEnvironment;

import com.openggf.camera.Camera;
import com.openggf.game.PlayableEntity;
import com.openggf.game.GameServices;
import com.openggf.game.sonic2.events.Sonic2MCZEvents;
import com.openggf.game.sonic2.objects.bosses.Sonic2MCZBossInstance;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.TouchCategory;
import com.openggf.level.objects.TouchResponseProvider;
import com.openggf.level.objects.TouchResponseResult;
import com.openggf.level.objects.boss.AbstractBossInstance;
import com.openggf.level.objects.boss.BossStateContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests for MCZ Boss arena events and collision specifications.
 *
 * <p>The MCZ boss (Obj57) collision system has two modes depending on
 * Boss_CollisionRoutine state. The actual collision box geometry requires
 * the full boss instance infrastructure (art loading, render manager, etc.)
 * and is documented below for reference.
 *
 * <p>ROM reference: BossCollision_MCZ (s2.asm:85217-85274)
 *
 * <p><b>Mode 0 (diggers pointing up):</b>
 * Two collision checks at x+$14 and x-$14, each with height=$10, width=4,
 * y offset of -$20 from boss Y position.
 *
 * <p><b>Mode 1 (diggers pointing to side):</b>
 * Single collision check with height=4, width=4.
 * X offset: -$30 or +$30 depending on flip (net range $60 = 96 pixels).
 * Y offset: +4.
 *
 * <p>This test file validates the MCZ event handler's arena setup, camera locking,
 * and boss spawn timing -- the behaviors that CAN be unit-tested without
 * OpenGL or ROM dependencies.
 *
 * <p>Production code: {@link Sonic2MCZEvents},
 * {@link com.openggf.game.sonic2.objects.bosses.Sonic2MCZBossInstance}
 */
public class TestTodo4_MCZBossCollision {

    private Sonic2MCZEvents events;
    private Camera cam;

    @BeforeEach
    public void setUp() {
        TestEnvironment.activeGameplayMode();
        GameServices.camera().resetState();
        cam = GameServices.camera();
        events = new Sonic2MCZEvents();
        events.init(1); // MCZ Act 2
    }

    @AfterEach
    public void tearDown() {
        SessionManager.clear();
    }

    /**
     * MCZ Act 1 has no events (returns early).
     * ROM reference: LevEvents_MCZ (s2.asm:20777)
     */
    @Test
    public void testMCZAct1_NoEvents() {
        Sonic2MCZEvents act1Events = new Sonic2MCZEvents();
        act1Events.init(0);
        cam.setX((short) 0x2080);
        act1Events.update(0, 0);
        assertEquals(0, act1Events.getEventRoutine(), "Act 1 should not advance routine");
    }

    /**
     * MCZ Act 2 Routine 0: Wait for camera X >= $2080, set minX and maxYTarget.
     * ROM reference: LevEvents_MCZ2_Routine1
     */
    @Test
    public void testMCZRoutine0_DoesNotAdvanceBelowThreshold() {
        cam.setX((short) 0x2000);
        events.update(1, 0);
        assertEquals(0, events.getEventRoutine(), "Should stay at routine 0 when camera X < $2080");
    }

    @Test
    public void testMCZRoutine0_AdvancesAndSetsMinX() {
        cam.setX((short) 0x2080);
        events.update(1, 0);
        assertEquals(2, events.getEventRoutine(), "Should advance to routine 2 when camera X >= $2080");
        assertEquals((short) 0x2080, cam.getMinX(), "MinX should be set to camera X ($2080)");
        assertEquals((short) 0x5D0, cam.getMaxYTarget(), "MaxY target should be set to $5D0");
    }

    /**
     * MCZ Act 2 Routine 2: Lock arena at camera X >= $20F0.
     * ROM: Camera_Min_X and Camera_Max_X both locked to $20F0.
     */
    @Test
    public void testMCZRoutine2_DoesNotAdvanceBelowThreshold() {
        events.setEventRoutine(2);
        cam.setX((short) 0x20A0);
        events.update(1, 0);
        assertEquals(2, events.getEventRoutine(), "Should stay at routine 2 when camera X < $20F0");
    }

    @Test
    public void testMCZRoutine2_LocksArena() {
        events.setEventRoutine(2);
        cam.setX((short) 0x20F0);
        events.update(1, 0);
        assertEquals(4, events.getEventRoutine(), "Should advance to routine 4 when camera X >= $20F0");
        assertEquals((short) 0x20F0, cam.getMinX(), "Arena minX should be locked at $20F0");
        assertEquals((short) 0x20F0, cam.getMaxX(), "Arena maxX should be locked at $20F0");
    }

    /**
     * MCZ Act 2 Routine 4: Lock min Y at $5C8, spawn boss after 90-frame delay.
     * ROM reference: LevEvents_MCZ2_Routine3
     */
    @Test
    public void testMCZRoutine4_LocksMinYWhenAboveThreshold() {
        events.setEventRoutine(4);
        cam.setY((short) 0x5C8);
        events.update(1, 0);
        assertEquals((short) 0x5C8, cam.getMinY(), "MinY should be locked at $5C8 when camera Y >= $5C8");
    }

    @Test
    public void testMCZRoutine4_DoesNotLockMinYBelowThreshold() {
        events.setEventRoutine(4);
        cam.setY((short) 0x500);
        short minYBefore = cam.getMinY();
        events.update(1, 0);
        assertEquals(minYBefore, cam.getMinY(), "MinY should not change when camera Y < $5C8");
    }

    @Test
    public void testMCZRoutine4_DoesNotSpawnBossBeforeDelay() {
        events.setEventRoutine(4);
        cam.setY((short) 0x5C8);
        // Step 89 frames (one short of $5A)
        for (int i = 0; i < 0x59; i++) {
            events.update(1, i);
        }
        assertEquals(4, events.getEventRoutine(), "Should stay at routine 4 before 90 frames ($5A)");
    }

    @Test
    public void testMCZRoutine4_SpawnsBossAtDelay() {
        events.setEventRoutine(4);
        cam.setY((short) 0x5C8);
        // Step exactly 90 frames ($5A)
        for (int i = 0; i < 0x5A; i++) {
            events.update(1, i);
        }
        assertEquals(6, events.getEventRoutine(), "Should advance to routine 6 after 90 frames ($5A)");
    }

    /**
     * MCZ Act 2 Routine 6: Boss fight tracking -- minX follows camera.
     */
    @Test
    public void testMCZRoutine6_TracksCamera() {
        events.setEventRoutine(6);
        cam.setX((short) 0x2100);
        events.update(1, 0);
        assertEquals((short) 0x2100, cam.getMinX(), "MinX should track camera X during boss fight");
    }

    /**
     * Verify the full MCZ event sequence for Act 2.
     * ROM: spawn position ($21A0, $560) with 8 HP.
     */
    @Test
    public void testMCZFullEventSequence() {
        // Routine 0: trigger at X >= $2080
        cam.setX((short) 0x2080);
        events.update(1, 0);
        assertEquals(2, events.getEventRoutine());

        // Routine 2: lock arena at X >= $20F0
        cam.setX((short) 0x20F0);
        events.update(1, 1);
        assertEquals(4, events.getEventRoutine());
        assertEquals((short) 0x20F0, cam.getMinX());
        assertEquals((short) 0x20F0, cam.getMaxX());

        // Routine 4: 90-frame delay then spawn
        cam.setY((short) 0x5C8);
        for (int i = 0; i < 0x5A; i++) {
            events.update(1, 2 + i);
        }
        assertEquals(6, events.getEventRoutine());
    }

    /**
     * Boss_CollisionRoutine = 1 uses a single side drill hurt check:
     * x_pos +/- $30, y_pos + 4, height=4, width=4.
     *
     * <p>ROM reference: BossCollision_MCZ (s2.asm:85736-85757)
     */
    @Test
    public void testObj57HorizontalDrillReportsSideHurtRegion() throws Exception {
        Sonic2MCZBossInstance boss = newMczBossAt(0x2200, 0x0660);
        setState(boss, "routineSecondary", 0x06);
        setField(boss, "countdown", 0x10);

        TouchResponseProvider.TouchRegion[] regions = boss.getMultiTouchRegions();

        assertNotNull(regions, "horizontal MCZ drill collision should use explicit boss-specific hurt region");
        assertEquals(2, regions.length);
        assertEquals(0x21D0, regions[0].x(), "unflipped horizontal drill is x_pos - $30");
        assertEquals(0x0664, regions[0].y(), "horizontal drill is y_pos + 4");
        assertEquals(0x80, regions[0].collisionFlags(), "HURT category with 4x4 size index");
        assertEquals(0x2200, regions[1].x(), "generic boss body region keeps x_pos");
        assertEquals(0x0660, regions[1].y(), "generic boss body region keeps y_pos");
        assertEquals(0xCF, regions[1].collisionFlags(), "generic boss body remains attackable after drill checks");
    }

    /**
     * Boss_CollisionRoutine = 0 checks both upward drills:
     * x_pos + $14 and x_pos - $14, y_pos - $20, height=$10, width=4.
     *
     * <p>ROM reference: BossCollision_MCZ2 (s2.asm:85768-85783)
     */
    @Test
    public void testObj57VerticalDrillsReportBothHurtRegions() throws Exception {
        Sonic2MCZBossInstance boss = newMczBossAt(0x2200, 0x0660);
        setState(boss, "routineSecondary", 0x02);

        TouchResponseProvider.TouchRegion[] regions = boss.getMultiTouchRegions();

        assertNotNull(regions, "vertical MCZ drill collision should expose both boss-specific hurt regions");
        assertEquals(3, regions.length);
        assertEquals(0x2214, regions[0].x(), "first vertical drill is x_pos + $14");
        assertEquals(0x0640, regions[0].y(), "vertical drills are y_pos - $20");
        assertEquals(0x84, regions[0].collisionFlags(), "HURT category with size index 4");
        assertEquals(0x21EC, regions[1].x(), "second vertical drill is x_pos - $14");
        assertEquals(0x0640, regions[1].y(), "vertical drills are y_pos - $20");
        assertEquals(0x84, regions[1].collisionFlags(), "HURT category with size index 4");
        assertEquals(0x2200, regions[2].x(), "generic boss body region keeps x_pos");
        assertEquals(0x0660, regions[2].y(), "generic boss body region keeps y_pos");
        assertEquals(0xCF, regions[2].collisionFlags(), "generic boss body remains attackable after drill checks");
    }

    /**
     * boss_hurt_sonic is latched by the main-character BossCollision_MCZ hurt path.
     * CPU Tails can be hurt by Obj57 without forcing the boss into the grin/reascend
     * branch.
     *
     * <p>ROM reference: BossCollision_MCZ tests the main character's
     * invulnerable_time(a0) == $78 before setting boss_hurt_sonic.
     */
    @Test
    public void testObj57DrillHurtLatchIgnoresCpuSidekick() throws Exception {
        Sonic2MCZBossInstance boss = newMczBossAt(0x2200, 0x0660);
        TouchResponseResult hurtResult = new TouchResponseResult(0, 4, 4, TouchCategory.HURT);

        PlayableEntity sidekick = mock(PlayableEntity.class);
        when(sidekick.isCpuControlled()).thenReturn(true);
        when(sidekick.getInvulnerable()).thenReturn(false);
        boss.onTouchResponse(sidekick, hurtResult, 0);
        assertFalse(getBooleanField(boss, "bossHurtSonic"),
                "CPU sidekick drill hurt should not latch boss_hurt_sonic");

        PlayableEntity sonic = mock(PlayableEntity.class);
        when(sonic.isCpuControlled()).thenReturn(false);
        when(sonic.getInvulnerable()).thenReturn(false);
        boss.onTouchResponse(sonic, hurtResult, 1);
        assertTrue(getBooleanField(boss, "bossHurtSonic"),
                "main-character drill hurt should latch boss_hurt_sonic");
    }

    /**
     * BossCollision_MCZ clears boss_hurt_sonic before each collision pass
     * (s2.asm:85732-85733), and Obj57_Main_Sub6 only consumes the flag after
     * Boss_Countdown has gone negative (s2.asm:65987-65996). A hurt that happened
     * while the countdown was still nonnegative must therefore not stay latched
     * until a later re-ascend decision.
     */
    @Test
    public void testObj57StaleHurtLatchClearsWhileSub6CountdownIsNonnegative() throws Exception {
        Sonic2MCZBossInstance boss = newMczBossAt(0x2200, 0x0660);
        setState(boss, "routineSecondary", 0x06);
        setField(boss, "countdown", 2);
        setBooleanField(boss, "bossHurtSonic", true);

        boss.update(0, null);

        assertEquals(0x06, bossState(boss).routineSecondary,
                "Sub6 stays active while Boss_Countdown is still nonnegative");
        assertFalse(getBooleanField(boss, "bossHurtSonic"),
                "BossCollision_MCZ clears stale boss_hurt_sonic before the next frame can consume it");
    }

    @Test
    public void testObj57HurtLatchReascendsWhenPreviousFrameHurtCrossesNegativeCountdown() throws Exception {
        Sonic2MCZBossInstance boss = newMczBossAt(0x2200, 0x0660);
        setState(boss, "routineSecondary", 0x06);
        setField(boss, "countdown", 0);
        setBooleanField(boss, "bossHurtSonic", true);

        boss.update(0, null);

        assertEquals(0x00, bossState(boss).routineSecondary,
                "Sub6 consumes boss_hurt_sonic when Boss_Countdown crosses negative");
        assertFalse(getBooleanField(boss, "bossHurtSonic"),
                "Consumed boss_hurt_sonic is cleared by Obj57_Main_Sub6_ReAscend1");
        assertEquals(0x64, getIntField(boss, "countdown"),
                "Reascend resets Boss_Countdown to the cycle delay");
    }

    @Test
    public void testObj57UsesRomOwnedEscapeDeleteInsteadOfManagerOutOfRange() throws Exception {
        Sonic2MCZBossInstance boss = newMczBossAt(0x2200, 0x0660);

        assertTrue(boss.isPersistent(),
                "Obj57 must not be unloaded by the shared out-of-range path before SubC opens Camera_Max_X_pos");
    }

    private static Sonic2MCZBossInstance newMczBossAt(int x, int y) throws Exception {
        Sonic2MCZBossInstance boss = new Sonic2MCZBossInstance(
                new ObjectSpawn(x, y, 0x57, 0, 0, false, y));
        BossStateContext state = bossState(boss);
        state.x = x;
        state.y = y;
        state.xFixed = x << 16;
        state.yFixed = y << 16;
        return boss;
    }

    private static BossStateContext bossState(Sonic2MCZBossInstance boss) throws Exception {
        Field stateField = AbstractBossInstance.class.getDeclaredField("state");
        stateField.setAccessible(true);
        return (BossStateContext) stateField.get(boss);
    }

    private static void setState(Sonic2MCZBossInstance boss, String name, int value) throws Exception {
        Field field = BossStateContext.class.getDeclaredField(name);
        field.setAccessible(true);
        field.setInt(bossState(boss), value);
    }

    private static void setField(Sonic2MCZBossInstance boss, String name, int value) throws Exception {
        Field field = Sonic2MCZBossInstance.class.getDeclaredField(name);
        field.setAccessible(true);
        field.setInt(boss, value);
    }

    private static int getIntField(Sonic2MCZBossInstance boss, String name) throws Exception {
        Field field = Sonic2MCZBossInstance.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.getInt(boss);
    }

    private static void setBooleanField(Sonic2MCZBossInstance boss, String name, boolean value) throws Exception {
        Field field = Sonic2MCZBossInstance.class.getDeclaredField(name);
        field.setAccessible(true);
        field.setBoolean(boss, value);
    }

    private static boolean getBooleanField(Sonic2MCZBossInstance boss, String name) throws Exception {
        Field field = Sonic2MCZBossInstance.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.getBoolean(boss);
    }

}
