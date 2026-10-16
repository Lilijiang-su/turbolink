package com.turbolink.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class Base62UtilTest {

    @Test
    @DisplayName("encode 边界：0 和 1")
    void encodeBoundary() {
        assertEquals("0", Base62Util.encode(0));
        assertEquals("1", Base62Util.encode(1));
    }

    @Test
    @DisplayName("encode 已知值")
    void encodeKnown() {
        // 61 -> 'Z'（字符集最后一位）
        assertEquals("Z", Base62Util.encode(61));
        // 62 -> "10"
        assertEquals("10", Base62Util.encode(62));
    }

    @Test
    @DisplayName("decode 是 encode 的逆运算")
    void roundTrip() {
        long[] samples = {0, 1, 61, 62, 63, 100, 123456, 999999999999L, Long.MAX_VALUE};
        for (long n : samples) {
            String code = Base62Util.encode(n);
            assertEquals(n, Base62Util.decode(code), "往返失败: " + n);
        }
    }

    @Test
    @DisplayName("连续区间编码无冲突，且长度符合预期")
    void uniqueness() {
        Set<String> seen = new HashSet<>();
        for (long i = 0; i < 100000; i++) {
            String code = Base62Util.encode(i);
            assertTrue(seen.add(code), "短码冲突: " + code);
        }
        // 100000 在 62^3(=238328) 内，故长度应 <= 3
        assertTrue(Base62Util.encode(99999).length() <= 3);
    }

    @Test
    @DisplayName("decode 非法输入抛异常")
    void decodeIllegal() {
        assertThrows(IllegalArgumentException.class, () -> Base62Util.decode(""));
        assertThrows(IllegalArgumentException.class, () -> Base62Util.decode(null));
        assertThrows(IllegalArgumentException.class, () -> Base62Util.decode("a-b"));
    }

    @Test
    @DisplayName("encode 负数抛异常")
    void encodeNegative() {
        assertThrows(IllegalArgumentException.class, () -> Base62Util.encode(-1));
    }
}
