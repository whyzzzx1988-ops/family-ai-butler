package com.familybutler.core.memory;

import com.familybutler.core.account.ApiException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Types;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 长期记忆（D5）：结构化条目，可查可删 —— 设计红线是「禁止只存向量不可见」，
 * 所以每条记忆都是明文行数据，向量只是以后的检索加速，不替代条目本身。
 *
 * 可见性模型（隔离的第二道闸，第一道是 family_id）：
 *   visibility=private 仅归属成员本人可见/可删；
 *   visibility=family  本家庭成员都可见（仍只有归属成员可删），如要进全家简报的内容。
 * 查不到与不存在统一按 404 处理，不向其他成员泄露条目是否存在。
 */
@Service
public class MemoryService {

    public static final Set<String> SCOPES = Set.of("work", "learn", "health", "common");
    public static final Set<String> KINDS = Set.of("fact", "preference", "event", "record");
    public static final Set<String> SOURCES = Set.of("voice", "text", "file", "system");
    public static final Set<String> VISIBILITIES = Set.of("private", "family");

    private final JdbcTemplate jdbc;

    public MemoryService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record MemoryView(long id, long familyId, long memberId, String scope, String kind,
                             String content, String source, String visibility,
                             String expiresAt, String createdAt) {
    }

    public MemoryView create(long familyId, long memberId, String scope, String kind, String content,
                             String source, String visibility, OffsetDateTime expiresAt) {
        requireIn(scope, SCOPES, "scope");
        requireIn(kind, KINDS, "kind");
        String src = source == null || source.isBlank() ? "text" : source;
        requireIn(src, SOURCES, "source");
        String vis = visibility == null || visibility.isBlank() ? "private" : visibility;
        requireIn(vis, VISIBILITIES, "visibility");

        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "insert into memory_item(family_id, member_id, scope, kind, content, source, visibility, expires_at) "
                            + "values (?, ?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, familyId);
            ps.setLong(2, memberId);
            ps.setString(3, scope);
            ps.setString(4, kind);
            ps.setString(5, content);
            ps.setString(6, src);
            ps.setString(7, vis);
            if (expiresAt == null) {
                ps.setNull(8, Types.TIMESTAMP_WITH_TIMEZONE);
            } else {
                ps.setObject(8, expiresAt);
            }
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("memory_item 插入未返回主键");
        }
        return getVisible(key.longValue(), familyId, memberId);
    }

    /** 我能看到的记忆：我自己的全部 + 本家庭 visibility=family 的；过期条目自动隐藏。 */
    public List<MemoryView> listVisible(long familyId, long memberId, String scope, String kind, int limit) {
        StringBuilder sql = new StringBuilder(
                "select id, family_id, member_id, scope, kind, content, source, visibility, expires_at, created_at "
                        + "from memory_item where family_id = ? "
                        + "and (member_id = ? or visibility = 'family') "
                        + "and (expires_at is null or expires_at > current_timestamp)");
        List<Object> args = new ArrayList<>(List.of(familyId, memberId));
        if (scope != null && !scope.isBlank()) {
            requireIn(scope, SCOPES, "scope");
            sql.append(" and scope = ?");
            args.add(scope);
        }
        if (kind != null && !kind.isBlank()) {
            requireIn(kind, KINDS, "kind");
            sql.append(" and kind = ?");
            args.add(kind);
        }
        sql.append(" order by id desc limit ?");
        args.add(Math.max(1, Math.min(limit, 200)));
        return jdbc.query(sql.toString(), MemoryService::mapRow, args.toArray());
    }

    /** 单条读取：看不到（别人的私有条目/跨家庭/不存在）一律 404。 */
    public MemoryView getVisible(long id, long familyId, long memberId) {
        List<MemoryView> rows = jdbc.query(
                "select id, family_id, member_id, scope, kind, content, source, visibility, expires_at, created_at "
                        + "from memory_item where id = ? and family_id = ? "
                        + "and (member_id = ? or visibility = 'family') "
                        + "and (expires_at is null or expires_at > current_timestamp)",
                MemoryService::mapRow, id, familyId, memberId);
        if (rows.isEmpty()) {
            throw ApiException.notFound("记忆不存在或不可见");
        }
        return rows.get(0);
    }

    /** 删除：只有归属成员能删自己的条目；硬删除，删后任何查询都查不到。 */
    public void deleteOwn(long id, long memberId) {
        int affected = jdbc.update("delete from memory_item where id = ? and member_id = ?", id, memberId);
        if (affected == 0) {
            throw ApiException.notFound("记忆不存在或不可见");
        }
    }

    private static MemoryView mapRow(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        return new MemoryView(rs.getLong("id"), rs.getLong("family_id"), rs.getLong("member_id"),
                rs.getString("scope"), rs.getString("kind"), rs.getString("content"),
                rs.getString("source"), rs.getString("visibility"),
                rs.getString("expires_at"), rs.getString("created_at"));
    }

    private static void requireIn(String value, Set<String> allowed, String field) {
        if (value == null || !allowed.contains(value)) {
            throw ApiException.badRequest(field + " 只能是 " + String.join(" / ", allowed));
        }
    }
}
