package com.qaagent.model;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name="scan_runs")
public class ScanRun {
    @Id @GeneratedValue(strategy=GenerationType.UUID) private String id;
    @Column(nullable=false) private String projectId;
    @Column(nullable=false) private String status = "QUEUED";
    private String message = "Queued";
    private int pagesDiscovered;
    private int pagesChecked;
    private Instant createdAt = Instant.now();
    private Instant finishedAt;
    protected ScanRun(){}
    public ScanRun(String projectId){this.projectId=projectId;}
    public String getId(){return id;} public String getProjectId(){return projectId;}
    public String getStatus(){return status;} public String getMessage(){return message;}
    public int getPagesDiscovered(){return pagesDiscovered;} public int getPagesChecked(){return pagesChecked;}
    public Instant getCreatedAt(){return createdAt;} public Instant getFinishedAt(){return finishedAt;}
    public void update(String status,String message,int discovered,int checked){
        this.status=status; this.message=message; this.pagesDiscovered=discovered; this.pagesChecked=checked;
        if(status.equals("COMPLETED")||status.equals("FAILED")) this.finishedAt=Instant.now();
    }
}
