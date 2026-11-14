package dev.breeze.compat;

import net.minecraft.client.Minecraft;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackRepository;

import java.util.ArrayList;
import java.util.List;

/**
 * After the pack repository's selection changed: up to 1.19.3 Minecraft's
 * options have no call for this, and its options screen does these steps
 * itself (OptionsScreen.updatePackList), which this repeats: the packs that
 * can move, in order, go into the options with the incompatible ones
 * noted, the options are saved, and resources reload only when the list
 * changed.
 */
public final class PackOptions {

    private PackOptions() {}

    public static void apply(Minecraft mc, PackRepository repo) {
        List<String> before = new ArrayList<>(mc.options.resourcePacks);
        mc.options.resourcePacks.clear();
        mc.options.incompatibleResourcePacks.clear();
        for (Pack pack : repo.getSelectedPacks()) {
            if (pack.isFixedPosition()) continue;
            mc.options.resourcePacks.add(pack.getId());
            if (!pack.getCompatibility().isCompatible()) mc.options.incompatibleResourcePacks.add(pack.getId());
        }
        mc.options.save();
        if (!before.equals(mc.options.resourcePacks)) mc.reloadResourcePacks();
    }
}
