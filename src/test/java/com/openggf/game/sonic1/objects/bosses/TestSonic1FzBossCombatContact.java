package com.openggf.game.sonic1.objects.bosses;

import com.openggf.game.sonic1.constants.Sonic1AnimationIds;
import com.openggf.game.sonic1.constants.Sonic1ObjectIds;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.SolidContact;
import com.openggf.level.objects.TestObjectServices;
import com.openggf.level.objects.boss.BossStateContext;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import static org.mockito.ArgumentMatchers.anyShort;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestSonic1FzBossCombatContact {

    @Test
    void combatSolidUsesFrameStartBossPositionBeforeCylinderSlotsMoveParent() throws Exception {
        Sonic1FZBossInstance boss = combatBoss();

        assertTrue(boss.usesPreUpdatePositionForSolidContact(mock(AbstractPlayableSprite.class)));
    }

    @Test
    void rollingStatusWithoutRollAnimationDoesNotBounceFromBossSide() throws Exception {
        Sonic1FZBossInstance boss = combatBoss();
        AbstractPlayableSprite player = mock(AbstractPlayableSprite.class);
        when(player.getRolling()).thenReturn(true);
        when(player.getAnimationId()).thenReturn(Sonic1AnimationIds.WALK.id());

        boss.onSolidContact(player, new SolidContact(false, true, false, false, false), 356);

        verify(player, never()).setXSpeed(anyShort());
    }

    @Test
    void initialRollMappingFrameDoesNotBounceFromBossSide() throws Exception {
        Sonic1FZBossInstance boss = combatBoss();
        bossState(boss).x = 100;
        AbstractPlayableSprite player = mock(AbstractPlayableSprite.class);
        when(player.getRolling()).thenReturn(true);
        when(player.getAnimationId()).thenReturn(Sonic1AnimationIds.ROLL.id());
        when(player.getMappingFrame()).thenReturn(0x2E);
        when(player.getGSpeed()).thenReturn((short) 0x18);
        when(player.getPushingAtFrameStart()).thenReturn(true);
        when(player.getCentreX()).thenReturn((short) 99);

        boss.onSolidContact(player, new SolidContact(false, true, false, false, false), 356);

        when(player.getMappingFrame()).thenReturn(0x30);
        boss.onSolidContact(player, new SolidContact(false, true, false, false, false), 358);

        verify(player, never()).setXSpeed(anyShort());
    }

    @Test
    void pushedRollStartingOnBossRightStillBounces() throws Exception {
        Sonic1FZBossInstance boss = combatBoss();
        bossState(boss).x = 100;
        bossState(boss).renderFlags = 1;
        AbstractPlayableSprite player = mock(AbstractPlayableSprite.class);
        when(player.getRolling()).thenReturn(true);
        when(player.getAnimationId()).thenReturn(Sonic1AnimationIds.ROLL.id());
        when(player.getMappingFrame()).thenReturn(0x2E);
        when(player.getGSpeed()).thenReturn((short) -0x18);
        when(player.getPushingAtFrameStart()).thenReturn(true);
        when(player.getCentreX()).thenReturn((short) 101);
        when(player.getYRadius()).thenReturn((short) 14);

        boss.onSolidContact(player, new SolidContact(false, true, false, false, false), 0);

        verify(player).setXSpeed((short) 0x300);
    }

    @Test
    void establishedRollMappingFrameStillBouncesFromBossSide() throws Exception {
        Sonic1FZBossInstance boss = combatBoss();
        AbstractPlayableSprite player = mock(AbstractPlayableSprite.class);
        when(player.getRolling()).thenReturn(true);
        when(player.getAnimationId()).thenReturn(Sonic1AnimationIds.ROLL.id());
        when(player.getMappingFrame()).thenReturn(0x30);
        when(player.getYRadius()).thenReturn((short) 14);

        boss.onSolidContact(player, new SolidContact(false, true, false, false, false), 0x1074);

        verify(player).setXSpeed((short) -0x300);
    }

    @Test
    void ongoingRollDoesNotBecomeSuppressedWhenMappingWrapsNearBoss() throws Exception {
        Sonic1FZBossInstance boss = combatBoss();
        AbstractPlayableSprite player = mock(AbstractPlayableSprite.class);
        when(player.getAir()).thenReturn(true);
        when(player.getRolling()).thenReturn(true);
        when(player.getAnimationId()).thenReturn(Sonic1AnimationIds.ROLL.id());
        when(player.getYRadius()).thenReturn((short) 14);
        when(player.getGSpeed()).thenReturn((short) 0x18);

        Method updateCylinderAttack = Sonic1FZBossInstance.class
                .getDeclaredMethod("updateCylinderAttack", AbstractPlayableSprite.class);
        updateCylinderAttack.setAccessible(true);
        when(player.getCentreX()).thenReturn((short) 0x100);
        when(player.getMappingFrame()).thenReturn(0x30);
        updateCylinderAttack.invoke(boss, player);

        when(player.getCentreX()).thenReturn((short) bossState(boss).x);
        when(player.getMappingFrame()).thenReturn(0x2E);
        updateCylinderAttack.invoke(boss, player);

        when(player.getMappingFrame()).thenReturn(0x31);
        boss.onSolidContact(player, new SolidContact(false, true, false, false, false), 1);

        verify(player).setXSpeed((short) 0x300);
    }

    @Test
    void initialFalseRollSuppressionEndsAtFirstNeutralSpeedWrap() throws Exception {
        Sonic1FZBossInstance boss = combatBoss();
        AbstractPlayableSprite player = mock(AbstractPlayableSprite.class);
        when(player.getAir()).thenReturn(true);
        when(player.getRolling()).thenReturn(true);
        when(player.getAnimationId()).thenReturn(Sonic1AnimationIds.ROLL.id());
        when(player.getYRadius()).thenReturn((short) 14);
        when(player.getMappingFrame()).thenReturn(0x2E);
        when(player.getGSpeed()).thenReturn((short) 0x18);
        when(player.getPushingAtFrameStart()).thenReturn(true);
        boss.onSolidContact(player, new SolidContact(false, true, false, false, false), 0);

        Method updateCylinderAttack = Sonic1FZBossInstance.class
                .getDeclaredMethod("updateCylinderAttack", AbstractPlayableSprite.class);
        updateCylinderAttack.setAccessible(true);
        when(player.getMappingFrame()).thenReturn(0x30);
        when(player.getGSpeed()).thenReturn((short) 0);
        updateCylinderAttack.invoke(boss, player);
        when(player.getMappingFrame()).thenReturn(0x2E);
        updateCylinderAttack.invoke(boss, player);

        when(player.getMappingFrame()).thenReturn(0x31);
        boss.onSolidContact(player, new SolidContact(false, true, false, false, false), 1);

        verify(player).setXSpeed((short) -0x300);
    }

    @Test
    void outsideVerticalBoundaryDoesNotEnterBossSideBouncePath() throws Exception {
        Sonic1FZBossInstance boss = combatBoss();
        AbstractPlayableSprite player = mock(AbstractPlayableSprite.class);
        when(player.getAnimationId()).thenReturn(Sonic1AnimationIds.ROLL.id());
        when(player.getMappingFrame()).thenReturn(0x30);
        when(player.getYRadius()).thenReturn((short) 14);
        when(player.getCentreY()).thenReturn((short) -39);

        boss.onSolidContact(player, new SolidContact(false, true, false, false, false), 1300);

        verify(player, never()).setXSpeed(anyShort());
    }

    private static Sonic1FZBossInstance combatBoss() throws Exception {
        return combatBoss(new TestObjectServices());
    }

    private static Sonic1FZBossInstance combatBoss(TestObjectServices services) throws Exception {
        Sonic1FZBossInstance boss = new Sonic1FZBossInstance(
                new ObjectSpawn(0, 0, Sonic1ObjectIds.FZ_BOSS, 0, 0, false, 0));
        boss.setServices(services);
        BossStateContext state = bossState(boss);
        state.routineSecondary = 2;
        state.y = 0;
        return boss;
    }

    private static BossStateContext bossState(Sonic1FZBossInstance boss) throws Exception {
        Field stateField = Sonic1FZBossInstance.class.getSuperclass().getDeclaredField("state");
        stateField.setAccessible(true);
        return (BossStateContext) stateField.get(boss);
    }
}
