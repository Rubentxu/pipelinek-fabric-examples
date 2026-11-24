// The canonical TypeScript reference pipeline.
//
// This file holds the logic only. The values it runs with (the profile, the coverage floor, the
// registry, the namespace) come from the environment, and the manifest beside it declares them:
// `typescript.pipeline.yaml`. Nothing here names a registry or a cluster.
//
// **The profile says which stages run.** `quick`, `developer` and `real-ci` are the profiles of
// the laboratory (PKH-038); no other name is accepted. A stage that the profile does not run is
// still declared, and it says `NOT_RUN` with the reason. A skipped suite that reported green
// would certify a suite that never executed.
//
// **The source is fixed.** The checkout clones the upstream project and moves it to the commit
// the manifest names, then checks that the commit it holds is that one. The lockfile the checkout
// brings is the pin of the toolchain: dependencies resolve with `npm ci`, never with `npm install`.
//
// **Publication is off by default.** Only `real-ci` can publish, and only when the run sets
// `TS_PUBLISH=true`. Then every value the publication needs must be declared, and a missing one
// refuses the run before anything is built.
//
// **The directed test is per origin.** The SCM origin is tested by the pinned checkout plus the
// real vitest unit suite in `quick`; the GitOps origin is tested by `manifests` and `deliver`
// under `real-ci` with publication on. The container stage is a smoke, not a duplicate: it boots
// the published-shaped image, which is the assurance the in-process e2e (same code, no packaging)
// does not give. The full auth-certification lives in the e2e cert front (PKH-267..271), not here.
//
// Run: pipelinek run typescript.pipeline.kts (with the manifest's values in the environment)

