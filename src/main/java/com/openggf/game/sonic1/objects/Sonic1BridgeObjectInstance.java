package com.openggf.game.sonic1.objects;

import com.openggf.data.RomByteReader;
import com.openggf.game.solid.PlayerSolidContactResult;
import com.openggf.game.solid.SolidCheckpointBatch;
import com.openggf.game.sonic1.constants.Sonic1Constants;
import com.openggf.graphics.GLCommand;
import com.openggf.graphics.RenderPriority;
import com.openggf.level.LevelManager;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.ObjectPlayerParticipationPolicy;
import com.openggf.level.objects.ObjectRenderManager;
import com.openggf.level.objects.ObjectServices;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.SpawnRewindRecreatable;
import com.openggf.level.objects.SlopedSolidProvider;
import com.openggf.level.objects.SolidContact;
import com.openggf.level.objects.SolidExecutionMode;
import com.openggf.level.objects.SolidObjectListener;
import com.openggf.level.objects.SolidObjectParams;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.physics.TrigLookupTable;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.game.PlayableEntity;

import java.util.List;

/**
 * Sonic 1 Bridge (Object 0x11) - A multi-segment log bridge with ROM-accurate
 * sine-based depression physics.
 * <p>
 * When Sonic stands on the bridge, it sags downward using a sine ramp-up
 * animation and ROM lookup tables for depression distribution. When Sonic
 * leaves, it springs back using a sine ramp-down.
 * <p>
 * Reference: docs/s1disasm/_incObj/11 Bridge (part 1).asm through (part 3).asm
 * <p>
 * Subtype = number of log segments (e.g., 8, 10, 12, 14, 16).
 * Each log is 16 pixels wide. The bridge is centered on the spawn X position.
 */
