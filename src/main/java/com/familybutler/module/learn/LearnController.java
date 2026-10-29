package com.familybutler.module.learn;

import com.familybutler.core.account.AccountService;
import com.familybutler.core.account.CurrentUser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 学习记录接口（第三周 L1/L2/L4）：建一条（可从 inbox 事件或直接文本）、列表过滤、详情。
 * 家庭与录入者从登录态反查；归属孩子走 childMemberId（服务层校验同家庭 + 孩子角色）。
 * 失败口径沿用底座：事件/记录不属于本家庭 404、参数不合法 400、
 * 加工失败不报错——记录照常建成，processNote 明示降级原因。
 */
@RestController
@RequestMapping("/api/learn")
public class LearnController {

    private final LearnService learnService;
    private final CurrentUser currentUser;
    private final ObjectMapper mapper = new ObjectMapper();

    public LearnController(LearnService learnService, CurrentUser currentUser) {
        this.learnService = learnService;
        this.currentUser = currentUser;
    }

    public record CreateLearnRequest(Long eventId, String kind, String subject, String content,
                                     Long childMemberId) {
    }

    @PostMapping("/records")
    public ResponseEntity<Map<String, Object>> create(@RequestBody CreateLearnRequest request) {
        AccountService.MemberView me = currentUser.requireMember();
        LearnService.CreateResult result = learnService.create(me.familyId(), me.id(),
                request.eventId(), request.kind(), request.subject(), request.content(),
                request.childMemberId());
        return ResponseEntity.status(HttpStatus.CREATED).body(resultBody(result));
    }

    @GetMapping("/records")
    public ResponseEntity<List<Map<String, Object>>> list(
            @RequestParam(required = false) Long memberId,
            @RequestParam(required = false) String kind,
            @RequestParam(required = false) String subject,
            @RequestParam(defaultValue = "50") int limit) {
        AccountService.MemberView me = currentUser.requireMember();
        return ResponseEntity.ok(learnService.listByFamily(me.familyId(), memberId, kind, subject, limit)
                .stream().map(this::recordBody).toList());
    }

    @GetMapping("/records/{id}")
    public ResponseEntity<Map<String, Object>> detail(@PathVariable long id) {
        AccountService.MemberView me = currentUser.requireMember();
        return ResponseEntity.ok(recordBody(learnService.getByIdForFamily(id, me.familyId())));
    }

    Map<String, Object> resultBody(LearnService.CreateResult result) {
        Map<String, Object> body = recordBody(result.record());
        body.put("briefingMemoryId", result.briefingMemoryId());
        if (result.llmLogId() != null) {
            body.put("llmLogId", result.llmLogId());
        }
        if (result.processNote() != null) {
            body.put("processNote", result.processNote());
        }
        return body;
    }

    Map<String, Object> recordBody(LearnService.LearnView r) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", r.id());
        body.put("familyId", r.familyId());
        body.put("memberId", r.memberId());
        body.put("eventId", r.eventId());
        body.put("kind", r.kind());
        body.put("subject", r.subject());
        body.put("content", r.content());
        body.put("detail", parseDetail(r.detailJson()));
        body.put("source", r.source());
        body.put("createdAt", r.createdAt());
        return body;
    }

    /** detail_json 存的是规范 JSON 字符串，响应里还原成 JSON 对象给前端直接用；万一损坏就原样返回字符串。 */
    private Object parseDetail(String detailJson) {
        if (detailJson == null || detailJson.isBlank()) {
            return null;
        }
        try {
            return mapper.readTree(detailJson);
        } catch (JsonProcessingException e) {
            return detailJson;
        }
    }
}
