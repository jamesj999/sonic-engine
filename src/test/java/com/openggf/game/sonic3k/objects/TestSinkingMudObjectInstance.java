package com.openggf.game.sonic3k.objects;

import com.openggf.configuration.SonicConfiguration;
import com.openggf.configuration.SonicConfigurationService;
import com.openggf.debug.DebugRenderContext;
import com.openggf.game.PlayableEntity;
import com.openggf.game.sonic3k.constants.Sonic3kObjectIds;
import com.openggf.game.sonic3k.constants.Sonic3kZoneIds;
import com.openggf.graphics.GLCommand;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectPlayerQuery;
import com.openggf.level.objects.ObjectServices;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.SolidContact;
import com.openggf.level.objects.SolidObjectParams;
import com.openggf.level.objects.TestObjectServices;
import com.openggf.tools.Sonic3kObjectProfile;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestSinkingMudObjectInstance {

    @Test
    void registryCreatesSinkingMudInstance() {
        ObjectInstance instance = new Sonic3kObjectRegistry().create(
                new ObjectSpawn(0x1200, 0x0600, Sonic3kObjectIds.SINKING_MUD, 0x04, 0x00, false, 0));

        assertInstanceOf(SinkingMudObjectInstance.class, instance);
    }

    @Test
    void subtypeControlsWidthAndSinglePlayerSurfaceHeight() {
        SinkingMudObjectInstance mud = new SinkingMudObjectInstance(
                new ObjectSpawn(0x100, 0x200, Sonic3kObjectIds.SINKING_MUD, 0x04, 0x00, false, 0));

        SolidObjectParams params = mud.getSolidParams();

        assertEquals(0x20, params.halfWidth());
        assertEquals(0x30, params.airHalfHeight());
        assertEquals(0x30, params.groundHalfHeight());
        assertTrue(mud.isTopSolidOnly());
        assertEquals(0x0003, mud.romObjectCodePointerHighWord());
        assertEquals(4, mud.getPriorityBucket());
    }

    @Test
    void competitionZonesUseHalfHeightSurface() {
        SinkingMudObjectInstance mud = new SinkingMudObjectInstance(
                new ObjectSpawn(0x100, 0x200, Sonic3kObjectIds.SINKING_MUD, 0x04, 0x00, false, 0));
        ObjectServices services = mock(ObjectServices.class);
        when(services.romZoneId()).thenReturn(Sonic3kZoneIds.ZONE_DPZ);
        mud.setServices(services);

        SolidObjectParams params = mud.getSolidParams();

        assertEquals(0x20, params.halfWidth());
        assertEquals(0x18, params.airHalfHeight());
        assertEquals(0x18, params.groundHalfHeight());
    }

    @Test
    void standingFramesEventuallyKillThePlayerAndResetTheirSurface() {
        SinkingMudObjectInstance mud = new SinkingMudObjectInstance(
                new ObjectSpawn(0x100, 0x200, Sonic3kObjectIds.SINKING_MUD, 0x04, 0x00, false, 0));
        mud.setServices(new TestObjectServices());
        PlayableEntity player = mock(PlayableEntity.class);
        when(player.getDead()).thenReturn(false);
        when(player.isOnObject()).thenReturn(false);
        when(player.getYRadius()).thenReturn((short) 19);
        when(player.getHeight()).thenReturn(38);

        for (int frame = 0; frame < 50; frame++) {
            mud.update(frame, player);
            mud.onSolidContact(player, new SolidContact(true, false, false, true, false), frame);
        }

        verify(player).applyCrushDeath();
        assertEquals(0x30, mud.rawSurfaceForTest(player));
    }

    @Test
    void updateAdvancesUniqueQueryParticipantsOnlyOnce() {
        SinkingMudObjectInstance mud = new SinkingMudObjectInstance(
                new ObjectSpawn(0x100, 0x200, Sonic3kObjectIds.SINKING_MUD, 0x04, 0x00, false, 0));
        PlayableEntity main = playable();
        PlayableEntity sidekick = playable();
        mud.setServices(new QueryOnlyPlayerServices(main, List.of(sidekick, sidekick)));
        mud.onSolidContact(sidekick, new SolidContact(true, false, false, true, false), 0);

        mud.update(1, main);

        assertEquals(0x2F, mud.rawSurfaceForTest(sidekick),
                "Query sidekicks should participate, but duplicate entries must not advance twice");
    }

    @Test
    void profileMarksSinkingMudImplemented() {
        Sonic3kObjectProfile profile = new Sonic3kObjectProfile();
        assertTrue(profile.getImplementedIds().contains(Sonic3kObjectIds.SINKING_MUD));
    }

    @Test
    void collisionVolumeOnlyRendersInObjectDebugPass() {
        SinkingMudObjectInstance mud = new SinkingMudObjectInstance(
                new ObjectSpawn(0x100, 0x200, Sonic3kObjectIds.SINKING_MUD, 0x04, 0x00, false, 0));
        SonicConfigurationService configuration = SonicConfigurationService.getInstance();
        boolean previousDebugView = configuration.getBoolean(SonicConfiguration.DEBUG_VIEW_ENABLED);
        configuration.setConfigValue(SonicConfiguration.DEBUG_VIEW_ENABLED, true);
        mud.setServices(new TestObjectServices().withConfiguration(configuration));
        List<GLCommand> normalCommands = new java.util.ArrayList<>();
        DebugRenderContext debugContext = new DebugRenderContext();

        try {
            mud.appendRenderCommands(normalCommands);
            mud.appendDebugRenderCommands(debugContext);

            assertTrue(normalCommands.isEmpty(), "Invisible mud must not leak debug lines into gameplay rendering");
            assertEquals(8, debugContext.getGeometryCommands().size(),
                    "OBJECT_DEBUG should retain the four-line collision-volume outline");
        } finally {
            configuration.setConfigValue(SonicConfiguration.DEBUG_VIEW_ENABLED, previousDebugView);
        }
    }

    private static PlayableEntity playable() {
        PlayableEntity player = mock(PlayableEntity.class);
        when(player.getDead()).thenReturn(false);
        when(player.isOnObject()).thenReturn(false);
        when(player.getYRadius()).thenReturn((short) 19);
        when(player.getHeight()).thenReturn(38);
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
