package com.turbolink.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "turbolink-mq")
public class MqProperties {

    /** 访问事件 topic */
    private String accessTopic = "TURBOLINK_ACCESS_TOPIC";

    /** 访问事件消费者组 */
    private String accessConsumerGroup = "turbolink-access-consumer";
}
