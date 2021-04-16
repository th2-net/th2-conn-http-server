import com.exactpro.th2.httpserver.server.Dialogue
import com.exactpro.th2.httpserver.server.DialogueManager
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import rawhttp.core.RawHttpRequest
import testimpl.TestSocket

class TestDialogueManager {

    @Test
    fun dialogueBeforeRemove() {
        val delay = 1L
        val manager = DialogueManager(delay+1)
        manager.startCleaner()
        manager.dialogues["test"] = Dialogue(RawHttpRequest(null, null, null,null), TestSocket(true))

        Thread.sleep((delay) * 1000)
        Assertions.assertEquals(1, manager.dialogues.size)
        manager.close()
    }

    @Test
    fun dialogueAfterRemove() {
        val delay = 1L
        val manager = DialogueManager(delay)
        manager.startCleaner()
        manager.dialogues["test"] = Dialogue(RawHttpRequest(null, null, null,null), TestSocket(true))
        Thread.sleep(delay * 1100)
        Assertions.assertEquals(0, manager.dialogues.size)
        manager.close()
    }

    @Test
    fun dialoguePersist() {
        val delay = 1L
        val manager = DialogueManager(delay)
        manager.startCleaner()
        manager.dialogues["test"] = Dialogue(RawHttpRequest(null, null, null,null), TestSocket(false))
        Thread.sleep(delay * 1100)
        Assertions.assertEquals(1, manager.dialogues.size)
        manager.close()
    }

    @Test
    fun dialogueCountRemove() {
        val delay = 2L
        val manager = DialogueManager(delay)
        manager.startCleaner()
        for (i in 1..2) {
            manager.dialogues["test$i"] = Dialogue(RawHttpRequest(null, null, null,null), TestSocket(false))
        }
        for (i in 3..5) {
            manager.dialogues["test$i"] = Dialogue(RawHttpRequest(null, null, null,null), TestSocket(true))
        }
        Thread.sleep((delay*2) * 1000)
        Assertions.assertEquals(2, manager.dialogues.size)
        manager.close()
    }
}