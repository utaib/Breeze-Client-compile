package com.terraformersmc.modmenu.api;

import dev.breeze.integrations.FakeScreen;

/** Test stand-in with the shape of Mod Menu's ConfigScreenFactory. */
@FunctionalInterface
public interface ConfigScreenFactory<S extends FakeScreen> {
    S create(FakeScreen parent);
}
