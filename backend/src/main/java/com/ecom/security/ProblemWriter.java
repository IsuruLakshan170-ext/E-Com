package com.ecom.security;

import java.io.IOException;

import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.servlet.http.HttpServletResponse;

/** Writes problem+json from filters, which run outside the controller advice. */
@Component
public class ProblemWriter {

    private final ObjectMapper objectMapper;

    public ProblemWriter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public void write(HttpServletResponse response, ProblemDetail problem) throws IOException {
        response.setStatus(problem.getStatus());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), problem);
    }
}
