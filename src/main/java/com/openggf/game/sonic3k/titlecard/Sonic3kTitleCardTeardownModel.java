package com.openggf.game.sonic3k.titlecard;

/**
 * Models the residual ROM lifetime of the title-card owner object after the
 * level loop starts.
 *
 * <p>Skipping the title-card <em>presentation</em> does not shorten the
 * title-card <em>owner</em>'s lifetime. {@code Obj_TitleCard} keeps running as
 * an ordinary object in {@code Obj_TitleCardWait2}
 * ({@code docs/skdisasm/sonic3k.asm:62249-62261}) and only reaches
 * {@code loc_2D8CA}'s {@code LoadEnemyArt}
 * ({@code docs/skdisasm/sonic3k.asm:62295-62301}) once two ROM conditions
 * clear in order:
 *
 * <ol>
 *   <li>{@code objoff_2E}, seeded to {@code $16} immediately before
 *       {@code LevelLoop} ({@code docs/skdisasm/sonic3k.asm:7878}), decrements
 *       once per level frame ({@code 62249-62253}).</li>
 *   <li>{@code objoff_30}, the count of live card elements incremented once per
 *       element at creation ({@code docs/skdisasm/sonic3k.asm:62190}), drains to
 *       zero. While it is non-zero the owner only bumps the stagger counter
 *       {@code objoff_32} ({@code 62256-62261}); each element consumes that
 *       counter to leave the screen and decrements {@code objoff_30} on the
 *       first frame it renders off-screen.</li>
 * </ol>
 *
 * <p>The drain is therefore driven by element state, not by a frame constant:
 * this class steps the actual {@code ObjArray_TtlCard} elements
 * ({@code docs/skdisasm/sonic3k.asm:62450-62478}) through
 * {@code Obj_TitleCardElement} ({@code 62356-62378}) and
 * {@code Obj_TitleCardRedBanner} ({@code 62303-62329}), and retires each one
 * using the same visibility test {@code Render_Sprites} applies to a
 * {@code render_flags} {@code $40} sprite
 * ({@code docs/skdisasm/sonic3k.asm:36440-36468}).
 *
 * <p>Working the two phases through gives final-child retirement on provider
 * tick 34 and owner release on provider tick 35 for the standard four-element
 * card: zero-based trace frames 0-21 drain {@code objoff_2E}
 * ({@code $16} = 22), trace frame 22 first bumps {@code objoff_32}, and the longest-lived element
 * ({@code Obj_TitleCardName}, {@code objoff_28} = 3, {@code width_pixels} =
 * {@code $80}) starts moving on frame 24 and needs nine {@code $20} steps to
 * pass the {@code x_pos >= 576} cull from its {@code objoff_46} rest position
 * {@code $120}. Its trace-frame-32 draw records the cull, and the child's next
 * dispatch retires it on provider tick 34 (zero-based trace frame 33). The
 * lower-slot owner has already returned
 * from its dispatch, so it reaches {@code LoadEnemyArt} on provider tick 35
 * (trace frame 34).
 *
 * <p>Recorded ROM ground truth agrees on both counts: every non-AIZ S3K zone
 * first becomes Kos-queue busy on zero-based trace frame 34, and ICZ's recorded
 * {@code KOS_DECOMPRESSION_QUEUE} completion whose fingerprint matches the
 * engine's enemy-art submission is admitted on that frame.
 *
 * <p>The owner runs before its higher-slot children in {@code ExecuteObjects}.
 * It therefore tests the still-nonzero {@code objoff_30} and returns before the
 * final child decrements that word. The owner can first observe zero on its
 * following dispatch; the fingerprint-matched recorded completion confirms
 * that ordering.
 *
 * <p>The model is a pure function of ROM data, so its progress is fully
 * described by the number of ticks taken; {@link #ticksElapsed()} and
 * {@link #restoreTicks(int)} give rewind a single scalar to carry.
 */
public final class Sonic3kTitleCardTeardownModel {

    /** {@code move.w #$16,...objoff_2E} — sonic3k.asm:7878. */
    private static final int WAIT2_INITIAL_COUNTER = 0x16;

    /** {@code addi.w #$20,x_pos} / {@code subi.w #$20,y_pos} — 62367, 62318. */
    private static final int EXIT_STEP = 0x20;

    /** {@code move.b #$70,height_pixels} — Obj_TitleCardRedBanner, 62327. */
    private static final int RED_BANNER_HEIGHT = 0x70;

    /** Screen extents used by Render_Sprites' bounds test — 36447-36467. */
    private static final int SCREEN_ORIGIN = 128;
    private static final int SCREEN_WIDTH = 320;
    private static final int SCREEN_HEIGHT = 224;

