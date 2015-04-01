package uk.co.jamesj999.sonic.sprites.interactive.monitors;

import uk.co.jamesj999.sonic.sprites.AbstractSprite;
import uk.co.jamesj999.sonic.sprites.Direction;
import uk.co.jamesj999.sonic.sprites.Sprite;
import uk.co.jamesj999.sonic.sprites.interactive.InteractiveSprite;

/**
 * Created by Jamesjohnstone on 01/04/15.
 */
public abstract class AbstractMonitor extends AbstractSprite implements InteractiveSprite {

    protected AbstractMonitor(String code, short xPixel, short yPixel) {
        super(code, xPixel, yPixel);
        //TODO: Find out the real dimensions
        setWidth(20);
        setHeight(20);
    }
}
