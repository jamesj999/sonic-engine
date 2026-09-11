package com.openggf.sprites.managers;

import com.openggf.game.sonic2.Sonic2GameModule;
import com.openggf.level.objects.PerObjectRewindSnapshot.PlayerRewindExtra;
import com.openggf.sprites.playable.SidekickCpuController;
import com.openggf.sprites.playable.Sonic;
import com.openggf.sprites.playable.Tails;
import com.openggf.tests.TestEnvironment;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class TestSpriteManagerRewindCapture {

    @BeforeEach
    void setUp() {
        TestEnvironment.configureGameModuleFixture(new Sonic2GameModule());
    }

    @AfterEach
    void tearDown() {
        TestEnvironment.resetAll();
    }

    @Test
    void captureIncludesFollowHistoryOnlyForSpritesWithFollowers() {
        SpriteManager manager = new SpriteManager();
        Sonic sonic = new Sonic("sonic", (short) 0x100, (short) 0x200);
        Tails firstSidekick = cpuTails("tails_p2", (short) 0x0E0, sonic);
        Tails terminalSidekick = cpuTails("tails_p3", (short) 0x0C0, firstSidekick);

        manager.addSprite(sonic);
        manager.addSprite(firstSidekick, "tails");
        manager.addSprite(terminalSidekick, "tails");

        Map<String, PlayerRewindExtra> entries = Arrays.stream(manager.rewindSnapshottable().capture().sprites())
                .collect(Collectors.toMap(entry -> entry.code(), entry -> entry.state().playerExtra()));

        assertHasFollowHistory(entries.get("sonic"));
        assertHasFollowHistory(entries.get("tails_p2"));
        assertHasNoFollowHistory(entries.get("tails_p3"));
    }

    private static Tails cpuTails(String code, short x, Sonic leader) {
        return cpuTails(code, x, (com.openggf.sprites.playable.AbstractPlayableSprite) leader);
    }

    private static Tails cpuTails(
            String code,
            short x,
            com.openggf.sprites.playable.AbstractPlayableSprite leader) {
        Tails tails = new Tails(code, x, (short) 0x200);
        tails.setCpuControlled(true);
        tails.setCpuController(new SidekickCpuController(tails, leader));
        return tails;
    }

    private static void assertHasFollowHistory(PlayerRewindExtra extra) {
        assertNotNull(extra.xHistory());
        assertNotNull(extra.yHistory());
        assertNotNull(extra.inputHistory());
        assertNotNull(extra.jumpPressHistory());
        assertNotNull(extra.statusHistory());
    }

    private static void assertHasNoFollowHistory(PlayerRewindExtra extra) {
        assertNull(extra.xHistory());
        assertNull(extra.yHistory());
        assertNull(extra.inputHistory());
        assertNull(extra.jumpPressHistory());
        assertNull(extra.statusHistory());
    }
}
