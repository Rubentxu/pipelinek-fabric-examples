// The canonical Go pipeline: productcatalogservice from GoogleCloudPlatform/microservices-demo.
//
// This file holds the logic only. The values it runs with (the profile, the coverage floor, the
// registry, the namespace) come from the environment, and the manifest beside it declares them:
// `productcatalog-go.pipeline.yaml`. Nothing here names a registry or a cluster.
//
// **The profile says which stages run.** `quick`, `developer` and `real-ci` are the profiles of
// the laboratory (PKH-038); no other name is accepted. A stage that the profile does not run is
// still declared, and it says `NOT_RUN` with the reason. A skipped suite that reported green would
// certify a suite that never executed.
//
// **The source is fixed.** The checkout clones the upstream project and moves it to the commit the
// manifest names, then checks that the commit it holds is that one. A branch is a name somebody can
// move; a run that measured whatever the branch held today would measure a moving target.
//
// **The slice is bounded.** The upstream project is a ten-service demo; this pipeline builds and
// tests `src/productcatalogservice` only — a self-contained Go module with its own `go.mod`, whose
// tests are real (product_catalog_test.go drives the catalog loader and the service methods). The
// manifest names the slice, and the prepare stage refuses when the pinned commit does not hold it.
//
// **The toolchain is the host Go, pinned and asserted.** The quick profile pulls no container
// image: it compiles with the `go` on the PATH, and the quality stage refuses when that compiler
// is not the version the manifest names — evidence gathered with an unnamed compiler describes no
// build. `GOTOOLCHAIN=local` stops go from downloading another toolchain behind the run's back to
// satisfy `go.mod`. The container path keeps its own pin: the service's Dockerfile names its
// builder by digest, and the real-ci rebuild runs only on an OCI toolchain the operator declares,
// by digest, or it says NOT_RUN.
//
// **The coverage floor measures hand-written code.** The floor applies to the service package, not
// to the module: `genproto` is machine-generated protobuf, its zero coverage would dilute the
// number to noise, and no floor on generated code protects anything.
//
// **Publication is off by default.** Only `real-ci` can publish, and only when the run sets
// `GO_PUBLISH=true`. Then every value the publication needs must be declared, and a missing
// one refuses the run before anything is built. The published image is built from the service's
// own Dockerfile — from the same source the quick profile compiled, not layered on an undeclared
// base.
//
// Run: pipelinek run productcatalog-go.pipeline.kts (with the manifest's values in the environment)

