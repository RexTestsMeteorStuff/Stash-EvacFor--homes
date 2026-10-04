package com.example.stashevac.modules;

import com.example.stashevac.StashEvacAddon;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Chest Evac
 *
 *  1. EVAC    - empty the nearest chests into your inventory, an ender chest behind you, or both.
 *  2. CHAT    - send the text from the "message" bar to chat once (e.g. "/tpa friend").
 *  3. DEPOSIT - after a delay, put items into the nearest chest from the selected source.
 *  4. repeat.
 *
 * The keybind, Active toggle and Chat Feedback rows come from Meteor's standard module window.
 */
public class ChestEvac extends Module {
    public enum Destination { Inventory, EChest, Both }
    public enum Source { Inventory, EChest, Both }

    private enum Phase { EVAC, SEND_CHAT, DEPOSIT, COOLDOWN }
    private enum Step { FIND, WAIT_MENU, TRANSFER }
    private enum Op { LOOT, DUMP, DEPOSIT, PULL }

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgChat = settings.createGroup("Chat");
    private final SettingGroup sgFilter = settings.createGroup("Filter");

    // ---- General ----
    private final Setting<Destination> destination = sgGeneral.add(new EnumSetting.Builder<Destination>()
        .name("evac-destination")
        .description("Where looted items go. Both = fill inventory first, overflow into the ender chest.")
        .defaultValue(Destination.Both)
        .build());

    private final Setting<Source> source = sgGeneral.add(new EnumSetting.Builder<Source>()
        .name("deposit-source")
        .description("Where items are taken from when depositing into the nearest chest.")
        .defaultValue(Source.Both)
        .build());

    private final Setting<Double> reach = sgGeneral.add(new DoubleSetting.Builder()
        .name("reach")
        .description("Max distance to a container. Keep at or below your interaction reach.")
        .defaultValue(4.5)
        .min(1)
        .sliderRange(2, 5)
        .build());

    private final Setting<Boolean> echestBehind = sgGeneral.add(new BoolSetting.Builder()
        .name("echest-behind-only")
        .description("Only use an ender chest that is behind the direction you're facing.")
        .defaultValue(true)
        .build());

    private final Setting<Integer> clickDelay = sgGeneral.add(new IntSetting.Builder()
        .name("click-delay")
        .description("Ticks between item clicks.")
        .defaultValue(1)
        .min(0)
        .sliderRange(0, 10)
        .build());

    private final Setting<Boolean> repeat = sgGeneral.add(new BoolSetting.Builder()
        .name("repeat")
        .description("Run the whole cycle again after it finishes.")
        .defaultValue(true)
        .build());

    private final Setting<Integer> cycleDelay = sgGeneral.add(new IntSetting.Builder()
        .name("cycle-delay-seconds")
        .description("Pause between cycles.")
        .defaultValue(3)
        .min(0)
        .sliderRange(0, 60)
        .visible(repeat::get)
        .build());

    // ---- Chat ----
    private final Setting<String> message = sgChat.add(new StringSetting.Builder()
        .name("message")
        .description("Text sent to chat after the evac step. Starts with / to run a command. Blank = send nothing.")
        .defaultValue("")
        .build());

    private final Setting<Boolean> sendOnce = sgChat.add(new BoolSetting.Builder()
        .name("send-once")
        .description("Only send the message in the first cycle instead of every cycle.")
        .defaultValue(false)
        .build());

    private final Setting<Integer> afterMessageDelay = sgChat.add(new IntSetting.Builder()
        .name("delay-after-message-seconds")
        .description("Wait this long after sending before depositing (e.g. teleport time).")
        .defaultValue(6)
        .min(0)
        .sliderRange(0, 60)
        .build());

    // ---- Filter ----
    private final Setting<List<Block>> containers = sgFilter.add(new BlockListSetting.Builder()
        .name("containers")
        .description("Containers treated as chests (single-inventory 9-wide menus only).")
        .defaultValue(Blocks.CHEST, Blocks.TRAPPED_CHEST, Blocks.BARREL)
        .build());

    private final Setting<List<Item>> blacklist = sgFilter.add(new ItemListSetting.Builder()
        .name("item-blacklist")
        .description("Items that are never moved out of your inventory.")
        .defaultValue(List.of())
        .build());

    private final Setting<Boolean> includeHotbar = sgFilter.add(new BoolSetting.Builder()
        .name("include-hotbar")
        .description("Also deposit/dump items from your hotbar.")
        .defaultValue(false)
        .build());

