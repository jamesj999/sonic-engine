package com.openggf.game.sonic2.objects;

import com.openggf.game.sonic2.constants.Sonic2ObjectIds;
import com.openggf.level.objects.ObjectConstructionContext;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.SolidObjectProvider;
import com.openggf.level.objects.SolidRoutineProfile;
import com.openggf.level.objects.StubObjectServices;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestTopSolidRoutineProfileAdoption {

    @Test
    void cpzPlatformDeclaresTopSolidRoutineProfile() throws Exception {
        CPZPlatformObjectInstance platform = new CPZPlatformObjectInstance(
                new ObjectSpawn(0x1000, 0x0300, Sonic2ObjectIds.GENERIC_PLATFORM_B, 0x00, 0, false, 0),
                "CPZPlatform");

        assertDeclaredTopSolidProfile(CPZPlatformObjectInstance.class, platform);
    }

    @Test
    void sidewaysPformDeclaresTopSolidRoutineProfile() throws Exception {
        SidewaysPformObjectInstance platform = new SidewaysPformObjectInstance(
                new ObjectSpawn(0x1000, 0x0300, Sonic2ObjectIds.SIDEWAYS_PFORM, 0x00, 0, false, 0),
                "SidewaysPform");

        assertDeclaredTopSolidProfile(SidewaysPformObjectInstance.class, platform);
    }

    @Test
    void swingingPformDeclaresTopSolidRoutineProfile() throws Exception {
        SwingingPformObjectInstance platform = new SwingingPformObjectInstance(
                new ObjectSpawn(0x1000, 0x0300, Sonic2ObjectIds.SWINGING_PFORM, 0x00, 0, false, 0),
                "SwingingPform");

        // Obj82 is NOT top-solid: Obj82_Main calls JmpTo23_SolidObject
        // (docs/s2disasm/s2.asm:57221), whose jmpTos thunk resolves to
        // SolidObject (docs/s2disasm/s2.asm:35014) -- the full four-sided
        // routine, whose SolidObject_ChkBounds axis choice
        // (docs/s2disasm/s2.asm:35376-35408) can resolve a contact through
        // SolidObject_LeftRight instead of landing the player. The top-only
        // entries the other three objects here use have no such classification.
        assertFalse(platform.isTopSolidOnly());
        assertDeclaredProfile(SwingingPformObjectInstance.class, platform,
                SolidRoutineProfile.fromProvider(platform));
    }

    @Test
    void swingingPlatformDeclaresTopSolidRoutineProfile() throws Exception {
        SwingingPlatformObjectInstance platform = ObjectConstructionContext.construct(
                new StubObjectServices(),
                () -> new SwingingPlatformObjectInstance(
                        new ObjectSpawn(0x1000, 0x0300, Sonic2ObjectIds.SWINGING_PLATFORM, 0x00, 0, false, 0),
                        "SwingingPlatform"));

        assertDeclaredTopSolidProfile(SwingingPlatformObjectInstance.class, platform);
    }

    private static void assertDeclaredTopSolidProfile(
            Class<?> owner,
            SolidObjectProvider provider) throws Exception {
        assertTrue(provider.isTopSolidOnly());
        assertDeclaredProfile(owner, provider,
                SolidRoutineProfile.topSolid(provider.usesStickyContactBuffer()));
    }

    private static void assertDeclaredProfile(
            Class<?> owner,
            SolidObjectProvider provider,
            SolidRoutineProfile expected) throws Exception {
        Method method = owner.getDeclaredMethod("getSolidRoutineProfile");

        assertEquals(SolidRoutineProfile.class, method.getReturnType());
        assertEquals(expected, provider.getSolidRoutineProfile());
    }
}
