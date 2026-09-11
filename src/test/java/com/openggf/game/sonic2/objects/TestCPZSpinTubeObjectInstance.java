package com.openggf.game.sonic2.objects;

import com.openggf.game.rules.GameRules;
import com.openggf.game.sonic2.constants.Sonic2AnimationIds;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.TestObjectServices;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.sprites.playable.ObjectControlState;
import com.openggf.tests.TestablePlayableSprite;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestCPZSpinTubeObjectInstance {

    @Test
    void captureUsesObjectControlWithoutGlobalControlLockedLatch() {
        ObjectSpawn spawn = new ObjectSpawn(0x0780, 0x0380, 0x1E, 0x02, 0, false, 0);
        TestablePlayableSprite player = new TestablePlayableSprite(
                "sonic",
                (short) spawn.x(),
                (short) spawn.y());
        player.setGameRulesForTest(GameRules.SONIC_2);
        player.setLogicalInputState(false, false, true, false, false);
        player.setJumping(true);
        player.endOfTick();
        assertEquals(AbstractPlayableSprite.INPUT_LEFT, player.getInputHistory(0));

        CPZSpinTubeObjectInstance tube = new CPZSpinTubeObjectInstance(spawn, "CPZSpinTube");
        tube.setServices(new TestObjectServices());

        tube.update(0, player);

        assertTrue(player.isObjectControlled(),
                "Obj1E writes obj_control=$81, so tube traversal must suppress normal movement/physics.");
        assertFalse(player.isControlLocked(),
                "S2 Obj1E writes obj_control(a1), not global Control_Locked; Ctrl_1_Logical must keep refreshing "
                        + "while the tube owns movement (s2.asm:48551-48568).");
        assertEquals(Sonic2AnimationIds.ROLL.id(), player.getAnimationId());
        assertFalse(player.isJumping(),
                "Obj1E loc_22688 clears jumping(a1) on capture, so later tube exit velocity is not jump-height capped.");

        player.setLogicalInputState(false, false, false, false, false);
        player.endOfTick();
        assertEquals(0, player.getInputHistory(0),
                "With Control_Locked untouched, a raw zero input refreshes Ctrl_1_Logical on the next tick "
                        + "instead of keeping stale pre-capture input.");
    }

    @Test
    void captureForcesRollAnimationWithoutSettingRollingStatus() {
        ObjectSpawn spawn = new ObjectSpawn(0x0780, 0x0380, 0x1E, 0x02, 0, false, 0);
        TestablePlayableSprite player = new TestablePlayableSprite(
                "sonic",
                (short) spawn.x(),
                (short) spawn.y());
        player.setGameRulesForTest(GameRules.SONIC_2);
        assertFalse(player.getRolling(), "precondition: standing player enters Obj1E");

        CPZSpinTubeObjectInstance tube = new CPZSpinTubeObjectInstance(spawn, "CPZSpinTube");
        tube.setServices(new TestObjectServices());

        tube.update(0, player);

        assertEquals(Sonic2AnimationIds.ROLL.id(), player.getAnimationId(),
                "Obj1E loc_22688 writes AniIDSonAni_Roll to anim(a1).");
        assertFalse(player.getRolling(),
                "Obj1E loc_22688 does not set status.player.rolling; it only forces the roll animation.");
        assertEquals(0x03B0, player.getCentreY(),
                "The capture waypoint y_pos must remain the ROM centre value without setRolling's 5px radius shift.");
    }

    @Test
    void engineDebugMovementCannotBeCapturedByTube() {
        ObjectSpawn spawn = new ObjectSpawn(0x0780, 0x0380, 0x1E, 0x02, 0, false, 0);
        TestablePlayableSprite player = new TestablePlayableSprite(
                "sonic",
                (short) spawn.x(),
                (short) spawn.y());
        player.setGameRulesForTest(GameRules.SONIC_2);
        player.setDebugMode(true);

        CPZSpinTubeObjectInstance tube = new CPZSpinTubeObjectInstance(spawn, "CPZSpinTube");
        tube.setServices(new TestObjectServices());

        tube.update(0, player);

        assertFalse(player.isObjectControlled(),
                "The supported engine debug movement mode must remain outside Obj1E capture.");
        assertEquals(0, player.getAnimationId(),
                "CPZ must not force the roll animation while the player is in engine debug movement.");
    }

    @Test
    void fullReleaseClearsObjectControlAndPreservesYSubpixel() throws Exception {
        ObjectSpawn spawn = new ObjectSpawn(0x2480, 0x0500, 0x1E, 0x02, 0, false, 0);
        TestablePlayableSprite player = new TestablePlayableSprite("sonic", (short) 0x24E8, (short) 0x0B30);
        player.setGameRulesForTest(GameRules.SONIC_2);
        player.setSubpixelRaw(0x4000, 0xAA00);
        player.setObjectControlled(true);
        player.setObjectControlSuppressesMovement(true);
        player.setAir(true);
        player.setAnimationId(Sonic2AnimationIds.ROLL);

        CPZSpinTubeObjectInstance tube = new CPZSpinTubeObjectInstance(spawn, "CPZSpinTube");
        tube.setServices(new TestObjectServices());
        var characterStateConstructor = Class.forName(CPZSpinTubeObjectInstance.class.getName() + "$CharacterState")
                .getDeclaredConstructor();
        characterStateConstructor.setAccessible(true);
        Object characterState = characterStateConstructor.newInstance();
        var exitTube = CPZSpinTubeObjectInstance.class.getDeclaredMethod(
                "exitTube", AbstractPlayableSprite.class, characterState.getClass(), int.class);
        exitTube.setAccessible(true);

        exitTube.invoke(tube, player, characterState, 3722);

        assertEquals(0x0330, player.getCentreY(),
                "Obj1E loc_227A6 masks y_pos with $7FF before release.");
        assertEquals(0xAA00, player.getYSubpixelRaw(),
                "andi.w #$7FF,y_pos(a1) leaves the sibling y_sub word untouched.");
        assertFalse(player.isObjectControlled(),
                "Obj1E loc_227A6 directly clears obj_control(a1).");
        assertFalse(player.isObjectControlSuppressesMovement(),
                "The loc_227A6 full-release path must allow the next player movement step to consume exit velocity.");
        assertTrue(player.getAir(),
                "Obj1E loc_22688 sets status.player.in_air and loc_227A6 does not clear it.");
        assertEquals(Sonic2AnimationIds.ROLL.id(), player.getAnimationId(),
                "Obj1E leaves anim(a1)=AniIDSonAni_Roll active after loc_227A6; CPZ1 BizHawk probe "
                        + "shows anim=02 while obj_control=00 at trace frames 3868-3874.");
        assertFalse(player.getSpringing(),
                "Obj1E loc_227A6 clears obj_control and masks y_pos only; it sets no springing "
                        + "latch (the former non-ROM collision-immunity shim was removed).");
        assertFalse(player.getRolling(),
                "Obj1E preserves the roll animation byte without setting status.player.rolling.");

        player.setAnimationId(Sonic2AnimationIds.WAIT);
        var preserveReleasedRollAnimation = CPZSpinTubeObjectInstance.class.getDeclaredMethod(
                "preserveReleasedRollAnimation", AbstractPlayableSprite.class, characterState.getClass());
        preserveReleasedRollAnimation.setAccessible(true);
        preserveReleasedRollAnimation.invoke(tube, player, characterState);

        assertEquals(Sonic2AnimationIds.ROLL.id(), player.getAnimationId(),
                "The CPZ-local post-release hold restores the ROM anim byte before later object gates run.");

        player.endOfTick();

        assertFalse(player.isObjectControlled());
        assertFalse(player.isObjectControlSuppressesMovement());
    }

    @Test
    void mainPathCompletionKeepsObjectControlForNeighborTubeHandoff() throws Exception {
        ObjectSpawn spawn = new ObjectSpawn(0x2480, 0x0500, 0x1E, 0x02, 0, false, 0);
        TestablePlayableSprite player = new TestablePlayableSprite("sonic", (short) 0x24E8, (short) 0x0B30);
        player.setGameRulesForTest(GameRules.SONIC_2);
        player.setSubpixelRaw(0x4000, 0xAA00);
        player.setObjectControlled(true);
        player.setObjectControlSuppressesMovement(true);

        CPZSpinTubeObjectInstance tube = new CPZSpinTubeObjectInstance(spawn, "CPZSpinTube");
        tube.setServices(new TestObjectServices());
        var characterStateConstructor = Class.forName(CPZSpinTubeObjectInstance.class.getName() + "$CharacterState")
                .getDeclaredConstructor();
        characterStateConstructor.setAccessible(true);
        Object characterState = characterStateConstructor.newInstance();
        var stateField = characterState.getClass().getDeclaredField("state");
        stateField.setAccessible(true);
        stateField.setInt(characterState, 4);
        var completeMainPathHandoff = CPZSpinTubeObjectInstance.class.getDeclaredMethod(
                "completeMainPathHandoff", AbstractPlayableSprite.class, characterState.getClass());
        completeMainPathHandoff.setAccessible(true);

        completeMainPathHandoff.invoke(tube, player, characterState);

        assertEquals(0x0330, player.getCentreY(),
                "Obj1E loc_22858 masks y_pos with $7FF at main-path completion.");
        assertEquals(0xAA00, player.getYSubpixelRaw(),
                "andi.w #$7FF,y_pos(a1) leaves the sibling y_sub word untouched.");
        assertEquals(0, stateField.getInt(characterState),
                "Obj1E loc_22858 clears this tube's character mode byte.");
        assertTrue(player.isObjectControlled(),
                "Obj1E loc_22858 does not clear obj_control(a1); neighboring Obj1E owns the next handoff.");
        assertTrue(player.isObjectControlSuppressesMovement(),
                "Leaving obj_control=$81 must keep normal movement/gravity suppressed through the handoff.");

        player.endOfTick();

        assertTrue(player.isObjectControlled(),
                "Unlike loc_227A6, loc_22858 does not schedule a deferred object-control release.");
        assertTrue(player.isObjectControlSuppressesMovement());
    }

    @Test
    void destinationHandoffUsesLivePositionWithoutInventingCatchUpMovement() {
        ObjectSpawn spawn = new ObjectSpawn(0x1000, 0x0200, 0x1E, 0x3D, 0, false, 0);
        TestablePlayableSprite player = new TestablePlayableSprite("tails", (short) 0x10F8, (short) 0x0230);
        player.setGameRulesForTest(GameRules.SONIC_2);
        player.setSubpixelRaw(0xD900, 0x0500);
        ObjectControlState.nativeBit7FullControl().applyTo(player);
        player.capturePrePhysicsSnapshot();

        // The source tube has already moved the player this frame. Obj1E reads
        // the live x_pos/y_pos when this destination slot runs.
        player.setCentreXPreserveSubpixel((short) 0x10F0);

        CPZSpinTubeObjectInstance tube = new CPZSpinTubeObjectInstance(spawn, "CPZSpinTube");
        tube.setServices(new TestObjectServices());

        tube.update(0, player);

        assertEquals(0x10F0, player.getCentreX() & 0xFFFF,
                "The capture pass writes velocity but does not invent an Obj1E_MoveCharacter call.");
        assertEquals(0x0230, player.getCentreY() & 0xFFFF);
        assertEquals((short) 0xF800, player.getXSpeed());
        assertTrue(player.isObjectControlled());

        player.setCentreXPreserveSubpixel((short) 0x10F0);
        tube.update(1, player);

        assertEquals(0x10E8, player.getCentreX() & 0xFFFF,
                "The destination starts entry movement on its following object dispatch.");

        tube.update(2, player);

        assertEquals(0x10E0, player.getCentreX() & 0xFFFF,
                "Subsequent destination dispatches continue normal entry movement.");
    }
}
