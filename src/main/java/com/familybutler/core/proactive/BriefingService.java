package com.familybutler.core.proactive;

import com.familybutler.core.account.ApiException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;

import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 全家简报 v0（D6）：规则拼装三段式，不调 LLM —— 数据有什么写什么，
 * 缺数据明说「暂无记录」，绝不编造。生成结果落 briefing 表（同家庭/日期/类型覆盖更新）。
 *
 * 三段的数据口径（都以简报日期 D 为锚，时区 Asia/Shanghai）：
 *   今日工作：请求者本人未完成、到期时间在 D 当天结束前的待办（含逾期）
 *   孩子昨日学习：D-1 全天、scope=learn 且「全家可见 或 归属成员是孩子」的记忆
 *   昨晚睡眠：D-1 12:00 ~ D 12:00、scope=health 且「本人 或 全家可见」的记忆（覆盖睡前记与晨起补记两种录入习惯）
 * 后续接 LLM 只换拼装后的润色环节，取数口径与落表结构不动。
 */
@Service
public class BriefingService {

    public static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter DAY_TIME = DateTimeFormatter.ofPattern("MM-dd HH:mm");

    private final JdbcTemplate jdbc;

    public BriefingService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record BriefingView(long id, long familyId, String date, String type,
                               String contentMd, String createdAt) {
    }

    private record TimedText(String nickname, String content) {
    }

    public BriefingView generate(long familyId, long requesterMemberId, LocalDate date, String type) {
        Map<Long, String> nicknames = jdbc.query(
                "select id, nickname from member where family_id = ?",
                (rs, i) -> Map.entry(rs.getLong("id"), rs.getString("nickname")),
                familyId).stream().collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

        String content = "# 全家简报 · " + date + "\n\n"
                + "## 今日工作\n" + workSection(requesterMemberId, date) + "\n\n"
                + "## 孩子昨日学习\n" + learnSection(familyId, date) + "\n\n"
                + "## 昨晚睡眠\n" + sleepSection(familyId, requesterMemberId, date, nicknames) + "\n";
        return upsert(familyId, date, type, content);
    }

    private String workSection(long requesterMemberId, LocalDate date) {
        OffsetDateTime endOfDay = date.plusDays(1).atStartOfDay(ZONE).toOffsetDateTime();
        OffsetDateTime startOfDay = date.atStartOfDay(ZONE).toOffsetDateTime();
        List<Map.Entry<OffsetDateTime, String>> todos = jdbc.query(
                "select title, due_at from todo_task where owner_member_id = ? and status = 'open' "
                        + "and due_at is not null and due_at < ? order by due_at",
                (rs, i) -> Map.entry(rs.getObject("due_at", OffsetDateTime.class), rs.getString("title")),
                requesterMemberId, endOfDay);
        if (todos.isEmpty()) {
            return "今日暂无到期待办。";
        }
        return todos.stream().map(e -> {
            OffsetDateTime due = e.getKey();
            String when = due.isBefore(startOfDay) ? "（逾期 " + DAY_TIME.format(due) + "）" : TIME.format(due);
            return "- " + when + " " + e.getValue();
        }).collect(Collectors.joining("\n"));
    }

    private String learnSection(long familyId, LocalDate date) {
        OffsetDateTime from = date.minusDays(1).atStartOfDay(ZONE).toOffsetDateTime();
        OffsetDateTime to = date.atStartOfDay(ZONE).toOffsetDateTime();
        List<TimedText> rows = jdbc.query(
                "select mem.nickname as nickname, m.content as content from memory_item m "
                        + "join member mem on mem.id = m.member_id "
                        + "where m.family_id = ? and m.scope = 'learn' "
                        + "and m.created_at >= ? and m.created_at < ? "
                        + "and (m.visibility = 'family' or mem.role = 'child') "
                        + "and (m.expires_at is null or m.expires_at > current_timestamp) order by m.id",
                (rs, i) -> new TimedText(rs.getString("nickname"), rs.getString("content")),
                familyId, from, to);
        if (rows.isEmpty()) {
            return "昨日暂无学习记录。";
        }
        return rows.stream().map(r -> "- " + r.nickname() + "：" + r.content()).collect(Collectors.joining("\n"));
    }

