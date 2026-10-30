package io.github.raju6302.cifa.github;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import io.github.raju6302.cifa.github.model.CheckRunAnnotation;
import io.github.raju6302.cifa.github.model.JobLog;
import io.github.raju6302.cifa.github.model.WorkflowJob;
import java.net.URI;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.util.unit.DataSize;

class GitHubClientTest {

    @RegisterExtension
    static WireMockExtension github = WireMockExtension.newInstance().options(wireMockConfig().dynamicPort()).build();

    GitHubClient client;

    @BeforeEach
    void setUp() {
        client = clientWithMaxLog(DataSize.ofKilobytes(64));
    }

    @Test
    void listsJobsAcrossPages() {
        github.stubFor(get(urlPathEqualTo("/repos/octo/app/actions/runs/7/attempts/2/jobs"))
                .withQueryParam("page", com.github.tomakehurst.wiremock.client.WireMock.equalTo("1"))
                .willReturn(okJson(jobsPage(101, 1, 100))));
        github.stubFor(get(urlPathEqualTo("/repos/octo/app/actions/runs/7/attempts/2/jobs"))
                .withQueryParam("page", com.github.tomakehurst.wiremock.client.WireMock.equalTo("2"))
                .willReturn(okJson(jobsPage(101, 101, 1))));

        List<WorkflowJob> jobs = client.listJobs("octo/app", 7, 2, null);

        assertThat(jobs).hasSize(101);
        assertThat(jobs.getFirst().failedStepNames()).containsExactly("Run tests");
        github.verify(getRequestedFor(urlPathEqualTo("/repos/octo/app/actions/runs/7/attempts/2/jobs"))
                .withHeader("Authorization", com.github.tomakehurst.wiremock.client.WireMock.equalTo("Bearer test-token"))
                .withHeader("X-GitHub-Api-Version", com.github.tomakehurst.wiremock.client.WireMock.equalTo("2022-11-28")));
    }

    @Test
    void downloadsLogFromRedirectWithoutSendingOurToken() {
        github.stubFor(get(urlEqualTo("/repos/octo/app/actions/jobs/55/logs"))
                .willReturn(aResponse().withStatus(302).withHeader("Location", github.baseUrl() + "/signed/log.txt")));
        github.stubFor(get(urlEqualTo("/signed/log.txt"))
                .willReturn(aResponse().withBody("2026-10-30T10:00:00Z [ERROR] BUILD FAILURE\n")));

        JobLog log = client.downloadJobLog("octo/app", 55, null);

        assertThat(log.text()).contains("BUILD FAILURE");
        assertThat(log.truncated()).isFalse();
        github.verify(getRequestedFor(urlEqualTo("/signed/log.txt")).withoutHeader("Authorization"));
    }

    @Test
    void keepsTheEndOfLargeLogs() {
        String body = "noise line\n".repeat(500) + "[ERROR] the real error\n";
        github.stubFor(get(urlEqualTo("/repos/octo/app/actions/jobs/56/logs"))
                .willReturn(aResponse().withStatus(302).withHeader("Location", github.baseUrl() + "/signed/big.txt")));
        github.stubFor(get(urlEqualTo("/signed/big.txt")).willReturn(aResponse().withBody(body)));

        JobLog log = clientWithMaxLog(DataSize.ofBytes(100)).downloadJobLog("octo/app", 56, null);

        assertThat(log.truncated()).isTrue();
        assertThat(log.totalBytes()).isEqualTo(body.length());
        assertThat(log.text()).endsWith("[ERROR] the real error\n");
    }

    @Test
    void listsCheckRunAnnotations() {
        github.stubFor(get(urlPathEqualTo("/repos/octo/app/check-runs/57/annotations"))
                .willReturn(okJson("""
                        [{"path": ".github", "start_line": 1, "end_line": 1, "annotation_level": "failure",
                          "title": "", "message": "The job has exceeded the maximum execution time of 1m0s"},
                         {"path": "src/main/java/shop/InvoiceService.java", "start_line": 9, "end_line": 9,
                          "annotation_level": "failure", "title": "", "message": "cannot find symbol"},
                         {"path": ".github", "start_line": 1, "end_line": 1, "annotation_level": "notice",
                          "title": "", "message": "ubuntu-latest is moving"}]""")));

        List<CheckRunAnnotation> annotations = client.listAnnotations("octo/app", 57, null);

        assertThat(annotations).hasSize(3);
        assertThat(annotations.getFirst().message()).contains("exceeded the maximum execution time");
        assertThat(annotations.get(1))
                .returns(9, CheckRunAnnotation::startLine)
                .returns(true, CheckRunAnnotation::hasFile)
                .returns(true, CheckRunAnnotation::isFailure);
        assertThat(annotations.get(2))
                .returns(false, CheckRunAnnotation::hasFile)
                .returns(false, CheckRunAnnotation::isFailure);
        github.verify(getRequestedFor(urlPathEqualTo("/repos/octo/app/check-runs/57/annotations"))
                .withQueryParam("per_page", com.github.tomakehurst.wiremock.client.WireMock.equalTo("100"))
                .withHeader("Authorization", com.github.tomakehurst.wiremock.client.WireMock.equalTo("Bearer test-token")));
    }

    @Test
    void mapsErrorsToRetryableOrNot() {
        github.stubFor(get(urlPathEqualTo("/repos/octo/gone/actions/runs/1/attempts/1/jobs"))
                .willReturn(aResponse().withStatus(404).withBody("{\"message\":\"Not Found\"}")));
        github.stubFor(get(urlPathEqualTo("/repos/octo/flaky/actions/runs/1/attempts/1/jobs"))
                .willReturn(aResponse().withStatus(502)));

        assertThatThrownBy(() -> client.listJobs("octo/gone", 1, 1, null))
                .isInstanceOfSatisfying(GitHubApiException.class, e -> {
                    assertThat(e.isNotFound()).isTrue();
                    assertThat(e.isRetryable()).isFalse();
                });
        assertThatThrownBy(() -> client.listJobs("octo/flaky", 1, 1, null))
                .isInstanceOfSatisfying(GitHubApiException.class, e -> assertThat(e.isRetryable()).isTrue());
    }

    @Test
    void rejectsMalformedRepositoryNames() {
        assertThatThrownBy(() -> client.listJobs("../../admin", 1, 1, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private GitHubClient clientWithMaxLog(DataSize maxLog) {
        GitHubProperties properties = new GitHubProperties(
                URI.create(github.baseUrl()), "test-token", new GitHubProperties.App(null, null, null), maxLog);
        return new GitHubClient(GitHubClientAutoConfiguration.gitHubRestClient(properties), id -> "test-token",
                (int) maxLog.toBytes());
    }

    private static String jobsPage(int total, int firstId, int count) {
        String jobs = IntStream.range(firstId, firstId + count)
                .mapToObj(id -> """
                        {"id": %d, "name": "build-%d", "status": "completed", "conclusion": "failure",
                         "html_url": "https://github.com/octo/app/actions/runs/7/job/%d",
                         "started_at": "2026-10-30T10:00:00Z", "completed_at": "2026-10-30T10:01:00Z",
                         "steps": [{"number": 1, "name": "Checkout", "conclusion": "success"},
                                   {"number": 2, "name": "Run tests", "conclusion": "failure"}]}"""
                        .formatted(id, id, id))
                .collect(Collectors.joining(","));
        return "{\"total_count\": %d, \"jobs\": [%s]}".formatted(total, jobs);
    }
}
