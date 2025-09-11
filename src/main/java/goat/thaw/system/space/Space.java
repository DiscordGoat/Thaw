package goat.thaw.system.space;

import org.bukkit.World;

import java.util.Collections;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public class Space {
    private final UUID id;
    private final String worldName;
    private final Set<BlockPos> blocks; // immutable snapshot
    private final double temperature; // computed internal temp proxy
    private final double totalInfluence; // sum of block influences touching internal air
    private final int airBlocks; // number of air cells in space

    public Space(UUID id, String worldName, Set<BlockPos> blocks) {
        this(id, worldName, blocks, 0.0, 0.0, blocks.size());
    }

    public Space(UUID id, String worldName, Set<BlockPos> blocks, double temperature, double totalInfluence, int airBlocks) {
        this.id = id;
        this.worldName = worldName;
        this.blocks = Collections.unmodifiableSet(new HashSet<>(blocks));
        this.temperature = temperature;
        this.totalInfluence = totalInfluence;
        this.airBlocks = airBlocks;
    }

    public UUID getId() { return id; }
    public String getWorldName() { return worldName; }
    public Set<BlockPos> getBlocks() { return blocks; }
    public double getTemperature() { return temperature; }
    public double getTotalInfluence() { return totalInfluence; }
    public int getAirBlocks() { return airBlocks; }

    public boolean contains(String world, int x, int y, int z) {
        if (!Objects.equals(worldName, world)) return false;
        return blocks.contains(new BlockPos(x, y, z));
    }

    public boolean contains(World world, int x, int y, int z) {
        return contains(world.getName(), x, y, z);
    }

    // 3D integer position
    public static class BlockPos {
        public final int x, y, z;
        public BlockPos(int x, int y, int z) { this.x = x; this.y = y; this.z = z; }
        @Override public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof BlockPos)) return false;
            BlockPos p = (BlockPos) o;
            return x == p.x && y == p.y && z == p.z;
        }
        @Override public int hashCode() { return Objects.hash(x, y, z); }
        @Override public String toString() { return x + "," + y + "," + z; }
        public static BlockPos parse(String s) {
            String[] parts = s.split(",");
            return new BlockPos(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2]));
        }
    }
}
