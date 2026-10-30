package io.github.raju6302.cifa.github.model;

import java.time.Instant;

/** A commit in a branch's history, without its diff. */
public record CommitSummary(String sha, String message, String authorName, Instant authoredAt) {

    /** The first line of the message, which by convention summarizes the change. */
    public String subject() {
        if (message == null) {
            return "";
        }
        int newline = message.indexOf('\n');
        return (newline < 0 ? message : message.substring(0, newline)).strip();
    }
}
