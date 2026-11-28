package com.yuzhuo.filehub.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

public record BatchFileIdsRequest(
        @NotEmpty(message = "不能为空")
        @Size(max = 500, message = "单次最多操作 500 个文件")
        List<String> fileIds
) {
}
