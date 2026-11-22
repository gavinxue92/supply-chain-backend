package dev.mattest.supplychain.dto.hotkeyword;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

public record HotKeywordsSaveRequest(
        @NotEmpty @Size(max = 12) List<@NotBlank @Size(max = 120) String> keywords) {
}
