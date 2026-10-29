package com.familybutler.core.memory;

import com.familybutler.core.account.AccountService;
import com.familybutler.core.account.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
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

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 长期记忆接口（D5）：存一条、查得到、删得掉、删后查不到。
 * 全部要登录；归属成员与家庭从登录态反查，请求里不接受 memberId/familyId。
 */
@RestController
@RequestMapping("/api/memories")
public class MemoryController {

    private final MemoryService memoryService;
    private final CurrentUser currentUser;

    public MemoryController(MemoryService memoryService, CurrentUser currentUser) {
        this.memoryService = memoryService;
        this.currentUser = currentUser;
    }

    public record CreateMemoryRequest(
            @NotBlank String scope,
            @NotBlank String kind,
            @NotBlank @Size(max = 4000) String content,
            String source,
            String visibility,
            OffsetDateTime expiresAt) {
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> create(@RequestBody @Valid CreateMemoryRequest request) {
        AccountService.MemberView me = currentUser.requireMember();
        MemoryService.MemoryView created = memoryService.create(me.familyId(), me.id(),
                request.scope(), request.kind(), request.content(),
                request.source(), request.visibility(), request.expiresAt());
        return ResponseEntity.status(HttpStatus.CREATED).body(memoryBody(created));
    }

    /** 我可见的记忆列表：我的全部 + 家人共享给全家的；可按 scope/kind 过滤。 */
    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> list(
            @RequestParam(required = false) String scope,
            @RequestParam(required = false) String kind,
            @RequestParam(defaultValue = "50") int limit) {
        AccountService.MemberView me = currentUser.requireMember();
        return ResponseEntity.ok(memoryService.listVisible(me.familyId(), me.id(), scope, kind, limit)
                .stream().map(MemoryController::memoryBody).toList());
    }

    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> get(@PathVariable long id) {
        AccountService.MemberView me = currentUser.requireMember();
        return ResponseEntity.ok(memoryBody(memoryService.getVisible(id, me.familyId(), me.id())));
    }

    /** 删除我自己的记忆：硬删除，删后查不到（包括家人视角）。 */
    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, Object>> delete(@PathVariable long id) {
        AccountService.MemberView me = currentUser.requireMember();
        memoryService.deleteOwn(id, me.id());
        return ResponseEntity.ok(Map.of("status", "deleted", "id", id));
    }

    static Map<String, Object> memoryBody(MemoryService.MemoryView m) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", m.id());
        body.put("familyId", m.familyId());
        body.put("memberId", m.memberId());
        body.put("scope", m.scope());
        body.put("kind", m.kind());
        body.put("content", m.content());
        body.put("source", m.source());
        body.put("visibility", m.visibility());
        body.put("expiresAt", m.expiresAt());
        body.put("createdAt", m.createdAt());
        return body;
    }
}
