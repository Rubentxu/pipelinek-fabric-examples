// The canonical .NET pipeline: Basket.UnitTests from dotnet/eShop.
//
// This file holds the logic only. The values it runs with (the profile, the SDK image, the format
// gate, the registry) come from the environment, and the manifest beside it declares them:
// `eshop-dotnet.pipeline.yaml`. Nothing here names a publication registry or a cluster.
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
// **The slice is bounded.** dotnet/eShop is a whole application: several APIs, a MAUI client, an
// Aspire AppHost and nine test assemblies. This pipeline builds and tests `tests/Basket.UnitTests`
// only — a self-contained MSTest assembly whose fakes are NSubstitute mocks of the repository
// interface, so it needs no database, no broker, no browser and no live app host. The
// FunctionalTests drive the whole application through Playwright and are out of scope by
// definition, the MAUI client tests need workloads the SDK image does not carry, and the other
// unit assemblies pull several of the application projects into the build for no additional law.
// The manifest names the slice, and the prepare stage refuses when the pinned commit does not
// hold it.
//
// **The toolchain is containerized and pinned by digest.** The worker that measures this reference
// has no dotnet on its PATH: unlike the other pipelines of this directory, quick cannot compile
// with a host toolchain. Every compile, test and format command therefore runs inside the SDK
// image the manifest names — `mcr.microsoft.com/dotnet/sdk`, the canonical public registry of the
// .NET images, referenced by @sha256 digest so the toolchain cannot move under the run — and the
// quality stage refuses when the SDK inside that container is not the version global.json pins.
// A run that compiled with an unnamed compiler describes no build.
//
// **The format check is not a promise the source made.** Upstream CI builds the app and does not
// format it, and the pinned source is not format-clean. A gate that is red on every commit of the
// source protects nothing, so `format` reports NOT_RUN with that reason unless the operator turns
// it on with DOTNET_FORMAT_GATE=true; turning it on makes it a real gate that fails on violations.
//
// **Publication is off by default.** Only `real-ci` can publish, and only when the run sets
// `DOTNET_PUBLISH=true`. Then every value the publication needs must be declared, and a missing
// one refuses the run before anything is built. The reference stops at publish: Basket.API serves
// only beside the Redis and the message bus the application wires around it, so this slice has no
// standalone staging story, and inventing one would certify a topology the source does not ship.
//
// Run: pipelinek run eshop-dotnet.pipeline.kts (with the manifest's values in the environment)

