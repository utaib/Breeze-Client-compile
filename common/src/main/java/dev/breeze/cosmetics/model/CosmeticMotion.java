package dev.breeze.cosmetics.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * The moving attachments, as the launcher's rig does them (rig.js
 * FlyingFollow and TrailEmitter), in the same skinview3d part space and
 * pixels, so a flying pet and a trail behave in game as in the Wardrobe.
 * One instance per player and cosmetic, stepped with the frame time.
 */
public final class CosmeticMotion {

    private CosmeticMotion() {}

    /**
     * A flying pet: it follows a point circling the player and bobbing, eased
     * by a spring so it drifts rather than snaps, faces and banks into the way
     * it flies, and stops to hover about a third of the time.
     */
    public static final class Flying {
        private final float baseX, baseY, baseZ;
        public final float[] pos;
        private final float[] vel = new float[3];
        private float angle;
        public float yaw;
        public float roll;
        private float t;
        /** "fly" while moving, "idle" while hovering. */
        public String state = "fly";

        public Flying(float baseX, float baseY, float baseZ) {
            this.baseX = baseX;
            this.baseY = baseY;
            this.baseZ = baseZ;
            this.pos = new float[]{baseX, baseY, baseZ};
        }

        public String step(float dt) {
            dt = Math.min(dt, 0.1f);
            t += dt;
            float cycle = (float) Math.sin(t * 0.25f);
            float pace = cycle < -0.6f ? 0 : 0.45f + 0.25f * cycle;
            angle += dt * pace;
            float radius = (float) Math.hypot(baseX, baseZ);
            if (radius == 0) radius = 12;
            float tx = (float) Math.cos(angle) * radius;
            float ty = baseY + (float) Math.sin(t * 1.4f) * 2 + (float) Math.sin(t * 0.37f) * 1.5f;
            float tz = (float) Math.sin(angle) * radius * 0.85f;
            float[] target = {tx, ty, tz};
            for (int i = 0; i < 3; i++) {
                float accel = (target[i] - pos[i]) * 5 - vel[i] * 3.2f;
                vel[i] += accel * dt;
                pos[i] += vel[i] * dt;
            }
            float speed = (float) Math.hypot(vel[0], vel[2]);
            if (speed > 0.5f) {
                float want = (float) Math.atan2(vel[0], vel[2]);
                float diff = want - yaw;
                diff = (float) Math.atan2(Math.sin(diff), Math.cos(diff));
                yaw += diff * Math.min(1, dt * 4);
                roll = Math.max(-0.5f, Math.min(0.5f, -diff * 0.6f));
            }
            // Separate thresholds for starting and stopping, so a pet at the
            // edge does not flicker between its two clips.
            if (state.equals("fly") && speed < 1.2f) state = "idle";
            else if (state.equals("idle") && speed > 3) state = "fly";
            return state;
        }
    }

    /** One copy a trail left behind. */
    public static final class Copy {
        public float x, y, z;
        public final float rotY;
        public float age;
        public static final float LIFE = 1.4f;

        Copy(float x, float y, float z, float rotY) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.rotY = rotY;
        }

        /** 1 to 0.2 over the copy's life. */
        public float scale() {
            return 1 - (age / LIFE) * 0.8f;
        }

        /** 1 to 0 over the copy's life. */
        public float opacity() {
            return Math.max(0, 1 - age / LIFE);
        }
    }

    /**
     * A trail: a copy of the model every 0.14 s near the anchor, drifting back,
     * rising, shrinking and fading over 1.4 s. The launcher's preview player
     * walks on the spot at a fixed pace; here the drift follows how fast the
     * player really moves, so copies stay put behind a player standing still.
     */
    public static final class Trail {
        private final Random random;
        public final List<Copy> copies = new ArrayList<>();
        private float spawnIn;

        public Trail(long seed) {
            this.random = new Random(seed);
        }

        /** speed: the player's ground speed in pixels per second (the launcher's walk is 16). */
        public void step(float dt, float originX, float originY, float originZ, float speed) {
            dt = Math.min(dt, 0.1f);
            spawnIn -= dt;
            if (spawnIn <= 0) {
                spawnIn = 0.14f;
                copies.add(new Copy(originX + (random.nextFloat() - 0.5f) * 6,
                        originY + (random.nextFloat() - 0.5f) * 4, originZ,
                        random.nextFloat() * (float) (Math.PI * 2)));
            }
            for (int i = copies.size() - 1; i >= 0; i--) {
                Copy c = copies.get(i);
                c.age += dt;
                if (c.age >= Copy.LIFE) {
                    copies.remove(i);
                    continue;
                }
                // The player moves forward (+Z), so what it leaves moves back past it.
                c.z -= dt * speed;
                c.y += dt * 2;
            }
        }
    }
}
