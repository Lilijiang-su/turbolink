package com.turbolink.mq;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.turbolink.config.MqProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.producer.SendCallback;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.stereotype.Component;

/**
 * 访问事件生产者。
 *
 * <p>关键点：用 <b>异步发送</b>（asyncSend），发送动作不阻塞跳转主链路，
 * 302 立即返回，MQ 的耗时（含网络往返）在独立线程池完成。
 * 这是「异步解耦」相对「同步写统计表」的核心收益。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AccessEventProducer {

    private final RocketMQTemplate rocketMQTemplate;
    private final MqProperties mqProperties;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public void sendAccess(String shortCode) {
        AccessEvent event = new AccessEvent(shortCode, System.currentTimeMillis() / 1000);
        try {
            String payload = objectMapper.writeValueAsString(event);
            rocketMQTemplate.asyncSend(
                    mqProperties.getAccessTopic(),
                    payload,
                    new SendCallback() {
                        @Override
                        public void onSuccess(SendResult sendResult) {
                            // 正常路径静默
                        }

                        @Override
                        public void onException(Throwable e) {
                            log.warn("[访问事件] 发送失败: {}", shortCode, e);
                        }
                    });
        } catch (Exception e) {
            // 统计是旁路，绝不能因它影响跳转主流程
            log.warn("[访问事件] 序列化/发送异常: {}", shortCode, e);
        }
    }
}
