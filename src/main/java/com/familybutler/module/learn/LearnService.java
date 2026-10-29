package com.familybutler.module.learn;

import com.familybutler.core.account.AccountService;
import com.familybutler.core.account.ApiException;
import com.familybutler.core.inbox.InboxService;
import com.familybutler.core.llm.LlmGateway;
import com.familybutler.core.memory.MemoryService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 学习记录（第三周 L1+L2）：学习模块第一条竖井 —— 一句话进来，沉淀成错题/单词条目。
 * 数据只通过底座接口拿：事件经 InboxService 读、成员经 AccountService 查、加工经 LlmGateway
 * （module=learn 落账）、简报喂数经 MemoryService 双写；本服务只直写自己的 learn_record 表。
 *
 * 定版口径（第三周清单拍板点，用户授权按推荐方案执行）：
 *   ① 统一表 + kind（word/mistake/note）+ detail_json 加工字段，不分表；
 *   ② 归属：传 childMemberId 时必须是本家庭 role=child 成员（代录），不传默认录入者本人；
 *   ③ 简报合流：建记录同轮双写一条 scope=learn 记忆（归属沿记录 owner、private，
 *      靠简报「孩子名下」口径进「孩子昨日学习」段），双写失败只记 note 不拖垮记录。
 *
 * 加工口径（红线：学习伙伴、不做搜题给答案）：单词补单词卡（音标/词性/释义/例句），
 * 错题补题目整理/知识点/解析思路/易错点——给思路不给「报答案」；note 类不调模型。
 * 加工失败降级：只存原文、detail 为空、响应明示原因（沿用 W2 口径）。
 */
@Service
public class LearnService {

    private static final Logger log = LoggerFactory.getLogger(LearnService.class);

    public static final Set<String> KINDS = Set.of("word", "mistake", "note");

    private static final String WORD_SYSTEM_PROMPT = """
            你是一名英语学习助手。从用户文本中找出要记的英语单词并补全单词卡。\
            只输出一个 JSON 对象，不要输出 Markdown 代码围栏或任何解释文字。格式：\
            {"headword":"单词原形","phonetic":"音标，没有把握则为 null","pos":"词性如 n./v./adj.，没有则为 null",\
            "meaning":"中文释义","example":"一个简单的英文例句","exampleCn":"例句的中文翻译"}。\
            文本可能是一句话（如「今天学了单词 abandon」），提取其中要记的单词。""";

    private static final String MISTAKE_SYSTEM_PROMPT = """
            你是一名学习伙伴，帮学生整理错题。只输出一个 JSON 对象，不要输出 Markdown 代码围栏或任何解释文字。格式：\
            {"question":"整理后的题目，原文不清就照原文","knowledgePoints":["涉及的知识点"],\
            "analysis":"解题思路与关键步骤，讲清为什么这样想","pitfall":"易错点，没有则为 null"}。\
            重点是思路、知识点和易错点，帮助学生自己想通，不要只给最终答案。""";

    private final JdbcTemplate jdbc;
    private final InboxService inboxService;
    private final AccountService accountService;
    private final LlmGateway llmGateway;
    private final MemoryService memoryService;
    private final ObjectMapper mapper = new ObjectMapper();

    public LearnService(JdbcTemplate jdbc, InboxService inboxService, AccountService accountService,
                        LlmGateway llmGateway, MemoryService memoryService) {
        this.jdbc = jdbc;
        this.inboxService = inboxService;
        this.accountService = accountService;
        this.llmGateway = llmGateway;
        this.memoryService = memoryService;
    }

    public record LearnView(long id, long familyId, long memberId, Long eventId, String kind,
                            String subject, String content, String detailJson, String source,
                            String createdAt) {
    }

    public record CreateResult(LearnView record, Long llmLogId, String processNote, Long briefingMemoryId) {
    }

