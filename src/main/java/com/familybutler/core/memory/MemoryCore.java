package com.familybutler.core.memory;

import org.springframework.stereotype.Component;

/**
 * 长期记忆（底座）：结构化记忆条目，按 member + scope 隔离。
 * 红线：每条记忆可查、可删，禁止只存向量不可见。D5 落地。
 */
@Component
public class MemoryCore {
    // TODO(D5): memory_item 入库 / 查询 / 删除
}
