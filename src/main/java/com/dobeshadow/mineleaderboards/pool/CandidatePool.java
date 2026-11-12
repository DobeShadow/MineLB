package com.dobeshadow.mineleaderboards.pool;

import com.dobeshadow.mineleaderboards.config.PluginConfig;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * 候选玩家池。
 * <p>
 * 老服的 usercache 可能有几万条，绝不能每轮都遍历 Bukkit.getOfflinePlayers()。
 * 这里在启动时扫一次 playerdata 目录，之后按需刷新，结果以不可变快照形式缓存。
 * <p>
 * 所有方法都必须在主线程调用（会触碰 Bukkit API）。
 */
public final class CandidatePool {

    private final Plugin plugin;
    private final Logger logger;
    private volatile List<Candidate> snapshot = List.of();
    private volatile long lastRebuild = 0L;

    public CandidatePool(Plugin plugin) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
    }

    /**
     * 重建候选池。必须在主线程执行。
     */
    public void rebuild(PluginConfig cfg) {
        long start = System.currentTimeMillis();
        long cutoff = System.currentTimeMillis() - (long) cfg.getActiveWithinDays() * 24L * 3600L * 1000L;
        Set<UUID> uuids = new HashSet<>();

        List<String> sources = cfg.getCandidateSources();

        // a) playerdata 目录
        if (sources.contains("playerdata")) {
            collectFromPlayerdata(uuids);
        }

        // b) 在线玩家
        if (sources.contains("online")) {
            Bukkit.getOnlinePlayers().forEach(p -> uuids.add(p.getUniqueId()));
        }

        // c) 配置里手写的额外名单
        if (sources.contains("extra")) {
            for (String name : cfg.getExtraPlayers()) {
                if (name == null || name.isBlank()) {
                    continue;
                }
                OfflinePlayer op = Bukkit.getOfflinePlayer(name);
                if (op.getUniqueId() != null) {
                    uuids.add(op.getUniqueId());
                }
            }
        }

        List<Candidate> list = new ArrayList<>(uuids.size());
        for (UUID uuid : uuids) {
            OfflinePlayer op = Bukkit.getOfflinePlayer(uuid);
            boolean online = op.isOnline();
            if (!online && !op.hasPlayedBefore()) {
                continue;
            }
            long lastPlayed = op.getLastPlayed();
            // 在线玩家 lastPlayed 可能为 0，不参与活跃度过滤
            if (!online && lastPlayed > 0 && lastPlayed < cutoff) {
                continue;
            }
            String name = op.getName();
            if (name == null || name.isBlank()) {
                continue;
            }
            list.add(new Candidate(uuid, name, op, lastPlayed));
        }

        // 按 lastPlayed 倒序，截断到上限
        list.sort(Comparator.comparingLong(Candidate::lastPlayed).reversed());
        if (list.size() > cfg.getMaxCandidates()) {
            list = new ArrayList<>(list.subList(0, cfg.getMaxCandidates()));
        }

        this.snapshot = List.copyOf(list);
        this.lastRebuild = System.currentTimeMillis();
        logger.info("候选玩家池已重建: " + snapshot.size() + " 人, 耗时 "
                + (System.currentTimeMillis() - start) + "ms");
    }

    private void collectFromPlayerdata(Set<UUID> out) {
        List<World> worlds = Bukkit.getWorlds();
        if (worlds.isEmpty()) {
            return;
        }
        // 主世界的 playerdata 是全局共享的，扫一次即可
        File dir = new File(worlds.get(0).getWorldFolder(), "playerdata");
        if (!dir.isDirectory()) {
            logger.warning("playerdata 目录不存在: " + dir.getAbsolutePath());
            return;
        }
        File[] files = dir.listFiles((d, n) -> n.endsWith(".dat"));
        if (files == null) {
            return;
        }
        for (File f : files) {
            String n = f.getName();
            String id = n.substring(0, n.length() - 4);
            try {
                out.add(UUID.fromString(id));
            } catch (IllegalArgumentException ignored) {
                // 非标准 UUID 文件名，跳过
            }
        }
    }

    public List<Candidate> get() {
        return snapshot;
    }

    public long getLastRebuild() {
        return lastRebuild;
    }

    /**
     * 候选玩家。
     */
    public record Candidate(UUID uuid, String name, OfflinePlayer player, long lastPlayed) {
    }
}
