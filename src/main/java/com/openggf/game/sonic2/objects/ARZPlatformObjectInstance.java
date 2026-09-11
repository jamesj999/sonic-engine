package com.openggf.game.sonic2.objects;

import com.openggf.game.PlayableEntity;
import com.openggf.game.OscillationManager;
import com.openggf.game.sonic2.S2SpriteDataLoader;
import com.openggf.game.sonic2.constants.Sonic2Constants;
import com.openggf.debug.DebugRenderContext;
import com.openggf.graphics.GLCommand;
import com.openggf.graphics.GraphicsManager;
import com.openggf.level.PatternDesc;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectConstructionContext;
import com.openggf.level.objects.ObjectLifetimeOps;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.ObjectPlayerParticipationPolicy;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.PerObjectRewindSnapshot;
import com.openggf.level.objects.PlatformBobHelper;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.objects.SolidContact;
import com.openggf.level.objects.SolidObjectListener;
import com.openggf.level.objects.SolidObjectParams;
import com.openggf.level.objects.SolidObjectProvider;
import com.openggf.level.render.SpriteMappingFrame;
import com.openggf.level.render.SpriteMappingPiece;
import com.openggf.level.render.SpritePieceRenderer;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.util.LazyMappingHolder;

import java.util.List;
import java.util.logging.Logger;

/**
 * Object 18 - Stationary floating platform (EHZ/ARZ/HTZ).
 * Implements movement behaviors and rendering from the disassembly.
 */
