package com.familybutler.core.proactive;

import com.familybutler.core.account.WechatNotifyService;
import com.familybutler.core.account.WechatProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.Map;

/**
 * 每日晨间简报定时任务：默认 07:00（butler.briefing.morning-cron，Asia/Shanghai）。
 * 逐家庭以户主为请求者口径生成；单家庭失败只记日志，不影响其他家庭。
 * 生成成功后尝试微信订阅消息推送（第七周 N1 接第五周 A3 的触发点）：模板未配则跳过，
 * 未绑定/未订阅/次数用完只记日志——推送是尽力而为，绝不打断简报生成与落表。
 */
@Component
public class BriefingScheduler {

    private static final Logger log = LoggerFactory.getLogger(BriefingScheduler.class);

    private final BriefingService briefingService;
    private final WechatNotifyService wechatNotifyService;
    private final WechatProperties wechatProperties;

    public BriefingScheduler(BriefingService briefingService, WechatNotifyService wechatNotifyService,
                             WechatProperties wechatProperties) {
        this.briefingService = briefingService;
        this.wechatNotifyService = wechatNotifyService;
        this.wechatProperties = wechatProperties;
    }

    @Scheduled(cron = "${butler.briefing.morning-cron:0 0 7 * * *}", zone = "Asia/Shanghai")
    public void generateMorningBriefings() {
        LocalDate today = LocalDate.now(BriefingService.ZONE);
        for (Long familyId : briefingService.allFamilyIds()) {
            try {
                Long owner = briefingService.familyOwner(familyId);
                if (owner == null) {
                    log.warn("家庭 {} 无户主成员，跳过晨间简报", familyId);
                    continue;
                }
                briefingService.generate(familyId, owner, today, "morning");
                log.info("家庭 {} 晨间简报已生成（{}）", familyId, today);
                notifyOwner(familyId, owner, today, "今日全家简报已生成");
            } catch (Exception e) {
                log.warn("家庭 {} 晨间简报生成失败", familyId, e);
            }
        }
    }

    /** 周报定时任务（第七周 N3）：周一 07:30 生成 type=weekly（覆盖上周一至周日），生成后同样尝试推送。 */
    @Scheduled(cron = "${butler.briefing.weekly-cron:0 30 7 * * MON}", zone = "Asia/Shanghai")
    public void generateWeeklyBriefings() {
        LocalDate today = LocalDate.now(BriefingService.ZONE);
        for (Long familyId : briefingService.allFamilyIds()) {
            try {
                Long owner = briefingService.familyOwner(familyId);
                if (owner == null) {
                    continue;
                }
                briefingService.generate(familyId, owner, today, "weekly");
                log.info("家庭 {} 全家周报已生成（{}）", familyId, today);
                notifyOwner(familyId, owner, today, "上周全家周报已生成");
            } catch (Exception e) {
                log.warn("家庭 {} 全家周报生成失败", familyId, e);
            }
        }
    }

    private void notifyOwner(long familyId, long owner, LocalDate today, String thing) {
        String templateId = wechatProperties.getTemplateBriefingId();
        if (templateId == null || templateId.isBlank()) {
            return;
        }
        try {
            WechatNotifyService.SendResult result = wechatNotifyService.sendToMember(owner, templateId,
                    Map.of("thing1", thing, "date2", today.toString()),
                    "pages/briefing/briefing");
            log.info("家庭 {} 简报微信推送：ok={} {}", familyId, result.ok(), result.message());
        } catch (Exception e) {
            log.warn("家庭 {} 简报微信推送异常（不影响简报）", familyId, e);
        }
    }
}
