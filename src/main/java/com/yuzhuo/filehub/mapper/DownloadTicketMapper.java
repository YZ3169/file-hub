package com.yuzhuo.filehub.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yuzhuo.filehub.entity.DownloadTicket;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;

@Mapper
public interface DownloadTicketMapper extends BaseMapper<DownloadTicket> {

    @Select("SELECT * FROM download_ticket WHERE ticket_hash = #{ticketHash} "
            + "AND file_id = #{fileId} AND expires_at > #{now} LIMIT 1")
    DownloadTicket findValid(@Param("ticketHash") String ticketHash,
                             @Param("fileId") String fileId,
                             @Param("now") LocalDateTime now);

    @Delete("DELETE FROM download_ticket WHERE share_id = #{shareId}")
    int deleteByShareId(@Param("shareId") Long shareId);

    @Delete("DELETE FROM download_ticket WHERE expires_at <= #{now}")
    int deleteExpired(@Param("now") LocalDateTime now);
}
