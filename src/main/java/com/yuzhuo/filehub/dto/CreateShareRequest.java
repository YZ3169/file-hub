package com.yuzhuo.filehub.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

/**
 * 创建分享。{@code expiresInDays} 只接受 0/1/7/30，0 表示永久；
 * {@code requireCode} 默认 true；{@code maxDownloads} null 表示不限制次数。
 */
public record CreateShareRequest(

        @NotBlank(message = "不能为空")
        String fileId,

        @Min(value = 0, message = "不能为负数")
        Integer expiresInDays,

        Boolean requireCode,

        @Min(value = 1, message = "至少为 1 次")
        Long maxDownloads
) {
    public CreateShareRequest(String fileId, Integer expiresInDays) {
        this(fileId, expiresInDays, Boolean.TRUE, null);
    }
}
