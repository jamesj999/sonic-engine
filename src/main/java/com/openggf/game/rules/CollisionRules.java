package com.openggf.game.rules;

import com.openggf.game.CollisionModel;

public record CollisionRules(
        CollisionModel collisionModel,
        boolean groundWallCollisionEnabled,
        boolean groundWallPushRequiresFacingIntoWall,
        boolean repeatedObjectRideGroundWallResponseDeferred,
        boolean topSolidLandingAllowsZeroDist,
        AirCollisionRules air,
        boolean fullSolidBottomOverlapUsesCurrentYRadiusOnly,
        boolean solidObjectOffscreenGate,
        boolean solidObjectRequiresSidekickOnScreen,
        boolean sidekickPushBypassUsesGraceStatus,
        boolean sidekickSuppressesFastLeaderTinyFollowNudge,
        boolean sidekickClearsStalePushVelocityBeforeGroundMove,
        boolean solidObjectTopBranchAlwaysLiftsOnUpwardVelocity,
        boolean rightWallDeepProbePreservesPenetration,
        boolean solidObjectBarelyPokingResolvesAsSide,
        boolean solidObjectKeepsOnObjWhenJumpedOffSameFrame,
        boolean advanceWaterLevelBeforePlayerPhysics,
        int defaultCollisionLayoutYMask,
        /**
         * Whether {@code defaultCollisionLayoutYMask} may be applied to EVERY collision
         * layout lookup, not just the negative-Y ceiling probe.
         *
         * <p>True for S1 and S2, whose tile lookups mask the row index with a constant
         * assembled into the routine: {@code andi.w #$380} over an 8-row/$80 buffer
         * (docs/s1disasm/_incObj/"sub FindNearestTile & FindFloor & FindWall.asm":15-17)
         * and {@code andi.w #$F00} over 16 rows of 128px (docs/s2disasm/s2.asm:43366-43368)
         * — both an 0x800px window.
         *
         * <p>False for S3K, whose {@code Find_Tile_FG} masks with the RUNTIME variable
         * {@code Layout_row_index_mask} (docs/skdisasm/sonic3k.asm:19143-19145), written
         * per level: {@code $7C} normally and {@code $3C} for looping levels
         * (sonic3k.asm:102207, 110071 "We're in a looping level!", 110322, 114224,
         * 114253). It also masks an already-shifted row index rather than a Y position,
         * so it is not the same quantity at all. Treating it as a constant 0x0FFF made
         * ICZ1's snowboard intro end its slope ride 193px short. Until the engine models
         * that per-level variable, S3K keeps its previous lookup behaviour.
         */
        boolean layoutYMaskAppliesToAllLookups) {
}
