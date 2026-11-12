package com.dobeshadow.mineleaderboards.http;

import com.dobeshadow.mineleaderboards.config.PluginConfig;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Duration;

/**
 * 通用 JSON 推送器。
 * <p>
 * 踩过的坑（务必保留）：
 * <ul>
 *   <li>必须强制 HTTP/1.1，否则配合自定义 SSLContext 会卡到超时</li>
 *   <li>证书过期时需 skip-tls-verify（信任所有 + 关闭 hostname 校验）</li>
 *   <li>请求和连接都要设超时</li>
 *   <li>成功码是 204，不是 200</li>
 * </ul>
 */
public final class JsonPusher {

    private static final TrustManager[] TRUST_ALL = new TrustManager[]{
            new X509TrustManager() {
                @Override
                public void checkClientTrusted(X509Certificate[] chain, String authType) {
                }

                @Override
                public void checkServerTrusted(X509Certificate[] chain, String authType) {
                }

                @Override
                public X509Certificate[] getAcceptedIssuers() {
                    return new X509Certificate[0];
                }
            }
    };

    private final PluginConfig cfg;
    private final HttpClient http;

    public JsonPusher(PluginConfig cfg) {
        this.cfg = cfg;
        this.http = buildClient(cfg);
    }

    private static HttpClient buildClient(PluginConfig cfg) {
        HttpClient.Builder b = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(cfg.getTimeoutSeconds()))
                .followRedirects(HttpClient.Redirect.NORMAL);
        if (cfg.isSkipTlsVerify()) {
            try {
                SSLContext sc = SSLContext.getInstance("TLS");
                sc.init(null, TRUST_ALL, new SecureRandom());
                SSLParameters sp = new SSLParameters();
                sp.setEndpointIdentificationAlgorithm("");
                b.sslContext(sc).sslParameters(sp);
            } catch (Exception ignored) {
                // 构建失败则退回默认 SSL
            }
        }
        return b.build();
    }

    /**
     * 推送 JSON 到排行榜端点。
     *
     * @return HTTP 状态码
     * @throws Exception 网络异常或超时
     */
    public int push(String json) throws Exception {
        String endpoint = cfg.getEndpoint();
        String url = endpoint
                + (endpoint.contains("?") ? "&" : "?")
                + "token=" + URLEncoder.encode(cfg.getToken(), StandardCharsets.UTF_8);

        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(cfg.getTimeoutSeconds()))
                .header("Content-Type", "application/json; charset=utf-8")
                .header("X-LB-Token", cfg.getToken())
                .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> resp = http.send(req,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        return resp.statusCode();
    }
}
