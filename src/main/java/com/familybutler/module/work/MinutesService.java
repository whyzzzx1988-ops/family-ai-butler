package com.familybutler.module.work;

import com.familybutler.core.account.ApiException;
import com.familybutler.core.inbox.InboxService;
import com.familybutler.core.llm.LlmGateway;
import com.familybutler.core.proactive.TodoService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 会议纪要（第二周 W1 生成 + W2 待办候选）：工作模块第一条业务竖井。
 * 数据只通过底座接口拿：事件经 InboxService 读（不直查 inbox_event），
 * 生成/抽取经 LlmGateway 走（module=work，每次调用落 llm_call_log），
 * 确认建待办经 TodoService（source_event_id 回填纪要来源事件）；
 * 本服务只直写自己模块的 meeting_note 表。
 *
 * 隔离与防呆口径（沿用底座）：
 *   事件/纪要不存在或不属于本家庭 → 统一 404，不泄露其他家庭数据是否存在；
 *   事件转写文本为空 → 400 明示（语音未转写完/转写未配置时先补转写，别拿空文本编纪要）；
 *   同一事件已生成过纪要 → 409（event_id 唯一，一对一溯源，且不白花一次模型调用）；
 *   候选确认：下标越界 400、重复下标 400、已确认过的下标 409（防重复建待办）。
 *
 * W2 候选口径：生成纪要同轮再调一次网关抽候选，结构化 JSON 存 meeting_note.todo_candidates 附属字段；
 * 负责人/到期只存原文线索（ownerHint/dueHint），不猜人、不把「下周三」这类表述擅自解析成日期——
 * 确认时由用户按需给 dueAt。抽取失败不拖垮纪要：纪要照常落表，候选为空并在响应里明示原因。
 */
@Service
public class MinutesService {

    private static final Logger log = LoggerFactory.getLogger(MinutesService.class);

    private static final String SYSTEM_PROMPT = """
            你是一名会议纪要助手。只根据用户给出的会议转写文本整理纪要，\
            不编造转写中没有出现的人名、时间、数字和结论；某部分信息不足时，在该段写明「转写未提及」。
            输出 Markdown，严格按以下结构：
            第一行：# <纪要标题，不超过 30 个字，概括会议主题>
            随后空一行，依次输出三个段落，段标题原样使用：
            ## 背景
            ## 结论
            ## 讨论要点
            讨论要点用无序列表逐条列出，其余段落用简洁短句。不要输出结构之外的解释或寒暄。
            """;

    private static final String EXTRACT_SYSTEM_PROMPT = """
            你是一名待办抽取助手。只根据用户给出的会议转写文本，抽取其中明确提出的待办事项（接下来要做的事），\
            不要把背景信息或已经定下的结论本身当成待办，不要编造转写中没有的事项、负责人和时间。
            只输出一个 JSON 数组，不要输出 Markdown 代码围栏或任何解释文字。数组元素格式：\
            {"title":"待办标题，简洁、动词开头","ownerHint":"负责人原文线索，没有则为 null","dueHint":"时间原文线索（如「下周三之前」），没有则为 null"}。\
            没有待办时输出 []。
            """;

    private static final int MAX_CANDIDATES = 20;

    private final JdbcTemplate jdbc;
    private final InboxService inboxService;
    private final LlmGateway llmGateway;
    private final TodoService todoService;
    private final ObjectMapper mapper = new ObjectMapper();

    public MinutesService(JdbcTemplate jdbc, InboxService inboxService, LlmGateway llmGateway,
                          TodoService todoService) {
        this.jdbc = jdbc;
        this.inboxService = inboxService;
        this.llmGateway = llmGateway;
        this.todoService = todoService;
    }

    public record TodoCandidate(String title, String ownerHint, String dueHint) {
    }

    public record MinutesView(long id, long familyId, long memberId, long eventId,
                              String title, String contentMd,
                              List<TodoCandidate> todoCandidates, List<Integer> todoConfirmedIndexes,
                              String createdAt) {
    }

    public record GenerateResult(MinutesView minutes, long llmLogId, String model,
                                 int tokensIn, int tokensOut, BigDecimal cost,
                                 Long todoExtractionLogId, String todoExtractionNote) {
    }

    public record ConfirmItem(int index, OffsetDateTime dueAt) {
    }

    public record ConfirmResult(MinutesView minutes, List<TodoService.TodoView> createdTodos) {
    }

