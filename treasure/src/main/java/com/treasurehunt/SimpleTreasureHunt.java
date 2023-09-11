package com.treasurehunt;

import org.bukkit.*;
import org.bukkit.block.Chest;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BossBar;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Skeleton;
import org.bukkit.entity.Wither;
import org.bukkit.entity.Zombie;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.loot.LootTable;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.enchantments.Enchantment;
import java.io.File;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.event.block.Action;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;
import java.util.*;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Creeper;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.event.entity.EntityDeathEvent;

public class SimpleTreasureHunt extends JavaPlugin implements Listener {

    private List<Location> treasureLocations = new ArrayList<>();
    private Map<Location, List<Guardian>> guardianChestMap = new HashMap<>();
    private BossBar minibossBar;
    private String worldName;
    private int itemsPerChest;
    private int chestDuration;
    private boolean useWorldBorder;
    private List<int[]> coordinateBounds;
    private int spawnRadius;
    private Map<TreasureTier, Map<Material, AbstractMap.SimpleEntry<Integer, Integer>>> lootTables;
    private Map<TreasureTier, Integer> guardiansPerTier;
    private Map<Location, List<ArmorStand>> chestHolograms = new HashMap<>();
    private Map<TreasureTier, Double> treasureChances = new HashMap<>();
    private FileConfiguration config = YamlConfiguration.loadConfiguration(new File(getDataFolder(), "treasurehuntconfig.yml"));

    private static class ChestData {
        long activationTime;
        TreasureTier tier;

        ChestData(long activationTime, TreasureTier tier) {
            this.activationTime = activationTime;
            this.tier = tier;
        }
    }

    private static class Guardian {
        private final Entity guardianEntity;

        public Guardian(Entity guardianEntity, Location chestLocation) {
            this.guardianEntity = guardianEntity;
        }
    
        public boolean isAlive() {
            return !guardianEntity.isDead();
        }

        public void remove() {
            guardianEntity.remove();
        }

        public Location getLocation() {
            return guardianEntity.getLocation();
        }
        
        public void teleport(Location location) {
            guardianEntity.teleport(location);
        }

    }    

    private Map<Location, ChestData> chestActivationTimes = new HashMap<>();

    private enum TreasureTier {
        BASIC, INTERMEDIATE, ADVANCED, LEGENDARY
    }

    private List<ArmorStand> spawnHologram(Location location, String... lines) {
        List<ArmorStand> armorStands = new ArrayList<>();
        double heightOffset = 0.5; // Adjusted the initial height to 2.25 above the chest
        for (String line : lines) {
            ArmorStand armorStand = (ArmorStand) location.getWorld().spawnEntity(location.add(0, heightOffset, 0), EntityType.ARMOR_STAND);
            armorStand.setGravity(false);
            armorStand.setVisible(false);
            armorStand.setCustomNameVisible(true);
            armorStand.setCustomName(line);
            armorStands.add(armorStand);
            heightOffset -= 0.25;  // Reduce the offset for subsequent lines
        }
        return armorStands;
    }    

