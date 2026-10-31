package com.familybutler.core.agent;

import com.familybutler.core.proactive.BriefingService;
import com.familybutler.core.proactive.TodoService;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/** 建待办工具（唯一的写工具）：给调用者新建一条待办，到期时间解析不了时返回 error 让模型纠正，不静默丢弃。 */
@Component
public class CreateTodoTool implements ButlerTool {

    private final TodoService todoService;

    public CreateTodoTool(TodoService todoService) {
        this.todoService = todoService;
    }

    @Override
    public String name() {
        return "create_todo";
    }

    @Override
    public String description() {
        return "给我新建一条待办";
    }

    @Override
    public String argsHint() {
        return "{\"title\":\"待办标题\",\"dueAt\":\"到期时间 ISO 格式如 2026-11-01T10:00:00+08:00，可空\"}";
    }

    @Override
    public Object execute(ToolContext ctx, JsonNode args) {
        JsonNode titleNode = args == null ? null : args.get("title");
        String title = titleNode != null && titleNode.isTextual() ? titleNode.asText().trim() : "";
        if (title.isEmpty()) {
            return Map.of("error", "title 不能为空");
        }
        OffsetDateTime dueAt = null;
        JsonNode dueNode = args.get("dueAt");
        if (dueNode != null && dueNode.isTextual() && !dueNode.asText().isBlank()) {
            String raw = dueNode.asText().trim();
            try {
                dueAt = OffsetDateTime.parse(raw);
            } catch (Exception e1) {
                try {
                    dueAt = LocalDateTime.parse(raw.replace(' ', 'T')).atZone(BriefingService.ZONE).toOffsetDateTime();
                } catch (Exception e2) {
                    return Map.of("error", "dueAt 无法解析（需 ISO 格式）：" + raw);
                }
            }
        }
        TodoService.TodoView created = todoService.create(ctx.familyId(), ctx.memberId(), title, dueAt);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("created", true);
        out.put("id", created.id());
        out.put("title", created.title());
        out.put("dueAt", created.dueAt());
        return out;
    }
}
