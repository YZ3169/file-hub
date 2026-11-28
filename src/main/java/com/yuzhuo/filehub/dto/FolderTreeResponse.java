package com.yuzhuo.filehub.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 文件夹层级浏览响应。
 */
public record FolderTreeResponse(
        String currentDir,
        List<Breadcrumb> breadcrumbs,
        List<FolderItem> folders,
        List<FileVO> files
) {
    public record Breadcrumb(String name, String path) {
    }

    public record FolderItem(
            String name,
            String path,
            long fileCount,
            long totalBytes,
            LocalDateTime updateTime
    ) {
    }
}
