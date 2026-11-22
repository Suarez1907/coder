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
    const val VERSION = 1
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

    val ALL = listOf(BLENDER, TOOLBOX)

    /** Maps the run_cloud_tool `tool` argument (also "coder-blender.yml", "Blender", "python", "ffmpeg") to a template. */
    fun forTool(tool: String): CloudTool? {
        val t = tool.trim().lowercase().removePrefix("coder-").removeSuffix(".yml")
        return when (t) {
            "blender", "bpy", "3d" -> BLENDER
            "toolbox", "python", "ffmpeg", "py" -> TOOLBOX
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
