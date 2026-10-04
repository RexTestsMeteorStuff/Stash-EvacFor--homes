package com.example.stashevac;

import com.example.stashevac.modules.ChestEvac;
import com.example.stashevac.modules.StashEvac;
import com.mojang.logging.LogUtils;
import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.addons.GithubRepo;
import meteordevelopment.meteorclient.addons.MeteorAddon;
import meteordevelopment.meteorclient.systems.modules.Category;
import meteordevelopment.meteorclient.systems.modules.Modules;
import org.slf4j.Logger;

import java.lang.invoke.MethodHandles;

public class StashEvacAddon extends MeteorAddon {
    public static final Logger LOG = LogUtils.getLogger();
    public static final Category CATEGORY = new Category("StashEvac");

    @Override
    public void onInitialize() {
        LOG.info("Initializing Stash Evac");

        // Allows Orbit to use lambda factories for @EventHandler in this package
        MeteorClient.EVENT_BUS.registerLambdaFactory("com.example.stashevac",
            (lookupInMethod, klass) -> (MethodHandles.Lookup) lookupInMethod.invoke(null, klass, MethodHandles.lookup()));

        Modules.get().add(new StashEvac());
        Modules.get().add(new ChestEvac());
    }

    @Override
    public void onRegisterCategories() {
        Modules.registerCategory(CATEGORY);
    }

    @Override
    public String getPackage() {
        return "com.example.stashevac";
    }

    @Override
    public GithubRepo getRepo() {
        return new GithubRepo("YourName", "meteor-stash-evac");
    }
}
