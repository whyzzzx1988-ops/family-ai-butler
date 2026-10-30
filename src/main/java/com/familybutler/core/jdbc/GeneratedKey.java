package com.familybutler.core.jdbc;

import org.springframework.jdbc.support.KeyHolder;

import java.util.Map;

/**
 * 从 KeyHolder 里取插入生成的单列主键 id。
 *
 * 不能直接用 KeyHolder.getKey()：它要求驱动只回一列，而 H2 在
 * Statement.RETURN_GENERATED_KEYS 下会把所有带默认值的列一起回传
 * （实测 family 表回 {id, created_at} 两项），getKey() 当场抛
 * 「The getKey method should only be used when a single key is returned」。
 * 这里走 getKeys() 按列名 id（忽略大小写，兼容 H2 的 DATABASE_TO_LOWER
 * 与 PG 的小写列名）取；总共只有一个条目时用它兜底；多条目且没有 id 列
 * 时返回 null，宁可让调用方按未取到主键处理，也不猜错列。
 */
public final class GeneratedKey {

    private GeneratedKey() {
    }

    /** 取名为 id 的生成键；没有可用键时返回 null（抛错还是降级由调用方决定）。 */
    public static Number id(KeyHolder keyHolder) {
        Map<String, Object> keys = keyHolder.getKeys();
        if (keys == null || keys.isEmpty()) {
            return null;
        }
        Object onlyValue = null;
        for (Map.Entry<String, Object> e : keys.entrySet()) {
            if (onlyValue == null) {
                onlyValue = e.getValue();
            }
            if (e.getKey() != null && e.getKey().equalsIgnoreCase("id")) {
                return asNumber(e.getValue());
            }
        }
        return keys.size() == 1 ? asNumber(onlyValue) : null;
    }

    private static Number asNumber(Object value) {
        return value instanceof Number number ? number : null;
    }
}
