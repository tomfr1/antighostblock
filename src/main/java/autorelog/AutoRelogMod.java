package autorelog;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.block.Block;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.gui.screen.multiplayer.ConnectScreen;
import net.minecraft.client.gui.screen.multiplayer.MultiplayerScreen;
import net.minecraft.client.network.ServerAddress;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.math.BlockPos;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

public class AutoRelogMod implements ClientModInitializer {
    private enum State { IDLE, DISCONNECTING, WAITING, CONNECTING }

    private static class Placed {
        final BlockPos hitPos;
        final BlockPos offsetPos;
        final Block block;
        final int created;
        BlockPos pos = null; // gesetzt, sobald der Client den Block wirklich anzeigt

        Placed(BlockPos hitPos, BlockPos offsetPos, Block block, int created) {
            this.hitPos = hitPos;
            this.offsetPos = offsetPos;
            this.block = block;
            this.created = created;
        }
    }

    private static final int CONFIRM_AFTER = 2;   // Ticks, bis der Client den Block anzeigen sollte
    private static final int CHECK_AFTER = 12;    // Ticks, nach denen der Block noch stehen muss
    private static final int MAX_ATTEMPTS = 5;

    private static KeyBinding toggleKey;
    private static KeyBinding relogKey;
    private static boolean autoDetect = true;

    private static State state = State.IDLE;
    private static int counter = 0;
    private static int attempts = 0;
    private static int tickCounter = 0;
    private static int lastRelogTick = -100000;
    private static int failStreak = 0;
    private static String address = null;
    private static String serverName = null;
    private static final List<Placed> pending = new ArrayList<>();

    @Override
    public void onInitializeClient() {
        RelogConfig.load();

        toggleKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.autorelog.toggle", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_G, KeyBinding.Category.MISC));
        relogKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.autorelog.relog", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_L, KeyBinding.Category.MISC));

        // Jede Block-Platzierung merken, die der Spieler auslöst
        UseBlockCallback.EVENT.register((player, world, hand, hit) -> {
            if (world.isClient() && autoDetect && state == State.IDLE && pending.size() < 200) {
                ItemStack stack = player.getStackInHand(hand);
                if (stack.getItem() instanceof BlockItem bi) {
                    pending.add(new Placed(hit.getBlockPos(), hit.getBlockPos().offset(hit.getSide()),
                            bi.getBlock(), tickCounter));
                }
            }
            return ActionResult.PASS;
        });

        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            tickCounter++;

            while (toggleKey.wasPressed()) {
                autoDetect = !autoDetect;
                pending.clear();
                failStreak = 0;
                if (mc.player != null)
                    mc.player.sendMessage(Text.literal("AutoRelog: " + (autoDetect ? "AN" : "AUS")), true);
            }
            while (relogKey.wasPressed()) {
                if (state == State.IDLE) relog(mc, "Manueller Relog");
            }

            switch (state) {
                case IDLE -> detect(mc);
                case DISCONNECTING -> {
                    if (mc.world == null) {
                        state = State.WAITING;
                        counter = RelogConfig.delaySeconds * 20;
                    } else if (++counter > 100) {
                        state = State.IDLE; // Disconnect hat nicht geklappt
                    }
                }
                case WAITING -> {
                    if (--counter <= 0) {
                        connect(mc);
                        state = State.CONNECTING;
                        counter = 0;
                        attempts++;
                    }
                }
                case CONNECTING -> {
                    if (mc.world != null && mc.player != null) {
                        state = State.IDLE;
                        attempts = 0;
                        lastRelogTick = tickCounter;
                        pending.clear();
                        failStreak = 0;
                    } else if (++counter > 400) {
                        if (attempts < MAX_ATTEMPTS) {
                            state = State.WAITING;
                            counter = 100;
                        } else {
                            state = State.IDLE;
                        }
                    }
                }
            }
        });
    }

    private static void detect(MinecraftClient mc) {
        if (!autoDetect || mc.world == null || mc.player == null) {
            pending.clear();
            failStreak = 0;
            return;
        }

        Iterator<Placed> it = pending.iterator();
        while (it.hasNext()) {
            Placed p = it.next();
            int age = tickCounter - p.created;

            if (p.pos == null) {
                if (age < CONFIRM_AFTER) continue;
                // Zeigt der Client den Block an? Wenn nicht, war die Platzierung schon lokal ungültig.
                if (mc.world.getBlockState(p.offsetPos).isOf(p.block)) p.pos = p.offsetPos;
                else if (mc.world.getBlockState(p.hitPos).isOf(p.block)) p.pos = p.hitPos;
                else it.remove();
                continue;
            }

            if (age < CHECK_AFTER) continue;
            it.remove();
            // Client hat den Block gezeigt. Ist er jetzt weg, hat der Server ihn abgelehnt.
            if (mc.world.getBlockState(p.pos).isOf(p.block)) failStreak = 0;
            else failStreak++;
        }

        if (failStreak >= RelogConfig.threshold) {
            if (tickCounter - lastRelogTick >= RelogConfig.minIntervalSeconds * 20) {
                relog(mc, "Ghost-Blöcke erkannt");
            } else {
                failStreak = 0;
            }
        }
    }

    private static void relog(MinecraftClient mc, String reason) {
        ServerInfo info = mc.getCurrentServerEntry();
        if (info == null || mc.getNetworkHandler() == null) {
            if (mc.player != null)
                mc.player.sendMessage(Text.literal("AutoRelog: funktioniert nur auf einem Server"), true);
            return;
        }
        address = info.address;
        serverName = info.name;
        pending.clear();
        failStreak = 0;
        attempts = 0;
        state = State.DISCONNECTING;
        counter = 0;
        mc.getNetworkHandler().getConnection().disconnect(Text.literal(reason));
    }

    private static void connect(MinecraftClient mc) {
        ServerInfo info = new ServerInfo(serverName, address, ServerInfo.ServerType.OTHER);
        ConnectScreen.connect(new MultiplayerScreen(new TitleScreen()), mc,
                ServerAddress.parse(address), info, false, null);
    }
}
