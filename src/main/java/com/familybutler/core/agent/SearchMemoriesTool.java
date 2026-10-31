package com.familybutler.core.agent;

import com.familybutler.core.memory.MemoryService;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 查记忆工具：按关键词/范围检索调用者可见的记忆（可见性口径同记忆接口，私密记忆不越权）。 */
@Component
public class SearchMemoriesTool implements ButlerTool {

    private static final int SCAN_LIMIT = 50;
    private static final int RESULT_LIMIT = 10;

    private final MemoryService memoryService;

    public SearchMemoriesTool(MemoryService memoryService) {
        this.memoryService = memoryService;
    }

    @Override
    public String name() {
        return "search_memories";
    }

    @Override
    public String description() {
        return "检索我能看到的家庭记忆（按关键词/范围）";
    }

    @Override
    public String argsHint() {
        return "{\"keyword\":\"关键词，可空\",\"scope\":\"work/learn/health/common，可空\"}";
    }

    @Override
    public Object execute(ToolContext ctx, JsonNode args) {
        String keyword = textOrNull(args, "keyword");
        String scope = textOrNull(args, "scope");
        if (scope != null && !MemoryService.SCOPES.contains(scope)) {
            return Map.of("error", "scope 只能是 work / learn / health / common");
        }
        List<MemoryService.MemoryView> visible =
                memoryService.listVisible(ctx.familyId(), ctx.memberId(), scope, null, SCAN_LIMIT);
        String kw = keyword == null ? null : keyword.toLowerCase();
        List<Map<String, Object>> items = visible.stream()
                .filter(m -> kw == null || m.content() != null && m.content().toLowerCase().contains(kw))
                .limit(RESULT_LIMIT)
                .map(m -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", m.id());
                    row.put("scope", m.scope());
                    row.put("kind", m.kind());
                    row.put("content", m.content());
                    row.put("createdAt", m.createdAt());
                    return row;
                })
                .toList();
        return Map.of("count", items.size(), "memories", items);
    }

    private static String textOrNull(JsonNode args, String field) {
        JsonNode node = args == null ? null : args.get(field);
        if (node == null || !node.isTextual() || node.asText().isBlank()) {
            return null;
        }
        return node.asText().trim();
    }
}
