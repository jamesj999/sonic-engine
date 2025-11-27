package uk.co.jamesj999.sonic.sprites.interactive.monitors;

import uk.co.jamesj999.sonic.audio.AudioManager;
import uk.co.jamesj999.sonic.graphics.GLCommand;
import uk.co.jamesj999.sonic.sprites.AbstractSprite;

import com.jogamp.opengl.GL2;

/**
 * Created by Jamesjohnstone on 01/04/15.
 */
public class RingMonitor extends AbstractMonitor {
    protected int noOfRings;

    public RingMonitor(String code, short x, short y, int noOfRings) {
        super(code, x, y);
    }

    public int getNoOfRings() {
        return noOfRings;
    }

    public void setNoOfRings(int noOfRings) {
        this.noOfRings = noOfRings;
    }

    @Override
    protected void createSensorLines() {

    }

    public void draw() {
        graphicsManager.registerCommand(new GLCommand(GLCommand.CommandType.RECTI,
                GL2.GL_2D, 1, 1, 1, xPixel, yPixel, xPixel + width, yPixel
                - height));
        graphicsManager.registerCommand(new GLCommand(GLCommand.CommandType.VERTEX2I,
                -1, 1, 0, 0, getCentreX(), getCentreY(), 0, 0));
    }

    @Override
    public boolean onCollide(AbstractSprite sprite) {
        // Add rings here:
        AudioManager.getInstance().playSfx("RING");

        // Call standard method for monitor collision:
        return super.onCollide(sprite);
    }
}
