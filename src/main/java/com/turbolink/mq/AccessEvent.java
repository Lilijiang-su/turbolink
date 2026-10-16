package com.turbolink.mq;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 短链访问事件，跳转时投递到 MQ 异步消费。
 * 只带必要字段，序列化开销小。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AccessEvent implements Serializable {

    private String shortCode;

    /** 访问时间戳（秒），消费者据此归到日期/小时 */
    private long timestamp;
}
