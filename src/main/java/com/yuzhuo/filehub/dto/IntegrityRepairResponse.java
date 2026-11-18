package com.yuzhuo.filehub.dto;

import java.util.List;

/**
 * 修复结果。只有「重新确认确实损坏」的记录才会被删除；
 * 如果复检发现文件已恢复（例如手工放回了物理文件），会保留记录并在 reason 里说明。
 */
public record IntegrityRepairResponse(int repaired, int failed, List<Item> items) {

    public record Item(String fileId, boolean repaired, String reason) {
    }
}
