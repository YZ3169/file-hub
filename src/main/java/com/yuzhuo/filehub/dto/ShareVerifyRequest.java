package com.yuzhuo.filehub.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ShareVerifyRequest(

        @NotBlank(message = "不能为空")
        @Size(max = 16, message = "长度不能超过 16")
        String code
) {
}
