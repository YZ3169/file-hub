package com.yuzhuo.filehub.common;

import lombok.Data;

/**
 * 统一返回体。
 */
@Data
public class Result<T> {

    /** 0 = 成功，非 0 = 业务失败 */
    private int code;

    private String msg;

    private T data;

    private Result(int code, String msg, T data) {
        this.code = code;
        this.msg = msg;
        this.data = data;
    }

    public static <T> Result<T> ok(T data) {
        return new Result<>(0, "ok", data);
    }

    public static <T> Result<T> ok() {
        return new Result<>(0, "ok", null);
    }

    public static <T> Result<T> fail(int code, String msg) {
        return new Result<>(code, msg, null);
    }
}
