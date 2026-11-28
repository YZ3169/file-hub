package com.yuzhuo.filehub.dto;

import jakarta.validation.constraints.Size;

public record MoveFileRequest(
        @Size(max = 4096, message = "长度不能超过 4096")
        String targetDir,

        @Size(max = 255, message = "长度不能超过 255")
        String newName
) {
}
