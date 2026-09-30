package dev.breeze;

import dev.breeze.modules.*;

import dev.breeze.modules.ArmorStatusHud;
import dev.breeze.modules.ComboCounterHud;
import dev.breeze.modules.CoordinatesHud;
import dev.breeze.modules.CpsHud;
import dev.breeze.modules.DirectionHud;
import dev.breeze.modules.Fullbright;
import dev.breeze.modules.HeldItemHud;
import dev.breeze.modules.FpsHud;
import dev.breeze.modules.PingHud;
import dev.breeze.modules.PlaytimeHud;
import dev.breeze.modules.PotionEffectsHud;
import dev.breeze.modules.RecordingIndicatorHud;
import dev.breeze.modules.ServerAddressHud;
import dev.breeze.modules.SpeedMeterHud;
import dev.breeze.modules.StopwatchHud;
import dev.breeze.modules.SystemResourcesHud;
import dev.breeze.modules.TimeHud;
import dev.breeze.modules.PackDisplayHud;
import dev.breeze.modules.ReachDisplayHud;
import dev.breeze.modules.ItemInfoHud;
import dev.breeze.modules.DeathInfoHud;
import dev.breeze.modules.WaypointHud;
import dev.breeze.modules.InventoryHud;
import dev.breeze.modules.AutoHideHud;
import dev.breeze.modules.ToggleSprint;
import dev.breeze.modules.AttackIndicatorHud;
import dev.breeze.modules.ItemCounterHud;
import dev.breeze.modules.ItemDespawnTimerHud;
import dev.breeze.modules.KeystrokesHud;
import dev.breeze.modules.MouseStrokesHud;
import dev.breeze.modules.SaturationHud;
import dev.breeze.modules.TntTimerHud;
import dev.breeze.modules.TotemCounterHud;
import dev.breeze.modules.Zoom;
import dev.breeze.modules.FovChanges;
import dev.breeze.modules.SmoothCamera;
import dev.breeze.modules.HitIndicator;
import dev.breeze.modules.CustomCrosshair;
import dev.breeze.modules.MobOverlay;
import dev.breeze.modules.BlockOverlay;
import dev.breeze.modules.LootBeams;
import dev.breeze.modules.LightLevelOverlay;
import dev.breeze.modules.DamageIndicator;
import dev.breeze.modules.FriendGlow;
import dev.breeze.modules.NicknameHider;
import dev.breeze.modules.TitleTweaker;
import dev.breeze.modules.DarkMode;
import dev.breeze.modules.BossBar;
import dev.breeze.modules.ArmorBarHud;
import dev.breeze.modules.Tooltips;
import dev.breeze.modules.ToastControl;
import dev.breeze.modules.CustomAdvancements;
import dev.breeze.modules.ScoreboardModule;
import dev.breeze.modules.HeartsHud;
import dev.breeze.modules.ItemScale;
import dev.breeze.modules.DropPrevention;
import dev.breeze.modules.SoundFilter;
import dev.breeze.modules.BlockIndicatorHud;
import dev.breeze.modules.SubtitlesToggle;
import dev.breeze.modules.PlayerModel;
import dev.breeze.modules.HorseStatsHud;
import dev.breeze.modules.AutoText;
import dev.breeze.modules.TpsHud;
import dev.breeze.modules.TimeChanger;
import dev.breeze.modules.Reconnect;
import dev.breeze.modules.WorldBackups;
import dev.breeze.modules.Screenshots;
import dev.breeze.modules.AutoPerspective;
import dev.breeze.modules.UiScaling;
import dev.breeze.modules.NameTagScale;
import dev.breeze.modules.Perspective;
import dev.breeze.modules.ShulkerTooltips;
import dev.breeze.modules.KeybindSearch;
import dev.breeze.modules.ItemPhysics;
import dev.breeze.modules.ToggleSneak;
import dev.breeze.modules.NoViewBobbing;
import dev.breeze.modules.DayCounterHud;
import dev.breeze.modules.NoHurtCam;
import dev.breeze.modules.LowFire;
import dev.breeze.modules.Hitboxes;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class ModuleManager {

    private static final List<Module> modules = new ArrayList<>();

    private ModuleManager() {}

    public static void init() {
        if (!modules.isEmpty()) return;
        register(new FpsHud());
        register(new CoordinatesHud());
        register(new PingHud());
        register(new DirectionHud());
        register(new SpeedMeterHud());
        register(new ServerAddressHud());
        register(new TimeHud());
        register(new PlaytimeHud());
        register(new StopwatchHud());
        register(new CpsHud());
        register(new SaturationHud());
        register(new TotemCounterHud());
        register(new ItemCounterHud());
        register(new TntTimerHud());
        register(new ItemDespawnTimerHud());
        register(new AttackIndicatorHud());
        register(new KeystrokesHud());
        register(new MouseStrokesHud());
        register(new ComboCounterHud());
        register(new SystemResourcesHud());
        register(new PotionEffectsHud());
        register(new ArmorStatusHud());
        register(new RecordingIndicatorHud());
        register(new ReachDisplayHud());
        register(new PackDisplayHud());
        register(new ItemInfoHud());
        register(new HeldItemHud());
        register(new DeathInfoHud());
        register(new WaypointHud());
        register(new InventoryHud());
        register(new AutoHideHud());
        register(new ToggleSprint());
        register(new Zoom());
        register(new FovChanges());
        register(new SmoothCamera());
        register(new HitIndicator());
        register(new CustomCrosshair());
        register(new MobOverlay());
        register(new BlockOverlay());
        register(new LootBeams());
        register(new LightLevelOverlay());
        register(new DamageIndicator());
        register(new ArmorBarHud());
        register(new FriendGlow());
        register(new NicknameHider());
        register(new TitleTweaker());
        register(new DarkMode());
        register(new BossBar());
        register(new HeartsHud());
        register(new Tooltips());
        register(new ToastControl());
        register(new CustomAdvancements());
        register(new ScoreboardModule());
        register(new BlockIndicatorHud());
        register(new ItemScale());
        register(new DropPrevention());
        register(new SoundFilter());
        register(new HorseStatsHud());
        register(new SubtitlesToggle());
        register(new PlayerModel());
        register(new AutoText());
        register(new TpsHud());
        register(new TimeChanger());
        register(new Reconnect());
        register(new WorldBackups());
        register(new Screenshots());
        register(new AutoPerspective());
        register(new UiScaling());
        register(new NameTagScale());
        register(new Perspective());
        register(new ShulkerTooltips());
        register(new KeybindSearch());
        register(new ItemPhysics());
        register(new DayCounterHud());
        register(new ToggleSneak());
        register(new NoViewBobbing());
        register(new NoHurtCam());
        register(new LowFire());
        register(new Hitboxes());
        register(new Fullbright());
        register(new CustomCape());
        BreezeClient.LOGGER.info("[Breeze] Registered {} modules", modules.size());
    }

    public static void register(Module m) { modules.add(m); }

    public static List<Module> getModules() { return Collections.unmodifiableList(modules); }

    public static void tickAll(Minecraft mc) {
        for (Module m : breeze$dispatch(0)) {
            if (!m.isEnabled()) continue;
            try {
                m.onTick(mc);
            } catch (Throwable t) {
                BreezeClient.LOGGER.warn("[Breeze] '{}' threw in tick: {}", m.getName(), t.toString());
            }
        }
    }

    public static void renderAll(GuiGraphics g, float partialTick) {
        for (Module m : breeze$dispatch(1)) {
            if (!m.isEnabled() || !m.isHud()) continue;
            try {
                m.onHudRender(g, partialTick);
            } catch (Throwable t) {
                BreezeClient.LOGGER.warn("[Breeze] '{}' threw in render: {}", m.getName(), t.toString());
            }
        }
    }

    public static void renderWorldAll(WorldRenderContext ctx) {
        for (Module m : breeze$dispatch(2)) {
            if (!m.isEnabled()) continue;
            try {
                m.onWorldRender(ctx);
            } catch (Throwable t) {
                BreezeClient.LOGGER.warn("[Breeze] '{}' threw in world render: {}", m.getName(), t.toString());
            }
        }
    }

    private static Module[][] breeze$capsCache;

    private static Module[] breeze$dispatch(int kind) {
        Module[][] c = breeze$capsCache;
        if (c == null) {
            String[] hooks = {"onTick", "onHudRender", "onWorldRender"};
            c = new Module[3][];
            for (int i = 0; i < 3; i++) {
                java.util.List<Module> list = new java.util.ArrayList<>();
                for (Module m : modules) {
                    if (breeze$overrides(m, hooks[i])) list.add(m);
                }
                c[i] = list.toArray(new Module[0]);
            }
            breeze$capsCache = c;
        }
        return c[kind];
    }

    private static boolean breeze$overrides(Module m, String hook) {
        Class<?> c = m.getClass();
        while (c != null && c != Module.class) {
            for (java.lang.reflect.Method mm : c.getDeclaredMethods()) {
                if (mm.getName().equals(hook)) return true;
            }
            c = c.getSuperclass();
        }
        return false;
    }
}
