package io.github.raju6302.cifa.github;

import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.notFound;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import io.github.raju6302.cifa.github.model.CodeSearchResult;
import io.github.raju6302.cifa.github.model.Commit;
import io.github.raju6302.cifa.github.model.CommitSummary;
import io.github.raju6302.cifa.github.model.FileContent;
import io.github.raju6302.cifa.github.model.GitTree;
import io.github.raju6302.cifa.github.model.WorkflowRun;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.util.unit.DataSize;

/** The repository-reading calls the MCP server uses: commits, files, search, runs and trees. */
class GitHubRepoContentTest {

    @RegisterExtension
    static WireMockExtension github = WireMockExtension.newInstance().options(wireMockConfig().dynamicPort()).build();

    GitHubClient client;

    @BeforeEach
    void setUp() {
        GitHubProperties properties = new GitHubProperties(
                URI.create(github.baseUrl()), null, new GitHubProperties.App(null, null, null), DataSize.ofKilobytes(64));
        client = new GitHubClient(GitHubClientAutoConfiguration.gitHubRestClient(properties),
                installationId -> "token-for-" + installationId, 1024);
    }

    @Test
    void getsCommitWithChangedFilesUsingTheInstallationToken() {
        github.stubFor(get(urlPathEqualTo("/repos/octo/shop/commits/abc123")).willReturn(okJson("""
                {"sha": "abc123full",
                 "commit": {"message": "Fix rounding\\n\\nUse HALF_EVEN.",
                            "author": {"name": "Jane Dev", "email": "jane@example.com", "date": "2026-10-30T10:00:00Z"}},
                 "parents": [{"sha": "parent1"}],
                 "files": [
                   {"filename": "src/Price.java", "status": "modified", "additions": 2, "deletions": 1,
                    "patch": "@@ -1,3 +1,4 @@\\n-a\\n+b\\n+c"},
                   {"filename": "logo.png", "status": "added", "additions": 0, "deletions": 0},
                   {"filename": "src/New.java", "previous_filename": "src/Old.java", "status": "renamed",
                    "additions": 0, "deletions": 0}
                 ]}""")));

        Commit commit = client.getCommit("octo/shop", "abc123", 42L);

        assertThat(commit.sha()).isEqualTo("abc123full");
        assertThat(commit.message()).startsWith("Fix rounding");
        assertThat(commit.authorName()).isEqualTo("Jane Dev");
        assertThat(commit.authoredAt()).isEqualTo(Instant.parse("2026-10-30T10:00:00Z"));
        assertThat(commit.parentShas()).containsExactly("parent1");
        assertThat(commit.files()).extracting(Commit.ChangedFile::filename)
                .containsExactly("src/Price.java", "logo.png", "src/New.java");
        assertThat(commit.files().get(0).patch()).contains("+b");
        assertThat(commit.files().get(1).patch()).isNull();
        assertThat(commit.files().get(2).previousFilename()).isEqualTo("src/Old.java");
        github.verify(getRequestedFor(urlPathEqualTo("/repos/octo/shop/commits/abc123"))
                .withHeader("Authorization", equalTo("Bearer token-for-42")));
    }

    @Test
    void listsRecentCommitsOfABranch() {
        github.stubFor(get(urlPathEqualTo("/repos/octo/shop/commits"))
                .withQueryParam("sha", equalTo("feature/tax"))
                .withQueryParam("per_page", equalTo("5"))
                .willReturn(okJson("""
                        [{"sha": "c2", "commit": {"message": "Second\\nbody", "author": {"name": "A", "date": "2026-10-30T11:00:00Z"}}},
                         {"sha": "c1", "commit": {"message": "First", "author": {"name": "B", "date": "2026-10-30T10:00:00Z"}}}]""")));

        List<CommitSummary> commits = client.listCommits("octo/shop", "feature/tax", 5, null);

        assertThat(commits).extracting(CommitSummary::sha).containsExactly("c2", "c1");
        assertThat(commits.getFirst().subject()).isEqualTo("Second");
    }

    @Test
    void decodesTextFilesAndEncodesEachPathSegment() {
        String text = "line one\nline two ✓\n";
        github.stubFor(get(urlPathEqualTo("/repos/octo/shop/contents/src/my%20dir/App.java"))
                .withQueryParam("ref", equalTo("main"))
                .willReturn(okJson(contentJson("src/my dir/App.java", base64Wrapped(text), text.length()))));

        FileContent file = client.getFileContent("octo/shop", "/src/my dir/App.java", "main", null);

        assertThat(file.isText()).isTrue();
        assertThat(file.text()).isEqualTo(text);
        assertThat(file.sha()).isEqualTo("blob1");
        assertThat(file.path()).isEqualTo("src/my dir/App.java");
    }

