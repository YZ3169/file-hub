package com.yuzhuo.filehub.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("zip_ticket")
public class ZipTicket {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String ticketHash;

    private Long userId;

    private String zipName;

    private String payload;

    private LocalDateTime expiresAt;

    private LocalDateTime createdAt;
}
