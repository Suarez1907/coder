package com.suarez.coder

import androidx.test.core.app.ApplicationProvider
import com.suarez.coder.data.Settings
import com.suarez.coder.evolution.EvolutionStore
import com.suarez.coder.evolution.Experiment
import com.suarez.coder.evolution.ExperimentApplier
import com.suarez.coder.skills.SkillRepository
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The architect's model is the user's choice; no component — not even an experiment that "won" — may change it. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExperimentLockTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun newApplier(settings: Settings) = ExperimentApplier(
        settings,
        EvolutionStore(tmp.newFolder()),
        SkillRepository(tmp.newFolder(), { _ -> emptyList() }, { _, _ -> null }),
    )

    private fun architectExperiment(model: String) = Experiment(
        kind = Experiment.Kind.MODEL,
        title = "Uygulayıcı → mimar tuzağı",
        payload = JSONObject().put("role", "architect").put("model", model),
        source = "test",
    )

    @Test fun theFactoryRefusesToCreateArchitectModelExperiments() {
        try {
            Experiment.model("architect", "deepseek/x", "test")
            throw AssertionError("architect deneyi üretilmemeliydi")
        } catch (e: IllegalArgumentException) {
            // beklenen: fabrika kilitli
        }
    }

    @Test fun anExperimentThatTargetsTheArchitectModelIsNeverApplied() {
        val settings = Settings(ApplicationProvider.getApplicationContext())
        val architect = settings.architectModel
        settings.editorModel = "deepseek/editor"
        newApplier(settings).apply(architectExperiment("deepseek/architect-killer"))
        assertEquals(architect, settings.architectModel)
        assertEquals("deepseek/editor", settings.editorModel)
    }

    @Test fun theUndoForAnArchitectModelExperimentCannotChangeTheArchitectEither() {
        val settings = Settings(ApplicationProvider.getApplicationContext())
        val architect = settings.architectModel
        newApplier(settings).undo("{\"kind\":\"MODEL\",\"role\":\"architect\",\"old\":\"deepseek/gecmis\"}")
        assertEquals(architect, settings.architectModel)
    }

    @Test fun anExperimentForTheEditorModelIsStillApplied() {
        val settings = Settings(ApplicationProvider.getApplicationContext())
        settings.editorModel = "deepseek/editor"
        newApplier(settings).apply(Experiment.model("editor", "deepseek/editor-kazanan", "test"))
        assertEquals("deepseek/editor-kazanan", settings.editorModel)
    }

    @Test fun undoRestoresTheEditorModel() {
        val settings = Settings(ApplicationProvider.getApplicationContext())
        settings.editorModel = "deepseek/editor-yeni"
        newApplier(settings).undo("{\"kind\":\"MODEL\",\"role\":\"editor\",\"old\":\"deepseek/editor-eski\"}")
        assertEquals("deepseek/editor-eski", settings.editorModel)
    }

    @Test fun aManuallyBuiltArchitectExperimentIsRecognisedAsLocked() {
        assertTrue(architectExperiment("deepseek/y").isArchitectExperiment())
    }
}
