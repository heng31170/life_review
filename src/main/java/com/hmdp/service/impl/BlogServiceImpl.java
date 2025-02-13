package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.IBlogService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IUserService;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.BLOG_LIKED_KEY;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {

    @Resource
    private IUserService userService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result queryHotBlog(Integer current) {
        // 根据用户查询
        Page<Blog> page = query()
                .orderByDesc("liked")
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 获取当前页数据
        List<Blog> records = page.getRecords();
        // 查询用户
        records.forEach(blog ->{
            queryBlogUser(blog);
            // 追加判断blog是否被当前用户点赞
            isBlogLiked(blog);
        });
        return Result.ok(records);
    }

    @Override
    public Result queryBlogById(Integer id) {
        // 查询blog
        Blog blog = getById(id);
        if(blog == null) {
            return Result.fail("笔记不存在！");
        }
        // 查询blog有关的用户
        queryBlogUser(blog);
        // 追加判断blog是否被当前用户点赞
        isBlogLiked(blog);
        return Result.ok(blog);
    }


    @Override
    public Result likeBlog(Long id) {
        // 1. 获取用户信息
        Long userId = UserHolder.getUser().getId();
        // 2. 如果未点赞，则点赞+1，同时将用户加入sortedset集合
        String key = BLOG_LIKED_KEY + id;
        // 尝试获取score
        Double score = stringRedisTemplate.opsForZSet().score(key,userId.toString());
        if(score == null) {
            // 点赞数+1
            boolean success = update().setSql("liked = liked + 1").eq("id",id).update();
            // 将用户加入sortedset集合
            if(success) {
                stringRedisTemplate.opsForZSet().add(key,userId.toString(),System.currentTimeMillis());
            }
        }
        // 3. 如果已经点赞过，则点赞取消，将用户从set集合中移除
        else {
            boolean success = update().setSql("liked = liked - 1").eq("id",id).update();
            if(success) {
                stringRedisTemplate.opsForZSet().remove(key,userId.toString());
            }
        }
        return Result.ok();
    }

    @Override
    public Result queryBlogLikes(Integer id) {
        String key = BLOG_LIKED_KEY + id;
        // zrang key 0 4 查询zset中前5个元素
        Set<String> top5 = stringRedisTemplate.opsForZSet().range(key,0, 4);
        if(top5 == null || top5.isEmpty()) {
            return Result.ok(Collections.emptyList());
        }
        List<Long> ids = top5.stream().map(Long::valueOf).collect(Collectors.toList());
        String idsStr = StrUtil.join(",",ids);
        // select * from tb_user where id in (ids[0],ids[1]...) order by field(id,ids[0],ids[1]...)
        List<UserDTO> userDTOS = userService.query().in("id",ids)
                .last("order by field(id," + idsStr + ")")
                .list().stream()
                .map(user -> BeanUtil.copyProperties(user,UserDTO.class))
                .collect(Collectors.toList());
        return Result.ok(userDTOS);
    }

    private void queryBlogUser(Blog blog) {
        Long userId = blog.getUserId();
        User user = userService.getById(userId);
        blog.setName(user.getNickName());
        blog.setIcon(user.getIcon());
    }
    private void isBlogLiked(Blog blog) {
        // 获取用户信息
        UserDTO userDTO = UserHolder.getUser();
        // 若用户未登录，直接返回
        if(userDTO == null) {
            return;
        }
        // 判断是否已点赞
        String key = BLOG_LIKED_KEY + blog.getId();
        Double score = stringRedisTemplate.opsForZSet().score(key,userDTO.getId().toString());
        blog.setIsLike(score != null);
    }

}
