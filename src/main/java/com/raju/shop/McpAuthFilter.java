package com.raju.shop;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

// Slice 5 (auth): protect the MCP endpoint. Only callers presenting the right
// "Authorization: Bearer <token>" may reach /mcp. This is the same bearer-token
// mechanism real OAuth2 uses — here the token is a shared secret instead of one
// minted by an auth server. If shop.mcp.token is blank, auth is disabled.
@Component
public class McpAuthFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(McpAuthFilter.class);

    @Value("${shop.mcp.token:}")
    private String expectedToken;

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {

        if (!expectedToken.isBlank() && req.getRequestURI().startsWith("/mcp")) {
            String auth = req.getHeader("Authorization");
            if (auth == null || !auth.equals("Bearer " + expectedToken)) {
                log.warn("MCP auth REJECTED for {} (missing/invalid bearer token)", req.getRequestURI());
                res.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                res.setContentType("application/json");
                res.getWriter().write("{\"error\":\"unauthorized\"}");
                return;
            }
        }
        chain.doFilter(req, res);
    }
}
