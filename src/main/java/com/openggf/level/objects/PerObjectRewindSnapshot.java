package com.openggf.level.objects;

import com.openggf.game.rewind.snapshot.GenericObjectSnapshot;
import com.openggf.game.rewind.schema.RewindObjectStateBlob;

/**
 * Immutable capture of the standard mutable field surface of
 * {@link AbstractObjectInstance} for rewind snapshots.
 *
 * <p>This record covers every field declared on {@code AbstractObjectInstance}
 * that changes during gameplay.  Fields that are {@code final} or purely
 * transient (service handles, static camera cache) are intentionally excluded.
 *
 * <p><strong>Subclass contract:</strong> Subclasses that hold private
 * gameplay-relevant state (boss phase counters, badnik AI timers, sub-state
 * machine indices, etc.) <em>must</em> override both
 * {@link AbstractObjectInstance#captureRewindState()} and
 * {@link AbstractObjectInstance#restoreRewindState(PerObjectRewindSnapshot)} and
 * incorporate their own extra fields.  Otherwise that state will silently fail
 * to round-trip across a rewind.  Classes known to need overrides include any
 * boss instance (phase counters, arena flags), badniks with multi-phase AI
 * (e.g. Turtloid, Spiker), and any object that accumulates a timer beyond
 * {@code animTimer} (e.g. CNZ bumper reload, HTZ earthquake object).
 */
