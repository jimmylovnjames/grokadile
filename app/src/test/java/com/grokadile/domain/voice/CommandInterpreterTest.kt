package com.grokadile.domain.voice

import com.grokadile.core.common.AppResult
import com.grokadile.domain.model.ChatResponse
import com.grokadile.testutil.FakeGrokRepository
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CommandInterpreterTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val interpreter = CommandInterpreter(
        grok = FakeGrokRepository(AppResult.Success(ChatResponse("{}", "grok-test"))),
        json = json,
    )

    @Test
    fun `start and stop autonomy`() {
        assertEquals(CommandIntent.StartAutonomy, interpreter.localInterpret("start autonomy"))
        assertEquals(CommandIntent.StartAutonomy, interpreter.localInterpret("enable agents"))
        assertEquals(CommandIntent.StopAutonomy, interpreter.localInterpret("stop autonomy"))
        assertEquals(CommandIntent.StopAutonomy, interpreter.localInterpret("turn off agents"))
    }

    @Test
    fun `global actions`() {
        assertEquals(CommandIntent.GlobalAction("BACK"), interpreter.localInterpret("go back"))
        assertEquals(CommandIntent.GlobalAction("HOME"), interpreter.localInterpret("home"))
        assertEquals(CommandIntent.GlobalAction("RECENTS"), interpreter.localInterpret("open recents"))
    }

    @Test
    fun `read screen`() {
        assertEquals(CommandIntent.ReadScreen("text"), interpreter.localInterpret("read the screen"))
        assertEquals(CommandIntent.ReadScreen("hierarchy"), interpreter.localInterpret("dump screen hierarchy"))
        assertEquals(CommandIntent.ReadScreen("text"), interpreter.localInterpret("what's on the screen"))
    }

    @Test
    fun `tap and type`() {
        assertEquals(CommandIntent.TapText("Login"), interpreter.localInterpret("tap Login"))
        assertEquals(CommandIntent.TapText("Submit"), interpreter.localInterpret("click on Submit"))
        assertEquals(CommandIntent.TypeText("hello world"), interpreter.localInterpret("type hello world"))
    }

    @Test
    fun `strips wake phrase`() {
        assertEquals(
            CommandIntent.ReadScreen("text"),
            interpreter.localInterpret("hey grok read the screen"),
        )
        assertEquals(
            CommandIntent.GlobalAction("BACK"),
            interpreter.localInterpret("hey grokadile go back"),
        )
    }

    @Test
    fun `questions become ask grok`() {
        val intent = interpreter.localInterpret("what is the capital of France?")
        assertTrue(intent is CommandIntent.AskGrok)
        assertEquals(
            "what is the capital of France?",
            (intent as CommandIntent.AskGrok).prompt,
        )
    }

    @Test
    fun `explicit ask`() {
        val intent = interpreter.localInterpret("ask grok summarize this")
        assertTrue(intent is CommandIntent.AskGrok)
        assertEquals("summarize this", (intent as CommandIntent.AskGrok).prompt)
    }

    @Test
    fun `echo`() {
        assertEquals(CommandIntent.Echo("ping"), interpreter.localInterpret("echo ping"))
    }

    @Test
    fun `remember and recall`() {
        assertEquals(
            CommandIntent.Remember("the gate code is 4821"),
            interpreter.localInterpret("remember the gate code is 4821"),
        )
        assertEquals(
            CommandIntent.SearchMemory("gate code"),
            interpreter.localInterpret("recall gate code"),
        )
        assertEquals(
            CommandIntent.SearchMemory("OTP"),
            interpreter.localInterpret("what do you remember about OTP"),
        )
    }

    @Test
    fun `plan clipboard launch and health`() {
        assertEquals(
            CommandIntent.Plan("a morning brief"),
            interpreter.localInterpret("plan a morning brief"),
        )
        assertEquals(CommandIntent.ClipboardGet, interpreter.localInterpret("what's on the clipboard"))
        assertEquals(
            CommandIntent.ClipboardSet("hello"),
            interpreter.localInterpret("copy hello"),
        )
        assertEquals(CommandIntent.LaunchApp("Maps"), interpreter.localInterpret("open Maps"))
        assertEquals(CommandIntent.DeviceStatus, interpreter.localInterpret("device health"))
        assertEquals(CommandIntent.RetryFailed, interpreter.localInterpret("retry failed tasks"))
    }

    @Test
    fun `open recents is still a global action not launch`() {
        assertEquals(CommandIntent.GlobalAction("RECENTS"), interpreter.localInterpret("open recents"))
    }

    @Test
    fun `wake phrase detection helpers`() {
        assertTrue(com.grokadile.voice.VoiceAssistant.containsWakePhrase("hey grok"))
        assertTrue(com.grokadile.voice.VoiceAssistant.containsWakePhrase("Hey Grokadile, tap Login"))
        assertEquals(
            "tap Login",
            com.grokadile.voice.VoiceAssistant.stripWakePhrase("hey grok tap Login"),
        )
    }

    @Test
    fun `llm fallback parses json intent`() = runTest {
        val grok = FakeGrokRepository(
            AppResult.Success(
                ChatResponse(
                    content = """{"intent":"tap_text","text":"Continue"}""",
                    model = "grok-test",
                ),
            ),
        )
        val llmInterpreter = CommandInterpreter(grok, json)
        val intent = llmInterpreter.interpret("please select continue", useLlmFallback = true)
        assertEquals(CommandIntent.TapText("Continue"), intent)
    }
}
