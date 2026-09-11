package com.openggf.level.objects;

import com.openggf.game.CrossGameFeatureProvider;
import com.openggf.game.GameModule;
import com.openggf.game.InstaShieldHandle;
import com.openggf.game.PlayableEntity;
import com.openggf.game.PowerUpObject;
import com.openggf.game.PowerUpSpawner;
import com.openggf.game.ShieldType;
import com.openggf.game.rules.GameRules;
import com.openggf.game.rules.PowerUpRules;
import com.openggf.level.WaterSystem;
import com.openggf.physics.Direction;
import com.openggf.sprites.managers.SpindashDustController;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.sprites.render.PlayerSpriteRenderer;

import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.logging.Logger;

/**
 * Default implementation of {@link PowerUpSpawner} that creates concrete
 * power-up objects and registers them with the {@link ObjectManager}.
 * <p>
 * Game-specific visuals (S3K elemental shields and insta-shield, the S1 LZ
 * splash, the S3K invincibility-stars subclass) are obtained from the active
 * {@link GameModule} factories, and slot placement divergences (S1's fixed
 * shield slot, S2/S3K's fixed dust-object splash) are gated through
 * {@link PowerUpRules} flags. This class never names a concrete Sonic object
 * class.
 */
public class DefaultPowerUpSpawner implements PowerUpSpawner {

    private static final Logger LOGGER = Logger.getLogger(DefaultPowerUpSpawner.class.getName());

    private final ObjectManager objectManager;
    private ObjectServices cachedServices;

    public DefaultPowerUpSpawner(ObjectManager objectManager) {
        this.objectManager = objectManager;
    }

    @Override
    public PowerUpObject spawnShield(PlayableEntity player, ShieldType type) {
        ShieldObjectInstance shield;
        BiFunction<AbstractPlayableSprite, ShieldType, ShieldObjectInstance> factory = shieldFactory();
        if (player instanceof AbstractPlayableSprite aps && factory != null) {
            shield = constructWithServices(() -> factory.apply(aps, type));
        } else {
            // Non-elemental fallback when player is not AbstractPlayableSprite
            // or no game module is active to supply elemental variants.
            shield = constructWithServices(() -> new ShieldObjectInstance(player));
        }
        addPowerUpObject(shield);
        return shield;
    }

    @Override
    public PowerUpObject spawnInvincibilityStars(PlayableEntity player) {
        GameModule module = gameModule();
        AbstractObjectInstance stars;
        if (module != null) {
            stars = constructWithServices(() -> module.getInvincibilityStarsFactory().apply(player));
        } else {
            stars = constructWithServices(() -> new InvincibilityStarsObjectInstance(player));
        }
        addPowerUpObject(stars);
        return (PowerUpObject) stars;
    }

    @Override
    public InstaShieldHandle createInstaShield(PlayableEntity player) {
        if (!(player instanceof AbstractPlayableSprite aps)) {
            LOGGER.warning("createInstaShield called with non-AbstractPlayableSprite");
            return null;
        }
        Function<AbstractPlayableSprite, AbstractObjectInstance> factory = instaShieldFactory();
        if (factory == null) {
            LOGGER.warning("createInstaShield called for a game module without an insta-shield object");
            return null;
        }
        AbstractObjectInstance instaShield = constructWithServices(() -> factory.apply(aps));
        if (!(instaShield instanceof InstaShieldHandle handle)) {
            LOGGER.warning("insta-shield factory returned an object that is not an InstaShieldHandle");
            return null;
        }
        return handle;
    }

    @Override
    public void registerObject(PowerUpObject obj) {
        if (obj instanceof ObjectInstance oi) {
            if (oi instanceof AbstractObjectInstance aoi) {
                // registerObject() is used for persistent visuals that survive an
                // ObjectManager rebuild. Their old slot belongs to the previous
                // manager and must be dropped before the new manager allocates one.
                ObjectLifetimeOps.clearPreviousManagerSlot(aoi);
            }
            addPowerUpObject(oi);
        }
    }

