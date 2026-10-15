package com.yuzhuo.filehub.dto;

/**
 * 秒传预检结果。
 *
 * @param exists 命中了就 true，前端可直接用 fileId，一个字节都不用传
 * @param fileId 命中的文件标识；未命中为 null
 */
public record CheckResponse(boolean exists, String fileId) {
}
