package ai.localstudio.app

import ai.localstudio.app.modelinstall.CandidateTrialState
import android.content.Context

/** One line for where the running test is -- the notification and the candidates screen say the same thing. */
fun CandidateTrialState.describe(context: Context): String {
    val name = repoId.substringAfter('/')
    return when (phase) {
        CandidateTrialState.Phase.LOADING -> context.getString(R.string.candidate_phase_loading, name)
        CandidateTrialState.Phase.ANSWERING -> context.getString(R.string.candidate_phase_answering, name, probe, probes)
    }
}