    @Override
    public void spawnSplash(PlayableEntity player) {
        if (objectManager == null) {
            return;
        }

        ObjectServices services = objectServices();
        if (services == null) {
            return;
        }

        var level = services.currentLevel();
        if (level == null) {
            return;
        }

        // The splash sits on the ROM's own water line: S1 Spla_Display is
        // `move.w (v_waterpos1).w,obY(a0)` (docs/s1disasm/_incObj/08 LZ Water
        // Splash.asm:29) and S2 Obj08_MdSplash is
        // `move.w (Water_Level_1).w,y_pos(a0)` (docs/s2disasm/s2.asm:42758).
        // getGameplayWaterLevelY is that value in all three games; the visual
        // accessor is only equal to it in S1 and S3K, because S2 centres the CPZ
        // bob around zero for rendering (`oscillation - 8`) rather than using the
        // ROM's `oscillation >> 1`, so reading it here put the CPZ2 splash on a
        // line the ROM never computes.
        WaterSystem waterSystem = services.waterSystem();
        if (waterSystem == null) {
            return;
        }
        int waterY = waterSystem.getGameplayWaterLevelY(level.getZoneIndex(), services.currentAct());

        // S2/S3K: use dust/splash renderer from SpindashDustController
        if (player instanceof AbstractPlayableSprite aps) {
            PowerUpRules rules = powerUpRulesFor(aps);
            if (rules != null && rules.waterSplashUsesFixedDustObject()) {
                // S2/S3K write the water splash animation into the existing
                // Sonic_Dust/Dust object, not a FindFreeObj slot. Consuming a
                // normal ObjectManager slot here changes S3K CNZ Load_Sprites
                // pressure (docs/s2disasm/s2.asm:36102,36132;
                // docs/skdisasm/sonic3k.asm:22241,22281). Drive the splash through
                // the fixed dust controller so it stays visible without a slot.
                SpindashDustController fixedDust = aps.getSpindashDustController();
                if (fixedDust != null && fixedDust.getRenderer() != null) {
                    boolean facingLeft = player.getDirection() == Direction.LEFT;
                    fixedDust.triggerSplash(player.getCentreX(), waterY, facingLeft);
                }
                return;
            }
            SpindashDustController dustController = aps.getSpindashDustController();
            if (dustController != null && dustController.getRenderer() != null) {
                PlayerSpriteRenderer renderer = dustController.getRenderer();
                boolean facingLeft = player.getDirection() == Direction.LEFT;
                var splash = new SplashObjectInstance(
                        player.getCentreX(), waterY, renderer, facingLeft);
                addWaterSplashObject(splash);
                return;
            }
        }

        // Games whose splash is a level object (S1 LZ splash, Object 0x08)
        // supply it through their module; games that draw the splash through
        // the fixed dust object returned above and have no factory here.
        GameModule module = gameModule();
        BiFunction<Integer, Integer, AbstractObjectInstance> splashFactory =
                module != null ? module.getWaterSplashFactory() : null;
        if (splashFactory == null) {
            return;
        }
        addWaterSplashObject(splashFactory.apply((int) player.getCentreX(), waterY));
    }

