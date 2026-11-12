package com.qaagent.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qaagent.model.Finding;
import com.qaagent.model.Project;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;

@Service
public class SonarAuditService {
    @Value("${qa.sonar.url:http://sonarqube:9000}")
    private String sonarUrl;

    @Value("${qa.sonar.token:squ_ff8f458c0c37781235070d424a11a961181ed9e1}")
    private String sonarToken;

    @Value("${qa.sonar.project-key:quickshop}")
    private String defaultProjectKey;

    private final SourceAuditService sourceAuditService;
    private final ObjectMapper mapper = new ObjectMapper();

    public SonarAuditService(SourceAuditService sourceAuditService) {
        this.sourceAuditService = sourceAuditService;
    }

    private String authHeader() {
        return "Basic " + Base64.getEncoder().encodeToString((sonarToken + ":").getBytes(StandardCharsets.UTF_8));
    }

    private String baseUrl() {
        return sonarUrl == null ? "" : sonarUrl.replaceAll("/+$", "");
    }

    public String resolveSonarProjectKey(Project project) {
        if (project == null || project.getSourceRepoUrl() == null || project.getSourceRepoUrl().isBlank()
                || project.getSourceRepoUrl().contains("HN_KS24_CNTT2_OJT")) {
            return defaultProjectKey;
        }
        String cleaned = project.getId().replaceAll("[^a-zA-Z0-9_-]", "");
        return "proj_" + cleaned.substring(0, Math.min(cleaned.length(), 16));
    }

    public Map<String, Object> fetchLiveSummary() {
        return fetchLiveSummary(defaultProjectKey);
    }

