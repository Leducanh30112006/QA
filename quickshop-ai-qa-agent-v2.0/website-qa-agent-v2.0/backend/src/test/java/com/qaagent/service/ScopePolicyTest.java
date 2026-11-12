package com.qaagent.service;

import com.qaagent.model.Project;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ScopePolicyTest {
    @Test void rejectsLocalhostTarget() {
        assertThrows(IllegalArgumentException.class, () -> ScopePolicy.validateProjectUrl("http://localhost:8080"));
    }
    @Test void acceptsOnlyExactHostAndPathBoundary() {
        Project p = new Project("QuickShop","https://quickshop.io.vn","/api",30);
        assertTrue(ScopePolicy.inScope(p,"https://quickshop.io.vn/api"));
        assertTrue(ScopePolicy.inScope(p,"https://quickshop.io.vn/api/v1/products"));
        assertFalse(ScopePolicy.inScope(p,"https://quickshop.io.vn/apix"));
        assertFalse(ScopePolicy.inScope(p,"https://other.example/api/v1"));
    }
    @Test void rejectsCredentialsAndQueryInTargetUrl() {
        assertThrows(IllegalArgumentException.class, () -> ScopePolicy.validateProjectUrl("https://user:pass@quickshop.io.vn"));
        assertThrows(IllegalArgumentException.class, () -> ScopePolicy.validateProjectUrl("https://quickshop.io.vn/?token=abc"));
    }
}
