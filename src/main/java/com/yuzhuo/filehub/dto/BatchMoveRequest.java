package com.yuzhuo.filehub.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

public record BatchMoveRequest(
        @NotEmpty(message = "不能为空")
        @Size(max = 500, message = "单次最多移动 500 个文件")
        List<String> fileIds,

        @Size(max = 4096, message = "目标目录长度不能超过 4096")
        String targetDir
) {
}
