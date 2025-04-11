package com.zaizi.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.zaizi.dto.Result;
import com.zaizi.entity.VoucherOrder;
import com.zaizi.mapper.VoucherOrderMapper;
import com.zaizi.service.ISeckillVoucherService;
import com.zaizi.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.zaizi.utils.RedisIdWorker;
import com.zaizi.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.aop.framework.AopContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.time.Duration;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Slf4j
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;
    @Resource
    private RedisIdWorker redisIdWorker;
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private RedissonClient redissonClient;
    @Resource
    private RabbitTemplate rabbitTemplate;

    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;
    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    // 定义队列和交换机名称
    private static final String  SECKILL_QUEUE = "seckill.queue";
    private static final String  SECKILL_EXCHANGE = "seckill.exchange";
    private static final String  SECKILL_ROUTING_KEY = "seckill.order";

    IVoucherOrderService proxy;

    // RabbitMQ 消息队列实现秒杀
    @Override
    public Result seckillVoucher(Long voucherId) {
        // 获取用户
        Long userId = UserHolder.getUser().getId();
        // 获取订单
        long orderId = redisIdWorker.nextId("order");
        // 1. 执行lua脚本
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(),userId.toString(),String.valueOf(orderId)
        );
        // 2。 判断结果是否为0
        int r = result.intValue();
        if(r != 0) {
            // 2.1 不为0 无购买资格
            return Result.fail(r == 1 ? "库存不足" : "不能重复下单");
        }
        // 3. 创建订单对象
        VoucherOrder voucherOrder = new VoucherOrder();
        voucherOrder.setId(orderId);
        voucherOrder.setVoucherId(voucherId);
        voucherOrder.setUserId(userId);
        // 4. 发送消息到 RabbitMQ
        rabbitTemplate.convertAndSend(SECKILL_EXCHANGE, SECKILL_ROUTING_KEY, voucherOrder);
        // 5. 获取代理对象
        proxy = (IVoucherOrderService) AopContext.currentProxy();
        // 6. 返回订单id
        return Result.ok(orderId);
    }

    // RabbitMQ 监听器
    @RabbitListener(queues = SECKILL_QUEUE)
    public void receiveSeckillOrder(VoucherOrder voucherOrder) {
        try {
            handleVoucherOrder(voucherOrder);
        } catch (Exception e) {
            log.error("秒杀处理订单异常", e);
            throw new RuntimeException(e);
        }
    }

    private void handleVoucherOrder(VoucherOrder voucherOrder) {
        Long userId = voucherOrder.getUserId();
        // 创建锁🔒对象
        RLock lock = redissonClient.getLock("lock:order:" + userId);
        // 获取锁🔒
        boolean isLock = lock.tryLock();
        // 获取锁🔒失败
        if(!isLock) {
            log.error("不允许重复下单！");
            return;
        }
        try {
            // 获取代理对象（事务）
            proxy.createVoucherOrder(voucherOrder);
        } finally {
            // 释放锁
            lock.unlock();
        }
    }

    @Transactional
    public void createVoucherOrder(VoucherOrder voucherOrder) {
        // * 一人一单逻辑
        Long userId = voucherOrder.getUserId();
        Long count = query().eq("voucher_id", voucherOrder)
                .eq("user_id", userId).count();
        if (count > 0) {
            log.error("你已经抢过优惠券了哦");
            return;
        }
        // 5. 扣减库存
        boolean success = seckillVoucherService.update()
                .setSql("stock = stock - 1")
                .eq("voucher_id", voucherOrder.getVoucherId())
                .gt("stock", 0)
                .update();
        if (!success) {
            log.error("库存不足！");
            return;
        }
        // 6. 创建订单
        save(voucherOrder);

    }
}
