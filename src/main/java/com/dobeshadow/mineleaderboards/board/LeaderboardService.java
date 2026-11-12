package com.dobeshadow.mineleaderboards.board;

import com.dobeshadow.mineleaderboards.config.PluginConfig;
import com.dobeshadow.mineleaderboards.head.HeadResolver;
import com.dobeshadow.mineleaderboards.http.JsonPusher;
import com.dobeshadow.mineleaderboards.metric.MetricProvider;
import com.dobeshadow.mineleaderboards.metric.PapiNumberProvider;
import com.dobeshadow.mineleaderboards.metric.PlaytimeProvider;
import com.dobeshadow.mineleaderboards.metric.SqlValueProvider;
import com.dobeshadow.mineleaderboards.metric.VaultProvider;
import com.dobeshadow.mineleaderboards.metric.YamlValueProvider;
import com.dobeshadow.mineleaderboards.pool.CandidatePool;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

/**
 * 排行榜采集与推送的调度核心。
 * <p>
 * 流程：
 * <ol>
 *   <li>主线程：刷新候选池（按需）+ 各 provider 采集</li>
 *   <li>异步：解析头像、排序、序列化、HTTP 推送</li>
 * </ol>
 * 用 AtomicBoolean 防重入，并带超时兜底，避免一次卡死导致后续全部跳过。
 */
public final class LeaderboardService {

    /** 防重入标志的最长持有时间，超过则强制释放。 */
    private static final long STUCK_TIMEOUT_MS = 5 * 60 * 1000L;

    private final JavaPlugin plugin;
    private final Logger logger;
    private final CandidatePool pool;
    private final HeadResolver headResolver;

    private final AtomicBoolean pushing = new AtomicBoolean(false);
    private volatile long pushStartedAt = 0L;

    private PluginConfig cfg;
    private JsonPusher pusher;
    private BukkitTask task;

    private final List<MetricProvider> providers = new ArrayList<>();

