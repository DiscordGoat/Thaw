package goat.thaw.system.logging;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.plugin.Plugin;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

public class DiceLogger implements Listener {
    private final Plugin plugin;
    private final Set<UUID> enabled = new HashSet<>();
    private final File logFile;

    public DiceLogger(Plugin plugin) {
        this.plugin = plugin;
        if (!plugin.getDataFolder().exists()) plugin.getDataFolder().mkdirs();
        File dir = new File(plugin.getDataFolder(), "logs");
        if (!dir.exists()) dir.mkdirs();
        String ts = new SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date());
        this.logFile = new File(dir, "dice-" + ts + ".log");
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        logLine("=== DICE logging started: " + ts + " ===");
    }

    public boolean isEnabled(UUID id) { return enabled.contains(id); }

    public void toggle(UUID id) {
        if (enabled.contains(id)) enabled.remove(id); else enabled.add(id);
        logLine("toggle " + id + " -> " + (enabled.contains(id) ? "on" : "off"));
    }

    public synchronized void log(UUID id, String line) {
        String who = id.toString();
        logLine("[" + who + "] " + line);
    }

    @EventHandler
    public void onChat(AsyncPlayerChatEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        if (!enabled.contains(id)) return;
        log(id, "CHAT: " + e.getMessage());
    }

    private synchronized void logLine(String line) {
        try (BufferedWriter out = new BufferedWriter(new FileWriter(logFile, true))) {
            String ts = new SimpleDateFormat("HH:mm:ss").format(new Date());
            out.write("[" + ts + "] " + line);
            out.newLine();
        } catch (IOException ignored) {}
    }
}

