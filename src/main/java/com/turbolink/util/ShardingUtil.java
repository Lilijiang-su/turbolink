package com.turbolink.util;

/**
 * 分库分表路由算法（参考实现）。
 *
 * <p>实际生产路由由 ShardingSphere-JDBC 自动完成，见 {@code sharding-datasource.yml}。
 * 本类仅作为算法文档/单测参考，二者必须保持一致：
 * <ul>
 *   <li>库：{@code (hashCode & 0x7fffffff) % 2}</li>
 *   <li>表：{@code ((hashCode & 0x7fffffff) / 2) % 4}</li>
 * </ul>
 *
 * <p>注意 {@code & 0x7fffffff} 用于消除负数哈希（等价于 abs，但能正确处理
 * Integer.MIN_VALUE 的边界），否则负数取模会得到负下标——常见 bug。
 */
public final class ShardingUtil {

    private static final int OLD_DB_COUNT = 2;
    private static final int NEW_DB_COUNT = 4;
    private static final int OLD_TB_COUNT = 4;
    private static final int NEW_TB_COUNT = 4;

    private ShardingUtil() {
    }

    /** 消负数哈希：正确处理 Integer.MIN_VALUE 边界 */
    private static int hash(String shortCode) {
        return shortCode.hashCode() & 0x7fffffff;
    }

    // ---------- 旧路由（2 库 × 4 表）----------

    public static int dbIndex(String shortCode) {
        return hash(shortCode) % OLD_DB_COUNT;
    }

    public static int tableIndex(String shortCode) {
        return (hash(shortCode) / OLD_DB_COUNT) % OLD_TB_COUNT;
    }

    // ---------- 新路由（4 库 × 4 表，扩容后）----------

    public static int newDbIndex(String shortCode) {
        return hash(shortCode) % NEW_DB_COUNT;
    }

    public static int newTableIndex(String shortCode) {
        return (hash(shortCode) / NEW_DB_COUNT) % NEW_TB_COUNT;
    }

    /** 判断某短码扩容后是否需要搬库（旧库下标 != 新库下标） */
    public static boolean needsMigration(String shortCode) {
        return dbIndex(shortCode) != newDbIndex(shortCode);
    }
}

