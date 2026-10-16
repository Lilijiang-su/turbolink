package com.turbolink.util;

/**
 * 数字 <-> 62 进制短码。
 * 字符集：0-9 a-z A-Z，共 62 位。
 * 将 10 位的 Long ID 压缩到 6-7 位短码，且天然无冲突。
 */
public final class Base62Util {

    private static final String CHARS =
            "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ";
    private static final int BASE = CHARS.length();

    /** 反向查找表：字符 ASCII 码 -> 数值，-1 表示非法字符，加速 decode 并拦截脏数据 */
    private static final int[] INDEX = new int[128];

    static {
        java.util.Arrays.fill(INDEX, -1);
        for (int i = 0; i < CHARS.length(); i++) {
            INDEX[CHARS.charAt(i)] = i;
        }
    }

    private Base62Util() {
    }

    /** 数字 -> 短码 */
    public static String encode(long num) {
        if (num < 0) {
            throw new IllegalArgumentException("num must be non-negative: " + num);
        }
        if (num == 0) {
            return String.valueOf(CHARS.charAt(0));
        }
        StringBuilder sb = new StringBuilder(11);
        while (num > 0) {
            int rem = (int) (num % BASE);
            sb.append(CHARS.charAt(rem));
            num /= BASE;
        }
        return sb.reverse().toString();
    }

    /** 短码 -> 数字，非法字符抛异常 */
    public static long decode(String code) {
        if (code == null || code.isEmpty()) {
            throw new IllegalArgumentException("code must not be empty");
        }
        long num = 0;
        for (int i = 0; i < code.length(); i++) {
            char c = code.charAt(i);
            if (c >= 128 || INDEX[c] < 0) {
                throw new IllegalArgumentException("illegal char in code: " + c);
            }
            int val = INDEX[c];
            num = num * BASE + val;
        }
        return num;
    }
}
