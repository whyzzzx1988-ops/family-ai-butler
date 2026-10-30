package com.familybutler.module.health;

import com.familybutler.core.account.AccountService;
import com.familybutler.core.account.ApiException;
import com.familybutler.core.inbox.InboxService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 健康记录（第六周 B1–B4）：睡眠 / 用药 / 测量 / 备注四类，落 health_record。
 *
 * 红线（总方案已定）：只做记录与管理——不走 LLM 加工、不解读不判断，
 * detail_json 只存两类东西：录入方显式给的结构化字段（如用药的 medicine/dosage/remindTime），
 * 与规则可机械抽取的字段（睡眠时长 durationHours）。归属成员 member_id 是「记录关于谁」，
 * 可由录入者指定（给孩子/长辈记），须同家庭校验，跨家庭一律 404。删除为硬删除：
 * 本人，或户主/配偶（家庭健康管理）可删，其余成员 403。
 */
@Service
public class HealthService {

    public static final Set<String> KINDS = Set.of("sleep", "medication", "measure", "note");
    private static final Pattern SLEEP_DURATION = Pattern.compile("睡了\\s*(\\d+(?:\\.\\d+)?)\\s*(?:个)?小时");
    private static final String COLUMNS =
            "id, family_id, member_id, event_id, kind, content, detail_json, source, created_at";

    private final JdbcTemplate jdbc;
    private final InboxService inboxService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public HealthService(JdbcTemplate jdbc, InboxService inboxService) {
        this.jdbc = jdbc;
        this.inboxService = inboxService;
    }

    public record HealthView(long id, long familyId, long memberId, Long eventId, String kind,
                             String content, String detailJson, String source, String createdAt) {
    }

    public HealthView create(long familyId, long recorderId, Long eventId, String content, String kind,
                             Long memberId, Map<String, Object> detail, String source) {
        long subjectId = resolveSubject(familyId, recorderId, memberId);
        Map<String, Object> merged = new LinkedHashMap<>();
        if (detail != null) {
            merged.putAll(detail);
        }
        String resolvedKind = (kind == null || kind.isBlank())
                ? inferKind(content, merged) : kind.trim();
        if (!KINDS.contains(resolvedKind)) {
            throw ApiException.badRequest("kind 只能是 sleep / medication / measure / note");
        }
        String resolvedContent = content == null ? "" : content.trim();
        if (resolvedContent.isBlank() && "medication".equals(resolvedKind)) {
            resolvedContent = composeMedicationContent(merged);
        }
        if (resolvedContent.isBlank()) {
            throw ApiException.badRequest("content 不能为空（用药计划可只传 detail 里的 medicine 等字段）");
        }
        if ("sleep".equals(resolvedKind) && !merged.containsKey("durationHours")) {
            Matcher m = SLEEP_DURATION.matcher(resolvedContent);
            if (m.find()) {
                merged.put("durationHours", Double.parseDouble(m.group(1)));
            }
        }
        long id = insert(
                "insert into health_record(family_id, member_id, event_id, kind, content, detail_json, source) "
                        + "values (?, ?, ?, ?, ?, ?, ?)",
                familyId, subjectId, eventId, resolvedKind, resolvedContent,
                merged.isEmpty() ? null : writeJson(merged),
                source == null || source.isBlank() ? "text" : source);
        return getById(id);
    }

    /** inbox 一键转健康记录：归属默认事件成员，可用 memberId 覆盖（须同家庭）。 */
    public HealthView createFromEvent(long familyId, long eventId, String kind, Long memberId) {
        InboxService.EventView event = inboxService.getEvent(eventId);
        if (event.familyId() != familyId) {
            throw ApiException.notFound("收件箱事件不存在");
        }
        String text = event.text();
        if (text == null || text.isBlank()) {
            throw ApiException.badRequest("事件暂无文本（语音未转写完成），无法转健康记录");
        }
        return create(familyId, event.memberId(), eventId, text, kind,
                memberId == null ? event.memberId() : memberId, null, event.source());
    }

    public List<HealthView> list(long familyId, Long memberId, String kind) {
        StringBuilder sql = new StringBuilder("select " + COLUMNS + " from health_record where family_id = ?");
        List<Object> args = new java.util.ArrayList<>();
        args.add(familyId);
        if (memberId != null) {
            sql.append(" and member_id = ?");
            args.add(memberId);
        }
        if (kind != null && !kind.isBlank()) {
            if (!KINDS.contains(kind)) {
                throw ApiException.badRequest("kind 只能是 sleep / medication / measure / note");
            }
            sql.append(" and kind = ?");
            args.add(kind);
        }
        sql.append(" order by id desc");
        return jdbc.query(sql.toString(), HealthService::mapRow, args.toArray());
    }

