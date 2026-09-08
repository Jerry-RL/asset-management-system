package com.ams.platform.auth.dto;

import java.util.Set;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LoginResponse {

    private String accessToken;
    private String refreshToken;
    private long expiresIn;
    private UserInfo user;
    /** 微信未绑定时为 true，前端跳转身份绑定 */
    private Boolean needBind;
    /** 绑定票据（Redis），绑定接口必传 */
    private String bindTicket;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class UserInfo {
        private Long id;
        private String username;
        private String name;
        private Long companyId;
        private Long tenantId;
        private Set<String> roles;
    }
}
