package uk.co.jamesj999.sonic.configuration;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.lang3.StringUtils;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

public class SonicConfigurationService {
	private static SonicConfigurationService sonicConfigurationService;
	private Map<String, Object> config;

	private SonicConfigurationService() {
		ObjectMapper mapper = new ObjectMapper();
		TypeReference<Map<String, Object>> type = new TypeReference<>(){};
		try (InputStream is = getClass().getResourceAsStream("/config.json")) {
			if (is != null) {
				config = mapper.readValue(is, type);
			} else {
				System.err.println("Could not find config.json, using defaults.");
				config = new HashMap<>();
			}
		} catch (IOException e) {
			e.printStackTrace();
			config = new HashMap<>();
		}
	}

	public int getInt(SonicConfiguration sonicConfiguration) {
		Object value = getConfigValue(sonicConfiguration);
		if (value instanceof Integer) {
			return ((Integer) value);
		} else {
			try {
				return Integer.parseInt(getString(sonicConfiguration));
			} catch (NumberFormatException e) {
				return -1;
			}
		}
	}

	public short getShort(SonicConfiguration sonicConfiguration) {
		Object value = getConfigValue(sonicConfiguration);
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
		Object value = getConfigValue(sonicConfiguration);
		if (value != null) {
			return value.toString();
		} else {
			return StringUtils.EMPTY;
		}
	}

	public double getDouble(SonicConfiguration sonicConfiguration) {
		Object value = getConfigValue(sonicConfiguration);
		if (value instanceof Double) {
			return ((Double) value);
		} else {
			try {
				return Double.parseDouble(getString(sonicConfiguration));
			} catch (NumberFormatException e) {
				return -1.00d;
			}
		}
	}

    public boolean getBoolean(SonicConfiguration sonicConfiguration) {
        Object value = getConfigValue(sonicConfiguration);
        if(value instanceof Boolean) {
            return ((Boolean) value);
        } else {
            return Boolean.parseBoolean(getString(sonicConfiguration));
        }
    }

	private Object getConfigValue(SonicConfiguration sonicConfiguration) {
		if (config != null && config.containsKey(sonicConfiguration.name())) {
			return config.get(sonicConfiguration.name());
		}
		return null;
	}

	public synchronized static SonicConfigurationService getInstance() {
		if (sonicConfigurationService == null) {
			sonicConfigurationService = new SonicConfigurationService();
		}
		return sonicConfigurationService;
	}
}
