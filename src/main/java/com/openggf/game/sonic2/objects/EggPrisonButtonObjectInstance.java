package com.openggf.game.sonic2.objects;

import com.openggf.graphics.GLCommand;
import com.openggf.graphics.RenderPriority;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectRenderManager;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.SolidContact;
import com.openggf.level.objects.SolidObjectListener;
import com.openggf.level.objects.SolidObjectParams;
import com.openggf.level.objects.SolidExecutionMode;
import com.openggf.level.objects.SolidObjectProvider;
import com.openggf.level.objects.SpawnNullableReferenceRewindRecreatable;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.game.PlayableEntity;

import java.util.List;

/**
 * EggPrison button component - separate object with full solid collision.
 *
 * In the ROM (s2.asm loc_3F354), the button is a child object with routine 4
 * that provides full SolidObject collision (width=$1B=27px, height=8px).
 * This matches that ROM structure by making the button a separate object
 * with its own collision system.
 *
 * Position: 40 pixels above parent capsule Y
 * Collision: Width=54px (±27), Height=16px (±8)
 * Behavior: Depresses 8 pixels when player lands on it, triggers parent
 */
public class EggPrisonButtonObjectInstance extends AbstractObjectInstance
        implements SolidObjectProvider, SolidObjectListener,
        SpawnNullableReferenceRewindRecreatable {

    // ROM constants from s2.asm
    private static final int BUTTON_HALF_WIDTH = 0x1B;  // 27 pixels
    private static final int BUTTON_HALF_HEIGHT = 8;     // 8 pixels
    private static final int BUTTON_OFFSET_Y = -40;      // 40px above parent body
    private static final int BUTTON_DEPRESS_DISTANCE = 8; // How far button moves when pressed

    // Button state
    private int baseY;                 // Original Y position (40px above parent)
    private int currentY;              // Current Y position (depresses when triggered)
    private boolean triggered;         // Has button been pressed?
    private EggPrisonObjectInstance parent; // Parent capsule to notify

    public EggPrisonButtonObjectInstance(ObjectSpawn spawn) {
        this(spawn, null);
    }

    /**
     * Create button instance attached to parent capsule.
     *
     * @param spawn Spawn data (uses parent X, Y position)
     * @param parent Parent capsule to notify when triggered
     */
    public EggPrisonButtonObjectInstance(ObjectSpawn spawn, EggPrisonObjectInstance parent) {
        super(spawn, "EggPrison Button");
        this.parent = parent;

        // Position button 40 pixels above parent body
        this.baseY = spawn.y() + BUTTON_OFFSET_Y;
        this.currentY = baseY;
        this.triggered = false;
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        // ROM loc_3F354 (docs/s2disasm/s2.asm:84937-84950) is, in order:
        //   move.w #$1B,d1 / move.w #8,d2 / move.w #8,d3
        //   move.w x_pos(a0),d4 / jsr (SolidObject).l
        //   move.w objoff_30(a0),y_pos(a0)   ; restore the stored base y
        //   move.b status(a0),d0 / andi.b #standing_mask,d0 / beq.s +
        //   addq.w #8,y_pos(a0)              ; press ONLY while someone stands
        //   clr.b (Update_HUD_timer).w / move.w #1,objoff_32(a0)
        // Only objoff_32 -- the "prison opened" flag the body's routine 2 polls
        // at loc_3F2B4 (:84884-84886) -- latches. The 8-pixel depression itself
        // is recomputed from objoff_30 and the CURRENT standing bit every frame,
        // so the button rises back to its base y the frame the player steps off.
        // SolidObject runs BEFORE the restore, so the surface a player collides
        // with this frame is the y written at the end of the previous frame;
        // MANUAL_CHECKPOINT reproduces that ordering (the automatic checkpoint
        // would otherwise resolve after update()).
        boolean standing = hasStandingContact(checkpointAll());
        currentY = baseY;
        if (standing) {
            currentY = baseY + BUTTON_DEPRESS_DISTANCE;
            if (!triggered) {
                triggered = true;
                if (parent != null) {
                    parent.onButtonTriggered();
                }
            }
        }
    }

    @Override
    public SolidExecutionMode solidExecutionMode() {
        return SolidExecutionMode.MANUAL_CHECKPOINT;
    }

    // ========================================================================================
    // SolidObjectProvider Implementation
    // ========================================================================================

    @Override
    public boolean usesInstanceSolidStateLatchKey() {
        // ROM Obj3E allocates each capsule piece (body, button, lock, broken
        // half) into its own SST slot via AllocateObject, and copies only the
        // per-piece load data into it (docs/s2disasm/s2.asm:84832-84865). Each
        // piece therefore owns a separate status(a0) byte, and
        // SolidObject_TestClearPush releases the player's push bit only when
        // the CALLING object's own pushing bit is set -- otherwise it branches
        // straight to SolidObject_NoCollision without touching status(a1)
        // (docs/s2disasm/s2.asm:35462-35466,35483-35490). Keying the engine's
        // push/standing latch on the shared ObjectSpawn instead lets a sibling
        // piece's no-contact pass clear the body's push mark inside the same
        // object pass, so Sonic's next Sonic_Animate never sees Status_Push and
        // publishes a walk mapping frame where ROM publishes SonAni_Push.
        return true;
    }

    @Override
    public SolidObjectParams getSolidParams() {
        return SolidObjectParams.of(
            BUTTON_HALF_WIDTH,          // halfWidth = 27px
            BUTTON_HALF_HEIGHT,         // airHalfHeight = 8px
            BUTTON_HALF_HEIGHT,         // groundHalfHeight = 8px
            0,                          // offsetX
            0                           // getY() already reports the ROM y_pos/current centre
        );
    }

    @Override
    public boolean isSolidFor(PlayableEntity sprite) {
        // AllocateObject can place the ROM's routine-4 button SST before the
        // routine-2 body SST, so the later body SolidObject call owns the final
        // Status_Push at its exact side edge. The engine's structural child can
        // occupy a later slot instead; do not let its non-overlapping button
        // check clear the body push that was just published for a CPU sidekick.
        return !isGroundedCpuSidekickAtCapsuleBodyEdge(sprite);
    }

    @Override
    public boolean preservesEdgeSubpixelMotion() {
        // S2 Obj3E button calls SolidObject at loc_3F354; exact-edge
        // SolidObject_AtEdge contact sets pushing without stopping velocity.
        return true;
    }

    @Override
    public boolean usesInclusiveRightEdge() {
        // Obj3E's routine-4 button calls the standard SolidObject helper with
        // d1=$1B. Its BHI range gate keeps relX == 2*d1 as the grounded
        // zero-distance side contact that publishes Status_Push.
        return true;
    }

    @Override
    public boolean preservesSidekickCpuPushGraceFromInteractSlot(PlayableEntity player) {
        // The button is Obj3E's routine-4 child and owns a normal SolidObject
        // status byte when TailsCPU_Normal samples interact(a0).
        return isGroundedCpuSidekickAtCapsuleBodyEdge(player);
    }

    @Override
    public boolean preservesMovingSidekickCpuPushAtZeroGraceFromInteractSlot(PlayableEntity player) {
        return isGroundedCpuSidekickAtCapsuleBodyEdge(player);
    }

    @Override
    public boolean publishesSidekickCpuPushFromInteractSlot(PlayableEntity player) {
        return isGroundedCpuSidekickAtCapsuleBodyEdge(player);
    }

    @Override
    public int sidekickCpuPushGraceMinimumFramesFromInteractSlot(PlayableEntity player) {
        return preservesSidekickCpuPushGraceFromInteractSlot(player) ? 0 : Integer.MAX_VALUE;
    }

    @Override
    public int sidekickCpuPushGraceMaximumFramesFromInteractSlot(PlayableEntity player) {
        return preservesSidekickCpuPushGraceFromInteractSlot(player) ? 0 : Integer.MIN_VALUE;
    }

    private boolean isGroundedCpuSidekickAtCapsuleBodyEdge(PlayableEntity player) {
        if (player == null || !player.isCpuControlled() || player.getAir()) {
            return false;
        }
        // The engine's persistent interact slot can point at Obj3E's button
        // child while the ROM-visible push is against the capsule body.
        int dx = Math.abs(player.getCentreX() - spawn.x());
        int dy = Math.abs(player.getCentreY() - spawn.y());
        return dx == 0x2B && dy <= 0x18;
    }

    // ========================================================================================
    // SolidObjectListener Implementation
    // ========================================================================================

    @Override
    public void onSolidContact(PlayableEntity playerEntity, SolidContact contact, int frameCounter) {
        // The manual checkpoint in update() drives the current-frame press
        // state, matching ROM loc_3F354's SolidObject-then-read-status order.
    }

    // ========================================================================================
    // Rendering
    // ========================================================================================

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        ObjectRenderManager renderManager = services().renderManager();
        PatternSpriteRenderer renderer = renderManager != null
                ? renderManager.getEggPrisonRenderer()
                : null;

        if (renderer == null || !renderer.isReady()) {
            renderPlaceholder(commands);
            return;
        }

        // Button uses frame 4 from capsule mappings
        int frameIndex = 4; // FRAME_BUTTON from EggPrisonObjectInstance

        // Render button sprite at current Y position
        renderer.drawFrameIndex(frameIndex, spawn.x(), currentY, false, false);
    }

    private void renderPlaceholder(List<GLCommand> commands) {
        int x = spawn.x();
        int y = currentY;
        int hw = BUTTON_HALF_WIDTH;
        int hh = BUTTON_HALF_HEIGHT;

        int left = x - hw;
        int right = x + hw;
        int top = y - hh;
        int bottom = y + hh;

        appendLine(commands, left, top, right, top, 0.9f, 0.2f, 0.2f);
        appendLine(commands, right, top, right, bottom, 0.9f, 0.2f, 0.2f);
        appendLine(commands, right, bottom, left, bottom, 0.9f, 0.2f, 0.2f);
        appendLine(commands, left, bottom, left, top, 0.9f, 0.2f, 0.2f);
    }

    private void appendLine(List<GLCommand> commands, int x1, int y1, int x2, int y2,
                            float r, float g, float b) {
        commands.add(new GLCommand(GLCommand.CommandType.VERTEX2I, -1, GLCommand.BlendType.SOLID,
                r, g, b, x1, y1, 0, 0));
        commands.add(new GLCommand(GLCommand.CommandType.VERTEX2I, -1, GLCommand.BlendType.SOLID,
                r, g, b, x2, y2, 0, 0));
    }

    @Override
    public int getPriorityBucket() {
        return RenderPriority.clamp(5); // Priority 5 per ROM
    }

    @Override
    public int getX() {
        return spawn.x();
    }

    @Override
    public int getY() {
        return currentY;
    }

    // ========================================================================================
    // Lifecycle
    // ========================================================================================

    /**
     * Detach from parent without destroying (called when results screen triggers).
     * Button persists to maintain visual and collision during results.
     */
    public void detachFromParent() {
        this.parent = null;
    }

    /**
     * Detach from parent and destroy button (called when parent is destroyed).
     */
    public void destroyButton() {
        this.parent = null;
        setDestroyed(true);
    }

    @Override
    public String toString() {
        return String.format("EggPrisonButton[x=%d, y=%d, triggered=%b]",
            spawn.x(), currentY, triggered);
    }
}
