package com.openggf.game.rules;

import com.openggf.game.DonorCapabilities;

/**
 * Composes typed cross-game rules by keeping runtime behavior owned by the host
 * game while importing only explicitly donated player capabilities.
 */
public final class CrossGameRuleComposer {

    private CrossGameRuleComposer() {
    }

    public static GameRules compose(GameRules host, GameRules donor, DonorCapabilities donorCapabilities) {
        if (host == null) {
            throw new IllegalArgumentException("Host GameRules are required");
        }
        if (donor == null) {
            throw new IllegalArgumentException("Donor GameRules are required");
        }
        if (donorCapabilities == null) {
            return host;
        }

        PlayerCapabilityRules hostCapability = host.playerCapability();
        PlayerCapabilityRules donorCapability = donor.playerCapability();
        short[] spindashSpeedTable = donorCapabilities.hasSpindash()
                ? donorCapability.spindashSpeedTable()
                : null;
        PlayerCapabilityRules hybridCapability = new PlayerCapabilityRules(
                donorCapabilities.hasSpindash(),
                spindashSpeedTable,
                donorCapabilities.hasElementalShields(),
                donorCapabilities.hasInstaShield(),
                donorCapabilities.hasTailsFlight(),
                hostCapability.jumpRepressClearsRollJumpBeforeAbility(),
                donorCapabilities.hasElementalShields(),
                hostCapability.superSpindashSpeedTable());

        return new GameRules(
                host.playerMovement(),
                hybridCapability,
                host.collision(),
                host.playerAnimation(),
                host.camera(),
                host.ring(),
                host.objectInteraction(),
                host.sidekickCpu(),
                host.powerUp(),
                host.drowningBubble(),
                host.dynamicArtDmaService());
    }
}
