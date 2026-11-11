package org.Cry_Legende.sHReminders;

import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * Lecture stricte de config.yml et messages.yml : un fichier illisible lève une erreur au lieu de donner une
 * configuration vide en silence. Une clé absente du fichier du serveur prend la valeur de celui du jar.
 */
public final class ConfigFiles {

    /** Fichier de configuration illisible. */
    public static final class ReadError extends Exception {
        private final String file;

        ReadError(String file, Throwable cause) {
            super(cause);
            this.file = file;
        }

        public String file() {
            return file;
        }

        /** Message de l'analyseur sur une seule ligne, sans code couleur. */
        public String detail() {
            String text = getCause() == null || getCause().getMessage() == null ? String.valueOf(getCause()) : getCause().getMessage();
            text = text.replaceAll("\\s*\\R\\s*", " ").replace('&', '+').trim();
            return text.length() > 220 ? text.substring(0, 220) + "..." : text;
        }
    }

    private ConfigFiles() {
    }

    /** Le fichier du dossier du plugin (créé depuis le jar s'il manque), complété par celui du jar. */
    public static YamlConfiguration read(JavaPlugin plugin, String name) throws ReadError {
        File file = new File(plugin.getDataFolder(), name);
        if (!file.exists()) {
            plugin.saveResource(name, false);
        }
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.load(file);
        } catch (IOException | InvalidConfigurationException e) {
            throw new ReadError(name, e);
        }
        yaml.setDefaults(bundled(plugin, name));
        return yaml;
    }

    /** Vérifie qu'un fichier YAML se lit sans erreur ; {@code label} est le nom affiché dans le message d'erreur. */
    public static void check(File file, String label) throws ReadError {
        try {
            new YamlConfiguration().load(file);
        } catch (IOException | InvalidConfigurationException e) {
            throw new ReadError(label, e);
        }
    }

    /** La copie du jar seule. */
    public static YamlConfiguration bundled(JavaPlugin plugin, String name) {
        YamlConfiguration yaml = new YamlConfiguration();
        try (InputStream in = plugin.getResource(name)) {
            if (in != null) {
                yaml.load(new InputStreamReader(in, StandardCharsets.UTF_8));
            }
        } catch (IOException | InvalidConfigurationException e) {
            plugin.getLogger().warning("\"" + name + "\" du jar illisible : " + e.getMessage());
        }
        return yaml;
    }
}