    @Test
    void returnsNoTextForBinaryAndOversizedFiles() {
        String binary = Base64.getEncoder().encodeToString(new byte[] {(byte) 0x89, 'P', 'N', 'G', 0, 1, 2});
        github.stubFor(get(urlPathEqualTo("/repos/octo/shop/contents/logo.png"))
                .willReturn(okJson(contentJson("logo.png", binary, 7))));
        github.stubFor(get(urlPathEqualTo("/repos/octo/shop/contents/huge.sql")).willReturn(okJson("""
                {"type": "file", "path": "huge.sql", "sha": "blob2", "size": 5000000, "encoding": "none", "content": ""}""")));

        assertThat(client.getFileContent("octo/shop", "logo.png", "main", null).isText()).isFalse();
        FileContent huge = client.getFileContent("octo/shop", "huge.sql", "main", null);
        assertThat(huge.isText()).isFalse();
        assertThat(huge.size()).isEqualTo(5_000_000);
    }

    @Test
    void refusesDirectoriesAndPathsThatClimbOut() {
        github.stubFor(get(urlPathEqualTo("/repos/octo/shop/contents/src"))
                .willReturn(okJson("[{\"type\": \"file\", \"path\": \"src/App.java\"}]")));

        assertThatThrownBy(() -> client.getFileContent("octo/shop", "src", "main", null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("directory");
        assertThatThrownBy(() -> client.getFileContent("octo/shop", "src/../../../orgs", "main", null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void mapsMissingFilesToNotFound() {
        github.stubFor(get(urlPathEqualTo("/repos/octo/shop/contents/nope.txt")).willReturn(notFound()));

        assertThatThrownBy(() -> client.getFileContent("octo/shop", "nope.txt", "main", null))
                .isInstanceOfSatisfying(GitHubApiException.class, e -> assertThat(e.isNotFound()).isTrue());
    }

    @Test
    void searchesCodeInOneRepositoryWithTextMatches() {
        github.stubFor(get(urlPathEqualTo("/search/code"))
                .withQueryParam("q", equalTo("InvoiceService repo:octo/shop"))
                .willReturn(okJson("""
                        {"total_count": 2, "incomplete_results": false, "items": [
                          {"path": "src/InvoiceService.java", "sha": "s1", "repository": {"full_name": "octo/shop"},
                           "text_matches": [{"fragment": "class InvoiceService {"}]},
                          {"path": "elsewhere.java", "sha": "s2", "repository": {"full_name": "other/repo"},
                           "text_matches": [{"fragment": "leak"}]}
                        ]}""")));

        CodeSearchResult result = client.searchCode("octo/shop", "InvoiceService", null);

        assertThat(result.totalCount()).isEqualTo(2);
        assertThat(result.matches()).singleElement().satisfies(match -> {
            assertThat(match.path()).isEqualTo("src/InvoiceService.java");
            assertThat(match.fragments()).containsExactly("class InvoiceService {");
        });
        github.verify(getRequestedFor(urlPathEqualTo("/search/code"))
                .withHeader("Accept", equalTo("application/vnd.github.text-match+json")));
    }

    @Test
    void getsWorkflowRun() {
        github.stubFor(get(urlPathEqualTo("/repos/octo/shop/actions/runs/9001")).willReturn(okJson("""
                {"id": 9001, "name": "CI", "path": ".github/workflows/ci.yml", "head_sha": "abc123",
                 "head_branch": "main", "run_attempt": 2, "html_url": "https://github.com/octo/shop/actions/runs/9001"}""")));

        WorkflowRun run = client.getWorkflowRun("octo/shop", 9001, null);

        assertThat(run.path()).isEqualTo(".github/workflows/ci.yml");
        assertThat(run.headSha()).isEqualTo("abc123");
        assertThat(run.headBranch()).isEqualTo("main");
        assertThat(run.runAttempt()).isEqualTo(2);
    }

    @Test
    void getsRecursiveTree() {
        github.stubFor(get(urlPathEqualTo("/repos/octo/shop/git/trees/abc123"))
                .withQueryParam("recursive", equalTo("1"))
                .willReturn(okJson("""
                        {"sha": "abc123", "truncated": true, "tree": [
                          {"path": "src", "mode": "040000", "type": "tree", "sha": "t1"},
                          {"path": "src/App.java", "mode": "100644", "type": "blob", "sha": "b1", "size": 120}
                        ]}""")));

        GitTree tree = client.getTree("octo/shop", "abc123", null);

        assertThat(tree.truncated()).isTrue();
        assertThat(tree.entries()).hasSize(2);
        assertThat(tree.entries().get(0).isFile()).isFalse();
        assertThat(tree.entries().get(1).isFile()).isTrue();
        assertThat(tree.entries().get(1).size()).isEqualTo(120L);
    }

    private static String contentJson(String path, String base64, long size) {
        return """
                {"type": "file", "path": "%s", "sha": "blob1", "size": %d, "encoding": "base64", "content": "%s"}"""
                .formatted(path, size, base64.replace("\n", "\\n"));
    }

    /** GitHub wraps base64 content at 60 characters per line. */
    private static String base64Wrapped(String text) {
        return Base64.getMimeEncoder(60, "\n".getBytes(StandardCharsets.US_ASCII))
                .encodeToString(text.getBytes(StandardCharsets.UTF_8));
    }
}
