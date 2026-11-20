package org.Cry_Legende.sHReminders;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.scheduler.BukkitTask;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

/**
 * Groupes de rappels coupés par les joueurs (clé {@code groupe} d'un rappel), dans une table MariaDB que d'autres outils
 * peuvent écrire (le site, par exemple). Une ligne = un joueur qui ne veut plus voir en jeu les rappels d'un groupe.
 *
 * <p>La table est relue en entier toutes les {@code preferences.relecture-secondes} : le fil principal ne consulte qu'une
 * copie en mémoire. Si la base ne répond plus, la dernière copie lue reste en place.</p>
 */
final class MutedGroups {

    /** Groupe d'un rappel : lettres, chiffres, tiret et tiret bas, 32 caractères au plus. */
    static final Pattern GROUP = Pattern.compile("[a-z0-9_-]{1,32}");
    private static final Pattern TABLE_NAME = Pattern.compile("[A-Za-z0-9_$]+(\\.[A-Za-z0-9_$]+)?");

    private final SHReminders plugin;
    private final HikariDataSource pool;
    private final String table;
    private final int refreshSeconds;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "SHReminders-Preferences");
        t.setDaemon(true);
        return t;
    });

    /** « uuid:groupe » des joueurs qui ont coupé un groupe ; remplacée en entier à chaque relecture. */
    private volatile Set<String> muted = Set.of();
    private volatile boolean tableReady;
    private volatile boolean closed;
    private long lastErrorLog;
    private BukkitTask refreshTask;

    private MutedGroups(SHReminders plugin, HikariDataSource pool, String table, int refreshSeconds) {
        this.plugin = plugin;
        this.pool = pool;
        this.table = table;
        this.refreshSeconds = refreshSeconds;
    }

    /** Null quand {@code preferences.base-de-donnees.url} est vide : personne ne peut alors couper ses rappels. */
    static MutedGroups open(SHReminders plugin) {
        ConfigurationSection section = plugin.getConfig().getConfigurationSection("preferences");
        if (section == null) return null;
        String url = section.getString("base-de-donnees.url", "").trim();
        if (url.isEmpty()) return null;

        String table = section.getString("table", "shreminders_muted").trim();
        if (!TABLE_NAME.matcher(table).matches()) {
            throw new IllegalArgumentException("preferences.table invalide : " + table);
        }
        int timeoutMs = Math.max(1000, section.getInt("base-de-donnees.delai-ms", 5000));

        HikariConfig hikari = new HikariConfig();
        hikari.setPoolName("SHReminders-Preferences");
        hikari.setJdbcUrl(url);
        hikari.setUsername(section.getString("base-de-donnees.utilisateur", ""));
        hikari.setPassword(section.getString("base-de-donnees.mot-de-passe", ""));
        hikari.setDriverClassName("org.mariadb.jdbc.Driver");
        hikari.addDataSourceProperty("connectTimeout", String.valueOf(timeoutMs));
        hikari.addDataSourceProperty("socketTimeout", String.valueOf(Math.max(timeoutMs, 10_000)));
        hikari.setMaximumPoolSize(2);
        hikari.setMinimumIdle(1);
        hikari.setConnectionTimeout(timeoutMs);
        // le serveur démarre même si la base est absente : la relecture suivante réessaie
        hikari.setInitializationFailTimeout(-1);
        hikari.setKeepaliveTime(60_000);

        int refreshSeconds = Math.max(5, section.getInt("relecture-secondes", 30));
        return new MutedGroups(plugin, new HikariDataSource(hikari), table, refreshSeconds);
    }

    /** UUID hors ligne de Minecraft, celui de tous les serveurs du réseau : il dépend de la casse exacte du pseudo. */
    static UUID offlineUuid(String name) {
        return UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8));
    }

    /** Groupe d'un rappel tel qu'écrit en config, ramené en minuscules ; vide si le rappel n'en a pas. */
    static String normalize(String group) {
        return group == null ? "" : group.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * Première lecture tout de suite, puis toutes les {@code relecture-secondes}. Relectures et écritures passent par le
     * même fil : une relecture lancée avant une écriture ne peut pas effacer son effet de la copie en mémoire.
     */
    void start() {
        refreshTask = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, () -> {
            if (!closed) executor.execute(this::refresh);
        }, 0L, refreshSeconds * 20L);
    }

    void close() {
        closed = true;
        if (refreshTask != null) refreshTask.cancel();
        executor.shutdownNow();
        pool.close();
    }

    /** Le joueur a coupé ce groupe. Un rappel sans groupe n'est jamais coupé. */
    boolean isMuted(UUID playerId, String group) {
        String normalized = normalize(group);
        return !normalized.isEmpty() && muted.contains(playerId + ":" + normalized);
    }

    /** Groupes coupés par ce joueur, d'après la dernière relecture. */
    List<String> groupsOf(UUID playerId) {
        String prefix = playerId + ":";
        List<String> groups = new ArrayList<>();
        for (String key : muted) {
            if (key.startsWith(prefix)) groups.add(key.substring(prefix.length()));
        }
        groups.sort(null);
        return groups;
    }

    /** Coupe ou rétablit un groupe pour un joueur, puis met la copie en mémoire à jour. */
    CompletableFuture<Void> set(UUID playerId, String playerName, String group, boolean mute) {
        String normalized = normalize(group);
        return CompletableFuture.runAsync(() -> {
            try {
                ensureTable();
                try (Connection c = pool.getConnection()) {
                    if (mute) {
                        try (PreparedStatement ps = c.prepareStatement("INSERT INTO " + table
                            + " (uuid, reminder_group, player_name) VALUES (?, ?, ?) ON DUPLICATE KEY UPDATE player_name = VALUES(player_name)")) {
                            ps.setString(1, playerId.toString());
                            ps.setString(2, normalized);
                            ps.setString(3, playerName);
                            ps.executeUpdate();
                        }
                    } else {
                        try (PreparedStatement ps = c.prepareStatement("DELETE FROM " + table + " WHERE uuid = ? AND reminder_group = ?")) {
                            ps.setString(1, playerId.toString());
                            ps.setString(2, normalized);
                            ps.executeUpdate();
                        }
                    }
                }
            } catch (SQLException e) {
                throw new CompletionException(e);
            }
            Set<String> next = new HashSet<>(muted);
            if (mute) next.add(playerId + ":" + normalized);
            else next.remove(playerId + ":" + normalized);
            muted = Set.copyOf(next);
        }, executor);
    }

    private void refresh() {
        if (closed) return;
        try {
            ensureTable();
            Set<String> fresh = new HashSet<>();
            try (Connection c = pool.getConnection();
                 Statement st = c.createStatement();
                 ResultSet rs = st.executeQuery("SELECT uuid, reminder_group FROM " + table)) {
                while (rs.next()) {
                    fresh.add(rs.getString(1).toLowerCase(Locale.ROOT) + ":" + normalize(rs.getString(2)));
                }
            }
            muted = Set.copyOf(fresh);
        } catch (SQLException | RuntimeException e) {
            long now = System.currentTimeMillis();
            if (!closed && now - lastErrorLog > 600_000L) {
                lastErrorLog = now;
                plugin.getLogger().warning("Lecture des rappels coupés impossible (" + table + "), dernière liste gardée : " + e.getMessage());
            }
        }
    }

    /** Crée la table si elle manque. Une table déjà là ne demande aucun droit CREATE à l'utilisateur. */
    private void ensureTable() throws SQLException {
        if (tableReady) return;
        try (Connection c = pool.getConnection(); Statement st = c.createStatement()) {
            // Métadonnées plutôt qu'un SELECT qui échoue : le pilote journaliserait l'erreur dans la console
            int dot = table.indexOf('.');
            String catalog = dot < 0 ? c.getCatalog() : table.substring(0, dot);
            try (ResultSet found = c.getMetaData().getTables(catalog, null, table.substring(dot + 1), new String[] {"TABLE"})) {
                if (found.next()) {
                    tableReady = true;
                    return;
                }
            }
            st.executeUpdate("CREATE TABLE IF NOT EXISTS " + table + " ("
                + "uuid CHAR(36) NOT NULL, "
                + "reminder_group VARCHAR(32) NOT NULL, "
                + "player_name VARCHAR(16) NOT NULL, "
                + "muted_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, "
                + "PRIMARY KEY (uuid, reminder_group)"
                + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci");
            tableReady = true;
        }
    }
}
