package goat.thaw.system.stats;

import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

public class StatsCommand implements CommandExecutor, TabCompleter {

    private final StatsManager stats;

    public StatsCommand(StatsManager stats) {
        this.stats = stats;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage(ChatColor.RED + "Player-only command.");
            return true;
        }
        if (args.length < 2) {
            sender.sendMessage(ChatColor.YELLOW + "Usage: /" + label + " <get|set|add|subtract> <stat> [amount]");
            return true;
        }
        String action = args[0].toLowerCase(Locale.ROOT);
        String statName = args[1];

        switch (action) {
            case "get": {
                var inst = stats.get(p.getUniqueId(), statName);
                if (inst == null) {
                    sender.sendMessage(ChatColor.RED + "Unknown stat: " + statName);
                    return true;
                }
                sender.sendMessage(ChatColor.GREEN + inst.getDefinition().getName() + ": " + inst.get());
                return true;
            }
            case "set":
            case "add":
            case "subtract": {
                if (args.length < 3) {
                    sender.sendMessage(ChatColor.YELLOW + "Amount required for " + action);
                    return true;
                }
                double amt;
                try { amt = Double.parseDouble(args[2]); }
                catch (NumberFormatException e) {
                    sender.sendMessage(ChatColor.RED + "Invalid amount: " + args[2]);
                    return true;
                }
                boolean ok;
                if (action.equals("set")) ok = stats.set(p.getUniqueId(), statName, amt);
                else if (action.equals("add")) ok = stats.add(p.getUniqueId(), statName, amt);
                else ok = stats.subtract(p.getUniqueId(), statName, amt);
                if (!ok) {
                    sender.sendMessage(ChatColor.RED + "Unknown stat: " + statName);
                    return true;
                }
                var inst = stats.get(p.getUniqueId(), statName);
                sender.sendMessage(ChatColor.AQUA + action.toUpperCase(Locale.ROOT) + " " + inst.getDefinition().getName() + " => " + inst.get());
                return true;
            }
            default:
                sender.sendMessage(ChatColor.YELLOW + "Usage: /" + label + " <get|set|add|subtract> <stat> [amount]");
                return true;
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> empty = new ArrayList<>();
        if (args.length == 1) {
            return List.of("get", "set", "add", "subtract")
                    .stream().filter(s -> s.startsWith(args[0].toLowerCase(Locale.ROOT))).collect(Collectors.toList());
        }
        if (args.length == 2) {
            return stats.getDefinedNames().stream()
                    .map(n -> n)
                    .filter(n -> n.startsWith(args[1].toLowerCase(Locale.ROOT)))
                    .collect(Collectors.toList());
        }
        return empty;
    }
}

