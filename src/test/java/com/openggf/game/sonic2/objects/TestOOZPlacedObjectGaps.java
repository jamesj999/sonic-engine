package com.openggf.game.sonic2.objects;

import com.openggf.game.sonic2.constants.Sonic2AnimationIds;
import com.openggf.game.sonic2.constants.Sonic2ObjectIds;
import com.openggf.game.PlayableEntity;
import com.openggf.game.solid.ContactKind;
import com.openggf.game.solid.ObjectSolidExecutionContext;
import com.openggf.game.solid.PlayerSolidContactResult;
import com.openggf.game.solid.PlayerStandingState;
import com.openggf.game.solid.PostContactState;
import com.openggf.game.solid.PreContactState;
import com.openggf.game.solid.SolidCheckpointBatch;
import com.openggf.game.solid.SolidExecutionRegistry;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.SolidContact;
import com.openggf.level.objects.SolidExecutionMode;
import com.openggf.level.objects.SolidObjectListener;
import com.openggf.level.objects.SolidObjectParams;
import com.openggf.level.objects.SolidObjectProvider;
import com.openggf.level.objects.TestObjectServices;
import com.openggf.level.objects.TouchCategoryDecodeMode;
import com.openggf.level.objects.TouchResponseProvider;
import com.openggf.physics.Direction;
import com.openggf.tests.TestablePlayableSprite;
import com.openggf.tests.FullReset;
import com.openggf.tests.SingletonResetExtension;
import com.openggf.tools.Sonic2ObjectProfile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

@FullReset
@ExtendWith(SingletonResetExtension.class)
class TestOOZPlacedObjectGaps {

    @Test
    void registryAndDiscoveryProfileCoverPlacedOozObjectIds() {
        Sonic2ObjectRegistry registry = new Sonic2ObjectRegistry();

        assertEquals(0x43, Sonic2ObjectIds.SLIDING_SPIKE);
        assertEquals(0x45, Sonic2ObjectIds.OOZ_SPRING);
        assertFalse(registry.hasRegisteredFactory(0x46),
                "Obj46 is an unused beta leftover, not the child allocated by Obj45");
        assertTrue(registry.hasRegisteredFactory(0x43));
        assertTrue(registry.hasRegisteredFactory(0x45));

        Sonic2ObjectProfile profile = new Sonic2ObjectProfile();
        assertTrue(profile.getImplementedIds().contains(0x43));
        assertTrue(profile.getImplementedIds().contains(0x45));
        assertFalse(profile.getImplementedIds().contains(0x46),
                "Obj46 should stay out of implemented placed-object coverage unless its unused beta routine is ported");
    }

    @Test
    void slidingSpikeSubtypesUseRomOffsetsAndOnePixelMotion() throws Exception {
        ObjectInstance subtype00 = newSlidingSpike(0x1000, 0x0500, 0x00);
        assertEquals(0x1000, subtype00.getX());
        assertEquals(0x0F98, intField(subtype00, "minX"));
        assertEquals(0x1068, intField(subtype00, "maxX"));

        ObjectInstance subtype06 = newSlidingSpike(0x1000, 0x0500, 0x06);
        assertEquals(0x0FE8, subtype06.getX(), "Obj43 subtype 06 parent starts at -$18 from origin");
        assertEquals(0x0F18, intField(subtype06, "minX"),
                "Obj43_Init loads originXOffset with move.b after moveq #0, so -$18 is the unsigned span $E8");
        assertEquals(0x10E8, intField(subtype06, "maxX"));
        assertInstanceOf(TouchResponseProvider.class, subtype06);
        assertEquals(0xA5, ((TouchResponseProvider) subtype06).getCollisionFlags());
        assertEquals(TouchCategoryDecodeMode.NORMAL,
                ((TouchResponseProvider) subtype06).getTouchResponseProfile().categoryDecodeMode(),
                "Obj43 ROM writes collision_flags=$A5 at s2.asm:49991; high bits $80 decode as HURT, not enemy");

        subtype06.update(0, playerAt(0x1000, 0x0500));
        assertEquals(0x0FE7, subtype06.getX(), "Obj43 moves one pixel toward its left bound when direction is clear");
        for (int i = 0; i < 0xD0; i++) {
            subtype06.update(i + 1, playerAt(0x1000, 0x0500));
        }
        assertEquals(0x0F19, subtype06.getX(),
                "Obj43 subtype 06 reverses only after reaching origin-$E8, not origin-$18");

        ObjectInstance subtype0C = newSlidingSpike(0x1000, 0x0500, 0x0C);
        assertEquals(0x0FA8, subtype0C.getX(), "Obj43 subtype 0C parent starts at -$58 from origin");
        assertEquals(0x0F58, intField(subtype0C, "minX"),
                "Obj43_Init treats -$58 as unsigned $A8 for the travel span");
        assertEquals(0x10A8, intField(subtype0C, "maxX"));
    }

