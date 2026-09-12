package com.openggf.game.sonic3k.objects;

import com.openggf.game.PlayableEntity;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.SolidContact;
import com.openggf.level.objects.SolidObjectListener;

/**
 * Object 0x6B - InvisibleHurtBlockV (Sonic 3 &amp; Knuckles).
 * <p>
 * Uses the same invisible solid collision as object 0x28, but applies the
 * ROM's direct {@code Kill_Character} path instead of normal hurt handling.
 * Face selection follows the same placement-flip selection as the horizontal
 * hurt block: no flips = standing contact, x-flip = side contact, y-flip =
 * bottom contact.
 * <p>
 * ROM: Obj_InvisibleHurtBlockVertical / sub_1F734 (sonic3k.asm)
 */
public class Sonic3kInvisibleHurtBlockVObjectInstance extends Sonic3kInvisibleBlockObjectInstance implements SolidObjectListener {

    public Sonic3kInvisibleHurtBlockVObjectInstance(ObjectSpawn spawn) {
        super(spawn, "InvisibleHurtBlockV");
    }

    @Override
    public void onSolidContact(PlayableEntity playerEntity, SolidContact contact, int frameCounter) {
        if (playerEntity == null || !isActiveHurtFace(contact)) {
            return;
        }
        playerEntity.applyCrushDeath();
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
