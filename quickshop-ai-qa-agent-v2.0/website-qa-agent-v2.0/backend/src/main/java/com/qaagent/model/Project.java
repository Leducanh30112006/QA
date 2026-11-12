package com.qaagent.model;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "projects")
public class Project {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private String id;
    @Column(nullable=false) private String name;
    @Column(nullable=false, length=2048) private String baseUrl;
    @Column(nullable=false) private String allowedPathPrefix = "/";
    @Column(length=2048) private String sourceRepoUrl;
    private int maxPages = 30;
    private Instant createdAt = Instant.now();

    protected Project() {}
    public Project(String name, String baseUrl, String prefix, int maxPages) {
        this(name, baseUrl, prefix, maxPages, null);
    }
    public Project(String name, String baseUrl, String prefix, int maxPages, String sourceRepoUrl) {
        this.name=name; this.baseUrl=baseUrl; this.allowedPathPrefix=prefix; this.maxPages=maxPages; this.sourceRepoUrl=sourceRepoUrl;
    }
    public String getId(){return id;} public String getName(){return name;}
    public String getBaseUrl(){return baseUrl;} public String getAllowedPathPrefix(){return allowedPathPrefix;}
    public int getMaxPages(){return maxPages;} public String getSourceRepoUrl(){return sourceRepoUrl;} public Instant getCreatedAt(){return createdAt;}
}
