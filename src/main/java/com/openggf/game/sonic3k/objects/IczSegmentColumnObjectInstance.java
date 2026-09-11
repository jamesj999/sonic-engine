package com.openggf.game.sonic3k.objects;

import com.openggf.game.PlayableEntity;
import com.openggf.game.solid.PlayerSolidContactResult;
import com.openggf.game.solid.SolidCheckpointBatch;
import com.openggf.game.sonic3k.Sonic3kObjectArtKeys;
import com.openggf.game.sonic3k.audio.Sonic3kSfx;
import com.openggf.graphics.GLCommand;
import com.openggf.graphics.RenderPriority;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.GravityDebrisChild;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.ObjectServices;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.objects.RewindRecreateObjectLinks;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.objects.RomObjectCodePointerProvider;
import com.openggf.level.objects.SolidContact;
import com.openggf.level.objects.SolidExecutionMode;
import com.openggf.level.objects.SolidObjectListener;
import com.openggf.level.objects.SolidObjectParams;
import com.openggf.level.objects.SolidObjectProvider;
import com.openggf.level.objects.SpawnRewindRecreatable;
import com.openggf.level.objects.SpawnTrailingZeroIntsRewindRecreatable;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.sprites.playable.AbstractPlayableSprite;

import java.util.ArrayList;
import java.util.List;

/**
 * Object 0xB3 - ICZ segmented column.
 *
 * <p>ROM reference: {@code Obj_ICZSegmentColumn} (sonic3k.asm:188694-188873).
 * The visible/solid pieces are child objects produced by
 * {@code CreateChild8_TreeListRepeated}; the root object only owns the cascade.
 */
