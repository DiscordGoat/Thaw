package goat.thaw;

import goat.thaw.resourcepack.ResourcePackListener;
import org.bukkit.plugin.java.JavaPlugin;

public final class Thaw extends JavaPlugin {

    @Override
    public void onEnable() {
        getServer().getPluginManager().registerEvents(new ResourcePackListener(), this);
    }

    @Override
    public void onDisable() {
        // No-op for now
    }
}
