package com.openggf.sonic.tools;

import com.openggf.game.sonic3k.objects.Sonic3kObjectRegistry;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.PlaceholderObjectInstance;
import com.openggf.tests.FullReset;
import com.openggf.tests.SingletonResetExtension;
import com.openggf.tools.Sonic3kObjectProfile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.HashSet;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link Sonic3kObjectProfile}'s implemented-id lists are transcribed from
 * {@link Sonic3kObjectRegistry}; this test derives the same sets by asking the
 * registry, zone by zone, which ids resolve to a concrete owner rather than a
 * {@link PlaceholderObjectInstance}, so a newly registered (or newly gated)
 * object cannot leave the checklist generator stale. Constructing every factory's
 * product touches ambient singletons that outlive {@code @FullReset} in the reused
 * ordinary fork, so this is a {@code *Guard} class: {@code -Pguards} runs it in a
 * fresh JVM and the ordinary suite excludes it.
 */
@FullReset
@ExtendWith(SingletonResetExtension.class)
class TestSonic3kObjectProfileRegistryGuard {

    private static final int FIRST_ZONE = 0;
    private static final int LAST_ZONE = 12; // DDZ, the last zone the profile scans

    @Test
    void profileImplementedIdsMatchRegistryForEveryZone() {
        for (int zoneId = FIRST_ZONE; zoneId <= LAST_ZONE; zoneId++) {
            Set<Integer> expected = new TreeSet<>(registryImplementedIds(zoneId));
            Set<Integer> actual = new TreeSet<>(Sonic3kObjectProfile.implementedIdsForZone(zoneId));
            assertEquals(hex(expected), hex(actual),
                    "implemented ids for zone " + zoneId + " (transcribe from the registry probe)");
        }
    }

    @Test
    void sharedIdsAreExactlyTheIdsImplementedInEveryZone() {
        Set<Integer> shared = null;
        for (int zoneId = FIRST_ZONE; zoneId <= LAST_ZONE; zoneId++) {
            Set<Integer> ids = registryImplementedIds(zoneId);
            if (shared == null) {
                shared = new HashSet<>(ids);
            } else {
                shared.retainAll(ids);
            }
        }
        assertEquals(hex(new TreeSet<>(shared)), hex(new TreeSet<>(Sonic3kObjectProfile.SHARED_IMPLEMENTED_IDS)));
    }

    private static Set<Integer> registryImplementedIds(int zoneId) {
        Sonic3kObjectRegistry registry = new Sonic3kObjectRegistry() {
            @Override
            protected int currentRomZoneId() {
                return zoneId;
            }
        };
        Set<Integer> ids = new HashSet<>();
        for (int id = 0; id <= 0xFF; id++) {
            ObjectSpawn spawn = new ObjectSpawn(0, 0, id, 0, 0, false, 0);
            try {
                ObjectInstance instance = registry.create(spawn);
                if (instance != null && !(instance instanceof PlaceholderObjectInstance)) {
                    ids.add(id);
                }
            } catch (IllegalStateException servicesUnavailable) {
                // A concrete constructor that insists on injected services still names an owner.
                ids.add(id);
            }
        }
        return ids;
    }

    private static String hex(Set<Integer> ids) {
        StringBuilder out = new StringBuilder();
        for (int id : ids) {
            out.append(String.format("%02X ", id));
        }
        return out.toString().trim();
    }
}
