package com.openggf.game.sonic1.objects;
import com.openggf.game.PlayableEntity;

import com.openggf.debug.DebugRenderContext;
import com.openggf.game.sonic1.constants.Sonic1Constants;
import com.openggf.game.sonic1.constants.Sonic1ObjectIds;
import com.openggf.graphics.GLCommand;
import com.openggf.graphics.RenderPriority;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectArtKeys;
import com.openggf.level.objects.ObjectLifetimeOps;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.objects.SpawnRomZoneRewindRecreatable;
import com.openggf.level.objects.TouchActorContextPolicy;
import com.openggf.level.objects.TouchAttackBouncePolicy;
import com.openggf.level.objects.TouchCategoryDecodeMode;
import com.openggf.level.objects.TouchOverlapStopPolicy;
import com.openggf.level.objects.TouchResponseProfile;
import com.openggf.level.objects.TouchResponseProvider;
import com.openggf.level.objects.TouchShieldDeflectCapability;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.physics.TrigLookupTable;

import java.util.List;

/**
 * Object 0x57 — Spiked Ball and Chain (SYZ, LZ).
 * <p>
 * A chain of spike balls rotating in a circle around a fixed pivot point.
 * Each chain element orbits at a different radius, creating a spinning chain effect.
 * <p>
 * <b>Zone variants:</b>
 * <ul>
 *   <li>SYZ: All chain links share the same 16x16 spikeball art (Map_SBall).
 *       All links (including parent) have obColType = $98 (hurt + size index $18).</li>
 *   <li>LZ: Uses Map_SBall2 with 3 frames. Chain links are harmless (obColType = 0).
 *       The parent ball at the chain end gets obColType = $8B (hurt + size index $0B)
 *       and uses frame 1 (large spikeball). The innermost link with radius 0 uses
 *       frame 2 (wall base/attachment).</li>
 * </ul>
 * <p>
 * <b>Subtype encoding:</b>
 * <ul>
 *   <li>High nibble (bits 4-7): Speed value — sign-extended then shifted left 3</li>
 *   <li>Bit 3: If set, reduces chain length by 1 (skips one ball)</li>
 *   <li>Bits 0-2: Chain length (number of elements from center to tip)</li>
 * </ul>
 * <p>
 * <b>Initial angle:</b> Derived from obStatus bits 0-1:
 * {@code ror.b #2,d0 / andi.b #$C0,d0} maps to quadrant starts ($00/$40/$80/$C0).
 * <p>
 * <b>Disassembly reference:</b> docs/s1disasm/_incObj/57 Spiked Ball and Chain.asm
 */
