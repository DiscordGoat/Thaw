package goat.thaw.system.dev;

import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.EditSession;
import com.sk89q.worldedit.EditSessionFactory;
import com.sk89q.worldedit.WorldEditException;
import com.sk89q.worldedit.extent.clipboard.Clipboard;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardFormat;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardFormats;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardReader;
import com.sk89q.worldedit.function.operation.Operation;
import com.sk89q.worldedit.function.operation.Operations;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.session.ClipboardHolder;
import com.sk89q.worldedit.bukkit.BukkitAdapter;

import org.bukkit.Location;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.*;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

public class SchematicManager {

    private final JavaPlugin plugin;
    private final Map<String, Clipboard> schematics = new HashMap<>();

    public SchematicManager(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Scan your plugin JAR for any files under /schematics/ ending in .schem or .schematic,
     * then load them all into memory.
     */
    public void loadAll() {
        plugin.getLogger().info("Scanning plugin JAR for schematics…");

        // 1. Locate the plugin JAR on disk via the ProtectionDomain
        File jarFile;
        try {
            URI location = plugin.getClass()
                    .getProtectionDomain()
                    .getCodeSource()
                    .getLocation()
                    .toURI();
            jarFile = new File(location);
        } catch (URISyntaxException e) {
            plugin.getLogger().severe("Failed to find plugin JAR: " + e.getMessage());
            return;
        }

        // 2. Open it and walk every entry
        try (JarFile jar = new JarFile(jarFile)) {
            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                String path = entry.getName();

                // only care about files under schematics/ ending .schem or .schematic
                if (!entry.isDirectory()
                        && path.startsWith("schematics/")
                        && (path.endsWith(".schem") || path.endsWith(".schematic"))
                ) {
                    // derive the base name ("schematics/foo.schem" → "foo")
                    String fileName = path.substring(path.lastIndexOf('/') + 1);
                    String name     = fileName.substring(0, fileName.lastIndexOf('.'))
                            .toLowerCase(Locale.ROOT);

                    // load it
                    loadOne(path, name);
                }
            }
        } catch (IOException ex) {
            plugin.getLogger().severe("Error reading plugin JAR for schematics: " + ex.getMessage());
        }

        plugin.getLogger().info("Loaded schematics: " + schematics.keySet());
    }


    /**
     * Load one schematic from inside the JAR at the given resource path.
     * @param resourcePath e.g. "schematics/bar.schem"
     * @param key          the map key (no extension, lowercase) e.g. "bar"
     */
    private void loadOne(String resourcePath, String key) {
        try (InputStream is = plugin.getResource(resourcePath)) {
            if (is == null) {
                plugin.getLogger().severe("Missing schematic resource: " + resourcePath);
                return;
            }

            ClipboardFormat fmt = ClipboardFormats.findByFile(new File(resourcePath));
            if (fmt == null) {
                plugin.getLogger().severe("Unknown format for schematic: " + resourcePath);
                return;
            }

            try (ClipboardReader reader = fmt.getReader(is)) {
                Clipboard clip = reader.read();
                schematics.put(key, clip);
                plugin.getLogger().info(" • Loaded '" + key + "' as " + fmt.getName());
            }

        } catch (IOException ex) {
            plugin.getLogger().severe("Failed to load schematic '" + resourcePath + "': " + ex.getMessage());
        }
    }

    /**
     * Paste a loaded schematic at the given Bukkit Location.
     * @return true if pasted, false if name not found or paste error
     */
    public boolean spawnSchematic(String name, Location loc) {
        Clipboard clip = schematics.get(name.toLowerCase(Locale.ROOT));
        if (clip == null) return false;

        var weWorld = BukkitAdapter.adapt(loc.getWorld());
        EditSessionFactory factory = WorldEdit.getInstance().getEditSessionFactory();

        try (EditSession session = factory.getEditSession(weWorld, -1)) {
            Operation op = new ClipboardHolder(clip)
                    .createPaste(session)
                    .to(BlockVector3.at(loc.getBlockX(),
                            loc.getBlockY(),
                            loc.getBlockZ()))
                    .ignoreAirBlocks(true)
                    .build();
            Operations.complete(op);
            return true;
        } catch (WorldEditException ex) {
            plugin.getLogger().severe("Failed to paste schematic '" + name + "': " + ex.getMessage());
            return false;
        }
    }

    /** @return the set of all loaded schematic keys (no extension) */
    public Set<String> getAvailableSchematics() {
        return Collections.unmodifiableSet(schematics.keySet());
    }
}
