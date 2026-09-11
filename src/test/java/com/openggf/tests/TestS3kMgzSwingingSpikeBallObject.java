package com.openggf.tests;

import com.openggf.game.sonic3k.Sonic3kObjectArtKeys;
import com.openggf.game.sonic3k.Sonic3kPlcArtRegistry;
import com.openggf.game.sonic3k.Sonic3kPlcArtRegistry.LevelArtEntry;
import com.openggf.game.sonic3k.constants.Sonic3kConstants;
import com.openggf.game.sonic3k.constants.Sonic3kObjectIds;
import com.openggf.game.sonic3k.objects.MGZSwingingSpikeBallObjectInstance;
import com.openggf.game.sonic3k.objects.Sonic3kObjectRegistry;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.TouchActorContextPolicy;
import com.openggf.level.objects.TouchAttackBouncePolicy;
import com.openggf.level.objects.TouchCategoryDecodeMode;
import com.openggf.level.objects.TouchOverlapStopPolicy;
import com.openggf.level.objects.TouchResponseProfile;
import com.openggf.level.objects.TouchResponseProvider;
import com.openggf.level.objects.TouchShieldDeflectCapability;
import com.openggf.tools.Sonic3kObjectProfile;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
class TestS3kMgzSwingingSpikeBallObject {

    @Test
    void registryCreatesMgzSwingingSpikeBallInstance() {
        ObjectInstance instance = new Sonic3kObjectRegistry().create(
                new ObjectSpawn(0x1200, 0x0600, Sonic3kObjectIds.MGZ_SWINGING_SPIKE_BALL, 0x00, 0x00, false, 0));
        assertInstanceOf(MGZSwingingSpikeBallObjectInstance.class, instance);
    }

    @Test
    void horizontalSubtypeStartsNinetySixPixelsToTheRight() {
        MGZSwingingSpikeBallObjectInstance instance = new MGZSwingingSpikeBallObjectInstance(
                new ObjectSpawn(0x1200, 0x0600, Sonic3kObjectIds.MGZ_SWINGING_SPIKE_BALL, 0x00, 0x00, false, 0));

        assertEquals(0x1260, instance.getX());
        assertEquals(0x0600, instance.getY());
        assertEquals(0x1200, instance.getOutOfRangeReferenceX(),
                "loc_341F0 uses the stored anchor rather than the swinging ball position");

        TouchResponseProvider.TouchRegion[] regions = instance.getMultiTouchRegions();
        assertNotNull(regions);
        assertEquals(1, regions.length);
        assertEquals(0x1260, regions[0].x());
        assertEquals(0x0600, regions[0].y());
        assertEquals(0x8F, regions[0].collisionFlags());
        assertEquals(1, instance.getReservedChildSlotCount(),
                "Obj_MGZSwingingSpikeBall allocates one loc_34244 visual helper SST");
    }

    @Test
    void verticalSubtypeStartsNinetySixPixelsAboveAnchor() {
        MGZSwingingSpikeBallObjectInstance instance = new MGZSwingingSpikeBallObjectInstance(
                new ObjectSpawn(0x1200, 0x0600, Sonic3kObjectIds.MGZ_SWINGING_SPIKE_BALL, 0x01, 0x00, false, 0));

        assertEquals(0x1200, instance.getX());
        assertEquals(0x05A0, instance.getY());
    }

    @Test
    void touchProfileNamesMultiRegionHurtPolicy() throws NoSuchMethodException {
        MGZSwingingSpikeBallObjectInstance instance = new MGZSwingingSpikeBallObjectInstance(
                new ObjectSpawn(0x1200, 0x0600, Sonic3kObjectIds.MGZ_SWINGING_SPIKE_BALL, 0x00, 0x00, false, 0));

        TouchResponseProfile expectedMultiRegion = new TouchResponseProfile(
                TouchCategoryDecodeMode.NORMAL,
                false,
                true,
                true,
                TouchShieldDeflectCapability.NONE,
                0,
                TouchAttackBouncePolicy.STANDARD_ENEMY_KILL,
                TouchActorContextPolicy.MAIN_FULL_SIDEKICK_HURT_ONLY,
                TouchOverlapStopPolicy.STOP_AFTER_FIRST_OVERLAP_FOR_MAIN_ONLY);
        TouchResponseProfile expectedSingleRegion = new TouchResponseProfile(
                TouchCategoryDecodeMode.NORMAL,
                false,
                true,
                false,
                TouchShieldDeflectCapability.NONE,
                0,
                TouchAttackBouncePolicy.STANDARD_ENEMY_KILL,
                TouchActorContextPolicy.MAIN_FULL_SIDEKICK_HURT_ONLY,
                TouchOverlapStopPolicy.STOP_AFTER_FIRST_OVERLAP_FOR_ALL_ACTORS);

        assertEquals(expectedMultiRegion, instance.getTouchResponseProfile());
        assertEquals(expectedMultiRegion, instance.getTouchResponseProfile(true));
        assertEquals(expectedSingleRegion, instance.getTouchResponseProfile(false));
        assertEquals(0x1260, instance.getMultiTouchRegions()[0].x());
        assertEquals(0x0600, instance.getMultiTouchRegions()[0].y());
        assertEquals(0x8F, instance.getMultiTouchRegions()[0].collisionFlags());
        MGZSwingingSpikeBallObjectInstance.class.getDeclaredMethod("getTouchResponseProfile");
        MGZSwingingSpikeBallObjectInstance.class.getDeclaredMethod("getTouchResponseProfile", boolean.class);
    }

    @Test
    void artRegistryProvidesMgzSwingingSpikeBallSheet() {
        LevelArtEntry entry = findLevelEntry(Sonic3kPlcArtRegistry.getPlan(0x02, 0).levelArt(),
                Sonic3kObjectArtKeys.MGZ_SWINGING_SPIKE_BALL);

        assertEquals(Sonic3kConstants.MAP_MGZ_SWINGING_SPIKE_BALL_ADDR, entry.mappingAddr());
        assertEquals(Sonic3kConstants.ARTTILE_MGZ_MISC1, entry.artTileBase());
        assertEquals(1, entry.palette());
    }

    @Test
    void profileMarksMgzSwingingSpikeBallImplemented() {
        Sonic3kObjectProfile profile = new Sonic3kObjectProfile();
        assertTrue(profile.getImplementedIds().contains(Sonic3kObjectIds.MGZ_SWINGING_SPIKE_BALL));
    }

    private static LevelArtEntry findLevelEntry(List<LevelArtEntry> entries, String key) {
        return entries.stream()
                .filter(entry -> key.equals(entry.key()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing art entry: " + key));
    }
}
