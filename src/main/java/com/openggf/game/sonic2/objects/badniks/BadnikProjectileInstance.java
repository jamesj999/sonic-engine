package com.openggf.game.sonic2.objects.badniks;

import com.openggf.game.sonic2.Sonic2ObjectArtKeys;
import com.openggf.game.PlayableEntity;
import com.openggf.graphics.GLCommand;
import com.openggf.graphics.RenderPriority;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectRenderManager;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.PerObjectRewindSnapshot;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.objects.SubpixelMotion;
import com.openggf.level.objects.TouchResponseProvider;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.sprites.playable.AbstractPlayableSprite;

import java.util.List;

/**
 * Projectile fired by Badniks (Buzzer stinger, Coconuts coconut).
 * Moves with configurable velocity and optional gravity.
 */
public class BadnikProjectileInstance extends AbstractObjectInstance
        implements TouchResponseProvider, RewindRecreatable {

    public enum ProjectileType {
        BUZZER_STINGER,
        COCONUT,
        SPINY_SPIKE,
        REXON_FIREBALL,
        OCTUS_BULLET,
        AQUIS_BULLET,
        ASTERON_SPIKE,
        TURTLOID_SHOT,
        NEBULA_BOMB,
        CLUCKER_SHOT
    }

    private static final int COLLISION_SIZE_STINGER = 0x18; // From disassembly $98 & 0x3F
    private static final int COLLISION_SIZE_COCONUT = 0x0B; // From disassembly $8B & 0x3F
    // CPZ Spiny shot is Obj98 with subtype $34 -> ObjA6_SubObjData, whose
    // subObjData collision byte is $98 (s2.asm:74659-74660, 76491). $98 & 0x3F =
    // 0x18 -> Touch_Sizes index 0x18 = 4x4 (s2.asm:85078). It is NOT the coconut
    // 0x8B/0x0B (8x8) size; the earlier "same as coconut" value made the spike
    // touchbox twice too tall/wide and produced phantom hits the ROM never has.
    private static final int COLLISION_SIZE_SPINY_SPIKE = 0x18; // ObjA6_SubObjData: $98 & 0x3F
    private static final int COLLISION_SIZE_REXON_FIREBALL = 0x18; // From disassembly $98 & 0x3F
    private static final int COLLISION_SIZE_OCTUS_BULLET = 0x18; // From disassembly $98 & 0x3F
    private static final int COLLISION_SIZE_AQUIS_BULLET = 0x18; // From disassembly $98 & 0x3F
    private static final int COLLISION_SIZE_ASTERON_SPIKE = 0x18; // From ObjA4_SubObjData2: collision_flags=$98
    private static final int COLLISION_SIZE_NEBULA_BOMB = 0x0B; // From Obj99_SubObjData: collision_flags=$8B
    private static final int COLLISION_SIZE_CLUCKER_SHOT = 0x18; // From ObjAD_SubObjData3: collision_flags=$98
    private static final int[] AQUIS_BULLET_FRAMES = {5, 6, 7, 6};
    private static final int GRAVITY_COCONUT = 0x20; // Obj98_CoconutFall
    private static final int GRAVITY_SPINY_SPIKE = 0x20; // From disassembly +$20 per frame
    private static final int GRAVITY_REXON_FIREBALL = 0x80; // From disassembly $80 per frame
    private static final boolean ASTERON_HIGH_PRIORITY_SPRITE = true;

    private ProjectileType type;
    private int currentX;
    private int currentY;
    private int xVelocity; // In subpixels (8.8 fixed point)
    private int yVelocity; // In subpixels (8.8 fixed point)
    private final SubpixelMotion.State motionState; // Subpixel position/velocity state
    private boolean applyGravity;
    private int gravity;
    private int collisionSizeIndex;
    private int animFrame;
    private int aquisBulletAnimTimer;
    private int aquisBulletAnimIndex;
    private boolean hFlip;
    private int initialDelay; // Frames to wait before moving (Octus bullet: 0x0F)
    private int fixedFrame = -1; // Fixed mapping frame override (Asteron spikes use different frames per projectile)
    private boolean paletteBlink; // Toggles every frame for Nebula bomb (ROM: bchg palette_bit_0)
    private int cluckerAnimTimer; // Clucker shot animation timer (counts down from duration)
    private int cluckerAnimIndex; // Clucker shot animation index (0-7, cycles through 8 frames)
    private boolean loadSubObjectInitPending;

    private BadnikProjectileInstance(ObjectSpawn spawn) {
        this(spawn, ProjectileType.BUZZER_STINGER, spawn.x(), spawn.y(), 0, 0, false, false);
    }

    /**
     * Create a new projectile.
     * 
     * @param spawn   Original spawn data
     * @param type    Type of projectile (determines graphics)
     * @param x       Starting X position
     * @param y       Starting Y position
     * @param xVel    X velocity in subpixels (positive = right)
     * @param yVel    Y velocity in subpixels (positive = down)
     * @param gravity Whether to apply gravity
     * @param hFlip   Horizontal flip for sprite
     */
    public BadnikProjectileInstance(ObjectSpawn spawn, ProjectileType type,
            int x, int y, int xVel, int yVel, boolean gravity, boolean hFlip) {
        super(spawn, "Projectile");
        this.type = type;
        this.currentX = x;
        this.currentY = y;
        this.xVelocity = xVel;
        this.yVelocity = yVel;
        this.motionState = new SubpixelMotion.State(x, y, 0, 0, xVel, yVel);
        this.applyGravity = gravity;
        this.animFrame = 0;
        this.hFlip = hFlip;
        switch (type) {
            case BUZZER_STINGER -> {
                this.gravity = 0;
                this.collisionSizeIndex = COLLISION_SIZE_STINGER;
            }
            case COCONUT -> {
                this.gravity = GRAVITY_COCONUT;
                this.collisionSizeIndex = COLLISION_SIZE_COCONUT;
            }
            case SPINY_SPIKE -> {
                this.gravity = GRAVITY_SPINY_SPIKE;
                this.collisionSizeIndex = COLLISION_SIZE_SPINY_SPIKE;
            }
            case REXON_FIREBALL -> {
                this.gravity = GRAVITY_REXON_FIREBALL;
                this.collisionSizeIndex = COLLISION_SIZE_REXON_FIREBALL;
            }
            case OCTUS_BULLET -> {
                this.gravity = 0;
                this.collisionSizeIndex = COLLISION_SIZE_OCTUS_BULLET;
            }
            case AQUIS_BULLET -> {
                this.gravity = 0;
                this.collisionSizeIndex = COLLISION_SIZE_AQUIS_BULLET;
                this.animFrame = 0;
                this.aquisBulletAnimTimer = 3;
            }
            case ASTERON_SPIKE -> {
                this.gravity = 0;
                this.collisionSizeIndex = COLLISION_SIZE_ASTERON_SPIKE;
            }
            case TURTLOID_SHOT -> {
                // Obj9A_SubObjData2: collision_flags=$98 -> HURT (0x80) + size 0x18
                this.gravity = 0;
                this.collisionSizeIndex = COLLISION_SIZE_STINGER; // 0x18
            }
            case NEBULA_BOMB -> {
                // Obj99_SubObjData: collision_flags=$8B -> HURT (0x80) + size 0x0B
                // Obj98_NebulaBombFall: falls with ObjectMoveAndFall (gravity from engine)
                this.gravity = 0x38; // Standard ObjectMoveAndFall gravity
                this.collisionSizeIndex = COLLISION_SIZE_NEBULA_BOMB;
            }
            case CLUCKER_SHOT -> {
                // ObjAD_SubObjData3: collision_flags=$98 -> HURT (0x80) + size 0x18
                // Obj98_CluckerShotMove: moves with ObjectMove (no gravity)
                this.gravity = 0;
                this.collisionSizeIndex = COLLISION_SIZE_CLUCKER_SHOT;
            }
        }
    }

    /**
     * Create a new projectile with an initial stationary delay.
     * While delay >= 0, the projectile does not move.
     */
    public BadnikProjectileInstance(ObjectSpawn spawn, ProjectileType type,
            int x, int y, int xVel, int yVel, boolean gravity, boolean hFlip, int initialDelay) {
        this(spawn, type, x, y, xVel, yVel, gravity, hFlip);
        this.initialDelay = initialDelay;
    }

    /**
     * Create a new projectile with a fixed mapping frame (e.g., Asteron spikes use
     * different frames per projectile direction).
     */
    public BadnikProjectileInstance(ObjectSpawn spawn, ProjectileType type,
            int x, int y, int xVel, int yVel, boolean gravity, boolean hFlip, int initialDelay,
            int fixedFrame) {
        this(spawn, type, x, y, xVel, yVel, gravity, hFlip, initialDelay);
        this.fixedFrame = fixedFrame;
    }

    @Override
    public AbstractObjectInstance recreateForRewind(RewindRecreateContext ctx) {
        ObjectSpawn spawn = ctx.spawn();
        if (ctx.state().objectSubclassExtra()
                instanceof PerObjectRewindSnapshot.BadnikProjectileRewindExtra extra) {
            return new BadnikProjectileInstance(
                    spawn,
                    ProjectileType.valueOf(extra.projectileType()),
                    extra.currentX(),
                    extra.currentY(),
                    extra.xVelocity(),
                    extra.yVelocity(),
                    extra.applyGravity(),
                    extra.hFlip(),
                    extra.initialDelay(),
                    extra.fixedFrame());
        }
        // Synthetic generic-recreate probes may not carry subclass extras.
        return new BadnikProjectileInstance(spawn);
    }

    /**
     * ROM Obj98 first runs routine 0, which only calls LoadSubObject and then
     * advances to routine 2. Children allocated after the current object can
     * execute that init routine in the same ExecuteObjects pass, before their
     * first movement frame.
     */
    public void deferFirstMovementForLoadSubObjectInit() {
        this.loadSubObjectInitPending = true;
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        if (loadSubObjectInitPending) {
            loadSubObjectInitPending = false;
            return;
        }
        boolean usesRomRangeUnload = type == ProjectileType.BUZZER_STINGER
                || type == ProjectileType.OCTUS_BULLET
                || type == ProjectileType.AQUIS_BULLET;
        // Initial delay: projectile stays stationary (Octus bullet: 16 frames)
        if (initialDelay > 0) {
            initialDelay--;
            animFrame = ((vIntRunCount >> 2) & 1);
            return;
        }

        // Apply gravity if enabled
        if (applyGravity) {
            yVelocity += gravity;
        }

        // Update position using 16.8 fixed-point (matches ObjectMove in s2.asm:29969-29982)
        motionState.x = currentX;
        motionState.y = currentY;
        motionState.xVel = xVelocity;
        motionState.yVel = yVelocity;
        SubpixelMotion.moveSprite2(motionState);
        currentX = motionState.x;
        currentY = motionState.y;

        if (!usesRomRangeUnload && !isOnScreen(projectileScreenMargin())) {
            setDestroyed(true);
        }

        // Animation cycling
        if (type == ProjectileType.AQUIS_BULLET) {
            // Ani_obj50_Bullet: dc.b 3, 5, 6, 7, 6, $FF.
            aquisBulletAnimTimer--;
            if (aquisBulletAnimTimer < 0) {
                aquisBulletAnimTimer = 3;
                animFrame = (animFrame + 1) & 3;
            }
            aquisBulletAnimIndex = animFrame;
        } else if (type == ProjectileType.CLUCKER_SHOT) {
            // Ani_CluckerShot: duration 3, frames $D-$14, end $FF (loop)
            // 8 frames total, each held for 4 game frames (duration 3 = 3+1 ticks)
            cluckerAnimTimer--;
            if (cluckerAnimTimer < 0) {
                cluckerAnimTimer = 3; // dc.b 3 = duration
                cluckerAnimIndex++;
                if (cluckerAnimIndex >= 8) {
                    // $FF end action = loop (restart animation from beginning)
                    cluckerAnimIndex = 0;
                }
            }
            animFrame = cluckerAnimIndex;
        } else {
            animFrame = ((vIntRunCount >> 2) & 1);
        }

        // Nebula bomb: toggle palette every frame (ROM: bchg #palette_bit_0)
        if (type == ProjectileType.NEBULA_BOMB) {
            paletteBlink = !paletteBlink;
        }
    }

    private int projectileScreenMargin() {
        if (type == ProjectileType.ASTERON_SPIKE) {
            if (xVelocity == 0 && yVelocity < 0) {
                return 32 + Math.max(1, Math.abs(yVelocity >> 8));
            }
            if (xVelocity > 0) {
                return 4;
            }
            if (xVelocity < 0 && yVelocity > 0) {
                return 32 + Math.max(1, Math.abs(xVelocity >> 8));
            }
        }
        return 32;
    }

    @Override
    public int getCollisionFlags() {
        if (loadSubObjectInitPending) {
            return 0;
        }
        // HURT category (0x80) + size index
        return 0x80 | (collisionSizeIndex & 0x3F);
    }

    @Override
    public int getCollisionProperty() {
        return 0;
    }

    @Override
    public ObjectSpawn getSpawn() {
        // Return dynamic spawn with current position
        return buildSpawnAt(currentX, currentY);
    }

    @Override
    public int getX() {
        return currentX;
    }

    @Override
    public int getY() {
        return currentY;
    }

    @Override
    public int getPriorityBucket() {
        // ObjAD_SubObjData3: Clucker shot priority = 5; other projectiles default to 4
        if (type == ProjectileType.CLUCKER_SHOT) {
            return RenderPriority.clamp(5);
        }
        if (type == ProjectileType.AQUIS_BULLET) {
            return RenderPriority.clamp(3);
        }
        return RenderPriority.clamp(4);
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        ObjectRenderManager renderManager = services().renderManager();
        if (renderManager == null) {
            return;
        }

        PatternSpriteRenderer renderer;
        int frame;
        int paletteOverride = -1; // -1 = use sprite sheet default
        boolean forceHighPriority = false;

        switch (type) {
            case BUZZER_STINGER:
                renderer = renderManager.getRenderer(Sonic2ObjectArtKeys.BUZZER);
                // Buzzer projectile uses frames 5-6 (animation 2 in disassembly)
                frame = 5 + animFrame;
                break;
            case COCONUT:
                renderer = renderManager.getRenderer(Sonic2ObjectArtKeys.COCONUTS);
                // Coconut uses frame 3
                frame = 3;
                break;
            case SPINY_SPIKE:
                renderer = renderManager.getRenderer(Sonic2ObjectArtKeys.SPINY);
                // Spiny spike uses frames 6-7 (alternating)
                frame = 6 + animFrame;
                break;
            case REXON_FIREBALL:
                renderer = renderManager.getRenderer(Sonic2ObjectArtKeys.REXON);
                // Rexon fireball uses frame 3 (1x1 tile)
                // Fireball uses palette line 1 (Obj94_SubObjData2: make_art_tile(ArtTile_ArtNem_Rexon,1,0))
                // while head/body use palette line 3 (the sheet default)
                frame = 3;
                paletteOverride = 1;
                break;
            case OCTUS_BULLET:
                renderer = renderManager.getRenderer(Sonic2ObjectArtKeys.OCTUS);
                // Octus bullet uses frames 5-6 (animation 2: dc.b 2, 5, 6, $FF)
                frame = 5 + animFrame;
                break;
            case AQUIS_BULLET:
                renderer = renderManager.getRenderer(Sonic2ObjectArtKeys.AQUIS);
                frame = AQUIS_BULLET_FRAMES[animFrame % AQUIS_BULLET_FRAMES.length];
                break;
            case ASTERON_SPIKE:
                renderer = renderManager.getRenderer(Sonic2ObjectArtKeys.ASTERON);
                // Each Asteron spike has a fixed frame set at creation (frames 2-4)
                // ROM ObjA4_SubObjData2: make_art_tile(ArtTile_ArtNem_MtzSupernova,0,1).
                frame = fixedFrame >= 0 ? fixedFrame : 2;
                forceHighPriority = true;
                break;
            case TURTLOID_SHOT:
                renderer = renderManager.getRenderer(Sonic2ObjectArtKeys.TURTLOID);
                // Turtloid shot animation: Ani_TurtloidShot = dc.b 1, 4, 5, $FF
                frame = 4 + animFrame;
                break;
            case NEBULA_BOMB:
                renderer = renderManager.getRenderer(Sonic2ObjectArtKeys.NEBULA);
                // Nebula bomb uses frame 4 (Map_obj99_0092), palette blinks via Obj98_NebulaBombFall
                frame = 4;
                // ROM: bchg #palette_bit_0,art_tile(a0) - toggles palette line every frame
                paletteOverride = paletteBlink ? 0 : 1;
                break;
            case CLUCKER_SHOT:
                renderer = renderManager.getRenderer(Sonic2ObjectArtKeys.CLUCKER);
                // Ani_CluckerShot: dc.b 3, $D, $E, $F, $10, $11, $12, $13, $14, $FF
                // Frames 13-20 cycle with duration 3, end action $FF = loop
                frame = 13 + (animFrame % 8);
                break;
            default:
                return;
        }

        if (renderer == null || !renderer.isReady()) {
            return;
        }

        if (forceHighPriority) {
            renderer.drawFrameIndexForcedPriority(
                    frame, currentX, currentY, hFlip, false, paletteOverride, ASTERON_HIGH_PRIORITY_SPRITE);
        } else {
            renderer.drawFrameIndex(frame, currentX, currentY, hFlip, false, paletteOverride);
        }
    }

    @Override
    public PerObjectRewindSnapshot captureRewindState() {
        return super.captureRewindState().withObjectSubclassExtra(
                new PerObjectRewindSnapshot.BadnikProjectileRewindExtra(
                        type.name(),
                        currentX,
                        currentY,
                        motionState.xSub,
                        motionState.ySub,
                        xVelocity,
                        yVelocity,
                        applyGravity,
                        gravity,
                        collisionSizeIndex,
                        animFrame,
                        hFlip,
                        initialDelay,
                        fixedFrame,
                        paletteBlink,
                        cluckerAnimTimer,
                        cluckerAnimIndex,
                        aquisBulletAnimTimer,
                        aquisBulletAnimIndex,
                        loadSubObjectInitPending));
    }

    @Override
    public void restoreRewindState(PerObjectRewindSnapshot snapshot) {
        super.restoreRewindState(snapshot);
        if (snapshot.objectSubclassExtra()
                instanceof PerObjectRewindSnapshot.BadnikProjectileRewindExtra extra) {
            currentX = extra.currentX();
            currentY = extra.currentY();
            xVelocity = extra.xVelocity();
            yVelocity = extra.yVelocity();
            applyGravity = extra.applyGravity();
            gravity = extra.gravity();
            collisionSizeIndex = extra.collisionSizeIndex();
            animFrame = extra.animFrame();
            hFlip = extra.hFlip();
            initialDelay = extra.initialDelay();
            fixedFrame = extra.fixedFrame();
            paletteBlink = extra.paletteBlink();
            cluckerAnimTimer = extra.cluckerAnimTimer();
            cluckerAnimIndex = extra.cluckerAnimIndex();
            aquisBulletAnimTimer = extra.aquisBulletAnimTimer();
            aquisBulletAnimIndex = extra.aquisBulletAnimIndex();
            loadSubObjectInitPending = extra.loadSubObjectInitPending();
            motionState.x = currentX;
            motionState.y = currentY;
            motionState.xSub = extra.xSub();
            motionState.ySub = extra.ySub();
            motionState.xVel = xVelocity;
            motionState.yVel = yVelocity;
        }
    }
}
