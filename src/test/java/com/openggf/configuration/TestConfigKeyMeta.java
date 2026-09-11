package com.openggf.configuration;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class TestConfigKeyMeta {
    @Test
    void persistedFactoryPopulatesFields() {
        ConfigKeyMeta m = ConfigKeyMeta.of("audio", "enabled", ConfigType.BOOL, "Music + SFX");
        assertEquals("audio", m.section());
        assertEquals("enabled", m.leaf());
        assertEquals(ConfigType.BOOL, m.type());
        assertEquals("Music + SFX", m.description());
        assertTrue(m.persisted());
        assertTrue(m.allowedValues().isEmpty());
    }

    @Test
    void enumFactoryCarriesAllowedValues() {
        ConfigKeyMeta m = ConfigKeyMeta.ofEnum("audio", "region", "NTSC/PAL timing", Set.of("NTSC", "PAL"));
        assertEquals(ConfigType.ENUM, m.type());
        assertEquals(Set.of("NTSC", "PAL"), m.allowedValues());
    }

    @Test
    void derivedKeyIsNotPersistedAndHasNoSection() {
        ConfigKeyMeta m = ConfigKeyMeta.derived(ConfigType.INT, "Derived screen pixel width");
        assertFalse(m.persisted());
        assertNull(m.section());
        assertNull(m.leaf());
    }
}
