package io.github.raju6302.cifa.github.auth;

/** No GitHub credentials are configured that can serve this request. */
public class MissingCredentialsException extends RuntimeException {

    public MissingCredentialsException(String message) {
        super(message);
    }
}
