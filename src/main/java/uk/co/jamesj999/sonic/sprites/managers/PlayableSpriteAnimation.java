package uk.co.jamesj999.sonic.sprites.managers;

import uk.co.jamesj999.sonic.physics.Direction;
import uk.co.jamesj999.sonic.sprites.animation.ScriptedVelocityAnimationProfile;
import uk.co.jamesj999.sonic.sprites.animation.SpriteAnimationProfile;
import uk.co.jamesj999.sonic.sprites.animation.SpriteAnimationScript;
import uk.co.jamesj999.sonic.sprites.playable.AbstractPlayableSprite;

/**
 * Updates a playable sprite's mapping frame based on its animation profile.
 */
public class PlayableSpriteAnimation {
    private static final int DEFAULT_RUN_SPEED_THRESHOLD = 0x600;
    private final AbstractPlayableSprite sprite;
    private int lastAnimationId = -1;

    public PlayableSpriteAnimation(AbstractPlayableSprite sprite) {
        this.sprite = sprite;
    }

    public void update(int frameCounter) {
        if (sprite == null) {
            return;
        }
        updateFlipAngle(frameCounter);
        boolean facingLeft = Direction.LEFT.equals(sprite.getDirection());
        sprite.setRenderFlips(facingLeft, false);
        if (sprite.getSpindashDustController() != null) {
            sprite.getSpindashDustController().update();
        }

        SpriteAnimationProfile profile = sprite.getAnimationProfile();
        if (sprite.getAnimationSet() != null && !sprite.getAnimationSet().getAllScripts().isEmpty()) {
            Integer desiredAnimId = profile != null
                    ? profile.resolveAnimationId(sprite, frameCounter, sprite.getAnimationSet().getScriptCount())
                    : null;
            if (desiredAnimId != null && desiredAnimId != sprite.getAnimationId()) {
                sprite.setAnimationId(desiredAnimId);
                resetScriptState();
            }
            updateScriptedAnimation(frameCounter);
            return;
        }

        if (profile == null) {
            return;
        }
        int frameCount = sprite.getAnimationFrameCount();
        if (frameCount <= 0) {
            return;
        }
        int frame = profile.resolveFrame(sprite, frameCounter, frameCount);
        sprite.setMappingFrame(frame);
    }

    private void updateScriptedAnimation(int frameCounter) {
        var animationSet = sprite.getAnimationSet();
        if (animationSet == null) {
            return;
        }
        if (sprite.getAnimationId() != lastAnimationId) {
            resetScriptState();
        }
        var script = animationSet.getScript(sprite.getAnimationId());
        if (script == null || script.frames().isEmpty()) {
            return;
        }

        int delayOrFlag = script.delay() & 0xFF;
        if (delayOrFlag >= 0x80) {
            updateSpecialScript(delayOrFlag, script);
            return;
        }

        updateScriptWithDelay(script, delayOrFlag, 0);
    }

    private void updateSpecialScript(int startFlag, SpriteAnimationScript script) {
        switch (startFlag & 0xFF) {
            case 0xFF -> updateWalkRun(script);
            case 0xFE -> updateRoll(script);
            case 0xFD -> updatePush(script);
            default -> updateScriptWithDelay(script, 0, 0);
        }
    }

    private void updateWalkRun(SpriteAnimationScript baseScript) {
        int flipAngle = sprite.getFlipAngle();
        if (flipAngle != 0) {
            updateTumble(flipAngle);
            return;
        }
        int speed = Math.abs(sprite.getGSpeed());
        ScriptedVelocityAnimationProfile profile = resolveVelocityProfile();
        int runThreshold = resolveRunThreshold(profile);

        SpriteAnimationScript walkScript = resolveScript(profile != null ? profile.getWalkAnimId() : -1, baseScript);
        SpriteAnimationScript runScript = resolveScript(profile != null ? profile.getRunAnimId() : -1, baseScript);
        SpriteAnimationScript active = speed >= runThreshold ? runScript : walkScript;
        if (active == null) {
            active = baseScript;
        }

        int slopeOffset = resolveSlopeOffset(speed >= runThreshold);
        int delay = computeSpeedDelay(speed, 0x800, 8);
        updateScriptWithDelay(active, delay, slopeOffset);
    }

