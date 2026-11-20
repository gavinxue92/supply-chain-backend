package dev.mattest.supplychain.vo.auth;

public record LoginVO(String username, String nickname, String role, boolean mustChangePassword) {
}