    @Test
    void oozPressureSpringSubtypesAreHorizontalStrongSprings() throws Exception {
        ObjectInstance subtype10 = newOOZSpring(0x1000, 0x0500, 0x10);
        ObjectInstance subtype30 = newOOZSpring(0x1000, 0x0500, 0x30);

        assertInstanceOf(SolidObjectProvider.class, subtype10);
        assertInstanceOf(SolidObjectProvider.class, subtype30);
        SolidObjectParams params = ((SolidObjectProvider) subtype10).getSolidParams();
        assertEquals(31, params.halfWidth());
        assertEquals(12, params.airHalfHeight());
        assertEquals(13, params.groundHalfHeight());
        assertEquals(SolidExecutionMode.MANUAL_CHECKPOINT,
                ((SolidObjectProvider) subtype10).solidExecutionMode(),
                "Obj45 runs SolidObject before its release logic in the ROM routine");

        assertTrue(booleanField(subtype10, "horizontal"));
        assertTrue(booleanField(subtype30, "horizontal"),
                "Subtype $30 depends on the ROM's out-of-table fall-through into horizontal init");
        assertEquals(-0x1000, intField(subtype10, "strength"));
        assertEquals(-0x1000, intField(subtype30, "strength"));
        assertEquals(0x0A, intField(subtype10, "mappingFrame"));
    }

    @Test
    void horizontalOozPressureSpringCompressesOnePixelBeforeReleaseLaunch() throws Exception {
        ObjectInstance spring = newOOZSpring(0x1000, 0x0500, 0x10);
        TestablePlayableSprite player = playerAt(0x101C, 0x0500);
        player.setDirection(Direction.LEFT);
        player.setGSpeed((short) -0x40);

        ((SolidObjectListener) spring).onSolidContact(player,
                new SolidContact(false, true, false, false, true, 1, true), 0);

        assertEquals(0x0FFF, spring.getX());
        assertEquals(0x0B, intField(spring, "mappingFrame"));

        spring.update(1, player);

        assertEquals(0x1000, spring.getX(), "released Obj45 snaps four pixels toward origin and clamps at base");
        assertEquals(0x0A, intField(spring, "mappingFrame"));
        assertEquals(0x0500, player.getXSpeed() & 0xFFFF,
                "release strength is (compression+$A)<<7, directed by the spring facing");
        assertEquals(0x0500, player.getGSpeed() & 0xFFFF);
        assertFalse(player.getPushing());
    }

    @Test
    void verticalOozPressureSpringAppliesFlipPlaneAndBit7SubtypeEffects() throws Exception {
        ObjectInstance spring = newOOZSpring(0x1000, 0x0500, 0x85);
        TestablePlayableSprite player = playerAt(0x1000, 0x04EC);
        player.setDirection(Direction.LEFT);
        player.setXSpeed((short) 0x0320);
        player.setHurt(true);
        setIntField(spring, "mappingFrame", 9);

        ((SolidObjectListener) spring).onSolidContact(player,
                new SolidContact(true, false, false, false, false), 0);

        assertEquals(0xF000, player.getYSpeed() & 0xFFFF);
        assertEquals(0, player.getXSpeed());
        assertTrue(player.getAir());
        assertFalse(player.isOnObject());
        assertFalse(player.isHurt(), "Obj45 writes routine #2 on vertical launch, clearing the hurt routine");
        assertEquals(Sonic2AnimationIds.WALK.id(), player.getAnimationId());
        assertEquals(0xFF, player.getFlipAngle());
        assertEquals(1, player.getFlipsRemaining());
        assertEquals(4, player.getFlipSpeed());
        assertEquals(0xFFFF, player.getGSpeed() & 0xFFFF);
        assertEquals(0x0C, player.getTopSolidBit() & 0xFF);
        assertEquals(0x0D, player.getLrbSolidBit() & 0xFF);
    }

