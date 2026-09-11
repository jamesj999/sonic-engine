package com.openggf.game.sonic3k.objects;

import com.openggf.game.PlayableEntity;
import com.openggf.game.sonic3k.audio.Sonic3kSfx;
import com.openggf.game.sonic3k.constants.Sonic3kObjectIds;
import com.openggf.game.sonic3k.runtime.LbzZoneRuntimeState;
import com.openggf.game.sonic3k.runtime.S3kRuntimeStates;
import com.openggf.graphics.GLCommand;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.PlaceholderObjectInstance;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.objects.TouchActorContextPolicy;
import com.openggf.level.objects.TouchAttackBouncePolicy;
import com.openggf.level.objects.TouchCategoryDecodeMode;
import com.openggf.level.objects.TouchOverlapStopPolicy;
import com.openggf.level.objects.TouchResponseListener;
import com.openggf.level.objects.TouchResponseProfile;
import com.openggf.level.objects.TouchResponseProvider;
import com.openggf.level.objects.TouchResponseResult;
import com.openggf.level.objects.TouchShieldDeflectCapability;

import java.util.List;

/**
 * ROM object: {@code Obj_LBZAlarm} ({@code sonic3k.asm:57034-57112}).
 *
 * <p>The alarm is an invisible S3K special-property touch object. Touching its
 * {@code collision_flags = $D7} box latches {@code collision_property}, starts
 * a {@code $81}-frame alarm window, plays {@code sfx_Alarm} every {@code $20}
 * frames while active, and spawns {@code Obj_Flybot767} from Player 1's
 * position. Subtype bit 0 delays the Flybot spawn until the timer reaches
 * {@code $41}; subtype bit 1 chooses the spawn side.
 */
public final class LbzAlarmObjectInstance extends AbstractObjectInstance
        implements TouchResponseProvider, TouchResponseListener, RewindRecreatable {

    private static final int COLLISION_FLAGS = 0xD7;
    private static final int COLLISION_SIZE_INDEX = 0x17;
    private static final int ALARM_DURATION = 0x81;
    private static final int DELAYED_SPAWN_TIMER_VALUE = 0x41;
    private static final int SFX_PERIOD_MASK = 0x1F;
    private static final int FLYBOT_X_OFFSET = 0x00C0;
    private static final int FLYBOT_Y_OFFSET = -0x0060;

    private static final TouchResponseProfile TOUCH_RESPONSE_PROFILE = new TouchResponseProfile(
            TouchCategoryDecodeMode.S3K_SPECIAL_PROPERTY,
            true,
            true,
            false,
            TouchShieldDeflectCapability.NONE,
            0,
            TouchAttackBouncePolicy.STANDARD_ENEMY_KILL,
            TouchActorContextPolicy.MAIN_FULL_SIDEKICK_HURT_ONLY,
            TouchOverlapStopPolicy.STOP_AFTER_FIRST_OVERLAP_FOR_ALL_ACTORS);

    private int subtype;
    private int alarmTimer;
    private int collisionProperty;

    public LbzAlarmObjectInstance(ObjectSpawn spawn) {
        super(spawn, "LBZAlarm");
        this.subtype = spawn.subtype();
    }

    @Override
    public LbzAlarmObjectInstance recreateForRewind(RewindRecreateContext ctx) {
        return new LbzAlarmObjectInstance(ctx.spawn());
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        if (isDestroyed()) {
            return;
        }

        if (alarmTimer != 0) {
            alarmTimer--;
            if (alarmTimer == 0) {
                setAlarmAnimationActive(false);
                return;
            }
            if ((subtype & 0x01) != 0 && alarmTimer == DELAYED_SPAWN_TIMER_VALUE) {
                spawnFlybot(playerEntity);
            }
            if ((alarmTimer & SFX_PERIOD_MASK) == 0) {
                services().playSfx(Sonic3kSfx.ALARM.id);
            }
            return;
        }

        if (collisionProperty == 0) {
            return;
        }

        // ROM loc_2947E: clr.b collision_property(a0); move.w #$81,$30(a0).
        collisionProperty = 0;
        alarmTimer = ALARM_DURATION;
        setAlarmAnimationActive(true);
        if ((subtype & 0x01) == 0) {
            spawnFlybot(playerEntity);
        }
    }

    @Override
    public int getCollisionFlags() {
        return alarmTimer == 0 ? COLLISION_FLAGS : 0;
    }

    @Override
    public int getCollisionProperty() {
        return collisionProperty;
    }

    @Override
    public TouchResponseProfile getTouchResponseProfile() {
        return TOUCH_RESPONSE_PROFILE;
    }

    @Override
    public TouchResponseProfile getTouchResponseProfile(boolean multiRegionSource) {
        return TOUCH_RESPONSE_PROFILE;
    }

    @Override
    public void onTouchResponse(PlayableEntity player, TouchResponseResult result, int frameCounter) {
        if (alarmTimer == 0 && result.sizeIndex() == COLLISION_SIZE_INDEX) {
            collisionProperty = 1;
        }
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        // Invisible trigger in ROM: no mappings, art tile, or DisplaySprite call.
    }

    public int alarmTimerForTesting() {
        return alarmTimer;
    }

    private void spawnFlybot(PlayableEntity playerEntity) {
        if (playerEntity == null) {
            return;
        }

        int xOffset = (subtype & 0x02) == 0 ? FLYBOT_X_OFFSET : -FLYBOT_X_OFFSET;
        ObjectSpawn flybotSpawn = new ObjectSpawn(
                playerEntity.getCentreX() + xOffset,
                playerEntity.getCentreY() + FLYBOT_Y_OFFSET,
                Sonic3kObjectIds.FLYBOT_767,
                0,
                0,
                false,
                0);
        spawnChild(() -> createFlybotChild(flybotSpawn));
    }

    private AbstractObjectInstance createFlybotChild(ObjectSpawn flybotSpawn) {
        ObjectInstance child = new Sonic3kObjectRegistry().create(flybotSpawn);
        if (child instanceof AbstractObjectInstance object) {
            return object;
        }
        return new PlaceholderObjectInstance(flybotSpawn, "Flybot767");
    }

    private void setAlarmAnimationActive(boolean active) {
        var registry = services().zoneRuntimeRegistry();
        if (registry == null) {
            return;
        }
        LbzZoneRuntimeState state = S3kRuntimeStates.currentLbz(registry).orElse(null);
        if (state != null) {
            state.setAlarmAnimationActive(active);
        }
    }
}
