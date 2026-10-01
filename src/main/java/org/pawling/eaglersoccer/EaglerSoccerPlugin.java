package org.pawling.eaglersoccer;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
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
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class EaglerSoccerPlugin extends JavaPlugin implements Listener, TabExecutor {

    private NamespacedKey ballKey;
    private World fieldWorld;
    private Location fieldCenter;
    private Slime ball;

    private int blueScore = 0;
    private int redScore = 0;

    private final Map<UUID, Long> lastKickAt = new HashMap<>();
    private boolean resettingBall = false;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        ballKey = new NamespacedKey(this, "soccer_ball");

        getServer().getPluginManager().registerEvents(this, this);

        if (getCommand("soccer") != null) {
            getCommand("soccer").setExecutor(this);
            getCommand("soccer").setTabCompleter(this);
        }

        // Let the world finish loading before restoring/building the pitch.
        getServer().getScheduler().runTaskLater(this, () -> {
            if (loadSavedField()) {
                findOrSpawnBall();
            } else {
                setupField(false);
            }

            getServer().getScheduler().runTaskTimer(this, this::tickSoccer, 1L, 1L);
        }, 60L);

        getLogger().info("EaglerSoccer enabled.");
    }

    @Override
    public void onDisable() {
        lastKickAt.clear();
    }

    private boolean loadSavedField() {
        if (!getConfig().getBoolean("field.built", false)) {
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

    private void setupField(boolean forceRebuild) {
        World world = Bukkit.getWorld(getConfig().getString("world", "world"));
        if (world == null) {
            getLogger().severe("Cannot create soccer field: configured world is not loaded.");
            return;
        }

        Location spawn = world.getSpawnLocation();
        int centerX = spawn.getBlockX();
        int centerZ = spawn.getBlockZ() - getConfig().getInt("distance-north-of-spawn", 100);

        int width = makeOddAtLeast(getConfig().getInt("field-width", 25), 15);
        int length = makeOddAtLeast(getConfig().getInt("field-length", 41), 25);
        int halfWidth = width / 2;
        int halfLength = length / 2;

        int surfaceY;
        if (!forceRebuild && getConfig().getBoolean("field.built", false)) {
            surfaceY = getConfig().getInt("field.center-y");
        } else {
            surfaceY = calculateFieldSurfaceY(world, centerX, centerZ, halfWidth, halfLength);
        }

        buildPitch(world, centerX, surfaceY, centerZ, halfWidth, halfLength);

        fieldWorld = world;
        fieldCenter = new Location(world, centerX + 0.5, surfaceY + 1.05, centerZ + 0.5);

        getConfig().set("field.built", true);
        getConfig().set("field.center-x", centerX);
        getConfig().set("field.center-y", surfaceY);
        getConfig().set("field.center-z", centerZ);
        saveConfig();

        removeTaggedBalls(world);
        spawnBall();

        getLogger().info("Soccer field ready at " + centerX + ", " + surfaceY + ", " + centerZ + ".");
    }

    private int calculateFieldSurfaceY(World world, int centerX, int centerZ, int halfWidth, int halfLength) {
        int highest = Math.max(world.getSeaLevel(), 4);

        for (int x = centerX - halfWidth - 3; x <= centerX + halfWidth + 3; x += 2) {
            for (int z = centerZ - halfLength - 6; z <= centerZ + halfLength + 6; z += 2) {
                highest = Math.max(highest, world.getHighestBlockYAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES));
            }
        }

        // Old Eaglercraft clients cannot reliably represent the modern extended world height.
        return Math.max(4, Math.min(248, highest + 1));
    }

    private void buildPitch(World world, int centerX, int y, int centerZ, int halfWidth, int halfLength) {
        int outerWidth = halfWidth + 2;
        int outerLength = halfLength + 5;

        for (int dx = -outerWidth; dx <= outerWidth; dx++) {
            for (int dz = -outerLength; dz <= outerLength; dz++) {
                int x = centerX + dx;
                int z = centerZ + dz;

                world.getBlockAt(x, y - 1, z).setType(Material.DIRT, false);

                boolean insidePitch = Math.abs(dx) <= halfWidth && Math.abs(dz) <= halfLength;
                Material surface = insidePitch ? pitchSurfaceFor(dx, dz, halfWidth, halfLength) : Material.GRASS_BLOCK;
                world.getBlockAt(x, y, z).setType(surface, false);

                for (int clearY = y + 1; clearY <= y + 5; clearY++) {
                    world.getBlockAt(x, clearY, z).setType(Material.AIR, false);
                }
            }
        }

        int goalWidth = makeOddAtLeast(getConfig().getInt("goal-width", 7), 3);
        int goalHeight = Math.max(2, getConfig().getInt("goal-height", 3));
        buildGoal(world, centerX, y, centerZ - halfLength - 1, goalWidth, goalHeight, Material.BLUE_WOOL);
        buildGoal(world, centerX, y, centerZ + halfLength + 1, goalWidth, goalHeight, Material.RED_WOOL);
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

    private void buildGoal(World world, int centerX, int y, int goalZ, int goalWidth, int goalHeight, Material material) {
        int halfGoal = goalWidth / 2;
        int leftX = centerX - halfGoal;
        int rightX = centerX + halfGoal;

        for (int dy = 1; dy <= goalHeight; dy++) {
            world.getBlockAt(leftX, y + dy, goalZ).setType(material, false);
            world.getBlockAt(rightX, y + dy, goalZ).setType(material, false);
        }

        for (int x = leftX; x <= rightX; x++) {
            world.getBlockAt(x, y + goalHeight, goalZ).setType(material, false);
        }
    }

    private void tickSoccer() {
        if (fieldWorld == null || fieldCenter == null) {
            return;
        }

        if (ball == null || !ball.isValid() || ball.isDead()) {
            findOrSpawnBall();
            if (ball == null) {
                return;
            }
        }

        processPlayerContact();
        applyGroundFriction();
        checkGoalOrOutOfBounds();
    }

    private void processPlayerContact() {
        double radius = Math.max(0.6, getConfig().getDouble("contact-radius", 1.20));
        long cooldown = Math.max(100L, getConfig().getLong("kick-cooldown-ms", 220L));
        long now = System.currentTimeMillis();

        for (Entity entity : ball.getNearbyEntities(radius, 1.25, radius)) {
            if (!(entity instanceof Player player) || player.getGameMode() == GameMode.SPECTATOR) {
                continue;
            }

            long last = lastKickAt.getOrDefault(player.getUniqueId(), 0L);
            if (now - last < cooldown) {
                continue;
            }

            double horizontalDistanceSquared = horizontalDistanceSquared(player.getLocation(), ball.getLocation());
            if (horizontalDistanceSquared > radius * radius) {
                continue;
            }

            kickBallFrom(player);
            lastKickAt.put(player.getUniqueId(), now);
        }
    }

    private void kickBallFrom(Player player) {
        Vector direction = ball.getLocation().toVector().subtract(player.getLocation().toVector());
        direction.setY(0);

        if (direction.lengthSquared() < 0.01) {
            direction = player.getLocation().getDirection().setY(0);
        }

        if (direction.lengthSquared() < 0.01) {
            direction = new Vector(0, 0, -1);
        }

        direction.normalize();

        Vector playerVelocity = player.getVelocity().clone().setY(0);
        double movementSpeed = playerVelocity.length();

        double strength = getConfig().getDouble("base-kick-strength", 0.46)
                + Math.min(0.35, movementSpeed * getConfig().getDouble("movement-kick-scale", 0.80));

        if (player.isSprinting()) {
            strength += getConfig().getDouble("sprint-kick-bonus", 0.12);
        }

        strength = Math.min(strength, getConfig().getDouble("maximum-kick-strength", 0.92));

        Vector velocity = direction.multiply(strength);
        velocity.setY(0.16 + Math.min(0.08, movementSpeed * 0.10));
        ball.setVelocity(velocity);
    }

    private void applyGroundFriction() {
        if (!ball.isOnGround()) {
            return;
        }

        Vector velocity = ball.getVelocity();
        double x = velocity.getX() * 0.94;
        double z = velocity.getZ() * 0.94;

        if (Math.abs(x) < 0.015) {
            x = 0;
        }
        if (Math.abs(z) < 0.015) {
            z = 0;
        }

        ball.setVelocity(new Vector(x, velocity.getY(), z));
    }

    private void checkGoalOrOutOfBounds() {
        Location location = ball.getLocation();

        int halfWidth = makeOddAtLeast(getConfig().getInt("field-width", 25), 15) / 2;
        int halfLength = makeOddAtLeast(getConfig().getInt("field-length", 41), 25) / 2;
        int goalHalf = makeOddAtLeast(getConfig().getInt("goal-width", 7), 3) / 2;

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

        boolean farOut = Math.abs(dx) > halfWidth + 4
                || Math.abs(dz) > halfLength + 7
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
            if (player.getLocation().distanceSquared(fieldCenter) <= 80 * 80) {
                player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.9f, 1.15f);
            }
        }
    }

    private void resetBallAfterGoal() {
        resettingBall = true;
        ball.setVelocity(new Vector(0, 0, 0));
        ball.teleport(ballSpawnLocation());

        getServer().getScheduler().runTaskLater(this, () -> resettingBall = false, 30L);
    }

    private void resetBallImmediately() {
        if (ball == null || !ball.isValid()) {
            spawnBall();
            return;
        }

        ball.setVelocity(new Vector(0, 0, 0));
        ball.teleport(ballSpawnLocation());
        ball.setFallDistance(0);
    }

    private void findOrSpawnBall() {
        if (fieldWorld == null || fieldCenter == null) {
            return;
        }

        for (Entity entity : fieldWorld.getNearbyEntities(fieldCenter, 50, 15, 60)) {
            if (entity instanceof Slime slime && isSoccerBall(slime)) {
                configureBall(slime);
                ball = slime;
                return;
            }
        }

        spawnBall();
    }

    private void spawnBall() {
        if (fieldWorld == null || fieldCenter == null) {
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
        slime.setInvulnerable(true);
        slime.setPersistent(true);
        slime.setRemoveWhenFarAway(false);
        slime.setCollidable(true);
        slime.setCustomName(ChatColor.WHITE + "Soccer Ball");
        slime.setCustomNameVisible(false);
    }

    private Location ballSpawnLocation() {
        return fieldCenter.clone().add(0, 0.35, 0);
    }

    private boolean isSoccerBall(Entity entity) {
        Byte value = entity.getPersistentDataContainer().get(ballKey, PersistentDataType.BYTE);
        return value != null && value == (byte) 1;
    }

    private void removeTaggedBalls(World world) {
        for (Entity entity : world.getEntities()) {
            if (isSoccerBall(entity)) {
                entity.remove();
            }
        }
        ball = null;
    }

    @EventHandler
    public void onBallDamage(EntityDamageEvent event) {
        if (isSoccerBall(event.getEntity())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onBallDeath(EntityDeathEvent event) {
        if (isSoccerBall(event.getEntity())) {
            event.getDrops().clear();
            event.setDroppedExp(0);
            getServer().getScheduler().runTaskLater(this, this::spawnBall, 1L);
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sendUsage(sender, label);
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "setup" -> {
                setupField(true);
                sender.sendMessage(ChatColor.GREEN + "Soccer field rebuilt 100 blocks north of spawn.");
                return true;
            }
            case "reset" -> {
                if (fieldCenter == null) {
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
                if (fieldCenter == null) {
                    sender.sendMessage(ChatColor.RED + "The soccer field is not ready yet.");
                    return true;
                }
                player.teleport(fieldCenter.clone().add(0, 1, 0));
                return true;
            }
            default -> {
                sendUsage(sender, label);
                return true;
            }
        }
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
}
