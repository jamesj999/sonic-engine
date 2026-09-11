package com.openggf.game.sonic2.objects;

import com.openggf.audio.GameSound;
import com.openggf.game.sonic2.constants.Sonic2AudioConstants;
import com.openggf.game.sonic2.constants.Sonic2ObjectIds;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.TestObjectServices;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TestVPropellerObjectInstance {

    @BeforeEach
    void placeCameraOverPropeller() {
        AbstractObjectInstance.updateCameraBounds(0x0F60, 0x0780, 0x10A0, 0x0860, 0);
    }

    @AfterEach
    void resetCameraBounds() {
        AbstractObjectInstance.resetCameraBoundsForTests();
    }

    @Test
    void helicopterSfxUsesRomVintRuncountOffset() {
        RecordingServices services = new RecordingServices();
        VPropellerObjectInstance propeller = new VPropellerObjectInstance(
                new ObjectSpawn(0x1000, 0x0800, Sonic2ObjectIds.VPROPELLER, 0x64, 0, false, 0));
        propeller.setServices(services);

        propeller.update(0, null);
        assertEquals(List.of(), services.soundIds,
                "ObjB4 reads (Vint_runcount+3), so frame 0 should not play the helicopter SFX");

        propeller.update(29, null);
        assertEquals(List.of(Sonic2AudioConstants.SFX_HELICOPTER), services.soundIds,
                "ObjB4 plays when (Vint_runcount+3) & $1F is zero");
    }

    @Test
    void yFlipClearsCollisionFlags() {
        VPropellerObjectInstance propeller = new VPropellerObjectInstance(
                new ObjectSpawn(0x1000, 0x0800, Sonic2ObjectIds.VPROPELLER, 0x64, 0x02, false, 0));

        assertEquals(0, propeller.getCollisionFlags(),
                "ObjB4 bclr render_flags.y_flip clears collision_flags when the bit was set");
    }

    @Test
    void usesRomRenderWidth() {
        VPropellerObjectInstance propeller = new VPropellerObjectInstance(
                new ObjectSpawn(0x1000, 0x0800, Sonic2ObjectIds.VPROPELLER, 0x64, 0, false, 0));

        assertEquals(4, propeller.getOnScreenHalfWidth(),
                "ObjB4_SubObjData sets width_pixels to 4");
    }

    private static final class RecordingServices extends TestObjectServices {
        private final List<Integer> soundIds = new ArrayList<>();

        @Override
        public void playSfx(int soundId) {
            soundIds.add(soundId);
        }

        @Override
        public void playSfx(GameSound sound) {
        }
    }
}
