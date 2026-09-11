package com.openggf.sprites.playable;

import com.openggf.sprites.managers.TailsTailsController;
import com.openggf.sprites.managers.SpindashDustController;
import com.openggf.sprites.render.PlayerSpriteRenderer;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class TestTailsRendering {

    @Test
    void hiddenTailsSuppressesBothParentAndIndependentTailChild() {
        Tails tails = new Tails("tails_p2", (short) 0, (short) 0);
        TailsTailsController child = mock(TailsTailsController.class);
        PlayerSpriteRenderer renderer = mock(PlayerSpriteRenderer.class);
        SpindashDustController dust = mock(SpindashDustController.class);
        tails.setTailsTailsController(child);
        tails.setSpriteRenderer(renderer);
        tails.setSpindashDustController(dust);
        tails.setHidden(true);

        tails.draw();

        verifyNoInteractions(child, renderer, dust);
    }

    @Test
    void visibleS2StyleTailsKeepsTailChildBeforeParentRendering() {
        Tails tails = new Tails("tails_p2", (short) 0, (short) 0);
        TailsTailsController child = mock(TailsTailsController.class);
        PlayerSpriteRenderer renderer = mock(PlayerSpriteRenderer.class);
        SpindashDustController dust = mock(SpindashDustController.class);
        tails.setTailsTailsController(child);
        tails.setSpriteRenderer(renderer);
        tails.setSpindashDustController(dust);
        tails.setHidden(false);

        tails.draw();

        InOrder order = inOrder(child, dust, renderer);
        order.verify(child).draw();
        order.verify(dust).draw();
        order.verify(renderer).drawFrame(
                anyInt(), anyInt(), anyInt(), anyBoolean(), anyBoolean());
        verify(child, never()).update();
    }
}
