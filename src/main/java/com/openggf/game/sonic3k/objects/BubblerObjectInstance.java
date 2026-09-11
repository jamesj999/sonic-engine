package com.openggf.game.sonic3k.objects;

import com.openggf.game.PlayableEntity;
import com.openggf.game.GameRng;
import com.openggf.game.ShieldType;
import com.openggf.game.sonic3k.Sonic3kObjectArtKeys;
import com.openggf.game.sonic3k.audio.Sonic3kSfx;
import com.openggf.graphics.GLCommand;
import com.openggf.level.WaterSystem;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectPlayerParticipationPolicy;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.sprites.playable.AbstractPlayableSprite;

import java.util.List;

/**
 * Object 0x54 - Bubbler.
 * <p>
 * Dual-mode object:
 * - subtype bit 7 clear: rising bubble child
 * - subtype bit 7 set: floor bubbler that periodically spawns child bubbles
 * <p>
 * ROM: Obj_Bubbler (sonic3k.asm:64446-64736)
 */
public class BubblerObjectInstance extends AbstractObjectInstance implements RewindRecreatable {
    private static final int ROUTINE_INIT = 0;
    private static final int ROUTINE_ANIMATE = 2;
    private static final int ROUTINE_CHK_WATER = 4;
    private static final int ROUTINE_DISPLAY = 6;
    private static final int ROUTINE_DELETE = 8;
    private static final int ROUTINE_MAKER = 10;

    private static final int AF_ROUTINE = 0xFC;
    private static final int AF_END = 0xFF;

    private static final int[][] ANIM_SCRIPTS = {
            {0x0E, 0, 1, 2, AF_ROUTINE},
            {0x0E, 1, 2, 3, 4, AF_ROUTINE},
            {0x0E, 2, 3, 4, 5, 6, AF_ROUTINE},
            {0x02, 5, 6, AF_ROUTINE},
            {0x04, AF_ROUTINE},
            {0x04, AF_ROUTINE},
            {0x04, 7, 8, AF_ROUTINE},
            {0x04, 7, 8, AF_ROUTINE},
            {0x0F, 0x13, 0x14, 0x15, AF_END}
    };

    private static final int[] WOBBLE_DATA = {
            0, 0, 0, 0, 0, 0, 1, 1, 1, 1, 1, 2, 2, 2, 2, 2,
            2, 2, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3,
            3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 2,
            2, 2, 2, 2, 2, 2, 1, 1, 1, 1, 1, 0, 0, 0, 0, 0,
            0, -1, -1, -1, -1, -1, -2, -2, -2, -2, -2, -3, -3, -3, -3, -3,
            -3, -3, -4, -4, -4, -4, -4, -4, -4, -4, -4, -4, -4, -4, -4, -4,
            -4, -4, -4, -4, -4, -4, -4, -4, -4, -4, -4, -4, -4, -4, -4, -3,
            -3, -3, -3, -3, -3, -3, -2, -2, -2, -2, -2, -1, -1, -1, -1, -1
    };

    private static final int[] BUBBLE_TYPE_TABLE = {
            0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 1, 0, 0, 1, 0
    };

    private static final int BUBBLE_RISE_VELOCITY = -0x88;
    private static final int COLLISION_X_RANGE = 0x10;
    private static final int COLLISION_Y_RANGE = 0x10;
    private static final int ON_SCREEN_MARGIN = 0x10;
    private static final ObjectPlayerParticipationPolicy PLAYER_PARTICIPATION =
            ObjectPlayerParticipationPolicy.MAIN_PLUS_ENGINE_SIDEKICKS_AS_NATIVE_P2_EXTENDED;

    private int routine = ROUTINE_INIT;
    private boolean maker;

    private int originalX;
    private int originalY;

    private int x;
    private int y;
    private int posY16;
    private int wobbleAngle;

    private int animId;
    private int prevAnimId = -1;
    private int animFrameIndex;
    private int animTimer;
    private int mappingFrame;

    private boolean inhalable;
    private boolean consumed;

    private int spawnTimer;
    private int spawnTimerReset;
    private int typeCounter;
    private int productionFlags;
    private int delayCounter;
    private int typeTableOffset;
    private boolean makerVisible;

    public BubblerObjectInstance(ObjectSpawn spawn) {
        super(spawn, "Bubbler");
        this.maker = (spawn.subtype() & 0x80) != 0;
        this.originalX = spawn.x();
        this.originalY = spawn.y();
        this.x = spawn.x();
        this.y = spawn.y();
        this.posY16 = spawn.y() << 16;
    }

