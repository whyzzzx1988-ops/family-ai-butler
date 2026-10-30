package com.familybutler.module.work;

import com.familybutler.core.account.AccountService;
import com.familybutler.core.account.CurrentUser;
import com.familybutler.core.proactive.TodoService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 纪要接口（第二周 W1 生成 + W2 待办确认 + W4 列表/详情）：传一条 inbox 事件 id 出纪要并落表，
 * 生成同轮抽出的待办候选可勾选确认成正式待办，列表给摘要、详情给全文。
 * 家庭与成员从登录态反查，请求里不接受 familyId/memberId。
 * 失败口径：事件/纪要不属于本家庭 404、转写为空 400、重复生成 409、
 * 没配 DEEPSEEK_API_KEY 503、模型调用失败 502（LlmGateway 透明上抛，且失败也已落账）；
 * 确认时下标越界/重复 400、已确认过的下标 409。
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

    /** template 可选（第七周 N5）：standard 默认 / weekly 周会 / client 客户拜访，乱填 400。 */
    public record CreateMinutesRequest(@NotNull Long eventId, String template) {
    }

    public record ConfirmItemRequest(@NotNull Integer index, OffsetDateTime dueAt) {
    }

    public record ConfirmTodosRequest(@NotEmpty List<@Valid ConfirmItemRequest> items) {
    }

    @PostMapping("/minutes")
    public ResponseEntity<Map<String, Object>> create(@RequestBody @Valid CreateMinutesRequest request) {
        AccountService.MemberView me = currentUser.requireMember();
        MinutesService.GenerateResult result = minutesService.generate(me.familyId(), me.id(),
                request.eventId(), request.template());
        Map<String, Object> body = minutesBody(result.minutes());
        body.put("model", result.model());
        body.put("tokensIn", result.tokensIn());
        body.put("tokensOut", result.tokensOut());
        body.put("cost", result.cost());
        body.put("llmLogId", result.llmLogId());
        if (result.todoExtractionLogId() != null) {
            body.put("todoExtractionLogId", result.todoExtractionLogId());
        }
        if (result.todoExtractionNote() != null) {
            body.put("todoExtractionNote", result.todoExtractionNote());
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(body);
    }

    /** 本家庭纪要列表（W4）：只给摘要（标题 + 候选计数），正文走详情，别把长文本塞进列表。 */
    @GetMapping("/minutes")
    public ResponseEntity<List<Map<String, Object>>> list(@RequestParam(defaultValue = "20") int limit) {
        AccountService.MemberView me = currentUser.requireMember();
        return ResponseEntity.ok(minutesService.listByFamily(me.familyId(), limit).stream()
                .map(MinutesController::summaryBody)
                .toList());
    }

    /** 纪要详情（W4）：全文 + 候选 + 已确认下标；跨家庭/不存在统一 404。 */
    @GetMapping("/minutes/{id}")
    public ResponseEntity<Map<String, Object>> detail(@PathVariable long id) {
        AccountService.MemberView me = currentUser.requireMember();
        return ResponseEntity.ok(minutesBody(minutesService.getByIdForFamily(id, me.familyId())));
    }

    /** 勾选候选转正式待办：负责人默认纪要创建者，dueAt 按候选的时间线索选填。 */
    @PostMapping("/minutes/{id}/todos/confirm")
    public ResponseEntity<Map<String, Object>> confirmTodos(@PathVariable long id,
                                                              @RequestBody @Valid ConfirmTodosRequest request) {
        AccountService.MemberView me = currentUser.requireMember();
        List<MinutesService.ConfirmItem> items = request.items().stream()
                .map(i -> new MinutesService.ConfirmItem(i.index(), i.dueAt()))
                .toList();
        MinutesService.ConfirmResult result = minutesService.confirmTodos(me.familyId(), id, items);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("minutesId", result.minutes().id());
        body.put("todoConfirmedIndexes", result.minutes().todoConfirmedIndexes());
        body.put("createdTodos", result.createdTodos().stream().map(MinutesController::todoBody).toList());
        return ResponseEntity.status(HttpStatus.CREATED).body(body);
    }

    static Map<String, Object> minutesBody(MinutesService.MinutesView m) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", m.id());
        body.put("familyId", m.familyId());
        body.put("memberId", m.memberId());
        body.put("eventId", m.eventId());
        body.put("title", m.title());
        body.put("contentMd", m.contentMd());
        body.put("template", m.template());
        body.put("todoCandidates", candidateBodies(m.todoCandidates()));
        body.put("todoConfirmedIndexes", m.todoConfirmedIndexes());
        body.put("createdAt", m.createdAt());
        return body;
    }

    static Map<String, Object> summaryBody(MinutesService.MinutesView m) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", m.id());
        body.put("familyId", m.familyId());
        body.put("memberId", m.memberId());
        body.put("eventId", m.eventId());
        body.put("title", m.title());
        body.put("template", m.template());
        body.put("todoCandidateCount", m.todoCandidates().size());
        body.put("todoConfirmedCount", m.todoConfirmedIndexes().size());
        body.put("createdAt", m.createdAt());
        return body;
    }

    static List<Map<String, Object>> candidateBodies(List<MinutesService.TodoCandidate> candidates) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (int i = 0; i < candidates.size(); i++) {
            MinutesService.TodoCandidate c = candidates.get(i);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("index", i);
            row.put("title", c.title());
            row.put("ownerHint", c.ownerHint());
            row.put("dueHint", c.dueHint());
            list.add(row);
        }
        return list;
    }

    static Map<String, Object> todoBody(TodoService.TodoView t) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", t.id());
        body.put("familyId", t.familyId());
        body.put("ownerMemberId", t.ownerMemberId());
        body.put("title", t.title());
        body.put("dueAt", t.dueAt());
        body.put("status", t.status());
        body.put("sourceEventId", t.sourceEventId());
        body.put("createdAt", t.createdAt());
        return body;
    }
}
