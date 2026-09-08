package com.openggf.game.sonic3k.continuescreen;

import com.openggf.game.ContinueScreenProvider;
import com.openggf.game.GameServices;
import com.openggf.game.sonic3k.audio.Sonic3kMusic;

/**
 * ContinueScreen / loc_5C4D6 and its screen-local actors in the shipped S3&K ROM.
 * Screen fades belong to the shared flow; only admitted Process_Sprites ticks
 * advance this owner. Sprite coordinates below retain the VDP's $80 bias.
 */
public final class Sonic3kContinueScreenProvider implements ContinueScreenProvider {
    private final int playerMode;
    private final boolean skAlone;
    private Sonic3kContinueScreenArt art;
    private int vintRunCount;
    private int displayedVintRunCount;
    private int eggY;
    private int eggVelocity;
    private boolean eggDown;
    private boolean retainedSuper;
    private final RawAnimation soloAnimation = new RawAnimation();
    private final RawAnimation knucklesAnimation = new RawAnimation();
    private final RawAnimation iconTailAnimation = new RawAnimation();
    private int countdown;
    private int countdownTimer;
    private int iconCount;
    private int acceptedAge;
    private int idleAge;
    private boolean active;
    private boolean accepted;
    private boolean finished;

    public Sonic3kContinueScreenProvider() {
        this(0, false);
    }

    public Sonic3kContinueScreenProvider(int playerMode, boolean skAlone) {
        if (playerMode < 0 || playerMode > 3) {
            throw new IllegalArgumentException("S3K Player_mode must be 0..3");
        }
        this.playerMode = playerMode;
        this.skAlone = skAlone;
    }

    @Override
    public void initialize(int continues) {
        initialize(continues, 0);
    }

    @Override
    public void initialize(int continues, int vintRunCount) {
        var sprites = GameServices.spritesOrNull();
        var previousMain = sprites == null ? null : sprites.getMainPlayable();
        boolean superFlag = previousMain != null && previousMain.isSuperSonic();
        reset();
        // FixBugs=0: ContinueScreen does not clear Super_Sonic_Knux_flag.
        // FixBugs=1 clears it to avoid the mismatched Sonic mapping/DPLC art.
        retainedSuper = superFlag;
        soloAnimation.reset(0);
        knucklesAnimation.reset(playerMode == 3 ? 0 : 7);
        iconTailAnimation.reset(0);
        updateRawActors();
        this.vintRunCount = vintRunCount;
        displayedVintRunCount = vintRunCount;
        eggY = 0xF0 << 8;
        eggVelocity = 0xC0;
        // sub_5CB1C: reserve the current continue; zero and >10 clamp to ten.
        int count = continues & 0xff;
        iconCount = (count == 0 || count > 10 ? 10 : count) - 1;
        // The setup Process_Sprites executes loc_5C4E6 once before the fade.
        countdown = 9;
        countdownTimer = 59;
        active = true;
        GameServices.audio().playMusic(Sonic3kMusic.CONTINUE.id);
    }

    @Override
    public void update(boolean startPressed, boolean start2Pressed) {
        if (!active || finished) {
            return;
        }
        vintRunCount++;
        displayedVintRunCount = vintRunCount;
        idleAge++;
        if (accepted) {
            acceptedAge++;
            if (playerMode == 3 && acceptedAge > 1) {
                updateEggRobo();
            }
            // The controller follows Player_1/2 and Reserved_object_3 in RAM.
            // They first see its Start bit on the NEXT Process_Sprites tick.
            // Tails finishes at $1E0 after wait40, wait20 (fallthrough), then
            // thirty six-pixel steps. Sonic alone instead waits for Knuckles.
            finished = acceptedAge >= (playerMode == 3 ? 82 : skAlone ? 79 : 90);
        } else if (startPressed || start2Pressed) {
            accepted = true;
        } else if (--countdownTimer < 0) {
            countdownTimer = 59;
            if (countdown == 0) {
                finished = true;
            } else {
                countdown--;
            }
        }
        updateRawActors();
    }

