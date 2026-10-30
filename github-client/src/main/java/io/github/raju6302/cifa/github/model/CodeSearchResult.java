package io.github.raju6302.cifa.github.model;

import java.util.List;

/**
 * Code search hits in one repository.
 *
 * @param totalCount        how many files matched, including those not on this page
 * @param incompleteResults true when GitHub timed out before searching everything
 */
public record CodeSearchResult(int totalCount, boolean incompleteResults, List<Match> matches) {

    /** @param fragments snippets of the file around the matched text */
    public record Match(String path, String sha, List<String> fragments) {
    }
}
