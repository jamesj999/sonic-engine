package com.openggf.sprites.ghost;

import com.openggf.data.PlayerSpriteArtProvider;
import com.openggf.game.GameServices;
import com.openggf.game.GroundMode;
import com.openggf.graphics.GraphicsManager;
import com.openggf.level.LevelManager;
import com.openggf.physics.Direction;
import com.openggf.sprites.art.SpriteArtSet;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.sprites.playable.Knuckles;
import com.openggf.sprites.playable.Sonic;
import com.openggf.sprites.playable.Tails;
import com.openggf.sprites.render.PlayerSpriteRenderer;
import com.openggf.trace.TraceCharacterState;
import com.openggf.trace.TraceFrame;
import com.openggf.trace.TraceMetadata;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Render-only trace ghosts with isolated sidekick-style DPLC banks.
 */
public final class GhostTraceRenderer {
    private static final Logger LOGGER = Logger.getLogger(GhostTraceRenderer.class.getName());
    private static final int FULL_OPACITY_DISTANCE = 32;

    private final Map<String, GhostSlot> slots = new HashMap<>();

    public void render(TraceMetadata metadata, TraceFrame frame,
                       AbstractPlayableSprite realMain,
                       List<AbstractPlayableSprite> realSidekicks) {
        renderForLayer(metadata, frame, realMain, realSidekicks, Integer.MIN_VALUE, false, false);
    }

    public void renderForLayer(TraceMetadata metadata, TraceFrame frame,
                               AbstractPlayableSprite realMain,
                               List<AbstractPlayableSprite> realSidekicks,
                               int bucket,
                               boolean highPriority) {
        renderForLayer(metadata, frame, realMain, realSidekicks, bucket, highPriority, true);
    }

    private void renderForLayer(TraceMetadata metadata, TraceFrame frame,
                                AbstractPlayableSprite realMain,
                                List<AbstractPlayableSprite> realSidekicks,
                                int bucket,
                                boolean highPriority,
                                boolean filterLayer) {
        if (metadata == null || frame == null || realMain == null) {
            return;
        }
        List<String> recorded = metadata.recordedCharacters();
        String mainCode = recorded.isEmpty() ? realMain.getCode() : recorded.getFirst();
        renderCharacter("main", mainCode, frame.primaryCharacterState(), realMain, frame.input(), frame.frame(),
                bucket, highPriority, filterLayer);

        TraceCharacterState sidekickState = frame.sidekick();
        if (sidekickState == null || !sidekickState.present()) {
            return;
        }
        AbstractPlayableSprite realSidekick = realSidekicks != null && !realSidekicks.isEmpty()
                ? realSidekicks.getFirst()
                : null;
        String sidekickCode = recorded.size() > 1 ? recorded.get(1)
                : realSidekick != null ? realSidekick.getCode() : "tails";
        renderCharacter("sidekick0", sidekickCode, sidekickState, realSidekick, 0, frame.frame(),
                bucket, highPriority, filterLayer);
    }

    private void renderCharacter(String slotId, String characterCode, TraceCharacterState state,
                                 AbstractPlayableSprite realSprite, int inputMask, int frameCounter,
                                 int bucket, boolean highPriority, boolean filterLayer) {
        if (state == null || !state.present() || realSprite == null) {
            return;
        }
        if (filterLayer && !GhostLayerFilter.matches(
                bucket, highPriority, realSprite.getPriorityBucket(), realSprite.isHighPriority())) {
            return;
        }
        int dx = (state.x() & 0xFFFF) - (realSprite.getCentreX() & 0xFFFF);
        int dy = (state.y() & 0xFFFF) - (realSprite.getCentreY() & 0xFFFF);
        float alpha = GhostOpacityCalculator.alphaForDistance(dx, dy, FULL_OPACITY_DISTANCE);
        if (alpha <= 0.0f) {
            return;
        }
        GhostSlot slot = slotFor(slotId, characterCode);
        if (slot == null) {
            return;
        }

        hydrateVisualState(slot.sprite(), state, inputMask);
        slot.sprite().getAnimationManager().update(frameCounter);

        GraphicsManager graphics = GameServices.graphics();
        graphics.flushPatternBatch();
        boolean previousHighPriority = graphics.getCurrentSpriteHighPriority();
        graphics.setCurrentSpriteHighPriority(realSprite.isHighPriority());
        graphics.beginGhostRenderEffect(alpha);
        graphics.beginPatternBatch();
        try {
            slot.renderer().drawFrame(
                    slot.sprite().getMappingFrame(),
                    state.x(),
                    state.y(),
                    slot.sprite().getRenderHFlip(),
                    slot.sprite().getRenderVFlip());
        } finally {
            graphics.flushPatternBatch();
            graphics.endGhostRenderEffect();
            graphics.setCurrentSpriteHighPriority(previousHighPriority);
        }
    }

