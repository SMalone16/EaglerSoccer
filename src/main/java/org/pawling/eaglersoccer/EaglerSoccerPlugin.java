package org.pawling.eaglersoccer;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Chunk;
import org.bukkit.GameMode;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.type.Door;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Slime;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;
import org.bukkit.util.Vector;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;

public final class EaglerSoccerPlugin extends JavaPlugin implements Listener, TabExecutor {

    private static final int FIELD_LAYOUT_VERSION = 3;
    private static final int LEGACY_HALF_WIDTH = 12;
    private static final int LEGACY_HALF_LENGTH = 20;

    private NamespacedKey ballKey;
    private World fieldWorld;
    private Location fieldCenter;
    private Slime ball;

    private Team redTeam;
    private Team blueTeam;
    private Scoreboard soccerOverlayScoreboard;
    private Objective soccerOverlayObjective;
    private Team overlayRedTeam;
    private Team overlayBlueTeam;
    private final Map<UUID, String> previousScoreboardTeams = new HashMap<>();
    private final Map<UUID, String> previousPlayerListNames = new HashMap<>();
    private final Map<UUID, Scoreboard> previousPlayerScoreboards = new HashMap<>();
    private final List<String> overlayLines = new ArrayList<>();
    private final Map<UUID, SoccerRole> playerRoles = new HashMap<>();

    private int blueScore = 0;
    private int redScore = 0;

    private UUID lastShooter;
    private long lastShotAtMs = 0L;
    private long shooterCollisionGraceUntilMs = 0L;
    private long allCollisionGraceUntilMs = 0L;
    private boolean resettingBall = false;
    private boolean fieldBuilding = false;
    private boolean spawningBall = false;
    private Vector previousBallVelocity = new Vector();

    private MatchState matchState = MatchState.IDLE;
    private final List<UUID> lobbyPlayers = new ArrayList<>();
    private final List<UUID> activePlayers = new ArrayList<>();
    private final Queue<UUID> nextGameQueue = new ArrayDeque<>();
    private int lobbySecondsRemaining = 0;
    private int matchSecondsRemaining = 0;
    private BukkitTask lobbyTask;
    private BukkitTask matchTask;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        ballKey = new NamespacedKey(this, "soccer_ball");
        initializeSoccerTeams();

        getServer().getPluginManager().registerEvents(this, this);

        if (getCommand("soccer") != null) {
            getCommand("soccer").setExecutor(this);
            getCommand("soccer").setTabCompleter(this);
        }
        if (getCommand("leave") != null) {
            getCommand("leave").setExecutor(this);
        }

        getServer().getScheduler().runTaskLater(this, () -> {
            if (!loadSavedField()) {
                setupField(false, null);
            } else {
                clearArenaMobs();
            }

            long period = Math.max(1L, getConfig().getLong("soccer-task-period-ticks", 1L));
            getServer().getScheduler().runTaskTimer(this, this::tickSoccer, period, period);
        }, 60L);

