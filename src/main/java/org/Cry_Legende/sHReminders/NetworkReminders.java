package org.Cry_Legende.sHReminders;

import com.google.gson.JsonObject;
import org.Cry_Legende.sHReminders.SHReminders.Reminder;
import org.Cry_Legende.sHReminders.network.NetBus;
import org.Cry_Legende.sHReminders.network.RemindersDb;
import org.Cry_Legende.sHReminders.network.RemindersDb.Row;
import org.Cry_Legende.sHReminders.network.ShNetworkBus;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;

/**
 * SHReminders sur plusieurs serveurs de jeu (SHNetwork) : les rappels sont dans la base partagée ({@link RemindersDb}) et
 * chaque serveur en garde une copie en mémoire (placeholder, planificateur) que le bus fait relire après chaque
 * écriture. Seule {@link ShNetworkBus} connaît SHNetwork : sans lui, aucune classe de ce paquet n'est chargée.
 *
 * <p>Pas de serveur meneur : chaque serveur parcourt sa copie chaque seconde et, pour un rappel échu, tente de le
 * réclamer en base. Un seul y parvient, c'est lui qui exécute la commande console du rappel ; l'autre voit que la ligne a
 * changé et relit. Un serveur qui tombe ne retarde rien, et deux serveurs ne peuvent jamais exécuter la même commande.
 * Le rappel principal peut être réclamé par n'importe quel serveur (la commande part même si le joueur est hors ligne) ;
 * une répétition ne l'est que par le serveur où le joueur se trouve.</p>
 *
 * <p>Messages du canal {@code reseau.canal} :</p>
 * <ul>
 *   <li>{@code changed} {@code {uuid, id}} : ce rappel a changé en base, le relire ;</li>
 *   <li>{@code fire} {@code {uuid, id}} : le rappel principal vient d'être déclenché ; le serveur où se trouve le joueur
 *       affiche titre, message, barre d'action et son ;</li>
 *   <li>{@code reload} : tous les rappels ont changé (importation), tout relire.</li>
 * </ul>
 */
public final class NetworkReminders {

    private static final long RETRY_MS = 10_000L;

    private final SHReminders plugin;
    private final NetBus bus;
    private final RemindersDb db;
    private final String channel;
    private final String accountsTable;
    // Rappels dont la réclamation est en cours : le planificateur ne les redemande pas à la seconde suivante
    private final Set<String> inFlight = ConcurrentHashMap.newKeySet();
    private final Set<String> warnedUnknown = ConcurrentHashMap.newKeySet();
    private volatile boolean loaded;
    private volatile boolean closed;
    private volatile long lastErrorLog;
    private BukkitTask ticker;

    private NetworkReminders(SHReminders plugin, NetBus bus, RemindersDb db, String channel, String accountsTable) {
        this.plugin = plugin;
        this.bus = bus;
        this.db = db;
        this.channel = channel;
        this.accountsTable = accountsTable;
    }

    /**
     * Prépare le pont vers SHNetwork et la connexion à la base partagée, sans les interroger. Une configuration
     * incomplète lève une exception.
     */
    static NetworkReminders open(SHReminders plugin) {
        FileConfiguration config = plugin.getConfig();
        String base = "reseau.base-de-donnees.";
        String user = config.getString(base + "utilisateur");
        String password = config.getString(base + "mot-de-passe");
        if (user == null || user.isBlank() || password == null || password.isBlank()) {
            throw new IllegalStateException("reseau.base-de-donnees.utilisateur et mot-de-passe sont à renseigner");
        }
        RemindersDb db = new RemindersDb(
            config.getString(base + "url", "jdbc:mariadb://127.0.0.1:3306/bo_net"),
            user,
            password,
            Math.max(250, config.getInt(base + "delai-ms", 5000)));
        try {
            NetBus bus = ShNetworkBus.open();
            return new NetworkReminders(plugin, bus, db, config.getString("reseau.canal", "shreminders"),
                config.getString("reseau.table-comptes", ""));
        } catch (RuntimeException | LinkageError e) {
            db.close();
            throw e;
        }
    }

