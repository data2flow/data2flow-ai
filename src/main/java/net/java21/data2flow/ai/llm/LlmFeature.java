package net.java21.data2flow.ai.llm;

/** 사용량 기능 구분(usage_logs.feature, API-AIA-08 groupBy=feature) */
public enum LlmFeature {
    CHAT,
    COMMENTARY,
    REPORT,
    SCRIPT,
    FLOW_DRAFT,
    ROOT_CAUSE,
    MCP,
    HELP
}
