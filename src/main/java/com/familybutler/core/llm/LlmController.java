package com.familybutler.core.llm;

import com.familybutler.core.account.AccountService;
import com.familybutler.core.account.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
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
 * 模型网关接口（D7）：chat 自检 + 本家庭用量汇总。
 * chat 是给底座验收与后续模块联调用的直通口，业务功能不直接面向它编程，
 * 走 LlmGateway 服务层（保证每次调用都落账）。
 */
@RestController
@RequestMapping("/api/llm")
public class LlmController {

    private final LlmGateway llmGateway;
    private final CurrentUser currentUser;

    public LlmController(LlmGateway llmGateway, CurrentUser currentUser) {
        this.llmGateway = llmGateway;
        this.currentUser = currentUser;
    }

    public record ChatRequest(
            @NotBlank @Size(max = 8000) String prompt,
            @Size(max = 2000) String system,
            @Size(max = 20) String module) {
    }

    @PostMapping("/chat")
    public ResponseEntity<Map<String, Object>> chat(@RequestBody @Valid ChatRequest request) {
        AccountService.MemberView me = currentUser.requireMember();
        String module = request.module() == null || request.module().isBlank() ? "base" : request.module();
        LlmGateway.ChatResult result = llmGateway.chat(me.familyId(), module, request.system(), request.prompt());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("reply", result.content());
        body.put("model", result.model());
        body.put("tokensIn", result.tokensIn());
        body.put("tokensOut", result.tokensOut());
        body.put("cost", result.cost());
        body.put("logId", result.logId());
        return ResponseEntity.ok(body);
    }

    /** 本家庭模型账汇总：按模型列调用数/token/成本 + 失败调用数。 */
    @GetMapping("/usage")
    public ResponseEntity<Map<String, Object>> usage() {
        AccountService.MemberView me = currentUser.requireMember();
        List<Map<String, Object>> byModel = llmGateway.usageSummary(me.familyId()).stream().map(u -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("model", u.model());
            row.put("calls", u.calls());
            row.put("tokensIn", u.tokensIn());
            row.put("tokensOut", u.tokensOut());
            row.put("cost", u.cost());
            return row;
        }).toList();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("byModel", byModel);
        body.put("failedCalls", llmGateway.failedCalls(me.familyId()));
        return ResponseEntity.ok(body);
    }
}
