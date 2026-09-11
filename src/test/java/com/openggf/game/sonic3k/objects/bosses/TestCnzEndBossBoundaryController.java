package com.openggf.game.sonic3k.objects.bosses;

import com.openggf.camera.Camera;
import com.openggf.level.objects.TestObjectServices;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TestCnzEndBossBoundaryController {
    @Test
    void gradualBoundaryUsesAccumulatorHighWordVerbatim() {
        Camera camera = new Camera();
        camera.setMaxX((short) 0x1000);
        CnzEndBossBoundaryController controller =
                CnzEndBossBoundaryController.increaseMaxX(0, 0, 0x1010);
        controller.setServices(new TestObjectServices().withCamera(camera));

        controller.update(0, null);
        controller.update(1, null);
        controller.update(2, null);

        assertEquals(0x1000, camera.getMaxX() & 0xFFFF,
                "three additions of $4000 still have a zero high word");

        controller.update(3, null);

        assertEquals(0x1001, camera.getMaxX() & 0xFFFF,
                "the fourth tick adds the accumulator high word of one");
    }
}
