package com.openggf.game.sonic1.events;

import com.openggf.game.sonic1.resources.Sonic1PlcService;
import java.util.function.Supplier;
import com.openggf.game.sonic1.titlecard.Sonic1TitleCardMappings;

import java.nio.ByteBuffer;

/**
 * Sonic 1 fixed-slot title-card object sidecar (Obj34) for the frames that run
 * <em>after</em> the locked title-card loop has released control.
 *
 * <p>{@code Level_StartGame} bumps the four fixed title-card elements out of
 * their move-in routine and enters {@code Level_MainLoop} with them still
 * alive: the level-name element becomes routine 4 and the other three become
 * routine 6 (docs/s1disasm/sonic.asm:2969-2972, 2987-2995). {@code ExecuteObjects}
 * then keeps running them as ordinary in-level fixed-slot objects:
 * {@code Card_Wait} burns the {@code obTimeFrame} of {@code 1*60} frames set at
 * load (docs/s1disasm/_incObj/34 Title Cards.asm:74, 114-118), {@code
 * Card_MoveOut} slides the element toward {@code card_finalX} at {@code 2*$10}
 * pixels per frame (34 Title Cards.asm:122-141), and on the frame the element
 * is already at {@code card_finalX} {@code Card_ChangeArt} re-queues the
 * explosion and per-zone animal art whose VRAM the cards borrowed — but only
 * for the routine-4 level-name element — before deleting the object
 * (34 Title Cards.asm:155-168; {@code AddPLC} at sonic.asm:1297-1324, armed by
 * {@code RunPLC} at sonic.asm:1376-1417 and drained three patterns per ordinary
 * level VBlank by {@code ProcessPLC_3Tiles} at sonic.asm:1439-1450).
 *
 * <p>That tail is ROM object lifecycle, not presentation: it runs identically
 * whether or not the engine draws the sliding card sprites, so it lives here in
 * the fixed in-level object pass rather than in the title-card renderer.
 */
public final class Sonic1FixedTitleCardManager {
    static final int REWIND_STATE_BYTES = 9;

    /**
     * Resolves the S1 PLC service. Supplied by the owning event manager so this
     * element routes runtime access through the shared event helpers rather than
     * reaching the service locator itself (TestZoneEventRuntimeAccessGuard).
     */
    private final Supplier<Sonic1PlcService> plcServiceSupplier;

    Sonic1FixedTitleCardManager(Supplier<Sonic1PlcService> plcServiceSupplier) {
        this.plcServiceSupplier = plcServiceSupplier;
    }

    /** {@code Card_Index} routine 4 — the level-name element (34 Title Cards.asm:15-18). */
    private static final int ROUTINE_LEVEL_NAME_WAIT = 4;
    /** {@code move.w #1*60,obTimeFrame(a1)} (34 Title Cards.asm:74). */
    private static final int WAIT_FRAMES = 1 * 60;
    /** {@code moveq #2*$10,d1} (34 Title Cards.asm:126). */
    private static final int MOVE_OUT_SPEED = 2 * 0x10;
    /** {@code plcid_Explode} (_inc/Pattern Load Cues.asm:30). */
    private static final int PLC_EXPLODE = 2;
    /** {@code plcid_GHZAnimals}; per-zone entries follow in order (Pattern Load Cues.asm:55). */
    private static final int PLC_GHZ_ANIMALS = 21;

    private int routine;
    private int timeFrame;
    private int x;
    private int finalX;
    private int romZone;

    /**
     * Models {@code addq.b #2,(v_ttlcardname+obRoutine).w} at
     * {@code Level_StartGame} (docs/s1disasm/sonic.asm:2969): the level-name
     * element enters {@code Card_Wait} as the first ordinary level frame begins.
     *
     * <p>Every S1 level load reaches this boundary exactly once — a presented
     * card releases through {@code PostTitleCardDestination}, a skipped one
     * through {@code LevelManager.completeSkippedInitialTitleCardPresentation} —
     * so arming fully replaces any previous level's element state.
     *
     * @param progressionZone engine progression zone index
     * @param progressionAct  engine act index
     * @param romZoneId       {@code v_zone}, the animal-PLC selector read by
     *                        {@code Card_ChangeArt}
     */
    void armAtLevelStart(int progressionZone, int progressionAct, int romZoneId) {
        int[] conData = Sonic1TitleCardMappings.getConData(
                Sonic1TitleCardMappings.getConfigIndex(progressionZone, progressionAct));
        // Card_Loop loads the same word into obX and card_finalX, then the
        // move-in target into card_mainX (34 Title Cards.asm:48-52); the card
        // has reached card_mainX by the time control is released.
        finalX = conData[0];
        x = conData[1];
        romZone = romZoneId;
        timeFrame = WAIT_FRAMES;
        routine = ROUTINE_LEVEL_NAME_WAIT;
    }

    /** One {@code ExecuteObjects} pass over the level-name title-card element. */
    void update() {
        if (routine != ROUTINE_LEVEL_NAME_WAIT) {
            return;
        }
        // Card_Wait (34 Title Cards.asm:114-118).
        if (timeFrame != 0) {
            timeFrame--;
            return;
        }
        // Card_MoveOut (34 Title Cards.asm:122-141). The level-name element is
        // still displayed every frame across its $000..$120 travel, so obRender
        // bit 7 stays set and the off-screen shortcut at line 123 is not taken.
        if (x == finalX) {
            changeArt();
            routine = 0;
            return;
        }
        x += finalX >= x ? MOVE_OUT_SPEED : -MOVE_OUT_SPEED;
    }

    /**
     * {@code Card_ChangeArt}: {@code AddPLC plcid_Explode}, then
     * {@code AddPLC plcid_GHZAnimals + v_zone}
     * (34 Title Cards.asm:155-166).
     */
    private void changeArt() {
        if (romZone < 0) {
            return;
        }
        Sonic1PlcService plcService = plcServiceSupplier.get();
        if (plcService == null) {
            return;
        }
        try {
            plcService.transact(
                    Sonic1PlcService.appendOperation(PLC_EXPLODE),
                    Sonic1PlcService.appendOperation(PLC_GHZ_ANIMALS + romZone));
        } catch (Exception ignored) {
            // AddPLC has no failure path in ROM; a rejected append here means the
            // engine queue is not available (focused tests without a ROM module).
        }
    }

    void writeRewindState(ByteBuffer buf) {
        buf.put((byte) routine);
        buf.putShort((short) timeFrame);
        buf.putShort((short) x);
        buf.putShort((short) finalX);
        buf.putShort((short) romZone);
    }

    void readRewindState(ByteBuffer buf) {
        routine = buf.get() & 0xFF;
        timeFrame = buf.getShort() & 0xFFFF;
        x = buf.getShort();
        finalX = buf.getShort();
        romZone = buf.getShort();
    }

    /** Test visibility: whether the level-name element is still executing. */
    boolean isLive() {
        return routine == ROUTINE_LEVEL_NAME_WAIT;
    }
}
