package com.openggf.game.sonic2.objects;

import com.openggf.camera.Camera;
import com.openggf.configuration.SonicConfiguration;
import com.openggf.configuration.SonicConfigurationService;
import com.openggf.debug.DebugOverlayManager;
import com.openggf.debug.DebugOverlayToggle;
import com.openggf.game.PlayableEntity;
import com.openggf.graphics.GLCommand;
import com.openggf.graphics.GraphicsManager;
import com.openggf.graphics.RenderPriority;
import com.openggf.level.PatternDesc;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.objects.SolidContact;
import com.openggf.level.objects.SolidObjectListener;
import com.openggf.level.objects.SolidObjectParams;
import com.openggf.level.objects.SolidObjectProvider;
import com.openggf.level.render.SpriteMappingFrame;
import com.openggf.level.render.SpriteMappingPiece;
import com.openggf.level.render.SpritePieceRenderer;
import com.openggf.sprites.playable.AbstractPlayableSprite;

import java.util.List;

/**
 * MTZ Twin Stompers (Object 0x64) - Crushing pistons from Metropolis Zone.
 * <p>
 * Twin piston stompers that extend and retract on a timer cycle.
 * Based on Obj64 in the Sonic 2 disassembly (s2.asm lines 52202-52335).
 * <p>
 * Subtypes:
 * <ul>
 *   <li>0x01: Large variant - wide top bar with two piston columns (frame 0).
 *       width_pixels=0x40, y_radius=0x6C, collision y_radius=0x0C, travel=0x40</li>
 *   <li>0x11: Small variant - compact square piston (frame 1).
 *       width_pixels=0x10, collision y_radius=0x20, travel=0x40</li>
 * </ul>
 * <p>
 * State machine (mode 1):
 * <ol>
 *   <li>Wait at retracted position for 90 frames</li>
 *   <li>Extend 8 pixels/frame until max travel (0x40)</li>
 *   <li>Wait at extended position for 90 frames</li>
 *   <li>Retract 8 pixels/frame back to 0</li>
 *   <li>Repeat</li>
 * </ol>
 * <p>
 * When x_flip is set, the direction is inverted with a 0x40 offset.
 * The extension modifies y_pos relative to base position.
 */
