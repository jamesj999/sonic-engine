package com.openggf.game.sonic3k.objects;

import com.openggf.game.PlayableEntity;
import com.openggf.game.sonic3k.Sonic3kObjectArtKeys;
import com.openggf.game.sonic3k.audio.Sonic3kSfx;
import com.openggf.game.sonic3k.constants.Sonic3kAnimationIds;
import com.openggf.game.sonic3k.runtime.MhzZoneRuntimeState;
import com.openggf.graphics.GLCommand;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectAnimationState;
import com.openggf.level.objects.ObjectPlayerParticipationPolicy;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.ObjectServices;
import com.openggf.level.objects.RomObjectCodePointerProvider;
import com.openggf.level.objects.SpawnRewindRecreatable;
import com.openggf.level.objects.SolidContact;
import com.openggf.level.objects.SolidObjectListener;
import com.openggf.level.objects.SolidObjectParams;
import com.openggf.level.objects.SolidObjectProvider;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.sprites.animation.SpriteAnimationEndAction;
import com.openggf.sprites.animation.SpriteAnimationScript;
import com.openggf.sprites.animation.SpriteAnimationSet;
import com.openggf.sprites.playable.AbstractPlayableSprite;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * S3K SKL object $23 - MHZ bouncy mushroom cap.
 *
 * <p>ROM reference: {@code Obj_MHZMushroomCap}. Ports the cap's
 * level-animation driven body motion, {@code SolidObjectTop} shape, and
 * animation-frame gated bounce response.
 */
