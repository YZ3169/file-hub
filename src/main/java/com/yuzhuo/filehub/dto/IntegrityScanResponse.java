package com.yuzhuo.filehub.dto;

import java.util.List;

/**
 * 完整性扫描结果。
 *
 * @param deep        是否做了内容哈希复算
 * @param checked     本次实际检查的 DONE 记录数（最多 limit 条）
 * @param brokenCount 确认损坏的记录数（不含暂时无法验证的）
 * @param truncated   是否还有没检查到的记录（DONE 总数 &gt; checked）
 * @param items       有问题的记录（确认损坏 + 暂时无法验证）
 * @param unverifiableCount 因存储读取故障（超时、权限、网络等）暂时无法验证的记录数
 */
public record IntegrityScanResponse(boolean deep, int checked, int brokenCount, boolean truncated,
                                    List<IntegrityItem> items, int unverifiableCount) {

    /** 确认损坏：物理文件不存在、大小不符或内容哈希不一致，可以修复 */
    public static final String VERDICT_CORRUPT = "CORRUPT";
    /** 暂时无法验证：读取存储失败，数据保留，稍后重试 */
    public static final String VERDICT_UNVERIFIABLE = "UNVERIFIABLE";

    /**
     * 一条问题记录。同一物理文件被秒传共享时，每一条逻辑记录都会单独列出——
     * 修复时按行删除，最后一条才会触发物理文件清理。
     *
     * @param verdict {@link #VERDICT_CORRUPT} 或 {@link #VERDICT_UNVERIFIABLE}
     */
    public record IntegrityItem(String fileId, String fileName, Long ownerId, Long fileSize,
                                String reason, String verdict) {
    }
}