    @Override
    public BubblerObjectInstance recreateForRewind(RewindRecreateContext ctx) {
        return new BubblerObjectInstance(ctx.spawn());
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        boolean initializedThisFrame = routine == ROUTINE_INIT;
        if (routine == ROUTINE_INIT) {
            initialize();
        }

        AbstractPlayableSprite player = playerEntity instanceof AbstractPlayableSprite p ? p : null;
        switch (routine) {
            case ROUTINE_ANIMATE -> updateAnimate(player);
            case ROUTINE_CHK_WATER -> updateChkWater(player);
            case ROUTINE_DISPLAY -> updateDisplay();
            case ROUTINE_DELETE -> setDestroyed(true);
            case ROUTINE_MAKER -> updateMaker(initializedThisFrame);
            default -> { }
        }
    }

    private void initialize() {
        if (maker) {
            routine = ROUTINE_MAKER;
            int freq = spawn.subtype() & 0x7F;
            spawnTimerReset = freq;
            spawnTimer = freq;
            animId = 8;
            updateMakerVisibility();
            return;
        }

        routine = ROUTINE_ANIMATE;
        animId = spawn.subtype() & 0x7F;
        wobbleAngle = romRng().nextByte();
    }

    private void updateAnimate(AbstractPlayableSprite player) {
        animateSprite();
        if (mappingFrame == 6) {
            inhalable = true;
        }
        updateChkWater(player);
    }

    private void updateChkWater(AbstractPlayableSprite player) {
        if (waterLevelY() >= y) {
            startBurst();
            return;
        }

        int wobbleIndex = wobbleAngle & 0x7F;
        wobbleAngle = (wobbleAngle + 1) & 0xFF;
        x = originalX + WOBBLE_DATA[wobbleIndex];

        if (inhalable) {
            if (player != null && tryCollect(player)) {
                startBurst();
                return;
            }
            for (PlayableEntity participant : services().playerQuery().playersFor(PLAYER_PARTICIPATION)) {
                if (participant != player
                        && participant instanceof AbstractPlayableSprite sprite
                        && tryCollect(sprite)) {
                    startBurst();
                    return;
                }
            }
        }

        posY16 += BUBBLE_RISE_VELOCITY << 8;
        y = posY16 >> 16;

        if (!isOnScreen(ON_SCREEN_MARGIN)) {
            setDestroyed(true);
        }
    }

    private void updateDisplay() {
        animateSprite();
        if (!isOnScreen(ON_SCREEN_MARGIN)) {
            setDestroyed(true);
        }
    }

    private void updateMaker(boolean initializedThisFrame) {
        updateMakerVisibility();

        if (!isInRange()) {
            setDestroyed(true);
            return;
        }

        if (!makerVisible) {
            return;
        }

        if (productionFlags != 0) {
            delayCounter--;
            if (delayCounter >= 0) {
                animateSprite();
                return;
            }
        } else {
            // SetUp_ObjAttributes writes render_flags=$84 before the ROM branches
            // directly into loc_2FA50, so the initialization dispatch observes
            // the maker as on-screen until Render_Sprites refreshes the flag.
            if (!initializedThisFrame && !isOnScreen()) {
                return;
            }

            delayCounter--;
            if (delayCounter >= 0) {
                animateSprite();
                return;
            }

            productionFlags = 1;

            int rng;
            do {
                rng = romRng().nextWord();
            } while ((rng & 7) >= 6);

            typeCounter = rng & 7;
            typeTableOffset = rng & 0x0C;

            spawnTimer--;
            if (spawnTimer < 0) {
                spawnTimer = spawnTimerReset;
                productionFlags |= 0x80;
            }
        }

        spawnBubbleChild();

        typeCounter--;
        if (typeCounter < 0) {
            delayCounter += (romRng().nextWord() & 0x7F) + 0x80;
            productionFlags = 0;
        }

        animateSprite();
    }

    private void updateMakerVisibility() {
        makerVisible = waterLevelY() < originalY;
    }

