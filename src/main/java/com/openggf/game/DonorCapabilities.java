package com.openggf.game;

import com.openggf.data.PlayerSpriteArtProvider;
import com.openggf.data.RomByteReader;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Set;

/**
 * Declares what a game can export when acting as a sprite donor in cross-game
 * feature donation.
 *
 * <p>When a host game borrows player art or animation data from a donor game
 * (e.g. Sonic 2 borrowing insta-shield art from Sonic 3&K), the donor's
 * {@code GameModule} returns a {@code DonorCapabilities} instance that
 * advertises which characters, moves, and art assets it can supply.</p>
 *
 * <p>Each game module that supports donation implements this interface and
 * returns it from {@link GameModule#getDonorCapabilities()}. Games that
 * cannot act as donors return {@code null} from that method.</p>
 */
public interface DonorCapabilities {

    /**
     * Returns the set of playable characters this game can provide art and
     * physics data for.
     *
     * @return non-null set of {@link PlayerCharacter} values supported by this donor
     */
    Set<PlayerCharacter> getPlayableCharacters();

    /**
     * Returns whether this game's player sprite sheet includes a spindash
     * charging animation that can be donated to other games.
     *
     * @return {@code true} if a spindash animation is available
     */
    boolean hasSpindash();

    /**
     * Returns whether this game's player sprite sheet includes a Super Sonic
     * transformation animation sequence.
     *
     * @return {@code true} if a super transformation animation is available
     */
    boolean hasSuperTransform();

    /**
     * Returns whether this game supports hyper transformation (Hyper Sonic /
     * Hyper Knuckles etc.), as seen in Sonic 3&K.
     *
     * @return {@code true} if hyper transformation animations are available
     */
    boolean hasHyperTransform();

    /**
     * Returns whether this game's mechanics include the insta-shield ability,
     * enabling its hitbox-expansion frames to be donated to host games.
     *
     * @return {@code true} if the insta-shield ability and associated art are present
     */
    boolean hasInstaShield();

    /**
     * Returns whether this game's Tails implementation includes the flight ability.
     *
     * @return {@code true} if Tails flight mechanics can be donated
     */
    boolean hasTailsFlight();

    /**
     * Returns whether this game supports elemental shields (fire, lightning,
     * bubble) introduced in Sonic 3&K, including their ability animations.
     *
     * @return {@code true} if elemental shield animations are available
     */
    boolean hasElementalShields();

    /**
     * Returns whether this game natively includes a sidekick character
     * (e.g. Tails), including that character's art and physics data.
     *
     * @return {@code true} if sidekick art and data can be donated
     */
    boolean hasSidekick();

    /**
     * Returns a map of animation fallback substitutions for this donor game.
     *
     * <p>When the host game requests an animation that does not exist in this
     * donor, the returned map may specify a substitute canonical animation to
     * use instead. An empty map means no fallbacks are defined and missing
     * animations should be handled by the host.</p>
     *
     * @return non-null map from requested animation to fallback animation
     */
    Map<CanonicalAnimation, CanonicalAnimation> getAnimationFallbacks();

    /**
     * Builds a fallback map seeded with identity entries for all native animations,
     * then applies the given overrides. Shared across all game module implementations.
     */
    static <E extends Enum<E> & AnimationId> Map<CanonicalAnimation, CanonicalAnimation> buildFallbackMap(
            E[] animValues, java.util.function.Function<E, CanonicalAnimation> toCanonical,
            Map<CanonicalAnimation, CanonicalAnimation> overrides) {
        Map<CanonicalAnimation, CanonicalAnimation> map = new EnumMap<>(CanonicalAnimation.class);
        for (E anim : animValues) {
            CanonicalAnimation canonical = toCanonical.apply(anim);
            if (canonical != null) {
                map.put(canonical, canonical);
            }
        }
        map.putAll(overrides);
        return Collections.unmodifiableMap(map);
    }

    /**
     * Resolves a {@link CanonicalAnimation} to this donor game's native integer
     * animation ID.
     *
     * <p>Returns {@code -1} if the canonical animation is not supported by
     * this donor game (e.g. insta-shield requested from a Sonic 1 donor).</p>
     *
     * @param canonical the cross-game animation identifier to resolve
     * @return the native integer animation ID, or {@code -1} if unsupported
     */
    int resolveNativeId(CanonicalAnimation canonical);

    /**
     * Returns a {@link PlayerSpriteArtProvider} loaded from the donor game's
     * ROM, ready to supply art tiles and sprite mappings to the host game.
     *
     * <p>May return {@code null} if the donor cannot provide art (e.g. ROM is
     * absent or the requested character code is not supported).</p>
     *
     * @param reader a {@link RomByteReader} backed by the donor game's ROM data
     * @return a provider for player sprite art, or {@code null} if unavailable
     */
    PlayerSpriteArtProvider getPlayerArtProvider(RomByteReader reader);
}
