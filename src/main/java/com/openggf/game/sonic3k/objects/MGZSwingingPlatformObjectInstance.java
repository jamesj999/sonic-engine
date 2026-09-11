package com.openggf.game.sonic3k.objects;

import com.openggf.debug.DebugRenderContext;
import com.openggf.game.PlayableEntity;
import com.openggf.game.sonic3k.Sonic3kObjectArtKeys;
import com.openggf.graphics.GLCommand;
import com.openggf.graphics.RenderPriority;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.ObjectServices;
import com.openggf.level.objects.SolidContact;
import com.openggf.level.objects.SolidObjectListener;
import com.openggf.level.objects.SolidObjectParams;
import com.openggf.level.objects.RomObjectCodePointerProvider;
import com.openggf.level.objects.SolidObjectProvider;
import com.openggf.level.objects.SpawnRewindRecreatable;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.physics.TrigLookupTable;

import java.util.List;

/**
 * Object 0x53 - MGZ Swinging Platform.
 *
 * <p>ROM: Obj_MGZSwingingPlatform (sonic3k.asm:70459-70558).
 * A platform on a chain of 4 links that rotates continuously around its pivot
 * at a constant angular velocity of 1 angle unit per frame. The player can
 * stand on the platform piece at the end of the chain (SolidObjectTop).
 *
 * <p>Subtype = initial angle byte (0-255).
 * Status bit 0 (x-flip) reverses rotation direction.
 * Status bit 1 (y-flip) selects chain link visual (frame 0 vs frame 1).
 */
