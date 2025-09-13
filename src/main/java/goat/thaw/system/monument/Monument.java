package goat.thaw.system.monument;

import org.bukkit.Location;

import java.util.List;
import java.util.UUID;

/**
 * Immutable representation of a monument loaded from monument.yml.
 */
public class Monument {
    private final String id;
    private final MonumentType type;
    private final Location center;
    private final Location base;
    private final List<int[]> stationOffsets;
    private final Location sign;

    private boolean completed;
    private UUID completedBy;
    private long completedAt;

    public Monument(String id, MonumentType type, Location center, Location base,
                    List<int[]> stationOffsets, Location sign,
                    boolean completed, UUID completedBy, long completedAt) {
        this.id = id;
        this.type = type;
        this.center = center;
        this.base = base;
        this.stationOffsets = stationOffsets;
        this.sign = sign;
        this.completed = completed;
        this.completedBy = completedBy;
        this.completedAt = completedAt;
    }

    public String getId() {
        return id;
    }

    public MonumentType getType() {
        return type;
    }

    public Location getCenter() {
        return center;
    }

    public Location getBase() {
        return base;
    }

    public List<int[]> getStationOffsets() {
        return stationOffsets;
    }

    public Location getSign() {
        return sign;
    }

    public boolean isCompleted() {
        return completed;
    }

    public UUID getCompletedBy() {
        return completedBy;
    }

    public long getCompletedAt() {
        return completedAt;
    }

    public void setCompleted(boolean completed) {
        this.completed = completed;
    }

    public void setCompletedBy(UUID completedBy) {
        this.completedBy = completedBy;
    }

    public void setCompletedAt(long completedAt) {
        this.completedAt = completedAt;
    }
}
