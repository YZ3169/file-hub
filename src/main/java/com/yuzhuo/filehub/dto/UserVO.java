package com.yuzhuo.filehub.dto;

import com.yuzhuo.filehub.entity.UserAccount;

public record UserVO(Long id, String username, String role) {

    public static UserVO from(UserAccount account) {
        return new UserVO(account.getId(), account.getUsername(), account.getRole());
    }
}
