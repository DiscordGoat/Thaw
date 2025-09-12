package goat.thaw.system.dev;

import goat.thaw.system.effects.EffectManager;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public class ToggleEffectsCommand implements CommandExecutor {
    private final EffectManager effects;

    public ToggleEffectsCommand(EffectManager effects) {
        this.effects = effects;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage("Only players can use this command.");
            return true;
        }
        Player p = (Player) sender;
        boolean enabled = effects.toggle(p.getUniqueId());
        p.sendMessage(enabled ? "Effects enabled." : "Effects disabled.");
        return true;
    }
}
