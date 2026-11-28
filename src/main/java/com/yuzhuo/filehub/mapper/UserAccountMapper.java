package com.yuzhuo.filehub.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yuzhuo.filehub.entity.UserAccount;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

@Mapper
public interface UserAccountMapper extends BaseMapper<UserAccount> {

    @Select("SELECT * FROM user_account WHERE username = #{username} LIMIT 1")
    UserAccount findByUsername(@Param("username") String username);

    /**
     * 配额预留的串行化点：在事务里锁住用户行，同一用户的「汇总用量 → 写入新记录」因此排队执行。
     * 只锁一行，不同用户之间互不影响；MySQL InnoDB 与 H2 都支持。
     */
    @Select("SELECT id FROM user_account WHERE id = #{id} FOR UPDATE")
    Long lockForQuota(@Param("id") Long id);

    @Update("UPDATE user_account SET storage_quota_bytes = #{quotaBytes}, updated_at = #{now} WHERE id = #{id}")
    int updateQuota(@Param("id") Long id, @Param("quotaBytes") long quotaBytes, @Param("now") LocalDateTime now);
}
