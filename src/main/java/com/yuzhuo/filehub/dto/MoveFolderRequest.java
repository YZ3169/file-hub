package com.yuzhuo.filehub.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record MoveFolderRequest(
        @NotBlank(message = "不能为空")
        @Size(max = 4096, message = "长度不能超过 4096")
        String sourcePath,

        @NotBlank(message = "不能为空")
        @Size(max = 4096, message = "长度不能超过 4096")
        String targetPath
) {
}
