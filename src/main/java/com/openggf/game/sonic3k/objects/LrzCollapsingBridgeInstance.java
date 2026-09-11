package com.openggf.game.sonic3k.objects;

import com.openggf.game.PlayableEntity;
import com.openggf.game.sonic3k.Sonic3kObjectArtKeys;
import com.openggf.game.sonic3k.audio.Sonic3kSfx;
import com.openggf.game.sonic3k.constants.Sonic3kObjectIds;
import com.openggf.graphics.GLCommand;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectConstructionContext;
import com.openggf.level.objects.ObjectLifetimeOps;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.objects.RomObjectCodePointerProvider;
import com.openggf.level.objects.SolidContact;
import com.openggf.level.objects.SolidObjectListener;
import com.openggf.level.objects.SolidObjectParams;
import com.openggf.level.objects.SolidObjectProvider;
import com.openggf.level.objects.SolidRoutineProfile;
import com.openggf.level.objects.SubpixelMotion;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.sprites.playable.AbstractPlayableSprite;

import java.util.List;

/**
 * ROM object {@code Obj_LRZCollapsingBridge} -- object id {@code $31} in the
 * {@code SKL} object pointer set (Lava Reef's collapsing stone walkway).
 *
 * <p>Not to be confused with {@code Obj_CollapsingBridge} (id {@code $0F}), which
 * {@link CollapsingBridgeObjectInstance} ports; the two share nothing but a name.
 *
 * <p><b>Shape.</b> The bridge is a single wide top-solid slab. It is inert until a
 * player stands on it; the first frame on which {@code SolidObjectTop} has left a
 * standing bit in {@code status(a0)} arms the collapse ({@code $32}), after which
 * a per-spawn countdown in {@code $30} runs down. When it expires the slab breaks:
 * 22 debris children are allocated from {@code word_39E20}, a collapse SFX plays,
 * the placement is released for respawn, and the (now invisible) parent stays
 * <em>solid</em> for a further {@code $2A} frames before deleting itself and
 * kicking any remaining rider into the air.
 *
 * <p>ROM references, all in {@code docs/skdisasm/sonic3k.asm}:
 * <ul>
 *   <li>Init {@code Obj_LRZCollapsingBridge} {@code :77383-77403} (ROM {@code $39C50})</li>
 *   <li>Parameter table {@code byte_39CA4} {@code :77405-77409} (ROM {@code $39CA4})</li>
 *   <li>Main routine {@code loc_39CA8} {@code :77411-77437}</li>
 *   <li>Post-collapse solid routine {@code loc_39CE8} {@code :77439-77453}</li>
 *   <li>Rider release {@code sub_39D1A} {@code :77458-77466}</li>
 *   <li>Collapse {@code loc_39D84} {@code :77496-77505},
 *       child allocation {@code loc_39DAA} {@code :77507-77537},
 *       respawn release {@code loc_39E08} {@code :77539-77545}</li>
 *   <li>Debris table {@code word_39E20} {@code :77548-77570} (ROM {@code $39E20})</li>
 *   <li>Debris routine {@code loc_39D3E} {@code :77470-77494}</li>
 * </ul>
 *
 * <p>No zone or act is tested anywhere in this class: the object reaches LRZ only
 * because {@code Sonic3kObjectRegistry} resolves id {@code $31} through
 * {@link com.openggf.game.sonic3k.objects.S3kZoneSet}.
 */
