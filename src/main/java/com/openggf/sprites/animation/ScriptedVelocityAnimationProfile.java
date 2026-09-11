package com.openggf.sprites.animation;

import com.openggf.game.AnimationId;
import com.openggf.physics.Direction;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.sprites.managers.PlayableSpriteAnimation;
import com.openggf.sprites.playable.SecondaryAbility;

/**
 * Chooses animation script IDs based on simple movement state.
 */
public class ScriptedVelocityAnimationProfile implements SpriteAnimationProfile {
    private int idleAnimId;
    // S2 impatient-wait interrupt anims (Obj01_MdNormal_Checks); -1 = game has none.
    private int blinkAnimId = -1;
    private int getUpAnimId = -1;
    private int walkAnimId;
    private int runAnimId;
    private int rollAnimId;
    private int roll2AnimId = -1;
    private int pushAnimId = -1;
    // Some character-specific $FF Walk handlers select the push script from
    // Status_Push without replacing the public raw animation byte.
    private boolean pushUsesWalkSpecialHandler;
    // Reload-timer shift for the $FF Walk handler's push sub-branch:
    // ROM Animate_Sonic/loc_12A72 uses lsr.w #6 (sonic3k.asm:25193), while
    // Animate_Knuckles/loc_17ECC uses lsr.w #8 (sonic3k.asm:33216). Defaults
    // to 6 so existing Sonic/Tails/S2 profiles are unaffected.
    private int pushDelayShift = 6;
    // S3K's Player_ChkWalk clears Duck before its no-input preservation tail;
    // earlier movement routines retain the previously published byte there.
    private boolean duckReleasePublishesWalk;
    // S3K slide terrain may publish its final airborne animation after the
    // playable routine while Status_Roll is still set for that frame.
    private boolean airborneSlidePreservesPublishedAnimation;
    private int duckAnimId = -1;
    private int lookUpAnimId = -1;
    private int spindashAnimId = -1;
    private int springAnimId = -1;
    private int deathAnimId = -1;
    private int hurtAnimId = -1;
    private int skidAnimId = -1;
    private int slideAnimId = -1;
    private int drownAnimId = -1;
    private int airAnimId;
    // Balance animations (ROM s2.asm:36246-36373)
    private int balanceAnimId = -1;   // 0x06 - facing toward edge, safe distance
    private int balance2AnimId = -1;  // 0x0C - facing toward edge, closer to falling
    private int balance3AnimId = -1;  // 0x1D - facing away from edge, safe distance
    private int balance4AnimId = -1;  // 0x1E - facing away from edge, closer to falling
    private int runSpeedThreshold;
    private int walkSpeedThreshold;
    private int fallbackFrame;
    // Classic Sonic movement keeps the raw anim byte at Walk and selects the
    // Run frame script by speed inside Animate_*.
    private boolean runFramesUseWalkAnimationId;
    // S2 adjusts the angle by -1 for positive values before computing the slope frame
    // offset (subq.b #1,d0 at s2.asm:38080). S1 does not do this.
    private boolean anglePreAdjust;
    // S2 Super Run uses compact slope layout (lsr.b #1,d0 = d0/2), while S3K Super Run
    // uses standard run spacing (add.b d0,d0 = d0*2). ROM: s2.asm:38159 vs s3.asm:22323.
    private boolean compactSuperRunSlope;
    // Some native character handlers publish the current walk/run mapping
    // before advancing their frame timer; others gate the mapping write first.
    private boolean walkRunPublishesFrameBeforeTimerAdvance;
    // Optional third locomotion tier selected inside the native $FF walk
    // handler without changing the public animation byte (for example Tails'
    // AniTails1F/HaulAss tier at $700).
    private int highSpeedWalkRunAnimId = -1;
    private int highSpeedWalkRunThreshold;
    // Zero retains the legacy script-length-derived slope stride.
    private int walkSlopeFrameStride;
    private int runSlopeFrameStride;
    private int highSpeedSlopeFrameStride;
    private boolean doubleWalkRunAnimationSpeedWhenSliding;
    // Tumble/rotation frame base: S2 = 0x5F (s2.asm:38216), S3K = 0x31 (sonic3k.asm:24955).
    private int tumbleFrameBase = 0x5F;
    // Optional native flip_type-indexed tumble bases. S3K uses
    // byte_1286E={0,$3D,$49,$49} for types 0-3.
    private int[] tumbleTypeFrameBases = new int[0];

    public ScriptedVelocityAnimationProfile() {
    }

