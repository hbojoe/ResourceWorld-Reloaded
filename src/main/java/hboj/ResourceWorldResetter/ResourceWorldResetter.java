package hboj.ResourceWorldResetter;

import hboj.ResourceWorldResetter.gui.AdminGUI;
import hboj.ResourceWorldResetter.gui.AdminGUIListener;
import hboj.ResourceWorldResetter.metrics.Metrics;
import hboj.ResourceWorldResetter.utils.LogUtil;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.GameRule;
import org.bukkit.Location;
import org.bukkit.Registry;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.WorldType;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.EnderDragon;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntitySpawnEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;

public class ResourceWorldResetter extends JavaPlugin implements Listener {
    private static final String OVERWORLD_RESOURCE_KEY = "overworld";

    private String worldName;
    private boolean multiverseEnabled;
    private Plugin multiversePlugin;
    private Object mvWorldManager;
    private int restartTime;
    private int resetWarningTime;
    private String resetType;
    private int resetDay;
    private int resetIntervalDays;
    private LocalDateTime nextIntervalReset;
    private AdminGUI adminGUI;
    private final Map<String, ResourceWorldSettings> resourceWorlds = new LinkedHashMap<>();
    private int warningTaskId = -1;
    private int resetTaskId = -1;

    public String getWorldName() { return this.worldName; }
    public String getResetType() { return this.resetType; }
    public int getRestartTime() { return this.restartTime; }
    public int getResetWarningTime() { return this.resetWarningTime; }
    public int getResetDay() { return this.resetDay; }
    public int getResetIntervalDays() { return this.resetIntervalDays; }
    public boolean isMultiverseEnabled() { return this.multiverseEnabled; }

    public String getEnabledResourceWorldSummary() {
        List<String> enabledWorldNames = new ArrayList<>();
        for (ResourceWorldSettings settings : getEnabledResourceWorlds()) {
            enabledWorldNames.add(settings.name);
        }

        if (enabledWorldNames.isEmpty()) {
            return "None enabled";
        }
        return String.join(", ", enabledWorldNames);
    }

    public String getResetScheduleDescription() {
        if ("interval".equals(resetType)) {
            return "Every " + resetIntervalDays + " " + (resetIntervalDays == 1 ? "Day" : "Days");
        }
        return capitalizeFirstLetter(resetType);
    }

    public void setWorldName(String name) {
        this.worldName = name;
        ResourceWorldSettings settings = resourceWorlds.get(OVERWORLD_RESOURCE_KEY);
        if (settings != null) {
            settings.name = name;
        }
        getConfig().set("worldName", name);
        getConfig().set("resourceWorlds." + OVERWORLD_RESOURCE_KEY + ".name", name);
        saveConfig();
        ensureResourceWorldExists();
    }

    public void setResetType(String type) {
        Integer intervalDaysFromType = parseIntervalDaysFromResetType(type);
        this.resetType = normalizeResetType(type);
        getConfig().set("resetType", this.resetType);
        if (intervalDaysFromType != null) {
            this.resetIntervalDays = intervalDaysFromType;
            getConfig().set("resetIntervalDays", this.resetIntervalDays);
        }
        if (!"interval".equals(this.resetType) || intervalDaysFromType != null) {
            clearNextIntervalReset();
        }
        saveConfig();
        scheduleNextReset();
    }

    public void setResetDay(int day) {
        this.resetDay = day;
        getConfig().set("resetDay", day);
        saveConfig();
        scheduleNextReset();
    }

    public void setIntervalResetDays(int days) {
        if (days < 1) {
            return;
        }

        this.resetType = "interval";
        this.resetIntervalDays = days;
        clearNextIntervalReset();
        getConfig().set("resetType", this.resetType);
        getConfig().set("resetIntervalDays", days);
        saveConfig();
        scheduleNextReset();
    }

    public void setRestartTime(int hour) {
        if (hour >= 0 && hour <= 23) {
            this.restartTime = hour;
            getConfig().set("restartTime", hour);
            if ("interval".equals(resetType) && nextIntervalReset != null) {
                nextIntervalReset = nextIntervalReset.withHour(restartTime).withMinute(0).withSecond(0).withNano(0);
                getConfig().set("nextIntervalReset", nextIntervalReset.toString());
            }
            saveConfig();
            scheduleNextReset();

            LogUtil.log(getLogger(), "Restart time set to " + hour + ":00", Level.INFO);
        }
    }

    public void setResetWarningTime(int minutes) {
        if (minutes >= 0) {
            this.resetWarningTime = minutes;
            getConfig().set("resetWarningTime", minutes);
            saveConfig();
            scheduleNextReset();

            LogUtil.log(getLogger(), "Reset warning time set to " + minutes + " minutes", Level.INFO);
        }
    }

    @Override
    public void onEnable() {
        saveDefaultConfig();
        LogUtil.init(this);
        loadConfig();

        setupMultiverseIntegration();

        if (getConfig().getBoolean("metrics.enabled", true)) {
            int pluginId = 25197;
            Metrics metrics = new Metrics(this, pluginId);
            metrics.addCustomChart(new Metrics.SimplePie("reset_type", () -> this.resetType));
            metrics.addCustomChart(new Metrics.SimplePie("reset_hour", () -> String.valueOf(this.restartTime)));
            metrics.addCustomChart(new Metrics.SimplePie("warning_time", () -> String.valueOf(this.resetWarningTime)));
            metrics.addCustomChart(new Metrics.SimplePie("reset_interval_days", () -> String.valueOf(this.resetIntervalDays)));
            metrics.addCustomChart(new Metrics.SimplePie("server_version", Bukkit::getBukkitVersion));
            LogUtil.log(getLogger(), "bStats metrics enabled", Level.INFO);
        } else {
            LogUtil.log(getLogger(), "bStats metrics disabled by configuration", Level.INFO);
        }

        adminGUI = new AdminGUI(this);
        getServer().getPluginManager().registerEvents(new AdminGUIListener(this, adminGUI), this);
        getServer().getPluginManager().registerEvents(this, this);

        ensureResourceWorldsExist();
        scheduleNextReset();
        LogUtil.log(getLogger(), "ResourcesWorldResetter v" + getDescription().getVersion() + " enabled successfully!", Level.INFO);
    }

