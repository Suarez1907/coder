package com.suarez.coder.cloudtools

/**
 * GitHub Actions workflows that run tools Coder cannot run on a phone. Each is started with workflow_dispatch by
 * run_cloud_tool and uploads its outputs as an artifact. Inputs reach shell commands only through env variables
 * (never interpolated into the script), so a crafted path cannot inject commands.
 */
object CloudWorkflowTemplates {
    data class CloudTool(val id: String, val title: String, val file: String, val artifact: String, val yaml: String,
                         /** The workflow works without a script file in the repo (it has defaults). */
                         val scriptOptional: Boolean = false) {
        val path get() = ".github/workflows/$file"
    }

    /** Bumped when a template changes; the installed file carries it so the Skills screen can offer an update. */
    const val VERSION = 4
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

    /**
     * Fast pre-commit check: compile / type-check only (Gradle compileDebugSources or classes, tsc --noEmit, Python syntax
     * and undefined names), or the repo's own .coder/quick-check.sh. Runs on a throwaway [skip ci] branch.
     */
    val QUICK = CloudTool("quick", "Hızlı ön kontrol", "coder-quick.yml", "coder-quick-output", y("""
        ${marker("quick")}
        name: Coder Quick Check

        on:
          workflow_dispatch:
            inputs:
              script_path:
                description: Özel kontrol betiği (varsa)
                required: false
                default: .coder/quick-check.sh
              output_dir:
                description: Çıktı klasörü
                required: false
                default: coder-out
              test_module:
                description: Gradle modülü (hedefli testler)
                required: false
                default: ""
              test_classes:
                description: Çalıştırılacak test sınıfları (boşlukla)
                required: false
                default: ""
              test_files:
                description: Çalıştırılacak test dosyaları (JS/Python, boşlukla)
                required: false
                default: ""

        permissions:
          contents: read

        concurrency:
          group: coder-quick-§{{ github.ref }}
          cancel-in-progress: true

        jobs:
          quick:
            runs-on: ubuntu-latest
            timeout-minutes: 20
            env:
              SCRIPT: §{{ inputs.script_path }}
              CODER_OUTPUT_DIR: §{{ inputs.output_dir }}
              TEST_MODULE: §{{ inputs.test_module }}
              TEST_CLASSES: §{{ inputs.test_classes }}
              TEST_FILES: §{{ inputs.test_files }}
            steps:
              - uses: actions/checkout@v4

              - name: Proje türü
                id: kind
                run: |
                  if [ -n "§SCRIPT" ] && [ -f "§SCRIPT" ]; then k=custom
                  elif [ -f gradlew ]; then k=gradle
                  elif [ -f package.json ]; then k=node
                  elif ls requirements*.txt pyproject.toml setup.py *.py > /dev/null 2>&1; then k=python
                  else k=none; fi
                  echo "kind=§k" >> "§GITHUB_OUTPUT"

              - uses: actions/setup-java@v4
                if: steps.kind.outputs.kind == 'gradle'
                with:
                  distribution: temurin
                  java-version: "17"

              - uses: gradle/actions/setup-gradle@v4
                if: steps.kind.outputs.kind == 'gradle'

              - uses: actions/setup-node@v4
                if: steps.kind.outputs.kind == 'node'
                with:
                  node-version: "20"

              - uses: actions/setup-python@v5
                if: steps.kind.outputs.kind == 'python'
                with:
                  python-version: "3.12"

              - name: Kontrol
                env:
                  KIND: §{{ steps.kind.outputs.kind }}
                run: |
                  set -o pipefail
                  case "§KIND" in
                    custom) bash "§SCRIPT" ;;
                    gradle)
                      chmod +x gradlew
                      if ! ./gradlew compileDebugSources --console=plain 2>&1 | tee check.log; then
                        if grep -q "Task 'compileDebugSources' not found" check.log; then ./gradlew classes --console=plain; else exit 1; fi
                      fi ;;
                    node)
                      if [ -f package-lock.json ]; then npm ci --no-audit --no-fund; else npm install --no-audit --no-fund; fi
                      if [ -f tsconfig.json ]; then npx --no-install tsc --noEmit || npx -y -p typescript tsc --noEmit; else echo "tsconfig yok: yalnızca kurulum denetlendi"; fi ;;
                    python)
                      python -m compileall -q .
                      pip install -q ruff && ruff check --select E9,F63,F7,F82 --output-format concise . ;;
                    *) echo "Tanınan proje türü yok; kontrol atlandı" ;;
                  esac

              - name: Etkilenen testler
                if: env.TEST_CLASSES != '' || env.TEST_FILES != ''
                env:
                  KIND: §{{ steps.kind.outputs.kind }}
                run: |
                  set -o pipefail
                  set -f
                  ARGS=""; for c in §TEST_CLASSES; do c=§(printf '%s' "§c" | tr -cd 'A-Za-z0-9_.*'); if [ -n "§c" ]; then ARGS="§ARGS --tests §c"; fi; done
                  FILES=""; for f in §TEST_FILES; do f=§(printf '%s' "§f" | tr -cd 'A-Za-z0-9_./@-'); if [ -n "§f" ]; then FILES="§FILES §f"; fi; done
                  MOD=§(printf '%s' "§TEST_MODULE" | tr -cd 'A-Za-z0-9_:-')
                  case "§KIND" in
                    gradle)
                      P=""; if [ -n "§MOD" ]; then P=":§MOD:"; fi
                      if ! ./gradlew "§{P}testDebugUnitTest" §ARGS --console=plain 2>&1 | tee tests.log; then
                        if grep -q "not found in" tests.log; then ./gradlew "§{P}test" §ARGS --console=plain; else exit 1; fi
                      fi ;;
                    node)
                      if grep -q '"vitest"' package.json; then npx --no-install vitest run §FILES
                      elif grep -q '"jest"' package.json; then npx --no-install jest §FILES
                      else echo "Test çalıştırıcısı bulunamadı (jest/vitest)"; fi ;;
                    python)
                      pip install -q pytest
                      set +f; for r in requirements*.txt; do if [ -f "§r" ]; then pip install -q -r "§r"; fi; done; set -f
                      python -m pytest -q §FILES ;;
                  esac

              - name: Günlüğü yükle
                if: failure()
                uses: actions/upload-artifact@v4
                with:
                  name: coder-quick-output
                  if-no-files-found: ignore
                  retention-days: 1
                  path: check.log
    """), scriptOptional = true)

