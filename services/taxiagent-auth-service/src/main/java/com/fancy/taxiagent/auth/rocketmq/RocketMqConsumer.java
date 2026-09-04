package com.fancy.taxiagent.auth.rocketmq;

import com.fancy.taxiagent.auth.consumer.UserEventConsumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;

/**
 * 消费端生命周期包装。
 *
 * <p>与 Spring 容器一致：{@code afterPropertiesSet} 时在 {@code enabled=true} 下启动
 * {@link UserEventConsumer}，{@code destroy} 时关闭。不可用 {@code @Lazy}（懒加载单例
 * 不会被容器实例化，生产环境消费者永不启动）；测试上下文通过
 * {@code taxiagent.rocketmq.enabled=false}（默认值即 false）规避 MQ 连接。</p>
 */
@Component
public class RocketMqConsumer implements InitializingBean, DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(RocketMqConsumer.class);

    private final RocketMqProperties properties;
    private final UserEventConsumer userEventConsumer;

    public RocketMqConsumer(RocketMqProperties properties, UserEventConsumer userEventConsumer) {
        this.properties = properties;
        this.userEventConsumer = userEventConsumer;
    }

    @Override
    public void afterPropertiesSet() throws Exception {
        if (!properties.isEnabled()) {
            log.info("event=rocketmq_consumer_disabled");
            return;
        }
        userEventConsumer.start();
    }

    @Override
    public void destroy() {
        userEventConsumer.shutdown();
    }
}
