package io.github.raju6302.cifa.api.runs;

import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** REST API for the dashboard. No auth until Phase 7, so only bind it to localhost. */
@RestController
@RequestMapping("/api/runs")
class RunsController {

    private static final int MAX_LIMIT = 200;

    private final RunQueries queries;

    RunsController(RunQueries queries) {
        this.queries = queries;
    }

    @GetMapping
    List<RunSummary> list(@RequestParam(defaultValue = "50") int limit) {
        return queries.latest(Math.clamp(limit, 1, MAX_LIMIT));
    }

    @GetMapping("/{id}")
    RunDetail get(@PathVariable long id) {
        return queries.detail(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Run " + id + " not found"));
    }
}
