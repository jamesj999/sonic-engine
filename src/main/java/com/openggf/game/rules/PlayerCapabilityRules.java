package com.openggf.game.rules;

import java.util.Arrays;

public record PlayerCapabilityRules(
        boolean spindashEnabled,
        short[] spindashSpeedTable,
        boolean elementalShieldsEnabled,
        boolean instaShieldEnabled,
        boolean tailsFlightEnabled,
        boolean jumpRepressClearsRollJumpBeforeAbility,
        boolean lightningShieldEnabled,
        short[] superSpindashSpeedTable) {

    public PlayerCapabilityRules {
        spindashSpeedTable = copy(spindashSpeedTable);
        superSpindashSpeedTable = copy(superSpindashSpeedTable);
    }

    @Override
    public short[] spindashSpeedTable() {
        return copy(spindashSpeedTable);
    }

    @Override
    public short[] superSpindashSpeedTable() {
        return copy(superSpindashSpeedTable);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof PlayerCapabilityRules other)) {
            return false;
        }
        return spindashEnabled == other.spindashEnabled
                && elementalShieldsEnabled == other.elementalShieldsEnabled
                && instaShieldEnabled == other.instaShieldEnabled
                && tailsFlightEnabled == other.tailsFlightEnabled
                && jumpRepressClearsRollJumpBeforeAbility == other.jumpRepressClearsRollJumpBeforeAbility
                && lightningShieldEnabled == other.lightningShieldEnabled
                && Arrays.equals(spindashSpeedTable, other.spindashSpeedTable)
                && Arrays.equals(superSpindashSpeedTable, other.superSpindashSpeedTable);
    }

    @Override
    public int hashCode() {
        int result = Boolean.hashCode(spindashEnabled);
        result = 31 * result + Arrays.hashCode(spindashSpeedTable);
        result = 31 * result + Boolean.hashCode(elementalShieldsEnabled);
        result = 31 * result + Boolean.hashCode(instaShieldEnabled);
        result = 31 * result + Boolean.hashCode(tailsFlightEnabled);
        result = 31 * result + Boolean.hashCode(jumpRepressClearsRollJumpBeforeAbility);
        result = 31 * result + Boolean.hashCode(lightningShieldEnabled);
        result = 31 * result + Arrays.hashCode(superSpindashSpeedTable);
        return result;
    }

    @Override
    public String toString() {
        return "PlayerCapabilityRules["
                + "spindashEnabled=" + spindashEnabled
                + ", spindashSpeedTable=" + Arrays.toString(spindashSpeedTable)
                + ", elementalShieldsEnabled=" + elementalShieldsEnabled
                + ", instaShieldEnabled=" + instaShieldEnabled
                + ", tailsFlightEnabled=" + tailsFlightEnabled
                + ", jumpRepressClearsRollJumpBeforeAbility=" + jumpRepressClearsRollJumpBeforeAbility
                + ", lightningShieldEnabled=" + lightningShieldEnabled
                + ", superSpindashSpeedTable=" + Arrays.toString(superSpindashSpeedTable)
                + "]";
    }

    private static short[] copy(short[] table) {
        return table == null ? null : table.clone();
    }
}
