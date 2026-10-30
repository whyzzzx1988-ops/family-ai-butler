package com.familybutler.core.proactive;

import com.familybutler.core.account.WechatNotifyService;
import com.familybutler.core.account.WechatProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 用药按点推送（第七周 N3）：每分钟扫一遍在册用药计划，detail_json 里 remindTime（HH:mm）
 * 与当前分钟一致的，给记录归属成员本人推一条微信订阅消息（不再只靠晨报那一段）。
 * 需要单独的用药模板（WECHAT_TEMPLATE_MEDICATION_ID），未配整体跳过；推送失败只记日志。
 * thing 型字段有 20 字上限，文案在此截断。归属成员未绑定微信时 sendToMember 会明示，不重试不打扰。
 */
@Component
public class MedicationReminderScheduler {

    private static final Logger log = LoggerFactory.getLogger(MedicationReminderScheduler.class);
    private static final Pattern REMIND_TIME = Pattern.compile("\"remindTime\"\\s*:\\s*\"([0-9]{2}:[0-9]{2})\"");
    private static final DateTimeFormatter HH_MM = DateTimeFormatter.ofPattern("HH:mm");

    private final JdbcTemplate jdbc;
    private final WechatNotifyService wechatNotifyService;
    private final WechatProperties wechatProperties;

    public MedicationReminderScheduler(JdbcTemplate jdbc, WechatNotifyService wechatNotifyService,
                                       WechatProperties wechatProperties) {
        this.jdbc = jdbc;
        this.wechatNotifyService = wechatNotifyService;
        this.wechatProperties = wechatProperties;
    }

    private record MedRow(long id, long memberId, String content, String detailJson) {
    }

    @Scheduled(cron = "${butler.health.medication-cron:0 * * * * *}", zone = "Asia/Shanghai")
    public void pushDueMedications() {
        String templateId = wechatProperties.getTemplateMedicationId();
        if (templateId == null || templateId.isBlank()) {
            return;
        }
        String now = LocalTime.now(BriefingService.ZONE).format(HH_MM);
        List<MedRow> rows = jdbc.query(
                "select id, member_id, content, detail_json from health_record "
                        + "where kind = 'medication' and detail_json is not null order by id",
                (rs, i) -> new MedRow(rs.getLong("id"), rs.getLong("member_id"),
                        rs.getString("content"), rs.getString("detail_json")));
        for (MedRow row : rows) {
            Matcher m = REMIND_TIME.matcher(row.detailJson());
            if (!m.find() || !now.equals(m.group(1))) {
                continue;
            }
            try {
                String thing = "该吃药了：" + row.content();
                if (thing.length() > 20) {
                    thing = thing.substring(0, 20);
                }
                WechatNotifyService.SendResult result = wechatNotifyService.sendToMember(
                        row.memberId(), templateId,
                        Map.of("thing1", thing, "date2", LocalDate.now(BriefingService.ZONE).toString()),
                        "pages/briefing/briefing");
                log.info("用药推送 记录 {} 成员 {}：ok={} {}",
                        row.id(), row.memberId(), result.ok(), result.message());
            } catch (Exception e) {
                log.warn("用药推送 记录 {} 处理失败", row.id(), e);
            }
        }
    }
}
