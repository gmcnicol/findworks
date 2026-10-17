package com.findworks.web;

import java.net.URI;

public final class SignInDestination {
    private SignInDestination() {}

    public static String safe(String candidate) {
        if (candidate == null || candidate.length() > 4096 || candidate.contains("\\")) return "/work";
        try {
            URI uri = URI.create(candidate);
            String path = uri.getPath();
            if (uri.isAbsolute() || uri.getRawAuthority() != null || path == null || path.contains("\\")
                    || !path.equals(uri.getRawPath()) || !uri.normalize().getPath().equals(path)) return "/work";
            return path.equals("/app") || path.startsWith("/app/") || path.equals("/oauth/authorize")
                    || path.equals("/oauth/grants") || path.equals("/work") || path.equals("/interviews")
                    || path.startsWith("/invite/") || path.matches("/interview/[0-9a-fA-F-]{36}") ? candidate : "/work";
        } catch (IllegalArgumentException invalid) { return "/work"; }
    }
}
