package dev.breeze.modules;

import dev.breeze.BreezeClient;
import dev.breeze.Category;
import dev.breeze.Module;
import net.minecraft.client.Minecraft;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.stream.Stream;

public class WorldBackups extends Module {

    public WorldBackups() {
        super("World Backups", Category.UTILITY, "Backs up the current singleplayer world.", KEY_NONE);
    }

    @Override
    protected void onEnable() {
        try {
            Minecraft mc = Minecraft.getInstance();
            IntegratedServer server = mc.getSingleplayerServer();
            if (server != null) {
                Path world = server.getWorldPath(LevelResource.ROOT);
                Path backups = mc.gameDirectory.toPath().resolve("backups");
                Files.createDirectories(backups);
                Path dest = backups.resolve(world.getFileName().toString() + "-" + System.currentTimeMillis());
                copyTree(world, dest);
                BreezeClient.LOGGER.info("[Breeze] World backed up to {}", dest);
            }
        } catch (Throwable t) {
            BreezeClient.LOGGER.warn("[Breeze] World backup failed: {}", t.toString());
        }
        setStateSilently(false);
    }

    private static void copyTree(Path src, Path dst) throws IOException {
        try (Stream<Path> stream = Files.walk(src)) {
            stream.forEach(p -> {
                try {
                    Path rel = dst.resolve(src.relativize(p).toString());
                    if (Files.isDirectory(p)) Files.createDirectories(rel);
                    else Files.copy(p, rel, StandardCopyOption.REPLACE_EXISTING);
                } catch (Throwable ignored) {}
            });
        }
    }
}
