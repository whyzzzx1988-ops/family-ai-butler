package com.familybutler.module.work;

import org.springframework.stereotype.Component;

/**
 * 工作模块挂载点。第二周 W1 纪要生成、W2 待办候选抽取与确认流、W4 纪要列表/详情已落地
 * （MinutesService / MinutesController），工作模块 P0「录音 → 纪要 + 待办」竖井打通。
 * 只能通过底座的记忆 / 提醒 / 模型网关接口协作，不直查其他模块的表；本模块只直写自己的表。
 */
@Component
public class WorkModule {
}
