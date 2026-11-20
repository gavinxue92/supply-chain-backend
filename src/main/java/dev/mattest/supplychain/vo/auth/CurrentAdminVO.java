package dev.mattest.supplychain.vo.auth;

public record CurrentAdminVO(String username, String nickname, String role, boolean mustChangePassword) {
}
