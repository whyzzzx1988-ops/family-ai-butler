package com.familybutler.core.account;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 微信订阅消息管道（第五周 A3）：access_token 换取 + subscribe/send 发送。
 *
 * 订阅消息是一次性的：用户在小程序里点一次「订阅」，服务端才能推一条。本服务只负责管道，
 * 模板 ID 在公众平台申请后配到 WECHAT_TEMPLATE_BRIEFING_ID / WECHAT_TEMPLATE_REVIEW_ID；
 * 触发点（晨报生成后、复习到期）后续接在 BriefingScheduler / 复习服务上，v0 先给手动测试口验证管道。
 * 发送失败（未订阅 errcode=43101、openid 无效等）返回 SendResult 说明，不抛异常打断业务。
 */
@Service
public class WechatNotifyService {

    private final WechatProperties properties;
    private final AccountService accountService;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    private volatile String cachedToken;
    private volatile Instant tokenExpiresAt = Instant.EPOCH;

    public WechatNotifyService(WechatProperties properties, AccountService accountService) {
        this.properties = properties;
        this.accountService = accountService;
    }

    public record SendResult(boolean ok, String message) {
    }

    /** 给成员发一条订阅消息；成员未绑定微信 → ok=false 明示，不抛异常。 */
    public SendResult sendToMember(long memberId, String templateId, Map<String, String> data, String page) {
        if (!properties.isConfigured()) {
            return new SendResult(false, "微信未配置（WECHAT_APPID / WECHAT_SECRET 缺失）");
        }
        if (templateId == null || templateId.isBlank()) {
            return new SendResult(false, "订阅消息模板 ID 未配置：先在小程序公众平台申请模板，再配 WECHAT_TEMPLATE_* 环境变量");
        }
        String openid = accountService.wechatOpenidOf(memberId);
        if (openid == null) {
            return new SendResult(false, "该成员尚未绑定微信，无法投递");
        }
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("touser", openid);
            payload.put("template_id", templateId);
            if (page != null && !page.isBlank()) {
                payload.put("page", page);
            }
            Map<String, Object> dataNode = new LinkedHashMap<>();
            data.forEach((k, v) -> dataNode.put(k, Map.of("value", v)));
            payload.put("data", dataNode);
            String url = "https://api.weixin.qq.com/cgi-bin/message/subscribe/send?access_token=" + accessToken();
            HttpResponse<String> resp = http.send(
                    HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(15))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload)))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            JsonNode root = objectMapper.readTree(resp.body());
            int errcode = root.path("errcode").asInt(-1);
            if (errcode == 0) {
                return new SendResult(true, "已发送");
            }
            return new SendResult(false, "微信返回 errcode=" + errcode + "：" + root.path("errmsg").asText("")
                    + "（43101 多为用户未订阅或订阅次数已用完）");
        } catch (ApiException e) {
            return new SendResult(false, e.getMessage());
        } catch (Exception e) {
            return new SendResult(false, "发送异常：" + e.getMessage());
        }
    }

    private String accessToken() throws Exception {
        if (cachedToken != null && Instant.now().isBefore(tokenExpiresAt)) {
            return cachedToken;
        }
        String url = "https://api.weixin.qq.com/cgi-bin/token?grant_type=client_credential&appid="
                + properties.getAppid() + "&secret=" + properties.getSecret();
        HttpResponse<String> resp = http.send(
                HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(15)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        JsonNode root = objectMapper.readTree(resp.body());
        String token = root.path("access_token").asText("");
        if (token.isBlank()) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "微信 access_token 获取失败："
                    + root.path("errmsg").asText("未知错误"));
        }
        cachedToken = token;
        tokenExpiresAt = Instant.now().plusSeconds(Math.max(60, root.path("expires_in").asInt(7200) - 300));
        return token;
    }
}