    public ScriptedVelocityAnimationProfile setIdleAnimId(int idleAnimId) { this.idleAnimId = idleAnimId; return this; }
    public ScriptedVelocityAnimationProfile setIdleAnimId(AnimationId id) { return setIdleAnimId(id.id()); }
    public ScriptedVelocityAnimationProfile setBlinkAnimId(AnimationId id) { this.blinkAnimId = id.id(); return this; }
    public ScriptedVelocityAnimationProfile setGetUpAnimId(AnimationId id) { this.getUpAnimId = id.id(); return this; }
    public ScriptedVelocityAnimationProfile setWalkAnimId(int walkAnimId) { this.walkAnimId = walkAnimId; return this; }
    public ScriptedVelocityAnimationProfile setWalkAnimId(AnimationId id) { return setWalkAnimId(id.id()); }
    public ScriptedVelocityAnimationProfile setRunAnimId(int runAnimId) { this.runAnimId = runAnimId; return this; }
    public ScriptedVelocityAnimationProfile setRunAnimId(AnimationId id) { return setRunAnimId(id.id()); }
    public ScriptedVelocityAnimationProfile setRollAnimId(int rollAnimId) { this.rollAnimId = rollAnimId; return this; }
    public ScriptedVelocityAnimationProfile setRollAnimId(AnimationId id) { return setRollAnimId(id.id()); }
    public ScriptedVelocityAnimationProfile setRoll2AnimId(int roll2AnimId) { this.roll2AnimId = roll2AnimId; return this; }
    public ScriptedVelocityAnimationProfile setRoll2AnimId(AnimationId id) { return setRoll2AnimId(id.id()); }
    public ScriptedVelocityAnimationProfile setPushAnimId(int pushAnimId) { this.pushAnimId = pushAnimId; return this; }
    public ScriptedVelocityAnimationProfile setPushAnimId(AnimationId id) { return setPushAnimId(id.id()); }
    public ScriptedVelocityAnimationProfile setPushUsesWalkSpecialHandler(boolean value) { this.pushUsesWalkSpecialHandler = value; return this; }
    public ScriptedVelocityAnimationProfile setPushDelayShift(int shift) { this.pushDelayShift = shift; return this; }
    public int getPushDelayShift() { return pushDelayShift; }
    public ScriptedVelocityAnimationProfile setDuckReleasePublishesWalk(boolean value) { this.duckReleasePublishesWalk = value; return this; }
    public ScriptedVelocityAnimationProfile setAirborneSlidePreservesPublishedAnimation(boolean value) { this.airborneSlidePreservesPublishedAnimation = value; return this; }
    public ScriptedVelocityAnimationProfile setDuckAnimId(int duckAnimId) { this.duckAnimId = duckAnimId; return this; }
    public ScriptedVelocityAnimationProfile setDuckAnimId(AnimationId id) { return setDuckAnimId(id.id()); }
    public ScriptedVelocityAnimationProfile setLookUpAnimId(int lookUpAnimId) { this.lookUpAnimId = lookUpAnimId; return this; }
    public ScriptedVelocityAnimationProfile setLookUpAnimId(AnimationId id) { return setLookUpAnimId(id.id()); }
    public ScriptedVelocityAnimationProfile setSpindashAnimId(int spindashAnimId) { this.spindashAnimId = spindashAnimId; return this; }
    public ScriptedVelocityAnimationProfile setSpindashAnimId(AnimationId id) { return setSpindashAnimId(id.id()); }
    public ScriptedVelocityAnimationProfile setSpringAnimId(int springAnimId) { this.springAnimId = springAnimId; return this; }
    public ScriptedVelocityAnimationProfile setSpringAnimId(AnimationId id) { return setSpringAnimId(id.id()); }
    public ScriptedVelocityAnimationProfile setDeathAnimId(int deathAnimId) { this.deathAnimId = deathAnimId; return this; }
    public ScriptedVelocityAnimationProfile setDeathAnimId(AnimationId id) { return setDeathAnimId(id.id()); }
    public ScriptedVelocityAnimationProfile setHurtAnimId(int hurtAnimId) { this.hurtAnimId = hurtAnimId; return this; }
    public ScriptedVelocityAnimationProfile setHurtAnimId(AnimationId id) { return setHurtAnimId(id.id()); }
    public ScriptedVelocityAnimationProfile setSkidAnimId(int skidAnimId) { this.skidAnimId = skidAnimId; return this; }
    public ScriptedVelocityAnimationProfile setSkidAnimId(AnimationId id) { return setSkidAnimId(id.id()); }
    public ScriptedVelocityAnimationProfile setSlideAnimId(int slideAnimId) { this.slideAnimId = slideAnimId; return this; }
    public ScriptedVelocityAnimationProfile setSlideAnimId(AnimationId id) { return setSlideAnimId(id.id()); }
    public ScriptedVelocityAnimationProfile setDrownAnimId(int drownAnimId) { this.drownAnimId = drownAnimId; return this; }
    public ScriptedVelocityAnimationProfile setDrownAnimId(AnimationId id) { return setDrownAnimId(id.id()); }
    public ScriptedVelocityAnimationProfile setAirAnimId(int airAnimId) { this.airAnimId = airAnimId; return this; }
    public ScriptedVelocityAnimationProfile setAirAnimId(AnimationId id) { return setAirAnimId(id.id()); }
    public ScriptedVelocityAnimationProfile setBalanceAnimId(int balanceAnimId) { this.balanceAnimId = balanceAnimId; return this; }
    public ScriptedVelocityAnimationProfile setBalanceAnimId(AnimationId id) { return setBalanceAnimId(id.id()); }
    public ScriptedVelocityAnimationProfile setBalance2AnimId(int balance2AnimId) { this.balance2AnimId = balance2AnimId; return this; }
    public ScriptedVelocityAnimationProfile setBalance2AnimId(AnimationId id) { return setBalance2AnimId(id.id()); }
    public ScriptedVelocityAnimationProfile setBalance3AnimId(int balance3AnimId) { this.balance3AnimId = balance3AnimId; return this; }
    public ScriptedVelocityAnimationProfile setBalance3AnimId(AnimationId id) { return setBalance3AnimId(id.id()); }
    public ScriptedVelocityAnimationProfile setBalance4AnimId(int balance4AnimId) { this.balance4AnimId = balance4AnimId; return this; }
    public ScriptedVelocityAnimationProfile setBalance4AnimId(AnimationId id) { return setBalance4AnimId(id.id()); }
    public ScriptedVelocityAnimationProfile setRunSpeedThreshold(int runSpeedThreshold) { this.runSpeedThreshold = runSpeedThreshold; return this; }
    public ScriptedVelocityAnimationProfile setRunFramesUseWalkAnimationId(boolean value) { this.runFramesUseWalkAnimationId = value; return this; }
    public ScriptedVelocityAnimationProfile setWalkSpeedThreshold(int walkSpeedThreshold) { this.walkSpeedThreshold = walkSpeedThreshold; return this; }
    public ScriptedVelocityAnimationProfile setFallbackFrame(int fallbackFrame) { this.fallbackFrame = fallbackFrame; return this; }
    public ScriptedVelocityAnimationProfile setAnglePreAdjust(boolean anglePreAdjust) { this.anglePreAdjust = anglePreAdjust; return this; }
    public ScriptedVelocityAnimationProfile setCompactSuperRunSlope(boolean compactSuperRunSlope) { this.compactSuperRunSlope = compactSuperRunSlope; return this; }