    private GhostSlot slotFor(String slotId, String characterCode) {
        String normalizedCode = normalizeCode(characterCode);
        String key = slotId + ":" + normalizedCode;
        GhostSlot existing = slots.get(key);
        if (existing != null) {
            return existing;
        }
        LevelManager level = GameServices.levelOrNull();
        if (level == null || !(level.getGame() instanceof PlayerSpriteArtProvider artProvider)) {
            return null;
        }
        try {
            SpriteArtSet sourceArt = artProvider.loadPlayerSpriteArt(normalizedCode);
            if (sourceArt == null || sourceArt.isEmpty() || sourceArt.bankSize() <= 0) {
                return null;
            }
            int bankBase = level.reserveSidekickPatternBank(sourceArt.bankSize());
            SpriteArtSet ghostArt = GhostArtBankAllocator.shiftToGhostBank(sourceArt, bankBase);
            AbstractPlayableSprite sprite = createVisualSprite(normalizedCode);
            PlayerSpriteRenderer renderer = new PlayerSpriteRenderer(ghostArt);
            sprite.setSpriteRenderer(renderer);
            sprite.setAnimationFrameCount(ghostArt.mappingFrames().size());
            sprite.setAnimationProfile(ghostArt.animationProfile());
            sprite.setAnimationSet(ghostArt.animationSet());
            GhostSlot slot = new GhostSlot(sprite, renderer);
            slots.put(key, slot);
            return slot;
        } catch (IOException | RuntimeException e) {
            LOGGER.log(Level.WARNING, "Failed to create trace ghost for " + characterCode, e);
            return null;
        }
    }

    private static AbstractPlayableSprite createVisualSprite(String characterCode) {
        return switch (normalizeCode(characterCode)) {
            case "tails" -> new Tails("tails", (short) 0, (short) 0);
            case "knuckles" -> new Knuckles("knuckles", (short) 0, (short) 0);
            default -> new Sonic("sonic", (short) 0, (short) 0);
        };
    }

    private static void hydrateVisualState(AbstractPlayableSprite sprite, TraceCharacterState state, int inputMask) {
        sprite.setDirection((state.statusByte() & 0x01) != 0 ? Direction.LEFT : Direction.RIGHT);
        sprite.setRolling(state.rolling());
        sprite.setAir(state.air());
        sprite.setGroundMode(groundMode(state.groundMode()));
        sprite.setCentreXPreserveSubpixel(state.x());
        sprite.setCentreYPreserveSubpixel(state.y());
        sprite.setSubpixelRaw(state.xSub(), state.ySub());
        sprite.setXSpeed(state.xSpeed());
        sprite.setYSpeed(state.ySpeed());
        sprite.setGSpeed(state.gSpeed());
        sprite.setAngle(state.angle());
        sprite.setMovementInputActive(movementInputActive(inputMask, state));
        sprite.setObjectMappingFrameControl(false);
    }

    private static boolean movementInputActive(int inputMask, TraceCharacterState state) {
        int horizontal = AbstractPlayableSprite.INPUT_LEFT | AbstractPlayableSprite.INPUT_RIGHT;
        return (inputMask & horizontal) != 0 || state.gSpeed() != 0 || state.xSpeed() != 0;
    }

    private static GroundMode groundMode(int ordinal) {
        GroundMode[] values = GroundMode.values();
        if (ordinal < 0 || ordinal >= values.length) {
            return GroundMode.GROUND;
        }
        return values[ordinal];
    }

    private static String normalizeCode(String characterCode) {
        if (characterCode == null || characterCode.isBlank()) {
            return "sonic";
        }
        return characterCode.trim().toLowerCase(Locale.ROOT);
    }

    private record GhostSlot(AbstractPlayableSprite sprite, PlayerSpriteRenderer renderer) {
    }
}
