package com.fancy.taxiagent.user.rocketmq;

import org.apache.rocketmq.client.exception.MQBrokerException;
import org.apache.rocketmq.client.exception.MQClientException;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.client.producer.SendStatus;
import org.apache.rocketmq.common.message.Message;
import org.apache.rocketmq.remoting.exception.RemotingException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * RocketMQ DefaultMQProducer 的轻量包装。
 *
 * <p>生命周期与 Spring 容器一致：{@code enabled=false} 时不启动（用于无 MQ 的测试上下文）；
 * 发送失败直接抛异常，由调用方决定重试/保留语义。</p>
 */
@Component
public class RocketMqProducer implements InitializingBean, DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(RocketMqProducer.class);

    private final RocketMqProperties properties;
    private DefaultMQProducer producer;

    public RocketMqProducer(RocketMqProperties properties) {
        this.properties = properties;
    }

    @Override
    public void afterPropertiesSet() throws Exception {
        if (!properties.isEnabled()) {
            log.info("event=rocketmq_producer_disabled");
            return;
        }
        producer = new DefaultMQProducer(properties.getProducerGroup());
        producer.setNamesrvAddr(properties.getNameServer());
        producer.setSendMsgTimeout(3000);
        producer.start();
        log.info("event=rocketmq_producer_started namesrv={}", properties.getNameServer());
    }

    @Override
    public void destroy() {
        if (producer != null) {
            producer.shutdown();
        }
    }

    public boolean send(String topic, String tag, String key, String body) throws MQClientException, MQBrokerException, RemotingException, InterruptedException {
        if (producer == null) {
            throw new IllegalStateException("RocketMQ producer not started (disabled?)");
        }
        Message message = new Message(topic, tag, key, body.getBytes(StandardCharsets.UTF_8));
        SendResult result = producer.send(message);
        return result.getSendStatus() == SendStatus.SEND_OK;
    }
}
