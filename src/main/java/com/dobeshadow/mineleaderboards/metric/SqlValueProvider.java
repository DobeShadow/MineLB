package com.dobeshadow.mineleaderboards.metric;

import com.dobeshadow.mineleaderboards.config.PluginConfig;
import com.dobeshadow.mineleaderboards.pool.CandidatePool;
import com.dobeshadow.mineleaderboards.util.NumberUtil;
import org.bukkit.plugin.java.JavaPlugin;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * 从数据库读取数值（signin 备用方案）。
 * <p>
 * 配置示例：
 * <pre>
 * sql:
 *   jdbc-url: "jdbc:sqlite:plugins/DailySign/data.db"
 *   query: "SELECT streak FROM signin WHERE name = ?"
 * </pre>
 * query 里第一个 ? 会被替换成玩家名。
 */
public final class SqlValueProvider implements MetricProvider {

    private final JavaPlugin plugin;
    private final Logger logger;
    private final String key;
    private final PluginConfig.BoardConfig cfg;

    public SqlValueProvider(JavaPlugin plugin, String key, PluginConfig.BoardConfig cfg) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
        this.key = key;
        this.cfg = cfg;
    }

    @Override
    public String key() {
        return key;
    }

    @Override
    public boolean isEnabled() {
        return cfg.isEnabled() && !cfg.getJdbcUrl().isBlank() && !cfg.getQuery().isBlank();
    }

    @Override
    public Map<UUID, Double> collect(CandidatePool.Candidate[] pool) {
        Map<UUID, Double> out = new HashMap<>();
        if (!isEnabled()) {
            return out;
        }
        try (Connection conn = DriverManager.getConnection(cfg.getJdbcUrl());
             PreparedStatement ps = conn.prepareStatement(cfg.getQuery())) {
            for (CandidatePool.Candidate c : pool) {
                ps.setString(1, c.name());
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        Double v = NumberUtil.parseNonNegative(rs.getString(1));
                        if (v != null) {
                            out.put(c.uuid(), v);
                        }
                    }
                }
            }
        } catch (Exception e) {
            logger.warning("SQL 查询失败: " + e.getMessage());
        }
        return out;
    }
}
