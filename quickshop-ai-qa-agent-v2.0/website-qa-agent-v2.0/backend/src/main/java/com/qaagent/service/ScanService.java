package com.qaagent.service;

import com.qaagent.model.Project;
import com.qaagent.model.ScanRun;
import com.qaagent.repo.FindingRepository;
import com.qaagent.repo.ProjectRepository;
import com.qaagent.repo.ScanRunRepository;
import org.springframework.stereotype.Service;
import java.util.List;

@Service
public class ScanService {
    private final ProjectRepository projects;
    private final ScanRunRepository scans;
    private final FindingRepository findings;
    private final ScanWorker scanWorker;

    public ScanService(ProjectRepository projects, ScanRunRepository scans,
                       FindingRepository findings, ScanWorker scanWorker) {
        this.projects = projects;
        this.scans = scans;
        this.findings = findings;
        this.scanWorker = scanWorker;
    }

    public ScanRun start(String projectId) {
        return start(projectId, false);
    }

    public ScanRun start(String projectId, boolean clearPrevious) {
        Project project = projects.findById(projectId).orElseThrow();
        if (clearPrevious) {
            List<String> oldIds = scans.findByProjectIdOrderByCreatedAtDesc(projectId).stream()
                    .map(ScanRun::getId)
                    .toList();
            if (!oldIds.isEmpty()) {
                findings.deleteByScanIdIn(oldIds);
                scans.deleteAllById(oldIds);
            }
        }
        ScanRun run = scans.save(new ScanRun(projectId));
        scanWorker.execute(run.getId(), project);
        return run;
    }
}
