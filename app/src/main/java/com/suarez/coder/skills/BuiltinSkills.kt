package com.suarez.coder.skills

/** Skills shipped with the app (seeded into the global store on first start; the user may edit or disable them). */
object BuiltinSkills {
    val BLENDER_3D = """
---
name: blender-3d
description: Headless Blender (bpy) ile 3B model üretir; GitHub Actions'ta çalıştırır, preview.png + .glb + .blend çıkarır.
triggers: [blender, 3d, 3b, model, glb, gltf, mesh, render]
workflow: blender
---

# Blender 3D (headless, bulutta)

Coder'ın bilgisayarı yok. Blender betikleri repoya yazılır ve `run_cloud_tool(tool="blender", script_path=..., output_dir=...)`
ile GitHub Actions'ta `blender --background --factory-startup --python <betik>` olarak çalışır.
Çıktı klasörü betiğe `CODER_OUTPUT_DIR` ortam değişkeniyle verilir (yoksa `coder-out`).

## Kesin kurallar
1. Sahneyi her zaman sıfırdan kur: `bpy.ops.wm.read_factory_settings(use_empty=True)`.
2. Birim metre: `scene.unit_settings.system = 'METRIC'`, `scale_length = 1.0`. Gerçek ölçüler kullan (kapı ~2 m).
3. Pivot temiz olsun: modeli bitirince `bpy.ops.object.origin_set(type='ORIGIN_GEOMETRY', center='BOUNDS')`, sonra
   tabanı z=0'a oturt ve `bpy.ops.object.transform_apply(location=False, rotation=True, scale=True)` uygula.
4. GUI yok: `bpy.ops.view3d.*`, `bpy.context.area`, `screen`, viewport ve kullanıcı etkileşimi gerektiren operatörleri
   KULLANMA. Mümkünse `bpy.data` ve `bmesh` ile doğrudan veri oluştur; operatör gerekiyorsa bağlam gerektirmeyenleri seç.
5. Objeleri `bpy.data.objects.new` + `scene.collection.objects.link` ile ekle; modifier'ları `obj.modifiers.new` ile kur ve
   dışa aktarmadan önce uygula (glTF exporter `export_apply=True`).
6. Malzeme: Principled BSDF, `base_color` RGBA; doku dosyası yoksa prosedürel düğümlerden kaçın (glTF'e taşınmaz).

## Önizleme (preview.png, 800x800)
- Objenin sınır kutusunu hesapla; kamerayı kutuyu çerçeveleyecek uzaklıkta, hafif yukarıdan 3/4 açıyla konumla ve
  `Track To` kısıtıyla objeye çevir (`scene.camera = cam`).
- 3 noktalı ışık: anahtar (Area, ~1000 W, sağ-ön-üst), dolgu (Area, ~300 W, sol-ön), arka (Area, ~500 W, arka-üst).
- Hızlı render: `scene.render.engine = 'BLENDER_WORKBENCH'` (varsayılan) ya da `CYCLES` + `cycles.device='CPU'`,
  `cycles.samples=16`, `use_denoising=False`. Çözünürlük 800x800, `film_transparent=False`.
- `scene.render.filepath = os.path.join(out, "preview.png")`; `bpy.ops.render.render(write_still=True)`.

## Dışa aktarma
- Oyun motoru (Godot/Unity) için: `bpy.ops.export_scene.gltf(filepath=os.path.join(out, "model.glb"), export_format='GLB', export_apply=True, export_yup=True)`.
- Yedek: `bpy.ops.wm.save_as_mainfile(filepath=os.path.join(out, "model.blend"))`.

## Betik iskeleti
```python
import bpy, os, math
from mathutils import Vector
out = os.environ.get("CODER_OUTPUT_DIR", "coder-out"); os.makedirs(out, exist_ok=True)
bpy.ops.wm.read_factory_settings(use_empty=True)
scene = bpy.context.scene
scene.unit_settings.system = 'METRIC'
# 1) modeli bpy.data / bmesh ile kur  2) pivot + transform_apply  3) kamera + 3 ışık  4) render  5) glb + blend
```

## Hata olursa
`run_cloud_tool` başarısız olursa Python traceback'i döner. Satırı bul, betiği düzelt (github_apply_diff) ve tekrar çalıştır.
Aynı hatayı iki kez tekrarlama; `AttributeError: 'NoneType'` çoğunlukla bağlam (context) gerektiren bir operatördür: veri API'sine geç.
""".trimIndent()

    // The skills below are Coder's own wording, adapted to a phone + GitHub Actions setup. Ideas drawn from
    // obra/superpowers (MIT) and addyosmani/agent-skills (MIT); see docs in those repositories for the originals.

