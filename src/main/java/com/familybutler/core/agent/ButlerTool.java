package com.familybutler.core.agent;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 管家工具（Agent 编排的插件单元）：业务模块以工具形式向 Agent 注册能力，
 * 思路平移自 DeepSeek Harness 的插件化——实现本接口并标注 @Component 即自动入册，
 * 注册表与执行器不感知具体业务。
 *
 * <p>约定：execute 返回可被 Jackson 序列化的对象（Map/List/基本类型）；
 * 业务校验失败时优先返回带 "error" 字段的 Map 让模型自行纠错，而不是抛异常中断整轮编排；
 * 数据访问必须走既有 Service，家庭隔离口径与 REST 接口保持一致（family_id 只从登录态来）。
 */
public interface ButlerTool {

    /** 工具上下文：family_id 与调用者成员 id，只从登录态反查，模型不可伪造。 */
    record ToolContext(long familyId, long memberId) {
    }

    /** 工具名（模型调用时使用，全册唯一，小写下划线）。 */
    String name();

    /** 一句话说明（进系统提示词的工具目录）。 */
    String description();

    /** 参数说明（如 {"title":"标题","dueAt":"到期时间，可空"}，无参数返回 "{}"）。 */
    String argsHint();

    /** 执行工具：args 为模型给出的 JSON 参数（可能缺字段，须自行容错）。 */
    Object execute(ToolContext ctx, JsonNode args);
}
