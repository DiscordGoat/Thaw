package goat.thaw.subsystems.eyespy;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.profile.PlayerProfile;
import org.bukkit.profile.PlayerTextures;
import org.bukkit.scheduler.BukkitTask;

import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class EyeSpyManager implements Listener {
    private static final double EYE_Y = 300.0;
    private static final String EYE_TEXTURE = "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvMTk4YTQ5Y2E1NGMzZWE2N2E4NmVjOGI5ZjE2YmRmNDZhYTVlZmM1YWVlZmI3YTE5Y2NjYzc5NjJlODIxYTU5OSJ9fX0=";

    private final JavaPlugin plugin;
    private final Map<UUID, ArmorStand> eyes = new HashMap<>();
    private BukkitTask tickTask;

    public EyeSpyManager(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void start() {
        Bukkit.getPluginManager().registerEvents(this, plugin);
        tickTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, 20L);
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (isNight(p.getWorld())) {
                eyes.put(p.getUniqueId(), spawnEye(p));
            }
        }
    }

    public void stop() {
        if (tickTask != null) {
            tickTask.cancel();
            tickTask = null;
        }
        for (ArmorStand stand : eyes.values()) {
            if (stand != null) {
                stand.remove();
            }
        }
        eyes.clear();
    }

    private void tick() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            ArmorStand stand = eyes.get(p.getUniqueId());
            if (isNight(p.getWorld())) {
                if (stand == null || !stand.isValid()) {
                    eyes.put(p.getUniqueId(), spawnEye(p));
                }
            } else {
                if (stand != null) {
                    stand.remove();
                    eyes.remove(p.getUniqueId());
                }
            }
        }
    }

    private boolean isNight(World world) {
        long time = world.getTime();
        return time >= 12000 && time < 24000;
    }

    private ArmorStand spawnEye(Player p) {
        Location loc = p.getLocation().clone();
        loc.setY(EYE_Y);
        loc.setPitch(90f);
        ArmorStand stand = (ArmorStand) p.getWorld().spawnEntity(loc, EntityType.ARMOR_STAND);
        stand.setInvisible(true);
        stand.setBasePlate(false);
        stand.setGravity(false);
        ItemStack head = createEyeSkull();
        if (stand.getEquipment() != null) {
            stand.getEquipment().setHelmet(head);
        }
        return stand;
    }

    @EventHandler
    public void onMove(PlayerMoveEvent e) {
        Player p = e.getPlayer();
        ArmorStand stand = eyes.get(p.getUniqueId());
        if (stand == null) return;
        if (e.getFrom().getX() == e.getTo().getX() && e.getFrom().getY() == e.getTo().getY() && e.getFrom().getZ() == e.getTo().getZ()) return;
        Location to = e.getTo().clone();
        to.setY(EYE_Y);
        to.setPitch(90f);
        stand.teleport(to);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        ArmorStand stand = eyes.remove(e.getPlayer().getUniqueId());
        if (stand != null) {
            stand.remove();
        }
    }

    @EventHandler
    public void onDamage(EntityDamageByEntityEvent e) {
        Entity entity = e.getEntity();
        if (!(entity instanceof ArmorStand stand)) return;
        UUID owner = null;
        for (Map.Entry<UUID, ArmorStand> entry : eyes.entrySet()) {
            if (entry.getValue().getUniqueId().equals(stand.getUniqueId())) {
                owner = entry.getKey();
                break;
            }
        }
        if (owner == null) return;
        Player damager = null;
        if (e.getDamager() instanceof Player) {
            damager = (Player) e.getDamager();
        } else if (e.getDamager() instanceof Projectile proj && proj.getShooter() instanceof Player) {
            damager = (Player) proj.getShooter();
        }
        if (damager == null) return;
        e.setCancelled(true);
        World w = damager.getWorld();
        w.playSound(damager.getLocation(), Sound.BLOCK_END_PORTAL_SPAWN, 1f, 1f);
        w.dropItemNaturally(damager.getLocation(), new ItemStack(Material.ENDER_EYE));
        stand.remove();
        eyes.remove(owner);
    }

    private ItemStack createEyeSkull() {
        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) head.getItemMeta();
        setCustomSkullTexture(meta, EYE_TEXTURE);
        head.setItemMeta(meta);
        return head;
    }

    private SkullMeta setCustomSkullTexture(SkullMeta skullMeta, String base64Json) {
        if (skullMeta == null || base64Json == null || base64Json.isEmpty()) {
            return skullMeta;
        }
        try {
            byte[] decoded = Base64.getDecoder().decode(base64Json);
            String json = new String(decoded, StandardCharsets.UTF_8);
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            String urlText = root.getAsJsonObject("textures").getAsJsonObject("SKIN").get("url").getAsString();
            PlayerProfile profile = Bukkit.createPlayerProfile(UUID.randomUUID());
            PlayerTextures textures = profile.getTextures();
            textures.setSkin(new URL(urlText), PlayerTextures.SkinModel.CLASSIC);
            profile.setTextures(textures);
            skullMeta.setOwnerProfile(profile);
        } catch (Exception ex) {
            ex.printStackTrace();
        }
        return skullMeta;
    }
}

