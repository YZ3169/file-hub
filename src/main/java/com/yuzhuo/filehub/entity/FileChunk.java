package com.yuzhuo.filehub.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("file_chunk")
public class FileChunk {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String fileId;

    /** 分片序号，从 0 开始 */
    private Integer chunkIndex;

    private Integer chunkSize;

    /** 分片内容的 SHA-256（hex 小写）。合并时逐片复算，坏哪片只补哪片 */
    private String chunkHash;

    private LocalDateTime createTime;
}