    /**
     * Android screenshots: builds the debug APK, boots an emulator, launches the app, captures the first screen and the
     * screens reached by the commands in .coder/screens.txt (one `adb shell` command per line), and fails on a crash.
     */
    val ANDROID_SHOTS = CloudTool("android", "Android ekran görüntüleri (emülatör)", "coder-android-shots.yml", "coder-android-output", y("""
        ${marker("android")}
        name: Coder Android Screens

        on:
          workflow_dispatch:
            inputs:
              script_path:
                description: Ekran adımları (her satır bir adb shell komutu)
                required: false
                default: .coder/screens.txt
              output_dir:
                description: Çıktı klasörü
                required: false
                default: coder-out
              api_level:
                description: Emülatör API seviyesi
                required: false
                default: "34"
              flows:
                description: Maestro akış klasörü (kabul ölçütleri; boş = yok)
                required: false
                default: ""

        permissions:
          contents: read

        concurrency:
          group: coder-android-§{{ github.ref }}
          cancel-in-progress: true

        jobs:
          shots:
            runs-on: ubuntu-latest
            timeout-minutes: 45
            env:
              SCRIPT: §{{ inputs.script_path }}
              CODER_OUTPUT_DIR: §{{ inputs.output_dir }}
              API_LEVEL: §{{ inputs.api_level }}
              FLOWS: §{{ inputs.flows }}
            steps:
              - uses: actions/checkout@v4

              - uses: actions/setup-java@v4
                with:
                  distribution: temurin
                  java-version: "17"

              - uses: gradle/actions/setup-gradle@v4

              - name: APK
                run: chmod +x gradlew && ./gradlew assembleDebug --console=plain

              - name: Maestro
                if: env.FLOWS != ''
                run: curl -fsSL "https://get.maestro.mobile.dev" | bash

              - name: KVM
                run: |
                  echo 'KERNEL=="kvm", GROUP="kvm", MODE="0666", OPTIONS+="static_node=kvm"' | sudo tee /etc/udev/rules.d/99-kvm4all.rules
                  sudo udevadm control --reload-rules
                  sudo udevadm trigger --name-match=kvm

              - name: Ekran betiği
                run: |
                  cat > "§RUNNER_TEMP/shots.sh" <<'SH'
                  set -e
                  OUT="§GITHUB_WORKSPACE/§CODER_OUTPUT_DIR"; mkdir -p "§OUT"
                  APK=§(find . -path '*build/outputs/apk/debug/*.apk' | head -1)
                  test -n "§APK" || { echo "Debug APK bulunamadı"; exit 1; }
                  adb install -r "§APK"
                  AAPT=§(ls "§ANDROID_HOME"/build-tools/*/aapt 2>/dev/null | tail -1)
                  PKG=§("§AAPT" dump badging "§APK" | sed -n "s/package: name='\([^']*\)'.*/\1/p")
                  echo "Paket: §PKG"
                  adb logcat -c || true
                  adb shell monkey -p "§PKG" -c android.intent.category.LAUNCHER 1 > /dev/null
                  sleep 10
                  adb exec-out screencap -p > "§OUT/preview.png"
                  i=1
                  if [ -f "§SCRIPT" ]; then
                    while IFS= read -r line || [ -n "§line" ]; do
                      case "§line" in ''|'#'*) continue ;; esac
                      adb shell "§line" < /dev/null || true
                      sleep 3
                      adb exec-out screencap -p > "§OUT/shot_§i.png" < /dev/null
                      i=§((i+1))
                    done < "§SCRIPT"
                  fi
                  if [ -n "§FLOWS" ] && [ -d "§GITHUB_WORKSPACE/§FLOWS" ]; then
                    export PATH="§PATH:§HOME/.maestro/bin"
                    ( cd "§OUT" && maestro test "§GITHUB_WORKSPACE/§FLOWS" --format junit --output "§OUT/maestro-report.xml" ) > "§OUT/maestro.log" 2>&1 < /dev/null || echo "MAESTRO_FAILED" >> "§OUT/maestro.log"
                    echo "---- Maestro ----"; tail -n 40 "§OUT/maestro.log"
                  fi
                  adb logcat -d -b crash > "§OUT/crash.txt" < /dev/null || true
                  if [ -s "§OUT/crash.txt" ]; then echo "UYGULAMA ÇÖKTÜ:"; cat "§OUT/crash.txt"; exit 1; fi
                  SH

              - uses: reactivecircus/android-emulator-runner@v2
                with:
                  api-level: §{{ env.API_LEVEL }}
                  arch: x86_64
                  target: google_apis
                  disable-animations: true
                  emulator-options: -no-window -gpu swiftshader_indirect -noaudio -no-boot-anim -no-snapshot
                  script: bash "§RUNNER_TEMP/shots.sh"

              - name: Çıktıları yükle
                if: always()
                uses: actions/upload-artifact@v4
                with:
                  name: coder-android-output
                  if-no-files-found: warn
                  retention-days: 7
                  path: §{{ env.CODER_OUTPUT_DIR }}/
    """), scriptOptional = true)

