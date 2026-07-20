package com.modelgate.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "modelgate.console-auth")
public class ConsoleAuthProperties {
    private String jwtSecret = "";
    private String bootstrapAdminPasswordHash = "";
    private boolean developmentDefaultCredentialsEnabled;
    private boolean initializeDevelopmentCredentials;
    private boolean refreshCookieSecure;

    public String getJwtSecret() { return jwtSecret; }
    public void setJwtSecret(String jwtSecret) { this.jwtSecret = jwtSecret; }
    public String getBootstrapAdminPasswordHash() { return bootstrapAdminPasswordHash; }
    public void setBootstrapAdminPasswordHash(String value) { this.bootstrapAdminPasswordHash = value; }
    public boolean isDevelopmentDefaultCredentialsEnabled() { return developmentDefaultCredentialsEnabled; }
    public void setDevelopmentDefaultCredentialsEnabled(boolean value) { this.developmentDefaultCredentialsEnabled = value; }
    public boolean isInitializeDevelopmentCredentials() { return initializeDevelopmentCredentials; }
    public void setInitializeDevelopmentCredentials(boolean value) { this.initializeDevelopmentCredentials = value; }
    public boolean isRefreshCookieSecure() { return refreshCookieSecure; }
    public void setRefreshCookieSecure(boolean refreshCookieSecure) { this.refreshCookieSecure = refreshCookieSecure; }
}
