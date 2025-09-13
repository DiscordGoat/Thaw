package goat.thaw.system.capsule;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Registry for time capsule schematic names.
 * Names should match the .schem file in resources/schematics.
 */
public final class CapsuleRegistry {

    private static final List<String> CAPSULES = new ArrayList<>();
    private static final Random RNG = new Random();

    private CapsuleRegistry() {
        // Utility class
    }

    /**
     * Register a new capsule schematic name.
     *
     * @param name base name of the schematic (without extension)
     */
    public static void register(String name) {
        CAPSULES.add(name);
    }

    /**
     * Returns a random registered capsule schematic name.
     *
     * @return schematic name or null if none registered
     */
    public static String random() {
        if (CAPSULES.isEmpty()) return null;
        return CAPSULES.get(RNG.nextInt(CAPSULES.size()));
    }

    static {
        // default capsule
        register("lilac");
    }
}

