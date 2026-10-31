package com.familybutler.core.agent;

import com.familybutler.core.proactive.TodoService;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 列待办工具：调用者本人未完成的待办（与待办接口同一口径，按负责人隔离）。 */
@Component
public class ListTodosTool implements ButlerTool {

    private final TodoService todoService;

    public ListTodosTool(TodoService todoService) {
        this.todoService = todoService;
    }

    @Override
    public String name() {
        return "list_todos";
    }

    @Override
    public String description() {
        return "列出我未完成的待办（含到期时间）";
    }

    @Override
    public String argsHint() {
        return "{}";
    }

    @Override
    public Object execute(ToolContext ctx, JsonNode args) {
        List<Map<String, Object>> items = todoService.listOpen(ctx.memberId()).stream()
                .map(t -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", t.id());
                    row.put("title", t.title());
                    row.put("dueAt", t.dueAt());
                    row.put("status", t.status());
                    return row;
                })
                .toList();
        return Map.of("count", items.size(), "todos", items);
    }
}
