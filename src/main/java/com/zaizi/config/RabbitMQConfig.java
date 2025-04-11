package com.zaizi.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitMQConfig {

    // 秒杀订单队列
    @Bean
    public Queue seckillQueue() {
        return new Queue("seckill.queue", true);
    }

    // 秒杀交换机
    @Bean
    public DirectExchange seckillExchange() {
        return new DirectExchange("seckill.exchange");
    }

    // 绑定队列到交换机
    @Bean
    public Binding seckillBinding() {
        return BindingBuilder.bind(seckillQueue())
                .to(seckillExchange())
                .with("seckill.order");
    }
}