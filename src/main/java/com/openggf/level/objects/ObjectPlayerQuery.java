package com.openggf.level.objects;

import com.openggf.camera.Camera;
import com.openggf.configuration.SonicConfiguration;
import com.openggf.configuration.SonicConfigurationService;
import com.openggf.game.PlayableEntity;
import com.openggf.game.session.WorldSession;
import com.openggf.sprites.Sprite;
import com.openggf.sprites.managers.SpriteManager;
import com.openggf.sprites.playable.AbstractPlayableSprite;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Transitional player-participation query layer for object code.
 */
public final class ObjectPlayerQuery {
    private final Supplier<? extends PlayableEntity> mainPlayerSource;
    private final Supplier<? extends List<? extends PlayableEntity>> sidekickSource;

    public record NearestPlayerX(PlayableEntity player, int distance) {
    }

    public ObjectPlayerQuery(Supplier<? extends PlayableEntity> mainPlayerSource,
                             Supplier<? extends List<? extends PlayableEntity>> sidekickSource) {
        this.mainPlayerSource = Objects.requireNonNull(mainPlayerSource, "mainPlayerSource");
        this.sidekickSource = Objects.requireNonNull(sidekickSource, "sidekickSource");
    }

    public static ObjectPlayerQuery from(ObjectServices services) {
        Objects.requireNonNull(services, "services");
        return new ObjectPlayerQuery(
                () -> resolveMainPlayer(services),
                services::sidekicks);
    }

    /** Builds the same policy query for non-object runtime code that owns a sprite manager. */
    public static ObjectPlayerQuery from(SpriteManager spriteManager, PlayableEntity mainPlayer) {
        Objects.requireNonNull(spriteManager, "spriteManager");
        return new ObjectPlayerQuery(() -> mainPlayer, spriteManager::getSidekicks);
    }

    public PlayableEntity mainPlayerOrNull() {
        return mainPlayerSource.get();
    }

    public PlayableEntity nativeP2OrNull() {
        List<PlayableEntity> sidekicks = sidekicks();
        return sidekicks.isEmpty() ? null : sidekicks.getFirst();
    }

    public List<PlayableEntity> sidekicks() {
        PlayableEntity main = mainPlayerOrNull();
        List<PlayableEntity> players = uniquePlayers(main, rawSidekicks(), Integer.MAX_VALUE);
        if (!players.isEmpty() && players.getFirst() == main) {
            return List.copyOf(players.subList(1, players.size()));
        }
        return List.copyOf(players);
    }

    public List<PlayableEntity> playersFor(ObjectPlayerParticipationPolicy policy) {
        return playersFor(policy, 0, 0);
    }

    public List<PlayableEntity> playersFor(ObjectPlayerParticipationPolicy policy, int referenceX, int referenceY) {
        Objects.requireNonNull(policy, "policy");
        List<PlayableEntity> ordered = switch (policy) {
            case MAIN_ONLY_NATIVE -> uniquePlayers(mainPlayerOrNull(), List.of(), 0);
            case NATIVE_P1_P2 -> uniquePlayers(mainPlayerOrNull(), rawSidekicks(), 1);
            case MAIN_PLUS_ENGINE_SIDEKICKS_AS_NATIVE_P2_EXTENDED, ALL_ENGINE_PLAYERS ->
                    uniquePlayers(mainPlayerOrNull(), rawSidekicks(), Integer.MAX_VALUE);
            case NEAREST_ENGINE_PLAYER -> nearestEnginePlayer(referenceX, referenceY);
        };
        return List.copyOf(ordered);
    }

    public NearestPlayerX nearestByRomX(ObjectPlayerParticipationPolicy policy, int referenceX) {
        return nearestByRomX(policy, referenceX, player -> true);
    }

    public NearestPlayerX nearestByRomX(ObjectPlayerParticipationPolicy policy,
                                        int referenceX,
                                        Predicate<? super PlayableEntity> playerFilter) {
        Objects.requireNonNull(policy, "policy");
        Objects.requireNonNull(playerFilter, "playerFilter");
        List<PlayableEntity> players = participantsForRomX(policy);
        PlayableEntity nearest = null;
        int nearestDistance = Integer.MAX_VALUE;
        for (PlayableEntity player : players) {
            if (!playerFilter.test(player)) {
                continue;
            }
            int distance = romSignedXDistance(referenceX, player.getCentreX());
            if (distance < nearestDistance) {
                nearest = player;
                nearestDistance = distance;
            }
        }
        return new NearestPlayerX(nearest, nearestDistance);
    }

