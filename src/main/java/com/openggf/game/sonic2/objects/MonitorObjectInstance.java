package com.openggf.game.sonic2.objects;
import com.openggf.audio.GameMusic;
import com.openggf.level.objects.ObjectAnimationState;
import com.openggf.level.objects.ExplosionObjectInstance;

import com.openggf.game.sonic2.constants.Sonic2AnimationIds;
import com.openggf.game.PlayableEntity;
import com.openggf.game.sonic2.audio.Sonic2Sfx;
import com.openggf.game.sonic2.constants.Sonic2AudioConstants;

import com.openggf.level.objects.AbstractMonitorObjectInstance;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.ObjectLifetimeOps;
import com.openggf.level.objects.ObjectRenderManager;
import com.openggf.level.objects.ObjectServices;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.objects.SolidContact;
import com.openggf.level.objects.SolidObjectListener;
import com.openggf.level.objects.SolidObjectParams;
import com.openggf.level.objects.SolidObjectProvider;
import com.openggf.level.objects.SolidRoutineProfile;
import com.openggf.level.objects.TouchResponseListener;
import com.openggf.level.objects.TouchResponseProfile;
import com.openggf.level.objects.TouchResponseProvider;
import com.openggf.level.objects.TouchResponseResult;

import com.openggf.graphics.GLCommand;
import com.openggf.physics.ObjectTerrainUtils;
import com.openggf.physics.TerrainCheckResult;
import com.openggf.graphics.RenderPriority;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.sprites.Sprite;
import com.openggf.sprites.managers.SpriteManager;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.game.sonic2.audio.Sonic2SmpsConstants;

import java.util.List;
import java.util.logging.Logger;