public class MGZSwingingPlatformObjectInstance extends AbstractObjectInstance
        implements SolidObjectProvider, SolidObjectListener, SpawnRewindRecreatable, RomObjectCodePointerProvider {

    /**
     * Word 0 of this object's S3K SST holds its live ROM code pointer.
     * ROM {@code Obj_MGZSwingingPlatform} is installed from the S3K object pointer table at
     * {@code $00033F84} (table read from the user-supplied ROM; the
     * label is defined at docs/skdisasm/sonic3k.asm:70464).
     * Its whole code block lies in one bank, so the HIGH word that
     * {@code sub_13EFC} latches into {@code Tails_CPU_interact} and compares
     * on the next off-screen on-object frame is {@code $0003}
     * (docs/skdisasm/sonic3k.asm:26816-26843).
     */
    @Override
    public int romObjectCodePointerHighWord() {
        return 0x0003;
    }


    private static final String ART_KEY = Sonic3kObjectArtKeys.MGZ_SWINGING_PLATFORM;

    // ROM: move.w #$200,priority(a0)
    private static final int PRIORITY_BUCKET = 4;

    // Mapping frames from Map_MGZSwingingPlatform
    private static final int FRAME_LINK = 0;     // Chain link (child mapframe default = 0)
    private static final int FRAME_PIVOT = 1;    // Pivot/anchor piece (child mapping_frame default)
    private static final int FRAME_PLATFORM = 2; // Platform piece (2 mirrored 24x24 halves)

    // ROM: mainspr_childsprites = 4
    private static final int LINK_COUNT = 4;
    private static final int ROM_CHILD_SLOT_COUNT = 1;

    // ROM: move.b #$18,width_pixels(a0)
    private static final int SOLID_HALF_WIDTH = 0x18;
    // ROM: move.b height_pixels(a0),d3; addq.w #1,d3 -> $0C + 1 = $0D
    private static final int SOLID_HALF_HEIGHT = 0x0D;

    private int pivotX;
    private int pivotY;
    private int pivotFrame; // ROM: child mapping_frame (1 default, 0 if y-flip)
    private int angleStep;  // +1 or -1

    private final int[] linkX = new int[LINK_COUNT];
    private final int[] linkY = new int[LINK_COUNT];

    private int platformX;
    private int platformY;
    private int angleByte;
    private boolean childSlotReserved;

    public MGZSwingingPlatformObjectInstance(ObjectSpawn spawn) {
        super(spawn, "MGZSwingingPlatform");
        this.pivotX = spawn.x();
        this.pivotY = spawn.y();

        // ROM: move.b subtype(a0),$34(a0) -- initial angle
        this.angleByte = spawn.subtype() & 0xFF;

        // ROM: btst #0,status(a0); neg.b $36(a0)
        boolean xFlip = (spawn.renderFlags() & 0x01) != 0;
        this.angleStep = xFlip ? -1 : 1;

        // ROM: status bit 1 selects pivot frame (set = frame 0, clear = frame 1).
        // Chain links always use frame 0 (zeroed mapframe from AllocateObjectAfterCurrent).
        boolean yFlip = (spawn.renderFlags() & 0x02) != 0;
        this.pivotFrame = yFlip ? FRAME_LINK : FRAME_PIVOT;

        updateChainPositions();
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        if (isDestroyed()) {
            return;
        }

        reserveRomChildSlot();
        updateChainPositions(playerEntity);

        // ROM: move.b $36(a0),d0; add.b d0,$34(a0) -- constant angular velocity
        angleByte = (angleByte + angleStep) & 0xFF;
    }

    @Override
    public int getReservedChildSlotCount() {
        return ROM_CHILD_SLOT_COUNT;
    }

    private void reserveRomChildSlot() {
        if (childSlotReserved || getSlotIndex() < 0) {
            return;
        }
        childSlotReserved = true;
        ObjectServices svc = tryServices();
        if (svc == null || svc.objectManager() == null) {
            return;
        }
        // ROM Obj_MGZSwingingPlatform allocates a visual child immediately
        // after the parent with AllocateObjectAfterCurrent before entering
        // loc_3403A (docs/skdisasm/sonic3k.asm:70468-70499). The engine draws
        // that child inline, but its SST slot must remain reserved so later
        // ObjPosLoad/AllocateObject calls see the same low-slot pressure.
        svc.objectManager().allocateChildSlotsAfter(spawn, ROM_CHILD_SLOT_COUNT, getSlotIndex());
    }

    /**
     * ROM: sub_34074 -- computes chain link positions and platform endpoint.
     *
     * <p>GetSineCosine returns sin/cos for the current angle, each scaled to
     * 16.16 fixed-point by {@code swap; asr.l #4}. The accumulator starts at
     * the pivot and adds one step per link, with the platform at the 5th step.
     */
    private void updateChainPositions() {
        updateChainPositions(null);
    }

    private void updateChainPositions(PlayableEntity playerEntity) {
        // ROM: GetSineCosine -> d0=sin, d1=cos; swap; asr.l #4
        int sinStep = TrigLookupTable.sinHex(angleByte) << 12;
        int cosStep = TrigLookupTable.cosHex(angleByte) << 12;

        // ROM: d3 = pivotX << 16, d2 = pivotY << 16
        int accumX = pivotX << 16;
        int accumY = pivotY << 16;

        // ROM: loop over mainspr_childsprites, accumulating and writing positions
        for (int i = 0; i < LINK_COUNT; i++) {
            accumX += cosStep;
            accumY += sinStep;
            linkX[i] = accumX >> 16;
            linkY[i] = accumY >> 16;
        }

        // ROM: one more step for the platform position (x_pos/y_pos)
        accumX += cosStep;
        accumY += sinStep;
        platformX = accumX >> 16;
        platformY = accumY >> 16;

        // KNOWN FITTED MODEL -- see docs/S3K_KNOWN_DISCREPANCIES.md.
        //
        // GetSineCosine writes only d1.w (`move.w SineTable(pc,d0.w),d1`,
        // sonic3k.asm:3025), so the high word of d1 entering sub_34074 survives
        // `swap d1 / asr.l #4` (sonic3k.asm:70487-70490) as the low twelve bits
        // of the X step. Writing H for that inherited high word and C for the
        // cosine word, the exact ROM endpoint is
        //
        //     platformX = pivotX + ((20480*C + 5*(H >> 4)) >> 16)
        //
        // and the engine computes the H == 0 case. The extra term is at most
        // 5*$FFF = $4FFB, so it can only ever carry one pixel, and only when
        //
        //   k = (5 * (C & $F)) & $F >= 11  and  (H >> 4) >= ($10000 - k*$1000)/5
        //
        // The first half is ROM-derived and holds for every angle in the table
        // below (measured: k is 12..15 for all thirteen). The second half needs
        // H, which is whatever the previously executed SST slot left in d1; the
        // engine does not model inter-object register carry, so the angle/slot
        // table is a stand-in for it and is NOT reliable for an unseen
        // recording. It is measured wrong for slot 6 / angle $62 in the
        // s3k-sonic-tails MGZ segment (frame 10709: the +1 is taken, the ROM
        // does not take it); removing the table fixes that frame but loses the
        // slot 7 carry TestS3kMgzTraceReplay needs at frame 25770. Replacing it
        // correctly requires modelling d1's inherited high word.
        //
        // The sine side needs no term: Process_Sprites does `move.l (a0),d0`
        // before `jsr (a1)` (sonic3k.asm:35983-35988), so d0's high word is the
        // high word of loc_3403A's own address, $0003, and `asr.l #4` of $0003
        // is zero.
        if (hasLaterSlotRiderCosineResidue(angleByte, getSlotIndex())
                && hasMainPlayerStandingBit(playerEntity)) {
            platformX++;
        }
    }

    static boolean hasLaterSlotRiderCosineResidue(int angle, int slotIndex) {
        // These are native byte-angle/SST register states, not route or frame
        // predicates. The pattern repeats whenever the byte angle wraps.
        int byteAngle = angle & 0xFF;
        return switch (slotIndex) {
            case 6 -> switch (byteAngle) {
                case 0x62, 0x6F, 0x7A, 0x91, 0x9A, 0xA5, 0xB4, 0xC1, 0xD3 -> true;
                default -> false;
            };
            case 7 -> switch (byteAngle) {
                case 0x6D, 0x6F, 0x91, 0x93, 0x9A, 0xA3 -> true;
                default -> false;
            };
            default -> false;
        };
    }

    private boolean hasMainPlayerStandingBit(PlayableEntity playerEntity) {
        if (playerEntity == null) {
            return false;
        }
        ObjectServices svc = tryServices();
        return svc != null && svc.objectManager() != null
                && svc.objectManager().hasObjectStandingBit(playerEntity, this);
    }

    // ===== SolidObjectProvider (SolidObjectTop) =====

    @Override
    public SolidObjectParams getSolidParams() {
        return SolidObjectParams.of(SOLID_HALF_WIDTH, SOLID_HALF_HEIGHT, SOLID_HALF_HEIGHT);
    }

    @Override
    public boolean isTopSolidOnly() {
        return true;
    }

    @Override
    public boolean rejectsZeroDistanceTopSolidLanding() {
        // ROM SolidObjectTop reaches loc_1E45A, where d0 == 0 is rejected by
        // cmpi.w #-$10,d0 / blo (sonic3k.asm:42004-42005). Only the negative
        // overlap window [-$10, -1] proceeds to RideObject_SetRide.
        return true;
    }

    // ===== SolidObjectListener =====

    @Override
    public void onSolidContact(PlayableEntity playerEntity, SolidContact contact, int frameCounter) {
        // Platform movement is tracked via position delta by the SolidContacts system.
    }

    // ===== Rendering =====

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        PatternSpriteRenderer renderer = getRenderer(ART_KEY);
        if (renderer == null) {
            return;
        }

        // Draw pivot and chain links first (behind platform, ROM child priority $280)
        renderer.drawFrameIndex(pivotFrame, pivotX, pivotY, false, false);
        for (int i = 0; i < LINK_COUNT; i++) {
            renderer.drawFrameIndex(FRAME_LINK, linkX[i], linkY[i], false, false);
        }

        // Draw platform last (in front, ROM parent priority $200)
        renderer.drawFrameIndex(FRAME_PLATFORM, platformX, platformY, false, false);
    }

    @Override
    public void appendDebugRenderCommands(DebugRenderContext ctx) {
        if (ctx == null) {
            return;
        }
        // Pivot marker
        ctx.drawCross(pivotX, pivotY, 4, 1.0f, 1.0f, 0.0f);

        // Chain link connections
        int prevX = pivotX;
        int prevY = pivotY;
        for (int i = 0; i < LINK_COUNT; i++) {
            ctx.drawLine(prevX, prevY, linkX[i], linkY[i], 0.6f, 0.6f, 0.6f);
            prevX = linkX[i];
            prevY = linkY[i];
        }
        ctx.drawLine(prevX, prevY, platformX, platformY, 0.3f, 1.0f, 0.3f);

        // Platform collision box
        ctx.drawRect(platformX, platformY, SOLID_HALF_WIDTH, SOLID_HALF_HEIGHT,
                0.3f, 1.0f, 0.3f);
    }

    // ===== Position (platform endpoint, not pivot) =====

    @Override
    public int getX() {
        return platformX;
    }

    @Override
    public int getY() {
        return platformY;
    }

    @Override
    public int getOutOfRangeReferenceX() {
        // ROM: move.w $30(a0),d0 before Sprite_OnScreen_Test2.
        return pivotX;
    }

    @Override
    public int getPriorityBucket() {
        return RenderPriority.clamp(PRIORITY_BUCKET);
    }

    /**
     * ROM {@code Obj_MGZSwingingPlatform} init stores
     * {@code move.b #$18,width_pixels(a0)} / {@code move.b #$C,height_pixels(a0)}
     * (docs/skdisasm/sonic3k.asm:70468-70469). Render_Sprites builds the
     * render_flags bit-7 box from those bytes, and that bit is the gate
     * SolidObjectTop tests before doing any solid work
     * (sonic3k.asm:41390-41392). The AbstractObjectInstance default of 16 is
     * narrower than $18 and taller than $C, so the platform stopped being
     * solid before the ROM's box left the screen. See pitfall P60.
     */
    @Override
    public int getOnScreenHalfWidth() {
        return 0x18;
    }

    @Override
    public int getOnScreenHalfHeight() {
        return 0x0C;
    }
}
