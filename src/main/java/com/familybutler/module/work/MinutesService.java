package com.familybutler.module.work;

import com.familybutler.core.account.ApiException;
import com.familybutler.core.inbox.InboxService;
import com.familybutler.core.llm.LlmGateway;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;

/**
 * 会议纪要生成（第二周 W1）：工作模块第一条业务竖井。
 * 数据只通过底座接口拿：事件经 InboxService 读（不直查 inbox_event），
 * 生成经 LlmGateway 走（module=work，每次调用落 llm_call_log）；
 * 本服务只直写自己模块的 meeting_note 表。
 *
 * 隔离与防呆口径（沿用底座）：
 *   事件不存在或不属于本家庭 → 统一 404，不泄露其他家庭事件是否存在；
 *   事件转写文本为空 → 400 明示（语音未转写完/转写未配置时先补转写，别拿空文本编纪要）；
 *   同一事件已生成过纪要 → 409（event_id 唯一，一对一溯源，且不白花一次模型调用）。
 */
@Service
public class MinutesService {

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

    private final JdbcTemplate jdbc;
    private final InboxService inboxService;
    private final LlmGateway llmGateway;

    public MinutesService(JdbcTemplate jdbc, InboxService inboxService, LlmGateway llmGateway) {
        this.jdbc = jdbc;
        this.inboxService = inboxService;
        this.llmGateway = llmGateway;
    }

    public record MinutesView(long id, long familyId, long memberId, long eventId,
                              String title, String contentMd, String createdAt) {
    }

    public record GenerateResult(MinutesView minutes, long llmLogId, String model,
                                 int tokensIn, int tokensOut, BigDecimal cost) {
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

        LlmGateway.ChatResult result = llmGateway.chat(familyId, "work", SYSTEM_PROMPT,
                "会议转写文本如下：\n\n" + event.text().trim());
        String contentMd = result.content() == null ? "" : result.content().trim();
        if (contentMd.isBlank()) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "模型返回的纪要为空，未落表");
        }
        String title = extractTitle(contentMd, eventId);

        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "insert into meeting_note(family_id, member_id, event_id, title, content_md) "
                            + "values (?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, familyId);
            ps.setLong(2, memberId);
            ps.setLong(3, eventId);
            ps.setString(4, title);
            ps.setString(5, contentMd);
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("meeting_note 插入未返回主键");
        }
        return new GenerateResult(getById(key.longValue()), result.logId(), result.model(),
                result.tokensIn(), result.tokensOut(), result.cost());
    }

    public MinutesView findByEventId(long eventId) {
        List<MinutesView> rows = jdbc.query(
                "select id, family_id, member_id, event_id, title, content_md, created_at "
                        + "from meeting_note where event_id = ?",
                MinutesService::mapRow, eventId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public MinutesView getById(long id) {
        List<MinutesView> rows = jdbc.query(
                "select id, family_id, member_id, event_id, title, content_md, created_at "
                        + "from meeting_note where id = ?",
                MinutesService::mapRow, id);
        if (rows.isEmpty()) {
            throw ApiException.notFound("纪要不存在");
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

    private static MinutesView mapRow(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        return new MinutesView(rs.getLong("id"), rs.getLong("family_id"), rs.getLong("member_id"),
                rs.getLong("event_id"), rs.getString("title"), rs.getString("content_md"),
                rs.getString("created_at"));
    }
}
