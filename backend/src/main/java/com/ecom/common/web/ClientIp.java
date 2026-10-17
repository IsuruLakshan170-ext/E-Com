package com.ecom.common.web;

import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Uses the socket address only. X-Forwarded-For is deliberately NOT parsed here: behind the nginx proxy, the
 * behind-proxy profile (BE-4) turns on Tomcat's RemoteIpValve, which takes the client IP from that header only
 * for connections from the explicitly trusted proxy addresses, so getRemoteAddr() is already the real client.
 */
public final class ClientIp {

    private ClientIp() {
    }

    public static String current() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs) {
            return attrs.getRequest().getRemoteAddr();
        }
        return null;
    }
}
