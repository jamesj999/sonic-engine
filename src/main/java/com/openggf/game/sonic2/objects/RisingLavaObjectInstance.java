package com.openggf.game.sonic2.objects;

import com.openggf.configuration.SonicConfiguration;
import com.openggf.configuration.SonicConfigurationService;
import com.openggf.game.PlayableEntity;
import com.openggf.game.sonic2.runtime.HtzRuntimeState;
import com.openggf.graphics.GLCommand;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.objects.SolidContact;
import com.openggf.level.objects.SolidObjectListener;
import com.openggf.level.objects.SolidObjectParams;
import com.openggf.level.objects.SolidObjectProvider;
import com.openggf.level.objects.SlopedSolidProvider;
import com.openggf.game.DamageCause;
import com.openggf.sprites.playable.AbstractPlayableSprite;

import java.util.List;

import static org.lwjgl.opengl.GL11.GL_TRIANGLE_FAN;

/**
 * Object 30 - Large rising lava platform during earthquake in HTZ.
 * <p>
 * This is an INVISIBLE solid platform whose Y position is controlled by the
 * Camera_BG_Y_offset value from the HTZ earthquake system. Players can stand
 * on these platforms, and for subtype 6, they get hurt while standing.
 * <p>
 * <b>Disassembly Reference:</b> s2.asm lines 49027-49191 (Obj30)
 *
 * <h3>Subtypes</h3>
 * <table border="1">
 *   <tr><th>Subtype</th><th>Width</th><th>Height D2/D3</th><th>Behavior</th></tr>
 *   <tr><td>0</td><td>0xC0 (192)</td><td>0x80/0x81</td><td>Regular solid</td></tr>
 *   <tr><td>2</td><td>0xC0 (192)</td><td>0x80/0x81</td><td>Regular solid</td></tr>
 *   <tr><td>4</td><td>0xC0 (192)</td><td>0x78/0x79</td><td>Hurts players standing on it (jsrto falls through)</td></tr>
 *   <tr><td>6</td><td>0xE0 (224)</td><td>0x78/0x79</td><td>Hurts players standing on it (explicit bra.s)</td></tr>
 *   <tr><td>8</td><td>0xC0 (192)</td><td>0x2E</td><td>Sloped solid</td></tr>
 * </table>
 *
 * <h3>Usage Statistics (from OBJECT_CHECKLIST.md)</h3>
 * <ul>
 *   <li>HTZ1: 3 instances [0x00, 0x02, 0x04]</li>
 *   <li>HTZ2: 4 instances [0x06, 0x08]</li>
 * </ul>
 *
 * @see HtzRuntimeState#cameraBgYOffset() For earthquake Y offset
 */
