package com.raju.shop;

import com.raju.shop.PolicyRagService.Scored;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.net.URI;
import java.util.List;
import java.util.Map;

// INSPECTION-ONLY (not a real assertion test): prints exactly what landed in Neon pgvector,
// plus the REAL cosine (from pgvector <=>) and REAL hybrid-rerank scores for one query.
// Runs only when NEON_DATABASE_URL is set (so it maps to shop.neon.url -> pgvector mode).
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "GROQ_API_KEY", matches = ".+")
class PgVectorInspectTest {

    @Autowired PolicyRagService rag;
    @Autowired HybridReranker reranker;
    @Value("${shop.neon.url:}") String neonUrl;

    @Test
    void showPgVectorData() {
        if (!neonUrl.isBlank()) {
            JdbcTemplate jdbc = new JdbcTemplate(ds(neonUrl));
            System.out.println("\n===== policy_docs ROWS stored in Neon pgvector =====");
            String sql = "SELECT id, section, page, left(content,55) AS preview, " +
                    "left(embedding::text,42) AS vec, " +
                    "array_length(string_to_array(trim(both '[]' from embedding::text), ','),1) AS dims " +
                    "FROM policy_docs ORDER BY id";
            for (Map<String, Object> r : jdbc.queryForList(sql)) {
                System.out.printf("#%02d [%s p.%s]  dims=%s%n     text: %s...%n     vec : %s ...]%n",
                        r.get("id"), r.get("section"), r.get("page"), r.get("dims"),
                        r.get("preview"), r.get("vec"));
            }
        } else {
            System.out.println("\n(NEON not set -> IN-MEMORY mode; scores below are the SAME math pgvector uses)");
        }

        String q = "what is your return policy?";
        System.out.println("\n===== STAGE 7: dense retrieve top-6 (REAL pgvector cosine) =====");
        System.out.println("query: " + q);
        List<Scored> cands = rag.retrieve(q, 6, 0.2);
        for (Scored s : cands)
            System.out.printf("  cosine=%.4f  [%s p.%d]  %.48s%n",
                    s.score(), s.chunk().section(), s.chunk().page(), s.chunk().content());

        System.out.println("\n===== STAGE 8: hybrid rerank -> top-3 (REAL) =====");
        List<Scored> ranked = reranker.rerank(q, cands, 3);
        for (Scored s : ranked)
            System.out.printf("  hybrid=%.4f  [%s p.%d]  %.48s%n",
                    s.score(), s.chunk().section(), s.chunk().page(), s.chunk().content());
        System.out.println("====================================================\n");
    }

    /** Same postgresql://user:pass@host/db URL parsing the app uses, for an independent connection. */
    private static DataSource ds(String raw) {
        URI uri = URI.create(raw);
        String[] creds = uri.getUserInfo().split(":", 2);
        String host = uri.getHost() + (uri.getPort() == -1 ? "" : ":" + uri.getPort());
        DriverManagerDataSource d = new DriverManagerDataSource();
        d.setDriverClassName("org.postgresql.Driver");
        d.setUrl("jdbc:postgresql://" + host + uri.getPath() + "?sslmode=require");
        d.setUsername(creds[0]);
        d.setPassword(creds.length > 1 ? creds[1] : "");
        return d;
    }
}
