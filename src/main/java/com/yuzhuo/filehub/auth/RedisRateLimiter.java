package com.yuzhuo.filehub.auth;

import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RScript;
import org.redisson.api.RedissonClient;
import org.redisson.client.RedisException;
import org.redisson.client.codec.StringCodec;
import org.springframework.http.HttpStatus;

import java.util.List;

/**
 * 多实例共享的固定窗口限流。
 *
 * <p>和本地实现一样按「窗口编号」分 key：{@code filehub:rate:<key>:<windowId>}。
 * Lua 脚本把 INCR 和首次设置过期时间放在同一次 Redis 调用里，保证原子性；
 * 返回当前计数和剩余毫秒数。</p>
 *
 * <p>Redis 不可用时返回 HTTP 503，而不是静默放行：限流失效加上分布式锁失效，
 * 攻击者可以无限制刷登录接口。运维应该先恢复 Redis。</p>
 */
@Slf4j
public class RedisRateLimiter implements RateLimiter {

    private static final String LUA = """
            local current = redis.call('INCR', KEYS[1])
            if current == 1 then
              redis.call('PEXPIRE', KEYS[1], ARGV[1])
            end
            local ttl = redis.call('PTTL', KEYS[1])
            return {current, ttl}
            """;

    private final RedissonClient client;

    private final String keyPrefix;

    public RedisRateLimiter(RedissonClient client, String keyPrefix) {
        this.client = client;
        this.keyPrefix = keyPrefix;
    }

    @Override
    public Decision tryAcquire(String key, int limit, long windowSeconds) {
        if (limit <= 0 || windowSeconds <= 0) {
            return new Decision(true, 0);
        }
        long now = System.currentTimeMillis() / 1000;
        long windowId = now / windowSeconds;
        String redisKey = keyPrefix + "rate:" + key + ":" + windowId;
        // key 自带窗口编号，过期时间略长于窗口即可；旧窗口 key 自然消失，不需要定时清理
        long windowMillis = windowSeconds * 1000 + 1000;
        try {
            // 必须显式用 StringCodec：Redisson 默认 codec 会把 key/参数编码成二进制，
            // Lua 里的 INCR/PEXPIRE 看到的是带类型前缀的字节串，直接报
            // "ERR value is not an integer or out of range"。
            RScript script = client.getScript(StringCodec.INSTANCE);
            List<?> result = script.eval(RScript.Mode.READ_WRITE, LUA, RScript.ReturnType.MULTI,
                    List.<Object>of(redisKey), String.valueOf(windowMillis));
            if (result == null || result.size() < 2) {
                throw new RedisException("限流脚本返回为空");
            }
            long used = ((Number) result.get(0)).longValue();
            long ttlMillis = ((Number) result.get(1)).longValue();
            if (used <= limit) {
                return new Decision(true, 0);
            }
            long retryAfter = ttlMillis > 0 ? (ttlMillis + 999) / 1000 : windowSeconds;
            return new Decision(false, Math.max(1, retryAfter));
        } catch (Exception e) {
            log.error("Redis 限流不可用 key={}", key, e);
            throw new AuthException(50003, "限流服务暂时不可用，请稍后重试",
                    HttpStatus.SERVICE_UNAVAILABLE);
        }
    }
}
