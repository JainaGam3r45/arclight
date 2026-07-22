package io.izzel.arclight.i18n;

import ninja.leaping.configurate.ConfigurationNode;
import ninja.leaping.configurate.yaml.YAMLConfigurationLoader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.yaml.snakeyaml.DumperOptions;

import java.io.BufferedReader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ArclightConfigWriteEntryTest {

    @TempDir
    Path tempDir;

    @Test
    void emptyMapNodeWritesBraces() throws Exception {
        ConfigurationNode root = load("""
            overrides: {}
            """);

        Path out = tempDir.resolve("empty-map.yml");
        ArclightConfig.writeYaml(root, out, ArclightLocale.getInstance());

        String text = Files.readString(out, StandardCharsets.UTF_8);
        assertTrue(text.contains("overrides: {}"), () -> "expected overrides: {}, got:\n" + text);
    }

    @Test
    void knownMapKeysHealWhenMergedFromDefaults() throws Exception {
        // SnakeYAML drops bare null keys on load; missing keys are filled from defaults as {}.
        ConfigurationNode cur = load("""
            async-catcher:
              dump: true
              warn: true
              defaultOperation: block
              log-overrides:
                chunk entity get: DEBUG
            compatibility:
              symlink-world: false
            """);
        ConfigurationNode defaults = load("""
            async-catcher:
              overrides: {}
            compatibility:
              material-property-overrides: {}
              entity-property-overrides: {}
            """);
        cur.mergeValuesFrom(defaults);

        Path out = tempDir.resolve("merged-maps.yml");
        ArclightConfig.writeYaml(cur, out, ArclightLocale.getInstance());

        String text = Files.readString(out, StandardCharsets.UTF_8);
        assertTrue(text.contains("overrides: {}"), () -> "expected overrides: {}, got:\n" + text);
        assertTrue(text.contains("material-property-overrides: {}"),
            () -> "expected material-property-overrides: {}, got:\n" + text);
        assertTrue(text.contains("entity-property-overrides: {}"),
            () -> "expected entity-property-overrides: {}, got:\n" + text);
    }

    @Test
    void nonMapNullishScalarIsNotConvertedToBraces() throws Exception {
        ConfigurationNode root = load("""
            valid-username-regex: ""
            secret: ""
            """);

        Path out = tempDir.resolve("scalars.yml");
        ArclightConfig.writeYaml(root, out, ArclightLocale.getInstance());

        String text = Files.readString(out, StandardCharsets.UTF_8);
        assertTrue(text.contains("valid-username-regex:"),
            () -> "expected valid-username-regex retained, got:\n" + text);
        assertTrue(!text.contains("valid-username-regex: {}"),
            () -> "scalar must not become {}, got:\n" + text);
        assertTrue(!text.contains("secret: {}"),
            () -> "scalar must not become {}, got:\n" + text);
    }

    private static ConfigurationNode load(String yaml) throws Exception {
        return YAMLConfigurationLoader.builder()
            .setSource(() -> new BufferedReader(new StringReader(yaml)))
            .setFlowStyle(DumperOptions.FlowStyle.BLOCK)
            .build()
            .load();
    }
}