public class Sonic1BridgeObjectInstance extends AbstractObjectInstance
        implements SlopedSolidProvider, SolidObjectListener, SpawnRewindRecreatable {

    private static final int LOG_WIDTH = 16;  // pixels per log segment
    private static final int LOG_HALF_HEIGHT = 8; // platform surface is 8px above segment center

    // From disassembly: depression angle range 0 to $40, changing by 4 per frame
    private static final int MAX_DEPRESSION_ANGLE = 0x40; // 64
    private static final int DEPRESSION_RATE = 4; // +/- per frame

    // From disassembly: obPriority = 3
    private static final int PRIORITY = 3;

    private static final ObjectPlayerParticipationPolicy PLAYER_PARTICIPATION =
            ObjectPlayerParticipationPolicy.ALL_ENGINE_PLAYERS;
    private static final int BEND_Y_MAX_ROWS = 17;
    private static final int BEND_ALIGN_ROWS = 16;
    private static final int BEND_COLUMNS = 16;

    // State fields
    private int logCount;            // Number of log segments
    private int depressionAngle;     // 0 to MAX_DEPRESSION_ANGLE, ramps +/- DEPRESSION_RATE/frame
    private int playerLogIndex;      // Which log Sonic is standing on (0-based from left)
    private int[] logYOffsets;       // Current Y offset for each log (pixels below base Y)
    private byte[] slopeData;        // Slope data for collision system
    private boolean playerOnBridge;  // Whether player is currently on the bridge
    private BridgeBendData bendData;
    // ROM Bri_Action runs in routine 2 (Bri_Solid -> Platform3); on the catch
    // frame Platform3 bumps the bridge to routine 4 (addq.b #2,obRoutine(a0),
    // sub PlatformObject.asm:42) and only Bri_MoveSonic finalises Sonic's Y on
    // the FOLLOWING object pass (11 Bridge.asm:163-177). The BizHawk recorder
    // samples the player at V-int (before that frame's object loop), so the ROM
    // land detected during frame N first appears in the frame N+1 sample. This
    // engine's checkpointAll() applies the land in the same pass it is detected,
    // surfacing it one frame early. landingDeferred reproduces the ROM's
    // detect-this-pass / apply-next-pass staging for a fresh airborne catch.
    private boolean landingDeferred;
    // ROM Bri_Main allocates one object RAM slot per child log during
    // ExecuteObjects (docs/s1disasm/_incObj/"11 GHZ Bridge.asm":51-97): the
    // .loopBuildBridge dbf runs subtype-1 times (subq.b #2,d1 before dbf), one
    // fewer than the log count because the parent IS the middle log. Each child
    // is a display-only log at routine $A. This engine draws every log from the
    // parent, but the slots must still be reserved so the ascending FindFreeObj
    // scan — and therefore every later dynamic object's slot number, which feeds
    // the (v_vblank_byte + 127 - slot) & 3 cadence gates — matches the ROM.
    private boolean childSlotsReserved;

    public Sonic1BridgeObjectInstance(ObjectSpawn spawn) {
        this(spawn, null);
    }

    Sonic1BridgeObjectInstance(ObjectSpawn spawn, BridgeBendData bendData) {
        super(spawn, "Bridge");
        this.bendData = bendData;
        this.logCount = Math.max(1, spawn.subtype() & 0xFF);
        if (this.logCount > 16) {
            this.logCount = 16; // Max 16 segments (Bri_Data table limit)
        }
        this.logYOffsets = new int[logCount];
        this.slopeData = new byte[getHalfWidth() + 1];
    }

    /**
     * Number of child log objects ROM Bri_Main creates. It works from the raw
     * subtype, not the bend-table-clamped log count: d1 = subtype,
     * subq.b #2,d1, bcs (no children for a 1-log bridge), then dbf, giving
     * subtype-1 iterations.
     */
    private int romChildLogCount() {
        return Math.max(0, (spawn.subtype() & 0xFF) - 1);
    }

    /**
     * Reserves the child-log object RAM slots ROM Bri_Main allocates on its
     * first pass (docs/s1disasm/_incObj/"11 GHZ Bridge.asm":51-97). The ROM uses
     * FindFreeObj (the non-FixBugs branch), an ascending first-free scan, and
     * aborts the loop when object RAM is full — {@code allocateChildSlots}
     * matches both.
     */
    private void reserveChildSlots() {
        if (childSlotsReserved || romChildLogCount() <= 0) {
            return;
        }
        childSlotsReserved = true;
        ObjectServices svc = tryServices();
        if (svc == null || svc.objectManager() == null || spawn == null) {
            return;
        }
        svc.objectManager().allocateChildSlots(spawn, romChildLogCount());
    }

    static BridgeBendData loadBendData(RomByteReader reader) {
        return new BridgeBendData(
                reader.slice(Sonic1Constants.BRIDGE_BEND_Y_MAX_ADDR, BEND_Y_MAX_ROWS * BEND_COLUMNS),
                reader.slice(Sonic1Constants.BRIDGE_BEND_ALIGN_ADDR, BEND_ALIGN_ROWS * BEND_COLUMNS));
    }

    private BridgeBendData bendData() {
        if (bendData != null) {
            return bendData;
        }
        try {
            bendData = loadBendData(services().romReader());
            return bendData;
        } catch (Exception e) {
            throw new IllegalStateException("Unable to load Sonic 1 bridge bend data from ROM", e);
        }
    }

    private int getHalfWidth() {
        // Half the visual/collision width: logCount * 16 / 2 = logCount * 8
        return (logCount * LOG_WIDTH) / 2;
    }

    // ---- SolidObjectProvider / SlopedSolidProvider ----

    @Override
    public SolidObjectParams getSolidParams() {
        // ROM Bri_Solid: d1 = subtype*8+8 (origin shift), d2 = subtype*16 (full width).
        // The range is [bridgeX-N*8-8, bridgeX+N*8-8) — asymmetric, centered at bridgeX-8.
        // We replicate this with halfWidth = N*8 (half the full width) and offsetX = -8
        // to shift the collision center 8px left.
        int halfWidth = logCount * 8;
        // ROM Plat_NoXCheck: subq.w #8,d0 — landing/riding surface is at obY-8.
        // offsetY = -8 replicates this for both the landing and riding paths.
        //
        // halfHeight = 0: ROM Platform3 does NOT use the object's half-height for
        // landing position — only the fixed -8 from Plat_NoXCheck and the player's
        // yRadius. Our landing formula includes halfHeight in the position
        // calculation, so we set it to 0 to avoid double-counting the -8.
        // The riding path ignores halfHeight for sloped objects (uses slope sample).
        return SolidObjectParams.of(halfWidth, 0, 0, -8, -8);
    }

    @Override
    public boolean isTopSolidOnly() {
        return true;
    }

    /**
     * The bridge's SST {@code obActWid}, which {@code Sonic_Balance} reads off
     * the stood-on object (docs/s1disasm/_incObj/01 Sonic.asm:423).
     *
     * <p>The player stands on the <em>parent</em>: the child logs are routine
     * {@code $A} ({@code Bri_ChildLog}, display only) and it is
     * {@code Bri_CheckOnBridge} in the parent's own routine that sets
     * {@code obRoutine = 4} ({@code Bri_StoodOn})
     * (docs/s1disasm/_incObj/11 GHZ Bridge.asm:85,100,111-118). So the byte that
     * matters is the parent's, not the logs' {@code #16/2} = 8 at {@code :94}.
     *
     * <p><b>{@code FixBugs} = 0.</b> {@code Bri_Main} writes {@code #256/2} = 128
     * on the un-fixed branch and {@code #16/2} under the flag ({@code :32-39}).
     * The listing's own comment says the wide value "is way too large, causing
     * the bridge to potentially screen-wrap" and was "likely forgotten when the
     * bridge was turned into individual 16px log objects". However plainly that
     * reads as an oversight, 128 is what the shipped ROM stores and therefore
     * what every recorded trace reflects.
     *
     * <p>Required rather than tidy: {@code getBalanceWidthPixels()} falls back to
     * {@code getSolidParams().halfWidth()} for top-solid objects, which here is
     * {@code logCount * 8} — the collision half-width from
     * {@code Bri_CheckOnBridge}'s {@code d1 = bridge_children*8 + 8}
     * ({@code :122-126}), a different quantity from {@code obActWid}. The
     * consequence is reachable and not marginal: with the ROM's 128 the balance
     * window {@code [4, 2*128-4)} is wider than any bridge, so the ROM never
     * balances on one, while the inherited {@code logCount * 8} puts a player
     * standing on the end log at {@code d1 = 0} and starts the animation.
     */
    @Override
    public int getBalanceWidthPixels() {
        return 256 / 2;
    }

    @Override
    public boolean usesCollisionHalfWidthForTopLanding() {
        // ROM Bri_Solid passes the already-final PlatformObject width in d1/d2.
        // Do not apply the generic SolidObject +$B landing-width narrowing.
        return true;
    }

    @Override
    public boolean forceAirOnRideExit() {
        return false;
    }

    @Override
    public byte[] getSlopeData() {
        return slopeData;
    }

    @Override
    public boolean isSlopeFlipped() {
        return false;
    }

    @Override
    public int getSlopeBaseline() {
        // Bridge slope offsets are absolute (0 = flat), not relative to first sample
        return 0;
    }

    @Override
    public void onSolidContact(PlayableEntity playerEntity, SolidContact contact, int frameCounter) {
        // Standing detection is handled via manual checkpoints in update().
    }

    @Override
    public SolidExecutionMode solidExecutionMode() {
        return SolidExecutionMode.MANUAL_CHECKPOINT;
    }

    // ---- Update logic ----

    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        // ROM Bri_Main runs once, on the object's first ExecuteObjects pass.
        reserveChildSlots();
        if (playerOnBridge) {
            AbstractPlayableSprite currentRider = currentRidingPlayer();
            if (currentRider != null) {
                updatePlayerLogIndex(currentRider);
            }
            // Increase depression angle (ramp up)
            // From disassembly: addq.b #4,objoff_3E(a0); cmpi.b #$40,d0
            if (depressionAngle < MAX_DEPRESSION_ANGLE) {
                depressionAngle += DEPRESSION_RATE;
                if (depressionAngle > MAX_DEPRESSION_ANGLE) {
                    depressionAngle = MAX_DEPRESSION_ANGLE;
                }
            }

            // Calculate bend and move Sonic
            calculateBend();
        } else {
            // Sonic is not on bridge - spring back
            // From Bri_Action: subq.b #4,objoff_3E(a0)
            if (depressionAngle > 0) {
                depressionAngle -= DEPRESSION_RATE;
                if (depressionAngle < 0) {
                    depressionAngle = 0;
                }
                calculateBend();
            } else {
                // Fully flat - clear offsets
                for (int i = 0; i < logCount; i++) {
                    logYOffsets[i] = 0;
                }
            }
        }

        // Update slope data for collision system
        updateSlopeData();

        // ROM routine-2 -> routine-4 landing stage (see landingDeferred field).
        // On the FIRST object pass where an airborne, falling player enters the
        // Platform3 catch window (and nobody is already riding), Bri_Solid /
        // Platform3 (11 Bridge.asm:108-114; sub PlatformObject.asm:23-42) detect
        // the landing and advance the bridge to routine 4, but Sonic's landed
        // state only becomes visible to the V-int recorder on the NEXT frame's
        // sample. Mirror that by deferring the apply one pass: skip the
        // land-applying checkpoint this pass, then run it normally next pass.
        // Already-riding carry (the Bri_WalkOff/Bri_MoveSonic path) is never
        // deferred, so a standing rider keeps being carried every frame.
        if (!landingDeferred && !playerOnBridge && hasPendingAirborneCatch()) {
            landingDeferred = true;
            return;
        }
        landingDeferred = false;

        // Manual checkpoints replace the legacy post-pass callback. Already-riding
        // players update the log index before bending above; new contacts latch here
        // for the next frame's Bri_WalkOff-equivalent bend.
        SolidCheckpointBatch batch = checkpointAll();
        updateStandingState(batch);
    }

    /**
     * ROM Platform3 catch detector (read-only), used to stage the one-frame
     * landing deferral without applying the land. Mirrors the ROM checks in
     * Bri_Solid (X range + falling test) and Platform3 (Y range):
     * <ul>
     *   <li>{@code tst.w obVelY(a1); bmi Plat_Exit} — player must be falling
     *       (y_speed &gt;= 0). 11 Bridge.asm:104.</li>
     *   <li>X range {@code d1 = subtype*8 + 8}, {@code d2 = subtype*16};
     *       {@code d0 = SonicX - bridgeX + d1}; reject if {@code d0 < 0} or
     *       {@code d0 >= d2}. 11 Bridge.asm:96-114.</li>
     *   <li>Y range {@code d0 = (obY-8) - (SonicY + obHeight + 4)}; catch when
     *       {@code -0x10 <= d0 <= 0}. sub PlatformObject.asm:23-42. obHeight is
     *       the player's y_radius ($E rolling / $13 standing). 11 Bridge.asm
     *       passes the raw object Y, so the unbent spawn Y is used here.</li>
     * </ul>
     *
     * <p><b>Why the deferral is gated on {@code d0 == 0} (the exact top edge),
     * not the whole catch window.</b> The BizHawk recorder samples the player at
     * V-int (before that frame's object loop), so a land Platform3 detects during
     * frame N first becomes visible in the frame N+1 sample. The engine's
     * checkpointAll() applies the land in the same pass it is detected.
     * When the falling player's collision-box bottom lands flush with the surface
     * ({@code d0 == 0}: {@code SonicBottom + 4 == surfaceTop}) the player is only
     * just entering the window this frame, so the engine surfaces the land one
     * frame ahead of the recorder — defer one pass to realign. When the player
     * has already penetrated the surface ({@code d0 < 0}, e.g. a fast running
     * approach that skips the exact-edge integer Y), the engine and recorder
     * already agree on the land frame, so no deferral is applied.</p>
     *
     * <p>Only fires for a freshly airborne player; the standing/riding carry path
     * keeps running through checkpointAll() every frame.</p>
     */
    private boolean hasPendingAirborneCatch() {
        for (PlayableEntity candidate : services().playerQuery().playersFor(PLAYER_PARTICIPATION)) {
            if (!(candidate instanceof AbstractPlayableSprite sprite)) {
                continue;
            }
            if (!sprite.getAir()) {
                continue; // ROM routine-2 catch only seats airborne players.
            }
            // tst.w obVelY(a1); bmi Plat_Exit — must be moving down (or level).
            if (sprite.getYSpeed() < 0) {
                continue;
            }
            // Bri_Solid X-range: d1 = subtype*8 + 8, d2 = subtype*16.
            int d1 = (logCount * 8) + 8;
            int d2 = logCount * 16;
            int dx = sprite.getCentreX() - spawn.x() + d1;
            if (dx < 0 || dx >= d2) {
                continue;
            }
            // Platform3 Y-range: d0 = (obY - 8) - (SonicY + obHeight + 4).
            // Defer only the flush-edge landing (d0 == 0); see Javadoc above.
            int d0 = (spawn.y() - 8) - (sprite.getCentreY() + sprite.getYRadius() + 4);
            if (d0 == 0) {
                return true;
            }
        }
        return false;
    }

    private AbstractPlayableSprite firstStandingPlayer(SolidCheckpointBatch batch) {
        for (PlayableEntity candidate : services().playerQuery().playersFor(PLAYER_PARTICIPATION)) {
            if (candidate instanceof AbstractPlayableSprite sprite) {
                PlayerSolidContactResult result = batch.perPlayer().get(candidate);
                if (result != null && result.standingNow()) {
                    return sprite;
                }
            }
        }
        return null;
    }

    private void updateStandingState(SolidCheckpointBatch batch) {
        AbstractPlayableSprite standingPlayer = firstStandingPlayer(batch);
        playerOnBridge = standingPlayer != null;
        if (standingPlayer == null) {
            return;
        }

        updatePlayerLogIndex(standingPlayer);
    }

    private AbstractPlayableSprite currentRidingPlayer() {
        ObjectManager objectManager = services().objectManager();
        if (objectManager == null) {
            return null;
        }
        for (PlayableEntity candidate : services().playerQuery().playersFor(PLAYER_PARTICIPATION)) {
            if (candidate instanceof AbstractPlayableSprite sprite
                    && objectManager.getRidingObject(sprite) == this) {
                return sprite;
            }
        }
        return null;
    }

    private void updatePlayerLogIndex(AbstractPlayableSprite standingPlayer) {
        // From Bri_WalkOff/ExitPlatform2: d0 = (sonicX - bridgeX + logCount*8 + 8) >> 4
        // The +8 offset comes from Bri_Solid: d1 = logCount*8; addq.w #8,d1
        int relX = standingPlayer.getCentreX() - spawn.x() + (logCount * 8) + 8;
        int logIdx = relX >> 4;
        if (logIdx < 0) logIdx = 0;
        if (logIdx >= logCount) logIdx = logCount - 1;
        playerLogIndex = logIdx;
    }

    /**
     * ROM-accurate bridge bend calculation from Bri_Bend.
     * <p>
     * For each log segment, calculates:
     *   offset = ((weight + 1) * maxDepression * sin(depressionAngle)) >> 16
     * <p>
     * Segments left of (and including) the player position read weights forward
     * from Bri_Data_Align[playerLogIndex]. Segments right of the player read
     * weights backward (mirrored) from Bri_Data_Align[segmentsRightCount].
     * <p>
     * Reference: docs/s1disasm/_incObj/11 Bridge (part 3).asm
     */
    private void calculateBend() {
        BridgeBendData data = bendData();
        // CalcSine: d4 = sin(depressionAngle), range 0-256 (8.8 fixed point)
        int sinValue = getSine(depressionAngle);

        // Look up max depression from Bri_Data_Y_Max[logCount][playerLogIndex].
        int bendRow = Math.min(logCount, BEND_Y_MAX_ROWS - 1);
        int bendCol = Math.min(playerLogIndex, 15);
        int maxDepression = data.yMax(bendRow, bendCol);

        // Get weight curve for segments LEFT of Sonic (read forward)
        int weightRow = Math.min(playerLogIndex, BEND_ALIGN_ROWS - 1);

        // Process segments left of and including Sonic's position
        for (int i = 0; i <= playerLogIndex && i < logCount; i++) {
            int weight = data.align(weightRow, i);
            // From disassembly: (weight+1) * maxDepression * sinValue, then swap (>> 16)
            long offset = ((long)(weight + 1) * maxDepression * sinValue) >> 16;
            logYOffsets[i] = (int) offset;
        }

        // Process segments right of Sonic's position (mirrored weights)
        int segmentsRight = logCount - 1 - playerLogIndex;
        if (segmentsRight > 0) {
            int rightWeightRow = Math.min(segmentsRight, BEND_ALIGN_ROWS - 1);
            // Read backwards from Bri_Data_Align[rightWeightRow].
            for (int i = playerLogIndex + 1; i < logCount; i++) {
                // Mirror index: count from right edge
                int mirrorIdx = logCount - 1 - i;
                if (mirrorIdx < 0) mirrorIdx = 0;
                if (mirrorIdx > rightWeightRow) mirrorIdx = rightWeightRow;
                int weight = data.align(rightWeightRow, mirrorIdx);
                long offset = ((long)(weight + 1) * maxDepression * sinValue) >> 16;
                logYOffsets[i] = (int) offset;
            }
        }
    }

    /**
     * Updates slope data array from current log Y offsets.
     * Each entry covers 2 pixels horizontally (halfWidth samples).
     */
    private void updateSlopeData() {
        int halfWidth = getHalfWidth();
        if (slopeData == null || slopeData.length != halfWidth + 1) {
            slopeData = new byte[halfWidth + 1];
        }

        int samplesPerLog = LOG_WIDTH / 2; // 8 samples per log
        for (int k = 0; k < slopeData.length; k++) {
            int logIndex = k / samplesPerLog;
            if (logIndex >= logCount) {
                logIndex = logCount - 1;
            }
            // Negative because slope data represents "how much lower" which is
            // subtracted from the collision surface. Y increases downward, so
            // positive sag needs negative slope values.
            slopeData[k] = (byte) -logYOffsets[logIndex];
        }
    }

    /**
     * ROM-accurate CalcSine for angles 0 to $40 (0 to 90 degrees).
     * Returns 8.8 fixed-point value: 0 at angle 0, 256 ($100) at angle $40.
     * Delegates to TrigLookupTable.sinHex() which uses the ROM SINCOSLIST.
     */
    private static int getSine(int angle) {
        if (angle <= 0) return 0;
        if (angle > MAX_DEPRESSION_ANGLE) angle = MAX_DEPRESSION_ANGLE;
        return TrigLookupTable.sinHex(angle);
    }

    record BridgeBendData(byte[] yMaxData, byte[] alignData) {
        BridgeBendData {
            yMaxData = yMaxData.clone();
            alignData = alignData.clone();
        }

        int yMax(int row, int column) {
            return unsignedAt(yMaxData, row, column);
        }

        int align(int row, int column) {
            return unsignedAt(alignData, row, column);
        }

        private static int unsignedAt(byte[] data, int row, int column) {
            return Byte.toUnsignedInt(data[(row * BEND_COLUMNS) + column]);
        }
    }

    // ---- Rendering ----

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        ObjectRenderManager renderManager = services().renderManager();
        if (renderManager == null) {
            return;
        }
        PatternSpriteRenderer bridgeRenderer = renderManager.getBridgeRenderer();
        if (bridgeRenderer == null || !bridgeRenderer.isReady()) {
            return;
        }

        // From Bri_Main: d3 = obX - (length/2 * 16)
        int startX = spawn.x() - ((logCount >> 1) * LOG_WIDTH);

        for (int i = 0; i < logCount; i++) {
            int x = startX + (i * LOG_WIDTH);
            int y = spawn.y() + logYOffsets[i];
            bridgeRenderer.drawFrameIndex(0, x, y, false, false);
        }
    }

    @Override
    public int getPriorityBucket() {
        return RenderPriority.clamp(PRIORITY);
    }
}
