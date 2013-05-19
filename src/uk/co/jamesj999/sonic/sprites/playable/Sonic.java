package uk.co.jamesj999.sonic.sprites.playable;

import java.awt.Color;
import java.awt.Graphics;
import java.awt.image.BufferedImage;


public class Sonic extends AbstractPlayableSprite {

	public Sonic(String code, int x, int y) {
		super(code, x, y);
		setWidth(16);
		setHeight(16);
	}

	@Override
	public BufferedImage draw() {
		Graphics graphics = spriteImage.getGraphics();
		graphics.setColor(Color.BLACK);
		graphics.fillRect(x, y, 16, 16);
		return spriteImage;
	}
}