    /**
     * Code index: universal-ctags definitions (all common languages) plus, for every defined name, the lines that use
     * it. The phone uses it for precise references and for reading one symbol instead of a whole file.
     */
    val INDEX = CloudTool("index", "Kod dizini", "coder-index.yml", "coder-index-output", y("""
        ${marker("index")}
        name: Coder Index

        on:
          workflow_dispatch:
            inputs:
              script_path:
                description: Kullanılmıyor
                required: false
                default: "-"
              output_dir:
                description: Çıktı klasörü
                required: false
                default: coder-out

        permissions:
          contents: read

        concurrency:
          group: coder-index-§{{ github.ref }}
          cancel-in-progress: true

        jobs:
          index:
            runs-on: ubuntu-latest
            timeout-minutes: 15
            env:
              CODER_OUTPUT_DIR: §{{ inputs.output_dir }}
            steps:
              - uses: actions/checkout@v4

              - name: Dizin
                run: |
                  sudo apt-get install -y -qq universal-ctags > /dev/null
                  mkdir -p "§CODER_OUTPUT_DIR"
                  ctags --output-format=json --fields=+nKS -R --exclude=.git --exclude=node_modules --exclude=build --exclude=dist --exclude=.gradle --exclude='*.min.js' -f "§RUNNER_TEMP/tags.json" . || true
                  cat > "§RUNNER_TEMP/index.py" <<'PY'
                  import json, os, re, sys
                  out_dir, tags = sys.argv[1], sys.argv[2]
                  KINDS = {"class", "interface", "object", "function", "method", "member", "enum", "typealias", "struct", "trait", "module", "constant", "func", "type"}
                  defs = {}
                  for line in open(tags, encoding="utf-8", errors="replace"):
                      if not line.startswith("{"): continue
                      t = json.loads(line)
                      n = t.get("name", "")
                      if t.get("kind") not in KINDS or len(n) < 3 or n.startswith("<") or n.startswith("__anon"): continue
                      p = t["path"][2:] if t["path"].startswith("./") else t["path"]
                      defs.setdefault(n, []).append([p, t.get("line", 0), t.get("kind", ""), t.get("scope", "")])
                  names = set(defs)
                  SRC = re.compile(r"\.(kt|kts|java|js|jsx|ts|tsx|mjs|py|gd|cs|go|rs|swift|dart|c|cc|cpp|h|hpp|rb|php|lua|vue|svelte)$")
                  refs, total = {}, 0
                  for root, dirs, files in os.walk("."):
                      dirs[:] = [d for d in dirs if d not in (".git", "node_modules", "build", "dist", ".gradle")]
                      for f in files:
                          if not SRC.search(f): continue
                          p = os.path.join(root, f)[2:]
                          try: lines = open(p, encoding="utf-8", errors="replace").read().split("\n")
                          except Exception: continue
                          for i, l in enumerate(lines, 1):
                              for w in set(re.findall(r"[A-Za-z_][A-Za-z0-9_]{2,}", l)):
                                  if w in names:
                                      r = refs.setdefault(w, [])
                                      if len(r) < 150 and total < 200000: r.append("%s:%d" % (p, i)); total += 1
                  json.dump({"sha": os.environ.get("GITHUB_SHA", ""), "defs": defs, "refs": refs}, open(os.path.join(out_dir, "index.json"), "w"))
                  print("tanım:", len(defs), "kullanım:", total)
                  PY
                  python3 "§RUNNER_TEMP/index.py" "§CODER_OUTPUT_DIR" "§RUNNER_TEMP/tags.json"

              - name: Çıktıları yükle
                if: success()
                uses: actions/upload-artifact@v4
                with:
                  name: coder-index-output
                  if-no-files-found: error
                  retention-days: 3
                  path: §{{ env.CODER_OUTPUT_DIR }}/index.json
    """), scriptOptional = true)