    @Test
    void verticalOozPressureSpringUsesWeakStrengthAndAlternatePlaneBits() throws Exception {
        ObjectInstance spring = newOOZSpring(0x1000, 0x0500, 0x0A);
        TestablePlayableSprite player = playerAt(0x1000, 0x04EC);
        setIntField(spring, "mappingFrame", 9);

        ((SolidObjectListener) spring).onSolidContact(player,
                new SolidContact(true, false, false, false, false), 0);

        assertEquals(0xF600, player.getYSpeed() & 0xFFFF);
        assertEquals(0x0E, player.getTopSolidBit() & 0xFF);
        assertEquals(0x0F, player.getLrbSolidBit() & 0xFF);
    }

    @Test
    void verticalOozPressureSpringCompressesOnlyOnceWhenBothNativePlayersWereStanding() throws Exception {
        ObjectInstance spring = newObject("com.openggf.game.sonic2.objects.OOZSpringObjectInstance",
                new ObjectSpawn(0x1000, 0x0500, 0x45, 0x00, 0, false, 0));
        TestablePlayableSprite sonic = playerAt(0x1000, 0x04EC);
        TestablePlayableSprite tails = playerAt(0x1004, 0x04EC);
        ((AbstractObjectInstance) spring).setServices(new TestObjectServices()
                .withSidekicks(List.of(tails))
                .withSolidExecutionRegistry(new BothPlayersStandingRegistry())
                .withIsolatedObjectManager());

        spring.update(1, sonic);

        assertEquals(1, intField(spring, "mappingFrame"),
                "Obj45 tests standing_mask once before SolidObject45, so two riders compress one frame");
    }

    @Test
    void horizontalOozPressureSpringAppliesFlipSubtypeAfterReleaseLaunch() throws Exception {
        ObjectInstance spring = newOOZSpring(0x1000, 0x0500, 0x11);
        TestablePlayableSprite player = playerAt(0x101C, 0x0500);
        player.setDirection(Direction.LEFT);
        player.setGSpeed((short) -0x40);

        ((SolidObjectListener) spring).onSolidContact(player,
                new SolidContact(false, true, false, false, true, 1, true), 0);
        spring.update(1, player);

        assertEquals(0x0500, player.getXSpeed() & 0xFFFF);
        assertEquals(1, player.getGSpeed(), "Obj45 bit 0 overwrites inertia with flip inertia after x_vel");
        assertEquals(1, player.getFlipAngle());
        assertEquals(3, player.getFlipsRemaining());
        assertEquals(8, player.getFlipSpeed());
        assertEquals(Sonic2AnimationIds.WALK.id(), player.getAnimationId());
    }

    @Test
    void flippedHorizontalOozPressureSpringLaunchesLeftAndCanClearYSpeedAndSwitchPlane() throws Exception {
        ObjectInstance spring = newOOZSpring(0x1000, 0x0500, 0x99, 1);
        TestablePlayableSprite player = playerAt(0x0FE4, 0x0500);
        player.setDirection(Direction.RIGHT);
        player.setGSpeed((short) 0x40);
        player.setYSpeed((short) 0x0340);

        ((SolidObjectListener) spring).onSolidContact(player,
                new SolidContact(false, true, false, false, true, -1, true), 0);
        spring.update(1, player);

        assertEquals(0xFB00, player.getXSpeed() & 0xFFFF);
        assertEquals(0, player.getYSpeed());
        assertEquals(Direction.LEFT, player.getDirection());
        assertEquals(0xFF, player.getFlipAngle());
        assertEquals(3, player.getFlipsRemaining());
        assertEquals(8, player.getFlipSpeed());
        assertEquals(0x0E, player.getTopSolidBit() & 0xFF);
        assertEquals(0x0F, player.getLrbSolidBit() & 0xFF);
    }

