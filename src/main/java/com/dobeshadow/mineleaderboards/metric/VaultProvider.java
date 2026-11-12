package com.dobeshadow.mineleaderboards.metric;

import com.dobeshadow.mineleaderboards.config.PluginConfig;
import com.dobeshadow.mineleaderboards.pool.CandidatePool;
import com.dobeshadow.mineleaderboards.util.NumberUtil;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * 财富榜：优先 Vault 经济，Vault 不可用时用 PAPI 占位符兜底。
 * <p>
 * 为避免编译期硬依赖 Vault / PlaceholderAPI，这里全部通过反射调用。
 * 运行时若插件不存在，自动降级。
 * <p>
 * 注意：部分经济实现（如 CMI 走 MySQL）的 getBalance 不是线程安全的，
 * 且单次查询可能较慢，因此提供 {@link #collectChunk} 供上层分批调用。
 */
public final class VaultProvider implements MetricProvider {

    private final JavaPlugin plugin;
    private final Logger logger;
    private final PluginConfig.BoardConfig cfg;

    private Object economy;
    private Method getBalanceOffline;
    private boolean papiAvailable;
    private Method papiSetPlaceholders;

    public VaultProvider(JavaPlugin plugin, PluginConfig.BoardConfig cfg) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
        this.cfg = cfg;
        resolveEconomy();
        resolvePapi();
    }

    private void resolveEconomy() {
        try {
            Class<?> economyClass = Class.forName("net.milkbowl.vault.economy.Economy");
            Object servicesManager = Bukkit.getServicesManager();
            Method getRegistration = servicesManager.getClass()
                    .getMethod("getRegistration", Class.class);
            Object rsp = getRegistration.invoke(servicesManager, economyClass);
            if (rsp != null) {
                Method getProvider = rsp.getClass().getMethod("getProvider");
                this.economy = getProvider.invoke(rsp);
                this.getBalanceOffline = economyClass.getMethod("getBalance", OfflinePlayer.class);
            }
        } catch (Throwable t) {
            this.economy = null;
        }
        if (economy == null) {
            logger.warning("未找到 Vault 经济实现，财富榜将尝试使用 PAPI 兜底占位符: "
                    + cfg.getFallbackPlaceholder());
        }
    }

    private void resolvePapi() {
        try {
            Class<?> papi = Class.forName("me.clip.placeholderapi.PlaceholderAPI");
            this.papiSetPlaceholders = papi.getMethod("setPlaceholders", OfflinePlayer.class, String.class);
            this.papiAvailable = Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI");
        } catch (Throwable t) {
            this.papiAvailable = false;
        }
    }

    @Override
    public String key() {
        return "wealth";
    }

    @Override
    public boolean isEnabled() {
        if (!cfg.isEnabled()) {
            return false;
        }
        return economy != null || (papiAvailable && !cfg.getFallbackPlaceholder().isBlank());
    }

    @Override
    public Map<UUID, Double> collect(CandidatePool.Candidate[] pool) {
        Map<UUID, Double> out = new HashMap<>();
        int batch = 50;
        for (int i = 0; i < pool.length; i += batch) {
            int end = Math.min(i + batch, pool.length);
            collectChunk(pool, i, end, out);
        }
        return out;
    }

    /**
     * 采集 [from, to) 区间的候选玩家，结果写入 out。
     */
    public void collectChunk(CandidatePool.Candidate[] pool, int from, int to, Map<UUID, Double> out) {
        for (int i = from; i < to && i < pool.length; i++) {
            CandidatePool.Candidate c = pool[i];
            Double v = readOne(c);
            if (v != null) {
                out.put(c.uuid(), v);
            }
        }
    }

    private Double readOne(CandidatePool.Candidate c) {
        if (economy != null && getBalanceOffline != null) {
            try {
                Object bal = getBalanceOffline.invoke(economy, c.player());
                if (bal instanceof Number n) {
                    double d = n.doubleValue();
                    if (Double.isFinite(d) && d >= 0) {
                        return d;
                    }
                }
                return null;
            } catch (Throwable ignored) {
                // 某些实现不支持 OfflinePlayer 重载，落到 PAPI 兜底
            }
        }
        String ph = cfg.getFallbackPlaceholder();
        if (papiAvailable && papiSetPlaceholders != null && !ph.isBlank()) {
            try {
                Object raw = papiSetPlaceholders.invoke(null, c.player(), "%" + ph + "%");
                return NumberUtil.parseNonNegative(raw == null ? null : raw.toString());
            } catch (Throwable t) {
                return null;
            }
        }
        return null;
    }
}
