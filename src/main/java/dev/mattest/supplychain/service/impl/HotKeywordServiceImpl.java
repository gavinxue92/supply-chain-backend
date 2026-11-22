package dev.mattest.supplychain.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import dev.mattest.supplychain.dto.hotkeyword.HotKeywordsSaveRequest;
import dev.mattest.supplychain.entity.SearchHotKeyword;
import dev.mattest.supplychain.mapper.SearchHotKeywordMapper;
import dev.mattest.supplychain.security.AdminPrincipal;
import dev.mattest.supplychain.service.HotKeywordService;
import dev.mattest.supplychain.vo.hotkeyword.HotKeywordVO;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

@Service
public class HotKeywordServiceImpl implements HotKeywordService {
    private static final int MAX_KEYWORDS = 12;
    private static final int MAX_KEYWORD_LENGTH = 120;

    private final SearchHotKeywordMapper keywordMapper;
    private final AuditLogWriter auditLogWriter;

    public HotKeywordServiceImpl(SearchHotKeywordMapper keywordMapper, AuditLogWriter auditLogWriter) {
        this.keywordMapper = keywordMapper;
        this.auditLogWriter = auditLogWriter;
    }

    @Override
    public List<String> listPublicKeywords() {
        return ordered().stream().map(SearchHotKeyword::getKeyword).toList();
    }

    @Override
    public List<HotKeywordVO> listForAdmin(AdminPrincipal actor) {
        requireAdministrator(actor);
        return toViews();
    }

    @Override
    @Transactional
    public List<HotKeywordVO> save(AdminPrincipal actor, HotKeywordsSaveRequest request) {
        requireAdministrator(actor);
        List<String> keywords = request.keywords().stream()
                .map(String::trim)
                .toList();
        if (keywords.isEmpty() || keywords.size() > MAX_KEYWORDS
                || keywords.stream().anyMatch(word -> word.isEmpty() || word.length() > MAX_KEYWORD_LENGTH)
                || new LinkedHashSet<>(keywords).size() != keywords.size()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Hot keywords require 1-12 distinct non-blank values of at most 120 characters");
        }
        keywordMapper.delete(new LambdaQueryWrapper<SearchHotKeyword>());
        for (int index = 0; index < keywords.size(); index++) {
            SearchHotKeyword keyword = new SearchHotKeyword();
            keyword.setKeyword(keywords.get(index));
            keyword.setSortOrder(index);
            keywordMapper.insert(keyword);
        }
        auditLogWriter.write(actor.id(), "SEARCH_HOT_KEYWORDS_UPDATED",
                "search_hot_keywords", null, Map.of("keywordCount", keywords.size()));
        return toViews();
    }

    private List<SearchHotKeyword> ordered() {
        return keywordMapper.selectList(new LambdaQueryWrapper<SearchHotKeyword>()
                .orderByAsc(SearchHotKeyword::getSortOrder)
                .orderByAsc(SearchHotKeyword::getId));
    }

    private List<HotKeywordVO> toViews() {
        return ordered().stream()
                .map(keyword -> new HotKeywordVO(keyword.getId(), keyword.getKeyword(), keyword.getSortOrder()))
                .toList();
    }

    private void requireAdministrator(AdminPrincipal actor) {
        if (!"ADMIN".equals(actor.role())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Administrator role required");
        }
    }
}
