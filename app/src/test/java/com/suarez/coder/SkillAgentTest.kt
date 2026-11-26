package com.suarez.coder

import androidx.test.core.app.ApplicationProvider
import com.suarez.coder.agent.Agent
import com.suarez.coder.data.Settings
import com.suarez.coder.llm.ToolCall
import com.suarez.coder.tools.ToolRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SkillAgentTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun systemPromptGetsTheCatalogAndLoadSkillGetsTheBody() = runBlocking {
        val settings = Settings(ApplicationProvider.getApplicationContext())
        settings.defaultRepo = ""
        val agent = Agent(settings, tmp.newFolder())
        val system = agent.agentSystem()
        assertTrue(system.contains("SKILLS (call load_skill"))
        assertTrue(system.contains("- blender-3d:"))
        assertFalse(system.contains("read_factory_settings")) // progressive disclosure
        assertEquals(system, agent.agentSystem()) // stable prefix
        assertTrue(agent.architectSystem(false).contains("- blender-3d:"))

        val body = agent.tools.execute(ToolCall("1", "load_skill", JSONObject().put("name", "blender-3d")))
        assertTrue(body.contains("read_factory_settings(use_empty=True)"))
        // Tools: load_skill is read-only (planner/question modes may use it); run_cloud_tool is a write, never for the architect.
        assertTrue("load_skill" in agent.tools.specs.map { it.name })
        assertTrue("run_cloud_tool" in agent.tools.specs.map { it.name })
        assertTrue("load_skill" in agent.tools.readOnlySpecs.map { it.name })
        assertNotNull(ToolRegistry.architectDenied("run_cloud_tool", null))
        assertTrue(agent.tools.needsApproval(ToolCall("2", "run_cloud_tool", JSONObject())))

        // Without app storage (evolution agents) there are no skills and no catalog.
        assertFalse(Agent(settings).agentSystem().contains("SKILLS"))
    }
}
