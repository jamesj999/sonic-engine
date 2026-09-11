package com.openggf.game;

import com.openggf.audio.GameAudioProfile;
import com.openggf.data.PlayerSpriteArtProvider;
import com.openggf.data.RomByteReader;
import com.openggf.data.SpindashDustArtProvider;
import com.openggf.level.Palette;
import com.openggf.sprites.art.SpriteArtSet;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.sprites.playable.SuperStateController;

import java.io.IOException;

/**
 * Module-owned provider for assets and behavior a game can donate to a
 * cross-game host.
 */
public interface CrossGameDonorProvider {
    DonorCapabilities getDonorCapabilities();

    PlayerSpriteArtProvider createPlayerArtProvider(RomByteReader reader);

    default SpindashDustArtProvider createSpindashDustArtProvider(RomByteReader reader) {
        return null;
    }

    GameAudioProfile getAudioProfile();

    default Palette loadCharacterPalette(RomByteReader reader, String characterCode) {
        return null;
    }

    /**
     * Loads the donor game's native underwater character palette, when it has
     * one distinct from the ordinary character palette.
     */
    default Palette loadUnderwaterCharacterPalette(RomByteReader reader, String characterCode) {
        return null;
    }

    default Palette loadHostCompatiblePalette(RomByteReader reader, String characterCode) {
        return null;
    }

    default SuperStateController createSuperStateController(AbstractPlayableSprite player) {
        return null;
    }

    default boolean hasSeparateTailsTailArt() {
        return false;
    }

    default SpriteArtSet loadTailsTailArt(RomByteReader reader) throws IOException {
        return SpriteArtSet.EMPTY;
    }

    default SpriteArtSet loadInstaShieldArt(RomByteReader reader) throws IOException {
        return SpriteArtSet.EMPTY;
    }

    /**
     * Returns the donor's shield object factory (see
     * {@link GameModule#getShieldFactory()}) so a host game whose hybrid rules
     * enable the donor's elemental shields can spawn the donor's objects, or
     * {@code null} when the donor has no shield variants to contribute.
     */
    default java.util.function.BiFunction<AbstractPlayableSprite, ShieldType,
            com.openggf.level.objects.ShieldObjectInstance> getShieldFactory() {
        return null;
    }

    /**
     * Returns the donor's insta-shield object factory (see
     * {@link GameModule#getInstaShieldFactory()}), or {@code null} when the
     * donor has no insta-shield object.
     */
    default java.util.function.Function<AbstractPlayableSprite,
            com.openggf.level.objects.AbstractObjectInstance> getInstaShieldFactory() {
        return null;
    }
}
