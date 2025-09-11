package goat.thaw.system.space;

import goat.thaw.system.space.Space.BlockPos;
import goat.thaw.system.space.temperature.TemperatureRegistry;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.IOException;
import java.util.*;

public class SpaceManager {
    private final Plugin plugin;
    private final Map<UUID, Space> spaces = new HashMap<>();
    // Fast lookup index: world|x,y,z -> space id
    private final Map<String, UUID> index = new HashMap<>();

    private File dataFile;

    public SpaceManager(Plugin plugin) {
        this.plugin = plugin;
    }

    public void load() {
        if (!plugin.getDataFolder().exists()) plugin.getDataFolder().mkdirs();
        dataFile = new File(plugin.getDataFolder(), "spaces.yml");
        if (!dataFile.exists()) return;

        FileConfiguration cfg = YamlConfiguration.loadConfiguration(dataFile);
        int count = 0;
        for (String idStr : cfg.getKeys(false)) {
            try {
                UUID id = UUID.fromString(idStr);
                String world = cfg.getString(idStr + ".world");
                List<String> coords = cfg.getStringList(idStr + ".blocks");
                if (world == null || coords == null || coords.isEmpty()) continue;
                Set<BlockPos> set = new HashSet<>();
                for (String s : coords) set.add(BlockPos.parse(s));
                double temp = cfg.getDouble(idStr + ".temperature", 0.0);
                double infl = cfg.getDouble(idStr + ".totalInfluence", 0.0);
                int air = cfg.getInt(idStr + ".airBlocks", set.size());
                Space space = new Space(id, world, set, temp, infl, air);
                register(space);
                count++;
            } catch (Exception ignored) { }
        }
        plugin.getLogger().info("Loaded " + count + " spaces.");
    }

    public void save() {
        if (dataFile == null) dataFile = new File(plugin.getDataFolder(), "spaces.yml");
        YamlConfiguration cfg = new YamlConfiguration();
        for (Space s : spaces.values()) {
            String id = s.getId().toString();
            cfg.set(id + ".world", s.getWorldName());
            List<String> list = new ArrayList<>(s.getBlocks().size());
            for (BlockPos p : s.getBlocks()) list.add(p.toString());
            cfg.set(id + ".blocks", list);
            cfg.set(id + ".temperature", s.getTemperature());
            cfg.set(id + ".totalInfluence", s.getTotalInfluence());
            cfg.set(id + ".airBlocks", s.getAirBlocks());
        }
        try {
            cfg.save(dataFile);
        } catch (IOException e) {
            plugin.getLogger().warning("Failed to save spaces: " + e.getMessage());
        }
    }

    public Collection<Space> getSpaces() { return Collections.unmodifiableCollection(spaces.values()); }

    public Space get(UUID id) { return spaces.get(id); }

    public Optional<Space> findSpaceAt(World world, int x, int y, int z) {
        UUID id = index.get(key(world.getName(), x, y, z));
        if (id == null) return Optional.empty();
        return Optional.ofNullable(spaces.get(id));
    }

    public void deleteSpace(UUID id) {
        Space s = spaces.remove(id);
        if (s == null) return;
        // remove from index
        for (BlockPos p : s.getBlocks()) {
            index.remove(key(s.getWorldName(), p.x, p.y, p.z));
        }
        save();
    }

    public void overwriteSpace(Space updated) {
        // Replace existing and rebuild index entries for this id
        Space prev = spaces.put(updated.getId(), updated);
        if (prev != null) {
            for (BlockPos p : prev.getBlocks()) {
                index.remove(key(prev.getWorldName(), p.x, p.y, p.z));
            }
        }
        for (BlockPos p : updated.getBlocks()) {
            index.put(key(updated.getWorldName(), p.x, p.y, p.z), updated.getId());
        }
        save();
    }

