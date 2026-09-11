package com.openggf.game.sonic2.timing;

import com.openggf.game.sonic2.scroll.Sonic2ZoneConstants;
import com.openggf.tests.TestEnvironment;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

@RequiresRom(SonicGame.SONIC_2)
class TestSonic2PreBgmTimingModel {
    @Test
    void ehzUsesExactRomTitleDescriptorsAndPerCallQueueWalks() {
        var resolved = assertInstanceOf(Sonic2PreBgmTimingModel.Resolved.class,
                resolve(0, 0, OptionalInt.empty(),
                        Sonic2PreBgmTimingModel.Region.NTSC, false));
        var evidence = resolved.evidence();

        assertEquals(0, evidence.levelEntry().titleDescriptorOffset());
        assertEquals(7, evidence.levelEntry().titleDescriptorGroups());
        assertEquals(224, evidence.levelEntry().titleVdpLongWrites());
        assertEquals(List.of(
                        new Sonic2PreBgmTimingModel.PlcCall(4, 0, 6),
                        new Sonic2PreBgmTimingModel.PlcCall(1, 6, 4)),
                evidence.levelEntry().plcCalls());
        assertEquals(11, evidence.terminalRowBucket());
        assertTrue(evidence.lowerRows() >= 11.0);
        assertTrue(evidence.upperRows() < 12.0);
    }

    @Test
    void allWaterRegionAndLifeArmsResolveOnlyInsideOneBucket() {
        int[][] zones = {
                {Sonic2ZoneConstants.ROM_ZONE_CPZ, 1},
                {Sonic2ZoneConstants.ROM_ZONE_ARZ, 0},
                {Sonic2ZoneConstants.ROM_ZONE_ARZ, 1},
                {Sonic2ZoneConstants.ROM_ZONE_HPZ, 0}
        };
        for (int[] zone : zones) {
            for (var region : Sonic2PreBgmTimingModel.Region.values()) {
                for (boolean priorWater : new boolean[]{false, true}) {
                    for (OptionalInt life : List.of(
                            OptionalInt.empty(), OptionalInt.of(7))) {
                        var resolution = resolve(zone[0], zone[1], life,
                                region, priorWater);
                        if (resolution instanceof Sonic2PreBgmTimingModel.Resolved r) {
                            assertEquals((int) Math.floor(r.evidence().lowerRows()),
                                    (int) Math.floor(r.evidence().upperRows()));
                        } else {
                            var u = (Sonic2PreBgmTimingModel.Unresolved) resolution;
                            assertTrue(Double.isFinite(u.lowerRows()));
                            assertTrue(Double.isFinite(u.upperRows()));
                            assertTrue(Math.floor(u.lowerRows()) != Math.floor(u.upperRows()));
                        }
                    }
                }
            }
        }
    }

    @Test
    void destinationWaterPathDoesNotReplaceThePriorLiveWaterFlag() {
        var priorWetToDry = assertInstanceOf(
                Sonic2PreBgmTimingModel.Resolved.class,
                resolve(0, 0, OptionalInt.empty(),
                        Sonic2PreBgmTimingModel.Region.NTSC, true));
        assertTrue(priorWetToDry.evidence().levelEntry().priorWaterFlag());
        assertEquals(Sonic2PreBgmTimingModel.WaterPath.NONE,
                priorWetToDry.evidence().levelEntry().waterPath());

        for (int[] destination : new int[][]{
                {Sonic2ZoneConstants.ROM_ZONE_CPZ, 1},
                {Sonic2ZoneConstants.ROM_ZONE_ARZ, 0},
                {Sonic2ZoneConstants.ROM_ZONE_ARZ, 1}}) {
            var priorDryToWet = assertInstanceOf(
                    Sonic2PreBgmTimingModel.Resolved.class,
                    resolve(destination[0], destination[1], OptionalInt.empty(),
                            Sonic2PreBgmTimingModel.Region.NTSC, false));
            assertTrue(!priorDryToWet.evidence().levelEntry().priorWaterFlag());
            assertTrue(priorDryToWet.evidence().levelEntry().waterPath()
                    != Sonic2PreBgmTimingModel.WaterPath.NONE);
        }
    }

    @Test
    void malformedZoneFailsClosedAsTypedUnresolved() {
        assertInstanceOf(Sonic2PreBgmTimingModel.Unresolved.class,
                resolve(0x7f, 0, OptionalInt.empty(),
                        Sonic2PreBgmTimingModel.Region.NTSC, false));
    }

    private static Sonic2PreBgmTimingModel.Resolution resolve(
            int zone, int act, OptionalInt life,
            Sonic2PreBgmTimingModel.Region region, boolean priorWater) {
        return Sonic2PreBgmTimingModel.resolve(TestEnvironment.currentRom(),
                zone, act, life, region, priorWater);
    }
}
