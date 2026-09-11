package com.openggf.level.objects;

import com.openggf.game.GameModule;
import com.openggf.graphics.GLCommand;
import com.openggf.graphics.RenderPriority;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectRenderManager;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.physics.Direction;
import com.openggf.game.PlayableEntity;
import com.openggf.game.PowerUpObject;

import java.util.List;

/**
 * Invincibility Stars - Obj35 from Sonic 2 disassembly.
 *
 * S2 structure (4 "objects", rendered from one Java instance):
 *   Star 0 (State 2): Orbits at player's current position, fast rotation ($12/frame = 9 entries),
 *                      2 sub-sprites at 180 degrees apart, both showing same animation frame.
 *   Stars 1-3 (State 4): Trail behind player via position history buffer (3/6/9 frames behind),
 *                         slow rotation ($02/frame = 1 entry), 2 sub-sprites at 180 degrees apart,
 *                         each showing different animation frames (primary vs secondary).
 *
 * byte_1DB42: 32-entry orbit offset table. Each dc.w encodes X (high byte) and Y (low byte),
 * both sign-extended. Index is a byte value masked by 0x3E (0-62 even = 32 entries).
 */
public class InvincibilityStarsObjectInstance extends AbstractObjectInstance
        implements PowerUpObject, PlayerBoundInvincibilityStarsRewindRecreatable {
    private final PlayableEntity player;
    private final PatternSpriteRenderer renderer;
    private Boolean sonic1TrailMode;

    // ── ROM data: byte_1DB42 (orbit offset table, 32 entries) ──
    // dc.w $F00, $F03, $E06, $D08, $B0B, $80D, $60E, $30F,
    //      $10, -$3F1, -$6F2, -$8F3, -$BF5, -$DF8, -$EFA, -$FFD,
    //      $F000, -$F04, -$E07, -$D09, -$B0C, -$80E, -$60F, -$310,
    //      -$10, $3F0, $6F1, $8F2, $BF4, $DF7, $EF9, $FFC
    // Each word: high byte = X offset (signed), low byte = Y offset (signed)
    static final int[][] ORBIT_OFFSETS = {
            // Entries 0-7: $0F00..$030F
            { 15, 0 }, { 15, 3 }, { 14, 6 }, { 13, 8 },
            { 11, 11 }, { 8, 13 }, { 6, 14 }, { 3, 15 },
            // Entries 8-15: $0010..$F003
            { 0, 16 }, { -4, 15 }, { -7, 14 }, { -9, 13 },
            { -12, 11 }, { -14, 8 }, { -15, 6 }, { -16, 3 },
            // Entries 16-23: $F000..$FCF0
            { -16, 0 }, { -16, -4 }, { -15, -7 }, { -14, -9 },
            { -12, -12 }, { -9, -14 }, { -7, -15 }, { -4, -16 },
            // Entries 24-31: $FFF0..$0FFC
            { -1, -16 }, { 3, -16 }, { 6, -15 }, { 8, -14 },
            { 11, -12 }, { 13, -9 }, { 14, -7 }, { 15, -4 }
    };

    // ── State 2 animation: byte_1DB82 (parent star, 12-frame loop) ──
    // dc.b 8, 5, 7, 6, 6, 7, 5, 8, 6, 7, 7, 6, $FF
    static final int[] PARENT_ANIM = { 8, 5, 7, 6, 6, 7, 5, 8, 6, 7, 7, 6 };

    // ── State 4 animation tables (trailing stars, primary + secondary halves) ──
    // byte_1DB8F: primary 10 frames, secondary at offset $0B (11)
    private static final int[] TRAIL1_PRIMARY   = { 8, 7, 6, 5, 4, 3, 4, 5, 6, 7 };
    private static final int[] TRAIL1_SECONDARY = { 3, 4, 5, 6, 7, 8, 7, 6, 5, 4 };
    // byte_1DBA4: primary 12 frames, secondary at offset $0D (13)
    private static final int[] TRAIL2_PRIMARY   = { 8, 7, 6, 5, 4, 3, 2, 3, 4, 5, 6, 7 };
    private static final int[] TRAIL2_SECONDARY = { 2, 3, 4, 5, 6, 7, 8, 7, 6, 5, 4, 3 };
    // byte_1DBBD: primary 12 frames, secondary at offset $0D (13)
    private static final int[] TRAIL3_PRIMARY   = { 7, 6, 5, 4, 3, 2, 1, 2, 3, 4, 5, 6 };
    private static final int[] TRAIL3_SECONDARY = { 1, 2, 3, 4, 5, 6, 7, 6, 5, 4, 3, 2 };

    static final int[][] TRAIL_PRIMARY   = { TRAIL1_PRIMARY, TRAIL2_PRIMARY, TRAIL3_PRIMARY };
    static final int[][] TRAIL_SECONDARY = { TRAIL1_SECONDARY, TRAIL2_SECONDARY, TRAIL3_SECONDARY };

    // ── Constants ──
    private static final int STAR_COUNT = 4;
    static final int PARENT_ROTATION_BYTES = 0x12; // 18 bytes/frame (9 entries/frame)
    static final int TRAIL_ROTATION_BYTES = 0x02;  // 2 bytes/frame (1 entry/frame)
    private static final int SUB_SPRITE_PHASE = 0x20; // 180 degrees in byte-index space

    // Position history lag per star (frames behind): starIndex * 3
    static final int[] TRAIL_LAG_FRAMES = { 0, 3, 6, 9 };

    // Initial angle bytes from off_1D992 and post-init override
    // Star 0: set to 4 after init loop; Stars 1-3: from table word high bytes
    static final int[] INITIAL_ANGLES = { 4, 0x00, 0x16, 0x2C };

    // ── S2 mutable state ──
    private final int[] angleByte;
    private final int[] animCounter;

    // ── S1 trail mode constants (unchanged) ──
    private static final int S1_TRAIL_PHASE_COUNT = 6;
    private static final int[][] S1_ANIMATION_SEQUENCES = {
            { 0, 1, 2, 3 },
            { 0, 0, 0, 0, 0, 0, 1, 1, 0, 1, 1, 0, 2, 2, 0, 2, 2, 0, 3, 3, 0, 3, 3, 0 },
            { 0, 0, 0, 0, 0, 0, 1, 1, 0, 1, 0, 0, 2, 2, 0, 2, 0, 0, 3, 3, 0, 3, 0, 0 },
            { 0, 0, 0, 0, 0, 0, 1, 0, 0, 1, 0, 0, 2, 0, 0, 2, 0, 0, 3, 0, 0, 3, 0, 0 }
    };
    private static final int[] S1_ANIMATION_SPEEDS = { 6, 1, 1, 1 };

    // ── S1 trail mode mutable state ──
    private int s1TrailPhase = 0;
    private final int[] s1AnimationIndices = new int[STAR_COUNT];
    private final int[] s1AnimationTimers = new int[STAR_COUNT];

    InvincibilityStarsObjectInstance() {
        this(null);
    }

    public InvincibilityStarsObjectInstance(PlayableEntity player) {
        super(null, "InvincibilityStars");
        this.player = player;

        ObjectRenderManager renderManager = getRenderManager();
        if (renderManager != null) {
            this.renderer = renderManager.getInvincibilityStarsRenderer();
        } else {
            this.renderer = null;
        }

        // Initialize S2 angle state from disassembly initial values
        this.angleByte = new int[STAR_COUNT];
        this.animCounter = new int[STAR_COUNT];
        for (int i = 0; i < STAR_COUNT; i++) {
            angleByte[i] = INITIAL_ANGLES[i];
        }
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity player) {
        if (isTrailModeEnabled()) {
            updateSonic1Trail();
            return;
        }

        boolean facingLeft = player.getDirection() == Direction.LEFT;

        // Update parent star (star 0, State 2): fast rotation
        int parentInc = PARENT_ROTATION_BYTES;
        if (facingLeft) parentInc = -parentInc;
        angleByte[0] = (angleByte[0] + parentInc) & 0xFF;
        animCounter[0]++;
        if (animCounter[0] >= PARENT_ANIM.length) {
            animCounter[0] = 0;
        }

        // Update trailing stars (stars 1-3, State 4): slow rotation
        int trailInc = TRAIL_ROTATION_BYTES;
        if (facingLeft) trailInc = -trailInc;
        for (int i = 1; i < STAR_COUNT; i++) {
            angleByte[i] = (angleByte[i] + trailInc) & 0xFF;
            animCounter[i]++;
            if (animCounter[i] >= TRAIL_PRIMARY[i - 1].length) {
                animCounter[i] = 0;
            }
        }
    }

    private void updateSonic1Trail() {
        s1TrailPhase = (s1TrailPhase + 1) % S1_TRAIL_PHASE_COUNT;
        for (int i = 0; i < STAR_COUNT; i++) {
            s1AnimationTimers[i]++;
            if (s1AnimationTimers[i] >= S1_ANIMATION_SPEEDS[i]) {
                s1AnimationTimers[i] = 0;
                s1AnimationIndices[i]++;
                if (s1AnimationIndices[i] >= S1_ANIMATION_SEQUENCES[i].length) {
                    s1AnimationIndices[i] = 0;
                }
            }
        }
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        if (renderer == null || player == null) {
            return;
        }

        if (isTrailModeEnabled()) {
            appendSonic1TrailRenderCommands();
            return;
        }

        // Star 0 (parent, State 2): orbits at current position, both sub-sprites same frame
        int px = player.getCentreX();
        int py = player.getCentreY();
        int parentFrame = PARENT_ANIM[animCounter[0]];
        int angle0 = angleByte[0];
        renderSubSprite(px, py, angle0, parentFrame);
        renderSubSprite(px, py, angle0 + SUB_SPRITE_PHASE, parentFrame);

        // Stars 1-3 (trailing, State 4): trail at position history, dual-frame sub-sprites
        for (int i = 1; i < STAR_COUNT; i++) {
            int tx = player.getCentreX(TRAIL_LAG_FRAMES[i]);
            int ty = player.getCentreY(TRAIL_LAG_FRAMES[i]);
            int angleI = angleByte[i];
            int counter = animCounter[i];
            int primaryFrame = TRAIL_PRIMARY[i - 1][counter];
            int secondaryFrame = TRAIL_SECONDARY[i - 1][counter];
            // First sub-sprite at base angle: secondary frame (per disasm render order)
            renderSubSprite(tx, ty, angleI, secondaryFrame);
            // Second sub-sprite at angle + 180: primary frame
            renderSubSprite(tx, ty, angleI + SUB_SPRITE_PHASE, primaryFrame);
        }
    }

    private void renderSubSprite(int baseX, int baseY, int rawAngle, int frame) {
        int entry = (rawAngle & 0x3E) >> 1;
        int x = baseX + ORBIT_OFFSETS[entry][0];
        int y = baseY + ORBIT_OFFSETS[entry][1];
        renderer.drawFrameIndex(frame, x, y, false, false);
    }

    private void appendSonic1TrailRenderCommands() {
        boolean hFlip = player.getDirection() == Direction.LEFT;

        for (int i = 0; i < STAR_COUNT; i++) {
            int framesBehind = s1FramesBehindForStar(i, s1TrailPhase);
            int starX = player.getCentreX(framesBehind);
            int starY = player.getCentreY(framesBehind);
            int frameIndex = S1_ANIMATION_SEQUENCES[i][s1AnimationIndices[i]];

            renderer.drawFrameIndex(frameIndex, starX, starY, hFlip, false);
        }
    }

    public static int s1FramesBehindForStar(int starIndex, int trailPhase) {
        int normalizedStar = Math.max(0, Math.min(STAR_COUNT - 1, starIndex));
        int normalizedPhase = Math.floorMod(trailPhase, S1_TRAIL_PHASE_COUNT);
        return 1 + (normalizedStar * S1_TRAIL_PHASE_COUNT) + normalizedPhase;
    }

    // Package-private accessors for testing
    int[] getAngleBytes() { return angleByte; }
    int[] getAnimCounters() { return animCounter; }

    private boolean isTrailModeEnabled() {
        if (sonic1TrailMode != null) {
            return sonic1TrailMode;
        }
        ObjectServices currentServices = tryServices();
        if (currentServices == null) {
            return false;
        }
        GameModule module = currentServices.gameModule();
        sonic1TrailMode = module != null && module.hasTrailInvincibilityStars();
        return sonic1TrailMode;
    }

    @Override
    public boolean isHighPriority() {
        return false;
    }

    @Override
    public int getPriorityBucket() {
        return RenderPriority.clamp(1);
    }

    public void destroy() {
        setDestroyed(true);
    }

    @Override
    public void setVisible(boolean visible) {
        // Invincibility stars are always visible while alive; no-op.
    }

    @Override
    public boolean isInvincibilityStars() {
        return true;
    }

    @Override
    public PlayableEntity boundPlayer() {
        return player;
    }
}
