package com.openggf.game.sonic3k.objects;

import com.openggf.audio.GameAudioProfile;
import com.openggf.audio.GameMusic;
import com.openggf.audio.GameSound;
import com.openggf.game.PlayableEntity;
import com.openggf.level.objects.ExplosionObjectInstance;
import com.openggf.level.objects.ObjectAnimationState;
import com.openggf.game.sonic3k.audio.Sonic3kSfx;
import com.openggf.game.sonic3k.constants.Sonic3kAnimationIds;
import com.openggf.graphics.GLCommand;
import com.openggf.graphics.RenderPriority;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.AbstractMonitorObjectInstance;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.ObjectLifetimeOps;
import com.openggf.level.objects.ObjectRenderManager;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.ObjectSpriteSheet;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.objects.RewindRecreateObjectLinks;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.objects.RomObjectCodePointerProvider;
import com.openggf.level.objects.SolidContact;
import com.openggf.level.objects.SolidObjectListener;
import com.openggf.level.objects.SolidObjectParams;
import com.openggf.level.objects.SolidObjectProvider;
import com.openggf.level.objects.SolidRoutineProfile;
import com.openggf.level.objects.SubpixelMotion;
import com.openggf.level.objects.TouchResponseListener;
import com.openggf.level.objects.TouchResponseProfile;
import com.openggf.level.objects.TouchResponseProvider;
import com.openggf.level.objects.TouchResponseResult;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.level.render.SpriteMappingFrame;
import com.openggf.level.render.SpriteMappingPiece;
import com.openggf.physics.ObjectTerrainUtils;
import com.openggf.physics.TerrainCheckResult;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.sprites.playable.SecondaryAbility;
import com.openggf.sprites.playable.SuperStateController;
import com.openggf.game.ShieldType;

import java.util.List;
import java.util.logging.Logger;

/**
 * Sonic 3&K Monitor (item box) object.
 * <p>
 * Object ID 0x01. Contains power-ups awarded when broken by the player.
 * S&K monitors do NOT fall when hit from below — they break directly if
 * the player is rolling, spinning, or (for Knuckles) gliding/sliding.
 * <p>
 * Subtypes: 0=Eggman, 1=1-Up, 2=Eggman, 3=Rings, 4=SpeedShoes,
 * 5=FireShield, 6=LightningShield, 7=BubbleShield, 8=Invincibility, 9=Super.
 * <p>
 * Reference: docs/skdisasm/sonic3k.asm lines 40442-40995
 */
