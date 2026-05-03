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
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;

public class ResourceWorldResetter extends JavaPlugin {
    private String worldName;
    private boolean multiverseEnabled;
    private Plugin multiversePlugin;
    private Object mvWorldManager;
    private int restartTime;
    private int resetWarningTime;
    private String resetType;
    private int resetDay;
    private AdminGUI adminGUI;
    private final Map<String, Object> configuredGameRules = new LinkedHashMap<>();
    private int warningTaskId = -1;
    private int resetTaskId = -1;

    public String getWorldName() { return this.worldName; }
    public String getResetType() { return this.resetType; }
    public int getRestartTime() { return this.restartTime; }
    public int getResetWarningTime() { return this.resetWarningTime; }
    public int getResetDay() { return this.resetDay; }
    public boolean isMultiverseEnabled() { return this.multiverseEnabled; }

    public void setWorldName(String name) {
        this.worldName = name;
        getConfig().set("worldName", name);
        saveConfig();
        ensureResourceWorldExists();
    }

    public void setResetType(String type) {
        this.resetType = type;
        getConfig().set("resetType", type);
        saveConfig();
        scheduleDailyReset();
    }

    public void setResetDay(int day) {
        this.resetDay = day;
        getConfig().set("resetDay", day);
        saveConfig();
        scheduleDailyReset();
    }

    public void setRestartTime(int hour) {
        if (hour >= 0 && hour <= 23) {
            this.restartTime = hour;
            getConfig().set("restartTime", hour);
            saveConfig();
            scheduleDailyReset();

            LogUtil.log(getLogger(), "Restart time set to " + hour + ":00", Level.INFO);
        }
    }

