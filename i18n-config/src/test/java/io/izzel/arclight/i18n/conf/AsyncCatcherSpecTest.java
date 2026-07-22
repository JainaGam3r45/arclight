package io.izzel.arclight.i18n.conf;

import com.google.common.reflect.TypeToken;
import ninja.leaping.configurate.ConfigurationNode;
import ninja.leaping.configurate.objectmapping.ObjectMappingException;
import ninja.leaping.configurate.yaml.YAMLConfigurationLoader;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.DumperOptions;

import java.io.BufferedReader;
import java.io.StringReader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AsyncCatcherSpecTest {

    @Test
    void bareOverridesDeserializesToEmptyMap() throws Exception {
        AsyncCatcherSpec spec = load("""
            dump: true
            warn: true
            defaultOperation: block
            overrides:
            log-overrides:
              chunk entity get: DEBUG
            """);

        assertNotNull(spec.getOverrides());
        assertTrue(spec.getOverrides().isEmpty());
        assertEquals(AsyncCatcherSpec.Operation.BLOCK,
            spec.getOverrides().getOrDefault("entity add", AsyncCatcherSpec.Operation.BLOCK));
        assertEquals(AsyncCatcherSpec.LogLevel.DEBUG, spec.getLogOverrides().get("chunk entity get"));
    }

    @Test
    void emptyBracesOverridesDeserializesToEmptyMap() throws Exception {
        AsyncCatcherSpec spec = load("""
            dump: true
            warn: true
            defaultOperation: block
            overrides: {}
            log-overrides: {}
            """);

        assertTrue(spec.getOverrides().isEmpty());
        assertTrue(spec.getLogOverrides().isEmpty());
    }

    @Test
    void populatedOverridesLoadAndApply() throws Exception {
        AsyncCatcherSpec spec = load("""
            dump: true
            warn: true
            defaultOperation: block
            overrides:
              entity add: NONE
              chunk entity get: DISPATCH
              entity teleport: EXCEPTION
            log-overrides:
              chunk entity get: DEBUG
            """);

        assertEquals(3, spec.getOverrides().size());
        assertEquals(AsyncCatcherSpec.Operation.NONE, spec.getOverrides().get("entity add"));
        assertEquals(AsyncCatcherSpec.Operation.DISPATCH, spec.getOverrides().get("chunk entity get"));
        assertEquals(AsyncCatcherSpec.Operation.EXCEPTION, spec.getOverrides().get("entity teleport"));
        assertEquals(AsyncCatcherSpec.Operation.BLOCK,
            spec.getOverrides().getOrDefault("missing", AsyncCatcherSpec.Operation.BLOCK));
        assertEquals(AsyncCatcherSpec.LogLevel.DEBUG, spec.getLogOverrides().get("chunk entity get"));
    }

    private static AsyncCatcherSpec load(String yaml) throws Exception {
        ConfigurationNode node = YAMLConfigurationLoader.builder()
            .setSource(() -> new BufferedReader(new StringReader(yaml)))
            .setFlowStyle(DumperOptions.FlowStyle.BLOCK)
            .build()
            .load();
        AsyncCatcherSpec spec = node.getValue(TypeToken.of(AsyncCatcherSpec.class));
        if (spec == null) {
            throw new ObjectMappingException("Failed to map AsyncCatcherSpec");
        }
        return spec;
    }
}
