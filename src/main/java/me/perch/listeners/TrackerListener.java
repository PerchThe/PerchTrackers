package me.perch.listeners;

import com.destroystokyo.paper.event.player.PlayerJumpEvent;
import com.vexsoftware.votifier.model.VotifierEvent;
import io.papermc.paper.event.player.PlayerTradeEvent;
import me.perch.Trackers;
import me.perch.util.ItemUtil;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.data.Ageable;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.*;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.*;
import org.bukkit.inventory.CraftingInventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Recipe;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.*;

public class TrackerListener implements Listener {

    private final Trackers plugin;
    private final Map<String, List<String>> eventTrackerCache = new HashMap<>();
    private final Set<Material> monitoredMaterials = new HashSet<>();
    private boolean wildCardMaterialActive = false;

    public TrackerListener(Trackers plugin) {
        this.plugin = plugin;
        refreshTrackerCache();
    }

    public void refreshTrackerCache() {
        eventTrackerCache.clear();
        monitoredMaterials.clear();
        wildCardMaterialActive = false;

        for (String id : plugin.getTrackerManager().getTrackerIds()) {
            YamlConfiguration config = plugin.getTrackerManager().getTrackerConfig(id);
            if (config == null) continue;

            String type = config.getString("type");
            if (type != null) {
                eventTrackerCache.computeIfAbsent(type.toUpperCase(), k -> new ArrayList<>()).add(id);
            }

            List<String> allowedItems = config.getStringList("allowed-items");
            if (allowedItems.contains("*")) {
                wildCardMaterialActive = true;
            } else {
                for (String matName : allowedItems) {
                    try {
                        if (matName.endsWith("*")) {
                            String prefix = matName.replace("*", "");
                            for (Material m : Material.values()) {
                                if (m.name().startsWith(prefix)) monitoredMaterials.add(m);
                            }
                        } else if (matName.startsWith("*")) {
                            String suffix = matName.replace("*", "");
                            for (Material m : Material.values()) {
                                if (m.name().endsWith(suffix)) monitoredMaterials.add(m);
                            }
                        } else {
                            monitoredMaterials.add(Material.valueOf(matName.toUpperCase()));
                        }
                    } catch (IllegalArgumentException ignored) {
                        plugin.getLogger().warning("Could not parse material: " + matName + ". Enabling safety mode.");
                        wildCardMaterialActive = true;
                    }
                }
            }
        }
    }

    private boolean isAllowed(String candidate, List<String> allowedList) {
        if (allowedList == null || allowedList.isEmpty()) return true;
        for (int i = 0; i < allowedList.size(); i++) {
            String allowed = allowedList.get(i);
            if (allowed.equals("*")) return true;
            if (allowed.startsWith("*")) {
                if (candidate.endsWith(allowed.substring(1))) return true;
            } else if (allowed.endsWith("*")) {
                if (candidate.startsWith(allowed.substring(0, allowed.length() - 1))) return true;
            } else {
                if (candidate.equalsIgnoreCase(allowed)) return true;
            }
        }
        return false;
    }

    private void checkTrackers(Player player, String eventType, String targetName, int amount, Block blockContext) {
        List<String> applicableTrackerIds = eventTrackerCache.get(eventType);
        if (applicableTrackerIds == null || applicableTrackerIds.isEmpty()) return;

        checkSingleItem(player.getInventory().getItemInMainHand(), applicableTrackerIds, targetName, amount, blockContext);
        checkSingleItem(player.getInventory().getItemInOffHand(), applicableTrackerIds, targetName, amount, blockContext);
        for (ItemStack armor : player.getInventory().getArmorContents()) {
            checkSingleItem(armor, applicableTrackerIds, targetName, amount, blockContext);
        }
    }