public class MTZTwinStompersObjectInstance extends AbstractObjectInstance
        implements SolidObjectProvider, SolidObjectListener, RewindRecreatable {

    private static final boolean DEBUG_VIEW_ENABLED = staticDebugViewEnabled();
    private static final DebugOverlayManager OVERLAY_MANAGER = staticDebugOverlay();

    // Obj64_Properties flat byte array (s2.asm:52214-52220)
    // Accessed as a3 = Properties + byteOffset, then 3 sequential reads: (a3)+, (a3)+, (a3)+
    private static final int[] PROPERTIES = {
            0x40, 0x0C,  // Offset 0: width_pixels=0x40, objoff_2E=0x0C
            0x40, 0x01,  // Offset 2: width_pixels=0x40, objoff_2E=0x01
            0x10, 0x20,  // Offset 4: width_pixels=0x10, objoff_2E=0x20
            0x40, 0x01,  // Offset 6: width_pixels=0x40, objoff_2E=0x01
    };

    // Timer duration: move.w #$5A,objoff_36(a0)
    private static final int WAIT_TIMER = 0x5A;  // 90 frames

    // Movement speed: addq.w #8 / subq.w #8
    private static final int MOVE_SPEED = 8;

    // x_flip Y offset: addi.w #$40,d0
    private static final int FLIP_OFFSET = 0x40;

    // art_tile palette: make_art_tile(ArtTile_ArtKos_LevelArt,1,0) => palette line 1
    private static final int ART_TILE_PALETTE = 1;

    // From mappings/sprite/obj64.asm - Frame 0 (large variant, 10 pieces)
    // spritePiece format: xOffset, yOffset, widthTiles, heightTiles, tileIndex, hFlip, vFlip, palette, priority
    private static final SpriteMappingFrame FRAME_LARGE = new SpriteMappingFrame(List.of(
            new SpriteMappingPiece(-0x40, -0x0C, 4, 3, 0x01, false, false, 0),
            new SpriteMappingPiece(-0x20, -0x0C, 4, 3, 0x01, true,  false, 0),
            new SpriteMappingPiece( 0x00, -0x0C, 4, 3, 0x01, false, false, 0),
            new SpriteMappingPiece( 0x20, -0x0C, 4, 3, 0x01, true,  false, 0),
            new SpriteMappingPiece(-0x28,  0x0C, 2, 4, 0x0D, false, false, 0),
            new SpriteMappingPiece(-0x28,  0x2C, 2, 4, 0x0D, false, false, 0),
            new SpriteMappingPiece(-0x28,  0x4C, 2, 4, 0x0D, false, false, 0),
            new SpriteMappingPiece( 0x18,  0x0C, 2, 4, 0x0D, false, false, 0),
            new SpriteMappingPiece( 0x18,  0x2C, 2, 4, 0x0D, false, false, 0),
            new SpriteMappingPiece( 0x18,  0x4C, 2, 4, 0x0D, false, false, 0)
    ));

    // From mappings/sprite/obj64.asm - Frame 1 (small variant, 4 pieces)
    private static final SpriteMappingFrame FRAME_SMALL = new SpriteMappingFrame(List.of(
            new SpriteMappingPiece(-0x10, -0x20, 2, 4, 0x57, false, false, 0),
            new SpriteMappingPiece( 0x00, -0x20, 2, 4, 0x57, true,  false, 0),
            new SpriteMappingPiece(-0x10,  0x00, 2, 4, 0x57, false, true,  0),
            new SpriteMappingPiece( 0x00,  0x00, 2, 4, 0x57, true,  true,  0)
    ));

    // Properties for this instance
    private int widthPixels;
    private int collisionYRadius;
    private int maxTravel;             // objoff_3C
    private int mappingFrame;
    private boolean xFlip;

    // Explicit y_radius for large variant (s2.asm:52235)
    private int renderYRadius;

    // Position tracking
    private int baseX;                 // objoff_34
    private int baseY;                 // objoff_30
    private int currentY;

    // State machine (mode 1 behavior)
    private int moveMode;              // subtype & 0x0F
    private boolean extending;         // objoff_38: false=retracting, true=extending
    private int extension;             // objoff_3A: current extension amount (0 to maxTravel)
    private int timer;                 // objoff_36: countdown timer
    public MTZTwinStompersObjectInstance(ObjectSpawn spawn, String name) {
        super(spawn, name);

        // Property lookup: (subtype >> 2) & 0x1C gives byte offset into flat array
        // s2.asm:52226-52228
        int subtype = spawn.subtype();
        int byteOffset = (subtype >> 2) & 0x1C;
        if (byteOffset + 2 >= PROPERTIES.length) byteOffset = 0;

        // Read 2 bytes: (a3)+, (a3)+ => width_pixels, objoff_2E (s2.asm:52230-52231)
        widthPixels = PROPERTIES[byteOffset];
        collisionYRadius = PROPERTIES[byteOffset + 1];

        // mapping_frame = (byteOffset >> 2): s2.asm:52232-52233
        // d0 was already shifted by 2, then shifted again by 2 = total shift of 4
        mappingFrame = byteOffset >> 2;

        // y_radius for large variant (frame 0): s2.asm:52234-52236
        renderYRadius = (mappingFrame == 0) ? 0x6C : 0;

        // 3rd byte read: (a3)+ after the 2 property bytes (s2.asm:52245-52247)
        // a3 now points to byteOffset + 2
        maxTravel = (byteOffset + 2 < PROPERTIES.length) ? PROPERTIES[byteOffset + 2] : 0;

        // x_flip from status byte: s2.asm:52321
        xFlip = (spawn.renderFlags() & 0x01) != 0;

        // Store base positions: s2.asm:52243-52244
        baseX = spawn.x();
        baseY = spawn.y();
        currentY = baseY;

        // Movement mode from lower nibble: s2.asm:52248
        moveMode = subtype & 0x0F;

        // State initialized to 0
        extending = false;
        extension = 0;
        timer = 0;

        if (moveMode == 1) {
            // S2 placement materializes new objects between slot-loop passes.
            // Prime the two Obj64_Main ticks that the ROM has consumed before
            // this moving solid first participates in the following frame's
            // player/object contact window.
            updateStomperMovement();
            updateStomperMovement();
        }

        updateDynamicSpawn(baseX, currentY);
    }

    @Override
    public MTZTwinStompersObjectInstance recreateForRewind(RewindRecreateContext ctx) {
        return new MTZTwinStompersObjectInstance(ctx.spawn(), getName());
    }

    @Override
    public int getX() {
        return baseX;
    }

    @Override
    public int getY() {
        return currentY;
    }

    @Override
    public boolean usesInstanceSolidStateLatchKey() {
        // Obj64 stores player standing/pushing bits in its live SST status byte.
        // The engine rebuilds this moving object's dynamic spawn as y_pos changes,
        // so use the instance key to let SolidObject_TestClearPush observe the
        // same per-SST bits across the next no-contact frame (s2.asm:35456-35467).
        return true;
    }

    @Override
    public SolidObjectParams getSolidParams() {
        // s2.asm:52264-52270: d1 = width_pixels + $B, d2 = objoff_2E, d3 = objoff_2E + 1.
        // ROM gates this JmpTo9_SolidObject call on render_flags.on_screen
        // (s2.asm:52262); the engine's solid-contact pass applies the equivalent
        // on-screen gate (isWithinSolidContactBounds), so no extra gating is needed here.
        int halfWidth = widthPixels + 0x0B;
        return SolidObjectParams.of(halfWidth, collisionYRadius, collisionYRadius + 1);
    }

    @Override
    public boolean usesPreUpdateYForContinuedRide(PlayableEntity player) {
        // Obj64_Main updates y_pos before JmpTo9_SolidObject (s2.asm:52726-52750),
        // but the MTZ1 trace shows the continued top ride remains seated on the
        // previous surface while loc_269FA starts retracting (s2.asm:52766-52805).
        // Keep this to downward/retracting motion so MTZ3 side-push cleanup still
        // observes the live post-update body position.
        return moveMode == 1 && !extending && currentY < getPreUpdateY();
    }

    @Override
    public void onSolidContact(PlayableEntity playerEntity, SolidContact contact, int frameCounter) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        // Crush detection is handled automatically by the engine's SolidContacts collision
        // resolution, matching the ROM's SolidObject routine (s2.asm:35336-35361).
        // When the player is standing on the ground (ySpeed==0, not airborne) and overlaps
        // the bottom of this object with sufficient horizontal depth (absDistX >= 16),
        // SolidObject_Squash triggers KillCharacter. Objects that call SolidObject (like
        // Obj64) do not need explicit crush logic - it is a built-in feature of the
        // SolidObject routine itself.
        //
        // No additional contact handling is needed for this object beyond the engine's
        // automatic solid object behavior (landing, side push, ceiling hit, crush).
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        if (isDestroyed()) return;

        // ROM Obj64_Main (s2.asm:52254-52278) runs the movement mode first, then
        // (gated on render_flags.on_screen) JmpTo9_SolidObject, then the
        // coarse-X delete test. Solid participation is provided to the engine via
        // SolidObjectProvider (getSolidParams), so movement runs here first.
        //
        // Push old x_pos (for SolidObject delta): s2.asm:52251
        // Execute movement mode: s2.asm:52252-52260
        if (moveMode == 1) {
            updateStomperMovement();
        }
        // Mode 0 is just rts (no movement)

        // SOLID-PASS GATE DIVERGENCE: the ROM only calls JmpTo9_SolidObject when
        // render_flags.on_screen is set (s2.asm:52262-52271). The engine resolves
        // solid contacts through ObjectManager.SolidContacts driven by the
        // SolidObjectProvider interface, which already skips objects whose render
        // box has scrolled off-screen (see AbstractObjectInstance.isWithinSolidContactBounds).
        // That gate is functionally equivalent to the ROM's on_screen check, so no
        // explicit gating is added here; doing so from update() would not affect the
        // engine's separate solid-contact pass.

        // Despawn check: ROM coarse-X delete test (s2.asm:52273-52277).
        //   move.w objoff_34(a0),d0     ; baseX (spawn X)
        //   andi.w #$FF80,d0            ; chunk-align object X
        //   sub.w  (Camera_X_pos_coarse).w,d0
        //   cmpi.w #$280,d0             ; 640px coarse cutoff
        //   bhi.s  JmpTo31_DeleteObject
        // Camera_X_pos_coarse is defined as (Camera_X_pos - 128) chunk-aligned
        // (s2.constants.asm:1619), so the engine models it as
        // ((camera.getX() - 128) & 0xFF80) — matching the established port in
        // TornadoObjectInstance / ConveyorObjectInstance. No left margin in the
        // ROM (the previous isOnScreen(128) added a spurious 128px margin and
        // used a symmetric viewport test).
        Camera camera = services().camera();
        if (camera != null) {
            int objChunk = baseX & 0xFF80;
            int camChunk = (camera.getX() - 128) & 0xFF80;
            int d0 = (objChunk - camChunk) & 0xFFFF;
            if (d0 > 0x280) {
                setDestroyed(true);
                return;
            }
        }

        updateDynamicSpawn(baseX, currentY);
    }

    /**
     * Stomper movement state machine (mode 1).
     * <p>
     * s2.asm lines 52290-52330 (loc_269FA through loc_26A50)
     */
    private void updateStomperMovement() {
        if (!extending) {
            // Retracting phase: objoff_38 == 0
            if (extension > 0) {
                // Still retracting: subq.w #8,objoff_3A (s2.asm:52295)
                extension -= MOVE_SPEED;
            } else {
                // Fully retracted, count down timer: s2.asm:52299-52303
                timer--;
                if (timer < 0) {
                    timer = WAIT_TIMER;
                    extending = true;
                }
            }
        } else {
            // Extending phase: objoff_38 != 0
            if (extension < maxTravel) {
                // Still extending: addq.w #8,objoff_3A (s2.asm:52309)
                extension += MOVE_SPEED;
            } else {
                // Fully extended, count down timer: s2.asm:52313-52317
                timer--;
                if (timer < 0) {
                    timer = WAIT_TIMER;
                    extending = false;
                }
            }
        }

        // Apply position: s2.asm:52319-52329
        int d0 = extension;
        if (xFlip) {
            // neg.w d0; addi.w #$40,d0 (s2.asm:52323-52324)
            d0 = -d0 + FLIP_OFFSET;
        }
        currentY = baseY + d0;
    }

    @Override
    public String traceDebugDetails() {
        return String.format("ext=%02X max=%02X timer=%04X dir=%d flip=%d base=%04X",
                extension & 0xFFFF,
                maxTravel & 0xFFFF,
                timer & 0xFFFF,
                extending ? 1 : 0,
                xFlip ? 1 : 0,
                baseY & 0xFFFF);
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        if (isDestroyed()) return;

        renderUsingLevelArt();

        if (isDebugViewEnabled()) {
            appendDebugRenderCommands(commands);
        }
    }

    @Override
    public int getPriorityBucket() {
        // move.b #4,priority(a0) (s2.asm:52242)
        return RenderPriority.clamp(4);
    }

    /**
     * Renders using level art patterns.
     * art_tile = make_art_tile(ArtTile_ArtKos_LevelArt, 1, 0) => palette line 1.
     */
    private void renderUsingLevelArt() {
        GraphicsManager graphicsManager = services().graphicsManager();
        SpriteMappingFrame frame = (mappingFrame == 0) ? FRAME_LARGE : FRAME_SMALL;
        List<SpriteMappingPiece> pieces = frame.pieces();

        for (int i = pieces.size() - 1; i >= 0; i--) {
            SpriteMappingPiece piece = pieces.get(i);
            SpritePieceRenderer.renderPieces(
                    List.of(piece),
                    baseX,
                    currentY,
                    0,
                    -1,
                    false,  // Object does not flip rendering based on x_flip
                    false,
                    (patternIndex, pieceHFlip, pieceVFlip, paletteIndex, drawX, drawY) -> {
                        int descIndex = patternIndex & 0x7FF;
                        if (pieceHFlip) descIndex |= 0x800;
                        if (pieceVFlip) descIndex |= 0x1000;
                        int finalPalette = (paletteIndex + ART_TILE_PALETTE) & 0x3;
                        descIndex |= (finalPalette & 0x3) << 13;
                        graphicsManager.renderPattern(new PatternDesc(descIndex), drawX, drawY);
                    });
        }
    }

    private void appendDebugRenderCommands(List<GLCommand> commands) {
        int halfWidth = widthPixels + 0x0B;
        int left = baseX - halfWidth;
        int right = baseX + halfWidth;
        int top = currentY - collisionYRadius;
        int bottom = currentY + collisionYRadius + 1;

        float r = 0.7f, g = 0.4f, b = 0.9f;
        appendDebugLine(commands, left, top, right, top, r, g, b);
        appendDebugLine(commands, right, top, right, bottom, r, g, b);
        appendDebugLine(commands, right, bottom, left, bottom, r, g, b);
        appendDebugLine(commands, left, bottom, left, top, r, g, b);

        // Center cross
        appendDebugLine(commands, baseX - 4, currentY, baseX + 4, currentY, r, g, b);
        appendDebugLine(commands, baseX, currentY - 4, baseX, currentY + 4, r, g, b);
    }

    private void appendDebugLine(List<GLCommand> commands, int x1, int y1, int x2, int y2,
                                  float r, float g, float b) {
        commands.add(new GLCommand(GLCommand.CommandType.VERTEX2I, -1, GLCommand.BlendType.SOLID,
                r, g, b, x1, y1, 0, 0));
        commands.add(new GLCommand(GLCommand.CommandType.VERTEX2I, -1, GLCommand.BlendType.SOLID,
                r, g, b, x2, y2, 0, 0));
    }

    private boolean isDebugViewEnabled() {
        return DEBUG_VIEW_ENABLED && OVERLAY_MANAGER.isEnabled(DebugOverlayToggle.OVERLAY);
    }
}
