package uk.co.jamesj999.sonic.tests;

import org.junit.Test;
import uk.co.jamesj999.sonic.Control.InputHandler;

import java.awt.Canvas;
import java.awt.event.KeyEvent;

import static org.junit.Assert.*;

public class TestInputHandler {
    @Test
    public void testKeyPressRelease() {
        Canvas canvas = new Canvas();
        InputHandler handler = new InputHandler(canvas);
        KeyEvent press = new KeyEvent(canvas, KeyEvent.KEY_PRESSED, System.currentTimeMillis(), 0, KeyEvent.VK_A, 'a');
        handler.keyPressed(press);
        assertTrue(handler.isKeyDown(KeyEvent.VK_A));
        KeyEvent release = new KeyEvent(canvas, KeyEvent.KEY_RELEASED, System.currentTimeMillis(), 0, KeyEvent.VK_A, 'a');
        handler.keyReleased(release);
        assertFalse(handler.isKeyDown(KeyEvent.VK_A));
        assertFalse(handler.isKeyDown(999));
    }
}
