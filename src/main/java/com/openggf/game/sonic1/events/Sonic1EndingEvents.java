package com.openggf.game.sonic1.events;

import com.openggf.game.sonic1.constants.Sonic1AnimationIds;
import com.openggf.game.sonic1.objects.Sonic1EndingSonicObjectInstance;
import com.openggf.level.objects.ObjectManager;
import com.openggf.physics.Direction;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.game.GameServices;

/**
 * Sonic 1 ending sequence bootstrap events.
 * ROM: End_MoveSonic in sonic.asm (GM_Ending main loop).
 *
 * <p>Implements the transition choreography right after entering id_EndZ:
 * force Sonic to run left, switch to right at X &lt; 0x90, then stop at X &gt;= 0xA0.
 * Once stopped, hides the player sprite and spawns Object 87 (EndSonic) which
 * manages the remainder of the ending cutscene (emeralds, STH logo, credits).
 */
class Sonic1EndingEvents extends Sonic1ZoneEvents {
    private static final int END_MOVE_RUN_LEFT = 0;
    private static final int END_MOVE_RUN_RIGHT = 2;
    private static final int END_MOVE_STOP = 4;
    private static final int END_MOVE_DONE = 6;

    private boolean bootstrapApplied;
    private boolean endingSonicSpawned;

    Sonic1EndingEvents() {
    }

    @Override
    void init() {
        super.init();
        bootstrapApplied = false;
        endingSonicSpawned = false;
    }

    boolean isBootstrapApplied()  { return bootstrapApplied; }
    boolean isEndingSonicSpawned() { return endingSonicSpawned; }
    void setBootstrapApplied(boolean v)   { bootstrapApplied   = v; }
    void setEndingSonicSpawned(boolean v) { endingSonicSpawned = v; }

    /**
     * The ending sequence has its own main loop: {@code End_MainLoop}
     * (docs/s1disasm/sonic.asm:3662-3680) runs ExecuteObjects, DeformLayers,
     * BuildSprites, ObjPosLoad, PaletteCycle, OscillateNumDo and
     * SynchroAnimate, and stops there — unlike {@code Level_MainLoop}
     * (docs/s1disasm/sonic.asm:3035) it never calls {@code SignpostArtLoad}.
     * <p>
     * That matters here because the ending's right camera boundary is only
     * $500 (LevelSizeArray "good ending", docs/s1disasm/_inc/LevelSizeArray.asm:50)
     * while Sonic starts at X=$620 with the camera already pinned to $500, so
     * SignpostArtLoad's {@code v_limitright2-$100} test would pass on the very
     * first frame and lock the left boundary to $400. Sonic runs left, hits
     * that boundary at $410 and the cutscene never reaches its X&lt;$90 handoff.
     */
    @Override
    boolean runsLevelMainLoopTail() {
        return false;
    }

    @Override
    void update(int act) {
        AbstractPlayableSprite player = camera().getFocusedSprite();
        if (player == null) {
            return;
        }

        if (!bootstrapApplied) {
            applyBootstrap(player);
            bootstrapApplied = true;
        }

        switch (eventRoutine) {
            case END_MOVE_RUN_LEFT -> updateRunLeft(player);
            case END_MOVE_RUN_RIGHT -> updateRunRight(player);
            case END_MOVE_STOP -> updateStop(player);
            case END_MOVE_DONE -> { }
            default -> { }
        }
    }

    private void applyBootstrap(AbstractPlayableSprite player) {
        // ROM GM_Ending init: face left, lock controls, force LEFT input, inertia = -$800.
        player.setDirection(Direction.LEFT);
        player.setControlLocked(true);
        player.setForcedInputMask(AbstractPlayableSprite.INPUT_LEFT);
        player.setGSpeed((short) -0x800);
    }

    private void updateRunLeft(AbstractPlayableSprite player) {
        int playerX = player.getCentreX() & 0xFFFF;
        if (playerX >= 0x90) {
            return;
        }

        // ROM End_MoveSonic state 0 -> 2
        eventRoutine = END_MOVE_RUN_RIGHT;
        player.setControlLocked(true);
        player.setForcedInputMask(AbstractPlayableSprite.INPUT_RIGHT);
    }

    private void updateRunRight(AbstractPlayableSprite player) {
        int playerX = player.getCentreX() & 0xFFFF;
        if (playerX < 0xA0) {
            return;
        }

        // ROM End_MoveSonic state 2 -> 4:
        // stop movement and lock controls before handoff to ending object.
        eventRoutine = END_MOVE_STOP;
        player.clearForcedInputMask();
        player.setGSpeed((short) 0);
        player.setXSpeed((short) 0);
        player.setControlLocked(true);
        player.setAnimationId(Sonic1AnimationIds.WAIT);
        player.setAnimationFrameIndex(0);
    }

    /**
     * ROM End_MoveSonic state 4 → 6: Transform player into Obj87 (EndSonic).
     * In the ROM, this overwrites the player's object ID. In the engine, we
     * hide the player and spawn a separate EndingSonic object at the same position.
     */
    private void updateStop(AbstractPlayableSprite player) {
        player.setCentreX((short) 0x00A0);
        player.setGSpeed((short) 0);
        player.setXSpeed((short) 0);
        player.clearForcedInputMask();
        player.setControlLocked(true);

        eventRoutine = END_MOVE_DONE;

        // ROM: move.b #id_EndSonic,obID+v_player / clr.b obRoutine+v_player
        // Hide the player sprite and spawn the ending Sonic object.
        if (!endingSonicSpawned) {
            endingSonicSpawned = true;
            player.setHidden(true);

            int sonicX = player.getCentreX() & 0xFFFF;
            int sonicY = player.getCentreY() & 0xFFFF;
            Sonic1EndingSonicObjectInstance endSonic =
                    new Sonic1EndingSonicObjectInstance(sonicX, sonicY);
            ObjectManager om = levelManager().getObjectManager();
            if (om != null) {
                om.addDynamicObject(endSonic);
            }
        }
    }
}
