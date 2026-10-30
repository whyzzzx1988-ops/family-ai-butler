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
import java.util.List;
import java.util.Map;

/**
 * 复习到期提醒（第七周 N1 起，N4 升级）：每晚 19:00（butler.learn.review-remind-cron，Asia/Shanghai）逐家庭检查——
 * 按孩子逐人统计今日到期未复习条数（口径同简报「今日待复习」：stage 未毕业且 D 当天结束前到期；
 * 直查 learn_record 的耦合取舍同 R3）：
 *   孩子本人绑了微信 → 直接推孩子（「你有 N 条待复习」）；
 *   户主 → 保留一条全家汇总（「孩子今日有 N 条待复习」，N 为全家孩子合计）。
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

    private record ChildDue(long memberId, long due) {
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
                List<ChildDue> perChild = jdbc.query(
                        "select lr.member_id as member_id, count(*) as c from learn_record lr "
                                + "join member m on m.id = lr.member_id "
                                + "where lr.family_id = ? and m.role = 'child' and lr.review_stage < 3 "
                                + "and (lr.next_review_at is null or lr.next_review_at < ?) "
                                + "group by lr.member_id",
                        (rs, i) -> new ChildDue(rs.getLong("member_id"), rs.getLong("c")),
                        familyId, endOfDay);
                long total = perChild.stream().mapToLong(ChildDue::due).sum();
                if (total == 0) {
                    continue;
                }
                for (ChildDue child : perChild) {
                    WechatNotifyService.SendResult r = wechatNotifyService.sendToMember(child.memberId(),
                            templateId,
                            Map.of("thing1", "你有" + child.due() + "条待复习", "date2", today.toString()),
                            "pages/learn/learn");
                    log.info("家庭 {} 复习提醒（孩子 {} 本人）：待复习 {} 条，ok={} {}",
                            familyId, child.memberId(), child.due(), r.ok(), r.message());
                }
                Long owner = briefingService.familyOwner(familyId);
                if (owner != null) {
                    WechatNotifyService.SendResult r = wechatNotifyService.sendToMember(owner, templateId,
                            Map.of("thing1", "孩子今日有" + total + "条待复习", "date2", today.toString()),
                            "pages/learn/learn");
                    log.info("家庭 {} 复习提醒（户主汇总）：待复习 {} 条，ok={} {}",
                            familyId, total, r.ok(), r.message());
                }
            } catch (Exception e) {
                log.warn("家庭 {} 复习提醒处理失败", familyId, e);
            }
        }
    }
}
