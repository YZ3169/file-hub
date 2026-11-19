package com.yuzhuo.filehub.dto;

import java.time.LocalDateTime;

/**
 * 创建分享的响应。{@code code} 是明文提取码，只在创建/重置时返回一次。
 *
 * @param sharePath 相对路径，如 {@code /#/s/xxx}；前端用当前 origin 拼完整链接
 */
public record ShareCreatedResponse(String token,
                                   String code,
                                   String sharePath,
                                   LocalDateTime expiresAt,
                                   LocalDateTime createTime) {
}