    private void checkSingleItem(ItemStack item, List<String> possibleIds, String targetName, int amount, Block blockContext) {
        if (item == null || item.getType() == Material.AIR) return;

        if (!wildCardMaterialActive && !monitoredMaterials.contains(item.getType())) {
            return;
        }

        if (!item.hasItemMeta()) return;

        for (String id : possibleIds) {
            if (!ItemUtil.hasTracker(item, id)) continue;

            YamlConfiguration config = plugin.getTrackerManager().getTrackerConfig(id);

            if (!isAllowed(item.getType().name(), config.getStringList("allowed-items"))) continue;
            if (targetName != null && !isAllowed(targetName, config.getStringList("allowed-targets"))) continue;

            if (blockContext != null && config.getBoolean("only-fully-grown", false)) {
                Material type = blockContext.getType();
                boolean isVerticalCrop = type == Material.SUGAR_CANE || type == Material.CACTUS ||
                        type == Material.BAMBOO || type == Material.KELP ||
                        type == Material.KELP_PLANT;

                if (!isVerticalCrop && blockContext.getBlockData() instanceof Ageable) {
                    Ageable ageable = (Ageable) blockContext.getBlockData();
                    if (ageable.getAge() != ageable.getMaximumAge()) continue;
                }
            }

            ItemUtil.increaseTracker(item, id, amount);
            ItemUtil.updateItemLore(item, id, config.getString("lore-format"));
        }
    }

    private static final Map<Material, Integer> MAX_HEIGHTS = Map.of(
            Material.SUGAR_CANE, 3,
            Material.CACTUS, 3,
            Material.BAMBOO, 16,
            Material.KELP, 26,
            Material.KELP_PLANT, 26
    );

    private int countBrokenFrom(Block start) {
        Material type = start.getType();

        // fallback
        int maxHeight = MAX_HEIGHTS.getOrDefault(type, Integer.MAX_VALUE);

        // only top block
        if (start.getRelative(0, 1, 0).getType() != type) {
            return 1;
        }

        int count = 1;
        Block up = start.getRelative(0, 1, 0);

        while (up.getType() == type && count < maxHeight) {
            count++;
            up = up.getRelative(0, 1, 0);
        }

        return count;
    }