        getLogger().info("EaglerSoccer enabled.");
    }

    @Override
    public void onDisable() {
        lastShooter = null;
        cancelLobbyTask();
        cancelMatchTask();
        matchState = MatchState.IDLE;
        lobbyPlayers.clear();
        activePlayers.clear();
        nextGameQueue.clear();

        for (Player player : new ArrayList<>(Bukkit.getOnlinePlayers())) {
            removePlayerFromSoccerTeam(player, true);
        }

        clearTeamEntries(redTeam);
        clearTeamEntries(blueTeam);
        despawnActiveBall();
    }

    private void initializeSoccerTeams() {
        Scoreboard scoreboard = Bukkit.getScoreboardManager().getMainScoreboard();

        redTeam = scoreboard.getTeam("soccer_red");
        if (redTeam == null) {
            redTeam = scoreboard.registerNewTeam("soccer_red");
        }

        blueTeam = scoreboard.getTeam("soccer_blue");
        if (blueTeam == null) {
            blueTeam = scoreboard.registerNewTeam("soccer_blue");
        }

        configureTeam(redTeam, ChatColor.RED, "[RED] ");
        configureTeam(blueTeam, ChatColor.BLUE, "[BLUE] ");

        // Main-scoreboard teams can survive a hot reload. Soccer assignments should
        // not, so begin each plugin lifecycle with a clean roster.
        clearTeamEntries(redTeam);
        clearTeamEntries(blueTeam);
        initializeSoccerOverlay();
    }

    private void initializeSoccerOverlay() {
        soccerOverlayScoreboard = Bukkit.getScoreboardManager().getNewScoreboard();
        soccerOverlayObjective = soccerOverlayScoreboard.registerNewObjective(
                "soccer_ui",
                "dummy",
                ChatColor.GOLD + "" + ChatColor.BOLD + "SOCCER"
        );
        soccerOverlayObjective.setDisplaySlot(DisplaySlot.SIDEBAR);

        overlayRedTeam = soccerOverlayScoreboard.registerNewTeam("soccer_red_ui");
        overlayBlueTeam = soccerOverlayScoreboard.registerNewTeam("soccer_blue_ui");
        configureTeam(overlayRedTeam, ChatColor.RED, "[RED] ");
        configureTeam(overlayBlueTeam, ChatColor.BLUE, "[BLUE] ");
    }

    private void configureTeam(Team team, ChatColor color, String prefix) {
        team.setColor(color);
        team.setPrefix(color + prefix);
        team.setOption(Team.Option.NAME_TAG_VISIBILITY, Team.OptionStatus.ALWAYS);
    }

    private void clearTeamEntries(Team team) {
        if (team == null) {
            return;
        }

        for (String entry : new ArrayList<>(team.getEntries())) {
            team.removeEntry(entry);
        }
    }

    private Team assignPlayerToSoccerTeam(Player player) {
        String entry = player.getName();

        if (redTeam.hasEntry(entry)) {
            refreshTeamDisplay(player, redTeam);
            return redTeam;
        }
        if (blueTeam.hasEntry(entry)) {
            refreshTeamDisplay(player, blueTeam);
            return blueTeam;
        }

        UUID uuid = player.getUniqueId();
        Scoreboard scoreboard = Bukkit.getScoreboardManager().getMainScoreboard();
        Team previousTeam = scoreboard.getEntryTeam(entry);

        previousScoreboardTeams.put(uuid, previousTeam == null ? "" : previousTeam.getName());
        previousPlayerListNames.put(uuid, player.getPlayerListName());

        Team assigned;
        int redSize = redTeam.getEntries().size();
        int blueSize = blueTeam.getEntries().size();

        if (redSize < blueSize) {
            assigned = redTeam;
        } else if (blueSize < redSize) {
            assigned = blueTeam;
        } else {
            assigned = ThreadLocalRandom.current().nextBoolean() ? redTeam : blueTeam;
        }

        assigned.addEntry(entry);
        refreshTeamDisplay(player, assigned);

        ChatColor color = assigned == redTeam ? ChatColor.RED : ChatColor.BLUE;
        String teamName = assigned == redTeam ? "RED" : "BLUE";

        player.sendTitle(
                color + teamName + " TEAM",
                ChatColor.WHITE + "Your name color shows your side.",
                5,
                45,
                10
        );
        player.sendMessage(ChatColor.GOLD + "[Soccer] " + ChatColor.WHITE + "You are on "
                + color + teamName + " TEAM" + ChatColor.WHITE + ".");

        return assigned;
    }

    private void refreshTeamDisplay(Player player, Team team) {
        ChatColor color = team == redTeam ? ChatColor.RED : ChatColor.BLUE;
        String teamName = team == redTeam ? "RED" : "BLUE";
        player.setPlayerListName(color + "[" + teamName + "] " + player.getName());
    }

    private void removePlayerFromSoccerTeam(Player player, boolean restorePreviousTeam) {
        String entry = player.getName();

        if (redTeam != null) {
            redTeam.removeEntry(entry);
        }
        if (blueTeam != null) {
            blueTeam.removeEntry(entry);
        }

        UUID uuid = player.getUniqueId();
        String previousTeamName = previousScoreboardTeams.remove(uuid);
        String previousListName = previousPlayerListNames.remove(uuid);

        if (restorePreviousTeam && previousTeamName != null && !previousTeamName.isEmpty()) {
            Team previousTeam = Bukkit.getScoreboardManager().getMainScoreboard().getTeam(previousTeamName);
            if (previousTeam != null) {
                previousTeam.addEntry(entry);
            }
        }

        if (previousListName != null) {
            player.setPlayerListName(previousListName);
        }

        detachSoccerOverlay(player);
        playerRoles.remove(uuid);
        syncOverlayTeams();
    }

    private void attachSoccerOverlay(Player player) {
        if (soccerOverlayScoreboard == null || soccerOverlayObjective == null) {
            return;
        }

        UUID uuid = player.getUniqueId();
        if (!previousPlayerScoreboards.containsKey(uuid) && player.getScoreboard() != soccerOverlayScoreboard) {
            previousPlayerScoreboards.put(uuid, player.getScoreboard());
        }

        syncOverlayTeams();
        player.setScoreboard(soccerOverlayScoreboard);
        updateSoccerOverlay();
    }

    private void detachSoccerOverlay(Player player) {
        Scoreboard previous = previousPlayerScoreboards.remove(player.getUniqueId());
        if (previous != null) {
            player.setScoreboard(previous);
        } else if (player.getScoreboard() == soccerOverlayScoreboard) {
            player.setScoreboard(Bukkit.getScoreboardManager().getMainScoreboard());
        }
    }

    private void syncOverlayTeams() {
        if (overlayRedTeam == null || overlayBlueTeam == null) {
            return;
        }

        clearTeamEntries(overlayRedTeam);
        clearTeamEntries(overlayBlueTeam);

        if (redTeam != null) {
            for (String entry : redTeam.getEntries()) {
                overlayRedTeam.addEntry(entry);
            }
        }
        if (blueTeam != null) {
            for (String entry : blueTeam.getEntries()) {
                overlayBlueTeam.addEntry(entry);
            }
        }
    }

    private void updateSoccerOverlay() {
        if (soccerOverlayScoreboard == null || soccerOverlayObjective == null) {
            return;
        }

        syncOverlayTeams();

        for (String line : new ArrayList<>(overlayLines)) {
            soccerOverlayScoreboard.resetScores(line);
        }
        overlayLines.clear();

        List<String> lines = new ArrayList<>();
        if (matchState == MatchState.RUNNING) {
            lines.add(ChatColor.BLUE + "Blue: " + ChatColor.WHITE + blueScore);
            lines.add(ChatColor.RED + "Red: " + ChatColor.WHITE + redScore);
            lines.add(ChatColor.YELLOW + "Time: " + ChatColor.WHITE + formatClock(matchSecondsRemaining));
            lines.add(ChatColor.AQUA + "Players: " + ChatColor.WHITE
                    + activePlayers.size() + "/" + maxMatchPlayers());
            if (activePlayers.size() < maxMatchPlayers()) {
                lines.add(ChatColor.GREEN + "Type play to join");
            }
        } else if (matchState == MatchState.LOBBY) {
            lines.add(ChatColor.YELLOW + "Lobby: " + ChatColor.WHITE
                    + lobbyPlayers.size() + "/" + maxMatchPlayers());
            lines.add(ChatColor.AQUA + "Starts in: " + ChatColor.WHITE + lobbySecondsRemaining + "s");
            lines.add(ChatColor.GREEN + "Type play to join");
        } else {
            lines.add(ChatColor.GRAY + "Waiting for next game");
        }

        int score = lines.size();
        for (String line : lines) {
            overlayLines.add(line);
            soccerOverlayObjective.getScore(line).setScore(score--);
        }
    }

    private String formatClock(int totalSeconds) {
        int safeSeconds = Math.max(0, totalSeconds);
        return String.format("%d:%02d", safeSeconds / 60, safeSeconds % 60);
    }

    private boolean loadSavedField() {
        if (!getConfig().getBoolean("field.built", false)) {
            return false;
        }

        if (getConfig().getInt("field.layout-version", 0) != FIELD_LAYOUT_VERSION) {
            getLogger().info("Older soccer field layout detected; rebuilding the smaller performance-safe field.");
            return false;
        }

        World world = Bukkit.getWorld(getConfig().getString("world", "world"));
        if (world == null) {
            getLogger().warning("Configured soccer world is not loaded.");
            return false;
        }

        fieldWorld = world;
        fieldCenter = new Location(
                world,
                getConfig().getInt("field.center-x") + 0.5,
                getConfig().getInt("field.center-y") + 1.05,
                getConfig().getInt("field.center-z") + 0.5
        );
        return true;
    }

    private void setupField(boolean forceRebuild, CommandSender requester) {
        if (fieldBuilding) {
            if (requester != null) {
                requester.sendMessage(ChatColor.YELLOW + "The soccer field is already being prepared.");
            }
            return;
        }

        World world = Bukkit.getWorld(getConfig().getString("world", "world"));
        if (world == null) {
            getLogger().severe("Cannot create soccer field: configured world is not loaded.");
            if (requester != null) {
                requester.sendMessage(ChatColor.RED + "The configured soccer world is not loaded.");
            }
            return;
        }

        boolean legacyField = getConfig().getBoolean("field.built", false)
                && getConfig().getInt("field.layout-version", 0) < FIELD_LAYOUT_VERSION;

        // Existing plugin config files survive JAR replacement. Force old installations
        // onto the compact layout instead of inheriting the original 25 x 41 values.
        if (legacyField) {
            getConfig().set("field-width", 13);
            getConfig().set("field-length", 23);
            getConfig().set("goal-width", 5);
        }

        Location spawn = world.getSpawnLocation();
        int centerX = legacyField
                ? getConfig().getInt("field.center-x")
                : spawn.getBlockX();
        int centerZ = legacyField
                ? getConfig().getInt("field.center-z")
                : spawn.getBlockZ() - getConfig().getInt("distance-north-of-spawn", 100);

        int width = makeOddAtLeast(getConfig().getInt("field-width", 13), 9);
        int length = makeOddAtLeast(getConfig().getInt("field-length", 23), 15);
        int halfWidth = width / 2;
        int halfLength = length / 2;

        int preloadHalfWidth = legacyField ? Math.max(halfWidth + 1, LEGACY_HALF_WIDTH + 2) : halfWidth + 1;
        int preloadHalfLength = legacyField ? Math.max(halfLength + 2, LEGACY_HALF_LENGTH + 5) : halfLength + 2;

        fieldBuilding = true;
        despawnActiveBall();

        if (requester != null) {
            requester.sendMessage(ChatColor.YELLOW + "Preparing soccer field chunks safely...");
        }

        preloadArea(world, centerX, centerZ, preloadHalfWidth, preloadHalfLength, () -> {
            int surfaceY;
            if (legacyField && !forceRebuild) {
                surfaceY = getConfig().getInt("field.center-y");
            } else {
                surfaceY = calculateFieldSurfaceY(world, centerX, centerZ, halfWidth, halfLength);
            }

            Queue<BlockChange> changes = new ArrayDeque<>();

            if (legacyField) {
                queueLegacyMarkerCleanup(changes, world, centerX, surfaceY, centerZ);
                queueLegacySupportRemoval(changes, centerX, surfaceY, centerZ, halfWidth, halfLength);
            }

            queuePitchBuild(changes, centerX, surfaceY, centerZ, halfWidth, halfLength);

            int blocksPerTick = Math.max(50, getConfig().getInt("build-blocks-per-tick", 180));
            applyBlockChangesBatched(world, changes, blocksPerTick, () -> {
                placeExitDoor(world, centerX, surfaceY, centerZ, halfWidth);
                fieldWorld = world;
                fieldCenter = new Location(world, centerX + 0.5, surfaceY + 1.05, centerZ + 0.5);

                getConfig().set("field.built", true);
                getConfig().set("field.layout-version", FIELD_LAYOUT_VERSION);
                getConfig().set("field.center-x", centerX);
                getConfig().set("field.center-y", surfaceY);
                getConfig().set("field.center-z", centerZ);
                saveConfig();

                removeTaggedBallsNearField();
                clearArenaMobs();
                fieldBuilding = false;

                getLogger().info("Soccer field ready at " + centerX + ", " + surfaceY + ", " + centerZ
                        + " (" + width + "x" + length + ", single-layer layout).");

                if (requester != null) {
                    requester.sendMessage(ChatColor.GREEN + "Soccer field rebuilt: "
                            + width + " x " + length + ", single layer.");
                }
            });
        }, () -> {
            fieldBuilding = false;
            if (requester != null) {
                requester.sendMessage(ChatColor.RED + "Could not prepare the soccer field chunks. Check the server log.");
            }
        });
    }

    private void preloadArea(World world, int centerX, int centerZ, int halfWidth, int halfLength,
                             Runnable onReady, Runnable onFailure) {
        int minChunkX = (centerX - halfWidth) >> 4;
        int maxChunkX = (centerX + halfWidth) >> 4;
        int minChunkZ = (centerZ - halfLength) >> 4;
        int maxChunkZ = (centerZ + halfLength) >> 4;

        List<CompletableFuture<Chunk>> futures = new ArrayList<>();

        for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                futures.add(world.getChunkAtAsync(chunkX, chunkZ));
            }
        }

        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .whenComplete((ignored, error) -> {
                    if (!isEnabled()) {
                        return;
                    }

                    getServer().getScheduler().runTask(this, () -> {
                        if (error != null) {
                            getLogger().severe("Failed to preload soccer field chunks: " + error.getMessage());
                            onFailure.run();
                        } else {
                            onReady.run();
                        }
                    });
                });
    }

    private int calculateFieldSurfaceY(World world, int centerX, int centerZ, int halfWidth, int halfLength) {
        int highest = Math.max(world.getSeaLevel(), 4);

        for (int x = centerX - halfWidth - 1; x <= centerX + halfWidth + 1; x += 2) {
            for (int z = centerZ - halfLength - 2; z <= centerZ + halfLength + 2; z += 2) {
                highest = Math.max(highest, world.getHighestBlockYAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES));
            }
        }

        return Math.max(4, Math.min(248, highest + 1));
    }

    private void queuePitchBuild(Queue<BlockChange> changes, int centerX, int y, int centerZ,
                                 int halfWidth, int halfLength) {
        int outerWidth = halfWidth + 1;
        int outerLength = halfLength + 2;

        for (int dx = -outerWidth; dx <= outerWidth; dx++) {
            for (int dz = -outerLength; dz <= outerLength; dz++) {
                boolean insidePitch = Math.abs(dx) <= halfWidth && Math.abs(dz) <= halfLength;
                Material surface = insidePitch
                        ? pitchSurfaceFor(dx, dz, halfWidth, halfLength)
                        : Material.GRASS_BLOCK;

                changes.add(new BlockChange(centerX + dx, y, centerZ + dz, surface));
            }
        }

        int goalWidth = makeOddAtLeast(getConfig().getInt("goal-width", 5), 3);
        int goalHeight = Math.max(2, getConfig().getInt("goal-height", 3));
        queueGoal(changes, centerX, y, centerZ - halfLength - 1, goalWidth, goalHeight, Material.BLUE_WOOL);
        queueGoal(changes, centerX, y, centerZ + halfLength + 1, goalWidth, goalHeight, Material.RED_WOOL);

        queueGlassCage(changes, centerX, y, centerZ, halfWidth, halfLength);
        queueExitPad(changes, centerX, y, centerZ, halfWidth);
    }

    private void queueGlassCage(Queue<BlockChange> changes, int centerX, int y, int centerZ,
                                int halfWidth, int halfLength) {
        int outerWidth = halfWidth + 1;
        int outerLength = halfLength + 2;
        int cageHeight = Math.max(4, getConfig().getInt("cage-height", 6));

        for (int dx = -outerWidth; dx <= outerWidth; dx++) {
            for (int dz = -outerLength; dz <= outerLength; dz++) {
                boolean wall = Math.abs(dx) == outerWidth || Math.abs(dz) == outerLength;

                if (wall) {
                    for (int dy = 1; dy < cageHeight; dy++) {
                        boolean doorOpening = dx == -outerWidth && dz == 0 && dy <= 2;
                        changes.add(new BlockChange(
                                centerX + dx,
                                y + dy,
                                centerZ + dz,
                                doorOpening ? Material.AIR : Material.GLASS
                        ));
                    }
                }

                changes.add(new BlockChange(centerX + dx, y + cageHeight, centerZ + dz, Material.GLASS));
            }
        }
    }

    private void queueExitPad(Queue<BlockChange> changes, int centerX, int y, int centerZ, int halfWidth) {
        int outerWidth = halfWidth + 1;

        for (int offset = 1; offset <= 3; offset++) {
            int x = centerX - outerWidth - offset;
            for (int dz = -1; dz <= 1; dz++) {
                changes.add(new BlockChange(x, y, centerZ + dz, Material.STONE_BRICKS));
            }
        }
    }

    private void placeExitDoor(World world, int centerX, int y, int centerZ, int halfWidth) {
        int doorX = centerX - (halfWidth + 1);

        Door bottomData = (Door) Material.IRON_DOOR.createBlockData();
        bottomData.setFacing(BlockFace.EAST);
        bottomData.setHalf(Bisected.Half.BOTTOM);
        bottomData.setOpen(false);

        Door topData = (Door) Material.IRON_DOOR.createBlockData();
        topData.setFacing(BlockFace.EAST);
        topData.setHalf(Bisected.Half.TOP);
        topData.setOpen(false);

        world.getBlockAt(doorX, y + 1, centerZ).setBlockData(bottomData, false);
        world.getBlockAt(doorX, y + 2, centerZ).setBlockData(topData, false);
    }

    private void queueLegacyMarkerCleanup(Queue<BlockChange> changes, World world,
                                          int centerX, int y, int centerZ) {
        for (int dx = -LEGACY_HALF_WIDTH - 2; dx <= LEGACY_HALF_WIDTH + 2; dx++) {
            for (int dz = -LEGACY_HALF_LENGTH - 5; dz <= LEGACY_HALF_LENGTH + 5; dz++) {
                Block block = world.getBlockAt(centerX + dx, y, centerZ + dz);
                if (block.getType() == Material.WHITE_WOOL) {
                    changes.add(new BlockChange(centerX + dx, y, centerZ + dz, Material.GRASS_BLOCK));
                }
            }
        }

        int oldGoalHalf = 3;
        int oldGoalHeight = 3;

        queueGoalRemoval(changes, centerX, y, centerZ - LEGACY_HALF_LENGTH - 1, oldGoalHalf, oldGoalHeight);
        queueGoalRemoval(changes, centerX, y, centerZ + LEGACY_HALF_LENGTH + 1, oldGoalHalf, oldGoalHeight);
    }

    private void queueLegacySupportRemoval(Queue<BlockChange> changes, int centerX, int y, int centerZ,
                                           int halfWidth, int halfLength) {
        // The original build wrote a full DIRT support layer at y - 1.
        // Clear that support only beneath the new compact pitch so the playable
        // platform is truly one block thick without excavating the entire legacy footprint.
        int outerWidth = halfWidth + 1;
        int outerLength = halfLength + 2;

        for (int dx = -outerWidth; dx <= outerWidth; dx++) {
            for (int dz = -outerLength; dz <= outerLength; dz++) {
                changes.add(new BlockChange(centerX + dx, y - 1, centerZ + dz, Material.AIR));
            }
        }
    }

    private void queueGoalRemoval(Queue<BlockChange> changes, int centerX, int y, int goalZ,
                                  int halfGoal, int goalHeight) {
        int leftX = centerX - halfGoal;
        int rightX = centerX + halfGoal;

        for (int dy = 1; dy <= goalHeight; dy++) {
            changes.add(new BlockChange(leftX, y + dy, goalZ, Material.AIR));
            changes.add(new BlockChange(rightX, y + dy, goalZ, Material.AIR));
        }

        for (int x = leftX; x <= rightX; x++) {
            changes.add(new BlockChange(x, y + goalHeight, goalZ, Material.AIR));
        }
    }

    private void applyBlockChangesBatched(World world, Queue<BlockChange> changes,
                                          int blocksPerTick, Runnable onComplete) {
        getServer().getScheduler().runTask(this, new Runnable() {
            @Override
            public void run() {
                int applied = 0;

                while (applied < blocksPerTick && !changes.isEmpty()) {
                    BlockChange change = changes.poll();
                    Block block = world.getBlockAt(change.x(), change.y(), change.z());

                    if (block.getType() != change.material()) {
                        block.setType(change.material(), false);
                    }
                    applied++;
                }

                if (changes.isEmpty()) {
                    onComplete.run();
                } else {
                    getServer().getScheduler().runTaskLater(EaglerSoccerPlugin.this, this, 1L);
                }
            }
        });
    }

    private Material pitchSurfaceFor(int dx, int dz, int halfWidth, int halfLength) {
        boolean border = Math.abs(dx) == halfWidth || Math.abs(dz) == halfLength;
        boolean halfwayLine = dz == 0;
        boolean centerSpot = dx == 0 && dz == 0;

        if (border || halfwayLine || centerSpot) {
            return Material.WHITE_WOOL;
        }
        return Material.GRASS_BLOCK;
    }

    private void queueGoal(Queue<BlockChange> changes, int centerX, int y, int goalZ,
                           int goalWidth, int goalHeight, Material material) {
        int halfGoal = goalWidth / 2;
        int leftX = centerX - halfGoal;
        int rightX = centerX + halfGoal;

        for (int dy = 1; dy <= goalHeight; dy++) {
            changes.add(new BlockChange(leftX, y + dy, goalZ, material));
            changes.add(new BlockChange(rightX, y + dy, goalZ, material));
        }

        for (int x = leftX; x <= rightX; x++) {
            changes.add(new BlockChange(x, y + goalHeight, goalZ, material));
        }
    }

    private void tickSoccer() {
        if (fieldBuilding || fieldWorld == null || fieldCenter == null) {
            return;
        }

        if (matchState != MatchState.RUNNING) {
            despawnActiveBall();
            return;
        }

        if (!hasPlayersNearField()) {
            despawnActiveBall();
            return;
        }

        int centerChunkX = fieldCenter.getBlockX() >> 4;
        int centerChunkZ = fieldCenter.getBlockZ() >> 4;
        if (!fieldWorld.isChunkLoaded(centerChunkX, centerChunkZ)) {
            ball = null;
            return;
        }

        if (ball == null || !ball.isValid() || ball.isDead()) {
            ball = null;
            spawnBall();
            if (ball == null) {
                return;
            }
        }

        processPlayerCollisionStops();
        applyGlassBounce();
        applyGroundFriction();
        checkGoalOrOutOfBounds();

        if (ball != null && ball.isValid()) {
            previousBallVelocity = ball.getVelocity().clone();
        }
    }

    private void applyGlassBounce() {
        if (ball == null || !ball.isValid()) {
            return;
        }

        Location location = ball.getLocation();
        Vector velocity = ball.getVelocity().clone();

        int halfWidth = makeOddAtLeast(getConfig().getInt("field-width", 13), 9) / 2;
        int halfLength = makeOddAtLeast(getConfig().getInt("field-length", 23), 15) / 2;

        // These limits sit just inside the glass block faces and account for the
        // size-1 slime's radius. If the server's native collision already zeroed
        // a component, use the previous tick's velocity to produce the rebound.
        double xLimit = halfWidth + 0.32;
        double zLimit = halfLength + 1.32;
        double retention = Math.max(0.35, Math.min(0.98,
                getConfig().getDouble("glass-bounce-retention", 0.82)));

        double dx = location.getX() - fieldCenter.getX();
        double dz = location.getZ() - fieldCenter.getZ();
        boolean bounced = false;
        Location corrected = location.clone();

        if (Math.abs(dx) >= xLimit) {
            double sourceX = Math.abs(velocity.getX()) > 0.01
                    ? velocity.getX()
                    : previousBallVelocity.getX();
            if (Math.abs(sourceX) > 0.01 && Math.signum(sourceX) == Math.signum(dx)) {
                velocity.setX(-sourceX * retention);
                corrected.setX(fieldCenter.getX() + Math.copySign(xLimit - 0.04, dx));
                bounced = true;
            }
        }

        if (Math.abs(dz) >= zLimit) {
            double sourceZ = Math.abs(velocity.getZ()) > 0.01
                    ? velocity.getZ()
                    : previousBallVelocity.getZ();
            if (Math.abs(sourceZ) > 0.01 && Math.signum(sourceZ) == Math.signum(dz)) {
                velocity.setZ(-sourceZ * retention);
                corrected.setZ(fieldCenter.getZ() + Math.copySign(zLimit - 0.04, dz));
                bounced = true;
            }
        }

        if (bounced) {
            ball.teleport(corrected);
            ball.setVelocity(velocity);
            ball.setFallDistance(0);
            previousBallVelocity = velocity.clone();
        }
    }

    private boolean hasPlayersNearField() {
        double activeRadius = Math.max(24.0, getConfig().getDouble("active-radius", 48.0));
        double radiusSquared = activeRadius * activeRadius;

        for (Player player : fieldWorld.getPlayers()) {
            if (!activePlayers.contains(player.getUniqueId())) {
                continue;
            }
            if (player.getGameMode() == GameMode.SPECTATOR) {
                continue;
            }

            if (player.getLocation().distanceSquared(fieldCenter) <= radiusSquared) {
                return true;
            }
        }

        return false;
    }

    private void processPlayerCollisionStops() {
        Vector velocity = ball.getVelocity();
        double horizontalSpeedSquared = velocity.getX() * velocity.getX() + velocity.getZ() * velocity.getZ();
        double minimumMovingSpeed = Math.max(0.005, getConfig().getDouble("minimum-moving-speed", 0.02));

        if (horizontalSpeedSquared < minimumMovingSpeed * minimumMovingSpeed) {
            return;
        }

        double radius = Math.max(0.45, getConfig().getDouble("player-collision-radius", 0.78));
        long now = System.currentTimeMillis();
        if (now < allCollisionGraceUntilMs) {
            return;
        }

        for (Entity entity : ball.getNearbyEntities(radius, 1.15, radius)) {
            if (!(entity instanceof Player player)
                    || !activePlayers.contains(player.getUniqueId())
                    || player.getGameMode() == GameMode.SPECTATOR) {
                continue;
            }

            if (lastShooter != null
                    && player.getUniqueId().equals(lastShooter)
                    && now < shooterCollisionGraceUntilMs) {
                continue;
            }

            if (horizontalDistanceSquared(player.getLocation(), ball.getLocation()) <= radius * radius) {
                stopBall();
                return;
            }
        }
    }

    private boolean canControlBall(Player player) {
        if (resettingBall
                || matchState != MatchState.RUNNING
                || !activePlayers.contains(player.getUniqueId())) {
            return false;
        }
        if (ball == null || !ball.isValid() || player.getWorld() != ball.getWorld()) {
            return false;
        }

        double interactionRadius = Math.max(1.0, getConfig().getDouble("interaction-radius", 2.35));
        double playerDistanceSquared = horizontalDistanceSquared(player.getLocation(), ball.getLocation());

        if (playerDistanceSquared > interactionRadius * interactionRadius) {
            return false;
        }

        double tieTolerance = 0.04;
        for (Player other : fieldWorld.getPlayers()) {
            if (other.equals(player)
                    || !activePlayers.contains(other.getUniqueId())
                    || other.getGameMode() == GameMode.SPECTATOR) {
                continue;
            }

            double otherDistanceSquared = horizontalDistanceSquared(other.getLocation(), ball.getLocation());
            if (otherDistanceSquared + tieTolerance < playerDistanceSquared) {
                return false;
            }
        }

        return true;
    }

    private void queueShot(Player player, Slime clickedBall) {
        long now = System.currentTimeMillis();
        long cooldown = Math.max(100L, getConfig().getLong("shot-cooldown-ms", 180L));
        if (now - lastShotAtMs < cooldown) {
            return;
        }

        // Reserve the click immediately, but apply the velocity one tick later.
        // Eagler/legacy attack translation can otherwise overwrite velocity that is
        // assigned from inside the same cancelled damage event.
        lastShotAtMs = now;
        UUID expectedBall = clickedBall.getUniqueId();

        getServer().getScheduler().runTaskLater(this, () -> {
            if (!player.isOnline()
                    || ball == null
                    || !ball.isValid()
                    || !ball.getUniqueId().equals(expectedBall)
                    || !canControlBall(player)) {
                return;
            }

            applyShot(player);
        }, 1L);
    }

    private void applyShot(Player player) {
        Vector direction = player.getEyeLocation().getDirection();
        direction.setY(0);

        if (direction.lengthSquared() < 0.01) {
            direction = ball.getLocation().toVector().subtract(player.getLocation().toVector()).setY(0);
        }
        if (direction.lengthSquared() < 0.01) {
            direction = new Vector(0, 0, -1);
        }
        direction.normalize();

        boolean strongShot = player.isSprinting();
        double strength = strongShot
                ? getConfig().getDouble("sprint-shot-strength", 1.02)
                : getConfig().getDouble("pass-strength", 0.68);

        // Multiplier defaults to 2.0 even for existing config files created by
        // earlier plugin versions, so upgrading immediately gets the stronger kick.
        double forceMultiplier = Math.max(0.1, getConfig().getDouble("kick-force-multiplier", 2.0));
        strength *= forceMultiplier;

        double lift = strongShot
                ? getConfig().getDouble("shot-lift", 0.10)
                : getConfig().getDouble("pass-lift", 0.055);

        Vector velocity = direction.multiply(strength);
        velocity.setY(lift);
        ball.setVelocity(velocity);
        ball.setFallDistance(0);
        previousBallVelocity = velocity.clone();

        long now = System.currentTimeMillis();
        lastShooter = player.getUniqueId();
        shooterCollisionGraceUntilMs = now
                + Math.max(100L, getConfig().getLong("shooter-collision-grace-ms", 300L));
        allCollisionGraceUntilMs = now
                + Math.max(50L, getConfig().getLong("post-kick-collision-grace-ms", 120L));

        player.playSound(player.getLocation(), Sound.ENTITY_SLIME_ATTACK, 0.45f, 1.35f);
    }

    private void stopBall() {
        if (ball == null || !ball.isValid()) {
            return;
        }

        ball.setVelocity(new Vector(0, 0, 0));
        ball.setFallDistance(0);
        previousBallVelocity = new Vector();
        lastShooter = null;
        shooterCollisionGraceUntilMs = 0L;
        allCollisionGraceUntilMs = 0L;
    }

    private void applyGroundFriction() {
        if (!ball.isOnGround()) {
            return;
        }

        Vector velocity = ball.getVelocity();
        double friction = Math.max(0.80, Math.min(0.999,
                getConfig().getDouble("ground-friction-multiplier", 0.965)));
        double x = velocity.getX() * friction;
        double z = velocity.getZ() * friction;
        double stopThreshold = Math.max(0.003, getConfig().getDouble("stop-speed-threshold", 0.012));

        if (Math.abs(x) < stopThreshold) {
            x = 0;
        }
        if (Math.abs(z) < stopThreshold) {
            z = 0;
        }

        ball.setVelocity(new Vector(x, velocity.getY(), z));
    }

    private void checkGoalOrOutOfBounds() {
        Location location = ball.getLocation();

        int halfWidth = makeOddAtLeast(getConfig().getInt("field-width", 13), 9) / 2;
        int halfLength = makeOddAtLeast(getConfig().getInt("field-length", 23), 15) / 2;
        int goalHalf = makeOddAtLeast(getConfig().getInt("goal-width", 5), 3) / 2;

        double dx = location.getX() - fieldCenter.getX();
        double dz = location.getZ() - fieldCenter.getZ();

        boolean insideGoalWidth = Math.abs(dx) <= goalHalf + 0.35;

        if (!resettingBall && insideGoalWidth && dz <= -(halfLength + 0.65)) {
            blueScore++;
            announceGoal(ChatColor.BLUE + "BLUE", blueScore, redScore);
            updateSoccerOverlay();
            if (scoreLimitReached()) {
                finishMatch("Score limit reached.");
            } else {
                resetBallAfterGoal(redTeam);
            }
            return;
        }

        if (!resettingBall && insideGoalWidth && dz >= halfLength + 0.65) {
            redScore++;
            announceGoal(ChatColor.RED + "RED", blueScore, redScore);
            updateSoccerOverlay();
            if (scoreLimitReached()) {
                finishMatch("Score limit reached.");
            } else {
                resetBallAfterGoal(blueTeam);
            }
            return;
        }

        boolean farOut = Math.abs(dx) > halfWidth + 3
                || Math.abs(dz) > halfLength + 4
                || location.getY() < fieldCenter.getY() - 8
                || location.getY() > 255;

        if (farOut) {
            resetBallImmediately();
        }
    }

    private void announceGoal(String scorer, int blue, int red) {
        String message = ChatColor.GOLD + "[Soccer] " + scorer + ChatColor.GOLD + " GOAL! "
                + ChatColor.BLUE + "Blue " + blue
                + ChatColor.WHITE + " - "
                + ChatColor.RED + red + " Red";

        Bukkit.broadcastMessage(message);

        for (Player player : fieldWorld.getPlayers()) {
            if (player.getLocation().distanceSquared(fieldCenter) <= 64 * 64) {
                player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.9f, 1.15f);
            }
        }
    }

    private void resetBallAfterGoal(Team concedingTeam) {
        prepareKickoff(concedingTeam, 30L);
    }

    private void prepareKickoff(Team possessionTeam, long freezeTicks) {
        if (matchState != MatchState.RUNNING || fieldCenter == null) {
            return;
        }

        resettingBall = true;
        positionPlayersForKickoff(possessionTeam);

        if (ball == null || !ball.isValid()) {
            spawnBall();
        } else {
            ball.setVelocity(new Vector(0, 0, 0));
            ball.teleport(ballSpawnLocation());
            ball.setFallDistance(0);
        }

        previousBallVelocity = new Vector();
        updateSoccerOverlay();

        getServer().getScheduler().runTaskLater(this, () -> {
            if (matchState == MatchState.RUNNING) {
                resettingBall = false;
            }
        }, Math.max(1L, freezeTicks));
    }

    private void resetBallImmediately() {
        if (fieldWorld == null || fieldCenter == null || !hasPlayersNearField()) {
            despawnActiveBall();
            return;
        }

        if (ball == null || !ball.isValid()) {
            ball = null;
            spawnBall();
            return;
        }

        ball.setVelocity(new Vector(0, 0, 0));
        ball.teleport(ballSpawnLocation());
        ball.setFallDistance(0);
        previousBallVelocity = new Vector();
    }

    private void spawnBall() {
        if (fieldWorld == null || fieldCenter == null || !hasPlayersNearField()) {
            return;
        }

        int centerChunkX = fieldCenter.getBlockX() >> 4;
        int centerChunkZ = fieldCenter.getBlockZ() >> 4;
        if (!fieldWorld.isChunkLoaded(centerChunkX, centerChunkZ)) {
            return;
        }

        Entity entity;
        spawningBall = true;
        try {
            entity = fieldWorld.spawnEntity(ballSpawnLocation(), EntityType.SLIME);
        } finally {
            spawningBall = false;
        }

        if (!(entity instanceof Slime slime)) {
            entity.remove();
            getLogger().severe("Could not spawn the soccer ball as a slime.");
            return;
        }

        slime.getPersistentDataContainer().set(ballKey, PersistentDataType.BYTE, (byte) 1);
        configureBall(slime);
        ball = slime;
        previousBallVelocity = new Vector();
    }

    private void configureBall(Slime slime) {
        slime.setSize(1);

        // Keep normal entity physics enabled while disabling autonomous mob goals.
        // This is more reliable for plugin-applied velocity than the NoAI flag on
        // translated legacy clients.
        slime.setAI(true);
        slime.setAware(false);
        slime.setGravity(true);
        slime.setSilent(true);

        // Damage is cancelled by the plugin so left-click attacks can be used as
        // a soccer control without actually hurting the slime.
        slime.setInvulnerable(false);

        // Session-only entity: never save soccer balls into chunk data.
        slime.setPersistent(false);
        slime.setRemoveWhenFarAway(true);

        // Native player/entity collision is client-predicted and inconsistent for
        // legacy Eagler clients. Player contact is simulated in tickSoccer instead.
        slime.setCollidable(false);
        slime.setCustomName(ChatColor.WHITE + "Soccer Ball");
        slime.setCustomNameVisible(false);
    }

    private void despawnActiveBall() {
        if (ball != null && ball.isValid()) {
            ball.remove();
        }
        ball = null;
        lastShooter = null;
        shooterCollisionGraceUntilMs = 0L;
        allCollisionGraceUntilMs = 0L;
        resettingBall = false;
        previousBallVelocity = new Vector();
    }

    private Location ballSpawnLocation() {
        return fieldCenter.clone().add(0, 0.35, 0);
    }

    private boolean isSoccerBall(Entity entity) {
        Byte value = entity.getPersistentDataContainer().get(ballKey, PersistentDataType.BYTE);
        return value != null && value == (byte) 1;
    }

    private void removeTaggedBallsNearField() {
        if (fieldWorld == null || fieldCenter == null) {
            return;
        }

        int halfWidth = makeOddAtLeast(getConfig().getInt("field-width", 13), 9) / 2;
        int halfLength = makeOddAtLeast(getConfig().getInt("field-length", 23), 15) / 2;

        for (Entity entity : fieldWorld.getNearbyEntities(fieldCenter, halfWidth + 12, 12, halfLength + 12)) {
            if (entity.equals(ball)) {
                continue;
            }
            if (isSoccerBall(entity)) {
                entity.remove();
            }
        }
    }

    private Slime resolveSoccerBall(Entity entity) {
        if (!(entity instanceof Slime slime) || !isSoccerBall(entity)) {
            return null;
        }

        if (ball == null || !ball.isValid() || !ball.getUniqueId().equals(slime.getUniqueId())) {
            ball = slime;
        }
        return slime;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onBallDamage(EntityDamageEvent event) {
        Slime clickedBall = resolveSoccerBall(event.getEntity());
        if (clickedBall == null) {
            return;
        }

        event.setCancelled(true);

        if (event instanceof EntityDamageByEntityEvent byEntity
                && byEntity.getDamager() instanceof Player player
                && canControlBall(player)) {
            queueShot(player, clickedBall);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onBallRightClick(PlayerInteractAtEntityEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }

        Slime clickedBall = resolveSoccerBall(event.getRightClicked());
        if (clickedBall == null) {
            return;
        }

        event.setCancelled(true);
        if (canControlBall(event.getPlayer())) {
            stopBall();
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayChat(AsyncPlayerChatEvent event) {
        if (!event.getMessage().trim().equalsIgnoreCase("play") || matchState == MatchState.IDLE) {
            return;
        }

        event.setCancelled(true);
        Player player = event.getPlayer();
        getServer().getScheduler().runTask(this, () -> handlePlayRequest(player));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onArenaDoorInteract(PlayerInteractEvent event) {
        if (matchState == MatchState.IDLE || event.getClickedBlock() == null) {
            return;
        }
        if (!isArenaDoorBlock(event.getClickedBlock())) {
            return;
        }

        event.setCancelled(true);
        closeArenaDoor();
        event.getPlayer().sendMessage(ChatColor.GOLD + "[Soccer] " + ChatColor.YELLOW
                + "The stadium door is locked during a soccer session. Use /leave to exit.");
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        handlePlayerDeparture(event.getPlayer(), false);
    }

    @EventHandler
    public void onBallDeath(EntityDeathEvent event) {
        if (isSoccerBall(event.getEntity())) {
            event.getDrops().clear();
            event.setDroppedExp(0);

            if (event.getEntity().equals(ball)) {
                ball = null;
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onArenaCreatureSpawn(CreatureSpawnEvent event) {
        if (spawningBall) {
            return;
        }

        if (isInsideArena(event.getLocation())) {
            event.setCancelled(true);
        }
    }

    private boolean isInsideArena(Location location) {
        if (fieldWorld == null || fieldCenter == null || location.getWorld() != fieldWorld) {
            return false;
        }

        int centerX = getConfig().getInt("field.center-x");
        int surfaceY = getConfig().getInt("field.center-y");
        int centerZ = getConfig().getInt("field.center-z");
        int halfWidth = makeOddAtLeast(getConfig().getInt("field-width", 13), 9) / 2;
        int halfLength = makeOddAtLeast(getConfig().getInt("field-length", 23), 15) / 2;
        int outerWidth = halfWidth + 1;
        int outerLength = halfLength + 2;
        int cageHeight = Math.max(4, getConfig().getInt("cage-height", 6));

        return location.getX() >= centerX - outerWidth
                && location.getX() <= centerX + outerWidth + 1
                && location.getZ() >= centerZ - outerLength
                && location.getZ() <= centerZ + outerLength + 1
                && location.getY() >= surfaceY
                && location.getY() <= surfaceY + cageHeight + 1;
    }

    private void clearArenaMobs() {
        if (fieldWorld == null || fieldCenter == null) {
            return;
        }

        int halfWidth = makeOddAtLeast(getConfig().getInt("field-width", 13), 9) / 2;
        int halfLength = makeOddAtLeast(getConfig().getInt("field-length", 23), 15) / 2;
        int cageHeight = Math.max(4, getConfig().getInt("cage-height", 6));

        for (Entity entity : fieldWorld.getNearbyEntities(
                fieldCenter,
                halfWidth + 2,
                cageHeight + 2,
                halfLength + 3
        )) {
            if (entity instanceof LivingEntity
                    && !(entity instanceof Player)
                    && !isSoccerBall(entity)) {
                entity.remove();
            }
        }
    }

    @EventHandler
    public void onArenaBlockBreak(BlockBreakEvent event) {
        if (isProtectedArenaBlock(event.getBlock())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onArenaBlockPlace(BlockPlaceEvent event) {
        if (isProtectedArenaBlock(event.getBlockPlaced())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onArenaEntityExplode(EntityExplodeEvent event) {
        event.blockList().removeIf(this::isProtectedArenaBlock);
    }

    @EventHandler
    public void onArenaBlockExplode(BlockExplodeEvent event) {
        event.blockList().removeIf(this::isProtectedArenaBlock);
    }

    private boolean isProtectedArenaBlock(Block block) {
        if (fieldWorld == null || fieldCenter == null || block.getWorld() != fieldWorld) {
            return false;
        }

        int centerX = getConfig().getInt("field.center-x");
        int surfaceY = getConfig().getInt("field.center-y");
        int centerZ = getConfig().getInt("field.center-z");
        int halfWidth = makeOddAtLeast(getConfig().getInt("field-width", 13), 9) / 2;
        int halfLength = makeOddAtLeast(getConfig().getInt("field-length", 23), 15) / 2;
        int outerWidth = halfWidth + 1;
        int outerLength = halfLength + 2;
        int cageHeight = Math.max(4, getConfig().getInt("cage-height", 6));

        return block.getX() >= centerX - outerWidth - 3
                && block.getX() <= centerX + outerWidth
                && block.getZ() >= centerZ - outerLength
                && block.getZ() <= centerZ + outerLength
                && block.getY() >= surfaceY
                && block.getY() <= surfaceY + cageHeight;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("leave")) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage("Only a player can use /leave.");
                return true;
            }

            leaveSoccer(player);
            return true;
        }

        if (args.length == 0) {
            if (!(sender instanceof Player player)) {
                sendUsage(sender, label);
                return true;
            }

            handleSoccerRequest(player);
            return true;
        }

        if (!sender.hasPermission("eaglersoccer.admin")) {
            sender.sendMessage(ChatColor.RED + "Use /" + label + " to start, join, or queue for soccer.");
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "setup" -> {
                setupField(true, sender);
                return true;
            }
            case "reset" -> {
                if (fieldCenter == null || fieldBuilding) {
                    sender.sendMessage(ChatColor.RED + "The soccer field is not ready yet.");
                } else {
                    resetBallImmediately();
                    sender.sendMessage(ChatColor.GREEN + "Soccer ball reset to midfield.");
                }
                return true;
            }
            case "score" -> {
                sender.sendMessage(ChatColor.BLUE + "Blue " + blueScore
                        + ChatColor.WHITE + " - "
                        + ChatColor.RED + redScore + " Red");
                return true;
            }
            case "resetscore" -> {
                blueScore = 0;
                redScore = 0;
                Bukkit.broadcastMessage(ChatColor.GOLD + "[Soccer] Score reset. "
                        + ChatColor.BLUE + "Blue 0" + ChatColor.WHITE + " - " + ChatColor.RED + "0 Red");
                resetBallImmediately();
                return true;
            }
            case "tp" -> {
                if (!(sender instanceof Player player)) {
                    sender.sendMessage("Only a player can teleport to the field.");
                    return true;
                }
                teleportPlayerToField(player);
                return true;
            }
            default -> {
                sendUsage(sender, label);
                return true;
            }
        }
    }


    private void handleSoccerRequest(Player player) {
        if (fieldCenter == null || fieldBuilding) {
            player.sendMessage(ChatColor.RED + "The soccer field is not ready yet.");
            return;
        }

        switch (matchState) {
            case IDLE -> beginLobby(player);
            case LOBBY -> joinLobby(player);
            case RUNNING -> {
                if (activePlayers.size() < maxMatchPlayers()) {
                    joinRunningMatch(player);
                } else {
                    queueForNextGame(player);
                }
            }
        }
    }

    private void handlePlayRequest(Player player) {
        if (!player.isOnline()) {
            return;
        }

        if (matchState == MatchState.LOBBY) {
            joinLobby(player);
        } else if (matchState == MatchState.RUNNING) {
            if (activePlayers.size() < maxMatchPlayers()) {
                joinRunningMatch(player);
            } else {
                queueForNextGame(player);
            }
        }
    }

    private int maxMatchPlayers() {
        return Math.min(6, Math.max(2, getConfig().getInt("max-match-players", 6)));
    }

    private int lobbyCountdownSeconds() {
        return Math.max(3, getConfig().getInt("lobby-countdown-seconds", 10));
    }

    private void beginLobby(Player initiator) {
        cancelLobbyTask();
        cancelMatchTask();
        lobbyPlayers.clear();
        activePlayers.clear();
        blueScore = 0;
        redScore = 0;
        matchState = MatchState.LOBBY;
        lobbySecondsRemaining = lobbyCountdownSeconds();
        closeArenaDoor();

        lobbyPlayers.add(initiator.getUniqueId());
        nextGameQueue.remove(initiator.getUniqueId());
        removePlayerFromSoccerTeam(initiator, true);
        teleportPlayerToField(initiator);
        attachSoccerOverlay(initiator);
        updateSoccerOverlay();

        Bukkit.broadcastMessage(ChatColor.GOLD + "[Soccer] " + ChatColor.YELLOW
                + initiator.getName() + " started a game! Type "
                + ChatColor.AQUA + "play" + ChatColor.YELLOW + " in chat to join. "
                + ChatColor.WHITE + "(" + lobbyPlayers.size() + "/" + maxMatchPlayers()
                + ", " + lobbySecondsRemaining + "s)");

        startLobbyCountdown();
    }

    private void joinLobby(Player player) {
        UUID uuid = player.getUniqueId();

        if (activePlayers.contains(uuid)) {
            player.sendMessage(ChatColor.GOLD + "[Soccer] " + ChatColor.YELLOW + "You are already playing.");
            return;
        }
        if (lobbyPlayers.contains(uuid)) {
            player.sendMessage(ChatColor.GOLD + "[Soccer] " + ChatColor.YELLOW
                    + "You are already in the lobby. " + lobbyPlayers.size() + "/" + maxMatchPlayers()
                    + " players, " + lobbySecondsRemaining + "s remaining.");
            return;
        }
        if (lobbyPlayers.size() >= maxMatchPlayers()) {
            queueForNextGame(player);
            return;
        }

        nextGameQueue.remove(uuid);
        lobbyPlayers.add(uuid);
        removePlayerFromSoccerTeam(player, true);
        teleportPlayerToField(player);
        attachSoccerOverlay(player);
        updateSoccerOverlay();

        Bukkit.broadcastMessage(ChatColor.GOLD + "[Soccer] " + ChatColor.GREEN
                + player.getName() + " joined! " + ChatColor.WHITE
                + lobbyPlayers.size() + "/" + maxMatchPlayers()
                + ChatColor.YELLOW + " players, " + lobbySecondsRemaining + "s remaining.");

        if (lobbyPlayers.size() >= maxMatchPlayers()) {
            Bukkit.broadcastMessage(ChatColor.GOLD + "[Soccer] " + ChatColor.GREEN
                    + "6/6 players ready — starting now!");
            startMatch();
        }
    }

    private void startLobbyCountdown() {
        cancelLobbyTask();

        lobbyTask = getServer().getScheduler().runTaskTimer(this, () -> {
            if (matchState != MatchState.LOBBY) {
                cancelLobbyTask();
                return;
            }

            lobbySecondsRemaining--;
            updateSoccerOverlay();

            if (lobbySecondsRemaining <= 0) {
                startMatch();
                return;
            }

            if (lobbySecondsRemaining <= 5 || lobbySecondsRemaining == 8) {
                Bukkit.broadcastMessage(ChatColor.GOLD + "[Soccer] " + ChatColor.YELLOW
                        + lobbyPlayers.size() + "/" + maxMatchPlayers() + " players — "
                        + lobbySecondsRemaining + "s to join. Type "
                        + ChatColor.AQUA + "play" + ChatColor.YELLOW + ".");
            }
        }, 20L, 20L);
    }

    private void startMatch() {
        if (matchState != MatchState.LOBBY) {
            return;
        }

        cancelLobbyTask();

        List<Player> players = new ArrayList<>();
        for (UUID uuid : new ArrayList<>(lobbyPlayers)) {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null && player.isOnline()) {
                players.add(player);
            }
        }

        if (players.size() < 2) {
            cancelLobby("Not enough players joined to start a game.");
            return;
        }

        Collections.shuffle(players);
        clearTeamEntries(redTeam);
        clearTeamEntries(blueTeam);
        activePlayers.clear();
        playerRoles.clear();

        for (Player player : players) {
            activePlayers.add(player.getUniqueId());
            assignPlayerToSoccerTeam(player);
        }

        lobbyPlayers.clear();
        blueScore = 0;
        redScore = 0;
        matchSecondsRemaining = Math.max(30, getConfig().getInt("match-duration-seconds", 300));
        matchState = MatchState.RUNNING;
        closeArenaDoor();
        clearArenaMobs();

        assignAllRoles();
        Team firstKickoff = ThreadLocalRandom.current().nextBoolean() ? redTeam : blueTeam;

        for (Player player : players) {
            attachSoccerOverlay(player);
            sendRoleAssignment(player);
        }

        prepareKickoff(firstKickoff, 20L);
        updateSoccerOverlay();

        Bukkit.broadcastMessage(ChatColor.GOLD + "[Soccer] " + ChatColor.GREEN
                + "Kickoff! " + ChatColor.RED + redTeam.getEntries().size() + " Red"
                + ChatColor.WHITE + " vs " + ChatColor.BLUE + blueTeam.getEntries().size() + " Blue"
                + ChatColor.WHITE + ". Game time: " + matchSecondsRemaining + "s.");

        if (activePlayers.size() < maxMatchPlayers()) {
            Bukkit.broadcastMessage(ChatColor.GOLD + "[Soccer] " + ChatColor.AQUA
                    + "The match is still open — type " + ChatColor.WHITE + "play"
                    + ChatColor.AQUA + " to join until " + maxMatchPlayers() + "/" + maxMatchPlayers() + ".");
        }

        startMatchTimer();
    }

    private void startMatchTimer() {
        cancelMatchTask();

        matchTask = getServer().getScheduler().runTaskTimer(this, () -> {
            if (matchState != MatchState.RUNNING) {
                cancelMatchTask();
                return;
            }

            matchSecondsRemaining--;
            updateSoccerOverlay();

            if (matchSecondsRemaining <= 0) {
                finishMatch("Time expired.");
                return;
            }

            if (matchSecondsRemaining == 60 || matchSecondsRemaining == 30
                    || matchSecondsRemaining == 10 || matchSecondsRemaining <= 5) {
                Bukkit.broadcastMessage(ChatColor.GOLD + "[Soccer] " + ChatColor.YELLOW
                        + matchSecondsRemaining + "s remaining. "
                        + ChatColor.BLUE + "Blue " + blueScore
                        + ChatColor.WHITE + " - " + ChatColor.RED + redScore + " Red");
            }
        }, 20L, 20L);
    }

    private boolean scoreLimitReached() {
        int scoreLimit = Math.max(1, getConfig().getInt("score-limit", 5));
        return blueScore >= scoreLimit || redScore >= scoreLimit;
    }

    private void finishMatch(String reason) {
        if (matchState != MatchState.RUNNING) {
            return;
        }

        cancelMatchTask();
        matchState = MatchState.IDLE;
        despawnActiveBall();
        playerRoles.clear();

        List<UUID> finishedPlayers = new ArrayList<>(activePlayers);
        activePlayers.clear();

        Bukkit.broadcastMessage(ChatColor.GOLD + "[Soccer] " + ChatColor.YELLOW + "Game over! "
                + ChatColor.BLUE + "Blue " + blueScore + ChatColor.WHITE + " - "
                + ChatColor.RED + redScore + " Red. " + ChatColor.GRAY + reason);

        for (UUID uuid : finishedPlayers) {
            Player player = Bukkit.getPlayer(uuid);
            if (player == null || !player.isOnline()) {
                continue;
            }

            removePlayerFromSoccerTeam(player, true);
            teleportPlayerToSpectator(player, ChatColor.YELLOW + "Game over. You are now outside the stadium.");
        }

        if (!nextGameQueue.isEmpty()) {
            Bukkit.broadcastMessage(ChatColor.GOLD + "[Soccer] " + ChatColor.AQUA
                    + "Queued players will open the next lobby.");
            getServer().getScheduler().runTaskLater(this, this::beginQueuedLobby, 40L);
        }
    }

    private void beginQueuedLobby() {
        if (matchState != MatchState.IDLE || fieldCenter == null || fieldBuilding) {
            return;
        }

        cancelLobbyTask();
        lobbyPlayers.clear();

        while (lobbyPlayers.size() < maxMatchPlayers() && !nextGameQueue.isEmpty()) {
            UUID uuid = nextGameQueue.poll();
            Player player = Bukkit.getPlayer(uuid);
            if (player == null || !player.isOnline()) {
                continue;
            }

            lobbyPlayers.add(uuid);
            removePlayerFromSoccerTeam(player, true);
            teleportPlayerToField(player);
            attachSoccerOverlay(player);
        }

        if (lobbyPlayers.isEmpty()) {
            return;
        }

        matchState = MatchState.LOBBY;
        lobbySecondsRemaining = lobbyCountdownSeconds();
        closeArenaDoor();
        updateSoccerOverlay();

        Bukkit.broadcastMessage(ChatColor.GOLD + "[Soccer] " + ChatColor.AQUA
                + "Next game lobby is open! " + ChatColor.WHITE
                + lobbyPlayers.size() + "/" + maxMatchPlayers()
                + ChatColor.YELLOW + " ready. Type " + ChatColor.AQUA + "play"
                + ChatColor.YELLOW + " to join. " + lobbySecondsRemaining + "s.");

        if (lobbyPlayers.size() >= maxMatchPlayers()) {
            startMatch();
        } else {
            startLobbyCountdown();
        }
    }

    private void queueForNextGame(Player player) {
        UUID uuid = player.getUniqueId();

        if (activePlayers.contains(uuid)) {
            player.sendMessage(ChatColor.GOLD + "[Soccer] " + ChatColor.YELLOW
                    + "You are already in the current game.");
            return;
        }
        if (lobbyPlayers.contains(uuid)) {
            player.sendMessage(ChatColor.GOLD + "[Soccer] " + ChatColor.YELLOW
                    + "You are already in the lobby.");
            return;
        }
        if (nextGameQueue.contains(uuid)) {
            int position = new ArrayList<>(nextGameQueue).indexOf(uuid) + 1;
            player.sendMessage(ChatColor.GOLD + "[Soccer] " + ChatColor.YELLOW
                    + "You are already queued for the next game (position " + position + ").");
            teleportPlayerToSpectator(player, null);
            return;
        }

        nextGameQueue.add(uuid);
        removePlayerFromSoccerTeam(player, true);
        teleportPlayerToSpectator(player, ChatColor.AQUA
                + "You joined the next-game queue. Watch from outside the glass!");
        attachSoccerOverlay(player);
        updateSoccerOverlay();

        Bukkit.broadcastMessage(ChatColor.GOLD + "[Soccer] " + ChatColor.AQUA
                + player.getName() + " joined the next-game queue. "
                + ChatColor.WHITE + nextGameQueue.size() + " waiting.");
    }

    private void joinRunningMatch(Player player) {
        UUID uuid = player.getUniqueId();

        if (activePlayers.contains(uuid)) {
            player.sendMessage(ChatColor.GOLD + "[Soccer] " + ChatColor.YELLOW
                    + "You are already playing.");
            return;
        }
        if (activePlayers.size() >= maxMatchPlayers()) {
            queueForNextGame(player);
            return;
        }

        nextGameQueue.remove(uuid);
        lobbyPlayers.remove(uuid);
        activePlayers.add(uuid);

        Team assigned = assignPlayerToSoccerTeam(player);
        assignAllRoles();
        attachSoccerOverlay(player);
        teleportPlayerToAssignedPosition(player, assigned);
        sendRoleAssignment(player);
        updateSoccerOverlay();

        String teamName = assigned == redTeam ? "RED" : "BLUE";
        ChatColor teamColor = assigned == redTeam ? ChatColor.RED : ChatColor.BLUE;

        Bukkit.broadcastMessage(ChatColor.GOLD + "[Soccer] " + ChatColor.GREEN
                + player.getName() + " joined the live match on "
                + teamColor + teamName + ChatColor.GREEN + ". "
                + ChatColor.WHITE + activePlayers.size() + "/" + maxMatchPlayers()
                + ChatColor.YELLOW + " players, " + formatClock(matchSecondsRemaining) + " remaining.");
    }

    private void leaveSoccer(Player player) {
        handlePlayerDeparture(player, true);
        teleportPlayerToExit(player);
    }

    private void handlePlayerDeparture(Player player, boolean voluntary) {
        UUID uuid = player.getUniqueId();
        boolean leftLobby = lobbyPlayers.remove(uuid);
        boolean leftMatch = activePlayers.remove(uuid);
        boolean leftQueue = nextGameQueue.remove(uuid);
        playerRoles.remove(uuid);

        removePlayerFromSoccerTeam(player, true);

        if (leftLobby && matchState == MatchState.LOBBY) {
            if (lobbyPlayers.isEmpty()) {
                cancelLobby("The soccer lobby was cancelled.");
            } else {
                Bukkit.broadcastMessage(ChatColor.GOLD + "[Soccer] " + ChatColor.YELLOW
                        + player.getName() + " left the lobby. " + lobbyPlayers.size()
                        + "/" + maxMatchPlayers() + " players, " + lobbySecondsRemaining + "s remaining.");
            }
        }

        if (leftMatch && matchState == MatchState.RUNNING) {
            Bukkit.broadcastMessage(ChatColor.GOLD + "[Soccer] " + ChatColor.YELLOW
                    + player.getName() + " left the game.");
            if (activePlayers.size() < 2) {
                finishMatch("Not enough players remain.");
            }
        }

        if (leftQueue && voluntary) {
            player.sendMessage(ChatColor.GOLD + "[Soccer] " + ChatColor.YELLOW
                    + "You left the next-game queue.");
        }

        if (matchState == MatchState.RUNNING) {
            assignAllRoles();
        }
        updateSoccerOverlay();
    }

    private void cancelLobby(String reason) {
        cancelLobbyTask();
        matchState = MatchState.IDLE;

        List<UUID> cancelledPlayers = new ArrayList<>(lobbyPlayers);
        lobbyPlayers.clear();
        playerRoles.clear();

        Bukkit.broadcastMessage(ChatColor.GOLD + "[Soccer] " + ChatColor.YELLOW + reason);

        for (UUID uuid : cancelledPlayers) {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null && player.isOnline()) {
                removePlayerFromSoccerTeam(player, true);
                teleportPlayerToSpectator(player, ChatColor.YELLOW + "Lobby closed. You are outside the stadium.");
            }
        }
    }

    private void assignAllRoles() {
        playerRoles.clear();
        assignRolesForTeam(redTeam);
        assignRolesForTeam(blueTeam);
    }

    private void assignRolesForTeam(Team team) {
        if (team == null) {
            return;
        }

        List<Player> roster = new ArrayList<>();
        for (UUID uuid : activePlayers) {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null && player.isOnline() && team.hasEntry(player.getName())) {
                roster.add(player);
            }
        }

        if (roster.isEmpty()) {
            return;
        }

        if (roster.size() == 1) {
            playerRoles.put(roster.get(0).getUniqueId(), SoccerRole.CENTER_STRIKER);
            return;
        }

        playerRoles.put(roster.get(0).getUniqueId(), SoccerRole.KEEPER);

        if (roster.size() == 2) {
            playerRoles.put(roster.get(1).getUniqueId(), SoccerRole.CENTER_STRIKER);
            return;
        }

        playerRoles.put(roster.get(1).getUniqueId(), SoccerRole.LEFT_STRIKER);
        playerRoles.put(roster.get(2).getUniqueId(), SoccerRole.RIGHT_STRIKER);
    }

    private Team soccerTeamFor(Player player) {
        if (redTeam != null && redTeam.hasEntry(player.getName())) {
            return redTeam;
        }
        if (blueTeam != null && blueTeam.hasEntry(player.getName())) {
            return blueTeam;
        }
        return null;
    }

    private UUID kickoffTakerFor(Team team) {
        SoccerRole[] preference = {
                SoccerRole.CENTER_STRIKER,
                SoccerRole.LEFT_STRIKER,
                SoccerRole.RIGHT_STRIKER,
                SoccerRole.KEEPER
        };

        for (SoccerRole role : preference) {
            for (UUID uuid : activePlayers) {
                Player player = Bukkit.getPlayer(uuid);
                if (player != null
                        && player.isOnline()
                        && soccerTeamFor(player) == team
                        && playerRoles.get(uuid) == role) {
                    return uuid;
                }
            }
        }

        return null;
    }

    private void positionPlayersForKickoff(Team possessionTeam) {
        assignAllRoles();
        UUID kickoffTaker = kickoffTakerFor(possessionTeam);

        for (UUID uuid : new ArrayList<>(activePlayers)) {
            Player player = Bukkit.getPlayer(uuid);
            if (player == null || !player.isOnline()) {
                continue;
            }

            Team team = soccerTeamFor(player);
            if (team == null) {
                continue;
            }

            Location target = assignedPosition(player, team, uuid.equals(kickoffTaker));
            player.teleport(target);
            player.setVelocity(new Vector());
            player.setFallDistance(0);
        }
    }

    private void teleportPlayerToAssignedPosition(Player player, Team team) {
        Location target = assignedPosition(player, team, false);
        player.teleportAsync(target).whenComplete((success, error) -> {
            if (!isEnabled() || !player.isOnline()) {
                return;
            }

            getServer().getScheduler().runTask(this, () -> {
                if (error != null || !Boolean.TRUE.equals(success)) {
                    player.sendMessage(ChatColor.RED + "Could not enter your soccer position.");
                    return;
                }
                player.setVelocity(new Vector());
                player.setFallDistance(0);
            });
        });
    }

    private Location assignedPosition(Player player, Team team, boolean kickoffTaker) {
        int halfLength = makeOddAtLeast(getConfig().getInt("field-length", 23), 15) / 2;
        double ownSide = team == redTeam ? -1.0 : 1.0;
        double x = 0.0;
        double z;

        if (kickoffTaker) {
            z = ownSide * 1.35;
        } else {
            SoccerRole role = playerRoles.getOrDefault(player.getUniqueId(), SoccerRole.CENTER_STRIKER);
            switch (role) {
                case KEEPER -> z = ownSide * Math.max(4.0, halfLength - 1.5);
                case LEFT_STRIKER -> {
                    x = -2.0;
                    z = ownSide * 3.0;
                }
                case RIGHT_STRIKER -> {
                    x = 2.0;
                    z = ownSide * 3.0;
                }
                case CENTER_STRIKER -> z = ownSide * 3.0;
                default -> z = ownSide * 3.0;
            }
        }

        Location target = fieldCenter.clone().add(x, 0, z);
        target.setYaw(team == redTeam ? 0.0f : 180.0f);
        target.setPitch(0.0f);
        return target;
    }

    private void sendRoleAssignment(Player player) {
        SoccerRole role = playerRoles.get(player.getUniqueId());
        if (role == null) {
            return;
        }

        player.sendMessage(ChatColor.GOLD + "[Soccer] " + ChatColor.WHITE + "Position: "
                + ChatColor.AQUA + role.displayName + ChatColor.WHITE + ".");
    }

    private void cancelLobbyTask() {
        if (lobbyTask != null) {
            lobbyTask.cancel();
            lobbyTask = null;
        }
    }

    private void cancelMatchTask() {
        if (matchTask != null) {
            matchTask.cancel();
            matchTask = null;
        }
    }

    private void teleportPlayerToField(Player player) {
        if (fieldCenter == null || fieldBuilding) {
            player.sendMessage(ChatColor.RED + "The soccer field is not ready yet.");
            return;
        }

        Location target = fieldEntryLocation();
        player.sendMessage(ChatColor.YELLOW + "Loading the soccer field...");

        player.teleportAsync(target).whenComplete((success, error) -> {
            if (!isEnabled()) {
                return;
            }

            getServer().getScheduler().runTask(this, () -> {
                if (error != null || !Boolean.TRUE.equals(success)) {
                    player.sendMessage(ChatColor.RED + "Could not teleport to the soccer field.");
                    if (error != null) {
                        getLogger().warning("Async soccer teleport failed for " + player.getName()
                                + ": " + error.getMessage());
                    }
                    return;
                }

                // Any persistent balls left by pre-1.1 builds are now in loaded chunks and can be removed safely.
                removeTaggedBallsNearField();
                player.sendMessage(ChatColor.GREEN + "Teleported to the soccer field.");
            });
        });
    }

    private Location fieldEntryLocation() {
        int halfWidth = makeOddAtLeast(getConfig().getInt("field-width", 13), 9) / 2;
        int outerWidth = halfWidth + 1;
        Location target = fieldCenter.clone().add(-(outerWidth - 1), 0, 0);
        target.setYaw(-90.0f);
        return target;
    }

    private Location fieldExitLocation() {
        int halfWidth = makeOddAtLeast(getConfig().getInt("field-width", 13), 9) / 2;
        int outerWidth = halfWidth + 1;
        Location target = fieldCenter.clone().add(-(outerWidth + 2), 0, 0);
        target.setYaw(-90.0f);
        return target;
    }

    private Location fieldSpectatorLocation() {
        int halfWidth = makeOddAtLeast(getConfig().getInt("field-width", 13), 9) / 2;
        int outerWidth = halfWidth + 1;
        Location target = fieldCenter.clone().add(-(outerWidth + 2), 0, 0);
        target.setYaw(-90.0f);
        return target;
    }

    private void teleportPlayerToSpectator(Player player, String message) {
        if (fieldCenter == null || fieldBuilding) {
            return;
        }

        removePlayerFromSoccerTeam(player, true);
        closeArenaDoor();

        player.teleportAsync(fieldSpectatorLocation()).whenComplete((success, error) -> {
            if (!isEnabled() || !player.isOnline()) {
                return;
            }

            getServer().getScheduler().runTask(this, () -> {
                if (error != null || !Boolean.TRUE.equals(success)) {
                    player.sendMessage(ChatColor.RED + "Could not teleport to the spectator area.");
                    return;
                }
                if (message != null && !message.isEmpty()) {
                    player.sendMessage(ChatColor.GOLD + "[Soccer] " + message);
                }
            });
        });
    }

    private void closeArenaDoor() {
        if (fieldWorld == null || fieldCenter == null) {
            return;
        }

        int centerX = getConfig().getInt("field.center-x");
        int surfaceY = getConfig().getInt("field.center-y");
        int centerZ = getConfig().getInt("field.center-z");
        int halfWidth = makeOddAtLeast(getConfig().getInt("field-width", 13), 9) / 2;
        int doorX = centerX - (halfWidth + 1);

        for (int y = surfaceY + 1; y <= surfaceY + 2; y++) {
            Block block = fieldWorld.getBlockAt(doorX, y, centerZ);
            if (block.getBlockData() instanceof Door door) {
                door.setOpen(false);
                block.setBlockData(door, false);
            }
        }
    }

    private boolean isArenaDoorBlock(Block block) {
        if (fieldWorld == null || fieldCenter == null || block.getWorld() != fieldWorld) {
            return false;
        }

        int centerX = getConfig().getInt("field.center-x");
        int surfaceY = getConfig().getInt("field.center-y");
        int centerZ = getConfig().getInt("field.center-z");
        int halfWidth = makeOddAtLeast(getConfig().getInt("field-width", 13), 9) / 2;
        int doorX = centerX - (halfWidth + 1);

        return block.getX() == doorX
                && block.getZ() == centerZ
                && (block.getY() == surfaceY + 1 || block.getY() == surfaceY + 2)
                && block.getType() == Material.IRON_DOOR;
    }

    private void teleportPlayerToExit(Player player) {
        if (fieldCenter == null || fieldBuilding) {
            removePlayerFromSoccerTeam(player, true);
            World world = Bukkit.getWorld(getConfig().getString("world", "world"));
            if (world != null) {
                player.teleportAsync(world.getSpawnLocation());
            }
            player.sendMessage(ChatColor.YELLOW + "Soccer field is not ready; returning to world spawn.");
            return;
        }

        Location target = fieldExitLocation();
        player.sendMessage(ChatColor.YELLOW + "Leaving the soccer field...");

        player.teleportAsync(target).whenComplete((success, error) -> {
            if (!isEnabled()) {
                return;
            }

            getServer().getScheduler().runTask(this, () -> {
                if (error != null || !Boolean.TRUE.equals(success)) {
                    player.sendMessage(ChatColor.RED + "Could not leave the soccer field.");
                    if (error != null) {
                        getLogger().warning("Async soccer exit teleport failed for " + player.getName()
                                + ": " + error.getMessage());
                    }
                    return;
                }

                removePlayerFromSoccerTeam(player, true);
                player.sendMessage(ChatColor.GREEN + "Returned to the soccer entrance.");
            });
        });
    }

    private void sendUsage(CommandSender sender, String label) {
        sender.sendMessage(ChatColor.YELLOW + "/leave");
        sender.sendMessage(ChatColor.YELLOW + "/" + label + " setup");
        sender.sendMessage(ChatColor.YELLOW + "/" + label + " tp");
        sender.sendMessage(ChatColor.YELLOW + "/" + label + " reset");
        sender.sendMessage(ChatColor.YELLOW + "/" + label + " score");
        sender.sendMessage(ChatColor.YELLOW + "/" + label + " resetscore");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission("eaglersoccer.admin")) {
            return List.of();
        }
        if (args.length == 1) {
            return List.of("setup", "tp", "reset", "score", "resetscore");
        }
        return List.of();
    }

    private int makeOddAtLeast(int value, int minimum) {
        int adjusted = Math.max(value, minimum);
        return adjusted % 2 == 0 ? adjusted + 1 : adjusted;
    }

    private double horizontalDistanceSquared(Location a, Location b) {
        double dx = a.getX() - b.getX();
        double dz = a.getZ() - b.getZ();
        return dx * dx + dz * dz;
    }

    private enum MatchState {
        IDLE,
        LOBBY,
        RUNNING
    }

    private enum SoccerRole {
        KEEPER("Keeper"),
        LEFT_STRIKER("Left Striker"),
        RIGHT_STRIKER("Right Striker"),
        CENTER_STRIKER("Striker");

        private final String displayName;

        SoccerRole(String displayName) {
            this.displayName = displayName;
        }
    }

    private record BlockChange(int x, int y, int z, Material material) {
    }
}
