package com.openggf.game;

import com.openggf.game.rules.GameRules;

import com.openggf.game.session.EngineContext;
import com.openggf.game.session.EngineServices;
import com.openggf.game.session.SessionManager;
import com.openggf.data.PlayerSpriteArtProvider;
import com.openggf.data.RomManager;
import com.openggf.graphics.RenderContext;
import com.openggf.level.Palette;
import com.openggf.level.Pattern;
import com.openggf.sprites.art.SpriteArtSet;
import com.openggf.sprites.animation.ScriptedVelocityAnimationProfile;
import com.openggf.sprites.animation.SpriteAnimationEndAction;
import com.openggf.sprites.animation.SpriteAnimationScript;
import com.openggf.sprites.animation.SpriteAnimationSet;
import com.openggf.tests.TestEnvironment;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Nested;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.util.List;
import java.util.Map;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;

class TestCrossGameFeatureProviderRefactor {

    @Nested
    @RequiresRom(SonicGame.SONIC_3K)
    class S3kTailsDonationIntegration {

        @Test
        void s3kTailsScriptsSurviveTranslationIntoS1Host() throws Exception {
            assertS3kTailsScriptsSurviveTranslation(new com.openggf.game.sonic1.Sonic1GameModule());
        }

        @Test
        void s3kTailsScriptsSurviveTranslationIntoS2Host() throws Exception {
            assertS3kTailsScriptsSurviveTranslation(new com.openggf.game.sonic2.Sonic2GameModule());
        }

        private void assertS3kTailsScriptsSurviveTranslation(GameModule host) throws Exception {
            GameModuleRegistry.setCurrent(host);
            SessionManager.openGameplaySession(host);
            CrossGameFeatureProvider provider = new CrossGameFeatureProvider(
                    RomManager.getInstance(), EngineServices.current().configuration());
            provider.initialize("s3k");

            SpriteArtSet donated = provider.loadPlayerSpriteArt("tails");

            assertNotNull(donated);
            assertInstanceOf(ScriptedVelocityAnimationProfile.class, donated.animationProfile());
            assertNotNull(donated.animationSet());
            for (int animationId = 0x20; animationId <= 0x28; animationId++) {
                assertNotNull(donated.animationSet().getScript(animationId),
                        "host " + host.getGameId() + " lost donated Tails script 0x"
                                + Integer.toHexString(animationId));
            }
        }
    }

    @BeforeEach
    void setUp() {
        // Clear lingering session/runtime state from prior tests in the same fork
        // so resolveHostGameId() falls back to the GameModuleRegistry bootstrap
        // default that this fixture configures via setCurrent().
        SessionManager.clear();
        EngineServices.configure(EngineContext.fromLegacySingletonsForBootstrap());
    }

    @AfterEach
    void cleanup() {
        SessionManager.clear();
        CrossGameFeatureProvider.getInstance().resetState();
        GameModuleRegistry.reset();
        EngineServices.configure(EngineContext.fromLegacySingletonsForBootstrap());
    }

    @Test
    void sameGameDonationIsDisabled() {
        GameModuleRegistry.setCurrent(new com.openggf.game.sonic2.Sonic2GameModule());
        try {
            CrossGameFeatureProvider.getInstance().initialize("s2");
        } catch (Exception e) {
            // ROM not available, but guard should fire before ROM access
        }
        assertFalse(CrossGameFeatureProvider.isActive(),
                "Same-game donation should be disabled");
    }

    @Test
    void hybridRulesReflectDonorCapabilities() {
        DonorCapabilities s1Caps = new com.openggf.game.sonic1.Sonic1GameModule()
                .getDonorCapabilities();
        assertFalse(s1Caps.hasSpindash());
        assertFalse(s1Caps.hasSuperTransform());
        assertFalse(s1Caps.hasInstaShield());
    }

    @Test
    void hybridRulesPreserveBaseBoundaryAndSidekickFlags() throws Exception {
        TestEnvironment.configureGameModuleFixture(new com.openggf.game.sonic2.Sonic2GameModule());
        CrossGameFeatureProvider provider = new CrossGameFeatureProvider(null, null);
        setField(provider, "donorGameId", GameId.S3K);
        setField(provider, "donorCapabilities", new StubDonorCapabilities());

        GameRules hybrid = invokeBuildHybridRules(provider);
        GameRules base = GameRules.SONIC_2;

        assertHybridPreservesBaseExceptDonatedCapabilities(base, hybrid);
        assertEquals(base.collision().sidekickPushBypassUsesGraceStatus(), hybrid.collision().sidekickPushBypassUsesGraceStatus());
        assertEquals(base.collision().sidekickClearsStalePushVelocityBeforeGroundMove(),
                hybrid.collision().sidekickClearsStalePushVelocityBeforeGroundMove());
        assertEquals(base.sidekickCpu().sidekickCpuUsesLevelFrameCounter(), hybrid.sidekickCpu().sidekickCpuUsesLevelFrameCounter());
        assertEquals(base.playerMovement().landing().landingRollClearUsesCurrentYRadiusDelta(),
                hybrid.playerMovement().landing().landingRollClearUsesCurrentYRadiusDelta());
        assertEquals(base.playerMovement().levelBoundaryRightStrict(), hybrid.playerMovement().levelBoundaryRightStrict());
        assertEquals(base.playerMovement().levelBoundaryUsesCentreY(), hybrid.playerMovement().levelBoundaryUsesCentreY());
        assertEquals(base.collision().solidObjectTopBranchAlwaysLiftsOnUpwardVelocity(),
                hybrid.collision().solidObjectTopBranchAlwaysLiftsOnUpwardVelocity());
        assertEquals(base.objectInteraction().sidekickNormalCpuSkipsHurtRoutine(), hybrid.objectInteraction().sidekickNormalCpuSkipsHurtRoutine());
    }