    @Override
    public void onDisable() {
        cancelScheduledTasks();
        LogUtil.log(getLogger(), "ResourceWorldResetter disabled.", Level.INFO);
    }

    private void cancelScheduledTasks() {
        if (warningTaskId != -1) {
            Bukkit.getScheduler().cancelTask(warningTaskId);
            warningTaskId = -1;
        }
        if (resetTaskId != -1) {
            Bukkit.getScheduler().cancelTask(resetTaskId);
            resetTaskId = -1;
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (sender.hasPermission("resourceworldresetter.admin")) {
            switch (command.getName().toLowerCase()) {
                case "rwrgui":
                    if (sender instanceof Player player) {
                        adminGUI.openMainMenu(player);
                        return true;
                    } else {
                        sender.sendMessage(ChatColor.RED + "This command can only be used by players.");
                        return true;
                    }

                case "reloadrwr":
                    reloadConfig();
                    loadConfig();
                    setupMultiverseIntegration();
                    scheduleNextReset();
                    ensureResourceWorldsExist();
                    sender.sendMessage(ChatColor.GREEN + "ResourcesWorldResetter configuration reloaded!");
                    return true;

                case "resetworld":
                    sender.sendMessage(ChatColor.GREEN + "Forcing resource world reset...");
                    resetResourceWorld(false);
                    return true;
            }
        } else {
            sender.sendMessage(ChatColor.RED + "You do not have permission to use this command.");
            return true;
        }
        return false;
    }

    private void scheduleNextReset() {
        cancelScheduledTasks();

        if (getEnabledResourceWorlds().isEmpty()) {
            LogUtil.log(getLogger(), "No enabled resource worlds. Global reset scheduling is disabled.", Level.INFO);
            return;
        }

        LocalDateTime now = LocalDateTime.now();
        LocalDateTime nextReset = calculateNextReset(now);

        long resetDelayTicks = Math.max(1, ChronoUnit.SECONDS.between(now, nextReset) * 20);

        if (resetWarningTime > 0) {
            LocalDateTime warningTime = nextReset.minusMinutes(resetWarningTime);

            if (now.isBefore(warningTime)) {
                long warningDelayTicks = Math.max(1, ChronoUnit.SECONDS.between(now, warningTime) * 20);

                LogUtil.log(getLogger(), "Warning scheduled for " + warningTime +
                        " (" + (warningDelayTicks / 20 / 60) + " minutes from now)", Level.INFO);

                warningTaskId = Bukkit.getScheduler().runTaskLater(this, () -> {
                    Bukkit.broadcastMessage(ChatColor.YELLOW + "[ResourceWorldResetter] " +
                            "Resource worlds will reset in " + resetWarningTime + " minutes!");
                    LogUtil.log(getLogger(), "Broadcast reset warning to players", Level.INFO);
                }, warningDelayTicks).getTaskId();
            }
        }

        LogUtil.log(getLogger(), "Next reset scheduled in " + (resetDelayTicks / 20 / 60) + " minutes (" +
                (resetDelayTicks / 20 / 60 / 60) + " hours)", Level.INFO);

        resetTaskId = Bukkit.getScheduler().runTaskLater(this, () -> {
            LogUtil.log(getLogger(), "Executing scheduled reset task", Level.INFO);
            resetResourceWorld(true);
        }, resetDelayTicks).getTaskId();
    }

    private LocalDateTime calculateNextReset(LocalDateTime now) {
        return switch (resetType) {
            case "interval" -> calculateNextIntervalReset(now);
            case "weekly" -> calculateNextWeeklyReset(now);
            case "monthly" -> calculateNextMonthlyReset(now);
            default -> calculateNextDailyReset(now);
        };
    }

    private LocalDateTime calculateNextDailyReset(LocalDateTime now) {
        LocalDateTime nextReset = resetTimeOn(now);
        if (!nextReset.isAfter(now)) {
            nextReset = nextReset.plusDays(1);
        }
        LogUtil.log(getLogger(), "Scheduled daily reset for " + nextReset, Level.INFO);
        return nextReset;
    }

    private LocalDateTime calculateNextIntervalReset(LocalDateTime now) {
        if (nextIntervalReset == null) {
            nextIntervalReset = resetTimeOn(now);
        }

        while (!nextIntervalReset.isAfter(now)) {
            nextIntervalReset = nextIntervalReset.plusDays(resetIntervalDays);
        }

        getConfig().set("nextIntervalReset", nextIntervalReset.toString());
        saveConfig();
        LogUtil.log(getLogger(), "Scheduled every-" + resetIntervalDays + "-days reset for " + nextIntervalReset, Level.INFO);
        return nextIntervalReset;
    }

    private LocalDateTime calculateNextWeeklyReset(LocalDateTime now) {
        int currentDay = now.getDayOfWeek().getValue();
        int daysUntilReset = (resetDay - currentDay + 7) % 7;
        LocalDateTime nextReset = resetTimeOn(now).plusDays(daysUntilReset);

        if (!nextReset.isAfter(now)) {
            nextReset = nextReset.plusDays(7);
        }

        LogUtil.log(getLogger(), "Scheduled weekly reset for " + nextReset, Level.INFO);
        return nextReset;
    }

    private LocalDateTime calculateNextMonthlyReset(LocalDateTime now) {
        LocalDateTime nextReset = monthlyResetTimeInMonth(now);

        if (!nextReset.isAfter(now)) {
            nextReset = monthlyResetTimeInMonth(now.plusMonths(1));
        }

        LogUtil.log(getLogger(), "Scheduled monthly reset for " + nextReset, Level.INFO);
        return nextReset;
    }

    private LocalDateTime resetTimeOn(LocalDateTime dateTime) {
        return dateTime.withHour(restartTime).withMinute(0).withSecond(0).withNano(0);
    }

    private LocalDateTime monthlyResetTimeInMonth(LocalDateTime dateTime) {
        int maxDay = dateTime.getMonth().length(dateTime.toLocalDate().isLeapYear());
        int actualResetDay = Math.min(Math.max(resetDay, 1), maxDay);
        return dateTime.withDayOfMonth(actualResetDay).withHour(restartTime).withMinute(0).withSecond(0).withNano(0);
    }

    public void resetResourceWorld() {
        resetResourceWorld(false);
    }

    public void resetResourceWorld(boolean isScheduled) {
        List<ResourceWorldSettings> enabledWorlds = getEnabledResourceWorlds();
        if (enabledWorlds.isEmpty()) {
            LogUtil.log(getLogger(), "No enabled resource worlds to reset.", Level.WARNING);
            Bukkit.broadcastMessage(ChatColor.YELLOW + "[ResourceWorldResetter] No resource worlds are enabled for reset.");
            return;
        }

        double tpsBefore = getServerTPS();
        long startTime = System.currentTimeMillis();
        List<String> failedWorlds = new ArrayList<>();

        LogUtil.log(getLogger(), "Starting global reset for " + enabledWorlds.size() + " resource worlds.", Level.INFO);
        Bukkit.broadcastMessage(ChatColor.RED + "[ResourceWorldResetter] " +
                "Resource world reset in progress. Players in enabled resource worlds will be teleported to safety.");

        resetResourceWorldsSequentially(enabledWorlds, 0, startTime, tpsBefore, failedWorlds);
    }

    private void resetResourceWorldsSequentially(List<ResourceWorldSettings> worlds, int index, long startTime,
                                                double tpsBefore, List<String> failedWorlds) {
        if (index >= worlds.size()) {
            finishGlobalReset(startTime, tpsBefore, failedWorlds);
            return;
        }

        performReset(worlds.get(index), failedWorlds,
                () -> resetResourceWorldsSequentially(worlds, index + 1, startTime, tpsBefore, failedWorlds));
    }

    private void finishGlobalReset(long startTime, double tpsBefore, List<String> failedWorlds) {
        long duration = System.currentTimeMillis() - startTime;
        double tpsAfter = getServerTPS();

        if (failedWorlds.isEmpty()) {
            Bukkit.broadcastMessage(ChatColor.GREEN + "[ResourceWorldResetter] " +
                    "Resource worlds reset completed in " + (duration / 1000) + " seconds (TPS: " +
                    String.format("%.2f", tpsBefore) + " -> " + String.format("%.2f", tpsAfter) + ").");
            LogUtil.log(getLogger(), "Global resource world reset completed in " + duration + "ms", Level.INFO);
        } else {
            Bukkit.broadcastMessage(ChatColor.RED + "[ResourceWorldResetter] " +
                    "Resource world reset completed with failures: " + String.join(", ", failedWorlds) + ".");
            LogUtil.log(getLogger(), "Global resource world reset completed with failures: " +
                    String.join(", ", failedWorlds), Level.WARNING);
        }

        if (resetTaskId != -1) {
            Bukkit.getScheduler().runTaskLater(this, this::scheduleNextReset, 20);
        }
    }

    private void performReset(ResourceWorldSettings settings, List<String> failedWorlds, Runnable afterReset) {
        World world = Bukkit.getWorld(settings.name);
        if (world == null) {
            LogUtil.log(getLogger(), "World '" + settings.name + "' not found! Attempting to create it...", Level.WARNING);
            ensureResourceWorldExists(settings);
            world = Bukkit.getWorld(settings.name);

            if (world == null) {
                LogUtil.log(getLogger(), "Failed to create world '" + settings.name + "'! Reset skipped.", Level.SEVERE);
                failedWorlds.add(settings.name);
                afterReset.run();
                return;
            }
        }

        LogUtil.log(getLogger(), "Starting world reset process for " + settings.name, Level.INFO);

        teleportPlayersSafely(world);

        if (!unloadWorld(settings, world)) {
            LogUtil.log(getLogger(), "Failed to unload world: " + settings.name + ". Retrying with forced unload.", Level.WARNING);

            if (!forceUnloadWorld(settings, world)) {
                LogUtil.log(getLogger(), "Forced unload also failed. Aborting reset.", Level.SEVERE);
                Bukkit.broadcastMessage(ChatColor.RED + "[ResourceWorldResetter] " +
                        "Failed to reset resource world '" + settings.name + "'. Please notify an administrator.");
                failedWorlds.add(settings.name);
                afterReset.run();
                return;
            }
        }

        CompletableFuture.supplyAsync(() -> {
            File worldFolder = new File(Bukkit.getWorldContainer(), settings.name);
            LogUtil.log(getLogger(), "Deleting world folder: " + worldFolder.getAbsolutePath(), Level.INFO);
            return deleteFolder(worldFolder);
        }).thenAccept(deleted -> Bukkit.getScheduler().runTask(this, () -> {
            if (deleted) {
                LogUtil.log(getLogger(), "World folder deleted, recreating world: " + settings.name, Level.INFO);
                if (!recreateWorld(settings)) {
                    failedWorlds.add(settings.name);
                }
            } else {
                failedWorlds.add(settings.name);
                LogUtil.log(getLogger(), "Failed to delete world folder: " + settings.name, Level.SEVERE);
                Bukkit.broadcastMessage(ChatColor.RED + "[ResourceWorldResetter] " +
                        "Resource world reset failed for '" + settings.name + "'! Check server logs for details.");
            }

            afterReset.run();
        }));
    }

    public double getServerTPS() {
        try {
            Object mcServer = Bukkit.getServer().getClass().getMethod("getServer").invoke(Bukkit.getServer());
            double[] recentTps = (double[]) mcServer.getClass().getField("recentTps").get(mcServer);
            return recentTps[0];
        } catch (Exception e) {
            getLogger().warning("Failed to get server TPS. Defaulting to 20.0");
            return 20.0;
        }
    }

    public void teleportPlayersSafely(World world) {
        World defaultWorld = Bukkit.getWorlds().get(0);
        Location spawn = defaultWorld.getSpawnLocation();

        for (Player player : world.getPlayers()) {
            player.teleport(spawn);
            player.sendMessage(ChatColor.GREEN + "[ResourceWorldResetter] " +
                    "You have been teleported to safety - the resource world is being reset.");
            LogUtil.log(getLogger(), "Teleported " + player.getName() + " out of resource world", Level.INFO);
        }
    }

    @EventHandler
    public void onEntitySpawn(EntitySpawnEvent event) {
        if (!(event.getEntity() instanceof EnderDragon)) {
            return;
        }

        if (shouldDisableDragonSpawn(event.getLocation().getWorld())) {
            event.setCancelled(true);
        }
    }

    public void recreateWorld() {
        ResourceWorldSettings settings = resourceWorlds.get(OVERWORLD_RESOURCE_KEY);
        if (settings != null) {
            recreateWorld(settings);
        }
    }

    private boolean recreateWorld(ResourceWorldSettings settings) {
        World recreatedWorld = null;
        boolean success = addWorld(settings);
        if (success) {
            recreatedWorld = Bukkit.getWorld(settings.name);
        }
        if (!success) {
            recreatedWorld = Bukkit.createWorld(new WorldCreator(settings.name)
                    .environment(settings.environment)
                    .type(settings.worldType));
            success = recreatedWorld != null;
        }

        if (success) {
            applyConfiguredGameRulesWithRetry(settings, recreatedWorld);
            removeEnderDragonsIfDisabled(settings, recreatedWorld);
            ensureMultiverseWorldRegisteredWithRetry(settings, recreatedWorld);
            Bukkit.broadcastMessage(ChatColor.GREEN + "[ResourceWorldResetter] " +
                    "The resource world '" + settings.name + "' has been reset and is ready to use!");
            LogUtil.log(getLogger(), "World recreation successful: " + settings.name, Level.INFO);
        } else {
            Bukkit.broadcastMessage(ChatColor.RED + "[ResourceWorldResetter] " +
                    "Failed to recreate the resource world '" + settings.name + "'!");
            LogUtil.log(getLogger(), "Failed to recreate world: " + settings.name, Level.SEVERE);
        }
        return success;
    }

    public void ensureResourceWorldExists() {
        ResourceWorldSettings settings = resourceWorlds.get(OVERWORLD_RESOURCE_KEY);
        if (settings != null) {
            ensureResourceWorldExists(settings);
        }
    }

    private void ensureResourceWorldsExist() {
        for (ResourceWorldSettings settings : getEnabledResourceWorlds()) {
            ensureResourceWorldExists(settings);
        }
    }

    private void ensureResourceWorldExists(ResourceWorldSettings settings) {
        if (Bukkit.getWorld(settings.name) == null) {
            LogUtil.log(getLogger(), "Resource world doesn't exist, creating: " + settings.name, Level.INFO);
            World createdWorld = null;
            boolean success = addWorld(settings);
            if (success) {
                createdWorld = Bukkit.getWorld(settings.name);
            }
            if (!success) {
                createdWorld = Bukkit.createWorld(new WorldCreator(settings.name)
                        .environment(settings.environment)
                        .type(settings.worldType));
                success = createdWorld != null;
            }
            if (success) {
                applyConfiguredGameRulesWithRetry(settings, createdWorld);
                removeEnderDragonsIfDisabled(settings, createdWorld);
                ensureMultiverseWorldRegisteredWithRetry(settings, createdWorld);
            }
            LogUtil.log(getLogger(), "Created resource world: " + settings.name + ", Success: " + success, Level.INFO);
        } else {
            LogUtil.log(getLogger(), "Resource world exists: " + settings.name, Level.INFO);
            World world = Bukkit.getWorld(settings.name);
            applyConfiguredGameRulesWithRetry(settings, world);
            removeEnderDragonsIfDisabled(settings, world);
            ensureMultiverseWorldRegisteredWithRetry(settings, world);
        }
    }

    public void loadConfig() {
        reloadConfig();
        multiverseEnabled = getConfig().getBoolean("multiverse.enabled", true);
        restartTime = getConfig().getInt("restartTime", 3);
        resetWarningTime = getConfig().getInt("resetWarningTime", 5);
        resetIntervalDays = Math.max(1, getConfig().getInt("resetIntervalDays", 1));
        String configuredResetType = getConfig().getString("resetType", "daily");
        Integer intervalDaysFromType = parseIntervalDaysFromResetType(configuredResetType);
        if (intervalDaysFromType != null) {
            resetIntervalDays = intervalDaysFromType;
        }
        resetType = normalizeResetType(configuredResetType);
        resetDay = getConfig().getInt("resetDay", 1);
        nextIntervalReset = parseNextIntervalReset(getConfig().getString("nextIntervalReset", null));
        loadResourceWorldsFromConfig();

        LogUtil.log(getLogger(), "Configuration loaded: worldName=" + worldName +
                ", multiverseEnabled=" + multiverseEnabled +
                ", resetType=" + resetType + ", restartTime=" + restartTime +
                ", resetWarningTime=" + resetWarningTime +
                ", resetIntervalDays=" + resetIntervalDays +
                ", enabledResourceWorlds=" + getEnabledResourceWorlds().size(), Level.INFO);
    }

    private void loadResourceWorldsFromConfig() {
        resourceWorlds.clear();

        Map<String, Object> legacyOverworldGameRules = loadGameRules("worldGameRules");
        Map<String, Object> defaultGameRules = defaultWorldGameRules();
        Map<String, Object> overworldFallbackGameRules = legacyOverworldGameRules.isEmpty()
                ? defaultGameRules
                : legacyOverworldGameRules;

        ResourceWorldSettings overworld = loadResourceWorldSettings(
                OVERWORLD_RESOURCE_KEY,
                "Overworld Resource World",
                getConfig().getString("worldName", "Resources"),
                World.Environment.NORMAL,
                true,
                overworldFallbackGameRules
        );
        resourceWorlds.put(overworld.key, overworld);
        worldName = overworld.name;

        ResourceWorldSettings nether = loadResourceWorldSettings(
                "nether",
                "Nether Resource World",
                "Resources_nether",
                World.Environment.NETHER,
                true,
                defaultGameRules
        );
        resourceWorlds.put(nether.key, nether);

        ResourceWorldSettings end = loadResourceWorldSettings(
                "end",
                "End Resource World",
                "Resources_the_end",
                World.Environment.THE_END,
                true,
                defaultGameRules
        );
        resourceWorlds.put(end.key, end);
    }

    private Map<String, Object> defaultWorldGameRules() {
        Map<String, Object> defaultGameRules = new LinkedHashMap<>();
        defaultGameRules.put("keep_inventory", true);
        return defaultGameRules;
    }

    private ResourceWorldSettings loadResourceWorldSettings(String key, String displayName, String defaultName,
                                                            World.Environment environment, boolean defaultEnabled,
                                                            Map<String, Object> fallbackGameRules) {
        String basePath = "resourceWorlds." + key;
        boolean enabled = getConfig().getBoolean(basePath + ".enabled", defaultEnabled);
        String name = getConfig().getString(basePath + ".name", defaultName);
        boolean disableDragonSpawn = World.Environment.THE_END.equals(environment)
                && getConfig().getBoolean(basePath + ".disableDragonSpawn", false);
        Map<String, Object> gameRules = loadGameRules(basePath + ".gameRules");
        if (gameRules.isEmpty()) {
            gameRules.putAll(fallbackGameRules);
        }
        return new ResourceWorldSettings(key, displayName, enabled, name, environment, WorldType.NORMAL, gameRules, disableDragonSpawn);
    }

    private Map<String, Object> loadGameRules(String path) {
        Map<String, Object> gameRules = new LinkedHashMap<>();
        ConfigurationSection gameRuleSection = getConfig().getConfigurationSection(path);
        if (gameRuleSection == null) {
            return gameRules;
        }

        for (String key : gameRuleSection.getKeys(false)) {
            gameRules.put(key, gameRuleSection.get(key));
        }
        return gameRules;
    }

    private List<ResourceWorldSettings> getEnabledResourceWorlds() {
        List<ResourceWorldSettings> enabledWorlds = new ArrayList<>();
        for (ResourceWorldSettings settings : resourceWorlds.values()) {
            if (settings.enabled) {
                enabledWorlds.add(settings);
            }
        }
        return enabledWorlds;
    }

    private boolean shouldDisableDragonSpawn(World world) {
        if (world == null) {
            return false;
        }

        for (ResourceWorldSettings settings : resourceWorlds.values()) {
            if (settings.shouldDisableDragonSpawnIn(world)) {
                return true;
            }
        }
        return false;
    }

    private void removeEnderDragonsIfDisabled(ResourceWorldSettings settings, World world) {
        if (world == null || !settings.shouldDisableDragonSpawnIn(world)) {
            return;
        }

        int removed = 0;
        for (EnderDragon dragon : world.getEntitiesByClass(EnderDragon.class)) {
            dragon.remove();
            removed++;
        }

        if (removed > 0) {
            LogUtil.log(getLogger(), "Removed " + removed + " Ender Dragon(s) from resource world '" + world.getName() + "'.", Level.INFO);
        }
    }

    private String normalizeResetType(String type) {
        if (type == null) {
            return "daily";
        }

        String normalized = type.trim().toLowerCase(Locale.ROOT).replace("-", "_").replace(" ", "_");
        return switch (normalized) {
            case "daily", "weekly", "monthly", "interval" -> normalized;
            case "every_2_days", "2_days", "two_days" -> "interval";
            case "every_3_days", "3_days", "three_days" -> "interval";
            default -> "daily";
        };
    }

    private Integer parseIntervalDaysFromResetType(String type) {
        if (type == null) {
            return null;
        }

        String normalized = type.trim().toLowerCase(Locale.ROOT).replace("-", "_").replace(" ", "_");
        return switch (normalized) {
            case "every_2_days", "2_days", "two_days" -> 2;
            case "every_3_days", "3_days", "three_days" -> 3;
            default -> null;
        };
    }

    private LocalDateTime parseNextIntervalReset(String configuredValue) {
        if (configuredValue == null || configuredValue.isBlank()) {
            return null;
        }

        try {
            return LocalDateTime.parse(configuredValue.trim()).withSecond(0).withNano(0);
        } catch (DateTimeParseException ex) {
            LogUtil.log(getLogger(), "Invalid nextIntervalReset value '" + configuredValue + "'. Recalculating interval reset schedule.", Level.WARNING);
            return null;
        }
    }

    private void clearNextIntervalReset() {
        nextIntervalReset = null;
        getConfig().set("nextIntervalReset", null);
    }

    private String capitalizeFirstLetter(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        return text.substring(0, 1).toUpperCase(Locale.ROOT) + text.substring(1).toLowerCase(Locale.ROOT);
    }

    private void setupMultiverseIntegration() {
        multiversePlugin = null;
        mvWorldManager = null;

        if (!multiverseEnabled) {
            LogUtil.log(getLogger(), "Multiverse integration disabled by configuration. Using Bukkit world APIs.", Level.INFO);
            return;
        }

        multiversePlugin = Bukkit.getPluginManager().getPlugin("Multiverse-Core");
        if (multiversePlugin == null || !multiversePlugin.isEnabled()) {
            LogUtil.log(getLogger(), "Multiverse integration enabled, but Multiverse-Core is not available. Using Bukkit world APIs.", Level.WARNING);
            return;
        }

        mvWorldManager = resolveWorldManager(multiversePlugin);
        if (mvWorldManager == null) {
            LogUtil.log(getLogger(), "Multiverse-Core found, but its legacy world manager API is unavailable. Using worlds.yml and command import fallback.", Level.WARNING);
            return;
        }

        LogUtil.log(getLogger(), "Multiverse integration enabled.", Level.INFO);
    }

    private void applyConfiguredGameRulesWithRetry(ResourceWorldSettings settings, World world) {
        if (world != null) {
            applyConfiguredGameRules(settings, world);
            return;
        }

        Bukkit.getScheduler().runTaskLater(this, () -> {
            World delayedWorld = Bukkit.getWorld(settings.name);
            if (delayedWorld == null) {
                LogUtil.log(getLogger(), "Could not find world '" + settings.name + "' to apply configured gamerules.", Level.WARNING);
                return;
            }
            applyConfiguredGameRules(settings, delayedWorld);
        }, 20L);
    }

    private void applyConfiguredGameRules(ResourceWorldSettings settings, World world) {
        if (settings.gameRules.isEmpty()) {
            return;
        }

        int applied = 0;
        int failed = 0;

        for (Map.Entry<String, Object> entry : settings.gameRules.entrySet()) {
            String configuredName = entry.getKey();
            GameRule<?> gameRule = resolveGameRule(configuredName);
            if (gameRule == null) {
                LogUtil.log(getLogger(), "Unknown gamerule '" + configuredName + "' in config. Skipping.", Level.WARNING);
                failed++;
                continue;
            }

            if (setGameRuleValue(world, gameRule, entry.getValue())) {
                applied++;
            } else {
                failed++;
            }
        }

        LogUtil.log(getLogger(), "Applied gamerules for world '" + world.getName() + "': success=" + applied + ", failed=" + failed, Level.INFO);
        applyMultiverseKeepInventoryIfConfigured(settings, world);
    }

    private void ensureMultiverseWorldRegisteredWithRetry(ResourceWorldSettings settings, World world) {
        if (!isMultiverseUsable()) {
            return;
        }

        if (world != null) {
            ensureMultiverseWorldRegistered(settings, world);
            return;
        }

        Bukkit.getScheduler().runTaskLater(this, () -> ensureMultiverseWorldRegistered(settings, Bukkit.getWorld(settings.name)), 20L);
    }

    private void ensureMultiverseWorldRegistered(ResourceWorldSettings settings, World world) {
        if (!isMultiverseUsable()) {
            return;
        }

        String targetWorldName = world != null ? world.getName() : settings.name;
        if (targetWorldName == null || targetWorldName.isBlank()) {
            return;
        }

        if (isMultiverseWorldRegistered(targetWorldName)) {
            applyMultiverseKeepInventoryIfConfigured(settings, world);
            return;
        }

        boolean registered = addWorld(settings);
        if (!registered) {
            registered = importWorldWithMultiverseCommand(settings);
        }
        if (!registered) {
            registered = isMultiverseWorldRegistered(targetWorldName);
        }

        if (registered) {
            LogUtil.log(getLogger(), "Registered resource world '" + targetWorldName + "' with Multiverse.", Level.INFO);
            applyMultiverseKeepInventoryIfConfigured(settings, world);
        } else {
            LogUtil.log(getLogger(), "Could not register resource world '" + targetWorldName + "' with Multiverse.", Level.WARNING);
        }
    }

    private boolean isMultiverseWorldRegistered(String targetWorldName) {
        if (isWorldListedInMultiverseConfig(targetWorldName)) {
            return true;
        }

        if (mvWorldManager == null) {
            return false;
        }

        Boolean isWorld = firstNonNull(
                invokeBooleanMethod(mvWorldManager, "isMVWorld", targetWorldName),
                invokeBooleanMethod(mvWorldManager, "isMVWorld", Bukkit.getWorld(targetWorldName))
        );
        if (isWorld != null) {
            return isWorld;
        }

        return getMultiverseWorld(targetWorldName) != null;
    }

    private boolean isWorldListedInMultiverseConfig(String targetWorldName) {
        if (multiversePlugin == null || targetWorldName == null || targetWorldName.isBlank()) {
            return false;
        }

        File worldsFile = new File(multiversePlugin.getDataFolder(), "worlds.yml");
        if (!worldsFile.isFile()) {
            return false;
        }

        ConfigurationSection worldsSection = YamlConfiguration.loadConfiguration(worldsFile).getConfigurationSection("worlds");
        if (worldsSection == null) {
            return false;
        }

        for (String worldNameKey : worldsSection.getKeys(false)) {
            if (worldNameKey.equalsIgnoreCase(targetWorldName)) {
                return true;
            }
        }
        return false;
    }

    private Object getMultiverseWorld(String targetWorldName) {
        if (mvWorldManager == null) {
            return null;
        }

        return firstNonNull(
                invokeMethod(mvWorldManager, "getMVWorld", targetWorldName),
                invokeMethod(mvWorldManager, "getMVWorld", Bukkit.getWorld(targetWorldName))
        );
    }

    private boolean importWorldWithMultiverseCommand(ResourceWorldSettings settings) {
        if (!isMultiverseUsable()) {
            return false;
        }

        String targetWorldName = settings.name;
        if (isMultiverseWorldRegistered(targetWorldName)) {
            return true;
        }

        if (!targetWorldName.matches("[A-Za-z0-9_./-]+")) {
            LogUtil.log(getLogger(), "Skipping Multiverse command import for world name with unsupported command characters: " + targetWorldName, Level.WARNING);
            return false;
        }

        boolean dispatched = Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "mv import " + targetWorldName + " " + getMultiverseCommandEnvironmentName(settings));
        return dispatched && isMultiverseWorldRegistered(targetWorldName);
    }

