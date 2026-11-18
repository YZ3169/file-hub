package com.yuzhuo.filehub.dto;

/**
 * 首页统计。logicalBytes 是「把所有逻辑文件各算一遍」，
 * physicalBytes 是「按 storage_path 去重后的真实占用」，
 * 两者之差就是秒传/合并去重替用户省下的空间。
 */
public record StatsResponse(
        long doneFiles,
        long uploadingFiles,
        long logicalBytes,
        long physicalBytes,
        long savedBytes
) {
}
