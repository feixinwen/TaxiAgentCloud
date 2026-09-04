package com.fancy.taxiagent.auth.consumer;

import com.fancy.taxiagent.auth.rocketmq.RocketMqProperties;
import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyContext;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyStatus;
import org.apache.rocketmq.client.consumer.listener.MessageListenerConcurrently;
import org.apache.rocketmq.client.exception.MQClientException;
import org.apache.rocketmq.common.message.MessageExt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 用户领域事件消费端（DefaultMQPushConsumer 包装）。
 *
 * <p>订阅 {@code user-events} 全部 tag（tag 即事件类型），消息监听器解析 JSON 后委托
 * {@link UserEventProcessor} 处理：幂等去重 → 按类型撤销会话。返回 CONSUME_SUCCESS，
 * 失败场景由 processor 内部捕获并记日志后丢弃（ACK）。</p>
 *
 * <p>生命周期由 {@link com.fancy.taxiagent.auth.rocketmq.RocketMqConsumer} 包装管理
 * （afterPropertiesSet 启动 / destroy 关闭，受 {@code enabled} 开关控制）。</p>
 */
@Component
public class UserEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(UserEventConsumer.class);

    private final RocketMqProperties properties;
    private final UserEventProcessor processor;
    private DefaultMQPushConsumer consumer;

    public UserEventConsumer(RocketMqProperties properties, UserEventProcessor processor) {
        this.properties = properties;
        this.processor = processor;
    }

    public void start() throws MQClientException {
        DefaultMQPushConsumer pushConsumer = new DefaultMQPushConsumer(properties.getConsumerGroup());
        pushConsumer.setNamesrvAddr(properties.getNameServer());
        pushConsumer.subscribe(properties.getTopic(), "*");
        pushConsumer.registerMessageListener(new MessageListenerConcurrently() {
            @Override
            public ConsumeConcurrentlyStatus consumeMessage(List<MessageExt> msgs, ConsumeConcurrentlyContext context) {
                for (MessageExt msg : msgs) {
                    processor.process(new String(msg.getBody(), StandardCharsets.UTF_8));
                }
                return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
            }
        });
        pushConsumer.start();
        this.consumer = pushConsumer;
        log.info("event=rocketmq_consumer_started group={} topic={} namesrv={}",
                properties.getConsumerGroup(), properties.getTopic(), properties.getNameServer());
    }

    public void shutdown() {
        if (consumer != null) {
            consumer.shutdown();
        }
    }
}
