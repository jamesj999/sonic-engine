package com.openggf.game;

import com.openggf.game.sonic1.Sonic1GameModule;
import com.openggf.game.sonic2.Sonic2GameModule;
import com.openggf.game.sonic3k.Sonic3kGameModule;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies that the {@link DonorCapabilities} interface compiles and exposes
 * all required methods for cross-game sprite donation.
 */
public class TestDonorCapabilities {

    @Test
    void donorCapabilitiesInterfaceHasRequiredMethods() {
        DonorCapabilities caps = new DonorCapabilities() {
            public java.util.Set<PlayerCharacter> getPlayableCharacters() {
                return java.util.Set.of(PlayerCharacter.SONIC_ALONE);
            }
            public boolean hasSpindash() { return false; }
            public boolean hasSuperTransform() { return false; }
            public boolean hasHyperTransform() { return false; }
            public boolean hasInstaShield() { return false; }
            public boolean hasTailsFlight() { return false; }
            public boolean hasElementalShields() { return false; }
            public boolean hasSidekick() { return false; }
            public java.util.Map<CanonicalAnimation, CanonicalAnimation> getAnimationFallbacks() {
                return java.util.Map.of();
            }
            public int resolveNativeId(CanonicalAnimation canonical) { return -1; }
            public com.openggf.data.PlayerSpriteArtProvider getPlayerArtProvider(
                    com.openggf.data.RomByteReader reader) {
                return null;
            }
        };

        assertEquals(1, caps.getPlayableCharacters().size());
        assertFalse(caps.hasSpindash());
        assertFalse(caps.hasSuperTransform());
        assertFalse(caps.hasHyperTransform());
        assertFalse(caps.hasInstaShield());
        assertFalse(caps.hasTailsFlight());
        assertFalse(caps.hasElementalShields());
        assertFalse(caps.hasSidekick());
        assertTrue(caps.getAnimationFallbacks().isEmpty());
        assertEquals(-1, caps.resolveNativeId(CanonicalAnimation.WALK));
        assertNull(caps.getPlayerArtProvider(null));
    }

    @Test
    void s1DonorCapabilitiesMatchSpec() {
        DonorCapabilities caps = new Sonic1GameModule().getDonorCapabilities();
        assertNotNull(caps, "S1 should support being a donor");
        assertTrue(caps.getPlayableCharacters().contains(PlayerCharacter.SONIC_ALONE));
        assertFalse(caps.getPlayableCharacters().contains(PlayerCharacter.TAILS_ALONE));
        assertFalse(caps.getPlayableCharacters().contains(PlayerCharacter.KNUCKLES));
        assertFalse(caps.hasSpindash());
        assertFalse(caps.hasSuperTransform());
        assertFalse(caps.hasHyperTransform());
        assertFalse(caps.hasInstaShield());
        assertFalse(caps.hasTailsFlight());
        assertFalse(caps.hasElementalShields());
        assertFalse(caps.hasSidekick());
        var fallbacks = caps.getAnimationFallbacks();
        assertEquals(CanonicalAnimation.DUCK, fallbacks.get(CanonicalAnimation.SPINDASH));
        assertEquals(CanonicalAnimation.STOP, fallbacks.get(CanonicalAnimation.SKID));
        assertEquals(CanonicalAnimation.WAIT, fallbacks.get(CanonicalAnimation.BLINK));
        assertEquals(CanonicalAnimation.WAIT, fallbacks.get(CanonicalAnimation.VICTORY));
        assertEquals(CanonicalAnimation.SPRING, fallbacks.get(CanonicalAnimation.GLIDE_DROP));
        // Identity mappings
        assertEquals(CanonicalAnimation.WALK, fallbacks.get(CanonicalAnimation.WALK));
        assertEquals(CanonicalAnimation.WAIT, fallbacks.get(CanonicalAnimation.WAIT));
        assertEquals(CanonicalAnimation.STOP, fallbacks.get(CanonicalAnimation.STOP));
    }

    @Test
    void s2DonorCapabilitiesMatchSpec() {
        DonorCapabilities caps = new Sonic2GameModule().getDonorCapabilities();
        assertNotNull(caps);
        assertTrue(caps.getPlayableCharacters().contains(PlayerCharacter.SONIC_ALONE));
        assertTrue(caps.getPlayableCharacters().contains(PlayerCharacter.TAILS_ALONE));
        assertTrue(caps.getPlayableCharacters().contains(PlayerCharacter.SONIC_AND_TAILS));
        assertFalse(caps.getPlayableCharacters().contains(PlayerCharacter.KNUCKLES));
        assertTrue(caps.hasSpindash());
        assertTrue(caps.hasSuperTransform());
        assertFalse(caps.hasHyperTransform());
        assertFalse(caps.hasInstaShield());
        assertFalse(caps.hasTailsFlight());
        assertFalse(caps.hasElementalShields());
        assertTrue(caps.hasSidekick());
        var fallbacks = caps.getAnimationFallbacks();
        assertEquals(CanonicalAnimation.ROLL, fallbacks.get(CanonicalAnimation.WARP1));
        assertEquals(CanonicalAnimation.BLINK, fallbacks.get(CanonicalAnimation.BLINK));
        assertEquals(CanonicalAnimation.GET_UP, fallbacks.get(CanonicalAnimation.GET_UP));
        assertEquals(CanonicalAnimation.BUBBLE, fallbacks.get(CanonicalAnimation.GET_AIR));
    }

    @Test
    void s3kDonorCapabilitiesMatchSpec() {
        DonorCapabilities caps = new Sonic3kGameModule().getDonorCapabilities();
        assertNotNull(caps);
        assertTrue(caps.getPlayableCharacters().contains(PlayerCharacter.SONIC_ALONE));
        assertTrue(caps.getPlayableCharacters().contains(PlayerCharacter.TAILS_ALONE));
        assertTrue(caps.getPlayableCharacters().contains(PlayerCharacter.KNUCKLES));
        assertTrue(caps.hasSpindash());
        assertTrue(caps.hasSuperTransform());
        assertTrue(caps.hasHyperTransform());
        assertTrue(caps.hasInstaShield());
        assertTrue(caps.hasTailsFlight());
        assertTrue(caps.hasElementalShields());
        assertTrue(caps.hasSidekick());
        var fallbacks = caps.getAnimationFallbacks();
        assertEquals(CanonicalAnimation.ROLL, fallbacks.get(CanonicalAnimation.WARP1));
        assertEquals(CanonicalAnimation.SKID, fallbacks.get(CanonicalAnimation.STOP));
        assertEquals(CanonicalAnimation.HURT_FALL, fallbacks.get(CanonicalAnimation.SLIDE));
    }

    @Test
    void resolveNativeIdWorks() {
        DonorCapabilities s1Caps = new Sonic1GameModule().getDonorCapabilities();
        assertEquals(0x00, s1Caps.resolveNativeId(CanonicalAnimation.WALK));  // S1 WALK = 0x00
        assertEquals(0x05, s1Caps.resolveNativeId(CanonicalAnimation.WAIT));  // S1 WAIT = 0x05
        assertEquals(-1, s1Caps.resolveNativeId(CanonicalAnimation.SPINDASH)); // Not in S1

        DonorCapabilities s2Caps = new Sonic2GameModule().getDonorCapabilities();
        assertEquals(0x09, s2Caps.resolveNativeId(CanonicalAnimation.SPINDASH)); // S2 SPINDASH = 0x09
        assertEquals(-1, s2Caps.resolveNativeId(CanonicalAnimation.WARP1)); // Not in S2
    }
}
