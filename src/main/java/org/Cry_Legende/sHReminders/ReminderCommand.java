package org.Cry_Legende.sHReminders;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * /shreminders. La forme historique /shreminders <joueur> <id> reste acceptée : un premier argument qui n'est pas
 * un nom de sous-commande est pris pour un pseudo, comme /shreminders schedule <joueur> <id>.
 */
final class ReminderCommand implements TabExecutor {

    private static final String SCHEDULE = "shreminders.admin.schedule";
    private static final String IMPORT = "shreminders.admin.import";
    private static final String RELOAD = "shreminders.admin.reload";
    private static final String LIST = "shreminders.admin.list";

    // Lignes affichées au plus par /shreminders list
    private static final int LIST_LINES = 15;

    private final SHReminders plugin;

    ReminderCommand(SHReminders plugin) {
        this.plugin = plugin;
    }

    /** Les sous-commandes que l'expéditeur a le droit d'utiliser, dans l'ordre d'affichage. */
    private static List<String> allowed(CommandSender sender) {
        List<String> allowed = new ArrayList<>();
        if (sender.hasPermission(SCHEDULE)) allowed.add("schedule");
        if (sender.hasPermission(IMPORT)) allowed.add("import");
        if (sender.hasPermission(RELOAD)) allowed.add("reload");
        if (sender.hasPermission(LIST)) allowed.add("list");
        return allowed;
    }

    private static boolean reserved(String name) {
        return name.equals("schedule") || name.equals("import") || name.equals("reload") || name.equals("list");
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, String @NotNull [] args) {
        List<String> allowed = allowed(sender);
        if (allowed.isEmpty()) {
            sender.sendMessage(plugin.message("no_permission"));
            return true;
        }
        String first = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);

        if (!reserved(first)) {
            // Forme historique : /shreminders <joueur> <id> (l'identifiant peut contenir des espaces)
            if (args.length < 2 || !allowed.contains("schedule")) {
                sender.sendMessage(plugin.message("usage", "%subcommands%", String.join("|", allowed)));
                return true;
            }
            plugin.schedule(sender, args[0], String.join(" ", Arrays.copyOfRange(args, 1, args.length)));
            return true;
        }
        if (!allowed.contains(first)) {
            sender.sendMessage(plugin.message("usage", "%subcommands%", String.join("|", allowed)));
            return true;
        }

        switch (first) {
            case "schedule" -> {
                if (args.length < 3) {
                    sender.sendMessage(plugin.message("schedule_usage"));
                } else {
                    plugin.schedule(sender, args[1], String.join(" ", Arrays.copyOfRange(args, 2, args.length)));
                }
            }
            case "import" -> {
                NetworkReminders network = plugin.network();
                if (network == null) {
                    sender.sendMessage(plugin.message("import_inactive"));
                } else {
                    network.importFile(sender);
                }
            }
            case "reload" -> plugin.reloadFiles(sender);
            default -> list(sender, args);
        }
        return true;
    }

    /** Sans argument : le nombre de rappels en attente par identifiant ; avec un joueur : ses rappels. */
    private void list(CommandSender sender, String[] args) {
        long now = System.currentTimeMillis();
        List<SHReminders.Reminder> reminders;
        Map<String, SHReminders.Reminder> active = plugin.activeReminders();
        synchronized (active) {
            reminders = new ArrayList<>(active.values());
        }

        if (args.length >= 2) {
            String name = args[1];
            List<SHReminders.Reminder> mine = reminders.stream()
                .filter(rem -> rem.playerName != null && rem.playerName.equalsIgnoreCase(name))
                .sorted(Comparator.comparing((SHReminders.Reminder rem) -> rem.reminderId))
                .toList();
            if (mine.isEmpty()) {
                sender.sendMessage(plugin.message("list_player_none", "%player%", name));
                return;
            }
            sender.sendMessage(plugin.message("list_player_header", "%player%", mine.get(0).playerName, "%count%", String.valueOf(mine.size())));
            for (SHReminders.Reminder rem : mine.subList(0, Math.min(LIST_LINES, mine.size()))) {
                String main = rem.mainTriggered ? plugin.message("list_main_done") : plugin.message("list_main_in", "%time%", duration(rem.mainTriggerTime - now));
                String repeats = !rem.repeatEnabled ? plugin.message("list_repeat_none")
                    : plugin.message(rem.maxRepeat > 0 ? "list_repeat_max" : "list_repeat_endless",
                        "%done%", String.valueOf(rem.repeatCount), "%max%", String.valueOf(rem.maxRepeat));
                sender.sendMessage(plugin.message("list_reminder", "%id%", rem.reminderId, "%main%", main, "%repeats%", repeats));
            }
            if (mine.size() > LIST_LINES) {
                sender.sendMessage(plugin.message("list_more", "%more%", String.valueOf(mine.size() - LIST_LINES)));
            }
            return;
        }

        if (reminders.isEmpty()) {
            sender.sendMessage(plugin.message("list_empty"));
            return;
        }
        Map<String, Integer> perId = new TreeMap<>();
        for (SHReminders.Reminder rem : reminders) {
            perId.merge(rem.reminderId, 1, Integer::sum);
        }
        sender.sendMessage(plugin.message("list_summary", "%count%", String.valueOf(reminders.size())));
        int shown = 0;
        for (Map.Entry<String, Integer> entry : perId.entrySet()) {
            if (shown++ >= LIST_LINES) {
                sender.sendMessage(plugin.message("list_more", "%more%", String.valueOf(perId.size() - LIST_LINES)));
                break;
            }
            sender.sendMessage(plugin.message("list_id", "%id%", entry.getKey(), "%count%", String.valueOf(entry.getValue())));
        }
    }

    /** Durée en heures et minutes, ou minutes et secondes sous l'heure. */
    private static String duration(long millis) {
        long seconds = Math.max(0, millis / 1000);
        if (seconds >= 3600) return seconds / 3600 + " h " + (seconds / 60) % 60 + " min";
        return seconds / 60 + " min " + seconds % 60 + " s";
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, String @NotNull [] args) {
        List<String> allowed = allowed(sender);
        List<String> completions = new ArrayList<>();
        String input = args[args.length - 1].toLowerCase(Locale.ROOT);
        String first = args[0].toLowerCase(Locale.ROOT);
        boolean canSchedule = allowed.contains("schedule");

        if (args.length == 1) {
            completions.addAll(allowed);
            if (canSchedule) completions.addAll(playerNames());
        } else if (args.length == 2 && (first.equals("list") && allowed.contains("list") || first.equals("schedule") && canSchedule)) {
            completions.addAll(playerNames());
        } else if (canSchedule && (args.length == 2 && !reserved(first) || args.length == 3 && first.equals("schedule"))) {
            // Identifiants de config.yml (ceux qui contiennent un espace ne se complètent pas : l'espace sépare les arguments)
            plugin.configuredReminderIds().stream().filter(id -> id.indexOf(' ') < 0).forEach(completions::add);
        }
        return completions.stream().filter(name -> name.toLowerCase(Locale.ROOT).startsWith(input)).sorted().distinct().toList();
    }

    private static List<String> playerNames() {
        return Bukkit.getOnlinePlayers().stream().map(Player::getName).toList();
    }
}
