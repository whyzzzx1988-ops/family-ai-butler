package com.familybutler.core.agent;

import com.familybutler.core.account.ApiException;
import com.familybutler.core.proactive.BriefingService;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** 读简报工具（只读）：取已生成的 daily/weekly 简报，没有就如实返回 found=false，不代为生成。 */
@Component
public class GetBriefingTool implements ButlerTool {

    private final BriefingService briefingService;

    public GetBriefingTool(BriefingService briefingService) {
        this.briefingService = briefingService;
    }

    @Override
    public String name() {
        return "get_briefing";
    }

    @Override
    public String description() {
        return "读取指定日期的全家简报（只读，不存在时返回 found=false）";
    }

    @Override
    public String argsHint() {
        return "{\"date\":\"yyyy-MM-dd，可空=今天\",\"type\":\"daily 或 weekly，可空=daily\"}";
    }

    @Override
    public Object execute(ToolContext ctx, JsonNode args) {
        String type = textOr(args, "type", "daily");
        if (!Set.of("daily", "weekly").contains(type)) {
            return Map.of("error", "type 只能是 daily / weekly");
        }
        LocalDate date;
        String dateText = textOr(args, "date", null);
        if (dateText == null) {
            date = LocalDate.now(BriefingService.ZONE);
        } else {
            try {
                date = LocalDate.parse(dateText);
            } catch (Exception e) {
                return Map.of("error", "date 格式应为 yyyy-MM-dd：" + dateText);
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("date", date.toString());
        out.put("type", type);
        try {
            BriefingService.BriefingView view = briefingService.get(ctx.familyId(), date, type);
            out.put("found", true);
            out.put("contentMd", view.contentMd());
        } catch (ApiException e) {
            if (e.status() != HttpStatus.NOT_FOUND) {
                throw e;
            }
            // 未生成不是错误：如实告诉模型 found=false，由它转告用户
            out.put("found", false);
            out.put("message", "该日期暂无简报（可用 POST /api/briefings/generate 生成）");
        }
        return out;
    }

    private static String textOr(JsonNode args, String field, String fallback) {
        JsonNode node = args == null ? null : args.get(field);
        if (node == null || !node.isTextual() || node.asText().isBlank()) {
            return fallback;
        }
        return node.asText().trim();
    }
}
