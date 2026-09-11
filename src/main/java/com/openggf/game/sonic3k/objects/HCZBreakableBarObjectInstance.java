package com.openggf.game.sonic3k.objects;

import com.openggf.game.PlayableEntity;
import com.openggf.game.sonic3k.Sonic3kObjectArtKeys;
import com.openggf.game.sonic3k.audio.Sonic3kSfx;
import com.openggf.game.sonic3k.constants.Sonic3kObjectIds;
import com.openggf.debug.DebugColor;
import com.openggf.debug.DebugRenderContext;
import com.openggf.graphics.GLCommand;
import com.openggf.graphics.RenderPriority;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectLifetimeOps;
import com.openggf.level.objects.ObjectPlayerParticipationPolicy;
import com.openggf.level.objects.ObjectPlayerQuery;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.SpawnRewindRecreatable;
import com.openggf.level.objects.SpawnTrailingZeroIntsRewindRecreatable;
import com.openggf.level.objects.SubpixelMotion;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.physics.Direction;
import com.openggf.sprites.NativePositionOps;
import com.openggf.sprites.playable.RawControllerInput;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.sprites.playable.ObjectControlState;

import java.util.List;
import java.util.logging.Logger;

/**
 * Object 0x36 - HCZ Breakable Bar (Hydrocity Zone).
 * <p>
 * A horizontal or vertical bar the player grabs onto and can slide along.
 * The player is held in a hanging animation and can move along the bar axis
 * using the D-pad. Pressing A/B/C releases the player and may trigger bar
 * destruction (unless subtype bit 6 is set for non-destructive release).
 * <p>
 * The bar also has an optional countdown timer (subtype bits 0-3) that triggers
 * destruction when it reaches zero while a player is captured.
 * <p>
 * Subtype encoding:
 * <ul>
 *   <li>Bit 7: Orientation. 0 = vertical, 1 = horizontal.</li>
 *   <li>Bit 6: Non-destructive release. If set, ABC releases player without breaking bar.</li>
 *   <li>Bits 4-5: Size index (0, 1, 2) into the size configuration table.</li>
 *   <li>Bits 0-3: Timer multiplier. value * 60 = break countdown frames. 0 = no timer.</li>
 * </ul>
 * <p>
 * ROM references: Obj_HCZBreakableBar (sonic3k.asm:42726), loc_1ED8E (vertical init),
 * loc_1EDB0 (vertical update), loc_1EF64 (horizontal update), sub_1EDEC (vertical capture),
 * sub_1EFA0 (horizontal capture), loc_1EEEC (vertical break), loc_1F09A (horizontal break).
 */
public class HCZBreakableBarObjectInstance extends AbstractObjectInstance implements SpawnRewindRecreatable {

    private static final Logger LOG = Logger.getLogger(HCZBreakableBarObjectInstance.class.getName());

    // ===== Art constants =====
    // ROM: make_art_tile(ArtTile_HCZMisc, 2, 0)
    private static final String ART_KEY = Sonic3kObjectArtKeys.HCZ_BREAKABLE_BAR;

    // ROM: move.w #$200,priority(a0) => bucket 4
    private static final int PRIORITY = 4;

    // ===== Size configuration table (byte_1ED1A, 3 entries of 4 bytes) =====
    // {halfExtent, totalExtent, widthOrHeight, mappingFrame}
    private static final int[][] SIZE_TABLE = {
            {0x14, 0x28, 0x20, 0},  // Size 0: 20px extent, 40px total, frame 0/4
            {0x24, 0x48, 0x30, 1},  // Size 1: 36px extent, 72px total, frame 1/5
            {0x34, 0x68, 0x40, 2},  // Size 2: 52px extent, 104px total, frame 2/6
    };

    // ROM: Hanging animation IDs
    // Vertical: move.b #$11,anim(a1) (sonic3k.asm)
    private static final int HANG_ANIM_VERTICAL = 0x11;
    // Horizontal: move.b #$14,anim(a1) (sonic3k.asm)
    private static final int HANG_ANIM_HORIZONTAL = 0x14;

    // ROM: Release cooldown = $3C frames (60 frames)
    private static final int RELEASE_COOLDOWN = 0x3C;

