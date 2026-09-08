package com.openggf.game.patch;

import com.openggf.game.GameModule;
import com.openggf.game.ContinueScreenProvider;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;

/**
 * Keeps patch decorators honest as {@link GameModule} evolves. Default methods
 * are deliberately included: inheriting one would bypass the wrapped module.
 */
class TestDelegatingGameModuleCoversInterface {

    @Test
    void continueFactoryPreservesFreshProvidersFromWrappedModule() {
        GameModule base = mock(GameModule.class);
        ContinueScreenProvider first = mock(ContinueScreenProvider.class);
        ContinueScreenProvider second = mock(ContinueScreenProvider.class);
        when(base.createContinueScreenProvider()).thenReturn(first, second);
        DelegatingGameModule patched = new DelegatingGameModule(base, "test-patch");

        assertSame(first, patched.createContinueScreenProvider());
        assertSame(second, patched.createContinueScreenProvider());
        verify(base, times(2)).createContinueScreenProvider();
    }

    @Test
    void gameplayFactoriesAndGameOverFlowPreserveWrappedProviders() {
        GameModule base = mock(GameModule.class);
        var flow = mock(com.openggf.game.GameOverFlowProvider.class);
        java.util.function.BiFunction<com.openggf.sprites.playable.AbstractPlayableSprite,
                com.openggf.game.ShieldType, com.openggf.level.objects.ShieldObjectInstance>
                shield = (player, type) -> null;
        java.util.function.Function<com.openggf.sprites.playable.AbstractPlayableSprite,
                com.openggf.level.objects.AbstractObjectInstance> instaShield = player -> null;
        java.util.function.BiFunction<Integer, Integer,
                com.openggf.level.objects.AbstractObjectInstance> splash = (x, y) -> null;
        when(base.getGameOverFlowProvider()).thenReturn(flow);
        when(base.getShieldFactory()).thenReturn(shield);
        when(base.getInstaShieldFactory()).thenReturn(instaShield);
        when(base.getWaterSplashFactory()).thenReturn(splash);
        DelegatingGameModule patched = new DelegatingGameModule(base, "test-patch");

        assertSame(flow, patched.getGameOverFlowProvider());
        assertSame(shield, patched.getShieldFactory());
        assertSame(instaShield, patched.getInstaShieldFactory());
        assertSame(splash, patched.getWaterSplashFactory());
    }

    @Test
    void delegatingGameModuleDeclaresEveryGameModuleMethod() {
        List<String> missing = new ArrayList<>();
        for (Method method : GameModule.class.getMethods()) {
            if (Modifier.isStatic(method.getModifiers())) {
                continue;
            }
            try {
                DelegatingGameModule.class.getDeclaredMethod(
                        method.getName(), method.getParameterTypes());
            } catch (NoSuchMethodException e) {
                missing.add(method.getName() + Arrays.toString(method.getParameterTypes()));
            }
        }
        assertTrue(missing.isEmpty(),
                "DelegatingGameModule must forward these GameModule methods to the base module:\n"
                        + String.join("\n", missing));
    }
}
