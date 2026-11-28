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

**Why `junit.results` and not `publishJUnitReports` — for the verdict.** PR #474 was tracked under
the working name `core.junit.publishReports` over an Ant-style pattern; the per-suite step that
shipped is `junit.results`: a single explicitly declared report file per suite, fail-closed on
absence and on non-junit-xml. The coverage the pattern was meant to give comes from staging
everything into the declared path (step 2) — an XML at an unconventional path is covered by the
declaration, not by a name heuristic. `publishJUnitReports` plays a different role here, and one
that no `junit.results` call covers: it is the **object-lane publication of the bytes** — see the
next section.

## El carril de objetos: publicar sin `archiveArtifacts` (PKH-378)

**Migrated:** `petclinic-maven` and `fastapi`. **Pending (declared debt):** `typescript`,
`axum-chat`, `eshop-dotnet`, `productcatalog-go` — their `artifacts` stage still calls
`archiveArtifacts`.

The migrated examples publish their real JUnit reports **end to end over the object lane** — worker
publication plus verified download — and no longer call `archiveArtifacts`:

1. **Declaration (the script).** One `post { always { publishJUnitReports("junit-reports/*/junit.xml",
   required = false) } }` per pipeline — the single contact point, mirroring the unique
   `archiveArtifacts` line it replaced. The step is `core.junit.publishReports` (pipeline-kotlin
   0.97.0+; the sealed runtime used here is 0.99.1).
2. **Immutable capture (the engine).** The step hands the declared selection to the run's archive
   port FIRST: the capture seals the bytes write-once at their digest address and answers with
   receipts whose **digest and size are read back from the store**, never re-computed from the
   workspace. The parse then reads *the capture*: a report rewritten after its capture cannot alter
   what the publication declares. With `required = false` a selection that matched nothing is an
   ABSENT verdict — publication never invents a result.
3. **Worker publication (the lane).** The captured files reach the Fabric Core through the worker:
   the archive facts are staged into the worker's `ArtifactSpool`, the `ArtifactPublisher` uploads
   each object over the worker channel (`WorkerUploads`, one stream per ticket, resuming from the
   offset the ticket names), and the Core takes **custody** of the sealed object. The identity of
   the capture is the receipt (PKH-402), not the live disk.
4. **Verified download (the port).** A reader asks the controller for the run's artifacts and gets,
   per file, its name, size and `sha256:` digest. The bytes are served **only when they hash to the
   declared digest** (`ArtifactBytes.readVerified`: size and digest checked at the port before a
   byte is seen); tampered or digest-less artifacts come back refused, never served. The same
   verified bytes are what the native test-results pages count.

### How to see the new journey

Run a migrated example on a Fabric deployment (the laboratory of `pipelinek-fabric-e2e` is the
reference) and then ask the controller, not the workspace:

```bash
# what the run published to the lane: name + size + digest, per file
curl -s "$JENKINS/job/<job>/workflow-stage/stages/artifacts?build=$BUILD"
# the bytes of one of them: served only because they hash to the listed digest
curl -s "$JENKINS/job/<job>/workflow-stage/stages/download?build=$BUILD&artifact=$ID" \
  | sha256sum
# the counts the controller read from the published reports (case-counted, not declared)
curl -s "$JENKINS/job/<job>/workflow-stage/stages/tests?build=$BUILD"
```

A `download` that returns bytes hashing to something other than the listed digest is a product
failure, not an operator one: the port refuses that answer and the pages do not count it. The
`package` stage's JARs, the SBOM and the coverage report are **not** on the lane: 0.99.1 has no
generic capture step — `core.junit.publishReports` speaks junit-xml only — so those files stay
workspace evidence whose digests the console names (`artifact-jacoco=`, `artifact-sbom=`). A
generic object-lane publication step is recorded as a product finding by PKH-378, not hacked here.

