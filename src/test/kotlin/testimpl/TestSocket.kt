package testimpl

import java.net.Socket

class TestSocket(val closed: Boolean = false) : Socket() {
    override fun isClosed(): Boolean {
        return closed
    }

    override fun close() {
        runCatching { super.close() }
    }
}