    @Test
    void loadPlayerSpriteArt_refreshesDonorPaletteForRequestedCharacter() throws Exception {
        Palette sonicPalette = paletteWithBlueMarker();
        Palette knucklesPalette = paletteWithRedMarker();
        CrossGameFeatureProvider provider = spy(new CrossGameFeatureProvider(null, null));
        doReturn(sonicPalette).when(provider).loadCharacterPalette(null);
        doReturn(sonicPalette).when(provider).loadCharacterPalette("sonic");
        doReturn(knucklesPalette).when(provider).loadCharacterPalette("knuckles");
        RenderContext context = RenderContext.getOrCreateDonor(GameId.S3K);
        context.setPalette(0, sonicPalette);
        setField(provider, "donorRenderContext", context);
        setField(provider, "donorGameId", GameId.S3K);
        setField(provider, "donorCapabilities", new StubDonorCapabilities());
        setField(provider, "donorPlayerArtProvider", StubDonorCapabilities.PROVIDER);
        setField(provider, "donorReader", new com.openggf.data.RomByteReader(new byte[0]));

        provider.loadPlayerSpriteArt("knuckles");

        assertSame(knucklesPalette, context.getPalette(0));
    }

    @Test
    void tailsFlightDonorRejectsMissingRequiredAnimationContract() throws Exception {
        CrossGameFeatureProvider provider = configuredArtProvider(
                new StubDonorCapabilities(true, CanonicalAnimation.TAILS_SWIM_CARRY));

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> provider.loadPlayerSpriteArt("tails"));