    @Test
    void horizontalOozPressureSpringCarriesExistingSidekickWhenOtherPlayerCompresses() throws Exception {
        ObjectInstance spring = newOOZSpring(0x04B4, 0x03D0, 0x10, 1);
        TestablePlayableSprite sonic = playerAt(0x04A7, 0x03B4);
        TestablePlayableSprite tails = playerAt(0x04A0, 0x03B4);
        sonic.setDirection(Direction.RIGHT);
        tails.setDirection(Direction.LEFT);

        PlayerSolidContactResult sonicPush = new PlayerSolidContactResult(
                ContactKind.SIDE,
                false,
                false,
                true,
                false,
                PreContactState.ZERO,
                PostContactState.ZERO,
                1);
        ((AbstractObjectInstance) spring).setServices(new TestObjectServices()
                .withSidekicks(List.of(tails))
                .withSolidExecutionRegistry(new ScriptedSolidRegistry(
                        spring,
                        Map.of(sonic, sonicPush),
                        Map.of(tails, new PlayerStandingState(ContactKind.TOP, true, false))))
                .withIsolatedObjectManager());

        spring.update(0, sonic);

        assertEquals(0x04B5, spring.getX(), "Obj45 flipped horizontal spring compresses one pixel right");
        assertEquals(0x04A1, tails.getCentreX() & 0xFFFF,
                "ROM Obj45_Horizontal restores routine-entry d4 before the Sidekick SolidObject call, "
                        + "so an existing sidekick rider is carried by Sonic's compression delta");
    }

    @Test
    void horizontalOozPressureSpringCompressesFromAirborneSideContactWithoutArmingLaunch() throws Exception {
        ObjectInstance spring = newOOZSpring(0x04B4, 0x0230, 0x10, 1);
        TestablePlayableSprite sonic = playerAt(0x0495, 0x0243);
        sonic.setAir(true);
        sonic.setDirection(Direction.RIGHT);
        sonic.setGSpeed((short) 0);
        sonic.setXSpeed((short) 0x0060);

        PlayerSolidContactResult airborneSide = new PlayerSolidContactResult(
                ContactKind.SIDE,
                false,
                false,
                false,
                false,
                PreContactState.ZERO,
                PostContactState.ZERO,
                1);
        ((AbstractObjectInstance) spring).setServices(new TestObjectServices()
                .withSolidExecutionRegistry(new ScriptedSolidRegistry(
                        spring,
                        Map.of(sonic, airborneSide),
                        Map.of()))
                .withIsolatedObjectManager());

        spring.update(0, sonic);

        assertEquals(0x04B5, spring.getX(),
                "Obj45_Horizontal calls loc_2433C on SolidObject d4==1 even for airborne side contact");
        assertEquals(0x0496, sonic.getCentreX() & 0xFFFF,
                "loc_243A6 adds the spring compression delta to x_pos(a1)");
        assertEquals(0x0000, sonic.getXSpeed() & 0xFFFF,
                "loc_243A6 clears x_vel(a1) while compressing");
        assertEquals(0x0040, sonic.getGSpeed() & 0xFFFF,
                "loc_243A6 writes the spring's compression inertia");
        assertFalse(booleanField(spring, "pendingMainHorizontalLaunch"),
                "airborne SolidObject_SideAir does not set status(a0)'s push bit for the later release launch");
    }

    @Test
    void horizontalOozPressureSpringCompressesWhenSideAirClearsOldPushBit() throws Exception {
        ObjectInstance spring = newOOZSpring(0x04B4, 0x03D0, 0x10, 1);
        TestablePlayableSprite tails = playerAt(0x04A1, 0x03CA);
        tails.setAir(true);
        tails.setDirection(Direction.RIGHT);
        tails.setGSpeed((short) 0x0040);
        setIntField(spring, "currentX", 0x04BB);
        setIntField(spring, "mappingFrame", 0x11);
        setBooleanField(spring, "pendingSidekickHorizontalLaunch", true);

        PlayerSolidContactResult sideAirClearedPush = new PlayerSolidContactResult(
                ContactKind.SIDE,
                false,
                false,
                false,
                true,
                PreContactState.ZERO,
                PostContactState.ZERO,
                5);
        ((AbstractObjectInstance) spring).setServices(new TestObjectServices()
                .withSidekicks(List.of(tails))
                .withSolidExecutionRegistry(new ScriptedSolidRegistry(
                        spring,
                        Map.of(tails, sideAirClearedPush),
                        Map.of(tails, new PlayerStandingState(ContactKind.SIDE, false, true))))
                .withIsolatedObjectManager());

        spring.update(0, playerAt(0x0400, 0x0300));

        assertEquals(0x04BC, spring.getX(),
                "SolidObject_SideAir clears the push bit but still returns d4=1, so Obj45_Horizontal "
                        + "must run loc_2433C instead of releasing (s2.asm:50393-50420, 50465-50525)");
        assertEquals(0x04A2, tails.getCentreX() & 0xFFFF,
                "loc_243A6 applies the one-pixel spring compression delta to x_pos(a1)");
        assertEquals(0x0040, tails.getGSpeed() & 0xFFFF);
        assertTrue(booleanField(spring, "pendingSidekickHorizontalLaunch"),
                "SideAir does not set a fresh status push bit, so the stale launch latch must not be consumed");
    }