    @Override
    public void advanceFadeFrame() {
        vintRunCount++;
    }

    private void updateRawActors() {
        iconTailAnimation.update(ICON_TAIL, 8);
        if (playerMode == 3) {
            if (acceptedAge < 49) {
                knucklesAnimation.update(KNUCKLES_IDLE, 11);
            } else if (acceptedAge == 49) {
                knucklesAnimation.reset(7);
            } else {
                knucklesAnimation.update(RUN, 2);
            }
        } else if (acceptedAge > 0 && acceptedAge <= 70) {
            knucklesAnimation.update(RUN, 2);
        }
        if (skAlone) {
            if (acceptedAge < 39) {
                soloAnimation.update(SOLO_IDLE, 11);
            } else if (acceptedAge == 39) {
                soloAnimation.frame = 0xBA;
            } else if (acceptedAge == 47) {
                soloAnimation.frame = 0x21;
            } else if (acceptedAge >= 48) {
                soloAnimation.update(RUN, 2);
            }
        }
    }

    private static final int[] ICON_TAIL = {4, 5, 6};
    private static final int[] RUN = {0x21, 0x22, 0x23, 0x24};
    private static final int[] SOLO_IDLE = {0xBD, 0xBE};
    private static final int[] KNUCKLES_IDLE = {2, 2, 4};

    /** Animate_RawNoSST[CheckResult] increments anim_frame before fetching. */
    private static final class RawAnimation {
        int frame;
        int index;
        int timer;

        void reset(int frame) {
            this.frame = frame;
            index = 0;
            timer = 0;
        }

        void update(int[] frames, int delay) {
            if (--timer >= 0) {
                return;
            }
            index++;
            if (index >= frames.length) {
                index = 0;
            }
            frame = frames[index];
            timer = delay;
        }
    }

    private void updateEggRobo() {
        // Swing_UpAndDown's upward limit falls through to the downward add.
        if (!eggDown) {
            eggVelocity -= 0x10;
            if (eggVelocity <= -0xC0) {
                eggDown = true;
                eggVelocity += 0x10;
            }
        } else {
            eggVelocity += 0x10;
            if (eggVelocity >= 0xC0) {
                eggDown = false;
                eggVelocity -= 0x10;
            }
        }
        eggY += eggVelocity;
    }

    @Override
    public void draw() {
        if (!active) {
            return;
        }
        if (art == null) {
            art = Sonic3kContinueScreenArt.load(retainedSuper);
        }
        art.draw(this);
    }

    @Override
    public void reset() {
        art = null;
        active = false;
        accepted = false;
        finished = false;
        acceptedAge = 0;
        idleAge = 0;
        eggDown = false;
        countdown = 0;
        countdownTimer = 0;
        iconCount = 0;
    }

    @Override public int currentVintRunCount() { return vintRunCount; }
    @Override public boolean isAccepted() { return accepted; }
    @Override public boolean isFinished() { return finished; }
    @Override public boolean savesOnContinue() { return true; }

    @Override
    public boolean clearsCheckpointOnContinue() {
        // loc_5C48A leaves Last_star_post_hit intact. Level/loc_6000 then
        // restores Saved_zone_and_act and the ordinary starpost snapshot.
        return false;
    }

    boolean retainedSuper() { return retainedSuper; }
    int soloFrame() { return soloAnimation.frame; }
    int knucklesFrame() { return knucklesAnimation.frame; }
    int iconTailFrame() { return iconTailAnimation.frame; }
    int playerMode() { return playerMode; }
    boolean skAlone() { return skAlone; }
    int vintRunCount() { return displayedVintRunCount; }
    int eggY() { return eggY >> 8; }
    int eggVelocity() { return eggVelocity; }
    int countdown() { return countdown; }
    int iconCount() { return iconCount; }
    int acceptedAge() { return acceptedAge; }
    int idleAge() { return idleAge; }
}
