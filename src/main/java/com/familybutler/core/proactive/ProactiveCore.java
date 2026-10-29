package com.familybutler.core.proactive;

import org.springframework.stereotype.Component;

/**
 * 主动引擎（底座）：定时 + 事件触发，负责提醒、催办、简报、复盘。
 * 这是本产品与被动问答聊天机器人的根本分界。
 * D6 已落地晨间简报：BriefingService（规则拼装三段式）+ BriefingScheduler（07:00 定时）
 * + TodoService（底座待办，简报工作段与催办的数据源）。本类仅保留为包级说明锚点。
 */
@Component
public class ProactiveCore {
    // 实现见同包 BriefingService / BriefingScheduler / TodoService
}
