package com.openggf.game.sonic3k;

import com.openggf.game.GameServices;
import com.openggf.game.PlayableEntity;
import com.openggf.game.ShieldType;
import com.openggf.game.sonic3k.objects.S3kAirCountdownObjectInstance;
import com.openggf.level.LevelManager;
import com.openggf.level.objects.ObjectManager;
import com.openggf.physics.Direction;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.sprites.playable.DrowningController;

import java.util.List;

/**
 * S3K fixed {@code Breathing_bubbles}/{@code Breathing_bubbles_P2} sidecars.
 *
 * <p>These are fixed object-RAM entries, not dynamic SST objects:
 * docs/skdisasm/sonic3k.constants.asm:311-312. Player water code installs the
 * controller directly (sonic3k.asm:22221-22224,27436-27439), and the fixed
 * object pass runs after dynamic object RAM but before {@code ScreenEvents}
 * (sonic3k.asm:7893-7898,35965). Visible bubbles are still allocated through
 * the normal dynamic {@code AllocateObject} path at sonic3k.asm:33591-33610.
 */
final class S3kFixedAirCountdownManager {
    static final int REWIND_STATE_BYTES = FixedController.REWIND_STATE_BYTES * 2;

    private static final int SUBTYPE_FIXED = 0x81;
    private static final int ROUTINE_INIT = 0x00;
    private static final int ROUTINE_FIXED_COUNTDOWN = 0x0A;
    private static final int INITIAL_COUNTER = 59;
    private static final int REGULAR_BUBBLE_SUBTYPE = 0x06;
    private static final int MOUTH_OFFSET = 6;

    private final FixedController p1 = new FixedController("Breathing_bubbles");
    private final FixedController p2 = new FixedController("Breathing_bubbles_P2");

    void reset() {
        p1.reset();
        p2.reset();
    }

    void update() {
        AbstractPlayableSprite player = GameServices.camera().getFocusedSprite();
        p1.update(player);

        List<AbstractPlayableSprite> sidekicks = GameServices.sprites().getRegisteredSidekicks();
        AbstractPlayableSprite sidekick = sidekicks.isEmpty() ? null : sidekicks.getFirst();
        p2.update(sidekick);
    }

    void processInitialP1Slot(AbstractPlayableSprite player) {
        p1.update(player);
    }

    void processInitialP2Slot(AbstractPlayableSprite sidekick) {
        p2.update(sidekick);
    }

    boolean ownsCadenceFor(AbstractPlayableSprite owner) {
        if (owner == null) {
            return false;
        }
        if (owner == GameServices.camera().getFocusedSprite()) {
            return true;
        }
        List<AbstractPlayableSprite> sidekicks = GameServices.sprites().getRegisteredSidekicks();
        return !sidekicks.isEmpty() && owner == sidekicks.getFirst();
    }

    void writeRewindState(java.nio.ByteBuffer buf) {
        p1.writeRewindState(buf);
        p2.writeRewindState(buf);
    }

    void readRewindState(java.nio.ByteBuffer buf) {
        p1.readRewindState(buf);
        p2.readRewindState(buf);
    }

    private static final class FixedController {
        private static final int REWIND_STATE_BYTES = 14;

        private final String label;
        private boolean installed;
        private int routine;
        private int subtype;
        private int obj30;
        private int obj36;
        private int obj37;
        private int obj38;
        private int obj3a;
        private int obj3c;
        private int obj3e;

        FixedController(String label) {
            this.label = label;
        }

        void reset() {
            installed = false;
            routine = 0;
            subtype = 0;
            obj30 = 0;
            obj36 = 0;
            obj37 = 0;
            obj38 = 0;
            obj3a = 0;
            obj3c = 0;
            obj3e = 0;
        }

        void writeRewindState(java.nio.ByteBuffer buf) {
            buf.put((byte) (installed ? 1 : 0));
            buf.put((byte) routine);
            buf.put((byte) subtype);
            buf.putShort((short) obj30);
            buf.put((byte) obj36);
            buf.put((byte) obj37);
            buf.put((byte) obj38);
            buf.putShort((short) obj3a);
            buf.putShort((short) obj3c);
            buf.putShort((short) obj3e);
        }

        void readRewindState(java.nio.ByteBuffer buf) {
            installed = buf.get() != 0;
            routine = buf.get() & 0xFF;
            subtype = buf.get() & 0xFF;
            obj30 = buf.getShort() & 0xFFFF;
            obj36 = buf.get() & 0xFF;
            obj37 = buf.get() & 0xFF;
            obj38 = buf.get() & 0xFF;
            obj3a = buf.getShort() & 0xFFFF;
            obj3c = buf.getShort();
            obj3e = buf.getShort();
        }

        void update(AbstractPlayableSprite owner) {
            if (owner == null) {
                return;
            }
            if (!installed) {
                if (!owner.isInWater()) {
                    return;
                }
                installed = true;
                routine = ROUTINE_INIT;
                subtype = SUBTYPE_FIXED;
                // Player_ResetAirTimer is part of the water-entry install path
                // (sonic3k.asm:33663-33688). The controller fields above stay
                // untouched on later water exit/re-entry; only air_left resets.
                DrowningController drowning = owner.getDrowningController();
                if (drowning != null) {
                    drowning.replenishAir();
                }
            }
            execute(owner);
        }