public record PerObjectRewindSnapshot(
        // Lifecycle / destruction flags
        boolean destroyed,
        boolean destroyedRespawnable,

        // Dynamic position (null when object has not called updateDynamicSpawn yet;
        // stored as pair to avoid capturing the live ObjectSpawn reference beyond
        // what is needed — position is the only mutable part of a dynamic spawn).
        boolean hasDynamicSpawn,
        int dynamicSpawnX,
        int dynamicSpawnY,

        // Pre-update position snapshot (frame-start position used by touch collision)
        int preUpdateX,
        int preUpdateY,
        boolean preUpdateValid,
        int preUpdateCollisionFlags,

        // Per-frame timing / touch gating flags
        boolean skipTouchThisFrame,
        boolean solidContactFirstFrame,

        // Slot bookkeeping (set by ObjectManager at construction, may change if slot
        // is released and re-assigned, e.g. ring parent-slot release)
        int slotIndex,

        // S1 counter-based respawn index (-1 when not used)
        int respawnStateIndex,

        // Badnik movement state (nullable; only present when capturing
        // AbstractBadnikInstance or subclass)
        BadnikRewindExtra badnikExtra,

        // Badnik subclass state (nullable; only present for concrete badniks
        // with private AI timers, substates, or movement helpers)
        BadnikSubclassRewindExtra badnikSubclassExtra,

        // Concrete object subclass state (nullable; only present for objects
        // with private state not covered by the base object fields)
        ObjectSubclassRewindExtra objectSubclassExtra,

        // Player gameplay state (nullable; only present when capturing
        // AbstractPlayableSprite or subclass)
        PlayerRewindExtra playerExtra,

        // Optional generic sidecar for explicitly eligible classes. Legacy
        // extras remain authoritative until parity tests migrate each class.
        GenericObjectSnapshot genericState,

        // Compact schema-backed sidecar for default object-subclass state.
        // Present when every default-captured subclass field has a compact codec;
        // genericState remains the compatibility fallback for unsupported shapes.
        RewindObjectStateBlob compactGenericState
) {
    public PerObjectRewindSnapshot(
            boolean destroyed,
            boolean destroyedRespawnable,
            boolean hasDynamicSpawn,
            int dynamicSpawnX,
            int dynamicSpawnY,
            int preUpdateX,
            int preUpdateY,
            boolean preUpdateValid,
            int preUpdateCollisionFlags,
            boolean skipTouchThisFrame,
            boolean solidContactFirstFrame,
            int slotIndex,
            int respawnStateIndex,
            BadnikRewindExtra badnikExtra,
            BadnikSubclassRewindExtra badnikSubclassExtra,
            PlayerRewindExtra playerExtra
    ) {
        this(
                destroyed, destroyedRespawnable,
                hasDynamicSpawn, dynamicSpawnX, dynamicSpawnY,
                preUpdateX, preUpdateY, preUpdateValid, preUpdateCollisionFlags,
                skipTouchThisFrame, solidContactFirstFrame,
                slotIndex, respawnStateIndex,
                badnikExtra,
                badnikSubclassExtra,
                null,
                playerExtra,
                null,
                null
        );
    }

    public PerObjectRewindSnapshot(
            boolean destroyed,
            boolean destroyedRespawnable,
            boolean hasDynamicSpawn,
            int dynamicSpawnX,
            int dynamicSpawnY,
            int preUpdateX,
            int preUpdateY,
            boolean preUpdateValid,
            int preUpdateCollisionFlags,
            boolean skipTouchThisFrame,
            boolean solidContactFirstFrame,
            int slotIndex,
            int respawnStateIndex,
            BadnikRewindExtra badnikExtra,
            BadnikSubclassRewindExtra badnikSubclassExtra,
            ObjectSubclassRewindExtra objectSubclassExtra,
            PlayerRewindExtra playerExtra,
            GenericObjectSnapshot genericState
    ) {
        this(
                destroyed, destroyedRespawnable,
                hasDynamicSpawn, dynamicSpawnX, dynamicSpawnY,
                preUpdateX, preUpdateY, preUpdateValid, preUpdateCollisionFlags,
                skipTouchThisFrame, solidContactFirstFrame,
                slotIndex, respawnStateIndex,
                badnikExtra,
                badnikSubclassExtra,
                objectSubclassExtra,
                playerExtra,
                genericState,
                null
        );
    }

    public interface BadnikSubclassRewindExtra {
    }

    public interface ObjectSubclassRewindExtra {
    }

    /**
     * Immutable capture of {@link AbstractBadnikInstance} movement-state fields
     * (currentX, currentY, xVelocity, yVelocity, animTimer, animFrame, facingLeft).
     */
    public static record BadnikRewindExtra(
            int currentX,
            int currentY,
            int xVelocity,
            int yVelocity,
            int animTimer,
            int animFrame,
            boolean facingLeft
    ) {}

    public record MasherRewindExtra(
            int motionX,
            int motionY,
            int motionXSub,
            int motionYSub,
            int motionXVel,
            int motionYVel,
            int initialYPos
    ) implements BadnikSubclassRewindExtra {}

    public record BuzzerRewindExtra(
            int stateOrdinal,
            int moveTimer,
            int turnDelay,
            int shotTimer,
            boolean shootingDisabled,
            boolean initPending
    ) implements BadnikSubclassRewindExtra {}

    public record CoconutsRewindExtra(
            int stateOrdinal,
            int throwStateOrdinal,
            int timer,
            int climbTableIndex,
            int attackTimer,
            int yVelocity
    ) implements BadnikSubclassRewindExtra {}

    public record ArzPlatformRewindExtra(
            int x,
            int y,
            int baseX,
            int baseY,
            int baseYFixed,
            int widthPixels,
            int mappingFrame,
            int subtype,
            int routine,
            int bobAngle,
            int angle,
            int timer,
            int yVel,
            int yRadius
    ) implements ObjectSubclassRewindExtra {}

    public record BadnikProjectileRewindExtra(
            String projectileType,
            int currentX,
            int currentY,
            int xSub,
            int ySub,
            int xVelocity,
            int yVelocity,
            boolean applyGravity,
            int gravity,
            int collisionSizeIndex,
            int animFrame,
            boolean hFlip,
            int initialDelay,
            int fixedFrame,
            boolean paletteBlink,
            int cluckerAnimTimer,
            int cluckerAnimIndex,
            int aquisBulletAnimTimer,
            int aquisBulletAnimIndex,
            boolean loadSubObjectInitPending
    ) implements ObjectSubclassRewindExtra {}

    public record BuzzerFlameRewindExtra(
            int parentSlotIndex,
            int currentX,
            int currentY,
            boolean facingLeft,
            int animFrame
    ) implements ObjectSubclassRewindExtra {}

    /**
     * Captures the {@link com.openggf.level.objects.AbstractMonitorObjectInstance}
     * {@code effectTarget} player identity by its stable sprite code so the
     * pending power-up recipient round-trips across rewind. The base monitor
     * class otherwise relies on the genericState mechanism to capture its scalar
     * fields; {@code effectTarget} is excluded from genericState because it
     * holds a {@link com.openggf.game.PlayableEntity} reference, which the
     * generic capturer cannot serialize. Without this extra, rewinding into a
     * frame mid-icon-rise would null out {@code effectTarget} and the apex
     * {@code applyPowerup} call would be skipped, leaving the player without
     * the shield/speed-shoes/etc. that the reference forward-run granted.
     *
     * <p>{@code effectTargetSpriteCode} is null when no monitor break is in
     * flight (i.e. {@code effectTarget} is null); the restore lookup tolerates
     * a missing or unmatched code by leaving {@code effectTarget} null, which
     * matches the live behavior of an inactive monitor.
     */
    public record MonitorRewindExtra(
            String effectTargetSpriteCode
    ) implements ObjectSubclassRewindExtra {}

    /**
     * Mutable scalar gameplay state on a sidekick's CPU controller. Structural
     * runtime wiring (leader, respawn strategy, carry trigger, owning sidekick)
     * is intentionally excluded and restored from the live controller graph.
     */
    public record SidekickCpuRewindExtra(
            com.openggf.sprites.playable.SidekickCpuController.State state,
            int deadFallingRomCpuRoutine,
            int despawnCounter,
            int frameCounter,
            int controlCounter,
            int controller2Held,
            int controller2Logical,
            boolean inputUp,
            boolean inputDown,
            boolean inputLeft,
            boolean inputRight,
            boolean inputJump,
            boolean inputJumpPress,
            boolean jumpingFlag,
            int minXBound,
            int maxXBound,
            int minYBound,
            int maxYBound,
            int lastInteractObjectId,
            boolean normalDespawnLastRenderFlagOffscreen,
            boolean normalDespawnFreshRenderEntryDelayConsumed,
            int diagnosticS3kInteractWord,
            int normalFrameCount,
            int approachFrameCount,
            int sidekickCount,
            int normalPushingGraceFrames,
            boolean suppressNextAirbornePushFollowSteering,
            boolean releasedUnderwaterPushConsumed,
            boolean objectOrderGracePushBypassThisFrame,
            int pendingGroundedFollowNudge,
            int pendingGroundedFollowNudgeFrame,
            boolean suppressNextLevelEventNormalMovement,
            boolean catchUpUsesRomVisibleLevelFrameCounter,
            boolean levelEventDormantMarkerReleasePending,
            boolean skipPhysicsThisFrame,
            boolean deadOnObjectReenteredVisibleWindow,
            boolean deferredDespawnDeadFallContinuingThisFrame,
            boolean levelStartLeaderHistoryPrefillPending,
            boolean bootstrapPreludePlacementApplied,
            boolean cpuFrameCounterFromStoredLevelFrame,
            int nextCpuFrameCounterOverride,
            int catchUpFrameCounterOverride,
            int lastNormalAutoJumpPressFrameCounter,
            boolean controller2SignedLocked,
            boolean nativeEndingPosePending,
            com.openggf.sprites.playable.SidekickCpuController.NormalStepDiagnostics latestNormalStepDiagnostics,
            boolean mgzCarryIntroAscend,
            int mgzCarryFlapTimer,
            boolean mgzReleasedChaseLatched,
            short mgzReleasedChaseXAccel,
            short mgzReleasedChaseYAccel,
            int flightTimer,
            int catchUpTargetX,
            int catchUpTargetY,
            boolean initialPresentationSuppressed,
            boolean initialPresentationWasHidden
    ) {}

    /**
     * Mutable gameplay state on AbstractPlayableSprite that AbstractObjectInstance's
     * default 11-field capture surface does NOT cover. Character physics
     * constants and render-service references are excluded. Animation cursor
     * state is included because visual rewind can render immediately after
     * restore, before any forward frame can regenerate mapping_frame from the
     * animation scripts.
     */
    public record PlayerRewindExtra(
            // AbstractSprite base position fields (not in AbstractObjectInstance hierarchy)
            short xPixel, short yPixel,
            short xSubpixel, short ySubpixel,
            int width, int height,
            com.openggf.physics.Direction direction,
            byte layer,
            com.openggf.game.GroundMode runningMode,
            short xRadius,
            short yRadius,
            // Movement / physics
            short gSpeed, short xSpeed, short ySpeed, short jump,
            byte angle, byte statusTertiary, boolean loopLowPlane,
            byte topSolidBit, byte lrbSolidBit,
            boolean prePhysicsAir, byte prePhysicsAngle,
            short prePhysicsGSpeed, short prePhysicsXSpeed, short prePhysicsYSpeed,
            short preZoneFeatureGSpeed,
            short prePhysicsCentreX, short prePhysicsCentreY,
            boolean air, boolean rolling, boolean jumping, boolean rollingJump,
            boolean pinballMode, boolean pinballSpeedLock,
            boolean preserveRollingOnNextLanding, boolean preserveRollingOnNextRollStop,
            boolean objectPreservedRollBoostFollowup,
            boolean objectPreservedRollWallProbe,
            boolean objectPreservedRollVelocityCarry,
            boolean tunnelMode,
            // Surface interaction / collision
            boolean onObject, boolean onObjectAtFrameStart, boolean onObjectAtPreviousFrameStart,
            boolean pushingAtFrameStart,
            boolean hurtAtFrameStart,
            boolean hurtRecoveryCompletedThisFrame,
            int latchedSolidObjectId, int interactSlotIndex, boolean slopeRepelJustSlipped,
            boolean stickToConvex, boolean sliding, boolean pushing,
            boolean skidding, int skidDustTimer, boolean fixedSkidDustActive,
            int lastFixedSkidDustTickFrame,
            short wallClimbX, int rightWallPenetrationTimer,
            int balanceState,
            // Special states / hazards
            boolean springing, int springingFrames,
            boolean dead, boolean drowningDeath, int drownPreDeathTimer,
            boolean hurt, int deathCountdown, boolean deathRestartRoutineActive,
            int invulnerableFrames, boolean suppressNextInvulnerabilityDecrement, int invincibleFrames,
            // Player abilities
            boolean spindash, short spindashCounter,
            boolean crouching, boolean lookingUp, short lookDelayCounter,
            int doubleJumpFlag, byte doubleJumpProperty,
            boolean shield, com.openggf.game.ShieldType shieldType,
            boolean instaShieldRegistered,
            // speedShoesRemainingTicks is captured directly (not read back from
            // TimerManager at restore time) because TimerManager's own generic
            // snapshot only preserves (code, ticks) pairs, not timer type --
            // restoring the SpeedShoesTimer's remaining duration must not
            // depend on RewindRegistry's cross-subsystem restore ordering.
            boolean speedShoes, int speedShoesRemainingTicks, boolean superSonic,
            // Input gating / control
            boolean forceInputRight, int forcedInputMask,
            boolean forcedJumpPress, boolean suppressNextJumpPress,
            boolean deferredObjectControlRelease,
            boolean controlLocked, boolean hasQueuedControlLockedState, boolean queuedControlLocked,
            boolean hasQueuedForceInputRightState, boolean queuedForceInputRight,
            int moveLockTimer,
            boolean objectControlled, boolean objectControlAllowsCpu, boolean objectControlSuppressesMovement,
            int objectControlReleasedFrame,
            boolean suppressAirCollision, boolean suppressGroundWallCollision, boolean forceFloorCheck,
            int suppressedObjectMoveAndFallAxes,
            boolean hidden, boolean nativeSlotPresent,
            boolean renderFlagOnScreen, boolean renderFlagOnScreenValid,
            boolean renderHFlip, boolean renderVFlip,
            // MGZ-specific
            boolean mgzTopPlatformSpringHandoffPending,
            int mgzTopPlatformSpringHandoffXVel,
            int mgzTopPlatformSpringHandoffYVel,
            // Input edge tracking
            boolean jumpInputPressed, boolean jumpInputJustPressed, boolean jumpInputPressedPreviousFrame,
            boolean upInputPressed, boolean downInputPressed,
            boolean leftInputPressed, boolean rightInputPressed,
            boolean movementInputActive,
            short logicalInputState, boolean logicalJumpPressState,
            // CPU / sidekick / spiral
            boolean cpuControlled, byte historyPos, boolean followerHistoryRecordedThisTick,
            int spiralActiveFrame, byte flipAngle, byte flipType, byte flipSpeed,
            byte flipsRemaining, boolean flipTurned,
            // Water
            boolean inWater, boolean waterPhysicsActive, boolean wasInWater, boolean waterSkimActive,
            boolean preventTailsRespawn,
            // Other
            int badnikChainCounter,
            int bubbleAnimId,
            boolean initPhysicsActive,
            boolean objectMappingFrameControl,
            int mappingFrame,
            int animationId,
            int forcedAnimationId,
            int animationFrameIndex,
            int animationTick,
            boolean debugMode,
            com.openggf.sprites.playable.PlayableSpriteController.RewindState controllerState,
            SidekickCpuRewindExtra sidekickCpuExtra,
            // Sidekick follow-history circular buffers (read by SidekickCpuController
            // each frame to position the follower; the leader writes new entries every
            // frame). Without snapshotting these, the follower reads stale history
            // entries after a rewind, producing divergent position/velocity/angle on
            // the very first replay step. 64 slots each.
            short[] xHistory, short[] yHistory,
            short[] inputHistory,
            byte[] jumpPressHistory, byte[] statusHistory
    ) {
        public PlayerRewindExtra {
            // Defensive copy of the array fields so the record is truly immutable.
            xHistory = xHistory == null ? null : xHistory.clone();
            yHistory = yHistory == null ? null : yHistory.clone();
            inputHistory = inputHistory == null ? null : inputHistory.clone();
            jumpPressHistory = jumpPressHistory == null ? null : jumpPressHistory.clone();
            statusHistory = statusHistory == null ? null : statusHistory.clone();
        }
    }

    /** Returns a copy of this snapshot with the given {@link PlayerRewindExtra} attached. */
    public PerObjectRewindSnapshot withPlayerExtra(PlayerRewindExtra extra) {
        return new PerObjectRewindSnapshot(
                destroyed, destroyedRespawnable,
                hasDynamicSpawn, dynamicSpawnX, dynamicSpawnY,
                preUpdateX, preUpdateY, preUpdateValid, preUpdateCollisionFlags,
                skipTouchThisFrame, solidContactFirstFrame,
                slotIndex, respawnStateIndex,
                badnikExtra,
                badnikSubclassExtra,
                objectSubclassExtra,
                extra,
                genericState,
                compactGenericState
        );
    }

    /** Returns a copy of this snapshot with concrete badnik subclass state attached. */
    public PerObjectRewindSnapshot withBadnikSubclassExtra(BadnikSubclassRewindExtra extra) {
        return new PerObjectRewindSnapshot(
                destroyed, destroyedRespawnable,
                hasDynamicSpawn, dynamicSpawnX, dynamicSpawnY,
                preUpdateX, preUpdateY, preUpdateValid, preUpdateCollisionFlags,
                skipTouchThisFrame, solidContactFirstFrame,
                slotIndex, respawnStateIndex,
                badnikExtra,
                extra,
                objectSubclassExtra,
                playerExtra,
                genericState,
                compactGenericState
        );
    }

    /** Returns a copy of this snapshot with concrete object subclass state attached. */
    public PerObjectRewindSnapshot withObjectSubclassExtra(ObjectSubclassRewindExtra extra) {
        return new PerObjectRewindSnapshot(
                destroyed, destroyedRespawnable,
                hasDynamicSpawn, dynamicSpawnX, dynamicSpawnY,
                preUpdateX, preUpdateY, preUpdateValid, preUpdateCollisionFlags,
                skipTouchThisFrame, solidContactFirstFrame,
                slotIndex, respawnStateIndex,
                badnikExtra,
                badnikSubclassExtra,
                extra,
                playerExtra,
                genericState,
                compactGenericState
        );
    }

    /** Returns a copy of this snapshot with the optional generic sidecar attached. */
    public PerObjectRewindSnapshot withGenericState(GenericObjectSnapshot genericState) {
        return new PerObjectRewindSnapshot(
                destroyed, destroyedRespawnable,
                hasDynamicSpawn, dynamicSpawnX, dynamicSpawnY,
                preUpdateX, preUpdateY, preUpdateValid, preUpdateCollisionFlags,
                skipTouchThisFrame, solidContactFirstFrame,
                slotIndex, respawnStateIndex,
                badnikExtra,
                badnikSubclassExtra,
                objectSubclassExtra,
                playerExtra,
                genericState,
                compactGenericState
        );
    }

    /** Returns a copy of this snapshot with the compact generic sidecar attached. */
    public PerObjectRewindSnapshot withCompactGenericState(RewindObjectStateBlob compactGenericState) {
        return new PerObjectRewindSnapshot(
                destroyed, destroyedRespawnable,
                hasDynamicSpawn, dynamicSpawnX, dynamicSpawnY,
                preUpdateX, preUpdateY, preUpdateValid, preUpdateCollisionFlags,
                skipTouchThisFrame, solidContactFirstFrame,
                slotIndex, respawnStateIndex,
                badnikExtra,
                badnikSubclassExtra,
                objectSubclassExtra,
                playerExtra,
                genericState,
                compactGenericState
        );
    }
}