public class MonitorObjectInstance extends AbstractMonitorObjectInstance implements TouchResponseProvider, TouchResponseListener,
        SolidObjectProvider, SolidObjectListener, RewindRecreatable {
    private static final Logger LOGGER = Logger.getLogger(MonitorObjectInstance.class.getName());
    private static final int HALF_RADIUS = 0x0E;
    private static final int BROKEN_FRAME = 0x0B;
    private static final int RING_MONITOR_REWARD = 10;

    // Monitor falling constants (from ROM: Touch_Monitor and Obj26_Main)
    private static final int FALLING_INITIAL_VEL = -0x180;  // Upward pop velocity when hit from below
    private static final int FALLING_GRAVITY = 0x38;        // Same gravity as other objects

    private MonitorType type;
    private ObjectAnimationState animationState;
    private boolean broken;
    private int mappingFrame;

    // Falling state (routineSecondary != 0 in ROM)
    private boolean falling;
    private int yVel;
    private int yFixed;
    private int currentY;

    private boolean initialized;
    private boolean mainCharacterStanding;
    private boolean mainCharacterPushing;
    private boolean sidekickStanding;
    private boolean sidekickPushing;
    private String lastTouchBranch = "none";
    private int lastTouchYSpeed;
    private int lastTouchPlayerY;
    private int lastTouchMonitorY;
    private int lastTouchAnimation;
    private int lastTouchMoveLock;
    private int lastTouchForcedAnimation;
    private boolean lastTouchObjectControlled;
    private boolean lastTouchRolling;
    private String lastTouchAnimationProfile = "none";
    private int lastTouchAnimationScriptCount;

    public MonitorObjectInstance(ObjectSpawn spawn, String name) {
        super(spawn, name);
        this.type = MonitorType.fromSubtype(spawn.subtype());
        this.broken = this.type == MonitorType.BROKEN;

        int initialFrame = broken ? BROKEN_FRAME : 0;
        this.mappingFrame = initialFrame;
        if (broken) {
            effectApplied = true;
        }

        // Initialize position tracking for falling behavior
        this.currentY = spawn.y();
        this.yFixed = spawn.y() << 8;
    }

    @Override
    public MonitorObjectInstance recreateForRewind(RewindRecreateContext ctx) {
        return new MonitorObjectInstance(ctx.spawn(), "Monitor");
    }

    private void ensureInitialized() {
        if (initialized) {
            return;
        }
        initialized = true;

        // Check persistence: if remembered, spawn as broken
        ObjectManager objectManager = services().objectManager();
        boolean previouslyBroken = objectManager != null && objectManager.isRemembered(spawn);
        if (previouslyBroken && !broken) {
            this.broken = true;
            this.mappingFrame = BROKEN_FRAME;
            effectApplied = true;
        }

        int initialAnim = type.id;
        int initialFrame = broken ? BROKEN_FRAME : 0;
        ObjectRenderManager renderManager = services().renderManager();
        this.animationState = new ObjectAnimationState(
                renderManager != null ? renderManager.getMonitorAnimations() : null,
                initialAnim,
                initialFrame);
    }

    @Override
    public boolean shouldStayActiveWhenRemembered() {
        // Monitor needs to stay active to show icon rising and apply powerup effect
        // After breaking, it remains as a broken monitor frame (doesn't self-destruct)
        return true;
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        ensureInitialized();
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        // Handle falling state first (ROM: Obj26_Main routine_secondary check)
        if (falling) {
            updateFalling();
        }

        if (!broken) {
            animationState.update();
            mappingFrame = animationState.getMappingFrame();
            return;
        }
        // ROM Obj26_Break leaves the shell as the broken monitor. Obj2E monitor
        // contents is a separate dynamic object, so the shell does not own icon
        // rise or power-up timing after the break.
    }

    /**
     * Update falling monitor (after being hit from below).
     * ROM reference: s2.asm lines 25401-25408 (ObjectMoveAndFall + ObjCheckFloorDist)
     */
    private void updateFalling() {
        // ObjectMoveAndFall: apply velocity and gravity
        yFixed += yVel;
        yVel += FALLING_GRAVITY;
        currentY = yFixed >> 8;

        // ObjCheckFloorDist: check for floor collision
        TerrainCheckResult result = ObjectTerrainUtils.checkFloorDist(spawn.x(), currentY, HALF_RADIUS);
        if (result.hasCollision()) {
            // Snap to floor and stop falling
            currentY = currentY + result.distance();
            yFixed = currentY << 8;
            yVel = 0;
            falling = false;
        }
    }

    @Override
    public void onTouchResponse(PlayableEntity playerEntity, TouchResponseResult result, int frameCounter) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        if (broken || player == null) {
            return;
        }
        lastTouchBranch = "enter";
        lastTouchYSpeed = player.getYSpeed();
        lastTouchPlayerY = player.getCentreY();
        lastTouchMonitorY = currentY;
        lastTouchAnimation = player.getAnimationId();
        lastTouchMoveLock = player.getMoveLockTimer();
        lastTouchForcedAnimation = player.getForcedAnimationId();
        lastTouchObjectControlled = player.isObjectControlled();
        lastTouchRolling = player.getRolling();
        lastTouchAnimationProfile = player.getAnimationProfile() == null
                ? "none"
                : player.getAnimationProfile().getClass().getSimpleName();
        lastTouchAnimationScriptCount = player.getAnimationSet() == null
                ? -1
                : player.getAnimationSet().getScriptCount();

        // Hitting from below (Moving Up)
        // ROM reference: Touch_Monitor (s2.asm lines 84742-84763)
        // Check: player.y - 0x10 >= monitor.y (player center minus 16 must be >= monitor y)
        if (player.getYSpeed() < 0) {
            int playerCenterY = player.getCentreY();
            int monitorY = currentY;  // Use current Y position (may have moved if falling)

            // ROM check: move.w y_pos(a0),d0; subi.w #$10,d0; cmp.w y_pos(a1),d0; blo.s return
            // If player center - 16 >= monitor Y, then player is hitting from below
            if (playerCenterY - 0x10 >= monitorY) {
                lastTouchBranch = "below";
                LOGGER.fine(() -> "Monitor hit from below: player at (" + player.getX() + "," + player.getY() +
                    ") ySpeed=" + player.getYSpeed() + " monitor at (" + spawn.x() + "," + currentY + ")");

                // Bounce player down (neg.w y_vel(a0))
                player.setYSpeed((short) -player.getYSpeed());

                // Make monitor pop up and fall (only if not already falling)
                if (!falling) {
                    falling = true;
                    yVel = FALLING_INITIAL_VEL;  // -0x180 upward
                }
            } else {
                lastTouchBranch = "below-side-return";
            }
            return;
        }

        // ROM Touch_Monitor .breakMonitor: cmpa.w #MainCharacter,a0 / beq +
        // / tst.w (Two_player_mode).w / beq return. A CPU sidekick can knock a
        // monitor down from below (handled above) but cannot break it in
        // single-player mode — only the lead character, or a human-controlled
        // player in 2P/competition mode, may. 2P mode is unimplemented, so the
        // sidekick is always blocked here. (s2.asm:85337-85340; S1/S3K match.)
        if (isSidekick(player)) {
            lastTouchBranch = "sidekick-no-break";
            return;
        }

        // Hitting from above (Moving Down or Stationary)
        // ROM: Touch_Monitor checks anim(a0) == AniIDSonAni_Roll here, not the
        // broader rolling status bit. The animation transition lags status changes
        // by a frame in some cases, which affects monitor break timing.
        if (player.getAnimationId() != Sonic2AnimationIds.ROLL.id()) {
            lastTouchBranch = "not-roll-return";
            return;
        }

        // Break Monitor and Bounce Player Up
        broken = true;

        boolean touchingMonitorAsSolid = wasTouchingMonitor(player);
        lastTouchBranch = "break-tms=" + (touchingMonitorAsSolid ? "1" : "0");

        // Mark as broken in persistence table
        ObjectManager objectManager = services().objectManager();
        ObjectLifetimeOps.markSpawnRemembered(objectManager, spawn);
        if (objectManager != null) {
            objectManager.clearRidingObject(player);
        }

        // ROM Obj26_Break only forces the character airborne when the monitor's
        // own standing/pushing bits were set for that character.
        if (touchingMonitorAsSolid) {
            player.setOnObject(false);
            player.setPushing(false);
            player.setAir(true);
        }
        clearTouchingMonitor(player);
        // ROM Obj26_SpawnIcon opens with `clr.b status(a0)`
        // (docs/s2disasm/s2.asm:25691), wiping the monitor's own standing and
        // pushing bits for BOTH characters unconditionally on the break path --
        // not just for the character that broke it.
        if (objectManager != null) {
            objectManager.solidContacts().releaseObjectPushLatchForAllPlayers(this);
        }
        releaseTouchingCharactersOnBreak(objectManager, player);
        player.setYSpeed((short) -player.getYSpeed());
        mappingFrame = BROKEN_FRAME;
        MonitorContentsObjectInstance contents = spawnFreeChild(() -> new MonitorContentsObjectInstance(
                spawn.x(), spawn.y(), type.id, player));
        if (contents.getSlotIndex() >= 0
                && getSlotIndex() >= 0
                && contents.getSlotIndex() < getSlotIndex()) {
            contents.delayFirstIconUpdateForPassedSlot();
        }

        ObjectRenderManager renderManager = services().renderManager();
        if (renderManager != null) {
            // ROM Obj26_SpawnSmoke allocates the explosion and gives it
            // routine 2, so its first execution runs Obj27_Init and that
            // routine plays SndID_Explosion (docs/s2disasm/s2.asm:25702-25707,
            // :46717-46734). The sound therefore belongs to the explosion's
            // own pass, not to the touch that broke the monitor.
            ExplosionObjectInstance explosion = spawnFreeChild(() ->
                    new ExplosionObjectInstance(0x27, spawn.x(), spawn.y(), renderManager,
                            Sonic2Sfx.EXPLOSION.id));
            if (explosion.getSlotIndex() >= 0
                    && getSlotIndex() >= 0
                    && explosion.getSlotIndex() < getSlotIndex()) {
                // AllocateObject is lowest-free, so the explosion often lands
                // below the monitor's own slot, which the ROM's scan has
                // already passed; Obj27 then first executes next frame.
                explosion.delayFirstUpdateForPassedSlot();
            }
        }
    }

    @Override
    public String traceDebugDetails() {
        return String.format("touch=%s ys=%04X py=%04X my=%04X anim=%02X roll=%d ml=%02X forced=%02X objctl=%d prof=%s scripts=%d broken=%d fall=%d mcS=%d mcP=%d skS=%d skP=%d",
                lastTouchBranch,
                lastTouchYSpeed & 0xFFFF,
                lastTouchPlayerY & 0xFFFF,
                lastTouchMonitorY & 0xFFFF,
                lastTouchAnimation & 0xFF,
                lastTouchRolling ? 1 : 0,
                lastTouchMoveLock & 0xFF,
                lastTouchForcedAnimation & 0xFF,
                lastTouchObjectControlled ? 1 : 0,
                lastTouchAnimationProfile,
                lastTouchAnimationScriptCount,
                broken ? 1 : 0,
                falling ? 1 : 0,
                mainCharacterStanding ? 1 : 0,
                mainCharacterPushing ? 1 : 0,
                sidekickStanding ? 1 : 0,
                sidekickPushing ? 1 : 0);
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        ObjectRenderManager renderManager = services().renderManager();
        if (renderManager == null) {
            appendFallbackBox(commands);
            return;
        }
        PatternSpriteRenderer renderer = renderManager.getMonitorRenderer();
        if (renderer == null || !renderer.isReady()) {
            appendFallbackBox(commands);
            return;
        }
        int frameIndex = broken ? BROKEN_FRAME : mappingFrame;
        // Use currentY for rendering (supports falling animation)
        renderer.drawFrameIndex(frameIndex, spawn.x(), currentY, false, false);

    }

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
        // ROM TouchResponse polls Obj26 every frame. The first overlap can see
        // status.player.rolling before anim reaches AniIDSonAni_Roll, so keep
        // rechecking while the player remains inside the monitor touch box.
        return true;
    }

    @Override
    public SolidObjectParams getSolidParams() {
        return SolidObjectParams.of(0x1A, 0x0F, 0x10);
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
        if (isSidekick(player)) {
            // S2 one-player mode: SolidObject_Monitor_Tails always branches to
            // SolidObject_cont before checking roll anim (docs/s2disasm/s2.asm:25459-25466).
            return true;
        }
        // ROM: SolidObject_Monitor_Sonic (s2disasm/s2.asm:25586-25616):
        //   btst d6,status(a0)              ; is Sonic already standing on the monitor?
        //   bne.s Obj26_ChkOverEdge         ; if yes → carry him regardless of rolling
        //   cmpi.b #AniIDSonAni_Roll,anim(a1) ; is Sonic's ANIMATION the ball/jump anim?
        //   bne.w SolidObject_cont           ; if not → solid
        //   rts                              ; if so → not solid (new landing blocked)
        // The rolling gate only blocks NEW landings. A player already standing
        // bypasses the check and goes straight to the edge/carry path.
        //
        // ROM keys this on the anim(a1) BYTE, not on the status.rolling bit. The two
        // agree on the ground, but a rolling jump stays anim==Roll for its whole
        // airborne arc (Sonic_Jump writes anim=Roll, Obj01_MdAir never re-runs
        // Sonic_Move; s2.asm:37392,36494-36506). Touch_Monitor's break gate also
        // tests anim==Roll (s2.asm:85337-85343), so both the solidity gate and the
        // break gate MUST consume the same anim signal or a rolling-jump player can
        // land-without-breaking when the engine's rolling status bit is cleared a
        // frame before the anim. Using getAnimationId() here keeps land-vs-break
        // consistent. S1 (s1disasm/_incObj: SolidObject_Monitor / Touch_Monitor) and
        // S3K (sonic3k.asm Touch_Monitor / SolidObject_Monitor) gate on the same
        // Roll anim, so this is a universal correction.
        // ROM btst d6,status(a0): the "already standing on this monitor" bypass is a
        // LIVE per-frame status bit, not a persistent latch. It is only true while the
        // player is actually riding THIS monitor; the moment the player jumps,
        // Sonic_Jump's clearRidingObjectForJump drops the riding link and the bit is
        // gone, so a subsequent rolling jump is gated purely by the anim==Roll check.
        // Using the sticky mainCharacterStanding flag here instead let a stale standing
        // latch (set on an earlier landing and never cleared on take-off) keep the
        // monitor solid, so a later rolling jump landed on it instead of breaking it.
        ObjectManager objectManager = services().objectManager();
        boolean currentlyRidingThisMonitor = objectManager != null
                && objectManager.isRidingObject(player, this);
        if (currentlyRidingThisMonitor) {
            return true;
        }
        if (player.getAnimationId() == Sonic2AnimationIds.ROLL.id()
                && hasNearbyOozLauncherResidueSource(player)) {
            // Obj3D's off-screen release clears obj_control/on_object but does
            // not write anim (docs/s2disasm/s2.asm:51159-51170). In the OOZ1
            // monitor route, the ROM anim byte has already returned to Walk
            // before Obj26 samples it, while the engine can still carry Obj3D's
            // object-written Roll for the same airborne release. Treat only that
            // live Obj3D release residue as the non-roll anim byte Obj26 sees;
            // ordinary rolling jumps and LauncherBall captures still use Roll.
            player.setAnimationId(Sonic2AnimationIds.WALK);
            return true;
        }
        return player.getAnimationId() != Sonic2AnimationIds.ROLL.id();
    }

    private boolean hasNearbyOozLauncherResidueSource(AbstractPlayableSprite player) {
        if (player == null
                || player.isObjectControlled()
                || player.isOnObject()
                || !player.getAir()
                || !player.getRolling()
                || player.getRollingJump()
                // Obj3D's release path leaves only the brief nonzero ground inertia visible before
                // Obj26 samples Sonic's animation; once it is gone, Roll remains non-solid.
                || player.getGSpeed() == 0
                || player.getYSpeed() <= 0) {
            return false;
        }

        ObjectManager objectManager = services().objectManager();
        if (objectManager == null) {
            return false;
        }
        for (ObjectInstance object : objectManager.getActiveObjects()) {
            if (!(object instanceof OOZLauncherObjectInstance) || object == this) {
                continue;
            }
            int objectDx = Math.abs((object.getX() & 0xFFFF) - (getX() & 0xFFFF));
            int objectDy = Math.abs((object.getY() & 0xFFFF) - (getY() & 0xFFFF));
            int playerDx = Math.abs((player.getCentreX() & 0xFFFF) - (getX() & 0xFFFF));
            int playerDy = Math.abs((player.getCentreY() & 0xFFFF) - (getY() & 0xFFFF));
            if (objectDx <= 0x80 && objectDy <= 0x80 && playerDx <= 0x80 && playerDy <= 0x80) {
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean hasMonitorSolidity() {
        // S2 Obj26 does not use the SPG Mon_SolidSides geometry. Its monitor
        // wrapper gates roll-animation hits, then branches to SolidObject_cont
        // for normal solid classification (docs/s2disasm/s2.asm:25448-25452).
        return false;
    }

    @Override
    public boolean usesInclusiveRightEdge() {
        // ROM Obj26 SolidObject_Monitor_* branches to SolidObject_cont
        // (docs/s2disasm/s2.asm:25628), whose X bounds gate rejects the right
        // edge with bhi: `cmp.w d3,d0 / bhi.w SolidObject_TestClearPush`
        // (docs/s2disasm/s2.asm:25587 width=$1A, s2.asm:35347-35348). bhi is a
        // strictly-greater test, so relX == halfWidth*2 (the player centred
        // exactly on the monitor's right solid edge) is still INSIDE the box
        // and resolves as a zero-distance side contact (SolidObject_AtEdge,
        // s2.asm:35427-35446 sets the pushing bit without shoving x_pos).
        // The engine default uses an exclusive (>=) bound, which dropped the
        // CPU sidekick's edge contact one pixel early: in MCZ2 the CPU Tails
        // walking right into the monitor reached centreX == 0x0E2A (= 0x0E10 +
        // $1A, the exact right edge) and the exclusive gate returned no contact,
        // so Tails was never pinned/pushing and the ROM auto-jump branch never
        // fired. Match the ROM bhi semantics with the inclusive right edge.
        return true;
    }

    @Override
    public SolidRoutineProfile getSolidRoutineProfile() {
        return SolidRoutineProfile.fullSolid(false, usesInclusiveRightEdge(), bypassesOffscreenSolidGate());
    }

    @Override
    public boolean projectsPreMovementGroundXForSolidContact(PlayableEntity player) {
        // Obj26 calls SolidObject_Monitor for Sonic and Tails from Obj26_Main
        // after the player slots have already run for the frame
        // (docs/s2disasm/s2.asm:25579-25605). The helper then branches to
        // SolidObject_cont for a fresh side hit (docs/s2disasm/s2.asm:25617-25636),
        // whose left-side path zeroes x_vel/inertia when the player has crossed
        // into the monitor (docs/s2disasm/s2.asm:35424-35439). The engine's
        // object pass runs before grounded player movement, so project that
        // pending flat-ground X step only for Obj26's new side-entry check.
        return true;
    }

    @Override
    public boolean projectsPreMovementAirYForSolidContact(PlayableEntity player) {
        // Same execution-order compensation as the X hook above, but only for
        // airborne top crossings. ROM Obj26_Main reaches
        // SolidObject_Monitor_Sonic after Obj01 has already applied y_vel to
        // y_pos (docs/s2disasm/s2.asm:25579-25623), then SolidObject_cont
        // accepts the crossed top band via SolidObject_Landed
        // (docs/s2disasm/s2.asm:35344-35500). In the pre-movement engine pass
        // the monitor would otherwise see the player still just above the box.
        return true;
    }

    @Override
    public boolean bypassesOffscreenSolidGate() {
        // Obj26's monitor wrappers branch directly to SolidObject_cont, not to
        // SolidObject_OnScreenTest (docs/s2disasm/s2.asm:25617-25636). Keep
        // monitor side/top collision live even when the generic full-solid
        // on-screen optimisation would skip it.
        return true;
    }

    @Override
    public boolean usesStickyContactBuffer() {
        // Monitors are static objects — the sticky buffer is only needed for
        // moving platforms to compensate for execution-order jitter. Using it
        // here would extend riding bounds 16px beyond the ROM's ExitPlatform width.
        return false;
    }

    @Override
    public void onSolidContact(PlayableEntity playerEntity, SolidContact contact, int frameCounter) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        if (player == null) {
            return;
        }
        if (contact.standing()) {
            setStandingOnMonitor(player, true);
        }
        if (contact.pushing()) {
            setPushingMonitor(player, true);
        }
    }

    private boolean wasTouchingMonitor(AbstractPlayableSprite player) {
        return isSidekick(player)
                ? sidekickStanding || sidekickPushing
                : mainCharacterStanding || mainCharacterPushing;
    }

    private void clearTouchingMonitor(AbstractPlayableSprite player) {
        setStandingOnMonitor(player, false);
        setPushingMonitor(player, false);
    }

    private void releaseTouchingCharactersOnBreak(ObjectManager objectManager, AbstractPlayableSprite breaker) {
        SpriteManager spriteManager = services().spriteManager();
        if (spriteManager == null) {
            return;
        }
        for (Sprite sprite : spriteManager.getAllSprites()) {
            if (!(sprite instanceof AbstractPlayableSprite playable) || playable == breaker) {
                continue;
            }
            if (!wasTouchingMonitor(playable)) {
                continue;
            }
            if (objectManager != null
                    && !objectManager.isRidingObject(playable, this)
                    && !playable.getPushing()) {
                // Obj26_Break clears another character only when this
                // monitor's own p1/p2 standing or pushing bit still describes
                // live contact (docs/s2disasm/s2.asm:25675-25688). A stale
                // monitor bit must not clear a separate object latch, e.g. an
                // offscreen Sidekick still carrying MTZ Obj69's Status_OnObj.
                clearTouchingMonitor(playable);
                continue;
            }
            spriteManager.deferCrossPlayableMutationUntilPostTick(
                    playable,
                    () -> releaseTouchingCharacterOnBreak(objectManager, playable));
        }
    }

    private void releaseTouchingCharacterOnBreak(ObjectManager objectManager, AbstractPlayableSprite player) {
        if (objectManager != null) {
            objectManager.clearRidingObject(player);
        }
        player.setOnObject(false);
        player.setPushing(false);
        player.setAir(true);
        clearTouchingMonitor(player);
    }

    private void setStandingOnMonitor(AbstractPlayableSprite player, boolean standing) {
        if (isSidekick(player)) {
            sidekickStanding = standing;
        } else {
            mainCharacterStanding = standing;
        }
    }

    private void setPushingMonitor(AbstractPlayableSprite player, boolean pushing) {
        if (isSidekick(player)) {
            sidekickPushing = pushing;
        } else {
            mainCharacterPushing = pushing;
        }
    }

    private boolean isSidekick(AbstractPlayableSprite player) {
        return player.isCpuControlled();
    }

    @Override
    protected void applyPowerup(PlayableEntity playerEntity) {
        applyMonitorPowerup(type.id, playerEntity, services());
    }

    static void applyMonitorPowerup(int subtype, PlayableEntity playerEntity, ObjectServices services) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        switch (MonitorType.fromSubtype(subtype)) {
            case RINGS -> {
                player.addRings(RING_MONITOR_REWARD);
                // ROM super_ring reaches the driver through the music mailbox,
                // not the SFX queue: it ends `move.w #SndID_Ring,d0 /
                // jmp (PlayMusic).l` (s2.asm:25864, :25913-25914). The driver
                // still applies the ring speaker alternation, because
                // QueueToPlay hands an SFX-range byte to zPlaySound_CheckRing
                // (s2.sounddriver.asm:1565-1571, :2116-2135).
                services.playMusicMailboxNativeRequest(
                        Sonic2AudioConstants.SFX_RING_RIGHT);
            }
            case SHIELD -> {
                player.giveShield();
                // ROM shield_monitor likewise uses the music mailbox:
                // `move.w #SndID_Shield,d0 / jsr (PlayMusic).l`
                // (s2.asm:25953-25956).
                services.playMusicMailboxNativeRequest(Sonic2Sfx.SHIELD.id);
            }
            case SHOES -> {
                player.giveSpeedShoes();
                services.playMusic(Sonic2SmpsConstants.CMD_SPEED_UP);
            }
            case INVINCIBILITY -> {
                // ROM: tst.b (Super_Sonic_flag).w / bne.s +++ - skip when Super Sonic
                if (!player.isSuperSonic()) {
                    player.giveInvincibility();
                    services.playMusic(GameMusic.INVINCIBILITY);
                }
            }
            case SONIC, TAILS -> {
                services.playMusic(GameMusic.EXTRA_LIFE);
                services.gameState().addLife();
            }
            case EGGMAN, STATIC -> {
                // ROM: robotnik_monitor (s2.asm:25656-25658)
                // Both Static (subtype 0) and Eggman (subtype 3) call Touch_ChkHurt2.
                // Hurts the player as if touching a badnik.
                player.setHurt(true);
            }
            case TELEPORT -> {
                // ROM: teleport_monitor (s2.asm:25825-25845)
                // Swaps player positions in 2P mode. No-op in 1P mode.
                // 2P mode is not yet implemented.
            }
            case RANDOM -> {
                // ROM: qmark_monitor (s2.asm:26018-26020)
                // addq.w #1,(a2) / rts — no gameplay effect.
            }
            default -> {}
        }
    }

    @Override
    public int getPriorityBucket() {
        return RenderPriority.clamp(3);
    }

    @Override
    public int getY() {
        return currentY;
    }

    @Override
    public ObjectSpawn getSpawn() {
        // Return spawn with dynamic Y for solid collision checks
        if (currentY != spawn.y()) {
            return buildSpawnAt(spawn.x(), currentY);
        }
        return spawn;
    }

    /**
     * Debug fallback: render as a colored box when art is unavailable.
     */
    private void appendFallbackBox(List<GLCommand> commands) {
        int cx = spawn.x();
        int cy = currentY;
        int left = cx - HALF_RADIUS;
        int right = cx + HALF_RADIUS;
        int top = cy - HALF_RADIUS;
        int bottom = cy + HALF_RADIUS;
        float r = 0.4f, g = 0.9f, b = 1.0f;
        commands.add(new GLCommand(GLCommand.CommandType.VERTEX2I, -1, GLCommand.BlendType.SOLID, r, g, b, left, top, 0, 0));
        commands.add(new GLCommand(GLCommand.CommandType.VERTEX2I, -1, GLCommand.BlendType.SOLID, r, g, b, right, top, 0, 0));
        commands.add(new GLCommand(GLCommand.CommandType.VERTEX2I, -1, GLCommand.BlendType.SOLID, r, g, b, right, top, 0, 0));
        commands.add(new GLCommand(GLCommand.CommandType.VERTEX2I, -1, GLCommand.BlendType.SOLID, r, g, b, right, bottom, 0, 0));
        commands.add(new GLCommand(GLCommand.CommandType.VERTEX2I, -1, GLCommand.BlendType.SOLID, r, g, b, right, bottom, 0, 0));
        commands.add(new GLCommand(GLCommand.CommandType.VERTEX2I, -1, GLCommand.BlendType.SOLID, r, g, b, left, bottom, 0, 0));
        commands.add(new GLCommand(GLCommand.CommandType.VERTEX2I, -1, GLCommand.BlendType.SOLID, r, g, b, left, bottom, 0, 0));
        commands.add(new GLCommand(GLCommand.CommandType.VERTEX2I, -1, GLCommand.BlendType.SOLID, r, g, b, left, top, 0, 0));
    }

    private enum MonitorType {
        STATIC(0),
        SONIC(1),
        TAILS(2),
        EGGMAN(3),
        RINGS(4),
        SHOES(5),
        SHIELD(6),
        INVINCIBILITY(7),
        TELEPORT(8),
        RANDOM(9),
        BROKEN(10);

        private final int id;

        MonitorType(int id) {
            this.id = id;
        }

        static MonitorType fromSubtype(int subtype) {
            int value = subtype & 0xF;
            for (MonitorType type : values()) {
                if (type.id == value) {
                    return type;
                }
            }
            return STATIC;
        }
    }
}
