package dev.mattest.supplychain.vo.contact;

public record CustomerServiceVO(
        long id,
        String displayName,
        String wechatId,
        Long qrMediaId,
        String qrImageUrl,
        String phone,
        boolean active) {
}
