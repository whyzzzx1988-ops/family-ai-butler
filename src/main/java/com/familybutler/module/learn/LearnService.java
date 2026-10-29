package com.familybutler.module.learn;

import com.familybutler.core.account.AccountService;
import com.familybutler.core.account.ApiException;
import com.familybutler.core.inbox.InboxService;
import com.familybutler.core.llm.LlmGateway;
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
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 学习记录（第三周 L1+L2 落地，第四周 R1/R2 加复习）：学习模块的条目与复习调度。
 * 数据只通过底座接口拿：事件经 InboxService 读、成员经 AccountService 查、加工/出题经
 * LlmGateway（module=learn 落账）；本服务只直写自己的 learn_record / review_log 表。
 * （简报学习段自第四周 R3 起由 BriefingService 直查 learn_record，双写记忆已下线。）
 *
 * 定版口径：
 *   ① 统一表 + kind（word/mistake/note）+ detail_json 加工字段，不分表；
 *   ② 归属：传 childMemberId 时必须是本家庭 role=child 成员（代录），不传默认录入者本人；
 *   ③ 复习调度（第四周拍板）：固定三段间隔 1/3/7 天为骨架——建记录时 stage=0、1 天后第一次到期；
 *      自评三档调档：mastered 进一段（stage 满 3 毕业、不再到期）、fuzzy 原地且 1 天后重试、
 *      forgot 回 stage=0 且 1 天后到期。不做动态参数拟合，规则就在本类里可复核。
 *
 * 加工/出题口径（红线：学习伙伴、不做搜题给答案）：单词补单词卡，错题补知识点/解析思路/易错点；
 * 加工失败降级只存原文 + note 明示；抽查出题失败降级为按 detail 规则拼题 + note 明示（沿用降级口径）。
 */
@Service
public class LearnService {

    private static final Logger log = LoggerFactory.getLogger(LearnService.class);

    public static final Set<String> KINDS = Set.of("word", "mistake", "note");
    public static final Set<String> REVIEW_RESULTS = Set.of("mastered", "fuzzy", "forgot");
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    /** stage 0/1/2 各段距下一次到期的天数；stage=3 毕业。 */
    private static final int[] STAGE_INTERVAL_DAYS = {1, 3, 7};

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

    private static final String QUIZ_SYSTEM_PROMPT = """
            你是一名学习伙伴，负责出抽查题。根据用户给出的到期复习条目逐条出题：\
            单词条目出「看释义说单词」或「看单词说意思」；错题条目出「复述解题思路」类问题；笔记条目出回顾问题。\
            只输出一个 JSON 数组，不要输出 Markdown 代码围栏或任何解释文字。元素格式：\
            {"recordId":条目 id,"question":"题面","reference":"参考要点（单词给单词本身与例句，错题给思路要点）"}。\
            每个输入条目必须恰好出一题，recordId 原样带回。""";

    private final JdbcTemplate jdbc;
    private final InboxService inboxService;
    private final AccountService accountService;
    private final LlmGateway llmGateway;
    private final ObjectMapper mapper = new ObjectMapper();

    public LearnService(JdbcTemplate jdbc, InboxService inboxService, AccountService accountService,
                        LlmGateway llmGateway) {
        this.jdbc = jdbc;
        this.inboxService = inboxService;
        this.accountService = accountService;
        this.llmGateway = llmGateway;
    }

    public record LearnView(long id, long familyId, long memberId, Long eventId, String kind,
                            String subject, String content, String detailJson, String source,
                            int reviewStage, String nextReviewAt, String createdAt) {
    }

    public record CreateResult(LearnView record, Long llmLogId, String processNote) {
    }

    public record QuizItem(long recordId, String kind, String question, String reference) {
    }

    public record QuizResult(List<QuizItem> items, Long llmLogId, String note) {
    }

    public record ReviewLogResult(LearnView record, long logId) {
    }

