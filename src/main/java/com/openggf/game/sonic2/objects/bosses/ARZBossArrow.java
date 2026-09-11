package com.openggf.game.sonic2.objects.bosses;

import com.openggf.game.PlayableEntity;
import com.openggf.game.sonic2.audio.Sonic2Sfx;
import com.openggf.game.sonic2.Sonic2ObjectArtKeys;
import com.openggf.graphics.GLCommand;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.ObjectPlayerParticipationPolicy;
import com.openggf.level.objects.ObjectRenderManager;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.ObjectServices;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.objects.SolidContact;
import com.openggf.level.objects.SolidObjectListener;
import com.openggf.level.objects.SolidObjectParams;
import com.openggf.level.objects.SolidObjectProvider;
import com.openggf.level.objects.TouchResponseProvider;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.sprites.playable.AbstractPlayableSprite;

import java.util.List;

/**
 * ARZ Boss Arrow - Projectile that becomes a platform.
 * ROM Reference: s2.asm Obj89 (subtype 6)
 *
 * States:
 * - INIT: Initialize arrow position/velocity
 * - FLYING: Arrow flies toward target pillar
 * - STUCK: Arrow stuck in pillar, acts as platform
 * - FALLING: Arrow falls when stood on too long or boss defeated
 */
public class ARZBossArrow extends AbstractObjectInstance
        implements SolidObjectProvider, SolidObjectListener, TouchResponseProvider, RewindRecreatable {

    private static final int ARROW_SUB_INIT = 0;
    private static final int ARROW_SUB_FLYING = 2;
    private static final int ARROW_SUB_STUCK = 4;
    private static final int ARROW_SUB_FALLING = 6;

    private static final int LEFT_ARROW_STOP_X = 0x2A77;
    private static final int RIGHT_ARROW_STOP_X = 0x2B49;
    private static final int ARROW_FLOOR_Y = 0x4F0;
    private static final int ARROW_COLLISION_FLAGS = 0xB0;

    private static final SolidObjectParams ARROW_SOLID_PARAMS = new SolidObjectParams(0x1B, 1, 2);

    private static final int[][] ARZ_ARROW_ANIMS = {
            { 1, 4, 6, 5, 4, 6, 4, 5, 4, 6, 4, 4, 6, 5, 4, 6, 4, 5, 4, 6, 4, 0xFD, 1 },
            { 0x0F, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4,
                    4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 0xF9 }
    };
    private Sonic2ARZBossInstance mainBoss;
    private transient ARZBossEyes eyes;
    private boolean fromRightPillar;

    private int x;
    private int y;
    private int renderFlags;
    private int routineState;
    private int mappingFrame;
    private int collisionFlags;
    private int xVel;
    private int yVel;
    private int arrowTimer;
    private boolean timerExpiredThisFrame;
    private boolean dropPlayersProcessed;
    private boolean dropPushClearProcessed;

    // Animation state
    private int arrowAnim;
    private int arrowAnimLast = -1;
    private int arrowAnimFrame;
    private int arrowAnimTimer;

    public ARZBossArrow(ObjectSpawn spawn, Sonic2ARZBossInstance mainBoss, ARZBossEyes eyes, boolean fromRightPillar) {
        super(spawn, "ARZ Boss Arrow");
        this.mainBoss = mainBoss;
        this.eyes = eyes;
        this.fromRightPillar = fromRightPillar;
        this.x = spawn.x();
        this.y = spawn.y();
        this.renderFlags = spawn.renderFlags();
        this.routineState = ARROW_SUB_INIT;
        this.mappingFrame = 4;
        this.collisionFlags = 0;
        this.arrowAnim = 0;
        this.arrowTimer = 0;
        this.dropPlayersProcessed = false;
        this.dropPushClearProcessed = false;
    }

    ARZBossArrow(ObjectSpawn spawn) {
        this(spawn, null, null, (spawn.renderFlags() & 1) != 0);
    }

    @Override
    public ARZBossArrow recreateForRewind(RewindRecreateContext ctx) {
        if (ctx == null || ctx.spawn() == null || ctx.objectServices() == null) {
            return null;
        }
        ObjectServices services = ctx.objectServices();
        ObjectManager objectManager = services.objectManager();
        if (objectManager == null) {
            return null;
        }

        Sonic2ARZBossInstance nearestBoss = null;
        ARZBossEyes nearestEyes = null;
        long nearestBossDistance = Long.MAX_VALUE;
        long nearestEyesDistance = Long.MAX_VALUE;
        int targetX = ctx.spawn().x();
        int targetY = ctx.spawn().y();
        for (ObjectInstance object : objectManager.getActiveObjects()) {
            if (object == null || object.isDestroyed()) {
                continue;
            }
            long distance = squaredDistance(object.getX(), object.getY(), targetX, targetY);
            if (object instanceof Sonic2ARZBossInstance boss && distance < nearestBossDistance) {
                nearestBoss = boss;
                nearestBossDistance = distance;
            } else if (object instanceof ARZBossEyes eye && distance < nearestEyesDistance) {
                nearestEyes = eye;
                nearestEyesDistance = distance;
            }
        }
        if (nearestBoss == null) {
            return null;
        }
        boolean derivedFromRightPillar = (ctx.spawn().renderFlags() & 1) != 0;
        return new ARZBossArrow(ctx.spawn(), nearestBoss, nearestEyes, derivedFromRightPillar);
    }

    private static long squaredDistance(int x, int y, int targetX, int targetY) {
        long dx = (long) x - targetX;
        long dy = (long) y - targetY;
        return dx * dx + dy * dy;
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        if (isDestroyed()) {
            return;
        }
        timerExpiredThisFrame = false;

        // Check if boss defeated
        if (mainBoss != null && mainBoss.isInDefeatSequence()) {
            routineState = ARROW_SUB_FALLING;
        }

        switch (routineState) {
            case ARROW_SUB_INIT -> initArrow();
            case ARROW_SUB_FLYING -> updateArrowFlying();
            case ARROW_SUB_STUCK -> updateArrowStuck();
            case ARROW_SUB_FALLING -> updateArrowFalling(player);
        }
    }

    private void initArrow() {
        routineState = ARROW_SUB_FLYING;
        collisionFlags = ARROW_COLLISION_FLAGS;
        mappingFrame = 4;
        yVel = 4;

        if (eyes != null) {
            x = eyes.getX();
            y = eyes.getY();
            eyes = null;
        }
        y += 9;

        if (fromRightPillar) {
            renderFlags |= 1;
            xVel = -3;
        } else {
            xVel = 3;
        }
    }

    private void updateArrowFlying() {
        int nextX = x + xVel;
        if (xVel < 0) {
            if (nextX <= LEFT_ARROW_STOP_X) {
                x = LEFT_ARROW_STOP_X;
                routineState = ARROW_SUB_STUCK;
                services().playSfx(Sonic2Sfx.ARROW_STICK.id);
            } else {
                x = nextX;
            }
        } else {
            if (nextX >= RIGHT_ARROW_STOP_X) {
                x = RIGHT_ARROW_STOP_X;
                routineState = ARROW_SUB_STUCK;
                services().playSfx(Sonic2Sfx.ARROW_STICK.id);
            } else {
                x = nextX;
            }
        }
    }

    private void updateArrowStuck() {
        collisionFlags = 0;
        updateArrowPlatform();
        animateArrow();
        if (mappingFrame == 0) {
            mappingFrame = 4;
        }
    }

    private void updateArrowFalling(AbstractPlayableSprite player) {
        if (!dropPlayersProcessed) {
            dropPlayers(player, false);
            dropPlayersProcessed = true;
        } else if (!dropPushClearProcessed) {
            dropPlayers(player, true);
            dropPushClearProcessed = true;
        }
        int nextY = y + yVel;
        if (nextY > ARROW_FLOOR_Y) {
            setDestroyed(true);
            return;
        }
        y = nextY;
    }

    private void updateArrowPlatform() {
        if (arrowTimer == 0) {
            return;
        }
        arrowTimer--;
        if (arrowTimer == 0) {
            timerExpiredThisFrame = true;
            routineState = ARROW_SUB_FALLING;
        }
    }

    private void dropPlayers(AbstractPlayableSprite player, boolean clearPush) {
        if (services().objectManager() == null) {
            return;
        }
        List<PlayableEntity> participants = services().playerQuery().playersFor(
                ObjectPlayerParticipationPolicy.ALL_ENGINE_PLAYERS);
        if (player != null && !participants.contains(player)) {
            dropPlayerIfLatched(player, clearPush);
        }
        for (PlayableEntity participant : participants) {
            if (participant instanceof AbstractPlayableSprite sprite) {
                dropPlayerIfLatched(sprite, clearPush);
            }
        }
    }

    private void dropPlayerIfLatched(AbstractPlayableSprite player, boolean clearPush) {
        if (player == null) {
            return;
        }
        ObjectManager objectManager = services().objectManager();
        boolean ridingThisArrow = objectManager.isRidingObject(player, this);
        boolean interactStillNamesThisArrow = player.getInteractSlotIndex() == getSlotIndex();
        if (!ridingThisArrow && !interactStillNamesThisArrow) {
            return;
        }
        if (ridingThisArrow) {
            objectManager.clearRidingObject(player);
        }
        player.setOnObject(false);
        player.setAir(true);
        // Obj89_Arrow_ChkDropPlayers clears both p1/p2 standing latches once.
        // The following Tails_Animate pass clears Status_Push one sampled frame later.
        // docs/s2disasm/s2.asm:65689-65704, 41272-41279
        if (clearPush) {
            player.setPushing(false);
        }
    }

    private void animateArrow() {
        int[] script = ARZ_ARROW_ANIMS[arrowAnim];
        int delay = script[0] & 0xFF;
        if (arrowAnim != arrowAnimLast) {
            arrowAnimFrame = 0;
            arrowAnimTimer = delay;
            arrowAnimLast = arrowAnim;
        }
        arrowAnimTimer--;
        if (arrowAnimTimer >= 0) {
            return;
        }
        arrowAnimTimer = delay;
        int frameValue = script[1 + arrowAnimFrame] & 0xFF;
        if ((frameValue & 0x80) != 0) {
            handleArrowCommand(frameValue, script);
            return;
        }
        mappingFrame = frameValue & 0x7F;
        arrowAnimFrame++;
    }

    private void handleArrowCommand(int command, int[] script) {
        if (command == 0xFF) {
            arrowAnimFrame = 0;
            mappingFrame = script[1] & 0x7F;
            arrowAnimFrame++;
            return;
        }
        if (command == 0xFD) {
            int nextAnim = script[2 + arrowAnimFrame] & 0xFF;
            arrowAnim = nextAnim;
            arrowAnimLast = -1;
            return;
        }
        if (command == 0xF9) {
            routineState = ARROW_SUB_FALLING;
        }
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        ObjectRenderManager renderManager = services().renderManager();
        if (renderManager == null) {
            return;
        }

        PatternSpriteRenderer renderer = renderManager.getRenderer(Sonic2ObjectArtKeys.ARZ_BOSS_PARTS);
        if (renderer == null || !renderer.isReady()) {
            return;
        }

        boolean hFlip = (renderFlags & 1) != 0;
        renderer.drawFrameIndex(mappingFrame, x, y, hFlip, false);
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
        return 4;
    }

    @Override
    public SolidObjectParams getSolidParams() {
        return ARROW_SOLID_PARAMS;
    }

    @Override
    public int getBalanceWidthPixels() {
        // Obj89's arrow initializer never writes width_pixels after
        // AllocateObject clears its SST slot (s2.asm:65565-65591). Sonic's
        // object-edge balance probe therefore compares x_pos against the
        // arrow's centre with a native width of zero, independently of the
        // wider SolidObject collision dimensions above.
        return 0;
    }

    @Override
    public boolean isSolidFor(PlayableEntity playerEntity) {
        // ROM Obj89_Arrow_Platform only calls PlatformObject while
        // obj89_arrow_timer is zero. Once a rider starts the timer, later
        // decay frames skip fresh platform contact entirely.
        // docs/s2disasm/s2.asm:65658-65683
        return routineState == ARROW_SUB_STUCK && arrowTimer == 0;
    }

    @Override
    public boolean isTopSolidOnly() {
        return routineState == ARROW_SUB_STUCK;
    }

    @Override
    public boolean usesCollisionHalfWidthForTopLanding() {
        // Obj89_Arrow_Platform passes d1=$1B directly to PlatformObject.
        // That is already the standable top half-width, not a full-solid
        // obActWid+$B width that needs the generic landing narrowing.
        // docs/s2disasm/s2.asm:65658-65665
        return true;
    }

    @Override
    public boolean usesGroundHalfHeightForTopSolidContact() {
        // Obj89_Arrow_Platform passes d3=2 to PlatformObject; S2's
        // PlatformObject_ChkYRange uses d3 as the landing surface height.
        // Using d2=1 misses the f5928 Tails landing by one pixel.
        // docs/s2disasm/s2.asm:65658-65665
        return true;
    }

    @Override
    public boolean suppressSlopeSampleThisFrame(PlayableEntity player) {
        // Despite the generic hook name, this is the flat PlatformObject
        // equivalent: timer-decay frames skip MvSonicOnPtfm, but the player
        // remains attached until Obj89_Arrow_Sub6 explicitly drops riders.
        // docs/s2disasm/s2.asm:65658-65702
        return (routineState == ARROW_SUB_STUCK && arrowTimer > 0) || timerExpiredThisFrame;
    }

    @Override
    public boolean preservesRidingPushStatus(PlayableEntity player) {
        // During Obj89_Arrow_Platform_Decay the shipped ROM skips PlatformObject,
        // so non-rolling CPU Tails keeps the prior side-push state while the
        // arrow remains latched. Tails_Animate can still clear Status_Push when
        // the sidekick switches to the rolling/jump animation before this later
        // object pass samples the arrow.
        // docs/s2disasm/s2.asm:41272-41279,65658-65702
        return player instanceof AbstractPlayableSprite sprite
                && sprite.isCpuControlled()
                && !sprite.getRolling()
                && ((routineState == ARROW_SUB_STUCK && arrowTimer > 0) || timerExpiredThisFrame);
    }

    @Override
    public boolean preservesMovingSidekickCpuPushAtZeroGraceFromInteractSlot(PlayableEntity player) {
        return preservesReleasedSidekickPushForCpu(player);
    }

    @Override
    public boolean publishesSidekickCpuPushFromInteractSlot(PlayableEntity player) {
        // TailsCPU_Normal reads the live Status_Push bit through the sidekick's
        // interact slot before its movement/animation dispatch. Obj89's drop
        // path leaves that bit set, so publish the same semantic predicate used
        // by the zero-grace auto-jump bridge.
        // docs/s2disasm/s2.asm:39297-39300,40484-40491,65689-65704
        return preservesReleasedSidekickPushForCpu(player);
    }

    private boolean preservesReleasedSidekickPushForCpu(PlayableEntity player) {
        // Obj89_Arrow_ChkDropPlayers only sets InAir and clears OnObject on the
        // player; it does not clear Status_Push. At ARZ2 f6364 the ordinary
        // engine grace counter has already reached zero, but TailsCPU_Normal can
        // still read the ROM-visible push bit through the sidekick's persistent
        // interact slot and take the push-bypass auto-jump path.
        // docs/s2disasm/s2.asm:39297-39300,65689-65704.
        if (!(player instanceof AbstractPlayableSprite sprite)
                || !sprite.isCpuControlled()
                || sprite.getAir()
                || sprite.isOnObject()
                || sprite.getRolling()) {
            return false;
        }
        return getSlotIndex() >= 0 && sprite.getInteractSlotIndex() == getSlotIndex();
    }

    @Override
    public void onSolidContact(PlayableEntity playerEntity, SolidContact contact, int frameCounter) {
        if (routineState != ARROW_SUB_STUCK) {
            return;
        }
        if (!contact.standing()) {
            return;
        }
        if (arrowTimer == 0) {
            // The ROM writes #$1F and immediately falls through to
            // Obj89_Arrow_Platform_Decay in the same object call. This
            // callback runs after object update, so store the already
            // decremented post-call value. CPU Tails does not start the timer
            // in the shipped ROM: the p2-standing timer write lives under the
            // disabled fixBugs block, while p1-standing still writes #$1F.
            // docs/s2disasm/s2.asm:65658-65683
            if (!playerEntity.isCpuControlled()) {
                arrowTimer = 0x1E;
            }
        }
    }

    @Override
    public int getCollisionFlags() {
        return collisionFlags;
    }

    @Override
    public int getCollisionProperty() {
        return 0;
    }

    @Override
    public ObjectSpawn getSpawn() {
        return new ObjectSpawn(x, y, spawn.objectId(), spawn.subtype(), renderFlags, spawn.respawnTracked(), spawn.rawYWord());
    }
}