    public Space register(Space s) {
        spaces.put(s.getId(), s);
        for (BlockPos p : s.getBlocks()) {
            index.put(key(s.getWorldName(), p.x, p.y, p.z), s.getId());
        }
        return s;
    }

    public Optional<Space> createFromFloodFill(Player player, int blocksPerTick, FloodFillCallback callback) {
        Block seed = player.getLocation().getBlock().getRelative(0, 1, 0);
        if (!seed.getType().isAir()) {
            player.sendMessage("No air above feet to map space.");
            return Optional.empty();
        }

        World world = player.getWorld();
        final Deque<Block> stack = new ArrayDeque<>();
        final Set<String> visited = new HashSet<>();
        final Set<BlockPos> collected = new HashSet<>();

        pushIfValid(world, stack, visited, seed);

        final int minY = world.getMinHeight();
        final int maxY = world.getMaxHeight();

        // Iterate synchronously but with a scan budget per cycle using scheduler to avoid long ticks.
        // We'll process blocksPerTick per tick similar to the earlier command behavior.
        final int quota = Math.max(1, blocksPerTick);

        Bukkit.getScheduler().runTaskTimer(plugin, task -> {
            int placed = 0;
            int scanBudget = 4096;
            while (placed < quota && scanBudget-- > 0) {
                if (stack.isEmpty()) {
                    task.cancel();
                    // Completed: create and register space
                    UUID id = UUID.randomUUID();
                    // Compute temperature/influence
                    InfluenceResult inf = computeInfluence(world, collected);
                    Space space = new Space(id, world.getName(), collected, inf.temperature, inf.totalInfluence, collected.size());
                    register(space);
                    save();
                    if (callback != null) callback.onComplete(space);
                    return;
                }

                Block b = stack.pop();
                if (b.getWorld() != world) continue;
                int y = b.getY();
                if (y < minY || y >= maxY) continue;

                int highest = world.getHighestBlockYAt(b.getX(), b.getZ());
                if (highest <= y) {
                    task.cancel();
                    if (callback != null) callback.onUnsealed();
                    return;
                }

                if (!b.getType().isAir()) continue;

                // collect
                collected.add(new BlockPos(b.getX(), y, b.getZ()));
                placed++;

                // neighbors
                pushIfValid(world, stack, visited, b.getRelative(1, 0, 0));
                pushIfValid(world, stack, visited, b.getRelative(-1, 0, 0));
                pushIfValid(world, stack, visited, b.getRelative(0, 1, 0));
                pushIfValid(world, stack, visited, b.getRelative(0, -1, 0));
                pushIfValid(world, stack, visited, b.getRelative(0, 0, 1));
                pushIfValid(world, stack, visited, b.getRelative(0, 0, -1));
            }
        }, 0L, 1L);

        return Optional.empty();
    }

