package com.yuzhuo.filehub.dto;

import com.yuzhuo.filehub.entity.UserAccount;

public record UserVO(Long id, String username, String role, long storageQuotaBytes) {

    public UserVO(Long id, String username, String role) {
        this(id, username, role, -1L);
    }

    public static UserVO from(UserAccount account) {
        long quota = account.getStorageQuotaBytes() == null ? -1L : account.getStorageQuotaBytes();
        return new UserVO(account.getId(), account.getUsername(), account.getRole(), quota);
    }
}
