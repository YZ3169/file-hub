package com.yuzhuo.filehub.dto;

import java.time.LocalDateTime;

/**
 * 提取码校验成功后的文件信息与短期下载地址。
 *
 * <p>{@code downloadUrl} 就是现有的票据下载接口，Range/多段下载等行为完全复用。</p>
 */
public record ShareVerifyResponse(String fileId,
                                  String fileName,
                                  Long fileSize,
                                  String downloadUrl,
                                  LocalDateTime ticketExpiresAt,
                                  LocalDateTime shareExpiresAt) {
}
