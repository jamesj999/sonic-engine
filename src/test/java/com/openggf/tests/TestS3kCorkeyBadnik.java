package com.openggf.tests;

import com.openggf.game.sonic3k.constants.Sonic3kObjectIds;
import com.openggf.game.sonic3k.objects.Sonic3kObjectRegistry;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.PlaceholderObjectInstance;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;

class TestS3kCorkeyBadnik {

    @Test
    void registryCreatesDedicatedCorkeyInstance() {
        ObjectInstance instance = new Sonic3kObjectRegistry().create(
                new ObjectSpawn(0x0200, 0x0100, Sonic3kObjectIds.CORKEY, 0, 0, false, 0));

        assertFalse(instance instanceof PlaceholderObjectInstance,
                "S3KL Obj_Corkey should have a dedicated badnik implementation");
    }
}
