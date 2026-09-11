package com.openggf.game.sonic1.events;

import com.openggf.game.ShieldType;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.sprites.playable.DrowningController;

import java.util.function.Supplier;

/**
 * Sonic 1 fixed {@code v_sonicbubbles} sidecar for Obj0A drowning bubbles.
 *
 * <p>S1 installs {@code id_DrownCount/$81} in fixed object RAM when Sonic
 * enters LZ water (docs/s1disasm/s1disasm/_incObj/01 Sonic.asm:236-241).
 * That sidecar owns {@code drown_time}, {@code objoff_34}, {@code objoff_36},
 * and {@code objoff_3A}; visible mouth bubbles are still allocated through
 * {@code FindFreeObj} (docs/s1disasm/s1disasm/_incObj/0A LZ Drowning
 * Countdown.asm:181-296).
 */
final class Sonic1FixedAirCountdownManager {
    static final int REWIND_STATE_BYTES = FixedController.REWIND_STATE_BYTES;

    private static final int SUBTYPE_FIXED = 0x81;
    private static final int ROUTINE_INIT = 0x00;
    private static final int ROUTINE_FIXED_COUNTDOWN = 0x0A;
    private static final int INITIAL_COUNTER = 59;

    private final FixedController p1 = new FixedController();
    private final Supplier<AbstractPlayableSprite> primaryPlayerSupplier;

    Sonic1FixedAirCountdownManager(Supplier<AbstractPlayableSprite> primaryPlayerSupplier) {
        this.primaryPlayerSupplier = primaryPlayerSupplier;
    }

    void reset() {
        p1.reset();
    }

    void update() {
        p1.update(primaryPlayer());
    }

    boolean ownsCadenceFor(AbstractPlayableSprite owner) {
        return owner != null && owner == primaryPlayer();
    }

    void writeRewindState(java.nio.ByteBuffer buf) {
        p1.writeRewindState(buf);
    }

    void readRewindState(java.nio.ByteBuffer buf) {
        p1.readRewindState(buf);
    }

    private AbstractPlayableSprite primaryPlayer() {
        return primaryPlayerSupplier.get();
    }

    private static final class FixedController {
        private static final int REWIND_STATE_BYTES = 14;

        private boolean installed;
        private int routine;
        private int subtype;
        private int obj32;
        private int obj33;
        private int obj34;
        private int obj36;
        private int drownTime;
        private int obj3a;
        private int obj2c;

        void reset() {
            installed = false;
            routine = 0;
            subtype = 0;
            obj32 = 0;
            obj33 = 0;
            obj34 = 0;
            obj36 = 0;
            drownTime = 0;
            obj3a = 0;
            obj2c = 0;
        }

        void writeRewindState(java.nio.ByteBuffer buf) {
            buf.put((byte) (installed ? 1 : 0));
            buf.put((byte) routine);
            buf.put((byte) subtype);
            buf.put((byte) obj32);
            buf.put((byte) obj33);
            buf.put((byte) obj34);
            buf.putShort((short) obj36);
            buf.putShort((short) drownTime);
            buf.putShort((short) obj3a);
            buf.putShort((short) obj2c);
        }

        void readRewindState(java.nio.ByteBuffer buf) {
            installed = buf.get() != 0;
            routine = buf.get() & 0xFF;
            subtype = buf.get() & 0xFF;
            obj32 = buf.get() & 0xFF;
            obj33 = buf.get() & 0xFF;
            obj34 = buf.get() & 0xFF;
            obj36 = buf.getShort() & 0xFFFF;
            drownTime = buf.getShort();
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
                    || owner.isDrowningPreDeath()
                    || owner.getShieldType() == ShieldType.BUBBLE) {
                return;
            }

            drownTime = signed16(drownTime - 1);
            if (drownTime < 0) {
                drownTime = INITIAL_COUNTER;
                obj36 = 1;
                obj34 = owner.currentRng().nextRaw() & 1;

                DrowningController drowning = owner.getDrowningController();
                DrowningController.FixedCountdownAirEvent airEvent = drowning != null
                        ? drowning.performFixedCountdownAirEvent(true)
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
            // ROM Drown_Countdown .makenum (docs/s1disasm/_incObj/0A LZ Drowning
            // Countdown.asm:280-282): RandomNumber & $F -> objoff_3A. Consumed
            // ALWAYS, BEFORE the FindFreeObj test below.
            obj3a = owner.currentRng().nextRaw() & 0x0F;

            // ROM .makenum (0A LZ Drowning Countdown.asm:283-284):
            //   jsr (FindFreeObj).l / bne.w .nocountdown
            // When the dynamic SST is full, the ROM has already reset obj3A
            // (consuming one RandomNumber) but then bails: it does NOT spawn a
            // bubble, does NOT consume the large-bubble RandomNumber, does NOT
            // decrement objoff_34, and does NOT clear objoff_36. The countdown
            // therefore stays "in production" and retries on the next obj3A
            // interval, consuming one RandomNumber each retry until a slot frees.
            // Mirroring this is required for global RNG cadence parity (LZ2 f7800
            // air-bubble drift): the old code always spawned and exhausted
            // objoff_34, stopping ~5 retries early and leaving the engine RNG
            // behind the ROM by the time the LZ air-bubble maker spawns.
            if (!hasFreeDynamicSlot(owner)) {
                return;
            }

            int countdownNumber = -1;
            if ((obj36 & 0x80) != 0) {
                int air = currentAir(owner);
                int numberSubtype = (air & 0xFFFF) >> 1;
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
                drowning.spawnFixedCountdownBubble(countdownNumber);
            }

            obj34 = (obj34 - 1) & 0xFF;
            if ((byte) obj34 < 0) {
                obj36 = 0;
            }
        }

        private boolean hasFreeDynamicSlot(AbstractPlayableSprite owner) {
            var levelManager = owner.currentLevelManagerIfAvailable();
            if (levelManager == null || levelManager.getObjectManager() == null) {
                // No object pool available (e.g. headless physics test): treat as
                // "slot free" so the spawn path proceeds as before.
                return true;
            }
            return levelManager.getObjectManager().hasFreeDynamicSlot();
        }

        private int currentAir(AbstractPlayableSprite owner) {
            DrowningController drowning = owner.getDrowningController();
            return drowning != null ? drowning.getRemainingAir() : 30;
        }

        private static int signed16(int value) {
            return (short) (value & 0xFFFF);
        }

        private static int signedByte(int value) {
            return (byte) (value & 0xFF);
        }
    }
}
