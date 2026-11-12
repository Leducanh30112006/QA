package com.qaagent.repo;
import com.qaagent.model.ScanRun;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
public interface ScanRunRepository extends JpaRepository<ScanRun,String> {
    List<ScanRun> findByProjectIdOrderByCreatedAtDesc(String projectId);
    long countByProjectId(String projectId);
}
