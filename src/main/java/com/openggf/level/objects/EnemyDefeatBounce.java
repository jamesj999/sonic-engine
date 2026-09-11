package com.openggf.level.objects;

import com.openggf.game.PlayableEntity;

/**
 * The badnik-kill player rebound shared by all three ROMs.
 *
 * <p>S3K {@code EnemyDefeated} ({@code docs/skdisasm/sonic3k.asm:179752-179771}):
 * <pre>
 *   movea.w $44(a0),a1        ; the player recorded by the badnik's own touch check
 *   tst.w   y_vel(a1)
 *   bmi.s   loc_85750         ; rising  -> addi.w #$100,y_vel(a1)
 *   move.w  y_pos(a1),d0
 *   cmp.w   y_pos(a0),d0
 *   bhs.s   loc_85758         ; at or below the badnik -> subi.w #$100,y_vel(a1)
 *   neg.w   y_vel(a1)         ; above the badnik -> negate
 * </pre>
 * The S1 ({@code React_Enemy}) and S2 ({@code Touch_KillEnemy}) forms are identical.
 *
 * <p>Two callers reach this: {@link ObjectTouchResponseController} for the ENEMY touch
 * category, which performs the ROM's {@code Touch_KillEnemy} tail itself, and objects
 * whose ObjDat flags select the ROM's {@code Touch_Special} route and therefore call
 * {@code EnemyDefeated} from their own code (for example {@code Obj_MegaChopper} at
 * {@code sonic3k.asm:184243}). Neither path may apply the bounce twice.
 */
public final class EnemyDefeatBounce {

    private EnemyDefeatBounce() {
    }

    /**
     * Applies the {@code EnemyDefeated} y-velocity rebound to {@code player}.
     *
     * <p>Only {@code y_vel} is touched; the ROM does not set the air flag here, so the
     * collision system keeps resolving air state naturally (this is what preserves a
     * ground roll through a badnik bounce).
     *
     * @param player  the player the defeated object recorded as its toucher
     * @param enemyY  the defeated object's ROM {@code y_pos} (centre Y), resolved at the
     *                same moment as the overlap that produced the kill
     */
    public static void apply(PlayableEntity player, int enemyY) {
        short ySpeed = player.getYSpeed();
        if (ySpeed < 0) {
            // bmi loc_85750: addi.w #$100,y_vel(a1)
            player.setYSpeed((short) (ySpeed + 0x100));
            return;
        }
        // The overlap and bounce both dereference the same object slot in all three
        // ROMs; keep the already-resolved touch Y instead of re-reading a later
        // engine projection (S1 ReactToItem.asm:163,301-304; S2 s2.asm:
        // 85127,85414-85420; S3K sonic3k.asm:20697,20974-20989).
        if (player.getCentreY() < enemyY) {
            player.setYSpeed((short) -ySpeed);
        } else {
            // loc_85758: subi.w #$100,y_vel(a1)
            player.setYSpeed((short) (ySpeed - 0x100));
        }
    }
}