public class RisingLavaObjectInstance extends AbstractObjectInstance
        implements SolidObjectProvider, SlopedSolidProvider, SolidObjectListener, RewindRecreatable {

    // ========================================================================
    // ROM Constants - Width table from Obj30_Widths (line 49042)
    // ========================================================================

    /** Width values indexed by subtype. */
    private static final int[] SUBTYPE_WIDTHS = {
            0xC0,  // Subtype 0
            0x00,  // Subtype 1 (padding)
            0xC0,  // Subtype 2
            0x00,  // Subtype 3 (padding)
            0xC0,  // Subtype 4
            0x00,  // Subtype 5 (padding)
            0xE0,  // Subtype 6
            0x00,  // Subtype 7 (padding)
            0xC0,  // Subtype 8
            0x00   // Subtype 9 (padding)
    };

    // ========================================================================
    // ROM Constants - Collision parameters
    // ========================================================================

    /** Standard D1 (half-width) for most subtypes: 0xCB */
    private static final int HALF_WIDTH_STANDARD = 0xCB;

    /** D1 (half-width) for subtype 6: 0xEB */
    private static final int HALF_WIDTH_SUBTYPE_6 = 0xEB;

    /** D2/D3 height values for subtypes 0, 2 */
    private static final int HEIGHT_D2_NORMAL = 0x80;
    private static final int HEIGHT_D3_NORMAL = 0x81;

    /** D2/D3 height values for subtypes 4, 6 */
    private static final int HEIGHT_D2_LOWER = 0x78;
    private static final int HEIGHT_D3_LOWER = 0x79;

    /** D2 height for sloped subtype 8 */
    private static final int HEIGHT_D2_SLOPED = 0x2E;

    /** Obj30_Init route split threshold (ROM: cmpi.w #$380,(Camera_Y_pos).w). */
    private static final int ROUTE_SPLIT_CAMERA_Y = 0x380;

    // ========================================================================
    // ROM Constants - Slope data from Obj30_SlopeData (lines 49169-49187)
    // 192 bytes: heights from +48 to -48 for the sloped platform
    // ========================================================================

    private static final byte[] SLOPE_DATA = {
            48, 48, 48, 48, 48, 48, 48, 48, 47, 47, 46, 46,
            45, 45, 44, 44, 43, 43, 42, 42, 41, 41, 40, 40,
            39, 39, 38, 38, 37, 37, 36, 36, 35, 35, 34, 34,
            33, 33, 32, 32, 31, 31, 30, 30, 29, 29, 28, 28,
            27, 27, 26, 26, 25, 25, 24, 24, 23, 23, 22, 22,
            21, 21, 20, 20, 19, 19, 18, 18, 17, 17, 16, 16,
            15, 15, 14, 14, 13, 13, 12, 12, 11, 11, 10, 10,
            9, 9, 8, 8, 7, 7, 6, 6, 5, 5, 4, 4,
            3, 3, 2, 2, 1, 1, 0, 0, -1, -1, -2, -2,
            -3, -3, -4, -4, -5, -5, -6, -6, -7, -7, -8, -8,
            -9, -9, -10, -10, -11, -11, -12, -12, -13, -13, -14, -14,
            -15, -15, -16, -16, -17, -17, -18, -18, -19, -19, -20, -20,
            -21, -21, -22, -22, -23, -23, -24, -24, -25, -25, -26, -26,
            -27, -27, -28, -28, -29, -29, -30, -30, -31, -31, -32, -32,
            -33, -33, -34, -34, -35, -35, -36, -36, -37, -37, -38, -38,
            -39, -39, -40, -40, -41, -41, -42, -42, -43, -43, -44, -44,
            -45, -45, -46, -46, -47, -47, -48, -48, -48, -48, -48, -48
    };

    // ========================================================================
    // Instance State
    // ========================================================================

    private int subtype;
    private int widthPixels;
    private int baseY;
    private int baseX;
    private boolean routeEnabled;
    private boolean routeChecked;
    private int currentY;

    /** Cached frame counter from last update for use in onSolidContact. */
    private int lastVIntRunCount;

    public RisingLavaObjectInstance(ObjectSpawn spawn, String name) {
        super(spawn, name);

        this.subtype = spawn.subtype() & 0xFF;
        this.baseY = spawn.y();
        this.baseX = spawn.x();
        this.currentY = baseY;

        // Get width from table (subtype is index)
        int widthIndex = Math.min(subtype, SUBTYPE_WIDTHS.length - 1);
        this.widthPixels = SUBTYPE_WIDTHS[widthIndex];

        updateDynamicSpawn(baseX, currentY);
    }

    @Override
    public RisingLavaObjectInstance recreateForRewind(RewindRecreateContext ctx) {
        return new RisingLavaObjectInstance(ctx.spawn(), getName());
    }

    /**
     * ROM Obj30_Init route filtering:
     * - Subtype 6: active only when Camera_Y >= $380 (bottom route)
     * - Subtype >=8: active only when Camera_Y < $380 (top route)
     * - Other subtypes: always active
     */
    private static boolean isEnabledForCurrentRoute(int subtype, int cameraY) {
        if (subtype < 6) {
            return true;
        }
        if (subtype == 6) {
            return cameraY >= ROUTE_SPLIT_CAMERA_Y;
        }
        return cameraY < ROUTE_SPLIT_CAMERA_Y;
    }

    // ========================================================================
    // Update Logic
    // ========================================================================

    private void ensureInitialized() {
        if (routeChecked) {
            return;
        }
        routeChecked = true;
        var cameraInst = services().camera();
        this.routeEnabled = isEnabledForCurrentRoute(subtype, cameraInst != null ? cameraInst.getY() : 0);
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        ensureInitialized();
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        if (!routeEnabled) {
            return;
        }

        // Store frame counter for use in onSolidContact callback
        this.lastVIntRunCount = vIntRunCount;

        // ROM: Obj30_Main (s2.asm:49083-49086)
        // y_pos = objoff_32 + Camera_BG_Y_offset (ONLY bgYOffset, no shake)
        // Ripple shake is a global screen-space effect applied via Camera shake offsets,
        // so objects don't add it to their world positions.
        int bgYOffset = services().zoneRuntimeRegistry()
                .currentAs(HtzRuntimeState.class)
                .map(HtzRuntimeState::cameraBgYOffset)
                .orElseThrow(() -> new IllegalStateException("HTZ runtime state not installed"));
        currentY = baseY + bgYOffset;

        updateDynamicSpawn(baseX, currentY);

        // Note: Hurt check for subtype 6 is handled in onSolidContact callback
        // when the player is standing on this platform
    }

    /**
     * Apply hurt to the player (from lava contact).
     * ROM: JmpTo_Touch_ChkHurt (line 49136)
     */
    private void applyHurt(AbstractPlayableSprite player) {
        if (player.getInvulnerable()) {
            return;
        }

        // ROM: Hurt_Sidekick - CPU Tails only gets knockback, no ring scatter or death
        if (player.isCpuControlled()) {
            player.applyHurt(getX(), DamageCause.FIRE);
            return;
        }
        // Check if player has rings
        boolean hadRings = player.getRingCount() > 0;
        if (hadRings && !player.hasShield()) {
            services().spawnLostRings(player, lastVIntRunCount);
        }
        // Apply hurt - lava uses DamageCause.FIRE for fire shield immunity
        player.applyHurtOrDeath(getX(), DamageCause.FIRE, hadRings);
    }

    // ========================================================================
    // Position Accessors
    // ========================================================================

    @Override
    public int getX() {
        return baseX;
    }

    @Override
    public int getY() {
        return currentY;
    }
    // ========================================================================
    // SolidObjectProvider Implementation
    // ========================================================================

    @Override
    public SolidObjectParams getSolidParams() {
        // Select parameters based on subtype
        int halfWidth;
        int airHalfHeight;
        int groundHalfHeight;

        switch (subtype) {
            case 0:
            case 2:
                // loc_23972: d1=$CB, d2=$80, d3=$81
                halfWidth = HALF_WIDTH_STANDARD;
                airHalfHeight = HEIGHT_D2_NORMAL;
                groundHalfHeight = HEIGHT_D3_NORMAL;
                break;
            case 4:
                // loc_2398A: d1=$CB, d2=$78, d3=$79
                halfWidth = HALF_WIDTH_STANDARD;
                airHalfHeight = HEIGHT_D2_LOWER;
                groundHalfHeight = HEIGHT_D3_LOWER;
                break;
            case 6:
                // loc_239D0: d1=$EB, d2=$78, d3=$79
                halfWidth = HALF_WIDTH_SUBTYPE_6;
                airHalfHeight = HEIGHT_D2_LOWER;
                groundHalfHeight = HEIGHT_D3_LOWER;
                break;
            case 8:
                // loc_239EA: d1=SlopeData.end-SlopeData-1=191, d2=$2E
                // For sloped solids, the width is derived from slope data length
                halfWidth = SLOPE_DATA.length - 1; // 191
                airHalfHeight = HEIGHT_D2_SLOPED;
                groundHalfHeight = HEIGHT_D2_SLOPED + 1;
                break;
            default:
                // Fallback to normal
                halfWidth = HALF_WIDTH_STANDARD;
                airHalfHeight = HEIGHT_D2_NORMAL;
                groundHalfHeight = HEIGHT_D3_NORMAL;
                break;
        }

        return SolidObjectParams.of(halfWidth, airHalfHeight, groundHalfHeight);
    }

    @Override
    public boolean isSolidFor(PlayableEntity playerEntity) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        if (!routeEnabled) {
            return false;
        }

        // ROM Obj30_Main runs Obj30_Modes (SolidObject_Always / DropOnFloor /
        // hurt-supported-player handling) before testing Screen_Shaking_Flag_HTZ
        // for MarkObjGone3 (docs/s2disasm/s2.asm:49568-49581,49635-49642).
        return true;
    }

    private boolean isHtzEarthquakeActive() {
        return services().zoneRuntimeRegistry()
                .currentAs(HtzRuntimeState.class)
                .map(HtzRuntimeState::earthquakeActive)
                .orElse(false);
    }

    @Override
    public boolean isTopSolidOnly() {
        return false;
    }

    @Override
    public boolean bypassesOffscreenSolidGate() {
        // Obj30 subtypes call SolidObject_Always before DropOnFloor / hurt
        // handling (docs/s2disasm/s2.asm:49598-49604,49635-49642).
        return true;
    }

    // ========================================================================
    // SlopedSolidProvider Implementation (for subtype 8)
    // ========================================================================

    @Override
    public byte[] getSlopeData() {
        if (subtype == 8) {
            return SLOPE_DATA;
        }
        return null;
    }

    @Override
    public boolean isSlopeFlipped() {
        return false;
    }

    // ========================================================================
    // SolidObjectListener Implementation
    // ========================================================================

    @Override
    public void onSolidContact(PlayableEntity playerEntity, SolidContact contact, int frameCounter) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        // ROM: Subtypes 4 and 6 fall through to Obj30_HurtSupportedPlayers (s2.asm:49151).
        // Subtype 4 uses jsrto (JSR) for DropOnFloor at line 49149, so execution returns
        // and falls directly into the hurt routine. Subtype 6 has an explicit bra.s to it.
        // Subtypes 0/2/8 use jmpto (JMP = tail call) so they never reach the hurt code.
        if ((subtype == 4 || subtype == 6) && contact.standing()) {
            if (!player.getInvulnerable()) {
                applyHurt(player);
                // Obj30 hurts supported players after SolidObject_Always and DropOnFloor.
                // Hurt_Sidekick does not clear Status_OnObj; the next solid pass owns unseating.
                player.setOnObject(true);
            }
        }
    }

    @Override
    public boolean dropOnFloor() {
        return true;
    }

    @Override
    public boolean usesSidekickCpuCurrentPushObjectOrderInputDelay(PlayableEntity player) {
        // Obj30 subtype 6 runs SolidObject_Always, DropOnFloor, then the
        // supported-player hurt path after TailsCPU_Normal has already sampled
        // Ctrl_2 (docs/s2disasm/s2.asm:49636-49643,39291-39294). The HTZ2
        // lower-route platform keeps that object-order push visible to the CPU
        // slot only on the left/probed side of the support. On the right side,
        // TailsCPU_Normal keeps the already-loaded d1 history word from the same
        // slot as d4 (s2.asm:39285-39300); re-reading the adjacent older input
        // manufactures stale LEFT on HTZ2 f4442.
        return subtype == 6
                && player != null
                && player.isCpuControlled()
                && ((short) (player.getCentreX() - getX())) <= 0;
    }

    @Override
    public boolean preservesSidekickCpuPushGraceFromInteractSlot(PlayableEntity player) {
        // HTZ2 Obj30 subtype 6 keeps its SST status visible through the
        // interact(a0) slot when TailsCPU_Normal tests Status_Push at
        // s2.asm:39297-39300, before Obj30's later SolidObject_Always /
        // DropOnFloor pass refreshes supported-player state (s2.asm:49635-49642).
        return subtype == 6
                && player != null
                && player.isCpuControlled();
    }

    @Override
    public boolean preservesSidekickDelayedLeaderPushFromInteractSlot(PlayableEntity player) {
        // Same Obj30 object-order window, but for the delayed d4 Status_Push
        // fall-through at s2.asm:39297-39300 after the current push grace has
        // decayed and Tails is stationary on the released interact target.
        return preservesSidekickCpuPushGraceFromInteractSlot(player);
    }

    @Override
    public int sidekickCpuPushGraceMinimumFramesFromInteractSlot(PlayableEntity player) {
        return preservesSidekickCpuPushGraceFromInteractSlot(player) ? 0 : Integer.MAX_VALUE;
    }

    @Override
    public int sidekickCpuPushGraceMaximumFramesFromInteractSlot(PlayableEntity player) {
        return preservesSidekickCpuPushGraceFromInteractSlot(player) ? 9 : Integer.MIN_VALUE;
    }

    @Override
    public int getOnScreenHalfWidth() {
        return widthPixels;
    }

    @Override
    public int getBalanceWidthPixels() {
        // Obj30_Init writes Obj30_Widths[subtype] to width_pixels(a0)
        // before the player balance routines read it (docs/s2disasm/s2.asm:49545-49547).
        return widthPixels;
    }

    @Override
    public int getOnScreenHalfHeight() {
        return getSolidParams().groundHalfHeight();
    }

    @Override
    public boolean keepsOnObjWhenAirborneAfterSameFrameStandingContact(PlayableEntity player) {
        // Obj30 subtype 4/6 runs SolidObject_Always/DropOnFloor before
        // Obj30_HurtSupportedPlayers; Hurt_Sidekick then sets InAir without
        // clearing OnObj (docs/s2disasm/s2.asm:49603-49615,49635-49642,85468-85472).
        return subtype == 4 || subtype == 6;
    }

    // ========================================================================
    // Rendering (Debug only - this is an invisible object)
    // ========================================================================

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        if (!routeEnabled) {
            return;
        }

        // Invisible during normal gameplay - only render in debug mode
        SonicConfigurationService config = config();
        if (!config.getBoolean(SonicConfiguration.DEBUG_VIEW_ENABLED)) {
            return;
        }

        // Only render when HTZ earthquake is active
        if (!isHtzEarthquakeActive()) {
            return;
        }

        // Debug rendering: red/orange box showing collision area
        SolidObjectParams params = getSolidParams();
        int halfWidth = params.halfWidth();
        int halfHeight = params.groundHalfHeight();

        int x1 = baseX - halfWidth;
        int y1 = currentY - halfHeight;
        int x2 = baseX + halfWidth;
        int y2 = currentY + halfHeight;

        // Red for hurt subtypes, orange for normal
        float r = (subtype == 6) ? 1.0f : 1.0f;
        float g = (subtype == 6) ? 0.0f : 0.5f;
        float b = 0.0f;

        // Semi-transparent fill
        commands.add(new GLCommand(
                GLCommand.CommandType.RECTI,
                GL_TRIANGLE_FAN,
                GLCommand.BlendType.ONE_MINUS_SRC_ALPHA,
                r, g, b, 0.3f,
                x1, y1, x2, y2));

        // Solid border
        commands.add(new GLCommand(
                GLCommand.CommandType.RECTI,
                GL_TRIANGLE_FAN,
                r, g, b,
                x1, y1, x2, y1 + 1));
        commands.add(new GLCommand(
                GLCommand.CommandType.RECTI,
                GL_TRIANGLE_FAN,
                r, g, b,
                x1, y2 - 1, x2, y2));
        commands.add(new GLCommand(
                GLCommand.CommandType.RECTI,
                GL_TRIANGLE_FAN,
                r, g, b,
                x1, y1, x1 + 1, y2));
        commands.add(new GLCommand(
                GLCommand.CommandType.RECTI,
                GL_TRIANGLE_FAN,
                r, g, b,
                x2 - 1, y1, x2, y2));
    }

    @Override
    public int getPriorityBucket() {
        return 0; // Low priority since usually invisible
    }
}