    /**
     * 建一条学习记录：来源二选一（eventId 从 inbox 事件取文本，或直接给 content）；
     * kind 不传时按内容推断（错→mistake、单词/生词→word、其余 note）。
     */
    public CreateResult create(long familyId, long operatorMemberId, Long eventId, String kind,
                               String subject, String content, Long childMemberId) {
        String resolvedContent;
        String source;
        if (eventId != null) {
            InboxService.EventView event = inboxService.getEvent(eventId);
            if (event == null || event.familyId() != familyId) {
                throw ApiException.notFound("收件箱事件不存在或不属于本家庭");
            }
            if (event.text() == null || event.text().isBlank()) {
                throw ApiException.badRequest("该事件文本为空，无法建学习记录（事件状态：" + event.status()
                        + "）。语音事件请先完成转写");
            }
            resolvedContent = content != null && !content.isBlank() ? content.trim() : event.text().trim();
            source = event.source();
        } else {
            if (content == null || content.isBlank()) {
                throw ApiException.badRequest("content 不能为空（或改传 eventId 从收件箱事件建记录）");
            }
            resolvedContent = content.trim();
            source = "text";
        }

        String resolvedKind = kind == null || kind.isBlank() ? inferKind(resolvedContent) : kind.trim();
        if (!KINDS.contains(resolvedKind)) {
            throw ApiException.badRequest("kind 只能是 " + String.join(" / ", KINDS));
        }
        long ownerMemberId = resolveOwner(familyId, operatorMemberId, childMemberId);
        String resolvedSubject = subject == null || subject.isBlank() ? null
                : subject.trim().length() > 30 ? subject.trim().substring(0, 30) : subject.trim();

        // L2 加工：word/mistake 调网关补结构化字段，失败降级只存原文 + note 明示。
        String detailJson = null;
        Long llmLogId = null;
        String processNote = null;
        if ("word".equals(resolvedKind) || "mistake".equals(resolvedKind)) {
            try {
                LlmGateway.ChatResult result = llmGateway.chat(familyId, "learn",
                        "word".equals(resolvedKind) ? WORD_SYSTEM_PROMPT : MISTAKE_SYSTEM_PROMPT,
                        resolvedContent);
                llmLogId = result.logId();
                detailJson = normalizeDetailJson(result.content());
            } catch (ApiException e) {
                processNote = "结构化加工失败，已只存原文：" + e.getMessage();
                log.warn("学习记录加工失败 kind={} : {}", resolvedKind, e.getMessage());
            }
        }

        KeyHolder keyHolder = new GeneratedKeyHolder();
        String detail = detailJson;
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "insert into learn_record(family_id, member_id, event_id, kind, subject, content, detail_json, source) "
                            + "values (?, ?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, familyId);
            ps.setLong(2, ownerMemberId);
            if (eventId == null) {
                ps.setNull(3, Types.BIGINT);
            } else {
                ps.setLong(3, eventId);
            }
            ps.setString(4, resolvedKind);
            ps.setString(5, resolvedSubject);
            ps.setString(6, resolvedContent);
            ps.setString(7, detail);
            ps.setString(8, source);
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("learn_record 插入未返回主键");
        }
        LearnView view = getById(key.longValue());

