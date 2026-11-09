import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

class ConsumerTest {
    @Test fun consumesPublishedArtifact() = runBlocking {
        val root = Files.createTempDirectory("shareinbox-consumer").toFile()
        try { assertEquals("published artifact", consumePublishedPackage(root.path)) }
        finally { root.deleteRecursively() }
    }
}
