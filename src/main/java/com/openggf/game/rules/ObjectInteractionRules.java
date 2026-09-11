package com.openggf.game.rules;

/**
 * Per-game object-interaction behaviour.
 *
 * @param duckTouchBoxMappingFrame ROM mapping frame that shrinks the object/ring touch box
 *        while ducking, or {@link #NO_DUCK_TOUCH_BOX} when the game has no such adjustment.
 *        <p>ASSEMBLY FLAG: {@code FixBugs} (s1, docs/s1disasm/sonic.asm:20) / {@code fixBugs}
 *        (s2, docs/s2disasm/s2.asm:27), both 0 in the shipped builds. THE ENGINE IMPLEMENTS
 *        THE SHIPPED (UN-FIXED) BRANCH, because the traces record shipped-ROM behaviour.
 *        <p>Shipped, the ducking test is a <em>mapping frame</em> compare:
 *        {@code cmpi.b #fr_Duck,obFrame(a0)} with {@code fr_Duck = $39}
 *        (docs/s1disasm/_incObj/Sonic ReactToItem.asm:34, docs/s1disasm/_anim/Sonic.asm:62),
 *        and {@code cmpi.b #$4D,mapping_frame(a0)} in Sonic 2 (s2.asm:31956 Touch_Rings,
 *        :85069 TouchResponse, :85212 Touch_Boss, :32521 Check_CNZ_bumpers). In Sonic 2 that
 *        matches only the <em>second</em> frame of
 *        {@code SonAni_Duck: dc.b 5,$4C,$4D,$FE,1} (s2.asm:38719) - the animation holds $4C
 *        for six frames first - and never matches Tails, whose
 *        {@code TailsAni_Duck: dc.b $3F,$5B,$FF} (s2.asm:41575) uses frame $5B, nor Super
 *        Sonic, whose {@code SupSonAni_Duck: dc.b 5,$C1,$FF} (s2.asm:38816) uses frame $C1.
 *        Both disassemblies flag this as a deliberate Sonic 1 leftover.
 *        <p>Were the flag 1, the test would instead be the animation id
 *        ({@code cmpi.b #AniIDSonAni_Duck,anim(a0)}), so the smaller box would apply for the
 *        whole duck, to Tails, and to Super Sonic.
 *        <p>Sonic 3 &amp; Knuckles removed the adjustment outright - see "Note the lack of a
 *        check for if the player is ducking / Height is no longer reduced by ducking" in
 *        {@code Touch_NoInstaShield} (docs/skdisasm/sonic3k.asm:20649-20650) and the equally
 *        duck-free {@code Test_Ring_Collisions_NoAttraction} (sonic3k.asm:18465-18476) - hence
 *        {@link #NO_DUCK_TOUCH_BOX} there.
 */
public record ObjectInteractionRules(
        boolean bossHitNegatesGroundSpeed,
        boolean bossHitHalvesBounceVelocity,
        boolean sidekickDespawnUsesObjectIdMismatch,
        boolean sidekickNormalDespawnDelaysFreshRenderEntry,
        boolean sidekickDespawnUsesRidingInstanceLoss,
        boolean sidekickDespawnUsesInteractCodeWordChange,
        boolean sidekickNormalCpuSkipsHurtRoutine,
        boolean permanentRespawnTableLatch,
        boolean objectsExecuteAfterPlayerPhysics,
        boolean touchResponseUsesRenderFlagYGate,
        boolean touchResponseUsesPreviousCollisionResponseList,
        boolean animalObjectPreservesObjectMoveXSubpixel,
        boolean animalObjectUsesRenderFlagDeleteBounds,
        boolean solidPushReleaseWritesWalkRunAnimationWord,
        boolean solidPushReleaseSkipsWalkRunWhenRolling,
        boolean solidPushReleaseSkipsWalkRunWhenSpindashing,
        int duckTouchBoxMappingFrame) {

    /** Sentinel for {@code duckTouchBoxMappingFrame}: no duck touch-box shrink in this game. */
    public static final int NO_DUCK_TOUCH_BOX = -1;

    /** ROM shift applied to the top edge when the duck frame matches (S1/S2 both 12px). */
    public static final int DUCK_TOUCH_BOX_TOP_SHIFT = 12;

    /** ROM height used when the duck frame matches: {@code moveq #$A,d5} doubled (S1/S2 both 20px). */
    public static final int DUCK_TOUCH_BOX_HEIGHT = 20;

    /** @return true when {@code mappingFrame} is this game's shipped ducking touch-box frame. */
    public boolean isDuckTouchBoxMappingFrame(int mappingFrame) {
        return duckTouchBoxMappingFrame != NO_DUCK_TOUCH_BOX
                && (mappingFrame & 0xFF) == duckTouchBoxMappingFrame;
    }
}
