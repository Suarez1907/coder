# Bulut araçları öz-testi

Coder'ın kullanıcı repolarına kurduğu bulut iş akışlarını bu reponun kendisinde denemek için girdiler.
Hepsi elle tetiklenir (Actions > ilgili iş akışı > Run workflow); hiçbiri repoya yazmaz.

- `coder-mutate.yml`: `script_path` = `.coder/selftest/mutants.json`, `test_module` = `app`, `test_classes` = `com.suarez.coder.CodeIntelTest`
- `coder-android-shots.yml`: `flows` = `.coder/selftest/flows`
- `coder-codemod.yml`: örnek `lang` = `kotlin`, `pattern` = `String.format(java.util.Locale.US, $$$A)`, `rewrite` = `java.lang.String.format(java.util.Locale.US, $$$A)`
- `coder-perf.yml`: girdisiz; soğuk açılışı profilsiz ve baseline profile ile ölçer.
