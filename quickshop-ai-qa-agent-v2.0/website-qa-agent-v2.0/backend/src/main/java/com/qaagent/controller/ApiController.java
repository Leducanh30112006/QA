package com.qaagent.controller;

import com.qaagent.model.*;
import com.qaagent.repo.*;
import com.qaagent.service.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.*;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.*;

@RestController
@RequestMapping("/api")
@CrossOrigin(origins="http://localhost:3000")
public class ApiController {
    private final ProjectRepository projects;
    private final ScanRunRepository scans;
    private final FindingRepository findings;
    private final ScanService scanService;
    private final SonarAuditService sonarAuditService;

    public ApiController(ProjectRepository p, ScanRunRepository s, FindingRepository f,
                         ScanService ss, SonarAuditService sonarAuditService) {
        this.projects = p;
        this.scans = s;
        this.findings = f;
        this.scanService = ss;
        this.sonarAuditService = sonarAuditService;
    }

    public record ProjectRequest(@NotBlank String name, @NotBlank String baseUrl,
        String allowedPathPrefix, @Min(1) @Max(200) Integer maxPages, String sourceRepoUrl){}

    public record ScanSummaryDto(String id, String projectId, String status, String message,
        int pagesDiscovered, int pagesChecked, Instant createdAt, Instant finishedAt, long findingCount){}

    public record BatchDeleteRequest(List<String> scanIds){}

    @GetMapping("/health") public Map<String,Object> health(){return Map.of("status","UP","service","Website QA Agent");}
    @GetMapping("/projects") public List<Project> projects(){return projects.findAll();}

    @PostMapping("/projects") public ResponseEntity<?> create(@Valid @RequestBody ProjectRequest r){
        try{
            var uri=ScopePolicy.validateProjectUrl(r.baseUrl());
            Project p=projects.save(new Project(r.name(),uri.toString(),
                r.allowedPathPrefix()==null?"/":r.allowedPathPrefix(),
                r.maxPages()==null?30:r.maxPages(),validateRepoUrl(r.sourceRepoUrl())));
            return ResponseEntity.status(201).body(p);
        }catch(Exception e){return ResponseEntity.badRequest().body(Map.of("error",e.getMessage()==null?"Invalid project configuration":e.getMessage()));}
    }

    private String validateRepoUrl(String raw) {
        if (raw == null || raw.isBlank()) return null;
        var u = java.net.URI.create(raw.trim());
        if (!"https".equalsIgnoreCase(u.getScheme()) || u.getHost() == null ||
            !(u.getHost().equalsIgnoreCase("github.com") || u.getHost().equalsIgnoreCase("gitlab.com")) || (u.getPort() != -1 && u.getPort() != 443) || u.getQuery() != null || u.getFragment() != null)
            throw new IllegalArgumentException("Source repository must be an HTTPS GitHub or GitLab URL");
        if (u.getUserInfo() != null) throw new IllegalArgumentException("Do not embed credentials in repository URLs");
        return raw.trim();
    }

    @GetMapping("/projects/{id}/scans") public List<ScanSummaryDto> projectScans(@PathVariable String id) {
        if (!projects.existsById(id)) throw new org.springframework.web.server.ResponseStatusException(HttpStatus.NOT_FOUND);
        return scans.findByProjectIdOrderByCreatedAtDesc(id).stream()
                .map(s -> new ScanSummaryDto(
                        s.getId(), s.getProjectId(), s.getStatus(), s.getMessage(),
                        s.getPagesDiscovered(), s.getPagesChecked(),
                        s.getCreatedAt(), s.getFinishedAt(),
                        findings.countByScanId(s.getId())))
                .toList();
    }

    @PostMapping("/projects/{id}/scans") public ResponseEntity<?> start(@PathVariable String id,
                                                                           @RequestParam(defaultValue = "false") boolean clearPrevious){
        try{return ResponseEntity.accepted().body(scanService.start(id, clearPrevious));}
        catch(Exception e){return ResponseEntity.badRequest().body(Map.of("error","Project not found or scan could not start"));}
    }

    @DeleteMapping("/scans/{id}")
    @Transactional
    public ResponseEntity<?> deleteScan(@PathVariable String id) {
        if (!scans.existsById(id)) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Không tìm thấy lượt quét"));
        }
        long deletedFindings = findings.countByScanId(id);
        findings.deleteByScanId(id);
        scans.deleteById(id);
        return ResponseEntity.ok(Map.of("deletedScanId", id, "deletedFindings", deletedFindings));
    }

    @PostMapping("/scans/batch-delete")
    @Transactional
    public ResponseEntity<?> batchDeleteScans(@RequestBody BatchDeleteRequest req) {
        List<String> ids = req == null || req.scanIds() == null ? List.of() : req.scanIds().stream()
                .filter(id -> id != null && !id.isBlank() && scans.existsById(id))
                .distinct()
                .toList();
        if (!ids.isEmpty()) {
            findings.deleteByScanIdIn(ids);
            scans.deleteAllById(ids);
        }
        return ResponseEntity.ok(Map.of("deletedScans", ids.size(), "deletedIds", ids));
    }

    @DeleteMapping("/projects/{id}/scans")
    @Transactional
    public ResponseEntity<?> clearProjectScans(@PathVariable String id,
                                               @RequestParam(defaultValue = "false") boolean keepLatest) {
        if (!projects.existsById(id)) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Project not found"));
        }
        List<ScanRun> all = scans.findByProjectIdOrderByCreatedAtDesc(id);
        List<ScanRun> toDelete = (keepLatest && all.size() > 1) ? all.subList(1, all.size()) : (keepLatest ? List.of() : all);
        List<String> ids = toDelete.stream().map(ScanRun::getId).toList();
        if (!ids.isEmpty()) {
            findings.deleteByScanIdIn(ids);
            scans.deleteAllById(ids);
        }
        return ResponseEntity.ok(Map.of("deletedScans", ids.size(), "keptLatest", keepLatest, "remainingScans", all.size() - ids.size()));
    }

    @GetMapping("/sonar/summary")
    public Map<String, Object> sonarSummary() {
        return sonarAuditService.fetchLiveSummary();
    }

    @GetMapping("/scans/{id}") public ScanRun scan(@PathVariable String id){return scans.findById(id).orElseThrow();}
    @GetMapping("/scans/{id}/findings") public List<Finding> findings(@PathVariable String id){return findings.findByScanIdOrderByCreatedAtDesc(id);}
    @GetMapping("/scans/{id}/report") public Map<String,Object> report(@PathVariable String id){
        ScanRun s=scans.findById(id).orElseThrow();
        List<Finding> f=findings.findByScanIdOrderByCreatedAtDesc(id);
        return Map.of("scan",s,"findings",f,"note","A clean scan is not proof that the target is secure.","project",projects.findById(s.getProjectId()).orElseThrow());
    }
}
