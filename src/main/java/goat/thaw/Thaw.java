package goat.thaw;

import goat.thaw.dev.DecomissionCommand;
import goat.thaw.dev.GenerateArcticCommand;
import goat.thaw.dev.RegenerateArcticCommand;
import goat.thaw.dev.WarpToCommand;
import goat.thaw.dev.TeleportToPeakCommand;
import goat.thaw.resourcepack.ResourcePackListener;
import org.bukkit.plugin.java.JavaPlugin;

public final class Thaw extends JavaPlugin {

    @Override
    public void onEnable() {
        // getServer().getPluginManager().registerEvents(new ResourcePackListener(), this);

        // Dev commands
        if (getCommand("generatearctic") != null) {
            getCommand("generatearctic").setExecutor(new GenerateArcticCommand());
        }
        if (getCommand("warpto") != null) {
            getCommand("warpto").setExecutor(new WarpToCommand());
        }
        if (getCommand("decomission") != null) {
            getCommand("decomission").setExecutor(new DecomissionCommand());
        }
        if (getCommand("regeneratearctic") != null) {
            getCommand("regeneratearctic").setExecutor(new RegenerateArcticCommand());
        }
        if (getCommand("teleporttopeak") != null) {
            getCommand("teleporttopeak").setExecutor(new TeleportToPeakCommand());
        }
    }

    @Override
    public void onDisable() {
        // No-op for now
    }
}