public final class MhzMushroomCapObjectInstance extends AbstractObjectInstance
        implements SolidObjectProvider, SolidObjectListener, RomObjectCodePointerProvider, SpawnRewindRecreatable {
    private static final int ANIM_IDLE = 0;
    private static final int ANIM_BOUNCE = 1;
    private static final int BOUNCE_MAPPING_FRAME = 3;
    private static final int SOLID_HALF_WIDTH = 0x18;
    private static final int SOLID_AIR_HALF_HEIGHT = 0;
    private static final int POSITION_COUNTER_WRAP = 0x58;
    private static final int LOW_BOUNCE_THRESHOLD = 0x0660;
    private static final int MEDIUM_BOUNCE_THRESHOLD = 0x0760;
    private static final int HIGH_BOUNCE_THRESHOLD = 0x0860;
    private static final int BOUNCE_BONUS = 0x20;
    private static final int ROM_CODE_POINTER_HIGH_WORD = 0x0003;
    private static final int[] MAPPING_GROUND_HALF_HEIGHTS = {
            0x12, 0x08, 0x12, 0x12
    };

    private static final byte[] POSITION_OFFSETS = {
            0, 0,
            0, 0,
            0, 0,
            0, 0,
            -1, 0,
            -1, 0,
            -1, 0,
            -2, 0,
            -2, 0,
            -2, 0,
            -3, 1,
            -3, 1,
            -3, 1,
            -3, 1,
            -3, 1,
            -3, 1,
            -2, 0,
            -2, 0,
            -2, 0,
            -1, 0,
            -1, 0,
            -1, 0,
            0, 0,
            0, 0,
            0, 0,
            0, 0,
            1, 0,
            1, 0,
            1, 0,
            2, 0,
            2, 0,
            2, 0,
            3, 1,
            3, 1,
            3, 1,
            3, 1,
            3, 1,
            3, 1,
            2, 0,
            2, 0,
            2, 0,
            1, 0,
            1, 0,
            1, 0,
            0, 0,
            0, 0,
            0, 0,
            0, 0,
            -1, 0,
            -1, 0,
            -1, 0,
            -2, 0,
            -2, 0,
            -2, 0
    };

    private int baseX;
    private int baseY;
    private int animationPhaseOffset;
    private int priorityBucket;
    private boolean planePriority;
    private final String artKey;
    private final ObjectAnimationState animationState;
    private final Map<AbstractPlayableSprite, Integer> previousYVelocities = new IdentityHashMap<>();
    private final Map<AbstractPlayableSprite, Integer> lastLaunchFrames = new IdentityHashMap<>();
    private final Set<AbstractPlayableSprite> standingPlayers =
            Collections.newSetFromMap(new IdentityHashMap<>());
    private int x;
    private int y;
    private int mappingFrame;

    public MhzMushroomCapObjectInstance(ObjectSpawn spawn) {
        super(spawn, "MHZMushroomCap");
        this.baseX = spawn.x();
        this.baseY = spawn.y();
        this.x = baseX;
        this.y = baseY;
        this.animationPhaseOffset = (spawn.subtype() & 0x01) != 0 ? 0x14 : 0;
        this.priorityBucket = (spawn.subtype() & 0x80) != 0 ? 6 : 1;
        this.planePriority = (spawn.subtype() & 0x40) == 0;
        this.artKey = (spawn.subtype() & 0x01) != 0
                ? Sonic3kObjectArtKeys.MHZ_MUSHROOM_CAP_LIGHT
                : Sonic3kObjectArtKeys.MHZ_MUSHROOM_CAP_DARK;
        this.animationState = new ObjectAnimationState(buildAnimationSet(), ANIM_IDLE, 0);
    }

    @Override
    public void setServices(ObjectServices services) {
        super.setServices(services);
        updatePosition(0);
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
        return priorityBucket;
    }

    @Override
    public int romObjectCodePointerHighWord() {
        // Obj_MHZMushroomCap lives at 0x0003E080; S3K Tails_CPU_interact stores word 0
        // of the stood-on object SST (docs/skdisasm/sonic3k.asm:26816-26843, 82129).
        return ROM_CODE_POINTER_HIGH_WORD;
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity player) {
        recordPreviousYVelocity(player);
        ObjectServices services = tryServices();
        if (services != null) {
            for (PlayableEntity sidekick : services.playerQuery().playersFor(
                    ObjectPlayerParticipationPolicy.ALL_ENGINE_PLAYERS)) {
                if (sidekick == player) {
                    continue;
                }
                recordPreviousYVelocity(sidekick);
            }
        }
        if (!standingPlayers.isEmpty()) {
            animationState.setAnimId(ANIM_BOUNCE);
        }
        animationState.update();
        mappingFrame = animationState.getMappingFrame();
        updatePosition(vIntRunCount);
    }

    @Override
    public SolidObjectParams getSolidParams() {
        int heightIndex = Math.min(mappingFrame, MAPPING_GROUND_HALF_HEIGHTS.length - 1);
        return SolidObjectParams.of(
                SOLID_HALF_WIDTH,
                SOLID_AIR_HALF_HEIGHT,
                MAPPING_GROUND_HALF_HEIGHTS[heightIndex]);
    }

    @Override
    public boolean isTopSolidOnly() {
        return true;
    }

    @Override
    public boolean usesCollisionHalfWidthForTopLanding() {
        return true;
    }

    @Override
    public boolean usesGroundHalfHeightForTopSolidContact() {
        return true;
    }

    @Override
    public boolean usesPlatformObjectLandingSnap() {
        return false;
    }

    @Override
    public boolean rejectsZeroDistanceTopSolidLanding() {
        // SolidObjectTop reaches loc_1E45A for a fresh cap landing. Its
        // unsigned cmpi.w #-$10,d0 / blo window accepts only [-$10,-1],
        // excluding the exact d0=0 boundary (sonic3k.asm:41996-42015).
        return true;
    }

    @Override
    public boolean allowsObjectControlledSolidContacts() {
        // Obj_MHZMushroomCap_Main reaches its landing path through SolidObjectTop
        // (sonic3k.asm:82182), whose new-contact test rejects object_control with
        // a SIGNED test -- `tst.b object_control(a1) / bmi` (sonic3k.asm:42014).
        // Only bit-7 states (warp/respawn) skip the contact; a positive
        // object_control, such as the Madmole side drill's carry value of 1
        // (sub_8D94A, sonic3k.asm:193465-193487), still lands on the cap.
        return true;
    }

    @Override
    public boolean carriesRiderOnHorizontalMove(PlayableEntity player) {
        return false;
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        PatternSpriteRenderer renderer = getRenderer(artKey);
        if (renderer != null) {
            renderer.drawFrameIndexForcedPriority(mappingFrame, x, y, false, false, -1, planePriority);
        }
    }

    @Override
    public void onSolidContact(PlayableEntity player, SolidContact contact, int frameCounter) {
        if (contact.standing() && player instanceof AbstractPlayableSprite sprite) {
            standingPlayers.add(sprite);
            // Obj_MHZMushroomCap calls SolidObjectTop before BounceCharacter
            // (sonic3k.asm:82170-82186), so launch after the contact snap.
            launchStandingPlayerOnSpringFrame(sprite, frameCounter);
        }
    }

    @Override
    public void onSolidContactCleared(PlayableEntity player, int frameCounter) {
        if (player instanceof AbstractPlayableSprite sprite) {
            standingPlayers.remove(sprite);
        }
    }

    private void updatePosition(int frameCounter) {
        int counter = mushroomCapPositionCounter(frameCounter);
        int tableOffset = counter + animationPhaseOffset;
        if (tableOffset < 0 || tableOffset + 1 >= POSITION_OFFSETS.length) {
            tableOffset = 0;
        }
        x = baseX + POSITION_OFFSETS[tableOffset];
        y = baseY + POSITION_OFFSETS[tableOffset + 1];
        updateDynamicSpawn(x, y);
    }

    private int mushroomCapPositionCounter(int frameCounter) {
        ObjectServices services = tryServices();
        if (services != null && services.zoneRuntimeState() instanceof MhzZoneRuntimeState state) {
            return state.mushroomCapPositionCounter();
        }
        int counter = frameCounter & 0xFF;
        return counter < POSITION_COUNTER_WRAP ? counter : 0;
    }

    private void recordPreviousYVelocity(PlayableEntity player) {
        if (player instanceof AbstractPlayableSprite sprite && !standingPlayers.contains(sprite)) {
            previousYVelocities.put(sprite, (int) sprite.getYSpeed());
        }
    }

    private void launchStandingPlayerOnSpringFrame(AbstractPlayableSprite player, int frameCounter) {
        if (mappingFrame != BOUNCE_MAPPING_FRAME) {
            return;
        }
        Integer lastFrame = lastLaunchFrames.get(player);
        if (lastFrame != null && lastFrame == frameCounter) {
            return;
        }
        lastLaunchFrames.put(player, frameCounter);

        int previousYVelocity = previousYVelocities.getOrDefault(player, (int) player.getYSpeed());
        int magnitude;
        if (previousYVelocity < LOW_BOUNCE_THRESHOLD) {
            magnitude = LOW_BOUNCE_THRESHOLD;
        } else if (previousYVelocity < MEDIUM_BOUNCE_THRESHOLD) {
            magnitude = MEDIUM_BOUNCE_THRESHOLD;
        } else {
            magnitude = HIGH_BOUNCE_THRESHOLD;
        }
        player.setYSpeed((short) -(magnitude + BOUNCE_BONUS));
        player.setAir(true);
        player.setOnObject(false);
        player.setJumping(false);
        player.setSpindash(false);
        player.setHurt(false);
        player.setAnimationId(Sonic3kAnimationIds.SPRING);
        playBounceSfx();
    }

    private void playBounceSfx() {
        ObjectServices services = tryServices();
        if (services != null) {
            services.playSfx(Sonic3kSfx.MUSHROOM_BOUNCE.id);
        }
    }

    private static SpriteAnimationSet buildAnimationSet() {
        SpriteAnimationSet set = new SpriteAnimationSet();
        set.addScript(ANIM_IDLE, new SpriteAnimationScript(
                7,
                List.of(0),
                SpriteAnimationEndAction.HOLD,
                0));
        set.addScript(ANIM_BOUNCE, new SpriteAnimationScript(
                0,
                // Ani_MHZMushroomCap_Seq1 (byte_3E1E1) plays exactly 23 frames before the
                // $FC "increment routine counter" terminator; no extra trailing idle-delay
                // frame at speed 0.
                List.of(1, 1, 1, 1, 1, 3, 0, 3, 0, 3, 0, 3, 0, 3, 0, 2, 0, 2, 0, 2, 0, 2, 0),
                SpriteAnimationEndAction.SWITCH,
                ANIM_IDLE));
        return set;
    }
}
