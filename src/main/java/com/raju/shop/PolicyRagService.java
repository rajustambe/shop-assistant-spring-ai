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
import java.util.Comparator;
import java.util.List;
import java.util.stream.IntStream;

// RAG store with TWO backends behind one retrieve() method:
//   - shop.neon.url blank  -> in-memory vector list (app always boots)
//   - shop.neon.url set    -> pgvector on Neon Postgres (persists across restarts)
//
// UPGRADE: instead of 6 hardcoded strings, we now PARSE a real PDF (shop-policies.pdf)
// and CHUNK it into retrievable pieces. This mirrors a production ingestion pipeline:
//   parse PDF -> chunk -> embed each chunk -> store.
// The PDF is authored one policy section per page, so page-based chunking (one
// Document per page from PagePdfDocumentReader) yields one clean chunk per section.
@Service
public class PolicyRagService {

    private static final Logger log = LoggerFactory.getLogger(PolicyRagService.class);

    private final EmbeddingModel embeddingModel;
    private final String neonUrl;               // blank => in-memory mode

    private JdbcTemplate jdbc;                    // set only in pgvector mode
    private final List<float[]> memVectors = new ArrayList<>();  // used only in in-memory mode

    // Populated at startup by loadAndChunk() — no longer hardcoded.
    private List<String> docs = new ArrayList<>();

    public PolicyRagService(EmbeddingModel embeddingModel,
                            @Value("${shop.neon.url:}") String neonUrl) {
        this.embeddingModel = embeddingModel;
        this.neonUrl = neonUrl == null ? "" : neonUrl.trim();
    }

    // ----------------------------------------------------------------------------------
    // INGESTION PIPELINE: parse the PDF (one page per section), then collect chunks.
    // ----------------------------------------------------------------------------------

    /**
     * Parse shop-policies.pdf with Spring AI's PagePdfDocumentReader, which returns ONE
     * Document per PDF page. The PDF is authored one policy section per page, so each page's
     * text is already a self-contained chunk (Returns, Shipping, Warranty, ...).
     * Whitespace is normalised so the embedding sees clean text.
     */
    private List<String> parseAndChunkPdf() {
        PagePdfDocumentReader reader = new PagePdfDocumentReader(new ClassPathResource("shop-policies.pdf"));
        List<String> chunks = new ArrayList<>();
        for (Document page : reader.get()) {
            String chunk = page.getText().replaceAll("\\s+", " ").trim();
            if (!chunk.isBlank()) chunks.add(chunk);
        }
        if (chunks.isEmpty()) {
            throw new IllegalStateException("shop-policies.pdf produced no chunks");
        }
        return chunks;
    }

    @PostConstruct
    void index() {
        // 1. PARSE PDF + 2. CHUNK (one page == one section == one chunk)
        this.docs = parseAndChunkPdf();
        log.info("RAG ingestion: parsed shop-policies.pdf -> {} chunks", docs.size());
        docs.forEach(c -> log.info("   chunk: {}", c.length() > 60 ? c.substring(0, 60) + "..." : c));

        // 3. EMBED + 4. STORE
        if (neonUrl.isBlank()) {
            log.info("RAG: shop.neon.url not set -> using IN-MEMORY vector store.");
            for (String d : docs) memVectors.add(embeddingModel.embed(d));
            log.info("RAG: embedded {} chunks ({}-dim vectors).", docs.size(),
                    memVectors.isEmpty() ? 0 : memVectors.get(0).length);
            return;
        }

        log.info("RAG: using PGVECTOR on Neon.");
        this.jdbc = new JdbcTemplate(neonDataSource(neonUrl));
        jdbc.execute("CREATE EXTENSION IF NOT EXISTS vector");
        // 768 dims to match nomic-embed-text (was 384 for all-MiniLM).
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS policy_docs (
                id        serial PRIMARY KEY,
                content   text NOT NULL,
                embedding vector(768)
            )
            """);
        jdbc.update("TRUNCATE policy_docs");   // re-embed fresh (model/dims changed)
        log.info("pgvector: embedding and inserting {} chunks into Neon...", docs.size());
        for (String d : docs) {
            jdbc.update("INSERT INTO policy_docs (content, embedding) VALUES (?, ?::vector)",
                    d, toVectorLiteral(embeddingModel.embed(d)));
        }
        log.info("pgvector: done.");
    }

    private record Scored(String content, double score) {}

    /** Retrieve up to k policy snippets ranked by cosine similarity, above the threshold. */
    public List<String> retrieve(String query, int k, double threshold) {
        float[] q = embeddingModel.embed(query);
        return (jdbc == null) ? retrieveInMemory(q, k, threshold)
                              : retrievePgvector(q, k, threshold);
    }

    private List<String> retrieveInMemory(float[] q, int k, double threshold) {
        return IntStream.range(0, docs.size())
                .mapToObj(i -> new Scored(docs.get(i), cosine(q, memVectors.get(i))))
                .filter(s -> s.score() >= threshold)
                .sorted(Comparator.comparingDouble(Scored::score).reversed())
                .limit(k)
                .map(Scored::content)
                .toList();
    }

    private List<String> retrievePgvector(float[] q, int k, double threshold) {
        String qv = toVectorLiteral(q);
        // <=> is pgvector cosine DISTANCE; similarity = 1 - distance. Postgres does the ranking.
        List<Scored> rows = jdbc.query(
                "SELECT content, 1 - (embedding <=> ?::vector) AS score " +
                "FROM policy_docs ORDER BY embedding <=> ?::vector LIMIT ?",
                (rs, i) -> new Scored(rs.getString("content"), rs.getDouble("score")),
                qv, qv, k);
        return rows.stream().filter(s -> s.score() >= threshold).map(Scored::content).toList();
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
