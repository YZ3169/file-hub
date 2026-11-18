package com.yuzhuo.filehub.dto;

import java.util.List;

/**
 * 通用分页响应。
 *
 * @param items 当前页数据
 * @param total 满足条件的总条数（不是当前页条数）
 * @param page  当前页码（从 1 开始）
 * @param size  每页条数
 */
public record PageResponse<T>(List<T> items, long total, int page, int size) {
}