pipeline {
    /** The variables the shell stages read. They are written to `env.sh` by the first stage. */
    val exportedVariables = listOf(
        "TS_PROFILE",
        "TS_WORK_DIR",
        "TS_SOURCE_URL",
        "TS_SOURCE_COMMIT",
        "TS_COVERAGE_MIN",
        "TS_PUBLISH",
        "TS_PUSH_REGISTRY",
        "TS_PULL_REGISTRY",
        "TS_REGISTRY_INSECURE",
        "TS_IMAGE_REPOSITORY",
        "TS_IMAGE_TAG",
        "TS_BASE_IMAGE",
        "TS_STAGING_NAMESPACE",
        "TS_STAGING_SERVICE",
        "TS_APP_PORT",
    )

    /** The stages each profile runs. The names of the profiles are the laboratory's three, and no others. */
    val stagesOfProfile: Map<String, Set<String>> = mapOf(
        "quick" to setOf("prepare", "quality", "build", "unit"),
        "developer" to setOf("prepare", "quality", "build", "unit", "e2e", "coverage"),
        "real-ci" to setOf(
            "prepare", "quality", "build", "unit", "e2e", "coverage", "containers", "package",
            "artifacts", "publish", "manifests", "deliver",
        ),
    )

    /** The stages that change a registry or a cluster. They run only when the publication is enabled. */
    val publishingStages = setOf("publish", "manifests", "deliver")

    val activeProfile: String = System.getenv("TS_PROFILE") ?: ""
    val publicationEnabled: Boolean = activeProfile == "real-ci" && System.getenv("TS_PUBLISH") == "true"
    val workDir: String = System.getenv("TS_WORK_DIR") ?: ""

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
                # The build keeps its home in the work directory: the root of the image is read-only,
                # and an npm that wrote its cache under the home of the user would fail there.
                export HOME="${'$'}TS_WORK_DIR/home"
                export npm_config_cache="${'$'}TS_WORK_DIR/home/.npm"
            """.trimIndent()
            sh("""
                set -eu
                mkdir -p ${shellQuoted(workDir)}
                cat > ${shellQuoted("$workDir/env.sh")} <<'EXPORTS'
            """.trimIndent() + "\n" + envFile + "\nEXPORTS\n" + """
                . ${shellQuoted("$workDir/env.sh")}
                case "${'$'}TS_PROFILE" in
                  quick|developer|real-ci) ;;
                  *) echo "refused: the profile '${'$'}TS_PROFILE' is not one of quick, developer, real-ci" >&2; exit 2 ;;
                esac
                case "${'$'}TS_COVERAGE_MIN" in
                  ''|*[!0-9]*) echo "refused: TS_COVERAGE_MIN must be a whole percent" >&2; exit 2 ;;
                esac
                case "${'$'}TS_PUBLISH" in
                  true|false) ;;
                  *) echo "refused: TS_PUBLISH must be true or false" >&2; exit 2 ;;
                esac
                if [ "${'$'}TS_PUBLISH" = true ] && [ "${'$'}TS_PROFILE" = real-ci ]; then
                  missing=""
                  [ -n "${'$'}TS_PUSH_REGISTRY" ] || missing="${'$'}missing TS_PUSH_REGISTRY"
                  [ -n "${'$'}TS_PULL_REGISTRY" ] || missing="${'$'}missing TS_PULL_REGISTRY"
                  [ -n "${'$'}TS_IMAGE_TAG" ] || missing="${'$'}missing TS_IMAGE_TAG"
                  [ -n "${'$'}TS_BASE_IMAGE" ] || missing="${'$'}missing TS_BASE_IMAGE"
                  [ -n "${'$'}TS_STAGING_NAMESPACE" ] || missing="${'$'}missing TS_STAGING_NAMESPACE"
                  if [ -n "${'$'}missing" ]; then
                    echo "refused: publication is enabled and the run does not declare:${'$'}missing" >&2
                    exit 2
                  fi
                fi
                mkdir -p "${'$'}TS_WORK_DIR/home"
                cd "${'$'}TS_WORK_DIR"
                rm -rf project
                git clone --quiet "${'$'}TS_SOURCE_URL" project
                cd project
                git -c advice.detachedHead=false checkout --quiet "${'$'}TS_SOURCE_COMMIT"
                if [ "${'$'}(git rev-parse HEAD)" != "${'$'}TS_SOURCE_COMMIT" ]; then
                  echo "the checkout holds ${'$'}(git rev-parse HEAD), not the commit the manifest names" >&2
                  exit 1
                fi
                echo source-commit=${'$'}(git rev-parse HEAD)
                # The lockfile is the pin of the toolchain. Without it, npm ci has nothing to be
                # reproducible against, and a run would resolve whatever the registry serves today.
                test -f package-lock.json
                if ! npm ci --no-audit --no-fund > "${'$'}TS_WORK_DIR/install.log" 2>&1; then
                  tail -40 "${'$'}TS_WORK_DIR/install.log"
                  exit 1
                fi
                echo install=ok
                cat > "${'$'}TS_WORK_DIR/summary.sh" <<'HELPER'
                #!/bin/sh
                # The totals of a vitest log: the passed, the failed and the skipped. The line of
                # the totals is the one that begins, after its padding, with the word Tests; the
                # line of every file carries a duration and never matches this shape. Vitest paints
                # its summary with ANSI codes even when the log is a file, so the codes come off
                # before the line is read: a parser of colors would find nothing to parse.
                log="${'$'}1"
                line=${'$'}(sed -E 's/\x1b\[[0-9;]*m//g' "${'$'}log" | grep -E '^[[:space:]]*Tests[[:space:]]+[0-9]' | tail -1)
                if [ -z "${'$'}line" ]; then
                  echo "the log says nothing about the tests" >&2
                  exit 1
                fi
                passed=${'$'}(echo "${'$'}line" | sed -nE 's/.*[[:space:]]([0-9]+) passed.*/\1/p')
                failed=${'$'}(echo "${'$'}line" | sed -nE 's/.*[[:space:]]([0-9]+) failed.*/\1/p')
                skipped=${'$'}(echo "${'$'}line" | sed -nE 's/.*[[:space:]]([0-9]+) skipped.*/\1/p')
                echo "${'$'}{passed:-0} ${'$'}{failed:-0} ${'$'}{skipped:-0}"
                HELPER
                chmod +x "${'$'}TS_WORK_DIR/summary.sh"
                echo profile=${'$'}TS_PROFILE publication=${'$'}TS_PUBLISH
            """.trimIndent())
        }
        stage("quality") {
            sh(stageOrSkip("quality", """
                set -eu
                . ${shellQuoted("$workDir/env.sh")}
                cd "${'$'}TS_WORK_DIR/project"
                if ! npm run lint > "${'$'}TS_WORK_DIR/quality.log" 2>&1; then
                  tail -40 "${'$'}TS_WORK_DIR/quality.log"
                  exit 1
                fi
                echo quality=ok
            """.trimIndent()))
        }
        stage("build") {
            sh(stageOrSkip("build", """
                set -eu
                . ${shellQuoted("$workDir/env.sh")}
                cd "${'$'}TS_WORK_DIR/project"
                if ! npm run build > "${'$'}TS_WORK_DIR/build.log" 2>&1; then
                  tail -40 "${'$'}TS_WORK_DIR/build.log"
                  exit 1
                fi
                if [ ! -f dist/main.js ]; then
                  echo "the build wrote no dist/main.js: the artifact of the image does not exist" >&2
                  exit 1
                fi
                echo build=dist/main.js
            """.trimIndent()))
        }
        stage("unit") {
            sh(stageOrSkip("unit", """
                set -eu
                . ${shellQuoted("$workDir/env.sh")}
                cd "${'$'}TS_WORK_DIR/project"
                mkdir -p "${'$'}TS_WORK_DIR/reports/unit"
                if ! npm run test -- --reporter=default --reporter=junit --outputFile.junit="${'$'}TS_WORK_DIR/reports/unit/junit.xml" > "${'$'}TS_WORK_DIR/unit.log" 2>&1; then
                  tail -40 "${'$'}TS_WORK_DIR/unit.log"
                  exit 1
                fi
                summary=${'$'}(sh "${'$'}TS_WORK_DIR/summary.sh" "${'$'}TS_WORK_DIR/unit.log")
                set -- ${'$'}summary
                echo unit-passed=${'$'}1 unit-failed=${'$'}2 unit-skipped=${'$'}3
                if [ "${'$'}2" != "0" ]; then
                  echo "the unit suite failed" >&2
                  exit 1
                fi
                if [ "${'$'}(( ${'$'}1 + ${'$'}2 ))" = "0" ]; then
                  echo "the unit suite ran no test at all" >&2
                  exit 1
                fi
            """.trimIndent()))
        }
        stage("e2e") {
            sh(stageOrSkip("e2e", """
                set -eu
                . ${shellQuoted("$workDir/env.sh")}
                cd "${'$'}TS_WORK_DIR/project"
                mkdir -p "${'$'}TS_WORK_DIR/reports/e2e"
                if ! npm run test:e2e -- --reporter=default --reporter=junit --outputFile.junit="${'$'}TS_WORK_DIR/reports/e2e/junit.xml" > "${'$'}TS_WORK_DIR/e2e.log" 2>&1; then
                  tail -40 "${'$'}TS_WORK_DIR/e2e.log"
                  exit 1
                fi
                summary=${'$'}(sh "${'$'}TS_WORK_DIR/summary.sh" "${'$'}TS_WORK_DIR/e2e.log")
                set -- ${'$'}summary
                echo e2e-passed=${'$'}1 e2e-failed=${'$'}2 e2e-skipped=${'$'}3
                if [ "${'$'}2" != "0" ]; then
                  echo "the suite of e2e failed" >&2
                  exit 1
                fi
                if [ "${'$'}(( ${'$'}1 + ${'$'}2 ))" = "0" ]; then
                  echo "the suite of e2e ran no test at all" >&2
                  exit 1
                fi
            """.trimIndent()))
        }
        stage("coverage") {
            sh(stageOrSkip("coverage", """
                set -eu
                . ${shellQuoted("$workDir/env.sh")}
                cd "${'$'}TS_WORK_DIR/project"
                if ! npm run test:cov -- --coverage.reporter=lcov --coverage.reporter=text > "${'$'}TS_WORK_DIR/coverage-run.log" 2>&1; then
                  tail -40 "${'$'}TS_WORK_DIR/coverage-run.log"
                  exit 1
                fi
                lcov="${'$'}TS_WORK_DIR/project/coverage/lcov.info"
                if [ ! -f "${'$'}lcov" ]; then
                  echo "the run of the coverage wrote no lcov report" >&2
                  exit 1
                fi
                # The line coverage is the sum of the lines hit over the sum of the lines found,
                # not the mean of per-file percents: a small file would weigh as much as a big one.
                counts=${'$'}(awk -F: '/^LF:/{found+=${'$'}2} /^LH:/{hit+=${'$'}2} END{if (found==0) {exit 1} print hit, found}' "${'$'}lcov")
                if [ -z "${'$'}counts" ]; then
                  echo "the report of the coverage holds no line" >&2
                  exit 1
                fi
                set -- ${'$'}counts
                percent=${'$'}(( ${'$'}1 * 100 / ${'$'}2 ))
                echo coverage=${'$'}percent threshold=${'$'}TS_COVERAGE_MIN
                if [ "${'$'}percent" -lt "${'$'}TS_COVERAGE_MIN" ]; then
                  echo "the coverage is under the floor the manifest declares" >&2
                  exit 1
                fi
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
                  cd "${'$'}TS_WORK_DIR/project"
                  # The context holds only what the image needs: the tree of node_modules of the
                  # development install would be packed into the daemon for nothing.
                  context="${'$'}TS_WORK_DIR/container-context"
                  rm -rf "${'$'}context"
                  mkdir -p "${'$'}context"
                  cp package.json package-lock.json "${'$'}context/"
                  cp -R dist "${'$'}context/dist"
                  cat > "${'$'}context/Containerfile" <<CONTAINER
                FROM ${'$'}{TS_BASE_IMAGE:-node:24-slim}
                WORKDIR /app
                COPY package.json package-lock.json ./
                RUN npm ci --omit=dev --no-audit --no-fund
                COPY dist ./dist
                ENV PORT=${'$'}{TS_APP_PORT:-3000}
                EXPOSE ${'$'}{TS_APP_PORT:-3000}
                USER node
                CMD ["node", "dist/main"]
                CONTAINER
                  image=${'$'}("${'$'}engine" build -q -f "${'$'}context/Containerfile" "${'$'}context")
                  cid=${'$'}("${'$'}engine" run -d -P "${'$'}image")
                  port=${'$'}("${'$'}engine" port "${'$'}cid" ${'$'}{TS_APP_PORT:-3000}/tcp | head -1 | sed -E 's/.*:([0-9]+)${'$'}/\1/')
                  waited=0
                  until wget -qO- "http://127.0.0.1:${'$'}port/" > "${'$'}TS_WORK_DIR/containers.response" 2>/dev/null; do
                    waited=${'$'}(( waited + 1 ))
                    if [ "${'$'}waited" -ge 30 ]; then break; fi
                    sleep 1
                  done
                  "${'$'}engine" rm -f "${'$'}cid" > /dev/null
                  case "${'$'}(cat "${'$'}TS_WORK_DIR/containers.response") in
                    *"Hello World!"*) echo containers-suite=RAN ;;
                    *) echo "the container answered without the greeting of the application" >&2; exit 1 ;;
                  esac
                fi
            """.trimIndent()))
        }
        stage("package") {
            sh(stageOrSkip("package", """
                set -eu
                . ${shellQuoted("$workDir/env.sh")}
                cd "${'$'}TS_WORK_DIR/project"
                # The bundle is the shape the image carries: the artifact of the build, the manifest
                # of the package and only the dependencies of the runtime.
                bundle="${'$'}TS_WORK_DIR/bundle"
                rm -rf "${'$'}bundle"
                mkdir -p "${'$'}bundle/app"
                cp package.json package-lock.json "${'$'}bundle/app/"
                cp -R dist "${'$'}bundle/app/dist"
                if ! (cd "${'$'}bundle/app" && npm ci --omit=dev --no-audit --no-fund > "${'$'}TS_WORK_DIR/package.log" 2>&1); then
                  tail -40 "${'$'}TS_WORK_DIR/package.log"
                  exit 1
                fi
                tar -cf "${'$'}TS_WORK_DIR/app.tar" -C "${'$'}bundle" app
                echo package-app=${'$'}(sha256sum "${'$'}TS_WORK_DIR/app.tar" | cut -d' ' -f1)
            """.trimIndent()))
        }
        stage("artifacts") {
            sh(stageOrSkip("artifacts", """
                set -eu
                . ${shellQuoted("$workDir/env.sh")}
                mkdir -p artifacts
                cp "${'$'}TS_WORK_DIR/project/coverage/lcov.info" artifacts/lcov.info
                # The lockfile is the provenance of the dependencies: it says what npm ci installed.
                cp "${'$'}TS_WORK_DIR/project/package-lock.json" artifacts/package-lock.json
                for suite in unit e2e; do
                  mkdir -p "artifacts/vitest-reports/${'$'}suite"
                  cp "${'$'}TS_WORK_DIR/reports/${'$'}suite/"*.xml "artifacts/vitest-reports/${'$'}suite/"
                done
                echo artifact-lcov=${'$'}(sha256sum artifacts/lcov.info | cut -d' ' -f1)
                echo artifact-lockfile=${'$'}(sha256sum artifacts/package-lock.json | cut -d' ' -f1)
            """.trimIndent()))
            if (reasonToSkip("artifacts") == null) archiveArtifacts("artifacts/**")
        }
        stage("publish") {
            sh(stageOrSkip("publish", """
                set -eu
                . ${shellQuoted("$workDir/env.sh")}
                insecure=""
                if [ "${'$'}TS_REGISTRY_INSECURE" = true ]; then insecure="--insecure"; fi
                image="${'$'}TS_PUSH_REGISTRY/${'$'}TS_IMAGE_REPOSITORY:${'$'}TS_IMAGE_TAG"
                # The layer is the bundle of the package stage: the base must be an image with the
                # node runtime, because this pipeline installs nothing inside the image at run.
                crane append ${'$'}insecure -b "${'$'}TS_BASE_IMAGE" -f "${'$'}TS_WORK_DIR/app.tar" -t "${'$'}image"
                echo published-digest=${'$'}(crane digest ${'$'}insecure "${'$'}image")
            """.trimIndent()))
        }
        stage("manifests") {
            sh(stageOrSkip("manifests", """
                set -eu
                . ${shellQuoted("$workDir/env.sh")}
                insecure=""
                if [ "${'$'}TS_REGISTRY_INSECURE" = true ]; then insecure="--insecure"; fi
                DIGEST=${'$'}(crane digest ${'$'}insecure "${'$'}TS_PUSH_REGISTRY/${'$'}TS_IMAGE_REPOSITORY:${'$'}TS_IMAGE_TAG")
                cat > "${'$'}TS_WORK_DIR/deployment.json" <<MANIFEST
                {
                  "apiVersion": "apps/v1",
                  "kind": "Deployment",
                  "metadata": {
                    "name": "${'$'}TS_STAGING_SERVICE",
                    "namespace": "${'$'}TS_STAGING_NAMESPACE"
                  },
                  "spec": {
                    "replicas": 1,
                    "selector": { "matchLabels": { "app": "${'$'}TS_STAGING_SERVICE" } },
                    "template": {
                      "metadata": { "labels": { "app": "${'$'}TS_STAGING_SERVICE" } },
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
                            "name": "${'$'}TS_STAGING_SERVICE",
                            "image": "${'$'}TS_PULL_REGISTRY/${'$'}TS_IMAGE_REPOSITORY@${'$'}DIGEST",
                            "command": ["/usr/local/bin/node", "dist/main"],
                            "workingDir": "/app",
                            "ports": [ { "containerPort": ${'$'}TS_APP_PORT } ],
                            "env": [ { "name": "PORT", "value": "${'$'}TS_APP_PORT" } ],
                            "resources": {
                              "requests": { "memory": "128Mi" },
                              "limits": { "memory": "256Mi" }
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
                cat > "${'$'}TS_WORK_DIR/service.json" <<MANIFEST
                {
                  "apiVersion": "v1",
                  "kind": "Service",
                  "metadata": {
                    "name": "${'$'}TS_STAGING_SERVICE",
                    "namespace": "${'$'}TS_STAGING_NAMESPACE"
                  },
                  "spec": {
                    "selector": { "app": "${'$'}TS_STAGING_SERVICE" },
                    "ports": [ { "port": ${'$'}TS_APP_PORT, "targetPort": ${'$'}TS_APP_PORT } ]
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
                withCredentials(dev.rubentxu.pipeline.v2.dsl.StepSpec.CredentialsBinding.file("typescript-staging-kubeconfig", "E2E_KUBECONFIG")) {
                    sh("""
                        set -eu
                        . ${shellQuoted("$workDir/env.sh")}
                        export KUBECONFIG="${'$'}E2E_KUBECONFIG"
                        kubectl apply -f "${'$'}TS_WORK_DIR/deployment.json" -f "${'$'}TS_WORK_DIR/service.json"
                        kubectl rollout status deployment/"${'$'}TS_STAGING_SERVICE" --timeout=300s
                        echo ran-image=${'$'}(kubectl get pod -l app="${'$'}TS_STAGING_SERVICE" -o jsonpath='{.items[0].status.containerStatuses[0].imageID}')
                    """.trimIndent())
                    sh("""
                        set -eu
                        . ${shellQuoted("$workDir/env.sh")}
                        base="http://${'$'}TS_STAGING_SERVICE.${'$'}TS_STAGING_NAMESPACE.svc.cluster.local:${'$'}TS_APP_PORT"
                        greeting=${'$'}(wget -qO- "${'$'}base/")
                        echo greeting=${'$'}greeting
                        case "${'$'}greeting" in *"Hello World!"*) ;; *) echo "the application answered without its greeting" >&2; exit 1 ;; esac
                    """.trimIndent())
                    sh("""
                        set -eu
                        . ${shellQuoted("$workDir/env.sh")}
                        export KUBECONFIG="${'$'}E2E_KUBECONFIG"
                        kubectl delete deployment "${'$'}TS_STAGING_SERVICE" --ignore-not-found --wait=true
                        kubectl delete service "${'$'}TS_STAGING_SERVICE" --ignore-not-found
                    """.trimIndent())
                }
            }
        }
    }
}
