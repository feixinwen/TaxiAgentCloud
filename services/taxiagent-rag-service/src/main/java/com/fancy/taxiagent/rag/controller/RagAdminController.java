package com.fancy.taxiagent.rag.controller;

import com.fancy.taxiagent.rag.domain.dto.RagQAAddDTO;
import com.fancy.taxiagent.rag.domain.dto.RagQADelDTO;
import com.fancy.taxiagent.rag.domain.dto.RagQAQueryDTO;
import com.fancy.taxiagent.rag.domain.dto.RagQAQueryVO;
import com.fancy.taxiagent.rag.domain.dto.RagQAUpdateAnswerDTO;
import com.fancy.taxiagent.rag.domain.dto.RagQAUpdateQuestionDTO;
import com.fancy.taxiagent.rag.domain.vo.PageResult;
import com.fancy.taxiagent.rag.service.RagAdminService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 知识库管理接口（仅 ADMIN）。
 */
@RestController
@RequestMapping("/api/rag/admin")
@PreAuthorize("hasRole('ADMIN')")
public class RagAdminController {

    private final RagAdminService ragAdminService;

    public RagAdminController(RagAdminService ragAdminService) {
        this.ragAdminService = ragAdminService;
    }

    @PostMapping("/page")
    public PageResult<RagQAQueryVO> page(@RequestBody RagQAQueryDTO req) {
        return ragAdminService.queryPage(req.getPage(), req.getSize(), req.getGroupId());
    }

    @PostMapping("/add")
    public void add(@RequestBody RagQAAddDTO req) {
        ragAdminService.addQA(req.getQuestions(), req.getAnswer());
    }

    @PostMapping("/add-batch")
    public void addBatch(@RequestBody List<RagQAAddDTO> reqs) {
        ragAdminService.addQAs(reqs.stream().map(RagQAAddDTO::getQuestions).toList(),
                reqs.stream().map(RagQAAddDTO::getAnswer).toList());
    }

    @PostMapping("/delete")
    public void delete(@RequestBody RagQADelDTO req) {
        ragAdminService.deleteQA(req.getGroupIds(), req.getQuestionIds());
    }

    @PostMapping("/update-answer")
    public void updateAnswer(@RequestBody RagQAUpdateAnswerDTO req) {
        ragAdminService.updateAnswer(req.getGroupId(), req.getAnswer());
    }

    @PostMapping("/update-question")
    public void updateQuestion(@RequestBody RagQAUpdateQuestionDTO req) {
        ragAdminService.updateQuestion(req.getQuestionId(), req.getQuestion());
    }
}