    /**
     * A single title-card element in its post-entry resting state.
     *
     * <p>{@code x}/{@code y} start at the element's {@code objoff_46} target,
     * because the entry animation ({@code loc_2D90A} / {@code loc_2D984}) has
     * already completed by the time {@code LevelLoop} runs. {@code stagger} is
     * the element's {@code objoff_28} threshold and {@code exitOnY} selects the
     * red banner's vertical exit.
     */
    private static final class Element {
        private final int stagger;
        private final int width;
        private final int height;
        private final boolean exitOnY;
        private int x;
        private int y;
        private boolean onScreen = true;
        private boolean retired;

        Element(int x, int y, int width, int height, int stagger, boolean exitOnY) {
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
            this.stagger = stagger;
            this.exitOnY = exitOnY;
        }

        /** Render_Sprites bounds test for a render_flags $40 sprite — 36444-36468. */
        private boolean visible() {
            int dx = x - SCREEN_ORIGIN;
            if (dx + width < 0 || dx - width >= SCREEN_WIDTH) {
                return false;
            }
            int dy = y - SCREEN_ORIGIN;
            return dy + height >= 0 && dy - height < SCREEN_HEIGHT;
        }

        /**
         * Runs one frame of Obj_TitleCardElement / Obj_TitleCardRedBanner.
         *
         * @return {@code true} when this frame decrements {@code objoff_30}
         */
        boolean step(int cardStagger) {
            if (retired) {
                return false;
            }
            if (!onScreen) {
                // subq.w #1,$30(a1); Delete_Current_Sprite — 62360-62361, 62311-62312
                retired = true;
                return true;
            }
            if (cardStagger >= stagger) {
                if (exitOnY) {
                    y -= EXIT_STEP;
                } else {
                    x += EXIT_STEP;
                }
            }
            onScreen = visible();
            return false;
        }
    }

    private int wait2Counter = WAIT2_INITIAL_COUNTER;
    private int cardStagger;
    private int elementsLeft;
    private int ticksElapsed;
    private boolean complete;
    private Element[] elements = newStandardCard();

    /**
     * {@code ObjArray_TtlCard} — sonic3k.asm:62450-62478. Fields per entry are
     * {@code objoff_46}, {@code x_pos}, {@code y_pos}, {@code mapping_frame},
     * {@code width_pixels}, {@code objoff_28}; the elements rest at
     * {@code objoff_46} once the entry animation finishes.
     */
    private static Element[] newStandardCard() {
        return new Element[] {
                // Obj_TitleCardName: $46=$120, width $80, $28=3
                new Element(0x120, 0xE0, 0x80, 0, 3, false),
                // Obj_TitleCardElement: $46=$17C, width $24, $28=5
                new Element(0x17C, 0x100, 0x24, 0, 5, false),
                // Obj_TitleCardAct: $46=$184, width $1C, $28=7
                new Element(0x184, 0x120, 0x1C, 0, 7, false),
                // Obj_TitleCardRedBanner: $46=$C0 on y, width 0, $28=1
                new Element(0xE0, 0xC0, 0, RED_BANNER_HEIGHT, 1, true),
        };
    }

    public Sonic3kTitleCardTeardownModel() {
        elementsLeft = elements.length;
    }

    /**
     * Advances one level-loop frame.
     *
     * @return {@code true} on the frame the owner reaches {@code loc_2D8CA}
     *         and calls {@code LoadEnemyArt}
     */
    public boolean tick() {
        if (complete) {
            return false;
        }
        ticksElapsed++;
        // Obj_TitleCardWait2: tst.w $2E; subq.w #1,$2E; rts — 62249-62253
        if (wait2Counter > 0) {
            wait2Counter--;
            return false;
        }
        // loc_2D862: the lower-slot owner tests/increments/returns before the
        // higher-slot children execute and can decrement $30 — 62256-62261.
        if (elementsLeft > 0) {
            cardStagger++;
            for (Element element : elements) {
                if (element.step(cardStagger)) {
                    elementsLeft--;
                }
            }
            return false;
        }
        // On the following owner dispatch loc_2D86E falls through to
        // loc_2D8CA: jsr LoadEnemyArt — 62263, 62295-62299.
        complete = true;
        return true;
    }

    /** Whether the owner has already reached {@code LoadEnemyArt}. */
    public boolean isComplete() {
        return complete;
    }

    /** Level frames stepped so far; the model's whole rewindable state. */
    public int ticksElapsed() {
        return ticksElapsed;
    }

    /** Replays the model from its ROM seed to {@code ticks} elapsed frames. */
    public void restoreTicks(int ticks) {
        wait2Counter = WAIT2_INITIAL_COUNTER;
        cardStagger = 0;
        elements = newStandardCard();
        elementsLeft = elements.length;
        ticksElapsed = 0;
        complete = false;
        for (int i = 0; i < ticks; i++) {
            tick();
        }
    }
}
