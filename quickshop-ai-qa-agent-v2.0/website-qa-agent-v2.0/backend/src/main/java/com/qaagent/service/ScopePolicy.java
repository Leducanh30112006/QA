package com.qaagent.service;

import com.qaagent.model.Project;
import java.net.*;
import java.util.*;

public final class ScopePolicy {
    private ScopePolicy() {}
    public static URI validateProjectUrl(String raw) {
        try {
            URI u = URI.create(raw.trim()).normalize();
            if (!("http".equalsIgnoreCase(u.getScheme()) || "https".equalsIgnoreCase(u.getScheme()))) throw new IllegalArgumentException("Only HTTP/HTTPS URLs are allowed");
            if (u.getHost() == null || u.getUserInfo() != null || u.getFragment() != null || u.getQuery() != null) throw new IllegalArgumentException("Invalid target URL");
            String host = u.getHost().toLowerCase(Locale.ROOT);
            if (host.equals("localhost") || host.endsWith(".localhost") || host.endsWith(".local")) throw new IllegalArgumentException("Local/private hostnames are blocked by default");
            for (InetAddress a : InetAddress.getAllByName(host)) if (isNonPublic(a)) throw new IllegalArgumentException("Target resolves to a non-public IP and is blocked by default");
            return u;
        } catch (UnknownHostException e) { throw new IllegalArgumentException("Target hostname cannot be resolved"); }
    }
    public static boolean inScope(Project p, String raw) {
        try {
            URI base = URI.create(p.getBaseUrl()), target = URI.create(raw).normalize();
            if (target.getUserInfo() != null || target.getHost() == null ||
                !base.getScheme().equalsIgnoreCase(target.getScheme()) || !base.getHost().equalsIgnoreCase(target.getHost()) || effectivePort(base) != effectivePort(target)) return false;
            String prefix = p.getAllowedPathPrefix(); if (prefix == null || prefix.isBlank()) prefix = "/"; if (!prefix.startsWith("/")) prefix = "/" + prefix;
            String path = target.getPath() == null ? "/" : target.getPath();
            if (path.contains("\\")) return false;
            if (!prefix.endsWith("/")) prefix += "/";
            String exactPrefix = p.getAllowedPathPrefix() == null ? "/" : p.getAllowedPathPrefix();
            return path.equals(exactPrefix) || path.startsWith(prefix) || exactPrefix.equals("/");
        } catch (Exception e) { return false; }
    }
    private static int effectivePort(URI u) { return u.getPort() != -1 ? u.getPort() : ("https".equalsIgnoreCase(u.getScheme()) ? 443 : 80); }
    private static boolean isNonPublic(InetAddress a) { return a.isAnyLocalAddress() || a.isLoopbackAddress() || a.isLinkLocalAddress() || a.isSiteLocalAddress() || a.isMulticastAddress(); }
}
