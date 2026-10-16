package com.turbolink.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import static org.junit.jupiter.api.Assertions.*;

class ShardingUtilTest {

    @Test
    @DisplayName("旧路由：库/表下标始终在合法范围，负数哈希不会产生负下标")
    void indexInRange() {
        String[] samples = {"0", "a", "Z", "abcDEF", "zzz", "0000001", "中文", "-negative"};
        for (String code : samples) {
            assertTrue(ShardingUtil.dbIndex(code) >= 0 && ShardingUtil.dbIndex(code) < 2,
                    "旧库下标越界: " + code);
            assertTrue(ShardingUtil.tableIndex(code) >= 0 && ShardingUtil.tableIndex(code) < 4,
                    "旧表下标越界: " + code);
        }
    }

    @Test
    @DisplayName("新路由：库/表下标始终在合法范围（4库4表）")
    void newIndexInRange() {
        String[] samples = {"0", "a", "Z", "abcDEF", "zzz", "0000001", "中文", "-negative"};
        for (String code : samples) {
            assertTrue(ShardingUtil.newDbIndex(code) >= 0 && ShardingUtil.newDbIndex(code) < 4,
                    "新库下标越界: " + code);
            assertTrue(ShardingUtil.newTableIndex(code) >= 0 && ShardingUtil.newTableIndex(code) < 4,
                    "新表下标越界: " + code);
        }
    }

    @Test
    @DisplayName("同一短码路由恒定")
    void stableRouting() {
        String code = "aB3xK9";
        assertEquals(ShardingUtil.dbIndex(code), ShardingUtil.dbIndex(code));
        assertEquals(ShardingUtil.newDbIndex(code), ShardingUtil.newDbIndex(code));
    }

    @Test
    @DisplayName("扩容：约一半数据需要搬库（统计上 2→4 库）")
    void migrationRatio() {
        int needMigration = 0;
        int total = 10000;
        for (long i = 1; i <= total; i++) {
            String code = Base62Util.encode(i);
            if (ShardingUtil.needsMigration(code)) {
                needMigration++;
            }
        }
        // 2 库扩到 4 库，理论上约 50% 数据需要搬库，允许 ±5% 波动
        double ratio = (double) needMigration / total;
        assertTrue(ratio > 0.45 && ratio < 0.55,
                "搬库比例异常: " + ratio);
    }

    @Test
    @DisplayName("needsMigration 判断正确：旧库下标 != 新库下标")
    void needsMigrationLogic() {
        for (long i = 1; i <= 1000; i++) {
            String code = Base62Util.encode(i);
            assertEquals(ShardingUtil.dbIndex(code) != ShardingUtil.newDbIndex(code),
                    ShardingUtil.needsMigration(code),
                    "needsMigration 逻辑错误: " + code);
        }
    }
}
