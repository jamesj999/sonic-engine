package com.openggf.sprites.managers;

import com.openggf.physics.Direction;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.sprites.playable.Tails;
import com.openggf.sprites.render.PlayerSpriteRenderer;

/**
 * Handles spindash dust animation and drawing.
 */
public class SpindashDustController {
    private static final int[] DASH_FRAMES = { 0x0A, 0x0B, 0x0C, 0x0D, 0x0E, 0x0F, 0x10 };
    private static final int FRAME_DELAY = 1;
    private static final int TAILS_Y_OFFSET = -4;

    // Water-entry/exit splash frames from the shared Sonic_Dust art
    // (Ani_obj08 byte_12CA6: 0..9, delay 2 -> 3 ticks/frame). ROM writes
    // anim=1 into the fixed Sonic_Dust object (sonic3k.asm:22241,22281) rather
    // than spawning a FindFreeObj slot, so the splash rides this controller.
    private static final int[] SPLASH_FRAMES = { 0, 1, 2, 3, 4, 5, 6, 7, 8, 9 };
    private static final int SPLASH_FRAME_DELAY = 2;

    // Surface-emerge splash (ROM Obj_DashDust anim 4 / Ani_DashSplashDrown
    // byte_18DE8: frames $16..$1D, duration 5 -> 6 displayed frames each, then
    // $FD 0 reverts to the invisible idle anim). Used at the LBZ1 start when the
    // launched player breaks the surface (Obj_LevelIntro_PlayerLaunchFromGround,
    // sonic3k.asm loc_39AD2, sets the Dust object's anim=4 at y=$5C0/x=player x).
    // Like the water splash above, this rides the controller — the engine's model
    // of the fixed Dust object — rather than an ObjectManager/SST object. That
    // matches the ROM (the splash is the fixed Dust slot, not a Dynamic_object_RAM
    // entry) and keeps it out of trace nearby-object snapshots.
    private static final int SURFACE_SPLASH_FIRST_FRAME = 0x16;
    private static final int SURFACE_SPLASH_FRAME_COUNT = 8;
    private static final int SURFACE_SPLASH_FRAME_DURATION = 5;

    private final AbstractPlayableSprite sprite;
    private final PlayerSpriteRenderer renderer;
    private final int fixedSlotIndex;
    private int frameIndex;
    private int frameTick;
    private int currentFrame;
    private boolean activeLastTick;

    private boolean splashActive;
    private int splashX;
    private int splashY;
    private boolean splashFacingLeft;
    private int splashFrameIndex;
    private int splashTick;

    // Surface-emerge splash uses a caller-supplied renderer (e.g. ArtUnc_SplashDrown),
    // keeping this controller game-agnostic. The reference is derived render state (not
    // captured for rewind), matching the water splash above.
    private PlayerSpriteRenderer surfaceSplashRenderer;
    private boolean surfaceSplashActive;
    private int surfaceSplashX;
    private int surfaceSplashY;
    private int surfaceSplashAnimFrame;
    private int surfaceSplashTimer;
    private int surfaceSplashMappingFrame = SURFACE_SPLASH_FIRST_FRAME;

    public SpindashDustController(AbstractPlayableSprite sprite, PlayerSpriteRenderer renderer) {
        this(sprite, renderer, -1);
    }

    public SpindashDustController(AbstractPlayableSprite sprite, PlayerSpriteRenderer renderer, int fixedSlotIndex) {
        this.sprite = sprite;
        this.renderer = renderer;
        this.fixedSlotIndex = fixedSlotIndex;
        this.frameIndex = 0;
        this.frameTick = 0;
        this.currentFrame = DASH_FRAMES[0];
        this.activeLastTick = false;
    }

    /**
     * Starts the water-entry/exit splash animation on the fixed dust object.
     *
     * <p>ROM: {@code Sonic_InWater}/{@code Sonic_OutWater} write
     * {@code move.w #1<<8,anim(a6)} into the existing Sonic_Dust object. Routing
     * the splash through this controller keeps it slot-free (no FindFreeObj),
     * matching ROM Load_Sprites pressure.
     *
     * @param x          splash X (player centre)
     * @param waterY     water-surface Y
     * @param facingLeft whether the player faced left
     */
    public void triggerSplash(int x, int waterY, boolean facingLeft) {
        splashActive = true;
        splashX = x;
        splashY = waterY;
        splashFacingLeft = facingLeft;
        splashFrameIndex = 0;
        splashTick = SPLASH_FRAME_DELAY;
    }

    /**
     * Starts the surface-emerge splash on the fixed dust object.
     *
     * <p>ROM: {@code Obj_LevelIntro_PlayerLaunchFromGround} (sonic3k.asm loc_39AD2)
     * sets the Dust object to {@code anim=4} snapped to {@code y=$5C0}/{@code x=player x}
     * when the launched player rises past the surface. Routed through this controller
     * (not an SST object) to mirror the ROM and preserve trace nearby-object parity.
     *
     * @param splashRenderer DPLC renderer for the splash art (caller supplies the
     *                       game-specific {@code ArtUnc_SplashDrown} renderer)
     * @param x              splash world X (player centre at emergence)
     * @param y              splash world Y (the ROM surface line)
     */
    public void triggerSurfaceSplash(PlayerSpriteRenderer splashRenderer, int x, int y) {
        if (splashRenderer == null) {
            return;
        }
        surfaceSplashRenderer = splashRenderer;
        surfaceSplashRenderer.invalidateDplcCache();
        surfaceSplashActive = true;
        surfaceSplashX = x;
        surfaceSplashY = y;
        surfaceSplashAnimFrame = 0;
        surfaceSplashTimer = 0;
        surfaceSplashMappingFrame = SURFACE_SPLASH_FIRST_FRAME;
    }

