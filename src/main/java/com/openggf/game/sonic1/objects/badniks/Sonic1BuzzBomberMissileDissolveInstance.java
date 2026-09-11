package com.openggf.game.sonic1.objects.badniks;

import com.openggf.graphics.GLCommand;
import com.openggf.graphics.RenderPriority;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectArtKeys;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.SpawnCoordinateRewindRecreatable;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.game.PlayableEntity;

import java.util.List;

/**
 * Buzz Bomber Missile Dissolve (0x24) - Visual effect when missile vanishes.
 * <p>
 * Based on docs/s1disasm/_incObj/24, 27 & 3F Explosions.asm (MDis_Main section).
 * Note: Object 24 is marked as "unused?" in the disassembly and ArtTile_Missile_Disolve ($41C)
 * is marked as "Unused" in Constants.asm. No PLC loads art to that VRAM region, so the dissolve
 * effect was likely cut during S1 development. We use buzz bomber art tiles as a visual stand-in.
 * <p>
 * Plays 4 animation frames. First frame shows for 9 game frames (MDis_Main sets obTimeFrame=9,
 * then falls through to MDis_Animate which decrements on the same frame). Subsequent frames
 * show for 10 game frames each. Then deletes itself. No collision.
 */
public class Sonic1BuzzBomberMissileDissolveInstance extends AbstractObjectInstance
        implements SpawnCoordinateRewindRecreatable {

    // Frame duration: obTimeFrame = 9 -> decrements to 0, then advances = 10 frames per step
    private static final int FRAME_DURATION = 10;

    // Total frames in dissolve animation
    private static final int TOTAL_FRAMES = 4;

    private int currentX;
    private int currentY;
    private int animFrame;
    private int frameTimer;

    public Sonic1BuzzBomberMissileDissolveInstance(int x, int y) {
        super(new ObjectSpawn(x, y, 0x24, 0, 0, false, 0), "MissileDissolve");
        this.currentX = x;
        this.currentY = y;
        this.animFrame = 0;
        // First frame is 1 tick shorter: MDis_Main sets obTimeFrame=9 then falls through
        // to MDis_Animate which does subq on the same frame, making effective first duration 9
        this.frameTimer = FRAME_DURATION - 1;
    }

    private Sonic1BuzzBomberMissileDissolveInstance() {
        this(0, 0);
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        frameTimer--;
        if (frameTimer <= 0) {
            frameTimer = FRAME_DURATION;
            animFrame++;
            if (animFrame >= TOTAL_FRAMES) {
                setDestroyed(true);
            }
        }
    }

    @Override
    public int getX() {
        return currentX;
    }

    @Override
    public int getY() {
        return currentY;
    }

    @Override
    public int getPriorityBucket() {
        return RenderPriority.clamp(1); // obPriority = 1
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        if (isDestroyed()) {
            return;
        }

        PatternSpriteRenderer renderer = getRenderer(ObjectArtKeys.BUZZ_BOMBER_MISSILE_DISSOLVE);
        if (renderer == null) return;

        renderer.drawFrameIndex(animFrame, currentX, currentY, false, false);
    }
}