    private void applyMultiverseKeepInventoryIfConfigured(ResourceWorldSettings settings, World world) {
        if (!isMultiverseUsable() || !isKeepInventoryConfiguredTrue(settings)) {
            return;
        }

        if (world != null) {
            GameRule<?> keepInventoryRule = resolveGameRule("keepInventory");
            if (keepInventoryRule != null) {
                setGameRuleValue(world, keepInventoryRule, true);
            }
        }

        Object mvWorld = getMultiverseWorld(world != null ? world.getName() : settings.name);
        if (mvWorld == null) {
            return;
        }

        boolean updated = setMultiverseWorldProperty(mvWorld, "keepInventory", "true")
                || setMultiverseWorldProperty(mvWorld, "keep-inventory", "true")
                || setMultiverseWorldProperty(mvWorld, "keepinventory", "true");

        if (updated) {
            LogUtil.log(getLogger(), "Applied Multiverse keep-inventory property for world '" + (world != null ? world.getName() : settings.name) + "'.", Level.INFO);
        }
    }

    private boolean setMultiverseWorldProperty(Object mvWorld, String property, String value) {
        Method method = findCompatibleMethod(mvWorld.getClass(), "setPropertyValue", property, value);
        if (method == null) {
            return false;
        }

        try {
            Object result = method.invoke(mvWorld, property, value);
            return !(result instanceof Boolean booleanResult) || booleanResult;
        } catch (IllegalAccessException | InvocationTargetException ignored) {
            return false;
        }
    }

