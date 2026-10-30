# Real CI logs

Logs, job metadata and check-run annotations from a real run of the
*Sandbox failures* workflow on GitHub (run 41063690728), saved as fixtures for the
log parsers and classifier. GitHub masks secrets in these logs; they contain none.

Note `job-timeout`: GitHub reports a job timeout as conclusion `cancelled`, and the
reason ("exceeded the maximum execution time") appears only in the annotations, not in the log.

To refresh: run the workflow, then for each job id
`gh api repos/<owner>/<repo>/actions/jobs/<id>/logs > <scenario>.log`,
`gh api repos/<owner>/<repo>/check-runs/<id>/annotations`, and
`gh api repos/<owner>/<repo>/actions/jobs/<id>`.
