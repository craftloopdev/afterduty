package com.afterduty.exception;

import org.springframework.http.HttpStatus;

/**
 * An expected, client-facing failure that serializes to the wire shape the spec
 * freezes for the passwordless-OTP endpoints: {@code {"detail": "<message>"}}
 * (see {@code docs/architecture/passwordless-otp-auth-spec.md} §2 — it matches
 * the {@code SecurityConfig} 401 writer and the FastAPI {@code {detail}} the web
 * layer already parses).
 *
 * <p>Rendered by {@code RestExceptionHandler}. The {@link #getMessage() message}
 * is the user-facing {@code detail} — keep OTP failure copy generic
 * (anti-enumeration); never put a raw code, hash, or stack detail in it.
 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;

    public ApiException(HttpStatus status, String detail) {
        super(detail);
        this.status = status;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public static ApiException badRequest(String detail) {
        return new ApiException(HttpStatus.BAD_REQUEST, detail);
    }

    public static ApiException tooManyRequests(String detail) {
        return new ApiException(HttpStatus.TOO_MANY_REQUESTS, detail);
    }

    public static ApiException badGateway(String detail) {
        return new ApiException(HttpStatus.BAD_GATEWAY, detail);
    }

    public static ApiException conflict(String detail) {
        return new ApiException(HttpStatus.CONFLICT, detail);
    }
}
