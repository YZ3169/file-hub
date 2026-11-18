package com.yuzhuo.filehub.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.yuzhuo.filehub.auth.UserPrincipal;
import com.yuzhuo.filehub.dto.IntegrityRepairRequest;
import com.yuzhuo.filehub.dto.IntegrityRepairResponse;
import com.yuzhuo.filehub.dto.IntegrityScanResponse;
import com.yuzhuo.filehub.entity.FileInfo;
import com.yuzhuo.filehub.mapper.FileInfoMapper;
import com.yuzhuo.filehub.storage.StorageBackend;
import com.yuzhuo.filehub.util.HashUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 管理员完整性检查：
 * <ul>
 *   <li>浅扫描只看「文件在不在、大小对不对」，一次 stat 就能判断，适合大范围巡检；</li>
 *   <li>深扫描额外流式复算 SHA-256，能发现「大小没变但内容被改坏」的静默损坏，
 *       代价是要把所有字节读一遍。</li>
 * </ul>
 * 秒传会让多条逻辑记录共享同一个 storage_path，所以同一物理文件只检查一次，
 * 但每条受影响的记录都会出现在结果里——修复是按逻辑记录删的。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IntegrityService {

    private static final int MAX_SCAN_LIMIT = 1000;

    private final FileInfoMapper fileInfoMapper;
    private final StorageBackend storage;
    private final FileStorageService fileStorageService;

    public IntegrityScanResponse scan(boolean deep, int limit) {
        int safeLimit = Math.min(MAX_SCAN_LIMIT, Math.max(1, limit));
        List<FileInfo> rows = fileInfoMapper.selectList(new LambdaQueryWrapper<FileInfo>()
                .eq(FileInfo::getStatus, FileInfo.STATUS_DONE)
                .orderByAsc(FileInfo::getId)
                .last("LIMIT " + safeLimit));

        Map<String, String> verdicts = new HashMap<>();
        List<IntegrityScanResponse.IntegrityItem> broken = new ArrayList<>();
        for (FileInfo info : rows) {
            String path = info.getStoragePath();
            String reason;
            if (path == null) {
                reason = "缺少 storage_path";
            } else if (verdicts.containsKey(path)) {
                // 同一物理文件已经查过：健康则这里必然为 null，损坏则沿用同样的原因
                reason = verdicts.get(path);
            } else {
                reason = inspect(info, path, deep);
                verdicts.put(path, reason);
            }
            if (reason != null) {
                broken.add(new IntegrityScanResponse.IntegrityItem(
                        info.getFileId(), info.getFileName(), info.getOwnerId(),
                        info.getFileSize(), reason));
            }
        }
        long total = fileInfoMapper.countDone();
        return new IntegrityScanResponse(deep, rows.size(), broken.size(),
                total > rows.size(), broken);
    }

    /**
     * 修复 = 删除逻辑记录（物理文件仍遵守引用计数）。
     * 删除前必须重新检查一遍：管理员看到扫描结果到点「修复」之间，
     * 文件可能被恢复、记录可能被别的请求删掉。不能拿一份过期结果直接删。
     */
    public IntegrityRepairResponse repair(IntegrityRepairRequest req, UserPrincipal admin) {
        int repaired = 0;
        int failed = 0;
        List<IntegrityRepairResponse.Item> items = new ArrayList<>();
        for (String fileId : req.fileIds()) {
            String problem;
            try {
                FileInfo info = fileInfoMapper.selectOne(new LambdaQueryWrapper<FileInfo>()
                        .eq(FileInfo::getFileId, fileId));
                if (info == null) {
                    problem = "记录不存在";
                } else if (info.getStatus() != FileInfo.STATUS_DONE) {
                    problem = "不是已完成文件，未处理";
                } else {
                    problem = info.getStoragePath() == null
                            ? "缺少 storage_path"
                            : inspect(info, info.getStoragePath(), true);
                    if (problem == null) {
                        problem = "文件实际完好，未删除";
                    } else {
                        fileStorageService.delete(fileId, admin);
                        items.add(new IntegrityRepairResponse.Item(fileId, true, null));
                        repaired++;
                        continue;
                    }
                }
            } catch (RuntimeException e) {
                problem = "修复失败：" + e.getMessage();
            }
            items.add(new IntegrityRepairResponse.Item(fileId, false, problem));
            failed++;
        }
        if (repaired > 0) {
            log.info("管理员完整性修复：删除 {} 条损坏记录（操作人 {}）", repaired, admin.username());
        }
        return new IntegrityRepairResponse(repaired, failed, items);
    }

    /**
     * @return null 表示健康；否则是写给管理员看的具体原因
     */
    private String inspect(FileInfo info, String path, boolean deep) {
        try {
            if (!storage.exists(path)) {
                return "物理文件不存在";
            }
            long expected = info.getFileSize() == null ? -1 : info.getFileSize();
            long actual = storage.size(path);
            if (actual != expected) {
                return "大小不符：索引 " + expected + " 字节，磁盘 " + actual + " 字节";
            }
            if (deep) {
                String actualHash;
                try (InputStream in = storage.open(path)) {
                    actualHash = HashUtil.sha256Hex(in);
                } catch (IOException e) {
                    return "读取失败：" + e.getMessage();
                }
                if (!actualHash.equalsIgnoreCase(info.getFileHash())) {
                    return "内容哈希不一致";
                }
            }
            return null;
        } catch (RuntimeException e) {
            return "检查失败：" + e.getMessage();
        }
    }
}