    public HealthView get(long familyId, long id) {
        List<HealthView> rows = jdbc.query(
                "select " + COLUMNS + " from health_record where family_id = ? and id = ?",
                HealthService::mapRow, familyId, id);
        if (rows.isEmpty()) {
            throw ApiException.notFound("健康记录不存在");
        }
        return rows.get(0);
    }

    /** 硬删除：本人，或户主/配偶可删；孩子删他人记录 403。删后简报与列表都不再出现。 */
    public void delete(long familyId, AccountService.MemberView deleter, long id) {
        HealthView record = get(familyId, id);
        boolean allowed = deleter.id() == record.memberId()
                || "self".equals(deleter.role()) || "spouse".equals(deleter.role());
        if (!allowed) {
            throw new ApiException(HttpStatus.FORBIDDEN, "只能删除本人（或户主/配偶管理）的健康记录");
        }
        jdbc.update("delete from health_record where id = ?", id);
    }

    private long resolveSubject(long familyId, long recorderId, Long memberId) {
        if (memberId == null) {
            return recorderId;
        }
        Integer count = jdbc.queryForObject(
                "select count(*) from member where id = ? and family_id = ?",
                Integer.class, memberId, familyId);
        if (count == null || count == 0) {
            throw ApiException.notFound("归属成员不存在或不属于本家庭");
        }
        return memberId;
    }

    /** kind 推断（B2）：用药优先于睡眠（「吃安眠药睡了」算用药），测量看指标词，其余 note。 */
    static String inferKind(String content, Map<String, Object> detail) {
        if (detail != null && detail.get("medicine") != null) {
            return "medication";
        }
        String text = content == null ? "" : content;
        if (containsAny(text, "吃药", "用药", "服药", "药片", "安眠药")) {
            return "medication";
        }
        if (containsAny(text, "睡", "失眠", "熬夜")) {
            return "sleep";
        }
        if (containsAny(text, "体重", "血压", "血糖", "体温", "身高", "心率")) {
            return "measure";
        }
        return "note";
    }

    private static boolean containsAny(String text, String... keys) {
        for (String k : keys) {
            if (text.contains(k)) {
                return true;
            }
        }
        return false;
    }

    /** 用药计划只给 detail 时拼一句可读 content，简报「今日用药」段直接用。 */
    private static String composeMedicationContent(Map<String, Object> detail) {
        String medicine = textOf(detail.get("medicine"));
        if (medicine == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(medicine);
        String dosage = textOf(detail.get("dosage"));
        if (dosage != null) {
            sb.append(" ").append(dosage);
        }
        String remindTime = textOf(detail.get("remindTime"));
        if (remindTime != null) {
            sb.append("（每天 ").append(remindTime).append("）");
        }
        return sb.toString();
    }

    private static String textOf(Object value) {
        if (value == null) {
            return null;
        }
        String s = String.valueOf(value).trim();
        return s.isEmpty() ? null : s;
    }

    private String writeJson(Map<String, Object> map) {
        try {
            return objectMapper.writeValueAsString(map);
        } catch (Exception e) {
            throw new IllegalStateException("detail_json 序列化失败", e);
        }
    }

    /** detail 回读解析（Controller 出参用）：解析失败给空 Map，不让脏数据打挂接口。 */
    public Map<String, Object> parseDetail(String detailJson) {
        if (detailJson == null || detailJson.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(detailJson, new TypeReference<>() {
            });
        } catch (Exception e) {
            return Map.of();
        }
    }

    private HealthView getById(long id) {
        return jdbc.queryForObject(
                "select " + COLUMNS + " from health_record where id = ?",
                HealthService::mapRow, id);
    }

    private static HealthView mapRow(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        long eventId = rs.getLong("event_id");
        Long event = rs.wasNull() ? null : eventId;
        return new HealthView(rs.getLong("id"), rs.getLong("family_id"), rs.getLong("member_id"),
                event, rs.getString("kind"), rs.getString("content"), rs.getString("detail_json"),
                rs.getString("source"), rs.getString("created_at"));
    }

    private long insert(String sql, Object... args) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("插入未返回主键: " + sql);
        }
        return key.longValue();
    }
}
