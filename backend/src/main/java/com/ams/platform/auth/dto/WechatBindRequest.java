package com.ams.platform.auth.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class WechatBindRequest {

    /** wx.login 的 code；与 bindTicket 二选一 */
    private String code;
    /** 登录接口返回的绑定票据 */
    private String bindTicket;

    @NotBlank
    private String name;
    @NotBlank
    private String phone;
    private String idNo;
}
