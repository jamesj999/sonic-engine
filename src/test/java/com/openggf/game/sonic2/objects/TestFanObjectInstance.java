package com.openggf.game.sonic2.objects;

import com.openggf.camera.Camera;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.TestObjectServices;
import com.openggf.tests.TestablePlayableSprite;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TestFanObjectInstance {

    @Test
    void horizontalFanPushesUniqueEngineParticipantsOnce() {
        FanObjectInstance fan = new FanObjectInstance(
                new ObjectSpawn(0x1000, 0x1060, 0x3F, 0x02, 0, false, 0),
                "OOZFan");
        TestablePlayableSprite main = playerAtFanCentre("sonic");
        TestablePlayableSprite tails = playerAtFanCentre("tails");
        TestablePlayableSprite knuckles = playerAtFanCentre("knuckles");
        Camera camera = new Camera();
        camera.setFocusedSprite(main);
        fan.setServices(new TestObjectServices()
                .withCamera(camera)
                .withSidekicks(List.of(tails, tails, knuckles)));

        fan.update(0, main);

        assertEquals(main.getCentreX(), tails.getCentreX(),
                "Duplicate sidekick entries should be de-duplicated by the participation policy");
        assertEquals(main.getCentreX(), knuckles.getCentreX(),
                "All engine sidekicks should still participate in the fan push");
    }

    @Test
    void horizontalFanKeepsUpdatePlayerFallbackWhenQueryOnlyFindsSidekicks() {
        FanObjectInstance fan = new FanObjectInstance(
                new ObjectSpawn(0x1000, 0x1060, 0x3F, 0x02, 0, false, 0),
                "OOZFan");
        TestablePlayableSprite main = playerAtFanCentre("sonic");
        TestablePlayableSprite tails = playerAtFanCentre("tails");
        fan.setServices(new TestObjectServices().withSidekicks(List.of(tails)));

        fan.update(0, main);

        assertEquals(tails.getCentreX(), main.getCentreX(),
                "The update(...) player should still participate when ObjectPlayerQuery cannot resolve main");
    }

    private static TestablePlayableSprite playerAtFanCentre(String code) {
        TestablePlayableSprite player = new TestablePlayableSprite(code, (short) 0x1000, (short) 0x1000);
        player.setCentreX((short) 0x1000);
        player.setCentreY((short) 0x1000);
        return player;
    }
}
