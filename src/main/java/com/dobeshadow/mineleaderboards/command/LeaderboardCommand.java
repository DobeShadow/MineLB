package com.dobeshadow.mineleaderboards.command;

import com.dobeshadow.mineleaderboards.MineLeaderboardsPlugin;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * /mineleaderboards 命令：reload / push / test &lt;board&gt;
 * 别名：/minelb、/mlb
 */
public final class LeaderboardCommand implements CommandExecutor, TabCompleter {

    private static final List<String> BOARDS = List.of("wealth", "playtime", "duel", "signin");

    private final MineLeaderboardsPlugin plugin;

    public LeaderboardCommand(MineLeaderboardsPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("mineleaderboards.admin")) {
            sender.sendMessage("§c你没有权限执行此命令。");
            return true;
        }
        if (args.length == 0) {
            sender.sendMessage("§e用法: /mineleaderboards <reload|push|test>");
            return true;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "reload" -> {
                plugin.reloadAll();
                sender.sendMessage("§a配置已重载，排行榜任务已重建。");
            }
            case "push" -> {
                sender.sendMessage("§e正在立即推送排行榜...");
                plugin.getLeaderboardService().pushNow();
            }
            case "test" -> {
                if (args.length < 2) {
                    sender.sendMessage("§e用法: /mineleaderboards test <wealth|playtime|duel|signin>");
                    return true;
                }
                String board = args[1].toLowerCase(Locale.ROOT);
                sender.sendMessage("§e正在采集榜单 " + board + "，结果输出到控制台...");
                plugin.getLeaderboardService().testBoard(board);
            }
            default -> sender.sendMessage("§e用法: /mineleaderboards <reload|push|test>");
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission("mineleaderboards.admin")) {
            return List.of();
        }
        if (args.length == 1) {
            return filter(List.of("reload", "push", "test"), args[0]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("test")) {
            return filter(BOARDS, args[1]);
        }
        return List.of();
    }

    private static List<String> filter(List<String> options, String prefix) {
        String p = prefix.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (String o : options) {
            if (o.startsWith(p)) {
                out.add(o);
            }
        }
        return out;
    }
}