    @Override
    public void onEnable() {
        this.getLogger().info("SimpleTreasureHunt has been enabled! :)");
        this.getCommand("starttreasurehunt").setExecutor(this);
        this.getCommand("treasureclue").setExecutor(this);
        this.getCommand("startcycle").setExecutor(this); 
        this.getCommand("stopcycle").setExecutor(this);
        this.getServer().getPluginManager().registerEvents(this, this);

        // Check if the treasurehuntconfig.yml exists
        if (!new File(getDataFolder(), "treasurehuntconfig.yml").exists()) {
            saveResource("treasurehuntconfig.yml", false);
        }

        for (World world : Bukkit.getWorlds()) {
            for (Zombie zombie : world.getEntitiesByClass(Zombie.class)) {
                if (zombie.getCustomName() != null && zombie.getCustomName().startsWith("Guardian of")) {
                    zombie.remove();
                }
            }
        }

        // Load the configuration
        config = YamlConfiguration.loadConfiguration(new File(getDataFolder(), "treasurehuntconfig.yml"));

        // Load world name
        worldName = config.getString("worldName");

        // Load items per chest
        itemsPerChest = config.getInt("itemsPerChest");

        chestDuration = config.getInt("chest_duration");

        // Load location determination logic
        useWorldBorder = config.getBoolean("location_determination.useWorldBorder");
        coordinateBounds = Arrays.asList(config.getIntegerList("location_determination.coordinateBounds").stream().mapToInt(Integer::intValue).toArray());
        spawnRadius = config.getInt("location_determination.spawnRadius");

        ConfigurationSection treasureChancesSection = config.getConfigurationSection("treasure_chances");
        if (treasureChancesSection == null) {
            this.getLogger().warning("Failed to load treasureChancesSection from config!");
            return;  // or handle this error appropriately
        }
        
        // Clear the global map and populate it with the new values
        treasureChances.clear();
        
        for (String tierKey : treasureChancesSection.getKeys(false)) {
            TreasureTier tier = TreasureTier.valueOf(tierKey.toUpperCase());
            double chance = treasureChancesSection.getDouble(tierKey);
            treasureChances.put(tier, chance);
            this.getLogger().info("Loaded chance for tier " + tier + ": " + chance);
        }        

        // Load loot tables
        ConfigurationSection lootTablesSection = config.getConfigurationSection("loot_tables");
        lootTables = new HashMap<>();
        for (String tierKey : lootTablesSection.getKeys(false)) {
            TreasureTier tier = TreasureTier.valueOf(tierKey.toUpperCase());
            ConfigurationSection tierSection = lootTablesSection.getConfigurationSection(tierKey);
            Map<Material, AbstractMap.SimpleEntry<Integer, Integer>> loot = new HashMap<>();
            for (String itemKey : tierSection.getKeys(false)) {
                Material material = Material.valueOf(itemKey.toUpperCase());
                String[] range = tierSection.getString(itemKey).split("-");
                loot.put(material, new AbstractMap.SimpleEntry<>(Integer.parseInt(range[0]), Integer.parseInt(range[1])));
            }
            lootTables.put(tier, loot);
        }

        // Load guardian count per tier
        ConfigurationSection guardiansSection = config.getConfigurationSection("guardians_per_tier");
        guardiansPerTier = new HashMap<>();
        for (String tierKey : guardiansSection.getKeys(false)) {
            TreasureTier tier = TreasureTier.valueOf(tierKey.toUpperCase());
            int count = guardiansSection.getInt(tierKey);
            guardiansPerTier.put(tier, count);
        }

        this.getServer().getScheduler().scheduleSyncRepeatingTask(this, () -> {
            for (Map.Entry<Location, List<Guardian>> entry : guardianChestMap.entrySet()) {
                Location chestLoc = entry.getKey();
                List<Guardian> guardians = entry.getValue();
                guardians.removeIf(guardian -> !guardian.isAlive());
                for (Guardian guardian : guardians) {
                    if (guardian.getLocation().distance(chestLoc) > 20) {
                        guardian.teleport(chestLoc);
                    }
                }
            }
        }, 20L, 20L);

        Bukkit.getScheduler().scheduleSyncRepeatingTask(this, () -> {
            long currentTime = System.currentTimeMillis();
            for (Location chestLocation : chestHolograms.keySet()) {
                ChestData chestData = chestActivationTimes.getOrDefault(chestLocation, new ChestData(currentTime, TreasureTier.BASIC));
                long timeElapsed = (currentTime - chestData.activationTime) / 1000; // Convert to seconds
                long timeRemaining = chestDuration - timeElapsed;
                List<ArmorStand> holograms = chestHolograms.get(chestLocation);
                if (holograms.size() == 2) {
                    if (timeRemaining > 0) {
                        holograms.get(0).setCustomName(ChatColor.YELLOW + "Time left: " + timeRemaining + "s");
                    } else {
                        holograms.forEach(h -> h.setCustomName(ChatColor.RED + "Chest expired!"));
                        Bukkit.getScheduler().runTaskLater(this, () -> {
                            chestLocation.getBlock().setType(Material.AIR);
                            holograms.forEach(ArmorStand::remove);
                        }, 60L); // This will run after 3 seconds.
                    }
                    holograms.get(1).setCustomName(ChatColor.BOLD + chestData.tier.name());                
                }
            }
        }, 0L, 20L);        
    
        Bukkit.getScheduler().scheduleSyncRepeatingTask(this, () -> {
            long currentTime = System.currentTimeMillis();
            List<Location> expiredChests = new ArrayList<>();
    
            for (Location chestLocation : chestActivationTimes.keySet()) {
                ChestData chestData = chestActivationTimes.getOrDefault(chestLocation, new ChestData(currentTime, TreasureTier.BASIC));
                long timeElapsed = (currentTime - chestData.activationTime) / 1000; // Convert to seconds
    
                if (timeElapsed >= chestDuration) {
                    expiredChests.add(chestLocation);
    
                    // Remove chest
                    chestLocation.getBlock().setType(Material.AIR);
    
                    // Remove guardians
                    List<Guardian> guardians = guardianChestMap.get(chestLocation);
                    if (guardians != null) {
                        for (Guardian guardian : guardians) {
                            if (!guardian.isAlive()) {
                                guardian.remove();
                            }
                        }
                    }
    
                    // Remove holograms
                    List<ArmorStand> holograms = chestHolograms.get(chestLocation);
                    if (holograms != null) {
                        for (ArmorStand hologram : holograms) {
                            hologram.remove();
                        }
                    }
                }
            }
    
            // Clean up the maps
            for (Location loc : expiredChests) {
                chestActivationTimes.remove(loc);
                guardianChestMap.remove(loc);
                chestHolograms.remove(loc);
            }
        }, 0L, 20L);  // 20 ticks = 1 second
    }    

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (cmd.getName().equalsIgnoreCase("starttreasurehunt") && sender.hasPermission("treasurehunt.admin")) {
            startTreasureHunt();
            sender.sendMessage("Treasure hunt started!");
            return true;
        } else if (cmd.getName().equalsIgnoreCase("treasureclue") && sender instanceof Player) {
            giveClue((Player) sender);
            return true;
        } else if (cmd.getName().equalsIgnoreCase("startcycle") && sender.hasPermission("treasurehunt.admin")) {
            this.getServer().getScheduler().scheduleSyncRepeatingTask(this, this::startTreasureHunt, 0L, 120000L);
            sender.sendMessage("Treasure hunt cycle started!");
            return true;
        } else if (cmd.getName().equalsIgnoreCase("stopcycle") && sender.hasPermission("treasurehunt.admin")) {
            this.getServer().getScheduler().cancelTasks(this);
            sender.sendMessage("Treasure hunt cycle stopped!");
            return true;
        }
        return false;
    }

    private void startTreasureHunt() {
        int numTreasuresToSpawn = config.getInt("numberOfChests", 10);  // Default to 10 if not specified
        clearPreviousTreasures();
        for (int i = 0; i < numTreasuresToSpawn; i++) {
            spawnTreasure();
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            player.sendTitle("A new treasure hunt has begun!", "Use /treasureclue to get a hint!", 10, 70, 20);
        }
    }

    private void clearPreviousTreasures() {
        this.getLogger().info("Clearing previous treasures...");
        this.getLogger().info("Previous treasure locations: " + treasureLocations.toString());
    
        for (Location loc : treasureLocations) {
            // Search within a 2-block radius for a chest and remove it
            for (int x = -2; x <= 2; x++) {
                for (int y = -2; y <= 2; y++) {
                    for (int z = -2; z <= 2; z++) {
                        Block block = loc.getWorld().getBlockAt(loc.getBlockX() + x, loc.getBlockY() + y, loc.getBlockZ() + z);
                        if (block.getType() == Material.CHEST) {
                            block.setType(Material.AIR);
                            this.getLogger().info("Removed chest at " + block.getLocation().toString());
                        }
                    }
                }
            }
    
            // Remove guardians
            List<Guardian> guardians = guardianChestMap.get(loc);
            if (guardians != null) {
                for (Guardian guardian : guardians) {
                    if (guardian.isAlive()) {
                        guardian.remove();
                    }
                }
            } 
    
            // Remove holograms
            List<ArmorStand> holograms = chestHolograms.get(loc);
            if (holograms != null) {
                for (ArmorStand hologram : holograms) {
                    hologram.remove();
                }
            }
    
            // Remove activation times
            chestActivationTimes.remove(loc);
        }
    
        treasureLocations.clear();
        guardianChestMap.clear();
        chestHolograms.clear();
    }    

    private void spawnTreasure() {
        this.getLogger().info("Attempting to spawn treasure...");
        this.getLogger().info("Current treasure locations: " + treasureLocations.toString());
        Location treasureLocation = determineTreasureLocation();
        if (treasureLocation != null) {
            treasureLocation.getBlock().setType(Material.CHEST);
            treasureLocations.add(treasureLocation);
            
            TreasureTier tier = determineTreasureTier();
            BlockState blockState = treasureLocation.getBlock().getState();
            populateChestWithLoot((Chest) blockState, tier);    
            int numberOfGuardians = spawnGuardians(treasureLocation, tier);
            this.getLogger().info("Spawned " + numberOfGuardians + " guardians for treasure at " + treasureLocation.toString());
            this.getLogger().info("Guardians associated with chest: " + guardianChestMap.get(treasureLocation).toString()); 
            List<ArmorStand> holograms = spawnHologram(
                treasureLocation.add(0, 0, 0),  // Adjust to center the hologram on the chest
                ChatColor.YELLOW + "Time left: " + chestDuration + "s",
                ChatColor.BOLD + "" + ChatColor.UNDERLINE + tier.name()  // Bold and underline the chest tier name
            );             
            chestHolograms.put(treasureLocation, holograms);
            chestActivationTimes.put(treasureLocation, new ChestData(System.currentTimeMillis(), tier));
        }
    }
    
    private Location determineTreasureLocation() {
        this.getLogger().info("Using world border for treasure location determination.");
        if (useWorldBorder) {
            return locationWithinWorldBorder();
        } else if (coordinateBounds != null && !coordinateBounds.isEmpty()) {
            return locationWithinCoordinateBounds();
        } else if (spawnRadius > 0) {
            return locationWithinSpawnRadius();
        }
        return null;
    }

    private Location locationWithinWorldBorder() {
        World world = Bukkit.getWorld(worldName);
        WorldBorder worldBorder = world.getWorldBorder();
        double size = worldBorder.getSize() / 2;
        double x = worldBorder.getCenter().getX() + (Math.random() * size * 2 - size);
        double z = worldBorder.getCenter().getZ() + (Math.random() * size * 2 - size);
        double y = world.getHighestBlockYAt((int) x, (int) z);
        // Check the block type at the determined Y-coordinate
        Material blockType = world.getBlockAt((int) x, (int) y, (int) z).getType();
        if (blockType != Material.WATER && blockType != Material.LAVA) {
            y += 1; // Increment Y to place the chest above the block
        }
        return new Location(world, x, y, z);
        }

    private Location locationWithinCoordinateBounds() {
        World world = Bukkit.getWorld(worldName);
        Random random = new Random();
        int x = random.nextInt(coordinateBounds.get(1)[0] - coordinateBounds.get(0)[0]) + coordinateBounds.get(0)[0];
        int z = random.nextInt(coordinateBounds.get(1)[1] - coordinateBounds.get(0)[1]) + coordinateBounds.get(0)[1];
        int y = world.getHighestBlockYAt(x, z);
        return new Location(world, x, y, z);
    }

    private Location locationWithinSpawnRadius() {
        World world = Bukkit.getWorld(worldName);
        Random random = new Random();
        double angle = random.nextDouble() * 2 * Math.PI;
        int distance = random.nextInt(spawnRadius);
        int x = (int) (distance * Math.cos(angle));
        int z = (int) (distance * Math.sin(angle));
        int y = world.getHighestBlockYAt(x, z);
        return new Location(world, x, y, z);
    }
    
    private TreasureTier determineTreasureTier() {
        this.getLogger().info("Determining treasure tier...");
        this.getLogger().info("Current treasureChances: " + treasureChances.toString());
    
        // Create a list of tiers with a 100% chance
        List<TreasureTier> guaranteedTiers = new ArrayList<>();
        for (Map.Entry<TreasureTier, Double> entry : treasureChances.entrySet()) {
            if (entry.getValue() == 1.0) {
                guaranteedTiers.add(entry.getKey());
            }
        }
        this.getLogger().info("Tiers with 100% chance: " + guaranteedTiers);
    
        // If one or more tiers have a 100% chance
        if (!guaranteedTiers.isEmpty()) {
            // Randomly select one of the 100% chance tiers if more than one
            TreasureTier selectedTier = guaranteedTiers.get(new Random().nextInt(guaranteedTiers.size()));
            this.getLogger().info("Selected tier: " + selectedTier + " (100% chance)");
            return selectedTier;
        }
    
        double randomValue = Math.random();  // Get a random value between 0.0 (inclusive) and 1.0 (exclusive)
        this.getLogger().info("Generated random value: " + randomValue);
    
        double accumulatedChance = 0.0;
        for (Map.Entry<TreasureTier, Double> entry : treasureChances.entrySet()) {
            accumulatedChance += entry.getValue();
            this.getLogger().info("Checking tier: " + entry.getKey() + " with accumulated chance: " + accumulatedChance);
            if (randomValue <= accumulatedChance) {
                this.getLogger().info("Selected tier: " + entry.getKey());
                return entry.getKey();
            }
        }
    
        this.getLogger().warning("Defaulting to BASIC tier. This should not happen if chances are configured correctly.");
        return TreasureTier.BASIC;  // Default to BASIC if something goes wrong
    }

    private void populateChestWithLoot(Chest chest, TreasureTier tier) {

        Map<Material, AbstractMap.SimpleEntry<Integer, Integer>> loot = lootTables.get(tier);
        Random random = new Random();
        
        Inventory chestInventory = chest.getInventory();
        int chestSize = chestInventory.getSize();
      
        Set<Integer> occupiedSlots = new HashSet<>();
      
        for (int i = 0; i < itemsPerChest; i++) {
      
          Material material = getRandomMaterial(loot.keySet());
          AbstractMap.SimpleEntry<Integer, Integer> range = loot.get(material);
          int quantity = random.nextInt(range.getValue() - range.getKey() + 1) + range.getKey();
      
          // Randomize slot placement
          int slot;
          do {
            slot = random.nextInt(chestSize);
          } while (occupiedSlots.contains(slot));
      
          occupiedSlots.add(slot);
          
          // Create the item stack
          ItemStack item = new ItemStack(material, quantity);
          
          // Enchant the item
          addRandomEnchantments(item, tier);
      
          // Add enchanted item to chest
          chestInventory.setItem(slot, item);
      
        }
      
      }

    private Material getRandomMaterial(Set<Material> materials) {
        int index = new Random().nextInt(materials.size());
        return (Material) materials.toArray()[index];
    }



    private void addRandomEnchantments(ItemStack item, TreasureTier tier) {

        Map<Enchantment, Integer> possibleEnchants = new HashMap<>();
        Random random = new Random();
      
        // Chance to enchant based on tier
        double enchantChance = config.getDouble("enchant_chances." + tier.toString(), 0);
      
        if (Math.random() < enchantChance) {
      
          // Max enchant level based on tier
          int maxLevel = config.getInt("max_levels." + tier.toString(), 1);
      
          // Generate possible enchants
          if (item.getType().name().endsWith("_SWORD")) {
            possibleEnchants.put(Enchantment.DAMAGE_ALL, random.nextInt(maxLevel) + 1);
            possibleEnchants.put(Enchantment.LOOT_BONUS_MOBS, random.nextInt(maxLevel) + 1);
            possibleEnchants.put(Enchantment.FIRE_ASPECT, random.nextInt(maxLevel) + 1);
            possibleEnchants.put(Enchantment.DURABILITY, random.nextInt(maxLevel) + 1);
            possibleEnchants.put(Enchantment.MENDING, random.nextInt(maxLevel) + 1);
            possibleEnchants.put(Enchantment.SWEEPING_EDGE, random.nextInt(maxLevel) + 1);
            
          } else if (item.getType().name().endsWith("_PICKAXE")) {
            possibleEnchants.put(Enchantment.DIG_SPEED, random.nextInt(maxLevel) + 1);
            possibleEnchants.put(Enchantment.LOOT_BONUS_BLOCKS, random.nextInt(maxLevel) + 1);
            possibleEnchants.put(Enchantment.DURABILITY, random.nextInt(maxLevel) + 1);
            possibleEnchants.put(Enchantment.MENDING, random.nextInt(maxLevel) + 1);
            possibleEnchants.put(Enchantment.SILK_TOUCH, random.nextInt(maxLevel) + 1);
            //...other pickaxe enchants

            } else if (item.getType().name().endsWith("_AXE")) {
            possibleEnchants.put(Enchantment.DIG_SPEED, random.nextInt(maxLevel) + 1);
            possibleEnchants.put(Enchantment.DAMAGE_ALL, random.nextInt(maxLevel) + 1);
            possibleEnchants.put(Enchantment.DURABILITY, random.nextInt(maxLevel) + 1);
            possibleEnchants.put(Enchantment.MENDING, random.nextInt(maxLevel) + 1);
            possibleEnchants.put(Enchantment.LOOT_BONUS_BLOCKS, random.nextInt(maxLevel) + 1);
            //...other axe enchants

            } else if (item.getType().name().endsWith("_SHOVEL")) {
            possibleEnchants.put(Enchantment.DIG_SPEED, random.nextInt(maxLevel) + 1);
            possibleEnchants.put(Enchantment.DURABILITY, random.nextInt(maxLevel) + 1);
            possibleEnchants.put(Enchantment.MENDING, random.nextInt(maxLevel) + 1);
            possibleEnchants.put(Enchantment.LOOT_BONUS_BLOCKS, random.nextInt(maxLevel) + 1);
            //...other shovel enchants

            } else if (item.getType().name().endsWith("_HELMET")) {
            possibleEnchants.put(Enchantment.PROTECTION_ENVIRONMENTAL, random.nextInt(maxLevel) + 1);
            possibleEnchants.put(Enchantment.PROTECTION_EXPLOSIONS, random.nextInt(maxLevel) + 1);
            possibleEnchants.put(Enchantment.PROTECTION_FIRE, random.nextInt(maxLevel) + 1);
            possibleEnchants.put(Enchantment.PROTECTION_PROJECTILE, random.nextInt(maxLevel) + 1);
            possibleEnchants.put(Enchantment.DURABILITY, random.nextInt(maxLevel) + 1);
            possibleEnchants.put(Enchantment.MENDING, random.nextInt(maxLevel) + 1);
            possibleEnchants.put(Enchantment.WATER_WORKER, random.nextInt(maxLevel) + 1);
            possibleEnchants.put(Enchantment.THORNS, random.nextInt(maxLevel) + 1);
            //...other helmet enchants

            } else if (item.getType().name().endsWith("_CHESTPLATE")) {
            possibleEnchants.put(Enchantment.PROTECTION_ENVIRONMENTAL, random.nextInt(maxLevel) + 1);
            possibleEnchants.put(Enchantment.PROTECTION_EXPLOSIONS, random.nextInt(maxLevel) + 1);
            possibleEnchants.put(Enchantment.PROTECTION_FIRE, random.nextInt(maxLevel) + 1);
            possibleEnchants.put(Enchantment.PROTECTION_PROJECTILE, random.nextInt(maxLevel) + 1);
            possibleEnchants.put(Enchantment.DURABILITY, random.nextInt(maxLevel) + 1);
            possibleEnchants.put(Enchantment.MENDING, random.nextInt(maxLevel) + 1);
            possibleEnchants.put(Enchantment.THORNS, random.nextInt(maxLevel) + 1);
            //...other chestplate enchants

            } else if (item.getType().name().endsWith("_LEGGINGS")) {
            possibleEnchants.put(Enchantment.PROTECTION_ENVIRONMENTAL, random.nextInt(maxLevel) + 1);
            possibleEnchants.put(Enchantment.PROTECTION_EXPLOSIONS, random.nextInt(maxLevel) + 1);
            possibleEnchants.put(Enchantment.PROTECTION_FIRE, random.nextInt(maxLevel) + 1);
            possibleEnchants.put(Enchantment.PROTECTION_PROJECTILE, random.nextInt(maxLevel) + 1);
            possibleEnchants.put(Enchantment.DURABILITY, random.nextInt(maxLevel) + 1);
            possibleEnchants.put(Enchantment.MENDING, random.nextInt(maxLevel) + 1);
            possibleEnchants.put(Enchantment.THORNS, random.nextInt(maxLevel) + 1);
            //...other leggings enchants

            } else if (item.getType().name().endsWith("_BOOTS")) {
            possibleEnchants.put(Enchantment.PROTECTION_ENVIRONMENTAL, random.nextInt(maxLevel) + 1);
            possibleEnchants.put(Enchantment.PROTECTION_EXPLOSIONS, random.nextInt(maxLevel) + 1);
            possibleEnchants.put(Enchantment.PROTECTION_FIRE, random.nextInt(maxLevel) + 1);
            possibleEnchants.put(Enchantment.PROTECTION_PROJECTILE, random.nextInt(maxLevel) + 1);
            possibleEnchants.put(Enchantment.PROTECTION_FALL, random.nextInt(maxLevel) + 1);
            possibleEnchants.put(Enchantment.DURABILITY, random.nextInt(maxLevel) + 1);
            possibleEnchants.put(Enchantment.MENDING, random.nextInt(maxLevel) + 1);
            possibleEnchants.put(Enchantment.DEPTH_STRIDER, random.nextInt(maxLevel) + 1);
            possibleEnchants.put(Enchantment.FROST_WALKER, random.nextInt(maxLevel) + 1);
            possibleEnchants.put(Enchantment.THORNS, random.nextInt(maxLevel) + 1);
            //...other boots enchants

            } else if (item.getType().name().endsWith("_BOW")) {
            possibleEnchants.put(Enchantment.ARROW_DAMAGE, random.nextInt(maxLevel) + 1);
            possibleEnchants.put(Enchantment.ARROW_FIRE, random.nextInt(maxLevel) + 1);
            possibleEnchants.put(Enchantment.ARROW_INFINITE, random.nextInt(maxLevel) + 1);
            possibleEnchants.put(Enchantment.ARROW_KNOCKBACK, random.nextInt(maxLevel) + 1);
            possibleEnchants.put(Enchantment.DURABILITY, random.nextInt(maxLevel) + 1);
            possibleEnchants.put(Enchantment.MENDING, random.nextInt(maxLevel) + 1);
            //...other bow enchants

            } else if (item.getType().name().endsWith("_FISHING_ROD")) {
            possibleEnchants.put(Enchantment.LUCK, random.nextInt(maxLevel) + 1);
            possibleEnchants.put(Enchantment.LURE, random.nextInt(maxLevel) + 1);
            possibleEnchants.put(Enchantment.DURABILITY, random.nextInt(maxLevel) + 1);
            possibleEnchants.put(Enchantment.MENDING, random.nextInt(maxLevel) + 1);
            //...other fishing rod enchants

            } else if (item.getType().name().endsWith("_TRIDENT")) {
            possibleEnchants.put(Enchantment.LOYALTY, random.nextInt(maxLevel) + 1);
            possibleEnchants.put(Enchantment.CHANNELING, random.nextInt(maxLevel) + 1);
            possibleEnchants.put(Enchantment.IMPALING, random.nextInt(maxLevel) + 1);
            possibleEnchants.put(Enchantment.RIPTIDE, random.nextInt(maxLevel) + 1);
            possibleEnchants.put(Enchantment.DURABILITY, random.nextInt(maxLevel) + 1);
            possibleEnchants.put(Enchantment.MENDING, random.nextInt(maxLevel) + 1);
            //...other trident enchants

            } else if (item.getType().name().endsWith("_CROSSBOW")) {
            possibleEnchants.put(Enchantment.QUICK_CHARGE, random.nextInt(maxLevel) + 1);
            possibleEnchants.put(Enchantment.MULTISHOT, random.nextInt(maxLevel) + 1);
            possibleEnchants.put(Enchantment.PIERCING, random.nextInt(maxLevel) + 1);
            possibleEnchants.put(Enchantment.DURABILITY, random.nextInt(maxLevel) + 1);
            possibleEnchants.put(Enchantment.MENDING, random.nextInt(maxLevel) + 1);
            //...other crossbow enchants

            } else if (item.getType().name().endsWith("_ELYTRA")) {
            possibleEnchants.put(Enchantment.DURABILITY, random.nextInt(maxLevel) + 1);
            possibleEnchants.put(Enchantment.MENDING, random.nextInt(maxLevel) + 1);
            possibleEnchants.put(Enchantment.PROTECTION_ENVIRONMENTAL, random.nextInt(maxLevel) + 1);
            //...other elytra enchants

            } else if (item.getType().name().endsWith("_SHIELD")) {
            possibleEnchants.put(Enchantment.DURABILITY, random.nextInt(maxLevel) + 1);
            possibleEnchants.put(Enchantment.MENDING, random.nextInt(maxLevel) + 1);
            //...other shield enchants

            } else if (item.getType().name().endsWith("_HOE")) {
            possibleEnchants.put(Enchantment.DURABILITY, random.nextInt(maxLevel) + 1);
            possibleEnchants.put(Enchantment.MENDING, random.nextInt(maxLevel) + 1);
            //...other hoe enchants
          }
          
          // Determine max enchants to add
          int maxEnchants = config.getInt("max_enchants." + tier.toString(), 1);
          List<Enchantment> enchantsToAdd = new ArrayList<>();
      
          // Randomly select enchants to add
          List<Enchantment> possibleEnchantKeys = new ArrayList<>(possibleEnchants.keySet());
          while (enchantsToAdd.size() < maxEnchants && !possibleEnchantKeys.isEmpty()) {
              int randomIndex = random.nextInt(possibleEnchantKeys.size());
              Enchantment randomEnchant = possibleEnchantKeys.get(randomIndex);
              enchantsToAdd.add(randomEnchant);
              possibleEnchantKeys.remove(randomIndex);
            }          
      
          // Apply selected enchantments
          for (Enchantment ench : enchantsToAdd) {
            int level = possibleEnchants.get(ench);
            if (level >= ench.getStartLevel() && level <= ench.getMaxLevel()) {
              item.addEnchantment(ench, level);
            }
          }
        
        }
      
      }

    private int spawnGuardians(Location chestLocation, TreasureTier tier) {
        World world = chestLocation.getWorld();
        Scoreboard scoreboard = Bukkit.getScoreboardManager().getMainScoreboard();
        Team team;
    
        List<Guardian> guardianList = new ArrayList<>();
        int numGuardiansToSpawn = guardiansPerTier.getOrDefault(tier, 0);
        String tierName = tier.name().toLowerCase();
        //capitalize first letter of tier name
        tierName = tierName.substring(0, 1).toUpperCase() + tierName.substring(1);

        switch (tier) {
            case BASIC:
                for (int i = 0; i < numGuardiansToSpawn; i++) {
                    Zombie guardianEntity = world.spawn(chestLocation, Zombie.class);
                    guardianEntity.setCustomName("Guardian of " + tierName + " Chest");
                    guardianEntity.setCustomNameVisible(true);
                    guardianEntity.getEquipment().setArmorContents(new ItemStack[]{
                        new ItemStack(Material.LEATHER_BOOTS),
                        new ItemStack(Material.LEATHER_LEGGINGS),
                        new ItemStack(Material.LEATHER_CHESTPLATE),
                        new ItemStack(Material.LEATHER_HELMET)
                    });
                    team = getOrCreateTeam(scoreboard, "BASIC_GUARDIANS", ChatColor.WHITE);
                    team.addEntry(guardianEntity.getUniqueId().toString());
                    guardianEntity.setGlowing(true);
                    guardianList.add(new Guardian(guardianEntity, chestLocation));
                }
                break;
    
            case INTERMEDIATE:
                for (int i = 0; i < numGuardiansToSpawn; i++) {
                    Skeleton guardianEntity = world.spawn(chestLocation, Skeleton.class);
                    guardianEntity.setCustomName("Guardian of " + tierName + " Chest");
                    guardianEntity.setCustomNameVisible(true);
                    team = getOrCreateTeam(scoreboard, "INTERMEDIATE_GUARDIANS", ChatColor.GREEN);
                    team.addEntry(guardianEntity.getUniqueId().toString());
                    guardianEntity.setGlowing(true);
                    guardianList.add(new Guardian(guardianEntity, chestLocation));
                }
                break;
    
            case ADVANCED:
                for (int i = 0; i < numGuardiansToSpawn; i++) {
                    Creeper guardianEntity = world.spawn(chestLocation, Creeper.class);
                    guardianEntity.setCustomName("Guardian of " + tierName + " Chest");
                    guardianEntity.setCustomNameVisible(true);
                    team = getOrCreateTeam(scoreboard, "ADVANCED_GUARDIANS", ChatColor.DARK_PURPLE);
                    team.addEntry(guardianEntity.getUniqueId().toString());
                    guardianEntity.setGlowing(true);
                    guardianList.add(new Guardian(guardianEntity, chestLocation));
                }
                break;
    
            case LEGENDARY:
                Wither witherEntity = world.spawn(chestLocation, Wither.class);
                witherEntity.setCustomName("Guardian of " + tierName + " Chest");
                witherEntity.setCustomNameVisible(true);
                witherEntity.setAI(true);
                witherEntity.setCanPickupItems(false);
                witherEntity.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, Integer.MAX_VALUE, 3));
                witherEntity.addPotionEffect(new PotionEffect(PotionEffectType.INCREASE_DAMAGE, Integer.MAX_VALUE, 2));
                witherEntity.addPotionEffect(new PotionEffect(PotionEffectType.DAMAGE_RESISTANCE, Integer.MAX_VALUE, 1));
                witherEntity.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION, Integer.MAX_VALUE, 1));
                BossBar bossbar = witherEntity.getBossBar();
                bossbar.setVisible(true);
                bossbar.setProgress(1.0);
                bossbar.setColor(BarColor.RED);
                bossbar.setTitle("Guardian of " + tierName + " Chest");
                team = getOrCreateTeam(scoreboard, "LEGENDARY_GUARDIANS", ChatColor.GOLD);
                team.addEntry(witherEntity.getUniqueId().toString());
                witherEntity.setGlowing(true);
                guardianList.add(new Guardian(witherEntity, chestLocation));
                break;
        }
    
        // Update the guardianChestMap to store the new Guardian class list
        guardianChestMap.put(chestLocation, guardianList);
        return guardianList.size();
    }    
    
    private Team getOrCreateTeam(Scoreboard scoreboard, String teamName, ChatColor color) {
        Team team = scoreboard.getTeam(teamName);
        if (team == null) {
            team = scoreboard.registerNewTeam(teamName);
            team.setColor(color);
        }
        return team;
    }

    private void giveClue(Player player) {
        if (!treasureLocations.isEmpty()) {
            Location nearestTreasure = treasureLocations.get(0);
            player.sendMessage("The treasure is near X: " + nearestTreasure.getBlockX() + " Z: " + nearestTreasure.getBlockZ());
        } else {
            player.sendMessage("No active treasures to find!");
        }
    }
}