pipeline {
    /** The variables the shell stages read. They are written to `env.sh` by the first stage. */
    val exportedVariables = listOf(
        "GO_PROFILE",
        "GO_WORK_DIR",
        "GO_SOURCE_URL",
        "GO_SOURCE_COMMIT",
        "GO_SERVICE_DIR",
        "GO_TOOLCHAIN_VERSION",
        "GO_COVERAGE_MIN",
        "GO_PUBLISH",
        "GO_PUSH_REGISTRY",
        "GO_PULL_REGISTRY",
        "GO_IMAGE_REPOSITORY",
        "GO_IMAGE_TAG",
        "GO_OCI_TOOLCHAIN_IMAGE",
        "GO_STAGING_NAMESPACE",
        "GO_STAGING_SERVICE",
        "GO_APP_PORT",
    )

    /** The stages each profile runs. The names of the profiles are the laboratory's three, and no others. */
    val stagesOfProfile: Map<String, Set<String>> = mapOf(
        "quick" to setOf("prepare", "quality", "unit"),
        "developer" to setOf("prepare", "quality", "unit", "race", "coverage"),
        "real-ci" to setOf(
            "prepare", "quality", "unit", "race", "coverage", "containers", "package", "artifacts",
            "publish", "manifests", "deliver",
        ),
    )

    /** The stages that change a registry or a cluster. They run only when the publication is enabled. */
    val publishingStages = setOf("publish", "manifests", "deliver")

    val activeProfile: String = System.getenv("GO_PROFILE") ?: ""
    val publicationEnabled: Boolean = activeProfile == "real-ci" && System.getenv("GO_PUBLISH") == "true"
    val workDir: String = System.getenv("GO_WORK_DIR") ?: ""

    /** Why a stage does not run in this profile, or nothing when it runs. */
    fun reasonToSkip(stage: String): String? = when {
        stagesOfProfile[activeProfile]?.contains(stage) != true -> "profile-$activeProfile-does-not-run-it"
        stage in publishingStages && !publicationEnabled -> "publication-not-enabled"
        else -> null
    }

    /** The script of a stage, or the line that says it did not run and why. */
    fun stageOrSkip(stage: String, script: String): String {
        val reason = reasonToSkip(stage) ?: return script
        return "echo $stage=NOT_RUN reason=$reason"
    }

    /** Quotes a value for the shell, so a value that holds a quote is still one word. */
    fun shellQuoted(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    /** The `export` lines the first stage writes: the values of the run, as the shell reads them. */
    fun exportLines(): String = exportedVariables.joinToString("\n") { name ->
        "export $name=${shellQuoted(System.getenv(name) ?: "")}"
    }

    stages {
        stage("prepare") {
            // The export lines are interpolated after the template is trimmed: trimIndent would
            // then indent the EXPORTS terminator, and the heredoc would never close.
            val envFile = exportLines() + "\n" + """
                # The build keeps its caches in the work directory: the root of the image is
                # read-only, and a go that downloaded its modules under the home of the user would
                # fail there. GOTOOLCHAIN=local is part of the same promise: the compiler is the
                # one the manifest names, and go must not fetch another to satisfy go.mod.
                export HOME="${'$'}GO_WORK_DIR/home"
                export GOCACHE="${'$'}GO_WORK_DIR/gocache"
                export GOMODCACHE="${'$'}GO_WORK_DIR/gomodcache"
                export GOTOOLCHAIN=local
            """.trimIndent()
            sh("""
                set -eu
                mkdir -p ${shellQuoted(workDir)}
                cat > ${shellQuoted("$workDir/env.sh")} <<'EXPORTS'
            """.trimIndent() + "\n" + envFile + "\nEXPORTS\n" + """
                . ${shellQuoted("$workDir/env.sh")}
                case "${'$'}GO_PROFILE" in
                  quick|developer|real-ci) ;;
                  *) echo "refused: the profile '${'$'}GO_PROFILE' is not one of quick, developer, real-ci" >&2; exit 2 ;;
                esac
                case "${'$'}GO_COVERAGE_MIN" in
                  ''|*[!0-9]*) echo "refused: GO_COVERAGE_MIN must be a whole percent" >&2; exit 2 ;;
                esac
                case "${'$'}GO_APP_PORT" in
                  ''|*[!0-9]*) echo "refused: GO_APP_PORT must be a whole port" >&2; exit 2 ;;
                esac
                case "${'$'}GO_PUBLISH" in
                  true|false) ;;
                  *) echo "refused: GO_PUBLISH must be true or false" >&2; exit 2 ;;
                esac
                if [ "${'$'}GO_PUBLISH" = true ] && [ "${'$'}GO_PROFILE" = real-ci ]; then
                  missing=""
                  [ -n "${'$'}GO_PUSH_REGISTRY" ] || missing="${'$'}missing GO_PUSH_REGISTRY"
                  [ -n "${'$'}GO_PULL_REGISTRY" ] || missing="${'$'}missing GO_PULL_REGISTRY"
                  [ -n "${'$'}GO_IMAGE_TAG" ] || missing="${'$'}missing GO_IMAGE_TAG"
                  [ -n "${'$'}GO_STAGING_NAMESPACE" ] || missing="${'$'}missing GO_STAGING_NAMESPACE"
                  if [ -n "${'$'}missing" ]; then
                    echo "refused: publication is enabled and the run does not declare:${'$'}missing" >&2
                    exit 2
                  fi
                fi
                mkdir -p "${'$'}GO_WORK_DIR/home" "${'$'}GO_WORK_DIR/gocache" "${'$'}GO_WORK_DIR/gomodcache"
                cd "${'$'}GO_WORK_DIR"
                rm -rf project
                git clone --quiet "${'$'}GO_SOURCE_URL" project
                cd project
                git -c advice.detachedHead=false checkout --quiet "${'$'}GO_SOURCE_COMMIT"
                if [ "${'$'}(git rev-parse HEAD)" != "${'$'}GO_SOURCE_COMMIT" ]; then
                  echo "the checkout holds ${'$'}(git rev-parse HEAD), not the commit the manifest names" >&2
                  exit 1
                fi
                echo source-commit=${'$'}(git rev-parse HEAD)
                # The bounded slice is a Go module of its own; if the commit does not hold it, the
                # counts below would describe some other tree, and this run has no evidence to give.
                test -f "${'$'}GO_SERVICE_DIR/go.mod"
                echo slice=${'$'}GO_SERVICE_DIR
                cat > "${'$'}GO_WORK_DIR/summary.sh" <<'HELPER'
                #!/bin/sh
                # The counts of a go test -v log: the tests, the failures, the errors and the skipped.
                # The --- lines count top-level tests (a subtest's line is indented, so it belongs to
                # its parent), and a [build failed] or [setup failed] package is an error no test line
                # reports. A log with none of the four says nothing about the tests, and that is not
                # a verdict a stage may read as green.
                log="${'$'}1"
                tests=${'$'}(grep -cE '^--- PASS:' "${'$'}log" || true)
                failures=${'$'}(grep -cE '^--- FAIL:' "${'$'}log" || true)
                skipped=${'$'}(grep -cE '^--- SKIP:' "${'$'}log" || true)
                errors=${'$'}(grep -cE '^FAIL.*\[.*failed\]' "${'$'}log" || true)
                if [ "${'$'}(( tests + failures + skipped + errors ))" = "0" ]; then
                  echo "the log says nothing about the tests" >&2
                  exit 1
                fi
                echo "${'$'}(( tests + failures + skipped )) ${'$'}failures ${'$'}errors ${'$'}skipped"
                HELPER
                chmod +x "${'$'}GO_WORK_DIR/summary.sh"
                echo profile=${'$'}GO_PROFILE publication=${'$'}GO_PUBLISH
            """.trimIndent())
        }
        stage("quality") {
            sh(stageOrSkip("quality", """
                set -eu
                . ${shellQuoted("$workDir/env.sh")}
                if ! command -v go > /dev/null 2>&1; then
                  echo "refused: the quick profile compiles with the host toolchain and there is no go on the PATH" >&2
                  exit 2
                fi
                observed=${'$'}(go env GOVERSION)
                if [ "${'$'}observed" != "go${'$'}GO_TOOLCHAIN_VERSION" ]; then
                  echo "refused: the host toolchain is ${'$'}observed and the manifest pins go${'$'}GO_TOOLCHAIN_VERSION" >&2
                  exit 2
                fi
                cd "${'$'}GO_WORK_DIR/project/${'$'}GO_SERVICE_DIR"
                if ! go build ./... > "${'$'}GO_WORK_DIR/quality.log" 2>&1; then
                  tail -40 "${'$'}GO_WORK_DIR/quality.log"
                  exit 1
                fi
                if ! go vet ./... >> "${'$'}GO_WORK_DIR/quality.log" 2>&1; then
                  tail -40 "${'$'}GO_WORK_DIR/quality.log"
                  exit 1
                fi
                echo quality=ok toolchain=${'$'}observed
            """.trimIndent()))
        }
        stage("unit") {
            sh(stageOrSkip("unit", """
                set -eu
                . ${shellQuoted("$workDir/env.sh")}
                cd "${'$'}GO_WORK_DIR/project/${'$'}GO_SERVICE_DIR"
                # -v keeps the package verdicts (the ok/FAIL lines) and adds the per-test ones (the
                # --- lines), so a package whose suite ran zero tests cannot read as a pass.
                if ! go test -v ./... > "${'$'}GO_WORK_DIR/unit.log" 2>&1; then
                  tail -40 "${'$'}GO_WORK_DIR/unit.log"
                  exit 1
                fi
                summary=${'$'}(sh "${'$'}GO_WORK_DIR/summary.sh" "${'$'}GO_WORK_DIR/unit.log")
                set -- ${'$'}summary
                echo unit-tests=${'$'}1 unit-failures=${'$'}2 unit-errors=${'$'}3 unit-skipped=${'$'}4
                if [ "${'$'}2" != "0" ] || [ "${'$'}3" != "0" ]; then
                  echo "the unit suite failed" >&2
                  exit 1
                fi
                if [ "${'$'}1" = "0" ]; then
                  echo "the unit suite ran no test at all" >&2
                  exit 1
                fi
            """.trimIndent()))
        }
        stage("race") {
            sh(stageOrSkip("race", """
                set -eu
                . ${shellQuoted("$workDir/env.sh")}
                # The race detector is cgo: without a C compiler the suite cannot even link, and a
                # stage that pretended to have run it would certify a binary that was never built.
                if ! command -v gcc > /dev/null 2>&1 && ! command -v clang > /dev/null 2>&1; then
                  echo race=NOT_RUN reason=no-c-compiler-for-the-race-detector
                  exit 0
                fi
                cd "${'$'}GO_WORK_DIR/project/${'$'}GO_SERVICE_DIR"
                if ! go test -race -v ./... > "${'$'}GO_WORK_DIR/race.log" 2>&1; then
                  tail -40 "${'$'}GO_WORK_DIR/race.log"
                  exit 1
                fi
                summary=${'$'}(sh "${'$'}GO_WORK_DIR/summary.sh" "${'$'}GO_WORK_DIR/race.log")
                set -- ${'$'}summary
                echo race-tests=${'$'}1 race-failures=${'$'}2 race-errors=${'$'}3 race-skipped=${'$'}4
                if [ "${'$'}2" != "0" ] || [ "${'$'}3" != "0" ]; then
                  echo "the race suite failed" >&2
                  exit 1
                fi
                if [ "${'$'}1" = "0" ]; then
                  echo "the race suite ran no test at all" >&2
                  exit 1
                fi
            """.trimIndent()))
        }
        stage("coverage") {
            sh(stageOrSkip("coverage", """
                set -eu
                . ${shellQuoted("$workDir/env.sh")}
                cd "${'$'}GO_WORK_DIR/project/${'$'}GO_SERVICE_DIR"
                # The service package alone, not ./...: genproto is generated protobuf whose zero
                # coverage would drag the measured number to noise, and a floor on it protects no
                # hand-written line.
                if ! go test -coverprofile="${'$'}GO_WORK_DIR/coverage.out" . > "${'$'}GO_WORK_DIR/coverage.log" 2>&1; then
                  tail -40 "${'$'}GO_WORK_DIR/coverage.log"
                  exit 1
                fi
                total=${'$'}(go tool cover -func="${'$'}GO_WORK_DIR/coverage.out" | tail -1)
                # The percent is the last field of the total line ("total: (statements) 11.1%").
                # Anything that is not a number refuses the stage: a comparison with a broken
                # value answers "not less" in the shell and would read a parsing failure as green.
                percent=${'$'}(echo "${'$'}total" | awk '{print ${'$'}NF}' | tr -d '%')
                case "${'$'}percent" in
                  ''|*[!0-9.]*|*.*.*)
                    echo "the report of the coverage holds no line" >&2
                    exit 1
                    ;;
                esac
                percent=${'$'}(echo "${'$'}percent" | cut -d. -f1)
                echo coverage=${'$'}percent threshold=${'$'}GO_COVERAGE_MIN
                if [ "${'$'}percent" -lt "${'$'}GO_COVERAGE_MIN" ]; then
                  echo "the coverage is under the floor the manifest declares" >&2
                  exit 1
                fi
            """.trimIndent()))
        }
        stage("containers") {
            sh(stageOrSkip("containers", """
                set -eu
                . ${shellQuoted("$workDir/env.sh")}
                status=0
                docker info > "${'$'}GO_WORK_DIR/containers.log" 2>&1 || status=${'$'}?
                if [ "${'$'}status" != "0" ]; then
                  echo containers=NOT_RUN reason=no-container-engine
                  exit 0
                fi
                # The rebuild is opt-in by digest: an image named by tag would move under the run,
                # and a toolchain nobody declared is not the one this reference measured with.
                if [ -z "${'$'}GO_OCI_TOOLCHAIN_IMAGE" ]; then
                  echo containers=NOT_RUN reason=oci-toolchain-image-not-declared
                  exit 0
                fi
                case "${'$'}GO_OCI_TOOLCHAIN_IMAGE" in
                  *@sha256:*) ;;
                  *) echo containers=NOT_RUN reason=oci-toolchain-image-not-pinned-by-digest; exit 0 ;;
                esac
                cd "${'$'}GO_WORK_DIR/project/${'$'}GO_SERVICE_DIR"
                # The caches the quick stages filled are mounted, not re-downloaded: the rebuild
                # must describe the same module bytes the host build described.
                if ! docker run --rm \
                    -v "${'$'}PWD":/src -w /src \
                    -v "${'$'}GO_WORK_DIR/gomodcache":/go/pkg/mod \
                    -v "${'$'}GO_WORK_DIR/gocache":/root/.cache/go-build \
                    "${'$'}GO_OCI_TOOLCHAIN_IMAGE" go build ./... >> "${'$'}GO_WORK_DIR/containers.log" 2>&1; then
                  tail -40 "${'$'}GO_WORK_DIR/containers.log"
                  echo "the rebuild under the pinned OCI toolchain failed" >&2
                  exit 1
                fi
                echo containers=rebuild-ok toolchain-image=${'$'}GO_OCI_TOOLCHAIN_IMAGE
            """.trimIndent()))
        }
        stage("package") {
            sh(stageOrSkip("package", """
                set -eu
                . ${shellQuoted("$workDir/env.sh")}
                cd "${'$'}GO_WORK_DIR/project/${'$'}GO_SERVICE_DIR"
                if ! go mod verify > "${'$'}GO_WORK_DIR/package.log" 2>&1; then
                  tail -40 "${'$'}GO_WORK_DIR/package.log"
                  exit 1
                fi
                mkdir -p "${'$'}GO_WORK_DIR/bin"
                go build -trimpath -o "${'$'}GO_WORK_DIR/bin/productcatalogservice" . >> "${'$'}GO_WORK_DIR/package.log" 2>&1
                # The binary embeds the module graph it was built with: go version -m reads that
                # graph back as an auditable inventory. The project ships no SBOM generator, and
                # naming an unpinned external one would put an unmeasured supply chain inside the
                # reference.
                go version -m "${'$'}GO_WORK_DIR/bin/productcatalogservice" > "${'$'}GO_WORK_DIR/module-inventory.txt"
                test -s "${'$'}GO_WORK_DIR/module-inventory.txt"
                ls -l "${'$'}GO_WORK_DIR/bin/productcatalogservice"
                echo package=binary
            """.trimIndent()))
        }
        stage("artifacts") {
            sh(stageOrSkip("artifacts", """
                set -eu
                . ${shellQuoted("$workDir/env.sh")}
                mkdir -p artifacts
                # A suite that reported NOT_RUN leaves no log behind: the artifact set says what
                # ran, and copying a missing file as a green lie is exactly what this pipeline is
                # here to refuse.
                for log in quality unit race coverage; do
                  if [ -f "${'$'}GO_WORK_DIR/${'$'}log.log" ]; then
                    cp "${'$'}GO_WORK_DIR/${'$'}log.log" "artifacts/${'$'}log.log"
                  fi
                done
                for extra in coverage.out module-inventory.txt bin/productcatalogservice; do
                  if [ -f "${'$'}GO_WORK_DIR/${'$'}extra" ]; then
                    cp "${'$'}GO_WORK_DIR/${'$'}extra" "artifacts/${'$'}(basename ${'$'}extra)"
                  fi
                done
                for f in artifacts/*; do
                  echo "artifact-${'$'}(basename "${'$'}f")-sha256=${'$'}(sha256sum "${'$'}f" | cut -d' ' -f1)"
                done
            """.trimIndent()))
            if (reasonToSkip("artifacts") == null) archiveArtifacts("artifacts/**")
        }
        stage("publish") {
            sh(stageOrSkip("publish", """
                set -eu
                . ${shellQuoted("$workDir/env.sh")}
                cd "${'$'}GO_WORK_DIR/project/${'$'}GO_SERVICE_DIR"
                image="${'$'}GO_PUSH_REGISTRY/${'$'}GO_IMAGE_REPOSITORY:${'$'}GO_IMAGE_TAG"
                # The service ships its own Dockerfile and pins its builder by digest, so the
                # published image is built from the same source the quick profile compiled — not
                # layered onto a base the operator has to name.
                if ! docker build --tag "${'$'}image" . > "${'$'}GO_WORK_DIR/publish.log" 2>&1; then
                  tail -40 "${'$'}GO_WORK_DIR/publish.log"
                  exit 1
                fi
                docker push "${'$'}image" >> "${'$'}GO_WORK_DIR/publish.log" 2>&1
                echo published-digest=${'$'}(docker image inspect --format '{{index .RepoDigests 0}}' "${'$'}image")
            """.trimIndent()))
        }
        stage("manifests") {
            // The staging deployment keeps the upstream promises of the service: it runs as a
            // non-root user over a read-only root, and its readiness and liveness are the gRPC
            // health service the server registers — not an HTTP probe the service does not answer.
            // DISABLE_PROFILER drops the Google Cloud profiler, which cannot start outside GCE.
            sh(stageOrSkip("manifests", """
                set -eu
                . ${shellQuoted("$workDir/env.sh")}
                DIGEST=${'$'}(crane digest "${'$'}GO_PUSH_REGISTRY/${'$'}GO_IMAGE_REPOSITORY:${'$'}GO_IMAGE_TAG")
                cat > "${'$'}GO_WORK_DIR/deployment.json" <<MANIFEST
                {
                  "apiVersion": "apps/v1",
                  "kind": "Deployment",
                  "metadata": {
                    "name": "${'$'}GO_STAGING_SERVICE",
                    "namespace": "${'$'}GO_STAGING_NAMESPACE"
                  },
                  "spec": {
                    "replicas": 1,
                    "selector": { "matchLabels": { "app": "${'$'}GO_STAGING_SERVICE" } },
                    "template": {
                      "metadata": { "labels": { "app": "${'$'}GO_STAGING_SERVICE" } },
                      "spec": {
                        "automountServiceAccountToken": false,
                        "securityContext": {
                          "runAsNonRoot": true,
                          "runAsUser": 10001,
                          "runAsGroup": 10001,
                          "seccompProfile": { "type": "RuntimeDefault" }
                        },
                        "containers": [
                          {
                            "name": "${'$'}GO_STAGING_SERVICE",
                            "image": "${'$'}GO_PULL_REGISTRY/${'$'}GO_IMAGE_REPOSITORY@${'$'}DIGEST",
                            "env": [
                              { "name": "PORT", "value": "${'$'}GO_APP_PORT" },
                              { "name": "DISABLE_PROFILER", "value": "1" }
                            ],
                            "ports": [ { "containerPort": ${'$'}GO_APP_PORT } ],
                            "readinessProbe": { "grpc": { "port": ${'$'}GO_APP_PORT } },
                            "livenessProbe": { "grpc": { "port": ${'$'}GO_APP_PORT } },
                            "resources": {
                              "requests": { "memory": "64Mi" },
                              "limits": { "memory": "128Mi" }
                            },
                            "securityContext": {
                              "allowPrivilegeEscalation": false,
                              "readOnlyRootFilesystem": true,
                              "capabilities": { "drop": ["ALL"] }
                            }
                          }
                        ]
                      }
                    }
                  }
                }
                MANIFEST
                cat > "${'$'}GO_WORK_DIR/service.json" <<MANIFEST
                {
                  "apiVersion": "v1",
                  "kind": "Service",
                  "metadata": {
                    "name": "${'$'}GO_STAGING_SERVICE",
                    "namespace": "${'$'}GO_STAGING_NAMESPACE"
                  },
                  "spec": {
                    "selector": { "app": "${'$'}GO_STAGING_SERVICE" },
                    "ports": [ { "name": "grpc", "port": ${'$'}GO_APP_PORT, "targetPort": ${'$'}GO_APP_PORT } ]
                  }
                }
                MANIFEST
                echo manifests=written digest=${'$'}DIGEST
            """.trimIndent()))
        }
        stage("deliver") {
            val reason = reasonToSkip("deliver")
            if (reason != null) {
                sh("echo deliver=NOT_RUN reason=$reason")
            } else {
                withCredentials(dev.rubentxu.pipeline.v2.dsl.StepSpec.CredentialsBinding.file("productcatalog-go-staging-kubeconfig", "E2E_KUBECONFIG")) {
                    // The rollout only completes when the gRPC readiness probe passes against the
                    // published digest, so kubectl itself is the health gate: a wget of an HTTP
                    // endpoint this service does not answer would certify a fiction.
                    sh("""
                        set -eu
                        . ${shellQuoted("$workDir/env.sh")}
                        export KUBECONFIG="${'$'}E2E_KUBECONFIG"
                        kubectl apply -f "${'$'}GO_WORK_DIR/deployment.json" -f "${'$'}GO_WORK_DIR/service.json"
                        kubectl rollout status deployment/"${'$'}GO_STAGING_SERVICE" --timeout=300s
                        echo ran-image=${'$'}(kubectl get pod -l app="${'$'}GO_STAGING_SERVICE" -o jsonpath='{.items[0].status.containerStatuses[0].imageID}')
                    """.trimIndent())
                    sh("""
                        set -eu
                        . ${shellQuoted("$workDir/env.sh")}
                        export KUBECONFIG="${'$'}E2E_KUBECONFIG"
                        kubectl delete deployment "${'$'}GO_STAGING_SERVICE" --ignore-not-found --wait=true
                        kubectl delete service "${'$'}GO_STAGING_SERVICE" --ignore-not-found
                    """.trimIndent())
                }
            }
        }
    }
}
