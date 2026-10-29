package com.familybutler.core.proactive;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

/**
 * 每日晨间简报定时任务：默认 07:00（butler.briefing.morning-cron，Asia/Shanghai）。
 * 逐家庭以户主为请求者口径生成；单家庭失败只记日志，不影响其他家庭。
 * 推送渠道（微信/站内）后续接，v0 先落表 + 接口可读。
 */
@Component
public class BriefingScheduler {

    private static final Logger log = LoggerFactory.getLogger(BriefingScheduler.class);

    private final BriefingService briefingService;

    public BriefingScheduler(BriefingService briefingService) {
        this.briefingService = briefingService;
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
            } catch (Exception e) {
                log.warn("家庭 {} 晨间简报生成失败", familyId, e);
            }
        }
    }
}
