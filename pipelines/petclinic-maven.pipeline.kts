// The canonical PetClinic Maven pipeline.
//
// This file holds the logic only. The values it runs with (the profile, the coverage floor, the
// registry, the namespace) come from the environment, and the manifest beside it declares them:
// `petclinic-maven.pipeline.yaml`. Nothing here names a registry or a cluster.
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
// **Publication is off by default.** Only `real-ci` can publish, and only when the run sets
// `PETCLINIC_PUBLISH=true`. Then every value the publication needs must be declared, and a missing
// one refuses the run before anything is built.
//
// Run: pipelinek run petclinic-maven.pipeline.kts (with the manifest's values in the environment)

pipeline {
    /** The variables the shell stages read. They are written to `env.sh` by the first stage. */
    val exportedVariables = listOf(
        "PETCLINIC_PROFILE",
        "PETCLINIC_WORK_DIR",
        "PETCLINIC_SOURCE_URL",
        "PETCLINIC_SOURCE_COMMIT",
        "PETCLINIC_COVERAGE_MIN",
        "PETCLINIC_PUBLISH",
        "PETCLINIC_PUSH_REGISTRY",
        "PETCLINIC_PULL_REGISTRY",
        "PETCLINIC_REGISTRY_INSECURE",
        "PETCLINIC_IMAGE_REPOSITORY",
        "PETCLINIC_IMAGE_TAG",
        "PETCLINIC_BASE_IMAGE",
        "PETCLINIC_STAGING_NAMESPACE",
        "PETCLINIC_STAGING_SERVICE",
        "PETCLINIC_APP_PORT",
    )

    /** The stages each profile runs. The names of the profiles are the laboratory's three, and no others. */
    val stagesOfProfile: Map<String, Set<String>> = mapOf(
        "quick" to setOf("prepare", "quality", "unit"),
        "developer" to setOf("prepare", "quality", "unit", "integration"),
        "real-ci" to setOf(
            "prepare", "quality", "unit", "integration", "containers", "package", "coverage",
            "artifacts", "publish", "manifests", "deliver",
        ),
    )

    /** The stages that change a registry or a cluster. They run only when the publication is enabled. */
    val publishingStages = setOf("publish", "manifests", "deliver")

    val activeProfile: String = System.getenv("PETCLINIC_PROFILE") ?: ""
    val publicationEnabled: Boolean = activeProfile == "real-ci" && System.getenv("PETCLINIC_PUBLISH") == "true"
    val workDir: String = System.getenv("PETCLINIC_WORK_DIR") ?: ""

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
                # and a Maven that wrote its distribution under the home of the user would fail there.
                export HOME="${'$'}PETCLINIC_WORK_DIR/home"
                export MAVEN_USER_HOME="${'$'}PETCLINIC_WORK_DIR/home/.m2"
            """.trimIndent()
            sh("""
                set -eu
                mkdir -p ${shellQuoted(workDir)}
                cat > ${shellQuoted("$workDir/env.sh")} <<'EXPORTS'
            """.trimIndent() + "\n" + envFile + "\nEXPORTS\n" + """
                . ${shellQuoted("$workDir/env.sh")}
                case "${'$'}PETCLINIC_PROFILE" in
                  quick|developer|real-ci) ;;
                  *) echo "refused: the profile '${'$'}PETCLINIC_PROFILE' is not one of quick, developer, real-ci" >&2; exit 2 ;;
                esac
                case "${'$'}PETCLINIC_COVERAGE_MIN" in
                  ''|*[!0-9]*) echo "refused: PETCLINIC_COVERAGE_MIN must be a whole percent" >&2; exit 2 ;;
                esac
                case "${'$'}PETCLINIC_PUBLISH" in
                  true|false) ;;
                  *) echo "refused: PETCLINIC_PUBLISH must be true or false" >&2; exit 2 ;;
                esac
                if [ "${'$'}PETCLINIC_PUBLISH" = true ] && [ "${'$'}PETCLINIC_PROFILE" = real-ci ]; then
                  missing=""
                  [ -n "${'$'}PETCLINIC_PUSH_REGISTRY" ] || missing="${'$'}missing PETCLINIC_PUSH_REGISTRY"
                  [ -n "${'$'}PETCLINIC_PULL_REGISTRY" ] || missing="${'$'}missing PETCLINIC_PULL_REGISTRY"
                  [ -n "${'$'}PETCLINIC_IMAGE_TAG" ] || missing="${'$'}missing PETCLINIC_IMAGE_TAG"
                  [ -n "${'$'}PETCLINIC_BASE_IMAGE" ] || missing="${'$'}missing PETCLINIC_BASE_IMAGE"
                  [ -n "${'$'}PETCLINIC_STAGING_NAMESPACE" ] || missing="${'$'}missing PETCLINIC_STAGING_NAMESPACE"
                  if [ -n "${'$'}missing" ]; then
                    echo "refused: publication is enabled and the run does not declare:${'$'}missing" >&2
                    exit 2
                  fi
                fi
                mkdir -p "${'$'}PETCLINIC_WORK_DIR/home"
                cd "${'$'}PETCLINIC_WORK_DIR"
                rm -rf project
                git clone --quiet "${'$'}PETCLINIC_SOURCE_URL" project
                cd project
                git -c advice.detachedHead=false checkout --quiet "${'$'}PETCLINIC_SOURCE_COMMIT"
                if [ "${'$'}(git rev-parse HEAD)" != "${'$'}PETCLINIC_SOURCE_COMMIT" ]; then
                  echo "the checkout holds ${'$'}(git rev-parse HEAD), not the commit the manifest names" >&2
                  exit 1
                fi
                echo source-commit=${'$'}(git rev-parse HEAD)
                cat > "${'$'}PETCLINIC_WORK_DIR/summary.sh" <<'HELPER'
                #!/bin/sh
                # The counts of a surefire log: the tests, the failures, the errors and the skipped.
                # The totals line is the one without a time: the line of every class carries one.
                log="${'$'}1"
                line=${'$'}(grep -E '^\[INFO\] Tests run: [0-9]+, Failures: [0-9]+, Errors: [0-9]+, Skipped: [0-9]+${'$'}' "${'$'}log" | tail -1)
                if [ -z "${'$'}line" ]; then
                  echo "the log says nothing about the tests" >&2
                  exit 1
                fi
                echo "${'$'}line" | sed -E 's/^\[INFO\] Tests run: ([0-9]+), Failures: ([0-9]+), Errors: ([0-9]+), Skipped: ([0-9]+)${'$'}/\1 \2 \3 \4/'
                HELPER
                chmod +x "${'$'}PETCLINIC_WORK_DIR/summary.sh"
                echo profile=${'$'}PETCLINIC_PROFILE publication=${'$'}PETCLINIC_PUBLISH
            """.trimIndent())
        }
        stage("quality") {
            sh(stageOrSkip("quality", """
                set -eu
                . ${shellQuoted("$workDir/env.sh")}
                cd "${'$'}PETCLINIC_WORK_DIR/project"
                if ! ./mvnw -B -Dmaven.repo.local="${'$'}PETCLINIC_WORK_DIR/m2" validate > "${'$'}PETCLINIC_WORK_DIR/quality.log" 2>&1; then
                  tail -40 "${'$'}PETCLINIC_WORK_DIR/quality.log"
                  exit 1
                fi
                echo quality=ok
            """.trimIndent()))
        }
        stage("unit") {
            sh(stageOrSkip("unit", """
                set -eu
                . ${shellQuoted("$workDir/env.sh")}
                cd "${'$'}PETCLINIC_WORK_DIR/project"
                if ! ./mvnw -B -Dmaven.repo.local="${'$'}PETCLINIC_WORK_DIR/m2" test -Dtest="!*IntegrationTests" -DfailIfNoTests=false > "${'$'}PETCLINIC_WORK_DIR/unit.log" 2>&1; then
                  tail -40 "${'$'}PETCLINIC_WORK_DIR/unit.log"
                  exit 1
                fi
                summary=${'$'}(sh "${'$'}PETCLINIC_WORK_DIR/summary.sh" "${'$'}PETCLINIC_WORK_DIR/unit.log")
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
                mkdir -p "${'$'}PETCLINIC_WORK_DIR/reports/unit"
                cp "${'$'}PETCLINIC_WORK_DIR/project"/target/surefire-reports/TEST-*.xml "${'$'}PETCLINIC_WORK_DIR/reports/unit/"
            """.trimIndent()))
        }
        stage("integration") {
            sh(stageOrSkip("integration", """
                set -eu
                . ${shellQuoted("$workDir/env.sh")}
                cd "${'$'}PETCLINIC_WORK_DIR/project"
                if ! ./mvnw -B -Dmaven.repo.local="${'$'}PETCLINIC_WORK_DIR/m2" test -Dtest="PetClinicIntegrationTests,CrashControllerIntegrationTests" -DfailIfNoTests=false > "${'$'}PETCLINIC_WORK_DIR/integration.log" 2>&1; then
                  tail -40 "${'$'}PETCLINIC_WORK_DIR/integration.log"
                  exit 1
                fi
                summary=${'$'}(sh "${'$'}PETCLINIC_WORK_DIR/summary.sh" "${'$'}PETCLINIC_WORK_DIR/integration.log")
                set -- ${'$'}summary
                echo integration-tests=${'$'}1 integration-failures=${'$'}2 integration-errors=${'$'}3 integration-skipped=${'$'}4
                if [ "${'$'}2" != "0" ] || [ "${'$'}3" != "0" ]; then
                  echo "the integration suite failed" >&2
                  exit 1
                fi
                if [ "${'$'}1" = "0" ]; then
                  echo "the integration suite ran no test at all" >&2
                  exit 1
                fi
                mkdir -p "${'$'}PETCLINIC_WORK_DIR/reports/integration"
                cp "${'$'}PETCLINIC_WORK_DIR/project"/target/surefire-reports/TEST-*.xml "${'$'}PETCLINIC_WORK_DIR/reports/integration/"
            """.trimIndent()))
        }
        stage("containers") {
            sh(stageOrSkip("containers", """
                set -eu
                . ${shellQuoted("$workDir/env.sh")}
                cd "${'$'}PETCLINIC_WORK_DIR/project"
                status=0
                ./mvnw -B -Dmaven.repo.local="${'$'}PETCLINIC_WORK_DIR/m2" test -Dtest="MySqlIntegrationTests" -DfailIfNoTests=false > "${'$'}PETCLINIC_WORK_DIR/containers.log" 2>&1 || status=${'$'}?
                if grep -q "Could not find a valid Docker environment" "${'$'}PETCLINIC_WORK_DIR/containers.log"; then
                  suite=NOT_RUN
                  reason=no-container-engine
                elif [ "${'$'}status" != "0" ]; then
                  tail -40 "${'$'}PETCLINIC_WORK_DIR/containers.log"
                  echo "the suite of the containers failed" >&2
                  exit 1
                else
                  summary=${'$'}(sh "${'$'}PETCLINIC_WORK_DIR/summary.sh" "${'$'}PETCLINIC_WORK_DIR/containers.log")
                  set -- ${'$'}summary
                  echo containers-tests=${'$'}1 containers-failures=${'$'}2 containers-errors=${'$'}3 containers-skipped=${'$'}4
                  if [ "${'$'}2" != "0" ] || [ "${'$'}3" != "0" ]; then
                    echo "the suite of the containers failed" >&2
                    exit 1
                  fi
                  if [ "${'$'}4" = "${'$'}1" ] && [ "${'$'}1" != "0" ]; then
                    suite=NOT_RUN
                    reason=the-suite-skipped-its-tests
                  else
                    suite=RAN
                    reason=none
                  fi
                fi
                echo containers-suite="${'$'}suite" containers-reason="${'$'}reason"
                mkdir -p "${'$'}PETCLINIC_WORK_DIR/reports/containers"
                cp "${'$'}PETCLINIC_WORK_DIR/project"/target/surefire-reports/TEST-*.xml "${'$'}PETCLINIC_WORK_DIR/reports/containers/"
            """.trimIndent()))
        }
        stage("package") {
            sh(stageOrSkip("package", """
                set -eu
                . ${shellQuoted("$workDir/env.sh")}
                cd "${'$'}PETCLINIC_WORK_DIR/project"
                if ! ./mvnw -B -Dmaven.repo.local="${'$'}PETCLINIC_WORK_DIR/m2" verify -Dtest="!MySqlIntegrationTests" -DfailIfNoTests=false > "${'$'}PETCLINIC_WORK_DIR/package.log" 2>&1; then
                  tail -40 "${'$'}PETCLINIC_WORK_DIR/package.log"
                  exit 1
                fi
                if ! ./mvnw -B -Dmaven.repo.local="${'$'}PETCLINIC_WORK_DIR/m2" org.cyclonedx:cyclonedx-maven-plugin:makeAggregateBom > "${'$'}PETCLINIC_WORK_DIR/sbom.log" 2>&1; then
                  tail -40 "${'$'}PETCLINIC_WORK_DIR/sbom.log"
                  exit 1
                fi
                test -f target/site/jacoco/jacoco.xml
                test -f target/bom.json
                ls -l target/*.jar
            """.trimIndent()))
        }
        stage("coverage") {
            sh(stageOrSkip("coverage", """
                set -eu
                . ${shellQuoted("$workDir/env.sh")}
                report="${'$'}PETCLINIC_WORK_DIR/project/target/site/jacoco/jacoco.xml"
                line=${'$'}(grep -o '<counter type="LINE" missed="[0-9]*" covered="[0-9]*"/>' "${'$'}report" | tail -1)
                if [ -z "${'$'}line" ]; then
                  echo "the report of the coverage holds no line" >&2
                  exit 1
                fi
                missed=${'$'}(echo "${'$'}line" | sed -E 's/.*missed="([0-9]+)".*/\1/')
                covered=${'$'}(echo "${'$'}line" | sed -E 's/.*covered="([0-9]+)".*/\1/')
                total=${'$'}(( missed + covered ))
                if [ "${'$'}total" = "0" ]; then
                  echo "the report of the coverage holds no line" >&2
                  exit 1
                fi
                percent=${'$'}((( covered * 100 ) / total ))
                echo coverage=${'$'}percent threshold=${'$'}PETCLINIC_COVERAGE_MIN
                if [ "${'$'}percent" -lt "${'$'}PETCLINIC_COVERAGE_MIN" ]; then
                  echo "the coverage is under the floor the manifest declares" >&2
                  exit 1
                fi
            """.trimIndent()))
        }
        stage("artifacts") {
            sh(stageOrSkip("artifacts", """
                set -eu
                . ${shellQuoted("$workDir/env.sh")}
                mkdir -p artifacts
                cp "${'$'}PETCLINIC_WORK_DIR/project/target/site/jacoco/jacoco.xml" artifacts/jacoco.xml
                cp "${'$'}PETCLINIC_WORK_DIR/project/target/bom.json" artifacts/bom.json
                for suite in unit integration containers; do
                  mkdir -p "artifacts/surefire-reports/${'$'}suite"
                  cp "${'$'}PETCLINIC_WORK_DIR/reports/${'$'}suite/"*.xml "artifacts/surefire-reports/${'$'}suite/"
                done
                echo artifact-jacoco=${'$'}(sha256sum artifacts/jacoco.xml | cut -d' ' -f1)
                echo artifact-sbom=${'$'}(sha256sum artifacts/bom.json | cut -d' ' -f1)
            """.trimIndent()))
            if (reasonToSkip("artifacts") == null) archiveArtifacts("artifacts/**")
        }
        stage("publish") {
            sh(stageOrSkip("publish", """
                set -eu
                . ${shellQuoted("$workDir/env.sh")}
                insecure=""
                if [ "${'$'}PETCLINIC_REGISTRY_INSECURE" = true ]; then insecure="--insecure"; fi
                image="${'$'}PETCLINIC_PUSH_REGISTRY/${'$'}PETCLINIC_IMAGE_REPOSITORY:${'$'}PETCLINIC_IMAGE_TAG"
                publish="${'$'}PETCLINIC_WORK_DIR/publish"
                rm -rf "${'$'}publish"
                mkdir -p "${'$'}publish/app"
                cp "${'$'}PETCLINIC_WORK_DIR/project/target/"*.jar "${'$'}publish/app/app.jar"
                tar -cf "${'$'}publish/layer.tar" -C "${'$'}publish" app
                crane append ${'$'}insecure -b "${'$'}PETCLINIC_BASE_IMAGE" -f "${'$'}publish/layer.tar" -t "${'$'}image"
                echo published-digest=${'$'}(crane digest ${'$'}insecure "${'$'}image")
            """.trimIndent()))
        }
        stage("manifests") {
            sh(stageOrSkip("manifests", """
                set -eu
                . ${shellQuoted("$workDir/env.sh")}
                insecure=""
                if [ "${'$'}PETCLINIC_REGISTRY_INSECURE" = true ]; then insecure="--insecure"; fi
                DIGEST=${'$'}(crane digest ${'$'}insecure "${'$'}PETCLINIC_PUSH_REGISTRY/${'$'}PETCLINIC_IMAGE_REPOSITORY:${'$'}PETCLINIC_IMAGE_TAG")
                cat > "${'$'}PETCLINIC_WORK_DIR/deployment.json" <<MANIFEST
                {
                  "apiVersion": "apps/v1",
                  "kind": "Deployment",
                  "metadata": {
                    "name": "${'$'}PETCLINIC_STAGING_SERVICE",
                    "namespace": "${'$'}PETCLINIC_STAGING_NAMESPACE"
                  },
                  "spec": {
                    "replicas": 1,
                    "selector": { "matchLabels": { "app": "${'$'}PETCLINIC_STAGING_SERVICE" } },
                    "template": {
                      "metadata": { "labels": { "app": "${'$'}PETCLINIC_STAGING_SERVICE" } },
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
                            "name": "${'$'}PETCLINIC_STAGING_SERVICE",
                            "image": "${'$'}PETCLINIC_PULL_REGISTRY/${'$'}PETCLINIC_IMAGE_REPOSITORY@${'$'}DIGEST",
                            "command": ["/opt/java/openjdk/bin/java", "-XX:MaxRAMPercentage=60.0", "-jar", "/app/app.jar"],
                            "ports": [ { "containerPort": ${'$'}PETCLINIC_APP_PORT } ],
                            "resources": {
                              "requests": { "memory": "384Mi" },
                              "limits": { "memory": "768Mi" }
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
                cat > "${'$'}PETCLINIC_WORK_DIR/service.json" <<MANIFEST
                {
                  "apiVersion": "v1",
                  "kind": "Service",
                  "metadata": {
                    "name": "${'$'}PETCLINIC_STAGING_SERVICE",
                    "namespace": "${'$'}PETCLINIC_STAGING_NAMESPACE"
                  },
                  "spec": {
                    "selector": { "app": "${'$'}PETCLINIC_STAGING_SERVICE" },
                    "ports": [ { "port": ${'$'}PETCLINIC_APP_PORT, "targetPort": ${'$'}PETCLINIC_APP_PORT } ]
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
                withCredentials(dev.rubentxu.pipeline.v2.dsl.StepSpec.CredentialsBinding.file("petclinic-staging-kubeconfig", "E2E_KUBECONFIG")) {
                    sh("""
                        set -eu
                        . ${shellQuoted("$workDir/env.sh")}
                        export KUBECONFIG="${'$'}E2E_KUBECONFIG"
                        kubectl apply -f "${'$'}PETCLINIC_WORK_DIR/deployment.json" -f "${'$'}PETCLINIC_WORK_DIR/service.json"
                        kubectl rollout status deployment/"${'$'}PETCLINIC_STAGING_SERVICE" --timeout=300s
                        echo ran-image=${'$'}(kubectl get pod -l app="${'$'}PETCLINIC_STAGING_SERVICE" -o jsonpath='{.items[0].status.containerStatuses[0].imageID}')
                    """.trimIndent())
                    sh("""
                        set -eu
                        . ${shellQuoted("$workDir/env.sh")}
                        base="http://${'$'}PETCLINIC_STAGING_SERVICE.${'$'}PETCLINIC_STAGING_NAMESPACE.svc.cluster.local:${'$'}PETCLINIC_APP_PORT"
                        health=${'$'}(wget -qO- "${'$'}base/actuator/health")
                        echo health=${'$'}health
                        case "${'$'}health" in *'"UP"'*) ;; *) echo "the application is not healthy" >&2; exit 1 ;; esac
                        vets=${'$'}(wget -qO- "${'$'}base/vets")
                        echo vets=${'$'}vets
                        case "${'$'}vets" in *Carter*) ;; *) echo "the vets endpoint answered without the seeded data" >&2; exit 1 ;; esac
                    """.trimIndent())
                    sh("""
                        set -eu
                        . ${shellQuoted("$workDir/env.sh")}
                        export KUBECONFIG="${'$'}E2E_KUBECONFIG"
                        kubectl delete deployment "${'$'}PETCLINIC_STAGING_SERVICE" --ignore-not-found --wait=true
                        kubectl delete service "${'$'}PETCLINIC_STAGING_SERVICE" --ignore-not-found
                    """.trimIndent())
                }
            }
        }
    }
}
