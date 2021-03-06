package uk.co.jamesj999.sonic.configuration;

import org.apache.commons.lang3.StringUtils;

public class SonicConfigurationService {
	private static SonicConfigurationService sonicConfigurationService;

	public int getInt(SonicConfiguration sonicConfiguration) {
		Object value = sonicConfiguration.getValue();
		if (value instanceof Integer) {
			return ((Integer) value).intValue();
		} else {
			try {
				return Integer.parseInt(getString(sonicConfiguration));
			} catch (NumberFormatException e) {
				return -1;
			}
		}
	}

	public short getShort(SonicConfiguration sonicConfiguration) {
		Object value = sonicConfiguration.getValue();
		if (value instanceof Short) {
			return ((Short) value).shortValue();
		} else if (value instanceof Integer) {
			return (short) getInt(sonicConfiguration);
		} else {
			try {
				return Short.parseShort(getString(sonicConfiguration));
			} catch (NumberFormatException e) {
				return -1;
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

	public double getDouble(SonicConfiguration sonicConfiguration) {
		Object value = sonicConfiguration.getValue();
		if (value instanceof Double) {
			return ((Double) value).doubleValue();
		} else {
			try {
				return Double.parseDouble(getString(sonicConfiguration));
			} catch (NumberFormatException e) {
				return -1.00d;
			}
		}
	}

    public boolean getBoolean(SonicConfiguration sonicConfiguration) {
        Object value = sonicConfiguration.getValue();
        if(value instanceof Boolean) {
            return ((Boolean) value).booleanValue();
        } else {
            return Boolean.parseBoolean(getString(sonicConfiguration));
        }
    }

	public synchronized static SonicConfigurationService getInstance() {
		if (sonicConfigurationService == null) {
			sonicConfigurationService = new SonicConfigurationService();
		}
		return sonicConfigurationService;
	}
}
