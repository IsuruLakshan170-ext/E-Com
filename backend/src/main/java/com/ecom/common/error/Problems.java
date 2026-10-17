package com.ecom.common.error;

import java.net.URI;
import java.util.Locale;

import org.slf4j.MDC;
import org.springframework.http.ProblemDetail;

import com.ecom.common.web.RequestIdFilter;

/** Builds the single RFC 7807 error shape used by every error path (controllers and security filters). */
public final class Problems {

    private Problems() {
    }

    public static ProblemDetail of(ErrorCode code, String detail) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(code.status(), detail);
        pd.setType(URI.create("urn:ecom:error:" + code.name().toLowerCase(Locale.ROOT)));
        pd.setTitle(code.status().getReasonPhrase());
        pd.setProperty("code", code.name());
        pd.setProperty("retryable", code.retryable());
        String requestId = MDC.get(RequestIdFilter.MDC_KEY);
        if (requestId != null) {
            pd.setProperty("requestId", requestId);
        }
        return pd;
    }
}
