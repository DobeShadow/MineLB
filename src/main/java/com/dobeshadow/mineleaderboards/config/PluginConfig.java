package com.dobeshadow.mineleaderboards.config;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 插件配置快照。
 * 每次 reload 都重新从 FileConfiguration 构建一份不可变快照，
 * 运行中的任务只读快照，避免读到半更新的状态。
 */
public final class PluginConfig {

    /** 配置结构版本，config.yml 里结构有变更时必须同步 +1。 */
    public static final int CONFIG_VERSION = 1;

    private final int fileVersion;
    private final boolean enabled;
    private final String endpoint;
    private final String token;
    private final int intervalSeconds;
    private final int timeoutSeconds;
    private final int topN;
    private final int maxCandidates;
    private final int activeWithinDays;
    private final List<String> candidateSources;
    private final List<String> extraPlayers;
    private final boolean skipTlsVerify;

    private final BoardConfig wealth;
    private final BoardConfig playtime;
    private final BoardConfig duel;
    private final BoardConfig signin;

    private final String headSource;
    private final String headCacheDir;
    private final int headSize;

    private PluginConfig(FileConfiguration c) {
        this.fileVersion = c.getInt("config-version", 0);
        this.enabled = c.getBoolean("leaderboard.enabled", true);
        this.endpoint = c.getString("leaderboard.endpoint", "");
        this.token = c.getString("leaderboard.token", "");
        this.intervalSeconds = Math.max(10, c.getInt("leaderboard.interval-seconds", 60));
        this.timeoutSeconds = Math.max(5, c.getInt("leaderboard.timeout-seconds", 20));
        this.topN = Math.max(1, Math.min(20, c.getInt("leaderboard.top-n", 10)));
        this.maxCandidates = Math.max(1, c.getInt("leaderboard.max-candidates", 400));
        this.activeWithinDays = Math.max(1, c.getInt("leaderboard.active-within-days", 180));
        this.skipTlsVerify = c.getBoolean("leaderboard.skip-tls-verify", true);

        List<String> src = c.getStringList("leaderboard.candidate-sources");
        this.candidateSources = src.isEmpty() ? List.of("playerdata", "online", "extra") : new ArrayList<>(src);
        this.extraPlayers = new ArrayList<>(c.getStringList("leaderboard.extra-players"));

        this.wealth = new BoardConfig(c.getConfigurationSection("leaderboard.boards.wealth"), "vault");
        this.playtime = new BoardConfig(c.getConfigurationSection("leaderboard.boards.playtime"), "statistic");
        this.duel = new BoardConfig(c.getConfigurationSection("leaderboard.boards.duel"), "papi");
        this.signin = new BoardConfig(c.getConfigurationSection("leaderboard.boards.signin"), "papi");

        this.headSource = c.getString("leaderboard.heads.source", "auto").toLowerCase(Locale.ROOT);
        this.headCacheDir = c.getString("leaderboard.heads.cache-dir", "heads");
        this.headSize = Math.max(8, Math.min(128, c.getInt("leaderboard.heads.size", 64)));
    }

    public static PluginConfig load(FileConfiguration c) {
        return new PluginConfig(c);
    }

    public int getFileVersion() {
        return fileVersion;
    }

    public boolean isVersionOutdated() {
        return fileVersion != CONFIG_VERSION;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public String getEndpoint() {
        return endpoint;
    }

    public String getToken() {
        return token;
    }

    public int getIntervalSeconds() {
        return intervalSeconds;
    }

    public int getTimeoutSeconds() {
        return timeoutSeconds;
    }

    public int getTopN() {
        return topN;
    }

    public int getMaxCandidates() {
        return maxCandidates;
    }

    public int getActiveWithinDays() {
        return activeWithinDays;
    }

    public List<String> getCandidateSources() {
        return candidateSources;
    }

    public List<String> getExtraPlayers() {
        return extraPlayers;
    }

    public boolean isSkipTlsVerify() {
        return skipTlsVerify;
    }

    public BoardConfig getWealth() {
        return wealth;
    }

    public BoardConfig getPlaytime() {
        return playtime;
    }

    public BoardConfig getDuel() {
        return duel;
    }

    public BoardConfig getSignin() {
        return signin;
    }

    public String getHeadSource() {
        return headSource;
    }

    public String getHeadCacheDir() {
        return headCacheDir;
    }

    public int getHeadSize() {
        return headSize;
    }

    /**
     * 单个榜单的配置。
     */
    public static final class BoardConfig {
        private final boolean enabled;
        private final String provider;
        private final String placeholder;
        private final String fallbackPlaceholder;
        private final boolean allowOffline;
        private final boolean fallbackStatsFile;
        private final String multiWorld;

        // yaml provider
        private final String yamlPath;
        private final String yamlKey;

        // sql provider
        private final String jdbcUrl;
        private final String query;

        BoardConfig(ConfigurationSection s, String defaultProvider) {
            if (s == null) {
                this.enabled = false;
                this.provider = defaultProvider;
                this.placeholder = "";
                this.fallbackPlaceholder = "";
                this.allowOffline = false;
                this.fallbackStatsFile = true;
                this.multiWorld = "sum";
                this.yamlPath = "";
                this.yamlKey = "";
                this.jdbcUrl = "";
                this.query = "";
                return;
            }
            this.enabled = s.getBoolean("enabled", true);
            this.provider = s.getString("provider", defaultProvider).toLowerCase(Locale.ROOT);
            this.placeholder = s.getString("placeholder", "");
            this.fallbackPlaceholder = s.getString("fallback-placeholder", "");
            this.allowOffline = s.getBoolean("allow-offline", false);
            this.fallbackStatsFile = s.getBoolean("fallback-stats-file", true);
            this.multiWorld = s.getString("multi-world", "sum").toLowerCase(Locale.ROOT);
            this.yamlPath = s.getString("yaml.path", "");
            this.yamlKey = s.getString("yaml.key", "");
            this.jdbcUrl = s.getString("sql.jdbc-url", "");
            this.query = s.getString("sql.query", "");
        }

        public boolean isEnabled() {
            return enabled;
        }

        public String getProvider() {
            return provider;
        }

        public String getPlaceholder() {
            return placeholder;
        }

        public String getFallbackPlaceholder() {
            return fallbackPlaceholder;
        }

        public boolean isAllowOffline() {
            return allowOffline;
        }

        public boolean isFallbackStatsFile() {
            return fallbackStatsFile;
        }

        public String getMultiWorld() {
            return multiWorld;
        }

        public String getYamlPath() {
            return yamlPath;
        }

        public String getYamlKey() {
            return yamlKey;
        }

        public String getJdbcUrl() {
            return jdbcUrl;
        }

        public String getQuery() {
            return query;
        }
    }
}