    private void updateTumble(int flipAngle) {
        int d0 = flipAngle & 0xFF;
        boolean facingLeft = Direction.LEFT.equals(sprite.getDirection());
        if (!facingLeft) {
            sprite.setRenderFlips(false, false);
            int frame = ((d0 + 0x0B) & 0xFF) / 0x16;
            sprite.setMappingFrame(frame + 0x5F);
            sprite.setAnimationTick(0);
            return;
        }

        boolean flipTurned = sprite.isFlipTurned();
        int adjusted;
        boolean hFlip = true;
        boolean vFlip;
        if (flipTurned) {
            vFlip = false;
            adjusted = (d0 + 0x0B) & 0xFF;
        } else {
            vFlip = true;
            adjusted = (0x100 - d0) & 0xFF;
            adjusted = (adjusted + 0x8F) & 0xFF;
        }
        int frame = (adjusted / 0x16) + 0x5F;
        sprite.setRenderFlips(hFlip, vFlip);
        sprite.setMappingFrame(frame);
        sprite.setAnimationTick(0);
    }

    private void updateFlipAngle(int frameCounter) {
        int flipAngle = sprite.getFlipAngle();
        if (flipAngle == 0) {
            return;
        }
        if (sprite.wasSpiralActive(frameCounter)) {
            return;
        }
        int flipSpeed = sprite.getFlipSpeed();
        if (flipSpeed == 0) {
            return;
        }
        int inertia = sprite.getGSpeed();
        if (inertia == 0) {
            inertia = sprite.getXSpeed();
        }
        boolean movingLeft = inertia < 0;
        int flipsRemaining = sprite.getFlipsRemaining();
        if (!movingLeft || sprite.isFlipTurned()) {
            int newAngle = flipAngle + flipSpeed;
            if (newAngle > 0xFF) {
                flipsRemaining -= 1;
                if (flipsRemaining < 0) {
                    flipsRemaining = 0;
                    newAngle = 0;
                } else {
                    newAngle &= 0xFF;
                }
            }
            sprite.setFlipAngle(newAngle);
            sprite.setFlipsRemaining(flipsRemaining);
            return;
        }

        int newAngle = flipAngle - flipSpeed;
        if (newAngle < 0) {
            flipsRemaining -= 1;
            if (flipsRemaining < 0) {
                flipsRemaining = 0;
                newAngle = 0;
            } else {
                newAngle = (newAngle + 0x100) & 0xFF;
            }
        }
        sprite.setFlipAngle(newAngle);
        sprite.setFlipsRemaining(flipsRemaining);
    }

    private void updateRoll(SpriteAnimationScript baseScript) {
        int speed = Math.abs(sprite.getGSpeed());
        ScriptedVelocityAnimationProfile profile = resolveVelocityProfile();
        int runThreshold = resolveRunThreshold(profile);

        int rollId = profile != null ? profile.getRollAnimId() : -1;
        int roll2Id = profile != null ? profile.getRoll2AnimId() : -1;
        int activeId = (speed >= runThreshold && roll2Id >= 0) ? roll2Id : rollId;
        SpriteAnimationScript active = resolveScript(activeId, baseScript);
        if (active == null) {
            active = baseScript;
        }

        int delay = computeSpeedDelay(speed, 0x400, 8);
        updateScriptWithDelay(active, delay, 0);
    }

    private void updatePush(SpriteAnimationScript baseScript) {
        int speed = Math.abs(sprite.getGSpeed());
        ScriptedVelocityAnimationProfile profile = resolveVelocityProfile();

        int pushId = profile != null ? profile.getPushAnimId() : -1;
        SpriteAnimationScript active = resolveScript(pushId, baseScript);
        if (active == null) {
            active = baseScript;
        }

        int delay = computeSpeedDelay(speed, 0x800, 6);
        updateScriptWithDelay(active, delay, 0);
    }

    private void updateScriptWithDelay(
            SpriteAnimationScript script,
            int delay,
            int frameOffset
    ) {
        if (script == null || script.frames().isEmpty()) {
            return;
        }

        int duration = sprite.getAnimationTick() - 1;
        boolean advanceFrame = duration < 0;
        if (advanceFrame) {
            duration = delay;
        }
        sprite.setAnimationTick(duration);

        int frameIndex = sprite.getAnimationFrameIndex();
        if (frameIndex < 0 || frameIndex >= script.frames().size()) {
            frameIndex = 0;
            sprite.setAnimationFrameIndex(0);
        }
        int mappingFrame = script.frames().get(frameIndex) + frameOffset;
        sprite.setMappingFrame(mappingFrame);

        if (advanceFrame) {
            advanceFrameIndex(script);
        }
    }

