package com.openggf.game.sonic1.objects;
import com.openggf.game.PlayableEntity;

import com.openggf.audio.AudioManager;
import com.openggf.game.sonic1.Sonic1ZoneFeatureProvider;
import com.openggf.game.ZoneFeatureProvider;
import com.openggf.level.objects.SpawnRewindRecreatable;
import com.openggf.game.sonic1.audio.Sonic1Sfx;
import com.openggf.graphics.GLCommand;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectArtKeys;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.SolidContact;
import com.openggf.level.objects.SolidObjectListener;
import com.openggf.level.objects.SolidObjectParams;
import com.openggf.level.objects.SolidObjectProvider;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.sprites.playable.AbstractPlayableSprite;

import java.util.List;

/**
 * Sonic 1 Object 0x0C - Flapping Door (LZ).
 * <p>
 * ROM reference: docs/s1disasm/_incObj/0C Flapping Door.asm
 */
public class Sonic1FlappingDoorObjectInstance extends AbstractObjectInstance
        implements SolidObjectProvider, SolidObjectListener, SpawnRewindRecreatable {

    private static final int FRAME_DURATION = 3;

    // SolidObject parameters from Flap_OpenClose.
    private static final SolidObjectParams SOLID_PARAMS = new SolidObjectParams(0x13, 0x20, 0x21);

    // Ani_Flap: opening = 0,1,2 then afBack,1 (hold on frame 2).
    private static final int[] OPENING_SEQUENCE = {0, 1, 2};
    // Ani_Flap: closing = 2,1,0 then afBack,1 (hold on frame 0).
    private static final int[] CLOSING_SEQUENCE = {2, 1, 0};

    private int flapTime;
    private int flapWait;

    // obAnim bit0 toggles between opening(0) and closing(1).
    private int animationId;
    private int animationFrameIndex;
    private int animationTimer;
    private int mappingFrame;

    private boolean solidActive;

    public Sonic1FlappingDoorObjectInstance(ObjectSpawn spawn) {
        super(spawn, "FlappingDoor");
        this.flapTime = (spawn.subtype() & 0xFF) * 60;
        this.flapWait = 0;
        this.animationId = 0;
        this.animationFrameIndex = 0;
        // Matches obTimeFrame default: AnimateSprite decrements first, then loads delay.
        this.animationTimer = 0;
        this.mappingFrame = 0;
        this.solidActive = false;
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        flapWait--;
        if (flapWait < 0) {
            flapWait = flapTime;
            animationId ^= 1;
            resetAnimation();
            if (isOnScreen()) {
                services().playSfx(Sonic1Sfx.DOOR.id);
            }
        }

        animate();

        // ROM resets f_wtunnelallow every frame, then re-disables while door is closed
        // and Sonic is to the left.
        setWindTunnelDisabled(false);
        solidActive = false;
        if (mappingFrame == 0 && player != null && player.getCentreX() < getX()) {
            solidActive = true;
            setWindTunnelDisabled(true);
        }
    }

    private void animate() {
        animationTimer--;
        if (animationTimer >= 0) {
            return;
        }
        animationTimer = FRAME_DURATION;

        int[] sequence = animationId == 0 ? OPENING_SEQUENCE : CLOSING_SEQUENCE;
        if (animationFrameIndex >= sequence.length) {
            // afBack,1 loops back to the last script frame, not the middle frame.
            animationFrameIndex = sequence.length - 1;
        }
        mappingFrame = sequence[animationFrameIndex];
        animationFrameIndex++;
    }

    private void resetAnimation() {
        animationFrameIndex = 0;
        // Matches AnimateSprite animation-change behavior (obTimeFrame := 0).
        animationTimer = 0;
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        PatternSpriteRenderer renderer = getRenderer(ObjectArtKeys.LZ_FLAPPING_DOOR);
        if (renderer == null) return;

        boolean hFlip = (spawn.renderFlags() & 0x1) != 0;
        boolean vFlip = (spawn.renderFlags() & 0x2) != 0;
        renderer.drawFrameIndex(mappingFrame, getX(), getY(), hFlip, vFlip);
    }

    @Override
    public SolidObjectParams getSolidParams() {
        return SOLID_PARAMS;
    }

    @Override
    public boolean isSolidFor(PlayableEntity playerEntity) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        return solidActive;
    }

    @Override
    public void onSolidContact(PlayableEntity playerEntity, SolidContact contact, int frameCounter) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        // SolidObject handles response; no extra per-contact behavior.
    }

    @Override
    public boolean isPersistent() {
        return !isDestroyed() && isOnScreenX(160);
    }

    @Override
    public void onUnload() {
        setWindTunnelDisabled(false);
    }

    private void setWindTunnelDisabled(boolean disabled) {
        ZoneFeatureProvider provider = services().zoneFeatureProvider();
        if (provider instanceof Sonic1ZoneFeatureProvider sonic1) {
            sonic1.setWindTunnelDisabled(disabled);
        }
    }
}