        private void execute(AbstractPlayableSprite owner) {
            if (routine == ROUTINE_INIT) {
                routine += 2;
                if ((subtype & 0x80) != 0) {
                    routine += 8;
                    obj37 = subtype & 0x7F;
                }
            }
            if (routine == ROUTINE_FIXED_COUNTDOWN) {
                countdown(owner);
            }
        }

        private void countdown(AbstractPlayableSprite owner) {
            if (!owner.isInWater()
                    || owner.getDead()
                    || owner.isDrowningPreDeath()
                    || owner.getShieldType() == ShieldType.BUBBLE) {
                return;
            }

            obj3c = signed16(obj3c - 1);
            if (obj3c < 0) {
                obj3c = INITIAL_COUNTER;
                obj3a = 1;
                obj38 = owner.currentRng().nextRaw() & 1;

                DrowningController drowning = owner.getDrowningController();
                DrowningController.FixedCountdownAirEvent airEvent = drowning != null
                        ? drowning.performFixedCountdownAirEvent(isPrimaryPlayer(owner))
                        : new DrowningController.FixedCountdownAirEvent(30, 29, -1, false);
                if (airEvent.airBefore() <= 12) {
                    obj36 = signedByte(obj36 - 1);
                    if ((byte) obj36 < 0) {
                        obj36 = obj37 & 0xFF;
                        obj3a |= 0x80;
                    }
                }
                if (drowning != null) {
                    drowning.setRemainingAirFromFixedCountdown(airEvent.airAfter());
                }
                if (airEvent.drowned()) {
                    if (drowning != null) {
                        drowning.resetAirTimerFromFixedCountdownDeath();
                    }
                    owner.applyDrownDeath();
                    return;
                }
                makeItem(owner);
                return;
            }

            if (obj3a != 0) {
                obj3e = signed16(obj3e - 1);
                if (obj3e < 0) {
                    makeItem(owner);
                }
            }
        }

        private void makeItem(AbstractPlayableSprite owner) {
            int random = owner.currentRng().nextRaw();
            obj3e = (random & 0x0F) + 8;

            ObjectManager objectManager = objectManager();
            if (objectManager != null) {
                int offset = owner.getDirection() == Direction.LEFT ? -MOUTH_OFFSET : MOUTH_OFFSET;
                int angle = owner.getDirection() == Direction.LEFT ? 0x40 : 0x00;
                CountdownChild countdownChild = countdownChildFor(owner);
                S3kAirCountdownObjectInstance child = new S3kAirCountdownObjectInstance(
                        owner.getCentreX() + offset,
                        owner.getCentreY(),
                        countdownChild.subtype(),
                        angle,
                        countdownChild.displayTimer(),
                        isPrimaryPlayer(owner));
                objectManager.addDynamicObject(child);
            }

            obj38 = (obj38 - 1) & 0xFF;
            if ((byte) obj38 < 0) {
                obj3a = 0;
            }
        }

        private CountdownChild countdownChildFor(AbstractPlayableSprite owner) {
            int subtypeForChild = REGULAR_BUBBLE_SUBTYPE;
            int displayTimer = 0;
            DrowningController drowning = owner.getDrowningController();
            int air = drowning != null ? drowning.getRemainingAir() : 30;
            if ((obj3a & 0x80) != 0 && air < 12) {
                int numberSubtype = (air & 0xFF) >> 1;
                int random = owner.currentRng().nextRaw();
                if ((random & 3) == 0 && (obj3a & 0x40) == 0) {
                    obj3a |= 0x40;
                    subtypeForChild = numberSubtype;
                    displayTimer = 0x1C;
                }
                if ((byte) obj38 == 0 && (obj3a & 0x40) == 0) {
                    obj3a |= 0x40;
                    subtypeForChild = numberSubtype;
                    displayTimer = 0x1C;
                }
            }
            return new CountdownChild(subtypeForChild, displayTimer);
        }

        private ObjectManager objectManager() {
            LevelManager level = GameServices.levelOrNull();
            return level != null ? level.getObjectManager() : null;
        }

        private static int signed16(int value) {
            return (short) (value & 0xFFFF);
        }

        private static int signedByte(int value) {
            return (byte) (value & 0xFF);
        }

        private boolean isPrimaryPlayer(AbstractPlayableSprite owner) {
            try {
                return owner == GameServices.camera().getFocusedSprite();
            } catch (IllegalStateException ex) {
                return false;
            }
        }

        private record CountdownChild(int subtype, int displayTimer) {
        }

        @Override
        public String toString() {
            return String.format("%s r=%02X sub=%02X $30=%04X $36=%04X $37=%04X $38=%04X $3A=%04X $3C=%04X $3E=%04X",
                    label,
                    routine & 0xFF,
                    subtype & 0xFF,
                    obj30 & 0xFFFF,
                    obj36 & 0xFFFF,
                    obj37 & 0xFFFF,
                    obj38 & 0xFFFF,
                    obj3a & 0xFFFF,
                    obj3c & 0xFFFF,
                    obj3e & 0xFFFF);
        }
    }
}
