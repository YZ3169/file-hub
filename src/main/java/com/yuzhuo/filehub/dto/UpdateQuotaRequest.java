package com.yuzhuo.filehub.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record UpdateQuotaRequest(
        @NotNull(message = "不能为空")
        @Min(value = -1, message = "不能小于 -1（-1 表示不限制）")
        Long quotaBytes
) {
}
