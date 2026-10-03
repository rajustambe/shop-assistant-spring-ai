package com.raju.shop;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

import java.util.List;

// Slice 2 (agentic RAG): retrieval is now a TOOL the model calls only when it needs policy,
// instead of being pre-fetched on every request. Same RAG engine (PolicyRagService), new doorway.
@Component
public class PolicyTools {

    private static final Logger log = LoggerFactory.getLogger(PolicyTools.class);

    private final PolicyRagService rag;

    public PolicyTools(PolicyRagService rag) {
        this.rag = rag;
    }

    @Tool(description = "Search the shop's policy documents for topics like returns, refunds, "
            + "shipping timelines, warranty, cancellation, or payment. Returns the relevant policy text.")
    public String searchPolicy(String question) {
        log.info("    [TOOL] searchPolicy(question=\"{}\") called -> RAG vector search", question);
        List<String> hits = rag.retrieve(question, 3, 0.35);
        log.info("    [TOOL] searchPolicy -> {} matching policy doc(s)", hits.size());
        return hits.isEmpty() ? "No matching policy found." : "- " + String.join("\n- ", hits);
    }
}
