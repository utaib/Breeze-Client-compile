package dev.breeze.modules;

import dev.breeze.Category;
import dev.breeze.Module;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import com.mojang.blaze3d.platform.InputConstants;

public class Perspective extends Module {

    public Perspective() {
        super("Perspective", Category.UTILITY, "Cycles the camera perspective.", InputConstants.KEY_P);
    }

    @Override
    protected void onEnable() {
        try {
            Minecraft mc = Minecraft.getInstance();
            CameraType current = mc.options.getCameraType();
            CameraType next = current == CameraType.FIRST_PERSON ? CameraType.THIRD_PERSON_BACK
                    : current == CameraType.THIRD_PERSON_BACK ? CameraType.THIRD_PERSON_FRONT
                    : CameraType.FIRST_PERSON;
            mc.options.setCameraType(next);
        } catch (Throwable ignored) {}
        setStateSilently(false);
    }
}
