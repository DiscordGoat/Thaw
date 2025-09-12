package goat.thaw.system.effects;

import goat.thaw.system.stats.StatInstance;
import goat.thaw.system.stats.StatsManager;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.*;

public class EffectManager implements Listener {

    private final JavaPlugin plugin;
    private final StatsManager statsManager;
    private BukkitTask tickTask;
    private BukkitTask saveTask;
    private File effectsDir;

    // Per-player active effects
    private final Map<UUID, Map<EffectId, ActiveEffect>> active = new HashMap<>();

    public EffectManager(JavaPlugin plugin, StatsManager statsManager) {
        this.plugin = plugin;
        this.statsManager = statsManager;
    }

    public void start() {
        if (!plugin.getDataFolder().exists()) plugin.getDataFolder().mkdirs();
        effectsDir = new File(plugin.getDataFolder(), "effects");
        if (!effectsDir.exists()) effectsDir.mkdirs();

        Bukkit.getPluginManager().registerEvents(this, plugin);

        // Load effects for players currently online
        for (Player p : Bukkit.getOnlinePlayers()) loadFromDisk(p.getUniqueId());

        if (tickTask == null) tickTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, 20L); // every second
        if (saveTask == null) saveTask = Bukkit.getScheduler().runTaskTimer(plugin, this::saveAllToDisk, 200L, 200L); // every 10s
    }

    public void stop() {
        if (tickTask != null) { tickTask.cancel(); tickTask = null; }
        if (saveTask != null) { saveTask.cancel(); saveTask = null; }
        saveAllToDisk();
        active.clear();
    }

    // Timed effects API
    public void applyTimedEffect(UUID uuid, EffectId id, int level, int durationSeconds) {
        Map<EffectId, ActiveEffect> map = active.computeIfAbsent(uuid, k -> new EnumMap<>(EffectId.class));
        map.put(id, new ActiveEffect(id, level, durationSeconds, false));
        Player p = Bukkit.getPlayer(uuid);
        if (p != null) applyEffectPotions(p, id, level);
    }

    public void clearEffect(UUID uuid, EffectId id) {
        Map<EffectId, ActiveEffect> map = active.get(uuid);
        if (map == null) return;
        ActiveEffect removed = map.remove(id);
        if (removed != null) {
            Player p = Bukkit.getPlayer(uuid);
            if (p != null) clearEffectPotions(p, id, removed.getLevel());
        }
    }

    // Persistence
    private File fileFor(UUID uuid) { return new File(effectsDir, uuid.toString() + ".yml"); }

    private void saveAllToDisk() {
        for (UUID uuid : new ArrayList<>(active.keySet())) saveToDisk(uuid);
    }

    private void saveToDisk(UUID uuid) {
        Map<EffectId, ActiveEffect> map = active.get(uuid);
        if (map == null) return;
        YamlConfiguration yml = new YamlConfiguration();
        for (Map.Entry<EffectId, ActiveEffect> e : map.entrySet()) {
            ActiveEffect ae = e.getValue();
            // Persist only timed effects with remaining > 0;
            if (!ae.isCircumstantial()) {
                Integer secs = ae.getRemainingSeconds();
                if (secs != null && secs > 0 && ae.getLevel() > 0) {
                    String key = e.getKey().name();
                    yml.set("effects." + key + ".level", ae.getLevel());
                    yml.set("effects." + key + ".remainingSeconds", secs);
                }
            }
        }
        try { yml.save(fileFor(uuid)); } catch (IOException ignore) {}
    }

    private void loadFromDisk(UUID uuid) {
        File f = fileFor(uuid);
        if (!f.exists()) return;
        try {
            YamlConfiguration yml = YamlConfiguration.loadConfiguration(f);
            Map<EffectId, ActiveEffect> map = active.computeIfAbsent(uuid, k -> new EnumMap<>(EffectId.class));
            for (EffectId id : EffectId.values()) {
                int level = yml.getInt("effects." + id.name() + ".level", 0);
                int secs = yml.getInt("effects." + id.name() + ".remainingSeconds", 0);
                if (level > 0 && secs > 0) {
                    map.put(id, new ActiveEffect(id, level, secs, false));
                }
            }
        } catch (Exception ignore) {}
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        loadFromDisk(e.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        saveToDisk(e.getPlayer().getUniqueId());
    }

    // Tick loop: handle circumstantial + timed countdown
    private void tick() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            UUID id = p.getUniqueId();

            // Circumstantial: Hypoxia based on Oxygen
            evaluateHypoxia(p);

            // Timed effects countdown
            Map<EffectId, ActiveEffect> map = active.get(id);
            if (map == null) continue;
            List<EffectId> toRemove = new ArrayList<>();
            for (ActiveEffect ae : map.values()) {
                if (ae.isCircumstantial()) {
                    // Re-apply potions with short duration so they persist smoothly
                    if (ae.getLevel() > 0) applyEffectPotions(p, ae.getId(), ae.getLevel());
                    continue;
                }
                Integer remaining = ae.getRemainingSeconds();
                if (remaining != null) {
                    remaining -= 1;
                    if (remaining <= 0) {
                        toRemove.add(ae.getId());
                    } else {
                        ae.setRemainingSeconds(remaining);
                        if (ae.getLevel() > 0) applyEffectPotions(p, ae.getId(), ae.getLevel());
                    }
                }
            }
            for (EffectId eid : toRemove) {
                ActiveEffect ended = map.remove(eid);
                if (ended != null) clearEffectPotions(p, eid, ended.getLevel());
            }
        }
    }

    private void evaluateHypoxia(Player p) {
        StatInstance oxy = statsManager.get(p.getUniqueId(), "Oxygen");
        if (oxy == null) return;
        double v = oxy.get();
        int desiredLevel;
        if (v > 500.0) {
            desiredLevel = 0; // clear
        } else if (v <= 0.0) {
            desiredLevel = 3;
        } else if (v < 150.0) {
            desiredLevel = 2;
        } else { // v < 500.0
            desiredLevel = 1;
        }
        setCircumstantial(p, EffectId.HYPOXIA, desiredLevel);
    }

    private void setCircumstantial(Player p, EffectId id, int level) {
        Map<EffectId, ActiveEffect> map = active.computeIfAbsent(p.getUniqueId(), k -> new EnumMap<>(EffectId.class));
        ActiveEffect current = map.get(id);
        if (level <= 0) {
            if (current != null) {
                map.remove(id);
                clearEffectPotions(p, id, current.getLevel());
            }
            return;
        }
        if (current == null) {
            current = new ActiveEffect(id, level, null, true);
            map.put(id, current);
        } else {
            current.setLevel(level);
        }
        applyEffectPotions(p, id, level);
    }

    // Map effect + level to actual potion effects applied to the player
    private void applyEffectPotions(Player p, EffectId id, int level) {
        switch (id) {
            case HYPOXIA -> applyHypoxiaPotions(p, level);
        }
    }

    private void clearEffectPotions(Player p, EffectId id, int lastLevel) {
        switch (id) {
            case HYPOXIA -> clearHypoxiaPotions(p);
        }
    }

    // Hypoxia mapping per design:
    // I: Slowness I
    // II: Slowness I + Mining Fatigue I
    // III: Slowness II + Mining Fatigue III + Darkness I
    private void applyHypoxiaPotions(Player p, int level) {
        int durationTicks = 40; // 2s, refreshed each second
        boolean ambient = true;
        boolean particles = false;
        boolean icon = false;

        // Always ensure baseline is cleared before applying stronger where appropriate
        clearHypoxiaPotions(p);

        if (level >= 1) {
            // Slowness I (amplifier 0)
            p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, durationTicks, 0, ambient, particles, icon));
        }
        if (level >= 2) {
            // Mining Fatigue I (amplifier 0)
            p.addPotionEffect(new PotionEffect(PotionEffectType.MINING_FATIGUE, durationTicks, 0, ambient, particles, icon));
        }
        if (level >= 3) {
            // Upgrade Slowness to II and Mining Fatigue to III, and add Darkness I
            p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, durationTicks, 1, ambient, particles, icon));
            p.addPotionEffect(new PotionEffect(PotionEffectType.MINING_FATIGUE, durationTicks, 2, ambient, particles, icon));
            p.addPotionEffect(new PotionEffect(PotionEffectType.DARKNESS, durationTicks, 0, ambient, particles, icon));
        }
    }

    private void clearHypoxiaPotions(Player p) {
        // Conservative clear: remove specific effect types we manage.
        // This may remove other sources; if needed later, introduce tagging via metadata.
        p.removePotionEffect(PotionEffectType.SLOWNESS);
        p.removePotionEffect(PotionEffectType.MINING_FATIGUE);
        p.removePotionEffect(PotionEffectType.DARKNESS);
    }

    // Presentation helpers
    public java.util.List<String> getActiveEffectLabels(UUID uuid) {
        Map<EffectId, ActiveEffect> map = active.get(uuid);
        if (map == null || map.isEmpty()) return java.util.Collections.emptyList();
        java.util.List<String> out = new java.util.ArrayList<>();
        for (Map.Entry<EffectId, ActiveEffect> e : map.entrySet()) {
            ActiveEffect ae = e.getValue();
            if (ae.getLevel() <= 0) continue;
            String name = displayName(e.getKey());
            String roman = roman(ae.getLevel());
            String label = name + (roman.isEmpty() ? "" : " " + roman);
            Integer secs = ae.getRemainingSeconds();
            if (!ae.isCircumstantial() && secs != null && secs > 0) label += " " + secs + "s";
            out.add(label);
        }
        out.sort(String::compareTo);
        return out;
    }

    private String displayName(EffectId id) {
        return switch (id) {
            case HYPOXIA -> "Hypoxia";
        };
    }

    private String roman(int n) {
        return switch (n) {
            case 1 -> "I";
            case 2 -> "II";
            case 3 -> "III";
            case 4 -> "IV";
            case 5 -> "V";
            default -> String.valueOf(n);
        };
    }
}