    /** Écoute le bus, charge la base (avec de nouveaux essais tant qu'elle ne répond pas) et lance le planificateur. */
    void start() {
        bus.listen(channel, this::onMessage, true);
        Thread loader = new Thread(this::initialLoad, "SHReminders-Reseau-Demarrage");
        loader.setDaemon(true);
        loader.start();
        ticker = new BukkitRunnable() {
            @Override
            public void run() {
                tick();
            }
        }.runTaskTimer(plugin, 20L, 20L);
    }

    /** Arrêt du plugin : arrête le planificateur et ferme la base. Rien n'est écrit dans reminders.yml. */
    void close() {
        closed = true;
        if (ticker != null) {
            ticker.cancel();
        }
        db.close();
    }

    // ------------------------------------------------------------------ chargement

    private void initialLoad() {
        while (!closed) {
            try {
                db.createTables();
                loadAll();
                return;
            } catch (Exception e) {
                plugin.getLogger().warning("Base du réseau injoignable, nouvel essai dans " + RETRY_MS / 1000 + " s : " + e.getMessage());
                try {
                    Thread.sleep(RETRY_MS);
                } catch (InterruptedException interrupted) {
                    return;
                }
            }
        }
    }

    /** Remplace la copie en mémoire par le contenu de la base (appelée hors du fil principal). */
    private void loadAll() throws SQLException {
        List<Row> rows = db.loadAll();
        Map<String, Reminder> fresh = new HashMap<>(rows.size() * 2);
        for (Row row : rows) {
            fresh.put(row.key(), toReminder(row));
        }
        plugin.replaceReminders(fresh);
        boolean first = !loaded;
        loaded = true;
        if (first) {
            plugin.getLogger().info(fresh.size() + " rappels actifs chargés depuis la base du réseau.");
        }
    }

    /** Relit un rappel en base et met la copie en mémoire à jour (supprimée s'il n'existe plus). */
    private void refresh(UUID playerId, String reminderId) {
        if (closed) {
            return;
        }
        try {
            db.async(() -> db.find(playerId, reminderId)).whenComplete((row, error) -> {
                if (error != null) {
                    logError("Lecture d'un rappel impossible", error);
                } else if (row == null) {
                    plugin.removeReminder(playerId + ":" + reminderId);
                } else {
                    plugin.putReminder(toReminder(row));
                }
            });
        } catch (RejectedExecutionException e) {
            // arrêt du plugin en cours
        }
    }

    private void reloadAll() {
        if (closed) {
            return;
        }
        try {
            db.async(() -> {
                loadAll();
                return null;
            }).whenComplete((ok, error) -> {
                if (error != null) {
                    logError("Lecture de la base du réseau impossible", error);
                }
            });
        } catch (RejectedExecutionException e) {
            // arrêt du plugin en cours
        }
    }

    private void logError(String what, Throwable error) {
        long now = System.currentTimeMillis();
        if (now - lastErrorLog > 60_000L) {
            lastErrorLog = now;
            Throwable cause = error.getCause() != null ? error.getCause() : error;
            plugin.getLogger().warning(what + " : " + cause.getMessage());
        }
    }

    // ------------------------------------------------------------------ bus

    private static JsonObject message(String type) {
        JsonObject json = new JsonObject();
        json.addProperty("type", type);
        return json;
    }

    private static JsonObject message(String type, UUID playerId, String reminderId) {
        JsonObject json = message(type);
        json.addProperty("uuid", playerId.toString());
        json.addProperty("id", reminderId);
        return json;
    }

    private void onMessage(JsonObject m) {
        try {
            switch (m.get("type").getAsString()) {
                case "changed" -> refresh(UUID.fromString(m.get("uuid").getAsString()), m.get("id").getAsString());
                case "reload" -> reloadAll();
                case "fire" -> {
                    UUID playerId = UUID.fromString(m.get("uuid").getAsString());
                    String reminderId = m.get("id").getAsString();
                    refresh(playerId, reminderId);
                    // le rappel a été réclamé par un autre serveur : on l'affiche si le joueur est ici
                    Player player = Bukkit.getPlayer(playerId);
                    if (player != null && plugin.configuredReminderIds().contains(reminderId)) {
                        plugin.triggerMainReminder(player, "reminders." + reminderId);
                    }
                }
                default -> {
                }
            }
        } catch (RuntimeException e) {
            plugin.getLogger().fine("Message du réseau ignoré : " + e);
        }
    }

