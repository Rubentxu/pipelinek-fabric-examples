// The canonical Rust reference pipeline.
//
// This file holds the logic only. The values it runs with (the profile, the crate, the registry,
// the namespace) come from the environment, and the manifest beside it declares them:
// `axum-chat.pipeline.yaml`. Nothing here names a registry or a cluster.
//
// **The profile says which stages run.** `quick`, `developer` and `real-ci` are the profiles of
// the laboratory (PKH-038); no other name is accepted. A stage that the profile does not run is
// still declared, and it says `NOT_RUN` with the reason. A skipped suite that reported green
// would certify a suite that never executed.
//
// **The source is fixed.** The checkout clones the upstream project and moves it to the commit
// the manifest names, then checks that the commit it holds is that one. The lockfile of the
// examples workspace is the pin of the dependencies: cargo builds with `--locked`, never with a
// fresh resolve, and a missing lockfile refuses the run.
//
// **The slice is one example crate.** The examples of axum form their own workspace, and this
// reference builds and tests ONE of its crates: the crate the manifest names, not the whole
// workspace. The intended candidate was the `chat` example, and at the pinned commit it holds no
// test at all; `example-compression` holds eight real `#[tokio::test]` tests, so the manifest
// names that crate and the file keeps the promised name of the task.
//
// **Publication is off by default.** Only `real-ci` can publish, and only when the run sets
// `RS_PUBLISH=true`. Then every value the publication needs must be declared, and a missing one
// refuses the run before anything is built.
//
// **The directed test is per origin.** The SCM origin is tested by the pinned checkout plus the
// real cargo suite in `quick`; the GitOps origin is tested by `manifests` and `deliver` under
// `real-ci` with publication on. The container stage is a smoke, not a duplicate: it builds and
// boots the image shape of the pinned source, which is the assurance the in-process suite (same
// code, no packaging) does not give.
//
// Run: pipelinek run axum-chat.pipeline.kts (with the manifest's values in the environment)

