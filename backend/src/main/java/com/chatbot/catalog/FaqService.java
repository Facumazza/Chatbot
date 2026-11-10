package com.chatbot.catalog;

import com.chatbot.tenant.TenantContext;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

@Service
public class FaqService {

    private static final int MAX_RESULTS = 3;

    private final JdbcClient jdbc;

    FaqService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<FaqAnswer> search(String query) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        return jdbc.sql("""
                SELECT question, answer FROM frequently_asked_questions
                WHERE tenant_id = :tenantId AND active
                  AND search_vector @@ plainto_tsquery('spanish', :query)
                ORDER BY ts_rank(search_vector, plainto_tsquery('spanish', :query)) DESC, sort_order
                LIMIT :limit
                """)
                .param("tenantId", TenantContext.require())
                .param("query", query)
                .param("limit", MAX_RESULTS)
                .query(FaqAnswer.class)
                .list();
    }

    public record FaqAnswer(String question, String answer) {
    }
}
