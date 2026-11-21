package kr.nightbeam.deathrules;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class DeathRulesPlugin extends JavaPlugin implements Listener {
    private final Map<UUID, Long> combatUntil = new ConcurrentHashMap<>();
    private final Map<UUID, Long> pendingEffects = new ConcurrentHashMap<>();
    private File pendingEffectsFile;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadPendingEffects();
        Bukkit.getPluginManager().registerEvents(this, this);
        getLogger().info("DeathRules enabled.");
    }

    @Override
    public void onDisable() {
        savePendingEffects();
        combatUntil.clear();
        pendingEffects.clear();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerDamage(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player victim)) return;
        Player attacker = resolvePlayer(event.getDamager());
        if (attacker == null || attacker.equals(victim)) return;

        long until = System.currentTimeMillis()
                + getConfig().getLong("combat-tag-seconds", 15) * 1000L;
        combatUntil.put(victim.getUniqueId(), until);
        combatUntil.put(attacker.getUniqueId(), until);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        // Keep hotbar, armor, and offhand; the handler manually drops slots 9-35.
        event.setKeepInventory(true);
        applyDeathInventoryRules(player, event.getDrops());
        pendingEffects.put(player.getUniqueId(), getConfig().getLong("respawn-effects-seconds", 120));
        savePendingEffects();
        combatUntil.remove(player.getUniqueId());
    }

    /**
     * Keep hotbar (0-8), armor and offhand. Drop only the main inventory
     * storage slots (9-35). Clearing event drops prevents duplicate drops.
     */
    private void applyDeathInventoryRules(Player player, java.util.List<ItemStack> eventDrops) {
        PlayerInventory inventory = player.getInventory();
        eventDrops.clear();

        Location dropLocation = player.getLocation();
        for (int slot = 9; slot <= 35; slot++) {
            ItemStack item = inventory.getItem(slot);
            if (item != null && !item.getType().isAir()) {
                player.getWorld().dropItemNaturally(dropLocation, item.clone());
                inventory.setItem(slot, null);
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();
        Long seconds = pendingEffects.remove(playerId);
        if (seconds == null) return;
        savePendingEffects();
        if (seconds <= 0) return;

        int durationTicks = (int) Math.min(Integer.MAX_VALUE, seconds * 20L);
        int amplifier = Math.max(0, getConfig().getInt("respawn-effects-amplifier", 0));
        Bukkit.getScheduler().runTaskLater(this, () -> {
            Player player = Bukkit.getPlayer(playerId);
            if (player == null || !player.isOnline() || player.isDead()) return;
            player.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, durationTicks, amplifier, false, true, true));
            player.addPotionEffect(new PotionEffect(PotionEffectType.WEAKNESS, durationTicks, amplifier, false, true, true));
            player.addPotionEffect(new PotionEffect(PotionEffectType.MINING_FATIGUE, durationTicks, amplifier, false, true, true));
        }, 1L);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        UUID playerId = player.getUniqueId();
        Long until = combatUntil.remove(playerId);
        if (until == null || until <= System.currentTimeMillis()) return;

        // Player#setHealth(0) does not reliably execute the full damage/death pipeline.
        // Apply lethal damage first so Bukkit/Paper fires PlayerDeathEvent normally.
        Location quitLocation = player.getLocation().clone();
        if (!player.isDead() && player.getHealth() > 0.0) {
            player.damage(1_000_000.0);
        }

        // If another plugin cancelled/intercepted lethal damage, still enforce our
        // inventory rule exactly once before the player leaves.
        if (!player.isDead()) {
            player.setHealth(0.0);
            dropRemainingInventory(player, quitLocation);
            pendingEffects.put(playerId, getConfig().getLong("respawn-effects-seconds", 120));
            savePendingEffects();
        }
    }

    private void dropRemainingInventory(Player player, Location location) {
        PlayerInventory inventory = player.getInventory();
        for (int slot = 9; slot <= 35; slot++) {
            ItemStack item = inventory.getItem(slot);
            if (item != null && !item.getType().isAir()) {
                location.getWorld().dropItemNaturally(location, item.clone());
                inventory.setItem(slot, null);
            }
        }
    }

    private void loadPendingEffects() {
        pendingEffectsFile = new File(getDataFolder(), "pending-effects.yml");
        if (!pendingEffectsFile.exists()) return;
        YamlConfiguration data = YamlConfiguration.loadConfiguration(pendingEffectsFile);
        for (String key : data.getKeys(false)) {
            try {
                pendingEffects.put(UUID.fromString(key), data.getLong(key));
            } catch (IllegalArgumentException ex) {
                getLogger().warning("Ignoring invalid UUID in pending-effects.yml: " + key);
            }
        }
    }

    private void savePendingEffects() {
        if (pendingEffectsFile == null) return;
        YamlConfiguration data = new YamlConfiguration();
        for (Map.Entry<UUID, Long> entry : pendingEffects.entrySet()) {
            data.set(entry.getKey().toString(), entry.getValue());
        }
        try {
            if (!getDataFolder().exists() && !getDataFolder().mkdirs()) {
                getLogger().warning("Could not create plugin data folder; pending effects were not saved.");
                return;
            }
            data.save(pendingEffectsFile);
        } catch (IOException ex) {
            getLogger().severe("Could not save pending effects: " + ex.getMessage());
        }
    }

    private Player resolvePlayer(org.bukkit.entity.Entity entity) {
        if (entity instanceof Player player) return player;
        if (entity instanceof Projectile projectile
                && projectile.getShooter() instanceof Player player) return player;
        return null;
    }
}
