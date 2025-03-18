package com.zaizi.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.zaizi.dto.Result;
import com.zaizi.dto.ScrollResult;
import com.zaizi.dto.UserDTO;
import com.zaizi.entity.Blog;
import com.zaizi.entity.Follow;
import com.zaizi.entity.User;
import com.zaizi.mapper.BlogMapper;
import com.zaizi.service.IBlogService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.zaizi.service.IFollowService;
import com.zaizi.service.IUserService;
import com.zaizi.utils.SystemConstants;
import com.zaizi.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static com.zaizi.utils.RedisConstants.BLOG_LIKED_KEY;
import static com.zaizi.utils.RedisConstants.FEED_KEY;

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
    private IFollowService followService;
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

    @Override
    public Result saveBlog(Blog blog) {
        // 1.获取登录用户
        UserDTO user = UserHolder.getUser();
        blog.setUserId(user.getId());
        // 2.保存探店笔记
        boolean success = save(blog);
        if(!success) {
            return Result.fail("新增笔记失败！");
        }
        // 3.查询所有粉丝 select * from tb_follow where follow_user_id = ?
        LambdaQueryWrapper<Follow> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(Follow::getFollowUserId,user.getId());
        List<Follow> follows = followService.list(queryWrapper);
        // 4. 推送笔记id至所有粉丝
        for(Follow follow:follows) {
            // 4.1 获取粉丝id
            Long userId = follow.getUserId();
            // 4.2 推送
            String key = FEED_KEY + userId;
            stringRedisTemplate.opsForZSet().add(key,blog.getId().toString(),System.currentTimeMillis());
        }
        // 5. 返回id
        return Result.ok(blog.getId());
    }

    @Override
    public Result queryBlogOffFollow(Long max, Integer offset) {
        // 1. 获取当前用户
        Long userId = UserHolder.getUser().getId();
        // 2. 查询该用户收件箱 （key = 前缀 + 粉丝id）
        String key = FEED_KEY + userId;
        Set<ZSetOperations.TypedTuple<String>> typedTuples = stringRedisTemplate.opsForZSet()
                .reverseRangeByScoreWithScores(key,0,max,offset,2);
        // 3. 非空判断
        if(typedTuples == null || typedTuples.isEmpty()) {
            return Result.ok(Collections.emptyList());
        }
        // 4. 解析数据 blogId,minTime(时间戳),offset, 这里指定创建list的大小可略微提高效率，因为我们知道list就得是这么大
        ArrayList<Long> ids = new ArrayList<>();
        long minTime = 0;
        int os = 1;
        for(ZSetOperations.TypedTuple<String> typedTuple:typedTuples) {
            // 4.1 获取id
            String id = typedTuple.getValue();
            ids.add(Long.valueOf(id));
            // 4.2 获取score（时间戳）
            long time = typedTuple.getScore().longValue();
            if(time == minTime) {
                os++;
            } else {
                minTime = time;
                os = 1;
            }
        }
        // 解决SQL不能排序问题，手动指定排序为传入的ids
        String idsStr = StrUtil.join(",",ids);
        // 5. 根据id查询blog
        List<Blog> blogs = query().in("id",ids).last("order by field(id," + idsStr + ")").list();
        for(Blog blog:blogs) {
            // 5.1 查询发布该blog的用户信息
            queryBlogUser(blog);
            // 5.2 查询当前用户是否给该blog点过赞
            isBlogLiked(blog);
        }
        // 6. 封装并返回结果
        ScrollResult scrollResult = new ScrollResult();
        scrollResult.setList(blogs);
        scrollResult.setOffset(os);
        scrollResult.setMinTime(minTime);
        return Result.ok(scrollResult);
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
