package com.example.stashevac.modules;

import com.example.stashevac.StashEvacAddon;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.friends.Friends;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.player.AbstractClientPlayer;

import java.util.List;
import java.util.Locale;

/**
 * Stash Evac: when an unfamiliar player comes within range of you,
 * automatically sends a teleport-request command (e.g. /tpa <friend>)
 * so you get pulled out of the stash area fast on TPA-based servers.
 */
public class StashEvac extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgFilter = settings.createGroup("Filter");

    private final Setting<String> command = sgGeneral.add(new StringSetting.Builder()
        .name("command")
        .description("Command to send, without the leading slash. {target} is replaced by the target name.")
        .defaultValue("tpa {target}")
        .build());

    private final Setting<String> target = sgGeneral.add(new StringSetting.Builder()
        .name("target")
        .description("Player to request a teleport to (your alt / base account).")
        .defaultValue("")
        .build());

    private final Setting<Double> range = sgGeneral.add(new DoubleSetting.Builder()
        .name("trigger-range")
        .description("Distance at which another player triggers the evac.")
        .defaultValue(64)
        .min(4)
        .sliderRange(8, 256)
        .build());

    private final Setting<Integer> cooldown = sgGeneral.add(new IntSetting.Builder()
        .name("cooldown-seconds")
        .description("Minimum time between command sends while a player stays in range.")
        .defaultValue(15)
        .min(1)
        .sliderRange(1, 120)
        .build());

    private final Setting<Boolean> disableAfterSend = sgGeneral.add(new BoolSetting.Builder()
        .name("disable-after-send")
        .description("Turn the module off after the first command is sent.")
        .defaultValue(false)
        .build());

    private final Setting<Boolean> ignoreFriends = sgFilter.add(new BoolSetting.Builder()
        .name("ignore-friends")
        .description("Don't trigger on players in your Meteor friends list.")
        .defaultValue(true)
        .build());

    private final Setting<List<String>> whitelist = sgFilter.add(new StringListSetting.Builder()
        .name("whitelist")
        .description("Extra player names that never trigger the evac.")
        .defaultValue(List.of())
        .build());

    private int timer = 0;

    public StashEvac() {
        super(StashEvacAddon.CATEGORY, "stash-evac",
            "Sends a TPA command when an unknown player gets near your stash.");
    }

    @Override
    public void onActivate() {
        timer = 0;
        if (target.get().isBlank() && command.get().contains("{target}")) {
            warning("No target set — configure one in the module settings.");
        }
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.player == null || mc.level == null || mc.player.connection == null) return;

        if (timer > 0) {
            timer--;
            return;
        }

        AbstractClientPlayer threat = findThreat();
        if (threat == null) return;

        String name = threat.getName().getString();
        String cmd = command.get().replace("{target}", target.get().trim()).trim();
        if (cmd.startsWith("/")) cmd = cmd.substring(1);
        if (cmd.isEmpty()) return;

        info("(highlight)%s(default) is in range — sending /%s", name, cmd);
        mc.player.connection.sendCommand(cmd);
        timer = cooldown.get() * 20;

        if (disableAfterSend.get()) toggle();
    }

    private AbstractClientPlayer findThreat() {
        double rangeSq = range.get() * range.get();

        for (AbstractClientPlayer player : mc.level.players()) {
            if (player == mc.player) continue;
            if (mc.player.distanceToSqr(player) > rangeSq) continue;
            if (ignoreFriends.get() && Friends.get().isFriend(player)) continue;

            String name = player.getName().getString().toLowerCase(Locale.ROOT);
            boolean whitelisted = whitelist.get().stream().anyMatch(n -> n.equalsIgnoreCase(name));
            if (whitelisted) continue;

            return player;
        }
        return null;
    }
}