    @Test
    void horizontalOozPressureSpringDoesNotDuplicateCarryAfterSidekickIsAlreadyPushing() throws Exception {
        ObjectInstance spring = newOOZSpring(0x04B4, 0x03D0, 0x10, 1);
        TestablePlayableSprite sonic = playerAt(0x04A1, 0x03B4);
        TestablePlayableSprite tails = playerAt(0x04A0, 0x03B4);
        sonic.setDirection(Direction.RIGHT);
        tails.setDirection(Direction.LEFT);

        PlayerSolidContactResult sonicPush = new PlayerSolidContactResult(
                ContactKind.SIDE,
                false,
                true,
                true,
                true,
                PreContactState.ZERO,
                PostContactState.ZERO,
                1);
        ((AbstractObjectInstance) spring).setServices(new TestObjectServices()
                .withSidekicks(List.of(tails))
                .withSolidExecutionRegistry(new ScriptedSolidRegistry(
                        spring,
                        Map.of(sonic, sonicPush),
                        Map.of(tails, new PlayerStandingState(ContactKind.SIDE, true, true))))
                .withIsolatedObjectManager());

        spring.update(0, sonic);
        assertEquals(0x04B5, spring.getX());
        tails.setCentreX((short) 0x04A0);

        spring.update(1, sonic);

        assertEquals(0x04B6, spring.getX(), "Obj45 keeps compressing while push bits are already latched");
        assertEquals(0x04A0, tails.getCentreX() & 0xFFFF,
                "Once the sidekick push bit is already latched, the normal riding update owns the carry; "
                        + "Obj45 must not add the ordered first-push carry again");
    }

    @Test
    void horizontalOozPressureSpringExactEdgeHoldsWithoutVelocityClamp() throws Exception {
        ObjectInstance spring = newOOZSpring(0x04B4, 0x03D0, 0x10, 1);
        TestablePlayableSprite sonic = playerAt(0x0497, 0x03CC);
        sonic.setDirection(Direction.RIGHT);
        sonic.setGSpeed((short) 0x0040);
        sonic.setXSpeed((short) 0x004C);
        setIntField(spring, "currentX", 0x04B6);
        setIntField(spring, "mappingFrame", 0x0C);

        PlayerSolidContactResult exactEdgePush = new PlayerSolidContactResult(
                ContactKind.SIDE,
                false,
                false,
                true,
                true,
                PreContactState.ZERO,
                PostContactState.ZERO,
                0);
        ((AbstractObjectInstance) spring).setServices(new TestObjectServices()
                .withSolidExecutionRegistry(new ScriptedSolidRegistry(
                        spring,
                        Map.of(sonic, exactEdgePush),
                        Map.of(sonic, new PlayerStandingState(ContactKind.SIDE, false, true))))
                .withIsolatedObjectManager());

        spring.update(0, sonic);

        assertEquals(0x04B6, spring.getX(),
                "Obj45 d0==0 path only sets objoff_36; it does not addq/subq x_pos(a0)");
        assertEquals(0x004C, sonic.getXSpeed() & 0xFFFF,
                "Obj45 d0==0 path does not clear x_vel(a1)");
        assertEquals(0x0040, sonic.getGSpeed() & 0xFFFF,
                "Obj45 d0==0 path does not overwrite inertia(a1)");
    }

