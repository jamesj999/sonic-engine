package com.openggf.game.sonic1.objects;

import com.openggf.game.PlayableEntity;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectConstructionContext;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectLifetimeOps;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.ObjectServices;

/** Owns S1's fixed {@code v_endcard} SST slot. */
public final class Sonic1FixedEndCardSlot {
    public static final int SLOT = 23;

    private Sonic1FixedEndCardSlot() {
    }

    public record ResultsData(
            int elapsedSeconds,
            int ringCount,
            int actNumber,
            boolean specialStageAfter) {
    }

    public enum ClaimState {
        NEW_UNCOMMITTED,
        EXISTING_UNCOMMITTED,
        EXISTING_COMMITTED,
        INVALID_OCCUPANT
    }

    public record ClaimResult(
            ClaimState state,
            Sonic1ResultsScreenObjectInstance card) {
        public Sonic1ResultsScreenObjectInstance requireCard() {
            if (card == null) {
                throw new IllegalStateException(
                        "S1 fixed v_endcard slot " + SLOT + " has an invalid occupant");
            }
            return card;
        }
    }

    public static ClaimResult claim(ObjectServices services, ResultsData data) {
        if (services == null || data == null) {
            throw new NullPointerException("services and results data are required");
        }
        ObjectManager objects = services.objectManager();
        if (objects == null) {
            throw new IllegalStateException("S1 fixed v_endcard requires an object manager");
        }
        for (ObjectInstance instance : objects.getActiveObjects()) {
            if (!(instance instanceof AbstractObjectInstance object)
                    || instance.isDestroyed()
                    || object.getSlotIndex() != SLOT) {
                continue;
            }
            if (!(instance instanceof Sonic1ResultsScreenObjectInstance card)) {
                return new ClaimResult(ClaimState.INVALID_OCCUPANT, null);
            }
            if (data.specialStageAfter()) {
                card.setSpecialStageAfter(true);
            }
            return new ClaimResult(
                    card.isResultsPlcCommitted()
                            ? ClaimState.EXISTING_COMMITTED
                            : ClaimState.EXISTING_UNCOMMITTED,
                    card);
        }

        Sonic1ResultsScreenObjectInstance card = ObjectConstructionContext.construct(
                services,
                () -> Sonic1ResultsScreenObjectInstance.awaitingResultsPlc(
                        data.elapsedSeconds(), data.ringCount(), data.actNumber()));
        ObjectLifetimeOps.addDynamicAtReservedSlot(objects, card, SLOT);
        if (data.specialStageAfter()) {
            card.setSpecialStageAfter(true);
        }
        return new ClaimResult(ClaimState.NEW_UNCOMMITTED, card);
    }

    /** Executes the fixed slot before S1's allocatable {@code v_lvlobjspace}. */
    public static void updateFixedPass(
            ObjectServices services, int vIntRunCount, PlayableEntity player) {
        if (services == null || services.objectManager() == null) {
            return;
        }
        updateFixedPass(services.objectManager(), vIntRunCount, player);
    }

    public static void updateFixedPass(
            ObjectManager objects, int vIntRunCount, PlayableEntity player) {
        if (objects == null) {
            return;
        }
        Sonic1ResultsScreenObjectInstance card = null;
        for (ObjectInstance instance : objects.getActiveObjects()) {
            if (instance instanceof Sonic1ResultsScreenObjectInstance candidate
                    && candidate.getSlotIndex() == SLOT) {
                card = candidate;
                break;
            }
        }
        if (card == null) {
            return;
        }
        if (!card.isDestroyed()) {
            card.update(vIntRunCount, player);
        }
        if (card.isDestroyed()) {
            objects.removeDynamicObject(card);
        }
    }
}