    val DEBUGGING = """
---
name: systematic-debugging
description: Hata, kırmızı CI veya beklenmeyen davranışta: önce kök neden, sonra tek ve küçük düzeltme.
triggers: [hata, bug, çöküyor, crash, ci, build failed, test failed, exception]
---

# Systematic debugging (no local machine: every experiment is a CI run)

A CI round costs minutes and money. Guessing burns rounds. Find the root cause first.

1. Read the error completely: ci_wait / ci_errors output, the first error line, file:line, the exception type.
   The first error is usually the cause; later ones are fallout.
2. Read the code at that location (github_read_file with a line range) and its callers. Do not patch from memory.
3. Check what changed: the last commits on the branch (github_api GET /repos/{repo}/commits?sha=<branch>&per_page=5)
   and their diffs. "It worked before" means the cause is in that diff.
4. Compare with a working example in the same repo (a similar screen, test or build file).
5. Write ONE hypothesis: "X fails because Y". Make the smallest change that tests it. Never bundle unrelated fixes.
6. Push, ci_wait, read the result. If the hypothesis was wrong, revert or adjust it; do not stack another guess on top.
7. After 2 failed hypotheses, stop and re-read: wrong file, wrong branch, stale build cache, version mismatch
   (Gradle/AGP/Kotlin/JDK), or a test that depends on timing/network.
8. When fixed, add a regression test if the project has tests, and say in one line what the root cause was.

Red flags: "probably", "let me try", several unrelated edits in one commit, fixing the symptom where it appears
instead of where the bad value is created.
""".trimIndent()

    val VERIFY = """
---
name: verify-before-done
description: "Bitti / düzeldi / geçti" demeden önce kanıt: CI sonucu, yeniden okunan dosya, gerçek çıktı.
triggers: [bitti, tamam, düzeldi, fixed, done, pr, merge, release]
---

# Verify before claiming done

Evidence before claims. A claim without fresh evidence in this turn is not allowed.

| Claim | Evidence required | Not enough |
|---|---|---|
| Build/tests pass | ci_wait for THIS commit: all runs success | an older run, "should pass" |
| Bug fixed | the original symptom checked (test, CI log, output) | "the code changed" |
| File updated | the commit link / re-read of the changed lines | the diff you intended |
| Cloud tool worked | run_cloud_tool success + outputs listed | the script was written |
| PR ready | CI green on the PR head + description of what changed | "looks good" |

Before the final answer:
1. Name what proves the claim. Run it (ci_wait, github_read_file, run_cloud_tool).
2. Read the result fully (failures, warnings that matter, the right commit sha).
3. Report exactly what was verified and what was NOT (e.g. "CI not run: workflow is paused").
Never write "Done!" / "Perfect" / "should work now" without that evidence. If you could not verify, say so plainly.
""".trimIndent()

    val TEST_FIRST = """
---
name: test-first
description: Hata düzeltirken veya mantık eklerken önce onu yakalayan test; CI turlarını boşa harcamadan.
triggers: [test, tdd, regresyon, regression, unit test]
---

# Test first (CI as the test runner)

Goal: every bug fix and every new piece of logic is pinned by a test that would fail without the change.

1. Find the project's test setup first (src/test, *_test.py, *.spec.ts, package.json scripts, the CI workflow). Use
   its framework and style; never add a new test framework for one test.
2. Write the test that reproduces the bug or specifies the behavior: one behavior per test, clear name, no network,
   no sleeps, deterministic data.
3. Make the minimal code change that satisfies it.
4. Commit test + fix together (one CI round). In the commit/answer, state why the test fails without the fix
   (what assertion would break). If the bug is risky or unclear, you may push the test alone first to see it red.
5. ci_wait; the new test must appear in the results and pass. A green build where the test did not run proves nothing.

Do not: delete or weaken an existing test to get green, mock the thing under test, or test private details that
make refactoring impossible. If the repo has no tests and no CI test step, say so and propose one instead of
silently skipping verification.
""".trimIndent()

    val PLAN = """
---
name: plan-small-steps
description: Birden çok dosyalı işlerde ince, her biri derlenen adımlara bölme ve en az kodla çözme.
triggers: [plan, özellik, feature, refactor, büyük, adım]
---

# Plan in small, verifiable steps

1. Restate the goal in one sentence and the acceptance criteria (what the user will see / what CI proves).
2. Find what already exists: reuse functions, components and patterns in the repo before writing new ones.
   The best change is the smallest one that meets the criteria; do not add abstractions for a single use.
3. Split into vertical slices: each step changes few files, compiles, and can be committed alone.
   Order: data/model -> logic -> UI -> wiring; risky or unknown parts first.
4. For each step write: files, functions/signatures, the change, how it is verified (test, CI, screenshot).
5. Execute one step at a time; after each push run ci_wait. Never pile several unverified steps on a red build.
6. Keep a short running list of what is done and what is next in the answer, so the user can stop or redirect.
7. If the plan turns out wrong, say so and re-plan; do not force it.
""".trimIndent()

