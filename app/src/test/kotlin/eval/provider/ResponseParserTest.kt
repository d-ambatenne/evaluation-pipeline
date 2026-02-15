package eval.provider

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ResponseParserTest {

    @Test
    fun `parse standard FILE marker with kotlin code fence`() {
        val response = """
FILE: src/test/kotlin/AppTest.kt
```kotlin
package com.example

import kotlin.test.Test
import kotlin.test.assertEquals

class AppTest {
    @Test
    fun testHello() {
        assertEquals("hello", "hello")
    }
}
```
        """.trimIndent()

        val result = ResponseParser.parse(response)
        assertEquals(1, result.files.size)
        assertTrue(result.files.containsKey("src/test/kotlin/AppTest.kt"))
        assertTrue(result.files["src/test/kotlin/AppTest.kt"]!!.contains("class AppTest"))
    }

    @Test
    fun `parse multiple files`() {
        val response = """
FILE: src/main/kotlin/Routes.kt
```kotlin
fun routes() { }
```

FILE: src/test/kotlin/RoutesTest.kt
```kotlin
fun testRoutes() { }
```
        """.trimIndent()

        val result = ResponseParser.parse(response)
        assertEquals(2, result.files.size)
        assertTrue(result.files.containsKey("src/main/kotlin/Routes.kt"))
        assertTrue(result.files.containsKey("src/test/kotlin/RoutesTest.kt"))
    }

    @Test
    fun `parse code fence without language tag`() {
        val response = """
FILE: src/main/kotlin/App.kt
```
fun main() { println("hello") }
```
        """.trimIndent()

        val result = ResponseParser.parse(response)
        assertEquals(1, result.files.size)
        assertTrue(result.files["src/main/kotlin/App.kt"]!!.contains("fun main()"))
    }

    @Test
    fun `parse FILE marker with heading prefix`() {
        val response = """
### FILE: src/main/kotlin/App.kt
```kotlin
fun main() {}
```
        """.trimIndent()

        val result = ResponseParser.parse(response)
        assertEquals(1, result.files.size)
        assertTrue(result.files.containsKey("src/main/kotlin/App.kt"))
    }

    @Test
    fun `ignore explanation text between files`() {
        val response = """
Here is the implementation:

FILE: src/main/kotlin/App.kt
```kotlin
fun main() {}
```

This test verifies the app works:

FILE: src/test/kotlin/AppTest.kt
```kotlin
fun test() {}
```

That should do it!
        """.trimIndent()

        val result = ResponseParser.parse(response)
        assertEquals(2, result.files.size)
    }

    @Test
    fun `unwrap outer code block wrapping FILE markers`() {
        val response = """
```
FILE: src/main/kotlin/App.kt
```kotlin
fun main() {}
```

FILE: src/test/kotlin/AppTest.kt
```kotlin
fun test() {}
```
```
        """.trimIndent()

        val result = ResponseParser.parse(response)
        assertEquals(2, result.files.size)
    }

    @Test
    fun `single file without FILE marker extracts as UNKNOWN_FILE`() {
        val response = """
```kotlin
fun main() {
    println("hello")
}
```
        """.trimIndent()

        val result = ResponseParser.parse(response)
        assertEquals(1, result.files.size)
        assertTrue(result.files.containsKey("UNKNOWN_FILE"))
        assertTrue(result.files["UNKNOWN_FILE"]!!.contains("fun main()"))
    }

    @Test
    fun `empty response produces no files`() {
        val result = ResponseParser.parse("")
        assertTrue(result.files.isEmpty())
    }

    @Test
    fun `response with no code blocks produces no files`() {
        val result = ResponseParser.parse("Here is some text without any code.")
        assertTrue(result.files.isEmpty())
    }

    @Test
    fun `raw response is preserved`() {
        val response = "FILE: a.kt\n```kotlin\ncode\n```"
        val result = ResponseParser.parse(response)
        assertEquals(response, result.rawResponse)
    }

    @Test
    fun `kts code fence language accepted`() {
        val response = """
FILE: build.gradle.kts
```kts
plugins { kotlin("jvm") }
```
        """.trimIndent()

        val result = ResponseParser.parse(response)
        assertEquals(1, result.files.size)
        assertTrue(result.files.containsKey("build.gradle.kts"))
    }
}
