package com.openggf.game.sonic3k.objects.bosses;

import com.openggf.game.PlayableEntity;
import com.openggf.game.rewind.RewindTransient;
import com.openggf.game.sonic3k.Sonic3kObjectArtKeys;
import com.openggf.game.sonic3k.audio.Sonic3kMusic;
import com.openggf.game.sonic3k.audio.Sonic3kSfx;
import com.openggf.game.sonic3k.constants.Sonic3kConstants;
import com.openggf.game.sonic3k.constants.Sonic3kObjectIds;
import com.openggf.game.sonic3k.objects.S3kBossExplosionChild;
import com.openggf.game.sonic3k.objects.SongFadeTransitionInstance;
import com.openggf.game.sonic3k.runtime.MhzZoneRuntimeState;
import com.openggf.graphics.GLCommand;
import com.openggf.level.Level;
import com.openggf.level.Palette;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.objects.RewindRecreateObjectLinks;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.objects.TouchResponseAttackable;
import com.openggf.level.objects.TouchResponseProvider;
import com.openggf.level.objects.TouchResponseResult;
import com.openggf.level.render.PatternSpriteRenderer;

import java.util.Arrays;
import java.util.List;

/**
 * Weather-machine child for S3K {@code Obj_MHZEndBoss}.
 *
 * <p>ROM reference: {@code ChildObjDat_76982 -> loc_76520}. The child is placed
 * at parent offset {@code +$11,-$51}, uses {@code ObjDat3_76940}
 * {@code collision_flags=$11}, and {@code loc_76574} sets the parent's
 * {@code $38} bit 2 when the child is destroyed.
 */
