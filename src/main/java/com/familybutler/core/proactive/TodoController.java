package com.familybutler.core.proactive;

import com.familybutler.core.account.AccountService;
import com.familybutler.core.account.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 待办接口（D6 配套）：建一条、列我的未完成、标记完成。全部要登录。
 */
@RestController
@RequestMapping("/api/todos")
public class TodoController {

    private final TodoService todoService;
    private final CurrentUser currentUser;

    public TodoController(TodoService todoService, CurrentUser currentUser) {
        this.todoService = todoService;
        this.currentUser = currentUser;
    }

    public record CreateTodoRequest(
            @NotBlank @Size(max = 255) String title,
            OffsetDateTime dueAt) {
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> create(@RequestBody @Valid CreateTodoRequest request) {
        AccountService.MemberView me = currentUser.requireMember();
        TodoService.TodoView created = todoService.create(me.familyId(), me.id(), request.title(), request.dueAt());
        return ResponseEntity.status(HttpStatus.CREATED).body(todoBody(created));
    }

    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> listOpen() {
        AccountService.MemberView me = currentUser.requireMember();
        return ResponseEntity.ok(todoService.listOpen(me.id()).stream().map(TodoController::todoBody).toList());
    }

    @PostMapping("/{id}/done")
    public ResponseEntity<Map<String, Object>> done(@PathVariable long id) {
        AccountService.MemberView me = currentUser.requireMember();
        todoService.markDone(id, me.id());
        return ResponseEntity.ok(Map.of("status", "done", "id", id));
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
