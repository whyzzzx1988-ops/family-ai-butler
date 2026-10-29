package com.familybutler.core.memory;

import org.springframework.stereotype.Component;

/**
 * 长期记忆（底座）：结构化条目 + 向量检索（pgvector）。
 * D5 已落地条目层：实现拆在 MemoryService + MemoryController（存/查/删 + visibility 隔离），
 * embedding 列与向量检索在需要语义召回时再接，不替代明文条目。
 * 本类仅保留为包级说明锚点。
 */
@Component
public class MemoryCore {
    // 实现见同包 MemoryService / MemoryController
}
