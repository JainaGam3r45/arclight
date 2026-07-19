package io.izzel.arclight.common.mod.server.chunk;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import io.izzel.arclight.common.bridge.core.command.CommandSourceBridge;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.craftbukkit.v.CraftServer;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionDefault;

/**
 * Ops inspection for chunk workload budget. Permission {@code minecraft.command.chunkcap}.
 */
public final class ChunkCapCommand {

    private static final String PERMISSION = "minecraft.command.chunkcap";

    private ChunkCapCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        if (Bukkit.getPluginManager().getPermission(PERMISSION) == null) {
            Bukkit.getPluginManager().addPermission(new Permission(PERMISSION, PermissionDefault.OP));
        }
        LiteralArgumentBuilder<CommandSourceStack> command = Commands.literal("chunkcap")
            .requires(source -> ((CommandSourceBridge) source).bridge$hasPermission(2, PERMISSION))
            .executes(ChunkCapCommand::show);
        dispatcher.register(command);

        Command bukkit = ((CraftServer) Bukkit.getServer()).getCommandMap().getCommand("chunkcap");
        if (bukkit != null) {
            bukkit.setPermission(PERMISSION);
            bukkit.setPermissionMessage(ChatColor.RED + "You do not have permission for /chunkcap.");
        }
    }

    private static int show(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        try {
            ChunkWorkloadManager.Stats stats = ChunkWorkloadManager.snapshot();
            msg(source, ChatColor.GOLD + "=== Arclight chunkcap ===");
            msg(source, ChatColor.YELLOW + "enabled=" + ChatColor.WHITE + stats.enabled()
                + ChatColor.GRAY + " mspt~" + String.format("%.1f", stats.mspt())
                + ChatColor.YELLOW + " skipGen=" + ChatColor.WHITE + stats.skipGen()
                + ChatColor.GRAY + " (threshold>=" + stats.skipGenMspt() + ")");
            msg(source, ChatColor.YELLOW + "last tick: "
                + ChatColor.GRAY + "gens=" + ChatColor.WHITE + stats.gensThisTick() + "/" + stats.maxGens()
                + ChatColor.GRAY + " loads=" + ChatColor.WHITE + stats.loadsThisTick() + "/" + stats.maxLoads()
                + ChatColor.GRAY + " sends=" + ChatColor.WHITE + stats.sendsThisTick() + "/" + stats.maxSends()
                + ChatColor.GRAY + " maxMs=" + stats.maxMs());
            msg(source, ChatColor.YELLOW + "queues: "
                + ChatColor.GRAY + "deferredSchedule=" + ChatColor.WHITE + stats.deferredScheduleQueue()
                + ChatColor.GRAY + " deferredSend=" + ChatColor.WHITE + stats.deferredSendQueue());
            msg(source, ChatColor.DARK_GRAY + "Tip: lower spigot.yml view-distance / simulation-distance; avoid keep-spawn-loaded on empty worlds");
            msg(source, ChatColor.DARK_GRAY + "Safe: pollTask never gated; sync getChunk flushes deferred work first");
            return 1;
        } catch (Exception e) {
            source.sendFailure(Component.literal(ChatColor.RED + "chunkcap failed: " + e.getMessage()));
            e.printStackTrace();
            return 0;
        }
    }

    private static void msg(CommandSourceStack source, String text) {
        source.sendSuccess(() -> Component.literal(text), false);
        try {
            CommandSender bukkit = ((CommandSourceBridge) source).bridge$getBukkitSender();
            if (bukkit != null) {
                bukkit.sendMessage(text);
            }
        } catch (Exception ignored) {
        }
    }
}
