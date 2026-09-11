package com.openggf.game.rewind;

import java.util.List;
import java.util.Objects;
import java.util.Set;

public final class EngineRefTypes {
    private static final Set<String> KNOWN_ENGINE_REF_TYPE_NAMES = Set.of(
            "com.openggf.level.objects.ObjectServices",
            "com.openggf.level.objects.ObjectManager",
            "com.openggf.level.objects.ObjectSpawn",
            "com.openggf.level.objects.ObjectInstance",
            "com.openggf.game.PlayableEntity",
            "com.openggf.sprites.AbstractSprite",
            "com.openggf.sprites.playable.AbstractPlayableSprite",
            "com.openggf.sprites.playable.SidekickRespawnStrategy",
            "com.openggf.sprites.playable.SidekickCarryTrigger",
            "com.openggf.level.render.PatternSpriteRenderer",
            "com.openggf.level.objects.ObjectRenderManager",
            "com.openggf.camera.Camera",
            "com.openggf.audio.AudioManager",
            "com.openggf.data.RomManager",
            "com.openggf.debug.DebugOverlayManager",
            "com.openggf.graphics.FadeManager",
            "com.openggf.graphics.GraphicsManager",
            "com.openggf.physics.CollisionSystem",
            "com.openggf.physics.TerrainCollisionManager",
            "com.openggf.level.LevelManager",
            "com.openggf.level.LevelTilemapManager",
            "com.openggf.level.ParallaxManager",
            "com.openggf.level.WaterSystem",
            "com.openggf.level.rings.RingManager",
            "com.openggf.timer.TimerManager",
            "com.openggf.game.GameModule",
            "com.openggf.game.GameStateManager",
            "com.openggf.game.AbstractLevelEventManager",
            "com.openggf.game.session.WorldSession",
            "com.openggf.game.session.GameplayModeContext",
            "com.openggf.game.session.SessionManager",
            "com.openggf.game.zone.ZoneRuntimeRegistry",
            "com.openggf.game.palette.PaletteOwnershipRegistry",
            "com.openggf.game.animation.AnimatedTileChannelGraph",
            "com.openggf.game.mutation.ZoneLayoutMutationPipeline",
            "com.openggf.game.render.SpecialRenderEffectRegistry",
            "com.openggf.game.render.AdvancedRenderModeController"
    );

    private static final List<String> ENGINE_OWNED_PACKAGE_PREFIXES = List.of(
            "com.openggf.camera.",
            "com.openggf.audio.",
            "com.openggf.data.",
            "com.openggf.debug.",
            "com.openggf.graphics.",
            "com.openggf.physics.",
            "com.openggf.timer.",
            "com.openggf.game.session.",
            "com.openggf.game.zone.",
            "com.openggf.game.palette.",
            "com.openggf.game.animation.",
            "com.openggf.game.mutation.",
            "com.openggf.game.render.",
            "com.openggf.level.render.",
            "com.openggf.sprites.managers."
    );

    private static final List<String> ENGINE_REF_SUFFIXES = List.of(
            "Manager",
            "Managers",
            "Service",
            "Services",
            "Registry",
            "Controller",
            "Renderer",
            "Provider",
            "Strategy",
            "Trigger",
            "Listener",
            "Callback"
    );

    private static final List<Class<?>> KNOWN_ENGINE_REF_TYPES =
            KNOWN_ENGINE_REF_TYPE_NAMES.stream()
                    .map(EngineRefTypes::loadIfPresent)
                    .filter(Objects::nonNull)
                    .toList();

    public static boolean isEngineRef(Class<?> declaredType) {
        Objects.requireNonNull(declaredType, "declaredType");
        Class<?> type = componentType(declaredType);
        if (type.isPrimitive() || type.isEnum() || type.getName().startsWith("java.")) {
            return false;
        }
        for (Class<?> engineType : KNOWN_ENGINE_REF_TYPES) {
            if (engineType.isAssignableFrom(type)) {
                return true;
            }
        }
        return isEngineOwnedReferenceName(type.getName());
    }

    private static boolean isEngineOwnedReferenceName(String name) {
        for (String prefix : ENGINE_OWNED_PACKAGE_PREFIXES) {
            if (name.startsWith(prefix) && hasEngineRefSuffix(name)) {
                return true;
            }
        }
        return name.startsWith("com.openggf.game.") && hasEngineRefSuffix(name)
                || name.startsWith("com.openggf.level.") && hasEngineRefSuffix(name)
                || name.startsWith("com.openggf.sprites.") && hasEngineRefSuffix(name);
    }

    private static boolean hasEngineRefSuffix(String name) {
        String simpleName = name.substring(name.lastIndexOf('.') + 1);
        int nestedSeparator = simpleName.lastIndexOf('$');
        if (nestedSeparator >= 0) {
            simpleName = simpleName.substring(nestedSeparator + 1);
        }
        for (String suffix : ENGINE_REF_SUFFIXES) {
            if (simpleName.endsWith(suffix)) {
                return true;
            }
        }
        return false;
    }

    private static Class<?> componentType(Class<?> type) {
        Class<?> current = type;
        while (current.isArray()) {
            current = current.getComponentType();
        }
        return current;
    }

    private static Class<?> loadIfPresent(String className) {
        try {
            return Class.forName(
                    className,
                    false,
                    Thread.currentThread().getContextClassLoader());
        } catch (ClassNotFoundException e) {
            return null;
        }
    }

    private EngineRefTypes() {
    }
}