    /**
     * S2 Super Sonic's alternate-frame step. {@code SAnim_SuperWalk} writes the
     * ordinary {@code mapping_frame = script byte + slope offset} and then, on
     * one frame in four, bumps it into the bright Super variants
     * (docs/s2disasm/s2.asm:38534-38539):
     * <pre>
     *   move.b (Level_frame_counter+1).w,d1
     *   andi.b #3,d1
     *   bne.s  +
     *   cmpi.b #$B5,mapping_frame(a0)
     *   bhs.s  +
     *   addi.b #$20,mapping_frame(a0)
     * </pre>
     * All three numbers are read out of that block: the mask is the
     * {@code andi.b #3}, the ceiling is the {@code cmpi.b #$B5} (the Super run
     * and push frames are already bright and are skipped), and the step is the
     * {@code addi.b #$20}. The rule is off unless a game installs it.
     */
    private int superAlternateFrameMask;
    private int superAlternateFrameStep;
    private int superAlternateFrameCeiling;

    public ScriptedVelocityAnimationProfile setSuperAlternateFrameStep(
            int mask, int step, int ceiling) {
        this.superAlternateFrameMask = mask;
        this.superAlternateFrameStep = step;
        this.superAlternateFrameCeiling = ceiling;
        return this;
    }

    public int getSuperAlternateFrameMask() { return superAlternateFrameMask; }
    public int getSuperAlternateFrameStep() { return superAlternateFrameStep; }
    public int getSuperAlternateFrameCeiling() { return superAlternateFrameCeiling; }

    /**
     * Applies the alternate-frame step to an already-resolved walk/run mapping
     * frame. Returns the frame unchanged when the game installs no such rule,
     * when the sprite is not Super, or when either ROM gate rejects it.
     */
    public int applySuperAlternateFrame(
            int mappingFrame, boolean superActive, int levelFrameCounter) {
        if (superAlternateFrameStep == 0 || !superActive) {
            return mappingFrame;
        }
        if ((levelFrameCounter & superAlternateFrameMask) != 0) {
            return mappingFrame;
        }
        if (mappingFrame >= superAlternateFrameCeiling) {
            return mappingFrame;
        }
        return mappingFrame + superAlternateFrameStep;
    }
    public ScriptedVelocityAnimationProfile setWalkRunPublishesFrameBeforeTimerAdvance(boolean value) { this.walkRunPublishesFrameBeforeTimerAdvance = value; return this; }
    public ScriptedVelocityAnimationProfile setHighSpeedWalkRunAnimId(int value) { this.highSpeedWalkRunAnimId = value; return this; }
    public ScriptedVelocityAnimationProfile setHighSpeedWalkRunThreshold(int value) { this.highSpeedWalkRunThreshold = value; return this; }
    public ScriptedVelocityAnimationProfile setWalkSlopeFrameStride(int value) { this.walkSlopeFrameStride = value; return this; }
    public ScriptedVelocityAnimationProfile setRunSlopeFrameStride(int value) { this.runSlopeFrameStride = value; return this; }
    public ScriptedVelocityAnimationProfile setHighSpeedSlopeFrameStride(int value) { this.highSpeedSlopeFrameStride = value; return this; }
    public ScriptedVelocityAnimationProfile setDoubleWalkRunAnimationSpeedWhenSliding(boolean value) { this.doubleWalkRunAnimationSpeedWhenSliding = value; return this; }
    public ScriptedVelocityAnimationProfile setTumbleFrameBase(int tumbleFrameBase) { this.tumbleFrameBase = tumbleFrameBase; return this; }
    public ScriptedVelocityAnimationProfile setTumbleTypeFrameBases(int... values) {
        this.tumbleTypeFrameBases = values != null ? values.clone() : new int[0];
        return this;
    }

    @Override
    public Integer resolveAnimationId(AbstractPlayableSprite sprite, int frameCounter, int scriptCount) {
        return resolveAnimationId(sprite, frameCounter, scriptCount, true);
    }

