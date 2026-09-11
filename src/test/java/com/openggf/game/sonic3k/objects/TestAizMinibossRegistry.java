package com.openggf.game.sonic3k.objects;

import com.openggf.tests.FullReset;
import com.openggf.tests.SingletonResetExtension;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.Test;
import com.openggf.game.sonic3k.constants.Sonic3kObjectIds;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectSpawn;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Object-registry assertions depend on the S3K zone set, which the registry
 * derives from the globally loaded level: {@code getCurrentZoneSet()} answers
 * S3KL only while no level is loaded, and any earlier test in the same reused
 * fork that left an MHZ-DDZ level behind flips it to SKL, at which point every
 * S3KL-only factory hands back a {@code PlaceholderObjectInstance}. A full
 * reset clears the session, so the zone set these tests assert against is
 * established rather than inherited.
 */
@FullReset
@ExtendWith(SingletonResetExtension.class)
public class TestAizMinibossRegistry {

    @Test
    public void registryCreatesAizMinibossForId0x91InS3klZoneSet() {
        Sonic3kObjectRegistry registry = new Sonic3kObjectRegistry();
        ObjectSpawn spawn = new ObjectSpawn(0x11F0, 0x289, Sonic3kObjectIds.AIZ_MINIBOSS, 0, 0, false, 0);

        ObjectInstance instance = registry.create(spawn);

        assertTrue(instance instanceof AizMinibossInstance);
    }

    @Test
    public void primaryNameFor0x91MatchesDisassemblyLabel() {
        Sonic3kObjectRegistry registry = new Sonic3kObjectRegistry();
        assertEquals("AIZMiniboss", registry.getPrimaryName(Sonic3kObjectIds.AIZ_MINIBOSS));
    }
}



