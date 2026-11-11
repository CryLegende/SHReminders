package org.Cry_Legende.sHReminders;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.Base64;
import java.util.logging.Level;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.NamespacedKey;
import org.bukkit.OfflinePlayer;
import org.bukkit.Registry;
import org.bukkit.Sound;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;

public class SHReminders extends JavaPlugin {

    private File remindersFile;
    private YamlConfiguration remindersConfig;
    private final Map<String, Reminder> activeReminders = new HashMap<>();
    private BukkitRunnable schedulerTask;
    // Une seule écriture de reminders.yml à la fois (l'instantané est pris sous ce verrou)
    private final Object fileLock = new Object();
    // Identifiants des rappels définis dans config.yml : la tâche de chaque seconde les consulte pour chaque rappel actif
    private Set<String> configuredReminderIds = Set.of();
    // Vrai une fois reminders.yml chargé : sans cela, l'arrêt écraserait le fichier par une liste vide
    private boolean remindersLoaded;
    // Mode réseau (SHNetwork présent) : null en mode local
    private NetworkReminders network;
    // Textes de messages.yml ; le fichier est lu dans tous les modes
    private volatile YamlConfiguration messages;
    // Section reseau au démarrage : elle n'est lue qu'à ce moment, un reload qui la change le signale
    private Map<String, Object> networkSettings = Map.of();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        cacheConfiguredReminderIds();
        loadMessages();
        networkSettings = networkSettingsNow();
        PluginCommand command = getCommand("shreminders");
        ReminderCommand executor = new ReminderCommand(this);
        command.setExecutor(executor);
        command.setTabCompleter(executor);