    /**
     * @param applyPushRenderSubstitution when false, skips the {@link #pushAnimId}
     *        render substitution so the result is the ROM {@code anim} byte the
     *        movement/state code actually writes (push display is a sub-handler of
     *        the walk script in ROM, not a distinct anim byte). Used by the
     *        Status_Push frame-end clear, which keys on the real anim byte.
     */
    public Integer resolveAnimationId(AbstractPlayableSprite sprite, int frameCounter, int scriptCount,
                                      boolean applyPushRenderSubstitution) {
        // ROM object_control bit 0 suppresses Sonic_Move and the normal
        // movement routines, so they cannot overwrite anim. Bit 7 alone only
        // suppresses touch response: scripted input may still drive ordinary
        // movement and publish animations (CNZ2's rival-Knuckles walk uses
        // object_control=$80 with Ctrl_1_locked; sonic3k.asm:129247-129294).
        if (sprite.isObjectControlSuppressesMovement()) {
            return null;
        }
        // ROM: when move_lock > 0, Sonic_Move doesn't run and doesn't overwrite obAnim.
        // This lets externally-set animations (e.g., bubble-breathing id_GetAir/id_Bubble)
        // persist for the duration of the lock. S3K is the exception for Duck:
        // SonicKnux_Roll/Tails_Roll runs after the move_lock-gated Move routine and
        // writes anim=$08 independently when Down is held below the $100 roll
        // threshold (sonic3k.asm:22434-22435,23223-23240,27796-27797,28458-28475).
        boolean rollCrouchWriteAfterMoveLock = !sprite.isCpuControlled()
                && sprite.getCrouching()
                && sprite.getGameRules() != null
                && sprite.getGameRules().playerMovement() != null
                && sprite.getGameRules().playerMovement().movingCrouchThreshold() > 0;
        if (sprite.getMoveLockTimer() > 0 && !rollCrouchWriteAfterMoveLock) {
            return null;
        }
        // The last locktime tick reaches Sonic_Move while still non-zero, so
        // that routine branches to ResetScr without writing obAnim. The later
        // SlopeRepel call decrements locktime before Sonic_Animate runs. Preserve
        // the movement dispatch result rather than interpreting the final zero
        // timer as an unlocked movement frame (S1 01 Sonic.asm:385-388,
        // 1405-1434; S2 s2.asm:36423-36429,37458-37479; S3K
        // sonic3k.asm:21619-21623,23909-23948).
        if (sprite.getAnimationManager() != null
                && sprite.getAnimationManager().isGroundMovementAnimationSuppressed()
                && !rollCrouchWriteAfterMoveLock) {
            return null;
        }
        // ROM S2 Obj01_MdNormal_Checks (s2.asm:36444-36468): while the
        // impatient-wait Blink/GetUp interrupt animation plays, the whole
        // grounded update -- including Sonic_Move's anim writes -- is skipped,
        // so the anim byte persists until the script's $FD command switches
        // back to walk. Mirror that by leaving the anim untouched here.
        int interruptAnim = sprite.getAnimationId();
        if ((blinkAnimId >= 0 && interruptAnim == blinkAnimId)
                || (getUpAnimId >= 0 && interruptAnim == getUpAnimId)) {
            return null;
        }
        // Drowning uses its own animation (0x17) throughout both pre-death and dead phases
        if (sprite.isDrowningDeath() && drownAnimId >= 0) {
            return drownAnimId;
        }
        if (sprite.getDead() && deathAnimId >= 0) {
            return deathAnimId;
        }
        // Hurt state uses separate hurt animation (animation 0x19).
        //
        // The native hurt animation is a ONE-SHOT byte write on HurtCharacter's
        // common tail (`move.b #$1A,anim(a0)`, sonic3k.asm:21321; equivalents in
        // s1disasm/_incObj/01 Sonic.asm and s2.asm). The hurt ROUTINE that runs on
        // the following frames -- S3K loc_1569C, S1 Sonic_Hurt, S2 Obj01_Hurt --
        // never rewrites anim, so any later owner that stores the byte during the
        // hurt keeps it. The solid push-release tail is exactly such an owner:
        // S3K loc_1E0A2 / S2 SolidObject_TestClearPush / S1 Solid_NoCollision
        // store anim=Walk, prev_anim=Run and exempt only Roll (and Spindash in
        // S3K) -- never Hurt. Re-deriving the hurt animation from the hurt state
        // every frame would clobber that store, so this branch only re-publishes
        // a hurt byte that is still live, exactly as the blink/get-up interrupt
        // above leaves a foreign byte alone.
        if (sprite.isHurt() && hurtAnimId >= 0) {
            return sprite.getAnimationId() == hurtAnimId ? hurtAnimId : null;
        }
        // Tails_FlyingSwimming calls Tails_Set_Flying_Animation every frame and
        // writes anim $20-$28 before the shared animation routine runs
        // (sonic3k.asm:27570-27717). Preserve that ROM-owned anim byte instead
        // of replacing it with the generic airborne walk/roll selection. CPU
        // recovery already reaches the same result through forcedAnimationId;
        // this branch covers player-controlled flight and swimming.
        if (sprite.getSecondaryAbility() == SecondaryAbility.FLY
                && sprite.getTailsFlightController() != null
                && sprite.getTailsFlightController().isActive()) {
            return null;
        }
        // The engine's springing flag is a control-lock timer, not a ROM
        // animation owner. Spring objects write anim explicitly on the launch
        // frame, and variants may instead write Walk for a twirl/horizontal
        // launch. Reasserting Spring for the whole synthetic lock overwrites
        // those later/native bytes (S2 Obj41: s2.asm:33732-33752,
        // 33867-33915,34023-34043).
        // The spindash flag is state, not a continuous animation owner. CheckSpindash
        // writes Spindash on entry, and UpdateSpindash writes it again only on a
        // fresh charge press (S2 Tails: s2.asm:40470-40483,40568-40587). Between
        // those writes, SolidObject_TestClearPush may legally publish Walk while
        // the flag remains set (s2.asm:35462-35487); that byte must survive until
        // another native routine changes it. S2 Obj84's forced-spin pinball mode
        // mirrors the same flag but also writes Status_Roll/anim=Roll. Treat that
        // forced-spin state as distinct from a genuine charge before the generic
        // grounded-roll preservation rule below: Obj84 is the animation owner on
        // entry, so its Roll write must be published explicitly.
        if (sprite.getSpindash() && sprite.getPinballMode() && sprite.getRolling()) {
            return rollAnimId;
        }
        if (sprite.getSpindash() && spindashAnimId >= 0) {
            return null;
        }
        // LZWaterSlides writes Slide only while grounded. A jump/roll dispatch
        // later overwrites it with Roll, but AnglePos terrain detach only changes
        // Status_InAir and prev_anim. Preserve Slide for that detach frame; the
        // next LZWaterSlides call observes InAir, clears slide mode, and installs
        // locktime (S1 LZWaterFeatures.asm:468-513; Sonic AnglePos.asm terrain
        // detach tails).
        if (sprite.isSliding() && !sprite.getAir()) {
            // Sliding is a state bit, not the animation owner. The slide
            // routines explicitly write Slide, Walk, or Wait as their own
            // branches require while leaving the bit set (S2 OilSlides,
            // s2.asm:5537-5643). Preserve that published raw byte.
            return null;
        }
        if (sprite.getAir()) {
            // ROM: Sonic_MdAir/Sonic_MdJump do NOT call Sonic_Move, so the anim
            // field is never overwritten while airborne. Only the ground routine
            // (Sonic_MdNormal) calls Sonic_Move which selects walk/run/idle anim.
            //
            // External object releases can place the player in the air without
            // writing a fresh jump/roll anim byte. S2 Obj80 moving vines release
            // with Status_Roll still set, but leave AniIDSonAni_Hang2 active until
            // Sonic_ResetOnFloor lands and rewrites Walk (s2.asm:56761-56775,
            // 38120-38160). Preserve those object-written bytes before applying
            // the generic airborne roll rule.
            // The ROM anim byte stays AniIDSonAni_Roll for the airborne arc of an
            // actual roll/jump: Sonic_Jump writes anim=Roll + sets status.rolling
            // together (s2.asm:37387-37388), Sonic_RollJump only sets the
            // rolljumping bit and leaves the already-Roll anim alone
            // (s2.asm:37395-37397), and Sonic_MdAir never re-runs Sonic_Move
            // (s2.asm:36791+). This lets a rolling jump break a Monitor on the way
            // down (Touch_Monitor / SolidObject_Monitor_Sonic gate on
            // anim==AniIDSonAni_Roll, s2.asm:25611-25616,85245-85255); S1 and S3K
            // monitor roll gates match (s1disasm/_incObj/26 Monitor.asm,
            // sonic3k.asm Touch_Monitor), so this is a universal correction.
            //
            // Exception: a non-zero flip_angle means a tumble/flip handler owns the
            // frame selection (e.g. S3K CNZ hover fans clear jumping/rollingJump
            // but set flip_angle + an object-written walk/tumble anim, s3.asm
            // sub_31E96). Fall through to the object-anim / walk-tumble path so the
            // fan's frames persist instead of snapping back to the ball.
            if (sprite.isSliding() && (airborneSlidePreservesPublishedAnimation
                    || !sprite.getRolling()
                    || (sprite.isJumping() && sprite.getRollingJump()))) {
                // Slide terrain runs after the playable slot. If terrain
                // collision sets InAir this frame, its explicit slide/tumble
                // anim byte survives even when Status_Roll is still set; the
                // post-player slide handler clears the state for next frame.
                // S3K sub_714E/sub_717C use this ordering.
                return null;
            }
            if (sprite.getRolling() && sprite.getFlipAngle() == 0) {
                // A jump that begins with Status_Roll already set takes the
                // native .rolljump tail: it sets the Roll-Jump bit but does not
                // write obAnim. Preserve whatever explicit owner was active
                // (normally Roll, but LZWaterSlides may have published Slide)
                // throughout that airborne arc (S1 01 Sonic.asm:1203-1274;
                // S2 s2.asm:37318-37397; S3K sonic3k.asm:23303-23363).
                if (sprite.getRollingJump()) {
                    return null;
                }
                if (!sprite.isSliding() && !sprite.getRollingJump() && sprite.getAnimationId() != rollAnimId) {
                    return null;
                }
                return rollAnimId;
            }
            // A frame that began grounded can run the complete Move routine,
            // which writes Walk/Wait, before AnglePos discovers there is no
            // floor and sets Status_InAir. Preserve that already-written
            // ground animation byte on the detach frame; the following frame
            // enters MdAir and no longer has a ground-movement snapshot.
            // S1 GM_Level's fresh-player ExecuteObjects pass in SBZ3 is the
            // canonical case: Sonic_Move writes Wait, then AnglePos sets air.
            PlayableSpriteAnimation animation = sprite.getAnimationManager();
            if (animation != null && animation.hasGroundMovementAnimSpeed()) {
                // A later grounded subroutine can replace Move's selection
                // before AnglePos detaches the player. In S3K, Tails_Roll
                // publishes Duck after Tails_InputAcceleration_Path; neither
                // the detach nor the following airborne routine overwrites it
                // (sonic3k.asm:27518-27531,28458-28513). Keep that later write
                // instead of reconstructing the earlier Walk value.
                if (duckAnimId >= 0 && sprite.getAnimationId() == duckAnimId) {
                    return null;
                }
                return resolveGroundMovementAnimId(sprite);
            }
            if (!sprite.isJumping() && !sprite.getRollingJump() && !sprite.isSliding()) {
                return null;
            }
            // ROM: rolling/jump state writes AniIDSonAni_Roll directly. A non-zero
            // flip_angle only diverts the walk/run animation handler into tumble
            // frames; it does not keep externally-set animations like Float2 active
            // once Sonic is actively rolling/jumping.
            if (sprite.getFlipAngle() != 0) {
                return null;
            }
            return sprite.getRolling() ? rollAnimId : airAnimId;
        }
        if (sprite.getRolling()) {
            // Grounded roll movement does not rewrite the native anim byte.
            // Roll entry publishes Roll once, but subsequent MdRoll frames
            // leave later object writes intact. In retail S1, SolidObject's
            // push-release word write can therefore leave Walk active while
            // Status_Roll remains set (01 Sonic.asm:344-353, 759-919;
            // sub SolidObject.asm:254-265). S2/S3K keep the same ownership:
            // their roll entry writes Roll and their rolling movement routine
            // does not (s2.asm:36654-36675,36954-37161; sonic3k.asm:
            // 22144-22166,22924-23269).
            return null;
        }
        if (sprite.getLookingUp() && lookUpAnimId >= 0) {
            return lookUpAnimId;
        }
        if (sprite.getCrouching() && duckAnimId >= 0) {
            return duckAnimId;
        }
        // ROM-accurate: Pushing state takes priority over speed-based animations
        // for RENDERING only. ROM keeps the anim byte at the movement-selected
        // value (walk/wait) and shows the push frames inside the walk script's
        // special handler (Animate_Sonic loc_12A72 btst #5,status, sonic3k.asm
        // 24832; Animate_Tails reads anim directly, 29356-29364). So skip this
        // substitution when the caller wants the real ROM anim byte.
        if (applyPushRenderSubstitution && sprite.getPushing() && pushAnimId >= 0) {
            return pushAnimId;
        }
        return resolveGroundMovementAnimId(sprite);
    }

