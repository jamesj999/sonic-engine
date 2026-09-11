package com.openggf.game.sonic2.objects;

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
import com.openggf.level.objects.ObjectArtKeys;
import com.openggf.level.objects.ObjectPlayerParticipationPolicy;
import com.openggf.level.objects.ObjectRenderManager;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.objects.SolidContact;
import com.openggf.level.objects.SolidObjectListener;
import com.openggf.level.objects.SolidObjectParams;
import com.openggf.level.objects.SolidObjectProvider;
import com.openggf.level.render.PatternSpriteRenderer;

import com.openggf.level.render.SpriteMappingPiece;
import com.openggf.level.render.SpritePieceRenderer;
import com.openggf.sprites.playable.AbstractPlayableSprite;

import java.util.ArrayList;
import java.util.List;

/**
 * Sliding Spikes (Object 0x76) - MCZ.
 * <p>
 * A spike block that slides out of the wall when the player approaches.
 * Based on Obj76 in the Sonic 2 disassembly (s2.asm lines 55203-55335).
 * <p>
 * Behavior:
 * - Mode 0 (subtype=0): Waiting - detects player approach
 * - Mode 2 (subtype=2): Sliding - moves 1 pixel/frame for 128 frames
 * <p>
 * Detection triggers when player is on ground and within range:
 * - X distance: |player_x - object_x + 0xC0| < 0x80 (adjusted by x_flip)
 * - Y distance: |player_y - object_y + 0x10| < 0x20
 */
