package com.openggf.game.sonic1.objects;

import com.openggf.debug.DebugRenderContext;
import com.openggf.game.sonic1.Sonic1SwitchManager;
import com.openggf.game.PlayableEntity;
import com.openggf.game.solid.PlayerSolidContactResult;
import com.openggf.game.solid.SolidCheckpointBatch;
import com.openggf.game.sonic1.audio.Sonic1Sfx;
import com.openggf.graphics.GLCommand;
import com.openggf.graphics.RenderPriority;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectArtKeys;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.SolidContact;
import com.openggf.level.objects.SolidExecutionMode;
import com.openggf.level.objects.SolidObjectListener;
import com.openggf.level.objects.SolidObjectParams;
import com.openggf.level.objects.SolidObjectProvider;
import com.openggf.level.objects.SolidRoutineProfile;
import com.openggf.level.objects.SpawnRewindRecreatable;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.sprites.playable.AbstractPlayableSprite;

import java.util.Collection;
import java.util.List;

/**
 * Object 0x32 - Button/Switch (MZ, SYZ, LZ, SBZ).
 * <p>
 * A floor button that the player (or in MZ, a green PushBlock) can press.
 * Sets bits in the f_switch array which other objects read to activate behavior.
 * <p>
 * Subtype encoding (from docs/s1disasm/_incObj/32 Button.asm):
 * <ul>
 *   <li>Bits 0-3: Switch index (0-15) - which f_switch byte to modify</li>
 *   <li>Bit 5: Flash mode - when pressed, toggles frame between pressed/alt every 8 frames</li>
 *   <li>Bit 6: Alternate flag - uses bit 7 instead of bit 0 in f_switch byte</li>
 *   <li>Bit 7: Block-pressable - in MZ, can also be pressed by PushBlock (Object 0x33)</li>
 * </ul>
 * <p>
 * Reference: docs/s1disasm/_incObj/32 Button.asm
 */