    /**
     * Places the water-entry splash in the SST the game's ROM owns for it.
     *
     * <p>Games whose splash lives in a fixed SST outside the level-object pool
     * never scan for a free slot, so allocating one from the dynamic pool would
     * displace every later level object by one slot -- and SST order is
     * execution order, so a displaced object's routine runs on the wrong side of
     * its neighbours. {@code waterSplashFixedSlotIndex < 0} keeps the ordinary
     * dynamic allocation for games that really do allocate.
     */
    private void addWaterSplashObject(ObjectInstance splash) {
        PowerUpRules rules = fixedSlotRules();
        int fixedSlot = rules != null ? rules.waterSplashFixedSlotIndex() : -1;
        if (fixedSlot >= 0) {
            // Sonic 1 loads the splash with `move.b #id_Splash,(v_splash).w`
            // (docs/s1disasm/_incObj/01 Sonic.asm:274,299) -- a single id byte
            // into one dedicated SST. When a splash is already running there
            // the byte already holds id_Splash, so the write is inert: the
            // existing object keeps its routine and animation, and is neither
            // restarted nor duplicated. Sonic bobbing across the surface
            // therefore produces one splash, not one per crossing.
            //
            // The lz1_completerun recording shows exactly that -- slot 12 holds
            // a single object at routine $02 for frames 11934-11948 and only
            // then advances to $04 (Spla_Delete). Without this the engine added
            // a second and third instance carrying slot index 12, which no ROM
            // SST can hold.
            //
            // Scoped to the splash deliberately. The other reserved-slot
            // callers (shield, invincibility stars, the end card, the AIZ
            // miniboss) hand the object back to a caller that keeps and uses
            // the reference, so silently dropping it there leaves a live but
            // unwired instance; their ROM write semantics have not been
            // established here.
            if (liveObjectInSlot(fixedSlot)) {
                return;
            }
            ObjectLifetimeOps.addDynamicAtReservedSlot(objectManager, splash, fixedSlot);
            return;
        }
        objectManager.addDynamicObject(splash);
    }

    /**
     * True when a live (non-destroyed) object already occupies {@code slot}.
     * Fixed SST occupants are constructed programmatically and carry no
     * {@link com.openggf.level.objects.ObjectSpawn}, so this cannot reuse the
     * spawn-backed dynamic-slot occupancy scan.
     */
    private boolean liveObjectInSlot(int slot) {
        for (ObjectInstance instance : objectManager.getActiveObjects()) {
            if (instance instanceof AbstractObjectInstance aoi
                    && !instance.isDestroyed()
                    && aoi.getSlotIndex() == slot) {
                return true;
            }
        }
        return false;
    }

    private PowerUpRules powerUpRulesFor(AbstractPlayableSprite sprite) {
        if (sprite == null) {
            return null;
        }
        GameRules rules = sprite.getGameRules();
        if (rules != null && rules.powerUp() != null) {
            return rules.powerUp();
        }
        return null;
    }

    /**
     * Shield objects come from the cross-game donor when one is active (its
     * hybrid capability rules are what enable elemental shields on a host that
     * has none), otherwise from the host module.
     */
    private BiFunction<AbstractPlayableSprite, ShieldType, ShieldObjectInstance> shieldFactory() {
        CrossGameFeatureProvider crossGame = crossGameFeatures();
        BiFunction<AbstractPlayableSprite, ShieldType, ShieldObjectInstance> donor =
                crossGame != null ? crossGame.getDonorShieldFactory() : null;
        if (donor != null) {
            return donor;
        }
        GameModule module = gameModule();
        return module != null ? module.getShieldFactory() : null;
    }

    private Function<AbstractPlayableSprite, AbstractObjectInstance> instaShieldFactory() {
        CrossGameFeatureProvider crossGame = crossGameFeatures();
        Function<AbstractPlayableSprite, AbstractObjectInstance> donor =
                crossGame != null ? crossGame.getDonorInstaShieldFactory() : null;
        if (donor != null) {
            return donor;
        }
        GameModule module = gameModule();
        return module != null ? module.getInstaShieldFactory() : null;
    }

    private CrossGameFeatureProvider crossGameFeatures() {
        ObjectServices services = objectServices();
        return services != null ? services.crossGameFeatures() : null;
    }

    private GameModule gameModule() {
        ObjectServices services = objectServices();
        return services != null ? services.gameModule() : null;
    }

    private ObjectServices objectServices() {
        if (cachedServices == null && objectManager != null) {
            cachedServices = objectManager.services();
        }
        return cachedServices;
    }

