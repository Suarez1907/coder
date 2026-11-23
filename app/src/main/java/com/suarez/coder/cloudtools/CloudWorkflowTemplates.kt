package com.suarez.coder.cloudtools

/**
 * GitHub Actions workflows that run tools Coder cannot run on a phone. Each is started with workflow_dispatch by
 * run_cloud_tool and uploads its outputs as an artifact. Inputs reach shell commands only through env variables
 * (never interpolated into the script), so a crafted path cannot inject commands.
 */
object CloudWorkflowTemplates {
    data class CloudTool(val id: String, val title: String, val file: String, val artifact: String, val yaml: String) {
        val path get() = ".github/workflows/$file"
    }

    /** Bumped when a template changes; the installed file carries it so the Skills screen can offer an update. */
    const val VERSION = 2
    private fun marker(id: String) = "# coder-cloud-tool: $id v$VERSION (Coder tarafından oluşturuldu; Ayarlar > Yetenekler'den güncellenir)"

    // § stands for $ (keeps the GitHub expressions readable inside Kotlin strings).
    private fun y(s: String) = s.trimIndent().replace("§", "$") + "\n"

    val BLENDER = CloudTool("blender", "Blender", "coder-blender.yml", "coder-blender-output", y("""
        ${marker("blender")}
        name: Coder Blender

        on:
          workflow_dispatch:
            inputs:
              script_path:
                description: Blender Python betiği (repo içi yol)
                required: true
              output_dir:
                description: Çıktı klasörü
                required: false
                default: coder-out
              blender_version:
                description: Blender sürümü (major.minor)
                required: false
                default: "4.2"

        permissions:
          contents: read

        concurrency:
          group: coder-blender-§{{ github.ref }}
          cancel-in-progress: true

        jobs:
          blender:
            runs-on: ubuntu-latest
            timeout-minutes: 30
            env:
              SCRIPT: §{{ inputs.script_path }}
              CODER_OUTPUT_DIR: §{{ inputs.output_dir }}
              BLENDER_VERSION: §{{ inputs.blender_version }}
            steps:
              - uses: actions/checkout@v4

              - name: Blender sürümünü bul
                id: bl
                run: |
                  file=§(curl -fsSL "https://download.blender.org/release/Blender§{BLENDER_VERSION}/" | grep -o "blender-§{BLENDER_VERSION}\.[0-9]*-linux-x64\.tar\.xz" | sort -uV | tail -1)
                  test -n "§file" || { echo "Blender §BLENDER_VERSION bulunamadı"; exit 1; }
                  echo "file=§file" >> "§GITHUB_OUTPUT"

              - name: Blender önbelleği
                id: cache
                uses: actions/cache@v4
                with:
                  path: ~/blender
                  key: §{{ steps.bl.outputs.file }}

              - name: Blender indir
                if: steps.cache.outputs.cache-hit != 'true'
                run: |
                  mkdir -p ~/blender
                  curl -fsSL "https://download.blender.org/release/Blender§{BLENDER_VERSION}/§{{ steps.bl.outputs.file }}" | tar -xJ -C ~/blender --strip-components=1

              - name: Sistem kütüphaneleri
                run: sudo apt-get update -qq && sudo apt-get install -y -qq libxi6 libxxf86vm1 libxfixes3 libxrender1 libxkbcommon0 libsm6 libgl1 libegl1 > /dev/null

              - name: Betiği çalıştır
                run: |
                  mkdir -p "§CODER_OUTPUT_DIR"
                  set -o pipefail
                  ~/blender/blender --background --factory-startup --python-exit-code 1 --python "§SCRIPT" 2>&1 | tee blender.log
                  test -s "§CODER_OUTPUT_DIR/preview.png" || echo "Uyarı: preview.png üretilmedi"

              - name: Hata özeti
                if: failure()
                run: |
                  echo "::group::Python traceback"
                  grep -n -A30 "Traceback (most recent call last)" blender.log || tail -n 60 blender.log || true
                  echo "::endgroup::"

              - name: Çıktıları yükle
                if: success()
                uses: actions/upload-artifact@v4
                with:
                  name: coder-blender-output
                  if-no-files-found: warn
                  retention-days: 7
                  path: |
                    §{{ env.CODER_OUTPUT_DIR }}/preview.png
                    §{{ env.CODER_OUTPUT_DIR }}/*.glb
                    §{{ env.CODER_OUTPUT_DIR }}/*.blend
                    §{{ env.CODER_OUTPUT_DIR }}/*.png
    """))

