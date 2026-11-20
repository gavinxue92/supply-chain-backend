package dev.mattest.supplychain.dto.auth;

import jakarta.validation.constraints.Size;

public record NicknameRequest(@Size(max = 50) String nickname) {
}
