package com.yuzhuo.filehub.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("file_info")
public class FileInfo {

    public static final int STATUS_UPLOADING = 0;
    public static final int STATUS_DONE = 1;

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 对外标识，不暴露自增 ID */
    private String fileId;

    private String fileName;

    private Long fileSize;

    /** 全文件 SHA-256（hex 小写） */
    private String fileHash;

    private Integer chunkSize;

    private Integer chunkTotal;

    /** 成品在存储后端中的相对路径，上传中为 null */
    private String storagePath;

    /** 0=上传中 1=已完成 */
    private Integer status;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
