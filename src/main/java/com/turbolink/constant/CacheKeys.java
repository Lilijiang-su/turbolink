package com.turbolink.constant;

/**
 * Redis Key 统一定义，避免散落各处的字符串拼接。
 */
public final class CacheKeys {

    private CacheKeys() {
    }

    /** 短码 -> 长链缓存 */
    public static String url(String shortCode) {
        return "shortlink:url:" + shortCode;
    }

    /** 空值缓存，防同一无效短码反复穿透 */
    public static String nullValue(String shortCode) {
        return "shortlink:null:" + shortCode;
    }

    /** 缓存击穿互斥锁 */
    public static String lock(String shortCode) {
        return "shortlink:lock:" + shortCode;
    }

    /** 空值缓存占位符 */
    public static final String NULL_PLACEHOLDER = "";
}