    @Test
    void horizontalOozPressureSpringPushClearFrameDoesNotLaunch() throws Exception {
        ObjectInstance spring = newOOZSpring(0x04B4, 0x03D0, 0x10, 1);
        TestablePlayableSprite sonic = playerAt(0x04A6, 0x03CC);
        sonic.setDirection(Direction.RIGHT);
        sonic.setGSpeed((short) -0x0080);
        sonic.setXSpeed((short) -0x0080);
        setIntField(spring, "currentX", 0x04C6);
        setIntField(spring, "mappingFrame", 0x1C);
        setBooleanField(spring, "pendingMainHorizontalLaunch", true);

        PlayerSolidContactResult pushCleared = new PlayerSolidContactResult(
                ContactKind.NONE,
                false,
                false,
                false,
                true,
                PreContactState.ZERO,
                PostContactState.ZERO,
                0);
        ((AbstractObjectInstance) spring).setServices(new TestObjectServices()
                .withSolidExecutionRegistry(new ScriptedSolidRegistry(
                        spring,
                        Map.of(sonic, pushCleared),
                        Map.of(sonic, new PlayerStandingState(ContactKind.SIDE, false, true))))
                .withIsolatedObjectManager());

        spring.update(0, sonic);

        assertEquals(0x04C2, spring.getX(),
                "Obj45 releases on the frame where SolidObject clears the old pushing bit");
        assertEquals(0xFF80, sonic.getXSpeed() & 0xFFFF,
                "Obj45 must not launch while the previous push bit is only being cleared");
        assertTrue(booleanField(spring, "pendingMainHorizontalLaunch"),
                "the pending Obj45 launch remains for the later release frame");
    }

    @Test
    void horizontalOozPressureSpringStatusPushCanRelaunchDuringRelease() throws Exception {
        ObjectInstance spring = newOOZSpring(0x04B4, 0x03D0, 0x10, 1);
        TestablePlayableSprite sonic = playerAt(0x049B, 0x03CC);
        sonic.setDirection(Direction.LEFT);
        sonic.setGSpeed((short) 0xF800);
        sonic.setXSpeed((short) 0xF800);
        setIntField(spring, "currentX", 0x04BA);
        setIntField(spring, "mappingFrame", 0x10);

        PlayerSolidContactResult exactEdgePushMovingAway = new PlayerSolidContactResult(
                ContactKind.SIDE,
                false,
                false,
                true,
                true,
                PreContactState.ZERO,
                PostContactState.ZERO,
                0);
        ((AbstractObjectInstance) spring).setServices(new TestObjectServices()
                .withSolidExecutionRegistry(new ScriptedSolidRegistry(
                        spring,
                        Map.of(sonic, exactEdgePushMovingAway),
                        Map.of(sonic, new PlayerStandingState(ContactKind.SIDE, false, true))))
                .withIsolatedObjectManager());

        spring.update(0, sonic);

        assertEquals(0x04B6, spring.getX(),
                "Obj45 releases four pixels before launching from status(a0)'s push bit");
        assertEquals(0xFA00, sonic.getXSpeed() & 0xFFFF,
                "Obj45_LaunchCharacterHorizontal uses the post-release two-pixel compression");
        assertEquals(0xFA00, sonic.getGSpeed() & 0xFFFF);
        assertEquals(0x0497, sonic.getCentreX() & 0xFFFF,
                "flipped Obj45 subtracts 4 from x_pos(a1) during horizontal launch");
    }

    @Test
    void oozPoppingPlatformKeepsRomSolidLatchAndObjectControlledSupport() throws Exception {
        ObjectInstance object = newObject("com.openggf.game.sonic2.objects.OOZPoppingPlatformObjectInstance",
                new ObjectSpawn(0x1000, 0x0500, 0x33, 0x00, 0, false, 0));

        SolidObjectProvider platform = assertInstanceOf(SolidObjectProvider.class, object);
        assertTrue(platform.usesInstanceSolidStateLatchKey(),
                "Obj33 rebuilds its dynamic spawn while ROM keeps standing/pushing bits in the SST status byte");
        assertTrue(platform.usesInclusiveRightEdge(),
                "Obj33's SolidObject BHI gate accepts relX == 2*d1");
        assertTrue(platform.preservesZeroDistanceSideContactMotion(),
                "Obj33's exact-edge AtEdge branch sets push without entering StopCharacter");
        assertFalse(platform.preservesEdgeSubpixelMotion(),
                "Obj33 must not alter ordinary nonzero side-correction semantics");
        assertTrue(platform.allowsObjectControlledSolidContacts(),
                "Obj33 keeps calling SolidObject while captured riders have positive obj_control=1");
        TestablePlayableSprite tails = playerAt(0x1000, 0x04E0);
        assertTrue(platform.rejectsBit7ObjectControlNewSolidContact(tails),
                "Obj33 allows positive obj_control=1 support but still rejects signed bit-7 states "
                        + "at SolidObject_ChkBounds (s2.asm:49727-49740, 35344-35489)");
    }

