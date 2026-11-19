package com.yuzhuo.filehub.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yuzhuo.filehub.entity.FileShare;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

@Mapper
public interface FileShareMapper extends BaseMapper<FileShare> {

    @Select("SELECT * FROM file_share WHERE share_token = #{token} LIMIT 1")
    FileShare findByToken(@Param("token") String token);

    /**
     * 原子地占用一次提取码尝试额度 —— 必须在校验提取码<b>之前</b>调用。
     *
     * <p>如果先跑 BCrypt 再计数，并发请求会同时读到「未锁定」，全部把码比对一遍，
     * 「连续 10 次锁定」实际挡不住一个并发脉冲。这里用单条 UPDATE 先占坑：
     * 返回 0 表示已锁定/已取消，调用方不得再比对提取码。</p>
     *
     * <p>SET 里先算 {@code locked_until} 再自增 {@code failed_attempts}：MySQL 会从左到右
     * 用已经更新过的列值，顺序反了会多加一次；H2 按旧行求值，两种顺序结果一致。</p>
     */
    @Update("UPDATE file_share SET "
            + "locked_until = CASE WHEN failed_attempts + 1 >= #{maxAttempts} THEN #{lockUntil} ELSE locked_until END, "
            + "failed_attempts = failed_attempts + 1, "
            + "update_time = #{now} "
            + "WHERE id = #{id} AND status = 1 "
            + "AND (locked_until IS NULL OR locked_until <= #{now})")
    int reserveAttempt(@Param("id") Long id,
                       @Param("maxAttempts") int maxAttempts,
                       @Param("lockUntil") LocalDateTime lockUntil,
                       @Param("now") LocalDateTime now);

    /** 提取码正确：清零失败计数、解除锁定（正确尝试不应当被算作失败） */
    @Update("UPDATE file_share SET failed_attempts = 0, locked_until = NULL, update_time = #{now} "
            + "WHERE id = #{id} AND status = 1")
    int clearFailures(@Param("id") Long id, @Param("now") LocalDateTime now);

    /**
     * 提取次数 +1。过期、取消都在 WHERE 里兜底，避免边界时刻先判定未过期、
     * BCrypt 跑完后分享已经失效却仍计数。
     */
    @Update("UPDATE file_share SET download_count = download_count + 1, update_time = #{now} "
            + "WHERE id = #{id} AND status = 1 "
            + "AND (expires_at IS NULL OR expires_at > #{now})")
    int incrementDownload(@Param("id") Long id, @Param("now") LocalDateTime now);

    /** 已登录用户某个文件的有效分享数（用于创建配额） */
    @Select("SELECT COUNT(*) FROM file_share WHERE owner_id = #{ownerId} AND file_id = #{fileId} "
            + "AND status = 1 AND (expires_at IS NULL OR expires_at > #{now})")
    int countActiveForFile(@Param("ownerId") Long ownerId,
                           @Param("fileId") String fileId,
                           @Param("now") LocalDateTime now);

    /** 已登录用户的有效分享总数（用于创建配额） */
    @Select("SELECT COUNT(*) FROM file_share WHERE owner_id = #{ownerId} "
            + "AND status = 1 AND (expires_at IS NULL OR expires_at > #{now})")
    int countActiveForOwner(@Param("ownerId") Long ownerId, @Param("now") LocalDateTime now);

    /** 锁定已到期时清零，避免下一次输错直接又锁 15 分钟 */
    @Update("UPDATE file_share SET failed_attempts = 0, locked_until = NULL, update_time = #{now} "
            + "WHERE id = #{id} AND locked_until IS NOT NULL AND locked_until <= #{now}")
    int clearExpiredLock(@Param("id") Long id, @Param("now") LocalDateTime now);

    @Update("UPDATE file_share SET status = 0, update_time = #{now} "
            + "WHERE share_token = #{token} AND owner_id = #{ownerId} AND status = 1")
    int cancel(@Param("token") String token,
               @Param("ownerId") Long ownerId,
               @Param("now") LocalDateTime now);

    /** 重置提取码并解除锁定；返回 0 表示不是自己的或已取消 */
    @Update("UPDATE file_share SET code_hash = #{codeHash}, failed_attempts = 0, "
            + "locked_until = NULL, update_time = #{now} "
            + "WHERE share_token = #{token} AND owner_id = #{ownerId} AND status = 1")
    int resetCode(@Param("token") String token,
                  @Param("ownerId") Long ownerId,
                  @Param("codeHash") String codeHash,
                  @Param("now") LocalDateTime now);

    /** 删除已取消且超过保留期的分享（走 idx_share_update_time） */
    @Delete("DELETE FROM file_share WHERE status = 0 AND update_time < #{cutoff}")
    int deleteCancelled(@Param("cutoff") LocalDateTime cutoff);

    /** 删除过期且超过保留期的分享（走 idx_share_expires） */
    @Delete("DELETE FROM file_share WHERE expires_at IS NOT NULL AND expires_at < #{cutoff}")
    int deleteExpired(@Param("cutoff") LocalDateTime cutoff);

    /** 文件记录已不存在的分享（文件被删、或历史数据清理后的残留） */
    @Delete("DELETE FROM file_share WHERE NOT EXISTS "
            + "(SELECT 1 FROM file_info f WHERE f.file_id = file_share.file_id)")
    int deleteOrphans();
}