    public LeaderboardService(JavaPlugin plugin, CandidatePool pool, HeadResolver headResolver) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
        this.pool = pool;
        this.headResolver = headResolver;
    }

    /**
     * 应用配置并（重新）启动定时任务。必须在主线程调用。
     */
    public void apply(PluginConfig cfg) {
        this.cfg = cfg;
        this.pusher = new JsonPusher(cfg);
        rebuildProviders(cfg);
        restartTask();
    }

    private void rebuildProviders(PluginConfig cfg) {
        providers.clear();
        providers.add(new VaultProvider(plugin, cfg.getWealth()));
        providers.add(new PlaytimeProvider(plugin, cfg.getPlaytime()));
        providers.add(new PapiNumberProvider(plugin, "duel", cfg.getDuel()));

        // signin 按 provider 类型选择实现
        PluginConfig.BoardConfig sc = cfg.getSignin();
        switch (sc.getProvider()) {
            case "yaml" -> providers.add(new YamlValueProvider(plugin, "signin", sc));
            case "sql" -> providers.add(new SqlValueProvider(plugin, "signin", sc));
            default -> providers.add(new PapiNumberProvider(plugin, "signin", sc));
        }
    }

    private void restartTask() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        if (!cfg.isEnabled()) {
            logger.info("排行榜采集已禁用");
            return;
        }
        long interval = cfg.getIntervalSeconds() * 20L;
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L * 5, interval);
        logger.info("排行榜采集任务已注册，间隔 " + cfg.getIntervalSeconds() + " 秒");
    }

    /**
     * 定时触发入口。
     */
    private void tick() {
        if (!cfg.isEnabled()) {
            return;
        }
        // 防重入 + 超时兜底
        if (pushing.get()) {
            if (System.currentTimeMillis() - pushStartedAt > STUCK_TIMEOUT_MS) {
                logger.warning("上一次排行榜推送超时未释放，强制重置标志");
                pushing.set(false);
            } else {
                logger.warning("上一次排行榜采集/推送尚未完成，跳过本轮");
                return;
            }
        }
        if (!pushing.compareAndSet(false, true)) {
            return;
        }
        pushStartedAt = System.currentTimeMillis();
        try {
            collectAndPush();
        } catch (Throwable t) {
            pushing.set(false);
            logger.warning("排行榜采集异常: " + describe(t));
        }
    }

    /**
     * 主线程采集 → 异步推送。
     */
    private void collectAndPush() {
        // 候选池为空或过期则重建（每 10 分钟）
        if (pool.get().isEmpty() || System.currentTimeMillis() - pool.getLastRebuild() > 10 * 60 * 1000L) {
            pool.rebuild(cfg);
        }
        CandidatePool.Candidate[] candidates = pool.get().toArray(new CandidatePool.Candidate[0]);

        // 主线程采集（Vault 分批，其余一次性）
        Map<String, Map<UUID, Double>> raw = new HashMap<>();
        for (MetricProvider p : providers) {
            if (!p.isEnabled()) {
                raw.put(p.key(), Map.of());
                continue;
            }
            try {
                raw.put(p.key(), p.collect(candidates));
            } catch (Throwable t) {
                logger.warning("榜单 " + p.key() + " 采集失败: " + describe(t));
                raw.put(p.key(), Map.of());
            }
        }

        // 异步：排序、头像、序列化、推送
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                List<BoardSnapshot> snapshots = buildSnapshots(raw, candidates);
                String json = buildJson(snapshots);
                int code = pusher.push(json);
                if (code == 204) {
                    logger.info("排行榜推送成功 (HTTP 204, " + snapshots.size() + " 个榜, "
                            + json.getBytes(java.nio.charset.StandardCharsets.UTF_8).length + " 字节)");
                } else {
                    logger.warning("排行榜推送失败: HTTP " + code + " @ " + cfg.getEndpoint());
                }
            } catch (Exception e) {
                logger.warning("排行榜推送异常: " + describe(e));
            } finally {
                pushing.set(false);
            }
        });
    }

    /**
     * 把原始 uuid->value 映射转成排序后的榜单快照。
     */
    private List<BoardSnapshot> buildSnapshots(Map<String, Map<UUID, Double>> raw,
                                               CandidatePool.Candidate[] candidates) {
        Map<UUID, CandidatePool.Candidate> byUuid = new HashMap<>();
        for (CandidatePool.Candidate c : candidates) {
            byUuid.put(c.uuid(), c);
        }
        List<BoardSnapshot> out = new ArrayList<>();
        for (String key : List.of("wealth", "playtime", "duel", "signin")) {
            Map<UUID, Double> data = raw.getOrDefault(key, Map.of());
            List<Map.Entry<UUID, Double>> sorted = new ArrayList<>(data.entrySet());
            sorted.sort(Map.Entry.<UUID, Double>comparingByValue(Comparator.reverseOrder()));
            int limit = Math.min(cfg.getTopN(), sorted.size());
            List<BoardSnapshot.Entry> entries = new ArrayList<>(limit);
            for (int i = 0; i < limit; i++) {
                Map.Entry<UUID, Double> e = sorted.get(i);
                CandidatePool.Candidate c = byUuid.get(e.getKey());
                String name = c != null ? c.name() : e.getKey().toString();
                entries.add(new BoardSnapshot.Entry(name, e.getKey().toString(), e.getValue()));
            }
            out.add(new BoardSnapshot(key, entries));
        }
        return out;
    }

    /**
     * 组装最终 JSON。头像在这里解析（异步线程）。
     * <p>
     * 手写拼接以避免编译期依赖 Gson，字符串统一走 {@link #escape(String)}。
     */
    private String buildJson(List<BoardSnapshot> snapshots) {
        StringBuilder sb = new StringBuilder(4096);
        sb.append("{\"time\":").append(System.currentTimeMillis() / 1000L);
        sb.append(",\"boards\":{");
        boolean firstBoard = true;
        for (BoardSnapshot snap : snapshots) {
            if (!firstBoard) {
                sb.append(',');
            }
            firstBoard = false;
            sb.append('"').append(escape(snap.key())).append("\":{\"entries\":[");
            boolean firstEntry = true;
            for (BoardSnapshot.Entry e : snap.entries()) {
                if (!firstEntry) {
                    sb.append(',');
                }
                firstEntry = false;
                String head = "";
                CandidatePool.Candidate c = findCandidate(e.uuid());
                if (c != null) {
                    try {
                        head = headResolver.resolve(c);
                    } catch (Throwable ignored) {
                        head = "";
                    }
                }
                sb.append("{\"name\":\"").append(escape(e.name())).append('"');
                sb.append(",\"uuid\":\"").append(escape(e.uuid())).append('"');
                sb.append(",\"value\":").append(formatNumber(e.value()));
                sb.append(",\"head\":\"").append(escape(head)).append("\"}");
            }
            sb.append("]}}");
        }
        sb.append("}}");
        return sb.toString();
    }

    /**
     * 数值序列化：整数不带小数点，小数保留原值。
     */
    private static String formatNumber(double v) {
        if (v == Math.rint(v) && !Double.isInfinite(v) && Math.abs(v) < 1e15) {
            return Long.toString((long) v);
        }
        return Double.toString(v);
    }

    /**
     * JSON 字符串转义。
     */
    private static String escape(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            switch (ch) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    if (ch < 0x20) {
                        sb.append(String.format("\\u%04x", (int) ch));
                    } else {
                        sb.append(ch);
                    }
                }
            }
        }
        return sb.toString();
    }

    private CandidatePool.Candidate findCandidate(String uuidStr) {
        try {
            UUID uuid = UUID.fromString(uuidStr);
            for (CandidatePool.Candidate c : pool.get()) {
                if (c.uuid().equals(uuid)) {
                    return c;
                }
            }
        } catch (IllegalArgumentException ignored) {
        }
        return null;
    }

    /**
     * 立即推送一次（命令用）。
     */
    public void pushNow() {
        if (!cfg.isEnabled()) {
            logger.warning("排行榜采集已禁用，无法推送");
            return;
        }
        if (!pushing.compareAndSet(false, true)) {
            logger.warning("已有推送在进行中，请稍后再试");
            return;
        }
        pushStartedAt = System.currentTimeMillis();
        try {
            collectAndPush();
        } catch (Throwable t) {
            pushing.set(false);
            logger.warning("立即推送异常: " + describe(t));
        }
    }

    /**
     * 打印某个榜的 top N（命令用）。
     */
    public void testBoard(String key) {
        if (!List.of("wealth", "playtime", "duel", "signin").contains(key)) {
            logger.warning("未知榜单: " + key + "（可选 wealth/playtime/duel/signin）");
            return;
        }
        if (pool.get().isEmpty()) {
            pool.rebuild(cfg);
        }
        CandidatePool.Candidate[] candidates = pool.get().toArray(new CandidatePool.Candidate[0]);
        MetricProvider target = null;
        for (MetricProvider p : providers) {
            if (p.key().equals(key)) {
                target = p;
                break;
            }
        }
        if (target == null || !target.isEnabled()) {
            logger.warning("榜单 " + key + " 未启用或依赖不可用");
            return;
        }
        Map<UUID, Double> data;
        try {
            data = target.collect(candidates);
        } catch (Throwable t) {
            logger.warning("榜单 " + key + " 采集失败: " + describe(t));
            return;
        }
        List<Map.Entry<UUID, Double>> sorted = new ArrayList<>(data.entrySet());
        sorted.sort(Map.Entry.<UUID, Double>comparingByValue(Comparator.reverseOrder()));
        logger.info("===== 榜单 " + key + " top " + Math.min(cfg.getTopN(), sorted.size()) + " =====");
        int i = 1;
        for (Map.Entry<UUID, Double> e : sorted) {
            if (i > cfg.getTopN()) {
                break;
            }
            CandidatePool.Candidate c = null;
            for (CandidatePool.Candidate cc : candidates) {
                if (cc.uuid().equals(e.getKey())) {
                    c = cc;
                    break;
                }
            }
            String name = c != null ? c.name() : e.getKey().toString();
            logger.info("  " + i + ". " + name + " = " + e.getValue());
            i++;
        }
        if (sorted.isEmpty()) {
            logger.info("  （无数据）");
        }
    }

    public void shutdown() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    private static String describe(Throwable t) {
        String m = t.getMessage();
        return t.getClass().getSimpleName() + (m == null ? "" : ": " + m);
    }
}
