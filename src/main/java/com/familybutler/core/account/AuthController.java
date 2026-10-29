package com.familybutler.core.account;

import cn.dev33.satoken.stp.StpUtil;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 登录接口（Sa-Token）。骨架期先通登录态管道：
 * D3 落地时在此处先查 account 表、用 BCrypt 校验密码，再调用 doLogin。
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    public record LoginRequest(Long memberId, String password) {
    }

    @PostMapping("/login")
    public ResponseEntity<Map<String, Object>> login(@RequestBody LoginRequest request) {
        // TODO(D3): 按 memberId 查 account，BCrypt 校验 password，失败返回 401
        StpUtil.login(request.memberId());
        return ResponseEntity.ok(Map.of(
                "tokenName", StpUtil.getTokenName(),
                "tokenValue", StpUtil.getTokenValue(),
                "loginId", StpUtil.getLoginId()
        ));
    }

    @PostMapping("/logout")
    public ResponseEntity<Map<String, Object>> logout() {
        StpUtil.logout();
        return ResponseEntity.ok(Map.of("status", "logged_out"));
    }

    @GetMapping("/me")
    public ResponseEntity<Map<String, Object>> me() {
        StpUtil.checkLogin();
        return ResponseEntity.ok(Map.of("loginId", StpUtil.getLoginId()));
    }
}
