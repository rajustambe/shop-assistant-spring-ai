package com.raju.shop;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.reader.pdf.PagePdfDocumentReader;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.stream.IntStream;

// RAG store with TWO backends behind one retrieve() method:
//   - shop.neon.url blank  -> in-memory vector list (app always boots)
//   - shop.neon.url set    -> pgvector on Neon Postgres (persists across restarts)
//
// PRODUCTION-SHAPED INGESTION:
//   parse PDF -> SMART CHUNK (overlapping windows) + METADATA (section, page) -> embed -> store.
// Unlike naive "one chunk per page", we split each page into overlapping word-windows so a long
// section becomes several focused, retrievable chunks, and we keep each chunk's source section
// heading + page number so answers can be CITED (e.g. "Returns and Exchanges, p.1").
@Service
public class PolicyRagService {

    private static final Logger log = LoggerFactory.getLogger(PolicyRagService.class);

    // Chunking knobs (words). Small overlap keeps a fact from being split across a boundary.
    private static final int CHUNK_WORDS = 60;
    private static final int OVERLAP_WORDS = 15;

    private final EmbeddingModel embeddingModel;
    private final String neonUrl;               // blank => in-memory mode

    private JdbcTemplate jdbc;                    // set only in pgvector mode
    private final List<float[]> memVectors = new ArrayList<>();  // used only in in-memory mode

    // Populated at startup by parseAndChunkPdf() — chunk text PLUS its source metadata.
    private List<PolicyChunk> chunks = new ArrayList<>();

    /** A retrievable piece of a policy doc, with enough metadata to cite its source. */
    public record PolicyChunk(String content, String section, int page) {}

    /** A chunk paired with a relevance score (cosine, or a reranked score). */
    public record Scored(PolicyChunk chunk, double score) {}

    public PolicyRagService(EmbeddingModel embeddingModel,
                            @Value("${shop.neon.url:}") String neonUrl) {
        this.embeddingModel = embeddingModel;
        this.neonUrl = neonUrl == null ? "" : neonUrl.trim();
    }

    // ----------------------------------------------------------------------------------
    // INGESTION PIPELINE: parse the PDF, then smart-chunk each page with metadata.
    // ----------------------------------------------------------------------------------

    /**
     * Parse shop-policies.pdf with Spring AI's PagePdfDocumentReader (one Document per page),
     * then split each page into OVERLAPPING word-windows. The page's first non-blank line is its
     * section heading; the page index is its page number. Both ride along on every chunk.
     */
    private List<PolicyChunk> parseAndChunkPdf() {
        PagePdfDocumentReader reader = new PagePdfDocumentReader(new ClassPathResource("shop-policies.pdf"));
        List<PolicyChunk> out = new ArrayList<>();
        int page = 0;
        for (Document doc : reader.get()) {
            page++;
            String raw = doc.getText();
            if (raw == null || raw.isBlank()) continue;
            String section = firstNonBlankLine(raw);
            String body = raw.replaceAll("\\s+", " ").trim();   // normalise for embedding
            for (String window : splitWithOverlap(body, CHUNK_WORDS, OVERLAP_WORDS)) {
                out.add(new PolicyChunk(window, section, page));
            }
        }
        if (out.isEmpty()) {
            throw new IllegalStateException("shop-policies.pdf produced no chunks");
        }
        return out;
    }

    /** First non-blank line of the raw page text = the section heading (whitespace collapsed:
     *  PDFBox often extracts bold headings with wide inter-letter spacing). */
    private static String firstNonBlankLine(String raw) {
        for (String line : raw.split("\\r?\\n")) {
            String t = line.replaceAll("\\s+", " ").trim();
            if (!t.isBlank()) return t;
        }
        return "Policy";
    }

    /**
     * Sliding-window chunker: cut the text into windows of {@code size} words that overlap by
     * {@code overlap} words, so a fact near a boundary still appears whole in one window.
     */
    private static List<String> splitWithOverlap(String text, int size, int overlap) {
        String[] words = text.split("\\s+");
        List<String> parts = new ArrayList<>();
        if (words.length <= size) {
            parts.add(text);
            return parts;
        }
        int step = size - overlap;                   // how far the window advances each time
        for (int start = 0; start < words.length; start += step) {
            int end = Math.min(start + size, words.length);
            parts.add(String.join(" ", Arrays.copyOfRange(words, start, end)));
            if (end == words.length) break;
        }
        return parts;
    }

