package dev.mattest.supplychain.service;

import dev.mattest.supplychain.dto.hotkeyword.HotKeywordsSaveRequest;
import dev.mattest.supplychain.security.AdminPrincipal;
import dev.mattest.supplychain.vo.hotkeyword.HotKeywordVO;

import java.util.List;

public interface HotKeywordService {
    List<String> listPublicKeywords();
    List<HotKeywordVO> listForAdmin(AdminPrincipal actor);
    List<HotKeywordVO> save(AdminPrincipal actor, HotKeywordsSaveRequest request);
}
