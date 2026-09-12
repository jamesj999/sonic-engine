package com.openggf.game.sonic3k.objects;

import com.openggf.level.objects.ObjectPlayerQuery;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;

class TestNativePlayerSlots {

    @Test
    void preservesNativeP1P2OrderAndExcludesEngineExtensions() {
        AbstractPlayableSprite main = mock(AbstractPlayableSprite.class);
        AbstractPlayableSprite nativeP2 = mock(AbstractPlayableSprite.class);
        AbstractPlayableSprite extension = mock(AbstractPlayableSprite.class);

        NativePlayerSlots slots = NativePlayerSlots.resolve(
                new ObjectPlayerQuery(() -> main, () -> List.of(nativeP2, extension)), main);

        assertSame(main, slots.p1());
        assertSame(nativeP2, slots.p2());
        assertNull(slots.player(2));
    }

    @Test
    void absentP1RetainsTheQueryNativeP2Slot() {
        AbstractPlayableSprite nativeP2 = mock(AbstractPlayableSprite.class);

        NativePlayerSlots slots = NativePlayerSlots.resolve(
                new ObjectPlayerQuery(() -> null, () -> List.of(nativeP2)), null);

        assertNull(slots.p1());
        assertSame(nativeP2, slots.p2());
    }

    @Test
    void usesTheUpdatePlayerAsP1WhenTheQueryHasNoPlayableMain() {
        AbstractPlayableSprite updatePlayer = mock(AbstractPlayableSprite.class);
        AbstractPlayableSprite nativeP2 = mock(AbstractPlayableSprite.class);

        NativePlayerSlots slots = NativePlayerSlots.resolve(
                new ObjectPlayerQuery(() -> null, () -> List.of(nativeP2)), updatePlayer);

        assertSame(updatePlayer, slots.p1());
        assertSame(nativeP2, slots.p2());
    }

    @Test
    void rejectsTheMainPlayerDuplicateBeforeChoosingNativeP2() {
        AbstractPlayableSprite main = mock(AbstractPlayableSprite.class);
        AbstractPlayableSprite nativeP2 = mock(AbstractPlayableSprite.class);

        NativePlayerSlots slots = NativePlayerSlots.resolve(
                new ObjectPlayerQuery(() -> main, () -> List.of(main, nativeP2, nativeP2)), main);

        assertSame(main, slots.p1());
        assertSame(nativeP2, slots.p2());
    }
}