        assertTrue(error.getMessage().contains("TAILS_SWIM_CARRY"));
    }

    @Test
    void tailsFlightDonorAcceptsCompleteRequiredAnimationContract() throws Exception {
        CrossGameFeatureProvider provider = configuredArtProvider(
                new StubDonorCapabilities(true, null));

        assertNotNull(provider.loadPlayerSpriteArt("tails"));
    }

    @Test
    void tailsFlightDonorRejectsMissingAnimationSetAfterArtLoad() throws Exception {
        CrossGameFeatureProvider provider = configuredArtProvider(
                new StubDonorCapabilities(true, null),
                characterCode -> new SpriteArtSet(
                        new Pattern[0], List.of(), List.of(), 0, 0, 0, 1, null, null));

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> provider.loadPlayerSpriteArt("tails"));

        assertTailsContractDiagnostic(error, CanonicalAnimation.TAILS_FLY, 0x20);
    }

    @Test
    void tailsFlightDonorRejectsMissingRequiredNativeScriptAfterArtLoad() throws Exception {
        CrossGameFeatureProvider provider = configuredArtProvider(
                new StubDonorCapabilities(true, null),
                characterCode -> tailsArtMissingScript(0x27));

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> provider.loadPlayerSpriteArt("tails"));

        assertTailsContractDiagnostic(error, CanonicalAnimation.TAILS_SWIM_CARRY, 0x27);
    }

    @Test
    void tailsFlightDonorPreservesNullArtSemantics() throws Exception {
        CrossGameFeatureProvider provider = configuredArtProvider(
                new StubDonorCapabilities(true, null), characterCode -> null);

        assertNull(provider.loadPlayerSpriteArt("tails"));
    }

    private static void assertTailsContractDiagnostic(
            IllegalStateException error, CanonicalAnimation canonical, int nativeId) {
        assertAll(
                () -> assertTrue(error.getMessage().contains("s3k")),
                () -> assertTrue(error.getMessage().contains("tails")),
                () -> assertTrue(error.getMessage().contains(canonical.name())),
                () -> assertTrue(error.getMessage().contains("0x" + Integer.toHexString(nativeId))));
    }

    private static SpriteArtSet tailsArtMissingScript(int missingId) {
        SpriteAnimationSet animations = new SpriteAnimationSet();
        for (int id = 0x20; id <= 0x28; id++) {
            if (id != missingId) {
                animations.addScript(id, new SpriteAnimationScript(
                        1, List.of(0), SpriteAnimationEndAction.LOOP, 0));
            }
        }
        return new SpriteArtSet(
                new Pattern[0], List.of(), List.of(), 0, 0, 0, 1, null, animations);
    }

    private static CrossGameFeatureProvider configuredArtProvider(DonorCapabilities capabilities) throws Exception {
        return configuredArtProvider(capabilities, StubDonorCapabilities.PROVIDER);
    }

    private static CrossGameFeatureProvider configuredArtProvider(
            DonorCapabilities capabilities, PlayerSpriteArtProvider artProvider) throws Exception {
        CrossGameFeatureProvider provider = spy(new CrossGameFeatureProvider(null, null));
        doReturn(null).when(provider).loadCharacterPalette("tails");
        setField(provider, "donorGameId", GameId.S3K);
        setField(provider, "donorCapabilities", capabilities);
        setField(provider, "donorPlayerArtProvider", artProvider);
        return provider;
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = CrossGameFeatureProvider.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static GameRules invokeBuildHybridRules(CrossGameFeatureProvider provider) throws Exception {
        Method method = CrossGameFeatureProvider.class.getDeclaredMethod("buildHybridRules", GameRules.class);
        method.setAccessible(true);
        return (GameRules) method.invoke(provider, GameRules.SONIC_3K);
    }

    private static void assertHybridPreservesBaseExceptDonatedCapabilities(
            GameRules base, GameRules hybrid) throws Exception {
        Set<String> donorFields = Set.of(
                "spindashEnabled",
                "spindashSpeedTable",
                "elementalShieldsEnabled",
                "instaShieldEnabled",
                "lightningShieldEnabled");
        for (RecordComponent component : GameRules.class.getRecordComponents()) {
            if ("playerCapability".equals(component.getName())) {
                continue;
            }
            Method accessor = component.getAccessor();
            assertEquals(accessor.invoke(base), accessor.invoke(hybrid),
                    "Hybrid rules must preserve base component " + component.getName());
        }
        for (RecordComponent component : base.playerCapability().getClass().getRecordComponents()) {
            if (donorFields.contains(component.getName())) {
                continue;
            }
            Method accessor = component.getAccessor();
            Object expected = accessor.invoke(base.playerCapability());
            Object actual = accessor.invoke(hybrid.playerCapability());
            String message = "Hybrid rules must preserve host player capability component " + component.getName();
            if (expected instanceof short[] expectedArray && actual instanceof short[] actualArray) {
                assertArrayEquals(expectedArray, actualArray, message);
            } else {
                assertEquals(expected, actual, message);
            }
        }
    }

    private static final class StubDonorCapabilities implements DonorCapabilities {
        private static final PlayerSpriteArtProvider PROVIDER = characterCode -> tailsArtMissingScript(-1);
        private final boolean tailsFlight;
        private final CanonicalAnimation missingAnimation;

        private StubDonorCapabilities() {
            this(false, null);
        }

        private StubDonorCapabilities(boolean tailsFlight, CanonicalAnimation missingAnimation) {
            this.tailsFlight = tailsFlight;
            this.missingAnimation = missingAnimation;
        }

        @Override public java.util.Set<PlayerCharacter> getPlayableCharacters() { return java.util.Set.of(PlayerCharacter.SONIC_ALONE, PlayerCharacter.KNUCKLES); }
        @Override public boolean hasSpindash() { return false; }
        @Override public boolean hasSuperTransform() { return false; }
        @Override public boolean hasHyperTransform() { return false; }
        @Override public boolean hasInstaShield() { return false; }
        @Override public boolean hasTailsFlight() { return tailsFlight; }
        @Override public boolean hasElementalShields() { return false; }
        @Override public boolean hasSidekick() { return false; }
        @Override public Map<CanonicalAnimation, CanonicalAnimation> getAnimationFallbacks() { return Map.of(); }
        @Override public int resolveNativeId(CanonicalAnimation canonical) {
            if (canonical == missingAnimation) {
                return -1;
            }
            return switch (canonical) {
                case TAILS_FLY -> 0x20;
                case TAILS_FLY_ASCEND -> 0x21;
                case TAILS_FLY_CARRY -> 0x22;
                case TAILS_FLY_CARRY_ASCEND -> 0x23;
                case TAILS_FLY_TIRED -> 0x24;
                case TAILS_SWIM -> 0x25;
                case TAILS_SWIM_ASCEND -> 0x26;
                case TAILS_SWIM_CARRY -> 0x27;
                case TAILS_SWIM_TIRED -> 0x28;
                default -> -1;
            };
        }
        @Override public PlayerSpriteArtProvider getPlayerArtProvider(com.openggf.data.RomByteReader reader) { return PROVIDER; }
    }

    private static Palette paletteWithBlueMarker() {
        Palette palette = new Palette();
        palette.setColor(1, new Palette.Color((byte) 0x22, (byte) 0x44, (byte) 0xEE));
        return palette;
    }

    private static Palette paletteWithRedMarker() {
        Palette palette = new Palette();
        palette.setColor(1, new Palette.Color((byte) 0xEE, (byte) 0x22, (byte) 0x22));
        return palette;
    }
}
