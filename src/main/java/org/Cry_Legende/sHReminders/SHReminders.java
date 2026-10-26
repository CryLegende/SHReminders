package org.Cry_Legende.sHReminders;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.Base64;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.NamespacedKey;
import org.bukkit.OfflinePlayer;
import org.bukkit.Registry;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;

public class SHReminders extends JavaPlugin implements CommandExecutor {

    private File remindersFile;
    private YamlConfiguration remindersConfig;
    private final Map<String, Reminder> activeReminders = new HashMap<>();
    private BukkitRunnable schedulerTask;
    // Une seule écriture de reminders.yml à la fois (l'instantané est pris sous ce verrou)
    private final Object fileLock = new Object();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        getCommand("shreminders").setExecutor(this);

        remindersFile = new File(getDataFolder(), "reminders.yml");
        if (!remindersFile.exists()) {
            try {
                remindersFile.getParentFile().mkdirs();
                remindersFile.createNewFile();
            } catch (IOException e) {
                getLogger().severe("Impossible de créer le fichier reminders.yml: " + e.getMessage());
            }
        }
        remindersConfig = YamlConfiguration.loadConfiguration(remindersFile);
        loadReminders();
        startScheduler();

        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null) {
            new SHRemindersPlaceholderExpansion(this).register();
            getLogger().info("PlaceholderAPI trouvée : les placeholders SHReminders ont été enregistrés.");
        }
    }

    @Override
    public void onDisable() {
        if (schedulerTask != null) {
            schedulerTask.cancel();
        }
        saveReminders(); // Sauvegarder à la désactivation est une bonne pratique
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("shreminders.admin")) {
            sender.sendMessage(ChatColor.RED + "Vous n'avez pas la permission.");
            return true;
        }
        if (args.length < 2) {
            sender.sendMessage(ChatColor.RED + "Usage: /shreminders <joueur> <id>");
            return true;
        }

        final String playerNameInput = args[0];
        final String reminderId = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
        final String configPath = "reminders." + reminderId;

        if (!getConfig().contains(configPath)) {
            sender.sendMessage(ChatColor.RED + "Aucun rappel trouvé dans la configuration pour l'id '" + reminderId + "'");
            return true;
        }

        // --- Début de la section asynchrone ---
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            OfflinePlayer offlinePlayer = Bukkit.getOfflinePlayer(playerNameInput);

            if (offlinePlayer == null || offlinePlayer.getUniqueId() == null || offlinePlayer.getName() == null) {
                Bukkit.getScheduler().runTask(this, () -> {
                    sender.sendMessage(ChatColor.RED + "Le joueur '" + playerNameInput + "' n'a pas été trouvé ou n'a jamais joué sur ce serveur.");
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
                sender.sendMessage(ChatColor.GREEN + "Rappel '" + reminderId + "' programmé pour " + canonicalPlayerName + ".");
            });
        });
        // --- Fin de la section asynchrone ---

        return true;
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

                        if (!getConfig().isConfigurationSection(configPath)) { // Vérifier si le rappel existe toujours dans config.yml
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


    private void triggerMainReminder(Player player, String configPath) {
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

    private void triggerRepeatReminder(Player player, String configPath) {
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
