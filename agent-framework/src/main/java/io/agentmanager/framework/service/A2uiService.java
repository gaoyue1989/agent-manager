package io.agentmanager.framework.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

@Service
public class A2uiService {

    public static final String STANDARD_CATALOG = "https://a2ui.org/specification/v0_8/standard_catalog_definition.json";

    private final String catalogId;

    public A2uiService(String catalogId) {
        this.catalogId = catalogId != null ? catalogId : STANDARD_CATALOG;
    }

    public Map<String, Object> extractA2uiFromText(String text) {
        var pattern = java.util.regex.Pattern.compile(
            "```a2ui\\n(.*?)\\n```",
            java.util.regex.Pattern.DOTALL
        );
        var matcher = pattern.matcher(text);
        var lines = new ArrayList<String>();
        if (matcher.find()) {
            for (var line : matcher.group(1).split("\n")) {
                if (!line.trim().isEmpty()) {
                    lines.add(line.trim());
                }
            }
        }
        if (!lines.isEmpty()) {
            return Map.of("a2ui_stream", String.join("\n", lines));
        }
        return null;
    }

    public Map<String, Object> generateArtifact(String surfaceId, String responseText) {
        var a2uiData = extractA2uiFromText(responseText);
        if (a2uiData != null) {
            return artifact(surfaceId, "A2UI Interface", "application/x-a2ui+jsonl", a2uiData);
        }
        return artifact(surfaceId + "-text", "Response", null, Map.of("text", responseText));
    }

    private Map<String, Object> artifact(String id, String name, String mediaType, Map<String, Object> data) {
        var part = new java.util.LinkedHashMap<String, Object>();
        if (mediaType != null) {
            part.put("data", data);
            part.put("mediaType", mediaType);
        } else {
            part.putAll(data);
        }
        return Map.of(
            "artifactId", id,
            "name", name,
            "parts", List.of(part)
        );
    }

    public Map<String, Object> getExtensionDeclaration() {
        return Map.of(
            "uri", "https://a2ui.org/a2a-extension/a2ui/v0.8",
            "params", Map.of(
                "supportedCatalogIds", List.of(catalogId),
                "acceptsInlineCatalogs", true
            )
        );
    }
}
