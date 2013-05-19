package uk.co.jamesj999.sonic.sprites;

import java.awt.Graphics;

import org.apache.commons.lang3.StringUtils;

public abstract class AbstractSprite implements Sprite {
	protected String code;
	
	protected int x;
	protected int y;
	
	protected Graphics graphics;

	protected AbstractSprite(String code) {
		this.code = code;
	}
	
	public final String getCode() {
		return (code != null) ? code : StringUtils.EMPTY;
	}
	
	public final void setCode(String code) {
		this.code = code;
	}
	
	public final Graphics getGraphics() {
		return graphics;
	}
}
