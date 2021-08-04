/*
 * Copyright 2020-2021 Exactpro (Exactpro Systems Limited)
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

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