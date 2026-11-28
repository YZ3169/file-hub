package com.yuzhuo.filehub.dto;

import jakarta.validation.constraints.Size;

public record ShareSaveRequest(
        @Size(max = 16, message = "长度不能超过 16")
        String code,

        @Size(max = 256, message = "票据过长")
        String ticket
) {
}