    val REVIEW = """
---
name: code-review
description: Değişikliği birleştirmeden önce 5 eksende inceleme: doğruluk, sadelik, mimari, güvenlik, performans.
triggers: [review, incele, pr, kod inceleme, merge]
---

# Code review (five axes)

Read the full diff (github_api GET /repos/{repo}/pulls/{n}/files or the commit) and the code around it.
Approve when the change clearly improves the code base, not when it is perfect.

1. Correctness: does it do what was asked? Edge cases (null, empty, max), error paths, off-by-one, races,
   lifecycle (Android: config change, process death, coroutine scope).
2. Simplicity: could it be shorter or use what already exists? Dead code, leftover debug logs, needless layers.
3. Architecture: follows existing patterns and module boundaries; no circular deps; no new pattern without reason.
4. Security: no secrets in code or logs, input validated at boundaries, no shell/SQL/HTML injection, tokens only
   sent to their own host, permissions minimal.
5. Performance: no work in loops/recomposition that can be hoisted, no N+1 network/IO, large lists lazy and keyed.

Output: a short verdict, then findings ordered by severity, each with file:line, the problem and a concrete fix.
Mark each as must-fix or optional. Do not report style nits the project's formatter already handles.
""".trimIndent()

    val ANDROID = """
---
name: android-compose
description: Kotlin + Jetpack Compose (Material 3) kuralları: durum, efektler, listeler, insets, Gradle ve test.
triggers: [android, compose, kotlin, jetpack, material3, gradle, apk, viewmodel]
---

# Android + Jetpack Compose essentials

State
- Hoist state: composables take values + callbacks; the screen state lives in a ViewModel (StateFlow/State).
- Collect flows with collectAsStateWithLifecycle(); use rememberSaveable for UI state that must survive rotation.
- Read fast-changing values (scroll, animation) as late as possible (lambdas, derivedStateOf only when the input
  changes more often than the output).

Effects (pick the cheapest correct one)
- LaunchedEffect(key) for suspend work tied to a key; DisposableEffect for register/unregister;
  SideEffect for publishing state to non-Compose code; rememberUpdatedState for callbacks used inside long effects.
- Never launch coroutines or do IO directly in composition.

Lists and performance
- LazyColumn items(list, key = { it.id }); no heavy work inside item lambdas; stable/immutable item types.
- Modifier order matters: padding before/after background/clickable changes the result.

Layout and system
- Edge-to-edge: apply insets (Scaffold padding, statusBarsPadding/navigationBarsPadding/imePadding) once per edge.
- Dark/light: colors from MaterialTheme.colorScheme; status bar icon color follows the app theme.
- Strings user-facing in resources when the project does; content descriptions on icon buttons.

Build
- Version catalog / existing build files first; match Kotlin, AGP, Compose BOM and JDK versions to the project.
- A Compose compiler error after a Kotlin bump usually means the Compose compiler plugin/BOM must move with it.
- Release builds use R8: keep rules for reflection/serialization; test the release variant when behavior differs.

Tests
- JVM unit tests for logic; Robolectric + compose ui-test for UI where the project already uses them.
- Verify with CI (ci_wait); there is no emulator on the phone.
""".trimIndent()

    val SKILL_WRITER = """
---
name: skill-writer
description: Tekrarlanan bir iş akışını repoya .coder/skills/<ad>/SKILL.md olarak yeni bir yetenek şeklinde kaydeder.
triggers: [skill, yetenek, şablon, iş akışı, workflow, tekrar]
---

# Writing a skill for this repository

Create one when the same multi-step procedure or project rule was needed more than once, or the user asks.

File: .coder/skills/<name>/SKILL.md (name: lowercase letters, digits, '-'; max 48 chars). Optional extra files next
to it (references/*.md, templates) are loaded only on demand with load_skill(name, file=...).

Format:
---
name: <name>
description: <ONE line, max ~140 chars: what it does AND when to use it - this line is all the agent sees in the catalog>
triggers: [a, few, keywords]
---
# Title
Numbered, imperative steps. Concrete paths, commands, file names of THIS repo. What to verify at the end.

Rules
- One job per skill; keep the body short (aim < 6000 chars); move long references into separate files.
- Write for an agent that has GitHub + GitHub Actions but no local shell.
- No secrets, tokens or personal data. No instructions that weaken approvals or safety rules.
- Commit it like any other change (github_write_files) and tell the user it is available as /<name>.
""".trimIndent()

