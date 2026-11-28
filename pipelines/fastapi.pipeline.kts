// The canonical FastAPI pipeline: the full-stack template, Python/uv edition.
//
// This file holds the logic only. The values it runs with (the profile, the coverage floor, the
// registry, the namespace) come from the environment, and the manifest beside it declares them:
// `fastapi.pipeline.yaml`. Nothing here names a registry or a cluster.
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
// **The toolchain is uv, not Maven.** The template is a uv workspace whose lock sits at the root
// (`uv.lock`, beside `pyproject.toml` — not `backend/uv.lock`), so the sync runs from the root with
// `--frozen`: the run installs the locked versions or refuses. Quality mirrors the project's own
// `scripts/lint.sh` (ruff, format check, mypy); the suites run through `uv run python -m pytest`.
//
// **The unit stage brings its own tests, and says why.** At the pinned commit every upstream test
// needs PostgreSQL: the autouse session fixture in `tests/conftest.py` opens a real Session and the
// tables arrive only with Alembic. The quick profile must not require a database, so the unit stage
// runs a pair of no-DB contract tests against the app's own security and token units — real code,
// real assertions — while the upstream suite is reserved for `integration` and `coverage`, which
// demand a reachable database and report NOT_RUN without one.
//
// **Publication is off by default.** Only `real-ci` can publish, and only when the run sets
// `FASTAPI_PUBLISH=true`. Then every value the publication needs must be declared, and a missing
// one refuses the run before anything is built. Unlike PetClinic there is no base image to declare:
// the template ships its own Dockerfile, so the published image is built from source, not layered.
//
// **This pair is the reference.** The complete certification — real registry, real cluster, real
// credentials — lives in PKH-042, and that card consumes this canonical reference rather than
// another hand copy. Do not grow a second copy of that evidence here.
//
// **The JUnit XML is published even when the suite fails.** Every suite below writes its real
// report with the tool's own switch — pytest `--junitxml=$FASTAPI_WORK_DIR/reports/<suite>/junit.xml`
// — and the stage copies that file, written whether the run was green or red, into
// `junit-reports/<suite>/junit.xml` at the workspace root. The next step publishes it with the
// SDK's `junit.results` (`junitResults(...)`): the step reads THE declared path — publication no
// longer depends on the `*.xml` name glob — parses the report, and records its declared totals as
// a typed receipt; a report that is missing or is not junit-xml never passes. Only then does a
// last step replay the failure verdict carried in `junit-reports/<suite>/verdict`, which remains
// the sole judge of the stage. A suite the profile did not run stages no XML and reports NOT_RUN;
// a suite the environment did not run (integration without a reachable database) stages an
// explicit zero-test report: the receipt says nothing ran, and the log and the replay say NOT_RUN
// with the reason — publication never invents a result. The JARs and wheels of the package stage
// remain ordinary artifacts, unaffected by any of this.
//
// **La publicación es `junit.results`, no un glob de nombres.** El paso declara EL informe
// (`junitResults("junit-reports/<suite>/junit.xml", ".", failOnFailure = false)`); con
// `failOnFailure = false` el paso acredita (parsea y recibe) y el veredicto lo dicta el replay,
// como siempre en este ejemplo. El SDK registrado por el runtime instalado (pipeline-kotlin
// 0.97.0+, plugin oficial `junit` 0.1.0) es el que lo ejecuta.
//
// **Los bytes viajan por el carril de objetos, no por `archiveArtifacts` (PKH-378).** Un único
// paso `publishJUnitReports("junit-reports/*/junit.xml", required = false)` en el `post { always }`
// captura los informes por el puerto de archivo del run —sellado write-once por digest, parseado
// desde la captura y no desde el fichero vivo—; el worker los sube al Fabric Core (spool → subida →
// custodia) y la descarga posterior sólo sirve bytes cuyo SHA-256 coincide con el digest declarado.
// La rueda, el SBOM y la cobertura quedan evidencia local nombrada por digest en la consola.
//
// Run: pipelinek run fastapi.pipeline.kts (with the manifest's values in the environment)

import dev.rubentxu.pipeline.v2.sdk.junit.step.junitResults