    val TOOLBOX = CloudTool("toolbox", "Python / FFmpeg", "coder-toolbox.yml", "coder-toolbox-output", y("""
        ${marker("toolbox")}
        name: Coder Toolbox

        on:
          workflow_dispatch:
            inputs:
              script_path:
                description: Python betiği (repo içi yol)
                required: true
              output_dir:
                description: Çıktı klasörü
                required: false
                default: coder-out
              requirements:
                description: pip gereksinim dosyası (isteğe bağlı)
                required: false
                default: ""
              preset:
                description: Hazır ortam (boş veya manim)
                required: false
                default: ""

        permissions:
          contents: read

        concurrency:
          group: coder-toolbox-§{{ github.ref }}
          cancel-in-progress: true

        jobs:
          toolbox:
            runs-on: ubuntu-latest
            timeout-minutes: 30
            env:
              SCRIPT: §{{ inputs.script_path }}
              CODER_OUTPUT_DIR: §{{ inputs.output_dir }}
              REQUIREMENTS: §{{ inputs.requirements }}
              PRESET: §{{ inputs.preset }}
            steps:
              - uses: actions/checkout@v4

              - uses: actions/setup-python@v5
                with:
                  python-version: "3.12"
                  cache: pip
                  cache-dependency-path: "**/requirements*.txt"

              - name: FFmpeg
                run: sudo apt-get update -qq && sudo apt-get install -y -qq ffmpeg > /dev/null

              - name: Gereksinimler
                run: |
                  if [ -n "§REQUIREMENTS" ] && [ -f "§REQUIREMENTS" ]; then pip install -q -r "§REQUIREMENTS"; fi

              - name: Manim
                if: inputs.preset == 'manim'
                run: |
                  sudo apt-get install -y -qq libcairo2-dev libpango1.0-dev texlive-latex-extra texlive-fonts-extra dvisvgm > /dev/null
                  pip install -q manim

              - name: Betiği çalıştır
                run: |
                  mkdir -p "§CODER_OUTPUT_DIR"
                  python "§SCRIPT"

              - name: Çıktıları yükle
                if: success()
                uses: actions/upload-artifact@v4
                with:
                  name: coder-toolbox-output
                  if-no-files-found: warn
                  retention-days: 7
                  path: §{{ env.CODER_OUTPUT_DIR }}/
    """))

