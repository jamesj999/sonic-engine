package com.openggf.tests;

import com.openggf.game.sonic3k.objects.TestFbzAct2TraversalPreboss;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import org.junit.jupiter.api.Test;


/**
 * Independent starpost-5 magnetic route slice. The full native Sonic+Tails
 * wave and its concrete player-class assertions live in TestFbzCompatibilityMatrix.
 */
@RequiresRom(SonicGame.SONIC_3K)
class TestFbzAct2RouteHeadless {
    @Test
    void starpost5WaveExecutesTheLowerMagneticPlatformAndChain() {
        TestFbzAct2TraversalPreboss
                .assertLateNativeStarpostRestartMaterializesAndExecutesLowerMagneticSection();
    }
}