    private String sleepSection(long familyId, long requesterMemberId, LocalDate date, Map<Long, String> nicknames) {
        OffsetDateTime from = date.minusDays(1).atTime(12, 0).atZone(ZONE).toOffsetDateTime();
        OffsetDateTime to = date.atTime(12, 0).atZone(ZONE).toOffsetDateTime();
        List<TimedText> rows = jdbc.query(
                "select member_id, content from memory_item "
                        + "where family_id = ? and scope = 'health' "
                        + "and created_at >= ? and created_at < ? "
                        + "and (member_id = ? or visibility = 'family') "
                        + "and (expires_at is null or expires_at > current_timestamp) order by id",
                (rs, i) -> new TimedText(nicknames.getOrDefault(rs.getLong("member_id"), "家人"),
                        rs.getString("content")),
                familyId, from, to, requesterMemberId);
        if (rows.isEmpty()) {
            return "暂无昨晚睡眠记录。";
        }
        return rows.stream().map(r -> "- " + r.nickname() + "：" + r.content()).collect(Collectors.joining("\n"));
    }

    private BriefingView upsert(long familyId, LocalDate date, String type, String contentMd) {
        List<Long> existing = jdbc.query(
                "select id from briefing where family_id = ? and date = ? and type = ?",
                (rs, i) -> rs.getLong(1), familyId, Date.valueOf(date), type);
        long id;
        if (existing.isEmpty()) {
            KeyHolder keyHolder = new GeneratedKeyHolder();
            jdbc.update(connection -> {
                PreparedStatement ps = connection.prepareStatement(
                        "insert into briefing(family_id, date, type, content_md) values (?, ?, ?, ?)",
                        Statement.RETURN_GENERATED_KEYS);
                ps.setLong(1, familyId);
                ps.setDate(2, Date.valueOf(date));
                ps.setString(3, type);
                ps.setString(4, contentMd);
                return ps;
            }, keyHolder);
            Number key = keyHolder.getKey();
            if (key == null) {
                throw new IllegalStateException("briefing 插入未返回主键");
            }
            id = key.longValue();
        } else {
            id = existing.get(0);
            jdbc.update("update briefing set content_md = ? where id = ?", contentMd, id);
        }
        return getById(id);
    }

    public BriefingView get(long familyId, LocalDate date, String type) {
        List<BriefingView> rows = jdbc.query(
                "select id, family_id, date, type, content_md, created_at from briefing "
                        + "where family_id = ? and date = ? and type = ?",
                BriefingService::mapRow, familyId, Date.valueOf(date), type);
        if (rows.isEmpty()) {
            throw ApiException.notFound("该日期简报尚未生成");
        }
        return rows.get(0);
    }

    private BriefingView getById(long id) {
        return jdbc.queryForObject(
                "select id, family_id, date, type, content_md, created_at from briefing where id = ?",
                BriefingService::mapRow, id);
    }

    /** 定时任务用：所有家庭 id。 */
    public List<Long> allFamilyIds() {
        return jdbc.query("select id from family order by id", (rs, i) -> rs.getLong(1));
    }

    /** 定时任务用：家庭户主（role=self 的第一个成员），简报的工作/睡眠段以户主为请求者口径。 */
    public Long familyOwner(long familyId) {
        List<Long> rows = jdbc.query(
                "select id from member where family_id = ? and role = 'self' order by id limit 1",
                (rs, i) -> rs.getLong(1), familyId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private static BriefingView mapRow(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        return new BriefingView(rs.getLong("id"), rs.getLong("family_id"), rs.getString("date"),
                rs.getString("type"), rs.getString("content_md"), rs.getString("created_at"));
    }
}
