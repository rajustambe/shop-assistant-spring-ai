package com.raju.shop;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

// Slice 3: a tool that calls an EXTERNAL REST API (India Post PIN-code lookup).
// Structurally identical to the DB tool — the only difference is where the data comes from:
// instead of a local DB query, this makes an HTTP call to a service on the internet.
@Component
public class DeliveryTools {

    private static final Logger log = LoggerFactory.getLogger(DeliveryTools.class);

    // Some public APIs (this one included) block Java's default User-Agent behind a WAF,
    // so we send a normal browser-like UA.
    private final RestClient http = RestClient.builder()
            .defaultHeader("User-Agent", "Mozilla/5.0 (compatible; ShopAssistant/1.0)")
            .build();

    @Tool(description = "Check whether the shop can deliver to an Indian PIN code. "
            + "Returns the area, district and state for that PIN code.")
    public String checkDelivery(String pincode) {
        log.info("    [TOOL] checkDelivery(pincode={}) called -> hitting India Post API", pincode);
        try {
            JsonNode arr = http.get()
                    .uri("https://api.postalpincode.in/pincode/{p}", pincode)
                    .retrieve()
                    .body(JsonNode.class);

            JsonNode first = (arr != null && arr.size() > 0) ? arr.get(0) : null;
            if (first == null || !"Success".equals(first.path("Status").asText())) {
                return "PIN code " + pincode + " was not found — we may not deliver there.";
            }

            JsonNode offices = first.path("PostOffice");
            if (offices.isArray() && offices.size() > 0) {
                JsonNode po = offices.get(0);
                String result = "PIN " + pincode + " is in " + po.path("Name").asText()
                        + ", " + po.path("District").asText()
                        + ", " + po.path("State").asText()
                        + ", India — we can deliver here.";
                log.info("    [TOOL] checkDelivery -> {}", result);
                return result;
            }
            return "PIN code " + pincode + " has no serviceable area.";
        } catch (Exception e) {
            log.warn("checkDelivery failed for PIN {}: {}", pincode, e.toString());
            return "Sorry, the delivery lookup service is unavailable right now for PIN " + pincode + ".";
        }
    }
}