public final class MhzEndBossWeatherMachineChild extends AbstractObjectInstance
        implements TouchResponseProvider, TouchResponseAttackable, RewindRecreatable {
    private static final int X_OFFSET = 0x11;
    private static final int Y_OFFSET = -0x51;
    private static final int COLLISION_FLAGS = 0x11;
    private static final int COLLISION_PROPERTY = 1;
    private static final int PARENT_SIGNAL_FLAG_OFFSET = 0x38;
    private static final int PARENT_SIGNAL_FLAG = 0x04;
    private static final int MAPPING_FRAME = 4;
    private static final int RENDER_HALF_WIDTH = 0x80;
    private static final int RENDER_HALF_HEIGHT = 0x80;
    private static final int BOSS_MUSIC_FADE_FRAMES = 2 * 60;
    private static final int DESTRUCTION_WAIT_FRAMES = 0x3F;

    @RewindTransient(reason = "Structural parent link; parent state carries the ROM $38 signal.")
    private final MhzEndBossInstance parent;
    private int x;
    private int y;
    private int collisionFlags = COLLISION_FLAGS;
    private boolean destructionSignalled;
    private boolean visualChildrenSpawned;
    private int weatherSoundTimer;
    private int destructionWaitTimer;

    private MhzEndBossWeatherMachineChild() {
        this(placeholderParentForRewindProbe());
    }

    public MhzEndBossWeatherMachineChild(MhzEndBossInstance parent) {
        super(new ObjectSpawn(
                        parent.getX() + X_OFFSET,
                        parent.getY() + Y_OFFSET,
                        Sonic3kObjectIds.MHZ_END_BOSS,
                        0,
                        0,
                        false,
                        0),
                "MHZEndBossWeatherMachine");
        this.parent = parent;
        refreshFromParent();
    }

    @Override
    public AbstractObjectInstance recreateForRewind(RewindRecreateContext ctx) {
        MhzEndBossInstance liveParent = RewindRecreateObjectLinks.nearestLiveObject(
                ctx, MhzEndBossInstance.class);
        return liveParent != null ? new MhzEndBossWeatherMachineChild(liveParent) : null;
    }

    private static MhzEndBossInstance placeholderParentForRewindProbe() {
        return new MhzEndBossInstance(new ObjectSpawn(
                -0xC0,
                0,
                Sonic3kObjectIds.MHZ_END_BOSS,
                0,
                0,
                false,
                0));
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity player) {
        spawnVisualChildrenOnce();
        if (collisionFlags == 0) {
            signalDestructionOnce();
            updateDestructionWait();
        } else {
            updateWeatherMachineSound();
        }
        updateDynamicSpawn(x, y);
    }

    private void updateWeatherMachineSound() {
        weatherSoundTimer--;
        if (weatherSoundTimer >= 0) {
            return;
        }
        var services = tryServices();
        int randomBits = services != null && services.rng() != null
                ? services.rng().nextBits(0x0F)
                : 0;
        weatherSoundTimer = randomBits + 8;
        if (services != null) {
            services.playSfx(Sonic3kSfx.WEATHER_MACHINE.id);
        }
    }

    private void signalDestructionOnce() {
        if (destructionSignalled) {
            return;
        }
        destructionSignalled = true;
        destructionWaitTimer = DESTRUCTION_WAIT_FRAMES;
        parent.setCustomFlag(PARENT_SIGNAL_FLAG_OFFSET,
                parent.getCustomFlag(PARENT_SIGNAL_FLAG_OFFSET) | PARENT_SIGNAL_FLAG);
        byte[][] targetPalette = buildPostWeatherMachineTargetPalette();
        spawnFreeChild(() -> new MhzEndBossPaletteFadeController(targetPalette));
        spawnFreeChild(() -> new SongFadeTransitionInstance(BOSS_MUSIC_FADE_FRAMES, Sonic3kMusic.BOSS.id));
        spawnChild(() -> new S3kBossExplosionChild(x, y));
        clearSeasonFlag();
    }

    private void clearSeasonFlag() {
        var services = tryServices();
        if (services != null && services.zoneRuntimeState() instanceof MhzZoneRuntimeState mhzState) {
            mhzState.clearSeasonFlag();
        }
    }

    private byte[][] buildPostWeatherMachineTargetPalette() {
        byte[][] lines = new byte[4][];
        var services = tryServices();
        if (services == null) {
            return lines;
        }
        lines[0] = copyPaletteLine(services.currentLevel(), 0);
        try {
            lines[1] = services.rom().readBytes(Sonic3kConstants.PAL_MHZ_END_BOSS_ADDR, 32);
            byte[] mhzLine3And4 = services.rom().readBytes(Sonic3kConstants.PAL_MHZ1_LINE3_ADDR, 64);
            lines[2] = Arrays.copyOfRange(mhzLine3And4, 0, 32);
            lines[3] = Arrays.copyOfRange(mhzLine3And4, 32, 64);
        } catch (Exception ignored) {
            // Partial object-unit harnesses may omit ROM access; the fade object still exists.
        }
        return lines;
    }

    private static byte[] copyPaletteLine(Level level, int lineIndex) {
        if (level == null || lineIndex >= level.getPaletteCount()) {
            return new byte[32];
        }
        Palette palette = level.getPalette(lineIndex);
        byte[] line = new byte[32];
        for (int i = 0; i < Palette.PALETTE_SIZE; i++) {
            int word = encodeColor(palette.getColor(i));
            line[i * 2] = (byte) ((word >>> 8) & 0xFF);
            line[i * 2 + 1] = (byte) (word & 0xFF);
        }
        return line;
    }

    private static int encodeColor(Palette.Color color) {
        int red = ((color.r & 0xFF) * 7 + 127) / 255;
        int green = ((color.g & 0xFF) * 7 + 127) / 255;
        int blue = ((color.b & 0xFF) * 7 + 127) / 255;
        return ((blue & 0x07) << 9) | ((green & 0x07) << 5) | ((red & 0x07) << 1);
    }

    private void updateDestructionWait() {
        if (!destructionSignalled || isDestroyed()) {
            return;
        }
        if (destructionWaitTimer-- <= 0) {
            setDestroyed(true);
        }
    }

    private void spawnVisualChildrenOnce() {
        if (visualChildrenSpawned) {
            return;
        }
        var services = tryServices();
        if (services == null || services.objectManager() == null) {
            return;
        }
        visualChildrenSpawned = true;
        spawnChild(() -> MhzEndBossWeatherVisualChild.animatedPart(this, 0));
        spawnChild(() -> MhzEndBossWeatherVisualChild.animatedPart(this, 1));
        for (int subtype = 0; subtype < 4; subtype++) {
            int childSubtype = subtype;
            spawnChild(() -> MhzEndBossWeatherVisualChild.spark(this, childSubtype));
        }
    }

    private void refreshFromParent() {
        x = parent.getX() + X_OFFSET;
        y = parent.getY() + Y_OFFSET;
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
        return RENDER_HALF_WIDTH;
    }

    @Override
    public int getOnScreenHalfHeight() {
        return RENDER_HALF_HEIGHT;
    }

    @Override
    public int getCollisionFlags() {
        return collisionFlags;
    }

    @Override
    public int getCollisionProperty() {
        return COLLISION_PROPERTY;
    }

    @Override
    public void onPlayerAttack(PlayableEntity player, TouchResponseResult result) {
        collisionFlags = 0;
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        PatternSpriteRenderer renderer = getRenderer(Sonic3kObjectArtKeys.MHZ_END_BOSS);
        if (renderer == null) {
            return;
        }
        renderer.drawFrameIndex(MAPPING_FRAME, x, y, false, false);
    }

    @Override
    public int getPriorityBucket() {
        return 4; // ObjDat3_76940 priority $200
    }
}