        // 拍板点③双写：scope=learn 记忆喂简报学习段；失败只记 note。
        Long briefingMemoryId = null;
        try {
            MemoryService.MemoryView memory = memoryService.create(familyId, ownerMemberId,
                    "learn", "record", resolvedContent, source, "private", null);
            briefingMemoryId = memory.id();
        } catch (RuntimeException e) {
            String note = "简报双写记忆失败：" + e.getMessage();
            processNote = processNote == null ? note : processNote + "；" + note;
            log.warn("学习记录 {} 双写记忆失败：{}", view.id(), e.getMessage());
        }
        return new CreateResult(view, llmLogId, processNote, briefingMemoryId);
    }

    /** 本家庭学习记录列表：可按归属成员/类型/学科过滤，新到旧。 */
    public List<LearnView> listByFamily(long familyId, Long memberId, String kind, String subject, int limit) {
        StringBuilder sql = new StringBuilder(
                "select id, family_id, member_id, event_id, kind, subject, content, detail_json, source, created_at "
                        + "from learn_record where family_id = ?");
        List<Object> args = new ArrayList<>(List.of(familyId));
        if (memberId != null) {
            sql.append(" and member_id = ?");
            args.add(memberId);
        }
        if (kind != null && !kind.isBlank()) {
            if (!KINDS.contains(kind)) {
                throw ApiException.badRequest("kind 只能是 " + String.join(" / ", KINDS));
            }
            sql.append(" and kind = ?");
            args.add(kind);
        }
        if (subject != null && !subject.isBlank()) {
            sql.append(" and subject = ?");
            args.add(subject.trim());
        }
        sql.append(" order by id desc limit ?");
        args.add(Math.max(1, Math.min(limit, 200)));
        return jdbc.query(sql.toString(), (rs, i) -> mapRow(rs), args.toArray());
    }

    /** 家庭内读单条：跨家庭与不存在统一 404。 */
    public LearnView getByIdForFamily(long id, long familyId) {
        List<LearnView> rows = jdbc.query(
                "select id, family_id, member_id, event_id, kind, subject, content, detail_json, source, created_at "
                        + "from learn_record where id = ? and family_id = ?",
                (rs, i) -> mapRow(rs), id, familyId);
        if (rows.isEmpty()) {
            throw ApiException.notFound("学习记录不存在或不属于本家庭");
        }
        return rows.get(0);
    }

    public LearnView getById(long id) {
        List<LearnView> rows = jdbc.query(
                "select id, family_id, member_id, event_id, kind, subject, content, detail_json, source, created_at "
                        + "from learn_record where id = ?",
                (rs, i) -> mapRow(rs), id);
        if (rows.isEmpty()) {
            throw ApiException.notFound("学习记录不存在");
        }
        return rows.get(0);
    }

    /** kind 推断（v0 规则，与 parseType 同风格、可复核）：含错→mistake，含单词/生词→word，其余 note。 */
    static String inferKind(String content) {
        if (content.contains("错")) {
            return "mistake";
        }
        if (content.contains("单词") || content.contains("生词")) {
            return "word";
        }
        return "note";
    }

    /** 归属解析：childMemberId 必须能在本家庭成员列表里找到且 role=child（家长代录），否则 400。 */
    private long resolveOwner(long familyId, long operatorMemberId, Long childMemberId) {
        if (childMemberId == null) {
            return operatorMemberId;
        }
        boolean isChild = accountService.listMembers(familyId).stream()
                .anyMatch(m -> m.id() == childMemberId && "child".equals(m.role()));
        if (!isChild) {
            throw ApiException.badRequest("childMemberId 不是本家庭的孩子成员：" + childMemberId);
        }
        return childMemberId;
    }

    /** 模型加工结果 → 规范 JSON 字符串：必须是 JSON 对象，容忍代码围栏；不合规抛错走降级。 */
    private String normalizeDetailJson(String raw) {
        if (raw == null || raw.isBlank()) {
            throw ApiException.badRequest("加工结果为空");
        }
        String json = raw.trim();
        if (json.startsWith("```")) {
            int firstNewline = json.indexOf('\n');
            if (firstNewline > 0) {
                json = json.substring(firstNewline + 1);
            }
            if (json.endsWith("```")) {
                json = json.substring(0, json.length() - 3).trim();
            }
        }
        try {
            JsonNode root = mapper.readTree(json);
            if (root == null || !root.isObject()) {
                throw ApiException.badRequest("加工结果不是 JSON 对象");
            }
            return mapper.writeValueAsString(root);
        } catch (JsonProcessingException e) {
            throw ApiException.badRequest("加工结果不是合法 JSON");
        }
    }

    private LearnView mapRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        long eventId = rs.getLong("event_id");
        boolean eventNull = rs.wasNull();
        return new LearnView(rs.getLong("id"), rs.getLong("family_id"), rs.getLong("member_id"),
                eventNull ? null : eventId,
                rs.getString("kind"), rs.getString("subject"), rs.getString("content"),
                rs.getString("detail_json"), rs.getString("source"), rs.getString("created_at"));
    }
}