    private List<PlayableEntity> nearestEnginePlayer(int referenceX, int referenceY) {
        List<PlayableEntity> players = uniquePlayers(mainPlayerOrNull(), rawSidekicks(), Integer.MAX_VALUE);
        PlayableEntity nearest = null;
        long nearestDistance = Long.MAX_VALUE;
        for (PlayableEntity player : players) {
            long dx = (long) player.getCentreX() - referenceX;
            long dy = (long) player.getCentreY() - referenceY;
            long distance = dx * dx + dy * dy;
            if (distance < nearestDistance) {
                nearest = player;
                nearestDistance = distance;
            }
        }
        return nearest == null ? List.of() : List.of(nearest);
    }

    private List<PlayableEntity> participantsForRomX(ObjectPlayerParticipationPolicy policy) {
        return switch (policy) {
            case MAIN_ONLY_NATIVE -> uniquePlayers(mainPlayerOrNull(), List.of(), 0);
            case NATIVE_P1_P2 -> uniquePlayers(mainPlayerOrNull(), rawSidekicks(), 1);
            case MAIN_PLUS_ENGINE_SIDEKICKS_AS_NATIVE_P2_EXTENDED, ALL_ENGINE_PLAYERS, NEAREST_ENGINE_PLAYER ->
                    uniquePlayers(mainPlayerOrNull(), rawSidekicks(), Integer.MAX_VALUE);
        };
    }

    private static int romSignedXDistance(int referenceX, int playerX) {
        int delta = (short) ((referenceX - playerX) & 0xFFFF);
        return Math.abs(delta);
    }

    private List<? extends PlayableEntity> rawSidekicks() {
        List<? extends PlayableEntity> sidekicks = sidekickSource.get();
        return sidekicks != null ? sidekicks : List.of();
    }

    private static List<PlayableEntity> uniquePlayers(PlayableEntity main,
                                                      List<? extends PlayableEntity> sidekicks,
                                                      int sidekickLimit) {
        ArrayList<PlayableEntity> players = new ArrayList<>();
        IdentityHashMap<PlayableEntity, Boolean> seen = new IdentityHashMap<>();
        addIfPresent(players, seen, main);
        int addedSidekicks = 0;
        for (PlayableEntity sidekick : sidekicks) {
            if (addedSidekicks >= sidekickLimit) {
                break;
            }
            if (addIfPresent(players, seen, sidekick)) {
                addedSidekicks++;
            }
        }
        return players;
    }

    private static boolean addIfPresent(List<PlayableEntity> players,
                                        IdentityHashMap<PlayableEntity, Boolean> seen,
                                        PlayableEntity player) {
        if (player == null || seen.containsKey(player)) {
            return false;
        }
        players.add(player);
        seen.put(player, Boolean.TRUE);
        return true;
    }

    private static PlayableEntity resolveMainPlayer(ObjectServices services) {
        SpriteManager spriteManager = services.spriteManager();
        PlayableEntity fromSpriteManager = resolveNamedMainFromSpriteManager(
                spriteManager,
                activeMainCharacterCode(services));
        if (fromSpriteManager == null) {
            fromSpriteManager = resolveFirstNonCpuPlayable(spriteManager);
        }
        if (fromSpriteManager != null) {
            return fromSpriteManager;
        }
        Camera camera = services.camera();
        return camera != null ? camera.getFocusedSprite() : null;
    }

    private static PlayableEntity resolveNamedMainFromSpriteManager(SpriteManager spriteManager, String mainCode) {
        if (spriteManager == null || mainCode == null || mainCode.isBlank()) {
            return null;
        }
        Sprite sprite = spriteManager.getSprite(mainCode);
        if (sprite instanceof AbstractPlayableSprite playable && !playable.isCpuControlled()) {
            return playable;
        }
        return null;
    }

    private static PlayableEntity resolveFirstNonCpuPlayable(SpriteManager spriteManager) {
        if (spriteManager == null) {
            return null;
        }
        for (Sprite sprite : spriteManager.getAllSprites()) {
            if (sprite instanceof AbstractPlayableSprite playable && !playable.isCpuControlled()) {
                return playable;
            }
        }
        return null;
    }

    private static String activeMainCharacterCode(ObjectServices services) {
        WorldSession worldSession = services.worldSession();
        if (worldSession != null
                && worldSession.getSaveSessionContext() != null
                && worldSession.getSaveSessionContext().selectedTeam() != null) {
            return worldSession.getSaveSessionContext().selectedTeam().mainCharacter();
        }
        SonicConfigurationService configuration = services.configuration();
        return configuration != null ? configuration.getString(SonicConfiguration.MAIN_CHARACTER_CODE) : null;
    }
}
