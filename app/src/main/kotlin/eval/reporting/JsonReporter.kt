package eval.reporting

import eval.model.EvalRun
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

object JsonReporter {

    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
    }

    fun write(run: EvalRun, outputDir: File) {
        outputDir.mkdirs()
        val file = File(outputDir, "results.json")
        file.writeText(json.encodeToString(run))
    }
}
