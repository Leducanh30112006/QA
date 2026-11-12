package com.qaagent.model;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name="findings")
public class Finding {
    @Id @GeneratedValue(strategy=GenerationType.UUID) private String id;
    private String scanId;
    private String category;
    private String severity;
    @Column(length=3000) private String title;
    @Column(length=6000) private String description;
    @Column(length=2048) private String url;
    private String confidence = "INFO";
    private String tool = "SYSTEM";
    @Column(columnDefinition="TEXT")
    private String screenshot;
    private Instant createdAt = Instant.now();

    protected Finding(){}

    public Finding(String scanId,String category,String severity,String title,String description,String url,String confidence){
        this(scanId, category, severity, title, description, url, confidence, "SYSTEM", null);
    }

    public Finding(String scanId,String category,String severity,String title,String description,String url,String confidence,String tool){
        this(scanId, category, severity, title, description, url, confidence, tool, null);
    }

    public Finding(String scanId,String category,String severity,String title,String description,String url,String confidence,String tool,String screenshot){
        this.scanId=scanId;this.category=category;this.severity=severity;this.title=title;
        this.description=description;this.url=url;this.confidence=confidence;
        this.tool=tool != null ? tool : "SYSTEM";
        this.screenshot=screenshot;
    }

    public String getId(){return id;} 
    public String getScanId(){return scanId;}
    public String getCategory(){return category;} 
    public String getSeverity(){return severity;}
    public String getTitle(){return title;} 
    public String getDescription(){return description;}
    public String getUrl(){return url;} 
    public String getConfidence(){return confidence;}
    public String getTool(){return tool;}
    public String getScreenshot(){return screenshot;}
    public Instant getCreatedAt(){return createdAt;}
}
