# pipelinek-fabric-examples

Canonical PipelineK Fabric examples. Pipelines live under `pipelines/`.

License: Apache-2.0 (see LICENSE).

## Publicación explícita de informes JUnit (PKH-355, migrada a `junit.results` en PKH-403)

The three JVM/JS/Python references (`petclinic-maven`, `typescript`, `fastapi`) publish the **real**
JUnit XML of every suite they run, **even when that suite fails**, as an explicit publication:

1. The tool writes its own report at its real path — the suite's exit code never gates the write:
   - **Maven surefire** (petclinic): `target/surefire-reports/TEST-*.xml` (one file per test class);
   - **Vitest/Jest junit reporter** (typescript): `--reporter=junit --outputFile.junit=$TS_WORK_DIR/reports/<suite>/junit.xml`;
   - **pytest** (fastapi): `--junitxml=$FASTAPI_WORK_DIR/reports/<suite>/junit.xml`.
2. The test stage stages those files into the workspace under the ONE declared path
   `junit-reports/<suite>/junit.xml` — surefire's per-class XMLs are aggregated into it (a nested
   `<testsuite>` per class inside a `<testsuites>` root) — and records the verdict in
   `junit-reports/<suite>/verdict`; the stage step itself exits green.
3. The DSL declares `junitResults("junit-reports/<suite>/junit.xml", ".", failOnFailure = false)`
   inside the same stage, before the verdict is replayed. The SDK's official `junit` plugin
   (`junit.results`, shipped with pipeline-kotlin 0.97.0) reads THE declared path — no `*.xml`
   name-glob decides coverage — verifies it parses as junit-xml, and records the declared totals as
   a typed receipt. A report that is missing or is not junit-xml never passes.
4. A last step replays the verdict from the marker and fails the stage if the suite failed. The run
   stays red for the suite failure; only its *order* changed: publish first, fail second.

A suite the profile does not run stages no XML and archives nothing: publication never invents a
result. A suite the *environment* did not run (integration under no reachable database, containers
under no container engine) stages an explicit **zero-test** report — the receipt says nothing ran
while the log says `NOT_RUN` with the reason. The JARs and wheels remain ordinary artifacts of the
`package`/`publish` stages, unaffected.

**Why `junit.results` and not `publishJUnitReports`.** PR #474 was tracked under the working name
`core.junit.publishReports` over an Ant-style pattern; the step that shipped is `junit.results`: a
single explicitly declared report file per suite, fail-closed on absence and on non-junit-xml. The
coverage the pattern was meant to give comes from staging everything into the declared path (step 2)
— an XML at an unconventional path is covered by the declaration, not by a name heuristic.

