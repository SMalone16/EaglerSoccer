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
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.entity.Slime;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Vector;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public final class EaglerSoccerPlugin extends JavaPlugin implements Listener, TabExecutor {

    private static final int FIELD_LAYOUT_VERSION = 2;
    private static final int LEGACY_HALF_WIDTH = 12;
    private static final int LEGACY_HALF_LENGTH = 20;

    private NamespacedKey ballKey;
    private World fieldWorld;
    private Location fieldCenter;
    private Slime ball;

    private int blueScore = 0;
    private int redScore = 0;

    private UUID lastShooter;
    private long lastShotAtMs = 0L;
    private long shooterCollisionGraceUntilMs = 0L;
    private boolean resettingBall = false;
    private boolean fieldBuilding = false;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        ballKey = new NamespacedKey(this, "soccer_ball");

        getServer().getPluginManager().registerEvents(this, this);

        if (getCommand("soccer") != null) {
            getCommand("soccer").setExecutor(this);
            getCommand("soccer").setTabCompleter(this);
        }

        getServer().getScheduler().runTaskLater(this, () -> {
            if (!loadSavedField()) {
                setupField(false, null);
            }

            long period = Math.max(1L, getConfig().getLong("soccer-task-period-ticks", 1L));
            getServer().getScheduler().runTaskTimer(this, this::tickSoccer, period, period);
        }, 60L);

        getLogger().info("EaglerSoccer enabled.");
    }

    @Override
    public void onDisable() {
        lastShooter = null;
        despawnActiveBall();
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
                fieldWorld = world;
                fieldCenter = new Location(world, centerX + 0.5, surfaceY + 1.05, centerZ + 0.5);

                getConfig().set("field.built", true);
                getConfig().set("field.layout-version", FIELD_LAYOUT_VERSION);
                getConfig().set("field.center-x", centerX);
                getConfig().set("field.center-y", surfaceY);
                getConfig().set("field.center-z", centerZ);
                saveConfig();

                removeTaggedBallsNearField();
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
        applyGroundFriction();
        checkGoalOrOutOfBounds();
    }

    private boolean hasPlayersNearField() {
        double activeRadius = Math.max(24.0, getConfig().getDouble("active-radius", 48.0));
        double radiusSquared = activeRadius * activeRadius;

        for (Player player : fieldWorld.getPlayers()) {
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

        for (Entity entity : ball.getNearbyEntities(radius, 1.15, radius)) {
            if (!(entity instanceof Player player) || player.getGameMode() == GameMode.SPECTATOR) {
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
            if (other.equals(player) || other.getGameMode() == GameMode.SPECTATOR) {
                continue;
            }

            double otherDistanceSquared = horizontalDistanceSquared(other.getLocation(), ball.getLocation());
            if (otherDistanceSquared + tieTolerance < playerDistanceSquared) {
                return false;
            }
        }

        return true;
    }

    private void shootOrPassBall(Player player) {
        long now = System.currentTimeMillis();
        long cooldown = Math.max(100L, getConfig().getLong("shot-cooldown-ms", 180L));
        if (now - lastShotAtMs < cooldown) {
            return;
        }

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
        double lift = strongShot
                ? getConfig().getDouble("shot-lift", 0.10)
                : getConfig().getDouble("pass-lift", 0.055);

        Vector velocity = direction.multiply(strength);
        velocity.setY(lift);
        ball.setVelocity(velocity);
        ball.setFallDistance(0);

        lastShooter = player.getUniqueId();
        lastShotAtMs = now;
        shooterCollisionGraceUntilMs = now
                + Math.max(100L, getConfig().getLong("shooter-collision-grace-ms", 300L));
    }

    private void stopBall() {
        if (ball == null || !ball.isValid()) {
            return;
        }

        ball.setVelocity(new Vector(0, 0, 0));
        ball.setFallDistance(0);
        lastShooter = null;
        shooterCollisionGraceUntilMs = 0L;
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
            resetBallAfterGoal();
            return;
        }

        if (!resettingBall && insideGoalWidth && dz >= halfLength + 0.65) {
            redScore++;
            announceGoal(ChatColor.RED + "RED", blueScore, redScore);
            resetBallAfterGoal();
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

    private void resetBallAfterGoal() {
        if (ball == null || !ball.isValid()) {
            return;
        }

        resettingBall = true;
        ball.setVelocity(new Vector(0, 0, 0));
        ball.teleport(ballSpawnLocation());

        getServer().getScheduler().runTaskLater(this, () -> resettingBall = false, 30L);
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

        Entity entity = fieldWorld.spawnEntity(ballSpawnLocation(), EntityType.SLIME);
        if (!(entity instanceof Slime slime)) {
            entity.remove();
            getLogger().severe("Could not spawn the soccer ball as a slime.");
            return;
        }

        slime.getPersistentDataContainer().set(ballKey, PersistentDataType.BYTE, (byte) 1);
        configureBall(slime);
        ball = slime;
    }

    private void configureBall(Slime slime) {
        slime.setSize(1);
        slime.setAI(false);
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
        resettingBall = false;
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

    @EventHandler
    public void onBallDamage(EntityDamageEvent event) {
        if (!isSoccerBall(event.getEntity())) {
            return;
        }

        event.setCancelled(true);

        if (event instanceof EntityDamageByEntityEvent byEntity
                && byEntity.getDamager() instanceof Player player
                && canControlBall(player)) {
            shootOrPassBall(player);
        }
    }

    @EventHandler
    public void onBallRightClick(PlayerInteractAtEntityEvent event) {
        if (!isSoccerBall(event.getRightClicked()) || event.getHand() != EquipmentSlot.HAND) {
            return;
        }

        event.setCancelled(true);
        if (canControlBall(event.getPlayer())) {
            stopBall();
        }
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

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            if (!(sender instanceof Player player)) {
                sendUsage(sender, label);
                return true;
            }

            teleportPlayerToField(player);
            return true;
        }

        if (!sender.hasPermission("eaglersoccer.admin")) {
            sender.sendMessage(ChatColor.RED + "Use /" + label + " to teleport to the soccer field.");
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

    private void teleportPlayerToField(Player player) {
        if (fieldCenter == null || fieldBuilding) {
            player.sendMessage(ChatColor.RED + "The soccer field is not ready yet.");
            return;
        }

        Location target = fieldCenter.clone().add(0, 1, 0);
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

    private void sendUsage(CommandSender sender, String label) {
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

    private record BlockChange(int x, int y, int z, Material material) {
    }
}
