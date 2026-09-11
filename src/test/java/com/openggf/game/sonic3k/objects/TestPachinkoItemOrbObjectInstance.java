package com.openggf.game.sonic3k.objects;

import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.TestObjectServices;
import com.openggf.level.objects.TouchCategoryDecodeMode;
import com.openggf.level.objects.TouchOverlapStopPolicy;
import com.openggf.level.objects.TouchResponseProfile;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestPachinkoItemOrbObjectInstance {

    private static final int[] EXPECTED_REWARD_TABLE = {
            1, 3, 1, 3, 8, 3, 8, 5, 1, 3, 6, 4, 1, 7, 6, 5, 8, 6, 4, 3,
            4, 3, 4, 5, 8, 4, 5, 3, 7, 3, 8, 3, 6, 5, 6, 7, 4, 3, 7, 5,
            6, 4, 6, 4, 7, 3, 3, 5, 4, 3, 4, 6, 3, 4, 3, 7, 4, 3, 4, 3,
            4, 3, 4, 3
    };

    @Test
    void rewardTableMatchesDisassemblyByte1E4484() {
        // ROM loc_4A238 keys the reward index on the HIGH byte of the y_pos word
        // (`move.b y_pos(a0),d1` reads the big-endian MSB), so the nibble that selects the
        // table band is (y_pos >> 8) & $F. Encode it in the high byte accordingly; the low
        // byte must not influence the index.
        for (int yNibble = 0; yNibble < 16; yNibble++) {
            for (int framePhase = 0; framePhase < 4; framePhase++) {
                int index = (yNibble << 2) + framePhase;
                assertEquals(EXPECTED_REWARD_TABLE[index],
                        PachinkoItemOrbObjectInstance.resolveRewardSubtype((yNibble << 8) | 0x00, framePhase),
                        "Mismatch at yNibble=" + yNibble + " framePhase=" + framePhase);
            }
        }
    }

    @Test
    void rewardIndexUsesHighByteOfYPosNotLowNibble() {
        // Regression for the y=$08B0 reward orb (trace slot 31): the ROM reads the HIGH byte
        // ($08) so base = ($08 & $F) << 2 = $20; with Level_frame_counter & 3 = 3 the index is
        // $23 = 35 -> byte_1E4484[35] = 7 (bubble shield). The old low-nibble read (0x8B0 & $F
        // = 0) would have picked base 0 -> subtype 1 or 3 (a ring-granting reward), producing a
        // spurious ring award. Both y values below share low nibble 0 but differ in high byte.
        assertEquals(7, PachinkoItemOrbObjectInstance.resolveRewardSubtype(0x08B0, 3));
        assertEquals(1, PachinkoItemOrbObjectInstance.resolveRewardSubtype(0x0000, 0));
    }

    /**
     * ROM sonic3k.asm:96777-96786 (loc_4A218) arms the orb on touch but does not convert the
     * same pass, and sonic3k.asm:96789-96791 (loc_4A238 -&gt; loc_4A274) re-checks
     * collision_property next pass and stays armed for as long as the touch persists — the orb
     * only converts once a pass resolves with the touch signal clear (contact released).
     */
    @Test
    void touchArmsOrb_conversionWaitsForReleaseBeforeUsingReleaseFrameCounter() {
        PachinkoItemOrbObjectInstance orb =
                new PachinkoItemOrbObjectInstance(new ObjectSpawn(0x140, 0x383, 0xED, 0, 0, false, 0));
        orb.setServices(new TestObjectServices());

        orb.onTouchResponse(null, null, 0);
        orb.update(1, null);

        // Armed, but not yet converted: still-touching still resolves at update(1).
        assertEquals(0xED, orb.getSpawn().objectId());
        assertEquals(0, orb.getSpawn().subtype());

        // Contact persists into the next pass (loc_4A274): stays armed, does not convert.
        orb.onTouchResponse(null, null, 1);
        orb.update(2, null);

        assertEquals(0xED, orb.getSpawn().objectId());
        assertEquals(0, orb.getSpawn().subtype());

        // Touch signal resolves clear (player released contact): converts now, using this
        // release pass's frame counter (3; TestObjectServices has no ObjectManager wired, so
        // resolveRomFrameCounter falls back to the raw update() parameter). The reward index
        // keys on the HIGH byte of y_pos: yNibble=3 ((0x383 >> 8) & 0xF), 3&3=3 ->
        // REWARD_TABLE[(3<<2)+3]=REWARD_TABLE[15]=5.
        orb.update(3, null);

        assertEquals(0xEB, orb.getSpawn().objectId());
        assertEquals(5, orb.getSpawn().subtype());
    }

    /**
     * A single touch that is never repeated still requires one additional touch-clear pass
     * before converting (ROM never converts on the same pass collision_property was set).
     */
    @Test
    void singleTouchArmsButDoesNotConvertOnTheImmediatelyFollowingUpdate() {
        PachinkoItemOrbObjectInstance orb =
                new PachinkoItemOrbObjectInstance(new ObjectSpawn(0x140, 0x383, 0xED, 0, 0, false, 0));
        orb.setServices(new TestObjectServices());

        orb.onTouchResponse(null, null, 0);
        orb.update(1, null);

        assertEquals(0xED, orb.getSpawn().objectId());
        assertEquals(0, orb.getSpawn().subtype());

        // High byte of y_pos: yNibble=3 ((0x383 >> 8) & 0xF), 2&3=2 ->
        // REWARD_TABLE[(3<<2)+2]=REWARD_TABLE[14]=6.
        orb.update(2, null);

        assertEquals(0xEB, orb.getSpawn().objectId());
        assertEquals(6, orb.getSpawn().subtype());
    }

    @Test
    void touchResponseProfilePollsContinuouslyForCollisionPropertyRelease() {
        PachinkoItemOrbObjectInstance orb =
                new PachinkoItemOrbObjectInstance(new ObjectSpawn(0x140, 0x183, 0xED, 0, 0, false, 0));

        TouchResponseProfile profile = orb.getTouchResponseProfile();

        // ROM collision_flags $D7 -> $C0 (Touch_Special / collision_property notify): the orb
        // is re-registered on the collision response list every frame and the player's
        // Touch_Loop sets collision_property for every overlapping pass, so the orb must poll
        // continuously (not once per overlap edge) to observe the RELEASE that drives its
        // armed->convert reward roll. The legacy per-object requiresContinuousTouchCallbacks()
        // hook stays un-overridden -- continuity is declared through the profile field instead.
        assertFalse(orb.requiresContinuousTouchCallbacks());
        assertEquals(TouchCategoryDecodeMode.NORMAL, profile.categoryDecodeMode());
        assertTrue(profile.continuousCallbacks());
        assertTrue(profile.requiresRenderFlagForTouch());
        assertFalse(profile.multiRegionSource());
        assertEquals(TouchOverlapStopPolicy.STOP_AFTER_FIRST_OVERLAP_FOR_ALL_ACTORS,
                profile.stopAfterFirstOverlapPolicy());
    }

    @Test
    void orbDeclaresProfileInsteadOfLegacyContinuousTouchHook() throws NoSuchMethodException {
        assertThrows(NoSuchMethodException.class,
                () -> PachinkoItemOrbObjectInstance.class
                        .getDeclaredMethod("requiresContinuousTouchCallbacks"));
        assertEquals(TouchResponseProfile.class, PachinkoItemOrbObjectInstance.class
                .getDeclaredMethod("getTouchResponseProfile")
                .getReturnType());
    }
}


