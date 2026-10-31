package com.familybutler.core.agent;

import com.familybutler.core.account.AccountService;
import com.familybutler.module.learn.LearnService;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 查复习工具：孩子今天待复习的学习条目；不传 memberId 时汇总全家所有孩子。 */
@Component
public class ReviewTodayTool implements ButlerTool {

    private static final int CONTENT_PREVIEW = 80;

    private final LearnService learnService;
    private final AccountService accountService;

    public ReviewTodayTool(LearnService learnService, AccountService accountService) {
        this.learnService = learnService;
        this.accountService = accountService;
    }

    @Override
    public String name() {
        return "review_today";
    }

    @Override
    public String description() {
        return "查孩子今天待复习的学习条目（单词/错题等）";
    }

    @Override
    public String argsHint() {
        return "{\"memberId\":\"孩子成员 id，可空=全家所有孩子\"}";
    }

    @Override
    public Object execute(ToolContext ctx, JsonNode args) {
        List<AccountService.MemberView> targets = new ArrayList<>();
        JsonNode idNode = args == null ? null : args.get("memberId");
        if (idNode != null && idNode.isNumber()) {
            long wanted = idNode.asLong();
            accountService.listMembers(ctx.familyId()).stream()
                    .filter(m -> m.id() == wanted)
                    .findFirst()
                    .ifPresent(targets::add);
            if (targets.isEmpty()) {
                return Map.of("error", "memberId 不属于本家庭：" + wanted);
            }
        } else {
            accountService.listMembers(ctx.familyId()).stream()
                    .filter(m -> "child".equals(m.role()))
                    .forEach(targets::add);
        }
        List<Map<String, Object>> children = new ArrayList<>();
        int total = 0;
        for (AccountService.MemberView child : targets) {
            List<LearnService.LearnView> due = learnService.reviewToday(ctx.familyId(), child.id());
            total += due.size();
            List<Map<String, Object>> items = due.stream()
                    .map(r -> {
                        Map<String, Object> row = new LinkedHashMap<>();
                        row.put("recordId", r.id());
                        row.put("kind", r.kind());
                        row.put("subject", r.subject());
                        row.put("content", preview(r.content()));
                        return row;
                    })
                    .toList();
            Map<String, Object> block = new LinkedHashMap<>();
            block.put("memberId", child.id());
            block.put("nickname", child.nickname());
            block.put("dueCount", due.size());
            block.put("items", items);
            children.add(block);
        }
        return Map.of("totalDue", total, "children", children);
    }

    private static String preview(String content) {
        if (content == null) {
            return null;
        }
        return content.length() > CONTENT_PREVIEW ? content.substring(0, CONTENT_PREVIEW) + "…" : content;
    }
}
