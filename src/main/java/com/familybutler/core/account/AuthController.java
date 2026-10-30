package com.familybutler.core.account;

import cn.dev33.satoken.stp.StpUtil;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 登录接口（Sa-Token）。D3 起为真校验：
 * 按 identifier 查 account → BCrypt 比对密码 → 成功才 StpUtil.login(memberId)。
 * 注册不在这里，走 POST /api/families（建家庭即注册户主）。
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AccountService accountService;
    private final WechatAuthService wechatAuthService;

    public AuthController(AccountService accountService, WechatAuthService wechatAuthService) {
        this.accountService = accountService;
        this.wechatAuthService = wechatAuthService;
    }

    public record LoginRequest(
            @NotBlank String identifier,
            @NotBlank String password) {
    }

    /** 小程序登录：code 必填；未绑定的微信首次须同带 identifier/password 完成绑定（见 WechatAuthService）。 */
    public record WechatLoginRequest(
            @NotBlank String code,
            String identifier,
            String password) {
    }

    @PostMapping("/login")
    public ResponseEntity<Map<String, Object>> login(@RequestBody @Valid LoginRequest request) {
        AccountService.LoginResult result = accountService.verifyPasswordLogin(request.identifier(), request.password());
        StpUtil.login(result.memberId());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("tokenName", StpUtil.getTokenName());
        body.put("tokenValue", StpUtil.getTokenValue());
        body.put("memberId", result.memberId());
        body.put("familyId", result.familyId());
        return ResponseEntity.ok(body);
    }

    @PostMapping("/wechat")
    public ResponseEntity<Map<String, Object>> wechat(@RequestBody @Valid WechatLoginRequest request) {
        WechatAuthService.LoginOutcome outcome =
                wechatAuthService.loginOrBind(request.code(), request.identifier(), request.password());
        StpUtil.login(outcome.member().id());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("tokenName", StpUtil.getTokenName());
        body.put("tokenValue", StpUtil.getTokenValue());
        body.put("memberId", outcome.member().id());
        body.put("familyId", outcome.member().familyId());
        body.put("bound", outcome.justBound());
        return ResponseEntity.ok(body);
    }

    @PostMapping("/logout")
    public ResponseEntity<Map<String, Object>> logout() {
        StpUtil.logout();
        return ResponseEntity.ok(Map.of("status", "logged_out"));
    }

    /** 当前登录成员的资料（id / familyId / role / nickname）。 */
    @GetMapping("/me")
    public ResponseEntity<Map<String, Object>> me() {
        AccountService.MemberView me = FamilyController.currentMember(accountService);
        return ResponseEntity.ok(FamilyController.memberBody(me));
    }
}
