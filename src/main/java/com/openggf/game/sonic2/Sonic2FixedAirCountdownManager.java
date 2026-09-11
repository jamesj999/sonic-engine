package com.openggf.game.sonic2;

import com.openggf.game.GameServices;
import com.openggf.game.ShieldType;
import com.openggf.level.LevelManager;
import com.openggf.level.objects.ObjectManager;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.sprites.playable.DrowningController;
import com.openggf.sprites.playable.SidekickCpuController;

import java.nio.ByteBuffer;
import java.util.List;

/**
 * Sonic 2 fixed {@code Sonic_BreathingBubbles}/{@code Tails_BreathingBubbles}
 * sidecars for Obj0A drowning bubbles.
 *
 * <p>S2 installs Obj0A in fixed level-only object RAM on water entry
 * (docs/s2disasm/s2.asm:36385-36387,39552-39554; s2.constants.asm:1149-1157).
 * The fixed slots execute as part of {@code RunObjects}, after dynamic SST
 * slots, and visible mouth bubbles are still allocated with the lowest-free
 * dynamic {@code AllocateObject} path (docs/s2disasm/s2.asm:5094-5095,
 * 42088-42214).
 */
final class Sonic2FixedAirCountdownManager {
    static final int REWIND_STATE_BYTES = FixedController.REWIND_STATE_BYTES * 2;

    private static final int SUBTYPE_FIXED = 0x81;
    private static final int ROUTINE_INIT = 0x00;
    private static final int ROUTINE_FIXED_COUNTDOWN = 0x0A;
    private static final int INITIAL_COUNTER = 59;

    private final FixedController p1 = new FixedController("Sonic_BreathingBubbles");
    private final FixedController p2 = new FixedController("Tails_BreathingBubbles");

    void reset() {
        p1.reset();
        p2.reset();
    }

    void update() {
        p1.update(primaryPlayer());
        p2.update(firstSidekick());
    }

    boolean ownsCadenceFor(AbstractPlayableSprite owner) {
        if (owner == null) {
            return false;
        }
        return owner == primaryPlayer() || owner == firstSidekick();
    }

    void writeRewindState(ByteBuffer buf) {
        p1.writeRewindState(buf);
        p2.writeRewindState(buf);
    }

    void readRewindState(ByteBuffer buf) {
        p1.readRewindState(buf);
        p2.readRewindState(buf);
    }

    private AbstractPlayableSprite primaryPlayer() {
        try {
            return GameServices.camera().getFocusedSprite();
        } catch (IllegalStateException ex) {
            return null;
        }
    }

    private AbstractPlayableSprite firstSidekick() {
        try {
            List<AbstractPlayableSprite> sidekicks = GameServices.sprites().getRegisteredSidekicks();
            return sidekicks.isEmpty() ? null : sidekicks.getFirst();
        } catch (IllegalStateException ex) {
            return null;
        }
    }

    private static final class FixedController {
        private static final int REWIND_STATE_BYTES = 14;

        private final String label;
        private boolean installed;
        private int routine;
        private int subtype;
        private int obj32;
        private int obj33;
        private int obj34;
        private int obj36;
        private int obj38;
        private int obj3a;
        private int obj2c;

        FixedController(String label) {
            this.label = label;
        }

        void reset() {
            installed = false;
            routine = 0;
            subtype = 0;
            obj32 = 0;
            obj33 = 0;
            obj34 = 0;
            obj36 = 0;
            obj38 = 0;
            obj3a = 0;
            obj2c = 0;
        }

        void writeRewindState(ByteBuffer buf) {
            buf.put((byte) (installed ? 1 : 0));
            buf.put((byte) routine);
            buf.put((byte) subtype);
            buf.put((byte) obj32);
            buf.put((byte) obj33);
            buf.put((byte) obj34);
            buf.putShort((short) obj36);
            buf.putShort((short) obj38);
            buf.putShort((short) obj3a);
            buf.putShort((short) obj2c);
        }

