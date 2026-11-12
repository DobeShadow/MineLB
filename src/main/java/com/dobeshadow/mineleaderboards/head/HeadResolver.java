package com.dobeshadow.mineleaderboards.head;

import com.dobeshadow.mineleaderboards.config.PluginConfig;
import com.dobeshadow.mineleaderboards.pool.CandidatePool;
import org.bukkit.plugin.java.JavaPlugin;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

/**
 * 头像解析器。
 * <p>
 * 三级策略：
 * <ol>
 *   <li>内存缓存（按 UUID）</li>
 *   <li>磁盘缓存 plugins/MineLeaderboards/heads/&lt;uuid&gt;.png</li>
 *   <li>从 mc-heads.net 下载头像，落盘后返回</li>
 * </ol>
 * 全部失败返回空字符串，前端会自动回退到自己的占位图。
 * <p>
 * 注意：mc-heads.net/avatar/ 返回的是「渲染好的头像」，不是 64×64 原始皮肤，
 * 因此绝不能按皮肤去裁 (8,8,8,8) 的脸部区域，否则头像会变成脸中央一小块糊图。
 * 这里只做尺寸归一。
 */
public final class HeadResolver {

    private static final String MC_HEADS = "https://mc-heads.net/avatar/";

    /** 连续下载失败达到该次数后，短时间内不再尝试外网，避免一轮推送卡死。 */
    private static final int FAILURE_THRESHOLD = 3;
    /** 外网不通时冷却 10 分钟，避免每轮推送都白等 3 次超时。 */
    private static final long CIRCUIT_COOLDOWN_MS = 600_000L;

    private final JavaPlugin plugin;
    private final Logger logger;
    private final PluginConfig cfg;
    private final File cacheDir;
    private final Map<UUID, String> memory = new ConcurrentHashMap<>();
    private final AtomicInteger consecutiveFailures = new AtomicInteger(0);
    private volatile long circuitOpenUntil = 0L;

    public HeadResolver(JavaPlugin plugin, PluginConfig cfg) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
        this.cfg = cfg;
        this.cacheDir = new File(plugin.getDataFolder(), cfg.getHeadCacheDir());
        if (!cacheDir.isDirectory() && !cacheDir.mkdirs()) {
            logger.warning("无法创建头像缓存目录: " + cacheDir.getAbsolutePath());
        }
    }

    /**
     * 解析单个玩家的头像 data URI。返回空字符串表示无头像。
     */
    public String resolve(CandidatePool.Candidate c) {
        String source = cfg.getHeadSource();
        if ("none".equals(source)) {
            return "";
        }
        UUID uuid = c.uuid();
        String cached = memory.get(uuid);
        if (cached != null) {
            return cached;
        }

        // 磁盘缓存
        File f = new File(cacheDir, uuid + ".png");
        if (f.isFile()) {
            try {
                byte[] bytes = Files.readAllBytes(f.toPath());
                String data = toDataUri(bytes);
                if (data != null) {
                    memory.put(uuid, data);
                    return data;
                }
            } catch (Exception ignored) {
                // 缓存损坏，重新下载
            }
        }

        if ("online-only".equals(source) && !c.player().isOnline()) {
            memory.put(uuid, "");
            return "";
        }

        // 外网不通时快速失败，不要为每个玩家都等一次超时
        if (System.currentTimeMillis() < circuitOpenUntil) {
            return "";
        }

        byte[] png = download(c.name());
        if (png == null) {
            return "";
        }
        try {
            Files.write(f.toPath(), png);
        } catch (Exception ignored) {
            // 落盘失败不影响本次返回
        }
        String data = toDataUri(png);
        String result = data == null ? "" : data;
        memory.put(uuid, result);
        return result;
    }

    private byte[] download(String name) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(MC_HEADS + name + "/" + cfg.getHeadSize());
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(3000);
            conn.setReadTimeout(5000);
            conn.setRequestProperty("User-Agent", "MineLeaderboards/1.0");
            int code = conn.getResponseCode();
            if (code != 200) {
                onDownloadFailure();
                return null;
            }
            try (InputStream in = conn.getInputStream()) {
                byte[] raw = in.readAllBytes();
                consecutiveFailures.set(0);
                return normalize(raw);
            }
        } catch (Exception e) {
            onDownloadFailure();
            return null;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private void onDownloadFailure() {
        // 只在刚触发熔断时提示一次，之后静默跳过
        if (consecutiveFailures.incrementAndGet() == FAILURE_THRESHOLD) {
            circuitOpenUntil = System.currentTimeMillis() + CIRCUIT_COOLDOWN_MS;
            logger.warning("头像服务连续失败 " + FAILURE_THRESHOLD
                    + " 次，10 分钟内跳过外网下载（前端会用自己的头像兜底）；"
                    + "若服务器长期无法访问外网，建议把 heads.source 设为 none");
        }
    }

    /**
     * 只做尺寸归一：mc-heads.net 返回的已经是头像，不做裁脸。
     * 尺寸一致时直接返回原始字节，避免无谓的重编码。
     */
    private byte[] normalize(byte[] raw) {
        try {
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(raw));
            if (img == null) {
                return raw;
            }
            int size = cfg.getHeadSize();
            if (img.getWidth() == size && img.getHeight() == size) {
                return raw;
            }
            BufferedImage scaled = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
            java.awt.Graphics2D g = scaled.createGraphics();
            g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION,
                    java.awt.RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
            g.drawImage(img, 0, 0, size, size, null);
            g.dispose();
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            ImageIO.write(scaled, "png", bos);
            return bos.toByteArray();
        } catch (Exception e) {
            return raw;
        }
    }

    private String toDataUri(byte[] png) {
        if (png == null || png.length == 0) {
            return null;
        }
        String b64 = Base64.getEncoder().encodeToString(png);
        String uri = "data:image/png;base64," + b64;
        // 端点限制 8192 字符
        if (uri.length() > 8192) {
            return null;
        }
        return uri;
    }

    public void clearCache() {
        memory.clear();
    }
}