    private <T extends AbstractObjectInstance> T constructWithServices(Supplier<T> factory) {
        ObjectServices services = objectServices();
        if (services == null) {
            return factory.get();
        }
        return ObjectConstructionContext.construct(services, factory);
    }

    private void addPowerUpObject(ObjectInstance object) {
        if (objectManager == null || object == null) {
            return;
        }
        if (usesAuxiliaryDynamicObjectSpace(object)) {
            objectManager.addAuxiliaryDynamicObject(object);
            return;
        }
        // Rewind: if a captured entry is pending for this dynamic class (Shield/Stars),
        // honour both the captured slot and the captured per-object field surface
        // so the post-restore re-spawn lands on the same slot the reference run had
        // and reapplies state like animation cursors that the constructor zeros.
        com.openggf.game.rewind.snapshot.ObjectManagerSnapshot.DynamicObjectEntry restored =
                consumePendingRestoredEntry(object);
        if (restored != null) {
            if (restored.objectId() != null) {
                objectManager.addRestoredDynamicObjectAtSlot(
                        object, restored.slotIndex(), restored.objectId());
            } else {
                // Legacy/injected entries predate captured dynamic identity.
                ObjectLifetimeOps.addDynamicAtReservedSlot(
                        objectManager, object, restored.slotIndex());
            }
            if (object instanceof AbstractObjectInstance aoi) {
                aoi.restoreRewindState(restored.state());
            }
            return;
        }
        int fixedSlot = fixedPowerUpSlotIndex(object);
        if (fixedSlot >= 0) {
            ObjectLifetimeOps.addDynamicAtReservedSlot(objectManager, object, fixedSlot);
            return;
        }
        objectManager.addDynamicObject(object);
    }

    private boolean usesAuxiliaryDynamicObjectSpace(ObjectInstance object) {
        if (!(object instanceof InstaShieldHandle)
                || !(object instanceof ShieldObjectInstance shield)
                || !(shield.getPlayer() instanceof AbstractPlayableSprite owner)) {
            return false;
        }
        return owner.isCpuControlled();
    }

    private com.openggf.game.rewind.snapshot.ObjectManagerSnapshot.DynamicObjectEntry
            consumePendingRestoredEntry(ObjectInstance object) {
        if (object instanceof ShieldObjectInstance) {
            ShieldObjectInstance shield = (ShieldObjectInstance) object;
            return objectManager.consumePendingPlayerBoundEntry(
                    ShieldObjectInstance.class,
                    entry -> object.getClass().getName().equals(entry.className())
                            && entry.playerOwner() == shield.getPlayer());
        }
        if (object instanceof PowerUpObject powerUp && powerUp.isInvincibilityStars()) {
            return objectManager.consumePendingPlayerBoundEntry(
                    InvincibilityStarsObjectInstance.class,
                    entry -> object.getClass().getName().equals(entry.className())
                            && entry.playerOwner() == powerUp.boundPlayer());
        }
        return null;
    }

    private int fixedPowerUpSlotIndex(ObjectInstance object) {
        PowerUpRules rules = fixedSlotRules();
        if (rules == null) {
            return -1;
        }
        if (object instanceof ShieldObjectInstance) {
            return rules.shieldObjectFixedSlotIndex();
        }
        if (object instanceof PowerUpObject powerUp && powerUp.isInvincibilityStars()) {
            return rules.invincibilityStarsFixedSlotIndex();
        }
        return -1;
    }

    private PowerUpRules fixedSlotRules() {
        ObjectServices services = objectServices();
        if (services == null) {
            return null;
        }
        GameModule module = services.gameModule();
        if (module == null) {
            return null;
        }
        GameRules rules = gameRulesFor(module);
        return rules != null ? rules.powerUp() : null;
    }

    private GameRules gameRulesFor(GameModule module) {
        try {
            GameRules rules = module.getRules();
            if (rules != null) {
                return rules;
            }
        } catch (IllegalArgumentException | IllegalStateException ignored) {
        }
        return null;
    }
}
