package com.openggf.tests;

import com.openggf.game.OscillationManager;
import com.openggf.game.sonic3k.Sonic3kObjectArtKeys;
import com.openggf.game.sonic3k.Sonic3kPlcArtRegistry;
import com.openggf.game.sonic3k.constants.Sonic3kAnimationIds;
import com.openggf.game.sonic3k.constants.Sonic3kConstants;
import com.openggf.game.sonic3k.objects.HCZSpinningColumnObjectInstance;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.SolidContact;
import com.openggf.level.objects.SolidObjectParams;
import com.openggf.physics.Direction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class TestS3kHczSpinningColumn {

    @BeforeEach
    public void resetOscillation() {
        OscillationManager.reset();
    }

    @Test
    public void hcz2ArtRegistryIncludesSpinningColumn() {
        var entry = Sonic3kPlcArtRegistry.getPlan(1, 1).levelArt().stream()
                .filter(levelArt -> Sonic3kObjectArtKeys.HCZ_SPINNING_COLUMN.equals(levelArt.key()))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "HCZ2 art plan should include spinning column level art"));
        assertEquals(Sonic3kConstants.MAP_HCZ_SPINNING_COLUMN_ADDR, entry.mappingAddr(), "Spinning column must point at the mapping table base, not Frame_231AF4");
        assertEquals(0x231AEE, entry.mappingAddr());
        assertTrue(entry.mappingAddr() > 0x200000, "Spinning column mapping table should stay in the lock-on data region");
    }

    @Test
    public void horizontalSubtypeFollowsRomMotionAndAnimation() {
        HCZSpinningColumnObjectInstance column = new HCZSpinningColumnObjectInstance(
                new ObjectSpawn(0x1000, 0x0800, 0x68, 0x01, 0x00, false, 0));

        column.update(0, null);

        assertEquals(0x1000 - 0x6F, column.getX());
        assertEquals(0x0800, column.getY());
        assertEquals(2, getMappingFrame(column));

        SolidObjectParams params = column.getSolidParams();
        assertEquals(0x1B, params.halfWidth());
        assertEquals(0x20, params.airHalfHeight());
        assertEquals(0x21, params.groundHalfHeight());
        assertFalse(column.carriesRiderOnHorizontalMove(null),
                "Obj68 passes post-movement x_pos through d4, producing zero horizontal carry");
        assertTrue(column.usesInclusiveRightEdge(),
                "SolidObjectFull keeps relX == d1*2 as an exact-edge side contact");
        assertTrue(column.rejectsBit7ObjectControlSideContact(null));
        assertTrue(column.rejectsBit7ObjectControlNewSolidContact(null));
    }

    @Test
    public void verticalSubtypeUsesOscillationTable() {
        HCZSpinningColumnObjectInstance column = new HCZSpinningColumnObjectInstance(
                new ObjectSpawn(0x1000, 0x0800, 0x68, 0x02, 0x00, false, 0));

        int expectedOffset = OscillationManager.getByte(0x1C) & 0xFF;

        column.update(0, null);

        assertEquals(0x1000, column.getX());
        assertEquals(0x0800 + expectedOffset, column.getY());
        assertEquals(2, getMappingFrame(column));
    }

    @Test
    public void standingPlayerGetsCapturedAndJumpReleaseUsesObjectLaunchPath() {
        HCZSpinningColumnObjectInstance column = new HCZSpinningColumnObjectInstance(
                new ObjectSpawn(0x1000, 0x0800, 0x68, 0x01, 0x00, false, 0));
        TestablePlayableSprite player = new TestablePlayableSprite("sonic", (short) 0x1008, (short) 0x07C0);
        player.defineSpeeds();

        column.onSolidContact(player, new SolidContact(true, false, false, false, false), 0);
        column.update(0, null);

        assertTrue(player.isObjectControlled());
        assertTrue(player.isObjectControlAllowsCpu());
        assertTrue(player.isObjectControlSuppressesMovement());
        assertFalse(player.isControlLocked(),
                "Obj68 writes object_control=3 but never Ctrl_1_locked");
        assertTrue(player.isObjectMappingFrameControl());
        assertFalse(player.getAir());
        assertEquals(Sonic3kAnimationIds.WALK.id(), player.getAnimationId());
        assertEquals(-1, player.getForcedAnimationId());
        assertEquals(0x55, player.getMappingFrame());

        int centreYBeforeRelease = player.getCentreY();
        player.setJumpInputPressed(true);
        column.onSolidContact(player, new SolidContact(true, false, false, false, false), 1);
        column.update(1, null);

        assertFalse(player.isObjectControlled());
        assertFalse(player.isControlLocked());
        assertFalse(player.isObjectMappingFrameControl());
        assertTrue(player.getAir());
        assertTrue(player.isJumping());
        assertTrue(player.getRolling());
        assertEquals(Sonic3kAnimationIds.ROLL.id(), player.getAnimationId());
        assertEquals(centreYBeforeRelease, player.getCentreY(),
                "column release changes radii without moving native y_pos");
        assertEquals(0, player.getXSpeed());
        assertEquals(-0x680, player.getYSpeed());
        assertEquals(TestablePlayableSprite.INPUT_JUMP, player.getLogicalInputState());
        assertTrue(player.getJumpPressHistory(0),
                "column release republishes the live press into Sonic_RecordPos history");
    }

    @Test
    public void heldJumpWithoutNewLogicalPressKeepsRiderCaptured() {
        HCZSpinningColumnObjectInstance column = new HCZSpinningColumnObjectInstance(
                new ObjectSpawn(0x1000, 0x0800, 0x68, 0x01, 0x00, false, 0));
        TestablePlayableSprite player = new TestablePlayableSprite(
                "sonic", (short) 0x1008, (short) 0x07C0);
        player.defineSpeeds();

        column.onSolidContact(player, new SolidContact(true, false, false, false, false), 0);
        column.update(0, null);
        player.setJumpInputPressed(true, false);
        column.onSolidContact(player, new SolidContact(true, false, false, false, false), 1);
        column.update(1, null);

        assertTrue(player.isObjectControlled(),
                "Obj68 masks the newly-pressed low byte of Ctrl_1_logical, not held B");
        assertFalse(player.getAir());
        assertEquals(0, player.getYSpeed());
    }

    @Test
    public void jumpOnTwistBoundaryRetainsThePreviouslyPublishedMapping() {
        HCZSpinningColumnObjectInstance column = new HCZSpinningColumnObjectInstance(
                new ObjectSpawn(0x1000, 0x0800, 0x68, 0x00, 0x00, false, 0));
        TestablePlayableSprite player = new TestablePlayableSprite(
                "sonic", (short) 0x1008, (short) 0x07C0);
        player.defineSpeeds();

        column.onSolidContact(player, new SolidContact(true, false, false, false, false), 0);
        column.update(0, null);
        for (int frame = 1; frame <= 5; frame++) {
            column.onSolidContact(player,
                    new SolidContact(true, false, false, false, false), frame);
            column.update(frame, null);
        }
        assertEquals(0x55, player.getMappingFrame());

        player.setJumpInputPressed(true);
        column.onSolidContact(player, new SolidContact(true, false, false, false, false), 6);
        column.update(6, null);

        assertEquals(0x55, player.getMappingFrame(),
                "the Obj68 jump branch returns before publishing the incremented twist frame");
        assertTrue(player.getAir());
        assertEquals(Sonic3kAnimationIds.ROLL.id(), player.getAnimationId());
    }

    @Test
    public void ordinaryReleasePreservesTheRidersPublishedAnimation() {
        HCZSpinningColumnObjectInstance column = new HCZSpinningColumnObjectInstance(
                new ObjectSpawn(0x1000, 0x0800, 0x68, 0x01, 0x00, false, 0));
        TestablePlayableSprite player = new TestablePlayableSprite(
                "sonic", (short) 0x1008, (short) 0x07C0);
        player.defineSpeeds();

        column.onSolidContact(player, new SolidContact(true, false, false, false, false), 0);
        column.update(0, null);
        player.setAnimationId(Sonic3kAnimationIds.DUCK);

        column.update(1, null);

        assertFalse(player.isObjectControlled());
        assertEquals(Sonic3kAnimationIds.DUCK.id(), player.getAnimationId(),
                "loc_328AC releases control without writing anim");
    }

    @Test
    public void twistRenderFlipDoesNotOverwriteLogicalFacing() {
        HCZSpinningColumnObjectInstance column = new HCZSpinningColumnObjectInstance(
                new ObjectSpawn(0x1000, 0x0800, 0x68, 0x00, 0x00, false, 0));
        TestablePlayableSprite player = new TestablePlayableSprite(
                "sonic", (short) 0x0FF8, (short) 0x07C0);
        player.defineSpeeds();
        player.setDirection(Direction.RIGHT);

        column.onSolidContact(player,
                new SolidContact(true, false, false, false, false), 0);
        column.update(0, null);

        assertEquals(Direction.RIGHT, player.getDirection(),
                "sub_32610 changes render_flags only, never Status_Facing");
        assertTrue(player.getRenderHFlip(),
                "the initial left-side twist frame still applies its visual flip");

        column.onSolidContact(player,
                new SolidContact(true, false, false, false, false), 1);
        column.update(1, null);

        assertEquals(0x0FF8, player.getCentreX(),
                "the ROM 8.8 radius word keeps the first left-side hold at x-8");
    }

    private int getMappingFrame(HCZSpinningColumnObjectInstance column) {
        try {
            var field = HCZSpinningColumnObjectInstance.class.getDeclaredField("mappingFrame");
            field.setAccessible(true);
            return field.getInt(column);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Unable to read mapping frame", e);
        }
    }
}
