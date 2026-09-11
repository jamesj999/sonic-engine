package com.openggf.sprites.playable;

import com.openggf.audio.AudioManager;
import com.openggf.camera.Camera;
import com.openggf.game.CanonicalAnimation;
import com.openggf.game.CrossGameFeatureProvider;
import com.openggf.game.GameModule;
import com.openggf.game.GameRng;
import com.openggf.game.GameServices;
import com.openggf.game.GameStateManager;
import com.openggf.game.LevelEventProvider;
import com.openggf.game.LevelState;
import com.openggf.game.session.SessionManager;
import com.openggf.game.session.WorldSession;
import com.openggf.level.LevelManager;
import com.openggf.level.WaterSystem;
import com.openggf.physics.CollisionSystem;
import com.openggf.timer.TimerManager;
import com.openggf.sprites.managers.SpriteManager;

final class PlayableSpriteRuntimeServices {
        private PlayableSpriteRuntimeServices() {
        }

        static Camera camera() { return GameServices.camera(); }
        static LevelManager level() { return GameServices.level(); }
        static LevelManager levelOrNull() { return GameServices.levelOrNull(); }
        static GameModule currentOrBootstrapGameModule() { return GameServices.currentOrBootstrapGameModule(); }
        static CrossGameFeatureProvider crossGameFeatures() { return GameServices.crossGameFeatures(); }
        /**
         * Level events belong to the active world session — nothing outside one
         * can have a boss fight running — so this deliberately does not fall
         * back to the bootstrap module.
         */
        static LevelEventProvider levelEventsOrNull() {
                WorldSession world = SessionManager.getCurrentWorldSession();
                GameModule module = world != null ? world.getGameModule() : null;
                return module != null ? module.getLevelEventProvider() : null;
        }
        static LevelState levelState(LevelManager levelManager) {
                return levelManager != null ? levelManager.getLevelGamestate() : null;
        }
        static int resolveAnimationId(GameModule module, CanonicalAnimation animation) {
                return module != null ? module.resolveAnimationId(animation) : -1;
        }
        static TimerManager timers() { return GameServices.timers(); }
        static GameStateManager gameState() { return GameServices.gameState(); }
        static GameStateManager gameStateOrNull() { return GameServices.gameStateOrNull(); }
        static CollisionSystem collision() { return GameServices.collision(); }
        static CollisionSystem collisionOrNull() { return GameServices.collisionOrNull(); }
        static AudioManager audio() { return GameServices.audio(); }
        static GameRng rng() { return GameServices.rng(); }
        static GameRng rngOrNull() { return GameServices.rngOrNull(); }
        static WaterSystem water() { return GameServices.water(); }
        static SpriteManager spritesOrNull() { return GameServices.spritesOrNull(); }
        static int gameplayFrameCounter() {
                SpriteManager sprites = spritesOrNull();
                return sprites != null ? sprites.getFrameCounter() : 0;
        }
}
