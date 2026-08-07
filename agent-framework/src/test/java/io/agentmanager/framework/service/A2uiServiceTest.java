package io.agentmanager.framework.service;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class A2uiServiceTest {

    @Test
    void extractShouldParseA2uiCodeBlock() {
        var service = new A2uiService(null);
        var text = "prefix\n```a2ui\n{\"card\":\"hello\"}\n```\nsuffix";

        var result = service.extractA2uiFromText(text);

        assertNotNull(result);
        assertEquals("{\"card\":\"hello\"}", result.get("a2ui_stream"));
    }

    @Test
    void extractShouldTrimEmptyLinesAndIndent() {
        var service = new A2uiService(null);
        var text = "```a2ui\n  line1  \n\n  line2\n```";

        var result = service.extractA2uiFromText(text);

        assertNotNull(result);
        assertEquals("line1\nline2", result.get("a2ui_stream"));
    }

    @Test
    void extractShouldReturnNullWhenNoBlock() {
        var service = new A2uiService(null);
        assertNull(service.extractA2uiFromText("plain text without code fence"));
    }

@Test
    void generateArtifactShouldReturnA2uiWhenInlined() {
        var service = new A2uiService(null);
        var result = service.generateArtifact("surface-1", "```a2ui\n{ \"k\": 1 }\n```");

        assertEquals("A2UI Interface", result.get("name"));
        assertEquals("application/x-a2ui+jsonl", getPart(result).get("mediaType"));
        var data = (Map<String, Object>) getPart(result).get("data");
        assertEquals("{ \"k\": 1 }", data.get("a2ui_stream"));
    }

    @Test
    void generateArtifactShouldFallbackToTextWhenNoA2ui() {
        var service = new A2uiService(null);
        var result = service.generateArtifact("surface-1", "plain text answer");

        assertEquals("surface-1-text", result.get("artifactId"));
        assertEquals("Response", result.get("name"));
        assertNotNull(getPart(result).get("text"));
    }

    @Test
    void getExtensionDeclarationShouldUseDefaultCatalogWhenNull() {
        var service = new A2uiService(null);
        var params = (Map<?, ?>) service.getExtensionDeclaration().get("params");
        assertEquals(List.of(A2uiService.STANDARD_CATALOG), params.get("supportedCatalogIds"));
        assertEquals(true, params.get("acceptsInlineCatalogs"));
    }

    @Test
    void getExtensionDeclarationShouldUseProvidedCatalogId() {
        var service = new A2uiService("https://custom/catalog.json");
        var params = (Map<?, ?>) service.getExtensionDeclaration().get("params");
        assertEquals(List.of("https://custom/catalog.json"), params.get("supportedCatalogIds"));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getPart(Map<String, Object> artifact) {
        return (Map<String, Object>) ((java.util.List<?>) artifact.get("parts")).get(0);
    }
}