    /**
     * Fetches exact live project measures & issue breakdown from SonarQube (both MQR & Classic metrics).
     */
    public Map<String, Object> fetchLiveSummary(String targetKey) {
        String key = (targetKey == null || targetKey.isBlank()) ? defaultProjectKey : targetKey;
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("available", false);
        summary.put("projectKey", key);
        summary.put("dashboardUrl", "http://localhost:9000/dashboard?id=" + key);
        if (baseUrl().isBlank()) return summary;

        try {
            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(6)).build();
            String metricsUri = baseUrl() + "/api/measures/component?component=" + key
                    + "&metricKeys=bugs,vulnerabilities,code_smells,security_hotspots,duplicated_lines_density,ncloc,sqale_index,files,security_issues,reliability_issues,maintainability_issues";
            HttpRequest mReq = HttpRequest.newBuilder(URI.create(metricsUri))
                    .timeout(Duration.ofSeconds(10))
                    .header("Authorization", authHeader())
                    .GET().build();
            HttpResponse<String> mRes = client.send(mReq, HttpResponse.BodyHandlers.ofString());

            if (mRes.statusCode() == 200) {
                summary.put("available", true);
                JsonNode root = mapper.readTree(mRes.body());
                JsonNode measures = root.path("component").path("measures");
                int bugs = 0, vulns = 0, smells = 0, hotspots = 0, ncloc = 0, debt = 0, files = 0;
                String dups = "0";
                Map<String, Object> mqrSecurity = Map.of("total", 0);
                Map<String, Object> mqrReliability = Map.of("total", 0);
                Map<String, Object> mqrMaintainability = Map.of("total", 0);

                if (measures.isArray()) {
                    for (JsonNode m : measures) {
                        String k = m.path("metric").asText();
                        String v = m.path("value").asText("0");
                        switch (k) {
                            case "bugs" -> bugs = parseIntSafe(v);
                            case "vulnerabilities" -> vulns = parseIntSafe(v);
                            case "code_smells" -> smells = parseIntSafe(v);
                            case "security_hotspots" -> hotspots = parseIntSafe(v);
                            case "duplicated_lines_density" -> dups = v;
                            case "ncloc" -> ncloc = parseIntSafe(v);
                            case "sqale_index" -> debt = parseIntSafe(v);
                            case "files" -> files = parseIntSafe(v);
                            case "security_issues" -> mqrSecurity = parseMqrJson(v);
                            case "reliability_issues" -> mqrReliability = parseMqrJson(v);
                            case "maintainability_issues" -> mqrMaintainability = parseMqrJson(v);
                        }
                    }
                }

                int totalOpenIssues = bugs + vulns + smells;
                try {
                    String countUri = baseUrl() + "/api/issues/search?componentKeys=" + key + "&resolved=false&ps=1";
                    HttpRequest cReq = HttpRequest.newBuilder(URI.create(countUri))
                            .timeout(Duration.ofSeconds(8))
                            .header("Authorization", authHeader())
                            .GET().build();
                    HttpResponse<String> cRes = client.send(cReq, HttpResponse.BodyHandlers.ofString());
                    if (cRes.statusCode() == 200) {
                        totalOpenIssues = mapper.readTree(cRes.body()).path("total").asInt(totalOpenIssues);
                    }
                } catch (Exception ignored) {}

                summary.put("ncloc", ncloc);
                summary.put("files", files);
                summary.put("duplicatedLinesDensity", dups);
                summary.put("sqaleIndexMinutes", debt);
                summary.put("securityHotspots", hotspots);
                summary.put("vulnerabilities", vulns);
                summary.put("bugs", bugs);
                summary.put("codeSmells", smells);
                summary.put("totalOpenIssues", totalOpenIssues);
                summary.put("securityIssues", mqrSecurity);
                summary.put("reliabilityIssues", mqrReliability);
                summary.put("maintainabilityIssues", mqrMaintainability);
            }
        } catch (Exception e) {
            summary.put("error", e.getMessage());
        }
        return summary;
    }

    public List<Finding> audit(Project project, String scanId) {
        List<Finding> out = new ArrayList<>();
        if (baseUrl().isBlank()) return out;
        String activeKey = resolveSonarProjectKey(project);

        // 0. Re-run sonar-scanner CLI on latest source code and wait for SonarQube CE task completion
        runFreshSonarScan(project, scanId, activeKey, out);

        try {
            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(6)).build();

            // 1. Fetch exact live measures overview (both MQR and Classic metrics)
            Map<String, Object> summary = fetchLiveSummary(activeKey);
            if (Boolean.TRUE.equals(summary.get("available"))) {
                int ncloc = (int) summary.getOrDefault("ncloc", 0);
                int files = (int) summary.getOrDefault("files", 0);
                int totalOpen = (int) summary.getOrDefault("totalOpenIssues", 0);
                int vulns = (int) summary.getOrDefault("vulnerabilities", 0);
                int bugs = (int) summary.getOrDefault("bugs", 0);
                int smells = (int) summary.getOrDefault("codeSmells", 0);
                String dups = String.valueOf(summary.getOrDefault("duplicatedLinesDensity", "0"));
                int debt = (int) summary.getOrDefault("sqaleIndexMinutes", 0);

                int mqrSec = getTotalFromMap(summary.get("securityIssues"));
                int mqrRel = getTotalFromMap(summary.get("reliabilityIssues"));
                int mqrMaint = getTotalFromMap(summary.get("maintainabilityIssues"));

                String sev = (mqrSec > 0 || vulns > 0) ? "HIGH" : ((mqrRel > 0 || bugs > 0) ? "MEDIUM" : "INFO");
                String desc = String.format(
                        "Mã nguồn: %,d dòng code (%d file) | Tổng vấn đề mở: %d | " +
                        "Chuẩn SonarQube MQR: Security: %d · Reliability: %d · Maintainability: %d | " +
                        "Phân loại Classic: Vulnerabilities: %d · Bugs: %d · Code Smells: %d | " +
                        "Trùng lặp: %s%% | Nợ kỹ thuật: %,d phút",
                        ncloc, files, totalOpen, mqrSec, mqrRel, mqrMaint, vulns, bugs, smells, dups, debt);

                out.add(new Finding(scanId, "SONAR_OVERVIEW", sev,
                        "SonarQube: Tổng quan chất lượng & bảo mật mã nguồn",
                        desc, "http://localhost:9000/dashboard?id=" + activeKey, "CONFIRMED", "SONARQUBE", null));
            }

            // 2. Paginate through ALL unresolved issues (ps=500 per page) so counts match SonarQube 100%
            int page = 1;
            int pageSize = 500;
            int maxPages = 10;
            int totalFetched = 0;
            int totalAvailable = Integer.MAX_VALUE;

            while (page <= maxPages && totalFetched < totalAvailable) {
                String issuesUri = baseUrl() + "/api/issues/search?componentKeys="
                        + activeKey + "&types=VULNERABILITY,BUG,CODE_SMELL&resolved=false&ps=" + pageSize + "&p=" + page;
                HttpRequest iReq = HttpRequest.newBuilder(URI.create(issuesUri))
                        .timeout(Duration.ofSeconds(20))
                        .header("Authorization", authHeader())
                        .GET().build();
                HttpResponse<String> iRes = client.send(iReq, HttpResponse.BodyHandlers.ofString());

                if (iRes.statusCode() != 200) break;

                JsonNode root = mapper.readTree(iRes.body());
                totalAvailable = root.path("total").asInt(0);
                JsonNode issues = root.path("issues");
                if (!issues.isArray() || issues.isEmpty()) break;

                for (JsonNode issue : issues) {
                    totalFetched++;
                    String key = issue.path("key").asText();
                    String message = issue.path("message").asText();
                    String type = issue.path("type").asText("CODE_SMELL");
                    String sonarSev = issue.path("severity").asText("MAJOR");
                    String component = issue.path("component").asText();
                    int line = issue.path("line").asInt(0);
                    String rule = issue.path("rule").asText();
                    String debt = issue.path("debt").asText("");

                    List<String> impactQualities = new ArrayList<>();
                    String highestImpactSev = sonarSev;
                    JsonNode impacts = issue.path("impacts");
                    if (impacts.isArray()) {
                        for (JsonNode imp : impacts) {
                            String sq = imp.path("softwareQuality").asText("");
                            String impSev = imp.path("severity").asText("");
                            if (!sq.isBlank()) {
                                impactQualities.add(sq + (impSev.isBlank() ? "" : "(" + impSev + ")"));
                                if ("BLOCKER".equalsIgnoreCase(impSev)) highestImpactSev = "BLOCKER";
                                else if ("HIGH".equalsIgnoreCase(impSev) && !"BLOCKER".equalsIgnoreCase(highestImpactSev)) highestImpactSev = "CRITICAL";
                            }
                        }
                    }

                    String appSev = switch (highestImpactSev.toUpperCase()) {
                        case "BLOCKER", "CRITICAL", "HIGH" -> "HIGH";
                        case "MAJOR", "MEDIUM" -> "MEDIUM";
                        case "MINOR", "LOW" -> "LOW";
                        default -> "INFO";
                    };

                    String typeLabel = switch (type) {
                        case "VULNERABILITY" -> "Lỗ hổng bảo mật";
                        case "BUG" -> "Bug logic tiềm ẩn";
                        case "CODE_SMELL" -> "Code bẩn / Nợ kỹ thuật";
                        default -> type;
                    };

                    String fileLocation = component.replace(activeKey + ":", "");
                    String title = "[" + typeLabel + "] " + message;
                    String mqrSuffix = impactQualities.isEmpty() ? "" : " | MQR: " + String.join(", ", impactQualities);
                    String desc = "File: " + fileLocation + (line > 0 ? " (Dòng: " + line + ")" : "")
                            + (debt.isBlank() ? "" : " | Thời gian sửa ước tính: " + debt)
                            + " | Rule: " + rule + " | Mức Sonar: " + sonarSev + mqrSuffix;
                    String issueUrl = "http://localhost:9000/project/issues?id=" + activeKey + "&open=" + key;

                    out.add(new Finding(scanId, "SONAR_" + type, appSev, title, desc, issueUrl, "CONFIRMED", "SONARQUBE", null));
                }

                if (issues.size() < pageSize) break;
                page++;
            }
        } catch (Exception e) {
            out.add(new Finding(scanId, "SONAR_ERROR", "LOW", "Không thể đồng bộ dữ liệu từ SonarQube",
                    "Kiểm tra container SonarQube hoặc cấu hình mạng: " + e.getMessage(), "http://localhost:9000", "LOW", "SONARQUBE", null));
        }

        return out;
    }

    private void runFreshSonarScan(Project project, String scanId, String activeKey, List<Finding> out) {
        long start = System.currentTimeMillis();
        try {
            Path sourceRoot = sourceAuditService.resolveSourceRoot(project);
            if (sourceRoot == null || !Files.isDirectory(sourceRoot)) {
                out.add(new Finding(scanId, "SONAR_SCAN_EXEC", "LOW",
                        "SonarQube Scanner: Bỏ qua phân tích lại do chưa có thư mục mã nguồn",
                        "Sử dụng dữ liệu hiện có trên SonarQube Server. Hãy cấu hình LOCAL_SOURCE_MOUNT hoặc Git repo để tự động quét lại code.",
                        "http://localhost:9000/dashboard?id=" + activeKey, "CONFIRMED", "SONARQUBE", null));
                return;
            }

            Path workDir = Paths.get("/tmp/sonar-work", activeKey);
            Files.createDirectories(workDir);

            List<String> cmd = List.of(
                    "sonar-scanner",
                    "-Dsonar.projectKey=" + activeKey,
                    "-Dsonar.projectName=" + (project.getName() == null || project.getName().isBlank() ? activeKey : project.getName()),
                    "-Dsonar.sources=.",
                    "-Dsonar.projectBaseDir=" + sourceRoot.toAbsolutePath().normalize(),
                    "-Dsonar.working.directory=" + workDir.toAbsolutePath().normalize(),
                    "-Dsonar.host.url=" + baseUrl(),
                    "-Dsonar.token=" + sonarToken,
                    "-Dsonar.scm.exclusions.disabled=true",
                    "-Dsonar.exclusions=**/node_modules/**,**/build/**,**/dist/**,**/.gradle/**,**/target/**,**/*.min.js,**/graphify-out/**"
            );

            ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true);
            Process process = pb.start();
            StringBuilder lastLines = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.contains("INFO") || line.contains("ERROR") || line.contains("EXECUTION")) {
                        if (lastLines.length() > 1500) lastLines.delete(0, 700);
                        lastLines.append(line).append(" | ");
                    }
                }
            }

            boolean finished = process.waitFor(180, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                out.add(new Finding(scanId, "SONAR_SCAN_EXEC", "MEDIUM",
                        "SonarQube Scanner: Quá thời gian quét (180s)",
                        "Tiến trình sonar-scanner vượt quá 180 giây nên đã được ngắt an toàn; dữ liệu hiển thị là bản phân tích gần nhất.",
                        "http://localhost:9000/dashboard?id=" + activeKey, "CONFIRMED", "SONARQUBE", null));
                return;
            }

            int exitCode = process.exitValue();
            if (exitCode != 0) {
                out.add(new Finding(scanId, "SONAR_SCAN_EXEC", "MEDIUM",
                        "SonarQube Scanner chạy lại gặp lỗi (exit " + exitCode + ")",
                        "Log scanner: " + lastLines,
                        "http://localhost:9000/dashboard?id=" + activeKey, "CONFIRMED", "SONARQUBE", null));
                return;
            }

            // Wait for SonarQube Compute Engine (CE) background task so fresh metrics & issues are ready immediately
            String ceStatus = waitForCeTask(workDir.resolve("report-task.txt"));
            long durationSec = Math.max(1, (System.currentTimeMillis() - start) / 1000);

            out.add(new Finding(scanId, "SONAR_SCAN_EXEC", "INFO",
                    "SonarQube Scanner: Đã quét lại mã nguồn mới nhất thành công (" + durationSec + "s)",
                    String.format("Thư mục mã nguồn: %s | Project Key: %s | Trạng thái xử lý SonarQube Engine: %s",
                            sourceRoot.toAbsolutePath().normalize(), activeKey, ceStatus),
                    "http://localhost:9000/dashboard?id=" + activeKey, "CONFIRMED", "SONARQUBE", null));
        } catch (Exception e) {
            out.add(new Finding(scanId, "SONAR_SCAN_EXEC", "LOW",
                    "SonarQube Scanner: Không thể kích hoạt quét lại tự động",
                    "Chi tiết: " + e.getMessage() + ". Đang đồng bộ từ dữ liệu hiện có trên SonarQube.",
                    "http://localhost:9000/dashboard?id=" + activeKey, "LOW", "SONARQUBE", null));
        }
    }

    private String waitForCeTask(Path reportTaskFile) {
        try {
            if (!Files.exists(reportTaskFile)) return "SUBMITTED";
            String ceTaskId = null;
            for (String line : Files.readAllLines(reportTaskFile, StandardCharsets.UTF_8)) {
                if (line.startsWith("ceTaskId=")) {
                    ceTaskId = line.substring("ceTaskId=".length()).trim();
                    break;
                }
            }
            if (ceTaskId == null || ceTaskId.isBlank()) return "SUBMITTED";

            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
            for (int i = 0; i < 40; i++) {
                HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl() + "/api/ce/task?id=" + ceTaskId))
                        .timeout(Duration.ofSeconds(6))
                        .header("Authorization", authHeader())
                        .GET().build();
                HttpResponse<String> res = client.send(req, HttpResponse.BodyHandlers.ofString());
                if (res.statusCode() == 200) {
                    String status = mapper.readTree(res.body()).path("task").path("status").asText("");
                    if ("SUCCESS".equalsIgnoreCase(status) || "FAILED".equalsIgnoreCase(status) || "CANCELED".equalsIgnoreCase(status)) {
                        return status;
                    }
                }
                Thread.sleep(1500);
            }
            return "IN_PROGRESS";
        } catch (Exception e) {
            return "UNKNOWN";
        }
    }

    private int parseIntSafe(String raw) {
        try { return Integer.parseInt(raw.trim()); } catch (Exception e) { return 0; }
    }

    @SuppressWarnings("unchecked")
    private int getTotalFromMap(Object obj) {
        if (obj instanceof Map<?, ?> m && m.get("total") instanceof Number n) return n.intValue();
        return 0;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseMqrJson(String json) {
        try {
            return mapper.readValue(json, Map.class);
        } catch (Exception e) {
            return Map.of("total", 0);
        }
    }
}
