package com.turbolink.mq;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.turbolink.config.MqProperties;
import com.turbolink.mapper.LinkAccessStatsMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.common.message.MessageExt;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

/**
 * 访问事件消费者：把跳转事件聚合成 PV 写入统计表。
 *
 * <p>消费端做成幂等友好：用 (short_code, date, hour) 唯一键 upsert，
 * 即便消息重投也只是多计一次，不会破坏结构。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@RocketMQMessageListener(
        topic = "${turbolink-mq.access-topic}",
        consumerGroup = "${turbolink-mq.access-consumer-group}"
)
public class AccessEventConsumer implements RocketMQListener<MessageExt> {

    private final LinkAccessStatsMapper statsMapper;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public void onMessage(MessageExt message) {
        try {
            String body = new String(message.getBody(), StandardCharsets.UTF_8);
            AccessEvent event = objectMapper.readValue(body, AccessEvent.class);

            Instant instant = Instant.ofEpochSecond(event.getTimestamp());
            LocalDate date = instant.atZone(ZoneId.systemDefault()).toLocalDate();
            int hour = instant.atZone(ZoneId.systemDefault()).getHour();

            statsMapper.upsertPv(event.getShortCode(), date, hour);
        } catch (Exception e) {
            log.warn("[访问事件] 消费失败 msgId={}", message.getMsgId(), e);
        }
    }
}
