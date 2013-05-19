package uk.co.jamesj999.sonic.sprites;

import org.apache.commons.lang3.StringUtils;

public abstract class AbstractSprite implements Sprite {
	protected String code;
	protected int x;
	protected int y;

	protected AbstractSprite(String code) {
		this.code = code;
	}
	
	public String getCode() {
		return (code != null) ? code : StringUtils.EMPTY;
	}
	
	public void setCode(String code) {
		this.code = code;
	}
}
