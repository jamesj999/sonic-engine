package com.openggf.game.sonic2.objects;

import com.openggf.debug.DebugRenderContext;
import com.openggf.game.sonic2.audio.Sonic2Sfx;
import com.openggf.game.PlayableEntity;
import com.openggf.game.sonic2.Sonic2ObjectArtKeys;
import com.openggf.graphics.GLCommand;
import com.openggf.graphics.RenderPriority;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectRenderManager;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.SpawnRewindRecreatable;
import com.openggf.level.objects.TouchResponseProvider;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.sprites.playable.AbstractPlayableSprite;

import com.openggf.debug.DebugColor;
import java.util.List;

/**
 * SpikyBlock spike child (Object 0x68, routine 4) - harmful spike that extends
 * from the parent block, cycling through 4 directions: Up, Right, Down, Left.
 * <p>
 * ROM reference: s2.asm Obj68_Spike (lines 53291-53382)
 * <ul>
 *   <li>Collision flags cycle: $84 (up), $A6 (right), $84 (down), $A6 (left)</li>
 *   <li>Offset speed: $800 per frame, max $2000 (4 frames extend, 4 retract)</li>
 *   <li>Wait: 64 frames (Level_frame_counter & $3F == 0)</li>
 *   <li>Sound: SndID_SpikesMove ($B6) when spike starts moving</li>
 *   <li>Art: ArtNem_MtzSpike, palette line 1</li>
 *   <li>Uses MarkObjGone2 (initial X for despawn check)</li>
 * </ul>
 */
