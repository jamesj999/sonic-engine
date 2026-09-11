package com.openggf.game.sonic3k;

import com.openggf.game.PowerUpObject;
import com.openggf.level.InitialFixedSstDispatcher;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectManager;
import com.openggf.sprites.managers.ProcessSpritesEpoch;
import com.openggf.sprites.managers.SpriteManager;
import com.openggf.sprites.playable.AbstractPlayableSprite;

import java.util.List;

/**
 * Explicit inventory adapter for S3K post-dynamic fixed SST slots 94-109.
 *
 * <p>The slot labels and order are defined at
 * docs/skdisasm/sonic3k.constants.asm:309-323. Empty visits below are
 * intentional fresh-level invariants, not omitted work. Absolute slot 93 is
 * the first, empty {@code Level_object_RAM} SST, but the ROM's allocation and
 * transition loops include it in the managed 4-93 window.
 */
final class S3kInitialFixedSstDispatcher implements InitialFixedSstDispatcher {
    private final Sonic3kLevelEventManager levelEvents;
    private final SpriteManager sprites;
    private final ObjectManager objects;
    private final InitialWaveSplashSstOwner waveSplash;
    private final AbstractPlayableSprite p1;
    private final AbstractPlayableSprite p2;
    private final ObjectInstance p1Shield;
    private final ObjectInstance p1Stars;
    private final ObjectInstance p2Shield;
    private final ObjectInstance p2Stars;

    S3kInitialFixedSstDispatcher(
            Sonic3kLevelEventManager levelEvents,
            SpriteManager sprites,
            ObjectManager objects,
            InitialWaveSplashSstOwner waveSplash) {
        this.levelEvents = levelEvents;
        this.sprites = sprites;
        this.objects = objects;
        this.waveSplash = waveSplash;
        this.p1 = sprites != null ? sprites.getMainPlayable() : null;
        List<AbstractPlayableSprite> sidekicks =
                sprites != null ? sprites.getRegisteredSidekicks() : List.of();
        this.p2 = sidekicks.isEmpty() ? null : sidekicks.getFirst();
        this.p1Shield = fixedObject(selectP1Shield(p1));
        this.p1Stars = fixedObject(p1 != null ? p1.getInvincibilityObject() : null);
        this.p2Shield = fixedObject(selectP1Shield(p2));
        this.p2Stars = fixedObject(p2 != null ? p2.getInvincibilityObject() : null);
    }

    @Override
    public void onInitialScopeAcquired() {
        objects.registerInitialFixedDispatchObject(p1Shield);
        objects.registerInitialFixedDispatchObject(p1Stars);
        objects.registerInitialFixedDispatchObject(p2Shield);
        objects.registerInitialFixedDispatchObject(p2Stars);
    }

    @Override
    public void processPostDynamicFixedSlots(ProcessSpritesEpoch epoch) {
        levelEvents.processInitialFixedAirSlot(0, p1);       // 94 Breathing_bubbles
        levelEvents.processInitialFixedAirSlot(1, p2);       // 95 Breathing_bubbles_P2
        empty(96);                                           // Tails_tails_2P
        sprites.processInitialTailsFixedSlot();                // 97 Tails_tails
        sprites.processInitialDustFixedSlot(0);                // 98 Dust
        sprites.processInitialDustFixedSlot(1);                // 99 Dust_P2
        objects.processInitialFixedDispatchObject(p1Shield); // 100 Shield
        processRegisteredOrEmpty(101, p2Shield);              // Shield_P2
        objects.processInitialFixedDispatchObject(p1Stars);  // 102-105 aggregate stars owner
        continuationOrEmpty(103, p1Stars);
        continuationOrEmpty(104, p1Stars);
        continuationOrEmpty(105, p1Stars);
        processRegisteredOrEmpty(106, p2Stars);               // 106-108 aggregate P2 stars
        continuationOrEmpty(107, p2Stars);
        continuationOrEmpty(108, p2Stars);
        if (waveSplash != null && waveSplash.isRegistered()) {
            waveSplash.processInitialWaveSplash(epoch);       // 109 Wave_Splash
        } else {
            empty(109);
        }
    }

    private static PowerUpObject selectP1Shield(AbstractPlayableSprite player) {
        if (player == null) {
            return null;
        }
        PowerUpObject liveShield = player.getShieldObject();
        if (liveShield != null && !liveShield.isDestroyed()) {
            return liveShield;
        }
        return player.getInstaShieldObject() instanceof PowerUpObject powerUp
                ? powerUp
                : null;
    }

    private static ObjectInstance fixedObject(Object candidate) {
        return candidate instanceof ObjectInstance instance ? instance : null;
    }

    private static void empty(int slot) {
        if (slot < 93 || slot > 109) {
            throw new IllegalArgumentException("fixed SST slot outside inventory: " + slot);
        }
    }

    private void processRegisteredOrEmpty(int slot, ObjectInstance owner) {
        if (owner == null) {
            empty(slot);
        } else {
            objects.processInitialFixedDispatchObject(owner);
        }
    }

    private static void continuationOrEmpty(int slot, ObjectInstance aggregateOwner) {
        // The engine represents all four native star records with one aggregate
        // visual owner. Its single update at the first slot advances every star;
        // the remaining records are explicit continuation visits.
        empty(slot);
    }
}
