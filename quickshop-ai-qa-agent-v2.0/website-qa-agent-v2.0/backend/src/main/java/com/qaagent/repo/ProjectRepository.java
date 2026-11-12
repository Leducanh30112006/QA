package com.qaagent.repo;
import com.qaagent.model.Project;
import org.springframework.data.jpa.repository.JpaRepository;
public interface ProjectRepository extends JpaRepository<Project,String> {}