    /**
     * Resolves the grounded movement-selected animation id (walk / run / balance /
     * idle). Only reached after the higher-priority state branches in
     * {@link #resolveAnimationId} (air/roll/hurt/spring/...), so callers wanting
     * the full ROM anim byte must go through
     * {@code resolveAnimationId(..., false)} rather than calling this directly.
     */
    public Integer resolveGroundMovementAnimId(AbstractPlayableSprite sprite) {
        // Hurt-stop owns the whole frame: after collision clears InAir it zeroes
        // velocity, writes Walk, and only then calls the ordinary animation
        // routine. Do not reinterpret that explicit write as Wait merely because
        // the recovered player now has zero inertia. The frame-start snapshot
        // clears this semantic marker before the next normal-control frame
        // (S1 01 Sonic.asm:1901-1908,1941-1951; S2 s2.asm:38187-38226,
        // 41074-41114; S3K sonic3k.asm:24463-24506,29208-29251).
        if (sprite.getHurtRecoveryCompletedThisFrame()
                || (sprite.getHurtAtFrameStart() && !sprite.isHurt())) {
            return walkAnimId;
        }
        // The player routine is selected from Status_InAir at the start of the
        // frame. If its ordinary airborne collision pass lands, that frame never
        // runs Sonic_Move/Tails_Move and therefore never writes a grounded Wait,
        // Balance, or Walk value before Animate. Keep the animation byte that
        // the air routine carried into the landing; normal ground selection
        // resumes on the next frame. HurtStop and rolling landings publish Walk
        // explicitly and therefore take priority over this ordinary path (S1
        // 01 Sonic.asm:1527-1547,1839-1864,1901-1908; S2 s2.asm:
        // 37464-37504,37744-37774; S3K sonic3k.asm:24046-24103,24325-24359).
        if (sprite.getAirAtFrameStart() && !sprite.getAir()) {
            return null;
        }
        // ROM-accurate: Skidding state (braking at speed >= 0x400)
        if (sprite.getSkidding() && skidAnimId >= 0) {
            return skidAnimId;
        }

        // ROM-accurate animation selection. The directional acceleration branches
        // write Walk, but opposite-direction braking does not. If braking reaches
        // zero, the enclosing Move routine then writes Wait even though the button
        // remains held (S1 Objects/Sonic.asm:284-310,480-567; S2 s2.asm:
        // 36558-36577,36880-36999; S3K sonic3k.asm:22787-22918).
        // Roll-stop likewise writes Wait directly before Animate runs (S1
        // Objects/Sonic.asm:573-623; S2 s2.asm:37009-37062; S3K sonic3k.asm:
        // 22924-22994,28169-28239).
        // Use isMovementInputActive() which reflects EFFECTIVE input (after control lock filtering),
        // not raw button state, to match ROM behavior where animation is only set in movement routines.
        boolean pressingDirection = sprite.isMovementInputActive();
        // Ground movement chooses Wait/Walk before the no-input friction and
        // ground-wall probe can zero inertia (S2 Tails_Move s2.asm:39689-39693,
        // Obj02_UpdateSpeedOnGround/Obj02_CheckWallsOnGround s2.asm:39789-39865).
        PlayableSpriteAnimation animation = sprite.getAnimationManager();
        boolean hasMovementSpeedSnapshot = animation != null && animation.hasGroundMovementAnimSpeed();
        int animSpeed = hasMovementSpeedSnapshot
                ? animation.getGroundMovementAnimSpeed()
                : sprite.getGSpeed();
        int speed = Math.abs(animSpeed);

        // With neither direction held, Move reaches ResetScr without writing
        // anim while inertia remains non-zero. Preserve the byte owned by the
        // preceding routine (normally Walk, but it can be Stop, WaterSlide, or
        // another explicit owner) until the zero-speed tail writes Wait.
        if (!pressingDirection && speed > 0) {
            // Player_ChkWalk/Tails_Roll runs after Move. A coasting no-input
            // frame leaves Duck intact, so releasing Down changes it to Walk.
            // At zero inertia Move has already replaced Duck with Wait, and
            // the later comparison therefore performs no write
            // (sonic3k.asm:23247-23265,28458-28482).
            if (duckReleasePublishesWalk && !sprite.getCrouching() && duckAnimId >= 0
                    && sprite.getAnimationId() == duckAnimId) {
                return walkAnimId;
            }
            return null;
        }

        // S1 MoveLeft/MoveRight always write id_Walk to obAnim; Sonic_Animate
        // selects SonAni_Run from inertia >= $600 instead
        // (docs/s1disasm/_incObj/01 Sonic.asm:634-658,704-722,2253-2315).
        // S2 and S3K use the same split (s2.asm:36880-36962,38473-38503;
        // sonic3k.asm:22792-22877,24833-24879). Preserve a distinct Run script
        // for rendering while exposing the ROM-accurate raw animation id.
        if (speed >= runSpeedThreshold) {
            return runFramesUseWalkAnimationId ? walkAnimId : runAnimId;
        }

        // At zero inertia, Move has reached its standing-state tail. That tail
        // chooses balance or Wait after the directional braking helper returns.
        if (speed == 0) {
            // With no Move/RollSpeed snapshot, zero is an already-stationary
            // state (not the result of this frame's braking). A held direction
            // therefore entered MoveLeft/MoveRight and selected Walk. This also
            // covers ResetOnFloor's explicit Walk write on a landing frame.
            if (pressingDirection && !hasMovementSpeedSnapshot) {
                return walkAnimId;
            }
            int balanceState = sprite.getBalanceState();
            if (balanceState > 0 && balanceAnimId >= 0) {
                var rules = sprite.getGameRules();
                if (rules != null
                        && rules.playerAnimation() != null
                        && rules.playerAnimation().singleFacingBalanceAnimationSet()) {
                    balanceState = switch (balanceState) {
                        case 3 -> 1;
                        case 4 -> 2;
                        default -> balanceState;
                    };
                }
                return switch (balanceState) {
                    case 1 -> balanceAnimId;
                    case 2 -> balance2AnimId >= 0 ? balance2AnimId : balanceAnimId;
                    case 3 -> balance3AnimId >= 0 ? balance3AnimId : balanceAnimId;
                    case 4 -> balance4AnimId >= 0 ? balance4AnimId
                            : (balance3AnimId >= 0 ? balance3AnimId : balanceAnimId);
                    default -> balanceAnimId;
                };
            }
            return idleAnimId;
        }

        return walkAnimId;
    }