pipeline {
    /** The variables the shell stages read. They are written to `env.sh` by the first stage. */
    val exportedVariables = listOf(
        "RS_PROFILE",
        "RS_WORK_DIR",
        "RS_SOURCE_URL",
        "RS_SOURCE_COMMIT",
        "RS_WORKSPACE_DIR",
        "RS_CRATE",
        "RS_TOOLCHAIN",
        "RS_PUBLISH",
        "RS_PUSH_REGISTRY",
        "RS_PULL_REGISTRY",
        "RS_REGISTRY_INSECURE",
        "RS_IMAGE_REPOSITORY",
        "RS_IMAGE_TAG",
        "RS_BASE_IMAGE",
        "RS_BUILD_IMAGE",
        "RS_APP_PORT",
        "RS_STAGING_NAMESPACE",
        "RS_STAGING_SERVICE",
    )

    /** The stages each profile runs. The names of the profiles are the laboratory's three, and no others. */
    val stagesOfProfile: Map<String, Set<String>> = mapOf(
        "quick" to setOf("prepare", "build", "test"),
        "developer" to setOf("prepare", "build", "test", "lint", "format"),
        "real-ci" to setOf(
            "prepare", "build", "test", "lint", "format", "release", "containers", "package",
            "artifacts", "publish", "manifests", "deliver",
        ),
    )

    /** The stages that change a registry or a cluster. They run only when the publication is enabled. */
    val publishingStages = setOf("publish", "manifests", "deliver")

    val activeProfile: String = System.getenv("RS_PROFILE") ?: ""
    val publicationEnabled: Boolean = activeProfile == "real-ci" && System.getenv("RS_PUBLISH") == "true"
    val workDir: String = System.getenv("RS_WORK_DIR") ?: ""

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
                # The artifacts go under the work directory, not under whatever cargo config the
                # worker happens to bring: the package stage reads the release binary from a path
                # this script can name, and a worker whose scratch is memory must not be measured
                # by the size of the dependency tree.
                export CARGO_TARGET_DIR="${'$'}RS_WORK_DIR/target"
            """.trimIndent()
            sh("""
                set -eu
                mkdir -p ${shellQuoted(workDir)}
                cat > ${shellQuoted("$workDir/env.sh")} <<'EXPORTS'
            """.trimIndent() + "\n" + envFile + "\nEXPORTS\n" + """
                . ${shellQuoted("$workDir/env.sh")}
                case "${'$'}RS_PROFILE" in
                  quick|developer|real-ci) ;;
                  *) echo "refused: the profile '${'$'}RS_PROFILE' is not one of quick, developer, real-ci" >&2; exit 2 ;;
                esac
                case "${'$'}RS_PUBLISH" in
                  true|false) ;;
                  *) echo "refused: RS_PUBLISH must be true or false" >&2; exit 2 ;;
                esac
                if [ "${'$'}RS_PUBLISH" = true ] && [ "${'$'}RS_PROFILE" = real-ci ]; then
                  missing=""
                  [ -n "${'$'}RS_PUSH_REGISTRY" ] || missing="${'$'}missing RS_PUSH_REGISTRY"
                  [ -n "${'$'}RS_PULL_REGISTRY" ] || missing="${'$'}missing RS_PULL_REGISTRY"
                  [ -n "${'$'}RS_IMAGE_TAG" ] || missing="${'$'}missing RS_IMAGE_TAG"
                  [ -n "${'$'}RS_BASE_IMAGE" ] || missing="${'$'}missing RS_BASE_IMAGE"
                  [ -n "${'$'}RS_STAGING_NAMESPACE" ] || missing="${'$'}missing RS_STAGING_NAMESPACE"
                  if [ -n "${'$'}missing" ]; then
                    echo "refused: publication is enabled and the run does not declare:${'$'}missing" >&2
                    exit 2
                  fi
                fi
                mkdir -p "${'$'}RS_WORK_DIR"
                cd "${'$'}RS_WORK_DIR"
                rm -rf project
                git clone --quiet "${'$'}RS_SOURCE_URL" project
                cd project
                git -c advice.detachedHead=false checkout --quiet "${'$'}RS_SOURCE_COMMIT"
                if [ "${'$'}(git rev-parse HEAD)" != "${'$'}RS_SOURCE_COMMIT" ]; then
                  echo "the checkout holds ${'$'}(git rev-parse HEAD), not the commit the manifest names" >&2
                  exit 1
                fi
                echo source-commit=${'$'}(git rev-parse HEAD)
                # The lockfile of the examples workspace is the pin of the dependencies. Without it,
                # cargo --locked has nothing to be reproducible against, and a run would resolve
                # whatever crates.io serves today.
                test -f "${'$'}RS_WORKSPACE_DIR/Cargo.lock"
                if command -v cargo >/dev/null 2>&1; then
                  echo cargo-version=${'$'}(cargo --version)
                  # The toolchain of the manifest is the version the reference was certified with,
                  # not a gate: a mismatch is reported, because the suite really ran either way.
                  rustc_actual=${'$'}(rustc -V 2>/dev/null || echo none)
                  case "${'$'}rustc_actual" in
                    *"${'$'}RS_TOOLCHAIN"*) ;;
                    *) echo "toolchain-warning: the manifest pins ${'$'}RS_TOOLCHAIN and the worker holds ${'$'}rustc_actual" ;;
                  esac
                else
                  echo "toolchain-warning: no cargo on the worker; the cargo stages report NOT_RUN with that reason"
                fi
                cat > "${'$'}RS_WORK_DIR/summary.sh" <<'HELPER'
                #!/bin/sh
                # The totals of a cargo test log: the line of the totals is
                # "test result: ok. N passed; M failed; K ignored;", and cargo prints it once per
                # test binary and again for the doc tests, so the totals of the suite are the sum
                # of every line, not the last one. Cargo paints the codes even when the log is a
                # file (the stage passes --color=never, and the stripper stays for a log that
                # ignored it): a parser of colors would find nothing to parse.
                log="${'$'}1"
                sed -E 's/\x1b\[[0-9;]*m//g' "${'$'}log" | awk '
                /^test result:/ {
                  if (${'$'}5 == "passed;") p += ${'$'}4
                  if (${'$'}7 == "failed;") f += ${'$'}6
                  if (${'$'}9 == "ignored;") i += ${'$'}8
                }
                END { printf "%d %d %d\n", p + 0, f + 0, i + 0 }'
                HELPER
                chmod +x "${'$'}RS_WORK_DIR/summary.sh"
                echo profile=${'$'}RS_PROFILE publication=${'$'}RS_PUBLISH
            """.trimIndent())
        }
        stage("build") {
            sh(stageOrSkip("build", """
                set -eu
                . ${shellQuoted("$workDir/env.sh")}
                if ! command -v cargo >/dev/null 2>&1; then
                  echo build=NOT_RUN reason=cargo-not-installed
                  exit 0
                fi
                cd "${'$'}RS_WORK_DIR/project/${'$'}RS_WORKSPACE_DIR"
                if ! cargo build -p "${'$'}RS_CRATE" --locked --color=never > "${'$'}RS_WORK_DIR/build.log" 2>&1; then
                  tail -40 "${'$'}RS_WORK_DIR/build.log"
                  exit 1
                fi
                echo build=ok
            """.trimIndent()))
        }
        stage("test") {
            sh(stageOrSkip("test", """
                set -eu
                . ${shellQuoted("$workDir/env.sh")}
                if ! command -v cargo >/dev/null 2>&1; then
                  echo test=NOT_RUN reason=cargo-not-installed
                  exit 0
                fi
                cd "${'$'}RS_WORK_DIR/project/${'$'}RS_WORKSPACE_DIR"
                if ! cargo test -p "${'$'}RS_CRATE" --locked --color=never > "${'$'}RS_WORK_DIR/test.log" 2>&1; then
                  tail -40 "${'$'}RS_WORK_DIR/test.log"
                  exit 1
                fi
                summary=${'$'}(sh "${'$'}RS_WORK_DIR/summary.sh" "${'$'}RS_WORK_DIR/test.log")
                set -- ${'$'}summary
                echo test-passed=${'$'}1 test-failed=${'$'}2 test-ignored=${'$'}3
                if [ "${'$'}2" != "0" ]; then
                  echo "the suite of tests failed" >&2
                  exit 1
                fi
                if [ "${'$'}(( ${'$'}1 + ${'$'}2 ))" = "0" ]; then
                  echo "the suite of tests ran no test at all" >&2
                  exit 1
                fi
            """.trimIndent()))
        }
        stage("lint") {
            sh(stageOrSkip("lint", """
                set -eu
                . ${shellQuoted("$workDir/env.sh")}
                if ! command -v cargo >/dev/null 2>&1; then
                  echo lint=NOT_RUN reason=cargo-not-installed
                  exit 0
                fi
                if ! cargo clippy --version >/dev/null 2>&1; then
                  echo lint=NOT_RUN reason=clippy-not-installed
                  exit 0
                fi
                cd "${'$'}RS_WORK_DIR/project/${'$'}RS_WORKSPACE_DIR"
                if ! cargo clippy -p "${'$'}RS_CRATE" --locked --color=never -- -D warnings > "${'$'}RS_WORK_DIR/lint.log" 2>&1; then
                  tail -40 "${'$'}RS_WORK_DIR/lint.log"
                  exit 1
                fi
                echo lint=ok
            """.trimIndent()))
        }
        stage("format") {
            sh(stageOrSkip("format", """
                set -eu
                . ${shellQuoted("$workDir/env.sh")}
                if ! command -v cargo >/dev/null 2>&1; then
                  echo format=NOT_RUN reason=cargo-not-installed
                  exit 0
                fi
                if ! cargo fmt --version >/dev/null 2>&1; then
                  echo format=NOT_RUN reason=rustfmt-not-installed
                  exit 0
                fi
                cd "${'$'}RS_WORK_DIR/project/${'$'}RS_WORKSPACE_DIR"
                if ! cargo fmt -p "${'$'}RS_CRATE" --check > "${'$'}RS_WORK_DIR/format.log" 2>&1; then
                  tail -40 "${'$'}RS_WORK_DIR/format.log"
                  exit 1
                fi
                echo format=ok
            """.trimIndent()))
        }
        stage("release") {
            sh(stageOrSkip("release", """
                set -eu
                . ${shellQuoted("$workDir/env.sh")}
                if ! command -v cargo >/dev/null 2>&1; then
                  echo release=NOT_RUN reason=cargo-not-installed
                  exit 0
                fi
                cd "${'$'}RS_WORK_DIR/project/${'$'}RS_WORKSPACE_DIR"
                if ! cargo build --release -p "${'$'}RS_CRATE" --locked --color=never > "${'$'}RS_WORK_DIR/release.log" 2>&1; then
                  tail -40 "${'$'}RS_WORK_DIR/release.log"
                  exit 1
                fi
                binary="${'$'}CARGO_TARGET_DIR/release/${'$'}RS_CRATE"
                if [ ! -f "${'$'}binary" ]; then
                  echo "the release build wrote no binary at ${'$'}binary" >&2
                  exit 1
                fi
                echo release-binary=${'$'}(sha256sum "${'$'}binary" | cut -d' ' -f1)
            """.trimIndent()))
        }
        stage("containers") {
            sh(stageOrSkip("containers", """
                set -eu
                . ${shellQuoted("$workDir/env.sh")}
                engine=""
                if command -v docker >/dev/null 2>&1; then
                  engine=docker
                elif command -v podman >/dev/null 2>&1; then
                  engine=podman
                fi
                if [ -z "${'$'}engine" ]; then
                  echo containers-suite=NOT_RUN reason=no-container-engine
                else
                  # The smoke builds the crate inside the image, on the pinned source, instead of
                  # copying the binary the worker compiled: the libc of the worker and the libc of
                  # the base image of a reference run are not the same system, and a smoke that
                  # dies of that says nothing about the application.
                  context="${'$'}RS_WORK_DIR/container-context"
                  rm -rf "${'$'}context"
                  mkdir -p "${'$'}context"
                  tar -C "${'$'}RS_WORK_DIR/project" --exclude=.git -cf - . | tar -C "${'$'}context" -xf -
                  cat > "${'$'}context/Containerfile" <<CONTAINER
                FROM ${'$'}{RS_BUILD_IMAGE:-rust:${'$'}RS_TOOLCHAIN}
                WORKDIR /src
                COPY . /src
                # The build of the image ignores the target directory of the worker that packed
                # the context, and lands its binary where this image can find it.
                ENV CARGO_TARGET_DIR=/build/target
                RUN cd /src/${'$'}{RS_WORKSPACE_DIR} && cargo build --release -p ${'$'}{RS_CRATE} --locked --color=never
                EXPOSE ${'$'}{RS_APP_PORT:-3000}
                CMD ["/build/target/release/${'$'}{RS_CRATE}"]
                CONTAINER
                  image=${'$'}("${'$'}engine" build -q -f "${'$'}context/Containerfile" "${'$'}context")
                  # The example binds, upstream and at its source, to the loopback of the machine
                  # that runs it: a mapped port never reaches that server, and the host network of
                  # a shared worker collides with whatever already listens on its 3000. So the
                  # smoke brings up the server and the probe inside the container's own namespace,
                  # where the answer belongs to this application alone.
                  probe_cmd="command -v curl >/dev/null 2>&1 || echo NO_CURL_IN_IMAGE; /build/target/release/${'$'}{RS_CRATE} & sleep 1; curl -s -m 10 --retry 20 --retry-connrefused --retry-delay 1 -X POST -H 'Content-Type: application/json' --data '{\"probe\":\"fref19\"}' http://127.0.0.1:${'$'}{RS_APP_PORT:-3000}/"
                  response=${'$'}("${'$'}engine" run --rm "${'$'}image" sh -c "${'$'}probe_cmd" 2>&1)
                  "${'$'}engine" rmi "${'$'}image" > /dev/null
                  case "${'$'}response" in
                    *NO_CURL_IN_IMAGE*) echo containers-suite=NOT_RUN reason=base-image-without-curl ;;
                    *'"probe"'*) echo containers-suite=RAN ;;
                    *)
                      echo "the container answered without the echo of the probe" >&2
                      printf '%s\n' "${'$'}response" | tail -10 >&2
                      exit 1 ;;
                  esac
                fi
            """.trimIndent()))
        }
        stage("package") {
            sh(stageOrSkip("package", """
                set -eu
                . ${shellQuoted("$workDir/env.sh")}
                # The bundle is the shape the image carries: the binary of the release stage, and
                # beside it the lockfile, the provenance of what the binary was linked from.
                bundle="${'$'}RS_WORK_DIR/bundle"
                rm -rf "${'$'}bundle"
                mkdir -p "${'$'}bundle/app"
                if [ ! -f "${'$'}CARGO_TARGET_DIR/release/${'$'}RS_CRATE" ]; then
                  echo package=NOT_RUN reason=no-release-binary
                  exit 0
                fi
                cp "${'$'}CARGO_TARGET_DIR/release/${'$'}RS_CRATE" "${'$'}bundle/app/${'$'}RS_CRATE"
                cp "${'$'}RS_WORK_DIR/project/${'$'}RS_WORKSPACE_DIR/Cargo.lock" "${'$'}bundle/app/Cargo.lock"
                tar -cf "${'$'}RS_WORK_DIR/app.tar" -C "${'$'}bundle" app
                echo package-app=${'$'}(sha256sum "${'$'}RS_WORK_DIR/app.tar" | cut -d' ' -f1)
            """.trimIndent()))
        }
        stage("artifacts") {
            sh(stageOrSkip("artifacts", """
                set -eu
                . ${shellQuoted("$workDir/env.sh")}
                mkdir -p artifacts
                # The lockfile is the provenance of the dependencies: it says what --locked built.
                cp "${'$'}RS_WORK_DIR/project/${'$'}RS_WORKSPACE_DIR/Cargo.lock" artifacts/Cargo.lock
                # The log of the suite is the evidence behind the totals: cargo writes no junit on
                # its own, and a suite that reported NOT_RUN leaves no log behind.
                if [ -f "${'$'}RS_WORK_DIR/test.log" ]; then
                  cp "${'$'}RS_WORK_DIR/test.log" artifacts/cargo-test.log
                  echo artifact-testlog=${'$'}(sha256sum artifacts/cargo-test.log | cut -d' ' -f1)
                fi
                echo artifact-lockfile=${'$'}(sha256sum artifacts/Cargo.lock | cut -d' ' -f1)
            """.trimIndent()))
            if (reasonToSkip("artifacts") == null) archiveArtifacts("artifacts/**")
        }
        stage("publish") {
            sh(stageOrSkip("publish", """
                set -eu
                . ${shellQuoted("$workDir/env.sh")}
                if ! command -v crane >/dev/null 2>&1; then
                  echo publish=NOT_RUN reason=crane-not-installed
                  exit 0
                fi
                insecure=""
                if [ "${'$'}RS_REGISTRY_INSECURE" = true ]; then insecure="--insecure"; fi
                image="${'$'}RS_PUSH_REGISTRY/${'$'}RS_IMAGE_REPOSITORY:${'$'}RS_IMAGE_TAG"
                # The layer is the bundle of the package stage: the base must be a runtime image
                # whose libc is at least the one the worker linked against, because this pipeline
                # installs nothing inside the image at run.
                crane append ${'$'}insecure -b "${'$'}RS_BASE_IMAGE" -f "${'$'}RS_WORK_DIR/app.tar" -t "${'$'}image"
                echo published-digest=${'$'}(crane digest ${'$'}insecure "${'$'}image")
            """.trimIndent()))
        }
        stage("manifests") {
            sh(stageOrSkip("manifests", """
                set -eu
                . ${shellQuoted("$workDir/env.sh")}
                if ! command -v crane >/dev/null 2>&1; then
                  echo manifests=NOT_RUN reason=crane-not-installed
                  exit 0
                fi
                insecure=""
                if [ "${'$'}RS_REGISTRY_INSECURE" = true ]; then insecure="--insecure"; fi
                DIGEST=${'$'}(crane digest ${'$'}insecure "${'$'}RS_PUSH_REGISTRY/${'$'}RS_IMAGE_REPOSITORY:${'$'}RS_IMAGE_TAG")
                cat > "${'$'}RS_WORK_DIR/deployment.json" <<MANIFEST
                {
                  "apiVersion": "apps/v1",
                  "kind": "Deployment",
                  "metadata": {
                    "name": "${'$'}RS_STAGING_SERVICE",
                    "namespace": "${'$'}RS_STAGING_NAMESPACE"
                  },
                  "spec": {
                    "replicas": 1,
                    "selector": { "matchLabels": { "app": "${'$'}RS_STAGING_SERVICE" } },
                    "template": {
                      "metadata": { "labels": { "app": "${'$'}RS_STAGING_SERVICE" } },
                      "spec": {
                        "automountServiceAccountToken": false,
                        "securityContext": {
                          "runAsNonRoot": true,
                          "runAsUser": 1000,
                          "runAsGroup": 1000,
                          "seccompProfile": { "type": "RuntimeDefault" }
                        },
                        "containers": [
                          {
                            "name": "${'$'}RS_STAGING_SERVICE",
                            "image": "${'$'}RS_PULL_REGISTRY/${'$'}RS_IMAGE_REPOSITORY@${'$'}DIGEST",
                            "command": ["/app/${'$'}RS_CRATE"],
                            "workingDir": "/app",
                            "ports": [ { "containerPort": ${'$'}RS_APP_PORT } ],
                            "resources": {
                              "requests": { "memory": "64Mi" },
                              "limits": { "memory": "128Mi" }
                            },
                            "securityContext": {
                              "allowPrivilegeEscalation": false,
                              "readOnlyRootFilesystem": true,
                              "capabilities": { "drop": ["ALL"] }
                            },
                            "volumeMounts": [ { "name": "tmp", "mountPath": "/tmp" } ]
                          }
                        ],
                        "volumes": [ { "name": "tmp", "emptyDir": {} } ]
                      }
                    }
                  }
                }
                MANIFEST
                cat > "${'$'}RS_WORK_DIR/service.json" <<MANIFEST
                {
                  "apiVersion": "v1",
                  "kind": "Service",
                  "metadata": {
                    "name": "${'$'}RS_STAGING_SERVICE",
                    "namespace": "${'$'}RS_STAGING_NAMESPACE"
                  },
                  "spec": {
                    "selector": { "app": "${'$'}RS_STAGING_SERVICE" },
                    "ports": [ { "port": ${'$'}RS_APP_PORT, "targetPort": ${'$'}RS_APP_PORT } ]
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
                withCredentials(dev.rubentxu.pipeline.v2.dsl.StepSpec.CredentialsBinding.file("rust-staging-kubeconfig", "E2E_KUBECONFIG")) {
                    sh("""
                        set -eu
                        . ${shellQuoted("$workDir/env.sh")}
                        export KUBECONFIG="${'$'}E2E_KUBECONFIG"
                        kubectl apply -f "${'$'}RS_WORK_DIR/deployment.json" -f "${'$'}RS_WORK_DIR/service.json"
                        kubectl rollout status deployment/"${'$'}RS_STAGING_SERVICE" --timeout=300s
                        echo ran-image=${'$'}(kubectl get pod -l app="${'$'}RS_STAGING_SERVICE" -o jsonpath='{.items[0].status.containerStatuses[0].imageID}')
                    """.trimIndent())
                    sh("""
                        set -eu
                        . ${shellQuoted("$workDir/env.sh")}
                        base="http://${'$'}RS_STAGING_SERVICE.${'$'}RS_STAGING_NAMESPACE.svc.cluster.local:${'$'}RS_APP_PORT"
                        # The route of the example answers POST with a JSON body and only POST:
                        # the echo of the probe is the proof that the deployed image runs this
                        # application. A client that cannot set the content type (a plain wget
                        # form, for one) gets 415 and says nothing about the deployment.
                        echo_probe=${'$'}(curl -s -m 10 --retry 20 --retry-connrefused --retry-delay 1 -X POST -H "Content-Type: application/json" --data '{"probe":"staging"}' "${'$'}base/")
                        echo probe-echo=${'$'}echo_probe
                        case "${'$'}echo_probe" in *'"probe"'*) ;; *) echo "the application answered without the echo of the probe" >&2; exit 1 ;; esac
                    """.trimIndent())
                    sh("""
                        set -eu
                        . ${shellQuoted("$workDir/env.sh")}
                        export KUBECONFIG="${'$'}E2E_KUBECONFIG"
                        kubectl delete deployment "${'$'}RS_STAGING_SERVICE" --ignore-not-found --wait=true
                        kubectl delete service "${'$'}RS_STAGING_SERVICE" --ignore-not-found
                    """.trimIndent())
                }
            }
        }
    }
}
