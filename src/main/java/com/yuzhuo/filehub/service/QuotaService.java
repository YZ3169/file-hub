package com.yuzhuo.filehub.service;

import com.yuzhuo.filehub.auth.UserPrincipal;
import com.yuzhuo.filehub.common.BizException;
import com.yuzhuo.filehub.config.StorageProperties;
import com.yuzhuo.filehub.entity.UserAccount;
import com.yuzhuo.filehub.mapper.FileInfoMapper;
import com.yuzhuo.filehub.mapper.UserAccountMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.function.Supplier;

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

    private TransactionTemplate transactionTemplate;

    @Autowired(required = false)
    public void setTransactionManager(PlatformTransactionManager transactionManager) {
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

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

    /**
     * 原子预留空间：在一个数据库事务里先锁住用户行，再汇总用量、检查余额、执行写入。
     *
     * <p>单独调用 {@link #checkCanAllocate} 再插入是「先查后写」：同一账号的两个请求可以
     * 同时读到同一份余额并都通过。这里把写入动作（插入占用配额的 file_info 行）放进同一事务，
     * 行锁让同一用户的预留串行化，提交后下一个请求才能汇总到新行。
     * 跨实例同样有效，因为串行化点在数据库而不是 JVM。</p>
     *
     * @param additionalBytes 净增字节数；≤ 0 时不做检查，只执行写入
     * @param insert          写入动作，必须在本方法内完成（返回前即提交）
     */
    public <T> T allocate(UserPrincipal user, long additionalBytes, Supplier<T> insert) {
        return allocateReplacing(user, additionalBytes, 0L, insert);
    }

    /**
     * 原子预留空间：在一个数据库事务里先锁住用户行，再汇总用量、检查余额、执行写入。
     *
     * <p>单独调用 {@link #checkCanAllocate} 再插入是「先查后写」：同一账号的两个请求可以
     * 同时读到同一份余额并都通过。这里把写入动作（插入占用配额的 file_info 行）放进同一事务，
     * 行锁让同一用户的预留串行化，提交后下一个请求才能汇总到新行。
     * 跨实例同样有效，因为串行化点在数据库而不是 JVM。</p>
     *
     * @param newBytes      新文件字节数；≤ 0 时不做检查，只执行写入
     * @param replacedBytes 将要同路径替换并删除的旧文件字节数
     * @param insert        写入动作，必须在本方法内完成（返回前即提交）
     */
    public <T> T allocateReplacing(UserPrincipal user, long newBytes, long replacedBytes, Supplier<T> insert) {
        if (user == null || newBytes <= 0 || quotaBytesFor(user.id()) < 0) {
            return insert.get();
        }
        if (transactionTemplate == null) {
            // 没有事务管理器（纯单元测试）：退化为先查后写
            checkProjected(user.id(), newBytes, replacedBytes);
            return insert.get();
        }
        return transactionTemplate.execute(status -> {
            userMapper.lockForQuota(user.id());
            checkProjected(user.id(), newBytes, replacedBytes);
            return insert.get();
        });
    }

    private void checkProjected(Long userId, long newBytes, long replacedBytes) {
        long quota = quotaBytesFor(userId);
        if (quota < 0) {
            return;
        }
        long used = usedBytesFor(userId);
        long projected = Math.max(0L, used - Math.max(0L, replacedBytes)) + newBytes;
        if (projected > quota) {
            throw new BizException(41001,
                    "存储空间不足：已用 " + used + " 字节，新增 " + newBytes + " 字节将超过配额 " + quota + " 字节");
        }
    }
}