    private boolean isKeepInventoryConfiguredTrue(ResourceWorldSettings settings) {
        for (Map.Entry<String, Object> entry : settings.gameRules.entrySet()) {
            if ("keepinventory".equals(normalizeGameRuleName(entry.getKey()))) {
                return Boolean.TRUE.equals(parseBoolean(entry.getValue()));
            }
        }
        return false;
    }

    private GameRule<?> resolveGameRule(String configuredName) {
        String normalizedConfiguredName = normalizeGameRuleName(configuredName);
        for (GameRule<?> gameRule : Registry.GAME_RULE) {
            if (normalizeGameRuleName(gameRule.getKey().getKey()).equals(normalizedConfiguredName)) {
                return gameRule;
            }
        }
        return null;
    }

    private String normalizeGameRuleName(String name) {
        if (name == null) {
            return "";
        }
        return name.replaceAll("[^A-Za-z0-9]", "").toLowerCase(Locale.ROOT);
    }

    @SuppressWarnings("unchecked")
    private boolean setGameRuleValue(World world, GameRule<?> gameRule, Object configuredValue) {
        Class<?> type = gameRule.getType();

        if (type == Boolean.class) {
            Boolean parsedValue = parseBoolean(configuredValue);
            if (parsedValue == null) {
                LogUtil.log(getLogger(), "Invalid boolean value '" + configuredValue + "' for gamerule '" + gameRule.getKey().getKey() + "'.", Level.WARNING);
                return false;
            }
            return world.setGameRule((GameRule<Boolean>) gameRule, parsedValue);
        }

        if (type == Integer.class) {
            Integer parsedValue = parseInteger(configuredValue);
            if (parsedValue == null) {
                LogUtil.log(getLogger(), "Invalid integer value '" + configuredValue + "' for gamerule '" + gameRule.getKey().getKey() + "'.", Level.WARNING);
                return false;
            }
            return world.setGameRule((GameRule<Integer>) gameRule, parsedValue);
        }

        LogUtil.log(getLogger(), "Unsupported gamerule type '" + type.getSimpleName() + "' for '" + gameRule.getKey().getKey() + "'.", Level.WARNING);
        return false;
    }