public class ARZPlatformObjectInstance extends AbstractObjectInstance
        implements SolidObjectProvider, SolidObjectListener, RewindRecreatable {
    private static final Logger LOGGER = Logger.getLogger(ARZPlatformObjectInstance.class.getName());

    private static final int[] WIDTH_PIXELS = {
            0x20, 0x20, 0x20, 0x40, 0x30
    };
    private static final int[] FRAME_INDEX = { 0, 1, 2, 3, 4 };
    private static final int HALF_HEIGHT = 8;
    private static final int SOLID_EXTRA_WIDTH = 0x0B;
    private static final int SOLID_Y_RADIUS_DEFAULT = 0x30;
    private static final int SOLID_Y_RADIUS_ARZ = 0x28;
    private static final int PALETTE_INDEX = 2;

    private static final int FALL_GRAVITY = 0x38;
    private static final int FALL_RELEASE_DELAY = 0x1E;
    private static final int FALL_START_DELAY = 0x20;
    private static final int BUTTON_DELAY = 60;
    private static final int OFFSCREEN_Y_MARGIN = 0x120;

    private static final byte[] BUTTON_VINE_TRIGGERS = new byte[16];

    private static final LazyMappingHolder MAPPINGS_A = new LazyMappingHolder();
    private static final LazyMappingHolder MAPPINGS_B = new LazyMappingHolder();

    private int x;
    private int y;
    private int baseX;
    private int baseY;
    private int baseYFixed;
    private int widthPixels;
    private int mappingFrame;
    private int subtype;
    private int routine;
    private final PlatformBobHelper bobHelper = new PlatformBobHelper();
    private int angle;
    private int timer;
    private int yVel;
    private int yRadius;
    public ARZPlatformObjectInstance(ObjectSpawn spawn, String name) {
        super(spawn, name);
        init();
    }

    @Override
    public ARZPlatformObjectInstance recreateForRewind(RewindRecreateContext ctx) {
        return ObjectConstructionContext.construct(ctx.objectServices(),
                () -> new ARZPlatformObjectInstance(ctx.spawn(), getName()));
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
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        x = baseX;

        boolean standing = hasAnyPlayerStandingLatch(playerEntity);
        if (routine == 2 || routine == 8) {
            bobHelper.update(standing);
        }

        boolean updateAngle = applyBehaviour(player, standing);
        if (updateAngle) {
            angle = OscillationManager.getByte(0x18);
        }

        y = (baseYFixed >> 8) + bobHelper.getOffset();
        updateDynamicSpawn(x, y);
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        List<SpriteMappingFrame> mappings = resolveMappings();
        if (mappings == null || mappings.isEmpty()) {
            return;
        }

        int frame = mappingFrame;
        if (frame < 0) {
            frame = 0;
        }
        if (frame >= mappings.size()) {
            frame = mappings.size() - 1;
        }

        SpriteMappingFrame mapping = mappings.get(frame);
        if (mapping == null || mapping.pieces().isEmpty()) {
            return;
        }

        boolean hFlip = (spawn.renderFlags() & 0x1) != 0;
        boolean vFlip = (spawn.renderFlags() & 0x2) != 0;

        GraphicsManager graphicsManager = services().graphicsManager();
        List<SpriteMappingPiece> pieces = mapping.pieces();
        for (int i = pieces.size() - 1; i >= 0; i--) {
            SpriteMappingPiece piece = pieces.get(i);
            SpritePieceRenderer.renderPieces(
                    List.of(piece),
                    x,
                    y,
                    0,
                    PALETTE_INDEX,
                    hFlip,
                    vFlip,
                    (patternIndex, pieceHFlip, pieceVFlip, paletteIndex, drawX, drawY) -> {
                        int descIndex = patternIndex & 0x7FF;
                        if (pieceHFlip) {
                            descIndex |= 0x800;
                        }
                        if (pieceVFlip) {
                            descIndex |= 0x1000;
                        }
                        descIndex |= (paletteIndex & 0x3) << 13;
                        graphicsManager.renderPattern(new PatternDesc(descIndex), drawX, drawY);
                    });
        }
    }

    @Override
    public SolidObjectParams getSolidParams() {
        if (routine == 8) {
            int halfWidth = widthPixels + SOLID_EXTRA_WIDTH;
            int airHalfHeight = Math.max(1, yRadius);
            int groundHalfHeight = Math.max(1, yRadius + 1);
            return SolidObjectParams.of(halfWidth, airHalfHeight, groundHalfHeight);
        }
        return SolidObjectParams.of(widthPixels, HALF_HEIGHT, HALF_HEIGHT);
    }

    @Override
    public int getOutOfRangeReferenceX() {
        return baseX;
    }

    @Override
    public int getBalanceWidthPixels() {
        return widthPixels;
    }

    @Override
    public boolean isTopSolidOnly() {
        return routine != 8;
    }

    @Override
    public void onSolidContact(PlayableEntity playerEntity, SolidContact contact, int frameCounter) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        // Platform state is driven via ObjectManager standing checks.
    }

    @Override
    public boolean isSolidFor(PlayableEntity playerEntity) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        return !isDestroyed();
    }

    @Override
    public boolean clearsStandingBitOnContinuedRideExit(PlayableEntity player) {
        // Obj18 reads status(a0)&standing_mask before Obj18_Move/Obj18_Nudge,
        // then PlatformObject clears status(a0)'s player bit on air/walk-off
        // exits (docs/s2disasm/s2.asm:23219-23242,35737-35755).
        return true;
    }

    private void init() {
        routine = 2;
        int initIndex = (spawn.subtype() >> 3) & 0x0E;
        initIndex /= 2;
        if (initIndex < 0) {
            initIndex = 0;
        }
        if (initIndex >= WIDTH_PIXELS.length) {
            initIndex = WIDTH_PIXELS.length - 1;
        }
        widthPixels = WIDTH_PIXELS[initIndex];
        mappingFrame = FRAME_INDEX[initIndex];

        baseX = spawn.x();
        baseY = spawn.y();
        baseYFixed = baseY << 8;
        x = baseX;
        y = baseY;
        angle = 0x80;

        subtype = spawn.subtype();
        if ((subtype & 0x80) != 0) {
            routine = 8;
            subtype &= 0x0F;
            yRadius = isAquaticRuin() ? SOLID_Y_RADIUS_ARZ : SOLID_Y_RADIUS_DEFAULT;
        } else {
            subtype &= 0x0F;
            yRadius = HALF_HEIGHT;
        }

        updateDynamicSpawn(x, y);
    }

    private boolean applyBehaviour(AbstractPlayableSprite player, boolean standing) {
        int behaviour = subtype & 0x0F;
        switch (behaviour) {
            case 0, 9 -> {
                return false;
            }
            case 1 -> {
                x = baseX + signedByte(angle - 0x40);
                return true;
            }
            case 2 -> {
                setBaseY(baseY + signedByte(angle - 0x40));
                return true;
            }
            case 3 -> {
                handleFallTrigger(standing);
                return false;
            }
            case 4 -> {
                handleFalling(player);
                return false;
            }
            case 5 -> {
                x = baseX + signedByte(0x40 - angle);
                return true;
            }
            case 6 -> {
                setBaseY(baseY + signedByte(0x40 - angle));
                return true;
            }
            case 7 -> {
                handleButtonTrigger();
                return false;
            }
            case 8 -> {
                handleRise();
                return false;
            }
            case 10 -> {
                int offset = signedByte(angle - 0x40) >> 1;
                setBaseY(baseY + offset);
                return true;
            }
            case 11 -> {
                int offset = signedByte(0x40 - angle) >> 1;
                setBaseY(baseY + offset);
                return true;
            }
            case 12 -> {
                int osc = OscillationManager.getByte(0x0C);
                setBaseY(baseY + signedByte(osc - 0x30));
                return true;
            }
            case 13 -> {
                int osc = OscillationManager.getByte(0x0C);
                setBaseY(baseY + signedByte(0x30 - osc));
                return true;
            }
            default -> {
                return false;
            }
        }
    }

    private boolean hasAnyPlayerStandingLatch(PlayableEntity player) {
        ObjectManager om = services().objectManager();
        if (om == null) {
            return isPlayerRiding();
        }
        if (player != null && isRomVisibleStandingLatch(om, player)) {
            return true;
        }
        for (PlayableEntity participant : services().playerQuery()
                .playersFor(ObjectPlayerParticipationPolicy.ALL_ENGINE_PLAYERS)) {
            if (participant != null && isRomVisibleStandingLatch(om, participant)) {
                return true;
            }
        }
        return false;
    }

    private boolean isRomVisibleStandingLatch(ObjectManager om, PlayableEntity player) {
        if (!om.hasObjectStandingBit(player, this)) {
            return false;
        }
        // Obj18 reads status(a0)&standing_mask before PlatformObject, but
        // PlatformObject keeps that bit paired with either an active on-object
        // state or the one-frame airborne stale-rider clear path
        // (docs/s2disasm/s2.asm:23219-23242, 35742-35755, 35990-36029).
        return player.isOnObject() || player.getAir();
    }

    private void handleFallTrigger(boolean standing) {
        if (timer == 0) {
            if (standing) {
                timer = FALL_RELEASE_DELAY;
            }
            return;
        }
        timer--;
        if (timer == 0) {
            timer = FALL_START_DELAY;
            subtype = (subtype + 1) & 0xFF;
        }
    }

    private void handleFalling(AbstractPlayableSprite player) {
        if (timer != 0) {
            timer--;
            if (timer == 0) {
                if (player != null && isPlayerRiding()) {
                    releasePlayer(player);
                }
                routine = 6;
            }
        }

        baseYFixed += yVel;
        yVel += FALL_GRAVITY;

        int cameraMaxY = services().camera().getMaxY();
        if ((baseYFixed >> 8) > cameraMaxY + OFFSCREEN_Y_MARGIN) {
            ObjectLifetimeOps.destroyRespawnableOffscreen(this);
        }
    }

    private void releasePlayer(AbstractPlayableSprite player) {
        player.setAir(true);
        player.setYSpeed((short) yVel);
    }

    private void handleButtonTrigger() {
        int triggerIndex = (subtype >> 4) & 0x0F;
        if (timer == 0) {
            if (triggerIndex >= 0 && triggerIndex < BUTTON_VINE_TRIGGERS.length
                    && BUTTON_VINE_TRIGGERS[triggerIndex] != 0) {
                timer = BUTTON_DELAY;
            }
            return;
        }
        timer--;
        if (timer == 0) {
            subtype = (subtype + 1) & 0xFF;
        }
    }

    private void handleRise() {
        baseYFixed -= (2 << 8);
        if ((baseYFixed >> 8) == (baseY - 0x200)) {
            subtype = 0;
        }
    }

    private int signedByte(int value) {
        return (byte) value;
    }

    private void setBaseY(int value) {
        baseYFixed = value << 8;
    }

    private boolean isAquaticRuin() {
        return services().currentLevel() != null
                && isAquaticRuinZone(services().currentLevel().getZoneIndex());
    }

    /**
     * Tests whether {@code zoneIndex} (as returned by {@link com.openggf.level.Level#getZoneIndex()},
     * which is the ROM zone ID) is Aquatic Ruin Zone. ARZ is the only zone that uses the Obj18B
     * mapping table ({@code Obj18_MapUnc_1084E}) and the 0x28 solid {@code y_radius} in the ROM
     * ({@code s2.asm} {@code Obj18_Init}, {@code cmpi.b #aquatic_ruin_zone,(Current_Zone).w}).
     */
    static boolean isAquaticRuinZone(int zoneIndex) {
        return zoneIndex == Sonic2Constants.ZONE_ARZ;
    }

    private List<SpriteMappingFrame> resolveMappings() {
        if (isAquaticRuin()) {
            return MAPPINGS_B.get(
                    Sonic2Constants.MAP_UNC_OBJ18_B_ADDR, S2SpriteDataLoader::loadMappingFrames, "Obj18B");
        }
        return MAPPINGS_A.get(
                Sonic2Constants.MAP_UNC_OBJ18_A_ADDR, S2SpriteDataLoader::loadMappingFrames, "Obj18A");
    }

    @Override
    public void appendDebugRenderCommands(DebugRenderContext ctx) {
        int halfWidth = widthPixels;
        int halfHeight = routine == 8 ? yRadius : HALF_HEIGHT;
        int left = x - halfWidth;
        int right = x + halfWidth;
        int top = y - halfHeight;
        int bottom = y + halfHeight;

        ctx.drawLine(left, top, right, top, 0.35f, 0.7f, 1.0f);
        ctx.drawLine(right, top, right, bottom, 0.35f, 0.7f, 1.0f);
        ctx.drawLine(right, bottom, left, bottom, 0.35f, 0.7f, 1.0f);
        ctx.drawLine(left, bottom, left, top, 0.35f, 0.7f, 1.0f);
    }

    @Override
    public PerObjectRewindSnapshot captureRewindState() {
        return super.captureRewindState().withObjectSubclassExtra(
                new PerObjectRewindSnapshot.ArzPlatformRewindExtra(
                        x, y,
                        baseX, baseY, baseYFixed,
                        widthPixels, mappingFrame,
                        subtype, routine,
                        bobHelper.getAngle(),
                        angle, timer, yVel, yRadius));
    }

    @Override
    public void restoreRewindState(PerObjectRewindSnapshot snapshot) {
        super.restoreRewindState(snapshot);
        if (!(snapshot.objectSubclassExtra()
                instanceof PerObjectRewindSnapshot.ArzPlatformRewindExtra extra)) {
            return;
        }
        this.x = extra.x();
        this.y = extra.y();
        this.baseX = extra.baseX();
        this.baseY = extra.baseY();
        this.baseYFixed = extra.baseYFixed();
        this.widthPixels = extra.widthPixels();
        this.mappingFrame = extra.mappingFrame();
        this.subtype = extra.subtype();
        this.routine = extra.routine();
        this.bobHelper.restoreAngle(extra.bobAngle());
        this.angle = extra.angle();
        this.timer = extra.timer();
        this.yVel = extra.yVel();
        this.yRadius = extra.yRadius();
    }

}
