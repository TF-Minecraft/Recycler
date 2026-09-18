package net.tfminecraft.recycler.command;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import net.tfminecraft.recycler.Messages;
import net.tfminecraft.recycler.Recycler;
import net.tfminecraft.recycler.manager.EscrowManager;

public final class CommandManager implements CommandExecutor, TabCompleter {

    private static final List<String> ROOT_SUBCOMMANDS = List.of("reload", "escrow");
    private static final List<String> ESCROW_SUBCOMMANDS = List.of("list", "return");

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("recycler.admin")) {
            sender.sendMessage("§cNo permission.");
            return true;
        }
        if (args.length == 0) {
            sender.sendMessage("§e/recycler reload");
            sender.sendMessage("§e/recycler escrow list");
            sender.sendMessage("§e/recycler escrow return <player>");
            return true;
        }
        if ("reload".equalsIgnoreCase(args[0])) {
            boolean ok = Recycler.plugin.reloadAll();
            sender.sendMessage(ok ? Messages.get("reload.success") : Messages.get("reload.failed"));
            return true;
        }
        if ("escrow".equalsIgnoreCase(args[0])) {
            return handleEscrow(sender, args);
        }
        sender.sendMessage("§cUnknown subcommand. Use §e/reload §cor §eescrow");
        return true;
    }

    private boolean handleEscrow(CommandSender sender, String[] args) {
        EscrowManager escrow = Recycler.plugin.getEscrowManager();
        if (args.length < 2) {
            sender.sendMessage("§e/recycler escrow list");
            sender.sendMessage("§e/recycler escrow return <player>");
            return true;
        }
        if ("list".equalsIgnoreCase(args[1])) {
            List<UUID> memoryIds = escrow.listMemoryEscrowPlayerIds();
            int pendingCount = escrow.countPendingFiles();
            sender.sendMessage("§6Recycler escrow:");
            sender.sendMessage("§7In-memory (online): §f" + memoryIds.size());
            for (UUID id : memoryIds) {
                Player online = Bukkit.getPlayer(id);
                String name = online != null ? online.getName() : id.toString();
                sender.sendMessage("§8- §f" + name);
            }
            sender.sendMessage("§7Pending return files: §f" + pendingCount);
            for (UUID id : escrow.listPendingPlayerIds()) {
                OfflinePlayer offline = Bukkit.getOfflinePlayer(id);
                String name = offline.getName() != null ? offline.getName() : id.toString();
                sender.sendMessage("§8- §f" + name + " §7(pending)");
            }
            return true;
        }
        if ("return".equalsIgnoreCase(args[1])) {
            if (args.length < 3) {
                sender.sendMessage("§cUsage: /recycler escrow return <player>");
                return true;
            }
            String targetName = args[2];
            Player online = Bukkit.getPlayer(targetName);
            if (online != null) {
                boolean hadEscrow = escrow.hasEscrow(online.getUniqueId());
                boolean hadPending = escrow.hasPending(online.getUniqueId());
                escrow.adminReturn(online);
                if (hadEscrow || hadPending) {
                    sender.sendMessage("§aReturned escrow to §f" + online.getName() + "§a.");
                } else {
                    sender.sendMessage("§7No escrow or pending return for §f" + online.getName() + "§7.");
                }
                return true;
            }
            for (UUID id : escrow.listPendingPlayerIds()) {
                OfflinePlayer offline = Bukkit.getOfflinePlayer(id);
                if (offline.getName() != null && offline.getName().equalsIgnoreCase(targetName)) {
                    sender.sendMessage("§7Player offline. Pending return file exists for §f" + offline.getName()
                            + "§7 - will deliver on join.");
                    return true;
                }
            }
            sender.sendMessage("§cNo online player or pending return found for §f" + targetName + "§c.");
            return true;
        }
        sender.sendMessage("§cUnknown escrow subcommand. Use §elist §cor §ereturn");
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission("recycler.admin")) {
            return Collections.emptyList();
        }
        if (args.length == 1) {
            return filterPrefix(ROOT_SUBCOMMANDS, args[0]);
        }
        if (args.length == 2 && "escrow".equalsIgnoreCase(args[0])) {
            return filterPrefix(ESCROW_SUBCOMMANDS, args[1]);
        }
        if (args.length == 3 && "escrow".equalsIgnoreCase(args[0]) && "return".equalsIgnoreCase(args[1])) {
            List<String> names = new ArrayList<>();
            for (Player player : Bukkit.getOnlinePlayers()) {
                names.add(player.getName());
            }
            return filterPrefix(names, args[2]);
        }
        return Collections.emptyList();
    }

    private static List<String> filterPrefix(List<String> options, String prefix) {
        if (prefix == null || prefix.isEmpty()) {
            return options;
        }
        String lower = prefix.toLowerCase(Locale.ROOT);
        List<String> matches = new ArrayList<>();
        for (String option : options) {
            if (option.toLowerCase(Locale.ROOT).startsWith(lower)) {
                matches.add(option);
            }
        }
        return matches;
    }
}
