package com.familybutler.module.health;

import org.springframework.stereotype.Component;

/**
 * 健康模块挂载点。第六周 B1–B4 已落地（HealthService / HealthController / HealthInboxController）：
 * health_record 记录睡眠/用药/测量/备注，inbox 一键转入，简报睡眠段直查本表并附「今日用药」段。
 * 定位红线：只做管理与整理，不诊断、不开药、不走 LLM 解读；字段级加密在提审/上线前补。
 */
@Component
public class HealthModule {
}