    /**
     * Structural codemod with ast-grep: a pattern/rewrite or a YAML rule applied to the whole repo; the changed files
     * come back as JSON and are committed by the app (through the usual commit checks), never by the workflow.
     */
    val CODEMOD = CloudTool("codemod", "Yapısal düzenleme (ast-grep)", "coder-codemod.yml", "coder-codemod-output", y("""
        ${marker("codemod")}
        name: Coder Codemod

        on:
          workflow_dispatch:
            inputs:
              script_path:
                description: Kullanılmıyor
                required: false
                default: "-"
              output_dir:
                description: Çıktı klasörü
                required: false
                default: coder-out
              lang:
                description: Dil (kotlin, java, typescript, tsx, javascript, python...)
                required: false
                default: ""
              pattern:
                description: Aranan kalıp
                required: false
                default: ""
              rewrite:
                description: Yerine yazılacak
                required: false
                default: ""
              rule:
                description: ast-grep YAML kuralı (kalıp yerine)
                required: false
                default: ""
              paths:
                description: Sınırlandırılacak klasör (boş = tümü)
                required: false
                default: ""

        permissions:
          contents: read

        jobs:
          codemod:
            runs-on: ubuntu-latest
            timeout-minutes: 15
            env:
              CODER_OUTPUT_DIR: §{{ inputs.output_dir }}
              LANG_ID: §{{ inputs.lang }}
              PATTERN: §{{ inputs.pattern }}
              REWRITE: §{{ inputs.rewrite }}
              RULE: §{{ inputs.rule }}
              PATHS: §{{ inputs.paths }}
            steps:
              - uses: actions/checkout@v4

              - uses: actions/setup-node@v4
                with:
                  node-version: "20"

              - name: ast-grep
                run: |
                  mkdir -p "§CODER_OUTPUT_DIR"
                  npm i -g -s @ast-grep/cli@0.51.1 > /dev/null
                  TARGET="§{PATHS:-.}"
                  test -e "§TARGET" || { echo "Klasör yok: §TARGET"; exit 1; }
                  rc=0
                  if [ -n "§RULE" ]; then
                    printf '%s\n' "§RULE" > "§RUNNER_TEMP/rule.yml"
                    ast-grep scan --rule "§RUNNER_TEMP/rule.yml" --update-all "§TARGET" > "§CODER_OUTPUT_DIR/log.txt" 2>&1 || rc=§?
                  else
                    ast-grep run --pattern "§PATTERN" --rewrite "§REWRITE" --lang "§LANG_ID" --update-all "§TARGET" > "§CODER_OUTPUT_DIR/log.txt" 2>&1 || rc=§?
                  fi
                  cat "§CODER_OUTPUT_DIR/log.txt"
                  # "ast-grep run" exits 1 when nothing matched; any other failure (bad rule, language) stops here.
                  if [ "§rc" -ne 0 ] && { [ "§rc" -ne 1 ] || [ -n "§RULE" ] || grep -qi "error" "§CODER_OUTPUT_DIR/log.txt"; }; then exit "§rc"; fi
                  git diff --stat > "§CODER_OUTPUT_DIR/summary.txt"
                  git -c core.quotepath=off diff --name-only > "§RUNNER_TEMP/changed.txt"
                  python3 - "§RUNNER_TEMP/changed.txt" "§CODER_OUTPUT_DIR/changes.json" <<'PY'
                  import json, sys
                  paths = [p for p in open(sys.argv[1], encoding="utf-8").read().splitlines() if p]
                  out, total = {}, 0
                  for p in paths:
                      t = open(p, encoding="utf-8").read()
                      total += len(t)
                      out[p] = t
                  if total > 2_000_000 or len(paths) > 300:
                      out = {"__too_large__": "%d dosya, %d karakter" % (len(paths), total)}
                  json.dump(out, open(sys.argv[2], "w"))
                  print(len(paths), "dosya değişti")
                  PY

              - name: Çıktıları yükle
                if: success()
                uses: actions/upload-artifact@v4
                with:
                  name: coder-codemod-output
                  if-no-files-found: error
                  retention-days: 1
                  path: §{{ env.CODER_OUTPUT_DIR }}/
    """), scriptOptional = true)