pipeline {
    /** The variables the shell stages read. They are written to `env.sh` by the first stage. */
    val exportedVariables = listOf(
        "FASTAPI_PROFILE",
        "FASTAPI_WORK_DIR",
        "FASTAPI_SOURCE_URL",
        "FASTAPI_SOURCE_COMMIT",
        "FASTAPI_COVERAGE_MIN",
        "FASTAPI_PUBLISH",
        "FASTAPI_PUSH_REGISTRY",
        "FASTAPI_PULL_REGISTRY",
        "FASTAPI_IMAGE_REPOSITORY",
        "FASTAPI_IMAGE_TAG",
        "FASTAPI_STAGING_NAMESPACE",
        "FASTAPI_STAGING_SERVICE",
        "FASTAPI_APP_PORT",
        "FASTAPI_DATABASE_URL",
        "FASTAPI_SECRET_KEY",
        "FASTAPI_PROJECT_NAME",
        "FASTAPI_FIRST_SUPERUSER",
        "FASTAPI_FIRST_SUPERUSER_PASSWORD",
    )

    /** The stages each profile runs. The names of the profiles are the laboratory's three, and no others. */
    val stagesOfProfile: Map<String, Set<String>> = mapOf(
        "quick" to setOf("prepare", "quality", "unit"),
        "developer" to setOf("prepare", "quality", "unit", "integration", "migrations"),
        "real-ci" to setOf(
            "prepare", "quality", "unit", "integration", "migrations", "containers", "package",
            "coverage", "artifacts", "publish", "manifests", "deliver",
        ),
    )

    /** The stages that change a registry or a cluster. They run only when the publication is enabled. */
    val publishingStages = setOf("publish", "manifests", "deliver")

    val activeProfile: String = System.getenv("FASTAPI_PROFILE") ?: ""
    val publicationEnabled: Boolean = activeProfile == "real-ci" && System.getenv("FASTAPI_PUBLISH") == "true"
    val workDir: String = System.getenv("FASTAPI_WORK_DIR") ?: ""

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

    /** The application settings the project needs to import `app.core.config`. They arrive from the
     * manifest under the FASTAPI_ prefix; the stages that boot the app hand them the canonical names,
     * and FASTAPI_ENV — a configuration field of the project, not a manifest variable — only downgrades
     * the placeholder warning. The values here are test scaffolding, never a secret (the real ones
     * belong to PKH-042's credentials). */
    val appEnvExports = """
        export FASTAPI_ENV=development
        export PROJECT_NAME="${'$'}FASTAPI_PROJECT_NAME"
        export SECRET_KEY="${'$'}FASTAPI_SECRET_KEY"
        export DATABASE_URL="${'$'}FASTAPI_DATABASE_URL"
        export FIRST_SUPERUSER="${'$'}FASTAPI_FIRST_SUPERUSER"
        export FIRST_SUPERUSER_PASSWORD="${'$'}FASTAPI_FIRST_SUPERUSER_PASSWORD"
    """.trimIndent()

    /** The probe that decides whether the database-backed stages run or report NOT_RUN. */
    val dbIsReachable = """
        db_status=0
        ( cd "${'$'}FASTAPI_WORK_DIR/project" && uv run python "${'$'}FASTAPI_WORK_DIR/db-check.py" "${'$'}FASTAPI_DATABASE_URL" ) || db_status=${'$'}?
    """.trimIndent()

    /** Multi-line fragments are substituted after the template is trimmed, never interpolated before
     * it: trimIndent looks at the evaluated string, and the continuation lines of an interpolated
     * fragment start at column zero, dragging the common indent down and leaving every heredoc
     * terminator indented — an open heredoc swallows the rest of the script and the step still
     * exits with the code of its last `cat`. A token on a line of its own keeps the shape honest. */
    fun withFragments(script: String): String = script
        .replace("APP_ENV_EXPORTS", appEnvExports)
        .replace("DB_IS_REACHABLE", dbIsReachable)

    stages {
        stage("prepare") {
            // The export lines are interpolated after the template is trimmed: trimIndent would
            // then indent the EXPORTS terminator, and the heredoc would never close.
            val envFile = exportLines() + "\n" + """
                # The build keeps its home in the work directory: the root of the image is read-only,
                # and a uv that downloaded its Python under the home of the user would fail there.
                export HOME="${'$'}FASTAPI_WORK_DIR/home"
                export UV_CACHE_DIR="${'$'}FASTAPI_WORK_DIR/uv-cache"
            """.trimIndent()
            sh("""
                set -eu
                mkdir -p ${shellQuoted(workDir)}
                cat > ${shellQuoted("$workDir/env.sh")} <<'EXPORTS'
            """.trimIndent() + "\n" + envFile + "\nEXPORTS\n" + """
                . ${shellQuoted("$workDir/env.sh")}
                case "${'$'}FASTAPI_PROFILE" in
                  quick|developer|real-ci) ;;
                  *) echo "refused: the profile '${'$'}FASTAPI_PROFILE' is not one of quick, developer, real-ci" >&2; exit 2 ;;
                esac
                case "${'$'}FASTAPI_COVERAGE_MIN" in
                  ''|*[!0-9]*) echo "refused: FASTAPI_COVERAGE_MIN must be a whole percent" >&2; exit 2 ;;
                esac
                case "${'$'}FASTAPI_PUBLISH" in
                  true|false) ;;
                  *) echo "refused: FASTAPI_PUBLISH must be true or false" >&2; exit 2 ;;
                esac
                if [ "${'$'}FASTAPI_PUBLISH" = true ] && [ "${'$'}FASTAPI_PROFILE" = real-ci ]; then
                  missing=""
                  [ -n "${'$'}FASTAPI_PUSH_REGISTRY" ] || missing="${'$'}missing FASTAPI_PUSH_REGISTRY"
                  [ -n "${'$'}FASTAPI_PULL_REGISTRY" ] || missing="${'$'}missing FASTAPI_PULL_REGISTRY"
                  [ -n "${'$'}FASTAPI_IMAGE_TAG" ] || missing="${'$'}missing FASTAPI_IMAGE_TAG"
                  [ -n "${'$'}FASTAPI_STAGING_NAMESPACE" ] || missing="${'$'}missing FASTAPI_STAGING_NAMESPACE"
                  if [ -n "${'$'}missing" ]; then
                    echo "refused: publication is enabled and the run does not declare:${'$'}missing" >&2
                    exit 2
                  fi
                fi
                mkdir -p "${'$'}FASTAPI_WORK_DIR/home"
                cd "${'$'}FASTAPI_WORK_DIR"
                rm -rf project
                git clone --quiet "${'$'}FASTAPI_SOURCE_URL" project
                cd project
                git -c advice.detachedHead=false checkout --quiet "${'$'}FASTAPI_SOURCE_COMMIT"
                if [ "${'$'}(git rev-parse HEAD)" != "${'$'}FASTAPI_SOURCE_COMMIT" ]; then
                  echo "the checkout holds ${'$'}(git rev-parse HEAD), not the commit the manifest names" >&2
                  exit 1
                fi
                echo source-commit=${'$'}(git rev-parse HEAD)
                # Reproducibility is the lockfile's promise: without it, `--frozen` has nothing to freeze.
                test -f uv.lock
                echo lockfile=present
                cat > "${'$'}FASTAPI_WORK_DIR/summary.sh" <<'HELPER'
                #!/bin/sh
                # The counts of a pytest terminal summary: the tests, the failures, the errors and the
                # skipped. The word alone marks the kind; pytest drops a count that is zero, and the line
                # "no tests ran" is caught by the total being zero. Singular or plural, the number leads.
                log="${'$'}1"
                line=${'$'}(grep -E '^=+ .+ in [0-9.]+s? =+$' "${'$'}log" | tail -1)
                if [ -z "${'$'}line" ]; then
                  echo "the log says nothing about the tests" >&2
                  exit 1
                fi
                count() {
                  echo "${'$'}line" | grep -oE "[0-9]+ ${'$'}1[a-z]*" | head -1 | grep -oE "[0-9]+" || true
                }
                passed=${'$'}(count passed); [ -n "${'$'}passed" ] || passed=0
                failed=${'$'}(count fail); [ -n "${'$'}failed" ] || failed=0
                errors=${'$'}(count error); [ -n "${'$'}errors" ] || errors=0
                skipped=${'$'}(count skip); [ -n "${'$'}skipped" ] || skipped=0
                total=${'$'}(( passed + failed + errors + skipped ))
                echo "${'$'}total ${'$'}failed ${'$'}errors ${'$'}skipped"
                HELPER
                chmod +x "${'$'}FASTAPI_WORK_DIR/summary.sh"
                cat > "${'$'}FASTAPI_WORK_DIR/db-check.py" <<'DBCHECK'
                # A TCP probe of the database the run declares: the driver answers more politely than
                # a half-open port, but what these stages need first is something listening there.
                import socket
                import sys
                import urllib.parse

                url = urllib.parse.urlparse(sys.argv[1])
                host = url.hostname or "localhost"
                port = url.port or 5432
                try:
                    with socket.create_connection((host, port), timeout=5):
                        pass
                except OSError:
                    sys.exit(1)
                sys.exit(0)
                DBCHECK
                echo profile=${'$'}FASTAPI_PROFILE publication=${'$'}FASTAPI_PUBLISH
            """.trimIndent())
        }
        stage("quality") {
            sh(stageOrSkip("quality", """
                set -eu
                . ${shellQuoted("$workDir/env.sh")}
                cd "${'$'}FASTAPI_WORK_DIR/project"
                # The locked sync or nothing: a drift between the manifest of dependencies and the
                # versions installed here would make every count below belong to an unnamed build.
                if ! uv sync --frozen > "${'$'}FASTAPI_WORK_DIR/quality.log" 2>&1; then
                  tail -40 "${'$'}FASTAPI_WORK_DIR/quality.log"
                  exit 1
                fi
                cd backend
                # The project's own lint gate (scripts/lint.sh), minus `ty`: quality stays cheap.
                for gate in "ruff check app scripts" "ruff format --check app scripts" "mypy app"; do
                  if ! uv run ${'$'}gate >> "${'$'}FASTAPI_WORK_DIR/quality.log" 2>&1; then
                    tail -40 "${'$'}FASTAPI_WORK_DIR/quality.log"
                    exit 1
                  fi
                done
                echo quality=ok
            """.trimIndent()))
        }
        stage("unit") {
            sh(stageOrSkip("unit", withFragments("""
                set -eu
                . ${shellQuoted("$workDir/env.sh")}
                mkdir -p "${'$'}FASTAPI_WORK_DIR/nodb-tests"
                # The no-DB contract of the reference, in the project's own venv: hashing, access
                # tokens and reset tokens, straight from app.core.security and app.utils. They fail
                # when those modules break, and they need no database to prove it.
                cat > "${'$'}FASTAPI_WORK_DIR/nodb-tests/test_fastapi_reference.py" <<'TESTS'
                import jwt
                import pytest
                from datetime import timedelta

                from app.core import security
                from app.core.config import settings
                from app.utils import generate_password_reset_token, verify_password_reset_token


                def test_password_hash_roundtrip() -> None:
                    hashed = security.get_password_hash("a-test-password")
                    ok, updated = security.verify_password("a-test-password", hashed)
                    assert ok
                    assert updated is None
                    wrong, _ = security.verify_password("another-password", hashed)
                    assert not wrong


                def test_access_token_carries_the_subject() -> None:
                    token = security.create_access_token("user-42", expires_delta=timedelta(minutes=5))
                    claims = jwt.decode(token, settings.SECRET_KEY, algorithms=[security.ALGORITHM])
                    assert claims["sub"] == "user-42"


                def test_expired_access_token_is_rejected() -> None:
                    token = security.create_access_token("user-42", expires_delta=timedelta(minutes=-5))
                    with pytest.raises(jwt.ExpiredSignatureError):
                        jwt.decode(token, settings.SECRET_KEY, algorithms=[security.ALGORITHM])


                def test_password_reset_token_roundtrip() -> None:
                    token = generate_password_reset_token("someone@example.com")
                    assert verify_password_reset_token(token) == "someone@example.com"


                def test_password_reset_token_rejects_garbage() -> None:
                    assert verify_password_reset_token("not-a-jwt") is None
                TESTS
                workspace="${'$'}PWD"
                cd "${'$'}FASTAPI_WORK_DIR/project/backend"
                APP_ENV_EXPORTS
                mkdir -p "${'$'}FASTAPI_WORK_DIR/reports/unit" "${'$'}workspace/junit-reports/unit"
                # (1) The suite runs; a failing pytest still leaves its real junit.xml behind.
                suite_status=0
                uv run python -m pytest -p no:cacheprovider --junitxml="${'$'}FASTAPI_WORK_DIR/reports/unit/junit.xml" "${'$'}FASTAPI_WORK_DIR/nodb-tests" > "${'$'}FASTAPI_WORK_DIR/unit.log" 2>&1 || suite_status=${'$'}?
                # (2) The real XML is staged before any verdict is read: publication first.
                if [ -f "${'$'}FASTAPI_WORK_DIR/reports/unit/junit.xml" ]; then
                  cp "${'$'}FASTAPI_WORK_DIR/reports/unit/junit.xml" "${'$'}workspace/junit-reports/unit/junit.xml"
                  echo junit-report=junit-reports/unit/junit.xml
                fi
                # (3) The verdict goes to a marker; the last step of the stage replays it.
                verdict=${'$'}suite_status
                if [ "${'$'}suite_status" != "0" ]; then
                  tail -40 "${'$'}FASTAPI_WORK_DIR/unit.log"
                elif summary=${'$'}(sh "${'$'}FASTAPI_WORK_DIR/summary.sh" "${'$'}FASTAPI_WORK_DIR/unit.log" 2>/dev/null); then
                  set -- ${'$'}summary
                  echo unit-tests=${'$'}1 unit-failures=${'$'}2 unit-errors=${'$'}3 unit-skipped=${'$'}4
                  if [ "${'$'}2" != "0" ] || [ "${'$'}3" != "0" ] || [ "${'$'}1" = "0" ]; then
                    verdict=1
                  fi
                else
                  verdict=1
                fi
                echo "${'$'}verdict" > "${'$'}workspace/junit-reports/unit/verdict"
            """.trimIndent())))
            if (reasonToSkip("unit") == null) junitResults("junit-reports/unit/junit.xml", ".", failOnFailure = false)
            sh(stageOrSkip("unit", """
                set -eu
                verdict=${'$'}(cat "junit-reports/unit/verdict" 2>/dev/null || echo no-verdict)
                if [ "${'$'}verdict" = "no-verdict" ]; then
                  echo "the unit suite left no verdict: the stage broke before publication" >&2
                  exit 1
                fi
                if [ "${'$'}verdict" != "0" ]; then
                  echo "the unit suite failed: its real XML is published at junit-reports/unit/junit.xml" >&2
                  exit 1
                fi
                echo unit=ok
            """.trimIndent()))
        }
        stage("integration") {
            sh(stageOrSkip("integration", withFragments("""
                set -eu
                . ${shellQuoted("$workDir/env.sh")}
                workspace="${'$'}PWD"
                DB_IS_REACHABLE
                if [ "${'$'}db_status" != "0" ]; then
                  mkdir -p "${'$'}workspace/junit-reports/integration"
                  # La etapa no corre, pero la publicacion declarada necesita su informe: un
                  # testsuite de cero tests declara que no corrio nada, no inventa un resultado.
                  printf '%s\n' '<?xml version="1.0" encoding="UTF-8"?>' '<testsuite name="integration" tests="0" failures="0" errors="0" skipped="0" time="0"/>' > "${'$'}workspace/junit-reports/integration/junit.xml"
                  echo integration=NOT_RUN reason=no-database-reachable
                  exit 0
                fi
                cd "${'$'}FASTAPI_WORK_DIR/project/backend"
                APP_ENV_EXPORTS
                mkdir -p "${'$'}FASTAPI_WORK_DIR/reports/integration" "${'$'}workspace/junit-reports/integration"
                suite_status=0
                uv run python -m pytest -p no:cacheprovider --junitxml="${'$'}FASTAPI_WORK_DIR/reports/integration/junit.xml" tests > "${'$'}FASTAPI_WORK_DIR/integration.log" 2>&1 || suite_status=${'$'}?
                if [ -f "${'$'}FASTAPI_WORK_DIR/reports/integration/junit.xml" ]; then
                  cp "${'$'}FASTAPI_WORK_DIR/reports/integration/junit.xml" "${'$'}workspace/junit-reports/integration/junit.xml"
                  echo junit-report=junit-reports/integration/junit.xml
                fi
                verdict=${'$'}suite_status
                if [ "${'$'}suite_status" != "0" ]; then
                  tail -40 "${'$'}FASTAPI_WORK_DIR/integration.log"
                elif summary=${'$'}(sh "${'$'}FASTAPI_WORK_DIR/summary.sh" "${'$'}FASTAPI_WORK_DIR/integration.log" 2>/dev/null); then
                  set -- ${'$'}summary
                  echo integration-tests=${'$'}1 integration-failures=${'$'}2 integration-errors=${'$'}3 integration-skipped=${'$'}4
                  if [ "${'$'}2" != "0" ] || [ "${'$'}3" != "0" ] || [ "${'$'}1" = "0" ]; then
                    verdict=1
                  fi
                else
                  verdict=1
                fi
                echo "${'$'}verdict" > "${'$'}workspace/junit-reports/integration/verdict"
            """.trimIndent())))
            if (reasonToSkip("integration") == null) junitResults("junit-reports/integration/junit.xml", ".", failOnFailure = false)
            sh(stageOrSkip("integration", """
                set -eu
                if [ ! -f "junit-reports/integration/verdict" ]; then
                  echo integration=NOT_RUN reason=no-verdict-marker-not-run-or-skipped
                  exit 0
                fi
                verdict=${'$'}(cat "junit-reports/integration/verdict")
                if [ "${'$'}verdict" != "0" ]; then
                  echo "the integration suite failed: its real XML is published at junit-reports/integration/junit.xml" >&2
                  exit 1
                fi
                echo integration=ok
            """.trimIndent()))
        }
        stage("migrations") {
            sh(stageOrSkip("migrations", withFragments("""
                set -eu
                . ${shellQuoted("$workDir/env.sh")}
                DB_IS_REACHABLE
                if [ "${'$'}db_status" != "0" ]; then
                  echo migrations=NOT_RUN reason=no-database-reachable
                  exit 0
                fi
                cd "${'$'}FASTAPI_WORK_DIR/project/backend"
                APP_ENV_EXPORTS
                if ! uv run alembic upgrade head > "${'$'}FASTAPI_WORK_DIR/migrations.log" 2>&1; then
                  tail -40 "${'$'}FASTAPI_WORK_DIR/migrations.log"
                  exit 1
                fi
                echo migrations=upgraded
            """.trimIndent())))
        }
        stage("containers") {
            sh(stageOrSkip("containers", """
                set -eu
                . ${shellQuoted("$workDir/env.sh")}
                status=0
                docker info > "${'$'}FASTAPI_WORK_DIR/containers.log" 2>&1 || status=${'$'}?
                if [ "${'$'}status" != "0" ]; then
                  echo containers=NOT_RUN reason=no-container-engine
                  exit 0
                fi
                cd "${'$'}FASTAPI_WORK_DIR/project"
                if ! docker build --file backend/Dockerfile --tag fastapi-reference:build . >> "${'$'}FASTAPI_WORK_DIR/containers.log" 2>&1; then
                  tail -40 "${'$'}FASTAPI_WORK_DIR/containers.log"
                  echo "the image of the reference did not build" >&2
                  exit 1
                fi
                docker image inspect fastapi-reference:build --format 'containers-built={{.Id}}'
            """.trimIndent()))
        }
        stage("package") {
            sh(stageOrSkip("package", """
                set -eu
                . ${shellQuoted("$workDir/env.sh")}
                cd "${'$'}FASTAPI_WORK_DIR/project"
                if ! uv build --package app --out-dir dist > "${'$'}FASTAPI_WORK_DIR/package.log" 2>&1; then
                  tail -40 "${'$'}FASTAPI_WORK_DIR/package.log"
                  exit 1
                fi
                # The inventory of the lock, frozen and without the dev group: the project ships no
                # CycloneDX generator, and naming an unpinned external one would put an unmeasured
                # supply chain inside the reference. The requirements export is auditable as it stands.
                if ! uv export --frozen --no-dev --no-emit-project --package app > dist/requirements.sbom.txt 2> "${'$'}FASTAPI_WORK_DIR/sbom.log"; then
                  tail -40 "${'$'}FASTAPI_WORK_DIR/sbom.log"
                  exit 1
                fi
                ls dist/*.whl
                test -s dist/requirements.sbom.txt
            """.trimIndent()))
        }
        stage("coverage") {
            sh(stageOrSkip("coverage", withFragments("""
                set -eu
                . ${shellQuoted("$workDir/env.sh")}
                DB_IS_REACHABLE
                if [ "${'$'}db_status" != "0" ]; then
                  echo coverage=NOT_RUN reason=no-database-reachable
                  exit 0
                fi
                cd "${'$'}FASTAPI_WORK_DIR/project/backend"
                APP_ENV_EXPORTS
                if ! uv run coverage run -m pytest -p no:cacheprovider tests > "${'$'}FASTAPI_WORK_DIR/coverage.log" 2>&1; then
                  tail -40 "${'$'}FASTAPI_WORK_DIR/coverage.log"
                  exit 1
                fi
                if ! uv run coverage xml >> "${'$'}FASTAPI_WORK_DIR/coverage.log" 2>&1; then
                  tail -40 "${'$'}FASTAPI_WORK_DIR/coverage.log"
                  exit 1
                fi
                rate=${'$'}(grep -o 'line-rate="[0-9.]*"' coverage.xml | head -1 | grep -oE '[0-9.]+')
                if [ -z "${'$'}rate" ]; then
                  echo "the report of the coverage holds no line" >&2
                  exit 1
                fi
                percent=${'$'}(awk -v r="${'$'}rate" 'BEGIN { printf "%d", r * 100 }')
                echo coverage=${'$'}percent threshold=${'$'}FASTAPI_COVERAGE_MIN
                if [ "${'$'}percent" -lt "${'$'}FASTAPI_COVERAGE_MIN" ]; then
                  echo "the coverage is under the floor the manifest declares" >&2
                  exit 1
                fi
            """.trimIndent())))
        }
        stage("artifacts") {
            sh(stageOrSkip("artifacts", """
                set -eu
                . ${shellQuoted("$workDir/env.sh")}
                cd "${'$'}FASTAPI_WORK_DIR/project"
                mkdir -p artifacts
                cp dist/*.whl artifacts/
                cp dist/requirements.sbom.txt artifacts/
                # A suite that reported NOT_RUN leaves no junit behind: the artifact set says what ran.
                for suite in unit integration; do
                  if [ -f "${'$'}FASTAPI_WORK_DIR/reports/${'$'}suite/junit.xml" ]; then
                    cp "${'$'}FASTAPI_WORK_DIR/reports/${'$'}suite/junit.xml" "artifacts/junit-${'$'}suite.xml"
                  fi
                done
                if [ -f backend/coverage.xml ]; then cp backend/coverage.xml artifacts/coverage.xml; fi
                echo artifact-wheel=${'$'}(sha256sum artifacts/*.whl | cut -d' ' -f1)
                echo artifact-sbom=${'$'}(sha256sum artifacts/requirements.sbom.txt | cut -d' ' -f1)
                if [ -f artifacts/coverage.xml ]; then
                  echo artifact-coverage=${'$'}(sha256sum artifacts/coverage.xml | cut -d' ' -f1)
                fi
            """.trimIndent()))
            // No `archiveArtifacts` here (PKH-378): the junit reports are published to the object
            // lane by the ONE `publishJUnitReports` declaration in the `post` block below, and the
            // wheel, SBOM and coverage files stay workspace evidence whose digests the console
            // names. The worker carries the declared reports to the Fabric Core; no step writes to
            // a controller's disk.
        }
        stage("publish") {
            // No `--insecure` flag beside crane's in PetClinic: docker's TLS policy for a registry is
            // the engine's configuration, not a per-command switch.
            sh(stageOrSkip("publish", """
                set -eu
                . ${shellQuoted("$workDir/env.sh")}
                cd "${'$'}FASTAPI_WORK_DIR/project"
                image="${'$'}FASTAPI_PUSH_REGISTRY/${'$'}FASTAPI_IMAGE_REPOSITORY:${'$'}FASTAPI_IMAGE_TAG"
                if ! docker build --file backend/Dockerfile --tag "${'$'}image" . > "${'$'}FASTAPI_WORK_DIR/publish.log" 2>&1; then
                  tail -40 "${'$'}FASTAPI_WORK_DIR/publish.log"
                  exit 1
                fi
                docker push "${'$'}image" >> "${'$'}FASTAPI_WORK_DIR/publish.log" 2>&1
                echo published-digest=${'$'}(docker image inspect --format '{{index .RepoDigests 0}}' "${'$'}image")
            """.trimIndent()))
        }
        stage("manifests") {
            sh(stageOrSkip("manifests", """
                set -eu
                . ${shellQuoted("$workDir/env.sh")}
                DIGEST=${'$'}(crane digest "${'$'}FASTAPI_PUSH_REGISTRY/${'$'}FASTAPI_IMAGE_REPOSITORY:${'$'}FASTAPI_IMAGE_TAG")
                cat > "${'$'}FASTAPI_WORK_DIR/deployment.json" <<MANIFEST
                {
                  "apiVersion": "apps/v1",
                  "kind": "Deployment",
                  "metadata": {
                    "name": "${'$'}FASTAPI_STAGING_SERVICE",
                    "namespace": "${'$'}FASTAPI_STAGING_NAMESPACE"
                  },
                  "spec": {
                    "replicas": 1,
                    "selector": { "matchLabels": { "app": "${'$'}FASTAPI_STAGING_SERVICE" } },
                    "template": {
                      "metadata": { "labels": { "app": "${'$'}FASTAPI_STAGING_SERVICE" } },
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
                            "name": "${'$'}FASTAPI_STAGING_SERVICE",
                            "image": "${'$'}FASTAPI_PULL_REGISTRY/${'$'}FASTAPI_IMAGE_REPOSITORY@${'$'}DIGEST",
                            "ports": [ { "containerPort": ${'$'}FASTAPI_APP_PORT } ],
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
                cat > "${'$'}FASTAPI_WORK_DIR/service.json" <<MANIFEST
                {
                  "apiVersion": "v1",
                  "kind": "Service",
                  "metadata": {
                    "name": "${'$'}FASTAPI_STAGING_SERVICE",
                    "namespace": "${'$'}FASTAPI_STAGING_NAMESPACE"
                  },
                  "spec": {
                    "selector": { "app": "${'$'}FASTAPI_STAGING_SERVICE" },
                    "ports": [ { "port": ${'$'}FASTAPI_APP_PORT, "targetPort": ${'$'}FASTAPI_APP_PORT } ]
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
                withCredentials(dev.rubentxu.pipeline.v2.dsl.StepSpec.CredentialsBinding.file("fastapi-staging-kubeconfig", "E2E_KUBECONFIG")) {
                    sh("""
                        set -eu
                        . ${shellQuoted("$workDir/env.sh")}
                        export KUBECONFIG="${'$'}E2E_KUBECONFIG"
                        kubectl apply -f "${'$'}FASTAPI_WORK_DIR/deployment.json" -f "${'$'}FASTAPI_WORK_DIR/service.json"
                        kubectl rollout status deployment/"${'$'}FASTAPI_STAGING_SERVICE" --timeout=300s
                        echo ran-image=${'$'}(kubectl get pod -l app="${'$'}FASTAPI_STAGING_SERVICE" -o jsonpath='{.items[0].status.containerStatuses[0].imageID}')
                    """.trimIndent())
                    sh("""
                        set -eu
                        . ${shellQuoted("$workDir/env.sh")}
                        base="http://${'$'}FASTAPI_STAGING_SERVICE.${'$'}FASTAPI_STAGING_NAMESPACE.svc.cluster.local:${'$'}FASTAPI_APP_PORT"
                        health=${'$'}(wget -qO- "${'$'}base/api/v1/utils/health-check/")
                        echo health=${'$'}health
                        case "${'$'}health" in *true*) ;; *) echo "the application is not healthy" >&2; exit 1 ;; esac
                    """.trimIndent())
                    sh("""
                        set -eu
                        . ${shellQuoted("$workDir/env.sh")}
                        export KUBECONFIG="${'$'}E2E_KUBECONFIG"
                        kubectl delete deployment "${'$'}FASTAPI_STAGING_SERVICE" --ignore-not-found --wait=true
                        kubectl delete service "${'$'}FASTAPI_STAGING_SERVICE" --ignore-not-found
                    """.trimIndent())
                }
            }
        }
    }

    post {
        always {
            // The object-lane publication of the real JUnit reports (PKH-378). The step is
            // `core.junit.publishReports`: it captures the selected reports through the run's
            // archive port FIRST — write-once bytes sealed at their digest address — and parses
            // the CAPTURE, never the workspace file again, so the receipt's digest and size are
            // read back from the store. The worker then carries every captured file to the Fabric
            // Core over the object lane (spool → upload → custody), and a later download is served
            // only when its bytes hash to the digest the receipt declared. `required = false` keeps
            // an absent selection an ABSENT verdict, never a pass invented from nothing; the
            // zero-test report of a suite the environment could not run parses whole and says
            // nothing ran. It is a `post` step so a failed suite never costs the publication.
            publishJUnitReports("junit-reports/*/junit.xml", required = false)
        }
    }
}