public class SlidingSpikesObjectInstance extends AbstractObjectInstance
        implements SolidObjectProvider, SolidObjectListener, RewindRecreatable {

    private static final boolean DEBUG_VIEW_ENABLED = staticDebugViewEnabled();
    private static final DebugOverlayManager OVERLAY_MANAGER = staticDebugOverlay();

    // Constants from disassembly (Obj76_InitData at s2.asm:55221-55225)
    // The ROM defines a table lookup for subtypes (s2.asm:55236-55242),
    // but all original S2 layouts use subtype 0. This single entry is sufficient.
    private static final int WIDTH_PIXELS = 0x40;  // 64 pixels (half-width for collision)
    private static final int Y_RADIUS = 0x10;      // 16 pixels

    // Slide parameters
    private static final int SLIDE_DISTANCE = 0x80; // 128 pixels total movement

    // Detection range constants from Obj76_CheckPlayer
    private static final int DETECT_X_OFFSET = 0xC0;     // +192 pixels offset to X delta
    private static final int DETECT_X_FLIP_SUB = 0x100;  // -256 if x_flipped
    private static final int DETECT_X_THRESHOLD = 0x80;  // Must be < 128 to trigger
    private static final int DETECT_Y_OFFSET = 0x10;     // +16 pixels offset to Y delta
    private static final int DETECT_Y_THRESHOLD = 0x20;  // Must be < 32 to trigger
    private static final ObjectPlayerParticipationPolicy PLAYER_PARTICIPATION =
            ObjectPlayerParticipationPolicy.MAIN_PLUS_ENGINE_SIDEKICKS_AS_NATIVE_P2_EXTENDED;

    // State
    private int baseX;
    private int currentX;
    private int slidingRemainingMovement = 0;
    private int currentSubtype;  // 0 = waiting, 2 = sliding
    // Orientation from spawn render_flags
    private boolean hFlip;

    // Sprite mapping for this object (single frame with 6 pieces)
    // From docs/s2disasm/mappings/sprite/obj76.asm
    // spritePiece format: xpos, ypos, width, height, tile, xflip, yflip, pal, pri
    //
    // Pieces 0-1 are end caps (ArtNem_HorizSpike at VRAM tile 0x42C, palette 1).
    // On real hardware these tiles are loaded via MCZ PLC2 into VRAM. In our engine,
    // the HorizSpike art is loaded as the SPIKE_SIDE ObjectSpriteSheet, so end caps
    // are rendered from that sheet (tile index 0 = first HorizSpike pattern).
    // Palette is 0 here because the SPIKE_SIDE sheet already has paletteIndex=1,
    // and SpritePieceRenderer ADDs piece palette to the default (0+1=1).
    private static final List<SpriteMappingPiece> END_CAP_PIECES = List.of(
            new SpriteMappingPiece(-0x40, -0x10, 2, 2, 0, false, false, 0),
            new SpriteMappingPiece(-0x40, 0x00, 2, 2, 0, false, false, 0)
    );

    // Pieces 2-5: Main spike body (level art tiles 0x40/0x48, palette 3)
    private static final List<SpriteMappingPiece> BODY_PIECES = List.of(
            new SpriteMappingPiece(-0x30, -0x10, 2, 4, 0x40, false, false, 3),
            new SpriteMappingPiece(-0x20, -0x10, 4, 4, 0x48, false, false, 3),
            new SpriteMappingPiece(0x00, -0x10, 4, 4, 0x48, false, false, 3),
            new SpriteMappingPiece(0x20, -0x10, 4, 4, 0x48, false, false, 3)
    );

    public SlidingSpikesObjectInstance(ObjectSpawn spawn, String name) {
        super(spawn, name);
        this.baseX = spawn.x();
        this.currentX = baseX;
        // Extract h_flip from render_flags bit 0 (status.npc.x_flip in disassembly)
        this.hFlip = (spawn.renderFlags() & 0x01) != 0;
        // subtype starts at 0 (waiting mode)
        this.currentSubtype = spawn.subtype() & 0x0F;  // Lower nibble only per disassembly
    }

    @Override
    public SlidingSpikesObjectInstance recreateForRewind(RewindRecreateContext ctx) {
        return new SlidingSpikesObjectInstance(ctx.spawn(), getName());
    }

    @Override
    public int getX() {
        return currentX;
    }

    @Override
    public int getY() {
        return spawn.y();
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        if (isDestroyed()) {
            return;
        }

        // Off-screen despawn check (MarkObjGone2 behavior from s2.asm:55280-55282)
        // Uses baseX (objoff_34) for the check, not currentX - the spike's spawn
        // position determines despawn, not its current sliding position
        if (!isBasePositionOnScreen()) {
            setDestroyed(true);
            return;
        }

        if (currentSubtype == 0) {
            // Mode 0: Waiting - check for player approach
            checkForPlayers(playerEntity);
        } else if (currentSubtype == 2) {
            // Mode 2: Sliding out
            slideOut();
        }

        updateDynamicSpawn(currentX, spawn.y());
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        if (isDestroyed()) {
            return;
        }

        renderUsingLevelArt();

        // Debug visualization: show collision bounds
        if (isDebugViewEnabled()) {
            renderDebugBounds(commands);
        }
    }

    @Override
    public SolidObjectParams getSolidParams() {
        // From disassembly: d1 = width_pixels + $B, d2 = y_radius, d3 = y_radius + 1
        int d1 = WIDTH_PIXELS + 0x0B;
        int d2 = Y_RADIUS;
        int d3 = Y_RADIUS + 1;
        return SolidObjectParams.of(d1, d2, d3);
    }

    @Override
    public int getBalanceWidthPixels() {
        return WIDTH_PIXELS; // Obj76_SubObjData width_pixels=$40, s2.asm:55719-55737
    }

    @Override
    public void onSolidContact(PlayableEntity playerEntity, SolidContact contact, int frameCounter) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        if (player == null || player.getInvulnerable()) {
            return;
        }

        // Only hurt on side contact (Touch_ChkHurt2 is called when touch_side_mask is set)
        if (!contact.touchSide()) {
            return;
        }

        // ROM: Hurt_Sidekick - CPU Tails only gets knockback, no ring scatter or death
        if (player.isCpuControlled()) {
            player.applyHurt(currentX);
            return;
        }
        boolean hadRings = player.getRingCount() > 0;
        if (hadRings && !player.hasShield()) {
            services().spawnLostRings(player, frameCounter);
        }
        player.applyHurtOrDeath(currentX, true, hadRings);
    }
    @Override
    public int getPriorityBucket() {
        return RenderPriority.clamp(4);
    }

    /**
     * Check if any player is within detection range and trigger sliding.
     * <p>
     * From Obj76_CheckPlayers (s2.asm lines 55290-55294):
     * The disassembly checks both MainCharacter and Sidekick:
     *   lea (MainCharacter).w,a1
     *   bsr.s Obj76_CheckPlayer
     *   lea (Sidekick).w,a1
     *   ; fall through to Obj76_CheckPlayer
     */
    private void checkForPlayers(PlayableEntity updatePlayer) {
        for (PlayableEntity participant : detectionParticipants(updatePlayer)) {
            checkSinglePlayer((AbstractPlayableSprite) participant);
        }
    }

    private List<PlayableEntity> detectionParticipants(PlayableEntity updatePlayer) {
        List<PlayableEntity> participants = services().playerQuery().playersFor(PLAYER_PARTICIPATION);
        if (updatePlayer != null && !participants.contains(updatePlayer)) {
            ArrayList<PlayableEntity> withUpdatePlayer = new ArrayList<>(participants.size() + 1);
            withUpdatePlayer.add(updatePlayer);
            withUpdatePlayer.addAll(participants);
            return withUpdatePlayer;
        }
        return participants;
    }

    /**
     * Check if a single player is within detection range and trigger sliding.
     * <p>
     * From Obj76_CheckPlayer (s2.asm lines 55295-55314):
     * - Player must be on ground (status.player.in_air = 0)
     * - X distance check: (player_x - object_x + 0xC0) must be < 0x80
     *   (subtract 0x100 if object is x_flipped)
     * - Y distance check: (player_y - object_y + 0x10) must be < 0x20
     */
    private void checkSinglePlayer(AbstractPlayableSprite player) {
        if (player == null) {
            return;
        }

        // Already triggered? Don't check again
        if (currentSubtype != 0) {
            return;
        }

        // Player must be on ground (btst #status.player.in_air,status(a1); bne.s rts)
        if (player.getAir()) {
            return;
        }

        // Calculate X distance with offset
        int playerX = player.getCentreX();
        int dx = playerX - baseX + DETECT_X_OFFSET;

        // Adjust for x_flip (object facing left)
        if (hFlip) {
            dx -= DETECT_X_FLIP_SUB;
        }

        // Check X threshold (cmpi.w #$80,d0; bhs.s rts)
        // bhs = branch if higher or same (unsigned >= )
        // So we trigger only if dx < 0x80 (unsigned)
        if (dx < 0 || dx >= DETECT_X_THRESHOLD) {
            return;
        }

        // Calculate Y distance with offset
        int playerY = player.getCentreY();
        int dy = playerY - spawn.y() + DETECT_Y_OFFSET;

        // Check Y threshold (cmpi.w #$20,d0; bhs.s rts)
        if (dy < 0 || dy >= DETECT_Y_THRESHOLD) {
            return;
        }

        // Trigger slide: set subtype=2, remaining_movement=0x80
        currentSubtype = 2;
        slidingRemainingMovement = SLIDE_DISTANCE;
    }

    /**
     * Slide the spike block out of the wall.
     * <p>
     * From Obj76_SlideOut (s2.asm lines 55317-55326):
     * - If remaining_movement > 0: decrement by 1, move x_pos by -1 (or +1 if x_flipped)
     */
    private void slideOut() {
        if (slidingRemainingMovement <= 0) {
            return;
        }

        slidingRemainingMovement--;

        // Move direction: -1 normally, +1 if x_flipped
        // (moveq #-1,d0; btst #x_flip; beq +; neg.w d0; add.w d0,x_pos)
        int moveDir = hFlip ? 1 : -1;
        currentX += moveDir;
    }

    /**
     * Update dynamic spawn to reflect current position for collision detection.
     */    /**
     * Render using mixed art sources.
     * <p>
     * Body pieces (tiles 0x40/0x48) use level art patterns (ArtTile_ArtKos_LevelArt).
     * End cap pieces (ArtNem_HorizSpike) use the SPIKE_SIDE ObjectSpriteSheet,
     * which is loaded via MCZ PLC2 and cached in the object pattern atlas (0x20000+).
     */
    private void renderUsingLevelArt() {
        GraphicsManager graphicsManager = services().graphicsManager();

        // Body first (drawn behind end caps, matching original reverse-order rendering)
        for (int i = BODY_PIECES.size() - 1; i >= 0; i--) {
            SpriteMappingPiece piece = BODY_PIECES.get(i);
            SpritePieceRenderer.renderPieces(
                    List.of(piece),
                    currentX,
                    spawn.y(),
                    0,  // Level patterns start at index 0
                    -1, // Use piece's palette directly (absolute)
                    hFlip,
                    false,
                    (patternIndex, pieceHFlip, pieceVFlip, paletteIndex, drawX, drawY) -> {
                        int descIndex = patternIndex & 0x7FF;
                        if (pieceHFlip) descIndex |= 0x800;
                        if (pieceVFlip) descIndex |= 0x1000;
                        descIndex |= (paletteIndex & 0x3) << 13;
                        graphicsManager.renderPattern(new PatternDesc(descIndex), drawX, drawY);
                    });
        }

        // End caps from SPIKE_SIDE sheet (HorizSpike art loaded via MCZ PLC2)
        ObjectRenderManager renderManager = services().renderManager();
        if (renderManager != null) {
            PatternSpriteRenderer spikeRenderer = renderManager.getSpikeRenderer(true);
            if (spikeRenderer != null && spikeRenderer.isReady()) {
                spikeRenderer.drawPieces(END_CAP_PIECES, currentX, spawn.y(), hFlip, false);
            }
        }
    }

    /**
     * Render debug collision bounds as a red box.
     */
    private void renderDebugBounds(List<GLCommand> commands) {
        // Use the actual collision dimensions
        int halfWidth = WIDTH_PIXELS;
        int halfHeight = Y_RADIUS;

        int left = currentX - halfWidth;
        int right = currentX + halfWidth;
        int top = spawn.y() - halfHeight;
        int bottom = spawn.y() + halfHeight;

        // Red color for hazardous object
        float r = 1.0f;
        float g = 0.2f;
        float b = 0.2f;

        // Draw collision box outline
        appendDebugLine(commands, left, top, right, top, r, g, b);
        appendDebugLine(commands, right, top, right, bottom, r, g, b);
        appendDebugLine(commands, right, bottom, left, bottom, r, g, b);
        appendDebugLine(commands, left, bottom, left, top, r, g, b);

        // Draw center cross
        int crossSize = 4;
        appendDebugLine(commands, currentX - crossSize, spawn.y(), currentX + crossSize, spawn.y(), r, g, b);
        appendDebugLine(commands, currentX, spawn.y() - crossSize, currentX, spawn.y() + crossSize, r, g, b);
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

    /**
     * Check if the BASE position (spawn point) is on screen.
     * <p>
     * ROM Obj76 ends every frame with {@code move.w objoff_34(a0),d0;
     * jmpto MarkObjGone2} (docs/s2disasm/s2.asm:55722-55723), using objoff_34
     * (the original x_pos captured in Obj76_Init, s2.asm:55681) — NOT the current
     * sliding x_pos. This anchors despawn to the spike's wall mount, not its tip.
     * <p>
     * MarkObjGone2 (docs/s2disasm/s2.asm:30231-30248) deletes the object when:
     * <pre>(x &amp; $FF80) - Camera_X_pos_coarse_back &gt; $80 + roundToNextMultiple(screen_width,$80) + $80</pre>
     * where {@code Camera_X_pos_coarse_back = (Camera_X_pos - $80) &amp; $FF80}, the
     * subtract is a 16-bit {@code sub.w} and the compare is an unsigned {@code bhi}.
     * At native 320px width the limit is {@code $80 + $180 + $80 = $280}.
     */
    private boolean isBasePositionOnScreen() {
        var camera = services().camera();
        if (camera == null) {
            return true;  // Assume on-screen if no camera
        }
        int baseAligned = baseX & 0xFF80;
        int cameraCoarseBack = (camera.getX() - 0x80) & 0xFF80;
        int dist = (baseAligned - cameraCoarseBack) & 0xFFFF;
        int roundedWidth = (viewportWidth() + 0x7F) & ~0x7F;
        int limit = 0x80 + roundedWidth + 0x80;
        return dist <= limit;
    }
}