pipeline {
    /** The variables the shell stages read. They are written to `env.sh` by the first stage. */
    val exportedVariables = listOf(
        "DOTNET_PROFILE",
        "DOTNET_WORK_DIR",
        "DOTNET_SOURCE_URL",
        "DOTNET_SOURCE_COMMIT",
        "DOTNET_TEST_PROJECT",
        "DOTNET_APP_PROJECT",
        "DOTNET_SDK_IMAGE",
        "DOTNET_SDK_VERSION",
        "DOTNET_FORMAT_GATE",
        "DOTNET_PUBLISH",
        "DOTNET_PUSH_REGISTRY",
        "DOTNET_IMAGE_REPOSITORY",
        "DOTNET_IMAGE_TAG",
        "DOTNET_BASE_IMAGE",
    )

    /** The stages each profile runs. The names of the profiles are the laboratory's three, and no others. */
    val stagesOfProfile: Map<String, Set<String>> = mapOf(
        "quick" to setOf("prepare", "quality", "unit"),
        "developer" to setOf("prepare", "quality", "unit", "format"),
        "real-ci" to setOf(
            "prepare", "quality", "unit", "format", "package", "containers", "artifacts", "publish",
        ),
    )

    /** The stage that changes a registry. It runs only when the publication is enabled. */
    val publishingStages = setOf("publish")

    val activeProfile: String = System.getenv("DOTNET_PROFILE") ?: ""
    val publicationEnabled: Boolean = activeProfile == "real-ci" && System.getenv("DOTNET_PUBLISH") == "true"
    val workDir: String = System.getenv("DOTNET_WORK_DIR") ?: ""

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

    /**
     * The pinned SDK container running one shell command. The tree and the two caches come in as
     * bind mounts with `:z`, the relabel SELinux-enforcing workers need and a no-op elsewhere.
     * No `--user` is passed: on a rootless engine the container user already IS the host worker,
     * and mapping the host uid would land it outside the namespace's uid range, where every write
     * into the mounted tree is denied. The caches live in the work directory so the stages of one
     * run restore once instead of three times.
     */
    fun containerRun(inner: String): String = """
        docker run --rm \
          -v "${'$'}DOTNET_WORK_DIR/project":/src:z -w /src \
          -v "${'$'}DOTNET_WORK_DIR/clihome":/work/clihome:z \
          -v "${'$'}DOTNET_WORK_DIR/nuget":/work/nuget:z \
          -e HOME=/work/clihome -e DOTNET_CLI_HOME=/work/clihome -e NUGET_PACKAGES=/work/nuget \
          -e DOTNET_NOLOGO=1 -e DOTNET_CLI_TELEMETRY_OPTOUT=1 \
          -e DOTNET_SDK_VERSION="${'$'}DOTNET_SDK_VERSION" \
          -e DOTNET_TEST_PROJECT="${'$'}DOTNET_TEST_PROJECT" \
          -e DOTNET_APP_PROJECT="${'$'}DOTNET_APP_PROJECT" \
          "${'$'}DOTNET_SDK_IMAGE" sh -c ${shellQuoted(inner)}
    """.trimIndent()

    stages {
        stage("prepare") {
            // The export lines are interpolated after the template is trimmed: trimIndent would
            // then indent the EXPORTS terminator, and the heredoc would never close. The last
            // line of the file rewrites the work directory as an absolute path on purpose: a
            // stage that cd's into the checkout must still find its own summary helper, and the
            // container engine reads the source of a bind mount as an absolute path and nothing
            // else.
            val envFile = exportLines() + "\n" + """
                DOTNET_WORK_DIR="$(cd "$(dirname ${shellQuoted("$workDir/env.sh")})" && pwd)"
                export DOTNET_WORK_DIR
            """.trimIndent()
            sh("""
                set -eu
                mkdir -p ${shellQuoted(workDir)}
                cat > ${shellQuoted("$workDir/env.sh")} <<'EXPORTS'
            """.trimIndent() + "\n" + envFile + "\nEXPORTS\n" + """
                . ${shellQuoted("$workDir/env.sh")}
                case "${'$'}DOTNET_PROFILE" in
                  quick|developer|real-ci) ;;
                  *) echo "refused: the profile '${'$'}DOTNET_PROFILE' is not one of quick, developer, real-ci" >&2; exit 2 ;;
                esac
                case "${'$'}DOTNET_FORMAT_GATE" in
                  true|false) ;;
                  *) echo "refused: DOTNET_FORMAT_GATE must be true or false" >&2; exit 2 ;;
                esac
                case "${'$'}DOTNET_PUBLISH" in
                  true|false) ;;
                  *) echo "refused: DOTNET_PUBLISH must be true or false" >&2; exit 2 ;;
                esac
                case "${'$'}DOTNET_SDK_IMAGE" in
                  mcr.microsoft.com/dotnet/sdk@sha256:*) ;;
                  *) echo "refused: the SDK image must be mcr.microsoft.com/dotnet/sdk pinned by @sha256 digest" >&2; exit 2 ;;
                esac
                if [ "${'$'}DOTNET_PUBLISH" = true ] && [ "${'$'}DOTNET_PROFILE" = real-ci ]; then
                  missing=""
                  [ -n "${'$'}DOTNET_PUSH_REGISTRY" ] || missing="${'$'}missing DOTNET_PUSH_REGISTRY"
                  [ -n "${'$'}DOTNET_IMAGE_TAG" ] || missing="${'$'}missing DOTNET_IMAGE_TAG"
                  [ -n "${'$'}DOTNET_BASE_IMAGE" ] || missing="${'$'}missing DOTNET_BASE_IMAGE"
                  if [ -n "${'$'}missing" ]; then
                    echo "refused: publication is enabled and the run does not declare:${'$'}missing" >&2
                    exit 2
                  fi
                fi
                mkdir -p "${'$'}DOTNET_WORK_DIR/clihome" "${'$'}DOTNET_WORK_DIR/nuget"
                cd "${'$'}DOTNET_WORK_DIR"
                rm -rf project
                git clone --quiet "${'$'}DOTNET_SOURCE_URL" project
                cd project
                git -c advice.detachedHead=false checkout --quiet "${'$'}DOTNET_SOURCE_COMMIT"
                if [ "${'$'}(git rev-parse HEAD)" != "${'$'}DOTNET_SOURCE_COMMIT" ]; then
                  echo "the checkout holds ${'$'}(git rev-parse HEAD), not the commit the manifest names" >&2
                  exit 1
                fi
                echo source-commit=${'$'}(git rev-parse HEAD)
                # The bounded slice is a project of its own; if the commit does not hold it, the
                # counts below would describe some other tree, and this run has no evidence to give.
                test -f global.json
                test -f "${'$'}DOTNET_TEST_PROJECT"
                test -f "${'$'}DOTNET_APP_PROJECT"
                echo slice=${'$'}DOTNET_TEST_PROJECT
                cat > "${'$'}DOTNET_WORK_DIR/summary.sh" <<'HELPER'
                #!/bin/sh
                # The counts of a Microsoft.Testing.Platform log: the total, the failures and the
                # skipped. The ANSI strip comes first because a runner that decides its output is a
                # terminal paints colour codes between the field name and its value, and a count
                # parsed from coloured output silently reads as no count. A summary block with no
                # total line says nothing about the tests, and that is not a verdict to read green:
                # a build that failed before discovering a single test lands here.
                log="${'$'}1"
                plain=${'$'}(sed -e "s/\\x1b\\[[0-9;]*m//g" "${'$'}log")
                total=${'$'}(printf "%s\\n" "${'$'}plain" | sed -n "s/^ *total: *\\([0-9][0-9]*\\).*/\\1/p" | tail -1)
                failed=${'$'}(printf "%s\\n" "${'$'}plain" | sed -n "s/^ *failed: *\\([0-9][0-9]*\\).*/\\1/p" | tail -1)
                skipped=${'$'}(printf "%s\\n" "${'$'}plain" | sed -n "s/^ *skipped: *\\([0-9][0-9]*\\).*/\\1/p" | tail -1)
                if [ -z "${'$'}total" ] || [ -z "${'$'}failed" ] || [ -z "${'$'}skipped" ]; then
                  echo "the log says nothing about the tests" >&2
                  exit 1
                fi
                echo "${'$'}total ${'$'}failed ${'$'}skipped"
                HELPER
                chmod +x "${'$'}DOTNET_WORK_DIR/summary.sh"
                echo profile=${'$'}DOTNET_PROFILE publication=${'$'}DOTNET_PUBLISH format-gate=${'$'}DOTNET_FORMAT_GATE
            """.trimIndent())
        }
        stage("quality") {
            sh(stageOrSkip("quality", """
                set -eu
                . ${shellQuoted("$workDir/env.sh")}
                # There is no host dotnet by construction: without the engine there is no toolchain
                # at all, and a quick run that could not compile refuses rather than reporting a
                # NOT_RUN it is the whole point of the profile to answer.
                if ! command -v docker > /dev/null 2>&1; then
                  echo "refused: the .NET toolchain lives in the pinned container and there is no docker on the PATH" >&2
                  exit 2
                fi
                if ! docker info > /dev/null 2>&1; then
                  echo "refused: the .NET toolchain lives in the pinned container and no engine answers" >&2
                  exit 2
                fi
                if ! ${containerRun("""if [ "${'$'}(dotnet --version)" != "${'$'}DOTNET_SDK_VERSION" ]; then echo "refused: the container SDK is ${'$'}(dotnet --version) and the manifest pins ${'$'}DOTNET_SDK_VERSION" >&2; exit 2; fi; dotnet build "${'$'}DOTNET_TEST_PROJECT" -c Release""")} > "${'$'}DOTNET_WORK_DIR/quality.log" 2>&1; then
                  tail -40 "${'$'}DOTNET_WORK_DIR/quality.log"
                  exit 1
                fi
                echo quality=ok sdk=${'$'}DOTNET_SDK_VERSION image=${'$'}DOTNET_SDK_IMAGE
            """.trimIndent()))
        }
        stage("unit") {
            sh(stageOrSkip("unit", """
                set -eu
                . ${shellQuoted("$workDir/env.sh")}
                # The whole log goes to the file and the verdict is the summary block the runner
                # prints, never the exit code of a pipeline ending in tail: a shell reads the exit
                # of the last element, and `dotnet ... | tail` is green whenever tail is happy.
                if ! ${containerRun("""dotnet test "${'$'}DOTNET_TEST_PROJECT" -c Release""")} > "${'$'}DOTNET_WORK_DIR/unit.log" 2>&1; then
                  tail -40 "${'$'}DOTNET_WORK_DIR/unit.log"
                  exit 1
                fi
                summary=${'$'}(sh "${'$'}DOTNET_WORK_DIR/summary.sh" "${'$'}DOTNET_WORK_DIR/unit.log")
                set -- ${'$'}summary
                echo unit-tests=${'$'}1 unit-failures=${'$'}2 unit-skipped=${'$'}3
                if [ "${'$'}2" != "0" ]; then
                  echo "the unit suite failed" >&2
                  exit 1
                fi
                if [ "${'$'}1" = "0" ]; then
                  echo "the unit suite ran no test at all" >&2
                  exit 1
                fi
            """.trimIndent()))
        }
        stage("format") {
            sh(stageOrSkip("format", """
                set -eu
                . ${shellQuoted("$workDir/env.sh")}
                # Upstream CI builds the application and never formats it, and the pinned source is
                # not format-clean: a gate red on every commit of the source protects nothing. The
                # honest word for a check this reference does not promise is NOT_RUN with that
                # reason; an operator who wants the promise turns it on and gets a real gate.
                if [ "${'$'}DOTNET_FORMAT_GATE" != "true" ]; then
                  echo format=NOT_RUN reason=the-pinned-source-is-not-format-gated-upstream-dotnet-format-gate-is-the-override
                  exit 0
                fi
                if ! ${containerRun("""dotnet format "${'$'}DOTNET_TEST_PROJECT" --verify-no-changes && dotnet format "${'$'}DOTNET_APP_PROJECT" --verify-no-changes""")} > "${'$'}DOTNET_WORK_DIR/format.log" 2>&1; then
                  tail -40 "${'$'}DOTNET_WORK_DIR/format.log"
                  echo "the source is not format-clean and the run asked for the gate" >&2
                  exit 1
                fi
                echo format=clean
            """.trimIndent()))
        }
        stage("package") {
            sh(stageOrSkip("package", """
                set -eu
                . ${shellQuoted("$workDir/env.sh")}
                # The publish folder and the dependency inventory are the two auditable halves of
                # the artifact: what ships, and which package graph it was built against. The SDK
                # ships the inventory command, and naming an external SBOM tool would put an
                # unmeasured supply chain inside the reference. The inventory is written inside the
                # mount (/src is the checkout) because the container never sees the host paths.
                if ! ${containerRun("""dotnet publish "${'$'}DOTNET_APP_PROJECT" -c Release -o publish/app && dotnet list "${'$'}DOTNET_APP_PROJECT" package > dependency-inventory.txt""")} > "${'$'}DOTNET_WORK_DIR/package.log" 2>&1; then
                  tail -40 "${'$'}DOTNET_WORK_DIR/package.log"
                  exit 1
                fi
                cp -r "${'$'}DOTNET_WORK_DIR/project/publish/app" "${'$'}DOTNET_WORK_DIR/publish"
                cp "${'$'}DOTNET_WORK_DIR/project/dependency-inventory.txt" "${'$'}DOTNET_WORK_DIR/dependency-inventory.txt"
                test -f "${'$'}DOTNET_WORK_DIR/publish/Basket.API.dll"
                test -s "${'$'}DOTNET_WORK_DIR/dependency-inventory.txt"
                echo package=publish-folder dll-sha256=${'$'}(sha256sum "${'$'}DOTNET_WORK_DIR/publish/Basket.API.dll" | cut -d' ' -f1)
            """.trimIndent()))
        }
        stage("containers") {
            sh(stageOrSkip("containers", """
                set -eu
                . ${shellQuoted("$workDir/env.sh")}
                status=0
                docker info > "${'$'}DOTNET_WORK_DIR/containers.log" 2>&1 || status=${'$'}?
                if [ "${'$'}status" != "0" ]; then
                  echo containers=NOT_RUN reason=no-container-engine
                  exit 0
                fi
                # The runtime base is the operator's to name and the run's to pin: an image named by
                # tag would move under the build, and a base the reference picked itself is a supply
                # chain this file cannot vouch for.
                if [ -z "${'$'}DOTNET_BASE_IMAGE" ]; then
                  echo containers=NOT_RUN reason=runtime-base-image-not-declared
                  exit 0
                fi
                case "${'$'}DOTNET_BASE_IMAGE" in
                  *@sha256:*) ;;
                  *) echo containers=NOT_RUN reason=runtime-base-image-not-pinned-by-digest; exit 0 ;;
                esac
                if [ -z "${'$'}DOTNET_IMAGE_TAG" ]; then
                  echo containers=NOT_RUN reason=image-tag-not-declared
                  exit 0
                fi
                # The image carries exactly the publish folder the package stage produced, so it is
                # built from the same bytes the unit suite verified — not recompiled on a recipe of
                # this pipeline's own.
                cat > "${'$'}DOTNET_WORK_DIR/app.Dockerfile" <<APPIMAGE
                FROM ${'$'}DOTNET_BASE_IMAGE
                COPY app/ /app/
                WORKDIR /app
                ENTRYPOINT ["dotnet", "Basket.API.dll"]
                APPIMAGE
                mv "${'$'}DOTNET_WORK_DIR/publish" "${'$'}DOTNET_WORK_DIR/publish-root" \
                  && mkdir -p "${'$'}DOTNET_WORK_DIR/publish" \
                  && mv "${'$'}DOTNET_WORK_DIR/publish-root" "${'$'}DOTNET_WORK_DIR/publish/app"
                if ! docker build --tag "${'$'}DOTNET_IMAGE_REPOSITORY:${'$'}DOTNET_IMAGE_TAG" \
                    -f "${'$'}DOTNET_WORK_DIR/app.Dockerfile" "${'$'}DOTNET_WORK_DIR/publish" \
                    >> "${'$'}DOTNET_WORK_DIR/containers.log" 2>&1; then
                  tail -40 "${'$'}DOTNET_WORK_DIR/containers.log"
                  echo "the image build failed" >&2
                  exit 1
                fi
                echo containers=local-image repository=${'$'}DOTNET_IMAGE_REPOSITORY tag=${'$'}DOTNET_IMAGE_TAG
            """.trimIndent()))
        }
        stage("artifacts") {
            sh(stageOrSkip("artifacts", """
                set -eu
                . ${shellQuoted("$workDir/env.sh")}
                mkdir -p artifacts
                # A stage that reported NOT_RUN or was switched off leaves no log behind: the artifact
                # set says what ran, and copying a missing file as a green lie is exactly what this
                # pipeline is here to refuse.
                for log in quality unit format package containers; do
                  if [ -f "${'$'}DOTNET_WORK_DIR/${'$'}log.log" ]; then
                    cp "${'$'}DOTNET_WORK_DIR/${'$'}log.log" "artifacts/${'$'}log.log"
                  fi
                done
                if [ -f "${'$'}DOTNET_WORK_DIR/dependency-inventory.txt" ]; then
                  cp "${'$'}DOTNET_WORK_DIR/dependency-inventory.txt" artifacts/dependency-inventory.txt
                fi
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
                image="${'$'}DOTNET_PUSH_REGISTRY/${'$'}DOTNET_IMAGE_REPOSITORY:${'$'}DOTNET_IMAGE_TAG"
                docker tag "${'$'}DOTNET_IMAGE_REPOSITORY:${'$'}DOTNET_IMAGE_TAG" "${'$'}image" \
                  > "${'$'}DOTNET_WORK_DIR/publish.log" 2>&1
                if ! docker push "${'$'}image" >> "${'$'}DOTNET_WORK_DIR/publish.log" 2>&1; then
                  tail -40 "${'$'}DOTNET_WORK_DIR/publish.log"
                  echo "the push failed" >&2
                  exit 1
                fi
                echo published-digest=${'$'}(docker image inspect --format='{{index .RepoDigests 0}}' "${'$'}image")
            """.trimIndent()))
        }
    }
}