    public Optional<Space> createFromFloodFillAt(Player player, Block seed, int blocksPerTick, FloodFillCallback callback) {
        if (seed == null || seed.getWorld() == null) return Optional.empty();
        if (!seed.getType().isAir()) {
            // try neighbors as seed fallback
            Block[] neighbors = new Block[]{
                    seed.getRelative(1,0,0), seed.getRelative(-1,0,0),
                    seed.getRelative(0,1,0), seed.getRelative(0,-1,0),
                    seed.getRelative(0,0,1), seed.getRelative(0,0,-1)
            };
            Block alt = null;
            for (Block n : neighbors) { if (n.getType().isAir()) { alt = n; break; } }
            if (alt == null) {
                player.sendMessage("No nearby air to map space.");
                return Optional.empty();
            }
            seed = alt;
        }

        World world = seed.getWorld();
        final Deque<Block> stack = new ArrayDeque<>();
        final Set<String> visited = new HashSet<>();
        final Set<BlockPos> collected = new HashSet<>();

        pushIfValid(world, stack, visited, seed);

        final int minY = world.getMinHeight();
        final int maxY = world.getMaxHeight();
        final int quota = Math.max(1, blocksPerTick);

        Bukkit.getScheduler().runTaskTimer(plugin, task -> {
            int placed = 0;
            int scanBudget = 4096;
            while (placed < quota && scanBudget-- > 0) {
                if (stack.isEmpty()) {
                    task.cancel();
                    UUID id = UUID.randomUUID();
                    InfluenceResult inf = computeInfluence(world, collected);
                    Space space = new Space(id, world.getName(), collected, inf.temperature, inf.totalInfluence, collected.size());
                    register(space);
                    save();
                    if (callback != null) callback.onComplete(space);
                    return;
                }

                Block b = stack.pop();
                if (b.getWorld() != world) continue;
                int y = b.getY();
                if (y < minY || y >= maxY) continue;

                int highest = world.getHighestBlockYAt(b.getX(), b.getZ());
                if (highest <= y) {
                    task.cancel();
                    if (callback != null) callback.onUnsealed();
                    return;
                }

                if (!b.getType().isAir()) continue;

                collected.add(new BlockPos(b.getX(), y, b.getZ()));
                placed++;

                pushIfValid(world, stack, visited, b.getRelative(1, 0, 0));
                pushIfValid(world, stack, visited, b.getRelative(-1, 0, 0));
                pushIfValid(world, stack, visited, b.getRelative(0, 1, 0));
                pushIfValid(world, stack, visited, b.getRelative(0, -1, 0));
                pushIfValid(world, stack, visited, b.getRelative(0, 0, 1));
                pushIfValid(world, stack, visited, b.getRelative(0, 0, -1));
            }
        }, 0L, 1L);

        return Optional.empty();
    }

    private void pushIfValid(World world, Deque<Block> stack, Set<String> visited, Block n) {
        if (n.getWorld() != world) return;
        int y = n.getY();
        if (y < world.getMinHeight() || y >= world.getMaxHeight()) return;
        String key = key(world.getName(), n.getX(), y, n.getZ());
        if (visited.add(key)) stack.push(n);
    }

    private String key(String world, int x, int y, int z) {
        return world + "|" + x + "," + y + "," + z;
    }

    public interface FloodFillCallback {
        void onComplete(Space space);
        void onUnsealed();
    }

    public static class InfluenceResult {
        public final double totalInfluence;
        public final double temperature;
        public InfluenceResult(double totalInfluence, double temperature) {
            this.totalInfluence = totalInfluence;
            this.temperature = temperature;
        }
    }

    // Compute total influence from neighboring non-air blocks and derive a simple temperature proxy.
    public InfluenceResult computeInfluence(World world, Set<BlockPos> airCells) {
        double total = 0.0;
        int air = airCells.size();
        if (air == 0) return new InfluenceResult(0.0, 0.0);

        for (BlockPos p : airCells) {
            total += neighborInfluence(world, p.x + 1, p.y, p.z);
            total += neighborInfluence(world, p.x - 1, p.y, p.z);
            total += neighborInfluence(world, p.x, p.y + 1, p.z);
            total += neighborInfluence(world, p.x, p.y - 1, p.z);
            total += neighborInfluence(world, p.x, p.y, p.z + 1);
            total += neighborInfluence(world, p.x, p.y, p.z - 1);
        }

        // Influence density scaled exponentially to reduce air resistance as influence rises
        double density = total / Math.max(1, air);
        double k = 0.08; // tuning factor for exponential amplification
        double magnitude = Math.abs(density);
        double amplified = magnitude * Math.exp(k * magnitude);
        double adjusted = Math.copySign(amplified, density);
        double temperature = 65.0 + adjusted;
        return new InfluenceResult(total, temperature);
    }

    private double neighborInfluence(World world, int x, int y, int z) {
        Block b = world.getBlockAt(x, y, z);
        if (b.getType().isAir()) return 0.0;
        return TemperatureRegistry.influence(b);
    }
}