public class Sonic3kMonitorObjectInstance extends AbstractMonitorObjectInstance
        implements TouchResponseProvider, TouchResponseListener,
        SolidObjectProvider, SolidObjectListener, RomObjectCodePointerProvider, RewindRecreatable {
    private static final Logger LOGGER = Logger.getLogger(Sonic3kMonitorObjectInstance.class.getName());

    // From disassembly: solid params d1=$19, d2=$10, d3=$11
    private static final int SOLID_WIDTH = 0x19;
    private static final int SOLID_D2 = 0x10;
    private static final int SOLID_D3 = 0x11;

    // Map_Monitor frame 11 = broken shell
    private static final int BROKEN_FRAME = 0x0B;

    // Monitor gives 10 rings
    private static final int RING_MONITOR_REWARD = 10;

    // Super monitor gives 50 rings
    private static final int SUPER_RING_REWARD = 50;

    // Icon frame offset: icon mapping frame = type.animId + 1
    // ROM: addq.b #1,d0 (sonic3k.asm line 40699)
    // (Mapping frames: 0=box, 1=eggman, 2=1up, 3=eggman2, 4=rings, ...)
    private static final int ICON_FRAME_OFFSET = 1;

    // Y radius for floor collision (from solid params d2)
    private static final int Y_RADIUS = 0x10;

    // Obj_Monitor = 0x0001D566 in the S&K-side ROM.
    private static final int ROM_CODE_POINTER_HIGH_WORD = 0x0001;

    // docs/skdisasm/sonic3k.constants.asm:131-148 define object status bits 3-6
    // as p1/p2 standing and pushing. Obj_MonitorBreak consumes these at
    // docs/skdisasm/sonic3k.asm:40624-40638 to release touching players.
    private static final int P1_STANDING = 1 << 3;
    private static final int P2_STANDING = 1 << 4;
    private static final int P1_PUSHING = 1 << 5;
    private static final int P2_PUSHING = 1 << 6;
    private static final int P1_CONTACT_MASK = P1_STANDING | P1_PUSHING;
    private static final int P2_CONTACT_MASK = P2_STANDING | P2_PUSHING;
    private static final int PLAYER_CONTACT_MASK = P1_CONTACT_MASK | P2_CONTACT_MASK;

    private MonitorType type;
    private ObjectAnimationState animationState;
    private boolean broken;
    private int mappingFrame;
    private boolean initialized;

    // "Revealed from hidden monitor" mode: pop up with velocity, fall with gravity
    private boolean revealed;
    private final SubpixelMotion.State motion;
    private int solidStatusBits;
    private PlayableEntity p1SolidContact;
    private PlayableEntity p2SolidContact;
    private int p2SolidContactFrame = Integer.MIN_VALUE;
    private PlayableEntity p2RecentlyClearedSolidContact;
    private int p2SolidContactClearedFrame = Integer.MIN_VALUE;
    private boolean pendingBreakContactRelease;
    private MonitorContentsSlot monitorContentsSlot;

    // (Icon rising state is managed by AbstractMonitorObjectInstance)

    public Sonic3kMonitorObjectInstance(ObjectSpawn spawn) {
        super(spawn, "Monitor");
        this.type = MonitorType.fromSubtype(spawn.subtype());
        this.animationState = new ObjectAnimationState(currentMonitorAnimations(), type.animId, 0);
        this.motion = new SubpixelMotion.State(spawn.x(), spawn.y(), 0, 0, 0, 0);
    }

    @Override
    public Sonic3kMonitorObjectInstance recreateForRewind(RewindRecreateContext ctx) {
        return new Sonic3kMonitorObjectInstance(ctx.spawn());
    }

    /**
     * Activate "revealed from hidden monitor" mode.
     * ROM: loc_83760 — sets y_vel = -$500, transforms to Obj_Monitor routine 2.
     * The monitor pops upward and falls with standard gravity until landing.
     */
    public void revealFromHidden() {
        revealed = true;
        motion.yVel = -0x500;
    }

    @Override
    public int getX() {
        return motion.x;
    }

    @Override
    public int getY() {
        return motion.y;
    }

    /** Current X position (uses motion state, which tracks spawn for static monitors). */
    private int posX() {
        return motion.x;
    }

    /** Current Y position (uses motion state, which tracks spawn for static monitors). */
    private int posY() {
        return motion.y;
    }

    @Override
    public boolean isPersistent() {
        return revealed;
    }

    @Override
    public boolean shouldStayActiveWhenRemembered() {
        return true;
    }

    @Override
    public int romObjectCodePointerHighWord() {
        // Tails_CPU_interact stores word 0 of the stood-on object SST
        // (docs/skdisasm/sonic3k.asm:26816-26843).
        return ROM_CODE_POINTER_HIGH_WORD;
    }

    @Override
    protected boolean delayFirstIconUpdateAfterBreak() {
        // ROM Obj_MonitorBreak allocates Obj_MonitorContents after the current
        // slot, then Obj_MonitorContents init falls through into sub_1D820 on
        // its first execution (docs/skdisasm/sonic3k.asm:40645-40718). Engine
        // touch responses break the shell before the post-physics object pass,
        // so this embedded content must consume that pass rather than skipping it.
        return false;
    }

    private void ensureInitialized() {
        if (initialized) {
            return;
        }
        initialized = true;

        // Check persistence: if previously broken, spawn as broken shell
        ObjectManager objectManager = services().objectManager();
        boolean previouslyBroken = objectManager != null && objectManager.isRemembered(spawn);
        this.broken = previouslyBroken;

        // Initialize animation: animId = subtype
        int initialAnim = type.animId;
        int initialFrame = broken ? BROKEN_FRAME : 0;
        this.animationState = new ObjectAnimationState(currentMonitorAnimations(), initialAnim, initialFrame);
        this.mappingFrame = initialFrame;
        if (broken) {
            effectApplied = true;
        }
    }

    private com.openggf.sprites.animation.SpriteAnimationSet currentMonitorAnimations() {
        var ctx = tryServices();
        ObjectRenderManager renderManager = ctx != null ? ctx.renderManager() : null;
        return renderManager != null ? renderManager.getMonitorAnimations() : null;
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        ensureInitialized();
        expireRecentlyClearedP2Contact(vIntRunCount);
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        if (revealed && !broken) {
            updateRevealed();
        }
        if (!broken) {
            animationState.update();
            mappingFrame = animationState.getMappingFrame();
            return;
        }
        if (pendingBreakContactRelease) {
            pendingBreakContactRelease = false;
            releaseTouchingPlayersOnBreak(player, vIntRunCount);
        }
        updateIcon();
    }

    /**
     * Physics for a monitor popping out of a hidden monitor slot.
     * ROM: Obj_MonitorNorm — SpeedToPos + gravity + ObjCheckFloorDist.
     */
    private void updateRevealed() {
        SubpixelMotion.moveSprite(motion, SubpixelMotion.S3K_GRAVITY);

        // Only check floor when moving downward
        if (motion.yVel > 0) {
            TerrainCheckResult floor = ObjectTerrainUtils.checkFloorDist(
                    motion.x, motion.y, Y_RADIUS);
            if (floor.distance() < 0) {
                motion.y += floor.distance();
                motion.yVel = 0;
                revealed = false; // Landed — become a normal static monitor
                LOGGER.fine("Revealed monitor landed at Y=" + motion.y);
            }
        }
    }

    /**
     * S&K touch response: monitors break directly when hit by a rolling/spinning player.
     * No falling behavior — unlike S2, hitting from below while rolling breaks immediately.
     * <p>
     * ROM: Touch_Monitor (sonic3k.asm ~line 20800)
     */
    @Override
    public void onTouchResponse(PlayableEntity playerEntity, TouchResponseResult result, int frameCounter) {
        ensureInitialized();
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        if (broken || player == null) {
            return;
        }

        // ROM: CPU sidekick cannot break monitors (s2.asm Touch_Monitor check)
        if (player.isCpuControlled()) {
            return;
        }

        // ROM: Touch_Monitor checks anim(a0) == AniIDSonAni_Roll here, not the
        // broader rolling status bit. The animation can lag the status byte by
        // a frame during object releases, which affects monitor break timing.
        boolean canBreak = player.getAnimationId() == Sonic3kAnimationIds.ROLL.id();
        // ROM Touch_Monitor.checkdestroy (docs/skdisasm/sonic3k.asm:20858-20866):
        // Knuckles gliding (double_jump_flag==1) or sliding (==3) also breaks the
        // monitor -- the identical set as the solid gate (isKnucklesGlidingOrSliding).
        canBreak |= isKnucklesGlidingOrSliding(player);

        if (!canBreak) {
            return;
        }

        // Negate player's Y-speed: neg.w y_vel(a0)
        player.setYSpeed((short) -player.getYSpeed());

        breakMonitor(player, frameCounter);
    }

    /**
     * Break the monitor: spawn explosion, start icon rising, mark persistence.
     * ROM: Mon_BreakOpen (sonic3k.asm ~line 40685)
     */
    private void breakMonitor(AbstractPlayableSprite player, int frameCounter) {
        broken = true;

        // Touch_Monitor selects Obj_MonitorBreak during the player slot; the
        // contact-bit release runs later when the monitor's own SST slot is
        // dispatched. Deferring prevents an earlier HCZ block slot from
        // re-landing a released sidekick in the same frame.
        pendingBreakContactRelease = true;

        // Mark as broken in persistence table
        ObjectManager objectManager = services().objectManager();
        ObjectLifetimeOps.markSpawnRemembered(objectManager, spawn);

        mappingFrame = BROKEN_FRAME;

        // Initialize icon rising
        startIconRise(posY(), player);
        spawnMonitorContentsSlot(objectManager);

        // Spawn explosion
        ObjectRenderManager renderManager = services().renderManager();
        if (renderManager != null && objectManager != null
                && renderManager.getExplosionRenderer() != null) {
            // ROM Obj_MonitorSpawnIcon creates Obj_MonitorContents, then
            // Obj_Explosion, with AllocateObjectAfterCurrent both times
            // (docs/skdisasm/sonic3k.asm:40640-40659; allocator at 37911-37925).
            objectManager.addDynamicObjectAfterSlot(
                    new ExplosionObjectInstance(0x27, posX(), posY(), renderManager),
                    getSlotIndex());
        }
        // ROM: Obj_Explosion loc_1E61A plays sfx_Break ($3D)
        services().playSfx(Sonic3kSfx.BREAK.id);
    }

    private void spawnMonitorContentsSlot(ObjectManager objectManager) {
        if (objectManager == null || monitorContentsSlot != null) {
            return;
        }
        monitorContentsSlot = new MonitorContentsSlot(this, buildSpawnAt(posX(), posY()));
        // The parent shell can be broken by TouchResponse before ObjectManager
        // is executing the monitor slot. Anchor AllocateObjectAfterCurrent to
        // this monitor's SST slot so the content object still lands after the
        // shell, matching Obj_MonitorSpawnIcon (sonic3k.asm:40640-40652).
        objectManager.addDynamicObjectAfterSlot(monitorContentsSlot, getSlotIndex());
    }

    /**
     * Apply the monitor's power-up effect.
     * ROM: Pow_ChkX branch table (sonic3k.asm ~line 40780)
     */
    @Override
    protected void applyPowerup(PlayableEntity playerEntity) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        switch (type) {
            case EGGMAN, EGGMAN_2 -> {
                // Eggman monitor hurts the player
                // ROM: calls HurtCharacter — checks invincibility first
                if (player.getInvincibleFrames() <= 0 && player.getInvulnerableFrames() <= 0) {
                    // ROM: Hurt_Sidekick - CPU Tails only gets knockback, no ring scatter or death
                    if (player.isCpuControlled()) {
                        player.applyHurt(player.getCentreX());
                    } else {
                        player.applyHurtOrDeath(player.getCentreX(), false, player.getRingCount() > 0);
                    }
                }
            }
            case ONE_UP -> {
                services().gameState().addLife();
                services().playMusic(GameMusic.EXTRA_LIFE);
            }
            case RINGS -> {
                player.addRings(RING_MONITOR_REWARD);
                services().playSfx(GameSound.RING);
            }
            case SPEED_SHOES -> {
                player.giveSpeedShoes();
                GameAudioProfile audioProfile = services().audioManager().getAudioProfile();
                if (audioProfile != null) {
                    // S3K monitor code writes 8 directly to zTempoSpeedup;
                    // E2/E3 are unrelated driver commands.
                    services().audioManager().setSpeedMultiplier(
                            audioProfile.getSpeedMultiplierValue());
                }
            }
            case FIRE_SHIELD -> {
                player.giveShield(ShieldType.FIRE);
                services().playSfx(GameSound.FIRE_SHIELD);
            }
            case LIGHTNING_SHIELD -> {
                player.giveShield(ShieldType.LIGHTNING);
                services().playSfx(GameSound.LIGHTNING_SHIELD);
            }
            case BUBBLE_SHIELD -> {
                player.giveShield(ShieldType.BUBBLE);
                services().playSfx(GameSound.BUBBLE_SHIELD);
            }
            case INVINCIBILITY -> {
                // Skip invincibility if player is already Super Sonic
                if (!player.isSuperSonic()) {
                    player.giveInvincibility();
                    services().playMusic(GameMusic.INVINCIBILITY);
                }
            }
            case SUPER -> {
                player.addRings(SUPER_RING_REWARD);
                SuperStateController superState = player.getSuperStateController();
                if (superState != null && superState.activateFromMonitor()) {
                    LOGGER.info("Super monitor collected - 50 rings awarded and transformation started");
                } else {
                    LOGGER.info("Super monitor collected - 50 rings awarded");
                }
            }
        }
    }

    @Override
    protected void onIconDeactivated() {
        destroyMonitorContentsSlot();
    }

    @Override
    public void onUnload() {
        destroyMonitorContentsSlot();
    }

    private boolean isMonitorContentsSlotActive() {
        return iconActive;
    }

    private void destroyMonitorContentsSlot() {
        if (monitorContentsSlot != null) {
            monitorContentsSlot.setDestroyed(true);
            monitorContentsSlot = null;
        }
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        ObjectRenderManager renderManager = services().renderManager();
        PatternSpriteRenderer renderer = renderManager != null ? renderManager.getMonitorRenderer() : null;
        boolean hasRenderer = renderer != null && renderer.isReady();

        if (hasRenderer) {
            // Draw monitor body (broken shell or animated frame)
            int frameIndex = broken ? BROKEN_FRAME : mappingFrame;
            renderer.drawFrameIndex(frameIndex, posX(), posY(), false, false);
        } else {
            // Fallback: full box when intact, half-height shell when broken
            appendFallbackBox(commands, broken);
        }

        // Draw rising icon
        if (iconActive) {
            if (hasRenderer) {
                int iconFrame = resolveIconFrame();
                ObjectSpriteSheet sheet = renderManager.getMonitorSheet();
                if (iconFrame >= 0 && sheet != null && iconFrame < sheet.getFrameCount()) {
                    SpriteMappingFrame frame = sheet.getFrame(iconFrame);
                    if (frame != null && !frame.pieces().isEmpty()) {
                        // Draw only the first piece (the icon overlay, not the box base)
                        SpriteMappingPiece iconPiece = frame.pieces().get(0);
                        renderer.drawPieces(List.of(iconPiece), posX(), iconSubY >> 8, false, false);
                    }
                }
            } else {
                // Fallback: small box for rising icon
                appendFallbackIcon(commands, posX(), iconSubY >> 8);
            }
        }
    }

    /**
     * Resolve the mapping frame index for the rising icon.
     * ROM: Pow_Main sets obFrame = obAnim + 2
     */
    private int resolveIconFrame() {
        return type.animId + ICON_FRAME_OFFSET;
    }

    private void appendFallbackBox(List<GLCommand> commands, boolean isBroken) {
        int cx = posX();
        int cy = posY();
        int half = 0x0E;
        int left = cx - half;
        int right = cx + half;
        // Broken shell: bottom half only (y to y+half)
        int top = isBroken ? cy : cy - half;
        int bottom = cy + half;
        float r = isBroken ? 0.6f : 0.4f;
        float g = isBroken ? 0.6f : 0.9f;
        float b = isBroken ? 0.6f : 1.0f;
        appendWireRect(commands, left, top, right, bottom, r, g, b);
    }

    private void appendFallbackIcon(List<GLCommand> commands, int cx, int cy) {
        int half = 6;
        appendWireRect(commands, cx - half, cy - half, cx + half, cy + half,
                1.0f, 1.0f, 0.4f);
    }

    private void appendWireRect(List<GLCommand> commands,
            int left, int top, int right, int bottom,
            float r, float g, float b) {
        commands.add(new GLCommand(GLCommand.CommandType.VERTEX2I, -1, GLCommand.BlendType.SOLID, r, g, b, left, top, 0, 0));
        commands.add(new GLCommand(GLCommand.CommandType.VERTEX2I, -1, GLCommand.BlendType.SOLID, r, g, b, right, top, 0, 0));
        commands.add(new GLCommand(GLCommand.CommandType.VERTEX2I, -1, GLCommand.BlendType.SOLID, r, g, b, right, top, 0, 0));
        commands.add(new GLCommand(GLCommand.CommandType.VERTEX2I, -1, GLCommand.BlendType.SOLID, r, g, b, right, bottom, 0, 0));
        commands.add(new GLCommand(GLCommand.CommandType.VERTEX2I, -1, GLCommand.BlendType.SOLID, r, g, b, right, bottom, 0, 0));
        commands.add(new GLCommand(GLCommand.CommandType.VERTEX2I, -1, GLCommand.BlendType.SOLID, r, g, b, left, bottom, 0, 0));
        commands.add(new GLCommand(GLCommand.CommandType.VERTEX2I, -1, GLCommand.BlendType.SOLID, r, g, b, left, bottom, 0, 0));
        commands.add(new GLCommand(GLCommand.CommandType.VERTEX2I, -1, GLCommand.BlendType.SOLID, r, g, b, left, top, 0, 0));
    }

    // -- Collision interfaces --

    // From disassembly: obColType = $46; cleared to 0 when broken (line 40642)
    @Override
    public int getCollisionFlags() {
        return broken ? 0 : 0x46;
    }

    @Override
    public int getCollisionProperty() {
        return 0;
    }

    @Override
    public TouchResponseProfile getTouchResponseProfile() {
        return TouchResponseProfile.fromProvider(this);
    }

    @Override
    public TouchResponseProfile getTouchResponseProfile(boolean multiRegionSource) {
        return TouchResponseProfile.fromProvider(this, multiRegionSource);
    }

    @Override
    public boolean requiresContinuousTouchCallbacks() {
        // ROM TouchResponse polls Obj_Monitor every frame. The first overlap can
        // see status.player.rolling before anim reaches AniIDSonAni_Roll, so keep
        // rechecking while the player remains inside the monitor touch box.
        return true;
    }

    // From disassembly: SolidObject_Monitor_SetValues params d1=$19, d2=$10, d3=$11
    @Override
    public SolidObjectParams getSolidParams() {
        return SolidObjectParams.of(SOLID_WIDTH, SOLID_D2, SOLID_D3);
    }

    @Override
    public boolean isSolidFor(PlayableEntity playerEntity) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        if (broken) {
            return false;
        }
        if (player == null) {
            return true;
        }
        // ROM: SolidObject_Monitor_SonicKnux and SolidObject_Monitor_Tails both
        // open with `btst d6,status(a0) / bne Monitor_ChkOverEdge`
        // (docs/skdisasm/sonic3k.asm:40559-40562,40583-40585). When the monitor's
        // own p1/p2 standing bit is already set, the ROM jumps straight to the
        // continued-ride edge test and NEVER evaluates the roll-anim, Knuckles
        // glide or competition-mode exemptions below. A rider who starts rolling
        // while standing on a monitor therefore keeps the ride: Monitor_ChkOverEdge
        // (sonic3k.asm:40594-40612) releases them only on Status_InAir or on
        // leaving the horizontal span. Re-testing the acquire-time exemptions on
        // every frame unseats the rider on the roll-entry frame, which the ROM
        // does not do.
        // The object-side bit is mirrored by the rider's own Status_OnObj: both are
        // set together in RideObject_SetRide (sonic3k.asm:42027-42041) and cleared
        // together in Monitor_ChkOverEdge (:40613-40617), so require both.
        ObjectManager solidObjectManager = services().objectManager();
        if (player.isOnObject() && solidObjectManager != null
                && solidObjectManager.getRidingObject(player) == this) {
            return true;
        }
        if (player.isCpuControlled()) {
            // ROM: SolidObject_Monitor_Tails branches directly to SolidObject_cont
            // outside competition mode before testing the roll anim
            // (docs/skdisasm/sonic3k.asm:40583-40590).
            return true;
        }
        // ROM: SolidObject_Monitor_SonicKnux (docs/skdisasm/sonic3k.asm:40567-40573)
        // also returns non-solid for Knuckles gliding (double_jump_flag==1) or
        // sliding after gliding (==3), after the roll-anim check.
        if (isKnucklesGlidingOrSliding(player)) {
            return false;
        }
        // ROM: SolidObject_Monitor_SonicKnux tests anim(a1) == AniIDSonAni_Roll,
        // not the broader rolling status bit (docs/skdisasm/sonic3k.asm:40559-40572).
        return player.getAnimationId() != Sonic3kAnimationIds.ROLL.id();
    }

    /**
     * ROM parity for the monitor's Knuckles glide/slide exemptions, shared by
     * the solid gate ({@code SolidObject_Monitor_SonicKnux},
     * docs/skdisasm/sonic3k.asm:40567-40573) and the break gate
     * ({@code Touch_Monitor.checkdestroy}, docs/skdisasm/sonic3k.asm:20858-20866)
     * -- both test {@code character_id==2} then {@code double_jump_flag} 1
     * (gliding) or 3 (sliding after gliding). Gated on the GLIDE secondary
     * ability so Sonic's insta-shield {@code double_jump_flag==1}
     * (InstaShieldObjectInstance) never qualifies.
     */
    private static boolean isKnucklesGlidingOrSliding(AbstractPlayableSprite player) {
        if (player.getSecondaryAbility() != SecondaryAbility.GLIDE) {
            return false;
        }
        int djf = player.getDoubleJumpFlag();
        return djf == 1 || djf == 3;
    }

    @Override
    public boolean hasMonitorSolidity() {
        return false;
    }

    @Override
    public int getMonitorSolidObjectVerticalOffset() {
        // ROM: SolidObject_Monitor_SonicKnux falls through to SolidObject_cont
        // (docs/skdisasm/sonic3k.asm:40559-40576), whose normal-gravity path
        // adds +4 before the d2/y_radius overlap check (lines 41429-41432).
        return 4;
    }

    @Override
    public boolean usesInclusiveRightEdge() {
        // ROM SolidObject_cont rejects with bhi after comparing relX against
        // width*2, so the exact right edge remains a zero-distance side contact.
        // HCZ1 trace frame 97 depends on that contact setting Status_Push while
        // Sonic is pinned against the monitor.
        return true;
    }

    @Override
    public boolean zeroXSpeedStopsOnLeftSideContact() {
        // S3K SolidObject_cont's player-left branch reaches loc_1E056 when
        // x_vel is zero: only a negative velocity takes the skip branch
        // (docs/skdisasm/sonic3k.asm:41473-41491). This also publishes the
        // wall-cling status_tertiary flag used by later controller slots.
        return true;
    }

    @Override
    public SolidRoutineProfile getSolidRoutineProfile() {
        // S3K monitor wrappers gate roll-animation hits, then branch into the
        // shared SolidObject_cont side/top classifier (docs/skdisasm/sonic3k.asm:
        // 40559-40590, 41394-41632). That normal classifier is required for
        // P2 side contact to win over top landing when horizontal penetration is
        // smaller, e.g. CNZ f11061 against the monitor at $1A50,$00D0.
        return SolidRoutineProfile.fullSolid(false, usesInclusiveRightEdge(), false);
    }

    @Override
    public boolean usesStickyContactBuffer() {
        // Monitors are static objects — the sticky buffer is only needed for
        // moving platforms to compensate for execution-order jitter. Using it
        // here would extend riding bounds 16px beyond the ROM's ExitPlatform width.
        return false;
    }

    @Override
    public void setPlayerPushing(PlayableEntity player, boolean pushing) {
        if (player == null) {
            return;
        }
        int bit = player.isCpuControlled() ? P2_PUSHING : P1_PUSHING;
        if (pushing) {
            solidStatusBits |= bit;
            rememberSolidContactPlayer(player, Integer.MIN_VALUE);
        } else {
            solidStatusBits &= ~bit;
        }
    }

    @Override
    public void onSolidContact(PlayableEntity playerEntity, SolidContact contact, int frameCounter) {
        if (playerEntity == null || contact == null) {
            return;
        }
        int standingBit = playerEntity.isCpuControlled() ? P2_STANDING : P1_STANDING;
        if (contact.standing()) {
            solidStatusBits |= standingBit;
            rememberSolidContactPlayer(playerEntity, frameCounter);
        } else {
            solidStatusBits &= ~standingBit;
        }
    }

    @Override
    public void onSolidContactCleared(PlayableEntity playerEntity, int frameCounter) {
        if (playerEntity == null) {
            return;
        }
        if (!playerEntity.isCpuControlled()) {
            // ROM: Monitor_ChkOverEdge's .notonmonitor arm does
            // `bclr d6,status(a0)` (docs/skdisasm/sonic3k.asm:40613-40617), so a
            // rider who leaves the monitor clears the object's OWN p1_standing
            // bit immediately. Leaving it latched makes a later Obj_MonitorBreak
            // (:40628-40634) force Status_InAir on a player who is nowhere near
            // the monitor. Only the standing bit is cleared here; p1_pushing is
            // maintained separately by setPlayerPushing, matching the ROM's
            // independent pushing_mask.
            solidStatusBits &= ~P1_STANDING;
            return;
        }

        // ROM: the sidekick monitor path uses p2_standing_bit (sonic3k.asm:
        // 40492-40494), and Obj_MonitorBreak releases P2 only if p2_standing or
        // p2_pushing is still set (40624-40638). MGZ aux at F342 has the monitor
        // status clear while Tails remains grounded, so clear stale engine-side P2
        // bookkeeping on no-contact without disturbing the P1 break-release path.
        solidStatusBits &= ~P2_CONTACT_MASK;
        if (p2SolidContact == playerEntity) {
            if (p2SolidContactFrame == frameCounter) {
                p2RecentlyClearedSolidContact = p2SolidContact;
                p2SolidContactClearedFrame = frameCounter;
            }
            p2SolidContact = null;
            p2SolidContactFrame = Integer.MIN_VALUE;
        }
    }

    @Override
    public int getPriorityBucket() {
        return RenderPriority.clamp(3);
    }

    private void rememberSolidContactPlayer(PlayableEntity player, int frameCounter) {
        if (player.isCpuControlled()) {
            p2SolidContact = player;
            p2SolidContactFrame = frameCounter;
            p2RecentlyClearedSolidContact = null;
            p2SolidContactClearedFrame = Integer.MIN_VALUE;
        } else {
            p1SolidContact = player;
        }
    }

    private void releaseTouchingPlayersOnBreak(AbstractPlayableSprite breaker, int vIntRunCount) {
        int contactBits = solidStatusBits & PLAYER_CONTACT_MASK;
        PlayableEntity sameFrameClearedP2Contact = p2SolidContactClearedFrame == vIntRunCount
                ? p2RecentlyClearedSolidContact
                : null;
        PlayableEntity inferredP2Contact = inferSidekickMonitorStandingBitOnBreak();
        if (contactBits == 0 && sameFrameClearedP2Contact == null && inferredP2Contact == null) {
            return;
        }

        // ROM: Obj_MonitorBreak checks standing_mask|pushing_mask, then applies
        // andi.b #$D7 plus Status_InAir for P1/P2 before spawning the icon/explosion
        // (docs/skdisasm/sonic3k.asm:40624-40638). This covers MGZ F239 where
        // Touch_Monitor sets routine=4 while the monitor still has p1_pushing set.
        if ((contactBits & P1_CONTACT_MASK) != 0) {
            releasePlayerFromBrokenMonitor(p1SolidContact != null ? p1SolidContact : breaker);
        }
        if ((contactBits & P2_CONTACT_MASK) != 0 && p2SolidContact != null) {
            releasePlayerFromBrokenMonitor(p2SolidContact);
        } else if (sameFrameClearedP2Contact != null) {
            releasePlayerFromBrokenMonitor(sameFrameClearedP2Contact);
        } else if (inferredP2Contact != null) {
            releasePlayerFromBrokenMonitor(inferredP2Contact);
        }
        solidStatusBits &= ~PLAYER_CONTACT_MASK;
        p2RecentlyClearedSolidContact = null;
        p2SolidContactClearedFrame = Integer.MIN_VALUE;
    }

    private PlayableEntity inferSidekickMonitorStandingBitOnBreak() {
        List<PlayableEntity> sidekicks = services().playerQuery().sidekicks();
        if (sidekicks == null || sidekicks.isEmpty()) {
            return null;
        }
        for (PlayableEntity sidekick : sidekicks) {
            if (sidekick != null && sidekick.isCpuControlled() && isWithinMonitorStandingBitBounds(sidekick)) {
                return sidekick;
            }
        }
        return null;
    }

    private boolean isWithinMonitorStandingBitBounds(PlayableEntity sidekick) {
        int relX = sidekick.getCentreX() - posX() + SOLID_WIDTH;
        int maxRelXInclusive = SOLID_WIDTH * 2;
        if (relX < 0 || relX > maxRelXInclusive) {
            return false;
        }
        return Math.abs(sidekick.getCentreY() - posY()) <= SOLID_D3;
    }

    private void expireRecentlyClearedP2Contact(int vIntRunCount) {
        if (p2RecentlyClearedSolidContact != null && p2SolidContactClearedFrame != vIntRunCount) {
            p2RecentlyClearedSolidContact = null;
            p2SolidContactClearedFrame = Integer.MIN_VALUE;
        }
    }

    private void releasePlayerFromBrokenMonitor(PlayableEntity player) {
        if (player == null) {
            return;
        }
        player.setOnObject(false);
        player.setPushing(false);
        player.setAir(true);
    }

    private static final class MonitorContentsSlot extends AbstractObjectInstance implements RewindRecreatable {
        private final Sonic3kMonitorObjectInstance parent;

        private MonitorContentsSlot(Sonic3kMonitorObjectInstance parent, ObjectSpawn spawn) {
            super(spawn, "MonitorContents");
            this.parent = parent;
        }

        private MonitorContentsSlot(Sonic3kMonitorObjectInstance parent) {
            this(parent, parent.buildSpawnAt(parent.posX(), parent.posY()));
        }

        @Override
        public MonitorContentsSlot recreateForRewind(RewindRecreateContext ctx) {
            Sonic3kMonitorObjectInstance restoredParent =
                    RewindRecreateObjectLinks.nearestLiveObject(ctx, Sonic3kMonitorObjectInstance.class);
            return restoredParent == null ? null : new MonitorContentsSlot(restoredParent, ctx.spawn());
        }

        @Override
        public void update(int vIntRunCount, PlayableEntity player) {
            if (!parent.isMonitorContentsSlotActive()) {
                setDestroyed(true);
            }
        }

        @Override
        public int getX() {
            return parent.posX();
        }

        @Override
        public int getY() {
            return parent.iconSubY >> 8;
        }

        @Override
        public ObjectSpawn getSpawn() {
            return parent.buildSpawnAt(getX(), getY());
        }

        @Override
        public boolean isDestroyed() {
            return super.isDestroyed() || parent.isDestroyed();
        }

        @Override
        public void appendRenderCommands(List<GLCommand> commands) {
            // Slot-only mirror of ROM Obj_MonitorContents. The parent shell
            // still owns the existing embedded icon render/update state.
        }
    }

    /**
     * S3K Monitor subtypes.
     * Mapping: 0=Eggman, 1=1-Up, 2=Eggman, 3=Rings, 4=SpeedShoes,
     * 5=FireShield, 6=LightningShield, 7=BubbleShield, 8=Invincibility, 9=Super.
     */
    private enum MonitorType {
        EGGMAN(0),
        ONE_UP(1),
        EGGMAN_2(2),
        RINGS(3),
        SPEED_SHOES(4),
        FIRE_SHIELD(5),
        LIGHTNING_SHIELD(6),
        BUBBLE_SHIELD(7),
        INVINCIBILITY(8),
        SUPER(9);

        /** Animation ID (also used for subtype matching). */
        private final int animId;

        MonitorType(int animId) {
            this.animId = animId;
        }

        static MonitorType fromSubtype(int subtype) {
            int value = subtype & 0xF;
            for (MonitorType t : values()) {
                if (t.animId == value) {
                    return t;
                }
            }
            return EGGMAN;
        }
    }
}
