package com.openggf.game.sonic2.objects.badniks;

import com.openggf.level.objects.ObjectSpawn;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TestShellcrackerClawInstance {

    @Test
    void firstExecutionAfterSpawnRunsInitOnlyBeforeActiveDelayAndMovement() {
        ObjectSpawn parentSpawn = new ObjectSpawn(0x063F, 0x01F3, 0x9F, 0x24, 0, false, 0x01F3);
        ShellcrackerBadnikInstance parent = new ShellcrackerBadnikInstance(parentSpawn);
        ShellcrackerClawInstance claw = new ShellcrackerClawInstance(
                parentSpawn, parent, 0x0617, 0x01EB, 0, false);

        claw.update(0, null);
        assertEquals(0x0617, claw.getX(),
                "ObjA0_Init same-frame execution should only run setup/MarkObjGone");

        claw.update(1, null);
        assertEquals(0x0617, claw.getX(),
                "Initial-delay completion sets velocity but does not ObjectMove");

        claw.update(2, null);
        assertEquals(0x0613, claw.getX(),
                "First active ObjectMove happens on the following ExecuteObjects pass");
    }
}
