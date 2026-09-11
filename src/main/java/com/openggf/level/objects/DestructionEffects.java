package com.openggf.level.objects;

import com.openggf.game.PlayableEntity;

/**
 * Centralised badnik destruction sequence shared across S1, S2, and S3K.
 * <p>
 * Each game configures a {@link DestructionConfig} capturing what varies
 * (SFX, animal spawn, respawn tracking, points popup factory) while the
 * core sequence (explosion, score chain, SFX) stays in one place.
 */
public final class DestructionEffects {

    private DestructionEffects() {
        // utility class
    }

    /**
     * Functional interface for game-specific points popup creation.
     * S2 creates {@code PointsObjectInstance}, S1 creates {@code Sonic1PointsObjectInstance},
     * S3K passes {@code null} (no popup).
     */
    @FunctionalInterface
    public interface PointsFactory {
        ObjectInstance create(ObjectSpawn spawn, ObjectServices services, int pointsValue);
    }

    /**
     * Functional interface for game-specific animal creation.
     * Sonic 1 uses the ROM-ported {@code Sonic1AnimalsObjectInstance}, while
     * Sonic 2 and S3K use the shared generic animal object.
     */
    @FunctionalInterface
    public interface AnimalFactory {
        ObjectInstance create(ObjectSpawn spawn, ObjectServices services);
    }

    /**
     * Functional interface for game-specific badnik replacement explosions.
     * Sonic 1 uses a custom ExplosionItem object that later spawns the animal.
     */
    @FunctionalInterface
    public interface ExplosionFactory {
        ObjectInstance create(int x, int y, ObjectServices services, int pointsValue);
    }

    /**
     * Immutable configuration record capturing what varies between games.
     *
     * @param sfxId              explosion SFX ID (game-specific)
     * @param animalFactory      game-specific animal factory, or {@code null} to skip
     * @param useRespawnTracking if true, uses {@code markRemembered} for respawn-tracked spawns;
     *                           if false, always uses {@code removeFromActiveSpawns}
     * @param pointsFactory      factory for the floating points popup, or {@code null} to skip
     * @param explosionFactory   factory for a custom replacement explosion object, or {@code null}
     * @param pointsAllocatedBeforeAnimal if true, allocate the points popup before the animal object
     */
    public record DestructionConfig(
            int sfxId,
            AnimalFactory animalFactory,
            boolean useRespawnTracking,
            PointsFactory pointsFactory,
            ExplosionFactory explosionFactory,
            boolean pointsAllocatedBeforeAnimal
    ) {
    }

    /**
     * Executes the standard badnik destruction sequence:
     * <ol>
     *   <li>Handle respawn tracking (mark remembered or remove from active spawns)</li>
     *   <li>Spawn explosion (inheriting the badnik's slot for ROM parity)</li>
     *   <li>Calculate and award chain score</li>
     *   <li>Optionally let the explosion routine allocate animal/points children</li>
     *   <li>Play explosion SFX</li>
     * </ol>
     *
     * @param x            current X position of the destroyed badnik
     * @param y            current Y position of the destroyed badnik
     * @param spawn        the badnik's original spawn data
     * @param badnikSlot   the SST slot index of the destroyed badnik, or -1 if unknown.
     *                     When &ge; 0, the explosion inherits this slot to match the ROM's
     *                     in-place obID change (see {@link ObjectManager#addDynamicObjectAtSlot}).
     * @param player       the player who destroyed the badnik (may be null)
     * @param services     injectable services handle
     * @param config       game-specific destruction configuration
     */
    public static void destroyBadnik(int x, int y, ObjectSpawn spawn, int badnikSlot,
            PlayableEntity player, ObjectServices services,
            DestructionConfig config) {

        // --- Respawn tracking ---
        var objectManager = services != null ? services.objectManager() : null;
        if (objectManager != null) {
            if (config.useRespawnTracking() && spawn.respawnTracked()) {
                ObjectLifetimeOps.markSpawnRemembered(objectManager, spawn);
            } else {
                ObjectLifetimeOps.removeSpawnFromActive(objectManager, spawn);
            }
        }

        // --- Calculate and award chain score ---
        int pointsValue = 100;
        if (player != null) {
            pointsValue = player.incrementBadnikChain();
            if (services != null) {
                services.gameState().addScore(pointsValue);
            }
        }

        // --- Spawn explosion ---
        // ROM parity: the ROM changes the badnik's obID to ExplosionItem (0x27)
        // in-place, keeping the same SST slot. We replicate this by spawning
        // the replacement explosion at the badnik's slot via addDynamicObjectAtSlot.
        ObjectRenderManager renderManager = services != null ? services.renderManager() : null;
        if (objectManager != null) {
            ObjectInstance explosion = config.explosionFactory() != null
                    ? config.explosionFactory().create(x, y, services, pointsValue)
                    : new ExplosionObjectInstance(
                            0x27,
                            x,
                            y,
                            renderManager,
                            config.animalFactory(),
                            config.pointsFactory(),
                            pointsValue,
                            config.pointsAllocatedBeforeAnimal());
            ObjectLifetimeOps.addReplacementAtTransferredSlot(objectManager, explosion, badnikSlot);
        }

        // --- Play explosion SFX ---
        if (services != null) {
            services.playSfx(config.sfxId());
        }
    }

    /**
     * Backward-compatible overload that allocates a new slot for the explosion.
     * Prefer the 7-arg variant with {@code badnikSlot} for ROM-accurate slot reuse.
     */
    public static void destroyBadnik(int x, int y, ObjectSpawn spawn,
            PlayableEntity player, ObjectServices services,
            DestructionConfig config) {
        destroyBadnik(x, y, spawn, -1, player, services, config);
    }
}
