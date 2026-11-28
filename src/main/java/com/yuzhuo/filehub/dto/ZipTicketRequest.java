package com.yuzhuo.filehub.dto;

import jakarta.validation.constraints.Size;

import java.util.List;

public record ZipTicketRequest(
        @Size(max = 1000, message = "单次打包最多 1000 个文件")
        List<String> fileIds,

        @Size(max = 4096, message = "目录路径长度不能超过 4096")
        String folderPath
) {
}
