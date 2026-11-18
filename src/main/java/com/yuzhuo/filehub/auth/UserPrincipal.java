package com.yuzhuo.filehub.auth;

import com.yuzhuo.filehub.entity.UserAccount;

/**
 * 请求上下文中的已认证用户。
 */
public record UserPrincipal(Long id, String username, String role) {

    public static UserPrincipal from(UserAccount account) {
        return new UserPrincipal(account.getId(), account.getUsername(), account.getRole());
    }

    public boolean isAdmin() {
        return UserAccount.ROLE_ADMIN.equals(role);
    }
}