public class Sonic1SpikedBallChainObjectInstance extends AbstractObjectInstance
        implements TouchResponseProvider, SpawnRomZoneRewindRecreatable {

    // Display priority: move.b #4,obPriority(a0)
    private static final int DISPLAY_PRIORITY = 4;

    // obActWid: move.b #8,obActWid(a0)
    private static final int HALF_WIDTH = 8;

    // SYZ collision type: move.b #$98,obColType(a0)
    // All chain elements hurt in SYZ — HURT ($80) + size index $18
    private static final int SYZ_COLLISION_TYPE = 0x98;

    // LZ parent collision type: move.b #$8B,obColType(a0)
    // Only the end ball hurts in LZ — HURT ($80) + size index $0B
    private static final int LZ_PARENT_COLLISION_TYPE = 0x8B;

    private static final TouchResponseProfile MULTI_REGION_HURT_PROFILE = hurtProfile(
            true, TouchOverlapStopPolicy.STOP_AFTER_FIRST_OVERLAP_FOR_MAIN_ONLY);
    private static final TouchResponseProfile SINGLE_REGION_HURT_PROFILE = hurtProfile(
            false, TouchOverlapStopPolicy.STOP_AFTER_FIRST_OVERLAP_FOR_ALL_ACTORS);

    // Zone variant
    private boolean isLZ;

    // Anchor / pivot position (sball_origX = objoff_3A, sball_origY = objoff_38)
    private int origX;
    private int origY;

    // Rotation state
    private int angle;        // obAngle: 16-bit angle accumulator (high byte used for CalcSine)
    private int speed;  // sball_speed = objoff_3E: angular velocity per frame

    // Chain element data (parent + children, ordered from outermost to innermost)
    // Index 0 = parent (outermost), last index = innermost (closest to pivot)
    private int elementCount;
    private final int[] elementRadius;   // sball_radius per element
    private final int[] elementFrame;    // mapping frame per element
    private final int[] elementColType;  // collision type per element
    private final int[] elementX;        // current X position
    private final int[] elementY;        // current Y position
    private final transient ChainChild[] children;
    private boolean childrenSpawned;

    // Art key for rendering
    private final String artKey;

    private static TouchResponseProfile hurtProfile(boolean multiRegionSource,
            TouchOverlapStopPolicy stopPolicy) {
        return new TouchResponseProfile(
                TouchCategoryDecodeMode.NORMAL,
                false,
                true,
                multiRegionSource,
                TouchShieldDeflectCapability.NONE,
                0,
                TouchAttackBouncePolicy.STANDARD_ENEMY_KILL,
                TouchActorContextPolicy.MAIN_FULL_SIDEKICK_HURT_ONLY,
                stopPolicy);
    }

    public Sonic1SpikedBallChainObjectInstance(ObjectSpawn spawn, int zoneIndex) {
        super(spawn, "SpikedBallChain");

        this.isLZ = (zoneIndex == Sonic1Constants.ZONE_LZ);
        this.origX = spawn.x();
        this.origY = spawn.y();

        int subtype = spawn.subtype() & 0xFF;

        // Speed: move.b obSubtype(a0),d1 / andi.b #$F0,d1 / ext.w d1 / asl.w #3,d1
        // Sign-extend the masked byte, then shift left 3 (multiply by 8)
        int d1 = (byte) (subtype & 0xF0); // sign-extend via cast to byte
        this.speed = (short) (d1 << 3);   // asl.w #3 (16-bit result)

        // Initial angle: move.b obStatus(a0),d0 / ror.b #2,d0 / andi.b #$C0,d0
        // obStatus bits 0-1 map to quadrant: 0→$00, 1→$40, 2→$80, 3→$C0
        int status = spawn.renderFlags() & 0xFF;
        int d0 = ((status >>> 2) | (status << 6)) & 0xFF;
        this.angle = (d0 & 0xC0) << 8;

        // Chain length from low nibble: andi.w #7,d1
        int chainLen = subtype & 0x07;

        // Radius for outermost element: lsl.w #4,d3 → chainLen * 16
        int outerRadius = chainLen * 0x10;

        // Number of children: subq.w #1,d1 then dbf runs d1+1 times = chainLen children.
        // Bit 3 check: btst #3,obSubtype(a0) / beq.s .makechain / subq.w #1,d1
        // If bit 3 is set, one additional subq.w #1 reduces children by 1.
        // If the result goes negative (bcs), no children are created.
        boolean bit3 = (subtype & 0x08) != 0;
        int numChildren = chainLen;
        if (bit3) {
            numChildren--;
        }
        if (numChildren < 0) {
            numChildren = 0;
        }

        // Total elements = children + parent (the parent itself is the outermost element)
        this.elementCount = numChildren + 1;
        this.elementRadius = new int[elementCount];
        this.elementFrame = new int[elementCount];
        this.elementColType = new int[elementCount];
        this.elementX = new int[elementCount];
        this.elementY = new int[elementCount];
        this.children = new ChainChild[numChildren];

        // Build child elements from outermost inward
        // Disasm: d3 starts at outerRadius, children get d3 -= 0x10 each
        // Child sball_radius = d3 after subtraction
        int d3 = outerRadius;
        int childIdx = 0;
        for (int i = 0; i < numChildren; i++) {
            d3 -= 0x10;
            elementRadius[childIdx] = d3;
            // Default frame: 0 for all (SYZ uses single frame Map_SBall)
            elementFrame[childIdx] = 0;
            // In LZ, chain links are harmless; in SYZ, all elements hurt
            elementColType[childIdx] = isLZ ? 0 : SYZ_COLLISION_TYPE;

            // LZ: if radius == 0, use frame 2 (wall base/attachment)
            // Disasm: tst.b d3 / bne.s .notlzagain / move.b #2,obFrame(a1)
            if (isLZ && d3 == 0) {
                elementFrame[childIdx] = 2;
            }
            childIdx++;
        }

        // Parent element (outermost): uses the full radius
        // Disasm at .fail: the parent is added as the last element in the chain
        int parentIdx = childIdx;
        elementRadius[parentIdx] = outerRadius;

        if (isLZ) {
            // LZ: parent gets obColType $8B and frame 1 (large spikeball)
            // Disasm: move.b #$8B,obColType(a0) / move.b #1,obFrame(a0)
            elementColType[parentIdx] = LZ_PARENT_COLLISION_TYPE;
            elementFrame[parentIdx] = 1;
        } else {
            // SYZ: parent has same collision as children
            elementColType[parentIdx] = SYZ_COLLISION_TYPE;
            elementFrame[parentIdx] = 0;
        }

        // Art key depends on zone
        this.artKey = isLZ ? ObjectArtKeys.LZ_SPIKEBALL_CHAIN : ObjectArtKeys.SYZ_SPIKEBALL_CHAIN;

        // Calculate initial positions
        updatePositions();
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        if (isDestroyed()) {
            return;
        }
        if (!childrenSpawned) {
            spawnChildren();
            childrenSpawned = true;
        }

        // move.w sball_speed(a0),d0 / add.w d0,obAngle(a0)
        angle = (angle + speed) & 0xFFFF;

        updatePositions();
        syncElementObjects();
    }

    private void spawnChildren() {
        for (int i = 0; i < children.length; i++) {
            final int index = i;
            children[i] = spawnFreeChild(() -> new ChainChild(
                    ChainChild.spawnFor(elementX[index], elementY[index], artKey, elementFrame[index],
                            elementColType[index], origX, index),
                    artKey,
                    elementFrame[index],
                    elementColType[index],
                    origX));
        }
    }

    /**
     * Positions all chain elements using CalcSine on the current angle.
     * <p>
     * From SBall_Move / .movesub:
     * <pre>
     *   move.b obAngle(a0),d0
     *   jsr    (CalcSine).l          ; d0=sin, d1=cos
     *   move.w sball_origY(a0),d2
     *   move.w sball_origX(a0),d3
     *   ; For each child:
     *   moveq  #0,d4
     *   move.b sball_radius(a1),d4
     *   muls.w d0,d4 / asr.l #8,d4  ; yOff = (sin * radius) >> 8
     *   muls.w d1,d5 / asr.l #8,d5  ; xOff = (cos * radius) >> 8
     *   add.w  d2,d4                 ; Y = origY + yOff
     *   add.w  d3,d5                 ; X = origX + xOff
     * </pre>
     */
    private void updatePositions() {
        // move.b obAngle(a0),d0 — reads high byte of 16-bit angle
        int angleByte = (angle >> 8) & 0xFF;

        int sin = TrigLookupTable.sinHex(angleByte);
        int cos = TrigLookupTable.cosHex(angleByte);

        for (int i = 0; i < elementCount; i++) {
            int radius = elementRadius[i];
            // muls.w d0,d4 / asr.l #8,d4
            int yOff = (sin * radius) >> 8;
            // muls.w d1,d5 / asr.l #8,d5
            int xOff = (cos * radius) >> 8;
            elementY[i] = origY + yOff;
            elementX[i] = origX + xOff;
        }
    }

    private void syncElementObjects() {
        for (int i = 0; i < children.length; i++) {
            if (children[i] != null) {
                children[i].setPosition(elementX[i], elementY[i]);
            }
        }
        int parentIdx = elementCount - 1;
        updateDynamicSpawn(elementX[parentIdx], elementY[parentIdx]);
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        PatternSpriteRenderer renderer = getRenderer(artKey);
        if (renderer == null) return;

        int parentIdx = elementCount - 1;
        renderer.drawFrameIndex(elementFrame[parentIdx], elementX[parentIdx], elementY[parentIdx],
                false, false);
    }

    @Override
    public int getPriorityBucket() {
        return RenderPriority.clamp(DISPLAY_PRIORITY);
    }

    // ---- TouchResponseProvider ----
    // In SYZ, all chain elements hurt. In LZ, only the parent (end ball) hurts.
    // We use getMultiTouchRegions() to report all harmful element positions.

    @Override
    public TouchResponseProfile getTouchResponseProfile() {
        return SINGLE_REGION_HURT_PROFILE;
    }

    @Override
    public TouchResponseProfile getTouchResponseProfile(boolean multiRegionSource) {
        return SINGLE_REGION_HURT_PROFILE;
    }

    @Override
    public int getCollisionFlags() {
        // Return the parent element's collision type as the primary
        return elementColType[elementCount - 1];
    }

    @Override
    public int getCollisionProperty() {
        return 0;
    }

    @Override
    public TouchRegion[] getMultiTouchRegions() {
        return null;
    }

    // ---- Persistence ----

    @Override
    public int getOutOfRangeReferenceX() {
        return origX;
    }

    @Override
    public void onUnload() {
        ObjectManager objectManager = tryServices() != null ? tryServices().objectManager() : null;
        for (ChainChild child : children) {
            if (child != null) {
                // Obj57 .delete loops sball_childs and calls DeleteChild, freeing each SST slot immediately.
                ObjectLifetimeOps.expireDynamic(child);
                if (objectManager != null) {
                    objectManager.removeDynamicObject(child);
                }
            }
        }
    }

    @Override
    public String traceDebugDetails() {
        StringBuilder slots = new StringBuilder();
        for (ChainChild child : children) {
            if (child != null) {
                if (!slots.isEmpty()) {
                    slots.append(',');
                }
                slots.append(child.getSlotIndex());
            }
        }
        return String.format("angle=%04X speed=%d children=%d childSlots=[%s] orig=%04X,%04X",
                angle & 0xFFFF,
                speed,
                children.length,
                slots,
                origX & 0xFFFF,
                origY & 0xFFFF);
    }

    private void adoptRestoredChild(ChainChild child, int childIndex) {
        if (childIndex >= 0 && childIndex < children.length) {
            children[childIndex] = child;
        }
    }

    // ---- Debug rendering ----

    @Override
    public void appendDebugRenderCommands(DebugRenderContext ctx) {
        // Draw pivot point (yellow cross)
        ctx.drawLine(origX - 4, origY, origX + 4, origY, 1.0f, 1.0f, 0.0f);
        ctx.drawLine(origX, origY - 4, origX, origY + 4, 1.0f, 1.0f, 0.0f);

        // Draw each element position
        for (int i = 0; i < elementCount; i++) {
            boolean harmful = elementColType[i] != 0;
            float r = harmful ? 1.0f : 0.0f;
            float g = harmful ? 0.0f : 1.0f;
            ctx.drawLine(elementX[i] - 3, elementY[i], elementX[i] + 3, elementY[i], r, g, 0.0f);
            ctx.drawLine(elementX[i], elementY[i] - 3, elementX[i], elementY[i] + 3, r, g, 0.0f);
        }

        // Draw line from pivot to parent element (outermost)
        int parentIdx = elementCount - 1;
        ctx.drawLine(origX, origY, elementX[parentIdx], elementY[parentIdx], 0.5f, 0.5f, 0.5f);
    }

    private static final class ChainChild extends AbstractObjectInstance
            implements TouchResponseProvider, RewindRecreatable {
        private static final int FRAME_MASK = 0x03;
        private static final int COLLISION_SHIFT = 2;
        private static final int COLLISION_MASK = 0x03;
        private static final int CHILD_INDEX_SHIFT = 4;
        private static final int CHILD_INDEX_MASK = 0x07;
        private static final int LZ_FLAG = 0x80;

        private final String artKey;
        private final int frame;
        private final int collisionType;
        private final int originX;

        private ChainChild(ObjectSpawn spawn) {
            this(spawn,
                    artKey(spawn),
                    frame(spawn),
                    collisionType(spawn),
                    originX(spawn));
        }

        private ChainChild(ObjectSpawn spawn, String artKey, int frame, int collisionType,
                           int originX) {
            super(spawn, "SpikedBallChainChild");
            this.artKey = artKey;
            this.frame = frame;
            this.collisionType = collisionType;
            this.originX = originX;
        }

        private static ObjectSpawn spawnFor(int x, int y, String artKey, int frame, int collisionType,
                                            int originX, int childIndex) {
            int subtype = (frame & FRAME_MASK)
                    | (collisionCode(collisionType) << COLLISION_SHIFT)
                    | ((childIndex & CHILD_INDEX_MASK) << CHILD_INDEX_SHIFT);
            if (ObjectArtKeys.LZ_SPIKEBALL_CHAIN.equals(artKey)) {
                subtype |= LZ_FLAG;
            }
            return new ObjectSpawn(x, y, Sonic1ObjectIds.SPIKED_BALL_CHAIN,
                    subtype, 0, false, originX);
        }

        @Override
        public ChainChild recreateForRewind(RewindRecreateContext ctx) {
            ChainChild child = new ChainChild(ctx.spawn());
            Sonic1SpikedBallChainObjectInstance parent = nearestParent(ctx, child.originX);
            if (parent == null) {
                return null;
            }
            parent.adoptRestoredChild(child, childIndex(ctx.spawn()));
            return child;
        }

        private void setPosition(int x, int y) {
            updateDynamicSpawn(x, y);
        }

        @Override
        public boolean isPersistent() {
            return !isDestroyed();
        }

        @Override
        public int getOutOfRangeReferenceX() {
            return originX;
        }

        @Override
        public void appendRenderCommands(List<GLCommand> commands) {
            PatternSpriteRenderer renderer = getRenderer(artKey);
            if (renderer != null) {
                renderer.drawFrameIndex(frame, getX(), getY(), false, false);
            }
        }

        @Override
        public int getPriorityBucket() {
            return RenderPriority.clamp(DISPLAY_PRIORITY);
        }

        @Override
        public TouchResponseProfile getTouchResponseProfile() {
            return SINGLE_REGION_HURT_PROFILE;
        }

        @Override
        public TouchResponseProfile getTouchResponseProfile(boolean multiRegionSource) {
            return SINGLE_REGION_HURT_PROFILE;
        }

        @Override
        public int getCollisionFlags() {
            return collisionType;
        }

        @Override
        public int getCollisionProperty() {
            return 0;
        }

        @Override
        public String traceDebugDetails() {
            return String.format("child frame=%d col=%02X orig=%04X",
                    frame,
                    collisionType & 0xFF,
                    originX & 0xFFFF);
        }

        private static Sonic1SpikedBallChainObjectInstance nearestParent(
                RewindRecreateContext ctx, int originX) {
            Sonic1SpikedBallChainObjectInstance nearest = null;
            int bestDistance = Integer.MAX_VALUE;
            ObjectManager objectManager = ctx.objectServices().objectManager();
            for (ObjectInstance object : objectManager.getActiveObjects()) {
                if (object instanceof Sonic1SpikedBallChainObjectInstance parent
                        && !parent.isDestroyed()) {
                    int distance = Math.abs(parent.origX - originX);
                    if (distance < bestDistance) {
                        nearest = parent;
                        bestDistance = distance;
                    }
                }
            }
            return nearest;
        }

        private static String artKey(ObjectSpawn spawn) {
            return (spawn.subtype() & LZ_FLAG) != 0
                    ? ObjectArtKeys.LZ_SPIKEBALL_CHAIN
                    : ObjectArtKeys.SYZ_SPIKEBALL_CHAIN;
        }

        private static int frame(ObjectSpawn spawn) {
            return spawn.subtype() & FRAME_MASK;
        }

        private static int collisionType(ObjectSpawn spawn) {
            return switch ((spawn.subtype() >> COLLISION_SHIFT) & COLLISION_MASK) {
                case 1 -> SYZ_COLLISION_TYPE;
                case 2 -> LZ_PARENT_COLLISION_TYPE;
                default -> 0;
            };
        }

        private static int originX(ObjectSpawn spawn) {
            return spawn.rawYWord();
        }

        private static int childIndex(ObjectSpawn spawn) {
            return (spawn.subtype() >> CHILD_INDEX_SHIFT) & CHILD_INDEX_MASK;
        }

        private static int collisionCode(int collisionType) {
            if (collisionType == SYZ_COLLISION_TYPE) {
                return 1;
            }
            if (collisionType == LZ_PARENT_COLLISION_TYPE) {
                return 2;
            }
            return 0;
        }
    }


}
