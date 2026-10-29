package com.familybutler.core.proactive;

import org.springframework.stereotype.Component;

/**
 * 主动引擎（底座）：定时 + 事件触发，负责提醒、催办、简报、复盘。
 * 这是本产品与被动问答聊天机器人的根本分界。D6 先落地每日 07:00 晨间简报。
 */
@Component
public class ProactiveCore {
    // TODO(D6): 简报定时生成与推送
}