    private void advanceFrameIndex(SpriteAnimationScript script) {
        int frameIndex = sprite.getAnimationFrameIndex() + 1;
        if (frameIndex < script.frames().size()) {
            sprite.setAnimationFrameIndex(frameIndex);
            return;
        }
        switch (script.endAction()) {
            case HOLD -> sprite.setAnimationFrameIndex(script.frames().size() - 1);
            case LOOP_BACK -> sprite.setAnimationFrameIndex(resolveLoopBackIndex(script));
            case SWITCH -> {
                int nextAnimId = script.endParam();
                if (nextAnimId == sprite.getAnimationId()) {
                    sprite.setAnimationFrameIndex(0);
                    return;
                }
                // ROM ACCURACY: Check if the profile wants the CURRENT animation to continue.
                // In the original game, $FD only sets 'anim' but not 'prev_anim'. If the
                // movement code immediately sets 'anim' back to the current animation,
                // the comparison 'anim == prev_anim' passes and no reset occurs.
                // This allows animations like skid to HOLD on the last frame while the
                // triggering condition (skidding) persists, rather than switching and
                // then immediately switching back with a reset.
                SpriteAnimationProfile profile = sprite.getAnimationProfile();
                if (profile != null) {
                    Integer desired = profile.resolveAnimationId(sprite, 0,
                            sprite.getAnimationSet() != null ? sprite.getAnimationSet().getScriptCount() : 0);
                    if (desired != null && desired == sprite.getAnimationId()) {
                        // Profile wants current animation - HOLD on last frame instead of switching
                        sprite.setAnimationFrameIndex(script.frames().size() - 1);
                        return;
                    }
                }
                sprite.setAnimationId(nextAnimId);
                resetScriptState();
                return;
            }
            case LOOP -> sprite.setAnimationFrameIndex(0);
            default -> sprite.setAnimationFrameIndex(0);
        }
    }

    private int computeSpeedDelay(int speedSubpixels, int base, int shift) {
        int value = base - speedSubpixels;
        if (value < 0) {
            value = 0;
        }
        return value >> shift;
    }

    private ScriptedVelocityAnimationProfile resolveVelocityProfile() {
        SpriteAnimationProfile profile = sprite.getAnimationProfile();
        if (profile instanceof ScriptedVelocityAnimationProfile velocityProfile) {
            return velocityProfile;
        }
        return null;
    }

    private int resolveRunThreshold(ScriptedVelocityAnimationProfile profile) {
        if (profile == null) {
            return DEFAULT_RUN_SPEED_THRESHOLD;
        }
        int threshold = profile.getRunSpeedThreshold();
        return threshold > 0 ? threshold : DEFAULT_RUN_SPEED_THRESHOLD;
    }

    private SpriteAnimationScript resolveScript(int scriptId, SpriteAnimationScript fallback) {
        if (scriptId < 0 || sprite.getAnimationSet() == null) {
            return fallback;
        }
        SpriteAnimationScript script = sprite.getAnimationSet().getScript(scriptId);
        return script != null ? script : fallback;
    }

    private int resolveSlopeOffset(boolean running) {
        int angle = (byte) sprite.getAngle();
        int d0 = angle;
        if (d0 > 0) {
            d0 -= 1;
        }
        boolean facingLeft = Direction.LEFT.equals(sprite.getDirection());
        if (!facingLeft) {
            d0 = ~d0;
        }
        d0 = (d0 + 0x10) & 0xFF;
        if ((d0 & 0x80) != 0) {
            sprite.setRenderFlips(!facingLeft, true);
        } else {
            sprite.setRenderFlips(facingLeft, false);
        }
        d0 = (d0 >> 4) & 0x6;
        return running ? d0 * 2 : d0 * 4;
    }

    private int resolveLoopBackIndex(SpriteAnimationScript script) {
        int loopBack = script.endParam();
        if (loopBack <= 0) {
            return 0;
        }
        int target = script.frames().size() - loopBack;
        if (target < 0) {
            return 0;
        }
        return target;
    }

    private void resetScriptState() {
        sprite.setAnimationFrameIndex(0);
        sprite.setAnimationTick(0);
        lastAnimationId = sprite.getAnimationId();
    }
}
