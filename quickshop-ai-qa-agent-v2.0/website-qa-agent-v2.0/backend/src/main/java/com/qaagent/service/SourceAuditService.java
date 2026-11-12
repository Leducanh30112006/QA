package com.qaagent.service;

import com.qaagent.model.Project;
import com.qaagent.model.Finding;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

@Service
public class SourceAuditService {
    @Value("${qa.source.workspace:/tmp/qa-agent-source}") private String workspace;
    @Value("${qa.source.local-path:}") private String configuredLocalPath;
    @Value("${GITHUB_TOKEN:}") private String githubToken;

    public List<Finding> audit(Project project, String scanId) {
        List<Finding> out = new ArrayList<>();
        Path root = null;
        try {
            root = resolveSourceRoot(project);
            if (root == null || !Files.isDirectory(root)) {
                out.add(f(scanId,"SOURCE","INFO","Source checkout unavailable","Set sourceRepoUrl to a public GitHub/GitLab repository or mount a read-only checkout at SOURCE_REPO_PATH.",project.getBaseUrl(),"LOW"));
                return out;
            }
            inspectLayout(root, scanId, out);
            inspectFiles(root, scanId, out);
            inspectGradle(root, scanId, out);
        } catch (Exception e) {
            out.add(f(scanId,"SOURCE","MEDIUM","Source audit could not complete","Review worker logs and repository access. The exception is not exposed in the report.",project.getBaseUrl(),"LOW"));
        }
        return out;
    }