    public GenerateResult generate(long familyId, long memberId, long eventId) {
        InboxService.EventView event = inboxService.getEvent(eventId);
        if (event == null || event.familyId() != familyId) {
            throw ApiException.notFound("收件箱事件不存在或不属于本家庭");
        }
        if (event.text() == null || event.text().isBlank()) {
            throw ApiException.badRequest("该事件转写文本为空，无法生成纪要（事件状态：" + event.status()
                    + "）。语音事件请先完成转写，文本事件请确认内容已落库");
        }
        MinutesView existing = findByEventId(eventId);
        if (existing != null) {
            throw ApiException.conflict("该事件已生成纪要（纪要 id=" + existing.id()
                    + "），一条事件只生成一份纪要");
        }

        String transcript = event.text().trim();
        LlmGateway.ChatResult result = llmGateway.chat(familyId, "work", SYSTEM_PROMPT,
                "会议转写文本如下：\n\n" + transcript);
        String contentMd = result.content() == null ? "" : result.content().trim();
        if (contentMd.isBlank()) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "模型返回的纪要为空，未落表");
        }
        String title = extractTitle(contentMd, eventId);

        // 同轮抽待办候选：失败只降级候选（为空 + 响应明示），不拖垮已生成的纪要。
        List<TodoCandidate> candidates = List.of();
        Long extractionLogId = null;
        String extractionNote = null;
        try {
            LlmGateway.ChatResult extraction = llmGateway.chat(familyId, "work", EXTRACT_SYSTEM_PROMPT,
                    "会议转写文本如下：\n\n" + transcript);
            extractionLogId = extraction.logId();
            candidates = parseCandidates(extraction.content());
        } catch (ApiException e) {
            extractionNote = "待办候选抽取失败：" + e.getMessage();
            log.warn("纪要 {} 待办候选抽取失败：{}", eventId, e.getMessage());
        }

        KeyHolder keyHolder = new GeneratedKeyHolder();
        String candidatesJson = serializeCandidates(candidates);
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "insert into meeting_note(family_id, member_id, event_id, title, content_md, todo_candidates) "
                            + "values (?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, familyId);
            ps.setLong(2, memberId);
            ps.setLong(3, eventId);
            ps.setString(4, title);
            ps.setString(5, contentMd);
            ps.setString(6, candidatesJson);
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("meeting_note 插入未返回主键");
        }
        return new GenerateResult(getById(key.longValue()), result.logId(), result.model(),
                result.tokensIn(), result.tokensOut(), result.cost(), extractionLogId, extractionNote);
    }

    /**
     * 勾选候选转正式待办：负责人默认纪要创建者（meeting_note.member_id），
     * dueAt 由确认人按候选的时间线索选填，不填则无到期日；每条待办回填来源事件 id。
     */
    public ConfirmResult confirmTodos(long familyId, long minutesId, List<ConfirmItem> items) {
        MinutesView minutes = getByIdForFamily(minutesId, familyId);
        if (items == null || items.isEmpty()) {
            throw ApiException.badRequest("至少勾选一条待办候选");
        }
        Set<Integer> seen = new HashSet<>();
        for (ConfirmItem item : items) {
            if (!seen.add(item.index())) {
                throw ApiException.badRequest("候选下标重复：" + item.index());
            }
            if (item.index() < 0 || item.index() >= minutes.todoCandidates().size()) {
                throw ApiException.badRequest("候选下标越界：" + item.index()
                        + "（本纪要共 " + minutes.todoCandidates().size() + " 条候选）");
            }
            if (minutes.todoConfirmedIndexes().contains(item.index())) {
                throw ApiException.conflict("候选 " + item.index() + " 已确认过，不要重复建待办");
            }
        }
        List<TodoService.TodoView> created = new ArrayList<>();
        for (ConfirmItem item : items) {
            TodoCandidate candidate = minutes.todoCandidates().get(item.index());
            created.add(todoService.create(familyId, minutes.memberId(), candidate.title(),
                    item.dueAt(), minutes.eventId()));
        }
        List<Integer> confirmed = new ArrayList<>(minutes.todoConfirmedIndexes());
        for (ConfirmItem item : items) {
            confirmed.add(item.index());
        }
        jdbc.update("update meeting_note set todo_confirmed_indexes = ? where id = ?",
                serializeIndexes(confirmed), minutesId);
        return new ConfirmResult(getById(minutesId), created);
    }

    public MinutesView findByEventId(long eventId) {
        List<MinutesView> rows = jdbc.query(
                "select id, family_id, member_id, event_id, title, content_md, todo_candidates, "
                        + "todo_confirmed_indexes, created_at from meeting_note where event_id = ?",
                (rs, i) -> mapRow(rs), eventId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public MinutesView getById(long id) {
        List<MinutesView> rows = jdbc.query(
                "select id, family_id, member_id, event_id, title, content_md, todo_candidates, "
                        + "todo_confirmed_indexes, created_at from meeting_note where id = ?",
                (rs, i) -> mapRow(rs), id);
        if (rows.isEmpty()) {
            throw ApiException.notFound("纪要不存在");
        }
        return rows.get(0);
    }

    /** 家庭内读纪要：跨家庭与不存在统一 404。 */
    public MinutesView getByIdForFamily(long id, long familyId) {
        List<MinutesView> rows = jdbc.query(
                "select id, family_id, member_id, event_id, title, content_md, todo_candidates, "
                        + "todo_confirmed_indexes, created_at from meeting_note where id = ? and family_id = ?",
                (rs, i) -> mapRow(rs), id, familyId);
        if (rows.isEmpty()) {
            throw ApiException.notFound("纪要不存在或不属于本家庭");
        }
        return rows.get(0);
    }

    /** 标题取纪要首行的一级标题（# ...）；模型没按格式给标题时用兜底标题，不让落表失败。 */
    private static String extractTitle(String contentMd, long eventId) {
        for (String line : contentMd.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.isBlank()) {
                continue;
            }
            if (trimmed.startsWith("# ")) {
                String title = trimmed.substring(2).trim();
                if (!title.isEmpty()) {
                    return title.length() > 255 ? title.substring(0, 255) : title;
                }
            }
            break;
        }
        return "会议纪要（事件 " + eventId + "）";
    }

    /** 模型抽取结果 → 候选列表：容忍代码围栏，逐项清洗（标题必填、线索可空、超长截断、数量封顶）。 */
    List<TodoCandidate> parseCandidates(String raw) {
        if (raw == null || raw.isBlank()) {
            throw ApiException.badRequest("待办抽取结果为空");
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
        JsonNode root;
        try {
            root = mapper.readTree(json);
        } catch (JsonProcessingException e) {
            throw ApiException.badRequest("待办抽取结果不是合法 JSON");
        }
        if (root == null || !root.isArray()) {
            throw ApiException.badRequest("待办抽取结果不是 JSON 数组");
        }
        List<TodoCandidate> candidates = new ArrayList<>();
        for (JsonNode node : root) {
            if (candidates.size() >= MAX_CANDIDATES) {
                break;
            }
            String title = textOrNull(node.get("title"));
            if (title == null) {
                continue;
            }
            candidates.add(new TodoCandidate(
                    title.length() > 255 ? title.substring(0, 255) : title,
                    capHint(textOrNull(node.get("ownerHint"))),
                    capHint(textOrNull(node.get("dueHint")))));
        }
        return candidates;
    }

    private static String textOrNull(JsonNode node) {
        if (node == null || !node.isTextual()) {
            return null;
        }
        String value = node.asText().trim();
        return value.isEmpty() ? null : value;
    }

    private static String capHint(String hint) {
        return hint != null && hint.length() > 100 ? hint.substring(0, 100) : hint;
    }

    private String serializeCandidates(List<TodoCandidate> candidates) {
        try {
            return mapper.writeValueAsString(candidates);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("待办候选序列化失败", e);
        }
    }

    private String serializeIndexes(List<Integer> indexes) {
        try {
            return mapper.writeValueAsString(indexes);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("已确认下标序列化失败", e);
        }
    }

    private List<TodoCandidate> readCandidates(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            List<TodoCandidate> list = mapper.readValue(json,
                    mapper.getTypeFactory().constructCollectionType(List.class, TodoCandidate.class));
            return list == null ? List.of() : list;
        } catch (JsonProcessingException e) {
            log.warn("meeting_note.todo_candidates 解析失败，按空处理：{}", e.getMessage());
            return List.of();
        }
    }

    private List<Integer> readIndexes(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            List<Integer> list = mapper.readValue(json,
                    mapper.getTypeFactory().constructCollectionType(List.class, Integer.class));
            return list == null ? List.of() : list;
        } catch (JsonProcessingException e) {
            log.warn("meeting_note.todo_confirmed_indexes 解析失败，按空处理：{}", e.getMessage());
            return List.of();
        }
    }

    private MinutesView mapRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new MinutesView(rs.getLong("id"), rs.getLong("family_id"), rs.getLong("member_id"),
                rs.getLong("event_id"), rs.getString("title"), rs.getString("content_md"),
                readCandidates(rs.getString("todo_candidates")),
                readIndexes(rs.getString("todo_confirmed_indexes")),
                rs.getString("created_at"));
    }
}
