package com.familybutler.core.account;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * 微信小程序登录（第五周 A1）：wx.login 的 code → code2session 换 openid → 查绑定发 token。
 *
 * 绑定策略 v0（防串号优先）：openid 已绑成员 → 直接登录；未绑 → 必须同请求带 identifier+password
 * 过一次密码校验才绑（等于「证明你就是这个家庭的成员」），绑完即登录。之后再打开小程序免密直登。
 * 实现说明：底座设计原定 JustAuth，这里用 JDK HttpClient 手写 code2session 等价实现（与 LlmGateway /
 * ASR 客户端同风格、零新依赖）；接更多第三方登录时再引 JustAuth 不迟。Secret 只走环境变量，未配 503。
 */
@Service
public class WechatAuthService {

    private final WechatProperties properties;
    private final AccountService accountService;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    public WechatAuthService(WechatProperties properties, AccountService accountService) {
        this.properties = properties;
        this.accountService = accountService;
    }

    public record LoginOutcome(AccountService.MemberView member, boolean justBound) {
    }

    public LoginOutcome loginOrBind(String code, String identifier, String password) {
        if (!properties.isConfigured()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
                    "微信登录未配置：请为后端设置环境变量 WECHAT_APPID / WECHAT_SECRET");
        }
        String openid = code2session(code);
        AccountService.MemberView bound = accountService.findWechatMember(openid);
        if (bound != null) {
            return new LoginOutcome(bound, false);
        }
        if (identifier == null || identifier.isBlank() || password == null || password.isBlank()) {
            throw ApiException.unauthorized("该微信尚未绑定家庭成员：首次使用请带 identifier 与 password 完成绑定");
        }
        AccountService.LoginResult verified = accountService.verifyPasswordLogin(identifier, password);
        accountService.bindWechat(verified.memberId(), openid);
        return new LoginOutcome(accountService.getMember(verified.memberId()), true);
    }

    private String code2session(String code) {
        String url = "https://api.weixin.qq.com/sns/jscode2session?appid=" + enc(properties.getAppid())
                + "&secret=" + enc(properties.getSecret())
                + "&js_code=" + enc(code) + "&grant_type=authorization_code";
        try {
            HttpResponse<String> resp = http.send(
                    HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(15)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            JsonNode root = objectMapper.readTree(resp.body());
            int errcode = root.path("errcode").asInt(0);
            if (errcode != 0) {
                throw new ApiException(HttpStatus.BAD_GATEWAY,
                        "微信 code2session 失败（errcode=" + errcode + "）：" + root.path("errmsg").asText("未知错误"));
            }
            String openid = root.path("openid").asText("");
            if (openid.isBlank()) {
                throw new ApiException(HttpStatus.BAD_GATEWAY, "微信 code2session 未返回 openid");
            }
            return openid;
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "微信 code2session 调用异常：" + e.getMessage());
        }
    }

    private static String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
