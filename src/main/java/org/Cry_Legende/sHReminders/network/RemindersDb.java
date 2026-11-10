package org.Cry_Legende.sHReminders.network;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

/**
 * Rappels en MariaDB, partagés par tous les serveurs. Chargée seulement avec SHNetwork (HikariCP et le pilote MariaDB
 * viennent de {@code libraries:} dans plugin.yml).
 *
 * <p>Une ligne de {@code reminders_active} par joueur et par rappel. « Une seule fois » se décide ici : chaque
 * déclenchement est une instruction {@code UPDATE} ou {@code DELETE} dont la condition porte sur l'état que le serveur
 * a lu (rappel principal pas encore déclenché et à son heure, ou répétition à son numéro et à son heure). Le serveur
 * qui touche la ligne exécute le déclenchement, l'autre voit zéro ligne et laisse faire.</p>
 *
 * <p>Toutes les méthodes bloquent : à appeler hors du fil principal, par {@link #async} ou depuis une tâche asynchrone.</p>
 */
public final class RemindersDb {

    /** État d'un rappel en base. Mêmes champs que {@code SHReminders.Reminder}. */
    public record Row(UUID playerId, String playerName, String reminderId, long mainTriggerTime, boolean mainTriggered,
                      boolean repeatEnabled, long repeatDelay, int maxRepeat, int repeatCount, long nextRepeatTime) {

        public String key() {
            return playerId + ":" + reminderId;
        }
    }

    /** Une requête, exécutée sur un fil de la base. */
    @FunctionalInterface
    public interface SqlTask<T> {
        T run() throws SQLException;
    }

    private static final String COLUMNS = "uuid, reminder_id, player_name, main_trigger_time, main_triggered, "
        + "repeat_enabled, repeat_delay, max_repeat, repeat_count, next_repeat_time";
    private static final Pattern TABLE_NAME = Pattern.compile("[A-Za-z0-9_$]+(\\.[A-Za-z0-9_$]+)?");
    private static final int IMPORT_COMMIT_EVERY = 1000;

    private final HikariDataSource pool;
    private final ExecutorService executor = Executors.newFixedThreadPool(3, r -> {
        Thread t = new Thread(r, "SHReminders-Reseau");
        t.setDaemon(true);
        return t;
    });

    public RemindersDb(String url, String user, String password, int timeoutMs) {
        HikariConfig hikari = new HikariConfig();
        hikari.setPoolName("SHReminders");
        hikari.setJdbcUrl(url);
        hikari.setUsername(user);
        hikari.setPassword(password);
        hikari.setDriverClassName("org.mariadb.jdbc.Driver");
        hikari.addDataSourceProperty("connectTimeout", String.valueOf(timeoutMs));
        hikari.addDataSourceProperty("socketTimeout", String.valueOf(Math.max(timeoutMs, 10_000)));
        // les décomptes de lignes ci-dessous sont les lignes trouvées, jamais les lignes modifiées
        hikari.addDataSourceProperty("useAffectedRows", "false");
        hikari.setMaximumPoolSize(4);
        hikari.setMinimumIdle(1);
        hikari.setConnectionTimeout(timeoutMs);
        // le serveur démarre même si la base est absente : le plugin réessaie jusqu'à ce qu'elle réponde
        hikari.setInitializationFailTimeout(-1);
        hikari.setKeepaliveTime(60_000);
        this.pool = new HikariDataSource(hikari);
    }

