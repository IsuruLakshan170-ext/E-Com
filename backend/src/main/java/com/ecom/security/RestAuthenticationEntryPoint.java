package com.ecom.security;

import java.io.IOException;

import org.springframework.http.HttpHeaders;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;

import com.ecom.common.error.ErrorCode;
import com.ecom.common.error.Problems;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

public class RestAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final ProblemWriter problemWriter;

    public RestAuthenticationEntryPoint(ProblemWriter problemWriter) {
        this.problemWriter = problemWriter;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException ex)
            throws IOException {
        boolean badToken = ex instanceof OAuth2AuthenticationException
                || request.getHeader(HttpHeaders.AUTHORIZATION) != null;
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, badToken ? "Bearer error=\"invalid_token\"" : "Bearer");
        problemWriter.write(response, badToken
                ? Problems.of(ErrorCode.INVALID_TOKEN, "Access token is invalid or expired")
                : Problems.of(ErrorCode.UNAUTHENTICATED,
                        "Authentication required: send 'Authorization: Bearer <token>' or 'X-API-Key: <key>'"));
    }
}