    // ROM: Capture proximity for cross-axis: player within $14-$24 offset
    private static final int CAPTURE_CROSS_MIN = 0x14;
    private static final int CAPTURE_CROSS_MAX = 0x24;
    private static final int HORIZONTAL_CAPTURE_Y_MIN_OFFSET = -0x14;
    private static final int HORIZONTAL_CAPTURE_Y_MAX_OFFSET = -0x04;

    // ROM: addi.w #$14,d0 / move.w d0,x_pos(a1) — player offset from bar center
    // Vertical bar: player hangs 0x14 (20px) to the right of the bar
    // Horizontal bar: player hangs 0x14 (20px) above the bar
    private static final int PLAYER_HANG_OFFSET = 0x14;

    // Debris gravity: addi.w #8,y_vel(a1) per frame (ROM: sub_1F188 children)
    private static final int DEBRIS_GRAVITY = 8;

    // Vertical break velocity: x_vel=$400, y_vel=0
    private static final int VERTICAL_BREAK_X_VEL = 0x400;
    private static final int VERTICAL_BREAK_Y_VEL = 0;

    // Horizontal break velocity: x_vel=0, y_vel=-$400
    private static final int HORIZONTAL_BREAK_X_VEL = 0;
    private static final int HORIZONTAL_BREAK_Y_VEL = -0x400;

    // Mapping frame offsets: horizontal frames start at 4
    private static final int HORIZONTAL_FRAME_OFFSET = 4;
    // Debris frame: 3 for vertical, 7 for horizontal
    private static final int VERTICAL_DEBRIS_FRAME = 3;
    private static final int HORIZONTAL_DEBRIS_FRAME = 7;

    // Debris spacing: each debris piece is 4 pixels apart along the bar axis
    private static final int DEBRIS_SPACING = 4;

    // ===== Instance state =====

    private int x;
    private int y;
    private int subtype;
    private boolean isHorizontal;
    private boolean nonDestructiveRelease;
    private int sizeIndex;

    // From size table
    private int halfExtent;
    private int totalExtent;
    private int widthOrHeight;
    private int mappingFrame;

    // Effective collision dimensions
    private int widthPixels;
    private int heightPixels;

    // Timer: counts down per frame while any player is captured
    private int breakTimer;
    private boolean hasTimer;

    // Per-player capture state — ROM: $32/$33 (captured flag), $34/$35 (cooldown)
    private final boolean[] captured = new boolean[2];   // $32(a0), $33(a0)
    private final int[] releaseCooldown = new int[2];    // $34(a0), $35(a0)

    // Destruction state — ROM: $3A(a0)
    private boolean triggerBreak;
    private boolean broken;

    public HCZBreakableBarObjectInstance(ObjectSpawn spawn) {
        super(spawn, "HCZBreakableBar");
        this.x = spawn.x();
        this.y = spawn.y();
        this.subtype = spawn.subtype() & 0xFF;

        // ROM: Bit 7 = orientation
        this.isHorizontal = (subtype & 0x80) != 0;

        // ROM: Bit 6 = non-destructive release
        this.nonDestructiveRelease = (subtype & 0x40) != 0;

        // ROM: Bits 4-5 = size index (clamped to 0-2)
        this.sizeIndex = Math.min((subtype >> 4) & 0x03, 2);

        // ROM: Bits 0-3 = timer multiplier
        int timerMultiplier = subtype & 0x0F;
        this.hasTimer = timerMultiplier > 0;
        this.breakTimer = timerMultiplier * 60;

        // Resolve size configuration
        int[] sizeConfig = SIZE_TABLE[sizeIndex];
        this.halfExtent = sizeConfig[0];
        this.totalExtent = sizeConfig[1];
        this.widthOrHeight = sizeConfig[2];

        if (isHorizontal) {
            // ROM: horizontal - width = size table byte 2, height = 4
            this.widthPixels = widthOrHeight;
            this.heightPixels = 4;
            // Frame offset: horizontal frames start at 4
            this.mappingFrame = sizeConfig[3] + HORIZONTAL_FRAME_OFFSET;
        } else {
            // ROM: vertical - width = 4, height = size table byte 2
            this.widthPixels = 4;
            this.heightPixels = widthOrHeight;
            this.mappingFrame = sizeConfig[3];
        }
    }