public final class LrzCollapsingBridgeInstance extends AbstractObjectInstance
        implements SolidObjectProvider, SolidObjectListener, RewindRecreatable, RomObjectCodePointerProvider {

    /**
     * Word 0 of this object's S3K SST holds its live ROM code pointer.
     * ROM {@code Obj_LRZCollapsingBridge} is installed from the S3K object pointer table at
     * {@code $00039C50} (table read from the user-supplied ROM; the
     * label is defined at docs/skdisasm/sonic3k.asm:77384).
     * Its whole code block lies in one bank, so the HIGH word that
     * {@code sub_13EFC} latches into {@code Tails_CPU_interact} and compares
     * on the next off-screen on-object frame is {@code $0003}
     * (docs/skdisasm/sonic3k.asm:26816-26843).
     */
    @Override
    public int romObjectCodePointerHighWord() {
        return 0x0003;
    }


    /** ROM {@code byte_39CA4} (sonic3k.asm:77405). Verified bytes {@code 40 20 08 00}. */
    private static final int PARAM_TABLE_ADDR = 0x00039CA4;
    /**
     * Fallback for {@code byte_39CA4} index 0, used only when the ROM handle is
     * unavailable (reflection-level unit tests construct the object with no
     * services). Values transcribed from ROM {@code $39CA4}.
     */
    private static final int[] PARAM_TABLE_ENTRY_0 = {0x40, 0x20, 0x08, 0x00};
    /** ROM {@code move.b #$1C,y_radius(a0)} (sonic3k.asm:77403). */
    private static final int Y_RADIUS = 0x1C;
    /** ROM {@code move.b #$2A,$30(a0)} in {@code loc_39D84} (sonic3k.asm:77497). */
    private static final int POST_COLLAPSE_SOLID_FRAMES = 0x2A;
    /** ROM {@code move.w #$80,priority(a0)} (sonic3k.asm:77387). */
    private static final int PRIORITY = 0x80;
    /** ROM {@code word_39E20} (sonic3k.asm:77548). Verified header {@code 0015} = 22 entries. */
    private static final int DEBRIS_TABLE_ADDR = 0x00039E20;
    /** ROM {@code move.b #7,anim_frame_timer(a0)} in {@code loc_39D4E} (sonic3k.asm:77477). */
    private static final int DEBRIS_ANIM_PERIOD = 7;
    /** ROM {@code move.b #$20,width_pixels(a1)} (sonic3k.asm:77531). */
    private static final int DEBRIS_HALF_WIDTH = 0x20;
    /** ROM {@code addi.w #$38,y_vel(a0)} inside {@code MoveSprite} (sonic3k.asm:36037). */
    private static final int DEBRIS_GRAVITY = 0x38;

    /** Raw {@code subtype(a0)} as placed, before Init overwrites it from the table. */
    private int spawnSubtype;
    /** Whether {@code byte_39CA4} has been read yet (deferred out of the constructor). */
    private boolean paramsResolved;
    /** ROM {@code width_pixels(a0)}, also the {@code d1} handed to {@code SolidObjectTop}. */
    private int widthPixels;
    /** ROM {@code height_pixels(a0)}. */
    private int heightPixels;
    /** ROM {@code mapping_frame(a0)}. */
    private int mappingFrame;
    /** ROM {@code $30(a0)}: frames until the slab breaks, then the post-break solid timer. */
    private int timer;
    /** ROM {@code $32(a0)}: set once a player has stood on the slab. */
    private boolean armed;
    /** ROM routine swap to {@code loc_39CE8}: the slab has broken but is still solid. */
    private boolean collapsed;
    private boolean p1Standing;
    private boolean p2Standing;
    private boolean p1StandingLatched;
    private boolean p2StandingLatched;

    public LrzCollapsingBridgeInstance(ObjectSpawn spawn) {
        super(spawn, "LRZCollapsingBridge");
        this.spawnSubtype = spawn.subtype() & 0xFF;

        // Init (sonic3k.asm:77388-77394):
        //   move.b subtype(a0),d0 / andi.w #$F,d0 / lsl.w #4,d0 / addq.w #8,d0
        this.timer = ((spawnSubtype & 0x0F) << 4) + 8;
        // The byte_39CA4 read is deferred to resolveParams(): object
        // constructors run before ObjectServices is injected.
    }

    /**
     * Init (sonic3k.asm:77395-77402): {@code andi.w #$F0,d1 / lsr.w #2,d1 /
     * lea byte_39CA4(pc,d1.w),a1} and four {@code move.b (a1)+} reads. The index
     * is applied to the ROM bytes themselves so that a high subtype nibble
     * reproduces the ROM's own read past the four-byte table rather than a
     * guess. {@code (a1)+}'s fourth byte overwrites {@code subtype(a0)}; nothing
     * in this object reads {@code subtype} again after Init, so it is discarded.
     */
    private void resolveParams() {
        if (paramsResolved) {
            return;
        }
        paramsResolved = true;
        int index = (spawnSubtype & 0xF0) >> 2;
        try {
            byte[] bytes = services().rom().readBytes(PARAM_TABLE_ADDR + index, 4);
            widthPixels = bytes[0] & 0xFF;
            heightPixels = bytes[1] & 0xFF;
            mappingFrame = bytes[2] & 0xFF;
        } catch (Exception e) {
            widthPixels = PARAM_TABLE_ENTRY_0[0];
            heightPixels = PARAM_TABLE_ENTRY_0[1];
            mappingFrame = PARAM_TABLE_ENTRY_0[2];
        }
    }

    @Override
    public LrzCollapsingBridgeInstance recreateForRewind(RewindRecreateContext ctx) {
        return ObjectConstructionContext.construct(ctx.objectServices(),
                () -> new LrzCollapsingBridgeInstance(ctx.spawn()));
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        resolveParams();
        // status(a0)'s standing bits are written by SolidObjectTop, which the
        // engine runs on this object's AUTO_AFTER_UPDATE checkpoint -- i.e. after
        // this method. Latching here therefore reproduces the ROM's read of the
        // bits left by the PREVIOUS frame's SolidObjectTop, which is exactly what
        // loc_39CBC (sonic3k.asm:77423-77427) sees.
        p1StandingLatched = p1Standing;
        p2StandingLatched = p2Standing;
        p1Standing = false;
        p2Standing = false;

        if (collapsed) {
            updateCollapsed(playerEntity);
            return;
        }

        // loc_39CA8 (sonic3k.asm:77411-77417).
        if (armed) {
            if (timer == 0) {
                breakCollapse(playerEntity);
                return;
            }
            timer--;
        }

        // loc_39CBC (sonic3k.asm:77423-77427): andi.b #standing_mask,d0.
        if (p1StandingLatched || p2StandingLatched) {
            armed = true;
        }

        // loc_39CCC (sonic3k.asm:77429-77435) is the SolidObjectTop call, run by
        // the engine's solid checkpoint from getSolidParams(). The tail is
        // Sprite_OnScreen_Test (:77436), a draw test -- not an unload.
    }

    /** ROM {@code loc_39CE8} (sonic3k.asm:77439-77453). */
    private void updateCollapsed(PlayableEntity playerEntity) {
        // SolidObjectTop runs first (:77440-77445) -- the broken slab is still
        // solid -- and only then does the countdown tick.
        timer = (timer - 1) & 0xFF;
        if (timer != 0) {
            return;
        }
        // sub_39D1A for Player_1 then Player_2 (:77447-77452), then
        // Delete_Current_Sprite.
        if (p1StandingLatched) {
            releaseRider(playerEntity);
        }
        if (p2StandingLatched) {
            releaseRider(nativeP2OrNull());
        }
        ObjectLifetimeOps.expireDynamic(this);
    }

    /** ROM {@code sub_39D1A} (sonic3k.asm:77458-77466). */
    private void releaseRider(PlayableEntity entity) {
        if (!(entity instanceof AbstractPlayableSprite player)) {
            return;
        }
        player.setOnObject(false);
        player.setPushing(false);
        player.setAir(true);
        player.getAnimationManager().publishPreviousAnimationId(1);
        try {
            if (services().objectManager() != null) {
                services().objectManager().clearRidingObject(player);
            }
        } catch (Exception ignored) {
            // Focused tests can drive the object without a wired ObjectManager.
        }
    }

    /** ROM {@code loc_39D84} (sonic3k.asm:77496-77545). */
    private void breakCollapse(PlayableEntity playerEntity) {
        collapsed = true;
        timer = POST_COLLAPSE_SOLID_FRAMES;

        int[] table = readDebrisTable();
        for (int i = 0; i + 3 < table.length; i += 4) {
            int dx = (byte) table[i];
            int dy = (byte) table[i + 1];
            int frame = table[i + 2];
            int delay = table[i + 3];
            final int childX = (getX() + dx) & 0xFFFF;
            final int childY = (getY() + dy) & 0xFFFF;
            final int childFrame = frame;
            final int childDelay = delay;
            // AllocateObjectAfterCurrent (sonic3k.asm:77508) scans forward from
            // this object's own slot, which is spawnChild's contract.
            spawnChild(() -> new LrzCollapsingBridgeDebris(
                    childX, childY, childFrame, childDelay));
        }

        // loc_39E08 (sonic3k.asm:77539-77544): bclr #7 on the respawn table entry
        // so the bridge is eligible to load again.
        try {
            ObjectLifetimeOps.releaseSpawnForRespawn(services().objectManager(), this, getSpawn());
        } catch (Exception ignored) {
            // Focused tests can drive the object without a wired ObjectManager.
        }

        // loc_39E18 (sonic3k.asm:77546-77547).
        try {
            services().playSfx(Sonic3kSfx.COLLAPSE.id);
        } catch (Exception ignored) {
            // Headless replays can omit the audio backend.
        }
    }

    /**
     * ROM {@code word_39E20} (sonic3k.asm:77548): a {@code dc.w} count of
     * {@code entries - 1} followed by {@code dx, dy, mapping_frame, delay}
     * quadruplets consumed by the {@code dbf} loop at {@code :77537}.
     */
    private int[] readDebrisTable() {
        try {
            int count = services().rom().read16BitAddr(DEBRIS_TABLE_ADDR) + 1;
            byte[] bytes = services().rom().readBytes(DEBRIS_TABLE_ADDR + 2, count * 4);
            int[] out = new int[bytes.length];
            for (int i = 0; i < bytes.length; i++) {
                out[i] = bytes[i] & 0xFF;
            }
            return out;
        } catch (Exception e) {
            return new int[0];
        }
    }

    private PlayableEntity nativeP2OrNull() {
        try {
            return services().playerQuery().nativeP2OrNull();
        } catch (IllegalStateException e) {
            return null;
        }
    }

    @Override
    public void onSolidContact(PlayableEntity player, SolidContact contact, int frameCounter) {
        if (!contact.standing()) {
            return;
        }
        if (player == nativeP2OrNull()) {
            p2Standing = true;
        } else {
            p1Standing = true;
        }
    }

    /**
     * The break frame reaches {@code loc_39D84} (sonic3k.asm:77496) by a
     * {@code bra.w} that jumps over {@code loc_39CCC}'s {@code SolidObjectTop}
     * call, so the ROM performs no solid processing on that one frame. It also
     * touches neither {@code status(a0)}'s standing bits nor the riders'
     * {@code Status_OnObj}, and {@code loc_39CE8} calls {@code SolidObjectTop}
     * again on the very next frame (:77440-77445); only {@code sub_39D1A}
     * (:77458) ever releases a rider. The slab is stationary, so a skipped
     * re-seat and a performed re-seat are indistinguishable in position. The
     * engine therefore stays solid throughout rather than reporting "not solid",
     * which its generic platform path reads as a ride exit.
     */
    @Override
    public SolidObjectParams getSolidParams() {
        // d1 = width_pixels(a0), d3 = y_radius(a0) (sonic3k.asm:77429-77433).
        // SolidObjectTop takes ONE vertical parameter and both the landing test
        // and MvSonicOnPtfm's per-frame re-seat use that same bare d3, so the air
        // and ground half heights are equal -- there is no d3+1 here.
        resolveParams();
        return SolidObjectParams.of(widthPixels, Y_RADIUS, Y_RADIUS);
    }

    @Override
    public boolean carriesRiderOnHorizontalMove(PlayableEntity player) {
        // d4 = x_pos(a0) (sonic3k.asm:77432, :77493). The slab never moves, so
        // MvSonicOnPtfm's d4 - x_pos(a0) carry is zero either way; false states
        // that plainly instead of relying on the engine default.
        return false;
    }

    @Override
    public boolean isTopSolidOnly() {
        return true;
    }

    @Override
    public boolean usesStickyContactBuffer() {
        // SolidObjectTop has no extra edge tolerance.
        return false;
    }

    @Override
    public SolidRoutineProfile getSolidRoutineProfile() {
        return SolidRoutineProfile.topSolid(usesStickyContactBuffer());
    }

    @Override
    public boolean usesCustomOutOfRangeCheck() {
        // Audited per P53: Obj_LRZCollapsingBridge contains NO out_of_range,
        // MarkObjGone, Delete_Sprite_If_Not_In_Range or Go_Delete_SpriteSlotted
        // in any routine. Its only delete is the post-collapse countdown expiry
        // in loc_39CE8 (sonic3k.asm:77453). Sprite_OnScreen_Test (:77436) is a
        // draw test, not an unload. The shared camera unload must not apply.
        return true;
    }

    @Override
    public boolean isCustomOutOfRange(int cameraX) {
        return false;
    }

    @Override
    public int getOnScreenHalfWidth() {
        resolveParams();
        return widthPixels;
    }

    @Override
    public int getOnScreenHalfHeight() {
        resolveParams();
        return heightPixels;
    }

    @Override
    public int getPriorityBucket() {
        return PRIORITY;
    }

    @Override
    public boolean isHighPriority() {
        // make_art_tile($0D3,2,1) (sonic3k.asm:77389) sets the priority bit.
        return true;
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        if (collapsed) {
            // loc_39CE8 has no Draw_Sprite: the broken slab is invisible while it
            // remains solid (sonic3k.asm:77439-77453).
            return;
        }
        resolveParams();
        PatternSpriteRenderer renderer = getRenderer(Sonic3kObjectArtKeys.LRZ_COLLAPSING_BRIDGE);
        if (renderer == null) {
            return;
        }
        renderer.drawFrameIndex(mappingFrame, getX(), getY(), false, false);
    }

    int getTimerForTest() {
        return timer;
    }

    boolean isArmedForTest() {
        return armed;
    }

    boolean isCollapsedForTest() {
        return collapsed;
    }

    int getWidthPixelsForTest() {
        resolveParams();
        return widthPixels;
    }

    int getMappingFrameForTest() {
        resolveParams();
        return mappingFrame;
    }

    /**
     * ROM {@code loc_39D3E} (sonic3k.asm:77470-77494) -- one falling slab
     * fragment, allocated by {@code loc_39DAA}.
     */
    public static final class LrzCollapsingBridgeDebris extends AbstractObjectInstance
            implements RewindRecreatable {

        private final SubpixelMotion.State motion;
        /** ROM {@code $30(a1)}: per-piece hold before the fragment starts falling. */
        private int holdTimer;
        /** ROM {@code anim_frame_timer(a0)}. */
        private int animTimer;
        /** ROM {@code mapping_frame(a0)}. */
        private int mappingFrame;
        /** ROM {@code $34(a1)} = {@code mapping_frame & $FC} (sonic3k.asm:77535-77536). */
        private int frameGroupBase;

        public LrzCollapsingBridgeDebris(int x, int y, int mappingFrame, int holdTimer) {
            // Id $31: Sonic3kObjectIds.LBZ_ROLLING_DRUM is the S3KL name for the
            // same numeric id this object occupies in the SKL set.
            super(new ObjectSpawn(x, y, Sonic3kObjectIds.LBZ_ROLLING_DRUM, 0, 0, false, 0),
                    "LRZCollapsingBridgeDebris");
            this.motion = new SubpixelMotion.State(x, y, 0, 0, 0, 0);
            this.mappingFrame = mappingFrame;
            this.frameGroupBase = mappingFrame & 0xFC;
            this.holdTimer = holdTimer;
        }

        @Override
        public AbstractObjectInstance recreateForRewind(RewindRecreateContext ctx) {
            ObjectSpawn spawn = ctx.spawn();
            int x = spawn != null ? spawn.x() : 0;
            int y = spawn != null ? spawn.y() : 0;
            return ObjectConstructionContext.construct(ctx.objectServices(),
                    () -> new LrzCollapsingBridgeDebris(x, y, 0, 0));
        }

        @Override
        public void update(int vIntRunCount, PlayableEntity player) {
            // loc_39D3E (sonic3k.asm:77470-77474): hold, drawing only.
            if (holdTimer != 0) {
                holdTimer = (holdTimer - 1) & 0xFF;
                return;
            }

            // loc_39D4E (sonic3k.asm:77476-77484).
            animTimer--;
            if (animTimer < 0) {
                animTimer = DEBRIS_ANIM_PERIOD;
                mappingFrame = ((mappingFrame + 1) & 3) + frameGroupBase;
            }

            // loc_39D6C (sonic3k.asm:77486-77494): MoveSprite (move, then apply
            // gravity to y_vel), then delete once render_flags bit 7 is clear --
            // i.e. once the fragment was not drawn on screen last frame.
            SubpixelMotion.moveSprite(motion, DEBRIS_GRAVITY);
            updateDynamicSpawn(motion.x, motion.y);
            if (!isOnScreen()) {
                ObjectLifetimeOps.expireDynamic(this);
            }
        }

        @Override
        public int getX() {
            return motion.x;
        }

        @Override
        public int getY() {
            return motion.y;
        }

        @Override
        public int getOnScreenHalfWidth() {
            return DEBRIS_HALF_WIDTH;
        }

        @Override
        public int getOnScreenHalfHeight() {
            // FixBugs = 0 (skdisasm/sonic3k.asm:38). The un-fixed branch at
            // sonic3k.asm:77527-77534 writes #$20 to width_pixels(a1) a SECOND
            // time instead of to height_pixels(a1), so a freshly allocated
            // fragment keeps height_pixels = 0 and its on-screen test is
            // vertically degenerate. The bug-fixed branch would write #$20 here.
            // The shipped ROM -- and therefore every recorded trace -- takes the
            // un-fixed path, so the engine does too.
            return 0;
        }

        @Override
        public int getPriorityBucket() {
            // move.w #$80,priority(a1) (sonic3k.asm:77530).
            return PRIORITY;
        }

        @Override
        public boolean isHighPriority() {
            // ori.w #high_priority,art_tile(a1) (sonic3k.asm:77529).
            return true;
        }

        @Override
        public void appendRenderCommands(List<GLCommand> commands) {
            PatternSpriteRenderer renderer = getRenderer(Sonic3kObjectArtKeys.LRZ_COLLAPSING_BRIDGE);
            if (renderer == null) {
                return;
            }
            renderer.drawFrameIndex(mappingFrame, getX(), getY(), false, false);
        }

        int getHoldTimerForTest() {
            return holdTimer;
        }

        int getMappingFrameForTest() {
            return mappingFrame;
        }
    }
}