    @PostConstruct
    void index() {
        // 1. PARSE PDF + 2. SMART CHUNK (+metadata)
        this.chunks = parseAndChunkPdf();
        log.info("RAG ingestion: parsed shop-policies.pdf -> {} chunks (size={}, overlap={} words)",
                chunks.size(), CHUNK_WORDS, OVERLAP_WORDS);
        chunks.forEach(c -> log.info("   chunk [{} p.{}]: {}", c.section(), c.page(),
                c.content().length() > 50 ? c.content().substring(0, 50) + "..." : c.content()));

        // 3. EMBED + 4. STORE
        if (neonUrl.isBlank()) {
            log.info("RAG: shop.neon.url not set -> using IN-MEMORY vector store.");
            for (PolicyChunk c : chunks) memVectors.add(embeddingModel.embed(c.content()));
            log.info("RAG: embedded {} chunks ({}-dim vectors).", chunks.size(),
                    memVectors.isEmpty() ? 0 : memVectors.get(0).length);
            return;
        }

        log.info("RAG: using PGVECTOR on Neon.");
        this.jdbc = new JdbcTemplate(neonDataSource(neonUrl));
        jdbc.execute("CREATE EXTENSION IF NOT EXISTS vector");
        // 768 dims to match nomic-embed-text. section/page columns carry the citation metadata.
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS policy_docs (
                id        serial PRIMARY KEY,
                content   text NOT NULL,
                section   text,
                page      int,
                embedding vector(768)
            )
            """);
        jdbc.update("TRUNCATE policy_docs");   // re-embed fresh (schema/chunking changed)
        log.info("pgvector: embedding and inserting {} chunks into Neon...", chunks.size());
        for (PolicyChunk c : chunks) {
            jdbc.update("INSERT INTO policy_docs (content, section, page, embedding) VALUES (?, ?, ?, ?::vector)",
                    c.content(), c.section(), c.page(), toVectorLiteral(embeddingModel.embed(c.content())));
        }
        log.info("pgvector: done.");
    }

    /**
     * Retrieve up to k chunks ranked by cosine similarity, above the threshold.
     * This is the DENSE (semantic) stage; a reranker can re-score the candidates afterwards.
     */
    public List<Scored> retrieve(String query, int k, double threshold) {
        float[] q = embeddingModel.embed(query);
        return (jdbc == null) ? retrieveInMemory(q, k, threshold)
                              : retrievePgvector(q, k, threshold);
    }

    private List<Scored> retrieveInMemory(float[] q, int k, double threshold) {
        return IntStream.range(0, chunks.size())
                .mapToObj(i -> new Scored(chunks.get(i), cosine(q, memVectors.get(i))))
                .filter(s -> s.score() >= threshold)
                .sorted(Comparator.comparingDouble(Scored::score).reversed())
                .limit(k)
                .toList();
    }

    private List<Scored> retrievePgvector(float[] q, int k, double threshold) {
        String qv = toVectorLiteral(q);
        // <=> is pgvector cosine DISTANCE; similarity = 1 - distance. Postgres does the ranking.
        List<Scored> rows = jdbc.query(
                "SELECT content, section, page, 1 - (embedding <=> ?::vector) AS score " +
                "FROM policy_docs ORDER BY embedding <=> ?::vector LIMIT ?",
                (rs, i) -> new Scored(
                        new PolicyChunk(rs.getString("content"), rs.getString("section"), rs.getInt("page")),
                        rs.getDouble("score")),
                qv, qv, k);
        return rows.stream().filter(s -> s.score() >= threshold).toList();
    }

    /** Build a JDBC DataSource from a postgresql://user:pass@host/db?... URL. */
    private static DataSource neonDataSource(String raw) {
        URI uri = URI.create(raw);
        String[] creds = uri.getUserInfo().split(":", 2);
        String host = uri.getHost() + (uri.getPort() == -1 ? "" : ":" + uri.getPort());
        String jdbcUrl = "jdbc:postgresql://" + host + uri.getPath() + "?sslmode=require";

        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setDriverClassName("org.postgresql.Driver");
        ds.setUrl(jdbcUrl);
        ds.setUsername(creds[0]);
        ds.setPassword(creds.length > 1 ? creds[1] : "");
        return ds;
    }

    private static String toVectorLiteral(float[] v) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < v.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(v[i]);
        }
        return sb.append(']').toString();
    }

    private static double cosine(float[] a, float[] b) {
        double dot = 0, na = 0, nb = 0;
        for (int i = 0; i < a.length; i++) { dot += a[i] * b[i]; na += a[i] * a[i]; nb += b[i] * b[i]; }
        return dot / (Math.sqrt(na) * Math.sqrt(nb) + 1e-9);
    }
}
