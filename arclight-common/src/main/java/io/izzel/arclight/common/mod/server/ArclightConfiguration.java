package io.izzel.arclight.common.mod.server;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;

public final class ArclightConfiguration {

    private static final File FILE = new File("arclight.yml");
    private static YamlConfiguration configuration;

    private ArclightConfiguration() {
    }

    public static synchronized YamlConfiguration get() {
        if (configuration == null) {
            configuration = YamlConfiguration.loadConfiguration(FILE);
        }
        return configuration;
    }

    public static synchronized YamlConfiguration reload() {
        configuration = YamlConfiguration.loadConfiguration(FILE);
        return configuration;
    }

    public static synchronized void save() {
        try {
            get().save(FILE);
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to save arclight.yml", exception);
        }
    }

    public static synchronized boolean copySection(YamlConfiguration source, String sourcePath, String targetPath) {
        ConfigurationSection sourceSection = source.getConfigurationSection(sourcePath);
        if (sourceSection == null || get().contains(targetPath)) {
            return false;
        }
        copySection(sourceSection, get().createSection(targetPath));
        return true;
    }

    private static void copySection(ConfigurationSection source, ConfigurationSection target) {
        for (String key : source.getKeys(false)) {
            Object value = source.get(key);
            if (value instanceof ConfigurationSection section) {
                copySection(section, target.createSection(key));
            } else {
                target.set(key, value);
            }
        }
    }
}