    @Override
    public int resolveFrame(AbstractPlayableSprite sprite, int frameCounter, int frameCount) {
        if (frameCount <= 0) {
            return 0;
        }
        return Math.min(fallbackFrame, frameCount - 1);
    }

    public int getBlinkAnimId() {
        return blinkAnimId;
    }

    public int getGetUpAnimId() {
        return getUpAnimId;
    }

    public int getIdleAnimId() {
        return idleAnimId;
    }

    public int getWalkAnimId() {
        return walkAnimId;
    }

    public int getRunAnimId() {
        return runAnimId;
    }

    public int getRollAnimId() {
        return rollAnimId;
    }

    public int getRoll2AnimId() {
        return roll2AnimId;
    }

    public int getPushAnimId() {
        return pushAnimId;
    }

    public boolean isPushUsesWalkSpecialHandler() {
        return pushUsesWalkSpecialHandler;
    }


    public int getDuckAnimId() {
        return duckAnimId;
    }

    public int getLookUpAnimId() {
        return lookUpAnimId;
    }

    public int getSpindashAnimId() {
        return spindashAnimId;
    }

    public int getSpringAnimId() {
        return springAnimId;
    }

    public int getDeathAnimId() {
        return deathAnimId;
    }

    public int getHurtAnimId() {
        return hurtAnimId;
    }

