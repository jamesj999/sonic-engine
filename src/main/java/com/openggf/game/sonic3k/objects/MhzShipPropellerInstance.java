package com.openggf.game.sonic3k.objects;

import com.openggf.game.PlayableEntity;
import com.openggf.game.sonic3k.Sonic3kObjectArtKeys;
import com.openggf.game.sonic3k.runtime.S3kRuntimeStates;
import com.openggf.graphics.GLCommand;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.SpawnRewindRecreatable;
import com.openggf.level.render.PatternSpriteRenderer;

import java.util.List;

/**
 * MHZ2 ship propeller sprite.
 *
 * <p>ROM: {@code loc_55814}, allocated twice by {@code MHZ2_ScreenEvent}
 * {@code loc_54E9C}. Uses {@code Map_MHZEndBossMisc} and
 * {@code Ani_MHZEndPropellers}.
 */
public class MhzShipPropellerInstance extends AbstractObjectInstance implements SpawnRewindRecreatable {
    private static final int OBJECT_ID = 0;
    private static final int[] ANIMATION_FRAMES = {5, 6, 7};
    private static final int ANIMATION_DELAY = 2;

    private int propellerIndex;
    private int animationIndex;
    private int animationTimer = ANIMATION_DELAY;
    private int mappingFrame = ANIMATION_FRAMES[0];

    public MhzShipPropellerInstance() {
        this(0);
    }

    public MhzShipPropellerInstance(int propellerIndex) {
        this(spawnForIndex(propellerIndex));
    }

    public MhzShipPropellerInstance(ObjectSpawn spawn) {
        super(spawn, "MHZShipPropeller");
        this.propellerIndex = spawn.subtype() & 1;
    }

    private static ObjectSpawn spawnForIndex(int propellerIndex) {
        return new ObjectSpawn(0, 0, OBJECT_ID, propellerIndex & 1, 0, false, 0);
    }

    public int getPropellerIndex() {
        return propellerIndex;
    }

    public int getMappingFrame() {
        return mappingFrame;
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity player) {
        S3kRuntimeStates.currentMhz(services().zoneRuntimeRegistry()).ifPresent(state -> {
            int x = propellerIndex == 0 ? state.shipPropellerOneX() : state.shipPropellerTwoX();
            updateDynamicSpawn(x, state.shipPropellerY());
        });
        advanceAnimation();
    }

    private void advanceAnimation() {
        int duration = animationTimer - 1;
        boolean advanceFrame = duration < 0;
        if (advanceFrame) {
            duration = ANIMATION_DELAY;
        }
        animationTimer = duration;
        mappingFrame = ANIMATION_FRAMES[animationIndex];
        if (advanceFrame) {
            animationIndex = (animationIndex + 1) % ANIMATION_FRAMES.length;
        }
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        PatternSpriteRenderer renderer = getRenderer(Sonic3kObjectArtKeys.MHZ_SHIP_PROPELLER);
        if (renderer != null) {
            renderer.drawFrameIndex(mappingFrame, getX(), getY(), false, false);
        }
    }
}
