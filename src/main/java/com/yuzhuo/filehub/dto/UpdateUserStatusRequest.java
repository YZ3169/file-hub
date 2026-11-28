package com.yuzhuo.filehub.dto;

import jakarta.validation.constraints.NotNull;

public record UpdateUserStatusRequest(
        @NotNull(message = "不能为空")
        Boolean enabled
) {
}