    public int getSkidAnimId() {
        return skidAnimId;
    }

    public int getSlideAnimId() {
        return slideAnimId;
    }

    public int getDrownAnimId() {
        return drownAnimId;
    }

    public int getAirAnimId() {
        return airAnimId;
    }

    public int getBalanceAnimId() {
        return balanceAnimId;
    }

    public int getBalance2AnimId() {
        return balance2AnimId;
    }

    public int getBalance3AnimId() {
        return balance3AnimId;
    }

    public int getBalance4AnimId() {
        return balance4AnimId;
    }

    public int getRunSpeedThreshold() {
        return runSpeedThreshold;
    }

    public boolean isRunFramesUseWalkAnimationId() {
        return runFramesUseWalkAnimationId;
    }

    public int getWalkSpeedThreshold() {
        return walkSpeedThreshold;
    }

    public int getFallbackFrame() {
        return fallbackFrame;
    }

    public boolean isAnglePreAdjust() {
        return anglePreAdjust;
    }

    public boolean isCompactSuperRunSlope() {
        return compactSuperRunSlope;
    }

    public boolean isWalkRunPublishesFrameBeforeTimerAdvance() {
        return walkRunPublishesFrameBeforeTimerAdvance;
    }

    public int getHighSpeedWalkRunAnimId() {
        return highSpeedWalkRunAnimId;
    }

