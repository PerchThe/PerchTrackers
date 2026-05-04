package me.perch;

import me.perch.util.ColorUtil;
import me.perch.util.ItemUtil;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.StringUtil;

import java.util.*;

public class TrackerCommand implements CommandExecutor, TabCompleter {

    private final Trackers plugin;

    public TrackerCommand(Trackers plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {

        if (!sender.hasPermission("perchtrackers.admin")) return true;

        if (args.length < 1) return false;
        String prefix = plugin.getConfigManager().getPrefix();

        if (args[0].equalsIgnoreCase("give")) {
            if (args.length < 3) {
                sender.sendMessage(ColorUtil.colorize("&cUsage: /trackers give <player> <filename_id|remover>"));
                return true;
            }

            Player target = Bukkit.getPlayer(args[1]);
            String id = args[2];

            if (target == null) {
                sender.sendMessage(ColorUtil.colorize("&cPlayer not found."));
                return true;
            }

            if (id.equalsIgnoreCase("remover") || id.equalsIgnoreCase("tracker_remover")) {
                target.getInventory().addItem(plugin.getTrackerManager().getTrackerRemoverItem());
                sender.sendMessage(prefix + plugin.getConfigManager().getMessage("tracker_remover_given")
                        .replace("%player%", target.getName()));
                return true;
            }

            ItemStack trackerItem = plugin.getTrackerManager().getTrackerItem(id);
            if (trackerItem == null) {
                sender.sendMessage(plugin.getConfigManager().getMessage("invalid_tracker"));
                return true;
            }

            target.getInventory().addItem(trackerItem);
            sender.sendMessage(prefix + plugin.getConfigManager().getMessage("tracker_given")
                    .replace("%id%", id)
                    .replace("%player%", target.getName()));
            return true;
        }

        if (args[0].equalsIgnoreCase("remove")) {
            if (!(sender instanceof Player)) return true;
            Player player = (Player) sender;
            ItemStack hand = player.getInventory().getItemInMainHand();

            if (hand.getType().isAir()) {
                player.sendMessage(prefix + plugin.getConfigManager().getMessage("no_item"));
                return true;
            }

            boolean removed = false;
            Set<String> trackerIds = new HashSet<>(plugin.getTrackerManager().getTrackerIds());

            for (String id : trackerIds) {
                if (ItemUtil.hasTracker(hand, id)) {
                    player.getInventory().addItem(plugin.getTrackerManager().getTrackerItem(id));
                    YamlConfiguration config = plugin.getTrackerManager().getTrackerConfig(id);
                    String format = config.getString("lore-format");
                    ItemUtil.removeTracker(hand, id, format);
                    removed = true;
                }
            }

            if (removed) {
                player.sendMessage(prefix + plugin.getConfigManager().getMessage("tracker_removed"));
            } else {
                player.sendMessage(prefix + plugin.getConfigManager().getMessage("no_tracker_on_item"));
            }
            return true;
        }

        if (args[0].equalsIgnoreCase("reload")) {
            plugin.reloadConfig();
            plugin.getTrackerManager().loadTrackers();
            plugin.getTrackerManager().loadTrackerRemover();
            sender.sendMessage(prefix + plugin.getConfigManager().getMessage("reload"));
            return true;
        }

        return false;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission("perchtrackers.admin")) return Collections.emptyList();

        List<String> completions = new ArrayList<>();

        if (args.length == 1) {
            List<String> subcommands = Arrays.asList("give", "remove", "reload");
            StringUtil.copyPartialMatches(args[0], subcommands, completions);
            Collections.sort(completions);
            return completions;
        }

        if (args.length == 2 && args[0].equalsIgnoreCase("give")) {
            return null;
        }

        if (args.length == 3 && args[0].equalsIgnoreCase("give")) {
            Set<String> ids = new HashSet<>(plugin.getTrackerManager().getTrackerIds());
            ids.add("remover");

            StringUtil.copyPartialMatches(args[2], ids, completions);
            Collections.sort(completions);
            return completions;
        }

        return Collections.emptyList();
    }
}