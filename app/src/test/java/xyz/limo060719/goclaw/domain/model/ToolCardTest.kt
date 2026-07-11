package xyz.limo060719.goclaw.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Covers the agent-delivered-file extraction that drives download chips in tool/reply bubbles. */
class ToolCardTest {

    @Test fun extracts_path_from_arguments_json() {
        val files = ToolCard.extractFiles("""{"path":"/workspace/out/report.pdf"}""", "")
        assertEquals(1, files.size)
        assertEquals("/workspace/out/report.pdf", files[0].path)
        assertEquals("report.pdf", files[0].filename)
        assertEquals("application/pdf", files[0].mimeType)
    }

    @Test fun extracts_media_and_workspace_paths_from_result_text() {
        val files = ToolCard.extractFiles("", "saved to /v1/media/abc123/photo.png and /workspace/a/b/data.json")
        val paths = files.map { it.path }
        assertTrue(paths.any { it.startsWith("/v1/media/") && it.endsWith("photo.png") })
        assertTrue(paths.any { it.endsWith("data.json") })
    }

    @Test fun dedupes_repeated_paths() {
        val files = ToolCard.extractFiles(
            """{"file":"/workspace/x.txt"}""",
            "wrote /workspace/x.txt (see /workspace/x.txt)",
        )
        assertEquals(1, files.size)
    }

    @Test fun ignores_text_without_file_references() {
        assertTrue(ToolCard.extractFiles("{}", "All done, no files here.").isEmpty())
    }

    @Test fun guessMime_maps_known_extensions() {
        assertEquals("image/png", ToolCard.guessMime("a.png"))
        assertEquals("image/jpeg", ToolCard.guessMime("photo.JPG"))
        assertEquals("application/pdf", ToolCard.guessMime("doc.pdf"))
        assertEquals("text/x-kotlin", ToolCard.guessMime("Main.kt"))
        assertEquals("application/zip", ToolCard.guessMime("bundle.zip"))
    }

    @Test fun guessMime_falls_back_to_octet_stream() {
        assertEquals("application/octet-stream", ToolCard.guessMime("mystery.qwerty"))
        assertEquals("application/octet-stream", ToolCard.guessMime("noext"))
    }
}
