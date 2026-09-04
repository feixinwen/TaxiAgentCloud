package com.fancy.taxiagent.auth.controller.dto;

/**
 * 发送邮箱验证码的请求体。
 *
 * @param email 目标邮箱（大小写不敏感）
 * @param scene 验证码场景，当前阶段仅支持 REGISTER
 */
public record SendEmailCodeRequest(String email, String scene) {}
