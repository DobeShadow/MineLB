package com.dobeshadow.mineleaderboards.metric;

import com.dobeshadow.mineleaderboards.config.PluginConfig;
import com.dobeshadow.mineleaderboards.pool.CandidatePool;
import com.dobeshadow.mineleaderboards.util.NumberUtil;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * 基于 PlaceholderAPI 的数值采集器，duel / signin 共用。
 * <p>
 * 为避免编译期硬依赖 PlaceholderAPI，这里通过反射调用。
 * 很多 PAPI 扩展只对在线 Player 有效，对 OfflinePlayer 会返回空或原样占位符，
 * 配置 allow-offline=false 时只统计在线玩家。
 */
public final class PapiNumberProvider implements MetricProvider {

    private final String key;
    private final Logger logger;
    private final PluginConfig.BoardConfig cfg;
    private final boolean papiAvailable;
    private final Method setPlaceholders;

    public PapiNumberProvider(JavaPlugin plugin, String key, PluginConfig.BoardConfig cfg) {
        this.key = key;
        this.logger = plugin.getLogger();
        this.cfg = cfg;

        Method m = null;
        boolean available = false;
        try {
            Class<?> papi = Class.forName("me.clip.placeholderapi.PlaceholderAPI");
            m = papi.getMethod("setPlaceholders", org.bukkit.OfflinePlayer.class, String.class);
            available = Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI");
        } catch (Throwable ignored) {
            available = false;
        }
        this.setPlaceholders = m;
        this.papiAvailable = available;

        if (cfg.isEnabled() && !papiAvailable) {
            logger.warning("榜单 " + key + " 使用 PAPI 但 PlaceholderAPI 未安装/未启用，该榜将为空");
        }
        if (cfg.isEnabled() && cfg.getPlaceholder().isBlank()) {
            logger.warning("榜单 " + key + " 的 placeholder 未配置，该榜将为空");
        }
    }

    @Override
    public String key() {
        return key;
    }

    @Override
    public boolean isEnabled() {
        return cfg.isEnabled() && papiAvailable && setPlaceholders != null && !cfg.getPlaceholder().isBlank();
    }

    @Override
    public Map<UUID, Double> collect(CandidatePool.Candidate[] pool) {
        Map<UUID, Double> out = new HashMap<>();
        if (!isEnabled()) {
            return out;
        }
        String ph = "%" + cfg.getPlaceholder() + "%";
        boolean allowOffline = cfg.isAllowOffline();
        for (CandidatePool.Candidate c : pool) {
            if (!allowOffline && !c.player().isOnline()) {
                continue;
            }
            try {
                Object raw = setPlaceholders.invoke(null, c.player(), ph);
                Double v = NumberUtil.parseNonNegative(raw == null ? null : raw.toString());
                if (v != null) {
                    out.put(c.uuid(), v);
                }
            } catch (Throwable ignored) {
                // 单个玩家解析失败不影响整体
            }
        }
        return out;
    }
}
