package com.dobeshadow.mineleaderboards;

import com.dobeshadow.mineleaderboards.board.LeaderboardService;
import com.dobeshadow.mineleaderboards.command.LeaderboardCommand;
import com.dobeshadow.mineleaderboards.config.PluginConfig;
import com.dobeshadow.mineleaderboards.head.HeadResolver;
import com.dobeshadow.mineleaderboards.pool.CandidatePool;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * MineLeaderboards 主类。
 * <p>
 * 定时采集财富 / 在线时长 / 决斗积分 / 签到数据，推送到 MineMC 排行榜端点。
 */
public final class MineLeaderboardsPlugin extends JavaPlugin {

    private PluginConfig config;
    private CandidatePool candidatePool;
    private HeadResolver headResolver;
    private LeaderboardService leaderboardService;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        this.config = PluginConfig.load(getConfig());

        if (config.isVersionOutdated()) {
            getLogger().warning("========================================");
            getLogger().warning("检测到旧版配置文件！");
            getLogger().warning("当前配置版本: " + config.getFileVersion()
                    + "，插件期望版本: " + PluginConfig.CONFIG_VERSION);
            getLogger().warning("请备份并删除 plugins/MineLeaderboards/config.yml 后重启，");
            getLogger().warning("或手动对照新版补全缺失项。");
            getLogger().warning("========================================");
        }

        this.candidatePool = new CandidatePool(this);
        this.headResolver = new HeadResolver(this, config);
        this.leaderboardService = new LeaderboardService(this, candidatePool, headResolver);

        // 启动时先建一次候选池（主线程）
        candidatePool.rebuild(config);
        leaderboardService.apply(config);

        PluginCommand cmd = getCommand("mineleaderboards");
        if (cmd != null) {
            LeaderboardCommand executor = new LeaderboardCommand(this);
            cmd.setExecutor(executor);
            cmd.setTabCompleter(executor);
        }

        if (config.isSkipTlsVerify()) {
            getLogger().warning("skip-tls-verify 已开启：该链路不校验服务器证书，");
            getLogger().warning("存在中间人风险。证书修复后请改回 false。");
        }

        getLogger().info("MineLeaderboards 已启用。");
    }

    @Override
    public void onDisable() {
        if (leaderboardService != null) {
            leaderboardService.shutdown();
        }
        getLogger().info("MineLeaderboards 已禁用。");
    }

    /**
     * 重载配置并重建任务 / HttpClient。
     */
    public void reloadAll() {
        // 先停掉旧任务，避免旧 service 继续用旧配置跑
        if (leaderboardService != null) {
            leaderboardService.shutdown();
        }
        reloadConfig();
        this.config = PluginConfig.load(getConfig());
        this.headResolver = new HeadResolver(this, config);
        this.leaderboardService = new LeaderboardService(this, candidatePool, headResolver);
        candidatePool.rebuild(config);
        leaderboardService.apply(config);
    }

    public PluginConfig getPluginConfig() {
        return config;
    }

    public LeaderboardService getLeaderboardService() {
        return leaderboardService;
    }
}
