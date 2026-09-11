package com.openggf.game.sonic2.events;

import com.openggf.audio.AudioManager;
import com.openggf.camera.Camera;
import com.openggf.game.GameStateManager;
import com.openggf.game.sonic2.audio.Sonic2Music;
import com.openggf.game.sonic2.constants.Sonic2Constants;
import com.openggf.game.sonic2.constants.Sonic2ObjectIds;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.sprites.managers.SpriteManager;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestSonic2OOZEvents {

    @Test
    void act2BossPathLocksArenaLoadsPlcAndSpawnsObj55AfterDelay() {
        Camera camera = new Camera();
        camera.setX((short) 0x2880);
        camera.setY((short) 0x01D8);
        AudioManager audio = mock(AudioManager.class);
        SpriteManager sprites = mock(SpriteManager.class);
        when(sprites.getSidekicks()).thenReturn(List.of());
        GameStateManager gameState = new GameStateManager();
        RecordingOOZEvents events = new RecordingOOZEvents(camera, audio, gameState, sprites);

        events.init(1);
        events.update(1, 0);
        assertEquals(2, events.getEventRoutine());
        assertEquals(0x2880, camera.getMinX());
        assertEquals(0x01E0, camera.getMaxYTarget());

        events.update(1, 1);
        assertEquals(4, events.getEventRoutine());
        assertEquals(0x2880, camera.getMinX());
        assertEquals(0x28C0, camera.getMaxX());
        assertEquals(8, gameState.getCurrentBossId());
        assertEquals(List.of(Sonic2Constants.PLC_OOZ_BOSS), events.requestedPlcs);
        assertEquals(List.of(Sonic2Constants.PAL_OOZ_BOSS_ADDR), events.requestedPalettes);
        verify(audio).fadeOutMusic();

        for (int i = 0; i < 0x59; i++) {
            events.update(1, 2 + i);
        }
        assertEquals(0, events.spawnedObjects.size());

        events.update(1, 0x60);
        assertEquals(6, events.getEventRoutine());
        assertEquals(0x01D8, camera.getMinY());
        assertEquals(1, events.spawnedObjects.size());
        assertEquals(Sonic2ObjectIds.OOZ_BOSS, events.spawnedObjects.get(0).getSpawn().objectId());
        assertInstanceOf(com.openggf.game.sonic2.objects.bosses.Sonic2OOZBossInstance.class,
                events.spawnedObjects.get(0));
        verify(audio).playMusic(Sonic2Music.BOSS.id);
    }

    private static final class RecordingOOZEvents extends Sonic2OOZEvents {
        private final Camera camera;
        private final AudioManager audio;
        private final GameStateManager gameState;
        private final SpriteManager sprites;
        private final List<Integer> requestedPlcs = new ArrayList<>();
        private final List<Integer> requestedPalettes = new ArrayList<>();
        private final List<ObjectInstance> spawnedObjects = new ArrayList<>();

        private RecordingOOZEvents(Camera camera, AudioManager audio, GameStateManager gameState,
                                   SpriteManager sprites) {
            this.camera = camera;
            this.audio = audio;
            this.gameState = gameState;
            this.sprites = sprites;
        }

        @Override
        protected Camera camera() {
            return camera;
        }

        @Override
        protected AudioManager audio() {
            return audio;
        }

        @Override
        protected GameStateManager gameState() {
            return gameState;
        }

        @Override
        protected SpriteManager spriteManager() {
            return sprites;
        }

        @Override
        protected boolean requestSonic2Plc(int plcId) {
            requestedPlcs.add(plcId);
            return true;
        }

        @Override
        protected void loadOOZBossPalette() {
            requestedPalettes.add(Sonic2Constants.PAL_OOZ_BOSS_ADDR);
        }

        @Override
        protected void spawnObject(ObjectInstance object) {
            spawnedObjects.add(object);
        }
    }
}
