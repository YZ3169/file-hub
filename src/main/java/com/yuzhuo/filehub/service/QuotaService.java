package com.yuzhuo.filehub.service;

import com.yuzhuo.filehub.auth.UserPrincipal;
import com.yuzhuo.filehub.common.BizException;
import com.yuzhuo.filehub.config.StorageProperties;
import com.yuzhuo.filehub.entity.UserAccount;
import com.yuzhuo.filehub.mapper.FileInfoMapper;
import com.yuzhuo.filehub.mapper.UserAccountMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 用户存储配额服务。
 *
 * <p>{@code storage_quota_bytes = -1} 表示不限制；{@code >= 0} 表示逻辑空间上限（含上传中、已完成与回收站）。</p>
 */
@Service
@RequiredArgsConstructor
public class QuotaService {

    private final UserAccountMapper userMapper;
    private final FileInfoMapper fileInfoMapper;
    private final StorageProperties props;

    public long quotaBytesFor(Long userId) {
        if (userId == null) {
            return -1L;
        }
        UserAccount account = userMapper.selectById(userId);
        if (account != null && account.getStorageQuotaBytes() != null && account.getStorageQuotaBytes() >= 0) {
            return account.getStorageQuotaBytes();
        }
        return props.getQuota().getDefaultBytes();
    }

    public long usedBytesFor(Long userId) {
        if (userId == null) {
            return 0L;
        }
        return fileInfoMapper.sumQuotaUsedByOwner(userId);
    }

    /** 返回剩余可用字节数，-1 表示不限制 */
    public long remainingBytesFor(UserPrincipal user) {
        if (user == null) {
            return -1L;
        }
        long quota = quotaBytesFor(user.id());
        if (quota < 0) {
            return -1L;
        }
        long used = usedBytesFor(user.id());
        return Math.max(0L, quota - used);
    }

    public void checkCanAllocate(UserPrincipal user, long additionalBytes) {
        if (user == null || additionalBytes <= 0) {
            return;
        }
        long quota = quotaBytesFor(user.id());
        if (quota < 0) {
            return;
        }
        long used = usedBytesFor(user.id());
        if (used + additionalBytes > quota) {
            throw new BizException(41001,
                    "存储空间不足：已用 " + used + " 字节，新增 " + additionalBytes + " 字节将超过配额 " + quota + " 字节");
        }
    }
}