    public Path resolveSourceRoot(Project p) throws Exception {
        boolean isDefaultQuickShop = p.getSourceRepoUrl() == null || p.getSourceRepoUrl().isBlank() || p.getSourceRepoUrl().contains("HN_KS24_CNTT2_OJT");
        if (isDefaultQuickShop && configuredLocalPath != null && !configuredLocalPath.isBlank()) {
            Path local = Paths.get(configuredLocalPath).toAbsolutePath().normalize();
            if (Files.isDirectory(local)) {
                try (var stream = Files.list(local)) {
                    if (stream.findAny().isPresent()) return local;
                }
            }
        }
        if (p.getSourceRepoUrl() == null || p.getSourceRepoUrl().isBlank()) return null;
        String repo = p.getSourceRepoUrl();
        if (!repo.matches("https://(github\\.com|gitlab\\.com)/[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+(\\.git)?")) throw new IllegalArgumentException("Unsupported repository URL");
        String cacheKey = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(repo.getBytes(StandardCharsets.UTF_8))).substring(0,20);
        Path dest = Paths.get(workspace, cacheKey).toAbsolutePath().normalize();
        if (Files.exists(dest.resolve(".git"))) {
            Process pull = new ProcessBuilder("git","-C",dest.toString(),"pull","--ff-only").redirectErrorStream(true).start();
            if (!pull.waitFor(60, java.util.concurrent.TimeUnit.SECONDS)) pull.destroyForcibly();
            return dest;
        }
        Files.createDirectories(dest.getParent());
        List<String> cmd = new ArrayList<>(List.of("git","clone","--depth","1",repo,dest.toString()));
        ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true);
        if (githubToken != null && !githubToken.isBlank() && repo.contains("github.com")) {
            String auth = Base64.getEncoder().encodeToString(("x-access-token:" + githubToken).getBytes(StandardCharsets.UTF_8));
            pb.environment().put("GIT_CONFIG_COUNT","1");
            pb.environment().put("GIT_CONFIG_KEY_0","http.extraheader");
            pb.environment().put("GIT_CONFIG_VALUE_0","AUTHORIZATION: basic " + auth);
        }
        Process process = pb.start();
        boolean done = process.waitFor(90, java.util.concurrent.TimeUnit.SECONDS);
        if (!done) { process.destroyForcibly(); throw new IOException("Clone timeout"); }
        if (process.exitValue() != 0) throw new IOException("Clone failed");
        return dest;
    }

    private void inspectLayout(Path root, String scan, List<Finding> out) throws IOException {
        String[] expected = {"api-gateway","identity-service","catalog-service","order-service","payment-service","shipping-service","notification-ai-service","frontend","common-library","deployments"};
        List<String> found = new ArrayList<>();
        for (String d : expected) if (Files.isDirectory(root.resolve(d))) found.add(d);
        if (found.size() < 6) out.add(f(scan,"ARCHITECTURE","INFO","QuickShop service layout partially detected","Detected modules: " + String.join(", ",found),root.toString(),"MEDIUM"));
        else out.add(f(scan,"ARCHITECTURE","INFO","QuickShop microservice modules detected","Detected modules: " + String.join(", ",found),root.toString(),"HIGH"));
        if (!Files.exists(root.resolve("settings.gradle")) && !Files.exists(root.resolve("settings.gradle.kts"))) out.add(f(scan,"BUILD","LOW","Gradle settings file not found","Expected settings.gradle or settings.gradle.kts for the documented multi-module Gradle build.",root.toString(),"MEDIUM"));
    }

    private void inspectFiles(Path root, String scan, List<Finding> out) throws IOException {
        Pattern secret = Pattern.compile("(?i)(api[_-]?key|secret|password|access[_-]?token)\\s*[:=]\\s*[\\\"']?([A-Za-z0-9_./+=-]{16,})");
        Pattern todo = Pattern.compile("(?i)(TODO|FIXME|XXX)");
        int count = 0;
        try (var paths = Files.walk(root)) {
            for (Path file : (Iterable<Path>) paths.filter(Files::isRegularFile)::iterator) {
                if (count++ > 12000) break;
                String rel = root.relativize(file).toString().replace('\\','/');
                if (rel.startsWith(".git/") || rel.contains("/.git/") || rel.contains("/node_modules/") || rel.contains("/build/") || rel.contains("/dist/")) continue;
                long size = Files.size(file); if (size > 1_000_000) continue;
                String name = file.getFileName().toString();
                if (name.equals(".env") || name.startsWith(".env." ) && !name.equals(".env.example")) out.add(f(scan,"SECRETS","HIGH","Environment file committed in source tree","Review whether this file contains live credentials. Rotate exposed secrets; removing the file from the latest commit is not sufficient.",rel,"MEDIUM"));
                if (!(name.endsWith(".java") || name.endsWith(".yml") || name.endsWith(".yaml") || name.endsWith(".properties") || name.endsWith(".json") || name.endsWith(".xml") || name.endsWith(".gradle") || name.endsWith(".kts") || name.endsWith(".ts") || name.endsWith(".tsx") || name.endsWith(".js") || name.endsWith(".env") || name.endsWith(".conf"))) continue;
                String text = Files.readString(file, StandardCharsets.UTF_8);
                Matcher m = secret.matcher(text);
                if (m.find() && !rel.endsWith(".example")) out.add(f(scan,"SECRETS","HIGH","Possible hard-coded secret","A secret-like assignment was found. Verify whether it is a placeholder; if real, revoke and rotate it immediately.",rel,"LOW"));
                if (rel.endsWith("SecurityConfig.java") || rel.endsWith("WebSecurityConfig.java")) {
                    if (text.contains("permitAll") && (text.contains("/admin") || text.contains("/api/**"))) out.add(f(scan,"AUTHZ","HIGH","Broad permitAll rule in security configuration","Review route matcher ordering and ensure administrative/business endpoints require explicit roles.",rel,"LOW"));
                    if (text.contains("csrf.disable()")) out.add(f(scan,"CSRF","MEDIUM","CSRF protection disabled","Confirm the API is stateless and does not authenticate browser requests with ambient cookies; otherwise add CSRF protection.",rel,"MEDIUM"));
                }
                if (rel.endsWith(".java") && (text.contains("@CrossOrigin(origins = \"*\")") || text.contains("@CrossOrigin(origins=\"*\")"))) out.add(f(scan,"CORS","MEDIUM","Wildcard CORS annotation detected","Restrict allowed origins and avoid credentialed wildcard CORS.",rel,"MEDIUM"));
                if (todo.matcher(text).find()) out.add(f(scan,"MAINTAINABILITY","INFO","TODO/FIXME marker found","Review outstanding implementation markers during release readiness review.",rel,"HIGH"));
            }
        }
    }

    private void inspectGradle(Path root, String scan, List<Finding> out) throws IOException {
        Path build = root.resolve("build.gradle"); if (!Files.exists(build)) build = root.resolve("build.gradle.kts");
        if (Files.exists(build)) {
            String t = Files.readString(build);
            if (!t.contains("org.owasp.dependencycheck") && !t.toLowerCase(Locale.ROOT).contains("dependency-check")) out.add(f(scan,"DEPENDENCY","INFO","Dependency vulnerability scanning not detected","Consider adding OWASP Dependency-Check or an SBOM + dependency scanner to CI.",root.relativize(build).toString(),"MEDIUM"));
        }
        if (!Files.exists(root.resolve(".github/workflows"))) out.add(f(scan,"CI","LOW","GitHub Actions workflow directory missing","Add CI gates for backend tests, frontend build, secret scanning, SAST and dependency review.",root.toString(),"HIGH"));
    }

    private Finding f(String scan,String cat,String sev,String title,String desc,String url,String confidence) { return new Finding(scan,cat,sev,title,desc,url,confidence); }
}
