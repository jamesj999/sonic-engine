package com.openggf.game.rules;

import com.openggf.game.CollisionModel;

public record GameRules(
        PlayerMovementRules playerMovement,
        PlayerCapabilityRules playerCapability,
        CollisionRules collision,
        PlayerAnimationRules playerAnimation,
        CameraRules camera,
        RingRules ring,
        ObjectInteractionRules objectInteraction,
        SidekickCpuRules sidekickCpu,
        PowerUpRules powerUp,
        DrowningBubbleRules drowningBubble,
        DynamicArtDmaServiceModel dynamicArtDmaService) {

    public GameRules(
            PlayerMovementRules playerMovement,
            PlayerCapabilityRules playerCapability,
            CollisionRules collision,
            PlayerAnimationRules playerAnimation,
            CameraRules camera,
            RingRules ring,
            ObjectInteractionRules objectInteraction,
            SidekickCpuRules sidekickCpu,
            PowerUpRules powerUp,
            DrowningBubbleRules drowningBubble) {
        this(playerMovement, playerCapability, collision, playerAnimation,
                camera, ring, objectInteraction, sidekickCpu, powerUp,
                drowningBubble, DynamicArtDmaServiceModel.EVERY_CLAIM);
    }

    public static final GameRules SONIC_1 = new GameRules(
            new PlayerMovementRules(
                    true,
                    true,
                    false,
                    (short) 0,
                    false,
                    false,
                    false,
                    false,
                    new PlayerLandingRules(false, false, false, false),
                    new PlayerLevelBoundaryRules(false, true, true, false, true, true, true),
                    false,
                    false,
                    true,
                    false,
                    false,
                    false,
                    false,
                    // tailsRollSpeedUsesEffectiveDecelQuarter: S1 has no Tails_RollSpeed
                    false,
                    // waterVelocityChangeGatedByObjectControl: S1 Sonic_Water has no object_control test
                    false
            ,
                    false
            ),
            new PlayerCapabilityRules(
                    false,
                    null,
                    false,
                    false,
                    false,
                    false,
                    false,
                    null
            ),
            new CollisionRules(
                    CollisionModel.UNIFIED,
                    true,
                    false,
                    false,
                    true,
                    new AirCollisionRules(false, false, false, false, false),
                    true,
                    false,
                    false,
                    false,
                    false,
                    false,
                    false,
                    false,
                    true,
                    false,
                    true,
                    0x07FF,
                    // S1 FindNearestTile masks the row index with a constant assembled
                    // into the routine, so it applies to every collision lookup.
                    true
            ),
            new PlayerAnimationRules(
                    false,
                    false,
                    false,
                    true,
                    false,
                    false,
                    TailsTailPushDetection.UNSUPPORTED
            ),
            new CameraRules(
                    (short) 0,
                    true,
                    16,
                    true,
                    false,
                    false
            ),
            new RingRules(
                    3,
                    0,
                    false,
                    false,
                    32,
                    6,
                    6,
                    true,
                    false
            ),
            new ObjectInteractionRules(
                    false,
                    true,
                    true,
                    false,
                    false,
                    false, // sidekickDespawnUsesInteractCodeWordChange (S3K-only)
                    false,
                    false,
                    true,
                    true,
                    false,
                    false,
                    false,
                    true,
                    false,
                    false,
                    0x39 // duckTouchBoxMappingFrame: S1 fr_Duck ($39)
            ),
            new SidekickCpuRules(
                    16,
                    16384,
                    0,
                    false,
                    false,
                    true,
                    0,
                    false,
                    192,
                    300,
                    12,
                    1,
                    32,
                    1024,
                    false,
                    false,
                    false,
                    false,
                    true
            ),
            new PowerUpRules(
                    6,
                    8,
                    12,
                    -1,
                    1,
                    false,
                    false,
                    -1,
                    -1
            ),
            new DrowningBubbleRules(
                    60,
                    0,
                    false,
                    -136
            ),
            DynamicArtDmaServiceModel.SONIC_1_VBLANK_SONIC_GFX
    );

    public static final GameRules SONIC_2 = new GameRules(
            new PlayerMovementRules(
                    false,
                    false,
                    true,
                    (short) 0,
                    false,
                    false,
                    true,
                    false,
                    new PlayerLandingRules(true, true, false, true),
                    new PlayerLevelBoundaryRules(false, true, false, true, true, false, false),
                    true,
                    false,
                    false,
                    true,
                    true,
                    false,
                    false,
                    // tailsRollSpeedUsesEffectiveDecelQuarter: shipped s2.asm:40037 keeps decel>>2
                    true,
                    // waterVelocityChangeGatedByObjectControl: S2 Obj01_InWater has no object_control test
                    false
            ,
                    true
            ),
            new PlayerCapabilityRules(
                    true,
                    new short[]{0x0800, 0x0880, 0x0900, 0x0980, 0x0A00, 0x0A80, 0x0B00, 0x0B80, 0x0C00},
                    false,
                    false,
                    false,
                    false,
                    false,
                    new short[]{0x0B00, 0x0B80, 0x0C00, 0x0C80, 0x0D00, 0x0D80, 0x0E00, 0x0E80, 0x0F00}
            ),
            new CollisionRules(
                    CollisionModel.DUAL_PATH,
                    true,
                    false,
                    true,
                    false,
                    new AirCollisionRules(false, false, false, false, false),
                    true,
                    true,
                    true,
                    false,
                    false,
                    false,
                    false,
                    false,
                    true,
                    false,
                    true,
                    0x07FF,
                    // S2 Find_Tile masks the row index with a constant assembled into the
                    // routine, so it applies to every collision lookup.
                    true
            ),
            new PlayerAnimationRules(
                    true,
                    false,
                    true,
                    false,
                    true,
                    // Sonic_CheckFloor hands FindFloor the shared Primary_Angle /
                    // Secondary_Angle bytes exactly as the grounded AnglePos path
                    // does (s2.asm:44035-44068), and both character tails copy them
                    // into next_tilt / tilt unconditionally every frame -- Obj01 at
                    // s2.asm:36252-36253, Obj02 at s2.asm:38987-38988. A landing
                    // frame therefore publishes fresh angles for either character,
                    // the same shape S3K already models.
                    true,
                    TailsTailPushDetection.STATUS_BIT_ONLY
            ),
            new CameraRules(
                    (short) 120,
                    false,
                    16,
                    false,
                    false,
                    true
            ),
            new RingRules(
                    7,
                    0,
                    true,
                    false,
                    32,
                    6,
                    6,
                    false,
                    true
            ),
            new ObjectInteractionRules(
                    false,
                    false,
                    true,
                    true,
                    false,
                    false, // sidekickDespawnUsesInteractCodeWordChange (S3K-only)
                    true,
                    false,
                    true,
                    false,
                    false,
                    true,
                    true,
                    true,
                    true,
                    false,
                    0x4D // duckTouchBoxMappingFrame: S2 SonAni_Duck second frame ($4D)
            ),
            new SidekickCpuRules(
                    16,
                    16384,
                    0,
                    false,
                    true,
                    true,
                    210,
                    false,
                    192,
                    300,
                    12,
                    1,
                    32,
                    1024,
                    true,
                    false,
                    false,
                    true,
                    false
            ),
            new PowerUpRules(
                    134,
                    136,
                    -1,
                    129,
                    1,
                    true,
                    true,
                    132,
                    133
            ),
            new DrowningBubbleRules(
                    0,
                    8,
                    true,
                    -136
            ),
            DynamicArtDmaServiceModel.SONIC_2_PROCESS_DMA_QUEUE
    );

    public static final GameRules SONIC_3K = new GameRules(
            new PlayerMovementRules(
                    false,
                    false,
                    true,
                    (short) 256,
                    true,
                    true,
                    false,
                    true,
                    new PlayerLandingRules(true, true, true, false),
                    new PlayerLevelBoundaryRules(true, true, false, false, false, false, false),
                    false,
                    true,
                    false,
                    true,
                    true,
                    true,
                    true,
                    // tailsRollSpeedUsesEffectiveDecelQuarter: S3K Tails_RollSpeed is flat $20
                    false,
                    // waterVelocityChangeGatedByObjectControl: sonic3k.asm:22235, :27448
                    true
            ,
                    true
            ),
            new PlayerCapabilityRules(
                    true,
                    new short[]{0x0800, 0x0880, 0x0900, 0x0980, 0x0A00, 0x0A80, 0x0B00, 0x0B80, 0x0C00},
                    true,
                    true,
                    true,
                    true,
                    true,
                    new short[]{0x0B00, 0x0B80, 0x0C00, 0x0C80, 0x0D00, 0x0D80, 0x0E00, 0x0E80, 0x0F00}
            ),
            new CollisionRules(
                    CollisionModel.DUAL_PATH,
                    true,
                    true,
                    false,
                    true,
                    new AirCollisionRules(true, true, true, true, true),
                    false,
                    true,
                    true,
                    true,
                    true,
                    true,
                    true,
                    false,
                    false,
                    true,
                    false,
                    0x0FFF,
                    // S3K's Find_Tile_FG masks with the per-level runtime
                    // Layout_row_index_mask, not a constant, so the mask above may not be
                    // applied to every lookup. See CollisionRules.layoutYMaskAppliesToAllLookups.
                    false
            ),
            new PlayerAnimationRules(
                    true,
                    true,
                    true,
                    false,
                    true,
                    true,
                    TailsTailPushDetection.STATUS_BIT_AND_PUSH_MAPPING_FRAMES
            ),
            new CameraRules(
                    (short) 120,
                    false,
                    24,
                    false,
                    true,
                    true
            ),
            new RingRules(
                    7,
                    4,
                    true,
                    true,
                    0,
                    6,
                    6,
                    false,
                    true
            ),
            new ObjectInteractionRules(
                    true,
                    false,
                    false,
                    false,
                    true,
                    true, // sidekickDespawnUsesInteractCodeWordChange (S3K sub_13EFC word compare)
                    true,
                    true,
                    true,
                    false,
                    true,
                    false,
                    false,
                    true,
                    true,
                    true,
                    ObjectInteractionRules.NO_DUCK_TOUCH_BOX // S3K removed the duck touch-box shrink
            ),
            new SidekickCpuRules(
                    48,
                    32512,
                    32,
                    true,
                    true,
                    false,
                    128,
                    true,
                    192,
                    300,
                    12,
                    1,
                    32,
                    1024,
                    false,
                    true,
                    true,
                    true,
                    true
            ),
            new PowerUpRules(
                    100,
                    102,
                    -1,
                    96,
                    8,
                    true,
                    true,
                    98,
                    99
            ),
            new DrowningBubbleRules(
                    60,
                    8,
                    true,
                    -256
            ),
            DynamicArtDmaServiceModel.EVERY_CLAIM_WITHOUT_PLAYER_ART_AUDIT
    );
}
