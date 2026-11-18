package com.yuzhuo.filehub.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 浏览器下载用的短期票据。
 *
 * <p>Bearer Token 不能塞进 &lt;a href&gt;，所以下载前换一张 5 分钟的票据；
 * 票据绑定 fileId + userId，数据库只存哈希。</p>
 */
@Data
@TableName("download_ticket")
public class DownloadTicket {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String ticketHash;

    private String fileId;

    private Long userId;

    private LocalDateTime expiresAt;

    private LocalDateTime createdAt;
}
