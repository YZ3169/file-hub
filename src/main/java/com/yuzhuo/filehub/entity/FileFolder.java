package com.yuzhuo.filehub.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("file_folder")
public class FileFolder {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long ownerId;

    private String folderPath;

    /** folderPath 的 SHA-256（小写 hex），用于在 MySQL utf8mb4 下提供大小写敏感唯一索引 */
    private String folderPathHash;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
