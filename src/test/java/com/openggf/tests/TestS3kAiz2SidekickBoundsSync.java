package com.openggf.tests;

import com.openggf.configuration.SonicConfiguration;
import com.openggf.configuration.SonicConfigurationService;
import com.openggf.game.GameServices;
import com.openggf.game.sonic3k.Sonic3kLevelEventManager;
import com.openggf.game.sonic3k.events.Sonic3kAIZEvents;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.sprites.playable.SidekickCpuController;
import com.openggf.sprites.playable.Tails;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression for S3K CPU sidekick bounds during dynamic camera resize.
 * AIZ2 updates camera bounds throughout the act; sidekicks must not keep a
 * stale copy from level load or they can despawn as soon as normal physics
 * resumes after a fly-in.
 */
@RequiresRom(SonicGame.SONIC_3K)
public class TestS3kAiz2SidekickBoundsSync {
    private static final int ZONE_AIZ = 0;
    private static final int ACT_2 = 1;

    private static Object oldSkipIntros;
    private static SharedLevel sharedLevel;

    private HeadlessTestFixture fixture;

    @BeforeAll
    public static void loadLevel() throws Exception {
        SonicConfigurationService config = SonicConfigurationService.getInstance();
        oldSkipIntros = config.getConfigValue(SonicConfiguration.S3K_SKIP_INTROS);
        config.setConfigValue(SonicConfiguration.S3K_SKIP_INTROS, true);

        sharedLevel = SharedLevel.load(SonicGame.SONIC_3K, ZONE_AIZ, ACT_2);
    }

    @AfterAll
    public static void cleanup() {
        SonicConfigurationService config = SonicConfigurationService.getInstance();
        config.setConfigValue(SonicConfiguration.S3K_SKIP_INTROS,
                oldSkipIntros != null ? oldSkipIntros : false);
        if (sharedLevel != null) {
            sharedLevel.dispose();
        }
    }

    @BeforeEach
    public void setUp() {
        fixture = HeadlessTestFixture.builder()
                .withSharedLevel(sharedLevel)
                .build();
        createSidekick();
    }

    @Test
    public void levelEventsRefreshSidekickBoundsFromLiveCamera() {
        AbstractPlayableSprite sidekick = GameServices.sprites().getSidekicks().getFirst();
        SidekickCpuController controller = sidekick.getCpuController();
        assertNotNull(controller, "CPU sidekick should have a controller");

        controller.setLevelBounds(0x1111, 0x2222, 0x3333);
        fixture.camera().setMaxY((short) 0x02F2);
        fixture.camera().setMaxYTarget((short) 0x0800);
        GameServices.module().getLevelEventProvider().update();

        assertEquals(fixture.camera().getMinX(), controller.getMinXBound(Integer.MIN_VALUE),
                "S3K level events should refresh sidekick minX bounds from camera");
        assertEquals(fixture.camera().getMaxX(), controller.getMaxXBound(Integer.MIN_VALUE),
                "S3K level events should refresh sidekick maxX bounds from camera");
        assertEquals(fixture.camera().getMaxY(),
                controller.getMaxYBound(Integer.MIN_VALUE),
                "S3K Tails uses the live Camera_max_Y_pos rather than its resize target");
    }

    @Test
    public void aizBattleshipPrePhysicsWrapRefreshesSidekickBoundsBeforeMovement() throws Exception {
        AbstractPlayableSprite sidekick = GameServices.sprites().getSidekicks().getFirst();
        SidekickCpuController controller = sidekick.getCpuController();
        assertNotNull(controller, "CPU sidekick should have a controller");

        fixture.camera().setX((short) 0x46BC);
        fixture.camera().setMinX((short) 0x46BC);
        fixture.camera().setMaxX((short) 0x46BC);
        sidekick.setCentreXPreserveSubpixel((short) 0x4736);
        sidekick.setXSpeed((short) 0x0400);
        sidekick.setGSpeed((short) 0x0400);
        controller.setLevelBounds(0x46BC, 0x45A4, 0x015A);

        Sonic3kLevelEventManager manager =
                (Sonic3kLevelEventManager) GameServices.module().getLevelEventProvider();
        Sonic3kAIZEvents events = manager.getAizEvents();
        assertNotNull(events, "AIZ events should be initialized");
        setPrivate(events, "battleshipAutoScrollActive", true);
        setPrivate(events, "battleshipWrapX", 0x46C0);

        events.updatePrePhysics(ACT_2);

        // AIZ2_DoShipLoop advances Camera_X by 4 to the wrap boundary (0x46C0) and
        // subtracts the ROM $200 Level_repeat_offset before Process_Sprites. This
        // regression asserts that the sidekick bound mirror observes the post-wrap
        // camera in the same frame; exact visual compensation belongs to render code.
        int wrappedCameraMaxX = fixture.camera().getMaxX() & 0xFFFF;
        assertTrue(wrappedCameraMaxX < 0x46C0,
                "AIZ2_DoShipLoop must wrap Camera_max_X_pos back before Process_Sprites");
        assertEquals(wrappedCameraMaxX, controller.getMaxXBound(Integer.MIN_VALUE) & 0xFFFF,
                "S3K sidekick boundary mirror must be refreshed immediately after the pre-physics camera wrap");
    }

    private void createSidekick() {
        Tails tails = new Tails("tails", (short) 0, (short) 0);
        tails.setCpuControlled(true);
        SidekickCpuController controller = new SidekickCpuController(tails, fixture.sprite());
        tails.setCpuController(controller);
        GameServices.sprites().addSprite(tails);
    }

    private static void setPrivate(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }
}
