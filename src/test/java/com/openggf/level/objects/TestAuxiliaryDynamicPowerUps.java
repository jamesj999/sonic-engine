package com.openggf.level.objects;

import com.openggf.game.InstaShieldHandle;
import com.openggf.game.PlayableEntity;
import com.openggf.game.session.EngineContext;
import com.openggf.game.session.EngineServices;
import com.openggf.game.session.SessionManager;
import com.openggf.tests.SingletonResetExtension;
import com.openggf.tests.TestEnvironment;
import com.openggf.tests.TestablePlayableSprite;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(SingletonResetExtension.class)
class TestAuxiliaryDynamicPowerUps {

    @BeforeEach
    void setUp() {
        EngineServices.configure(EngineContext.fromLegacySingletonsForBootstrap());
        TestEnvironment.activeGameplayMode();
    }

    @AfterEach
    void tearDown() {
        SessionManager.clear();
    }

    @Test
    void cpuSidekickInstaShieldRegistersOutsideRomObjectSlots() {
        ObjectManager manager = new ObjectManager(List.of(), null, 0, null, null);
        DefaultPowerUpSpawner spawner = new DefaultPowerUpSpawner(manager);
        TestablePlayableSprite sidekick = new TestablePlayableSprite("sidekick-sonic", (short) 0x100, (short) 0x180);
        sidekick.setCpuControlled(true);
        TestInstaShieldObject instaShield = constructInstaShield(manager, sidekick);

        spawner.registerObject(instaShield);

        assertEquals(-1, instaShield.getSlotIndex(),
                "Sidekick-only insta-shields are extension overlays and must not consume ROM SST slots");
        assertEquals(0, manager.getActiveObjectSlotCount());
        assertTrue(manager.getActiveObjects().contains(instaShield),
                "Auxiliary objects should still update and render through ObjectManager");
        assertTrue(manager.rewindSnapshottable().capture().dynamicObjects().isEmpty(),
                "Auxiliary extension overlays must stay out of trace-visible dynamic object snapshots");
    }

    @Test
    void cpuSidekickInstaShieldDoesNotMoveExistingShieldSlot() {
        ObjectManager manager = new ObjectManager(List.of(), null, 0, null, null);
        DefaultPowerUpSpawner spawner = new DefaultPowerUpSpawner(manager);
        TestablePlayableSprite main = new TestablePlayableSprite("sonic", (short) 0x100, (short) 0x180);
        TestablePlayableSprite sidekick = new TestablePlayableSprite("sidekick-sonic", (short) 0x120, (short) 0x180);
        sidekick.setCpuControlled(true);
        ShieldObjectInstance existingShield = ObjectConstructionContext.construct(
                manager.services(), () -> new ShieldObjectInstance(main));
        manager.addDynamicObject(existingShield);
        int existingShieldSlot = existingShield.getSlotIndex();

        spawner.registerObject(constructInstaShield(manager, sidekick));

        assertEquals(existingShieldSlot, existingShield.getSlotIndex());
        assertEquals(1, manager.getActiveObjectSlotCount());
        assertTrue(manager.getActiveObjects().contains(existingShield));
    }

    @Test
    void playerInstaShieldStillUsesRomObjectSlot() {
        ObjectManager manager = new ObjectManager(List.of(), null, 0, null, null);
        DefaultPowerUpSpawner spawner = new DefaultPowerUpSpawner(manager);
        TestablePlayableSprite player = new TestablePlayableSprite("sonic", (short) 0x100, (short) 0x180);
        TestInstaShieldObject instaShield = constructInstaShield(manager, player);

        spawner.registerObject(instaShield);

        assertTrue(instaShield.getSlotIndex() >= 0,
                "Main-player insta-shield remains on the ROM-modeled dynamic object path");
        assertEquals(1, manager.rewindSnapshottable().capture().dynamicObjects().size());
    }

    private static TestInstaShieldObject constructInstaShield(ObjectManager manager, TestablePlayableSprite owner) {
        return ObjectConstructionContext.construct(
                manager.services(), () -> new TestInstaShieldObject(owner));
    }

    private static final class TestInstaShieldObject extends ShieldObjectInstance implements InstaShieldHandle {
        private TestInstaShieldObject(PlayableEntity player) {
            super(player);
        }

        @Override
        public void triggerAttack() {
        }

        @Override
        public void invalidateDplcCache() {
        }
    }
}