    public int getHighSpeedWalkRunThreshold() {
        return highSpeedWalkRunThreshold;
    }

    public int getWalkSlopeFrameStride() {
        return walkSlopeFrameStride;
    }

    public int getRunSlopeFrameStride() {
        return runSlopeFrameStride;
    }

    public int getHighSpeedSlopeFrameStride() {
        return highSpeedSlopeFrameStride;
    }

    public boolean isDoubleWalkRunAnimationSpeedWhenSliding() {
        return doubleWalkRunAnimationSpeedWhenSliding;
    }

    public int getTumbleFrameBase() {
        return tumbleFrameBase;
    }

    public int getTumbleTypeFrameBase(int flipType) {
        return flipType >= 0 && flipType < tumbleTypeFrameBases.length
                ? tumbleTypeFrameBases[flipType]
                : -1;
    }

    public ScriptedVelocityAnimationProfile withRunSpeedThreshold(int newThreshold) {
        ScriptedVelocityAnimationProfile copy = new ScriptedVelocityAnimationProfile();
        copy.idleAnimId = this.idleAnimId;
        copy.walkAnimId = this.walkAnimId;
        copy.runAnimId = this.runAnimId;
        copy.rollAnimId = this.rollAnimId;
        copy.roll2AnimId = this.roll2AnimId;
        copy.pushAnimId = this.pushAnimId;
        copy.pushUsesWalkSpecialHandler = this.pushUsesWalkSpecialHandler;
        copy.duckReleasePublishesWalk = this.duckReleasePublishesWalk;
        copy.airborneSlidePreservesPublishedAnimation = this.airborneSlidePreservesPublishedAnimation;
        copy.duckAnimId = this.duckAnimId;
        copy.lookUpAnimId = this.lookUpAnimId;
        copy.spindashAnimId = this.spindashAnimId;
        copy.springAnimId = this.springAnimId;
        copy.deathAnimId = this.deathAnimId;
        copy.hurtAnimId = this.hurtAnimId;
        copy.skidAnimId = this.skidAnimId;
        copy.slideAnimId = this.slideAnimId;
        copy.drownAnimId = this.drownAnimId;
        copy.airAnimId = this.airAnimId;
        copy.balanceAnimId = this.balanceAnimId;
        copy.balance2AnimId = this.balance2AnimId;
        copy.balance3AnimId = this.balance3AnimId;
        copy.balance4AnimId = this.balance4AnimId;
        copy.walkSpeedThreshold = this.walkSpeedThreshold;
        copy.runSpeedThreshold = newThreshold;
        copy.runFramesUseWalkAnimationId = this.runFramesUseWalkAnimationId;
        copy.fallbackFrame = this.fallbackFrame;
        copy.anglePreAdjust = this.anglePreAdjust;
        copy.compactSuperRunSlope = this.compactSuperRunSlope;
        copy.superAlternateFrameMask = this.superAlternateFrameMask;
        copy.superAlternateFrameStep = this.superAlternateFrameStep;
        copy.superAlternateFrameCeiling = this.superAlternateFrameCeiling;
        copy.walkRunPublishesFrameBeforeTimerAdvance = this.walkRunPublishesFrameBeforeTimerAdvance;
        copy.highSpeedWalkRunAnimId = this.highSpeedWalkRunAnimId;
        copy.highSpeedWalkRunThreshold = this.highSpeedWalkRunThreshold;
        copy.walkSlopeFrameStride = this.walkSlopeFrameStride;
        copy.runSlopeFrameStride = this.runSlopeFrameStride;
        copy.highSpeedSlopeFrameStride = this.highSpeedSlopeFrameStride;
        copy.doubleWalkRunAnimationSpeedWhenSliding = this.doubleWalkRunAnimationSpeedWhenSliding;
        copy.tumbleFrameBase = this.tumbleFrameBase;
        copy.tumbleTypeFrameBases = this.tumbleTypeFrameBases.clone();
        return copy;
    }
}
