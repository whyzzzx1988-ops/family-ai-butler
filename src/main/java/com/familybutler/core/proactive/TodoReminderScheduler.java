package com.familybutler.core.proactive;

import com.familybutler.core.account.WechatNotifyService;
import com.familybutler.core.account.WechatProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * 待办到期推送（第七周 N5）：每 10 分钟（butler.work.todo-remind-cron）扫一遍——
 * status=open、有 due_at、还没推过（due_notified_at 为空）、且到期点落在 [昨天此时, 30 分钟后]
 * 窗口内的待办，推给负责人本人（订阅消息）。推送成功才回填 due_notified_at 防重复；
 * 失败（多为未订阅）不回填，窗口内下轮还会再试，滑出窗口后由每日晨报继续兜底。
 * 需要单独的待办模板（WECHAT_TEMPLATE_TODO_ID），未配整体跳过。thing 文案 20 字截断。
 */
@Component
public class TodoReminderScheduler {

    private static final Logger log = LoggerFactory.getLogger(TodoReminderScheduler.class);

    private final JdbcTemplate jdbc;
    private final WechatNotifyService wechatNotifyService;
    private final WechatProperties wechatProperties;

    public TodoReminderScheduler(JdbcTemplate jdbc, WechatNotifyService wechatNotifyService,
                                 WechatProperties wechatProperties) {
        this.jdbc = jdbc;
        this.wechatNotifyService = wechatNotifyService;
        this.wechatProperties = wechatProperties;
    }

    private record DueTodo(long id, long ownerMemberId, String title, OffsetDateTime dueAt) {
    }

    @Scheduled(cron = "${butler.work.todo-remind-cron:0 */10 * * * *}", zone = "Asia/Shanghai")
    public void pushDueTodos() {
        String templateId = wechatProperties.getTemplateTodoId();
        if (templateId == null || templateId.isBlank()) {
            return;
        }
        OffsetDateTime now = OffsetDateTime.now(BriefingService.ZONE);
        List<DueTodo> rows = jdbc.query(
                "select id, owner_member_id, title, due_at from todo_task "
                        + "where status = 'open' and due_at is not null and due_notified_at is null "
                        + "and due_at <= ? and due_at >= ? order by due_at",
                (rs, i) -> new DueTodo(rs.getLong("id"), rs.getLong("owner_member_id"),
                        rs.getString("title"), rs.getObject("due_at", OffsetDateTime.class)),
                now.plusMinutes(30), now.minusDays(1));
        for (DueTodo todo : rows) {
            try {
                String thing = "待办到期：" + todo.title();
                if (thing.length() > 20) {
                    thing = thing.substring(0, 20);
                }
                String dueDate = todo.dueAt().atZoneSameInstant(BriefingService.ZONE)
                        .format(DateTimeFormatter.ISO_LOCAL_DATE);
                WechatNotifyService.SendResult result = wechatNotifyService.sendToMember(
                        todo.ownerMemberId(), templateId,
                        Map.of("thing1", thing, "date2", dueDate),
                        "pages/briefing/briefing");
                log.info("待办到期推送 待办 {} 负责人 {}：ok={} {}",
                        todo.id(), todo.ownerMemberId(), result.ok(), result.message());
                if (result.ok()) {
                    jdbc.update("update todo_task set due_notified_at = current_timestamp where id = ?",
                            todo.id());
                }
            } catch (Exception e) {
                log.warn("待办到期推送 待办 {} 处理失败", todo.id(), e);
            }
        }
    }
}
