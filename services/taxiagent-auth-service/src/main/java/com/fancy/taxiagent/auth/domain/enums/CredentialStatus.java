package com.fancy.taxiagent.auth.domain.enums;

/**
 * Auth Service 管理的认证凭证状态。
 *
 * <p>该状态只描述密码等安全凭证是否可用，不代表用户正常、封禁或注销等业务状态；
 * 后者始终由 User Service 判断。</p>
 */
public enum CredentialStatus {

    /** 凭证可用于认证。 */
    ACTIVE,

    /** 因连续认证失败等安全原因被临时锁定。 */
    LOCKED,

    /** 必须完成密码重置后才能继续使用密码认证。 */
    PASSWORD_RESET_REQUIRED
}
