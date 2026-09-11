package com.openggf.game.sonic1.objects.badniks;

import com.openggf.game.ObjectArtProvider;
import com.openggf.graphics.GLCommand;
import com.openggf.graphics.GraphicsManager;
import com.openggf.level.Pattern;
import com.openggf.level.objects.ObjectArtKeys;
import com.openggf.level.objects.ObjectRenderManager;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.ObjectSpriteSheet;
import com.openggf.level.objects.StubObjectServices;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.level.render.SpriteMappingFrame;
import com.openggf.level.render.SpriteMappingPiece;
import com.openggf.sprites.animation.SpriteAnimationSet;
import com.openggf.tests.TestEnvironment;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Covers the SYZ Roller's three lifecycle collision states separately
 * (docs/s1disasm/_incObj/43 Badnik - Roller.asm), triaging the reported
 * "Roller cannot be defeated in its initial standing state" symptom
 * (S1 bug batch ledger row 7).
 * <p>
 * ROM finding: Roll_Main (routine 0) and Roll_Action_FromLeft (ob2ndRout=0,
 * the initial curled/waiting state before Sonic gets 256px ahead) never
 * write {@code obColType} -- it stays 0 (col_none) from the object's
 * zeroed spawn RAM. ReactToItem skips col_none objects entirely
 * ("Sonic ReactToItem.asm:52-53", {@code move.b obColType(a1),d0 / bne...}),
 * so a dormant, not-yet-activated Roller genuinely cannot be touched,
 * hurt Sonic, OR be defeated by Sonic -- this is not a "blanket invincible"
 * placeholder, it is the literal absence of any obColType write in that
 * state. Once activated it becomes destroyable ($0E, Roll_Action_StopAndUnfold,
 * line 177) while stopped/unfolded, and invincible-and-damaging ($8E,
 * Roll_Action_FromLeft line 96 / Roll_Action_Unfolded line 111) while rolling.
 * <p>
 * These tests drive the private lifecycle fields directly via reflection
 * (rather than {@code update()}) to isolate the ROM-cited {@code obColType}
 * decision in {@link Sonic1RollerBadnikInstance#getCollisionFlags()} from
 * terrain/services plumbing that a bare {@code new Sonic1RollerBadnikInstance(...)}
 * (outside {@code ObjectManager} injection) does not have wired up.
 */
public class TestSonic1RollerBadnikInstance {

    private static final int STATE_ROLL_CHK = 0;
    private static final int STATE_ROLL_NO_CHK = 1;
    private static final int STATE_CHK_JUMP = 2;

    @BeforeEach
    public void setUp() {
        // Needed only by the render-gate tests below: PatternSpriteRenderer's
        // single-arg constructor resolves GameServices.graphics(), which
        // requires an active gameplay mode.
        TestEnvironment.resetAll();
        TestEnvironment.activeGameplayMode();
    }

    @AfterEach
    public void tearDown() {
        TestEnvironment.resetAll();
    }

    private Sonic1RollerBadnikInstance newRoller() {
        return new Sonic1RollerBadnikInstance(new ObjectSpawn(160, 100, 0x43, 0, 0, false, 0));
    }

    private void setState(Sonic1RollerBadnikInstance roller, int secondaryState, boolean invincible) throws Exception {
        Field stateField = Sonic1RollerBadnikInstance.class.getDeclaredField("secondaryState");
        stateField.setAccessible(true);
        stateField.set(roller, secondaryState);
        Field invincibleField = Sonic1RollerBadnikInstance.class.getDeclaredField("invincible");
        invincibleField.setAccessible(true);
        invincibleField.set(roller, invincible);
    }

    @Test
    public void initialStandingStateHasNoCollisionAtAll() throws Exception {
        // ROM: Roll_Main / Roll_Action_FromLeft never write obColType while
        // waiting for Sonic to be 0x100px to the right (docs/s1disasm/_incObj/
        // 43 Badnik - Roller.asm:19-38, 86-100). The object's RAM starts
        // zeroed, so obColType == 0 (col_none) until activation. This is NOT
        // "invincible" (obColType $80+) -- it is no collision entry at all,
        // so ReactToItem's `move.b obColType(a1),d0 / bne` skip (Sonic
        // ReactToItem.asm:52-53) applies: Sonic cannot touch, hurt from, or
        // defeat a still-curled, not-yet-activated Roller.
        Sonic1RollerBadnikInstance roller = newRoller();
        setState(roller, STATE_ROLL_CHK, false);

        assertEquals(0, roller.getCollisionFlags(),
                "Dormant pre-activation Roller must report col_none (0), matching ROM's unset obColType");
    }

    @Test
    public void rollingStateIsDamagingCategoryNotDestroyable() throws Exception {
        // ROM Roll_Action_FromLeft (line 96) / Roll_Action_Unfolded re-fold
        // (line 111): obColType = col_28x28|col_hurt = $8E while actively
        // rolling. col_hurt ($80) routes straight to React_ChkHurt in ROM
        // (Sonic ReactToItem.asm:188-189), bypassing the badnik-defeat check
        // entirely -- touching a rolling Roller always hurts Sonic, even
        // while he is spinning/invincible, and it can never be destroyed by
        // touch in this state.
        Sonic1RollerBadnikInstance roller = newRoller();
        setState(roller, STATE_CHK_JUMP, true);

        int flags = roller.getCollisionFlags();
        assertEquals(0x80, flags & 0xC0,
                "Rolling Roller must use the col_hurt ($80) category, not badnik ($00)");
    }

    @Test
    public void stoppedUnfoldedStateIsDestroyableBadnikCategory() throws Exception {
        // ROM Roll_Action_StopAndUnfold (line 177): obColType =
        // col_28x28|col_badnik = $0E once the Roller has passed Sonic by
        // 48px and stops to unfold. This is the one window where the Roller
        // is a normal, destroyable badnik (col_badnik == 0, ENEMY category)
        // -- the reported "cannot be defeated" symptom does NOT reproduce
        // here: getCollisionFlags() already returns the ENEMY category, so
        // AbstractBadnikInstance's default onPlayerAttack()/destroyBadnik()
        // path applies normally.
        Sonic1RollerBadnikInstance roller = newRoller();
        setState(roller, STATE_ROLL_NO_CHK, false);

        int flags = roller.getCollisionFlags();
        assertEquals(0x00, flags & 0xC0,
                "Stopped/unfolded Roller must use the col_badnik (ENEMY, $00) category so it can be destroyed");
    }

    /**
     * Bug repro (S1 bug-triage row 7, post-merge finding -- see
     * .superpowers/sdd/roller-activation-report.md): the "cannot be defeated"
     * ledger row correctly found the collision math ROM-accurate (above), but
     * missed that ROM's dormant Roller is not just hitbox-less -- it is never
     * drawn at all. {@code Roll_Main} (routine 0) is entered via {@code jmp}
     * from the dispatcher and its own {@code rts} returns straight to the
     * object-list caller, so it never reaches {@code AnimateSprite}/
     * {@code DisplaySprite}. {@code Roll_Action_FromLeft} (ob2ndRout=0, the
     * curled waiting state) pops its own return address every single call
     * (docs/s1disasm/_incObj/"43 Badnik - Roller.asm":36-39, 99) to skip the
     * same two calls -- the source comment states this explicitly ("skip
     * returning to Roll_Action to avoid sprite render and despawning"). The
     * engine drew the curled pose the whole time regardless, so the player saw
     * a static, hitbox-less statue ROM never shows.
     * <p>
     * {@code appendRenderCommands}'s {@code getRenderer(...)} short-circuits
     * to {@code null} whenever no {@code ObjectServices} are injected, which
     * would mask this bug in a bare {@code new Sonic1RollerBadnikInstance(...)}
     * test -- so this wires a {@link StubObjectServices} subclass with a
     * fake *ready* {@link PatternSpriteRenderer} registered under
     * {@link ObjectArtKeys#ROLLER} to make the render path observable.
     */
    @Test
    public void dormantRollerEmitsNoRenderCommands() throws Exception {
        RecordingRenderer renderer = new RecordingRenderer();
        Sonic1RollerBadnikInstance roller = newRollerWithStubRenderer(renderer);
        setInitialized(roller, true);
        setState(roller, STATE_ROLL_CHK, false);

        List<GLCommand> commands = new ArrayList<>();
        roller.appendRenderCommands(commands);

        assertEquals(0, renderer.drawCount,
                "ROM's dormant Roller (ob2ndRout=0) skips DisplaySprite entirely via "
                        + "addq.l #4,sp (43 Badnik - Roller.asm:36-39,99) -- the engine must not draw it either");
    }

    /**
     * Companion to {@link #dormantRollerEmitsNoRenderCommands()}: the render
     * gate must not affect the Roller once activated -- ROM's
     * {@code Roll_Action_Rolling}/{@code StopAndUnfold} paths return via a
     * plain {@code rts} that DOES fall through to {@code AnimateSprite}/
     * {@code DisplaySprite}.
     */
    @Test
    public void activatedRollerStillRenders() throws Exception {
        RecordingRenderer renderer = new RecordingRenderer();
        Sonic1RollerBadnikInstance roller = newRollerWithStubRenderer(renderer);
        setInitialized(roller, true);
        setState(roller, STATE_CHK_JUMP, true);

        List<GLCommand> commands = new ArrayList<>();
        roller.appendRenderCommands(commands);

        assertEquals(1, renderer.drawCount,
                "An activated (rolling) Roller must still render exactly as before -- the "
                        + "dormant-only gate must not suppress its normal draw call");
    }

    /**
     * Animation side of the same gap: ROM never reaches {@code AnimateSprite}
     * for a dormant Roller either (same {@code addq.l #4,sp} skip), so the
     * curled idle pose must not advance while waiting for activation.
     */
    @Test
    public void dormantRollerDoesNotAnimate() throws Exception {
        Sonic1RollerBadnikInstance roller = newRoller();
        setInitialized(roller, true);
        setState(roller, STATE_ROLL_CHK, false);

        int animIndexBefore = getAnimIndex(roller);
        for (int i = 0; i < 64; i++) {
            roller.updateAnimation(i);
        }

        assertEquals(animIndexBefore, getAnimIndex(roller),
                "ROM never calls AnimateSprite for a dormant Roller -- the curled pose must not "
                        + "advance while ob2ndRout==0 (43 Badnik - Roller.asm:36-39,99)");
    }

    private Sonic1RollerBadnikInstance newRollerWithStubRenderer(RecordingRenderer renderer) {
        Sonic1RollerBadnikInstance roller = newRoller();
        roller.setServices(new StubObjectServices() {
            @Override
            public ObjectRenderManager renderManager() {
                return new ObjectRenderManager(new StubObjectArtProvider(renderer));
            }
        });
        return roller;
    }

    private void setInitialized(Sonic1RollerBadnikInstance roller, boolean initialized) throws Exception {
        Field initializedField = Sonic1RollerBadnikInstance.class.getDeclaredField("initialized");
        initializedField.setAccessible(true);
        initializedField.set(roller, initialized);
    }

    private int getAnimIndex(Sonic1RollerBadnikInstance roller) throws Exception {
        Field animIndexField = Sonic1RollerBadnikInstance.class.getDeclaredField("animIndex");
        animIndexField.setAccessible(true);
        return animIndexField.getInt(roller);
    }

    private static final class StubObjectArtProvider implements ObjectArtProvider {
        private final PatternSpriteRenderer renderer;

        private StubObjectArtProvider(PatternSpriteRenderer renderer) {
            this.renderer = renderer;
        }

        @Override
        public void loadArtForZone(int zoneIndex) {
        }

        @Override
        public PatternSpriteRenderer getRenderer(String key) {
            return ObjectArtKeys.ROLLER.equals(key) ? renderer : null;
        }

        @Override
        public ObjectSpriteSheet getSheet(String key) {
            return null;
        }

        @Override
        public SpriteAnimationSet getAnimations(String key) {
            return null;
        }

        @Override
        public int getZoneData(String key, int zoneIndex) {
            return -1;
        }

        @Override
        public Pattern[] getHudDigitPatterns() {
            return new Pattern[0];
        }

        @Override
        public Pattern[] getHudTextPatterns() {
            return new Pattern[0];
        }

        @Override
        public Pattern[] getHudLivesPatterns() {
            return new Pattern[0];
        }

        @Override
        public Pattern[] getHudLivesNumbers() {
            return new Pattern[0];
        }

        @Override
        public List<String> getRendererKeys() {
            return List.of(ObjectArtKeys.ROLLER);
        }

        @Override
        public int ensurePatternsCached(GraphicsManager graphicsManager, int baseIndex) {
            return baseIndex;
        }

        @Override
        public boolean isReady() {
            return true;
        }
    }

    private static final class RecordingRenderer extends PatternSpriteRenderer {
        private int drawCount;

        private RecordingRenderer() {
            super(dummySheet());
        }

        @Override
        public boolean isReady() {
            return true;
        }

        @Override
        public void drawFrameIndex(int frameIndex, int originX, int originY, boolean hFlip, boolean vFlip) {
            drawCount++;
        }

        private static ObjectSpriteSheet dummySheet() {
            Pattern[] patterns = {new Pattern()};
            SpriteMappingPiece piece = new SpriteMappingPiece(0, 0, 1, 1, 0, false, false, 0, false);
            return new ObjectSpriteSheet(patterns, List.of(new SpriteMappingFrame(List.of(piece))), 0, 1);
        }
    }
}
