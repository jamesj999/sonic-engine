package com.openggf.configuration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TestYamlDependencyAvailable {
    @Test
    void yamlMapperParsesNestedDocument() throws Exception {
        ObjectMapper yaml = new YAMLMapper();
        Map<String, Object> parsed = yaml.readValue("audio:\n  enabled: true\n",
                yaml.getTypeFactory().constructMapType(Map.class, String.class, Object.class));
        @SuppressWarnings("unchecked")
        Map<String, Object> audio = (Map<String, Object>) parsed.get("audio");
        assertEquals(Boolean.TRUE, audio.get("enabled"));
    }
}
