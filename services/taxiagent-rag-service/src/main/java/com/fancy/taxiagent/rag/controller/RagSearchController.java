package com.fancy.taxiagent.rag.controller;

import com.fancy.taxiagent.rag.domain.dto.RagSearchRequest;
import com.fancy.taxiagent.rag.domain.dto.RagSearchResult;
import com.fancy.taxiagent.rag.service.RagSearchService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 知识库检索接口（登录用户可用；未来 Agent 服务经服务身份调用）。
 */
@RestController
@RequestMapping("/api/rag")
public class RagSearchController {

    private final RagSearchService ragSearchService;

    public RagSearchController(RagSearchService ragSearchService) {
        this.ragSearchService = ragSearchService;
    }

    @PostMapping("/search")
    public List<RagSearchResult> search(@RequestBody RagSearchRequest req) {
        if (req.getQuestion() == null || req.getQuestion().isBlank()) {
            throw new com.fancy.taxiagent.rag.exception.InvalidRagRequestException("查询问题不能为空");
        }
        return ragSearchService.search(req.getQuestion().trim(), req.getTopK());
    }
}
