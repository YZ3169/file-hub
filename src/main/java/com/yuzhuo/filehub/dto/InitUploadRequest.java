package com.yuzhuo.filehub.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 初始化上传（第一步）。
 *
 * <p>注意这里**没有 chunkTotal 字段**——分片总数由服务端用 fileSize 和 chunkSize 自己算。
 * 早期版本让前端传 chunkTotal，结果前端向上取整写错一次就合并出坏文件，
 * 还得靠最后的 hash 校验才发现。能推导出来的东西就不要让调用方传。</p>
 */
public record InitUploadRequest(

        @NotBlank(message = "不能为空")
        @Size(max = 255, message = "长度不能超过 255")
        String fileName,

        @NotNull(message = "不能为空")
        @Min(value = 0, message = "不能为负数")
        Long fileSize,

        /** 全文件 SHA-256（hex 小写），秒传和完整性校验都用它 */
        @NotBlank(message = "不能为空")
        @Pattern(regexp = "^[0-9a-fA-F]{64}$", message = "必须是 64 位十六进制的 SHA-256")
        String fileHash
) {
}
