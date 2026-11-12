package com.qaagent.service;

import com.qaagent.model.Finding;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

@Service
public class GeminiAnalysisService {
    @Value("${qa.gemini.api-key:}") private String apiKey;
    @Value("${qa.gemini.model:gemini-2.5-flash}") private String model;
    private final ObjectMapper mapper = new ObjectMapper();

    public Optional<String> summarize(List<Finding> findings) {
        if (findings == null || findings.isEmpty()) return Optional.empty();
        
        // 1. Try Gemini AI if API key is provided
        if (apiKey != null && !apiKey.isBlank()) {
            try {
                StringBuilder evidence = new StringBuilder();
                for (Finding f : findings.stream().limit(50).toList()) {
                    evidence.append("- [").append(f.getSeverity()).append("] ").append(f.getCategory()).append(" | ")
                        .append(f.getTitle()).append(" | ").append(f.getDescription()).append("\n");
                }
                String prompt = "You are an expert QA Lead reviewing evidence from an authorized assessment. " +
                    "Synthesize BOTH Static Code Analysis from SonarQube (SAST, vulnerabilities, code smells) and Dynamic Runtime Browser checks. " +
                    "Prioritize critical business risks (e.g. hard-coded secrets, security vulnerabilities, browser runtime failures, missing security headers). " +
                    "Provide a clear, structured summary and a prioritized remediation plan in Vietnamese. " +
                    "Do not invent vulnerabilities or claim tests passed without evidence. Do not request or infer secrets. Evidence:\n" + evidence;
                ObjectNode body = mapper.createObjectNode();
                ArrayNode parts = body.putArray("contents").addObject().putArray("parts");
                parts.addObject().put("text", prompt);
                body.putObject("generationConfig").put("temperature", 0.2).put("maxOutputTokens", 1200);

                List<String> modelsToTry = List.of(model, "gemini-3.8-flash", "gemini-2.5-flash", "gemini-1.5-flash");
                for (String m : modelsToTry) {
                    try {
                        String endpoint = "https://generativelanguage.googleapis.com/v1beta/models/" + URLEncoder.encode(m, StandardCharsets.UTF_8) + ":generateContent";
                        HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint))
                            .timeout(Duration.ofSeconds(15))
                            .header("Content-Type", "application/json")
                            .header("x-goog-api-key", apiKey)
                            .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body))).build();
                        HttpResponse<String> response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
                        if (response.statusCode() >= 200 && response.statusCode() < 300) {
                            JsonNode root = mapper.readTree(response.body());
                            JsonNode text = root.path("candidates").path(0).path("content").path("parts").path(0).path("text");
                            if (!text.isMissingNode() && !text.asText().isBlank()) {
                                return Optional.of(text.asText());
                            }
                        }
                    } catch (Exception ignored) {}
                }
            } catch (Exception ignored) {}
        }

        // 2. Intelligent Rule-based QA Lead synthesis (always available as fallback)
        long vulnCnt = findings.stream().filter(f -> "SONAR_VULNERABILITY".equals(f.getCategory()) || ("SONARQUBE".equals(f.getTool()) && "HIGH".equalsIgnoreCase(f.getSeverity()))).count();
        long bugCnt = findings.stream().filter(f -> "SONAR_BUG".equals(f.getCategory())).count();
        long smellCnt = findings.stream().filter(f -> "SONAR_CODE_SMELL".equals(f.getCategory())).count();
        long uiDefectCnt = findings.stream().filter(f -> "PLAYWRIGHT".equals(f.getTool()) && !"INFO".equalsIgnoreCase(f.getSeverity())).count();
        long secretCnt = findings.stream().filter(f -> f.getCategory().contains("SECRETS")).count();
        long zapCnt = findings.stream().filter(f -> "OWASP_ZAP".equals(f.getTool()) && !"INFO".equalsIgnoreCase(f.getSeverity())).count();
        String sonarOverviewLine = findings.stream()
                .filter(f -> "SONAR_OVERVIEW".equals(f.getCategory()))
                .map(Finding::getDescription)
                .findFirst()
                .orElse(String.format("%d lỗ hổng bảo mật · %d bugs · %d code smells", vulnCnt, bugCnt, smellCnt));

        String summary = String.format(
            "📋 **BÁO CÁO ĐÁNH GIÁ TỔNG HỢP QA LEAD (SONARQUBE SAST + RUNTIME DAST + PLAYWRIGHT UI)**\n\n" +
            "1. **Chất lượng mã nguồn tĩnh (SonarQube SAST):**\n" +
            "   - %s\n" +
            "   - Cảnh báo bảo mật mã nguồn: %s (%d cảnh báo secret/config).\n\n" +
            "2. **Kiểm thử giao diện & Vận hành thực tế (Playwright UI + OWASP ZAP + REST Assured):**\n" +
            "   - Phát hiện **%d vấn đề giao diện / console / tài nguyên** trong quá trình kiểm thử trình duyệt đa màn hình (Desktop & Mobile).\n" +
            "   - Phát hiện **%d cảnh báo bảo mật HTTP/Headers** từ OWASP ZAP.\n\n" +
            "3. **Kế hoạch khắc phục ưu tiên:**\n" +
            "   - **Mức 1 (Khẩn cấp):** Rà soát và thu hồi các secret/credential lộ trong source code; đưa vào biến môi trường hoặc Vault.\n" +
            "   - **Mức 2 (Bảo mật web):** Bổ sung đầy đủ Security Headers (CSP, Permissions-Policy, Cookie SameSite) và xử lý các lỗi bảo mật trên SonarQube.\n" +
            "   - **Mức 3 (Trải nghiệm giao diện & Ổn định):** Xử lý %d sự cố giao diện/ảnh vỡ/responsive và %d lỗi code tiềm ẩn (Bugs) được đồng bộ từ SonarQube.",
            sonarOverviewLine,
            secretCnt > 0 ? "Phát hiện nguy cơ hard-coded secret trong các file cấu hình và test code" : "Đã rà soát cấu hình bí mật",
            secretCnt,
            uiDefectCnt,
            zapCnt,
            uiDefectCnt,
            bugCnt
        );

        return Optional.of(summary);
    }
}
