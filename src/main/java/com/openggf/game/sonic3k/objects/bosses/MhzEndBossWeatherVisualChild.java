package com.openggf.game.sonic3k.objects.bosses;

import com.openggf.game.PlayableEntity;
import com.openggf.game.rewind.RewindTransient;
import com.openggf.game.sonic3k.Sonic3kObjectArtKeys;
import com.openggf.game.sonic3k.constants.Sonic3kObjectIds;
import com.openggf.graphics.GLCommand;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.objects.RewindRecreateObjectLinks;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.render.PatternSpriteRenderer;

import java.util.List;

/**
 * Weather-machine visual children for S3K {@code Obj_MHZEndBoss}.
 *
 * <p>ROM references: {@code ChildObjDat_769A4 -> loc_76604} and
 * {@code ChildObjDat_769AA -> loc_76636}. The first pair follows the
 * weather-machine position and uses the raw multi-delay scripts at
 * {@code byte_769B6/byte_769C1}; the spark quartet starts at the ROM
 * {@code word_7672E} offsets and uses {@code byte_769CE}.
 */
public final class MhzEndBossWeatherVisualChild extends AbstractObjectInstance implements RewindRecreatable {
    private static final int PRIORITY_BUCKET = 3; // priority $180
    private static final int ANIMATED_PART_RENDER_HALF_WIDTH = 0x10;
    private static final int ANIMATED_PART_RENDER_HALF_HEIGHT = 0x10;
    private static final int SPARK_RENDER_HALF_WIDTH = 0x80;
    private static final int SPARK_RENDER_HALF_HEIGHT = 0x80;
    private static final int[][] SPARK_OFFSETS = {
            {-0x4D, -0x4E},
            {-0xA1, -0xA2},
            {-0xF5, -0xF6},
            {-0x149, -0x14A}
    };
    private static final int[][] ANIMATED_PART_FRAMES = {
            {5, 6, 7, 8, 0},
            {9, 0x0A, 9, 0x0A, 0x0B, 0x0C}
    };
    private static final int[][] ANIMATED_PART_DELAYS = {
            {1, 3, 5, 7, 9},
            {1, 1, 1, 1, 1, 1}
    };
    private static final int[] SPARK_FRAMES = {0x0D, 0x0E, 0x0F, 0x10};

    @RewindTransient(reason = "Structural parent link; child position derives from the weather-machine parent.")
    private final MhzEndBossWeatherMachineChild parent;
    private int subtype;
    private boolean spark;
    private int x;
    private int y;
    private int frameIndex;
    private int frameTimer;
    private int mappingFrame;

    /**
     * Bit 0 of {@code rawYWord} encodes the {@code spark} discriminator so a
     * rewind recreate can re-derive it from the captured spawn (which round-trips
     * through {@link #buildSpawnAt}). {@code subtype} round-trips as the spawn's
     * subtype field. The high {@code rawFlags()} bits ({@code 0xF000}) are
     * untouched.
     */
    private static final int SPARK_SPAWN_BIT = 0x0001;

    static MhzEndBossWeatherVisualChild animatedPart(MhzEndBossWeatherMachineChild parent, int subtype) {
        return new MhzEndBossWeatherVisualChild(parent, subtype, false);
    }

    static MhzEndBossWeatherVisualChild spark(MhzEndBossWeatherMachineChild parent, int subtype) {
        return new MhzEndBossWeatherVisualChild(parent, subtype, true);
    }

    /**
     * Rewind-restore entry: re-derives {@code subtype} and {@code spark} from the
     * captured spawn and routes through the correct static factory so the
     * construction-time branch matches the original instance. The live parent is
     * relinked by object-reference restore.
     */
    public static MhzEndBossWeatherVisualChild forRewindRecreate(
            MhzEndBossWeatherMachineChild parent, ObjectSpawn spawn) {
        int subtype = spawn.subtype();
        boolean spark = (spawn.rawYWord() & SPARK_SPAWN_BIT) != 0;
        return spark ? spark(parent, subtype) : animatedPart(parent, subtype);
    }

    private MhzEndBossWeatherVisualChild() {
        this(placeholderWeatherMachineForRewindProbe(), 0, false);
    }

    private MhzEndBossWeatherVisualChild(MhzEndBossWeatherMachineChild parent, int subtype, boolean spark) {
        super(new ObjectSpawn(
                        initialX(parent, subtype, spark),
                        initialY(parent, subtype, spark),
                        Sonic3kObjectIds.MHZ_END_BOSS,
                        subtype,
                        0,
                        false,
                        spark ? SPARK_SPAWN_BIT : 0),
                spark ? "MHZEndBossWeatherSpark" : "MHZEndBossWeatherAnimatedPart");
        this.parent = parent;
        this.subtype = subtype;
        this.spark = spark;
        this.mappingFrame = spark ? SPARK_FRAMES[0] : 5;
        refreshPosition();
    }

    @Override
    public AbstractObjectInstance recreateForRewind(RewindRecreateContext ctx) {
        MhzEndBossWeatherMachineChild liveParent = RewindRecreateObjectLinks.nearestLiveObject(
                ctx, MhzEndBossWeatherMachineChild.class);
        return liveParent != null ? forRewindRecreate(liveParent, ctx.spawn()) : null;
    }

    private static MhzEndBossWeatherMachineChild placeholderWeatherMachineForRewindProbe() {
        return new MhzEndBossWeatherMachineChild(new MhzEndBossInstance(new ObjectSpawn(
                -0xC0,
                0,
                Sonic3kObjectIds.MHZ_END_BOSS,
                0,
                0,
                false,
                0)));
    }

    private static int initialX(MhzEndBossWeatherMachineChild parent, int subtype, boolean spark) {
        return parent.getX() + (spark ? SPARK_OFFSETS[subtype][0] : 0);
    }

    private static int initialY(MhzEndBossWeatherMachineChild parent, int subtype, boolean spark) {
        return parent.getY() + (spark ? SPARK_OFFSETS[subtype][1] : 0);
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity player) {
        if (parent.isDestroyed()) {
            setDestroyed(true);
            return;
        }
        refreshPosition();
        animate();
        updateDynamicSpawn(x, y);
    }

    private void refreshPosition() {
        x = initialX(parent, subtype, spark);
        y = initialY(parent, subtype, spark);
    }

    private void animate() {
        frameTimer--;
        if (frameTimer >= 0) {
            return;
        }
        if (spark) {
            frameIndex = (frameIndex + 1) % SPARK_FRAMES.length;
            mappingFrame = SPARK_FRAMES[frameIndex];
            frameTimer = 1;
            return;
        }
        int[] frames = ANIMATED_PART_FRAMES[subtype];
        int[] delays = ANIMATED_PART_DELAYS[subtype];
        mappingFrame = frames[frameIndex];
        frameTimer = delays[frameIndex];
        frameIndex = (frameIndex + 1) % frames.length;
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
    public int getOnScreenHalfWidth() {
        return spark ? SPARK_RENDER_HALF_WIDTH : ANIMATED_PART_RENDER_HALF_WIDTH;
    }

    @Override
    public int getOnScreenHalfHeight() {
        return spark ? SPARK_RENDER_HALF_HEIGHT : ANIMATED_PART_RENDER_HALF_HEIGHT;
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        PatternSpriteRenderer renderer = getRenderer(Sonic3kObjectArtKeys.MHZ_END_BOSS);
        if (renderer == null) {
            return;
        }
        renderer.drawFrameIndex(mappingFrame, x, y, false, false);
    }

    @Override
    public int getPriorityBucket() {
        return PRIORITY_BUCKET;
    }
}
