package goat.thaw.system.dev;

import goat.thaw.Thaw;
import goat.thaw.system.capsule.CapsuleLootManager;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;

import java.util.*;
import java.util.stream.Collectors;

public class SimulateLootTableCommand implements CommandExecutor, TabCompleter {

    private final Thaw plugin;

    public SimulateLootTableCommand(Thaw plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage(ChatColor.RED + "Player-only command.");
            return true;
        }

        if (args.length < 2) {
            sender.sendMessage(ChatColor.YELLOW + "Usage: /" + label + " <name> <instances>");
            return true;
        }

        String name = args[0].toUpperCase(Locale.ROOT);
        int instances;
        try {
            instances = Integer.parseInt(args[1]);
        } catch (NumberFormatException e) {
            sender.sendMessage(ChatColor.RED + "Invalid instances: " + args[1]);
            return true;
        }
        if (instances <= 0) {
            sender.sendMessage(ChatColor.RED + "Instances must be > 0");
            return true;
        }

        BungalowLootManager bungalow = plugin.getBungalowLootManager();
        CapsuleLootManager capsule = plugin.getCapsuleLootManager();

        boolean isBungalow = bungalow != null && bungalow.getAvailableTypes().contains(name);
        boolean isCapsule = capsule != null && capsule.getAvailableTypes().contains(name);

        if (!isBungalow && !isCapsule) {
            sender.sendMessage(ChatColor.RED + "Unknown loot table: " + name);
            sender.sendMessage(ChatColor.GRAY + "Bungalow: " + String.join(", ", safeList(bungalow)));
            sender.sendMessage(ChatColor.GRAY + "Capsule:  " + String.join(", ", safeList(capsule)));
            return true;
        }

        Inventory inv = Bukkit.createInventory(p, 27, ChatColor.DARK_AQUA + "Simulate: " + name);
        p.openInventory(inv);

        // First fill immediately
        inv.clear();
        if (isBungalow) bungalow.populateInventory(inv, name);
        else capsule.populateInventory(inv, name);

        // Then repeat every 2s for remaining instances
        final int total = instances;
        final UUID playerId = p.getUniqueId();
        final boolean useBungalow = isBungalow;

        final int[] count = new int[] {1};
        Bukkit.getScheduler().runTaskTimer(plugin, task -> {
            Player pl = Bukkit.getPlayer(playerId);
            if (pl == null || !pl.isOnline()) {
                task.cancel();
                return;
            }
            if (count[0] >= total) {
                task.cancel();
                return;
            }

            inv.clear();
            if (useBungalow) bungalow.populateInventory(inv, name);
            else capsule.populateInventory(inv, name);
            count[0]++;
        }, 20L, 20L);

        sender.sendMessage(ChatColor.GREEN + "Simulating loot table '" + name + "' x" + instances + ".");
        return true;
    }

    private List<String> safeList(BungalowLootManager m) {
        if (m == null) return List.of();
        return m.getAvailableTypes().stream().sorted().collect(Collectors.toList());
    }
    private List<String> safeList(CapsuleLootManager m) {
        if (m == null) return List.of();
        return m.getAvailableTypes().stream().sorted().collect(Collectors.toList());
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            Set<String> names = new HashSet<>();
            if (plugin.getBungalowLootManager() != null) names.addAll(plugin.getBungalowLootManager().getAvailableTypes());
            if (plugin.getCapsuleLootManager() != null) names.addAll(plugin.getCapsuleLootManager().getAvailableTypes());
            String prefix = args[0].toUpperCase(Locale.ROOT);
            return names.stream()
                    .map(String::toUpperCase)
                    .filter(n -> n.startsWith(prefix))
                    .sorted()
                    .collect(Collectors.toList());
        }
        if (args.length == 2) {
            return List.of("1", "5", "10", "20");
        }
        return List.of();
    }
}

