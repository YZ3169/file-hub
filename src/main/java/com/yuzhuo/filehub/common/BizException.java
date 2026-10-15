package com.yuzhuo.filehub.common;

import lombok.Getter;

/**
 * 业务异常。错误码约定：
 * 10xxx 参数/状态类，20xxx 存储类，30xxx 完整性类。
 */
@Getter
public class BizException extends RuntimeException {

    private final int code;

    public BizException(int code, String message) {
        super(message);
        this.code = code;
    }

    public BizException(String message) {
        this(10000, message);
    }
}
