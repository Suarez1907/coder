package com.suarez.coder

import com.suarez.coder.repomap.RepoMapBuilder
import com.suarez.coder.repomap.RepoSkeletonExtractor
import com.suarez.coder.repomap.TarStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class RepoMapTest {
    @Test fun kotlinOutlineDropsBodies() {
        val src = """
            package a
            import x.Y

            /** doc { not a brace } */
            data class User(val id: Int, val name: String)

            class Repo(private val api: Api) : Base() {
                val cache = HashMap<String, User>()
                private val secret = "x{"
                companion object {
                    const val MAX = 10
                }
                suspend fun load(
                    id: Int,
                    force: Boolean = false,
                ): User {
                    val local = 1
                    fun inner() {}
                    return api.get(id)
                }
                fun size() = cache.size
            }

            fun topLevel(a: Int): Int {
                if (a > 0) { return a }
                return 0
            }
        """.trimIndent()
        val out = RepoSkeletonExtractor.extract("src/Repo.kt", src)
        assertEquals(
            listOf(
                "data class User(val id: Int, val name: String)",
                "class Repo(private val api: Api) : Base()",
                "  val cache",
                "  companion object",
                "    const val MAX",
                "  suspend fun load( id: Int, force: Boolean = false, ): User",
                "  fun size()",
                "fun topLevel(a: Int): Int",
            ),
            out,
        )
    }

    @Test fun javaOutline() {
        val src = """
            public class Main extends Activity {
                public static final String TAG = "Main";
                private int count = 0;
                @Override
                protected void onCreate(Bundle b) {
                    super.onCreate(b);
                    if (b != null) { count++; }
                }
                public int getCount() { return count; }
            }
        """.trimIndent()
        assertEquals(
            listOf("public class Main extends Activity", "  public static final String TAG", "  protected void onCreate(Bundle b)", "  public int getCount()"),
            RepoSkeletonExtractor.extract("Main.java", src),
        )
    }

    @Test fun tsOutline() {
        val src = """
            export interface Props { title: string }
            export const API_URL = "https://x";
            export default function App({ title }: Props) {
              const [a, setA] = useState(0);
              return <div>{title}</div>;
            }
            export const add = (a: number, b: number): number => a + b;
            class Store {
              items: string[] = [];
              async load(id: string): Promise<void> {
                if (id) { await fetch(id); }
              }
            }
        """.trimIndent()
        val out = RepoSkeletonExtractor.extract("src/App.tsx", src)
        assertTrue(out.toString(), out.contains("export interface Props"))
        assertTrue(out.toString(), out.contains("export const API_URL"))
        assertTrue(out.toString(), out.contains("export default function App({ title }: Props)"))
        assertTrue(out.toString(), out.contains("export const add = (a: number, b: number): number =>"))
        assertTrue(out.toString(), out.contains("class Store"))
        assertTrue(out.toString(), out.contains("  async load(id: string): Promise<void>"))
        assertFalse(out.toString(), out.any { it.contains("useState") || it.contains("fetch") })
    }

    @Test fun pythonOutline() {
        val src = "MAX_SIZE = 10\n\nclass A:\n    def run(self, x,\n            y=2):\n        def helper():\n            pass\n        return x\n\nasync def main():\n    pass\n"
        assertEquals(listOf("MAX_SIZE", "class A", "    def run(self, x, y=2)", "async def main()"), RepoSkeletonExtractor.extract("a.py", src))
    }

    @Test fun xmlAndJson() {
        val manifest = """<manifest package="com.x"><uses-permission android:name="android.permission.INTERNET"/>
            <application><activity android:name=".MainActivity"/></application></manifest>"""
        assertEquals(listOf("package com.x", "permission INTERNET", "activity .MainActivity"), RepoSkeletonExtractor.extract("app/AndroidManifest.xml", manifest))
        val layout = """<LinearLayout><Button android:id="@+id/ok" /><TextView android:id="@+id/title"/></LinearLayout>"""
        assertEquals(listOf("<LinearLayout>", "ids: Button#ok, TextView#title"), RepoSkeletonExtractor.extract("res/layout/a.xml", layout))
        val pkg = """{"name":"web","scripts":{"dev":"vite","build":"vite build"},"dependencies":{"react":"18"}}"""
        assertEquals(listOf("name: web", "scripts: dev, build", "dependencies: react"), RepoSkeletonExtractor.extract("package.json", pkg))
    }

    @Test fun mapIsSmallerThanSourceAndDeterministic() {
        val files = mapOf("b/B.kt" to listOf("class B", "  fun x()"), "a/A.kt" to listOf("class A"), "README.md" to emptyList())
        val m1 = RepoMapBuilder.build(files)
        assertEquals(m1, RepoMapBuilder.build(files.toList().reversed().toMap()))
        assertEquals("README.md\na/A.kt\n  class A\nb/B.kt\n  class B\n    fun x()\n", m1)
        // Over budget: outlines drop, paths stay.
        val small = RepoMapBuilder.build(files, maxChars = 40)
        assertTrue(small, small.contains("b/B.kt") && !small.contains("fun x()"))
    }

    // ---- tar reader ----

    private fun header(name: String, size: Int, type: Char): ByteArray {
        val h = ByteArray(512)
        name.toByteArray().copyInto(h, 0)
        String.format("%011o", size).toByteArray().copyInto(h, 124)
        h[156] = type.code.toByte()
        "ustar".toByteArray().copyInto(h, 257)
        return h
    }

    private fun ByteArrayOutputStream.entry(name: String, data: ByteArray, type: Char = '0') {
        write(header(name, data.size, type)); write(data); write(ByteArray((512 - data.size % 512) % 512))
    }

    @Test fun readsTarWithPaxAndGlobalHeaders() {
        val out = ByteArrayOutputStream()
        out.entry("pax_global_header", "52 comment=abc\n".toByteArray(), 'g')
        out.entry("repo-sha/a.txt", "hello".toByteArray())
        val long = "repo-sha/" + "d/".repeat(60) + "long.kt"
        val rec = "path=$long\n"
        val len = (rec.length + 4).let { if ((it.toString().length + 1 + rec.length) != it) it + 1 else it }
        out.entry("PaxHeader", "${len} $rec".toByteArray(), 'x')
        out.entry("repo-sha/short", "class X".toByteArray())
        out.entry("repo-sha/skipped.bin", ByteArray(1000) { 1 })
        out.write(ByteArray(1024))
        val seen = LinkedHashMap<String, String>()
        TarStream.forEachFile(ByteArrayInputStream(out.toByteArray())) { e ->
            if (!e.path.endsWith(".bin")) seen[e.path] = String(e.read())
        }
        assertEquals(listOf("a.txt", "d/".repeat(60) + "long.kt"), seen.keys.toList())
        assertEquals("class X", seen.values.last())
    }
}
