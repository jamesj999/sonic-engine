package com.openggf.configuration;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import org.apache.commons.lang3.StringUtils;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.OptionalInt;
import java.util.logging.Level;
import java.util.logging.Logger;

import static org.lwjgl.glfw.GLFW.*;

public class SonicConfigurationService {
	private static final Logger LOGGER = Logger.getLogger(SonicConfigurationService.class.getName());
	private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};
	private static SonicConfigurationService sonicConfigurationService;

	private Map<String, Object> config;
	private Map<String, Object> defaults = new HashMap<>();
	private boolean loadedFromExistingFile;
	private boolean migratedFromLegacyJson;
	private boolean defaultInsertedSinceLastApply;
	/** Whether the loaded file already declared the sparse user-settings format. */
	private boolean sparseFormatDeclared;
	private final Path configDirectoryOverride;
	private final ConfigFileReader yamlReader;
	private final Map<String, Object> sessionOverrides = new HashMap<>();
	// Derived (non-persisted) display values; read before `config`, never saved.
	private final Map<String, Object> transientResolved = new HashMap<>();
	private final Map<SonicConfiguration, Integer> intCache = new EnumMap<>(SonicConfiguration.class);
	private final Map<SonicConfiguration, KeyChord> keyChordCache = new EnumMap<>(SonicConfiguration.class);

	private SonicConfigurationService() {
		this(null, null);
	}

	private SonicConfigurationService(Path configDirectoryOverride, ConfigFileReader yamlReader) {
		this.configDirectoryOverride = configDirectoryOverride == null
				? null
				: configDirectoryOverride.toAbsolutePath();
		this.yamlReader = yamlReader == null ? this::readYamlFlat : yamlReader;
		if (System.getProperty("org.graalvm.nativeimage.imagecode") != null) {
			// Native image: look for config.yaml next to the executable binary
			File execConfig = findConfigNextToExecutable();
			if (execConfig != null && execConfig.exists()) {
				try {
					config = this.yamlReader.read(execConfig);
					loadedFromExistingFile = true;
				} catch (JsonProcessingException e) {
					LOGGER.log(Level.WARNING, "Failed to parse config.yaml from executable directory", e);
					quarantineUnreadableConfig(execConfig);
				} catch (IOException e) {
					LOGGER.log(Level.WARNING,
							"Transient I/O while loading config.yaml from executable directory; leaving it in place",
							e);
				}
			}
		} else {
			// JAR mode: look for config.yaml in the working directory
			File file = resolveRelativeFile("config.yaml");
			if (file.exists()) {
				try {
					config = this.yamlReader.read(file);
					loadedFromExistingFile = true;
				} catch (JsonProcessingException e) {
					LOGGER.log(Level.WARNING, "Failed to parse config.yaml from working directory", e);
					quarantineUnreadableConfig(file);
				} catch (IOException e) {
					LOGGER.log(Level.WARNING,
							"Transient I/O while loading config.yaml from working directory; leaving it in place",
							e);
				}
			}
		}

		// Migrate a legacy flat config.json (keys are enum names) to config.yaml on first run.
		if (config == null) {
			File legacy = resolveRelativeFile("config.json");
			if (legacy.exists()) {
				try {
					ObjectMapper jsonMapper = new ObjectMapper();
					config = jsonMapper.readValue(legacy, MAP_TYPE);
					loadedFromExistingFile = true;
					migratedFromLegacyJson = true;
				} catch (IOException e) {
					LOGGER.log(Level.WARNING, "Failed to read legacy config.json for migration", e);
				}
			}
		}

		boolean legacyMaterialisedFile = loadedFromExistingFile && !sparseFormatDeclared;
		if (config == null) {
			// A fresh install starts with no user settings at all: every read
			// falls through to the registered default, and the bundled
			// template is published beside the file as config.yaml.example
			// for reference rather than copied into it.
			config = new HashMap<>();
		}

		applySessionOutputOverrides();

		publishBundledConfigExample();

		// Migrate deprecated key encodings/defaults before applying defaults.
		ConfigMigrationService migrationService = new ConfigMigrationService();
		boolean configChanged = false;
		if (migrationService.migrateDeprecatedJumpBindings(config)) {
			configChanged = true;
		}
		if (migrationService.detectAwtKeyCodes(config)) {
			migrationService.migrateConfig(config);
			configChanged = true;
		}
		if (migrationService.migrateDeprecatedS1PreviewCoordLogKey(config)) {
			configChanged = true;
		}
		if (migrationService.migrateDeprecatedDisplayColorProfileToggleKey(config)) {
			configChanged = true;
		}
		if (migrationService.migrateDeprecatedCaptureToggleKey(config)) {
			configChanged = true;
		}
		if (normalizeDisplayShaderSelection(config)) {
			configChanged = true;
		}

		boolean renamedKeys = applyDefaults();
		validateEnumeratedValues();
		if (legacyMaterialisedFile
				&& migrationService.convertMaterialisedDefaults(config, defaults)) {
			configChanged = true;
		}

		if (configChanged || renamedKeys || migratedFromLegacyJson || legacyMaterialisedFile) {
			boolean persisted = saveConfigInternal();
			if (migratedFromLegacyJson && persisted) {
				File legacy = resolveRelativeFile("config.json");
				if (legacy.exists()) {
					try {
						Path backup = moveToUniqueSibling(legacy.toPath(), ".bak");
						LOGGER.info("Migrated legacy config.json to config.yaml (backup at "
								+ backup.getFileName() + ")");
					} catch (IOException e) {
						LOGGER.log(Level.WARNING,
								"Saved config.yaml but could not back up the old legacy config.json; leaving it in place", e);
					}
				}
			}
		}
		resolveDisplayAspect();
	}

	public int getInt(SonicConfiguration sonicConfiguration) {
		Integer cached = intCache.get(sonicConfiguration);
		if (cached != null) {
			return cached;
		}
		int resolved = resolveInt(sonicConfiguration);
		intCache.put(sonicConfiguration, resolved);
		return resolved;
	}

	/**
	 * Reads a KEY binding as a chord, so the modifiers a shortcut requires live
	 * in its configured value rather than being hardcoded at its call site.
	 *
	 * <p>Honours everything {@link #getInt} honours: session overrides and the
	 * transient overlay, the DERIVED fallback that sends JUMP to P1_A when unset,
	 * and the fall-back-to-registered-default rule for an unresolvable value.
	 * Returns an unbound chord when the value is explicitly empty — which is how
	 * a shortcut is deliberately unbound — or when the default is itself unbound.
	 */
	public KeyChord getKeyChord(SonicConfiguration sonicConfiguration) {
		KeyChord cached = keyChordCache.get(sonicConfiguration);
		if (cached != null) {
			return cached;
		}
		KeyChord resolved = resolveKeyChord(sonicConfiguration);
		keyChordCache.put(sonicConfiguration, resolved);
		return resolved;
	}

	/**
	 * Drops both resolved-value caches. They must be dropped together, or a
	 * rebind is fresh through one accessor and stale through the other.
	 */
	private void invalidateResolvedCaches() {
		intCache.clear();
		keyChordCache.clear();
	}

	private KeyChord resolveKeyChord(SonicConfiguration sonicConfiguration) {
		if (sonicConfiguration == SonicConfiguration.JUMP && !hasExplicitValue(SonicConfiguration.JUMP)) {
			return getKeyChord(SonicConfiguration.P1_A);
		}
		if (sonicConfiguration == SonicConfiguration.P2_JUMP && !hasExplicitValue(SonicConfiguration.P2_JUMP)) {
			return getKeyChord(SonicConfiguration.P2_A);
		}
		Object value = getConfigValue(sonicConfiguration);
		KeyChord chord = KeyChord.parse(value);
		if (chord.isBound()) {
			return chord;
		}
		// resolveInt falls back to the registered default rather than reporting
		// unbound -- but only for a NON-EMPTY value. An explicitly empty value
		// returns -1 with no default lookup, so the same gate belongs here or a
		// player who writes `toggleKey: ""` to unbind a shortcut gets -1 from
		// getInt and the default chord from here, and the shortcut keeps firing.
		// isEmpty(), NOT trim().isEmpty(): resolveInt's gate is `!str.isEmpty()`
		// on an untrimmed getString(), so a whitespace-only value takes the
		// fall-back-to-default path there and must take it here too.
		if (value == null || value.toString().isEmpty()) {
			return chord;
		}
		// The other spelling of the same intent, and the one the shipped config
		// uses: P1_B, P1_C, P2_B and P2_C default to -1. resolveInt returns it
		// verbatim -- an Integer goes straight through sanitizeIntValue and the
		// string form parses to -1 before the default lookup -- so falling back
		// here would report a binding the player switched off as still bound.
		if (isExplicitlyUnbound(value)) {
			return chord;
		}
		return KeyChord.parse(defaults.get(sonicConfiguration.name()));
	}

	/** True for a value resolveInt reads as the literal key code -1. */
	private static boolean isExplicitlyUnbound(Object value) {
		if (value instanceof Number number) {
			return number.intValue() == KeyChord.NO_KEY;
		}
		try {
			// Untrimmed, exactly as resolveInt parses getString()'s result: a
			// padded " -1 " throws there and falls back to the default, so it
			// must fall back here too.
			return Integer.parseInt(value.toString()) == KeyChord.NO_KEY;
		} catch (NumberFormatException ignored) {
			return false;
		}
	}

	private int resolveInt(SonicConfiguration sonicConfiguration) {
		if (sonicConfiguration == SonicConfiguration.JUMP && !hasExplicitValue(SonicConfiguration.JUMP)) {
			return getInt(SonicConfiguration.P1_A);
		}
		if (sonicConfiguration == SonicConfiguration.P2_JUMP && !hasExplicitValue(SonicConfiguration.P2_JUMP)) {
			return getInt(SonicConfiguration.P2_A);
		}
		Object value = getConfigValue(sonicConfiguration);
		if (value instanceof Integer) {
			return sanitizeIntValue(sonicConfiguration, (Integer) value);
		} else {
			String str = getString(sonicConfiguration);

			// KEY values such as "1" are GLFW key names first, not raw integer
			// codes. Numeric raw codes remain supported when no key name matches.
			// KeyChord applies exactly that order and additionally understands a
			// value carrying modifiers, which is otherwise neither a name nor an
			// integer; getInt returns its bare key so unconverted bindings that
			// read a key code are unaffected by the modifiers.
			if (ConfigCatalog.meta(sonicConfiguration).type() == ConfigType.KEY) {
				KeyChord chord = KeyChord.parse(str);
				if (chord.isBound()) {
					return chord.keyCode();
				}
			}

			// Step 1: try numeric parse
			try {
				return sanitizeIntValue(sonicConfiguration, Integer.parseInt(str));
			} catch (NumberFormatException ignored) {
			}

			// Step 2: try GLFW key name resolution
			OptionalInt resolved = GlfwKeyNameResolver.resolve(str);
			if (resolved.isPresent()) {
				return resolved.getAsInt();
			}

			// Step 3: fall back to default with warning
			if (!str.isEmpty()) {
				int intDefault = resolveKeyCode(defaults.get(sonicConfiguration.name()));
				if (intDefault > 0) {
					LOGGER.warning("'" + str + "' could not be interpreted as a valid input for "
							+ sonicConfiguration.name() + ". Defaulting to '"
							+ GlfwKeyNameResolver.nameOf(intDefault) + "'");
					return intDefault;
				} else {
					LOGGER.warning("'" + str + "' could not be interpreted as a valid input for "
							+ sonicConfiguration.name() + ". Defaulting to unbound");
				}
			}
			return -1;
		}
	}

	private int sanitizeIntValue(SonicConfiguration sonicConfiguration, int value) {
		if (sonicConfiguration == SonicConfiguration.FPS) {
			return Math.max(1, value);
		}
		return value;
	}

	/**
	 * Creates an independent configuration service with the same loading rules as
	 * the process singleton. Intended for standalone tools that are not wired
	 * through {@code EngineContext}.
	 */
	public static SonicConfigurationService createStandalone() {
		return new SonicConfigurationService();
	}

	public static SonicConfigurationService createStandalone(Path configDirectory) {
		return new SonicConfigurationService(configDirectory, null);
	}

	static SonicConfigurationService createStandalone(Path configDirectory, ConfigFileReader yamlReader) {
		return new SonicConfigurationService(configDirectory, yamlReader);
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
		if (sessionOverrides.containsKey(sonicConfiguration.name())) {
			return sessionOverrides.get(sonicConfiguration.name());
		}
		Object overlay = transientResolved.get(sonicConfiguration.name());
		if (overlay != null) {
			return overlay;
		}
		if (config != null && config.containsKey(sonicConfiguration.name())) {
			return config.get(sonicConfiguration.name());
		}
		return defaults.get(sonicConfiguration.name());
	}

	private boolean hasExplicitValue(SonicConfiguration sonicConfiguration) {
		String name = sonicConfiguration.name();
		return sessionOverrides.containsKey(name)
				|| transientResolved.containsKey(name)
				|| (config != null && config.containsKey(name));
	}

	/**
	 * Returns the default value for a configuration key, or {@code null} if
	 * no default is registered.
	 */
	public Object getDefaultValue(SonicConfiguration key) {
		return defaults.get(key.name());
	}

	/**
	 * Resolves DISPLAY_ASPECT into the derived SCREEN_WIDTH_PIXELS (and, when
	 * DISPLAY_WINDOW_AUTOSIZE is true with a widescreen preset,
	 * SCREEN_WIDTH/SCREEN_HEIGHT). Derived values are stored in an in-memory
	 * overlay only and are NEVER written to config.yaml. SCREEN_WIDTH_PIXELS is
	 * therefore a derived value here, not a user setting; a manual
	 * SCREEN_WIDTH_PIXELS in config.yaml is superseded by the preset. Idempotent.
	 * Height pixels stay 224.
	 *
	 * <p>When {@code TEST_MODE_ENABLED} is {@code true} the aspect is always
	 * forced to {@code NATIVE_4_3} regardless of the persisted value.
	 * Trace replay tests and the test-mode trace picker are parity-critical and
	 * only valid at 320×224; a developer's widescreen {@code DISPLAY_ASPECT}
	 * must never leak into those runs.
	 */
	public void resolveDisplayAspect() {
		WidescreenAspect aspect = WidescreenAspect.parse(getString(SonicConfiguration.DISPLAY_ASPECT));
		if (getBoolean(SonicConfiguration.TEST_MODE_ENABLED)) {
			if (aspect != WidescreenAspect.NATIVE_4_3) {
				LOGGER.info("TEST_MODE_ENABLED: forcing DISPLAY_ASPECT to NATIVE_4_3 (320x224) for this run.");
			}
			aspect = WidescreenAspect.NATIVE_4_3;
		}
		boolean autosize = getBoolean(SonicConfiguration.DISPLAY_WINDOW_AUTOSIZE);
		int currentWindowW = persistedInt(SonicConfiguration.SCREEN_WIDTH, 640);
		int currentWindowH = persistedInt(SonicConfiguration.SCREEN_HEIGHT, 448);
		DisplayWindowPolicy.Resolved resolved =
				DisplayWindowPolicy.resolve(aspect, autosize, currentWindowW, currentWindowH);
		// Pixel dimensions are always derived; height pixels are always 224 (the
		// aspect system never changes vertical resolution, so any persisted
		// SCREEN_HEIGHT_PIXELS is intentionally superseded).
		transientResolved.put(SonicConfiguration.SCREEN_WIDTH_PIXELS.name(), resolved.pixelWidth());
		transientResolved.put(SonicConfiguration.SCREEN_HEIGHT_PIXELS.name(), 224);
		if (resolved.windowWidth() != currentWindowW || resolved.windowHeight() != currentWindowH) {
			// Widescreen preset with autosize derived a new window; overlay it.
			transientResolved.put(SonicConfiguration.SCREEN_WIDTH.name(), resolved.windowWidth());
			transientResolved.put(SonicConfiguration.SCREEN_HEIGHT.name(), resolved.windowHeight());
			LOGGER.info("Display aspect " + aspect + " -> " + resolved.pixelWidth() + "x224, window "
					+ resolved.windowWidth() + "x" + resolved.windowHeight()
					+ " (in-memory only); set DISPLAY_WINDOW_AUTOSIZE=false to keep a custom window.");
		} else {
			// Window unchanged (NATIVE, autosize off, or already matching): clear any
			// stale derived window so reads fall through to the persisted config.
			transientResolved.remove(SonicConfiguration.SCREEN_WIDTH.name());
			transientResolved.remove(SonicConfiguration.SCREEN_HEIGHT.name());
			if (aspect != WidescreenAspect.NATIVE_4_3) {
				LOGGER.info("Display aspect " + aspect + " -> " + resolved.pixelWidth()
						+ "x224 (window preserved).");
			}
		}
		invalidateResolvedCaches();
	}

	/** Reads an int from the persisted {@code config} map only, bypassing the transient overlay. */
	private int persistedInt(SonicConfiguration key, int fallback) {
		Object v = (config != null) ? config.get(key.name()) : null;
		if (v == null) {
			v = defaults.get(key.name());
		}
		if (v instanceof Number n) {
			return n.intValue();
		}
		if (v != null) {
			try {
				return Integer.parseInt(v.toString());
			} catch (NumberFormatException ignored) {
				// fall through
			}
		}
		return fallback;
	}

	public void setConfigValue(SonicConfiguration key, Object value) {
		if (config == null) {
			config = new HashMap<>();
		}
		config.put(key.name(), value);
		invalidateResolvedCaches();
	}

	public void setSessionOverride(SonicConfiguration key, Object value) {
		sessionOverrides.put(key.name(), value);
		invalidateResolvedCaches();
	}

	public void clearSessionOverrides() {
		sessionOverrides.clear();
		invalidateResolvedCaches();
	}

	public boolean hasSessionOverride(SonicConfiguration key) {
		return sessionOverrides.containsKey(key.name());
	}

	public void saveConfig() {
		saveConfigInternal();
	}

	/**
	 * Persists the current user map and reports whether the replacement was
	 * published. Migration callers must not retire their legacy source until
	 * this boundary succeeds.
	 */
	private boolean saveConfigInternal() {
		File target = resolveConfigFile();
		try {
			String yaml = new ConfigYamlWriter().write(config);
			writeStringAtomically(target.toPath(), yaml);
			return true;
		} catch (IOException e) {
			LOGGER.log(Level.WARNING, "Failed to save config.yaml", e);
			return false;
		}
	}

	private static void writeStringAtomically(Path target, String content) throws IOException {
		Path absoluteTarget = target.toAbsolutePath();
		Path parent = absoluteTarget.getParent();
		if (parent != null) {
			Files.createDirectories(parent);
		}
		Path temp = Files.createTempFile(parent, absoluteTarget.getFileName() + ".", ".tmp");
		try {
			Files.writeString(temp, content, StandardCharsets.UTF_8);
			try {
				Files.move(temp, absoluteTarget,
						StandardCopyOption.ATOMIC_MOVE,
						StandardCopyOption.REPLACE_EXISTING);
			} catch (AtomicMoveNotSupportedException e) {
				Files.move(temp, absoluteTarget, StandardCopyOption.REPLACE_EXISTING);
			}
		} finally {
			Files.deleteIfExists(temp);
		}
	}

	private static void quarantineUnreadableConfig(File configFile) {
		if (configFile == null || !configFile.exists()) {
			return;
		}
		try {
			Path quarantined = moveToUniqueSibling(configFile.toPath(), ".corrupt");
			LOGGER.warning("Unreadable config.yaml moved to " + quarantined.getFileName());
		} catch (IOException moveFailure) {
			LOGGER.log(Level.WARNING,
					"Failed to quarantine unreadable config.yaml; leaving it in place", moveFailure);
		}
	}

	private static Path moveToUniqueSibling(Path source, String suffix) throws IOException {
		Path absoluteSource = source.toAbsolutePath();
		Path target = uniqueSibling(absoluteSource.resolveSibling(
				absoluteSource.getFileName().toString() + suffix));
		try {
			Files.move(absoluteSource, target, StandardCopyOption.ATOMIC_MOVE);
		} catch (AtomicMoveNotSupportedException e) {
			Files.move(absoluteSource, target);
		}
		return target;
	}

	private static Path uniqueSibling(Path preferred) {
		if (!Files.exists(preferred)) {
			return preferred;
		}
		for (int i = 1; i < 1000; i++) {
			Path candidate = preferred.resolveSibling(preferred.getFileName().toString() + "." + i);
			if (!Files.exists(candidate)) {
				return candidate;
			}
		}
		throw new IllegalStateException("Could not find unique backup name for " + preferred);
	}

	public void ensureConfigFileExists() {
		File target = resolveConfigFile();
		if (!target.exists()) {
			saveConfig();
		}
	}

	public synchronized static SonicConfigurationService getInstance() {
		if (sonicConfigurationService == null) {
			sonicConfigurationService = new SonicConfigurationService();
		}
		return sonicConfigurationService;
	}

	/**
	 * Resets the singleton instance. Used by tests that need a fresh
	 * configuration with defaults re-applied.
	 */
	static void resetStaticInstance() {
		sonicConfigurationService = null;
	}

	public void resetToDefaults() {
		config = new HashMap<>();
		defaults = new HashMap<>();
		sessionOverrides.clear();
		applySessionOutputOverrides();
		invalidateResolvedCaches();
		applyDefaults();
		// Re-derive SCREEN_WIDTH_PIXELS (and related) from the freshly-set
		// DISPLAY_ASPECT=NATIVE_4_3 default so any widescreen value left in
		// transientResolved from the singleton constructor is discarded.
		// Without this call a developer's ULTRA_21_9 config.yaml would leave
		// SCREEN_WIDTH_PIXELS=528 in the overlay even after the test harness
		// calls resetToDefaults(), silently widening trace and headless test runs.
		resolveDisplayAspect();
	}

	/** Warn on ENUM-typed values outside their allowed set and reset them to the registered default. */
	private void validateEnumeratedValues() {
		for (SonicConfiguration key : ConfigCatalog.emitOrder()) {
			ConfigKeyMeta meta = ConfigCatalog.meta(key);
			if (meta.type() != ConfigType.ENUM) {
				continue;
			}
			Object value = config.get(key.name());
			if (value == null) {
				continue;
			}
			if (!meta.allowedValues().contains(value.toString())) {
				Object fallback = defaults.get(key.name());
				LOGGER.warning("Invalid value '" + value + "' for " + meta.path()
						+ "; allowed " + meta.allowedValues() + ". Defaulting to '" + fallback + "'.");
				config.remove(key.name());
				invalidateResolvedCaches();
			}
		}
	}

	private boolean normalizeDisplayShaderSelection(Map<String, Object> config) {
		if (config == null) {
			return false;
		}
		String key = SonicConfiguration.DISPLAY_SHADER_SELECTION.name();
		Object value = config.get(key);
		boolean parsedFromUnquotedOff = Boolean.FALSE.equals(value)
				|| (value instanceof String str && "false".equalsIgnoreCase(str.trim()));
		if (!parsedFromUnquotedOff) {
			return false;
		}
		config.put(key, "OFF");
		invalidateResolvedCaches();
		return true;
	}

	private boolean applyDefaults() {
		if (config == null) {
			config = new HashMap<>();
		}
		defaultInsertedSinceLastApply = false;
		// Fill in core defaults if missing to keep tests and headless runs stable.
		putDefault(SonicConfiguration.SCREEN_WIDTH, 640);
		putDefault(SonicConfiguration.SCREEN_WIDTH_PIXELS, 320);
		putDefault(SonicConfiguration.SCREEN_HEIGHT, 448);
		putDefault(SonicConfiguration.SCREEN_HEIGHT_PIXELS, 224);
		putDefault(SonicConfiguration.SCALE, 1.0);
		// Keep the release default off; developers can enable this for debug keys.
		putDefault(SonicConfiguration.DEBUG_VIEW_ENABLED, false);
		putDefault(SonicConfiguration.EDITOR_ENABLED, false);
		putDefault(SonicConfiguration.DISPLAY_COLOR_PROFILE, "RAW_RGB");
		putDefaultKey(SonicConfiguration.DISPLAY_COLOR_PROFILE_TOGGLE_KEY, GLFW_KEY_V);
		putDefault(SonicConfiguration.DISPLAY_ASPECT, "NATIVE_4_3");
		putDefault(SonicConfiguration.WIDESCREEN_DEADZONE_MODE, "PROPORTIONAL");
		putDefault(SonicConfiguration.DISPLAY_WINDOW_AUTOSIZE, true);
		putDefault(SonicConfiguration.DISPLAY_SHADER_LIBRARY_ROOT, "shaders");
		putDefault(SonicConfiguration.DISPLAY_SHADER_SELECTION, "OFF");
		putDefaultKey(SonicConfiguration.DISPLAY_SHADER_NEXT_KEY, GLFW_KEY_RIGHT_BRACKET);
		putDefaultKey(SonicConfiguration.DISPLAY_SHADER_PREVIOUS_KEY, GLFW_KEY_LEFT_BRACKET);
		putDefaultKey(SonicConfiguration.DISPLAY_SHADER_PICKER_KEY, GLFW_KEY_BACKSLASH);
		putDefault(SonicConfiguration.DISPLAY_SHADER_DEFAULT_PHASE, "PRESENTATION");
		putDefault(SonicConfiguration.DAC_INTERPOLATE, false);
		putDefault(SonicConfiguration.FM6_DAC_OFF, true); // Default true for Sonic 2 parity
		putDefault(SonicConfiguration.AUDIO_ENABLED, true);
		putDefault(SonicConfiguration.AUDIO_INTERNAL_RATE_OUTPUT, false);
		putDefault(SonicConfiguration.AUDIO_FM_CORE, "fast");
		putDefault(SonicConfiguration.REGION, "NTSC");
		putDefaultKey(SonicConfiguration.UP, GLFW_KEY_UP);
		putDefaultKey(SonicConfiguration.DOWN, GLFW_KEY_DOWN);
		putDefaultKey(SonicConfiguration.LEFT, GLFW_KEY_LEFT);
		putDefaultKey(SonicConfiguration.RIGHT, GLFW_KEY_RIGHT);
		putDefaultKey(SonicConfiguration.P1_A, GLFW_KEY_SPACE);
		putDefault(SonicConfiguration.P1_B, "");
		putDefault(SonicConfiguration.P1_C, "");
		putDefaultKey(SonicConfiguration.START, GLFW_KEY_BACKSPACE);
		putDefaultKey(SonicConfiguration.P2_UP, GLFW_KEY_I);
		putDefaultKey(SonicConfiguration.P2_DOWN, GLFW_KEY_K);
		putDefaultKey(SonicConfiguration.P2_LEFT, GLFW_KEY_J);
		putDefaultKey(SonicConfiguration.P2_RIGHT, GLFW_KEY_L);
		putDefaultKey(SonicConfiguration.P2_A, GLFW_KEY_RIGHT_SHIFT);
		putDefault(SonicConfiguration.P2_B, "");
		putDefault(SonicConfiguration.P2_C, "");
		putDefaultKey(SonicConfiguration.P2_START, GLFW_KEY_RIGHT_CONTROL);
		putDefault(SonicConfiguration.CONTROLLER_ENABLED, true);
		putDefault(SonicConfiguration.CONTROLLER_DEADZONE, 0.35);
		putDefault(SonicConfiguration.CONTROLLER_PLAYER1, "auto");
		putDefault(SonicConfiguration.CONTROLLER_PLAYER2, "auto");
		putDefaultKey(SonicConfiguration.TEST, GLFW_KEY_T);
		putDefaultKey(SonicConfiguration.NEXT_ACT, GLFW_KEY_PAGE_UP);
		putDefaultKey(SonicConfiguration.NEXT_ZONE, GLFW_KEY_PAGE_DOWN);
		putDefaultKey(SonicConfiguration.DEBUG_MODE_KEY, GLFW_KEY_D);
		putDefault(SonicConfiguration.FPS, 60);
		putDefault(SonicConfiguration.LOAD_TIME_SIMULATION, "FAST");
		putDefaultKey(SonicConfiguration.SPECIAL_STAGE_KEY, GLFW_KEY_TAB);
		putDefaultKey(SonicConfiguration.SPECIAL_STAGE_COMPLETE_KEY, GLFW_KEY_END);
		putDefaultKey(SonicConfiguration.SPECIAL_STAGE_FAIL_KEY, GLFW_KEY_DELETE);
		putDefaultKey(SonicConfiguration.SPECIAL_STAGE_SPRITE_DEBUG_KEY, GLFW_KEY_F12);
		putDefaultKey(SonicConfiguration.SPECIAL_STAGE_PLANE_DEBUG_KEY, GLFW_KEY_F3);
		putDefaultKey(SonicConfiguration.PAUSE_KEY, GLFW_KEY_ENTER);
		putDefaultKey(SonicConfiguration.FRAME_STEP_KEY, GLFW_KEY_Q);
		putDefault(SonicConfiguration.PLAYBACK_MOVIE_PATH, "");
		putDefault(SonicConfiguration.PLAYBACK_TOGGLE_KEY, "");
		putDefault(SonicConfiguration.PLAYBACK_LOAD_KEY, "");
		putDefault(SonicConfiguration.PLAYBACK_PLAY_PAUSE_KEY, "");
		putDefault(SonicConfiguration.PLAYBACK_STEP_BACK_KEY, "");
		putDefault(SonicConfiguration.PLAYBACK_STEP_FORWARD_KEY, "");
		putDefault(SonicConfiguration.PLAYBACK_JUMP_BACK_KEY, "");
		putDefault(SonicConfiguration.PLAYBACK_JUMP_FORWARD_KEY, "");
		putDefault(SonicConfiguration.PLAYBACK_FAST_RATE_KEY, "");
		putDefault(SonicConfiguration.PLAYBACK_RESET_TO_START_KEY, "");
		putDefault(SonicConfiguration.PLAYBACK_START_OFFSET_FRAME, 0);
		putDefaultKey(SonicConfiguration.RECORDING_RECORD_KEY, GLFW_KEY_F9);
		putDefaultKey(SonicConfiguration.TRACE_REWIND_KEY, GLFW_KEY_R);
		putDefault(SonicConfiguration.TRACE_SHOW_DESYNC_GHOSTS, true);
		putDefault(SonicConfiguration.TRACE_SHOW_GAME_HUD, true);
		putDefault(SonicConfiguration.TRACE_SHOW_DEBUG_HUD, false);
		putDefault(SonicConfiguration.CAPTURE_OUTPUT_DIR, "target/trace-videos");
		putDefault(SonicConfiguration.CAPTURE_SCALE, 4);
		putDefault(SonicConfiguration.CAPTURE_FPS, 60);
		putDefault(SonicConfiguration.CAPTURE_CODEC, "ffv1");
		putDefault(SonicConfiguration.CAPTURE_AUDIO_CODEC, "flac");
		putDefault(SonicConfiguration.CAPTURE_CONTAINER, "mkv");
		putDefault(SonicConfiguration.CAPTURE_QUEUE_BUDGET_MB, 192);
		putDefault(SonicConfiguration.CAPTURE_ENCODER_THREADS, 0);
		putDefault(SonicConfiguration.CAPTURE_ENCODER_PRESET, "fast");
		putDefault(SonicConfiguration.CAPTURE_FFMPEG_PASS1_ARGS, "default");
		putDefault(SonicConfiguration.CAPTURE_FFMPEG_PASS2_ARGS, "default");
		// putDefault, not putDefaultKey: the default carries its own modifier.
		putDefault(SonicConfiguration.CAPTURE_TOGGLE_KEY, "SHIFT+O");
		putDefault(SonicConfiguration.LIVE_REWIND_ENABLED, false);
		putDefault(SonicConfiguration.LIVE_REWIND_DETERMINISM_AUDIT, false);
		putDefaultKey(SonicConfiguration.LIVE_REWIND_KEY, GLFW_KEY_R);
		putDefaultKey(SonicConfiguration.LIVE_REWIND_HALF_SPEED_KEY, GLFW_KEY_LEFT_CONTROL);
		putDefaultKey(SonicConfiguration.LIVE_REWIND_DOUBLE_SPEED_KEY, GLFW_KEY_LEFT_SHIFT);
		putDefault(SonicConfiguration.LIVE_REWIND_TAPE_COAST_ENABLED, false);
		putDefault(SonicConfiguration.LIVE_REWIND_TAPE_COAST_ACCELERATION, 0.25);
		putDefault(SonicConfiguration.LIVE_REWIND_TAPE_COAST_DECELERATION, 0.5);
		putDefault(SonicConfiguration.LIVE_REWIND_TAPE_COAST_MAX_STEPS, 4.0);
		putDefault(SonicConfiguration.LIVE_REWIND_TAPE_COAST_MIN_STEPS, 0.25);
		putDefault(SonicConfiguration.LIVE_REWIND_VHS_EFFECT, true);
		putDefault(SonicConfiguration.LIVE_REWIND_VHS_TEAR_BANDS, true);
		putDefault(SonicConfiguration.REWIND_HISTORY_SECONDS, 60);
		putDefault(SonicConfiguration.REWIND_AUDIO_HISTORY_LIMIT_TYPE, "time");
		putDefault(SonicConfiguration.REWIND_AUDIO_HISTORY_SECONDS, 60);
		putDefault(SonicConfiguration.REWIND_AUDIO_HISTORY_SIZE_MB, 10);
		putDefaultKey(SonicConfiguration.DEBUG_LAST_CHECKPOINT_KEY, GLFW_KEY_C);
		putDefaultKey(SonicConfiguration.LEVEL_SELECT_KEY, GLFW_KEY_F9);
		putDefault(SonicConfiguration.TITLE_SCREEN_ON_STARTUP, true);
		putDefault(SonicConfiguration.LEVEL_SELECT_ON_STARTUP, false);
		putDefault(SonicConfiguration.MAIN_CHARACTER_CODE, "sonic");
		putDefault(SonicConfiguration.SIDEKICK_CHARACTER_CODE, "tails");
		putDefault(SonicConfiguration.DATA_SELECT_EXTRA_PLAYER_COMBOS, "");
		putDefault(SonicConfiguration.SONIC_1_ROM, "s1.gen");
		putDefault(SonicConfiguration.SONIC_2_ROM, "s2.gen");
		putDefault(SonicConfiguration.SONIC_3K_ROM, "s3k.gen");
		// Migrate renamed config key: S3K_SKIP_AIZ1_INTRO → S3K_SKIP_INTROS
		if (config.containsKey("S3K_SKIP_AIZ1_INTRO")) {
			if (!config.containsKey(SonicConfiguration.S3K_SKIP_INTROS.name())) {
				config.put(SonicConfiguration.S3K_SKIP_INTROS.name(), config.get("S3K_SKIP_AIZ1_INTRO"));
			}
			config.remove("S3K_SKIP_AIZ1_INTRO");
			defaultInsertedSinceLastApply = true;
		}
		putDefault(SonicConfiguration.S3K_SKIP_INTROS, false);
		putDefault(SonicConfiguration.MAIN_CHARACTER_CODE, "sonic");
		putDefault(SonicConfiguration.DEFAULT_ROM, "s2");
		putDefaultKey(SonicConfiguration.SUPER_SONIC_DEBUG_KEY, GLFW_KEY_U);
		putDefaultKey(SonicConfiguration.GIVE_EMERALDS_KEY, GLFW_KEY_E);
		putDefault(SonicConfiguration.MASTER_TITLE_SCREEN_ON_STARTUP, true);
		putDefault(SonicConfiguration.SHOW_LEGAL_DISCLAIMER_ON_STARTUP, true);
		putDefault(SonicConfiguration.CROSS_GAME_FEATURES_ENABLED, false);
		putDefault(SonicConfiguration.CROSS_GAME_S1_DATA_SELECT_IMAGE_GEN_OVERRIDE, false);
		putDefault(SonicConfiguration.CROSS_GAME_S2_DATA_SELECT_IMAGE_GEN_OVERRIDE, false);
		putDefaultKey(SonicConfiguration.CROSS_GAME_S1_DATA_SELECT_IMAGE_COORD_LOG_KEY, GLFW_KEY_APOSTROPHE);
		putDefault(SonicConfiguration.CROSS_GAME_SOURCE, "s2");
		putDefault(SonicConfiguration.LAUNCH_S1_REWIND, false);
		putDefault(SonicConfiguration.LAUNCH_S1_CROSS_GAME_SOURCE, "off");
		putDefault(SonicConfiguration.LAUNCH_S1_DEBUG_TOOLS, false);
		putDefault(SonicConfiguration.LAUNCH_S1_ASPECT, "global");
		putDefault(SonicConfiguration.LAUNCH_S1_MAIN_CHARACTER, "sonic");
		putDefault(SonicConfiguration.LAUNCH_S1_SIDEKICK, "none");
		putDefault(SonicConfiguration.LAUNCH_S2_REWIND, false);
		putDefault(SonicConfiguration.LAUNCH_S2_CROSS_GAME_SOURCE, "off");
		putDefault(SonicConfiguration.LAUNCH_S2_DEBUG_TOOLS, false);
		putDefault(SonicConfiguration.LAUNCH_S2_ASPECT, "global");
		putDefault(SonicConfiguration.LAUNCH_S2_MAIN_CHARACTER, "sonic");
		putDefault(SonicConfiguration.LAUNCH_S2_SIDEKICK, "tails");
		putDefault(SonicConfiguration.LAUNCH_S3K_REWIND, false);
		putDefault(SonicConfiguration.LAUNCH_S3K_CROSS_GAME_SOURCE, "off");
		putDefault(SonicConfiguration.LAUNCH_S3K_DEBUG_TOOLS, false);
		putDefault(SonicConfiguration.LAUNCH_S3K_ASPECT, "global");
		putDefault(SonicConfiguration.LAUNCH_S3K_MAIN_CHARACTER, "sonic");
		putDefault(SonicConfiguration.LAUNCH_S3K_SIDEKICK, "tails");
		putDefault(SonicConfiguration.TEST_MODE_ENABLED, false);
		putDefault(SonicConfiguration.TRACE_CATALOG_DIR, "src/test/resources/traces");
		putDefault(SonicConfiguration.DISCORD_RICH_PRESENCE_ENABLED, false);
		putDefault(SonicConfiguration.DISCORD_RICH_PRESENCE_SHOW_TIMER, true);
		putDefault(SonicConfiguration.DISCORD_RICH_PRESENCE_SHOW_ZONE, true);
		return defaultInsertedSinceLastApply;
	}

	/**
	 * A coordinator-launched run owns its capture directory. Keep the bundled
	 * config and no-session default unchanged, but redirect the bundled default
	 * into the session diagnostic namespace when the coordinator supplies one.
	 */
	private void applySessionOutputOverrides() {
		String diagnostics = System.getProperty("openggf.test.diagnostics");
		if (diagnostics == null || diagnostics.isBlank()) {
			return;
		}
		Object configured = config == null
				? null
				: config.get(SonicConfiguration.CAPTURE_OUTPUT_DIR.name());
		if (configured == null || "target/trace-videos".equals(configured.toString())) {
			sessionOverrides.put(SonicConfiguration.CAPTURE_OUTPUT_DIR.name(),
					Path.of(diagnostics, "trace-videos").toString());
		}
	}

	/**
	 * Registers a default. Defaults are never copied into the user map: the
	 * file only ever holds settings the player set, so a default that changes
	 * in a later build applies to every install that never touched the key.
	 */
	private void putDefault(SonicConfiguration key, Object value) {
		defaults.put(key.name(), value);
	}

	private void putDefaultKey(SonicConfiguration key, int glfwKeyCode) {
		putDefault(key, GlfwKeyNameResolver.nameOf(glfwKeyCode));
	}

	private Map<String, Object> readYamlFlat(File file) throws IOException {
		Map<String, Object> nested = new YAMLMapper().readValue(file, MAP_TYPE);
		Object format = nested.remove(ConfigYamlWriter.FORMAT_KEY);
		sparseFormatDeclared = format != null
				&& String.valueOf(format).trim().equals(String.valueOf(ConfigYamlWriter.SPARSE_FORMAT));
		return flattenAndWarn(nested);
	}

	private Map<String, Object> flattenAndWarn(Map<String, Object> nested) {
		ConfigFlattener.Result result = ConfigFlattener.flatten(nested);
		for (String unknown : result.unknownKeys()) {
			LOGGER.warning("Unknown config key ignored: " + unknown);
		}
		return result.flat();
	}

	/**
	 * Finds config.yaml next to the native image executable binary.
	 * Uses ProcessHandle to determine the executable path.
	 */
	private static File findConfigNextToExecutable() {
		try {
			String cmd = ProcessHandle.current().info().command().orElse("");
			if (!cmd.isEmpty()) {
				return resolveNativeConfigForExecutable(new File(cmd));
			}
		} catch (Exception ignored) {
		}
		return null;
	}

	static File resolveNativeConfigForExecutable(File executable) {
		if (executable == null) {
			return null;
		}
		File execDir = executable.isDirectory() ? executable : executable.getParentFile();
		if (execDir == null) {
			return null;
		}
		File bundleSiblingConfig = macosAppBundleSiblingConfig(execDir);
		if (bundleSiblingConfig != null) {
			return bundleSiblingConfig;
		}
		return new File(execDir, "config.yaml");
	}

	private static File macosAppBundleSiblingConfig(File execDir) {
		File contentsDir = execDir.getParentFile();
		File appBundle = contentsDir == null ? null : contentsDir.getParentFile();
		if (!"MacOS".equals(execDir.getName())
				|| contentsDir == null
				|| !"Contents".equals(contentsDir.getName())
				|| appBundle == null
				|| !appBundle.getName().endsWith(".app")) {
			return null;
		}
		File appParent = appBundle.getParentFile();
		return appParent == null ? null : new File(appParent, "config.yaml");
	}

	/**
	 * Resolves a relative filename against user.dir. In GraalVM native images
	 * launched from macOS Finder, getcwd() is broken so File("relative") may
	 * resolve against the wrong directory. This ensures consistent behavior.
	 */
	/**
	 * Writes the bundled {@code config.yaml} to {@code config.yaml.example}
	 * beside wherever the player's own {@code config.yaml} is looked for,
	 * overwriting it on every run.
	 *
	 * <p>A player's {@code config.yaml} is the values they have changed, and
	 * once written it never regains the comments, new keys, or worked examples
	 * that the bundled template gains later. Refreshing a sibling example file
	 * means the current documented template is always readable next to their
	 * own, to consult or to copy over.
	 *
	 * <p>Deliberately never touches {@code config.yaml} itself: overwriting a
	 * player's settings to give them comments would be a poor trade. Failure is
	 * logged and ignored — a read-only install directory must not stop the game
	 * starting.
	 */
	private void publishBundledConfigExample() {
		File target = new File(resolveConfigFile().getAbsoluteFile().getParent(),
				"config.yaml.example");
		try (InputStream is = getClass().getResourceAsStream("/config.yaml")) {
			if (is == null) {
				return;
			}
			java.nio.file.Files.copy(is, target.toPath(),
					java.nio.file.StandardCopyOption.REPLACE_EXISTING);
		} catch (IOException | RuntimeException e) {
			LOGGER.log(Level.FINE, "Could not refresh config.yaml.example", e);
		}
	}

	private File resolveRelativeFile(String name) {
		File f = new File(name);
		if (!f.isAbsolute()) {
			if (configDirectoryOverride != null) {
				return configDirectoryOverride.resolve(name).toFile();
			}
			String userDir = System.getProperty("user.dir");
			if (userDir != null) {
				return new File(userDir, name);
			}
		}
		return f;
	}

	private File resolveConfigFile() {
		if (System.getProperty("org.graalvm.nativeimage.imagecode") != null) {
			File execConfig = findConfigNextToExecutable();
			return (execConfig != null) ? execConfig : resolveRelativeFile("config.yaml");
		}
		return resolveRelativeFile("config.yaml");
	}

	@FunctionalInterface
	interface ConfigFileReader {
		Map<String, Object> read(File file) throws IOException;
	}

	/**
	 * Bare key code of a registered default, which may itself carry modifiers
	 * (e.g. {@code "SHIFT+O"}). KeyChord handles the Number, name and raw-code
	 * forms, so a chorded default resolves to its key rather than to unbound.
	 */
	private static int resolveKeyCode(Object value) {
		return KeyChord.parse(value).keyCode();
	}
}