    // ---- State ----
    private Phase phase;
    private Step step;
    private Op op;
    private BlockPos pos;
    private final Set<BlockPos> visited = new HashSet<>();
    private boolean echestFull, echestEmpty;
    private boolean sentOnce;
    private int delay, timeout, lastSlot, sameCount;

    public ChestEvac() {
        super(StashEvacAddon.CATEGORY, "chest-evac",
            "Empties nearby chests to inventory / ender chest, sends a chat message, then deposits and repeats.");
    }

    @Override
    public void onActivate() {
        sentOnce = false;
        beginEvac();
    }

    @Override
    public void onDeactivate() {
        if (mc.player != null && mc.player.containerMenu instanceof ChestMenu) mc.player.closeContainer();
    }

    // ---------------------------------------------------------------- main loop

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.level == null || mc.gameMode == null) return;
        if (delay > 0) { delay--; return; }

        switch (phase) {
            case EVAC, DEPOSIT -> runWork();
            case SEND_CHAT -> {
                String msg = message.get().trim();
                if (!msg.isEmpty() && !(sendOnce.get() && sentOnce)) {
                    sendChat(msg);
                    sentOnce = true;
                    delay = afterMessageDelay.get() * 20;
                }
                beginDeposit();
            }
            case COOLDOWN -> {
                if (repeat.get()) beginEvac();
                else { info("Cycle finished."); toggle(); }
            }
        }
    }

    private void beginEvac() {
        phase = Phase.EVAC; step = Step.FIND; visited.clear();
        echestFull = false; echestEmpty = false; lastSlot = -1; sameCount = 0;
    }

    private void beginDeposit() {
        phase = Phase.DEPOSIT; step = Step.FIND; visited.clear(); echestPulled = false;
        echestFull = false; echestEmpty = false; lastSlot = -1; sameCount = 0;
    }

    private void endPhase() {
        switch (phase) {
            case EVAC -> phase = Phase.SEND_CHAT;
            case DEPOSIT -> { phase = Phase.COOLDOWN; delay = cycleDelay.get() * 20; }
            default -> {}
        }
        step = Step.FIND;
    }

    private void runWork() {
        switch (step) {
            case FIND -> {
                op = nextOp();
                if (op == null) { endPhase(); return; }

                pos = (op == Op.DUMP || op == Op.PULL)
                    ? findNearest(b -> b == Blocks.ENDER_CHEST, echestBehind.get(), false)
                    : findNearest(b -> containers.get().contains(b), false, true);

                if (pos == null) {
                    if (op == Op.DUMP || op == Op.PULL) warning("No ender chest in reach" + (echestBehind.get() ? " behind you." : "."));
                    endPhase();
                    return;
                }

                mc.player.setShiftKeyDown(false);
                BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false);
                mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, hit);
                mc.player.swing(InteractionHand.MAIN_HAND);
                step = Step.WAIT_MENU;
                timeout = 30;
            }
            case WAIT_MENU -> {
                if (mc.player.containerMenu instanceof ChestMenu) {
                    step = Step.TRANSFER; lastSlot = -1; sameCount = 0;
                    delay = 2;
                } else if (--timeout <= 0) {
                    visited.add(pos.immutable()); // couldn't open it, skip
                    step = Step.FIND;
                }
            }
            case TRANSFER -> {
                if (mc.player.containerMenu instanceof ChestMenu menu) transfer(menu);
                else step = Step.FIND;
            }
        }
    }

    // ---------------------------------------------------------------- decisions

    /** Picks the next container operation for the current phase, or null when the phase is done. */
    private Op nextOp() {
        if (phase == Phase.EVAC) {
            boolean useEchest = destination.get() != Destination.Inventory;
            if (!hasFreeSlot()) return (useEchest && !echestFull) ? Op.DUMP : null;
            if (findNearest(b -> containers.get().contains(b), false, true) != null) return Op.LOOT;
            if (destination.get() == Destination.EChest && hasDepositable() && !echestFull) return Op.DUMP;
            return null;
        }

        // DEPOSIT
        boolean fromInv = source.get() != Source.EChest;
        boolean fromEchest = source.get() != Source.Inventory;
        boolean chestLeft = findNearest(b -> containers.get().contains(b), false, true) != null;

        if (hasDepositable() && (fromInv || echestPulled)) {
            return chestLeft ? Op.DEPOSIT : null;
        }
        if (fromEchest && !echestEmpty && hasFreeSlot()) {
            echestPulled = true;
            return Op.PULL;
        }
        return null;
    }

    // EChest-only source: inventory is only deposited after something was pulled from the ender chest.
    private boolean echestPulled = false;

    // ---------------------------------------------------------------- transfers

    private void transfer(ChestMenu menu) {
        int size = menu.getRowCount() * 9;

        switch (op) {
            case LOOT, PULL -> {
                int s = firstNonEmpty(menu, 0, size);
                if (s < 0) {
                    if (op == Op.LOOT) visited.add(pos.immutable()); else echestEmpty = true;
                    finishContainer();
                } else if (!hasFreeSlot()) {
                    finishContainer();
                } else click(s);
            }
            case DEPOSIT, DUMP -> {
                int s = firstDepositable(menu, size);
                if (s < 0) { finishContainer(); return; }
                if (firstEmpty(menu, 0, size) < 0) { // container full
                    if (op == Op.DUMP) echestFull = true; else visited.add(pos.immutable());
                    finishContainer();
                    return;
                }
                click(s);
            }
        }
    }

    private void click(int slotId) {
        if (slotId == lastSlot && ++sameCount > 3) { // click isn't doing anything, bail out
            visited.add(pos.immutable());
            finishContainer();
            return;
        }
        if (slotId != lastSlot) { lastSlot = slotId; sameCount = 0; }
        InvUtils.shiftClick().slotId(slotId);
        delay = clickDelay.get();
    }

    private void finishContainer() {
        mc.player.closeContainer();
        step = Step.FIND;
        delay = 3;
        lastSlot = -1; sameCount = 0;
    }

    // ---------------------------------------------------------------- helpers

    private int firstNonEmpty(ChestMenu m, int from, int to) {
        for (int i = from; i < to; i++) if (!m.getSlot(i).getItem().isEmpty()) return i;
        return -1;
    }

    private int firstEmpty(ChestMenu m, int from, int to) {
        for (int i = from; i < to; i++) if (m.getSlot(i).getItem().isEmpty()) return i;
        return -1;
    }

    /** Menu slot ids [size, size+27) = main inventory, [size+27, size+36) = hotbar. */
    private int firstDepositable(ChestMenu m, int size) {
        int end = size + (includeHotbar.get() ? 36 : 27);
        for (int i = size; i < end; i++) {
            ItemStack st = m.getSlot(i).getItem();
            if (!st.isEmpty() && !blacklist.get().contains(st.getItem())) return i;
        }
        return -1;
    }

    private boolean hasFreeSlot() {
        for (int i = 0; i < 36; i++) if (mc.player.getInventory().getItem(i).isEmpty()) return true;
        return false;
    }

    private boolean hasDepositable() {
        int from = includeHotbar.get() ? 0 : 9;
        for (int i = from; i < 36; i++) {
            ItemStack st = mc.player.getInventory().getItem(i);
            if (!st.isEmpty() && !blacklist.get().contains(st.getItem())) return true;
        }
        return false;
    }

    private BlockPos findNearest(Predicate<Block> test, boolean behindOnly, boolean skipVisited) {
        Vec3 eye = mc.player.getEyePosition();
        double reachSq = reach.get() * reach.get();
        int r = (int) Math.ceil(reach.get());
        BlockPos base = mc.player.blockPosition();
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();

        BlockPos best = null;
        double bestD = Double.MAX_VALUE;

        for (int x = -r; x <= r; x++) for (int y = -r; y <= r; y++) for (int z = -r; z <= r; z++) {
            p.set(base.getX() + x, base.getY() + y, base.getZ() + z);
            if (!test.test(mc.level.getBlockState(p).getBlock())) continue;
            if (skipVisited && visited.contains(p)) continue;

            double d = eye.distanceToSqr(Vec3.atCenterOf(p));
            if (d > reachSq || d >= bestD) continue;
            if (behindOnly && !isBehind(p)) continue;

            best = p.immutable();
            bestD = d;
        }
        return best;
    }

    private boolean isBehind(BlockPos p) {
        double yaw = Math.toRadians(mc.player.getYRot());
        double fx = -Math.sin(yaw), fz = Math.cos(yaw);
        double ox = p.getX() + 0.5 - mc.player.getX();
        double oz = p.getZ() + 0.5 - mc.player.getZ();
        return ox * fx + oz * fz < 0;
    }

    private void sendChat(String msg) {
        if (mc.player.connection == null) return;
        if (msg.startsWith("/")) mc.player.connection.sendCommand(msg.substring(1));
        else mc.player.connection.sendChat(msg);
    }
}
