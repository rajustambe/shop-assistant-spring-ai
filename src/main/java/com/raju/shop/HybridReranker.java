package com.raju.shop;

import com.raju.shop.PolicyRagService.Scored;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

// SECOND-STAGE RERANKER (hybrid: semantic + keyword).
//
// The vector retriever is great at MEANING but blind to exact terms (SKUs, "COD", "EMI", "411001").
// A pure keyword search is the opposite. Production RAG combines both and reranks, because that
// one step is usually the biggest quality win. Here we keep it dependency-free and deterministic:
//
//   finalScore = ALPHA * (normalised cosine)  +  (1 - ALPHA) * (keyword overlap)
//
// In a real system you'd often swap this for a trained cross-encoder (e.g. Cohere Rerank or a
// local bge-reranker). The INTERFACE is the same: take candidates, return a better-ordered top-k.
@Component
public class HybridReranker {

    private static final double ALPHA = 0.7;     // weight on semantic vs keyword signal
    // tiny stopword list so "what is the return policy" keys on {return, policy}, not {what, is, the}
    private static final Set<String> STOP = Set.of(
            "the", "is", "a", "an", "of", "to", "do", "you", "i", "my", "what", "how", "can",
            "for", "on", "in", "are", "and", "me", "your", "it", "this", "that", "with");

    /** Re-score the retriever's candidates with cosine + keyword overlap and return the top-k. */
    public List<Scored> rerank(String query, List<Scored> candidates, int topK) {
        if (candidates.isEmpty()) return candidates;

        Set<String> queryTerms = terms(query);

        // Min-max normalise cosine across THIS candidate set so it's comparable to the [0,1] keyword score.
        double min = candidates.stream().mapToDouble(Scored::score).min().orElse(0);
        double max = candidates.stream().mapToDouble(Scored::score).max().orElse(1);
        double span = (max - min) < 1e-9 ? 1 : (max - min);

        return candidates.stream()
                .map(c -> {
                    double cosNorm = (c.score() - min) / span;
                    double keyword = keywordOverlap(queryTerms, c.chunk().content());
                    double hybrid = ALPHA * cosNorm + (1 - ALPHA) * keyword;
                    return new Scored(c.chunk(), hybrid);
                })
                .sorted(Comparator.comparingDouble(Scored::score).reversed())
                .limit(topK)
                .toList();
    }

    /** Fraction of the query's content words that appear in the chunk (0..1). */
    private static double keywordOverlap(Set<String> queryTerms, String content) {
        if (queryTerms.isEmpty()) return 0;
        Set<String> chunkTerms = terms(content);
        long hits = queryTerms.stream().filter(chunkTerms::contains).count();
        return (double) hits / queryTerms.size();
    }

    /** Lowercase, split on non-letters/digits, drop stopwords and 1-char tokens. */
    private static Set<String> terms(String text) {
        return Arrays.stream(text.toLowerCase().split("[^a-z0-9]+"))
                .filter(t -> t.length() > 1 && !STOP.contains(t))
                .collect(Collectors.toSet());
    }
}