    private int getMaxCrafts(CraftingInventory inv, Recipe recipe) {
        ItemStack[] matrix = inv.getMatrix();

        int max = Integer.MAX_VALUE;

        for (ItemStack item : matrix) {
            if (item == null || item.getType().isAir()) continue;
            max = Math.min(max, item.getAmount());
        }

        return max == Integer.MAX_VALUE ? 1 : max;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInventoryClick(InventoryClickEvent e) {
        if (e.getCursor() == null || e.getCurrentItem() == null) return;
        if (e.getCursor().getType() == Material.AIR || e.getCurrentItem().getType() == Material.AIR) return;

        ItemStack cursor = e.getCursor();
        ItemStack current = e.getCurrentItem();

        if (!(e.getWhoClicked() instanceof Player)) return;
        Player player = (Player) e.getWhoClicked();

        if (!cursor.hasItemMeta()) return;

        if (plugin.getTrackerManager().isTrackerRemover(cursor)) {
            e.setCancelled(true);

            boolean removedAny = removeAllTrackersFromItem(current, player);

            if (!removedAny) {
                player.sendMessage(plugin.getConfigManager().getPrefix() +
                        plugin.getConfigManager().getMessage("no_trackers_to_remove"));
                return;
            }

            cursor.setAmount(cursor.getAmount() - 1);
            player.sendMessage(plugin.getConfigManager().getPrefix() +
                    plugin.getConfigManager().getMessage("tracker_remover_used"));
            playApplicationEffects(player);
            return;
        }

        if (!cursor.getItemMeta().getPersistentDataContainer().has(plugin.getTrackerManager().TRACKER_ID_KEY, PersistentDataType.STRING)) {
            return;
        }

        String trackerId = cursor.getItemMeta().getPersistentDataContainer().get(plugin.getTrackerManager().TRACKER_ID_KEY, PersistentDataType.STRING);
        YamlConfiguration config = plugin.getTrackerManager().getTrackerConfig(trackerId);

        if (config == null) {
            player.sendMessage(plugin.getConfigManager().getMessage("invalid_tracker"));
            return;
        }

        if (!isAllowed(current.getType().name(), config.getStringList("allowed-items"))) {
            player.sendMessage(plugin.getConfigManager().getPrefix() +
                    plugin.getConfigManager().getMessage("invalid_item_type"));
            return;
        }

        if (ItemUtil.hasTracker(current, trackerId)) {
            player.sendMessage(plugin.getConfigManager().getPrefix() +
                    plugin.getConfigManager().getMessage("tracker_already_applied"));
            return;
        }

        ItemMeta toolMeta = current.getItemMeta();
        toolMeta.getPersistentDataContainer().set(new org.bukkit.NamespacedKey(plugin, "tracker_stat_" + trackerId), PersistentDataType.INTEGER, 0);
        current.setItemMeta(toolMeta);

        ItemUtil.updateItemLore(current, trackerId, config.getString("lore-format"));

        e.setCancelled(true);
        cursor.setAmount(cursor.getAmount() - 1);
        player.sendMessage(plugin.getConfigManager().getPrefix() +
                plugin.getConfigManager().getMessage("tracker_applied"));

        playApplicationEffects(player);
    }

    private boolean removeAllTrackersFromItem(ItemStack item, Player player) {
        boolean removed = false;

        for (String id : new HashSet<>(plugin.getTrackerManager().getTrackerIds())) {
            if (!ItemUtil.hasTracker(item, id)) continue;

            YamlConfiguration config = plugin.getTrackerManager().getTrackerConfig(id);
            if (config == null) continue;

            ItemStack returnedTracker = plugin.getTrackerManager().getTrackerItem(id);
            if (returnedTracker != null) {
                HashMap<Integer, ItemStack> leftovers = player.getInventory().addItem(returnedTracker);
                leftovers.values().forEach(leftover ->
                        player.getWorld().dropItemNaturally(player.getLocation(), leftover)
                );
            }

            ItemUtil.removeTracker(item, id, config.getString("lore-format"));
            removed = true;
        }

        return removed;
    }

    private void playApplicationEffects(Player player) {
        if (plugin.getConfig().getBoolean("application-effects.sound.enabled", true)) {
            try {
                String soundName = plugin.getConfig().getString("application-effects.sound.type", "ENTITY_PLAYER_LEVELUP");
                float volume = (float) plugin.getConfig().getDouble("application-effects.sound.volume", 1.0);
                float pitch = (float) plugin.getConfig().getDouble("application-effects.sound.pitch", 1.0);
                player.playSound(player.getLocation(), org.bukkit.Sound.valueOf(soundName.toUpperCase()), volume, pitch);
            } catch (Exception ignored) {}
        }

        if (plugin.getConfig().getBoolean("application-effects.particle.enabled", true)) {
            try {
                String particleName = plugin.getConfig().getString("application-effects.particle.type", "HAPPY_VILLAGER");
                int count = plugin.getConfig().getInt("application-effects.particle.count", 15);
                player.spawnParticle(org.bukkit.Particle.valueOf(particleName.toUpperCase()), player.getLocation().add(0, 1, 0), count, 0.5, 0.5, 0.5, 0);
            } catch (Exception ignored) {}
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBowShoot(EntityShootBowEvent e) {
        if (e.getEntity() instanceof Player) {
            checkTrackers((Player) e.getEntity(), "SHOOT_BOW", null, 1, null);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSneakMove(PlayerMoveEvent e) {
        Location from = e.getFrom();
        Location to = e.getTo();

        if (to == null || (from.getBlockX() == to.getBlockX() && from.getBlockY() == to.getBlockY() && from.getBlockZ() == to.getBlockZ())) {
            return;
        }

        if (e.getPlayer().isSneaking()) {
            checkTrackers(e.getPlayer(), "MOVE", null, 1, null);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHeadshotAttempt(EntityDamageByEntityEvent e) {
        if (!(e.getDamager() instanceof org.bukkit.entity.Projectile)) return;
        org.bukkit.entity.Projectile projectile = (org.bukkit.entity.Projectile) e.getDamager();

        if (!(projectile.getShooter() instanceof Player)) return;
        Player shooter = (Player) projectile.getShooter();

        if (!(e.getEntity() instanceof org.bukkit.entity.LivingEntity)) return;
        org.bukkit.entity.LivingEntity victim = (org.bukkit.entity.LivingEntity) e.getEntity();

        double projectileY = projectile.getLocation().getY();
        double eyeY = victim.getEyeLocation().getY();

        if (projectileY > (eyeY - 0.25) && projectileY < (eyeY + 0.5)) {
            checkTrackers(shooter, "HEADSHOT", victim.getType().name(), 1, null);
        }
    }

    @EventHandler
    public void onVote(VotifierEvent e) {
        org.bukkit.entity.Player player = org.bukkit.Bukkit.getPlayer(e.getVote().getUsername());
        if (player != null && player.isOnline()) {
            checkTrackers(player, "VOTE", null, 1, null);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent e) {
        if (e.getPlayer().getGameMode() == GameMode.CREATIVE) return;

        Block block = e.getBlock();
        Material type = block.getType();

        int amount = 1;

        if (MAX_HEIGHTS.containsKey(type)) {
            amount = countBrokenFrom(block);
        }

        checkTrackers(e.getPlayer(), "BLOCK_BREAK", type.name(), amount, block);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInteractBlock(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        if (e.getClickedBlock() == null) return;

        Material type = e.getClickedBlock().getType();

        if (type == Material.SWEET_BERRY_BUSH || type == Material.CAVE_VINES || type == Material.CAVE_VINES_PLANT) {
            checkTrackers(e.getPlayer(), "BLOCK_BREAK", type.name(), 1, e.getClickedBlock());
        }

        else if (type == Material.CAKE || type.name().endsWith("CANDLE_CAKE")) {

            Player player = e.getPlayer();
            Block block = e.getClickedBlock();

            if (!(block.getBlockData() instanceof org.bukkit.block.data.type.Cake cake)) return;

            int bitesBefore = cake.getBites();

            Bukkit.getScheduler().runTask(plugin, () -> {

                if (!(block.getBlockData() instanceof org.bukkit.block.data.type.Cake newCake)) {
                    checkTrackers(player, "ITEM_CONSUME", "CAKE", 1, block);
                    return;
                }

                int bitesAfter = newCake.getBites();
                int eaten = bitesAfter - bitesBefore;

                if (eaten > 0) {
                    checkTrackers(player, "ITEM_CONSUME", "CAKE", eaten, block);
                }

            });
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntityDeath(EntityDeathEvent e) {
        if (e.getEntity().getKiller() == null) return;
        Player killer = e.getEntity().getKiller();
        String type = (e.getEntity() instanceof Player) ? "PLAYER_KILL" : "MOB_KILL";
        checkTrackers(killer, type, e.getEntity().getType().name(), 1, null);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamageDealt(EntityDamageByEntityEvent e) {
        if (!(e.getDamager() instanceof Player)) return;
        if (e.getFinalDamage() <= 0) return;

        Player player = (Player) e.getDamager();
        int damage = (int) Math.round(e.getFinalDamage());
        checkTrackers(player, "DAMAGE_DEALT", e.getEntity().getType().name(), damage, null);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onJump(PlayerJumpEvent e) {
        checkTrackers(e.getPlayer(), "JUMP", null, 1, null);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTrade(PlayerTradeEvent e) {
        checkTrackers(e.getPlayer(), "VILLAGER_TRADE", null, 1, null);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent e) {
        if (e.getPlayer().getGameMode() != GameMode.CREATIVE)
            checkTrackers(e.getPlayer(), "BLOCK_PLACE", e.getBlock().getType().name(), 1, null);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onProjectileHit(ProjectileHitEvent e) {
        if (e.getHitEntity() == null && e.getHitBlock() == null) return;
        if (e.getEntity().getShooter() instanceof Player) {
            Player p = (Player) e.getEntity().getShooter();
            checkTrackers(p, "PROJECTILE_HIT", null, 1, null);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFish(PlayerFishEvent e) {
        if (e.getState() == PlayerFishEvent.State.CAUGHT_FISH)
            checkTrackers(e.getPlayer(), "FISH_CAUGHT", null, 1, null);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onConsume(PlayerItemConsumeEvent e) {
        checkTrackers(e.getPlayer(), "ITEM_CONSUME", e.getItem().getType().name(), 1, null);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onExpChange(PlayerExpChangeEvent e) {
        if (e.getAmount() > 0)
            checkTrackers(e.getPlayer(), "XP_COLLECTED", null, e.getAmount(), null);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFallDamage(EntityDamageEvent e) {
        if (!(e.getEntity() instanceof Player)) return;
        if (e.getCause() != EntityDamageEvent.DamageCause.FALL) return;
        if (e.getFinalDamage() <= 0) return;

        Player player = (Player) e.getEntity();
        int damage = (int) Math.round(e.getFinalDamage());
        checkTrackers(player, "FALL_DAMAGE", null, damage, null);
    }

    @EventHandler
    public void onCraft(CraftItemEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;

        ItemStack result = event.getRecipe().getResult();

        String targetName = result.getType().name();

        int perCraftAmount = result.getAmount();

        CraftingInventory inv = event.getInventory();

        int crafts = 1;

        if (event.isShiftClick()) {
            crafts = getMaxCrafts(inv, event.getRecipe());
        }

        int totalAmount = perCraftAmount * crafts;

        checkTrackers(player, "CRAFT_ITEM", targetName, totalAmount, null);
    }
}