    /** Lance la requête sur un fil de la base ; une erreur SQL termine le futur en erreur. */
    public <T> CompletableFuture<T> async(SqlTask<T> task) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return task.run();
            } catch (SQLException e) {
                throw new CompletionException(e);
            }
        }, executor);
    }

    public void close() {
        executor.shutdownNow();
        pool.close();
    }

    /** Crée la table si elle manque. Une table déjà là ne demande aucun droit CREATE à l'utilisateur. */
    public void createTables() throws SQLException {
        try (Connection c = pool.getConnection(); Statement st = c.createStatement()) {
            try (ResultSet rs = st.executeQuery("SELECT 1 FROM reminders_active LIMIT 1")) {
                return;
            } catch (SQLException missing) {
                // la table n'existe pas encore : création ci-dessous
            }
            st.executeUpdate("CREATE TABLE IF NOT EXISTS reminders_active ("
                + "uuid CHAR(36) NOT NULL, "
                + "reminder_id VARCHAR(190) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL, "
                + "player_name VARCHAR(64) NULL, "
                + "main_trigger_time BIGINT NOT NULL, "
                + "main_triggered TINYINT(1) NOT NULL DEFAULT 0, "
                + "repeat_enabled TINYINT(1) NOT NULL DEFAULT 0, "
                + "repeat_delay BIGINT NOT NULL DEFAULT 0, "
                + "max_repeat INT NOT NULL DEFAULT 0, "
                + "repeat_count INT NOT NULL DEFAULT 0, "
                + "next_repeat_time BIGINT NOT NULL DEFAULT 0, "
                + "PRIMARY KEY (uuid, reminder_id)"
                + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci");
        }
    }

    // ------------------------------------------------------------------ lecture

    /** Tous les rappels : de quoi remplir la copie en mémoire (placeholder et planificateur). */
    public List<Row> loadAll() throws SQLException {
        List<Row> rows = new ArrayList<>();
        try (Connection c = pool.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT " + COLUMNS + " FROM reminders_active");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                rows.add(read(rs));
            }
        }
        return rows;
    }

    /** Un rappel, ou null s'il n'existe plus. */
    public Row find(UUID playerId, String reminderId) throws SQLException {
        try (Connection c = pool.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT " + COLUMNS + " FROM reminders_active WHERE uuid = ? AND reminder_id = ?")) {
            ps.setString(1, playerId.toString());
            ps.setString(2, reminderId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? read(rs) : null;
            }
        }
    }

    private static Row read(ResultSet rs) throws SQLException {
        return new Row(UUID.fromString(rs.getString(1)), rs.getString(3), rs.getString(2), rs.getLong(4), rs.getInt(5) != 0,
            rs.getInt(6) != 0, rs.getLong(7), rs.getInt(8), rs.getInt(9), rs.getLong(10));
    }

    // ------------------------------------------------------------------ écriture

    /** Programme le rappel ; un rappel déjà programmé pour ce joueur est remplacé, comme en mode local. */
    public void upsert(Row row) throws SQLException {
        try (Connection c = pool.getConnection();
             PreparedStatement ps = c.prepareStatement("INSERT INTO reminders_active (" + COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?) "
                 + "ON DUPLICATE KEY UPDATE player_name = VALUES(player_name), main_trigger_time = VALUES(main_trigger_time), "
                 + "main_triggered = VALUES(main_triggered), repeat_enabled = VALUES(repeat_enabled), repeat_delay = VALUES(repeat_delay), "
                 + "max_repeat = VALUES(max_repeat), repeat_count = VALUES(repeat_count), next_repeat_time = VALUES(next_repeat_time)")) {
            bind(ps, row);
            ps.executeUpdate();
        }
    }

    private static void bind(PreparedStatement ps, Row row) throws SQLException {
        ps.setString(1, row.playerId().toString());
        ps.setString(2, row.reminderId());
        ps.setString(3, row.playerName());
        ps.setLong(4, row.mainTriggerTime());
        ps.setInt(5, row.mainTriggered() ? 1 : 0);
        ps.setInt(6, row.repeatEnabled() ? 1 : 0);
        ps.setLong(7, row.repeatDelay());
        ps.setInt(8, row.maxRepeat());
        ps.setInt(9, row.repeatCount());
        ps.setLong(10, row.nextRepeatTime());
    }

    /**
     * Réclame le déclenchement du rappel principal que ce serveur a lu (pas encore déclenché, à cette heure). Vrai pour
     * un seul serveur : celui qui change la ligne. Avec répétition, la ligne passe à « déclenché » et la première
     * répétition est fixée à {@code nextRepeatTime} ; sans répétition, la ligne est supprimée.
     */
    public boolean claimMain(Row seen, long nextRepeatTime) throws SQLException {
        try (Connection c = pool.getConnection()) {
            if (seen.repeatEnabled()) {
                try (PreparedStatement ps = c.prepareStatement("UPDATE reminders_active SET main_triggered = 1, next_repeat_time = ? "
                    + "WHERE uuid = ? AND reminder_id = ? AND main_triggered = 0 AND main_trigger_time = ?")) {
                    ps.setLong(1, nextRepeatTime);
                    ps.setString(2, seen.playerId().toString());
                    ps.setString(3, seen.reminderId());
                    ps.setLong(4, seen.mainTriggerTime());
                    return ps.executeUpdate() == 1;
                }
            }
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM reminders_active "
                + "WHERE uuid = ? AND reminder_id = ? AND main_triggered = 0 AND main_trigger_time = ?")) {
                ps.setString(1, seen.playerId().toString());
                ps.setString(2, seen.reminderId());
                ps.setLong(3, seen.mainTriggerTime());
                return ps.executeUpdate() == 1;
            }
        }
    }

    /**
     * Réclame la répétition que ce serveur a lue (même numéro, même heure). Vrai pour un seul serveur. Si c'était la
     * dernière, la ligne est supprimée ; sinon le compteur monte et la répétition suivante est fixée à
     * {@code nextRepeatTime}.
     */
    public boolean claimRepeat(Row seen, boolean last, long nextRepeatTime) throws SQLException {
        try (Connection c = pool.getConnection()) {
            if (last) {
                try (PreparedStatement ps = c.prepareStatement("DELETE FROM reminders_active "
                    + "WHERE uuid = ? AND reminder_id = ? AND main_triggered = 1 AND repeat_count = ? AND next_repeat_time = ?")) {
                    ps.setString(1, seen.playerId().toString());
                    ps.setString(2, seen.reminderId());
                    ps.setInt(3, seen.repeatCount());
                    ps.setLong(4, seen.nextRepeatTime());
                    return ps.executeUpdate() == 1;
                }
            }
            try (PreparedStatement ps = c.prepareStatement("UPDATE reminders_active SET repeat_count = ?, next_repeat_time = ? "
                + "WHERE uuid = ? AND reminder_id = ? AND main_triggered = 1 AND repeat_count = ? AND next_repeat_time = ?")) {
                ps.setInt(1, seen.repeatCount() + 1);
                ps.setLong(2, nextRepeatTime);
                ps.setString(3, seen.playerId().toString());
                ps.setString(4, seen.reminderId());
                ps.setInt(5, seen.repeatCount());
                ps.setLong(6, seen.nextRepeatTime());
                return ps.executeUpdate() == 1;
            }
        }
    }

    // ------------------------------------------------------------------ comptes

    /**
     * Pseudo avec sa casse d'un compte connu du réseau, retrouvé par son pseudo en minuscules dans la table des comptes
     * (colonnes {@code name} et {@code display_name}), ou null.
     */
    public String displayName(String table, String lowerName) throws SQLException {
        if (!TABLE_NAME.matcher(table).matches()) {
            throw new SQLException("nom de table invalide : " + table);
        }
        try (Connection c = pool.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT display_name FROM " + table + " WHERE name = ?")) {
            ps.setString(1, lowerName);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    // ------------------------------------------------------------------ importation

    /**
     * Reprend les rappels de l'ancien reminders.yml. Une ligne déjà en base n'est pas modifiée : l'importation peut être
     * relancée sans remettre un rappel à zéro.
     *
     * @return {importés, déjà présents}
     */
    public int[] importRows(List<Row> rows) throws SQLException {
        int imported = 0;
        int present = 0;
        try (Connection c = pool.getConnection()) {
            c.setAutoCommit(false);
            try (PreparedStatement ps = c.prepareStatement("INSERT IGNORE INTO reminders_active (" + COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
                int pending = 0;
                for (Row row : rows) {
                    bind(ps, row);
                    if (ps.executeUpdate() == 1) {
                        imported++;
                    } else {
                        present++;
                    }
                    if (++pending >= IMPORT_COMMIT_EVERY) {
                        c.commit();
                        pending = 0;
                    }
                }
                c.commit();
            } catch (SQLException e) {
                c.rollback();
                throw e;
            }
        }
        return new int[]{imported, present};
    }
}
