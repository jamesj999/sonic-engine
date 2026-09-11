package com.openggf.level.objects;

/**
 * Game-specific dynamic object slot layout for the shared {@link ObjectManager}.
 *
 * <p>Only the allocatable dynamic slot window is modeled here. Fixed player/UI/support
 * slots that live outside the manager remain owned by their respective systems.
 */
public record ObjectSlotLayout(
        int firstDynamicSlot,
        int dynamicSlotCount,
        int processSlotCount,
        boolean twoAxisCursorPlacement,
        boolean preallocatesLostRingOwnerSlot,
        boolean lostRingRemainderAllocatesAfterOwnerSlot) {
    // S1 HurtSonic reserves the Obj37 owner slot with FindFreeObj and only stamps
    // id_RingLoss into it; v_rings is untouched when HurtSonic returns
    // (docs/s1disasm/_incObj/Sonic ReactToItem.asm:375-387). The clear happens in the
    // owner's own routine 0: RLoss_Count -> .resetcounter -> move.w #0,(v_rings).w
    // (docs/s1disasm/_incObj/25, 37 Rings.asm:234,250-251,297-298), so it lands the same
    // frame only when the object loop still reaches that slot. FindFreeObj scans upward
    // from v_lvlobjspace (docs/s1disasm/_incObj/sub FindFreeObj.asm:10-20), so a hit
    // taken inside a higher-numbered object's own tick — Obj36 Spikes calling HurtSonic
    // (docs/s1disasm/_incObj/36 Spikes.asm:129-154) — leaves the owner behind the live
    // cursor and slips both the spill and the ring clear to the next pass.
    public static final ObjectSlotLayout SONIC_1 = new ObjectSlotLayout(32, 96, false, true);
    // S2 HurtCharacter allocates the first Obj37 owner slot before Obj37_Init
    // fills the spill with plain AllocateObject from that owner
    // (docs/s2disasm/s2.asm:85444-85461, 25125-25146).
    public static final ObjectSlotLayout SONIC_2 = new ObjectSlotLayout(16, 112, false, true);
    // S3K Object_RAM has Player_1, Player_2, and Reserved_object_3 before
    // Dynamic_object_RAM, but AllocateObject pre-increments from
    // Dynamic_object_RAM before testing a slot (docs/skdisasm/sonic3k.asm:37906-37918),
    // so normal dynamic allocation starts at global SST slot 4. Its dbeq counter probes
    // 90 slots through global SST slot 93, which is also the first Level_object_RAM slot.
    //
    // S3K Load_Sprites also has separate X-cursor and Y-camera allocation passes:
    // the X pass advances Object_load_addr_front (docs/skdisasm/sonic3k.asm:37640-37658),
    // then the Y pass allocates previously X-passed entries that enter the vertical band
    // (docs/skdisasm/sonic3k.asm:37723-37762). This can allocate newer X-pass entries
    // before older deferred-Y entries.
    // S3K HurtCharacter allocates the first Obj37 owner slot before Obj37_Init
    // fills the spill with AllocateObjectAfterCurrent from that owner.
    //
    // S3K Process_Sprites still walks the full 110-slot Object_RAM table
    // (docs/skdisasm/sonic3k.constants.asm:303-323;
    // docs/skdisasm/sonic3k.asm:35965-35980). Most gameplay objects live in the
    // managed dynamic window above; call sites choose the ROM countdown that
    // matches the object path they are modeling.
    public static final ObjectSlotLayout SONIC_3K = new ObjectSlotLayout(4, 90, 110, true, true, true);

    public ObjectSlotLayout(int firstDynamicSlot, int dynamicSlotCount) {
        this(firstDynamicSlot, dynamicSlotCount, firstDynamicSlot + dynamicSlotCount, false, false, false);
    }

    public ObjectSlotLayout(int firstDynamicSlot, int dynamicSlotCount, boolean twoAxisCursorPlacement) {
        this(firstDynamicSlot, dynamicSlotCount, firstDynamicSlot + dynamicSlotCount,
                twoAxisCursorPlacement, false, false);
    }

    public ObjectSlotLayout(int firstDynamicSlot, int dynamicSlotCount,
                            boolean twoAxisCursorPlacement, boolean preallocatesLostRingOwnerSlot) {
        this(firstDynamicSlot, dynamicSlotCount, firstDynamicSlot + dynamicSlotCount,
                twoAxisCursorPlacement, preallocatesLostRingOwnerSlot, false);
    }

    public ObjectSlotLayout {
        if (firstDynamicSlot < 0) {
            throw new IllegalArgumentException("firstDynamicSlot must be >= 0");
        }
        if (dynamicSlotCount < 0) {
            throw new IllegalArgumentException("dynamicSlotCount must be >= 0");
        }
        if (processSlotCount < firstDynamicSlot + dynamicSlotCount) {
            throw new IllegalArgumentException("processSlotCount must cover the dynamic slot window");
        }
        if (lostRingRemainderAllocatesAfterOwnerSlot && !preallocatesLostRingOwnerSlot) {
            throw new IllegalArgumentException("lost-ring after-owner allocation requires an owner slot");
        }
    }

    public int lastDynamicSlotExclusive() {
        return firstDynamicSlot + dynamicSlotCount;
    }

    public int lastProcessSlotExclusive() {
        return processSlotCount;
    }

    public boolean isDynamicSlot(int slotIndex) {
        return slotIndex >= firstDynamicSlot && slotIndex < lastDynamicSlotExclusive();
    }

    public int toExecIndex(int slotIndex) {
        return slotIndex - firstDynamicSlot;
    }

    public int toSlotIndex(int execIndex) {
        return firstDynamicSlot + execIndex;
    }
}