    /** Test/diagnostic: whether the surface-emerge splash is currently playing. */
    public boolean isSurfaceSplashActive() {
        return surfaceSplashActive;
    }

    /** Test/diagnostic: the splash mapping frame currently shown ($16..$1D). */
    public int surfaceSplashMappingFrame() {
        return surfaceSplashMappingFrame;
    }

    public void update() {
        updateSurfaceSplash();
        updateSplash();
        boolean active = isActive();
        if (!active) {
            reset();
            return;
        }
        if (!activeLastTick) {
            activeLastTick = true;
            frameIndex = 0;
            frameTick = 0;
        }
        int duration = frameTick - 1;
        boolean advance = duration < 0;
        if (advance) {
            duration = FRAME_DELAY;
        }
        frameTick = duration;
        currentFrame = DASH_FRAMES[frameIndex];
        if (advance) {
            frameIndex = (frameIndex + 1) % DASH_FRAMES.length;
        }
    }

    public void draw() {
        drawSurfaceSplash();
        drawSplash();
        if (!isActive() || renderer == null) {
            return;
        }
        int originX = sprite.getRenderCentreX();
        int originY = sprite.getRenderCentreY();
        if (sprite instanceof Tails) {
            originY += TAILS_Y_OFFSET;
        }
        boolean hFlip = Direction.LEFT.equals(sprite.getDirection());
        renderer.drawFrame(currentFrame, originX, originY, hFlip, false);
    }

    /** Test/diagnostic: whether the water splash animation is currently playing. */
    public boolean isSplashActive() {
        return splashActive;
    }

    /**
     * ROM object-table slot for the fixed Sonic_Dust/Dust sidecar represented by
     * this controller, or {@code -1} for engine-extension sidekicks that have no
     * native fixed dust slot.
     */
    public int fixedSlotIndex() {
        return fixedSlotIndex;
    }

    private void updateSurfaceSplash() {
        if (!surfaceSplashActive) {
            return;
        }
        // Animate_Sprite (sonic3k.asm Animate_Sprite): advance when the per-frame
        // timer underflows, i.e. each frame displays for duration+1 game frames.
        if (--surfaceSplashTimer >= 0) {
            return;
        }
        if (surfaceSplashAnimFrame >= SURFACE_SPLASH_FRAME_COUNT) {
            // ROM: anim 4 ends with $FD 0 -> idle anim 0 (invisible). End the splash.
            surfaceSplashActive = false;
            return;
        }
        surfaceSplashMappingFrame = SURFACE_SPLASH_FIRST_FRAME + surfaceSplashAnimFrame;
        surfaceSplashAnimFrame++;
        surfaceSplashTimer = SURFACE_SPLASH_FRAME_DURATION;
    }

    private void drawSurfaceSplash() {
        if (!surfaceSplashActive || surfaceSplashRenderer == null) {
            return;
        }
        // ROM status is cleared on the splash's first frame (no flip); faces right.
        surfaceSplashRenderer.drawFrame(surfaceSplashMappingFrame, surfaceSplashX, surfaceSplashY, false, false);
    }

    private void updateSplash() {
        if (!splashActive) {
            return;
        }
        splashTick--;
        if (splashTick < 0) {
            splashTick = SPLASH_FRAME_DELAY;
            splashFrameIndex++;
            if (splashFrameIndex >= SPLASH_FRAMES.length) {
                splashActive = false;
            }
        }
    }

    private void drawSplash() {
        if (!splashActive || renderer == null
                || splashFrameIndex < 0 || splashFrameIndex >= SPLASH_FRAMES.length) {
            return;
        }
        renderer.drawFrame(SPLASH_FRAMES[splashFrameIndex], splashX, splashY, splashFacingLeft, false);
    }

    private boolean isActive() {
        return sprite != null
                && sprite.getSpindash()
                && !sprite.getAir()
                && !(sprite.getPinballMode() && sprite.getRolling());
    }

    private void reset() {
        activeLastTick = false;
        frameIndex = 0;
        frameTick = 0;
        currentFrame = DASH_FRAMES[0];
    }

    public RewindState captureRewindState() {
        return new RewindState(frameIndex, frameTick, currentFrame, activeLastTick);
    }

    public void restoreRewindState(RewindState state) {
        if (state == null) {
            reset();
            return;
        }
        frameIndex = state.frameIndex();
        frameTick = state.frameTick();
        currentFrame = state.currentFrame();
        activeLastTick = state.activeLastTick();
    }

    public record RewindState(
            int frameIndex,
            int frameTick,
            int currentFrame,
            boolean activeLastTick
    ) {}

    /**
     * Returns the renderer used for dust/splash animation.
     * Used by SplashObjectInstance to share the same art assets.
     */
    public PlayerSpriteRenderer getRenderer() {
        return renderer;
    }
}
