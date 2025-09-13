/*
package goat.thaw.resources.resourcepack;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import goat.thaw.Thaw;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.data.type.EndPortalFrame;
import org.bukkit.boss.BossBar;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityRegainHealthEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.profile.PlayerProfile;
import org.bukkit.profile.PlayerTextures;
import org.bukkit.scheduler.BukkitRunnable;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class DragonFightManager implements Listener {

    private static final String EYE_TEXTURE = "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvMTk4YTQ5Y2E1NGMzZWE2N2E4NmVjOGI5ZjE2YmRmNDZhYTVlZmM1YWVlZmI3YTE5Y2NjYzc5NjJlODIxYTU5OSJ9fX0=";

    private final Map<Location, Integer> portalEyeCounts = new HashMap<>();


    private BossBar dragonBar;





    private Location parseLocationKey(String key) {
        String[] parts = key.split("_");
        if (parts.length != 4) return null;
        World world = Bukkit.getWorld(parts[0]);
        if (world == null) return null;
        int x = Integer.parseInt(parts[1]);
        int y = Integer.parseInt(parts[2]);
        int z = Integer.parseInt(parts[3]);
        return new Location(world, x, y, z);
    }

    private String locationKey(Location loc) {
        return loc.getWorld().getName() + "_" + loc.getBlockX() + "_" + loc.getBlockY() + "_" + loc.getBlockZ();
    }




    @EventHandler
    public void onPlayerPlaceEye(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        if (e.getClickedBlock() == null) return;
        if (e.getClickedBlock().getType() != Material.END_PORTAL_FRAME) return;
        ItemStack item = e.getItem();
        if (item == null || item.getType() != Material.ENDER_EYE) return;
        Player player = e.getPlayer();
        int before = item.getAmount();
        EquipmentSlot slot = e.getHand();
        Location blockLoc = e.getClickedBlock().getLocation();
            ItemStack afterStack = slot == EquipmentSlot.HAND ? player.getInventory().getItemInMainHand() : player.getInventory().getItemInOffHand();
            int after = afterStack != null && afterStack.getType() == Material.ENDER_EYE ? afterStack.getAmount() : 0;
            EndPortalFrame frame = (EndPortalFrame) blockLoc.getBlock().getBlockData();
            boolean placed = before - 1 == after && frame.hasEye();
            if (placed) {
                Location portalLoc = findPortal(blockLoc);
                int count = countPortalEyes(portalLoc);
                portalEyeCounts.put(portalLoc, count);

                Bukkit.broadcastMessage(ChatColor.DARK_PURPLE + "An Eye of Ender was placed! Total: " + count);
                blockLoc.getWorld().playSound(blockLoc, Sound.ENTITY_ENDER_DRAGON_GROWL, 1f, 1f);
                blockLoc.getWorld().spawnParticle(Particle.DRAGON_BREATH, blockLoc.clone().add(0.5, 1, 0.5), 100, 0.3, 0.3, 0.3, 0.01);
                spawnFallingEye(blockLoc, player);
                if (count >= 12 && activeFight == null) {
                    List<Block> frames = getPortalFramesWithEyes(portalLoc);
                    new BukkitRunnable() {
                        int index = 0;
                        @Override
                        public void run() {
                            if (index < frames.size()) {
                                Block b = frames.get(index++);
                                EndPortalFrame data = (EndPortalFrame) b.getBlockData();
                                data.setEye(false);
                                b.setBlockData(data);
                                portalEyeCounts.put(portalLoc, frames.size() - index);
                                if (frames.size() - index == 0) {
                                    deactivatePortal(portalLoc);
                                    portalEyeCounts.remove(portalLoc);
                                    saveData();
                                    cancel();
                                }
                            } else {
                                cancel();
                            }
                        }
                    }.runTaskTimer(plugin, 20L, 20L);
                }
            }
        }, 1L);
    }

    private List<Block> getPortalFramesWithEyes(Location portalLoc) {
        List<Block> frames = new ArrayList<>();
        World world = portalLoc.getWorld();
        if (world == null) return frames;
        int radius = 5;
        for (int x = portalLoc.getBlockX() - radius; x <= portalLoc.getBlockX() + radius; x++) {
            for (int y = portalLoc.getBlockY() - 1; y <= portalLoc.getBlockY() + 1; y++) {
                for (int z = portalLoc.getBlockZ() - radius; z <= portalLoc.getBlockZ() + radius; z++) {
                    Block block = world.getBlockAt(x, y, z);
                    if (block.getType() == Material.END_PORTAL_FRAME) {
                        EndPortalFrame frame = (EndPortalFrame) block.getBlockData();
                        if (frame.hasEye()) {
                            frames.add(block);
                        }
                    }
                }
            }
        }
        return frames;
    }

    private Location findPortal(Location loc) {
        for (Location stored : portalEyeCounts.keySet()) {
            if (stored.getWorld().equals(loc.getWorld()) && stored.distanceSquared(loc) <= 400) {
                return stored;
            }
        }
        return new Location(loc.getWorld(), loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
    }

    private int countPortalEyes(Location portalLoc) {
        World world = portalLoc.getWorld();
        if (world == null) return 0;
        int radius = 5;
        int startX = portalLoc.getBlockX() - radius;
        int endX = portalLoc.getBlockX() + radius;
        int startY = portalLoc.getBlockY() - 1;
        int endY = portalLoc.getBlockY() + 1;
        int startZ = portalLoc.getBlockZ() - radius;
        int endZ = portalLoc.getBlockZ() + radius;
        int count = 0;
        for (int x = startX; x <= endX; x++) {
            for (int y = startY; y <= endY; y++) {
                for (int z = startZ; z <= endZ; z++) {
                    Block block = world.getBlockAt(x, y, z);
                    if (block.getType() == Material.END_PORTAL_FRAME) {
                        EndPortalFrame frame = (EndPortalFrame) block.getBlockData();
                        if (frame.hasEye()) {
                            count++;
                        }
                    }
                }
            }
        }
        return count;
    }

    private void deactivatePortal(Location portalLoc) {
        World world = portalLoc.getWorld();
        if (world == null) return;
        int radius = 5;
        int startX = portalLoc.getBlockX() - radius;
        int endX = portalLoc.getBlockX() + radius;
        int startY = portalLoc.getBlockY() - 1;
        int endY = portalLoc.getBlockY() + 1;
        int startZ = portalLoc.getBlockZ() - radius;
        int endZ = portalLoc.getBlockZ() + radius;
        for (int x = startX; x <= endX; x++) {
            for (int y = startY; y <= endY; y++) {
                for (int z = startZ; z <= endZ; z++) {
                    Block block = world.getBlockAt(x, y, z);
                    if (block.getType() == Material.END_PORTAL) {
                        block.setType(Material.AIR);
                    }
                }
            }
        }
    }



    private void spawnFallingEye(Location frameLoc, Player player) {
        Location start = frameLoc.clone().add(0.5, 1.5, 0.5);
        float yaw = player.getLocation().getYaw() + 180F;
        start.setYaw(yaw);
        ArmorStand stand = (ArmorStand) frameLoc.getWorld().spawnEntity(start, EntityType.ARMOR_STAND);
        stand.setSmall(true);
        stand.setBasePlate(false);
        stand.setGravity(false);
        stand.setVisible(false);
        stand.setRotation(yaw, 0F);
        stand.getEquipment().setHelmet(createEyeSkull());
        new BukkitRunnable() {
            int ticks = 0;
            @Override
            public void run() {
                if (!stand.isValid()) { cancel(); return; }
                stand.getWorld().spawnParticle(Particle.DRAGON_BREATH, stand.getLocation(), 5, 0.1, 0.1, 0.1, 0.01);
                stand.teleport(stand.getLocation().subtract(0, 0.1, 0));
                ticks++;
                if (ticks >= 10) {
                    stand.remove();
                    cancel();
                }
            }
        }.runTaskTimer(Thaw.getInstance(), 0L, 1L);
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

*/