    private static ObjectInstance newSlidingSpike(int x, int y, int subtype) throws Exception {
        ObjectInstance object = newObject("com.openggf.game.sonic2.objects.SlidingSpikeObjectInstance",
                new ObjectSpawn(x, y, 0x43, subtype, 0, false, 0));
        ((AbstractObjectInstance) object).setServices(
                new TestObjectServices().withIsolatedObjectManager());
        return object;
    }

    private static ObjectInstance newOOZSpring(int x, int y, int subtype) throws Exception {
        return newOOZSpring(x, y, subtype, 0);
    }

    private static ObjectInstance newOOZSpring(int x, int y, int subtype, int renderFlags) throws Exception {
        ObjectInstance object = newObject("com.openggf.game.sonic2.objects.OOZSpringObjectInstance",
                new ObjectSpawn(x, y, 0x45, subtype, renderFlags, false, 0));
        ((AbstractObjectInstance) object).setServices(
                new TestObjectServices().withIsolatedObjectManager());
        return object;
    }

    private static ObjectInstance newObject(String className, ObjectSpawn spawn) throws Exception {
        Class<?> type = Class.forName(className);
        Constructor<?> ctor = type.getConstructor(ObjectSpawn.class, String.class);
        return (ObjectInstance) ctor.newInstance(spawn, "test");
    }

    private static TestablePlayableSprite playerAt(int x, int y) {
        TestablePlayableSprite player = new TestablePlayableSprite("sonic", (short) x, (short) y);
        player.setCentreX((short) x);
        player.setCentreY((short) y);
        player.setAir(false);
        return player;
    }

    private static int intField(Object target, String fieldName) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        return field.getInt(target);
    }

    private static boolean booleanField(Object target, String fieldName) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        return field.getBoolean(target);
    }

    private static void setIntField(Object target, String fieldName, int value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.setInt(target, value);
    }

    private static void setBooleanField(Object target, String fieldName, boolean value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.setBoolean(target, value);
    }

    private static final class BothPlayersStandingRegistry implements SolidExecutionRegistry {
        @Override public void beginFrame(int frameCounter, List<? extends com.openggf.game.PlayableEntity> players) {}
        @Override public void beginObject(ObjectInstance object, ObjectSolidExecutionContext.Resolver resolver) {}
        @Override public ObjectSolidExecutionContext currentObject() { return ObjectSolidExecutionContext.inert(); }
        @Override public PlayerStandingState previousStanding(ObjectInstance object, com.openggf.game.PlayableEntity player) {
            return new PlayerStandingState(ContactKind.TOP, true, false);
        }
        @Override public void publishCheckpoint(SolidCheckpointBatch batch) {}
        @Override public void endObject(ObjectInstance object) {}
        @Override public void finishFrame() {}
        @Override public void clearTransientState() {}
    }

    private static final class ScriptedSolidRegistry implements SolidExecutionRegistry {
        private final ObjectSolidExecutionContext context;
        private final Map<PlayableEntity, PlayerStandingState> previousStanding;

        private ScriptedSolidRegistry(
                ObjectInstance object,
                Map<PlayableEntity, PlayerSolidContactResult> checkpoint,
                Map<PlayableEntity, PlayerStandingState> previousStanding) {
            this.previousStanding = previousStanding;
            this.context = new ObjectSolidExecutionContext(
                    this,
                    object,
                    () -> new SolidCheckpointBatch(object, checkpoint));
        }

        @Override public void beginFrame(int frameCounter, List<? extends PlayableEntity> players) {}
        @Override public void beginObject(ObjectInstance object, ObjectSolidExecutionContext.Resolver resolver) {}
        @Override public ObjectSolidExecutionContext currentObject() { return context; }
        @Override public PlayerStandingState previousStanding(ObjectInstance object, PlayableEntity player) {
            return previousStanding.getOrDefault(player, PlayerStandingState.NONE);
        }
        @Override public void publishCheckpoint(SolidCheckpointBatch batch) {}
        @Override public void endObject(ObjectInstance object) {}
        @Override public void finishFrame() {}
        @Override public void clearTransientState() {}
    }
}