    // ------------------------------------------------------------------ planificateur

    /** Chaque seconde, sur le fil principal : réclame en base les rappels échus de la copie en mémoire. */
    private void tick() {
        if (!loaded || closed) {
            return;
        }
        long now = System.currentTimeMillis();
        List<Reminder> mainDue = new ArrayList<>();
        List<Reminder> repeatDue = new ArrayList<>();
        synchronized (plugin.activeReminders()) {
            for (Reminder rem : plugin.activeReminders().values()) {
                if (!rem.mainTriggered) {
                    if (now >= rem.mainTriggerTime) {
                        mainDue.add(rem);
                    }
                } else if (rem.repeatEnabled && now >= rem.nextRepeatTime && Bukkit.getPlayer(rem.playerId) != null) {
                    // une répétition ne concerne que le serveur où le joueur se trouve
                    repeatDue.add(rem);
                }
            }
        }
        for (Reminder rem : mainDue) {
            if (isConfigured(rem)) {
                claimMain(rem, now);
            }
        }
        for (Reminder rem : repeatDue) {
            if (isConfigured(rem)) {
                claimRepeat(rem, now);
            }
        }
    }

    /**
     * Un rappel dont l'identifiant n'est plus dans config.yml est ignoré par ce serveur. Sa ligne reste en base : un autre
     * serveur dont la configuration le connaît peut encore le déclencher.
     */
    private boolean isConfigured(Reminder rem) {
        if (plugin.configuredReminderIds().contains(rem.reminderId)) {
            return true;
        }
        if (warnedUnknown.add(rem.reminderId)) {
            plugin.getLogger().warning("Le rappel " + rem.reminderId + " n'a pas de configuration sur ce serveur : ignoré ici.");
        }
        return false;
    }

    private void claimMain(Reminder rem, long now) {
        String key = rem.playerId + ":" + rem.reminderId;
        if (!inFlight.add(key)) {
            return;
        }
        Row seen = toRow(rem);
        long nextRepeat = rem.repeatEnabled ? now + rem.repeatDelay : 0L;
        db.async(() -> db.claimMain(seen, nextRepeat)).whenComplete((won, error) -> plugin.runMain(() -> {
            inFlight.remove(key);
            if (error != null) {
                logError("Réclamation d'un rappel impossible", error);
            } else if (won) {
                mainClaimed(seen, nextRepeat);
            } else {
                // un autre serveur l'a réclamé, ou le rappel a été reprogrammé : on relit
                refresh(seen.playerId(), seen.reminderId());
            }
        }));
    }

    /** Ce serveur a réclamé le rappel principal : commande console, affichage si le joueur est ici, puis état partagé. */
    private void mainClaimed(Row seen, long nextRepeat) {
        String configPath = "reminders." + seen.reminderId();
        Player player = Bukkit.getPlayer(seen.playerId());
        String name = player != null ? player.getName() : seen.playerName();
        if (name == null) {
            name = bus.onlineName(seen.playerId()).orElse(null);
        }
        if (name == null) {
            name = Bukkit.getOfflinePlayer(seen.playerId()).getName();
        }
        if (name == null) {
            plugin.getLogger().warning("Impossible de déterminer le nom du joueur pour UUID " + seen.playerId() + " pour le rappel " + seen.reminderId());
        }

        String command = plugin.getConfig().getString(configPath + ".command", "");
        if (!command.isEmpty() && name != null) {
            command = ChatColor.translateAlternateColorCodes('&', command).replace("%player%", name);
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);
        }
        if (player != null) {
            plugin.triggerMainReminder(player, configPath);
        }

