package com.openggf.camera;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.game.GameServices;
import com.openggf.tests.TestEnvironment;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Tests for the ROM {@code Scroll_force_positions} model on {@link Camera}
 * ({@link Camera#requestForcedScroll(int, int)}).
 *
 * <p>ROM {@code loc_1BFB8} (sonic3k.asm:38296-38300): when
 * {@code Scroll_force_positions} is set, the camera routine clears the flag,
 * zeros {@code H_scroll_frame_offset}, and points the camera-position math at
 * {@code Scroll_forced_X_pos}/{@code Scroll_forced_Y_pos} instead of Player_1
 * for that frame. Setter {@code loc_226F2} (sonic3k.asm:47072-47074) writes the
 * flag plus the forced X/Y — the request therefore carries coordinates.
 */
public class TestCameraForcedScroll {

    private Camera camera;
    private AbstractPlayableSprite mockSprite;

    @BeforeEach
    public void setUp() throws Exception {
        TestEnvironment.resetAll();
        camera = GameServices.camera();

        // Player sits in the horizontal deadzone (centreX 160, camera X 0 -> no
        // horizontal scroll) and at the vertical bias (centreY 96, camera Y 0 ->
        // no vertical scroll). So any camera movement below is driven ONLY by the
        // forced coordinates, not the player.
        mockSprite = mock(AbstractPlayableSprite.class);
        when(mockSprite.getCentreX()).thenReturn((short) 160);
        when(mockSprite.getCentreY()).thenReturn((short) 96);
        when(mockSprite.getCentreX(anyInt())).thenReturn((short) 160);
        when(mockSprite.getCentreY(anyInt())).thenReturn((short) 96);
        when(mockSprite.getX()).thenReturn((short) 160);
        when(mockSprite.getY()).thenReturn((short) 96);
        when(mockSprite.getAir()).thenReturn(false);
        when(mockSprite.getRolling()).thenReturn(false);
        when(mockSprite.getYSpeed()).thenReturn((short) 0);
        when(mockSprite.getGSpeed()).thenReturn((short) 0);

        camera.setFocusedSprite(mockSprite);
        camera.setMinX((short) 0);
        camera.setMinY((short) 0);
        camera.setMaxX((short) 6000);
        camera.setMaxY((short) 1000);
        camera.setX((short) 0);
        camera.setY((short) 0);
    }

    @Test
    public void testForcedScrollTracksForcedCoordinatesNotPlayer() {
        // Player is in the deadzone/at-bias, so without a forced request the
        // camera would not move at all.
        camera.requestForcedScroll(500, 200);
        camera.updatePosition();

        // Horizontal: forced X 500 is far right of the deadzone -> capped move of
        // 16px this frame. If the player position were used the camera would stay
        // at 0 (player in deadzone).
        assertEquals(16, camera.getX(),
                "Forced scroll should drive horizontal camera math from the forced X, not the player");

        // Vertical: forced Y 200 -> grounded delta 104 above bias, medium 6px cap.
        // If the player position were used the camera would stay at 0 (at bias).
        assertEquals(6, camera.getY(),
                "Forced scroll should drive vertical camera math from the forced Y, not the player");
    }

    @Test
    public void testForcedScrollZeroesHorizontalScrollFrameOffset() {
        // A pending horizontal scroll delay (ROM H_scroll_frame_offset) must be
        // zeroed by a forced-scroll frame (ROM loc_1BFB8 move.w #0,H_scroll...).
        camera.setHorizScrollDelay(8);

        camera.requestForcedScroll(160, 96);
        camera.updatePosition();

        assertEquals(0, camera.getHorizScrollDelay(),
                "Forced scroll should zero the horizontal scroll frame offset");
    }

    @Test
    public void testForcedScrollRequestDoesNotPersistToNextFrame() {
        camera.requestForcedScroll(500, 200);
        camera.updatePosition();

        assertEquals(16, camera.getX(), "sanity: forced frame moved X toward forced X");
        assertEquals(6, camera.getY(), "sanity: forced frame moved Y toward forced Y");

        // Next frame with no new request: the camera must track the PLAYER again
        // (player is in the deadzone / at bias). If the request had persisted the
        // camera would keep advancing toward the forced (500,200): X -> 32, Y -> 12.
        // Instead X stays in the deadzone sweet spot (160-16=144) and Y scrolls the
        // 6px back down to the player's bias.
        camera.updatePosition();

        assertEquals(16, camera.getX(),
                "Forced scroll request should not persist: X tracks the player, not the forced X");
        assertEquals(0, camera.getY(),
                "Forced scroll request should not persist: Y tracks the player back to bias, not the forced Y");
    }
}
