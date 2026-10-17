package com.ecom.security;

import java.io.IOException;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;

import com.ecom.audit.AuditAction;
import com.ecom.audit.AuditService;
import com.ecom.common.error.ErrorCode;
import com.ecom.common.error.Problems;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

public class RestAccessDeniedHandler implements AccessDeniedHandler {

    private final ProblemWriter problemWriter;
    private final AuditService audit;

    public RestAccessDeniedHandler(ProblemWriter problemWriter, AuditService audit) {
        this.problemWriter = problemWriter;
        this.audit = audit;
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException ex)
            throws IOException {
        audit.recordThrottled(AuditService.callerBucket(request.getRemoteAddr()), AuditAction.ACCESS_DENIED, "endpoint",
                null, request.getMethod() + " " + request.getRequestURI());
        problemWriter.write(response, Problems.of(ErrorCode.FORBIDDEN, "Missing required permission (scope) for this operation"));
    }
}
