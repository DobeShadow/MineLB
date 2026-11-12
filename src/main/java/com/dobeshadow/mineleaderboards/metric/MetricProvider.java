package com.dobeshadow.mineleaderboards.metric;

import com.dobeshadow.mineleaderboards.pool.CandidatePool;

import java.util.Map;
import java.util.UUID;

/**
 * 单个榜单的指标采集器。
 * <p>
 * 实现类在 {@link #collect} 中返回 uuid -> 数值 的映射，
 * 缺失的玩家直接不出现在 map 里（不要塞 0，否则会污染榜单）。
 */
public interface MetricProvider {

    /** 榜单 key：wealth / playtime / duel / signin。 */
    String key();

    /** 是否启用（配置里 enabled 且依赖可用）。 */
    boolean isEnabled();

    /**
     * 采集数据。默认在主线程调用，实现里不要做长时间阻塞 IO。
     * 纯文件读取的实现可以自己切异步，但要注意 Bukkit API 不能跨线程。
     */
    Map<UUID, Double> collect(CandidatePool.Candidate[] pool);
}