    private static final String COLUMNS =
            "id, family_id, member_id, event_id, kind, subject, content, detail_json, source, "
                    + "review_stage, next_review_at, created_at";

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
        OffsetDateTime firstReviewAt = OffsetDateTime.now(ZONE).plusDays(STAGE_INTERVAL_DAYS[0]);
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "insert into learn_record(family_id, member_id, event_id, kind, subject, content, "
                            + "detail_json, source, review_stage, next_review_at) "
                            + "values (?, ?, ?, ?, ?, ?, ?, ?, 0, ?)",
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
            ps.setObject(9, firstReviewAt);
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("learn_record 插入未返回主键");
        }
        return new CreateResult(getById(key.longValue()), llmLogId, processNote);
    }

    /** 本家庭学习记录列表：可按归属成员/类型/学科过滤，新到旧。 */
    public List<LearnView> listByFamily(long familyId, Long memberId, String kind, String subject, int limit) {
        StringBuilder sql = new StringBuilder(
                "select " + COLUMNS + " from learn_record where family_id = ?");
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
                "select " + COLUMNS + " from learn_record where id = ? and family_id = ?",
                (rs, i) -> mapRow(rs), id, familyId);
        if (rows.isEmpty()) {
            throw ApiException.notFound("学习记录不存在或不属于本家庭");
        }
        return rows.get(0);
    }

    public LearnView getById(long id) {
        List<LearnView> rows = jdbc.query(
                "select " + COLUMNS + " from learn_record where id = ?",
                (rs, i) -> mapRow(rs), id);
        if (rows.isEmpty()) {
            throw ApiException.notFound("学习记录不存在");
        }
        return rows.get(0);
    }

    /**
     * 今日待复习（R1）：stage 未毕业（<3）且 next_review_at 已到（含为空的历史数据，按立即到期处理）。
     * memberId 不传则全家庭（家长视角），传了只看该成员。
     */
    public List<LearnView> reviewToday(long familyId, Long memberId) {
        StringBuilder sql = new StringBuilder(
                "select " + COLUMNS + " from learn_record where family_id = ? and review_stage < 3 "
                        + "and (next_review_at is null or next_review_at <= current_timestamp)");
        List<Object> args = new ArrayList<>(List.of(familyId));
        if (memberId != null) {
            sql.append(" and member_id = ?");
            args.add(memberId);
        }
        sql.append(" order by next_review_at asc nulls first, id");
        return jdbc.query(sql.toString(), (rs, i) -> mapRow(rs), args.toArray());
    }

    /**
     * 抽查出题（R2）：取今日待复习的前 limit 条，一次网关调用批量出题（module=learn）；
     * 模型漏题/调用失败时，缺的题按 detail 规则拼兜底题并在 note 里明示，绝不静默少题。
     */
    public QuizResult quiz(long familyId, Long memberId, int limit) {
        List<LearnView> due = reviewToday(familyId, memberId);
        int safeLimit = Math.max(1, Math.min(limit, 20));
        if (due.size() > safeLimit) {
            due = due.subList(0, safeLimit);
        }
        if (due.isEmpty()) {
            return new QuizResult(List.of(), null, null);
        }
        Map<Long, QuizItem> byId = new HashMap<>();
        Long llmLogId = null;
        String note = null;
        try {
            StringBuilder prompt = new StringBuilder("到期复习条目如下（JSON 字段 detail 是已加工的结构化内容，可能为空）：\n");
            for (LearnView r : due) {
                prompt.append("- recordId=").append(r.id())
                        .append("，kind=").append(r.kind())
                        .append("，content=").append(r.content())
                        .append("，detail=").append(r.detailJson() == null ? "null" : r.detailJson())
                        .append('\n');
            }
            LlmGateway.ChatResult result = llmGateway.chat(familyId, "learn", QUIZ_SYSTEM_PROMPT, prompt.toString());
            llmLogId = result.logId();
            for (QuizItem item : parseQuizItems(result.content())) {
                byId.put(item.recordId(), item);
            }
        } catch (ApiException e) {
            note = "模型出题失败，已全部改用规则兜底题：" + e.getMessage();
            log.warn("抽查出题失败：{}", e.getMessage());
        }
        List<QuizItem> items = new ArrayList<>();
        int fallbackCount = 0;
        for (LearnView r : due) {
            QuizItem item = byId.get(r.id());
            if (item == null) {
                item = fallbackItem(r);
                fallbackCount++;
            }
            items.add(new QuizItem(r.id(), r.kind(), item.question(), item.reference()));
        }
        if (fallbackCount > 0 && note == null) {
            note = "有 " + fallbackCount + " 条模型未出题，已用规则兜底题补齐";
        }
        return new QuizResult(items, llmLogId, note);
    }

    /**
     * 回记一次复习结果（R2）：写 review_log（学习者 = 记录归属成员）并按定版规则调档：
     * mastered 进一段（满 3 毕业、next 置空）；fuzzy 原地、1 天后重试；forgot 回 stage 0、1 天后到期。
     * 不要求记录当前必须到期——提前复习同样记账调档，规则一致。
     */
    public ReviewLogResult logReview(long familyId, long recordId, String result) {
        if (result == null || !REVIEW_RESULTS.contains(result)) {
            throw ApiException.badRequest("result 只能是 " + String.join(" / ", REVIEW_RESULTS));
        }
        LearnView record = getByIdForFamily(recordId, familyId);
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "insert into review_log(family_id, member_id, record_id, result) values (?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, familyId);
            ps.setLong(2, record.memberId());
            ps.setLong(3, recordId);
            ps.setString(4, result);
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("review_log 插入未返回主键");
        }

        int stage = record.reviewStage();
        OffsetDateTime next;
        OffsetDateTime now = OffsetDateTime.now(ZONE);
        switch (result) {
            case "mastered" -> {
                stage = Math.min(stage + 1, 3);
                next = stage >= 3 ? null : now.plusDays(STAGE_INTERVAL_DAYS[stage]);
            }
            case "fuzzy" -> next = now.plusDays(1);
            default -> { // forgot
                stage = 0;
                next = now.plusDays(1);
            }
        }
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "update learn_record set review_stage = ?, next_review_at = ? where id = ?");
            ps.setInt(1, stage);
            if (next == null) {
                ps.setNull(2, Types.TIMESTAMP_WITH_TIMEZONE);
            } else {
                ps.setObject(2, next);
            }
            ps.setLong(3, recordId);
            return ps;
        });
        return new ReviewLogResult(getById(recordId), key.longValue());
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
        JsonNode root = readJsonLoose(raw);
        if (root == null || !root.isObject()) {
            throw ApiException.badRequest("加工结果不是 JSON 对象");
        }
        try {
            return mapper.writeValueAsString(root);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("加工结果序列化失败", e);
        }
    }

    private List<QuizItem> parseQuizItems(String raw) {
        JsonNode root = readJsonLoose(raw);
        if (root == null || !root.isArray()) {
            throw ApiException.badRequest("出题结果不是 JSON 数组");
        }
        List<QuizItem> items = new ArrayList<>();
        for (JsonNode node : root) {
            JsonNode idNode = node.get("recordId");
            String question = textOrNull(node.get("question"));
            if (idNode == null || !idNode.isNumber() || question == null) {
                continue;
            }
            items.add(new QuizItem(idNode.asLong(), "", question, textOrNull(node.get("reference"))));
        }
        return items;
    }

    /** 规则兜底题（模型没出/出失败时用）：只用条目原文与已加工 detail 拼题，不编新内容。 */
    private QuizItem fallbackItem(LearnView r) {
        JsonNode detail = null;
        if (r.detailJson() != null) {
            try {
                detail = mapper.readTree(r.detailJson());
            } catch (JsonProcessingException ignored) {
                // detail 损坏时按无 detail 处理
            }
        }
        return switch (r.kind()) {
            case "word" -> {
                String meaning = detail == null ? null : textOrNull(detail.get("meaning"));
                String headword = detail == null ? null : textOrNull(detail.get("headword"));
                yield new QuizItem(r.id(), r.kind(),
                        meaning != null ? "说出这个释义对应的单词：「" + meaning + "」" : "复习这个单词条目：" + r.content(),
                        headword != null ? headword : r.content());
            }
            case "mistake" -> {
                String question = detail == null ? null : textOrNull(detail.get("question"));
                String analysis = detail == null ? null : textOrNull(detail.get("analysis"));
                yield new QuizItem(r.id(), r.kind(),
                        "复述这道题的解题思路：" + (question != null ? question : r.content()),
                        analysis != null ? analysis : r.content());
            }
            default -> new QuizItem(r.id(), r.kind(), "回顾这条笔记：" + r.content(), r.content());
        };
    }

    /** 容忍代码围栏读 JSON；读不出来返回 null（由调用方决定抛错还是降级）。 */
    private JsonNode readJsonLoose(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
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
            return mapper.readTree(json);
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    private static String textOrNull(JsonNode node) {
        if (node == null || !node.isTextual()) {
            return null;
        }
        String value = node.asText().trim();
        return value.isEmpty() ? null : value;
    }

    private LearnView mapRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        long eventId = rs.getLong("event_id");
        boolean eventNull = rs.wasNull();
        return new LearnView(rs.getLong("id"), rs.getLong("family_id"), rs.getLong("member_id"),
                eventNull ? null : eventId,
                rs.getString("kind"), rs.getString("subject"), rs.getString("content"),
                rs.getString("detail_json"), rs.getString("source"),
                rs.getInt("review_stage"), rs.getString("next_review_at"),
                rs.getString("created_at"));
    }
}
