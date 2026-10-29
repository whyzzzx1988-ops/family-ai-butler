package com.familybutler.core.account;

import cn.dev33.satoken.stp.StpUtil;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 家庭与成员接口（D3）。
 * 除「建家庭」外全部要登录；家庭边界一律从登录态反查，
 * 请求体/路径里不接受 familyId —— 这是 family_id 隔离的第一道闸。
 */
@RestController
@RequestMapping("/api/families")
public class FamilyController {

    private final AccountService accountService;

    public FamilyController(AccountService accountService) {
        this.accountService = accountService;
    }

    public record CreateFamilyRequest(
            @NotBlank @Size(max = 100) String familyName,
            @NotBlank @Size(max = 50) String ownerNickname,
            @NotBlank @Size(max = 100) String identifier,
            @NotBlank @Size(min = 6, max = 64) String password) {
    }

    public record AddMemberRequest(
            @NotBlank @Size(max = 50) String nickname,
            @NotBlank String role,
            @Size(max = 100) String identifier,
            @Size(max = 64) String password) {
    }

    /** 建家庭 = 注册入口：建家庭 + 户主成员 + 密码账号，并直接登录返回 token。 */
    @PostMapping
    public ResponseEntity<Map<String, Object>> createFamily(
            @RequestBody @Valid CreateFamilyRequest request) {
        AccountService.MemberView owner = accountService.createFamily(
                request.familyName(), request.ownerNickname(), request.identifier(), request.password());
        StpUtil.login(owner.id());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("familyId", owner.familyId());
        body.put("memberId", owner.id());
        body.put("tokenName", StpUtil.getTokenName());
        body.put("tokenValue", StpUtil.getTokenValue());
        return ResponseEntity.status(HttpStatus.CREATED).body(body);
    }

    /** 给「我所在的家庭」加成员 —— familyId 从登录态反查，不从请求里取。 */
    @PostMapping("/members")
    public ResponseEntity<Map<String, Object>> addMember(
            @RequestBody @Valid AddMemberRequest request) {
        AccountService.MemberView me = currentMember();
        AccountService.MemberView created = accountService.addMember(
                me.familyId(), request.nickname(), request.role(), request.identifier(), request.password());
        return ResponseEntity.status(HttpStatus.CREATED).body(memberBody(created));
    }

    /** 我所在家庭的全部成员（只查本家庭，天然隔离）。 */
    @GetMapping("/members")
    public ResponseEntity<List<Map<String, Object>>> listMembers() {
        AccountService.MemberView me = currentMember();
        return ResponseEntity.ok(accountService.listMembers(me.familyId()).stream()
                .map(FamilyController::memberBody)
                .toList());
    }

    /** 我的家庭信息 + 我的成员资料。 */
    @GetMapping("/me")
    public ResponseEntity<Map<String, Object>> myFamily() {
        AccountService.MemberView me = currentMember();
        AccountService.FamilyView family = accountService.getFamily(me.familyId());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("family", Map.of("id", family.id(), "name", family.name(), "plan", family.plan()));
        body.put("me", memberBody(me));
        return ResponseEntity.ok(body);
    }

    /** 当前登录成员：先 checkLogin（未登录抛 NotLoginException → 401），再按 loginId 反查 member。 */
    static AccountService.MemberView currentMember(AccountService accountService) {
        StpUtil.checkLogin();
        return accountService.getMember(StpUtil.getLoginIdAsLong());
    }

    private AccountService.MemberView currentMember() {
        return currentMember(accountService);
    }

    static Map<String, Object> memberBody(AccountService.MemberView m) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", m.id());
        body.put("familyId", m.familyId());
        body.put("role", m.role());
        body.put("nickname", m.nickname());
        body.put("avatar", m.avatar());
        return body;
    }
}