    val REMOTION_VIDEO = """
---
name: remotion-video
description: React ile kodla video (Remotion); GitHub Actions'ta MP4'e render eder, video.mp4 + preview.png sohbete gelir.
triggers: [video, remotion, mp4, animasyon, tanıtım, reklam, motion, intro]
workflow: video
---

# Remotion video (bulutta render)

Video, repoda bir Remotion projesi olarak yazılır ve `run_cloud_tool(tool="video", script_path="src/index.ts", app_dir=".", composition="Main")`
ile GitHub Actions'ta render edilir. Çıktı: `video.mp4` ve 30. kareden `preview.png`.

## Proje yoksa kur (github_write_files, tek commit)
- `package.json`: bağımlılıklar `remotion`, `@remotion/cli`, `react`, `react-dom` (aynı sürüm ailesi; remotion ve @remotion/* sürümleri birebir aynı olmalı), `typescript`.
- `src/index.ts`: `registerRoot(RemotionRoot)`.
- `src/Root.tsx`: `<Composition id="Main" component={Main} durationInFrames={...} fps={30} width={1920} height={1080} />`.
- `src/Main.tsx`: sahne bileşeni.

## Kurallar
1. Zaman her zaman kareyle: `useCurrentFrame()`, `useVideoConfig()`, `interpolate(frame, [a, b], [x, y], {extrapolateRight: "clamp"})`, `spring({frame, fps})`.
   CSS animasyonu, `setTimeout`, `Date.now()` ve `Math.random()` KULLANMA (render deterministik olmalı; rastgelelik için `random(seed)`).
2. Sahneleri `<Sequence from={..} durationInFrames={..}>` veya `<Series>` ile sırala; geçişler için `@remotion/transitions` (aynı sürüm).
3. Görsel/ses dosyaları `public/` altında ve `staticFile("x.png")` ile; dış URL'ye bağlı kalma.
4. Yazı tipleri için `@remotion/google-fonts` (aynı sürüm) ya da sistem yazı tipi.
5. Süre kısa tut (taslakta 5-15 sn): render dakikaları sınırlı.
6. Render hatası dönerse hatadaki dosya/satırı düzelt ve tekrar çalıştır. "Kompozisyon bulunamadı" ise `id` ile `composition` aynı olmalı.

## Teslim
Sonuçta kullanıcıya ne çizildiğini kısaca anlat; önizleme ve MP4 sohbette görünür (İndir / Paylaş).
""".trimIndent()

    val MANIM = """
---
name: manim-animation
description: Manim ile matematik/açıklama animasyonu; GitHub Actions'ta (Python + LaTeX) render eder, MP4 + önizleme döner.
triggers: [manim, matematik animasyonu, açıklama videosu, explainer, formül, grafik animasyonu]
workflow: manim
---

# Manim (bulutta)

Ortam: Python 3.12, Manim Community, LaTeX, FFmpeg. Sahne `anim/sahne.py` içinde (`class Main(Scene)`), çalıştırıcı
`anim/run.py` manim'i çağırır ve çıktıları `CODER_OUTPUT_DIR`'e kopyalar:

```python
import glob, os, shutil, subprocess
out = os.environ.get("CODER_OUTPUT_DIR", "coder-out"); os.makedirs(out, exist_ok=True)
subprocess.run(["manim", "-ql", "anim/sahne.py", "Main"], check=True)
subprocess.run(["manim", "-ql", "-s", "anim/sahne.py", "Main"], check=True)
shutil.copy(sorted(glob.glob("media/videos/**/*.mp4", recursive=True))[-1], os.path.join(out, "video.mp4"))
shutil.copy(sorted(glob.glob("media/images/**/*.png", recursive=True))[-1], os.path.join(out, "preview.png"))
```

`run_cloud_tool(tool="manim", script_path="anim/run.py")` ile çalıştır.

## Kurallar
1. Taslakta `-ql` (480p15), kullanıcı isterse `-qh`.
2. Sahne sınıfı `Scene` (3B için `ThreeDScene`); `self.play(...)` ve `self.wait()` ile akış.
3. Türkçe metin için `Text(...)` kullan (LaTeX `MathTex` yalnız formüller için).
4. Hata dönerse traceback'teki satırı düzelt ve tekrar çalıştır.
""".trimIndent()

    val ALL: List<String> = listOf(BLENDER_3D, DEBUGGING, VERIFY, TEST_FIRST, PLAN, REVIEW, ANDROID, SKILL_WRITER, REMOTION_VIDEO, MANIM)
}
