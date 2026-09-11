package com.openggf.game.sonic3k.objects;

import com.openggf.game.PlayableEntity;
import com.openggf.game.sonic3k.constants.Sonic3kObjectIds;
import com.openggf.level.objects.ObjectPlayerQuery;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.TestObjectServices;
import com.openggf.tests.TestablePlayableSprite;
import com.openggf.sprites.playable.ObjectControlState;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestHCZCGZFanObjectInstance {

    @Test
    void activeFanPushesUniqueQueryParticipantsIndependently() {
        HCZCGZFanObjectInstance fan = new HCZCGZFanObjectInstance(
                new ObjectSpawn(0x1000, 0x1000, Sonic3kObjectIds.HCZ_CGZ_FAN, 0x10, 0, false, 0));
        TestablePlayableSprite main = playerInFanColumn("sonic");
        TestablePlayableSprite tails = playerInFanColumn("tails");
        TestablePlayableSprite knuckles = playerInFanColumn("knuckles");
        fan.setServices(new QueryOnlyPlayerServices(main, List.of(tails, tails, knuckles)));

        fan.update(0, main);

        assertTrue(main.getAir());
        assertEquals(main.getCentreY(), tails.getCentreY(),
                "Duplicate sidekick entries should be de-duplicated by the participation policy");
        assertEquals(main.getCentreY(), knuckles.getCentreY(),
                "All engine sidekicks should still receive the independent fan push");
    }

    @Test
    void timerFanKeepsTerminalIdleTickBeforeSettingConveyorMarker() {
        HCZCGZFanObjectInstance fan = new HCZCGZFanObjectInstance(
                new ObjectSpawn(0x1000, 0x1000, Sonic3kObjectIds.HCZ_CGZ_FAN, 0x44, 0, false, 0));
        TestablePlayableSprite player = new TestablePlayableSprite("sonic", (short) 0x1000, (short) 0x0F80);
        player.setCentreX((short) 0x1000);
        player.setCentreY((short) 0x0F80);
        ObjectControlState.nativeBits0To6CpuAllowedMovementSuppressed().applyTo(player);
        fan.setServices(new QueryOnlyPlayerServices(player, List.of()));

        for (int frame = 0; frame <= 121; frame++) {
            player.setGSpeed((short) 0);
            fan.update(frame, player);
            assertEquals(0, player.getGSpeed(),
                    "Timer fan should not set the conveyor marker during the terminal idle tick");
        }

        fan.update(122, player);

        assertEquals(1, player.getGSpeed(),
                "The next update after the terminal idle tick should resume fan/conveyor interaction");
    }

    @Test
    void fanLiftPreservesNativeYSubpixel() {
        HCZCGZFanObjectInstance fan = new HCZCGZFanObjectInstance(
                new ObjectSpawn(0x1000, 0x1000, Sonic3kObjectIds.HCZ_CGZ_FAN, 0x10, 0, false, 0));
        TestablePlayableSprite player = playerInFanColumn("sonic");
        player.setSubpixelRaw(0, 0xF000);
        fan.setServices(new QueryOnlyPlayerServices(player, List.of()));

        fan.update(0, player);

        assertEquals(0xF000, player.getYSubpixelRaw(),
                "Obj_HCZCGZFan add.w y_pos must preserve the low subpixel word");
        assertTrue(player.getAir());
    }

    @Test
    void fanAndPlatformUnloadFromRomOriginWhenSliding() {
        HCZCGZFanObjectInstance fan = new HCZCGZFanObjectInstance(
                new ObjectSpawn(0x1000, 0x1000, Sonic3kObjectIds.HCZ_CGZ_FAN, 0x10, 0, false, 0));
        fan.setFanX(0x0F80);

        HCZCGZFanObjectInstance.FanPlatformChild platform =
                new HCZCGZFanObjectInstance.FanPlatformChild(
                        new ObjectSpawn(0x1000, 0x101C, Sonic3kObjectIds.HCZ_CGZ_FAN, 0, 0, false, 0),
                        fan,
                        0x60,
                        true);

        assertEquals(0x1000, fan.getOutOfRangeReferenceX(),
                "Obj_HCZCGZFan Sprite_OnScreen_Test2 uses $40(a0), not current x_pos");
        assertEquals(0x1000, platform.getOutOfRangeReferenceX(),
                "Obj_HCZCGZFan platform Sprite_OnScreen_Test2 uses $40(a0), not current x_pos");
    }

    @Test
    void platformModeRunsFanAfterSlidingFanX() {
        HCZCGZFanObjectInstance fan = new HCZCGZFanObjectInstance(
                new ObjectSpawn(0x0FD0, 0x1050, Sonic3kObjectIds.HCZ_CGZ_FAN, 0x10, 0, false, 0));
        TestablePlayableSprite player = playerInFanColumn("sonic");

        HCZCGZFanObjectInstance.FanPlatformChild platform =
                new HCZCGZFanObjectInstance.FanPlatformChild(
                        new ObjectSpawn(0x1000, 0x0FC0, Sonic3kObjectIds.HCZ_CGZ_FAN, 0, 0, false, 0),
                        fan,
                        0x60,
                        false);
        QueryOnlyPlayerServices services = new QueryOnlyPlayerServices(player, List.of());
        fan.setServices(services);
        platform.setServices(services);

        platform.update(0, player);
        fan.setLatchedOn(false);
        fan.setFanX(0x0FD0);
        player.setCentreY((short) 0x0FD0);
        player.setYSpeed((short) 0x0200);

        platform.update(1, player);

        assertEquals(0, player.getYSpeed(),
                "Platform-mode fan child should apply lift after the platform writes the current fan x_pos");
        assertTrue(player.getAir());
    }

    private static TestablePlayableSprite playerInFanColumn(String code) {
        TestablePlayableSprite player = new TestablePlayableSprite(code, (short) 0x1000, (short) 0x1000);
        player.setCentreX((short) 0x1000);
        player.setCentreY((short) 0x1000);
        return player;
    }

    private static final class QueryOnlyPlayerServices extends TestObjectServices {
        private final PlayableEntity main;
        private final List<? extends PlayableEntity> queriedSidekicks;

        private QueryOnlyPlayerServices(PlayableEntity main, List<? extends PlayableEntity> queriedSidekicks) {
            this.main = main;
            this.queriedSidekicks = List.copyOf(queriedSidekicks);
        }

        @Override
        public ObjectPlayerQuery playerQuery() {
            return new ObjectPlayerQuery(() -> main, () -> queriedSidekicks);
        }

        @Override
        public List<PlayableEntity> sidekicks() {
            return List.of();
        }
    }
}
