package com.yuzhuo.filehub.dto;

import jakarta.validation.constraints.Size;

public record ShareVerifyRequest(

        @Size(max = 16, message = "长度不能超过 16")
        String code
) {
}