public class Sonic1ButtonObjectInstance extends AbstractObjectInstance
        implements SolidObjectProvider, SolidObjectListener, SpawnRewindRecreatable {

    // From disassembly: move.w #$1B,d1 / move.w #5,d2 / move.w #5,d3
    private static final int SOLID_HALF_WIDTH = 0x1B; // 27 pixels
    private static final int SOLID_HALF_HEIGHT = 5;

    // From disassembly: move.b #4,obPriority(a0)
    private static final int PRIORITY = 4;

    // From disassembly: move.b #$10,obActWid(a0)
    private static final int ACTIVE_WIDTH = 0x10; // 16 pixels

    // From disassembly: move.b #7,obTimeFrame(a0) — flash toggle interval
    private static final int FLASH_INTERVAL = 7;

    // MZ PushBlock search dimensions (But_MZBlock)
    // From disassembly: subi.w #$10,d2 / subq.w #8,d3 / move.w #$20,d4 / move.w #$10,d5
    private static final int MZ_BLOCK_SEARCH_HALF_WIDTH = 0x10;
    private static final int MZ_BLOCK_SEARCH_Y_OFFSET = 8;
    private static final int MZ_BLOCK_SEARCH_WIDTH = 0x20;
    private static final int MZ_BLOCK_SEARCH_HEIGHT = 0x10;

    // PushBlock object ID
    private static final int ID_PUSH_BLOCK = 0x33;

    // Subtype fields
    private int switchIndex;
    private boolean flashMode;
    private int switchBit;
    private boolean blockPressable;

    private int flashTimer;
    private int currentFrame;

    // Position adjusted by +3 Y as per disassembly: addq.w #3,obY(a0)
    private int adjustedY;

    public Sonic1ButtonObjectInstance(ObjectSpawn spawn) {
        super(spawn, "Button");

        int subtype = spawn.subtype() & 0xFF;
        this.switchIndex = subtype & 0x0F;
        this.flashMode = (subtype & 0x20) != 0;
        // Bit 6: alternate flag selects bit 7 instead of bit 0
        this.switchBit = (subtype & 0x40) != 0 ? 7 : 0;
        this.blockPressable = (subtype & 0x80) != 0;
        this.adjustedY = spawn.y() + 3;
        this.flashTimer = 0;
        this.currentFrame = 0;
    }

    @Override
    public int getY() {
        // addq.w #3,obY(a0) in But_Main
        return adjustedY;
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        Sonic1SwitchManager switches = services().gameService(Sonic1SwitchManager.class);

        // Default to unpressed frame
        // bclr #0,obFrame(a0)
        boolean pressed = hasStandingContact(checkpointAll());

        // Check if MZ PushBlock is pressing the button (bit 7 of subtype)
        // tst.b obSubtype(a0) / bpl.s loc_BDBE / bsr.w But_MZBlock
        if (!pressed && blockPressable) {
            pressed = checkMZBlockContact();
        }

        if (pressed) {
            // loc_BDC8: button is being pressed
            if (!switches.isPressed(switchIndex)) {
                // First press: play switch sound
                // move.w #sfx_Switch,d0 / jsr (QueueSound2).l
                try {
                    services().playSfx(Sonic1Sfx.SWITCH.id);
                } catch (Exception e) {
                    // Prevent audio failure from breaking game logic
                }
            }
            // bset d3,(a3) — set switch bit
            switches.setBit(switchIndex, switchBit);
            // bset #0,obFrame(a0) — use pressed frame
            currentFrame = 1;
        } else {
            // loc_BDBE/loc_BDB2: not pressed
            // bclr d3,(a3) — clear switch bit
            switches.clearBit(switchIndex, switchBit);
            // bclr #0,obFrame(a0) — use unpressed frame
            currentFrame = 0;
        }

        // Flash mode: btst #5,obSubtype(a0) / beq.s But_Display
        if (flashMode) {
            // subq.b #1,obTimeFrame(a0) / bpl.s But_Display
            flashTimer--;
            if (flashTimer < 0) {
                // move.b #7,obTimeFrame(a0) — reset timer
                flashTimer = FLASH_INTERVAL;
                // bchg #1,obFrame(a0) — toggle bit 1 of frame
                currentFrame ^= 2;
            }
        }
    }

    @Override
    public void onSolidContact(PlayableEntity playerEntity, SolidContact contact, int frameCounter) {
        // Manual checkpoints drive the current-frame press state from update().
    }

    @Override
    public SolidExecutionMode solidExecutionMode() {
        return SolidExecutionMode.MANUAL_CHECKPOINT;
    }

    /**
     * But_MZBlock: Search active objects for a PushBlock (id_PushBlock = 0x33)
     * within range of this button.
     * <p>
     * From disassembly: checks if any PushBlock's center is within a 32x16 pixel
     * rectangle centered around the button (offset by -16x, -8y).
     */
    private boolean checkMZBlockContact() {
        if (services().objectManager() == null) {
            return false;
        }

        int buttonX = spawn.x();
        int buttonY = adjustedY;

        // From disassembly:
        // subi.w #$10,d2 — search starts at buttonX - 16
        // subq.w #8,d3  — search starts at buttonY - 8
        // move.w #$20,d4 — width of search area = 32
        // move.w #$10,d5 — height of search area = 16
        int searchLeft = buttonX - MZ_BLOCK_SEARCH_HALF_WIDTH;
        int searchTop = buttonY - MZ_BLOCK_SEARCH_Y_OFFSET;

        // But_MZData: dc.b $10, $10
        // Loaded via: moveq #1,d0 / andi.w #$3F,d0 / add.w d0,d0
        // lea But_MZData-2(pc,d0.w),a2 — effectively points to But_MZData
        // d1 = (a2)+ = $10 (signed byte = 16), d1 used as half-width of block
        int blockHalfWidth = 0x10;
        int blockHalfHeight = 0x10;

        Collection<ObjectInstance> activeObjects = services().objectManager().getActiveObjects();
        for (ObjectInstance obj : activeObjects) {
            ObjectSpawn objSpawn = obj.getSpawn();
            if (objSpawn == null || objSpawn.objectId() != ID_PUSH_BLOCK) {
                continue;
            }

            int objX = obj.getX();
            int objY = obj.getY();

            // X check: move.w obX(a1),d0 / sub.w d1,d0 / sub.w d2,d0
            // bcc.s loc_BE80 / add.w d1,d1 / add.w d1,d0 / bcs.s loc_BE84
            int dx = objX - blockHalfWidth - searchLeft;
            if (dx < 0) {
                dx += blockHalfWidth * 2;
                if (dx < 0) {
                    continue;
                }
            } else if (dx > MZ_BLOCK_SEARCH_WIDTH) {
                continue;
            }

            // Y check: same pattern with d5 as comparison
            int dy = objY - blockHalfHeight - searchTop;
            if (dy < 0) {
                dy += blockHalfHeight * 2;
                if (dy < 0) {
                    continue;
                }
            } else if (dy > MZ_BLOCK_SEARCH_HEIGHT) {
                continue;
            }

            return true;
        }
        return false;
    }

    @Override
    public boolean isSolidFor(PlayableEntity playerEntity) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        return true;
    }

    @Override
    public boolean isTopSolidOnly() {
        return false;
    }

    @Override
    public SolidObjectParams getSolidParams() {
        return SolidObjectParams.of(SOLID_HALF_WIDTH, SOLID_HALF_HEIGHT, SOLID_HALF_HEIGHT);
    }

    @Override
    public SolidRoutineProfile getSolidRoutineProfile() {
        // SolidObject's horizontal bounds use `bhi`, so a player exactly flush
        // with the right edge remains a side contact. This matters while pushing
        // SBZ buttons: treating that edge as exclusive clears the object's native
        // push bit intermittently and executes Solid_NoCollision too early.
        return SolidRoutineProfile.fullSolid(usesStickyContactBuffer(), true, false);
    }

    @Override
    public int getTopLandingHalfWidth(PlayableEntity playerEntity, int collisionHalfWidth) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        // ROM uses obActWid ($10) for Solid_Landed / SolidObject_InsideTop,
        // not the collision halfWidth ($1B).
        return ACTIVE_WIDTH;
    }

    @Override
    public int getPriorityBucket() {
        return RenderPriority.clamp(PRIORITY);
    }

    protected SolidCheckpointBatch checkpointAll() {
        return services().solidExecution().resolveSolidNowAll();
    }

    protected boolean hasStandingContact(SolidCheckpointBatch batch) {
        for (PlayerSolidContactResult result : batch.perPlayer().values()) {
            if (result != null && result.standingNow()) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        PatternSpriteRenderer renderer = getRenderer(ObjectArtKeys.BUTTON);
        if (renderer == null) return;

        renderer.drawFrameIndex(currentFrame, spawn.x(), adjustedY, false, false);
    }

    @Override
    public void appendDebugRenderCommands(DebugRenderContext ctx) {
        int x = spawn.x();
        int y = adjustedY;
        boolean pressed = currentFrame != 0;

        // Draw solid collision box
        float r = pressed ? 0f : 0.5f;
        float g = pressed ? 1f : 0.5f;
        float b = 0f;
        ctx.drawRect(x, y, SOLID_HALF_WIDTH, SOLID_HALF_HEIGHT, r, g, b);

        // Label with switch info
        Sonic1SwitchManager switches = services().gameService(Sonic1SwitchManager.class);
        String state = switches.isPressed(switchIndex) ? "ON" : "OFF";
        ctx.drawWorldLabel(x, y - 12, 0,
                String.format("SW%d.b%d=%s", switchIndex, switchBit, state),
                com.openggf.debug.DebugColor.YELLOW);
    }
}
