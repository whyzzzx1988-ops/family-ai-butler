package com.familybutler.core.proactive;

import com.familybutler.core.account.ApiException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Types;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * 底座级待办（todo_task，三模块共用）：D6 先给最小接口——建、列、完成。
 * 简报的「今日工作」段与后续主动催办都读这张表；归属成员从登录态来，不收前端传值。
 */
@Service
public class TodoService {

    private final JdbcTemplate jdbc;

    public TodoService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record TodoView(long id, long familyId, long ownerMemberId, String title,
                           String dueAt, String status, String createdAt) {
    }

    public TodoView create(long familyId, long ownerMemberId, String title, OffsetDateTime dueAt) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "insert into todo_task(family_id, owner_member_id, title, due_at, status) values (?, ?, ?, ?, 'open')",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, familyId);
            ps.setLong(2, ownerMemberId);
            ps.setString(3, title);
            if (dueAt == null) {
                ps.setNull(4, Types.TIMESTAMP_WITH_TIMEZONE);
            } else {
                ps.setObject(4, dueAt);
            }
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("todo_task 插入未返回主键");
        }
        return getOwn(key.longValue(), ownerMemberId);
    }

    /** 我的未完成待办，按到期时间排（无日期的排最后）。 */
    public List<TodoView> listOpen(long ownerMemberId) {
        return jdbc.query(
                "select id, family_id, owner_member_id, title, due_at, status, created_at from todo_task "
                        + "where owner_member_id = ? and status = 'open' order by due_at asc nulls last, id",
                TodoService::mapRow, ownerMemberId);
    }

    public TodoView getOwn(long id, long ownerMemberId) {
        List<TodoView> rows = jdbc.query(
                "select id, family_id, owner_member_id, title, due_at, status, created_at from todo_task "
                        + "where id = ? and owner_member_id = ?",
                TodoService::mapRow, id, ownerMemberId);
        if (rows.isEmpty()) {
            throw ApiException.notFound("待办不存在或不可见");
        }
        return rows.get(0);
    }

    /** 完成我自己的待办；不是我的按 404 处理，不泄露存在性。 */
    public void markDone(long id, long ownerMemberId) {
        int affected = jdbc.update(
                "update todo_task set status = 'done' where id = ? and owner_member_id = ? and status = 'open'",
                id, ownerMemberId);
        if (affected == 0) {
            throw ApiException.notFound("待办不存在或不可见");
        }
    }

    private static TodoView mapRow(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        return new TodoView(rs.getLong("id"), rs.getLong("family_id"), rs.getLong("owner_member_id"),
                rs.getString("title"), rs.getString("due_at"), rs.getString("status"),
                rs.getString("created_at"));
    }
}
