-- 比较线程标示与锁中的标示是否一致  KEYS[1] 传入锁的key  ARGV[1] 线程id
if(redis.call('get', KEYS[1]) == ARGV[1]) then
    -- 释放锁 del key
    return redis.call('del', KEYS[1])
end
return 0