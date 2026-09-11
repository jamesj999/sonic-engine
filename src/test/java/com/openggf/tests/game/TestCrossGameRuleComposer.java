package com.openggf.tests.game;

import com.openggf.data.PlayerSpriteArtProvider;
import com.openggf.data.RomByteReader;
import com.openggf.game.CanonicalAnimation;
import com.openggf.game.DonorCapabilities;
import com.openggf.game.rules.GameRules;
import com.openggf.game.PlayerCharacter;
import com.openggf.game.rules.CrossGameRuleComposer;
import com.openggf.game.rules.GameRules;
import com.openggf.game.rules.PlayerCapabilityRules;
import com.openggf.game.sonic2.Sonic2GameModule;
import com.openggf.game.sonic3k.Sonic3kGameModule;
import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestCrossGameRuleComposer {

    private static final Set<String> DONOR_CAPABILITY_COMPONENTS = Set.of(
            "spindashEnabled",
            "spindashSpeedTable",
            "elementalShieldsEnabled",
            "instaShieldEnabled",
            "tailsFlightEnabled",
            "lightningShieldEnabled");

    @Test
    void donorCapabilitiesAffectOnlyDonorOwnedPlayerCapabilities() throws Exception {
        GameRules host = GameRules.SONIC_1;
        GameRules donor = GameRules.SONIC_3K;
        DonorCapabilities capabilities = new StubDonorCapabilities(true, true, true, true);

        GameRules hybrid = CrossGameRuleComposer.compose(host, donor, capabilities);

        assertNotSame(host.playerCapability(), hybrid.playerCapability());
        assertTrue(hybrid.playerCapability().spindashEnabled());
        assertArrayEquals(donor.playerCapability().spindashSpeedTable(),
                hybrid.playerCapability().spindashSpeedTable());
        assertTrue(hybrid.playerCapability().elementalShieldsEnabled());
        assertTrue(hybrid.playerCapability().instaShieldEnabled());
        assertTrue(hybrid.playerCapability().tailsFlightEnabled());
        assertTrue(hybrid.playerCapability().lightningShieldEnabled());
        assertHostOwnedTopLevelRulesAreKept(host, hybrid);
        assertHostOwnedPlayerCapabilityComponentsAreKept(host.playerCapability(), hybrid.playerCapability());
    }

    @Test
    void spindashTableIsNullWhenDonorDoesNotDonateSpindash() {
        GameRules host = GameRules.SONIC_2;
        GameRules donor = GameRules.SONIC_3K;
        DonorCapabilities capabilities = new StubDonorCapabilities(false, true, false, false);

        GameRules hybrid = CrossGameRuleComposer.compose(host, donor, capabilities);

        assertEquals(false, hybrid.playerCapability().spindashEnabled());
        assertEquals(null, hybrid.playerCapability().spindashSpeedTable());
        assertTrue(hybrid.playerCapability().elementalShieldsEnabled());
        assertEquals(false, hybrid.playerCapability().instaShieldEnabled());
        assertEquals(false, hybrid.playerCapability().tailsFlightEnabled());
        assertTrue(hybrid.playerCapability().lightningShieldEnabled());
    }

    @Test
    void sonic2DonorDisablesTailsFlightInSonic3kHost() {
        GameRules hybrid = CrossGameRuleComposer.compose(
                GameRules.SONIC_3K,
                GameRules.SONIC_2,
                new Sonic2GameModule().getDonorCapabilities());

        assertEquals(false, hybrid.playerCapability().tailsFlightEnabled());
    }

    @Test
    void sonic3kDonorEnablesTailsFlightInSonic2Host() {
        GameRules hybrid = CrossGameRuleComposer.compose(
                GameRules.SONIC_2,
                GameRules.SONIC_3K,
                new Sonic3kGameModule().getDonorCapabilities());

        assertTrue(hybrid.playerCapability().tailsFlightEnabled());
    }

    @Test
    void nullDonorCapabilitiesReturnHostRulesInstance() {
        GameRules host = GameRules.SONIC_2;
        GameRules donor = GameRules.SONIC_3K;

        assertSame(host, CrossGameRuleComposer.compose(host, donor, null));
    }

    @Test
    void nullRulesAreRejected() {
        GameRules rules = GameRules.SONIC_2;
        DonorCapabilities capabilities = new StubDonorCapabilities(true, true, true, true);

        IllegalArgumentException nullHost = assertThrows(IllegalArgumentException.class,
                () -> CrossGameRuleComposer.compose(null, rules, capabilities));
        IllegalArgumentException nullDonor = assertThrows(IllegalArgumentException.class,
                () -> CrossGameRuleComposer.compose(rules, null, capabilities));

        assertEquals("Host GameRules are required", nullHost.getMessage());
        assertEquals("Donor GameRules are required", nullDonor.getMessage());
    }

    private static void assertHostOwnedTopLevelRulesAreKept(GameRules host, GameRules hybrid) {
        assertSame(host.playerMovement(), hybrid.playerMovement());
        assertSame(host.collision(), hybrid.collision());
        assertSame(host.playerAnimation(), hybrid.playerAnimation());
        assertSame(host.camera(), hybrid.camera());
        assertSame(host.ring(), hybrid.ring());
        assertSame(host.objectInteraction(), hybrid.objectInteraction());
        assertSame(host.sidekickCpu(), hybrid.sidekickCpu());
        assertSame(host.powerUp(), hybrid.powerUp());
        assertSame(host.drowningBubble(), hybrid.drowningBubble());
    }

    private static void assertHostOwnedPlayerCapabilityComponentsAreKept(
            PlayerCapabilityRules host, PlayerCapabilityRules hybrid) throws Exception {
        for (RecordComponent component : PlayerCapabilityRules.class.getRecordComponents()) {
            if (DONOR_CAPABILITY_COMPONENTS.contains(component.getName())) {
                continue;
            }
            Object expected = component.getAccessor().invoke(host);
            Object actual = component.getAccessor().invoke(hybrid);
            if (expected instanceof short[] expectedTable) {
                assertArrayEquals(expectedTable, (short[]) actual, component.getName());
            } else {
                assertEquals(expected, actual, component.getName());
            }
        }
        assertArrayEquals(host.superSpindashSpeedTable(), hybrid.superSpindashSpeedTable(),
                "superSpindashSpeedTable must remain host-owned");
    }

    private record StubDonorCapabilities(
            boolean hasSpindash,
            boolean hasElementalShields,
            boolean hasInstaShield,
            boolean hasTailsFlight) implements DonorCapabilities {

        @Override
        public Set<PlayerCharacter> getPlayableCharacters() {
            return Set.of(PlayerCharacter.SONIC_ALONE);
        }

        @Override
        public boolean hasSuperTransform() {
            return false;
        }

        @Override
        public boolean hasHyperTransform() {
            return false;
        }

        @Override
        public boolean hasSidekick() {
            return false;
        }

        @Override
        public Map<CanonicalAnimation, CanonicalAnimation> getAnimationFallbacks() {
            return Map.of();
        }

        @Override
        public int resolveNativeId(CanonicalAnimation canonical) {
            return -1;
        }

        @Override
        public PlayerSpriteArtProvider getPlayerArtProvider(RomByteReader reader) {
            return null;
        }
    }
}
