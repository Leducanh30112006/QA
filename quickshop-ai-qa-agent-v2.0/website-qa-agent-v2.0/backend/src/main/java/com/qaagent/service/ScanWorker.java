package com.qaagent.service;

import com.qaagent.model.*;
import com.qaagent.repo.*;
import org.springframework.stereotype.Service;
import org.springframework.scheduling.annotation.Async;
import java.util.*;

@Service
public class ScanWorker {
    private final ScanRunRepository scans;
    private final FindingRepository findings;
    private final DiscoveryService discovery;
    private final SourceAuditService sourceAudit;
    private final SonarAuditService sonarAudit;
    private final ApiAuditService apiAudit;
    private final ZapSecurityService zapSecurity;
    private final PerformanceAuditService performanceAudit;
    private final RuntimeProbeService runtimeProbe;
    private final BrowserAuditService browserAudit;
    private final AiExplorationService aiExploration;
    private final GeminiAnalysisService gemini;

    public ScanWorker(ScanRunRepository s, FindingRepository f, DiscoveryService d, 
                      SourceAuditService sourceAudit, SonarAuditService sonarAudit, 
                      ApiAuditService apiAudit, ZapSecurityService zapSecurity,
                      PerformanceAuditService performanceAudit,
                      RuntimeProbeService runtimeProbe, BrowserAuditService browserAudit, 
                      AiExplorationService aiExploration, GeminiAnalysisService gemini) {
        this.scans = s; this.findings = f; this.discovery = d; this.sourceAudit = sourceAudit; 
        this.sonarAudit = sonarAudit; this.apiAudit = apiAudit; this.zapSecurity = zapSecurity;
        this.performanceAudit = performanceAudit; this.runtimeProbe = runtimeProbe; 
        this.browserAudit = browserAudit; this.aiExploration = aiExploration; this.gemini = gemini;
    }

    @Async("taskExecutor")
    public void execute(String scanId, Project project) {
        ScanRun run = scans.findById(scanId).orElseThrow();
        try {
            // 1. Source & Secrets Audit
            run.update("SOURCE_AUDIT", "Kiểm tra cấu trúc mã nguồn và rà quét secrets", 0, 0); scans.save(run);
            findings.saveAll(sourceAudit.audit(project, scanId));

            // 2. SonarQube SAST (Re-scan source code + sync Bugs, Code Smells, Vulnerabilities)
            run.update("SONAR_AUDIT", "SonarQube Scanner: Đang quét lại mã nguồn mới nhất & đồng bộ dữ liệu", 0, 0); scans.save(run);
            findings.saveAll(sonarAudit.audit(project, scanId));

            // 3. API Testing (REST Assured)
            run.update("API_TESTING", "REST Assured: Kiểm thử sức khỏe và độ trễ API Spring Boot", 0, 0); scans.save(run);
            findings.saveAll(apiAudit.audit(project, scanId));

            // 4. Security Testing (OWASP ZAP DAST)
            run.update("SECURITY_TESTING", "OWASP ZAP: Rà quét lỗ hổng DAST, Headers & Cookie bảo mật", 0, 0); scans.save(run);
            findings.saveAll(zapSecurity.audit(project, scanId));

            // 5. Performance Testing (Lighthouse + k6)
            run.update("PERF_TESTING", "Lighthouse & k6: Đo lường Web Vitals và tải đồng thời", 0, 0); scans.save(run);
            findings.saveAll(performanceAudit.audit(project, scanId));

            // 6. Passive Runtime Probe
            run.update("RUNTIME_PROBE", "Kiểm tra kết nối và chứng chỉ mạng HTTP/TLS", 0, 0); scans.save(run);
            findings.saveAll(runtimeProbe.probe(project, scanId));

            // 7. Page Discovery
            run.update("DISCOVERING", "Khám phá danh sách trang giao diện mục tiêu", 0, 0); scans.save(run);
            List<String> pages = discovery.discover(project);
            if (pages.isEmpty()) pages = List.of(project.getBaseUrl());

            // 8. UI Testing (Playwright with Screenshots on error)
            run.update("UI_TESTING", "Playwright: Kiểm tra giao diện và tự động chụp ảnh màn hình lỗi", pages.size(), 0); scans.save(run);
            int checked = 0;
            for (String url : pages) {
                if (!ScopePolicy.inScope(project, url)) continue;
                checked++;
            }
            findings.saveAll(browserAudit.audit(project, scanId, pages));

            // 9. AI Exploration (Stagehand + Gemini)
            run.update("AI_EXPLORATION", "Stagehand AI: Khám phá hành vi người dùng và tương tác DOM", pages.size(), checked); scans.save(run);
            findings.saveAll(aiExploration.explore(project, scanId, pages));

            // 10. Unified AI QA Lead Assessment
            List<Finding> all = findings.findByScanIdOrderByCreatedAtDesc(scanId);
            gemini.summarize(all).ifPresent(summary -> 
                findings.save(new Finding(scanId, "AI_ANALYSIS", "INFO", "Báo cáo Đánh giá QA Lead Hợp nhất", summary, project.getBaseUrl(), "ADVISORY", "GEMINI_AI", null)));

            run.update("COMPLETED", "Hoàn tất kiểm thử đa lớp: UI (Playwright) + AI + API (REST Assured) + ZAP + Lighthouse + SonarQube", pages.size(), checked);
            scans.save(run);
        } catch(Exception ex) {
            run.update("FAILED", "Scan failed: " + ex.getClass().getSimpleName(), 0, 0); scans.save(run);
            findings.save(new Finding(scanId, "SYSTEM", "MEDIUM", "Quá trình quét kiểm thử gặp lỗi", 
                "Xem log hệ thống worker. Chi tiết lỗi đã được cô lập an toàn.", project.getBaseUrl(), "CONFIRMED", "SYSTEM", null));
        }
    }
}
