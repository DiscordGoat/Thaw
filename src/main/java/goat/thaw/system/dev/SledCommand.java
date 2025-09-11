package goat.thaw.system.dev;

import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public class SledCommand implements CommandExecutor {

    private final SledManager sleds;

    public SledCommand(SledManager sleds) { this.sleds = sleds; }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage(ChatColor.RED + "Player-only command.");
            return true;
        }
        if (sleds.hasSession(p)) {
            sleds.stopSled(p);
            sender.sendMessage(ChatColor.YELLOW + "SLED disabled.");
        } else {
            int dogCount = 2;
            if (args.length >= 1) {
                try { dogCount = Integer.parseInt(args[0]); } catch (NumberFormatException ignore) {}
            }
            if (dogCount < 1 || dogCount > 2) {
                sender.sendMessage(ChatColor.RED + "Usage: /" + label + " <dogCount 1-2>");
                return true;
            }
            sleds.startSled(p, dogCount);
            sender.sendMessage(ChatColor.GREEN + "SLED engaged with " + dogCount + " dog(s). Look to steer; shift to dismount.");
        }
        return true;
    }
}
