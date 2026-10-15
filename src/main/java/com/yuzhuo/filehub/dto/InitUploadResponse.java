package com.yuzhuo.filehub.dto;

import java.util.List;

/**
 * 初始化上传的结果。前端拿到后：
 * <ul>
 *   <li>{@code instant = true} —— 秒传命中，不用传了，直接拿 fileId 用；</li>
 *   <li>否则按 {@code chunkSize} 切片，<b>跳过 {@code uploadedIndexes} 里列出的序号</b>，
 *       这就是断点续传的客户端一侧，全部逻辑就是"别重传已经有了的片"。</li>
 * </ul>
 */
public record InitUploadResponse(
        String fileId,
        boolean instant,
        int chunkSize,
        int chunkTotal,
        long fileSize,
        List<Integer> uploadedIndexes
) {
}
