package com.raju.shop;

import com.raju.shop.PolicyRagService.Scored;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

// Slice 2 (agentic RAG): retrieval is a TOOL the model calls only when it needs policy.
// Two-stage pipeline: (1) dense vector retrieve a wider candidate set, (2) hybrid rerank to top-k.
@Component
public class PolicyTools {

    private static final Logger log = LoggerFactory.getLogger(PolicyTools.class);

    // retrieve a wider net, then rerank down — a reranker only helps if it has candidates to reorder.
    private static final int CANDIDATES = 6;
    private static final int TOP_K = 3;
    private static final double THRESHOLD = 0.2;   // looser than before; rerank does the final filtering

    private final PolicyRagService rag;
    private final HybridReranker reranker;

    public PolicyTools(PolicyRagService rag, HybridReranker reranker) {
        this.rag = rag;
        this.reranker = reranker;
    }

    @Tool(description = "Search the shop's policy documents for topics like returns, refunds, "
            + "shipping timelines, warranty, cancellation, or payment. Returns the relevant policy text.")
    public String searchPolicy(String question) {
        log.info("    [TOOL] searchPolicy(question=\"{}\") -> dense retrieve top-{}", question, CANDIDATES);
        List<Scored> candidates = rag.retrieve(question, CANDIDATES, THRESHOLD);
        List<Scored> ranked = reranker.rerank(question, candidates, TOP_K);
        log.info("    [TOOL] searchPolicy -> {} candidates reranked to {} hit(s)", candidates.size(), ranked.size());

        if (ranked.isEmpty()) return "No matching policy found.";
        // Each snippet is cited with its section + page so the model (and auditors) can trace the source.
        return ranked.stream()
                .map(s -> String.format("- %s  (%s, p.%d)",
                        s.chunk().content(), s.chunk().section(), s.chunk().page()))
                .collect(Collectors.joining("\n"));
    }
}