        if (seen.repeatEnabled()) {
            plugin.putReminder(toReminder(new Row(seen.playerId(), seen.playerName(), seen.reminderId(), seen.mainTriggerTime(), true,
                true, seen.repeatDelay(), seen.maxRepeat(), seen.repeatCount(), nextRepeat)));
        } else {
            plugin.removeReminder(seen.playerId() + ":" + seen.reminderId());
        }
        // les autres serveurs relisent la ligne et affichent le rappel si le joueur est chez eux
        bus.publish(channel, message("fire", seen.playerId(), seen.reminderId()));
    }

    private void claimRepeat(Reminder rem, long now) {
        String key = rem.playerId + ":" + rem.reminderId;
        if (!inFlight.add(key)) {
            return;
        }
        Row seen = toRow(rem);
        boolean last = isLastRepeat(rem.repeatCount, rem.maxRepeat);
        long next = now + rem.repeatDelay;
        db.async(() -> db.claimRepeat(seen, last, next)).whenComplete((won, error) -> plugin.runMain(() -> {
            inFlight.remove(key);
            if (error != null) {
                logError("Réclamation d'un rappel impossible", error);
            } else if (won) {
                repeatClaimed(seen, last, next);
            } else {
                refresh(seen.playerId(), seen.reminderId());
            }
        }));
    }

    private void repeatClaimed(Row seen, boolean last, long next) {
        Player player = Bukkit.getPlayer(seen.playerId());
        if (player != null) {
            plugin.triggerRepeatReminder(player, "reminders." + seen.reminderId());
        }
        if (last) {
            plugin.removeReminder(seen.playerId() + ":" + seen.reminderId());
        } else {
            plugin.putReminder(toReminder(new Row(seen.playerId(), seen.playerName(), seen.reminderId(), seen.mainTriggerTime(), true,
                seen.repeatEnabled(), seen.repeatDelay(), seen.maxRepeat(), seen.repeatCount() + 1, next)));
        }
        bus.publish(channel, message("changed", seen.playerId(), seen.reminderId()));
    }

    /** Vrai si la répétition qui vient d'être faite est la dernière : {@code maxRepeat} vaut -1 (ou 0) pour « sans fin ». */
    static boolean isLastRepeat(int repeatCount, int maxRepeat) {
        return maxRepeat > 0 && repeatCount + 1 >= maxRepeat;
    }

    // ------------------------------------------------------------------ commande /shreminders

    /**
     * Programme un rappel pour un joueur, connecté sur n'importe quel serveur ou non. À appeler hors du fil principal :
     * la recherche du joueur et l'écriture en base bloquent. Rend la réponse à l'administrateur par le fil principal.
     */
    void schedule(CommandSender sender, String playerNameInput, String reminderId, String configPath) {
        try {
            NetBus.Found target = resolve(playerNameInput);
            if (target == null) {
                plugin.runMain(() -> sender.sendMessage(plugin.networkMessage("player_not_found").replace("%player%", playerNameInput)));
                return;
            }

            // mêmes champs qu'en mode local
            int delayMinutes = plugin.getConfig().getInt(configPath + ".delay", 90);
            long mainDelayMs = delayMinutes * 60L * 1000L;
            boolean repeatEnabled = plugin.getConfig().getBoolean(configPath + ".repeat.enabled", false);
            long repeatDelay = 0L;
            int maxRepeat = 0;
            if (repeatEnabled) {
                repeatDelay = plugin.getConfig().getInt(configPath + ".repeat.delay", 5) * 60L * 1000L;
                maxRepeat = plugin.getConfig().getInt(configPath + ".repeat.maxRepeat", -1);
            }
            Row row = new Row(target.uuid(), target.name(), reminderId, System.currentTimeMillis() + mainDelayMs, false,
                repeatEnabled, repeatDelay, maxRepeat, 0, 0L);

            db.upsert(row);
            plugin.putReminder(toReminder(row));
            bus.publish(channel, message("changed", row.playerId(), row.reminderId()));
            plugin.runMain(() -> sender.sendMessage(ChatColor.GREEN + "Rappel '" + reminderId + "' programmé pour " + target.name() + "."));
        } catch (SQLException e) {
            logError("Programmation d'un rappel impossible", e);
            plugin.runMain(() -> sender.sendMessage(plugin.networkMessage("database_error")));
        }
    }

    /**
     * Joueur visé par la commande : connecté sur le réseau, sinon compte connu de la table des comptes, sinon joueur déjà
     * venu sur ce serveur. L'UUID est celui du réseau (hors ligne), jamais celui d'une recherche Mojang.
     */
    private NetBus.Found resolve(String input) {
        Optional<NetBus.Found> online = bus.find(input);
        if (online.isPresent()) {
            return online.get();
        }
        if (!accountsTable.isBlank()) {
            try {
                String displayName = db.displayName(accountsTable, input.toLowerCase(Locale.ROOT));
                if (displayName != null) {
                    return new NetBus.Found(offlineUuid(displayName), displayName);
                }
            } catch (SQLException e) {
                logError("Table des comptes illisible", e);
            }
        }
        OfflinePlayer offline = Bukkit.getOfflinePlayer(input);
        if (offline == null || offline.getUniqueId() == null || offline.getName() == null) {
            return null;
        }
        return new NetBus.Found(offline.getUniqueId(), offline.getName());
    }

    /** UUID « hors ligne » d'un pseudo avec sa casse, celui de tout le réseau. */
    static UUID offlineUuid(String name) {
        return UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8));
    }

    // ------------------------------------------------------------------ importation

    /** /shreminders import : reprend reminders.yml dans la base ; ce qui y est déjà n'est pas modifié. */
    void importFile(CommandSender sender) {
        CompletableFuture.supplyAsync(this::readFile)
            .thenCompose(rows -> db.async(() -> db.importRows(rows)))
            .whenComplete((counts, error) -> plugin.runMain(() -> {
                if (error != null) {
                    plugin.getLogger().warning("Importation impossible : " + (error.getCause() != null ? error.getCause() : error));
                    sender.sendMessage(plugin.networkMessage("import_error"));
                    return;
                }
                sender.sendMessage(plugin.networkMessage("import_success")
                    .replace("%IMPORTED%", String.valueOf(counts[0]))
                    .replace("%PRESENT%", String.valueOf(counts[1])));
                bus.publish(channel, message("reload"));
                reloadAll();
            }));
    }

    /** Lit reminders.yml (clés en base64 « uuid:identifiant », ancien format compris) sans toucher à la copie en mémoire. */
    private List<Row> readFile() {
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(new File(plugin.getDataFolder(), "reminders.yml"));
        List<Row> rows = new ArrayList<>();
        for (String encodedKey : yaml.getKeys(false)) {
            ConfigurationSection section = yaml.getConfigurationSection(encodedKey);
            if (section == null) {
                continue;
            }
            try {
                UUID playerId;
                String reminderId;
                String playerIdText = section.getString("playerId");
                String reminderIdText = section.getString("reminderId");
                if (playerIdText != null && reminderIdText != null) {
                    playerId = UUID.fromString(playerIdText);
                    reminderId = reminderIdText;
                } else {
                    String[] parts = new String(Base64.getDecoder().decode(encodedKey), StandardCharsets.UTF_8).split(":", 2);
                    if (parts.length != 2) {
                        plugin.getLogger().warning("Clé invalide dans reminders.yml : " + encodedKey);
                        continue;
                    }
                    playerId = UUID.fromString(parts[0]);
                    reminderId = parts[1];
                }
                rows.add(new Row(playerId, section.getString("playerName"), reminderId, section.getLong("mainTriggerTime"),
                    section.getBoolean("mainTriggered"), section.getBoolean("repeatEnabled"), section.getLong("repeatDelay"),
                    section.getInt("maxRepeat"), section.getInt("repeatCount"), section.getLong("nextRepeatTime")));
            } catch (RuntimeException e) {
                plugin.getLogger().warning("Rappel illisible dans reminders.yml (clé " + encodedKey + ") : " + e.getMessage());
            }
        }
        return rows;
    }

    // ------------------------------------------------------------------ conversions

    private static Reminder toReminder(Row row) {
        Reminder rem = new Reminder();
        rem.playerId = row.playerId();
        rem.playerName = row.playerName();
        rem.reminderId = row.reminderId();
        rem.mainTriggerTime = row.mainTriggerTime();
        rem.mainTriggered = row.mainTriggered();
        rem.repeatEnabled = row.repeatEnabled();
        rem.repeatDelay = row.repeatDelay();
        rem.maxRepeat = row.maxRepeat();
        rem.repeatCount = row.repeatCount();
        rem.nextRepeatTime = row.nextRepeatTime();
        return rem;
    }

    private static Row toRow(Reminder rem) {
        return new Row(rem.playerId, rem.playerName, rem.reminderId, rem.mainTriggerTime, rem.mainTriggered, rem.repeatEnabled,
            rem.repeatDelay, rem.maxRepeat, rem.repeatCount, rem.nextRepeatTime);
    }
}
