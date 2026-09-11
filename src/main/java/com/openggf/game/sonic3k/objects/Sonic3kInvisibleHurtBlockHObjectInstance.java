package com.openggf.game.sonic3k.objects;

import com.openggf.game.DamageCause;
import com.openggf.game.PlayableEntity;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.SolidContact;

/**
 * Object 0x6A - InvisibleHurtBlockH (Sonic 3 & Knuckles).
 * <p>
 * Uses the same invisible solid collision as object 0x28, but applies normal
 * hurt handling on one face selected by the placement flip bits:
 * <ul>
 *   <li>no flips: top face only (standing contact)</li>
 *   <li>x-flip: side face only (swapped d6 bits 0/1 in the ROM)</li>
 *   <li>y-flip: bottom face only (swapped d6 bits 2/3 in the ROM)</li>
 * </ul>
 * <p>
 * ROM: Obj_InvisibleHurtBlockHorizontal / sub_1F58C (sonic3k.asm)
 */
public class Sonic3kInvisibleHurtBlockHObjectInstance extends Sonic3kInvisibleBlockObjectInstance {

    public Sonic3kInvisibleHurtBlockHObjectInstance(ObjectSpawn spawn) {
        super(spawn, "InvisibleHurtBlockH");
    }

    @Override
    public void onSolidContact(PlayableEntity playerEntity, SolidContact contact, int frameCounter) {
        if (playerEntity == null || !isActiveHurtFace(contact) || playerEntity.getInvulnerable()) {
            return;
        }

        int sourceX = getX();
        rewindPlayerYBeforeHurt(playerEntity);
        if (playerEntity.isCpuControlled()) {
            playerEntity.applyHurt(sourceX);
            return;
        }

        boolean hadRings = playerEntity.getRingCount() > 0;
        if (hadRings && !playerEntity.hasShield()) {
            // HurtCharacter reserves the Obj37 owner; the shared slot scheduler
            // derives whether its initializer clears Ring_count this pass or
            // the next. This object has no separate ring-clear rule: it reaches
            // the same HurtCharacter path as ordinary touch damage.
            // ROM: sonic3k.asm:21065-21077, 35549-35616.
            services().spawnLostRingsAfterCurrentFrame(playerEntity, frameCounter);
        }
        playerEntity.applyHurtOrDeath(sourceX, DamageCause.NORMAL, hadRings);
    }

    private void rewindPlayerYBeforeHurt(PlayableEntity player) {
        short ySpeed = player.getYSpeed();
        if (ySpeed != 0) {
            // ROM sub_1F58C routes to sub_24280, which subtracts y_vel<<8 from
            // y_pos before HurtCharacter (docs/skdisasm/sonic3k.asm:43422-43431,
            // 49200-49220).
            player.move((short) 0, (short) -ySpeed);
        }
    }

    private boolean isActiveHurtFace(SolidContact contact) {
        int renderFlags = spawn.renderFlags() & 0x03;
        if ((renderFlags & 0x01) != 0) {
            return contact.touchSide();
        }
        if ((renderFlags & 0x02) != 0) {
            return contact.touchBottom();
        }
        return contact.standing();
    }
}
