package eval.runner

import eval.model.TestFailure
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GradleExecutorTest {

    @Test
    fun `parse standard compiler error format`() {
        val output = """
> Task :backend:compileKotlin FAILED
e: file:///Users/dev/project/src/main/kotlin/Routes.kt:42:15 Unresolved reference: respondText
e: file:///Users/dev/project/src/main/kotlin/Routes.kt:55:10 Type mismatch: inferred type is String but Int was expected

BUILD FAILED in 5s
        """.trimIndent()

        val errors = GradleExecutor.parseCompilerErrors(output)
        assertEquals(2, errors.size)

        assertEquals("/Users/dev/project/src/main/kotlin/Routes.kt", errors[0].file)
        assertEquals(42, errors[0].line)
        assertEquals(15, errors[0].column)
        assertEquals("Unresolved reference: respondText", errors[0].message)

        assertEquals(55, errors[1].line)
        assertEquals(10, errors[1].column)
        assertTrue(errors[1].message.contains("Type mismatch"))
    }

    @Test
    fun `parse compiler errors without file prefix`() {
        val output = """
e: /src/main/kotlin/App.kt:10:5 Expecting member declaration
        """.trimIndent()

        val errors = GradleExecutor.parseCompilerErrors(output)
        assertEquals(1, errors.size)
        assertEquals("/src/main/kotlin/App.kt", errors[0].file)
        assertEquals(10, errors[0].line)
    }

    @Test
    fun `no compiler errors in clean output`() {
        val output = """
> Task :backend:compileKotlin
> Task :backend:classes

BUILD SUCCESSFUL in 3s
        """.trimIndent()

        val errors = GradleExecutor.parseCompilerErrors(output)
        assertTrue(errors.isEmpty())
    }

    @Test
    fun `parseCompilerErrorMessages returns formatted strings`() {
        val output = "e: file:///path/File.kt:10:5 Some error"
        val messages = GradleExecutor.parseCompilerErrorMessages(output)
        assertEquals(1, messages.size)
        assertEquals("/path/File.kt:10:5 Some error", messages[0])
    }

    @Test
    fun `parseTestResultsFromXml with valid JUnit XML`() {
        val tempDir = createTempDir("test-results")
        try {
            val xmlContent = """
<?xml version="1.0" encoding="UTF-8"?>
<testsuite name="com.example.AppTest" tests="3" failures="1" skipped="0" time="0.5">
  <testcase name="testSuccess" classname="com.example.AppTest" time="0.1"/>
  <testcase name="testAlsoSuccess" classname="com.example.AppTest" time="0.2"/>
  <testcase name="testFailure" classname="com.example.AppTest" time="0.2">
    <failure message="expected 1 but was 2" type="AssertionError">
java.lang.AssertionError: expected 1 but was 2
    at com.example.AppTest.testFailure(AppTest.kt:15)
    </failure>
  </testcase>
</testsuite>
            """.trimIndent()

            File(tempDir, "TEST-com.example.AppTest.xml").writeText(xmlContent)

            val results = GradleExecutor.parseTestResultsFromXml(tempDir)
            assertNotNull(results)
            assertEquals(3, results.totalTests)
            assertEquals(2, results.passed)
            assertEquals(1, results.failed)
            assertEquals(0, results.skipped)
            assertEquals(1, results.failures.size)
            assertEquals("testFailure", results.failures[0].testName)
            assertEquals("com.example.AppTest", results.failures[0].className)
            assertEquals("expected 1 but was 2", results.failures[0].message)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `parseTestResultsFromXml with multiple test suite files`() {
        val tempDir = createTempDir("test-results")
        try {
            val xml1 = """
<?xml version="1.0" encoding="UTF-8"?>
<testsuite name="Suite1" tests="2" failures="0" skipped="0">
  <testcase name="t1" classname="Suite1"/>
  <testcase name="t2" classname="Suite1"/>
</testsuite>
            """.trimIndent()

            val xml2 = """
<?xml version="1.0" encoding="UTF-8"?>
<testsuite name="Suite2" tests="3" failures="1" skipped="1">
  <testcase name="t3" classname="Suite2"/>
  <testcase name="t4" classname="Suite2">
    <failure message="fail">stack</failure>
  </testcase>
  <testcase name="t5" classname="Suite2">
    <skipped/>
  </testcase>
</testsuite>
            """.trimIndent()

            File(tempDir, "TEST-Suite1.xml").writeText(xml1)
            File(tempDir, "TEST-Suite2.xml").writeText(xml2)

            val results = GradleExecutor.parseTestResultsFromXml(tempDir)
            assertNotNull(results)
            assertEquals(5, results.totalTests)
            assertEquals(3, results.passed)
            assertEquals(1, results.failed)
            assertEquals(1, results.skipped)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `parseTestResultsFromXml returns null for nonexistent directory`() {
        val result = GradleExecutor.parseTestResultsFromXml(File("/nonexistent/path"))
        assertNull(result)
    }

    @Test
    fun `parseTestResultsFromXml returns null for empty directory`() {
        val tempDir = createTempDir("empty-results")
        try {
            val result = GradleExecutor.parseTestResultsFromXml(tempDir)
            assertNull(result)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    private fun createTempDir(prefix: String): File {
        val dir = File(System.getProperty("java.io.tmpdir"), "$prefix-${System.currentTimeMillis()}")
        dir.mkdirs()
        return dir
    }
}
