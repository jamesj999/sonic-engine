package com.openggf.level.objects;

import com.openggf.camera.Camera;
import com.openggf.configuration.SonicConfiguration;
import com.openggf.configuration.SonicConfigurationService;
import com.openggf.game.DamageCause;
import com.openggf.game.GroundMode;
import com.openggf.game.rules.GameRules;
import com.openggf.game.PlayableEntity;
import com.openggf.game.ShieldType;
import com.openggf.physics.Direction;
import com.openggf.sprites.Sprite;
import com.openggf.sprites.managers.SpriteManager;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TestObjectPlayerQuery {

    @Test
    void mainOnlyNativeReturnsOnlyMainPlayer() {
        FakePlayer main = player("main", 0, 0);
        FakePlayer sidekick = player("tails", 16, 0);
        ObjectPlayerQuery query = query(main, sidekick);

        assertEquals(List.of(main), query.playersFor(ObjectPlayerParticipationPolicy.MAIN_ONLY_NATIVE));
    }

    @Test
    void nativeP1P2ReturnsMainAndFirstSidekickOnly() {
        FakePlayer main = player("main", 0, 0);
        FakePlayer firstSidekick = player("tails", 16, 0);
        FakePlayer secondSidekick = player("knuckles", 32, 0);
        ObjectPlayerQuery query = query(main, firstSidekick, secondSidekick);

        assertEquals(List.of(main, firstSidekick),
                query.playersFor(ObjectPlayerParticipationPolicy.NATIVE_P1_P2));
        assertSame(firstSidekick, query.nativeP2OrNull());
    }

    @Test
    void mainPlusEngineSidekicksUsesSpriteManagerSidekickOrder() {
        FakePlayer main = player("main", 0, 0);
        FakePlayer firstSidekick = player("tails", 16, 0);
        FakePlayer secondSidekick = player("knuckles", 32, 0);
        ObjectPlayerQuery query = query(main, firstSidekick, secondSidekick);

        assertEquals(List.of(main, firstSidekick, secondSidekick),
                query.playersFor(ObjectPlayerParticipationPolicy.MAIN_PLUS_ENGINE_SIDEKICKS_AS_NATIVE_P2_EXTENDED));
    }

    @Test
    void allEnginePlayersExcludesNullsAndDuplicateInstances() {
        FakePlayer main = player("main", 0, 0);
        FakePlayer firstSidekick = player("tails", 16, 0);
        ObjectPlayerQuery query = new ObjectPlayerQuery(
                () -> main,
                () -> Arrays.asList(firstSidekick, null, firstSidekick, main));

        assertEquals(List.of(main, firstSidekick),
                query.playersFor(ObjectPlayerParticipationPolicy.ALL_ENGINE_PLAYERS));
        assertEquals(List.of(firstSidekick), query.sidekicks());
    }

    @Test
    void nearestEnginePlayerKeepsStableOrderOnDistanceTie() {
        FakePlayer main = player("main", 90, 100);
        FakePlayer firstSidekick = player("tails", 110, 100);
        FakePlayer secondSidekick = player("knuckles", 120, 100);
        ObjectPlayerQuery query = query(main, firstSidekick, secondSidekick);

        assertEquals(List.of(main),
                query.playersFor(ObjectPlayerParticipationPolicy.NEAREST_ENGINE_PLAYER, 100, 100));
    }

    @Test
    void nearestRomXPlayerUsesSignedWordXDistanceOnlyAndKeepsMainOnTie() {
        FakePlayer main = player("main", 0x00C0, 0x7000);
        FakePlayer firstSidekick = player("tails", 0x0140, 0);
        FakePlayer secondSidekick = player("knuckles", 0x0180, 100);
        ObjectPlayerQuery query = query(main, firstSidekick, secondSidekick);

        ObjectPlayerQuery.NearestPlayerX nearest = query.nearestByRomX(
                ObjectPlayerParticipationPolicy.ALL_ENGINE_PLAYERS, 0x0100);

        assertSame(main, nearest.player());
        assertEquals(0x40, nearest.distance());
    }

    @Test
    void nearestRomXPlayerUsesParticipationPolicyAndSignedSixteenBitWrapping() {
        FakePlayer main = player("main", 0x0100, 0);
        FakePlayer nativeP2 = player("tails", 0x0020, 0);
        FakePlayer extendedSidekick = player("knuckles", 0xFFE0, 0);
        ObjectPlayerQuery query = query(main, nativeP2, extendedSidekick);

        ObjectPlayerQuery.NearestPlayerX nearestNative = query.nearestByRomX(
                ObjectPlayerParticipationPolicy.NATIVE_P1_P2, 0xFFE0);
        ObjectPlayerQuery.NearestPlayerX nearestExtended = query.nearestByRomX(
                ObjectPlayerParticipationPolicy.ALL_ENGINE_PLAYERS, 0xFFE0);

        assertSame(nativeP2, nearestNative.player());
        assertEquals(0x40, nearestNative.distance());
        assertSame(extendedSidekick, nearestExtended.player());
        assertEquals(0, nearestExtended.distance());
    }

    @Test
    void nearestRomXPlayerCanFilterDeadPlayersBeforeSelectingTarget() {
        FakePlayer deadMain = player("main", 0x0100, 0, true);
        FakePlayer liveSidekick = player("tails", 0x0180, 0);
        ObjectPlayerQuery query = query(deadMain, liveSidekick);

        ObjectPlayerQuery.NearestPlayerX nearest = query.nearestByRomX(
                ObjectPlayerParticipationPolicy.ALL_ENGINE_PLAYERS,
                0x0100,
                livePlayers());

        assertSame(liveSidekick, nearest.player());
        assertEquals(0x80, nearest.distance());
    }

    @Test
    void policyMappingExposesNativeAndEngineParticipationSemantics() {
        assertEquals(ObjectPlayerParticipationPolicy.MAIN_ONLY_NATIVE,
                ObjectPlayerParticipationPolicy.nativePlayers(false));
        assertEquals(ObjectPlayerParticipationPolicy.NATIVE_P1_P2,
                ObjectPlayerParticipationPolicy.nativePlayers(true));
        assertEquals(ObjectPlayerParticipationPolicy.MAIN_PLUS_ENGINE_SIDEKICKS_AS_NATIVE_P2_EXTENDED,
                ObjectPlayerParticipationPolicy.engineSidekicksAsNativeP2(true));
        assertEquals(ObjectPlayerParticipationPolicy.MAIN_ONLY_NATIVE,
                ObjectPlayerParticipationPolicy.engineSidekicksAsNativeP2(false));
    }

    @Test
    void fromObjectServicesPrefersSpriteManagerMainPlayableOverCameraFocus() {
        AbstractPlayableSprite spriteManagerMain = mock(AbstractPlayableSprite.class);
        when(spriteManagerMain.isCpuControlled()).thenReturn(false);
        AbstractPlayableSprite cameraFocus = mock(AbstractPlayableSprite.class);
        when(cameraFocus.isCpuControlled()).thenReturn(false);
        AbstractPlayableSprite sidekick = mock(AbstractPlayableSprite.class);
        when(sidekick.isCpuControlled()).thenReturn(true);

        SpriteManager spriteManager = mock(SpriteManager.class);
        when(spriteManager.getAllSprites()).thenReturn(List.<Sprite>of(sidekick, spriteManagerMain));
        when(spriteManager.getSidekicks()).thenReturn(List.of(sidekick));

        Camera camera = mock(Camera.class);
        when(camera.getFocusedSprite()).thenReturn(cameraFocus);

        ObjectServices services = mock(ObjectServices.class);
        when(services.spriteManager()).thenReturn(spriteManager);
        when(services.camera()).thenReturn(camera);
        when(services.sidekicks()).thenReturn(List.of(sidekick));

        ObjectPlayerQuery query = ObjectPlayerQuery.from(services);

        assertSame(spriteManagerMain, query.mainPlayerOrNull());
        assertEquals(List.of(spriteManagerMain, sidekick),
                query.playersFor(ObjectPlayerParticipationPolicy.ALL_ENGINE_PLAYERS));
    }

    @Test
    void fromObjectServicesUsesConfiguredMainCharacterBeforeFirstNonCpuFallback() {
        AbstractPlayableSprite sonic = mock(AbstractPlayableSprite.class);
        when(sonic.isCpuControlled()).thenReturn(false);
        AbstractPlayableSprite tails = mock(AbstractPlayableSprite.class);
        when(tails.isCpuControlled()).thenReturn(false);

        SpriteManager spriteManager = mock(SpriteManager.class);
        when(spriteManager.getSprite("tails")).thenReturn(tails);
        when(spriteManager.getAllSprites()).thenReturn(List.<Sprite>of(sonic, tails));

        SonicConfigurationService configuration = mock(SonicConfigurationService.class);
        when(configuration.getString(SonicConfiguration.MAIN_CHARACTER_CODE)).thenReturn("tails");

        ObjectServices services = mock(ObjectServices.class);
        when(services.spriteManager()).thenReturn(spriteManager);
        when(services.configuration()).thenReturn(configuration);
        when(services.sidekicks()).thenReturn(List.of());

        ObjectPlayerQuery query = ObjectPlayerQuery.from(services);

        assertSame(tails, query.mainPlayerOrNull());
    }

    @Test
    void objectServicesExposesPlayerQueryWhileRetainingRawSidekicks() {
        AbstractPlayableSprite main = mock(AbstractPlayableSprite.class);
        when(main.isCpuControlled()).thenReturn(false);
        AbstractPlayableSprite sidekick = mock(AbstractPlayableSprite.class);
        when(sidekick.isCpuControlled()).thenReturn(true);

        SpriteManager spriteManager = mock(SpriteManager.class);
        when(spriteManager.getAllSprites()).thenReturn(List.<Sprite>of(main, sidekick));

        TestObjectServices services = new TestObjectServices()
                .withSpriteManager(spriteManager)
                .withSidekicks(List.of(sidekick));

        ObjectPlayerQuery query = services.playerQuery();

        assertEquals(List.of(sidekick), services.sidekicks());
        assertEquals(List.of(main, sidekick),
                query.playersFor(ObjectPlayerParticipationPolicy.ALL_ENGINE_PLAYERS));
    }

    private static ObjectPlayerQuery query(FakePlayer main, FakePlayer... sidekicks) {
        return new ObjectPlayerQuery(() -> main, () -> Arrays.asList(sidekicks));
    }

    private static FakePlayer player(String name, int x, int y) {
        return player(name, x, y, false);
    }

    private static FakePlayer player(String name, int x, int y, boolean dead) {
        return new FakePlayer(name, (short) x, (short) y, dead);
    }

    private static Predicate<PlayableEntity> livePlayers() {
        return player -> !player.getDead();
    }

    private record FakePlayer(String name, short centreX, short centreY, boolean dead) implements PlayableEntity {
        @Override public short getCentreX() { return centreX; }
        @Override public short getCentreY() { return centreY; }
        @Override public void setCentreX(short x) {}
        @Override public void setCentreYPreserveSubpixel(short y) {}
        @Override public short getX() { return centreX; }
        @Override public short getY() { return centreY; }
        @Override public void setY(short y) {}
        @Override public void shiftX(int delta) {}
        @Override public void move(short xSpeed, short ySpeed) {}
        @Override public void move() {}
        @Override public short getCentreX(int framesBehind) { return centreX; }
        @Override public short getCentreY(int framesBehind) { return centreY; }
        @Override public int getHeight() { return 0; }
        @Override public short getYRadius() { return 0; }
        @Override public short getXRadius() { return 0; }
        @Override public short getRollHeightAdjustment() { return 0; }
        @Override public short getXSpeed() { return 0; }
        @Override public short getYSpeed() { return 0; }
        @Override public void setXSpeed(short xSpeed) {}
        @Override public void setYSpeed(short ySpeed) {}
        @Override public short getGSpeed() { return 0; }
        @Override public void setGSpeed(short gSpeed) {}
        @Override public boolean getAir() { return false; }
        @Override public void setAir(boolean air) {}
        @Override public byte getAngle() { return 0; }
        @Override public void setAngle(byte angle) {}
        @Override public boolean getRolling() { return false; }
        @Override public void setRolling(boolean rolling) {}
        @Override public boolean getSpindash() { return false; }
        @Override public GroundMode getGroundMode() { return GroundMode.GROUND; }
        @Override public void setGroundMode(GroundMode groundMode) {}
        @Override public boolean isObjectControlled() { return false; }
        @Override public boolean isOnObject() { return false; }
        @Override public void setOnObject(boolean onObject) {}
        @Override public void setPushing(boolean pushing) {}
        @Override public boolean getPinballMode() { return false; }
        @Override public boolean isCpuControlled() { return false; }
        @Override public int getAnimationId() { return 0; }
        @Override public int getMappingFrame() { return 0; }
        @Override public void forceAnimationRestart() {}
        @Override public void setTopSolidBit(byte topSolidBit) {}
        @Override public void setLrbSolidBit(byte lrbSolidBit) {}
        @Override public void setLayer(byte layer) {}
        @Override public boolean isHighPriority() { return false; }
        @Override public void setHighPriority(boolean highPriority) {}
        @Override public boolean getDead() { return dead; }
        @Override public boolean isDebugMode() { return false; }
        @Override public boolean getInvulnerable() { return false; }
        @Override public boolean hasShield() { return false; }
        @Override public ShieldType getShieldType() { return ShieldType.BASIC; }
        @Override public int getInvincibleFrames() { return 0; }
        @Override public int getDoubleJumpFlag() { return 0; }
        @Override public boolean isSuperSonic() { return false; }
        @Override public boolean getCrouching() { return false; }
        @Override public int getRingCount() { return 0; }
        @Override public Direction getDirection() { return Direction.RIGHT; }
        @Override public boolean applyHurt(int sourceX) { return false; }
        @Override public boolean applyHurt(int sourceX, boolean spikeHit) { return false; }
        @Override public boolean applyHurt(int sourceX, DamageCause cause) { return false; }
        @Override public boolean applyHurtIgnoringIFrames(int sourceX, DamageCause cause) { return false; }
        @Override public boolean applyHurtOrDeath(int sourceX, boolean spikeHit, boolean hadRings) { return false; }
        @Override public boolean applyHurtOrDeath(int sourceX, DamageCause cause, boolean hadRings) { return false; }
        @Override public boolean applyCrushDeath() { return false; }
        @Override public int incrementBadnikChain() { return 0; }
        @Override public com.openggf.game.rules.GameRules getGameRules() { return null; }
    }
}