    // ===== Update =====

    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        if (broken) return;

        AbstractPlayableSprite updatePlayer =
                playerEntity instanceof AbstractPlayableSprite sprite ? sprite : null;
        NativePlayerSlots players = nativePlayerSlots(updatePlayer);
        if (players.p1() == null && players.p2() == null) return;

        // ROM: loc_1EDB0 / loc_1EF64 — timer countdown while ANY player is captured.
        // tst.w (a2) tests the word at $32, which is nonzero if $32 or $33 is set.
        boolean anyCaptured = captured[0] || captured[1];
        if (hasTimer && anyCaptured) {
            breakTimer--;
            if (breakTimer <= 0) {
                performBreak(players);
                return;
            }
        }

        // ROM: calls sub_1EDEC/sub_1EFA0 for Player 1 (d2=0), then Player 2 (d2=1)
        if (players.p1() != null) {
            processPlayerCapture(players.p1(), 0);
        }

        // Process sidekick (Player 2)
        if (players.p2() != null) {
            processPlayerCapture(players.p2(), 1);
        }

        // ROM: tst.b $3A(a0) / bne loc_1EEEC — break flag from ABC release
        if (triggerBreak) {
            performBreak(players);
            return;
        }
    }

    private NativePlayerSlots nativePlayerSlots(AbstractPlayableSprite updatePlayer) {
        ObjectPlayerQuery query = services().playerQuery();
        PlayableEntity main = query.mainPlayerOrNull();
        if (!(main instanceof AbstractPlayableSprite) && updatePlayer != null) {
            main = updatePlayer;
        }

        AbstractPlayableSprite p1 = (main instanceof AbstractPlayableSprite sprite) ? sprite : null;
        AbstractPlayableSprite p2 = null;
        for (PlayableEntity candidate : query.playersFor(ObjectPlayerParticipationPolicy.NATIVE_P1_P2)) {
            if (candidate == main || !(candidate instanceof AbstractPlayableSprite sprite)) {
                continue;
            }
            p2 = sprite;
            break;
        }
        if (p2 == p1) {
            p2 = null;
        }
        return new NativePlayerSlots(p1, p2);
    }

    private record NativePlayerSlots(AbstractPlayableSprite p1, AbstractPlayableSprite p2) {
    }

    /**
     * Processes capture/movement/release for a single player.
     * ROM: sub_1EDEC (vertical) or sub_1EFA0 (horizontal).
     *
     * @param player the player sprite to process
     * @param playerIndex 0 for P1, 1 for P2 (maps to $32/$33 and _unkF7C7 bit)
     */
    private void processPlayerCapture(AbstractPlayableSprite player, int playerIndex) {
        // Tick cooldown for this player
        if (releaseCooldown[playerIndex] > 0) {
            releaseCooldown[playerIndex]--;
        }

        if (isHorizontal) {
            updateHorizontalCaptureForPlayer(player, playerIndex);
        } else {
            updateVerticalCaptureForPlayer(player, playerIndex);
        }
    }

    // ===== Vertical capture logic (loc_1EDB0 / sub_1EDEC) =====

    private void updateVerticalCaptureForPlayer(AbstractPlayableSprite player, int pi) {
        if (!captured[pi]) {
            // ROM: tst.b 2(a2) / subq.b #1,2(a2) — cooldown active, skip
            if (releaseCooldown[pi] > 0) return;

            int playerX = player.getCentreX();
            int playerY = player.getCentreY();

            // ROM: player_y must be in [bar_y-height_pixels, bar_y+height_pixels).
            int top = y - heightPixels;
            int bottom = y + heightPixels;
            if (playerY < top || playerY >= bottom) return;

            int dx = playerX - x;
            // ROM uses `cmp.w x_pos(a1),d0` / `bhs` at bar_x+$14, so the
            // lower capture edge is strict: player_x must be greater than
            // bar_x+$14, not equal to it.
            if (dx <= CAPTURE_CROSS_MIN || dx > CAPTURE_CROSS_MAX) return;

            // ROM: cmpi.b #4,routine(a1) / bhs locret
            if (player.getDead() || player.isHurt()) return;
            // ROM: tst.b object_control(a1) / bne locret
            if (player.isObjectControlled()) return;

            // Capture — ROM: clamp Y, set x = x_pos + $14, anim $11, object_control = 1
            capturePlayer(player, pi, HANG_ANIM_VERTICAL);
            clampPlayerVertical(player);
            NativePositionOps.writeXPosPreserveSubpixel(player, x + PLAYER_HANG_OFFSET);
            // ROM: bclr #Status_Facing,status(a1) — face right
            player.setDirection(Direction.RIGHT);
        } else {
            // Player captured — ROM: allow up/down movement, clamp to extent
            int hangX = x + PLAYER_HANG_OFFSET;
            if (RawControllerInput.isHeld(player, AbstractPlayableSprite.INPUT_UP)) {
                player.setY((short) (player.getY() - 1));
            }
            if (RawControllerInput.isHeld(player, AbstractPlayableSprite.INPUT_DOWN)) {
                player.setY((short) (player.getY() + 1));
            }
            NativePositionOps.writeXPosPreserveSubpixel(player, hangX);
            clampPlayerVertical(player);
            player.setXSpeed((short) 0);
            player.setYSpeed((short) 0);

            // ROM: andi.w #button_A|B|C,d1 / beq locret — ABC to release
            // ROM masks the LOW byte of (Ctrl_1).w, which is the *pressed* byte
            // (the high byte is held, as the btst #button_up+8 tests above show).
            // Release is therefore edge-triggered on a fresh A/B/C press; a jump
            // button still held from before the grab must not release the player.
            if (player.isRawControllerJumpJustPressed()) {
                releasePlayer(player, pi);
                // ROM: btst #6,subtype / bne locret — if non-destructive, don't break
                if (!nonDestructiveRelease) {
                    triggerBreak = true;
                }
            }
        }
    }

    // ===== Horizontal capture logic (loc_1EF64 / sub_1EFA0) =====

    private void updateHorizontalCaptureForPlayer(AbstractPlayableSprite player, int pi) {
        if (!captured[pi]) {
            if (releaseCooldown[pi] > 0) return;

            int playerX = player.getCentreX();
            int playerY = player.getCentreY();

            // ROM: player_x must be in [bar_x-width_pixels, bar_x+width_pixels).
            int left = x - widthPixels;
            int right = x + widthPixels;
            if (playerX < left || playerX >= right) return;

            int dy = playerY - y;
            // ROM: lower edge is strict (player_y > bar_y-$14), upper edge is inclusive.
            if (dy <= HORIZONTAL_CAPTURE_Y_MIN_OFFSET || dy > HORIZONTAL_CAPTURE_Y_MAX_OFFSET) return;

            if (player.getDead() || player.isHurt()) return;
            if (player.isObjectControlled()) return;

            // Capture — ROM: clamp X, set y = y_pos - $14, anim $14, object_control = 1
            capturePlayer(player, pi, HANG_ANIM_HORIZONTAL);
            clampPlayerHorizontal(player);
            NativePositionOps.writeYPosPreserveSubpixel(player, y - PLAYER_HANG_OFFSET);
        } else {
            int hangY = y - PLAYER_HANG_OFFSET;
            if (RawControllerInput.isHeld(player, AbstractPlayableSprite.INPUT_LEFT)) {
                NativePositionOps.addXPosPreserveSubpixel(player, -1);
            }
            if (RawControllerInput.isHeld(player, AbstractPlayableSprite.INPUT_RIGHT)) {
                NativePositionOps.addXPosPreserveSubpixel(player, 1);
            }
            NativePositionOps.writeYPosPreserveSubpixel(player, hangY);
            clampPlayerHorizontal(player);
            player.setXSpeed((short) 0);
            player.setYSpeed((short) 0);

            // ROM masks the LOW byte of (Ctrl_1).w, which is the *pressed* byte
            // (the high byte is held, as the btst #button_up+8 tests above show).
            // Release is therefore edge-triggered on a fresh A/B/C press; a jump
            // button still held from before the grab must not release the player.
            if (player.isRawControllerJumpJustPressed()) {
                releasePlayer(player, pi);
                if (!nonDestructiveRelease) {
                    triggerBreak = true;
                }
            }
        }
    }

    // ===== Capture / Release helpers =====

    /**
     * Captures a player onto the bar.
     * ROM: sets (a2)=1, object_control=1, anim, bset d2,(_unkF7C7).
     *
     * @param player the sprite to capture
     * @param pi     player index (0=P1, 1=P2) — maps to $32/$33 and _unkF7C7 bit
     * @param hangAnim animation ID ($11 vertical, $14 horizontal)
     */
    private void capturePlayer(AbstractPlayableSprite player, int pi, int hangAnim) {
        captured[pi] = true;
        player.setXSpeed((short) 0);
        player.setYSpeed((short) 0);
        // ROM: move.b #1,object_control(a1)
        ObjectControlState.nativeBits0To6CpuAllowedMovementSuppressed().applyTo(player);
        // ROM: move.b #$11,anim(a1) (or #$14 for horizontal)
        player.setAnimationId(hangAnim);
        player.setForcedAnimationId(hangAnim);
        // ROM: bset d2,(_unkF7C7).w — blocks water tunnel capture while on bar
        HCZWaterRushObjectInstance.HCZBreakableBarState.setBit(pi);
    }

    /**
     * Releases a player from the bar.
     * ROM: clr.b (a2), sets cooldown at 2(a2)=$3C, bclr d2,(_unkF7C7).
     *
     * @param player the sprite to release
     * @param pi     player index (0=P1, 1=P2)
     */
    private void releasePlayer(AbstractPlayableSprite player, int pi) {
        captured[pi] = false;
        releaseCooldown[pi] = RELEASE_COOLDOWN;
        // ROM: andi.b #$FE,object_control(a1)
        ObjectControlState.none().applyTo(player);
        player.setForcedAnimationId(-1);
        // ROM: bclr d2,(_unkF7C7).w — re-enables water tunnel capture
        HCZWaterRushObjectInstance.HCZBreakableBarState.clearBit(pi);
    }

    private void clampPlayerVertical(AbstractPlayableSprite player) {
        int playerCY = player.getCentreY();
        int minY = y - halfExtent;
        int maxY = y + halfExtent;
        if (playerCY < minY) {
            player.setY((short) (minY - player.getYRadius()));
        } else if (playerCY > maxY) {
            player.setY((short) (maxY - player.getYRadius()));
        }
    }

    private void clampPlayerHorizontal(AbstractPlayableSprite player) {
        int playerCX = player.getCentreX();
        int minX = x - halfExtent;
        int maxX = x + halfExtent;
        if (playerCX < minX) {
            NativePositionOps.writeXPosPreserveSubpixel(player, minX);
        } else if (playerCX > maxX) {
            NativePositionOps.writeXPosPreserveSubpixel(player, maxX);
        }
    }

    // ===== Break / Destruction logic =====

    private void performBreak(NativePlayerSlots players) {
        if (broken) return;
        broken = true;

        // ROM: loc_1EEEC / loc_1F09A — release both players if captured
        if (captured[0] && players.p1() != null) {
            ObjectControlState.none().applyTo(players.p1());
            players.p1().setForcedAnimationId(-1);
        }
        // Release P2 (sidekick)
        if (captured[1] && players.p2() != null) {
            ObjectControlState.none().applyTo(players.p2());
            players.p2().setForcedAnimationId(-1);
        }
        captured[0] = false;
        captured[1] = false;

        // ROM: clr.b (_unkF7C7).w — clear all water tunnel blocks on bar break
        HCZWaterRushObjectInstance.HCZBreakableBarState.setState(0);

        // Play collapse SFX (ROM: sfx_Collapse = $59)
        if (isOnScreen()) {
            try {
                services().playSfx(Sonic3kSfx.COLLAPSE.id);
            } catch (Exception e) {
                // Prevent audio failure from breaking game logic
            }
        }

        // Spawn debris
        spawnDebris();

        // Mark as remembered so it doesn't respawn
        markRemembered();
    }

    // ROM: word_1F108 — vertical bar debris offsets (X, Y) relative to bar center.
    // 16 entries (supports up to size 2 = 0x40/4 = 16 pieces).
    private static final int[][] VERT_DEBRIS_OFFSETS = {
            {0, -0x1C}, {0, -0x14}, {0, -0x0C}, {0, -0x04},
            {0,  0x04}, {0,  0x0C}, {0,  0x14}, {0,  0x1C},
            {0,  0x24}, {0, -0x24}, {0,  0x2C}, {0, -0x2C},
            {0,  0x34}, {0, -0x34}, {0,  0x3C}, {0, -0x3C},
    };

    // ROM: word_1F148 — horizontal bar debris offsets (X, Y) relative to bar center.
    private static final int[][] HORZ_DEBRIS_OFFSETS = {
            {-0x1C, 0}, {-0x14, 0}, {-0x0C, 0}, {-0x04, 0},
            { 0x04, 0}, { 0x0C, 0}, { 0x14, 0}, { 0x1C, 0},
            { 0x24, 0}, {-0x24, 0}, { 0x2C, 0}, {-0x2C, 0},
            { 0x34, 0}, {-0x34, 0}, { 0x3C, 0}, {-0x3C, 0},
    };

    // ROM: byte_1F0F0 — stagger delay per debris piece.
    // sub_1F188 reads one byte per piece via (a2)+,$3F(a1).
    private static final int[] DEBRIS_DELAYS = {
            7, 5, 2, 0, 1, 3, 4, 6, 7, 5, 2, 0, 1, 3, 4, 6, 8, 9, 10, 11,
            12, 13, 14, 15,
    };

    private void spawnDebris() {
        int debrisFrame = isHorizontal ? HORIZONTAL_DEBRIS_FRAME : VERTICAL_DEBRIS_FRAME;
        int baseXVel = isHorizontal ? HORIZONTAL_BREAK_X_VEL : VERTICAL_BREAK_X_VEL;
        int baseYVel = isHorizontal ? HORIZONTAL_BREAK_Y_VEL : VERTICAL_BREAK_Y_VEL;
        int[][] offsets = isHorizontal ? HORZ_DEBRIS_OFFSETS : VERT_DEBRIS_OFFSETS;

        // ROM: d1 = widthOrHeight >> 2, then subq #1 for dbf.
        // dbf loops d1+1 times, so total count = widthOrHeight / 4.
        // Size 0 (0x20): 8 pieces. Size 1 (0x30): 12. Size 2 (0x40): 16.
        int debrisCount = widthOrHeight / 4;
        if (debrisCount > offsets.length) debrisCount = offsets.length;

        // ROM: sub_1F188 — first iteration uses the parent object itself (a0),
        // subsequent iterations allocate new objects. In our engine, the parent
        // is marked broken and stops rendering, so all pieces are children.
        for (int i = 0; i < debrisCount; i++) {
            int childX = x + offsets[i][0];
            int childY = y + offsets[i][1];
            int delay = (i < DEBRIS_DELAYS.length) ? DEBRIS_DELAYS[i] : 0;

            BreakableBarDebris debris = new BreakableBarDebris(
                    childX, childY, debrisFrame, baseXVel, baseYVel, delay);
            spawnDynamicObject(debris);
        }
    }

    private void markRemembered() {
        try {
            var om = services().objectManager();
            ObjectLifetimeOps.markSpawnRemembered(om, spawn);
        } catch (Exception e) {
            // Safe fallback
        }
    }

    // ===== Rendering =====

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        if (broken) return;

        PatternSpriteRenderer renderer = getRenderer(ART_KEY);
        if (renderer != null) {
            renderer.drawFrameIndex(mappingFrame, x, y, false, false);
        }
    }

    @Override
    public void appendDebugRenderCommands(DebugRenderContext ctx) {
        if (broken) return;

        // Draw bar extents
        float r = (captured[0] || captured[1]) ? 0.0f : 0.3f;
        float g = (captured[0] || captured[1]) ? 1.0f : 0.8f;
        float b = (captured[0] || captured[1]) ? 0.0f : 1.0f;

        if (isHorizontal) {
            int left = x - halfExtent;
            int right = x + halfExtent;
            ctx.drawLine(left, y - 2, right, y - 2, r, g, b);
            ctx.drawLine(left, y + 2, right, y + 2, r, g, b);
            ctx.drawLine(left, y - 2, left, y + 2, r, g, b);
            ctx.drawLine(right, y - 2, right, y + 2, r, g, b);
        } else {
            int top = y - halfExtent;
            int bottom = y + halfExtent;
            ctx.drawLine(x - 2, top, x - 2, bottom, r, g, b);
            ctx.drawLine(x + 2, top, x + 2, bottom, r, g, b);
            ctx.drawLine(x - 2, top, x + 2, top, r, g, b);
            ctx.drawLine(x - 2, bottom, x + 2, bottom, r, g, b);
        }

        // Draw center cross
        ctx.drawCross(x, y, 4, 0.5f, 0.5f, 0.5f);

        // Subtype label
        StringBuilder sb = ctx.getLabelBuilder();
        sb.append("Bar ");
        sb.append(isHorizontal ? 'H' : 'V');
        sb.append(" s").append(sizeIndex);
        if (hasTimer) {
            sb.append(" t").append(breakTimer);
        }
        if ((captured[0] || captured[1])) {
            sb.append(" [CAP]");
        }
        ctx.drawWorldLabel(x, y - 12, 0, sb.toString(), DebugColor.CYAN);
    }

    @Override
    public int getX() { return x; }

    @Override
    public int getY() { return y; }

    @Override
    public int getPriorityBucket() {
        return RenderPriority.clamp(PRIORITY);
    }

    // ===================================================================
    // Debris inner class
    // ===================================================================

    /**
     * Debris fragment spawned when the breakable bar is destroyed.
     * Each fragment has an initial frame delay before it starts moving.
     * Once moving, it applies gravity until it goes off screen.
     * <p>
     * ROM: sub_1F188 spawns children; loc_1EF3E handles debris update
     * (frame delay countdown, MoveSprite2 + gravity of 8/frame).
     */
    public static class BreakableBarDebris extends AbstractObjectInstance
            implements SpawnTrailingZeroIntsRewindRecreatable {

        private int currentX;
        private int currentY;
        private int debrisFrame;
        private int frameDelay;
        private final SubpixelMotion.State motionState;

        public BreakableBarDebris(int spawnX, int spawnY, int debrisFrame,
                                  int xVel, int yVel, int frameDelay) {
            super(new ObjectSpawn(spawnX, spawnY, Sonic3kObjectIds.HCZ_BREAKABLE_BAR,
                    debrisFrame, 0, false, 0), "BreakableBarDebris");
            this.currentX = spawnX;
            this.currentY = spawnY;
            this.debrisFrame = debrisFrame;
            this.frameDelay = frameDelay;
            this.motionState = new SubpixelMotion.State(
                    spawnX, spawnY, 0, 0, xVel, yVel);
        }

        private BreakableBarDebris(ObjectSpawn spawn, int ignored) {
            super(spawn, "BreakableBarDebris");
            this.currentX = spawn.x();
            this.currentY = spawn.y();
            this.debrisFrame = spawn.subtype() & 0xFF;
            this.frameDelay = 0;
            this.motionState = new SubpixelMotion.State(spawn.x(), spawn.y(), 0, 0, 0, 0);
        }

        @Override
        public int getX() { return currentX; }

        @Override
        public int getY() { return currentY; }

        @Override
        public void update(int vIntRunCount, PlayableEntity playerEntity) {
            // ROM: frame delay countdown before movement starts
            if (frameDelay > 0) {
                frameDelay--;
                return;
            }

            // ROM: jsr (MoveSprite2).l — apply velocity without gravity first
            motionState.x = currentX;
            motionState.y = currentY;
            SubpixelMotion.moveSprite2(motionState);

            // ROM: addi.w #8,y_vel(a1) — apply gravity to Y velocity
            motionState.yVel += DEBRIS_GRAVITY;

            currentX = motionState.x;
            currentY = motionState.y;

            // ROM: tst.b render_flags(a0) / bpl — delete when off screen
            if (!isOnScreen(128)) {
                setDestroyed(true);
            }
        }

        @Override
        public void appendRenderCommands(List<GLCommand> commands) {
            PatternSpriteRenderer renderer = getRenderer(Sonic3kObjectArtKeys.HCZ_BREAKABLE_BAR);
            if (renderer != null) {
                renderer.drawFrameIndex(debrisFrame, currentX, currentY, false, false);
            }
        }

        @Override
        public int getPriorityBucket() {
            // ROM: move.w #$80,priority(a0) — debris gets low priority
            return RenderPriority.clamp(1);
        }

        @Override
        public boolean isPersistent() {
            return !isDestroyed();
        }
    }
}
