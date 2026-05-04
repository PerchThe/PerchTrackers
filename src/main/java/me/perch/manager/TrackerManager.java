package me.perch.manager;

import me.perch.Trackers;
import me.perch.util.ColorUtil;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.io.File;
import java.util.*;

public class TrackerManager {

    private final Trackers plugin;
    private final Map<String, YamlConfiguration> loadedTrackers = new HashMap<>();
    private YamlConfiguration trackerRemoverConfig;

    public final NamespacedKey TRACKER_ID_KEY;
    public final NamespacedKey TRACKER_REMOVER_KEY;

    public TrackerManager(Trackers plugin) {
        this.plugin = plugin;
        this.TRACKER_ID_KEY = new NamespacedKey(plugin, "tracker_id");
        this.TRACKER_REMOVER_KEY = new NamespacedKey(plugin, "tracker_remover");

        createDefaults();
        loadTrackers();
        loadTrackerRemover();
    }

    private void createDefaults() {
        String[] defaultFiles = {
                "ancient_debris.yml",
                "arrows_shot.yml",
                "blocks_broken.yml",
                "blocks_placed.yml",
                "bosses_killed.yml",
                "crops_farmed.yml",
                "damage_dealt.yml",
                "distance_sneaked.yml",
                "endermen_killed.yml",
                "fish_caught.yml",
                "food_eaten.yml",
                "headshots.yml",
                "jumps.yml",
                "logs_chopped.yml",
                "mob_kills.yml",
                "players_killed.yml",
                "fall_damage.yml",
                "pumpkins_farmed.yml",
                "tnt_crafted.yml",
                "villager_trades.yml",
                "votes.yml",
                "xp_collected.yml"
        };

        File trackerDir = new File(plugin.getDataFolder(), "trackers");
        if (!trackerDir.exists()) {
            trackerDir.mkdirs();
        }

        for (String fileName : defaultFiles) {
            File file = new File(trackerDir, fileName);
            if (!file.exists()) {
                try {
                    plugin.saveResource("trackers/" + fileName, false);
                } catch (IllegalArgumentException e) {
                    plugin.getLogger().warning("Could not find default file in JAR: trackers/" + fileName);
                }
            }
        }

        File removerFile = new File(plugin.getDataFolder(), "tracker_remover.yml");
        if (!removerFile.exists()) {
            try {
                plugin.saveResource("tracker_remover.yml", false);
            } catch (IllegalArgumentException e) {
                plugin.getLogger().warning("Could not find default file in JAR: tracker_remover.yml");
            }
        }
    }

    public void loadTrackers() {
        loadedTrackers.clear();

        File folder = new File(plugin.getDataFolder(), "trackers");
        if (!folder.exists()) folder.mkdirs();

        File[] files = folder.listFiles();
        if (files == null) return;

        for (File file : files) {
            if (file.getName().endsWith(".yml")) {
                String id = file.getName().replace(".yml", "");
                loadedTrackers.put(id, YamlConfiguration.loadConfiguration(file));
                plugin.getLogger().info("Loaded tracker module: " + id);
            }
        }
    }

    public void loadTrackerRemover() {
        File file = new File(plugin.getDataFolder(), "tracker_remover.yml");
        trackerRemoverConfig = YamlConfiguration.loadConfiguration(file);
        plugin.getLogger().info("Loaded tracker remover item.");
    }

    public YamlConfiguration getTrackerConfig(String id) {
        return loadedTrackers.get(id);
    }

    public Set<String> getTrackerIds() {
        return loadedTrackers.keySet();
    }

    public ItemStack getTrackerItem(String id) {
        if (!loadedTrackers.containsKey(id)) return null;

        YamlConfiguration config = loadedTrackers.get(id);

        Material mat = Material.getMaterial(config.getString("item", "NETHER_STAR").toUpperCase());
        ItemStack item = new ItemStack(mat != null ? mat : Material.NETHER_STAR);
        ItemMeta meta = item.getItemMeta();

        meta.setDisplayName(ColorUtil.colorize(config.getString("name")));
        meta.setLore(ColorUtil.colorize(config.getStringList("description")));
        meta.getPersistentDataContainer().set(TRACKER_ID_KEY, PersistentDataType.STRING, id);

        item.setItemMeta(meta);
        return item;
    }

    public ItemStack getTrackerRemoverItem() {
        Material mat = Material.getMaterial(trackerRemoverConfig.getString("item", "SHEARS").toUpperCase());

        ItemStack item = new ItemStack(mat != null ? mat : Material.SHEARS);
        ItemMeta meta = item.getItemMeta();

        meta.setDisplayName(ColorUtil.colorize(trackerRemoverConfig.getString("name", "&cTracker Remover")));
        meta.setLore(ColorUtil.colorize(trackerRemoverConfig.getStringList("description")));
        meta.getPersistentDataContainer().set(TRACKER_REMOVER_KEY, PersistentDataType.BYTE, (byte) 1);

        item.setItemMeta(meta);
        return item;
    }

    public boolean isTrackerRemover(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return false;

        return item.getItemMeta()
                .getPersistentDataContainer()
                .has(TRACKER_REMOVER_KEY, PersistentDataType.BYTE);
    }
}