package dev.breeze.ui;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WallpaperRotationTest {

    @Test
    void listsOnlyPicturesSortedByName(@TempDir Path dir) throws Exception {
        Files.write(dir.resolve("b_nether.jpg"), new byte[] {1});
        Files.write(dir.resolve("A_island.PNG"), new byte[] {1});
        Files.write(dir.resolve("c_mineshaft.jpeg"), new byte[] {1});
        Files.write(dir.resolve("notes.txt"), new byte[] {1});
        Files.write(dir.resolve(".hidden.jpg"), new byte[] {1});
        Files.write(dir.resolve("empty.jpg"), new byte[0]);
        Files.createDirectory(dir.resolve("folder.jpg"));
        List<Path> files = WallpaperRotation.list(dir);
        assertEquals(List.of("A_island.PNG", "b_nether.jpg", "c_mineshaft.jpeg"),
                files.stream().map(p -> p.getFileName().toString()).toList());
    }

    @Test
    void noFolderMeansNoWallpapers() {
        assertTrue(WallpaperRotation.list(null).isEmpty());
        assertTrue(WallpaperRotation.list(Path.of("does-not-exist-" + System.nanoTime())).isEmpty());
    }

    @Test
    void nextComesAfterTheLastShownAndWraps() {
        List<String> names = List.of("a.jpg", "b.jpg", "c.jpg");
        assertEquals(0, WallpaperRotation.next(names, null));
        assertEquals(0, WallpaperRotation.next(names, "gone.jpg"));
        assertEquals(1, WallpaperRotation.next(names, "a.jpg"));
        assertEquals(0, WallpaperRotation.next(names, "c.jpg"));
        assertEquals(-1, WallpaperRotation.next(List.of(), "a.jpg"));
        assertEquals(0, WallpaperRotation.next(List.of("a.jpg"), "a.jpg"));
    }

    @Test
    void coverKeepsTheMiddleWithoutStretching() {
        // Same shape: the whole picture.
        assertArrayEquals(new int[] {0, 0, 1920, 1080}, WallpaperRotation.cover(1920, 1080, 1280, 720));
        // A wider screen: full width, top and bottom cut evenly.
        assertArrayEquals(new int[] {0, 128, 1920, 823}, WallpaperRotation.cover(1920, 1080, 2560, 1097));
        // A narrower (4:3) screen: full height, sides cut evenly.
        assertArrayEquals(new int[] {240, 0, 1440, 1080}, WallpaperRotation.cover(1920, 1080, 320, 240));
        // Nonsense sizes never divide by zero.
        assertArrayEquals(new int[] {0, 0, 1, 1}, WallpaperRotation.cover(0, 0, 0, 0));
    }

    @Test
    void mimeFollowsTheExtension() {
        assertEquals("image/png", WallpaperRotation.mime("x.PNG"));
        assertEquals("image/jpeg", WallpaperRotation.mime("x.jpg"));
        assertEquals("image/jpeg", WallpaperRotation.mime("x.jpeg"));
    }
}
