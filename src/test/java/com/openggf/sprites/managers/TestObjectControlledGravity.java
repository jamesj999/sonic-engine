package com.openggf.sprites.managers;

import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.tests.HeadlessTestFixture;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import com.openggf.game.GameServices;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

@RequiresRom(SonicGame.SONIC_3K)
class TestObjectControlledGravity {

    @Test
    void gravityIsSkippedWhenObjectControlled() {
        HeadlessTestFixture fixture = HeadlessTestFixture.builder()
                .withZoneAndAct(3, 0)  // CNZ1 avoids AIZ1 intro object-control scripting.
                .build();
        AbstractPlayableSprite sonic = fixture.sprite();

        GameServices.camera().setLevelStarted(true);
        sonic.setCentreY((short) 0x0200);
        sonic.setOnObject(false);
        sonic.setAir(true);
        sonic.setObjectControlled(true);
        sonic.setYSpeed((short) 0);
        short before = sonic.getYSpeed();

        // 5 booleans: up, down, left, right, jump — all false = idle frame
        fixture.stepFrame(false, false, false, false, false);

        short after = sonic.getYSpeed();
        assertEquals(before, after,
                "y_speed must not change when object-controlled (gravity gated)");
    }

    @Test
    void gravityStillAppliedWhenNotObjectControlled() {
        HeadlessTestFixture fixture = HeadlessTestFixture.builder()
                .withZoneAndAct(3, 0)
                .build();
        AbstractPlayableSprite sonic = fixture.sprite();

        GameServices.camera().setLevelStarted(true);
        sonic.setCentreY((short) 0x0200);
        sonic.setOnObject(false);
        sonic.setAir(true);
        sonic.setObjectControlled(false);
        sonic.setYSpeed((short) 0);
        short before = sonic.getYSpeed();

        fixture.stepFrame(false, false, false, false, false);

        short after = sonic.getYSpeed();
        assertEquals((short) (before + (short) sonic.getGravity()), after,
                "y_speed must accumulate gravity when not object-controlled");
    }

    @Test
    void gravityStillAppliesWhenObjectControlDoesNotSuppressMovement() {
        HeadlessTestFixture fixture = HeadlessTestFixture.builder()
                .withZoneAndAct(3, 0)
                .build();
        AbstractPlayableSprite sonic = fixture.sprite();

        GameServices.camera().setLevelStarted(true);
        sonic.setCentreY((short) 0x0200);
        sonic.setOnObject(false);
        sonic.setAir(true);
        sonic.setObjectControlled(true);
        sonic.setObjectControlAllowsCpu(true);
        sonic.setObjectControlSuppressesMovement(false);
        sonic.setYSpeed((short) 0);
        short before = sonic.getYSpeed();

        fixture.stepFrame(false, false, false, false, false);

        short after = sonic.getYSpeed();
        assertEquals((short) (before + (short) sonic.getGravity()), after,
                "ROM object_control bits without bit 0 should not skip movement gravity");
    }
}
