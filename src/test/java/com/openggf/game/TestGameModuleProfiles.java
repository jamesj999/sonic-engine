package com.openggf.game;

import com.openggf.audio.GameMusic;
import com.openggf.game.sonic1.Sonic1GameModule;
import com.openggf.game.sonic1.audio.Sonic1Music;
import com.openggf.game.sonic1.Sonic1LevelInitProfile;
import com.openggf.game.sonic2.Sonic2GameModule;
import com.openggf.game.sonic2.audio.Sonic2Music;
import com.openggf.game.sonic2.Sonic2LevelInitProfile;
import com.openggf.game.sonic3k.Sonic3kGameModule;
import com.openggf.game.sonic3k.audio.Sonic3kMusic;
import com.openggf.game.sonic3k.Sonic3kLevelInitProfile;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class TestGameModuleProfiles {

    @Test
    public void sonic2ModuleReturnsSonic2Profile() {
        GameModule module = new Sonic2GameModule();
        assertTrue(module.getLevelInitProfile() instanceof Sonic2LevelInitProfile);
    }

    @Test
    public void sonic1ModuleReturnsSonic1Profile() {
        GameModule module = new Sonic1GameModule();
        assertTrue(module.getLevelInitProfile() instanceof Sonic1LevelInitProfile);
    }

    @Test
    public void sonic3kModuleReturnsSonic3kProfile() {
        GameModule module = new Sonic3kGameModule();
        assertTrue(module.getLevelInitProfile() instanceof Sonic3kLevelInitProfile);
    }

    @Test
    public void profilesAreNotNull() {
        assertNotNull(new Sonic1GameModule().getLevelInitProfile());
        assertNotNull(new Sonic2GameModule().getLevelInitProfile());
        assertNotNull(new Sonic3kGameModule().getLevelInitProfile());
    }

    @Test
    public void onlySonic3kPreservesFreshGroundedStatusUntilFirstDispatch() {
        assertFalse(new Sonic1GameModule().getLevelInitProfile()
                .preserveFreshGroundedStatusUntilFirstDispatch());
        assertFalse(new Sonic2GameModule().getLevelInitProfile()
                .preserveFreshGroundedStatusUntilFirstDispatch());
        assertTrue(new Sonic3kGameModule().getLevelInitProfile()
                .preserveFreshGroundedStatusUntilFirstDispatch());
    }

    @Test
    public void audioProfilesMapCommonMusicCues() {
        var s1Music = new Sonic1GameModule().getAudioProfile().getMusicMap();
        assertEquals(Sonic1Music.GOT_THROUGH.id, s1Music.get(GameMusic.ACT_CLEAR));
        assertEquals(Sonic1Music.DROWNING.id, s1Music.get(GameMusic.DROWNING));
        assertEquals(Sonic1Music.CHAOS_EMERALD.id, s1Music.get(GameMusic.EMERALD));
        assertEquals(Sonic1Music.EXTRA_LIFE.id, s1Music.get(GameMusic.EXTRA_LIFE));
        assertEquals(Sonic1Music.INVINCIBILITY.id, s1Music.get(GameMusic.INVINCIBILITY));
        assertEquals(Sonic1Music.SPECIAL_STAGE.id, s1Music.get(GameMusic.SPECIAL_STAGE));
        assertFalse(s1Music.containsKey(GameMusic.SUPER));

        var s2Music = new Sonic2GameModule().getAudioProfile().getMusicMap();
        assertEquals(Sonic2Music.ACT_CLEAR.id, s2Music.get(GameMusic.ACT_CLEAR));
        assertEquals(Sonic2Music.UNDERWATER.id, s2Music.get(GameMusic.DROWNING));
        assertEquals(Sonic2Music.GOT_EMERALD.id, s2Music.get(GameMusic.EMERALD));
        assertEquals(Sonic2Music.EXTRA_LIFE.id, s2Music.get(GameMusic.EXTRA_LIFE));
        assertEquals(Sonic2Music.INVINCIBILITY.id, s2Music.get(GameMusic.INVINCIBILITY));
        assertEquals(Sonic2Music.SPECIAL_STAGE.id, s2Music.get(GameMusic.SPECIAL_STAGE));
        assertEquals(Sonic2Music.SUPER_SONIC.id, s2Music.get(GameMusic.SUPER));

        var s3kMusic = new Sonic3kGameModule().getAudioProfile().getMusicMap();
        assertEquals(Sonic3kMusic.ACT_CLEAR.id, s3kMusic.get(GameMusic.ACT_CLEAR));
        assertEquals(Sonic3kMusic.DROWNING.id, s3kMusic.get(GameMusic.DROWNING));
        assertEquals(Sonic3kMusic.EMERALD.id, s3kMusic.get(GameMusic.EMERALD));
        assertEquals(Sonic3kMusic.EXTRA_LIFE.id, s3kMusic.get(GameMusic.EXTRA_LIFE));
        assertEquals(Sonic3kMusic.INVINCIBILITY.id, s3kMusic.get(GameMusic.INVINCIBILITY));
        assertEquals(Sonic3kMusic.SPECIAL_STAGE.id, s3kMusic.get(GameMusic.SPECIAL_STAGE));
        assertEquals(Sonic3kMusic.INVINCIBILITY.id, s3kMusic.get(GameMusic.SUPER));
    }

    @Test
    public void defaultReturnsEmptyProfile() {
        // Anonymous GameModule gets the safe empty default
        GameModule anon = new GameModule() {
            @Override
            public String getIdentifier() { return "test"; }
            @Override
            public com.openggf.data.Game createGame(com.openggf.data.Rom rom) { return null; }
            @Override
            public com.openggf.level.objects.ObjectRegistry createObjectRegistry() { return null; }
            @Override
            public com.openggf.audio.GameAudioProfile getAudioProfile() { return null; }
            @Override
            public com.openggf.level.objects.TouchResponseTable createTouchResponseTable(
                    com.openggf.data.RomByteReader r) { return null; }
            @Override
            public int getPlaneSwitcherObjectId() { return 0; }
            @Override
            public com.openggf.level.objects.PlaneSwitcherConfig getPlaneSwitcherConfig() { return null; }
            @Override
            public LevelEventProvider getLevelEventProvider() { return null; }
            @Override
            public RespawnState createRespawnState() { return null; }
            @Override
            public LevelState createLevelState() { return null; }
            @Override
            public ZoneRegistry getZoneRegistry() { return null; }
            @Override
            public ScrollHandlerProvider getScrollHandlerProvider() { return null; }
            @Override
            public ZoneFeatureProvider getZoneFeatureProvider() { return null; }
            @Override
            public RomOffsetProvider getRomOffsetProvider() { return null; }
            @Override
            public DebugModeProvider getDebugModeProvider() { return null; }
            @Override
            public DebugOverlayProvider getDebugOverlayProvider() { return null; }
            @Override
            public ZoneArtProvider getZoneArtProvider() { return null; }
            @Override
            public ObjectArtProvider getObjectArtProvider() { return null; }
            @Override
            public com.openggf.game.PhysicsProvider getPhysicsProvider() { return null; }
            @Override
            public GameId getGameId() { return null; }
        };
        LevelInitProfile profile = anon.getLevelInitProfile();
        assertNotNull(profile);
        assertTrue(profile.levelLoadSteps(new LevelLoadContext()).isEmpty());
        assertTrue(profile.levelTeardownSteps().isEmpty());
        assertTrue(profile.perTestResetSteps().isEmpty());
        assertTrue(profile.postTeardownFixups().isEmpty());
    }
}