    private void spawnBubbleChild() {
        GameRng rng = romRng();
        delayCounter = rng.nextWord() & 0x1F;

        int tableIndex = typeTableOffset + typeCounter;
        int bubbleSubtype = (tableIndex >= 0 && tableIndex < BUBBLE_TYPE_TABLE.length)
                ? BUBBLE_TYPE_TABLE[tableIndex]
                : 0;

        if ((productionFlags & 0x80) != 0) {
            if (((rng.nextWord() & 0x03) == 0 || typeCounter == 0) && (productionFlags & 0x40) == 0) {
                productionFlags |= 0x40;
                bubbleSubtype = 2;
            }
        } else if (typeCounter == 0 && (productionFlags & 0x40) == 0) {
            productionFlags |= 0x40;
            bubbleSubtype = 2;
        }

        int spawnX = originalX + (rng.nextWord() & 0x0F) - 8;
        final int childSubtype = bubbleSubtype;
        spawnChild(() -> new BubblerObjectInstance(
                new ObjectSpawn(spawnX, originalY, spawn.objectId(), childSubtype, 0, false, 0)));
    }

    private boolean tryCollect(AbstractPlayableSprite player) {
        if (consumed || !player.isInWater() || player.isObjectControlled()) {
            return false;
        }
        if (player.hasShield() && player.getShieldType() == ShieldType.BUBBLE) {
            return false;
        }

        int playerX = player.getCentreX();
        int playerY = player.getCentreY();

        if (playerX <= x - COLLISION_X_RANGE || playerX > x + COLLISION_X_RANGE) {
            return false;
        }
        if (playerY <= y || playerY > y + COLLISION_Y_RANGE) {
            return false;
        }

        if ("sonic".equalsIgnoreCase(player.getCode())) {
            player.replenishAir();
        } else {
            player.replenishAirPreservingRollingStatus();
        }
        services().playSfx(Sonic3kSfx.BUBBLE.id);
        consumed = true;
        inhalable = false;
        return true;
    }

    private void startBurst() {
        if (routine == ROUTINE_DISPLAY || routine == ROUTINE_DELETE) {
            return;
        }
        routine = ROUTINE_DISPLAY;
        animId = Math.min(animId + 4, ANIM_SCRIPTS.length - 1);
        prevAnimId = -1;
        animateSprite();
    }

    private void animateSprite() {
        if (animId < 0 || animId >= ANIM_SCRIPTS.length) {
            return;
        }

        int[] script = ANIM_SCRIPTS[animId];
        int duration = script[0];

        if (animId != prevAnimId) {
            prevAnimId = animId;
            animFrameIndex = 0;
            animTimer = 0;
        }

        animTimer--;
        if (animTimer >= 0) {
            return;
        }

        animTimer = duration;
        int frameIndex = 1 + animFrameIndex;
        if (frameIndex >= script.length) {
            return;
        }

        int frame = script[frameIndex];
        if (frame == AF_ROUTINE) {
            routine += 2;
            return;
        }
        if (frame == AF_END) {
            animFrameIndex = 0;
            if (script.length > 1 && script[1] < 0xFC) {
                mappingFrame = script[1];
            }
            return;
        }

        mappingFrame = frame;
        animFrameIndex++;
    }

    private int waterLevelY() {
        WaterSystem waterSystem = services().waterSystem();
        if (waterSystem == null) {
            return Integer.MAX_VALUE;
        }
        int zoneId = services().featureZoneId();
        int actId = services().featureActId();
        if (!waterSystem.hasWater(zoneId, actId)) {
            return Integer.MAX_VALUE;
        }
        return waterSystem.getWaterLevelY(zoneId, actId);
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        if (isDestroyed()) {
            return;
        }
        if (maker && !makerVisible) {
            return;
        }

        PatternSpriteRenderer renderer = getRenderer(Sonic3kObjectArtKeys.BUBBLER);
        if (renderer == null) {
            return;
        }
        if (mappingFrame < 0 || mappingFrame >= 22) {
            return;
        }

        renderer.drawFrameIndex(mappingFrame, x, y, false, false);
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
        return 1;
    }

    /**
     * {@code Obj_Bubbler} writes {@code width_pixels=$10} but leaves
     * {@code height_pixels} at the cleared SST value (sonic3k.asm:64491-64498).
     * Its rising-child and maker paths both use the resulting zero-height
     * Render_Sprites bound as the off-screen delete gate.
     */
    @Override
    public int getOnScreenHalfHeight() {
        return 0;
    }

    int getWobbleAngleForTest() {
        return wobbleAngle;
    }

    private GameRng romRng() {
        return services().rng();
    }
}