public class SpikyBlockSpikeInstance extends AbstractObjectInstance
        implements TouchResponseProvider, SpawnRewindRecreatable {

    // From disassembly: Obj68_CollisionFlags (s2.asm line 53386-53390)
    // Direction 0=Up ($84), 1=Right ($A6), 2=Down ($84), 3=Left ($A6)
    private static final int[] COLLISION_FLAGS = {0x84, 0xA6, 0x84, 0xA6};

    // From disassembly: addi.w #$800,spikearoundblock_offset(a0)
    private static final int OFFSET_STEP = 0x800;

    // From disassembly: cmpi.w #$2000,spikearoundblock_offset(a0)
    private static final int OFFSET_MAX = 0x2000;

    // From disassembly: andi.b #$3F,d0
    private static final int WAIT_MASK = 0x3F;

    // From disassembly: move.b #$10,width_pixels(a1)
    private static final int WIDTH_PIXELS = 0x10;

    // From disassembly: move.b #4,priority(a1)
    private static final int PRIORITY = 4;

    // Initial position (spikearoundblock_initial_x_pos / initial_y_pos).
    // Non-final for rewind: the dynamic spawn tracks currentX/currentY, so the
    // original anchor must be restored from captured scalar state.
    private int initialX;
    private int initialY;

    // Current position
    private int currentX;
    private int currentY;

    // spikearoundblock_offset (objoff_34) - 16.8 fixed-point extension offset
    private int offset;

    // spikearoundblock_position (objoff_36) - 0=retracted/expanding, 1=expanded/retracting
    private int position;

    // spikearoundblock_waiting (objoff_38) - 0=moving, 1=waiting
    private int waiting;

    // routine_secondary - current direction (0=Up, 1=Right, 2=Down, 3=Left)
    private int direction;

    /**
     * Create spike child with initial state synchronized to level frame counter.
     *
     * @param spawn     spawn data (position matches parent block)
     * @param name      display name
     * @param direction initial direction (0-3), derived from subtype + frame counter
     * @param position  initial position state (0=retracted, 1=expanded)
     */
    public SpikyBlockSpikeInstance(ObjectSpawn spawn, String name, int direction, int position) {
        super(spawn, name);
        this.initialX = spawn.x();
        this.initialY = spawn.y();
        this.currentX = initialX;
        this.currentY = initialY;
        this.direction = direction & 3;
        this.position = position;
    }

    private SpikyBlockSpikeInstance(ObjectSpawn spawn) {
        this(spawn, "SpikyBlock-Spike", 0, 0);
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        updateAction(vIntRunCount);
        updatePosition();
        updateDynamicSpawn(currentX, currentY);
        // ROM Obj68_Spike (s2.asm:53302) ends with MarkObjGone2 keyed on
        // spikearoundblock_initial_x_pos (the block's anchor X), not the spike's
        // extended currentX — so the spike does not despawn early when it pokes
        // off-screen. This object intentionally performs no self off-screen
        // despawn: that culling is owned centrally by ObjectManager.Placement,
        // and the spike's initialX anchor is preserved in this.initialX for the
        // central check (currentX never replaces it).
    }

    /**
     * ROM: Obj68_Spike_Action (s2.asm lines 53345-53382)
     * Handles the expand/retract/wait cycle and direction rotation.
     */
    private void updateAction(int vIntRunCount) {
        // ROM Obj68_Spike_Action reads Level_frame_counter, not the
        // VBlank-style object update counter passed into ObjectManager.
        int levelFrameCounter = services().levelManager() != null
                ? services().levelManager().getFrameCounter()
                : vIntRunCount;
        if (waiting != 0) {
            // ROM: move.b (Level_frame_counter+1).w,d0 / andi.b #$3F,d0
            int timerByte = levelFrameCounter & 0xFF;
            if ((timerByte & WAIT_MASK) != 0) {
                return;
            }
            // ROM: clr.w spikearoundblock_waiting(a0)
            waiting = 0;
            // ROM: btst #render_flags.on_screen,render_flags(a0)
            if (isOnScreen()) {
                services().playSfx(Sonic2Sfx.SPIKES_MOVE.id);
            }
        }

        if (position != 0) {
            // Retracting: subi.w #$800,spikearoundblock_offset(a0) / bcc.s (branch if >= 0)
            offset -= OFFSET_STEP;
            if (offset < 0) {
                // ROM: move.w #0,spikearoundblock_offset(a0)
                //      move.w #0,spikearoundblock_position(a0)
                //      move.w #1,spikearoundblock_waiting(a0)
                offset = 0;
                position = 0;
                waiting = 1;
                // ROM: addq.b #1,routine_secondary(a0) / andi.b #3
                direction = (direction + 1) & 3;
            }
        } else {
            // Expanding: addi.w #$800,spikearoundblock_offset(a0)
            offset += OFFSET_STEP;
            if (offset >= OFFSET_MAX) {
                // ROM: move.w #$2000,spikearoundblock_offset(a0)
                //      move.w #1,spikearoundblock_position(a0)
                //      move.w #1,spikearoundblock_waiting(a0)
                offset = OFFSET_MAX;
                position = 1;
                waiting = 1;
            }
        }
    }

    /**
     * Update current position based on direction and offset.
     * ROM: Obj68_Spike_Up/Right/Down/Left (s2.asm lines 53311-53342)
     * Note: move.b reads the high byte of the 16-bit offset word,
     * so effective pixel offset = offset >> 8.
     */
    private void updatePosition() {
        int pixelOffset = (offset >> 8) & 0xFF;
        switch (direction) {
            case 0 -> { // Up: neg offset on Y
                currentX = initialX;
                currentY = initialY - pixelOffset;
            }
            case 1 -> { // Right: add offset on X
                currentX = initialX + pixelOffset;
                currentY = initialY;
            }
            case 2 -> { // Down: add offset on Y
                currentX = initialX;
                currentY = initialY + pixelOffset;
            }
            case 3 -> { // Left: neg offset on X
                currentX = initialX - pixelOffset;
                currentY = initialY;
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
        return RenderPriority.clamp(PRIORITY);
    }

    // TouchResponseProvider - collision flags depend on current direction
    @Override
    public int getCollisionFlags() {
        return COLLISION_FLAGS[direction];
    }

    @Override
    public int getCollisionProperty() {
        return 0;
    }

    @Override
    public boolean requiresRenderFlagForTouch() {
        // S2 Touch_Loop branches only on collision_flags(a1)
        // (docs/s2disasm/s2.asm:85048-85054). Obj68's render flag check
        // gates only the spike movement SFX (s2.asm:53828-53831), while the
        // child keeps collision_flags from Obj68_CollisionFlags.
        return false;
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        ObjectRenderManager renderManager = services().renderManager();
        if (renderManager == null) {
            return;
        }

        PatternSpriteRenderer renderer = renderManager.getRenderer(Sonic2ObjectArtKeys.MTZ_SPIKE);
        if (renderer != null && renderer.isReady()) {
            // Frame index matches direction: 0=Up, 1=Right, 2=Down, 3=Left
            renderer.drawFrameIndex(direction, currentX, currentY, false, false);
        }
    }

    @Override
    public void appendDebugRenderCommands(DebugRenderContext ctx) {
        String[] dirNames = {"Up", "Right", "Down", "Left"};
        String state;
        if (waiting != 0) {
            state = "wait";
        } else if (position != 0) {
            state = "retract";
        } else {
            state = "expand";
        }
        int pxOffset = (offset >> 8) & 0xFF;
        String label = name + " " + dirNames[direction] + " " + state + " ofs=" + pxOffset;

        // Harmful spike - red debug rectangle
        ctx.drawRect(currentX, currentY, WIDTH_PIXELS, WIDTH_PIXELS, 1f, 0f, 0f);
        ctx.drawCross(initialX, initialY, 3, 0.5f, 0.5f, 0.5f);
        ctx.drawWorldLabel(currentX, currentY, -2, label, DebugColor.RED);
    }
}
