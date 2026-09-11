package com.openggf.game.sonic3k.objects;

import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.TestObjectServices;
import com.openggf.sprites.animation.ScriptedVelocityAnimationProfile;
import com.openggf.tests.TestablePlayableSprite;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TestCnzHoverFanObjectInstance {

    @Test
    void hoverFanLiftsUniqueEngineParticipantsOnce() {
        CnzHoverFanInstance fan = new CnzHoverFanInstance(
                new ObjectSpawn(0x100, 0x100, 0x46, 0x00, 0, false, 0));
        TestablePlayableSprite main = playerAt("sonic", 0x100, 0x100);
        TestablePlayableSprite tails = playerAt("tails", 0x100, 0x100);
        TestablePlayableSprite knuckles = playerAt("knuckles", 0x100, 0x100);

        fan.setServices(new TestObjectServices()
                .withSidekicks(List.of(tails, tails, knuckles)));

        fan.update(0, main);

        assertEquals(main.getCentreY(), tails.getCentreY(),
                "Duplicate sidekick entries must not apply the hover fan lift twice");
        assertEquals(main.getCentreY(), knuckles.getCentreY(),
                "Every unique engine sidekick should receive the same one-frame lift as the main player");
    }

    @Test
    void hoverFanKeepsUpdatePlayerFallbackWhenQueryOnlyFindsSidekicks() {
        CnzHoverFanInstance fan = new CnzHoverFanInstance(
                new ObjectSpawn(0x100, 0x100, 0x46, 0x00, 0, false, 0));
        TestablePlayableSprite main = playerAt("sonic", 0x100, 0x100);
        TestablePlayableSprite tails = playerAt("tails", 0x100, 0x100);

        fan.setServices(new TestObjectServices()
                .withSidekicks(List.of(tails)));

        fan.update(0, main);

        assertEquals(tails.getCentreY(), main.getCentreY(),
                "The update player must still participate when ObjectPlayerQuery cannot resolve main");
    }

    @Test
    void movingHoverFanUsesSavedOriginForOffscreenLifecycle() {
        CnzHoverFanInstance fan = new CnzHoverFanInstance(
                new ObjectSpawn(0x1850, 0x0AA0, 0x46, 0x80, 0x01, false, 0));

        fan.setServices(new TestObjectServices());
        fan.update(0, null);

        assertEquals(0x1850, fan.getOutOfRangeReferenceX(),
                "Obj_CNZHoverFan loc_31E36 feeds saved $30(a0) to Sprite_OnScreen_Test2 "
                        + "after moving live x_pos (docs/skdisasm/sonic3k.asm:67327-67332,67349-67350).");
    }

    @Test
    void overlappingActiveFansRecoverLayoutOrderWhenManagedSlotsAreReversed() {
        CnzHoverFanInstance earlier = new CnzHoverFanInstance(
                new ObjectSpawn(0x17D0, 0x0A08, 0x46, 0x80, 0, false, 0, 193));
        CnzHoverFanInstance later = new CnzHoverFanInstance(
                new ObjectSpawn(0x1800, 0x0AA0, 0x46, 0x83, 1, false, 0, 198));
        earlier.setSlotIndex(7);
        later.setSlotIndex(6);

        assertEquals(6, earlier.resolveActiveVariantExecutionSlot(List.of(earlier, later)));
        assertEquals(7, later.resolveActiveVariantExecutionSlot(List.of(earlier, later)));
    }

    @Test
    void staticFansKeepTheirOwnedSlotOrder() {
        CnzHoverFanInstance earlier = new CnzHoverFanInstance(
                new ObjectSpawn(0x100, 0x100, 0x46, 0x03, 0, false, 0, 20));
        CnzHoverFanInstance later = new CnzHoverFanInstance(
                new ObjectSpawn(0x100, 0x100, 0x46, 0x13, 0, false, 0, 19));
        earlier.setSlotIndex(15);
        later.setSlotIndex(16);

        assertEquals(15, earlier.resolveActiveVariantExecutionSlot(List.of(earlier, later)),
                "loc_31E68 static fans retain ascending SST execution order");
        assertEquals(16, later.resolveActiveVariantExecutionSlot(List.of(earlier, later)));
    }

    @Test
    void jumpingIntoHoverFanPreservesFanWalkAnimationInsteadOfResolvingBackToRoll() {
        CnzHoverFanInstance fan = new CnzHoverFanInstance(
                new ObjectSpawn(0x100, 0x100, 0x46, 0x80, 0, false, 0));
        TestablePlayableSprite player = playerAt("sonic", 0x100, 0x100);
        player.setAir(true);
        player.setJumping(true);
        player.setRolling(true);
        player.setRollingJump(true);
        player.setAnimationId(2);
        player.setFlipAngle(0);

        fan.setServices(new TestObjectServices());
        fan.update(0, player);

        ScriptedVelocityAnimationProfile profile = new ScriptedVelocityAnimationProfile()
                .setIdleAnimId(5)
                .setWalkAnimId(0)
                .setRunAnimId(1)
                .setRollAnimId(2)
                .setAirAnimId(2);

        assertEquals(0, player.getAnimationId(),
                "Obj_CNZHoverFan writes anim=0 when it seeds flip motion");
        assertEquals(null, profile.resolveAnimationId(player, 0, 3),
                "The post-capture airborne external-force state must let fan anim=0 persist");
    }

    private static TestablePlayableSprite playerAt(String code, int centreX, int centreY) {
        TestablePlayableSprite player = new TestablePlayableSprite(code, (short) 0, (short) 0);
        player.setCentreX((short) centreX);
        player.setCentreY((short) centreY);
        return player;
    }
}