        remindersFile = new File(getDataFolder(), "reminders.yml");
        if (!remindersFile.exists()) {
            try {
                remindersFile.getParentFile().mkdirs();
                remindersFile.createNewFile();
            } catch (IOException e) {
                getLogger().severe("Impossible de créer le fichier reminders.yml: " + e.getMessage());
            }
        }
        if (!enableNetwork()) {
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        if (network == null) {
            remindersConfig = YamlConfiguration.loadConfiguration(remindersFile);
            loadReminders();
            startScheduler();
            remindersLoaded = true;
        } else {
            // les rappels sont dans la base partagée : le planificateur du réseau les réclame un à un
            network.start();
        }

        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null) {
            new SHRemindersPlaceholderExpansion(this).register();
            getLogger().info("PlaceholderAPI trouvée : les placeholders SHReminders ont été enregistrés.");
        }
    }

    // Chemins de toutes les sections sous « reminders », à toute profondeur : un identifiant contenant un point
    // (« Vote N°2 (top-serveurs.net) ») est découpé en sous-sections par Bukkit, son chemin complet doit y figurer
    private void cacheConfiguredReminderIds() {
        Set<String> ids = new HashSet<>();
        ConfigurationSection section = getConfig().getConfigurationSection("reminders");
        if (section != null) {
            for (String path : section.getKeys(true)) {
                if (section.isConfigurationSection(path)) {
                    ids.add(path);
                }
            }
        }
        configuredReminderIds = Set.copyOf(ids);
    }

    @Override
    public void onDisable() {
        if (network != null) {
            // reminders.yml n'est pas réécrit : la base fait foi
            network.close();
            return;
        }
        if (schedulerTask != null) {
            schedulerTask.cancel();
        }
        if (remindersLoaded) {
            saveReminders(); // Sauvegarder à la désactivation est une bonne pratique
        }
    }

    /** Programme le rappel de config.yml {@code reminders.<id>} pour un joueur, même hors ligne. */
    void schedule(CommandSender sender, String playerNameInput, String reminderId) {
        final String configPath = "reminders." + reminderId;

        if (!getConfig().contains(configPath)) {
            sender.sendMessage(message("unknown_reminder", "%id%", reminderId));
            return;
        }

        // --- Début de la section asynchrone ---
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            if (network != null) {
                network.schedule(sender, playerNameInput, reminderId, configPath);
                return;
            }
            OfflinePlayer offlinePlayer = Bukkit.getOfflinePlayer(playerNameInput);

            if (offlinePlayer == null || offlinePlayer.getUniqueId() == null || offlinePlayer.getName() == null) {
                Bukkit.getScheduler().runTask(this, () -> {
                    sender.sendMessage(message("player_not_found_local", "%player%", playerNameInput));
                });
                return;
            }

            UUID playerId = offlinePlayer.getUniqueId();
            String canonicalPlayerName = offlinePlayer.getName(); // Nom canonique du joueur

            String key = playerId.toString() + ":" + reminderId;

            // Accès à la config (thread-safe pour la lecture)
            int delayMinutes = getConfig().getInt(configPath + ".delay", 90);
            long mainDelayMs = delayMinutes * 60L * 1000L;
            long now = System.currentTimeMillis();

            Reminder newReminder = new Reminder();
            newReminder.playerId = playerId;
            newReminder.playerName = canonicalPlayerName; // Stocker le nom du joueur
            newReminder.reminderId = reminderId;
            newReminder.mainTriggerTime = now + mainDelayMs;
            newReminder.mainTriggered = false;

            newReminder.repeatEnabled = getConfig().getBoolean(configPath + ".repeat.enabled", false);
            if (newReminder.repeatEnabled) {
                int repeatDelayMinutes = getConfig().getInt(configPath + ".repeat.delay", 5);
                newReminder.repeatDelay = repeatDelayMinutes * 60L * 1000L;
                newReminder.maxRepeat = getConfig().getInt(configPath + ".repeat.maxRepeat", -1);
                newReminder.repeatCount = 0;
                newReminder.nextRepeatTime = 0;
            }

            // Synchronisation pour la modification de activeReminders et la sauvegarde
            // Bien que activeReminders soit un HashMap simple, saveReminders fait de l'I/O.
            // Il est plus sûr de synchroniser l'accès si plusieurs tâches asynchrones peuvent modifier en même temps,
            // ou si le scheduler y accède en même temps.
            // Pour simplifier, on peut supposer que la commande est la source principale de modification.
            // Le scheduler lit et retire.
            synchronized (activeReminders) {
                activeReminders.put(key, newReminder);
            }
            saveReminders(); // I/O : on est déjà dans une tâche asynchrone, hors du verrou de la map

            Bukkit.getScheduler().runTask(this, () -> {
                sender.sendMessage(message("scheduled", "%id%", reminderId, "%player%", canonicalPlayerName));
            });
        });
        // --- Fin de la section asynchrone ---
    }

    /**
     * /shreminders reload. config.yml et messages.yml sont lus avant tout remplacement : si l'un est illisible,
     * l'ancienne configuration reste active. reminders.yml et la base ne sont pas touchés.
     */
    void reloadFiles(CommandSender sender) {
        YamlConfiguration loadedMessages;
        try {
            ConfigFiles.read(this, "config.yml");
            loadedMessages = ConfigFiles.read(this, "messages.yml");
        } catch (ConfigFiles.ReadError e) {
            getLogger().severe("Reload annulé, " + e.file() + " illisible : " + e.detail());
            sender.sendMessage(message("reload_error", "%file%", e.file(), "%error%", e.detail()));
            return;
        }
        reloadConfig();
        cacheConfiguredReminderIds();
        messages = loadedMessages;
        sender.sendMessage(message("reloaded", "%count%", String.valueOf(configuredReminderIds.size())));

        // Rappels en attente dont l'identifiant n'est plus dans config.yml : le planificateur local les supprime
        int orphans;
        synchronized (activeReminders) {
            orphans = (int) activeReminders.values().stream().filter(rem -> !configuredReminderIds.contains(rem.reminderId)).count();
        }
        if (orphans > 0) {
            sender.sendMessage(message(network == null ? "reload_orphans_local" : "reload_orphans_network", "%count%", String.valueOf(orphans)));
        }
        if (!networkSettings.equals(networkSettingsNow())) {
            sender.sendMessage(message("reload_restart"));
        }
    }

    // Valeurs de la section reseau, lues au démarrage seulement
    private Map<String, Object> networkSettingsNow() {
        ConfigurationSection section = getConfig().getConfigurationSection("reseau");
        return section == null ? Map.of() : section.getValues(true);
    }

    /** Le mode réseau est actif (SHNetwork présent et reseau.actif vrai). */
    NetworkReminders network() {
        return network;
    }

    private void startScheduler() {
        schedulerTask = new BukkitRunnable() {
            @Override
            public void run() {
                long now = System.currentTimeMillis();
                // Itérer sur une copie pour éviter ConcurrentModificationException si on modifie activeReminders ailleurs
                // Cependant, si on utilise l'itérateur et sa méthode remove(), c'est sûr.
                // Mais, comme saveReminders() est appelé dans la boucle, il est plus prudent de synchroniser
                // ou de collecter les éléments à supprimer puis les supprimer en dehors de la boucle d'itération.

                List<String> remindersToRemove = new ArrayList<>();
                List<Reminder> remindersToUpdate = new ArrayList<>(); // Pour saveReminders après la boucle

                synchronized (activeReminders) { // Synchroniser la lecture et la préparation des modifications
                    Iterator<Map.Entry<String, Reminder>> it = activeReminders.entrySet().iterator();
                    while (it.hasNext()) {
                        Map.Entry<String, Reminder> entry = it.next();
                        Reminder rem = entry.getValue();
                        String configPath = "reminders." + rem.reminderId; // Chemin dans config.yml

                        if (!configuredReminderIds.contains(rem.reminderId)) { // Vérifier si le rappel existe toujours dans config.yml
                            getLogger().warning("Le rappel " + rem.reminderId + " pour " + rem.playerName + " n'a plus de configuration. Suppression...");
                            it.remove(); // Supprimer directement avec l'itérateur
                            remindersToUpdate.add(null); // Signale une modification pour saveReminders
                            continue;
                        }

                        // Rappel principal non encore déclenché
                        if (!rem.mainTriggered && now >= rem.mainTriggerTime) {
                            Player player = Bukkit.getPlayer(rem.playerId);
                            boolean isPlayerOnline = player != null && player.isOnline();
                            String playerNameForCommand = rem.playerName; // Utiliser le nom stocké

                            if (isPlayerOnline) { // Utiliser le nom actuel si en ligne
                                playerNameForCommand = player.getName();
                            }

                            // Si playerNameForCommand est null (ancien format de données sans playerName stocké)
                            if (playerNameForCommand == null) {
                                OfflinePlayer offlineP = Bukkit.getOfflinePlayer(rem.playerId); // Fallback, potentiellement risqué
                                if (offlineP != null && offlineP.getName() != null) {
                                    playerNameForCommand = offlineP.getName();
                                    rem.playerName = playerNameForCommand; // Mettre à jour pour la prochaine fois
                                } else {
                                    getLogger().warning("Impossible de déterminer le nom du joueur pour UUID " + rem.playerId + " pour le rappel " + rem.reminderId);
                                    // On pourrait supprimer le rappel ici ou le laisser pour une tentative ultérieure
                                }
                            }


                            String commandToDispatch = getConfig().getString(configPath + ".command", "");
                            if (!commandToDispatch.isEmpty() && playerNameForCommand != null) {
                                commandToDispatch = ChatColor.translateAlternateColorCodes('&', commandToDispatch);
                                commandToDispatch = commandToDispatch.replace("%player%", playerNameForCommand);
                                final String finalCommand = commandToDispatch;
                                // Exécuter la commande sur le thread principal
                                Bukkit.getScheduler().runTask(SHReminders.this, () -> {
                                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), finalCommand);
                                });
                            }

                            if (isPlayerOnline) {
                                triggerMainReminder(player, configPath);
                            }

                            rem.mainTriggered = true;
                            if (rem.repeatEnabled) {
                                rem.nextRepeatTime = now + rem.repeatDelay;
                            } else {
                                it.remove(); // Supprimer si pas de répétition
                            }
                            remindersToUpdate.add(rem); // Signale une modification
                        }
                        // Rappel répétitif
                        else if (rem.mainTriggered && rem.repeatEnabled && now >= rem.nextRepeatTime) {
                            Player player = Bukkit.getPlayer(rem.playerId);
                            if (player != null && player.isOnline()) {
                                triggerRepeatReminder(player, configPath);
                                rem.repeatCount++;
                                if (rem.maxRepeat > 0 && rem.repeatCount >= rem.maxRepeat) {
                                    it.remove(); // Max répétitions atteintes
                                } else {
                                    rem.nextRepeatTime = now + rem.repeatDelay;
                                }
                                remindersToUpdate.add(rem); // Signale une modification
                            } else {
                                // Le joueur n'est pas en ligne lors du rappel répétitif
                                // Option: le supprimer, ou le laisser pour la prochaine fois que le joueur se connecte
                                // Comportement actuel: suppression implicite si on ne fait rien et qu'il n'y a pas de maxRepeat
                                // Pour être explicite et si c'est le comportement voulu:
                                // it.remove();
                                // remindersToUpdate.add(null);
                                // Pour l'instant, on le laisse, il tentera la prochaine fois.
                                // Si vous voulez le supprimer quand le joueur est hors ligne pour un rappel répétitif:
                                // it.remove();
                                // remindersToUpdate.add(null); // Signal pour saveReminders
                            }
                        }
                    }
                } // Fin du synchronized block

                if (!remindersToUpdate.isEmpty()) {
                    // L'écriture du fichier part en asynchrone : le thread principal ne fait pas d'I/O
                    Bukkit.getScheduler().runTaskAsynchronously(SHReminders.this, SHReminders.this::saveReminders);
                }
            }
        };
        // Sur le thread principal (Bukkit.getPlayer, Player#isOnline...) : la boucle ne parcourt que quelques rappels
        schedulerTask.runTaskTimer(this, 20L, 20L); // Exécution toutes les secondes
    }


    void triggerMainReminder(Player player, String configPath) {
        // S'assurer que toutes les interactions API Bukkit (sendTitle, sendMessage, playSound)
        // sont exécutées sur le thread principal.
        Bukkit.getScheduler().runTask(this, () -> {
            String title = getConfig().getString(configPath + ".title", "");
            String subtitle = getConfig().getString(configPath + ".subtitle", "");
            String message = getConfig().getString(configPath + ".message", "");
            String actionbar = getConfig().getString(configPath + ".actionbar", "");
            String soundString = getConfig().getString(configPath + ".sound", "");
            float volume = (float) getConfig().getDouble(configPath + ".soundVolume", 1.0);
            float pitch = (float) getConfig().getDouble(configPath + ".soundPitch", 1.0);

            if (!title.isEmpty() || !subtitle.isEmpty()) { // Vérifier si au moins l'un des deux est non vide
                player.sendTitle(
                    ChatColor.translateAlternateColorCodes('&', title),
                    ChatColor.translateAlternateColorCodes('&', subtitle),
                    10, 70, 20
                );
            }
            if (!message.isEmpty()) {
                player.sendMessage(ChatColor.translateAlternateColorCodes('&', message));
            }
            if (!actionbar.isEmpty()) {
                player.sendActionBar(ChatColor.translateAlternateColorCodes('&', actionbar));
            }
            if (!soundString.isEmpty()) {
                Sound sound = sound(soundString);
                if (sound != null) {
                    player.playSound(player.getLocation(), sound, volume, pitch);
                } else {
                    getLogger().warning("Sound " + soundString + " invalide pour le rappel " + configPath);
                }
            }
        });
    }

    void triggerRepeatReminder(Player player, String configPath) {
        Bukkit.getScheduler().runTask(this, () -> {
            String repTitle = getConfig().getString(configPath + ".repeat.title", "");
            String repSubtitle = getConfig().getString(configPath + ".repeat.subtitle", "");
            String repMessage = getConfig().getString(configPath + ".repeat.message", "");
            String repCommand = getConfig().getString(configPath + ".repeat.command", "");
            String repActionbar = getConfig().getString(configPath + ".repeat.actionbar", "");
            String repSoundString = getConfig().getString(configPath + ".repeat.sound", "");
            float repVolume = (float) getConfig().getDouble(configPath + ".repeat.soundVolume", 1.0);
            float repPitch = (float) getConfig().getDouble(configPath + ".repeat.soundPitch", 1.0);

            if (!repTitle.isEmpty() || !repSubtitle.isEmpty()) {
                player.sendTitle(
                    ChatColor.translateAlternateColorCodes('&', repTitle),
                    ChatColor.translateAlternateColorCodes('&', repSubtitle),
                    10, 70, 20
                );
            }
            if (!repMessage.isEmpty()) {
                player.sendMessage(ChatColor.translateAlternateColorCodes('&', repMessage));
            }
            if (!repCommand.isEmpty()) {
                String finalRepCommand = ChatColor.translateAlternateColorCodes('&', repCommand)
                    .replace("%player%", player.getName());
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), finalRepCommand);
            }
            if (!repActionbar.isEmpty()) {
                player.sendActionBar(ChatColor.translateAlternateColorCodes('&', repActionbar));
            }
            if (!repSoundString.isEmpty()) {
                Sound repSound = sound(repSoundString);
                if (repSound != null) {
                    player.playSound(player.getLocation(), repSound, repVolume, repPitch);
                } else {
                    getLogger().warning("Sound " + repSoundString + " invalide pour le rappel répétitif " + configPath);
                }
            }
        });
    }

    /** Son lu en config : ancien nom (ENTITY_PLAYER_LEVELUP) ou clé (minecraft:entity.player.levelup). */
    static Sound sound(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String s = raw.trim();
        if (s.indexOf(':') >= 0 || s.indexOf('.') >= 0) {
            NamespacedKey key = NamespacedKey.fromString(s.toLowerCase(Locale.ROOT));
            return key == null ? null : Registry.SOUND_EVENT.get(key);
        }
        try {
            return (Sound) Sound.class.getField(s.toUpperCase(Locale.ROOT)).get(null);
        } catch (ReflectiveOperationException | ClassCastException e) {
            return null;
        }
    }

    private String encodeKey(String key) {
        return Base64.getEncoder().encodeToString(key.getBytes(StandardCharsets.UTF_8));
    }

    private String decodeKey(String encodedKey) {
        return new String(Base64.getDecoder().decode(encodedKey), StandardCharsets.UTF_8);
    }

    // Appelée depuis des tâches asynchrones (onCommand, schedulerTask) et depuis onDisable.
    // Ne jamais l'appeler en tenant le verrou de activeReminders (ordre des verrous : fileLock puis activeReminders).
    private void saveReminders() {
        synchronized (fileLock) {
            YamlConfiguration tempConfig = new YamlConfiguration();
            synchronized (activeReminders) { // S'assurer de lire une copie cohérente
                for (Map.Entry<String, Reminder> entry : activeReminders.entrySet()) {
                    String key = entry.getKey(); // "playerUUID:reminderId"
                    String encodedKey = encodeKey(key); // Clé pour le fichier YAML
                    Reminder rem = entry.getValue();
                    ConfigurationSection section = tempConfig.createSection(encodedKey);

                    section.set("playerId", rem.playerId.toString()); // Redondant avec la clé mais bon pour la lisibilité
                    section.set("playerName", rem.playerName);       // Stocker le nom
                    section.set("reminderId", rem.reminderId);       // Redondant aussi

                    section.set("mainTriggerTime", rem.mainTriggerTime);
                    section.set("mainTriggered", rem.mainTriggered);
                    section.set("repeatEnabled", rem.repeatEnabled);
                    section.set("repeatDelay", rem.repeatDelay);
                    section.set("maxRepeat", rem.maxRepeat);
                    section.set("repeatCount", rem.repeatCount);
                    section.set("nextRepeatTime", rem.nextRepeatTime);
                }
            }
            // Écriture dans un fichier temporaire puis remplacement : un arrêt en plein milieu ne tronque pas reminders.yml
            File tmp = new File(remindersFile.getParentFile(), remindersFile.getName() + ".tmp");
            try {
                tempConfig.save(tmp);
                Files.move(tmp.toPath(), remindersFile.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException e) {
                getLogger().severe("Impossible de sauvegarder reminders.yml: " + e.getMessage());
            }
        }
    }

    private void loadReminders() {
        synchronized (activeReminders) {
            activeReminders.clear();
            remindersConfig = YamlConfiguration.loadConfiguration(remindersFile); // Recharger depuis le disque
            for (String encodedKey : remindersConfig.getKeys(false)) {
                ConfigurationSection section = remindersConfig.getConfigurationSection(encodedKey);
                if (section == null) continue;
                try {
                    String decodedKey = decodeKey(encodedKey); // "playerUUID:reminderId"

                    Reminder rem = new Reminder();
                    // Essayer de lire depuis la section d'abord, puis de la clé décodée si absent
                    String playerIdStr = section.getString("playerId");
                    String reminderIdFromFile = section.getString("reminderId");

                    if (playerIdStr != null && reminderIdFromFile != null) {
                        rem.playerId = UUID.fromString(playerIdStr);
                        rem.reminderId = reminderIdFromFile;
                    } else { // Fallback à l'ancien format où c'était seulement dans la clé
                        String[] parts = decodedKey.split(":", 2);
                        if (parts.length != 2) {
                            getLogger().warning("Clé invalide (après décodage) dans reminders.yml : " + decodedKey);
                            continue;
                        }
                        rem.playerId = UUID.fromString(parts[0]);
                        rem.reminderId = parts[1];
                    }

                    rem.playerName = section.getString("playerName"); // Charger le nom

                    rem.mainTriggerTime = section.getLong("mainTriggerTime");
                    rem.mainTriggered = section.getBoolean("mainTriggered");
                    rem.repeatEnabled = section.getBoolean("repeatEnabled");
                    rem.repeatDelay = section.getLong("repeatDelay");
                    rem.maxRepeat = section.getInt("maxRepeat");
                    rem.repeatCount = section.getInt("repeatCount");
                    rem.nextRepeatTime = section.getLong("nextRepeatTime");

                    // Reconstruire la clé standard pour la map activeReminders
                    String standardKey = rem.playerId.toString() + ":" + rem.reminderId;
                    activeReminders.put(standardKey, rem);

                } catch (Exception e) {
                    getLogger().warning("Erreur lors du chargement du reminder (clé encodée: " + encodedKey + "): " + e.getMessage());
                    e.printStackTrace(); // Utile pour le débogage
                }
            }
            getLogger().info(activeReminders.size() + " rappels actifs chargés depuis reminders.yml.");
        }
    }

    // --- MODE RÉSEAU ---

    /**
     * Passe en mode réseau si SHNetwork est installé et que reseau.actif est vrai. Les classes qui touchent à SHNetwork
     * ne sont résolues qu'ici : sans lui, rien n'est chargé et le plugin garde son fonctionnement d'origine. Faux si le
     * mode réseau est demandé mais inutilisable : le plugin ne démarre pas, sinon chaque serveur exécuterait les
     * commandes de ses propres rappels.
     */
    private boolean enableNetwork() {
        if (!getConfig().getBoolean("reseau.actif", true)) return true;
        if (!getServer().getPluginManager().isPluginEnabled("SHNetwork")) return true;
        try {
            network = NetworkReminders.open(this);
            getLogger().info("SHNetwork détecté : rappels en base partagée, chaque rappel déclenché une seule fois pour tout le réseau.");
            return true;
        } catch (RuntimeException | LinkageError e) {
            if (network != null) network.close();
            network = null;
            getLogger().log(Level.SEVERE, "SHNetwork présent mais mode réseau inutilisable, plugin désactivé.", e);
            return false;
        }
    }

    /** messages.yml : un fichier illisible n'empêche pas le démarrage, les textes du jar servent jusqu'au prochain reload. */
    private void loadMessages() {
        try {
            messages = ConfigFiles.read(this, "messages.yml");
        } catch (ConfigFiles.ReadError e) {
            getLogger().severe(e.file() + " illisible, textes du jar utilisés : " + e.detail());
            messages = ConfigFiles.bundled(this, "messages.yml");
        }
    }

    /** Texte de messages.yml avec ses variables (par paires), codes couleur & traduits. */
    String message(String key, String... replacements) {
        // getString(clé) lit le fichier du serveur puis celui du jar ; getString(clé, défaut) ignorerait celui du jar
        String text = messages.getString(key);
        if (text == null) {
            text = "";
        }
        for (int i = 0; i + 1 < replacements.length; i += 2) {
            text = text.replace(replacements[i], replacements[i + 1]);
        }
        return ChatColor.translateAlternateColorCodes('&', text);
    }

    /** Texte du mode réseau (messages.yml), avec les codes couleur &. */
    String networkMessage(String key) {
        return message(key);
    }

    /** Exécute sur le fil principal (appelable de n'importe quel fil). */
    void runMain(Runnable task) {
        Bukkit.getScheduler().runTask(this, task);
    }

    /** Copie en mémoire des rappels : à parcourir et modifier sous son propre verrou. */
    Map<String, Reminder> activeReminders() {
        return activeReminders;
    }

    Set<String> configuredReminderIds() {
        return configuredReminderIds;
    }

    /** Mode réseau : remplace tous les rappels par ceux de la base. */
    void replaceReminders(Map<String, Reminder> fresh) {
        synchronized (activeReminders) {
            activeReminders.clear();
            activeReminders.putAll(fresh);
        }
    }

    /** Mode réseau : ajoute ou remplace un rappel (l'objet n'est plus modifié ensuite). */
    void putReminder(Reminder reminder) {
        synchronized (activeReminders) {
            activeReminders.put(reminder.playerId + ":" + reminder.reminderId, reminder);
        }
    }

    void removeReminder(String key) {
        synchronized (activeReminders) {
            activeReminders.remove(key);
        }
    }

    public List<Reminder> getRemindersForPlayer(UUID playerId) {
        List<Reminder> list = new ArrayList<>();
        synchronized (activeReminders) {
            for (Reminder rem : activeReminders.values()) {
                if (rem.playerId.equals(playerId)) {
                    list.add(rem);
                }
            }
        }
        return list;
    }

    public static class Reminder {
        public UUID playerId;
        public String playerName; // Ajout du nom du joueur
        public String reminderId;
        public long mainTriggerTime;
        public boolean mainTriggered;
        public boolean repeatEnabled;
        public long repeatDelay;
        public int maxRepeat;
        public int repeatCount;
        public long nextRepeatTime;
    }

    // --- PlaceholderAPI Expansion (non modifiée en profondeur mais vérifiée pour la synchro) ---
    public static class SHRemindersPlaceholderExpansion extends PlaceholderExpansion {
        private final SHReminders plugin;

        public SHRemindersPlaceholderExpansion(SHReminders plugin) {
            this.plugin = plugin;
        }

        @Override
        public boolean persist() {
            return true;
        }

        @Override
        public boolean canRegister() {
            return true;
        }

        @Override
        public String getIdentifier() {
            return "shreminders";
        }

        @Override
        public String getAuthor() {
            return plugin.getDescription().getAuthors().isEmpty() ? "Cry_Legende" : String.join(", ", plugin.getDescription().getAuthors());
        }

        @Override
        public String getVersion() {
            return plugin.getDescription().getVersion();
        }

        @Override
        public String onPlaceholderRequest(Player player, String identifier) {
            if (player == null) return "none";

            if (identifier.startsWith("next_")) {
                String placeholderId = identifier.substring("next_".length()).toLowerCase();
                List<Reminder> reminders = plugin.getRemindersForPlayer(player.getUniqueId()); // Déjà thread-safe
                Reminder matched = null;

                for (Reminder rem : reminders) {
                    ConfigurationSection section = plugin.getConfig().getConfigurationSection("reminders." + rem.reminderId);
                    if (section != null) {
                        String confPlaceholderId = section.getString("placeholderId");
                        if (confPlaceholderId != null && confPlaceholderId.equalsIgnoreCase(placeholderId)) {
                            matched = rem;
                            break;
                        } else if (confPlaceholderId == null) {
                            String sanitized = rem.reminderId.replaceAll("[^a-zA-Z0-9]", "").toLowerCase();
                            if (sanitized.equals(placeholderId)) {
                                matched = rem;
                                break;
                            }
                        }
                    }
                }

                if (matched != null) {
                    if (matched.mainTriggered && !matched.repeatEnabled) { // Si principal déclenché ET pas de répétition
                        return plugin.getConfig().getString("placeholderMessages.triggeredNoRepeat", "none");
                    }
                    if (matched.mainTriggered && matched.repeatEnabled && (matched.maxRepeat != -1 && matched.repeatCount >= matched.maxRepeat)) { // Si répétition terminée
                        return plugin.getConfig().getString("placeholderMessages.repeatFinished", "none");
                    }

                    // Toujours compter par rapport au mainTriggerTime (le repeat n'affecte pas le placeholder next)
                    long triggerTime = matched.mainTriggerTime;

                    long now = System.currentTimeMillis();
                    long remaining = triggerTime - now;
                    if (remaining < 0) remaining = 0;

                    if (remaining == 0 && matched.mainTriggered) {
                        return plugin.getConfig().getString("placeholderMessages.mainDoneNoRepeat", "none");
                    }

                    return formatCountdown(remaining, plugin);
                }
            }
            return plugin.getConfig().getString("placeholderMessages.notFound", "none"); // Message par défaut si rien ne correspond
        }

        private String formatCountdown(long remainingMs, SHReminders plugin) {
            long totalSeconds = remainingMs / 1000;
            long days = totalSeconds / 86400;
            long hours = (totalSeconds % 86400) / 3600;
            long minutes = (totalSeconds % 3600) / 60;
            long secs = totalSeconds % 60;

            // Construire dynamiquement : n'affiche que les unités non-nulles
            StringBuilder sb = new StringBuilder();
            if (days > 0) sb.append(days).append("j ");
            if (hours > 0) sb.append(hours).append("h ");
            if (minutes > 0) sb.append(minutes).append("min ");
            if (secs > 0 || sb.length() == 0) sb.append(secs).append("s");

            return sb.toString().trim();
        }
    }
}