    /**
     * Evaluator: serves a web app (static folder, or a start command) and runs a Playwright script that checks the
     * contract's criteria in a real browser. Writes results.json (criteria + console errors) and screenshots.
     */
    val EVALUATE = CloudTool("evaluate", "Değerlendirici (Playwright)", "coder-evaluate.yml", "coder-evaluate-output", y("""
        ${marker("evaluate")}
        name: Coder Evaluate

        on:
          workflow_dispatch:
            inputs:
              script_path:
                description: Playwright değerlendirme betiği (.mjs)
                required: true
              output_dir:
                description: Çıktı klasörü
                required: false
                default: coder-out
              app_dir:
                description: Statik web kökü (start_command boşsa)
                required: false
                default: "."
              start_command:
                description: Uygulamayı başlatan komut (ör. npm run dev -- --port 8080); boş = statik sunucu
                required: false
                default: ""

        permissions:
          contents: read

        concurrency:
          group: coder-evaluate-§{{ github.ref }}
          cancel-in-progress: true

        jobs:
          evaluate:
            runs-on: ubuntu-latest
            timeout-minutes: 25
            env:
              SCRIPT: §{{ inputs.script_path }}
              CODER_OUTPUT_DIR: §{{ inputs.output_dir }}
              APP_DIR: §{{ inputs.app_dir }}
              START_COMMAND: §{{ inputs.start_command }}
              BASE_URL: http://127.0.0.1:8080
            steps:
              - uses: actions/checkout@v4

              - uses: actions/setup-node@v4
                with:
                  node-version: "20"

              - name: Playwright
                run: |
                  mkdir -p "§RUNNER_TEMP/pw" && cd "§RUNNER_TEMP/pw" && npm init -y > /dev/null && npm i -s playwright@1.47.2
                  npx playwright install --with-deps chromium > /dev/null
                  echo "NODE_PATH=§RUNNER_TEMP/pw/node_modules" >> "§GITHUB_ENV"

              - name: Uygulamayı başlat
                run: |
                  mkdir -p "§CODER_OUTPUT_DIR"
                  if [ -n "§START_COMMAND" ]; then
                    if [ -f package.json ]; then npm ci || npm install; fi
                    nohup bash -c "§START_COMMAND" > app.log 2>&1 &
                  else
                    nohup python3 -m http.server 8080 --directory "§APP_DIR" > app.log 2>&1 &
                  fi
                  for i in §(seq 1 60); do curl -fs "§BASE_URL" > /dev/null && exit 0; sleep 2; done
                  echo "Uygulama 2 dakikada açılmadı"; tail -n 50 app.log; exit 1

              - name: Değerlendir
                run: |
                  cp "§SCRIPT" "§RUNNER_TEMP/pw/eval.mjs"
                  node "§RUNNER_TEMP/pw/eval.mjs"

              - name: Çıktıları yükle
                if: always()
                uses: actions/upload-artifact@v4
                with:
                  name: coder-evaluate-output
                  if-no-files-found: warn
                  retention-days: 7
                  path: §{{ env.CODER_OUTPUT_DIR }}/
    """))

    /**
     * Remotion: renders a React video composition to MP4 plus a still (preview.png). script_path is the entry file
     * (e.g. src/index.ts) relative to app_dir, the folder with package.json.
     */
    val VIDEO = CloudTool("video", "Video (Remotion)", "coder-video.yml", "coder-video-output", y("""
        ${marker("video")}
        name: Coder Video

        on:
          workflow_dispatch:
            inputs:
              script_path:
                description: Remotion giriş dosyası (app_dir'e göre, ör. src/index.ts)
                required: true
              output_dir:
                description: Çıktı klasörü
                required: false
                default: coder-out
              app_dir:
                description: package.json'ın olduğu klasör
                required: false
                default: "."
              composition:
                description: Kompozisyon kimliği (boş = ilk kompozisyon)
                required: false
                default: ""

        permissions:
          contents: read

        concurrency:
          group: coder-video-§{{ github.ref }}
          cancel-in-progress: true

        jobs:
          video:
            runs-on: ubuntu-latest
            timeout-minutes: 30
            env:
              SCRIPT: §{{ inputs.script_path }}
              CODER_OUTPUT_DIR: §{{ inputs.output_dir }}
              APP_DIR: §{{ inputs.app_dir }}
              COMPOSITION: §{{ inputs.composition }}
            steps:
              - uses: actions/checkout@v4

              - uses: actions/setup-node@v4
                with:
                  node-version: "20"

              - name: Bağımlılıklar
                run: |
                  cd "§APP_DIR"
                  if [ -f package-lock.json ]; then npm ci; else npm install; fi
                  npx remotion browser ensure

              - name: Render
                run: |
                  OUT="§GITHUB_WORKSPACE/§CODER_OUTPUT_DIR"
                  mkdir -p "§OUT"
                  cd "§APP_DIR"
                  if [ -z "§COMPOSITION" ]; then
                    COMPOSITION=§(npx remotion compositions "§SCRIPT" -q | head -1 | awk '{print §1}')
                  fi
                  test -n "§COMPOSITION" || { echo "Kompozisyon bulunamadı"; exit 1; }
                  npx remotion render "§SCRIPT" "§COMPOSITION" "§OUT/video.mp4" --log=error
                  npx remotion still "§SCRIPT" "§COMPOSITION" "§OUT/preview.png" --frame=30 --log=error || true

              - name: Çıktıları yükle
                if: success()
                uses: actions/upload-artifact@v4
                with:
                  name: coder-video-output
                  if-no-files-found: warn
                  retention-days: 7
                  path: §{{ env.CODER_OUTPUT_DIR }}/
    """))