    private Boolean parseBoolean(Object value) {
        if (value instanceof Boolean booleanValue) {
            return booleanValue;
        }
        if (value instanceof Number numberValue) {
            return numberValue.intValue() != 0;
        }
        if (value instanceof String stringValue) {
            String normalized = stringValue.trim().toLowerCase(Locale.ROOT);
            switch (normalized) {
                case "true":
                case "1":
                case "yes":
                case "on":
                    return true;
                case "false":
                case "0":
                case "no":
                case "off":
                    return false;
                default:
                    return null;
            }
        }
        return null;
    }

    private Integer parseInteger(Object value) {
        if (value instanceof Number numberValue) {
            return numberValue.intValue();
        }
        if (value instanceof String stringValue) {
            try {
                return Integer.parseInt(stringValue.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    public Set<String> getAvailableWorldNames() {
        LinkedHashSet<String> worldNames = new LinkedHashSet<>();

        Object mvWorlds = invokeMethod(mvWorldManager, "getMVWorlds");
        if (mvWorlds instanceof Iterable<?> iterable) {
            for (Object mvWorld : iterable) {
                String name = getWorldNameFromMultiverseWorld(mvWorld);
                if (name != null && !name.isBlank()) {
                    worldNames.add(name);
                }
            }
        }

        for (World world : Bukkit.getWorlds()) {
            worldNames.add(world.getName());
        }

        return worldNames;
    }

    private String getWorldNameFromMultiverseWorld(Object mvWorld) {
        Object name = invokeMethod(mvWorld, "getName");
        if (name instanceof String worldNameValue) {
            return worldNameValue;
        }

        Object cbWorld = invokeMethod(mvWorld, "getCBWorld");
        if (cbWorld instanceof World world) {
            return world.getName();
        }
        return null;
    }

    private Object resolveWorldManager(Plugin plugin) {
        Object manager = invokeMethod(plugin, "getMVWorldManager");
        if (manager == null) {
            LogUtil.log(getLogger(), "Multiverse plugin found but getMVWorldManager is unavailable.", Level.WARNING);
        }
        return manager;
    }

    private boolean unloadWorld(ResourceWorldSettings settings, World world) {
        if (!isMultiverseUsable()) {
            return Bukkit.unloadWorld(world, true);
        }

        Boolean unloaded = firstNonNull(
                invokeBooleanMethod(mvWorldManager, "unloadWorld", settings.name),
                invokeBooleanMethod(mvWorldManager, "unloadWorld", world)
        );

        if (unloaded != null) {
            return unloaded;
        }

        return Bukkit.unloadWorld(world, true);
    }

    private boolean forceUnloadWorld(ResourceWorldSettings settings, World world) {
        if (!isMultiverseUsable()) {
            return Bukkit.unloadWorld(world, true);
        }

        Boolean unloaded = firstNonNull(
                invokeBooleanMethod(mvWorldManager, "unloadWorld", settings.name, true),
                invokeBooleanMethod(mvWorldManager, "unloadWorld", settings.name, false),
                invokeBooleanMethod(mvWorldManager, "unloadWorld", world, true)
        );

        if (unloaded != null) {
            return unloaded;
        }

        return Bukkit.unloadWorld(world, true);
    }

    private boolean addWorld(ResourceWorldSettings settings) {
        if (!isMultiverseUsable() || mvWorldManager == null) {
            return false;
        }

        Boolean added = firstNonNull(
                invokeBooleanMethod(mvWorldManager, "addWorld", settings.name, settings.environment, null, settings.worldType, true, "DEFAULT"),
                invokeBooleanMethod(mvWorldManager, "addWorld", settings.name, getMultiverseEnvironmentName(settings), null, settings.worldType.name(), true, "DEFAULT"),
                invokeBooleanMethod(mvWorldManager, "addWorld", settings.name, settings.environment, null, settings.worldType, true),
                invokeBooleanMethod(mvWorldManager, "addWorld", settings.name, getMultiverseEnvironmentName(settings), null, settings.worldType.name(), true)
        );

        return Boolean.TRUE.equals(added);
    }

    private String getMultiverseEnvironmentName(ResourceWorldSettings settings) {
        if (settings.environment == World.Environment.THE_END) {
            return "END";
        }
        return settings.environment.name();
    }

    private String getMultiverseCommandEnvironmentName(ResourceWorldSettings settings) {
        if (settings.environment == World.Environment.THE_END) {
            return "THE_END";
        }
        return settings.environment.name();
    }

    private boolean isMultiverseUsable() {
        return multiverseEnabled && multiversePlugin != null && multiversePlugin.isEnabled();
    }

    private boolean deleteFolder(File folder) {
        if (!folder.exists()) {
            return true;
        }

        File[] files = folder.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.isDirectory()) {
                    if (!deleteFolder(file)) {
                        return false;
                    }
                } else if (!file.delete()) {
                    return false;
                }
            }
        }
        return folder.delete();
    }

    private Object invokeMethod(Object target, String methodName, Object... args) {
        if (target == null) {
            return null;
        }

        Method method = findCompatibleMethod(target.getClass(), methodName, args);
        if (method == null) {
            return null;
        }

        try {
            return method.invoke(target, args);
        } catch (IllegalAccessException | InvocationTargetException ex) {
            LogUtil.log(getLogger(), "Failed to invoke method '" + methodName + "' on " +
                    target.getClass().getName() + ": " + ex.getMessage(), Level.WARNING);
            return null;
        }
    }

    private Boolean invokeBooleanMethod(Object target, String methodName, Object... args) {
        Object result = invokeMethod(target, methodName, args);
        if (result instanceof Boolean booleanValue) {
            return booleanValue;
        }
        return null;
    }

    private Method findCompatibleMethod(Class<?> clazz, String methodName, Object... args) {
        for (Method method : clazz.getMethods()) {
            if (!method.getName().equals(methodName) || method.getParameterCount() != args.length) {
                continue;
            }

            Class<?>[] parameterTypes = method.getParameterTypes();
            boolean compatible = true;

            for (int i = 0; i < parameterTypes.length; i++) {
                Object arg = args[i];
                Class<?> parameterType = wrapPrimitive(parameterTypes[i]);

                if (arg == null) {
                    if (parameterTypes[i].isPrimitive()) {
                        compatible = false;
                        break;
                    }
                    continue;
                }

                if (!parameterType.isAssignableFrom(arg.getClass())) {
                    compatible = false;
                    break;
                }
            }

            if (compatible) {
                return method;
            }
        }
        return null;
    }

    private Class<?> wrapPrimitive(Class<?> type) {
        if (!type.isPrimitive()) {
            return type;
        }
        if (type == boolean.class) {
            return Boolean.class;
        }
        if (type == int.class) {
            return Integer.class;
        }
        if (type == long.class) {
            return Long.class;
        }
        if (type == double.class) {
            return Double.class;
        }
        if (type == float.class) {
            return Float.class;
        }
        if (type == byte.class) {
            return Byte.class;
        }
        if (type == short.class) {
            return Short.class;
        }
        if (type == char.class) {
            return Character.class;
        }
        return type;
    }

    private static final class ResourceWorldSettings {
        private final String key;
        private final String displayName;
        private final boolean enabled;
        private String name;
        private final World.Environment environment;
        private final WorldType worldType;
        private final Map<String, Object> gameRules;
        private final boolean disableDragonSpawn;

        private ResourceWorldSettings(String key, String displayName, boolean enabled, String name,
                                      World.Environment environment, WorldType worldType,
                                      Map<String, Object> gameRules, boolean disableDragonSpawn) {
            this.key = key;
            this.displayName = displayName;
            this.enabled = enabled;
            this.name = name;
            this.environment = environment;
            this.worldType = worldType;
            this.gameRules = gameRules;
            this.disableDragonSpawn = disableDragonSpawn;
        }

        private boolean shouldDisableDragonSpawnIn(World world) {
            return enabled
                    && disableDragonSpawn
                    && environment == World.Environment.THE_END
                    && world != null
                    && name.equals(world.getName());
        }
    }

    @SafeVarargs
    private final <T> T firstNonNull(T... values) {
        for (T value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
    }
}
