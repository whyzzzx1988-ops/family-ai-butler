package com.familybutler.core.inbox;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;

/**
 * 统一收件箱落库：一句话/一段语音先进 inbox_event 再谈分发。
 * status 语义：
 *   new              已就绪（文本已落、或语音转写完成），等 D6+ 的解析分发消费
 *   asr_processing   语音已存，正在转写（同步转写时只是中间态）
 *   asr_unconfigured 没配 DASHSCOPE_API_KEY，音频已存、转写待补
 *   asr_failed       转写失败（原因记在服务端日志），音频仍保留可重跑
 */
@Service
public class InboxService {

    private final JdbcTemplate jdbc;

    public InboxService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record EventView(long id, long familyId, long memberId, String source, String rawRef,
                            String text, String parsedType, String status, String createdAt) {
    }

    public long createEvent(long familyId, long memberId, String source, String rawRef,
                            String text, String parsedType, String status) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "insert into inbox_event(family_id, member_id, source, raw_ref, text, parsed_type, status) "
                            + "values (?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, familyId);
            ps.setLong(2, memberId);
            ps.setString(3, source);
            ps.setString(4, rawRef);
            ps.setString(5, text);
            ps.setString(6, parsedType);
            ps.setString(7, status);
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("inbox_event 插入未返回主键");
        }
        return key.longValue();
    }

    /** 转写收尾：回填文本并置状态。 */
    public void completeTranscription(long eventId, String text, String status) {
        jdbc.update("update inbox_event set text = ?, status = ? where id = ?", text, status, eventId);
    }

    public void markStatus(long eventId, String status) {
        jdbc.update("update inbox_event set status = ? where id = ?", status, eventId);
    }

    public EventView getEvent(long eventId) {
        List<EventView> rows = jdbc.query(
                "select id, family_id, member_id, source, raw_ref, text, parsed_type, status, created_at "
                        + "from inbox_event where id = ?",
                (rs, i) -> new EventView(rs.getLong("id"), rs.getLong("family_id"), rs.getLong("member_id"),
                        rs.getString("source"), rs.getString("raw_ref"), rs.getString("text"),
                        rs.getString("parsed_type"), rs.getString("status"), rs.getString("created_at")),
                eventId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 本家庭最近的事件，新到旧 —— family_id 仍是唯一的过滤边界。 */
    public List<EventView> listByFamily(long familyId, int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 100));
        return jdbc.query(
                "select id, family_id, member_id, source, raw_ref, text, parsed_type, status, created_at "
                        + "from inbox_event where family_id = ? order by id desc limit ?",
                (rs, i) -> new EventView(rs.getLong("id"), rs.getLong("family_id"), rs.getLong("member_id"),
                        rs.getString("source"), rs.getString("raw_ref"), rs.getString("text"),
                        rs.getString("parsed_type"), rs.getString("status"), rs.getString("created_at")),
                familyId, safeLimit);
    }
}
