package uk.co.jamesj999.sonic.configuration;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.lang3.StringUtils;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

public class SonicConfigurationService {
	private static SonicConfigurationService sonicConfigurationService;
	public static String ENGINE_VERSION = "0.1.20260110";

	private Map<String, Object> config;

	private SonicConfigurationService() {
		ObjectMapper mapper = new ObjectMapper();
		TypeReference<Map<String, Object>> type = new TypeReference<>(){};

		File file = new File("config.json");
		if (file.exists()) {
			try {
				config = mapper.readValue(file, type);
			} catch (IOException e) {
				System.err.println("Failed to load config.json from working directory: " + e.getMessage());
			}
		}

		if (config == null) {
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
		applyDefaults();
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
		} else if (value instanceof Number) {
			return ((Number) value).intValue() != 0;
		} else {
			return Boolean.parseBoolean(getString(sonicConfiguration));
		}
	}

	public Object getConfigValue(SonicConfiguration sonicConfiguration) {
		if (config != null && config.containsKey(sonicConfiguration.name())) {
			return config.get(sonicConfiguration.name());
		}
		return null;
	}

	public void setConfigValue(SonicConfiguration key, Object value) {
		if (config == null) {
			config = new HashMap<>();
		}
		config.put(key.name(), value);
	}

	public void saveConfig() {
		ObjectMapper mapper = new ObjectMapper();
		try {
			mapper.writerWithDefaultPrettyPrinter().writeValue(new File("config.json"), config);
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	public synchronized static SonicConfigurationService getInstance() {
		if (sonicConfigurationService == null) {
			sonicConfigurationService = new SonicConfigurationService();
		}
		return sonicConfigurationService;
	}

	private void applyDefaults() {
		if (config == null) {
			config = new HashMap<>();
		}
		// Fill in core defaults if missing to keep tests and headless runs stable.
		putDefault(SonicConfiguration.SCREEN_WIDTH, 640);
		putDefault(SonicConfiguration.SCREEN_WIDTH_PIXELS, 320);
		putDefault(SonicConfiguration.SCREEN_HEIGHT, 480);
		putDefault(SonicConfiguration.SCREEN_HEIGHT_PIXELS, 240);
		putDefault(SonicConfiguration.SCALE, 1.0);
		putDefault(SonicConfiguration.ROM_FILENAME, "Sonic The Hedgehog 2 (W) (REV01) [!].gen");
		// Force debug view enabled for tests/headless use unless explicitly overridden
		config.put(SonicConfiguration.DEBUG_VIEW_ENABLED.name(), true);
		putDefault(SonicConfiguration.DEBUG_COLLISION_VIEW_ENABLED, false);
		putDefault(SonicConfiguration.DAC_INTERPOLATE, true);
		putDefault(SonicConfiguration.FM6_DAC_OFF, true); // Default true for Sonic 2 parity
		putDefault(SonicConfiguration.AUDIO_ENABLED, true);
		putDefault(SonicConfiguration.REGION, "NTSC");
		putDefault(SonicConfiguration.SPECIAL_STAGE_KEY, java.awt.event.KeyEvent.VK_HOME);
		putDefault(SonicConfiguration.SPECIAL_STAGE_COMPLETE_KEY, java.awt.event.KeyEvent.VK_END);
		putDefault(SonicConfiguration.SPECIAL_STAGE_FAIL_KEY, java.awt.event.KeyEvent.VK_DELETE);
		putDefault(SonicConfiguration.SPECIAL_STAGE_SPRITE_DEBUG_KEY, java.awt.event.KeyEvent.VK_F12);
	}

	private void putDefault(SonicConfiguration key, Object value) {
		if (config == null) {
			config = new HashMap<>();
		}
		config.putIfAbsent(key.name(), value);
	}
}
