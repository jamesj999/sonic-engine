package uk.co.jamesj999.sonic.configuration;

import org.apache.commons.lang3.StringUtils;

public class SonicConfigurationService {
	private static SonicConfigurationService sonicConfigurationService;

	public Integer getInt(SonicConfiguration sonicConfiguration) {
		Object value = sonicConfiguration.getValue();
		if (value instanceof Integer) {
			return ((Integer) value).intValue();
		} else {
			try {
				return Integer.parseInt(getString(sonicConfiguration));
			} catch (NumberFormatException e) {
				return null;
			}
		}
	}

	public String getString(SonicConfiguration sonicConfiguration) {
		if (sonicConfiguration.getValue() != null) {
			return sonicConfiguration.getValue().toString();
		} else {
			return StringUtils.EMPTY;
		}
	}

	public static SonicConfigurationService getInstance() {
		if (sonicConfigurationService == null) {
			sonicConfigurationService = new SonicConfigurationService();
		}
		return sonicConfigurationService;
	}
}
