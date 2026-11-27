package fm.heterodyne.ui.sandbox

import fm.heterodyne.ui.contract.*

class SandboxContractTest : RadioUiContractTest() {
    private lateinit var scripted: ScriptedRadioUi
    override fun openRadio(noReply: Boolean): RadioUi {
        scripted = ScriptedRadioUi(if (noReply) Scenario.TuneTimeout else Scenario.Profiles)
        if (!noReply) scripted.act(RadioAction.ExitParking)
        return scripted
    }

    override fun confirmTarget() = scripted.advanceBy(REPLY_MILLIS)
    override fun startMoving() = scripted.moving(true)
    override fun settle() = Unit
    override fun detachBeforeReply() = scripted.detach()
    override fun advance(millis: Long) = scripted.advanceBy(millis)
}
