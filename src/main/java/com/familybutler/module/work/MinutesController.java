package com.familybutler.module.work;

import com.familybutler.core.account.AccountService;
import com.familybutler.core.account.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 纪要生成接口（第二周 W1）：传一条 inbox 事件 id，出一份三段式纪要并落表。
 * 家庭与成员从登录态反查，请求里不接受 familyId/memberId。
 * 生成失败的口径：事件不属于本家庭 404、转写为空 400、重复生成 409、
 * 没配 DEEPSEEK_API_KEY 503、模型调用失败 502（LlmGateway 透明上抛，且失败也已落账）。
 */
@RestController
@RequestMapping("/api/work")
public class MinutesController {

    private final MinutesService minutesService;
    private final CurrentUser currentUser;

    public MinutesController(MinutesService minutesService, CurrentUser currentUser) {
        this.minutesService = minutesService;
        this.currentUser = currentUser;
    }

    public record CreateMinutesRequest(@NotNull Long eventId) {
    }

    @PostMapping("/minutes")
    public ResponseEntity<Map<String, Object>> create(@RequestBody @Valid CreateMinutesRequest request) {
        AccountService.MemberView me = currentUser.requireMember();
        MinutesService.GenerateResult result = minutesService.generate(me.familyId(), me.id(), request.eventId());
        MinutesService.MinutesView m = result.minutes();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", m.id());
        body.put("familyId", m.familyId());
        body.put("memberId", m.memberId());
        body.put("eventId", m.eventId());
        body.put("title", m.title());
        body.put("contentMd", m.contentMd());
        body.put("createdAt", m.createdAt());
        body.put("model", result.model());
        body.put("tokensIn", result.tokensIn());
        body.put("tokensOut", result.tokensOut());
        body.put("cost", result.cost());
        body.put("llmLogId", result.llmLogId());
        return ResponseEntity.status(HttpStatus.CREATED).body(body);
    }
}
