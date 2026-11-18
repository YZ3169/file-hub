package com.yuzhuo.filehub.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yuzhuo.filehub.entity.UserSession;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;

@Mapper
public interface UserSessionMapper extends BaseMapper<UserSession> {

    @Select("SELECT * FROM user_session WHERE token_hash = #{tokenHash} "
            + "AND expires_at > #{now} LIMIT 1")
    UserSession findActive(@Param("tokenHash") String tokenHash, @Param("now") LocalDateTime now);

    @Delete("DELETE FROM user_session WHERE token_hash = #{tokenHash}")
    int deleteByTokenHash(@Param("tokenHash") String tokenHash);

    @Delete("DELETE FROM user_session WHERE expires_at <= #{now}")
    int deleteExpired(@Param("now") LocalDateTime now);
}
