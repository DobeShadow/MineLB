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
import java.util.logging.Logger;

/**
 * 头像解析器。
 * <p>
 * 三级策略：
 * <ol>
 *   <li>内存缓存（按 UUID）</li>
 *   <li>磁盘缓存 plugins/MineLeaderboards/heads/&lt;uuid&gt;.png</li>
 *   <li>从 mc-heads.net 下载 64x64 头像，落盘后返回</li>
 * </ol>
 * 全部失败返回空字符串，前端会自动回退到自己的占位图。
 * <p>
 * 所有方法都在异步线程调用，不触碰 Bukkit API。
 */
public final class HeadResolver {

    private static final String MC_HEADS = "https://mc-heads.net/avatar/";

    private final JavaPlugin plugin;
    private final Logger logger;
    private final PluginConfig cfg;
    private final File cacheDir;
    private final Map<UUID, String> memory = new ConcurrentHashMap<>();

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

        // 下载
        byte[] png = download(c.name());
        if (png == null) {
            memory.put(uuid, "");
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
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(8000);
            conn.setRequestProperty("User-Agent", "MineLeaderboards/1.0");
            int code = conn.getResponseCode();
            if (code != 200) {
                return null;
            }
            try (InputStream in = conn.getInputStream()) {
                byte[] raw = in.readAllBytes();
                // 统一裁成头像（mc-heads 返回的已经是头像，但保险起见再处理一次）
                return normalize(raw);
            }
        } catch (Exception e) {
            return null;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    /**
     * 若图片是 64x64 皮肤，裁出脸部 (8,8,8,8) 并放大；若已是头像则原样返回。
     */
    private byte[] normalize(byte[] raw) {
        try {
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(raw));
            if (img == null) {
                return raw;
            }
            int w = img.getWidth();
            int h = img.getHeight();
            BufferedImage face;
            if (w == 64 && h == 64) {
                // 皮肤：裁脸
                face = img.getSubimage(8, 8, 8, 8);
            } else if (w == 8 && h == 8) {
                face = img;
            } else {
                // 已经是头像尺寸，直接返回原字节
                return raw;
            }
            int size = cfg.getHeadSize();
            BufferedImage scaled = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
            java.awt.Graphics2D g = scaled.createGraphics();
            g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION,
                    java.awt.RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
            g.drawImage(face, 0, 0, size, size, null);
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
