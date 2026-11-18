package com.yuzhuo.filehub.dto;

import java.util.List;

/**
 * 完整性扫描结果。
 *
 * @param deep        是否做了内容哈希复算
 * @param checked     本次实际检查的 DONE 记录数（最多 limit 条）
 * @param brokenCount 发现问题的记录数
 * @param truncated   是否还有没检查到的记录（DONE 总数 &gt; checked）
 * @param items       有问题的记录
 */
public record IntegrityScanResponse(boolean deep, int checked, int brokenCount, boolean truncated,
                                    List<IntegrityItem> items) {

    /**
     * 一条损坏记录。同一物理文件被秒传共享时，每一条逻辑记录都会单独列出——
     * 修复时按行删除，最后一条才会触发物理文件清理。
     */
    public record IntegrityItem(String fileId, String fileName, Long ownerId, Long fileSize,
                                String reason) {
    }
}
