package com.openggf.game.sonic3k.objects;

import com.openggf.camera.Camera;
import com.openggf.game.PlayableEntity;
import com.openggf.game.rewind.RewindStateful;
import java.util.logging.Logger;
import com.openggf.graphics.GLCommand;
import com.openggf.level.objects.ObjectServices;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.sprites.playable.AbstractPlayableSprite;

import java.util.List;

/**
 * Booster flame sub-child for the AIZ1 intro plane.
 * ROM: loc_45C00 and loc_45C3E (s3.asm).
 *
 * Two instances follow the plane child at fixed offsets, cycling through
 * animation frames using Animate_RawNoSST (simple frame cycling with loop).
 *
 * Booster 1: offset (+0x38, +4), frames {2, 3, 4, 3, 2} (byte_45E6B)
 * Booster 2: offset (+0x18, +0x18), frames {5, 6} (byte_45E73)
 *
 * Not spawned as a dynamic object — the plane child manages update/render directly.
 */
public class AizIntroBoosterChild implements RewindStateful<AizIntroBoosterChild.RewindState> {

    private static final Logger LOG = Logger.getLogger(AizIntroBoosterChild.class.getName());

    /**
     * ROM byte_45E6B/byte_45E73 both have timer reset = 0.
     * Animate_RawNoSST decrements timer each frame; when it goes negative
     * the frame advances and timer reloads from data[0].  Timer=0 means
     * every frame: subq.b #1 → -1 → advance → reload 0.
     */
    private static final int ANIM_FRAME_DURATION = 1;

    private final AizIntroPlaneChild parent;
    private final int xOffset;
    private final int yOffset;
    private final int[] animSequence;

    private int currentX;
    private int currentY;
    private int animTimer;
    private int animIndex;

    public record RewindState(int currentX, int currentY, int animTimer, int animIndex) {}

    public AizIntroBoosterChild(AizIntroPlaneChild parent, int xOffset, int yOffset, int[] animSequence) {
        this.parent = parent;
        this.xOffset = xOffset;
        this.yOffset = yOffset;
        this.animSequence = animSequence;
        this.animTimer = 0;
        this.animIndex = 0;
    }

    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        // Follow parent plane position with fixed offset
        currentX = parent.getX() + xOffset;
        currentY = parent.getY() + yOffset;

        // Animate_RawNoSST: cycle through frames, loop at end
        animTimer++;
        if (animTimer >= ANIM_FRAME_DURATION) {
            animTimer = 0;
            animIndex++;
            if (animIndex >= animSequence.length) {
                animIndex = 0;
            }
        }
    }

    public void appendRenderCommands(List<GLCommand> commands, Camera camera, ObjectServices services) {
        PatternSpriteRenderer renderer = AizIntroArtLoader.getPlaneRenderer(services);
        if (renderer == null || !renderer.isReady()) return;
        int frame = animSequence[animIndex];
        // Screen-space coordinates use the ROM +128 sprite-table bias.
        int renderX = currentX;
        int renderY = currentY;
        try {
            if (camera != null) {
                renderX += camera.getX() - 128;
                renderY += camera.getY() - 128;
            }
        } catch (Exception e) {
            LOG.fine(() -> "AizIntroBoosterChild.appendRenderCommands: " + e.getMessage());
        }
        renderer.drawFrameIndex(frame, renderX, renderY, false, false);
    }

    public int getCurrentX() {
        return currentX;
    }

    public int getCurrentY() {
        return currentY;
    }

    public int getAnimFrame() {
        return animSequence[animIndex];
    }

    @Override
    public RewindState captureRewindStateValue() {
        return new RewindState(currentX, currentY, animTimer, animIndex);
    }

    @Override
    public void restoreRewindStateValue(RewindState state) {
        currentX = state.currentX();
        currentY = state.currentY();
        animTimer = state.animTimer();
        animIndex = state.animIndex();
    }
}
