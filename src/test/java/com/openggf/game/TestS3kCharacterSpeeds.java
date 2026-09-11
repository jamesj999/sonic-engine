package com.openggf.game;

import com.openggf.tests.TestEnvironment;
import com.openggf.game.sonic3k.Sonic3kGameModule;
import com.openggf.game.sonic3k.Sonic3kPhysicsProvider;
import com.openggf.game.session.SessionManager;
import com.openggf.tests.TestablePlayableSprite;
import com.openggf.tests.TestableTailsSprite;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests S3K physics constants for normal single-player mode.
 * <p>The {@code Character_Speeds} table (sonic3k.asm:202288) is only used in
 * Competition mode ({@code Sonic2P_Index}, line 21457). Normal single-player
 * uses the same canonical $600/$C/$80 values as S2.
 * <p>The competition-mode profiles ({@code SONIC_3K_SONIC_INIT}, {@code SONIC_3K_TAILS_INIT})
 * are retained in {@code PhysicsProfile} for future Competition mode support.
 */
class TestS3kCharacterSpeeds {

    @BeforeEach
    void setUp() {
        GameModuleRegistry.setCurrent(new Sonic3kGameModule());
        SessionManager.clear();
        TestEnvironment.activeGameplayMode();
    }

    @AfterEach
    void tearDown() {
        SessionManager.clear();
        GameModuleRegistry.reset();
    }

    // --- Competition Mode Profile Constants (for reference) ---

    @Test
    void sonicCompetitionProfile_matchesCharacterSpeedsTable() {
        // sonic3k.asm:202289 — dc.w $600, $10, $20, 0
        PhysicsProfile p = PhysicsProfile.SONIC_3K_SONIC_INIT;
        assertEquals(0x600, p.max(), "Sonic competition max");
        assertEquals(0x10, p.runAccel(), "Sonic competition accel");
        assertEquals(0x20, p.runDecel(), "Sonic competition decel");
    }

    @Test
    void tailsCompetitionProfile_matchesCharacterSpeedsTable() {
        // sonic3k.asm:202290 — dc.w $4C0, $1C, $70, 0
        PhysicsProfile p = PhysicsProfile.SONIC_3K_TAILS_INIT;
        assertEquals(0x4C0, p.max(), "Tails competition max");
        assertEquals(0x1C, p.runAccel(), "Tails competition accel");
        assertEquals(0x70, p.runDecel(), "Tails competition decel");
    }

    // --- S3K Normal Mode Uses Canonical Profiles (same as S2) ---

    @Test
    void s3kProvider_getProfile_returnsCanonical() {
        Sonic3kPhysicsProvider provider = new Sonic3kPhysicsProvider();
        assertSame(PhysicsProfile.SONIC_2_SONIC, provider.getProfile("sonic"));
        assertSame(PhysicsProfile.SONIC_2_TAILS, provider.getProfile("tails"));
    }

    @Test
    void s3kProvider_getInitProfile_returnsNull() {
        // Normal single-player has no init override — Character_Speeds is competition-only
        Sonic3kPhysicsProvider provider = new Sonic3kPhysicsProvider();
        assertNull(provider.getInitProfile("sonic"));
        assertNull(provider.getInitProfile("tails"));
    }

    @Test
    void s1Provider_getInitProfile_returnsNull() {
        PhysicsProvider s1 = new com.openggf.game.sonic1.Sonic1PhysicsProvider();
        assertNull(s1.getInitProfile("sonic"));
    }

    @Test
    void s2Provider_getInitProfile_returnsNull() {
        PhysicsProvider s2 = new com.openggf.game.sonic2.Sonic2PhysicsProvider();
        assertNull(s2.getInitProfile("sonic"));
    }

    // --- S3K Sonic Uses Canonical Values at Init ---

    @Test
    void sonic_s3k_usesCanonicalAtInit() {
        TestablePlayableSprite sprite = new TestablePlayableSprite("test", (short) 100, (short) 100);
        assertEquals(0x0C, sprite.getRunAccel(), "S3K Sonic: canonical accel $C");
        assertEquals(0x80, sprite.getRunDecel(), "S3K Sonic: canonical decel $80");
        assertEquals(0x600, sprite.getMax(), "S3K Sonic: canonical max $600");
    }

    @Test
    void tails_s3k_usesCanonicalAtInit() {
        TestableTailsSprite sprite = new TestableTailsSprite("test", (short) 100, (short) 100);
        assertEquals(0x0C, sprite.getRunAccel(), "S3K Tails: canonical accel $C");
        assertEquals(0x80, sprite.getRunDecel(), "S3K Tails: canonical decel $80");
        assertEquals(0x600, sprite.getMax(), "S3K Tails: canonical max $600");
    }

