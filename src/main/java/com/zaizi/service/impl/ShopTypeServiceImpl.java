package com.zaizi.service.impl;

import cn.hutool.json.JSONUtil;
import com.zaizi.dto.Result;
import com.zaizi.entity.ShopType;
import com.zaizi.mapper.ShopTypeMapper;
import com.zaizi.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.List;

import static com.zaizi.utils.RedisConstants.CACHE_SHOP_TYPE_KEY;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {

    @Resource
    StringRedisTemplate stringRedisTemplate;

    @Override
    public Result queryType() {
        // 先从redis中查询
        List<String> shopTypes = stringRedisTemplate.opsForList().range(CACHE_SHOP_TYPE_KEY,0,-1);
        // 若不为空，则转为ShopType并返回
        if(!shopTypes.isEmpty()) {
            List<ShopType> tmp = new ArrayList<>();
            for(String types: shopTypes) {
                ShopType shopType = JSONUtil.toBean(types,ShopType.class);
                tmp.add(shopType);
            }
            return Result.ok(tmp);
        }
        // 否则去数据库中查询
        List<ShopType> tmp = query().orderByAsc("sort").list();
        if(tmp == null) {
            return Result.fail("店铺类型不存在！");
        }
        // 查到了转为json字符串，存入redis
        for(ShopType shopType:tmp) {
            String shopTypeJson = JSONUtil.toJsonStr(shopType);
            shopTypes.add(shopTypeJson);
        }
        stringRedisTemplate.opsForList().leftPushAll(CACHE_SHOP_TYPE_KEY,shopTypes);

        return Result.ok(tmp);
    }
}
