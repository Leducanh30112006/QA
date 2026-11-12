package com.qaagent.repo;
import com.qaagent.model.Finding;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
public interface FindingRepository extends JpaRepository<Finding,String> {
    List<Finding> findByScanIdOrderByCreatedAtDesc(String scanId);
    long countByScanId(String scanId);
    @Modifying
    @Transactional
    void deleteByScanId(String scanId);
    @Modifying
    @Transactional
    void deleteByScanIdIn(List<String> scanIds);
}
