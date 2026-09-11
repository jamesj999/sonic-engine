package com.openggf.game.sonic1.objects;

import com.openggf.game.GameModuleRegistry;
import com.openggf.game.sonic1.Sonic1GameModule;
import com.openggf.sprites.animation.SpriteAnimationEndAction;
import com.openggf.sprites.animation.SpriteAnimationScript;
import com.openggf.sprites.animation.SpriteAnimationSet;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import com.openggf.level.objects.ObjectSpawn;

import java.util.List;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class TestSonic1RunningDiscObjectInstance {

    @AfterEach
    void resetGameModuleOverride() {
        GameModuleRegistry.reset();
    }

    @Test
    public void attachesAndSetsStickToConvex() {
        Sonic1RunningDiscObjectInstance disc = createDisc(0x10, 0x200, 0x200);
        TestPlayableSprite player = new TestPlayableSprite();
        player.setCentreX((short) 0x200);
        player.setCentreY((short) 0x200);
        player.setAir(false);
        player.setGSpeed((short) 0);

        disc.update(1, player);

        // ROM: move.b #1,stick_to_convex(a1)
        assertTrue(player.isStickToConvex());
        // ROM: move.w #$400,obInertia(a1) (positive angularSpeed -> clamp rightward)
        assertEquals(0x400, player.getGSpeed());
    }

    @Test
    public void leavingDiscClearsStickToConvex() {
        Sonic1RunningDiscObjectInstance disc = createDisc(0x10, 0x200, 0x200);
        TestPlayableSprite player = new TestPlayableSprite();
        player.setCentreX((short) 0x200);
        player.setCentreY((short) 0x200);
        player.setAir(false);

        disc.update(1, player);
        assertTrue(player.isStickToConvex());

        // Outside detection square -> detach
        // ROM: clr.b stick_to_convex(a1) / clr.b disc_sonic_attached(a0)
        player.setCentreX((short) 0x300);
        player.setCentreY((short) 0x300);
        disc.update(2, player);

        assertFalse(player.isStickToConvex());
    }

    @Test
    public void airborneInsideRangeClearsAttachmentButKeepsConvex() {
        Sonic1RunningDiscObjectInstance disc = createDisc(0x10, 0x200, 0x200);
        TestPlayableSprite player = new TestPlayableSprite();
        player.setCentreX((short) 0x200);
        player.setCentreY((short) 0x200);
        player.setAir(false);

        disc.update(1, player);
        assertTrue(player.isStickToConvex());

        // In-range but airborne: ROM clears disc_sonic_attached but does NOT
        // clear stick_to_convex (only the .detach path does that).
        player.setAir(true);
        disc.update(2, player);
        assertTrue(player.isStickToConvex());

        // Out of range, but disc_sonic_attached was already cleared by the
        // airborne path, so the .detach branch skips stick_to_convex clearing
        // (ROM: tst.b disc_sonic_attached / beq.s .return). ROM-accurate.
        player.setCentreX((short) 0x300);
        player.setCentreY((short) 0x300);
        disc.update(3, player);
        assertTrue(player.isStickToConvex());
    }

    @Test
    public void firstGroundedAttachmentPublishesWalkWithRunPreviousAnimation() {
        GameModuleRegistry.setCurrent(new Sonic1GameModule());
        Sonic1RunningDiscObjectInstance disc = createDisc(0x10, 0x200, 0x200);
        TestPlayableSprite player = new TestPlayableSprite();
        player.setCentreX((short) 0x200);
        player.setCentreY((short) 0x200);
        player.setAir(false);
        player.setRolling(false);
        SpriteAnimationSet animations = new SpriteAnimationSet();
        animations.addScript(0, new SpriteAnimationScript(0,
                List.of(0x08, 0x09), SpriteAnimationEndAction.LOOP, 0));
        animations.addScript(2, new SpriteAnimationScript(0,
                List.of(0x22), SpriteAnimationEndAction.LOOP, 0));
        player.setAnimationSet(animations);
        player.setAnimationId(2);
        player.getAnimationManager().update(0);

        disc.update(1, player);

        assertEquals(0, player.getAnimationId(), "Disc_MoveSonic clears obAnim");
        assertEquals(1, player.getAnimationManager().captureRewindState().lastAnimationId(),
                "Disc_MoveSonic writes id_Run to obPrevAni");
        player.getAnimationManager().update(1);
        assertEquals(0x08, player.getMappingFrame(),
                "The next animation pass must restart Walk from its first mapping");
    }

    private static Sonic1RunningDiscObjectInstance createDisc(int subtype, int x, int y) {
        return new Sonic1RunningDiscObjectInstance(
                new ObjectSpawn(x, y, 0x67, subtype, 0, false, 0));
    }

}
