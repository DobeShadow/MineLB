package com.dobeshadow.mineleaderboards.metric;

import com.dobeshadow.mineleaderboards.config.PluginConfig;
import com.dobeshadow.mineleaderboards.pool.CandidatePool;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.Statistic;
import org.bukkit.World;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 在线时长榜。
 * <p>
 * 主来源：{@link Statistic#PLAY_ONE_MINUTE}（单位是 tick，名字有误导）。
 * 兜底：直接读各世界 stats/&lt;uuid&gt;.json 里的 minecraft:play_one_minute。
 * 多世界按配置 sum / max 合并。
 * <p>
 * 结果单位：小时。
 * <p>
 * 为避免编译期依赖 Gson，stats 文件用正则提取数值。
 */
public final class PlaytimeProvider implements MetricProvider {

    private static final double TICKS_PER_HOUR = 20.0 * 3600.0;
    private static final Pattern PLAY_MINUTE = Pattern.compile(
            "\"minecraft:play_one_minute\"\\s*:\\s*(\\d+)");

    private final JavaPlugin plugin;
    private final Logger logger;
    private final PluginConfig.BoardConfig cfg;

    public PlaytimeProvider(JavaPlugin plugin, PluginConfig.BoardConfig cfg) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
        this.cfg = cfg;
    }

    @Override
    public String key() {
        return "playtime";
    }

    @Override
    public boolean isEnabled() {
        return cfg.isEnabled();
    }

    @Override
    public Map<UUID, Double> collect(CandidatePool.Candidate[] pool) {
        Map<UUID, Double> out = new HashMap<>();
        for (CandidatePool.Candidate c : pool) {
            Double hours = readOne(c);
            if (hours != null && hours > 0) {
                out.put(c.uuid(), hours);
            }
        }
        return out;
    }

    private Double readOne(CandidatePool.Candidate c) {
        // 1) Bukkit 统计接口
        try {
            OfflinePlayer op = c.player();
            int ticks = op.getStatistic(Statistic.PLAY_ONE_MINUTE);
            if (ticks > 0) {
                return ticks / TICKS_PER_HOUR;
            }
        } catch (Throwable ignored) {
            // 部分服务端对 OfflinePlayer#getStatistic 会抛异常，走文件兜底
        }

        // 2) stats 文件兜底
        if (cfg.isFallbackStatsFile()) {
            return readFromStatsFiles(c.uuid());
        }
        return null;
    }

    private Double readFromStatsFiles(UUID uuid) {
        long total = 0L;
        long max = 0L;
        boolean found = false;
        for (World world : Bukkit.getWorlds()) {
            File f = new File(world.getWorldFolder(), "stats" + File.separator + uuid + ".json");
            if (!f.isFile()) {
                continue;
            }
            long ticks = readTicksFromFile(f);
            if (ticks < 0) {
                continue;
            }
            found = true;
            total += ticks;
            if (ticks > max) {
                max = ticks;
            }
        }
        if (!found) {
            return null;
        }
        long value = "max".equals(cfg.getMultiWorld()) ? max : total;
        return value / TICKS_PER_HOUR;
    }

    private long readTicksFromFile(File f) {
        try {
            String content = Files.readString(f.toPath(), StandardCharsets.UTF_8);
            Matcher m = PLAY_MINUTE.matcher(content);
            if (m.find()) {
                return Long.parseLong(m.group(1));
            }
            return -1;
        } catch (Exception e) {
            return -1;
        }
    }
}
