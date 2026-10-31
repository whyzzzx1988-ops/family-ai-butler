package com.familybutler.core.agent;

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
 * Agent 编排接口：
 * GET /api/agent/tools —— 已注册工具目录（不调模型，未配 Key 也可查，用于验收注册表）；
 * POST /api/agent/run —— 一条自然语言指令的多步执行（查数据/建待办等），未配 Key 时 503 明示。
 */
@RestController
@RequestMapping("/api/agent")
public class AgentController {

    private final AgentService agentService;
    private final ToolRegistry registry;
    private final CurrentUser currentUser;

    public AgentController(AgentService agentService, ToolRegistry registry, CurrentUser currentUser) {
        this.agentService = agentService;
        this.registry = registry;
        this.currentUser = currentUser;
    }

    public record RunRequest(
            @NotBlank @Size(max = 2000) String instruction,
            Integer maxSteps) {
    }

    @GetMapping("/tools")
    public ResponseEntity<Map<String, Object>> tools() {
        currentUser.requireMember();
        List<Map<String, Object>> tools = registry.all().stream()
                .map(t -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("name", t.name());
                    row.put("description", t.description());
                    row.put("argsHint", t.argsHint());
                    return row;
                })
                .toList();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("count", tools.size());
        body.put("tools", tools);
        return ResponseEntity.ok(body);
    }

    @PostMapping("/run")
    public ResponseEntity<Map<String, Object>> run(@RequestBody @Valid RunRequest request) {
        AccountService.MemberView me = currentUser.requireMember();
        AgentService.RunResult result =
                agentService.run(me.familyId(), me.id(), request.instruction(), request.maxSteps());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("answer", result.answer());
        body.put("toolCalls", result.toolCalls());
        body.put("llmLogIds", result.llmLogIds());
        body.put("steps", result.steps().stream()
                .map(s -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("step", s.step());
                    row.put("tool", s.tool());
                    row.put("args", s.argsJson());
                    row.put("resultPreview", s.resultPreview());
                    row.put("ok", s.ok());
                    return row;
                })
                .toList());
        return ResponseEntity.ok(body);
    }
}