    public void setResetWarningTime(int minutes) {
        if (minutes >= 0) {
            this.resetWarningTime = minutes;
            getConfig().set("resetWarningTime", minutes);
            saveConfig();
            scheduleDailyReset();

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
            metrics.addCustomChart(new Metrics.SimplePie("server_version", Bukkit::getBukkitVersion));
            LogUtil.log(getLogger(), "bStats metrics enabled", Level.INFO);
        } else {
            LogUtil.log(getLogger(), "bStats metrics disabled by configuration", Level.INFO);
        }

        adminGUI = new AdminGUI(this);
        getServer().getPluginManager().registerEvents(new AdminGUIListener(this, adminGUI), this);

        ensureResourceWorldExists();
        applyConfiguredGameRulesWithRetry(Bukkit.getWorld(worldName));
        ensureMultiverseWorldRegisteredWithRetry(Bukkit.getWorld(worldName));
        scheduleDailyReset();
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
                    scheduleDailyReset();
                    applyConfiguredGameRulesWithRetry(Bukkit.getWorld(worldName));
                    ensureMultiverseWorldRegisteredWithRetry(Bukkit.getWorld(worldName));
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

    private void scheduleDailyReset() {
        cancelScheduledTasks();

        LocalDateTime now = LocalDateTime.now();
        LocalDateTime nextReset = now.withHour(restartTime).withMinute(0).withSecond(0);

        if (now.compareTo(nextReset) >= 0) {
            nextReset = nextReset.plusDays(1);
        }

        if ("weekly".equals(resetType)) {
            int currentDay = now.getDayOfWeek().getValue();
            int daysUntilReset = (resetDay - currentDay + 7) % 7;
            if (daysUntilReset == 0 && now.compareTo(nextReset) >= 0) {
                daysUntilReset = 7;
            }
            nextReset = nextReset.plusDays(daysUntilReset);
            LogUtil.log(getLogger(), "Scheduled weekly reset for " + nextReset, Level.INFO);
        } else if ("monthly".equals(resetType)) {
            LocalDateTime nextMonth = now;
            if (now.getDayOfMonth() > resetDay || (now.getDayOfMonth() == resetDay && now.compareTo(nextReset) >= 0)) {
                nextMonth = now.plusMonths(1);
            }

            int maxDay = nextMonth.getMonth().length(nextMonth.toLocalDate().isLeapYear());
            int actualResetDay = Math.min(resetDay, maxDay);
            nextReset = nextMonth.withDayOfMonth(actualResetDay).withHour(restartTime).withMinute(0).withSecond(0);
            LogUtil.log(getLogger(), "Scheduled monthly reset for " + nextReset, Level.INFO);
        } else {
            LogUtil.log(getLogger(), "Scheduled daily reset for " + nextReset, Level.INFO);
        }

        long resetDelayTicks = Math.max(1, ChronoUnit.SECONDS.between(now, nextReset) * 20);

        if (resetWarningTime > 0) {
            LocalDateTime warningTime = nextReset.minusMinutes(resetWarningTime);

            if (now.isBefore(warningTime)) {
                long warningDelayTicks = Math.max(1, ChronoUnit.SECONDS.between(now, warningTime) * 20);

                LogUtil.log(getLogger(), "Warning scheduled for " + warningTime +
                        " (" + (warningDelayTicks / 20 / 60) + " minutes from now)", Level.INFO);

                warningTaskId = Bukkit.getScheduler().runTaskLater(this, () -> {
                    Bukkit.broadcastMessage(ChatColor.YELLOW + "[ResourceWorldResetter] " +
                            "Resource world will reset in " + resetWarningTime + " minutes!");
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

    public void resetResourceWorld() {
        resetResourceWorld(false);
    }

    public void resetResourceWorld(boolean isScheduled) {
        World world = Bukkit.getWorld(worldName);
        if (world == null) {
            LogUtil.log(getLogger(), "World '" + worldName + "' not found! Attempting to create it...", Level.WARNING);
            ensureResourceWorldExists();
            world = Bukkit.getWorld(worldName);

            if (world == null) {
                LogUtil.log(getLogger(), "Failed to create world '" + worldName + "'! Reset aborted.", Level.SEVERE);
                return;
            }
        }

        if (!isScheduled || resetWarningTime <= 0) {
            performReset(world);
        } else {
            performReset(world);
        }
    }

    private void performReset(World world) {
        double tpsBefore = getServerTPS();
        long startTime = System.currentTimeMillis();

        LogUtil.log(getLogger(), "Starting world reset process for " + worldName, Level.INFO);
        Bukkit.broadcastMessage(ChatColor.RED + "[ResourceWorldResetter] " +
                "Resource world reset in progress. Players in that world will be teleported to safety.");

        teleportPlayersSafely(world);

        if (!unloadWorld(world)) {
            LogUtil.log(getLogger(), "Failed to unload world: " + worldName + ". Retrying with forced unload.", Level.WARNING);

            if (!forceUnloadWorld(world)) {
                LogUtil.log(getLogger(), "Forced unload also failed. Aborting reset.", Level.SEVERE);
                Bukkit.broadcastMessage(ChatColor.RED + "[ResourceWorldResetter] " +
                        "Failed to reset resource world. Please notify an administrator.");
                return;
            }
        }

        CompletableFuture.runAsync(() -> {
            File worldFolder = new File(Bukkit.getWorldContainer(), worldName);
            LogUtil.log(getLogger(), "Deleting world folder: " + worldFolder.getAbsolutePath(), Level.INFO);

            if (deleteFolder(worldFolder)) {
                Bukkit.getScheduler().runTask(this, () -> {
                    LogUtil.log(getLogger(), "World folder deleted, recreating world", Level.INFO);
                    recreateWorld();
                    long duration = System.currentTimeMillis() - startTime;
                    double tpsAfter = getServerTPS();
                    Bukkit.broadcastMessage(ChatColor.GREEN + "[ResourceWorldResetter] " +
                            "Resource world reset completed in " + (duration / 1000) + " seconds (TPS: " +
                            String.format("%.2f", tpsBefore) + " -> " + String.format("%.2f", tpsAfter) + ").");
                    LogUtil.log(getLogger(), "Resource world reset completed in " + duration + "ms", Level.INFO);

                    if (resetTaskId != -1) {
                        Bukkit.getScheduler().runTaskLater(this, this::scheduleDailyReset, 20);
                    }
                });
            } else {
                LogUtil.log(getLogger(), "Failed to delete world folder: " + worldName, Level.SEVERE);
                Bukkit.getScheduler().runTask(this, () -> Bukkit.broadcastMessage(ChatColor.RED + "[ResourceWorldResetter] " +
                        "Resource world reset failed! Check server logs for details."));
            }
        });
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

    public void recreateWorld() {
        World recreatedWorld = null;
        boolean success = addWorld(worldName);
        if (success) {
            recreatedWorld = Bukkit.getWorld(worldName);
        }
        if (!success) {
            recreatedWorld = Bukkit.createWorld(new WorldCreator(worldName)
                    .environment(World.Environment.NORMAL)
                    .type(WorldType.NORMAL));
            success = recreatedWorld != null;
        }

        if (success) {
            applyConfiguredGameRulesWithRetry(recreatedWorld);
            ensureMultiverseWorldRegisteredWithRetry(recreatedWorld);
            Bukkit.broadcastMessage(ChatColor.GREEN + "[ResourceWorldResetter] " +
                    "The resource world has been reset and is ready to use!");
            LogUtil.log(getLogger(), "World recreation successful", Level.INFO);
        } else {
            Bukkit.broadcastMessage(ChatColor.RED + "[ResourceWorldResetter] " +
                    "Failed to recreate the resource world!");
            LogUtil.log(getLogger(), "Failed to recreate world: " + worldName, Level.SEVERE);
        }
    }

    public void ensureResourceWorldExists() {
        if (Bukkit.getWorld(worldName) == null) {
            LogUtil.log(getLogger(), "Resource world doesn't exist, creating: " + worldName, Level.INFO);
            World createdWorld = null;
            boolean success = addWorld(worldName);
            if (success) {
                createdWorld = Bukkit.getWorld(worldName);
            }
            if (!success) {
                createdWorld = Bukkit.createWorld(new WorldCreator(worldName)
                        .environment(World.Environment.NORMAL)
                        .type(WorldType.NORMAL));
                success = createdWorld != null;
            }
            if (success) {
                applyConfiguredGameRulesWithRetry(createdWorld);
                ensureMultiverseWorldRegisteredWithRetry(createdWorld);
            }
            LogUtil.log(getLogger(), "Created resource world: " + worldName + ", Success: " + success, Level.INFO);
        } else {
            LogUtil.log(getLogger(), "Resource world exists: " + worldName, Level.INFO);
            ensureMultiverseWorldRegisteredWithRetry(Bukkit.getWorld(worldName));
        }
    }

    public void loadConfig() {
        reloadConfig();
        worldName = getConfig().getString("worldName", "Resources");
        multiverseEnabled = getConfig().getBoolean("multiverse.enabled", true);
        restartTime = getConfig().getInt("restartTime", 3);
        resetWarningTime = getConfig().getInt("resetWarningTime", 5);
        resetType = getConfig().getString("resetType", "daily");
        resetDay = getConfig().getInt("resetDay", 1);
        loadGameRulesFromConfig();

        LogUtil.log(getLogger(), "Configuration loaded: worldName=" + worldName +
                ", multiverseEnabled=" + multiverseEnabled +
                ", resetType=" + resetType + ", restartTime=" + restartTime +
                ", resetWarningTime=" + resetWarningTime +
                ", gameRules=" + configuredGameRules.size(), Level.INFO);
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
            LogUtil.log(getLogger(), "Multiverse-Core found, but its legacy world manager API is unavailable. Command import fallback remains enabled.", Level.WARNING);
            return;
        }

        LogUtil.log(getLogger(), "Multiverse integration enabled.", Level.INFO);
    }

    private void loadGameRulesFromConfig() {
        configuredGameRules.clear();

        ConfigurationSection gameRuleSection = getConfig().getConfigurationSection("worldGameRules");
        if (gameRuleSection == null) {
            return;
        }

        for (String key : gameRuleSection.getKeys(false)) {
            configuredGameRules.put(key, gameRuleSection.get(key));
        }
    }

    private void applyConfiguredGameRulesWithRetry(World world) {
        if (world != null) {
            applyConfiguredGameRules(world);
            return;
        }

        Bukkit.getScheduler().runTaskLater(this, () -> {
            World delayedWorld = Bukkit.getWorld(worldName);
            if (delayedWorld == null) {
                LogUtil.log(getLogger(), "Could not find world '" + worldName + "' to apply configured gamerules.", Level.WARNING);
                return;
            }
            applyConfiguredGameRules(delayedWorld);
        }, 20L);
    }

    private void applyConfiguredGameRules(World world) {
        if (configuredGameRules.isEmpty()) {
            return;
        }

        int applied = 0;
        int failed = 0;

        for (Map.Entry<String, Object> entry : configuredGameRules.entrySet()) {
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
        applyMultiverseKeepInventoryIfConfigured(world);
    }

    private void ensureMultiverseWorldRegisteredWithRetry(World world) {
        if (!isMultiverseUsable()) {
            return;
        }

        if (world != null) {
            ensureMultiverseWorldRegistered(world);
            return;
        }

        Bukkit.getScheduler().runTaskLater(this, () -> ensureMultiverseWorldRegistered(Bukkit.getWorld(worldName)), 20L);
    }

    private void ensureMultiverseWorldRegistered(World world) {
        if (!isMultiverseUsable()) {
            return;
        }

        String targetWorldName = world != null ? world.getName() : worldName;
        if (targetWorldName == null || targetWorldName.isBlank()) {
            return;
        }

        if (isMultiverseWorldRegistered(targetWorldName)) {
            applyMultiverseKeepInventoryIfConfigured(world);
            return;
        }

        boolean registered = addWorld(targetWorldName);
        if (!registered) {
            registered = importWorldWithMultiverseCommand(targetWorldName);
        }
        if (!registered) {
            registered = isMultiverseWorldRegistered(targetWorldName);
        }

        if (registered) {
            LogUtil.log(getLogger(), "Registered resource world '" + targetWorldName + "' with Multiverse.", Level.INFO);
            applyMultiverseKeepInventoryIfConfigured(world);
        } else {
            LogUtil.log(getLogger(), "Could not register resource world '" + targetWorldName + "' with Multiverse.", Level.WARNING);
        }
    }

    private boolean isMultiverseWorldRegistered(String targetWorldName) {
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

    private Object getMultiverseWorld(String targetWorldName) {
        if (mvWorldManager == null) {
            return null;
        }

        return firstNonNull(
                invokeMethod(mvWorldManager, "getMVWorld", targetWorldName),
                invokeMethod(mvWorldManager, "getMVWorld", Bukkit.getWorld(targetWorldName))
        );
    }

    private boolean importWorldWithMultiverseCommand(String targetWorldName) {
        if (!isMultiverseUsable()) {
            return false;
        }

        if (!targetWorldName.matches("[A-Za-z0-9_./-]+")) {
            LogUtil.log(getLogger(), "Skipping Multiverse command import for world name with unsupported command characters: " + targetWorldName, Level.WARNING);
            return false;
        }

        boolean dispatched = Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "mv import " + targetWorldName + " NORMAL");
        return dispatched && (mvWorldManager == null || isMultiverseWorldRegistered(targetWorldName));
    }

    private void applyMultiverseKeepInventoryIfConfigured(World world) {
        if (!isMultiverseUsable() || !isKeepInventoryConfiguredTrue()) {
            return;
        }

        if (world != null) {
            GameRule<?> keepInventoryRule = resolveGameRule("keepInventory");
            if (keepInventoryRule != null) {
                setGameRuleValue(world, keepInventoryRule, true);
            }
        }

        Object mvWorld = getMultiverseWorld(world != null ? world.getName() : worldName);
        if (mvWorld == null) {
            return;
        }

        boolean updated = setMultiverseWorldProperty(mvWorld, "keepInventory", "true")
                || setMultiverseWorldProperty(mvWorld, "keep-inventory", "true")
                || setMultiverseWorldProperty(mvWorld, "keepinventory", "true");

        if (updated) {
            LogUtil.log(getLogger(), "Applied Multiverse keep-inventory property for world '" + (world != null ? world.getName() : worldName) + "'.", Level.INFO);
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

    private boolean isKeepInventoryConfiguredTrue() {
        for (Map.Entry<String, Object> entry : configuredGameRules.entrySet()) {
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

    private boolean unloadWorld(World world) {
        if (!isMultiverseUsable()) {
            return Bukkit.unloadWorld(world, true);
        }

        Boolean unloaded = firstNonNull(
                invokeBooleanMethod(mvWorldManager, "unloadWorld", worldName),
                invokeBooleanMethod(mvWorldManager, "unloadWorld", world)
        );

        if (unloaded != null) {
            return unloaded;
        }

        return Bukkit.unloadWorld(world, true);
    }

    private boolean forceUnloadWorld(World world) {
        if (!isMultiverseUsable()) {
            return Bukkit.unloadWorld(world, true);
        }

        Boolean unloaded = firstNonNull(
                invokeBooleanMethod(mvWorldManager, "unloadWorld", worldName, true),
                invokeBooleanMethod(mvWorldManager, "unloadWorld", worldName, false),
                invokeBooleanMethod(mvWorldManager, "unloadWorld", world, true)
        );

        if (unloaded != null) {
            return unloaded;
        }

        return Bukkit.unloadWorld(world, true);
    }

    private boolean addWorld(String targetWorldName) {
        if (!isMultiverseUsable() || mvWorldManager == null) {
            return false;
        }

        Boolean added = firstNonNull(
                invokeBooleanMethod(mvWorldManager, "addWorld", targetWorldName, World.Environment.NORMAL, null, WorldType.NORMAL, true, "DEFAULT"),
                invokeBooleanMethod(mvWorldManager, "addWorld", targetWorldName, "NORMAL", null, "NORMAL", true, "DEFAULT"),
                invokeBooleanMethod(mvWorldManager, "addWorld", targetWorldName, World.Environment.NORMAL, null, WorldType.NORMAL, true),
                invokeBooleanMethod(mvWorldManager, "addWorld", targetWorldName, "NORMAL", null, "NORMAL", true)
        );

        return Boolean.TRUE.equals(added);
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
