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

    val ALL: List<String> = listOf(BLENDER_3D)
}
