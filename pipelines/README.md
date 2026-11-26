# pipelinek-fabric-examples

Canonical PipelineK Fabric examples. Pipelines live under `pipelines/`.

License: Apache-2.0 (see LICENSE).

## Publicación explícita de informes JUnit (PKH-355)

The three JVM/JS/Python references (`petclinic-maven`, `typescript`, `fastapi`) publish the **real**
JUnit XML of every suite they run, **even when that suite fails**, as ordinary artifacts:

1. The tool writes its own report at its real path — the suite's exit code never gates the write:
   - **Maven surefire** (petclinic): `target/surefire-reports/TEST-*.xml` (one file per test class);
   - **Vitest/Jest junit reporter** (typescript): `--reporter=junit --outputFile.junit=$TS_WORK_DIR/reports/<suite>/junit.xml`;
   - **pytest** (fastapi): `--junitxml=$FASTAPI_WORK_DIR/reports/<suite>/junit.xml`.
2. The test stage copies those files into the workspace at `junit-reports/<suite>/` and records the
   verdict in `junit-reports/<suite>/verdict` — the stage step itself exits green.
3. The DSL declares `archiveArtifacts("junit-reports/<suite>/*.xml")` inside the same stage, before
   the verdict is replayed, so a red suite still publishes its XML as an ordinary artifact.
4. A last step replays the verdict from the marker and fails the stage if the suite failed. The run
   stays red for the suite failure; only its *order* changed: publish first, fail second.

A suite the profile or the environment does not run (e.g. `integration` under `quick`, or any
database-backed stage without a reachable database) says `NOT_RUN` with the reason, stages no XML
and archives nothing: publication never invents a result. The JARs and wheels remain ordinary
artifacts of the `package`/`publish` stages, unaffected.

**Adoption touchpoint — `core.junit.publishReports`.** The runtime installed today (pipeline-kotlin
0.97.0) registers no JUnit publication step (`validate` on `publishJUnitReports(...)` fails with
*unresolved reference*). The step ships in pipeline-kotlin PR #474 (branch
`feat/pkh-353-junit-publication`): a declaration that verifies every file selected by an Ant-style
pattern parses as junit-xml, captures it through the run's archive port, and treats a glob that
matched nothing as never-a-pass. When a released runtime publishes it, each test stage replaces its
stage → `archiveArtifacts` → replay dance with a single publication declaration over the same
`junit-reports/<suite>/*.xml` pattern. That comment in each pipeline header is the one contact
point; until then, nothing in these examples references the unreleased step.