    /**
     * Mutation testing: small deliberate bugs (mutants, given as JSON on the branch) are applied one at a time and the
     * selected tests run against each; a mutant the tests do not notice "survives" — a gap in the tests.
     */
    val MUTATE = CloudTool("mutate", "Mutasyon testi", "coder-mutate.yml", "coder-mutate-output", y("""
        ${marker("mutate")}
        name: Coder Mutate

        on:
          workflow_dispatch:
            inputs:
              script_path:
                description: Mutant listesi (JSON)
                required: true
              output_dir:
                description: Çıktı klasörü
                required: false
                default: coder-out
              test_module:
                description: Gradle modülü
                required: false
                default: ""
              test_classes:
                description: Test sınıfları (boşlukla)
                required: false
                default: ""
              test_files:
                description: Test dosyaları (JS/Python, boşlukla)
                required: false
                default: ""

        permissions:
          contents: read

        jobs:
          mutate:
            runs-on: ubuntu-latest
            timeout-minutes: 40
            env:
              SCRIPT: §{{ inputs.script_path }}
              CODER_OUTPUT_DIR: §{{ inputs.output_dir }}
              TEST_MODULE: §{{ inputs.test_module }}
              TEST_CLASSES: §{{ inputs.test_classes }}
              TEST_FILES: §{{ inputs.test_files }}
            steps:
              - uses: actions/checkout@v4

              - name: Proje türü
                id: kind
                run: |
                  if [ -f gradlew ]; then k=gradle; elif [ -f package.json ]; then k=node; else k=python; fi
                  echo "kind=§k" >> "§GITHUB_OUTPUT"

              - uses: actions/setup-java@v4
                if: steps.kind.outputs.kind == 'gradle'
                with:
                  distribution: temurin
                  java-version: "17"

              - uses: gradle/actions/setup-gradle@v4
                if: steps.kind.outputs.kind == 'gradle'

              - uses: actions/setup-node@v4
                if: steps.kind.outputs.kind == 'node'
                with:
                  node-version: "20"

              - uses: actions/setup-python@v5
                if: steps.kind.outputs.kind == 'python'
                with:
                  python-version: "3.12"

              - name: Mutantlar
                env:
                  KIND: §{{ steps.kind.outputs.kind }}
                run: |
                  mkdir -p "§CODER_OUTPUT_DIR"
                  set -f
                  ARGS=""; for c in §TEST_CLASSES; do c=§(printf '%s' "§c" | tr -cd 'A-Za-z0-9_.*'); if [ -n "§c" ]; then ARGS="§ARGS --tests §c"; fi; done
                  FILES=""; for f in §TEST_FILES; do f=§(printf '%s' "§f" | tr -cd 'A-Za-z0-9_./@-'); if [ -n "§f" ]; then FILES="§FILES §f"; fi; done
                  MOD=§(printf '%s' "§TEST_MODULE" | tr -cd 'A-Za-z0-9_:-')
                  set +f
                  case "§KIND" in
                    gradle)
                      chmod +x gradlew
                      P=""; if [ -n "§MOD" ]; then P=":§MOD:"; fi
                      T="§{P}testDebugUnitTest"
                      ./gradlew tasks --all -q 2>/dev/null | grep -q "testDebugUnitTest" || T="§{P}test"
                      export TEST_CMD="./gradlew §T §ARGS --console=plain -q" ;;
                    node)
                      if [ -f package-lock.json ]; then npm ci --no-audit --no-fund; else npm install --no-audit --no-fund; fi
                      if grep -q '"vitest"' package.json; then export TEST_CMD="npx --no-install vitest run §FILES"; else export TEST_CMD="npx --no-install jest §FILES"; fi ;;
                    python)
                      pip install -q pytest; for r in requirements*.txt; do if [ -f "§r" ]; then pip install -q -r "§r"; fi; done
                      export TEST_CMD="python -m pytest -q -x §FILES" ;;
                  esac
                  cat > "§RUNNER_TEMP/mutate.py" <<'PY'
                  import json, os, re, subprocess, sys, time
                  muts = json.load(open(sys.argv[1])); out = sys.argv[2]; cmd = os.environ["TEST_CMD"]
                  # Every write gets a new, later mtime and Python keeps no bytecode: a cached .pyc of the previous
                  # mutant (same size, same second) must never be what the tests run.
                  os.environ["PYTHONDONTWRITEBYTECODE"] = "1"
                  stamp = [int(time.time())]
                  def write(path, text):
                      open(path, "w", encoding="utf-8").write(text)
                      stamp[0] += 2; os.utime(path, (stamp[0], stamp[0]))
                  # A mutant that does not even compile says nothing about the tests: it is not counted as caught.
                  BROKEN = re.compile(r"(?m)^e: |Compilation error|Compilation failed|SyntaxError|IndentationError|TS[0-9]{4}:")
                  def run():
                      try:
                          p = subprocess.run(cmd, shell=True, timeout=600, capture_output=True, text=True, errors="replace")
                          return p.returncode, p.stdout + p.stderr
                      except subprocess.TimeoutExpired: return 124, "zaman aşımı"
                  rc, log = run()
                  if rc != 0:
                      print(log[-4000:])
                      json.dump({"error": "Testler değişiklik olmadan da başarısız; mutasyon ölçülemez.\n" + log[-1500:]}, open(out, "w")); sys.exit(0)
                  res = []
                  for m in muts[:8]:
                      p = m["path"]; text = open(p, encoding="utf-8").read()
                      if text.count(m["search"]) != 1:
                          res.append({"id": m["id"], "status": "skipped"}); continue
                      write(p, text.replace(m["search"], m["replace"], 1))
                      rc, log = run()
                      write(p, text)
                      res.append({"id": m["id"], "status": "survived" if rc == 0 else ("invalid" if BROKEN.search(log) else "killed")})
                  json.dump({"results": res}, open(out, "w"))
                  print(res)
                  PY
                  python3 "§RUNNER_TEMP/mutate.py" "§SCRIPT" "§CODER_OUTPUT_DIR/results.json"

              - name: Çıktıları yükle
                if: always()
                uses: actions/upload-artifact@v4
                with:
                  name: coder-mutate-output
                  if-no-files-found: warn
                  retention-days: 1
                  path: §{{ env.CODER_OUTPUT_DIR }}/
    """))

    val ALL = listOf(BLENDER, TOOLBOX, EVALUATE, VIDEO, DOCS, QUICK, ANDROID_SHOTS, INDEX, CODEMOD, MUTATE)

    /** Inputs a template accepts besides script_path/output_dir: anything else would make the dispatch fail. */
    fun extraInputNames(t: CloudTool): Set<String> = when (t) {
        BLENDER -> setOf("blender_version")
        TOOLBOX -> setOf("requirements", "preset")
        EVALUATE -> setOf("app_dir", "start_command")
        VIDEO -> setOf("app_dir", "composition")
        ANDROID_SHOTS -> setOf("api_level", "flows")
        QUICK -> setOf("test_module", "test_classes", "test_files")
        CODEMOD -> setOf("lang", "pattern", "rewrite", "rule", "paths")
        MUTATE -> setOf("test_module", "test_classes", "test_files")
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
            "quick", "quick_check", "check" -> QUICK
            "android", "emulator", "screens", "android_shots", "screenshot" -> ANDROID_SHOTS
            "index", "code_index" -> INDEX
            "codemod", "ast-grep", "ast_grep" -> CODEMOD
            "mutate", "mutation" -> MUTATE
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
