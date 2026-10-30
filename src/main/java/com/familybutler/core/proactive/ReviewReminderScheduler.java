package com.familybutler.core.proactive;

import com.familybutler.core.account.WechatNotifyService;
import com.familybutler.core.account.WechatProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Map;

/**
 * 复习到期提醒（第七周 N1）：每晚 19:00（butler.learn.review-remind-cron，Asia/Shanghai）逐家庭检查——
 * 孩子名下有今日到期未复习的学习记录时，给户主推一条微信订阅消息（条数进口径与简报「今日待复习」一致：
 * stage 未毕业且 D 当天结束前到期；直查 learn_record 的耦合取舍同 R3）。
 * 模板未配则整体跳过；未绑定/未订阅只记日志。提醒只报事实条数，不评价学得好不好（学习定位红线）。
 */
@Component
public class ReviewReminderScheduler {

    private static final Logger log = LoggerFactory.getLogger(ReviewReminderScheduler.class);

    private final JdbcTemplate jdbc;
    private final BriefingService briefingService;
    private final WechatNotifyService wechatNotifyService;
    private final WechatProperties wechatProperties;

    public ReviewReminderScheduler(JdbcTemplate jdbc, BriefingService briefingService,
                                   WechatNotifyService wechatNotifyService, WechatProperties wechatProperties) {
        this.jdbc = jdbc;
        this.briefingService = briefingService;
        this.wechatNotifyService = wechatNotifyService;
        this.wechatProperties = wechatProperties;
    }

    @Scheduled(cron = "${butler.learn.review-remind-cron:0 0 19 * * *}", zone = "Asia/Shanghai")
    public void remindDueReviews() {
        String templateId = wechatProperties.getTemplateReviewId();
        if (templateId == null || templateId.isBlank()) {
            return;
        }
        LocalDate today = LocalDate.now(BriefingService.ZONE);
        OffsetDateTime endOfDay = today.plusDays(1).atStartOfDay(BriefingService.ZONE).toOffsetDateTime();
        for (Long familyId : briefingService.allFamilyIds()) {
            try {
                Long due = jdbc.queryForObject(
                        "select count(*) from learn_record lr join member m on m.id = lr.member_id "
                                + "where lr.family_id = ? and m.role = 'child' and lr.review_stage < 3 "
                                + "and (lr.next_review_at is null or lr.next_review_at < ?)",
                        Long.class, familyId, endOfDay);
                if (due == null || due == 0) {
                    continue;
                }
                Long owner = briefingService.familyOwner(familyId);
                if (owner == null) {
                    continue;
                }
                WechatNotifyService.SendResult result = wechatNotifyService.sendToMember(owner, templateId,
                        Map.of("thing1", "孩子今日有" + due + "条待复习", "date2", today.toString()),
                        "pages/learn/learn");
                log.info("家庭 {} 复习提醒微信推送：待复习 {} 条，ok={} {}",
                        familyId, due, result.ok(), result.message());
            } catch (Exception e) {
                log.warn("家庭 {} 复习提醒处理失败", familyId, e);
            }
        }
    }
}
