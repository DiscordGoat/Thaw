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
            sleds.startSled(p);
            sender.sendMessage(ChatColor.GREEN + "SLED engaged. Look to steer; shift to dismount.");
        }
        return true;
    }
}

