package com.yuzhuo.filehub.dto;

import java.util.List;

/**
 * 上传任务状态。断点续传的客户端在「暂停后继续」「页面刷新后重连」
 * 「合并发现坏片」三种情况下都调它，以磁盘上真实存在的分片为准。
 *
 * @param uploadedIndexes 以磁盘为准的已落盘分片序号
 * @param done            是否已经是完成态
 */
public record UploadStatusResponse(
        String fileId,
        String fileName,
        long fileSize,
        int status,
        boolean done,
        int chunkSize,
        int chunkTotal,
        List<Integer> uploadedIndexes
) {
}
