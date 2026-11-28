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

        Map<String, Inspection> verdicts = new HashMap<>();
        List<IntegrityScanResponse.IntegrityItem> problems = new ArrayList<>();
        int corrupt = 0;
        int unverifiable = 0;
        for (FileInfo info : rows) {
            String path = info.getStoragePath();
            Inspection result;
            if (path == null) {
                result = Inspection.corrupt("缺少 storage_path");
            } else {
                // 同一物理文件只查一次：健康则这里必然健康，损坏/无法验证则沿用同样的结论
                result = verdicts.computeIfAbsent(path, p -> inspect(info, p, deep));
            }
            if (result.verdict() == Verdict.OK) {
                continue;
            }
            String label;
            if (result.verdict() == Verdict.CORRUPT) {
                corrupt++;
                label = IntegrityScanResponse.VERDICT_CORRUPT;
            } else {
                unverifiable++;
                label = IntegrityScanResponse.VERDICT_UNVERIFIABLE;
            }
            problems.add(new IntegrityScanResponse.IntegrityItem(
                    info.getFileId(), info.getFileName(), info.getOwnerId(),
                    info.getFileSize(), result.reason(), label));
        }
        long total = fileInfoMapper.countDone();
        return new IntegrityScanResponse(deep, rows.size(), corrupt,
                total > rows.size(), problems, unverifiable);
    }

    /**
     * 修复 = 删除逻辑记录（物理文件仍遵守引用计数）。
     * 删除前必须重新检查一遍：管理员看到扫描结果到点「修复」之间，
     * 文件可能被恢复、记录可能被别的请求删掉。不能拿一份过期结果直接删。
     *
     * <p><b>只有复检「确认损坏」才删</b>。S3 超时、权限错误、读取中断等只说明「现在读不到」，
     * 不说明数据坏了；这时删除逻辑记录（甚至最后一个引用对应的物理对象）会把一次短暂故障
     * 变成永久数据丢失。无法验证的记录一律保留，返回失败原因让管理员稍后重试。</p>
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
                    Inspection result = info.getStoragePath() == null
                            ? Inspection.corrupt("缺少 storage_path")
                            : inspect(info, info.getStoragePath(), true);
                    switch (result.verdict()) {
                        case OK -> problem = "文件实际完好，未删除";
                        case UNVERIFIABLE -> problem = "暂时无法验证，已保留数据，请稍后重试："
                                + result.reason();
                        default -> {
                            fileStorageService.delete(fileId, admin);
                            items.add(new IntegrityRepairResponse.Item(fileId, true, null));
                            repaired++;
                            continue;
                        }
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

    enum Verdict {
        /** 完好 */
        OK,
        /** 确认损坏：存储明确回答了「不存在 / 大小不对 / 内容不对」 */
        CORRUPT,
        /** 暂时无法验证：读存储时出错，无法得出结论 */
        UNVERIFIABLE
    }

    record Inspection(Verdict verdict, String reason) {
        static final Inspection HEALTHY = new Inspection(Verdict.OK, null);

        static Inspection corrupt(String reason) {
            return new Inspection(Verdict.CORRUPT, reason);
        }

        static Inspection unverifiable(String reason) {
            return new Inspection(Verdict.UNVERIFIABLE, reason);
        }
    }

    /**
     * 三态检查：只有存储后端给出了明确答复（对象不存在、长度不符、完整读完后哈希不符）
     * 才判定为损坏；任何读取异常都只算「暂时无法验证」。
     */
    private Inspection inspect(FileInfo info, String path, boolean deep) {
        long actual;
        try {
            if (!storage.exists(path)) {
                return Inspection.corrupt("物理文件不存在");
            }
            actual = storage.size(path);
        } catch (RuntimeException e) {
            log.warn("完整性检查无法访问存储：{}", path, e);
            return Inspection.unverifiable("访问存储失败：" + e.getMessage());
        }
        if (actual < 0) {
            // exists 与 size 之间对象消失（S3 HEAD 返回 404）
            return Inspection.corrupt("物理文件不存在");
        }
        long expected = info.getFileSize() == null ? -1 : info.getFileSize();
        if (actual != expected) {
            return Inspection.corrupt("大小不符：索引 " + expected + " 字节，磁盘 " + actual + " 字节");
        }
        if (!deep) {
            return Inspection.HEALTHY;
        }
        String actualHash;
        try (InputStream in = storage.open(path)) {
            actualHash = HashUtil.sha256Hex(in);
        } catch (IOException | RuntimeException e) {
            log.warn("完整性检查读取内容失败：{}", path, e);
            return Inspection.unverifiable("读取失败：" + e.getMessage());
        }
        if (!actualHash.equalsIgnoreCase(info.getFileHash())) {
            return Inspection.corrupt("内容哈希不一致");
        }
        return Inspection.HEALTHY;
    }
}
