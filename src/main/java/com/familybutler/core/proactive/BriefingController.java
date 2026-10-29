package com.familybutler.core.proactive;

import com.familybutler.core.account.AccountService;
import com.familybutler.core.account.CurrentUser;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 全家简报接口（D6）：一键生成 + 按日期读取。
 * date 可选（默认今天，Asia/Shanghai）：学习/睡眠段按相对日期取数，
 * 传明天的日期即可预览「以今天为昨日」的效果，方便验收。
 */
@RestController
@RequestMapping("/api/briefings")
public class BriefingController {

    private final BriefingService briefingService;
    private final CurrentUser currentUser;

    public BriefingController(BriefingService briefingService, CurrentUser currentUser) {
        this.briefingService = briefingService;
        this.currentUser = currentUser;
    }

    @PostMapping("/generate")
    public ResponseEntity<Map<String, Object>> generate(
            @RequestParam(required = false) LocalDate date,
            @RequestParam(defaultValue = "morning") String type) {
        AccountService.MemberView me = currentUser.requireMember();
        LocalDate target = date == null ? LocalDate.now(BriefingService.ZONE) : date;
        BriefingService.BriefingView view = briefingService.generate(me.familyId(), me.id(), target, type);
        return ResponseEntity.ok(briefingBody(view));
    }

    @GetMapping
    public ResponseEntity<Map<String, Object>> get(
            @RequestParam(required = false) LocalDate date,
            @RequestParam(defaultValue = "morning") String type) {
        AccountService.MemberView me = currentUser.requireMember();
        LocalDate target = date == null ? LocalDate.now(BriefingService.ZONE) : date;
        return ResponseEntity.ok(briefingBody(briefingService.get(me.familyId(), target, type)));
    }

    static Map<String, Object> briefingBody(BriefingService.BriefingView b) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", b.id());
        body.put("familyId", b.familyId());
        body.put("date", b.date());
        body.put("type", b.type());
        body.put("contentMd", b.contentMd());
        body.put("createdAt", b.createdAt());
        return body;
    }
}
