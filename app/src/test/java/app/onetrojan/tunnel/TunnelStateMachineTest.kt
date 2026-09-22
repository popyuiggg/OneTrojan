package app.onetrojan.tunnel

import org.junit.Assert.assertEquals
import org.junit.Test

class TunnelStateMachineTest {
    @Test
    fun `connection is protected only after engine is ready`() {
        val machine = TunnelStateMachine()

        assertEquals(TunnelPhase.REQUESTING_PERMISSION, machine.dispatch(TunnelEvent.EnableRequested).phase)
        assertEquals(TunnelPhase.STARTING, machine.dispatch(TunnelEvent.PermissionGranted).phase)
        assertEquals(TunnelPhase.BLOCKING, machine.dispatch(TunnelEvent.TunEstablished).phase)
        assertEquals(TunnelPhase.PROTECTED, machine.dispatch(TunnelEvent.EngineReady).phase)
    }

    @Test
    fun `engine cannot claim protection before tun exists`() {
        val machine = TunnelStateMachine()

        machine.dispatch(TunnelEvent.EnableRequested)
        val result = machine.dispatch(TunnelEvent.EngineReady)

        assertEquals(TunnelPhase.REQUESTING_PERMISSION, result.phase)
    }

    @Test
    fun `disable always returns to off`() {
        val machine = TunnelStateMachine()
        machine.dispatch(TunnelEvent.Failed("test"))

        assertEquals(TunnelPhase.OFF, machine.dispatch(TunnelEvent.Disabled).phase)
    }

    @Test
    fun `engine failure remains fail closed instead of claiming protection`() {
        val machine = TunnelStateMachine()
        machine.dispatch(TunnelEvent.TunEstablished)

        val result = machine.dispatch(TunnelEvent.EngineWaiting("retrying"))

        assertEquals(TunnelPhase.BLOCKING, result.phase)
        assertEquals("retrying", result.detail)
    }
}
