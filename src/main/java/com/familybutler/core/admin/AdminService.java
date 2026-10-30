package com.familybutler.core.admin;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 家庭数据总览聚合（第七周 N6）：给 Web 管理页一次性供数，避免前端拼十几个接口。
 * 全部只读统计，家庭边界 = 登录态反查的 family_id（由 Controller 传入），口径与各模块接口一致：
 * 待复习计数同简报口径（stage 未毕业且当天结束前到期），LLM 账只算 status=ok、失败单列。
 */
@Service
public class AdminService {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private final JdbcTemplate jdbc;

    public AdminService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Map<String, Object> overview(long familyId) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("family", family(familyId));
        out.put("members", members(familyId));
        out.put("counts", counts(familyId));
        out.put("llm", llm(familyId));
        out.put("recentEvents", recentEvents(familyId));
        return out;
    }

    private Map<String, Object> family(long familyId) {
        return jdbc.queryForObject(
                "select id, name, plan from family where id = ?",
                (rs, i) -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", rs.getLong("id"));
                    row.put("name", rs.getString("name"));
                    row.put("plan", rs.getString("plan"));
                    return row;
                }, familyId);
    }

    private List<Map<String, Object>> members(long familyId) {
        return jdbc.query(
                "select m.id as id, m.nickname as nickname, m.role as role, "
                        + "exists(select 1 from account a where a.member_id = m.id and a.login_type = 'password') as has_pw, "
                        + "exists(select 1 from account a where a.member_id = m.id and a.login_type = 'wechat') as has_wx "
                        + "from member m where m.family_id = ? order by m.id",
                (rs, i) -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", rs.getLong("id"));
                    row.put("nickname", rs.getString("nickname"));
                    row.put("role", rs.getString("role"));
                    row.put("hasAccount", rs.getBoolean("has_pw"));
                    row.put("hasWechat", rs.getBoolean("has_wx"));
                    return row;
                }, familyId);
    }

    private Map<String, Object> counts(long familyId) {
        Map<String, Object> counts = new LinkedHashMap<>();
        counts.put("memories", count("select count(*) from memory_item where family_id = ?", familyId));
        counts.put("inboxTotal", count("select count(*) from inbox_event where family_id = ?", familyId));
        counts.put("inboxByType", groupCounts(
                "select parsed_type as k, count(*) as c from inbox_event where family_id = ? group by parsed_type",
                familyId));
        Map<String, Object> todos = new LinkedHashMap<>();
        todos.put("open", count("select count(*) from todo_task where family_id = ? and status = 'open'", familyId));
        todos.put("done", count("select count(*) from todo_task where family_id = ? and status = 'done'", familyId));
        todos.put("overdueOpen", count(
                "select count(*) from todo_task where family_id = ? and status = 'open' "
                        + "and due_at is not null and due_at < current_timestamp", familyId));
        counts.put("todos", todos);
        counts.put("minutes", count("select count(*) from meeting_note where family_id = ?", familyId));
        Map<String, Object> learn = new LinkedHashMap<>();
        learn.put("total", count("select count(*) from learn_record where family_id = ?", familyId));
        learn.put("byKind", groupCounts(
                "select kind as k, count(*) as c from learn_record where family_id = ? group by kind", familyId));
        OffsetDateTime endOfToday = LocalDate.now(ZONE).plusDays(1).atStartOfDay(ZONE).toOffsetDateTime();
        learn.put("dueToday", count(
                "select count(*) from learn_record where family_id = ? and review_stage < 3 "
                        + "and (next_review_at is null or next_review_at < ?)", familyId, endOfToday));
        learn.put("graduated", count(
                "select count(*) from learn_record where family_id = ? and review_stage >= 3", familyId));
        counts.put("learn", learn);
        Map<String, Object> health = new LinkedHashMap<>();
        health.put("total", count("select count(*) from health_record where family_id = ?", familyId));
        health.put("byKind", groupCounts(
                "select kind as k, count(*) as c from health_record where family_id = ? group by kind", familyId));
        counts.put("health", health);
        counts.put("briefings", count("select count(*) from briefing where family_id = ?", familyId));
        return counts;
    }

    private Map<String, Object> llm(long familyId) {
        List<Map<String, Object>> byModule = jdbc.query(
                "select module, count(*) as calls, coalesce(sum(tokens_in),0) as tin, "
                        + "coalesce(sum(tokens_out),0) as tout, coalesce(sum(cost),0) as cost "
                        + "from llm_call_log where family_id = ? and status = 'ok' group by module order by module",
                (rs, i) -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("module", rs.getString("module"));
                    row.put("calls", rs.getLong("calls"));
                    row.put("tokensIn", rs.getLong("tin"));
                    row.put("tokensOut", rs.getLong("tout"));
                    row.put("cost", rs.getBigDecimal("cost"));
                    return row;
                }, familyId);
        Map<String, Object> llm = new LinkedHashMap<>();
        llm.put("byModule", byModule);
        llm.put("failedCalls", count(
                "select count(*) from llm_call_log where family_id = ? and status = 'error'", familyId));
        return llm;
    }

    private List<Map<String, Object>> recentEvents(long familyId) {
        return jdbc.query(
                "select id, parsed_type, text, created_at from inbox_event "
                        + "where family_id = ? order by id desc limit 5",
                (rs, i) -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", rs.getLong("id"));
                    row.put("parsedType", rs.getString("parsed_type"));
                    row.put("text", rs.getString("text"));
                    row.put("createdAt", rs.getString("created_at"));
                    return row;
                }, familyId);
    }

    private long count(String sql, Object... args) {
        Long value = jdbc.queryForObject(sql, Long.class, args);
        return value == null ? 0 : value;
    }

    private Map<String, Long> groupCounts(String sql, Object... args) {
        Map<String, Long> map = new LinkedHashMap<>();
        jdbc.query(sql, (rs, i) -> Map.entry(rs.getString("k"), rs.getLong("c")), args)
                .forEach(e -> map.put(e.getKey(), e.getValue()));
        return map;
    }
}
