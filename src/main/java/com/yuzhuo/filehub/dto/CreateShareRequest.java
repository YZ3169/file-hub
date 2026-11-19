package com.yuzhuo.filehub.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

/**
 * 创建分享。{@code expiresInDays} 只接受 0/1/7/30，0 表示永久；
 * 不传按默认 7 天处理，具体校验在 {@code ShareService}。
 */
public record CreateShareRequest(

        @NotBlank(message = "不能为空")
        String fileId,

        @Min(value = 0, message = "不能为负数")
        Integer expiresInDays
) {
}