        void readRewindState(ByteBuffer buf) {
            installed = buf.get() != 0;
            routine = buf.get() & 0xFF;
            subtype = buf.get() & 0xFF;
            obj32 = buf.get() & 0xFF;
            obj33 = buf.get() & 0xFF;
            obj34 = buf.get() & 0xFF;
            obj36 = buf.getShort() & 0xFFFF;
            obj38 = buf.getShort();
            obj3a = buf.getShort();
            obj2c = buf.getShort();
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
                    obj33 = subtype & 0x7F;
                }
            }
            if (routine == ROUTINE_FIXED_COUNTDOWN) {
                countdown(owner);
            }
        }

        private void countdown(AbstractPlayableSprite owner) {
            if (obj2c != 0) {
                obj2c = signed16(obj2c - 1);
                return;
            }
            if (!owner.isInWater()
                    || owner.getDead()
                    || isInDeadRoutine(owner)
                    || owner.isDrowningPreDeath()
                    || owner.getShieldType() == ShieldType.BUBBLE) {
                return;
            }

            obj38 = signed16(obj38 - 1);
            if (obj38 < 0) {
                obj38 = INITIAL_COUNTER;
                obj36 = 1;
                obj34 = owner.currentRng().nextRaw() & 1;

                DrowningController drowning = owner.getDrowningController();
                DrowningController.FixedCountdownAirEvent airEvent = drowning != null
                        ? drowning.performFixedCountdownAirEvent(isPrimaryPlayer(owner))
                        : new DrowningController.FixedCountdownAirEvent(30, 29, -1, false);
                if (airEvent.airBefore() <= 12) {
                    obj32 = signedByte(obj32 - 1);
                    if ((byte) obj32 < 0) {
                        obj32 = obj33 & 0xFF;
                        obj36 |= 0x80;
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

            if (obj36 != 0) {
                obj3a = signed16(obj3a - 1);
                if (obj3a < 0) {
                    makeItem(owner);
                }
            }
        }

        private void makeItem(AbstractPlayableSprite owner) {
            obj3a = (owner.currentRng().nextRaw() & 0x0F) + 8;
            if (!hasFreeDynamicSlot(owner)) {
                return;
            }

            int countdownNumber = -1;
            if ((obj36 & 0x80) != 0) {
                int air = currentAir(owner);
                int numberSubtype = (air & 0xFF) >> 1;
                int random = owner.currentRng().nextRaw();
                if ((random & 3) == 0 && (obj36 & 0x40) == 0) {
                    obj36 |= 0x40;
                    countdownNumber = numberSubtype;
                }
                if ((byte) obj34 == 0 && (obj36 & 0x40) == 0) {
                    obj36 |= 0x40;
                    countdownNumber = numberSubtype;
                }
            }

            DrowningController drowning = owner.getDrowningController();
            if (drowning != null) {
                drowning.spawnFixedCountdownBubble(countdownNumber, false);
            }

            obj34 = (obj34 - 1) & 0xFF;
            if ((byte) obj34 < 0) {
                obj36 = 0;
            }
        }

        private boolean hasFreeDynamicSlot(AbstractPlayableSprite owner) {
            LevelManager level = owner.currentLevelManagerIfAvailable();
            ObjectManager objectManager = level != null ? level.getObjectManager() : null;
            return objectManager == null || objectManager.hasFreeDynamicSlot();
        }

        private int currentAir(AbstractPlayableSprite owner) {
            DrowningController drowning = owner.getDrowningController();
            return drowning != null ? drowning.getRemainingAir() : 30;
        }

        /**
         * ROM Obj0A_Countdown returns before decrementing obj0a_timer while the
         * bound player's routine is >= 6 (cmpi.b #6,routine(a2) / bhs.w return,
         * docs/s2disasm/s2.asm:42095-42096). The CPU sidekick's death keeps the
         * engine dead flag clear and models routine 6 as DEAD_FALLING, so the
         * countdown must honour that state too or it consumes RandomNumber
         * draws the ROM never makes while Tails is dead underwater.
         */
        private boolean isInDeadRoutine(AbstractPlayableSprite owner) {
            SidekickCpuController cpu = owner.getCpuController();
            return owner.isCpuControlled()
                    && cpu != null
                    && cpu.getState() == SidekickCpuController.State.DEAD_FALLING;
        }

        private boolean isPrimaryPlayer(AbstractPlayableSprite owner) {
            try {
                return owner == GameServices.camera().getFocusedSprite();
            } catch (IllegalStateException ex) {
                return false;
            }
        }

        private static int signed16(int value) {
            return (short) (value & 0xFFFF);
        }

        private static int signedByte(int value) {
            return (byte) (value & 0xFF);
        }

        @Override
        public String toString() {
            return String.format("%s r=%02X sub=%02X $32=%02X $33=%02X $34=%02X $36=%04X $38=%04X $3A=%04X $2C=%04X",
                    label,
                    routine & 0xFF,
                    subtype & 0xFF,
                    obj32 & 0xFF,
                    obj33 & 0xFF,
                    obj34 & 0xFF,
                    obj36 & 0xFFFF,
                    obj38 & 0xFFFF,
                    obj3a & 0xFFFF,
                    obj2c & 0xFFFF);
        }
    }
}
