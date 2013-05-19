package uk.co.jamesj999.sonic.sprites;

import java.awt.image.BufferedImage;

import org.apache.commons.lang3.StringUtils;

import uk.co.jamesj999.sonic.configuration.SonicConfiguration;
import uk.co.jamesj999.sonic.configuration.SonicConfigurationService;

public abstract class AbstractSprite implements Sprite {
	protected final SonicConfigurationService configService = SonicConfigurationService.getInstance();
	
	protected BufferedImage spriteImage = new BufferedImage(
			configService.getInt(SonicConfiguration.SCREEN_WIDTH),
			configService.getInt(SonicConfiguration.SCREEN_HEIGHT),
			BufferedImage.TYPE_INT_RGB);
	
	protected String code;
	
	protected int x;
	protected int y;
	
	protected int width;
	protected int height;

	protected AbstractSprite(String code, int x, int y) {
		this.code = code;
		this.x = x;
		this.y = y;
	}
	
	public final String getCode() {
		return (code != null) ? code : StringUtils.EMPTY;
	}
	
	public final void setCode(String code) {
		this.code = code;
	}
	
	public final int getX() {
		return x;
	}
	
	public final void setX(int x) {
		this.x = x;
	}
	
	public final int getY() {
		return y;
	}
	
	public final void setY(int y) {
		this.y = y;
	}
	
	public int getWidth() {
		return width;
	}

	public void setWidth(int width) {
		this.width = width;
	}

	public int getHeight() {
		return height;
	}

	public void setHeight(int height) {
		this.height = height;
	}
}
