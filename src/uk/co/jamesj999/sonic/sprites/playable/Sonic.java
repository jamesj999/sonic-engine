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
		graphics.fillRect(x, y, width, height);
		return spriteImage;
	}

	@Override
	public void defineSpeeds() {
		runAccel = 0.046875f;
		runDecel = 0.5f;
		friction = 0.046875f;
		max = 14f;
	}
}
