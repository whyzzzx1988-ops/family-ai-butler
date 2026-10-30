package com.familybutler.module.health;

import com.familybutler.core.account.AccountService;
import com.familybutler.core.account.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
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
 * 健康记录接口（第六周 B2/B4）。只记录与管理，不做任何解读（红线）。
 * memberId 是归属成员（记录关于谁），不传 = 记录者本人；家庭边界只认登录态。
 */
@RestController
@RequestMapping("/api/health/records")
public class HealthController {

    private final HealthService healthService;
    private final CurrentUser currentUser;

    public HealthController(HealthService healthService, CurrentUser currentUser) {
        this.healthService = healthService;
        this.currentUser = currentUser;
    }

    /** detail 给结构化字段：用药 {medicine, dosage, remindTime}；其余字段原样存 detail_json。 */
    public record CreateRequest(
            @Size(max = 2000) String content,
            String kind,
            Long memberId,
            Map<String, Object> detail) {
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> create(@RequestBody @Valid CreateRequest request) {
        AccountService.MemberView me = currentUser.requireMember();
        HealthService.HealthView view = healthService.create(me.familyId(), me.id(), null,
                request.content(), request.kind(), request.memberId(), request.detail(), "text");
        return ResponseEntity.status(HttpStatus.CREATED).body(recordBody(view));
    }

    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> list(
            @RequestParam(required = false) Long memberId,
            @RequestParam(required = false) String kind) {
        AccountService.MemberView me = currentUser.requireMember();
        return ResponseEntity.ok(healthService.list(me.familyId(), memberId, kind).stream()
                .map(this::recordBody)
                .toList());
    }

    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> get(@PathVariable long id) {
        AccountService.MemberView me = currentUser.requireMember();
        return ResponseEntity.ok(recordBody(healthService.get(me.familyId(), id)));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, Object>> delete(@PathVariable long id) {
        AccountService.MemberView me = currentUser.requireMember();
        healthService.delete(me.familyId(), me, id);
        return ResponseEntity.ok(Map.of("deleted", true, "id", id));
    }

    Map<String, Object> recordBody(HealthService.HealthView r) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", r.id());
        body.put("memberId", r.memberId());
        body.put("eventId", r.eventId());
        body.put("kind", r.kind());
        body.put("content", r.content());
        body.put("detail", healthService.parseDetail(r.detailJson()));
        body.put("source", r.source());
        body.put("createdAt", r.createdAt());
        return body;
    }
}