public class IczSegmentColumnObjectInstance extends AbstractObjectInstance
        implements SpawnRewindRecreatable {

    private static final int OBJECT_ID = 0xB3;
    private static final int NORMAL_SEGMENT_COUNT = 3;
    private static final int TALL_SEGMENT_COUNT = 4;
    private static final int SEGMENT_SUBTYPE_STEP = 2;
    private static final int SEGMENT_Y_SHIFT = 4;
    private static final int NORMAL_MAPPING_FRAME = 0x0A;
    private static final int TOP_CAP_MAPPING_FRAME = 0x03;
    private static final int DEBRIS_INITIAL_MAPPING_FRAME = 0x0C;
    private static final int[][] BREAK_DEBRIS_OFFSETS = {
            {-0x0C, -0x08}, {-0x04, -0x08}, {0x04, -0x08}, {0x0C, -0x08},
            {-0x0C, 0x00}, {-0x04, 0x00}, {0x04, 0x00}, {0x0C, 0x00},
            {-0x0C, 0x08}, {-0x04, 0x08}, {0x04, 0x08}, {0x0C, 0x08}
    };
    private static final int[][] VELOCITY_INDEX = {
            {-0x100, -0x100}, {0x100, -0x100}, {-0x200, -0x200}, {0x200, -0x200},
            {-0x300, -0x200}, {0x300, -0x200}, {-0x200, -0x200}, {0, -0x200},
            {-0x400, -0x300}, {0x400, -0x300}, {0x300, -0x300}, {-0x400, -0x300},
            {0x400, -0x300}, {-0x200, -0x200}, {0x200, -0x200}, {0, -0x100}
    };

    private int x;
    private int y;
    private int segmentCount;
    private boolean spawnedSegments;
    private int screenShakeFrames;
    private boolean ownsScreenShakeFlag;

    public IczSegmentColumnObjectInstance(ObjectSpawn spawn) {
        super(spawn, "ICZSegmentColumn");
        this.x = spawn.x();
        this.y = spawn.y();
        this.segmentCount = (spawn.subtype() & 0xFF) == 0 ? NORMAL_SEGMENT_COUNT : TALL_SEGMENT_COUNT;
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        if (!spawnedSegments) {
            spawnSegments();
            spawnedSegments = true;
        }
        updateTimedShake();
    }

    private void spawnSegments() {
        Segment previous = null;
        for (SegmentSpec spec : segmentSpecsForTesting()) {
            Segment parent = previous;
            previous = spawnChild(() -> new Segment(spec.x(), spec.y(), spec.subtype(), spec.mappingFrame(), this, parent));
        }
    }

    List<SegmentSpec> segmentSpecsForTesting() {
        List<SegmentSpec> specs = new ArrayList<>(segmentCount);
        for (int i = 0; i < segmentCount; i++) {
            int subtype = i * SEGMENT_SUBTYPE_STEP;
            specs.add(new SegmentSpec(subtype, x, y - (subtype << SEGMENT_Y_SHIFT),
                    mappingFrameForSubtype(subtype)));
        }
        return List.copyOf(specs);
    }

    @Override
    public int getX() {
        return x;
    }

    @Override
    public int getY() {
        return y;
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        // The root object is only a child-spawner; ROM rendering is handled by the segment children.
    }

    public record SegmentSpec(int subtype, int x, int y, int mappingFrame) {
    }

    public record BreakDebrisSpec(int subtype, int x, int y, int xVel, int yVel, int initialMappingFrame) {
    }

    static List<BreakDebrisSpec> breakDebrisSpecsForTesting(int parentX, int parentY) {
        return buildBreakDebrisSpecs(parentX, parentY);
    }

    private static List<BreakDebrisSpec> buildBreakDebrisSpecs(int parentX, int parentY) {
        List<BreakDebrisSpec> specs = new ArrayList<>(BREAK_DEBRIS_OFFSETS.length);
        for (int i = 0; i < BREAK_DEBRIS_OFFSETS.length; i++) {
            int subtype = i * SEGMENT_SUBTYPE_STEP;
            int velocityIndex = 3 + (subtype / SEGMENT_SUBTYPE_STEP);
            int[] offset = BREAK_DEBRIS_OFFSETS[i];
            int[] velocity = VELOCITY_INDEX[velocityIndex];
            specs.add(new BreakDebrisSpec(subtype, parentX + offset[0], parentY + offset[1],
                    velocity[0], velocity[1], DEBRIS_INITIAL_MAPPING_FRAME));
        }
        return List.copyOf(specs);
    }

    private static int mappingFrameForSubtype(int subtype) {
        return subtype == 6 ? TOP_CAP_MAPPING_FRAME : NORMAL_MAPPING_FRAME;
    }

    public static final class Segment extends AbstractObjectInstance
            implements SolidObjectProvider, SolidObjectListener, RomObjectCodePointerProvider, RewindRecreatable {

        private static final String ART_KEY = Sonic3kObjectArtKeys.ICZ_WALL_AND_COLUMN;
        private static final int PRIORITY = 5; // ROM: priority $280
        private static final int ROM_CODE_POINTER_HIGH_WORD = 0x0008;
        private static final int SOLID_HALF_WIDTH = 0x2B;
        private static final int SOLID_HALF_HEIGHT = 0x10;
        private static final int TOP_LANDING_HALF_WIDTH = 0x20;
        private static final int BALANCE_WIDTH_PIXELS = 0x20;
        private static final int PUSH_BREAK_SPEED = 0x600;
        private static final int PUSH_ANIMATION_ID = 2;
        private static final int CASCADE_WAIT_BASE = 0x0F;
        private static final int CASCADE_WAIT_STEP = 4;
        private static final int FALL_TIMER_START = 7;
        private static final int FALL_STEP_PIXELS = 4;
        private static final int LAND_SHAKE_FRAMES = 0x10;

        private enum Phase {
            NORMAL,
            WAITING_TO_FALL,
            FALLING
        }

        private final IczSegmentColumnObjectInstance root;
        private final Segment previous;
        private int x;
        private int y;
        private int subtype;
        private final int mappingFrame;
        private Phase phase = Phase.NORMAL;
        private int timer;
        private boolean cascadeActive;

        private Segment(int x, int y, int subtype, int mappingFrame,
                        IczSegmentColumnObjectInstance root, Segment previous) {
            super(new ObjectSpawn(x, y, OBJECT_ID, subtype, 0, false, y), "ICZSegmentColumnSegment");
            this.x = x;
            this.y = y;
            this.subtype = subtype;
            this.mappingFrame = mappingFrame;
            this.root = root;
            this.previous = previous;
        }

        private Segment(ObjectSpawn spawn) {
            this(spawn.x(), spawn.y(), spawn.subtype() & 0xFF,
                    mappingFrameForSubtype(spawn.subtype() & 0xFF), null, null);
        }

        static Segment forTesting(int x, int y, int subtype, Segment previous) {
            return new Segment(x, y, subtype, mappingFrameForSubtype(subtype), null, previous);
        }

        @Override
        public Segment recreateForRewind(RewindRecreateContext ctx) {
            ObjectSpawn spawn = ctx.spawn();
            int restoredSubtype = spawn.subtype() & 0xFF;
            // A missing root or previous segment is a legitimate captured state, not a
            // restore error: a player push-break destroys a segment (and it can be
            // swept from the captured set entirely) while the survivors keep their
            // subtypes. Both links are consumed null-safely (updateNormal /
            // triggerTimedShake), and by end-of-frame capture the segment above a
            // break has already latched its cascade phase (ascending update order),
            // so a degraded link never loses the fall.
            IczSegmentColumnObjectInstance restoredRoot =
                    RewindRecreateObjectLinks.nearestLiveObject(ctx, IczSegmentColumnObjectInstance.class);
            Segment restoredPrevious = restoredSubtype == 0 ? null : previousSegmentForRewind(ctx, spawn, restoredSubtype);
            return new Segment(spawn.x(), spawn.y(), restoredSubtype, mappingFrameForSubtype(restoredSubtype),
                    restoredRoot, restoredPrevious);
        }

        private static Segment previousSegmentForRewind(
                RewindRecreateContext ctx,
                ObjectSpawn spawn,
                int subtype) {
            int expectedSubtype = subtype - SEGMENT_SUBTYPE_STEP;
            Segment best = null;
            long bestDistance = Long.MAX_VALUE;
            ObjectServices services = ctx.objectServices();
            if (services == null || services.objectManager() == null) {
                return null;
            }
            for (ObjectInstance object : services.objectManager().getActiveObjects()) {
                // Destroyed candidates stay eligible: after a push-break the true live
                // link points at exactly the destroyed segment, whose restored
                // cascadeActive flag is load-bearing for the fall sequence.
                if (!(object instanceof Segment candidate) || candidate.subtype != expectedSubtype) {
                    continue;
                }
                long dx = candidate.getX() - spawn.x();
                long dy = candidate.getY() - (spawn.y() + (SEGMENT_SUBTYPE_STEP << SEGMENT_Y_SHIFT));
                long distance = dx * dx + dy * dy;
                if (distance < bestDistance) {
                    bestDistance = distance;
                    best = candidate;
                }
            }
            return best;
        }

        @Override
        public void update(int vIntRunCount, PlayableEntity playerEntity) {
            if (isDestroyed()) {
                return;
            }

            switch (phase) {
                case NORMAL -> updateNormal();
                case WAITING_TO_FALL -> {
                    checkpointAll();
                    if (timer-- <= 0) {
                        startFalling();
                    }
                }
                case FALLING -> {
                    checkpointAll();
                    y += FALL_STEP_PIXELS;
                    if (timer-- <= 0) {
                        landAfterFall();
                    }
                }
            }
        }

        private void updateNormal() {
            if (subtype != 0 && previous != null && previous.cascadeActive) {
                startCascadeWait();
                return;
            }

            SolidCheckpointBatch batch = checkpointAll();
            if (subtype == 0 && mappingFrame != TOP_CAP_MAPPING_FRAME) {
                applyBreakContact(batch);
            }
        }

        private void applyBreakContact(SolidCheckpointBatch batch) {
            for (var entry : batch.perPlayer().entrySet()) {
                if (isDestroyed()) {
                    return;
                }
                if (entry.getKey() instanceof AbstractPlayableSprite player) {
                    tryBreakFromContact(player, entry.getValue());
                }
            }
        }

        private void tryBreakFromContact(AbstractPlayableSprite player, PlayerSolidContactResult result) {
            if (result == null || !result.pushingNow()) {
                return;
            }
            int xSpeed = result.preContact().xSpeed();
            if (Math.abs(xSpeed) < PUSH_BREAK_SPEED || result.preContact().animationId() != PUSH_ANIMATION_ID) {
                return;
            }

            player.setXSpeed((short) xSpeed);
            player.setGSpeed((short) xSpeed);
            player.setPushing(false);
            player.setOnObject(false);
            cascadeActive = true;
            spawnBreakDebris();
            setDestroyed(true);
            playSfx(Sonic3kSfx.COLLAPSE.id);
        }

        void spawnBreakDebrisForTesting() {
            spawnBreakDebris();
        }

        private void spawnBreakDebris() {
            for (BreakDebrisSpec spec : buildBreakDebrisSpecs(x, y)) {
                spawnChild(() -> new BreakDebris(spec));
            }
        }

        private void startCascadeWait() {
            phase = Phase.WAITING_TO_FALL;
            cascadeActive = true;
            timer = (subtype * CASCADE_WAIT_STEP) + CASCADE_WAIT_BASE;
        }

        private void startFalling() {
            phase = Phase.FALLING;
            timer = FALL_TIMER_START;
            subtype -= SEGMENT_SUBTYPE_STEP;
        }

        private void landAfterFall() {
            phase = Phase.NORMAL;
            cascadeActive = false;
            triggerTimedShake();
            playSfx(Sonic3kSfx.MECHA_LAND.id);
        }

        private void playSfx(int sfxId) {
            var services = tryServices();
            if (services != null && services.audioManager() != null) {
                services.audioManager().playSfx(sfxId);
            }
        }

        private void triggerTimedShake() {
            if (root != null) {
                root.triggerScreenShake(LAND_SHAKE_FRAMES);
            }
        }

        @Override
        public SolidObjectParams getSolidParams() {
            return SolidObjectParams.of(SOLID_HALF_WIDTH, SOLID_HALF_HEIGHT, SOLID_HALF_HEIGHT);
        }

        @Override
        public int getTopLandingHalfWidth(PlayableEntity playerEntity, int collisionHalfWidth) {
            return TOP_LANDING_HALF_WIDTH;
        }

        @Override
        public int getBalanceWidthPixels() {
            // ROM width_pixels byte for the segment, written by SetUp_ObjAttributes3 from
            // word_8ACEE (docs/skdisasm/sonic3k.asm:188863-188864: dc.w $280 / dc.b $20,$10,$A,0;
            // field order at :176907-176912). Verified in the ROM image at 0x8ACEE:
            // 02 80 20 10 0A 00.
            //
            // Deliberately NOT the SolidObjectFull d1 ($2B, sub_8AC70 at :188811-188815): the
            // on-object balance test reads width_pixels(a1), not the solid half-width. Sonic's
            // Sonic_Move (:22460-22473) and Tails' Tails_InputAcceleration_Path (:27820-27831)
            // both compute d1 = width_pixels(a1) + x_pos(a0) - x_pos(a1) and compare it against
            // d2 = 2*width_pixels - shift, so the shared 16px default balances the rider on the
            // wrong edge of this 32px-wide column.
            return BALANCE_WIDTH_PIXELS;
        }

        @Override
        public boolean isSolidFor(PlayableEntity playerEntity) {
            return !isDestroyed();
        }

        @Override
        public SolidExecutionMode solidExecutionMode() {
            return SolidExecutionMode.MANUAL_CHECKPOINT;
        }

        @Override
        public void onSolidContact(PlayableEntity playerEntity, SolidContact contact, int frameCounter) {
            // Manual checkpoints drive contact state from update(), matching the inline SolidObjectFull call.
        }

        @Override
        public void appendRenderCommands(List<GLCommand> commands) {
            if (isDestroyed()) {
                return;
            }
            PatternSpriteRenderer renderer = getRenderer(ART_KEY);
            if (renderer != null) {
                renderer.drawFrameIndex(mappingFrame, x, y, false, false);
            }
        }

        @Override
        public int getX() {
            return x;
        }

        @Override
        public int getY() {
            return y;
        }

        @Override
        public int getPriorityBucket() {
            return RenderPriority.clamp(PRIORITY);
        }

        @Override
        public int romObjectCodePointerHighWord() {
            // Obj_ICZSegmentColumn child segments install loc_8ABB0 in word 0
            // (docs/skdisasm/sonic3k.asm:188708-188731); S3K Tails CPU
            // stores that high word in Tails_CPU_interact.
            return ROM_CODE_POINTER_HIGH_WORD;
        }
    }

    public static final class BreakDebris extends GravityDebrisChild
            implements SpawnTrailingZeroIntsRewindRecreatable {
        private static final String ART_KEY = Sonic3kObjectArtKeys.ICZ_PLATFORMS;
        private static final int GRAVITY = 0x38;
        private static final int[] RAW_ANIMATION_LOWER = {
                0, 0x27, 0x23, 0x27, 0x13, 0x27, 0x24, 0x27, 0x14, 0xFC
        };
        private static final int[] RAW_ANIMATION_UPPER = {
                0, 0x27, 0x0C, 0x27, 0x0D, 0x27, 0x0E, 0xFC
        };

        private final int[] rawAnimation;
        private int mappingFrame;
        private int animFrame;
        private int animFrameTimer;

        private BreakDebris(BreakDebrisSpec spec) {
            super(new ObjectSpawn(spec.x(), spec.y(), OBJECT_ID, spec.subtype(), 0, false, spec.y()),
                    "ICZSegmentColumnDebris", spec.xVel(), spec.yVel(), GRAVITY);
            this.rawAnimation = spec.subtype() >= 8 ? RAW_ANIMATION_UPPER : RAW_ANIMATION_LOWER;
            this.mappingFrame = spec.initialMappingFrame();
            this.animFrame = initialAnimFrame();
        }

        private BreakDebris(ObjectSpawn spawn, int ignored) {
            super(spawn, "ICZSegmentColumnDebris", 0, 0, GRAVITY);
            this.rawAnimation = (spawn.subtype() & 0xFF) >= 8 ? RAW_ANIMATION_UPPER : RAW_ANIMATION_LOWER;
            this.animFrame = initialAnimFrame();
        }

        private int initialAnimFrame() {
            ObjectServices services = constructionContext();
            if (services == null || services.rng() == null) {
                return 0;
            }
            return services.rng().nextBits(3);
        }

        @Override
        public void update(int vIntRunCount, PlayableEntity player) {
            animateRaw();
            super.update(vIntRunCount, player);
        }

        private void animateRaw() {
            animFrameTimer--;
            if (animFrameTimer >= 0) {
                return;
            }

            animFrame = (animFrame + 1) & 0xFF;
            int scriptIndex = animFrame + 1;
            int value = scriptIndex < rawAnimation.length ? rawAnimation[scriptIndex] : 0xFC;
            if ((value & 0x80) != 0) {
                mappingFrame = rawAnimation[1];
                animFrameTimer = rawAnimation[0];
                animFrame = 0;
                return;
            }
            animFrameTimer = rawAnimation[0];
            mappingFrame = value;
        }

        @Override
        public int getPriorityBucket() {
            return RenderPriority.clamp(1); // ROM ObjDat3_8AAB6: priority $80
        }

        @Override
        public void appendRenderCommands(List<GLCommand> commands) {
            PatternSpriteRenderer renderer = getRenderer(ART_KEY);
            if (renderer != null) {
                renderer.drawFrameIndex(mappingFrame, getX(), getY(), false, false);
            }
        }

        int getMappingFrameForTesting() {
            return mappingFrame;
        }
    }

    private void triggerScreenShake(int frames) {
        screenShakeFrames = Math.max(screenShakeFrames, frames);
    }

    private void updateTimedShake() {
        var services = tryServices();
        if (services == null || services.gameState() == null) {
            return;
        }
        if (screenShakeFrames > 0) {
            services.gameState().setScreenShakeActive(true);
            screenShakeFrames--;
            ownsScreenShakeFlag = true;
        } else if (ownsScreenShakeFlag) {
            services.gameState().setScreenShakeActive(false);
            ownsScreenShakeFlag = false;
        }
    }
}