    // --- Water Modifier Produces Correct Values ---

    @Test
    void sonic_s3k_waterEntry_producesCorrectValues() {
        TestablePlayableSprite sprite = new TestablePlayableSprite("test", (short) 100, (short) 100);
        sprite.updateWaterState(50); // Water at Y=50, sprite at Y=100 → underwater
        // Canonical accel = $C, water = 0.5x = $6
        assertEquals(0x06, sprite.getRunAccel(), "In water: accel $6");
        assertEquals(0x40, sprite.getRunDecel(), "In water: decel $40");
        assertEquals(0x300, sprite.getMax(), "In water: max $300");
    }

    @Test
    void sonic_s3k_waterExit_restoresCanonical() {
        TestablePlayableSprite sprite = new TestablePlayableSprite("test", (short) 100, (short) 100);
        sprite.updateWaterState(50);
        sprite.updateWaterState(200); // Water at Y=200, sprite at Y=100 → above water
        assertEquals(0x0C, sprite.getRunAccel(), "After water exit: canonical accel $C");
        assertEquals(0x80, sprite.getRunDecel(), "After water exit: canonical decel $80");
        assertEquals(0x600, sprite.getMax(), "After water exit: canonical max $600");
    }

    // --- Speed Shoes ---

    @Test
    void sonic_s3k_speedShoes_doublesCanonical() {
        TestablePlayableSprite sprite = new TestablePlayableSprite("test", (short) 100, (short) 100);
        sprite.giveSpeedShoes();
        assertEquals(0x18, sprite.getRunAccel(), "With shoes: canonical * 2 accel");
        assertEquals(0xC00, sprite.getMax(), "With shoes: canonical * 2 max");
    }

    @Test
    void sonic_s3k_speedShoesExpire_restoresCanonical() {
        TestablePlayableSprite sprite = new TestablePlayableSprite("test", (short) 100, (short) 100);
        sprite.giveSpeedShoes();
        sprite.deactivateSpeedShoes();
        assertEquals(0x0C, sprite.getRunAccel(), "After shoes expire: canonical accel");
        assertEquals(0x80, sprite.getRunDecel(), "After shoes expire: canonical decel");
    }

    @Test
    void speedShoesTimerExpiresBeforeNextMovementFrameAfterRomWindow() {
        TestablePlayableSprite sprite = new TestablePlayableSprite("test", (short) 100, (short) 100);
        var levelManager = sprite.currentLevelManagerIfAvailable();
        assertNotNull(levelManager, "test gameplay mode must provide a level manager");
        sprite.giveSpeedShoes();

        // S3K speed shoes are a byte timer (150) decremented only on every 8th
        // level frame (Sonic_ChkShoes, sonic3k.asm:22072-22078). Advance the
        // level frame counter alongside each timer tick, as the live frame step
        // does, so the every-8th-frame gate fires. The shoes then expire across
        // the ~1200-frame ROM window via 150 decrements.
        int ticks = 0;
        while (sprite.hasSpeedShoes() && ticks < 2000) {
            levelManager.setFrameCounter(ticks);
            GameServices.timers().updateDisplayPhaseTimersFor(sprite);
            ticks++;
        }

        assertFalse(sprite.hasSpeedShoes(),
                "S3K Sonic_Display clears speed shoes and restores movement constants "
                        + "(docs/skdisasm/sonic3k.asm:21540-21561,22067-22081).");
        assertEquals(0x0C, sprite.getRunAccel(), "After shoes expire: canonical accel");
        assertEquals(0x600, sprite.getMax(), "After shoes expire: canonical max");
        assertTrue(ticks >= 1190 && ticks <= 1201,
                "S3K speed shoes last ~1200 wall-clock frames via 150 every-8th-frame "
                        + "decrements (sonic3k.asm:40818 (20*60)/8); was " + ticks);
    }

    // --- S2 Comparison ---

    @Test
    void s2_sonic_matchesS3kSonic() {
        GameModuleRegistry.setCurrent(new com.openggf.game.sonic2.Sonic2GameModule());
        SessionManager.clear();
        TestEnvironment.activeGameplayMode();
        TestablePlayableSprite sprite = new TestablePlayableSprite("test", (short) 100, (short) 100);
        assertEquals(0x0C, sprite.getRunAccel(), "S2 Sonic: same accel as S3K");
        assertEquals(0x80, sprite.getRunDecel(), "S2 Sonic: same decel as S3K");
        assertEquals(0x600, sprite.getMax(), "S2 Sonic: same max as S3K");
    }
}


