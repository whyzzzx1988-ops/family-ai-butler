package com.familybutler.core.llm;

import org.springframework.stereotype.Component;

/**
 * 模型网关（底座）：DeepSeek 主力，Qwen / GLM 可切换。
 * 每次调用必须落 llm_call_log，成本从第一天可算账。
 * 底座期只做透明报错，不做多商回退策略。
 */
@Component
public class LlmGateway {
    // TODO: 统一 chat 接口 + 调用日志落库
}
