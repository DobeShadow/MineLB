package com.dobeshadow.mineleaderboards.metric;

import com.dobeshadow.mineleaderboards.config.PluginConfig;
import com.dobeshadow.mineleaderboards.pool.CandidatePool;
import com.dobeshadow.mineleaderboards.util.NumberUtil;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * 从 YAML 数据文件读取数值（signin 备用方案）。
 * <p>
 * 配置示例：
 * <pre>
 * yaml:
 *   path: "plugins/DailySign/players.yml"
 *   key: "players.&lt;name&gt;.streak"
 * </pre>
 * key 中支持 &lt;uuid&gt; 和 &lt;name&gt; 占位。
 */
public final class YamlValueProvider implements MetricProvider {

    private final JavaPlugin plugin;
    private final Logger logger;
    private final String key;
    private final PluginConfig.BoardConfig cfg;

    public YamlValueProvider(JavaPlugin plugin, String key, PluginConfig.BoardConfig cfg) {
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
        return cfg.isEnabled() && !cfg.getYamlPath().isBlank() && !cfg.getYamlKey().isBlank();
    }

    @Override
    public Map<UUID, Double> collect(CandidatePool.Candidate[] pool) {
        Map<UUID, Double> out = new HashMap<>();
        if (!isEnabled()) {
            return out;
        }
        File f = null;
        File parent = plugin.getDataFolder().getParentFile();
        if (parent != null) {
            f = new File(parent, cfg.getYamlPath().replaceFirst("^plugins/", ""));
        }
        if (f == null || !f.isFile()) {
            // 也尝试按服务器根目录解析
            f = new File(cfg.getYamlPath());
        }
        if (!f.isFile()) {
            logger.warning("YAML 数据文件不存在: " + cfg.getYamlPath());
            return out;
        }
        YamlConfiguration yaml;
        try {
            yaml = YamlConfiguration.loadConfiguration(f);
        } catch (Exception e) {
            logger.warning("读取 YAML 失败: " + e.getMessage());
            return out;
        }
        for (CandidatePool.Candidate c : pool) {
            String path = cfg.getYamlKey()
                    .replace("<uuid>", c.uuid().toString())
                    .replace("<name>", c.name());
            if (!yaml.contains(path)) {
                continue;
            }
            Double v = NumberUtil.parseNonNegative(String.valueOf(yaml.get(path)));
            if (v != null) {
                out.put(c.uuid(), v);
            }
        }
        return out;
    }
}
