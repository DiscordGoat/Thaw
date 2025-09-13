package goat.thaw.system.enchanting;

import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.ChiseledBookshelf;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Inventory;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

import java.util.*;

/**
 * Custom enchanting table logic requiring chiseled bookshelves.
 * <p>
 * This is a simplified implementation of the feature request and omits
 * visual book animations and the full vanilla enchantment algorithm.
 */
public class EnchantingManager implements Listener {
    private final JavaPlugin plugin;
    private final Map<Location, Session> sessions = new HashMap<>();

    public EnchantingManager(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void start() {
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    private static class Session {
        Player owner;
        ItemStack item;
        ArmorStand display;
        int lapis;
        int booksConsumed;
        boolean enchanting;
        boolean ready;
        List<Block> shelves;
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent e) {
        Block clicked = e.getClickedBlock();
        if (clicked == null || clicked.getType() != Material.ENCHANTING_TABLE) return;
        if (e.getHand() != EquipmentSlot.HAND) return; // only main hand

        Player player = e.getPlayer();
        Location tableLoc = clicked.getLocation();
        Action action = e.getAction();
        Session session = sessions.get(tableLoc);

        if (action == Action.RIGHT_CLICK_BLOCK) {
            ItemStack hand = player.getInventory().getItemInMainHand();
            if (hand.getType() == Material.LAPIS_LAZULI && session != null && session.item != null && !session.enchanting && session.lapis < 3) {
                // Deposit lapis
                session.lapis++;
                hand.setAmount(hand.getAmount() - 1);
                if (hand.getAmount() <= 0) {
                    player.getInventory().setItemInMainHand(null);
                }
                player.sendMessage(ChatColor.AQUA + "Lapis placed: " + session.lapis + "/3");
            } else if (session == null && isEnchantable(hand)) {
                List<Block> shelves = findNearbyShelves(tableLoc);
                if (shelves.isEmpty()) {
                    player.sendMessage(ChatColor.RED + "Requires chiseled bookshelves within 4 blocks.");
                    e.setCancelled(true);
                    return;
                }
                // Start session
                Session s = new Session();
                s.owner = player;
                s.item = hand.clone();
                s.shelves = shelves;
                player.getInventory().setItemInMainHand(null);
                ArmorStand stand = spawnDisplay(tableLoc, s.item);
                s.display = stand;
                sessions.put(tableLoc, s);
                player.sendMessage(ChatColor.YELLOW + "Item placed. Add lapis to enchant.");
            }
            e.setCancelled(true);
        } else if (action == Action.LEFT_CLICK_BLOCK) {
            if (session == null) return;
            if (session.owner != player) {
                player.sendMessage(ChatColor.RED + "Another player is using this table.");
                e.setCancelled(true);
                return;
            }
            if (session.ready) {
                // Give item back
                player.getInventory().addItem(session.item);
                session.display.remove();
                sessions.remove(tableLoc);
                player.sendMessage(ChatColor.GREEN + "Enchanted item acquired.");
            } else if (!session.enchanting) {
                if (session.lapis < 3) {
                    player.sendMessage(ChatColor.RED + "Need 3 lapis to enchant.");
                } else {
                    beginEnchant(session, tableLoc);
                    player.sendMessage(ChatColor.LIGHT_PURPLE + "Enchanting started...");
                }
            }
            e.setCancelled(true);
        }
    }

    private void beginEnchant(Session session, Location tableLoc) {
        session.enchanting = true;
        session.booksConsumed = 0;
        new BukkitRunnable() {
            int ticks = 0;
            @Override
            public void run() {
                if (!session.enchanting || session.display == null || !session.display.isValid()) {
                    cancel();
                    return;
                }
                ticks++;
                session.display.setRotation(session.display.getLocation().getYaw() + 12f, 0f);
                if (ticks % 20 == 0) {
                    consumeRandomBook(session);
                }
                if (ticks >= 15 * 20) {
                    finishEnchant(session, tableLoc);
                    cancel();
                }
            }
        }.runTaskTimer(plugin, 1L, 1L);
    }

    private void finishEnchant(Session session, Location tableLoc) {
        session.enchanting = false;
        session.ready = true;
        session.lapis = 0; // lapis consumed
        applyRandomEnchant(session.item, session.booksConsumed);
        Player p = session.owner;
        p.playSound(tableLoc, Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f);
        session.display.setRotation(0f, 0f);
    }
    private void consumeRandomBook(Session session) {
        Collections.shuffle(session.shelves);
        for (Block shelfBlock : session.shelves) {
            if (!(shelfBlock.getState() instanceof ChiseledBookshelf shelf)) continue;

            Inventory inv = shelf.getInventory();
            List<Integer> occupied = new ArrayList<>();
            for (int i = 0; i < inv.getSize(); i++) {
                if (inv.getItem(i) != null) occupied.add(i);
            }
            if (occupied.isEmpty()) continue;

            int slot = occupied.get(new Random().nextInt(occupied.size()));

            // Remove the book from inventory


            // Update blockdata so the texture changes
            org.bukkit.block.data.type.ChiseledBookshelf data =
                    (org.bukkit.block.data.type.ChiseledBookshelf) shelfBlock.getBlockData();

            data.setSlotOccupied(slot, false);
            shelf.getInventory().getItem(slot).setAmount(0);


            shelfBlock.setBlockData(data, true);
            shelf.update();

            session.booksConsumed++;

            // Spawn book flying animation
            animateBookFlight(shelfBlock, session.display.getLocation(), plugin);
            inv.clear(slot);
            return;
        }
    }
    private void animateBookFlight(Block from, Location to, JavaPlugin plugin) {
        Location start = from.getLocation().add(0.5, 1, 0.5);
        ItemStack bookItem = new ItemStack(Material.BOOK);
        Item itemEntity = from.getWorld().dropItem(start, bookItem);
        itemEntity.setPickupDelay(Integer.MAX_VALUE);
        itemEntity.setGravity(false);

        new BukkitRunnable() {
            int life = 0;
            @Override
            public void run() {
                if (!itemEntity.isValid() || life > 20) {
                    itemEntity.remove();
                    cancel();
                    return;
                }
                Location current = itemEntity.getLocation();
                Vector dir = to.clone().add(0.5, 1.2, 0.5).toVector().subtract(current.toVector()).normalize().multiply(0.25);
                itemEntity.setVelocity(dir);
                life++;
            }
        }.runTaskTimer(plugin, 0L, 1L);
    }


    private void applyRandomEnchant(ItemStack item, int books) {
        List<Enchantment> possible = Arrays.stream(Enchantment.values())
                .filter(e -> e.canEnchantItem(item))
                .toList();
        if (possible.isEmpty()) return;
        Enchantment chosen = possible.get(new Random().nextInt(possible.size()));
        int level = Math.max(1, books / 5);
        level = Math.min(level, chosen.getMaxLevel());
        item.addUnsafeEnchantment(chosen, level);
    }

    private ArmorStand spawnDisplay(Location table, ItemStack item) {
        Location loc = table.clone().add(0.5, -0.4, 0.5);
        ArmorStand stand = (ArmorStand) table.getWorld().spawnEntity(loc, EntityType.ARMOR_STAND);
        stand.setInvisible(true);
        stand.setGravity(false);
        stand.setMarker(true);
        if (stand.getEquipment() != null) {
            stand.getEquipment().setHelmet(item.clone());
        }
        return stand;
    }

    private List<Block> findNearbyShelves(Location table) {
        List<Block> shelves = new ArrayList<>();
        World world = table.getWorld();
        int bx = table.getBlockX();
        int by = table.getBlockY();
        int bz = table.getBlockZ();
        for (int dx = -4; dx <= 4; dx++) {
            for (int dy = -4; dy <= 4; dy++) {
                for (int dz = -4; dz <= 4; dz++) {
                    Block b = world.getBlockAt(bx + dx, by + dy, bz + dz);
                    if (b.getType() == Material.CHISELED_BOOKSHELF) {
                        shelves.add(b);
                    }
                }
            }
        }
        return shelves;
    }

    private boolean isEnchantable(ItemStack item) {
        if (item == null || item.getType() == Material.AIR) return false;
        for (Enchantment e : Enchantment.values()) {
            if (e.canEnchantItem(item)) return true;
        }
        return false;
    }
}
