package com.openggf.game.sonic3k.objects.bosses;

import com.openggf.game.PlayableEntity;
import com.openggf.game.sonic3k.Sonic3kObjectArtKeys;
import com.openggf.game.sonic3k.audio.Sonic3kSfx;
import com.openggf.level.objects.ObjectRenderManager;
import com.openggf.level.objects.ObjectPlayerQuery;
import com.openggf.level.objects.StubObjectServices;
import com.openggf.level.render.PatternSpriteRenderer;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestCnzEndBossChildren {

    @Test
    void bodyShipAndHeadShareNativeRightFacingRenderBit() throws Exception {
        CnzEndBossInstance boss = boss();
        setBoolean(boss, "startupComplete", true);
        setBoolean(boss, "facingRight", true);
        setBossRoutine(boss, CnzEndBossInstance.Routine.ENTRY);
        PatternSpriteRenderer bodyRenderer = mock(PatternSpriteRenderer.class);
        PatternSpriteRenderer shipRenderer = mock(PatternSpriteRenderer.class);
        when(bodyRenderer.isReady()).thenReturn(true);
        when(shipRenderer.isReady()).thenReturn(true);
        ObjectRenderManager renderManager = mock(ObjectRenderManager.class);
        when(renderManager.getRenderer(Sonic3kObjectArtKeys.CNZ_END_BOSS)).thenReturn(bodyRenderer);
        when(renderManager.getRenderer(Sonic3kObjectArtKeys.ROBOTNIK_SHIP)).thenReturn(shipRenderer);
        StubObjectServices services = new StubObjectServices() {
            @Override public ObjectRenderManager renderManager() { return renderManager; }
        };
        boss.setServices(services);
        CnzEndBossRobotnikShipChild ship = new CnzEndBossRobotnikShipChild(boss);
        ship.setServices(services);
        CnzEndBossRobotnikHeadChild head = new CnzEndBossRobotnikHeadChild(ship);
        head.setServices(services);

        boss.appendRenderCommands(List.of());
        ship.appendRenderCommands(List.of());
        head.appendRenderCommands(List.of());

        verify(bodyRenderer).drawFrameIndex(0, boss.getCentreX(), boss.getCentreY(), true, false);
        assertEquals(boss.facingRight(), ship.isFacingRight(),
                "Refresh_ChildPositionAdjusted copies parent render bit 0 without inversion");
        verify(shipRenderer).drawFrameIndexForcedPriority(
                9, ship.getCentreX(), ship.getCentreY(), true, false, 0, true);
        verify(shipRenderer).drawFrameIndex(0, 0, 0, true, false);
    }

    @Test
    void gravityMachineUsesSixteenFrameNativeCadenceWithoutLobeDuplication() throws Exception {
        CnzEndBossInstance boss = boss();
        setBoolean(boss, "magneticFieldActive", true);
        RecordingSfxServices services = new RecordingSfxServices();
        boss.setServices(services);
        CnzEndBossFieldChild left = new CnzEndBossFieldChild(boss, -0x0C);
        CnzEndBossFieldChild right = new CnzEndBossFieldChild(boss, 0x0C);
        left.setServices(services);
        right.setServices(services);

        for (int frame : new int[] {0, 1, 15, 16}) {
            left.update(frame, null);
            right.update(frame, null);
        }

        assertEquals(List.of(Sonic3kSfx.GRAVITY_MACHINE.id, Sonic3kSfx.GRAVITY_MACHINE.id),
                services.playedSfx,
                "Play_SFX_Continuous requests $78 once when V_int_run_count's low nibble is zero");
    }

    @Test
    void landingPlaysFloorThumpButReattachmentAddsNoPickupSound() throws Exception {
        CnzEndBossInstance boss = boss();
        RecordingSfxServices services = new RecordingSfxServices();
        PlayableEntity player = playerAt(boss.getCentreX());
        CnzEndBossMagnetChild magnet = new CnzEndBossMagnetChild(boss);
        magnet.setServices(services.withPlayerQuery(
                new ObjectPlayerQuery(() -> player, List::of)));
        field(magnet, "yVelocity").setInt(magnet, 0x70);

        magnet.resolveFloorContact(0);

        assertEquals(List.of(Sonic3kSfx.FLOOR_THUMP.id), services.playedSfx,
                "loc_6E8B6 plays $5D on the final low-speed landing");
        services.playedSfx.clear();

        magnet.reattachAtDescentBottom();

        assertEquals(List.of(), services.playedSfx,
                "loc_6E920 reattaches the magnet without a grab, clank, or other SFX");
    }

    @Test
    void postFieldWindDownConsumesExactFfWaitBeforeDescent() throws Exception {
        CnzEndBossInstance boss = boss();
        boss.setServices(new StubObjectServices().withPlayerQuery(
                new ObjectPlayerQuery(() -> null, List::of)));
        setBossRoutine(boss, CnzEndBossInstance.Routine.CHARGE);
        setBoolean(boss, "magneticFieldActive", true);
        field(boss, "routineTimer").setInt(boss, 0);
        int heldY = boss.getCentreY();

        boss.update(0, null);

        assertEquals(CnzEndBossInstance.Routine.WIND_DOWN, boss.nativeRoutine(),
                "loc_6E650 must enter the dedicated parent bit-7 wind-down state");
        assertEquals(heldY, boss.getCentreY(),
                "loc_6E62C is Obj_Wait and does not dispatch Swing_UpAndDown/MoveSprite2");
        for (int frame = 0; frame < 255; frame++) {
            boss.update(frame + 1, null);
        }
        assertEquals(heldY, boss.getCentreY(),
                "the entire bit-7 wind-down interval remains position-stationary");
        assertEquals(CnzEndBossInstance.Routine.WIND_DOWN, boss.nativeRoutine(),
                "Obj_Wait with $2E=$FF remains active for 255 decrement frames");

        boss.update(256, null);

        assertEquals(CnzEndBossInstance.Routine.DESCEND, boss.nativeRoutine(),
                "the 256th wind-down update must dispatch loc_6E66C");
    }

    @Test
    void exactFloorContactRunsMagnetBounceCallback() throws Exception {
        CnzEndBossInstance boss = boss();
        CnzEndBossMagnetChild magnet = magnet(boss, playerAt(boss.getCentreX()));
        field(magnet, "centreY").setInt(magnet, 0x0300);
        field(magnet, "yVelocity").setInt(magnet, 0x0100);

        magnet.resolveFloorContact(0);

        assertEquals(0x0300, magnet.getCentreY());
        assertEquals(-0x80, magnet.yVelocityForTest());
        assertFalse(magnet.isLanded());
    }

    @Test
    void lowSpeedMagnetLandingRetainsVelocityForNextDrop() throws Exception {
        CnzEndBossInstance boss = boss();
        PlayableEntity player = playerAt(boss.getCentreX());
        CnzEndBossMagnetChild magnet = magnet(boss, player);
        field(magnet, "yVelocity").setInt(magnet, 0x70);
        field(magnet, "ySubpixel").setInt(magnet, 0xA7);

        magnet.resolveFloorContact(0);
        magnet.reattachAtDescentBottom();
        magnet.beginDrop();

        assertEquals(0x70, magnet.yVelocityForTest(),
                "loc_6E8D2, loc_6E920, and loc_6E87E do not clear y_vel");
        assertEquals(0xA7, field(magnet, "ySubpixel").getInt(magnet),
                "the same routine-only transitions preserve y_subpixel");
    }

    @Test
    void descentBottomSignalsImmediateMagnetReattach() throws Exception {
        CnzEndBossInstance boss = boss();
        boss.setServices(new StubObjectServices());
        CnzEndBossMagnetChild magnet = magnet(boss, playerAt(boss.getCentreX()));
        magnet.beginDrop();
        setBoolean(magnet, "landed", true);
        field(magnet, "centreY").setInt(magnet, boss.getCentreY() + 0x10);
        field(boss, "magnetChild").set(boss, magnet);
        setBossRoutine(boss, CnzEndBossInstance.Routine.DESCEND);
        int heldY = boss.getCentreY();

        boss.update(0, null);

        assertEquals(CnzEndBossInstance.Routine.ASCEND, boss.nativeRoutine());
        assertEquals(heldY, boss.getCentreY(),
                "loc_6E69C switches routine without storing the final incremented Y");
        assertFalse(magnet.isReleasedForTest(),
                "loc_6E69C bit 3 must make loc_6E920 return the landed magnet to follow mode");
        assertEquals(boss.getCentreY() + 0x14, magnet.getCentreY(),
                "reattachment occurs at descent bottom, not after the later ascent");
    }

    @Test
    void finalAscentPixelBeginsTrackingInSameUpdate() throws Exception {
        CnzEndBossInstance boss = boss();
        boss.setServices(new StubObjectServices());
        int hoverY = boss.getCentreY();
        field(boss, "savedHoverY").setInt(boss, hoverY);
        field(boss, "centreY").setInt(boss, hoverY + 1);
        setBossRoutine(boss, CnzEndBossInstance.Routine.ASCEND);

        boss.update(0, null);

        assertEquals(hoverY, boss.getCentreY());
        assertEquals(CnzEndBossInstance.Routine.TRACK, boss.nativeRoutine(),
                "loc_6E6BC stores the hover Y and branches directly into tracking setup");
    }

    @Test
    void alignFacingUsesPreMoveComparisonOnFinalPixel() throws Exception {
        CnzEndBossInstance boss = boss();
        boss.setServices(new StubObjectServices());
        CnzEndBossMagnetChild magnet = magnet(boss, playerAt(boss.getCentreX()));
        field(magnet, "centreX").setInt(magnet, boss.getCentreX() + 1);
        field(boss, "magnetChild").set(boss, magnet);
        setBossRoutine(boss, CnzEndBossInstance.Routine.ALIGN);

        boss.update(0, null);

        assertEquals(magnet.getCentreX(), boss.getCentreX());
        assertEquals(true, boss.facingRight(),
                "loc_6E5D8 sets render bit 0 from the pre-move comparison on the final pixel");
    }

    @Test
    void magnetDropTargetsClosestNativePlayerAndMovesBeforeApplyingGravity() {
        CnzEndBossInstance boss = boss();
        PlayableEntity main = playerAt(boss.getCentreX() - 0x40);
        PlayableEntity sidekick = playerAt(boss.getCentreX() + 0x20);
        CnzEndBossMagnetChild magnet = magnet(boss, main, sidekick);

        magnet.beginDrop();

        assertEquals(0x100, magnet.xVelocityForTest(),
                "loc_6E87E must aim the released magnet toward the closest native player");
        int startX = magnet.getCentreX();
        int startY = magnet.getCentreY();
        magnet.update(0, main);

        assertEquals(startX, magnet.getCentreX(), "loc_6E87E returns after installing the fall routine");
        assertEquals(startY, magnet.getCentreY());
        assertEquals(0, magnet.yVelocityForTest());

        magnet.advanceDropMotion();

        assertEquals(startX + 1, magnet.getCentreX(), "MoveSprite applies x_vel on the first falling frame");
        assertEquals(startY, magnet.getCentreY(), "MoveSprite moves with the old zero y_vel first");
        assertEquals(0x38, magnet.yVelocityForTest(), "MoveSprite adds $38 gravity after movement");
        magnet.advanceDropMotion();
        assertEquals(startX + 2, magnet.getCentreX(),
                "horizontal drop velocity must persist through later fall/bounce updates");
        assertEquals(0x70, magnet.yVelocityForTest());
    }

    @Test
    void magnetRemainsHazardousWhileDockedAndLanded() throws Exception {
        CnzEndBossInstance boss = boss();
        PlayableEntity main = playerAt(boss.getCentreX());
        CnzEndBossMagnetChild magnet = magnet(boss, main);

        assertEquals(0x8B, magnet.getCollisionFlags(), "ObjDat3_6ED9C installs collision at init");
        magnet.beginDrop();
        setBoolean(magnet, "landed", true);
        assertEquals(0x8B, magnet.getCollisionFlags(),
                "sub_6ED22 clears collision only when the parent enters defeat");
        setBossRoutine(boss, CnzEndBossInstance.Routine.DEFEATED);
        assertEquals(0, magnet.getCollisionFlags());
    }

    @Test
    void magnetUsesExactBitThreeMultiDelayScriptAndResetsAtDescent() throws Exception {
        CnzEndBossInstance boss = boss();
        PlayableEntity main = playerAt(boss.getCentreX());
        CnzEndBossMagnetChild magnet = magnet(boss, main);
        magnet.beginDrop();
        setBoolean(magnet, "landed", true);
        setBossRoutine(boss, CnzEndBossInstance.Routine.CHARGE);

        int[] expected = {5, 4, 5, 4, 4, 4, 4, 4, 5};
        for (int frame : expected) {
            magnet.update(0, main);
            assertEquals(frame, magnet.frameForTest());
        }

        setBossRoutine(boss, CnzEndBossInstance.Routine.DESCEND);
        magnet.update(0, main);
        assertEquals(4, magnet.frameForTest(),
                "loc_6E910 resets the magnet head when parent bit 3 clears");
    }

    @Test
    void defeatScatterUnlinksExpiredMagnetFromRewindGraph() throws Exception {
        CnzEndBossInstance boss = boss();
        CnzEndBossMagnetChild magnet = magnet(boss, playerAt(boss.getCentreX()));
        field(boss, "magnetChild").set(boss, magnet);

        magnet.beginDefeatScatter();

        assertEquals(true, magnet.isDestroyed());
        assertNull(field(boss, "magnetChild").get(boss),
                "an expired native child must not remain in the captured boss graph");
    }

    @Test
    void nativeBossChildrenIgnoreGenericOffscreenCulling() {
        CnzEndBossInstance boss = boss();
        CnzEndBossMagnetChild magnet = magnet(boss, playerAt(boss.getCentreX()));
        CnzEndBossArmChild arm = new CnzEndBossArmChild(boss, 0);

        assertEquals(true, magnet.isPersistent());
        assertEquals(true, arm.isPersistent());
        assertEquals(magnet.getCentreX(), magnet.getMultiTouchRegions()[0].x());
        assertEquals(magnet.getCentreY(), magnet.getMultiTouchRegions()[0].y());
        assertEquals(arm.getCentreX(), arm.getMultiTouchRegions()[0].x());
        assertEquals(arm.getCentreY(), arm.getMultiTouchRegions()[0].y());
    }

    @Test
    void repeatedArmSubtypesProduceQuarterTurnPhases() {
        CnzEndBossInstance boss = boss();
        for (int childIndex = 0; childIndex < 4; childIndex++) {
            CnzEndBossArmChild arm = new CnzEndBossArmChild(boss, childIndex << 6);
            assertEquals(childIndex << 6, arm.angleForTest());
            assertEquals(childIndex << 1, arm.getSpawn().subtype());
        }
    }

    @Test
    void bossTouchResponseStartsOnlyAfterNativeRoutineZeroSetup() throws Exception {
        CnzEndBossInstance boss = boss();

        assertEquals(0, boss.getCollisionFlags(),
                "the camera-gate wrapper does not call Draw_And_Touch_Sprite");
        setBoolean(boss, "startupComplete", true);
        assertEquals(0x06, boss.getCollisionFlags(),
                "loc_6E4F2 installs ObjDat_CNZEndBoss collision response 6");
        var region = boss.getMultiTouchRegions()[0];
        assertEquals(boss.getCentreX(), region.x());
        assertEquals(boss.getCentreY(), region.y());
    }

    @Test
    void armMultiDelayFrameThreeSurvivesAngleFrameSelection() throws Exception {
        CnzEndBossInstance boss = boss();
        CnzEndBossArmChild arm = new CnzEndBossArmChild(boss, 0);
        arm.setServices(new StubObjectServices());
        setBossRoutine(boss, CnzEndBossInstance.Routine.CHARGE);

        int[] expected = {3, 1, 3, 1, 1, 1, 1, 1, 3};
        for (int frame : expected) {
            arm.update(0, null);
            assertEquals(frame, arm.frameForTest(),
                    "sub_6EBF0 must preserve byte_6EE0E frame 3 and delay each pair by delay+1");
        }
    }

    private static CnzEndBossInstance boss() {
        return new CnzEndBossInstance(new com.openggf.level.objects.ObjectSpawn(
                0x4740, 0x0240, 0xA7, 0, 0, false, 0));
    }

    private static CnzEndBossMagnetChild magnet(CnzEndBossInstance boss, PlayableEntity main,
                                                 PlayableEntity... sidekicks) {
        CnzEndBossMagnetChild magnet = new CnzEndBossMagnetChild(boss);
        magnet.setServices(new StubObjectServices().withPlayerQuery(
                new ObjectPlayerQuery(() -> main, () -> List.of(sidekicks))));
        return magnet;
    }

    private static PlayableEntity playerAt(int x) {
        PlayableEntity player = mock(PlayableEntity.class);
        when(player.getCentreX()).thenReturn((short) x);
        return player;
    }

    private static void setBossRoutine(CnzEndBossInstance boss, CnzEndBossInstance.Routine routine)
            throws Exception {
        field(boss, "routine").set(boss, routine);
    }

    private static void setBoolean(Object target, String name, boolean value) throws Exception {
        field(target, name).setBoolean(target, value);
    }

    private static Field field(Object target, String name) throws NoSuchFieldException {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static final class RecordingSfxServices extends StubObjectServices {
        private final java.util.ArrayList<Integer> playedSfx = new java.util.ArrayList<>();

        @Override public void playSfx(int soundId) {
            playedSfx.add(soundId);
        }
    }
}
