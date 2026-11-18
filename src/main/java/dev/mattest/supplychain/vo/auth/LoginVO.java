package dev.mattest.supplychain.vo.auth;

public record LoginVO(String username, String role, boolean mustChangePassword) {
}
