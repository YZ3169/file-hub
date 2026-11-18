package com.yuzhuo.filehub.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

public record IntegrityRepairRequest(
        @NotEmpty(message = "至少选择一个要修复的文件")
        @Size(max = 200, message = "一次最多修复 200 条记录")
        List<String> fileIds) {
}