    /** markitdown: converts a document (PDF, Office, HTML, ...) in the repo to document.md. Used for chat attachments. */
    val DOCS = CloudTool("docs", "Belge okuyucu (markitdown)", "coder-docs.yml", "coder-docs-output", y("""
        ${marker("docs")}
        name: Coder Docs

        on:
          workflow_dispatch:
            inputs:
              script_path:
                description: Dönüştürülecek belge (repo içi yol)
                required: true
              output_dir:
                description: Çıktı klasörü
                required: false
                default: coder-out

        permissions:
          contents: read

        jobs:
          docs:
            runs-on: ubuntu-latest
            timeout-minutes: 15
            env:
              SCRIPT: §{{ inputs.script_path }}
              CODER_OUTPUT_DIR: §{{ inputs.output_dir }}
            steps:
              - uses: actions/checkout@v4

              - uses: actions/setup-python@v5
                with:
                  python-version: "3.12"

              - name: markitdown
                run: |
                  pip install -q 'markitdown[all]'
                  mkdir -p "§CODER_OUTPUT_DIR"
                  markitdown "§SCRIPT" > "§CODER_OUTPUT_DIR/document.md"
                  test -s "§CODER_OUTPUT_DIR/document.md" || { echo "Belgeden metin çıkmadı"; exit 1; }

              - name: Çıktıları yükle
                if: success()
                uses: actions/upload-artifact@v4
                with:
                  name: coder-docs-output
                  if-no-files-found: error
                  retention-days: 1
                  path: §{{ env.CODER_OUTPUT_DIR }}/document.md
    """))

    val ALL = listOf(BLENDER, TOOLBOX, EVALUATE, VIDEO, DOCS)

    /** Inputs a template accepts besides script_path/output_dir: anything else would make the dispatch fail. */
    fun extraInputNames(t: CloudTool): Set<String> = when (t) {
        BLENDER -> setOf("blender_version")
        TOOLBOX -> setOf("requirements", "preset")
        EVALUATE -> setOf("app_dir", "start_command")
        VIDEO -> setOf("app_dir", "composition")
        else -> emptySet()
    }

    /** Maps the run_cloud_tool `tool` argument (also "coder-blender.yml", "Blender", "python", "ffmpeg") to a template. */
    fun forTool(tool: String): CloudTool? {
        val t = tool.trim().lowercase().removePrefix("coder-").removeSuffix(".yml")
        return when (t) {
            "blender", "bpy", "3d" -> BLENDER
            "toolbox", "python", "ffmpeg", "py" -> TOOLBOX
            "evaluate", "evaluator", "playwright", "e2e" -> EVALUATE
            "video", "remotion", "mp4" -> VIDEO
            "manim" -> TOOLBOX
            "docs", "markitdown", "document", "belge" -> DOCS
            else -> null
        }
    }

    enum class InstallState(val label: String) { MISSING("Kurulu değil"), OUTDATED("Güncel değil"), CUSTOM("Elle düzenlenmiş"), CURRENT("Kurulu") }

    /** Compares the file in the repository with the template. */
    fun state(tool: CloudTool, installed: String?): InstallState = when {
        installed == null -> InstallState.MISSING
        installed.trim() == tool.yaml.trim() -> InstallState.CURRENT
        installed.contains("# coder-cloud-tool: ${tool.id} v") -> InstallState.OUTDATED
        else -> InstallState.CUSTOM
    }
}
