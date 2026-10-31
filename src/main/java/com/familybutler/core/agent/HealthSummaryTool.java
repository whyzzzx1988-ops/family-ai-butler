package com.familybutler.core.agent;

import com.familybutler.module.health.HealthService;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 健康概览工具：调用者本人的最近睡眠记录与用药计划（只汇总事实，不做任何解读，守健康红线）。 */
@Component
public class HealthSummaryTool implements ButlerTool {

    private final HealthService healthService;

    public HealthSummaryTool(HealthService healthService) {
        this.healthService = healthService;
    }

    @Override
    public String name() {
        return "health_summary";
    }

    @Override
    public String description() {
        return "看我最近的睡眠记录和用药计划（只报记录事实）";
    }

    @Override
    public String argsHint() {
        return "{}";
    }

    @Override
    public Object execute(ToolContext ctx, JsonNode args) {
        List<Map<String, Object>> sleeps = healthService.list(ctx.familyId(), ctx.memberId(), "sleep").stream()
                .limit(7)
                .map(h -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("content", h.content());
                    row.put("detail", healthService.parseDetail(h.detailJson()));
                    row.put("createdAt", h.createdAt());
                    return row;
                })
                .toList();
        List<Map<String, Object>> medications = healthService.list(ctx.familyId(), ctx.memberId(), "medication").stream()
                .map(h -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("content", h.content());
                    row.put("detail", healthService.parseDetail(h.detailJson()));
                    return row;
                })
                .toList();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("recentSleeps", sleeps);
        out.put("medications", medications);
        return out;
    }
}
