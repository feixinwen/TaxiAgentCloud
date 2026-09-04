package com.fancy.taxiagent.rag.domain.dto;

import java.util.Map;

public record RagQAQueryVO(String groupId, Map<String, String> questionMap, String answer) {
}
