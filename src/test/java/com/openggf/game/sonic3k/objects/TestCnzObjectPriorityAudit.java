package com.openggf.game.sonic3k.objects;

import com.openggf.game.sonic3k.objects.badniks.BatbotBadnikInstance;
import com.openggf.game.sonic3k.objects.badniks.SparkleBadnikInstance;
import com.openggf.level.objects.ObjectSpawn;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestCnzObjectPriorityAudit {
    @Test
    void visibleCnzObjectsUseRomPriorityWords() {
        assertEquals(5, new CnzBalloonInstance(spawn(0x41)).getPriorityBucket());
        assertEquals(5, new CnzLightBulbInstance(spawn(0x45)).getPriorityBucket());
        assertEquals(5, new CnzCylinderInstance(spawn(0x47)).getPriorityBucket());
    }

    @Test
    void cnzBadnikArtUsesRomHighPlaneFlag() {
        assertTrue(new ClamerObjectInstance(spawn(0xA3)).isHighPriority());
        assertTrue(new SparkleBadnikInstance(spawn(0xA4)).isHighPriority());
        assertTrue(new BatbotBadnikInstance(spawn(0xA5)).isHighPriority());
    }

    private static ObjectSpawn spawn(int objectId) {
        return new ObjectSpawn(0x100, 0x100, objectId, 0, 0, false, 0x100);
    }
}
