package com.yuzhuo.filehub.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yuzhuo.filehub.entity.ZipTicket;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;

@Mapper
public interface ZipTicketMapper extends BaseMapper<ZipTicket> {

    @Select("SELECT * FROM zip_ticket WHERE ticket_hash = #{ticketHash} AND expires_at > #{now} LIMIT 1")
    ZipTicket findValid(@Param("ticketHash") String ticketHash, @Param("now") LocalDateTime now);

    @Delete("DELETE FROM zip_ticket WHERE expires_at <= #{now}")
    int deleteExpired(@Param("now") LocalDateTime now);
}
