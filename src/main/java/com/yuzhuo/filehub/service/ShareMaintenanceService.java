package com.yuzhuo.filehub.service;

import com.yuzhuo.filehub.config.ShareProperties;
import com.yuzhuo.filehub.mapper.FileShareMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * 分享记录清理。
 *
 * <p>删除都是幂等 SQL，多实例重复执行无害。文件被删后分享马上就不能提取了；
 * 这里只是把「已取消/已过期/文件已不存在」的垃圾行清掉，避免表无限增长。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ShareMaintenanceService {

    private final FileShareMapper shareMapper;

    private final ShareProperties properties;

    @Scheduled(fixedDelayString = "${file-hub.share.cleanup-interval-ms:1800000}",
            initialDelayString = "${file-hub.share.cleanup-initial-delay-ms:60000}")
    public void cleanup() {
        int retentionDays = Math.max(1, properties.getStaleRetentionDays());
        LocalDateTime cutoff = LocalDateTime.now().minusDays(retentionDays);
        // 拆成两条 DELETE：OR 条件用不上两个单列索引，MySQL 会退化成全表扫描
        int cancelled = shareMapper.deleteCancelled(cutoff);
        int expired = shareMapper.deleteExpired(cutoff);
        int orphans = shareMapper.deleteOrphans();
        if (cancelled + expired + orphans > 0) {
            log.info("分享清理完成：取消 {} 条，过期 {} 条，文件已不存在 {} 条",
                    cancelled, expired, orphans);
        }
    }
}
