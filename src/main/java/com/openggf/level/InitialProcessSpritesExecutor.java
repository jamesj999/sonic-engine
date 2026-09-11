package com.openggf.level;

import com.openggf.camera.Camera;
import com.openggf.game.GameModule;
import com.openggf.game.ZoneFeatureProvider;
import com.openggf.level.objects.InitialObjectDispatchScope;
import com.openggf.level.objects.ObjectManager;
import com.openggf.sprites.managers.ProcessSpritesEpoch;
import com.openggf.sprites.managers.SpriteManager;

/**
 * Adapts runtime owners to the ROM-ordered initial {@code Process_Sprites}
 * coordinator without making {@link LevelManager} own the dispatch details.
 */
final class InitialProcessSpritesExecutor {
    void execute(
            GameModule gameModule,
            SpriteManager spriteManager,
            ObjectManager objectManager,
            Camera camera,
            ZoneFeatureProvider zoneFeatureProvider,
            int frameCounter) {
        InitialFixedSstDispatcher fixed =
                gameModule.createInitialFixedSstDispatcher(
                        spriteManager, objectManager, zoneFeatureProvider);
        InitialDynamicSstDispatcher dynamic = dynamicDispatcher(objectManager, spriteManager, camera);
        CollisionListSstDispatcher collision = collisionDispatcher(objectManager);
        int objectOrdinal = objectManager.getFrameCounter() + 1;
        new InitialProcessSpritesCoordinator().execute(new InitialProcessSpritesContext(
                new InitialProcessSpritesStages(dynamic, spriteManager, collision, fixed),
                new ProcessSpritesEpoch(frameCounter, objectOrdinal, false)));
    }

    private static InitialDynamicSstDispatcher dynamicDispatcher(
            ObjectManager objectManager, SpriteManager spriteManager, Camera camera) {
        return new InitialDynamicSstDispatcher() {
            private InitialObjectDispatchScope scope;

            @Override
            public InitialObjectDispatchScope begin(ProcessSpritesEpoch epoch) {
                scope = objectManager.beginInitialProcessSprites(
                        camera.getX(),
                        spriteManager.getMainPlayable(),
                        spriteManager.getSidekicks());
                return scope;
            }

            @Override
            public void loadSprites() {
                objectManager.loadInitialDynamicSlots(scope);
            }

            @Override
            public void processAbsoluteDynamicSlot3() {
                objectManager.processInitialAbsoluteDynamicSlot3(scope);
            }

            @Override
            public void processManagedDynamicSlots4Through93() {
                objectManager.processInitialDynamicSlots(scope);
            }
        };
    }

    private static CollisionListSstDispatcher collisionDispatcher(ObjectManager objectManager) {
        return new CollisionListSstDispatcher() {
            @Override
            public void freezePreviousReadView() {
                objectManager.freezeInitialCollisionResponseReadView();
            }

            @Override
            public void resetCurrentBuild() {
                objectManager.resetInitialCollisionResponseBuild();
            }

            @Override
            public void markDynamicBuildComplete() {
                objectManager.markInitialDynamicCollisionBuildComplete();
            }

            @Override
            public void captureCompletedBuild() {
                objectManager.captureInitialCollisionResponseBuild();
            }
        };
    }
}
