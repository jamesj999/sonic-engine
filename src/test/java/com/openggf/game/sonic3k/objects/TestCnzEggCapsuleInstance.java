package com.openggf.game.sonic3k.objects;

import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.SolidContact;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TestCnzEggCapsuleInstance {

    @Test
    void standingTriggerChangesTheButtonFrameWithoutMovingItsSolidAnchor() {
        CnzEggCapsuleInstance capsule = new CnzEggCapsuleInstance(
                new ObjectSpawn(0x4990, 0x02E0, 0x2A, 0, 0, false, 0));
        int buttonY = capsule.getPieceY(1);

        capsule.onPieceContact(1, null,
                new SolidContact(true, false, false, true, false), 0);

        assertEquals(0x02BC, buttonY);
        assertEquals(buttonY, capsule.getPieceY(1),
                "loc_8672A changes mapping_frame but leaves child_dy unchanged");
    }
}
