package com.openggf.game.sonic3k.objects;

import com.openggf.game.sonic3k.constants.Sonic3kObjectIds;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectSpawn;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class TestHczLargeFanRegistry {

    @BeforeEach
    public void clearLeakedGameplaySession() {
        // The registry derives its S3K zone-set from the active level when one
        // exists. Reused Surefire forks may inherit an SKL-zone session from a
        // prior test, which would make this no-context S3KL registry assertion
        // resolve ID $39 to a placeholder.
        com.openggf.game.session.SessionManager.clear();
    }

    @Test
    public void registryCreatesHczLargeFanForId0x39InS3klZoneSet() {
        Sonic3kObjectRegistry registry = new Sonic3kObjectRegistry();
        ObjectSpawn spawn = new ObjectSpawn(0x0B80, 0x0580, Sonic3kObjectIds.HCZ_LARGE_FAN, 0, 0, false, 0);

        ObjectInstance instance = registry.create(spawn);

        assertTrue(instance instanceof HCZLargeFanObjectInstance);
    }

    @Test
    public void primaryNameFor0x39MatchesDisassemblyLabel() {
        Sonic3kObjectRegistry registry = new Sonic3kObjectRegistry();
        assertEquals("HCZLargeFan", registry.getPrimaryName(Sonic3kObjectIds.HCZ_LARGE_FAN));
    }
}


