import com.exactpro.th2.common.event.Event
import com.exactpro.th2.common.grpc.Direction
import com.exactpro.th2.common.grpc.RawMessage
import com.exactpro.th2.common.message.addField
import com.exactpro.th2.common.message.message
import com.exactpro.th2.httpserver.server.Th2HttpServer
import com.exactpro.th2.httpserver.server.responses.Th2Response
import com.google.protobuf.ByteString
import mu.KotlinLogging
import testimpl.TestServerOptions
import java.io.File

private val LOGGER = KotlinLogging.logger { }

open class TestServerManager(private val https: Boolean = false) {
    private val options = TestServerOptions(https)
    private val th2server = Th2HttpServer({ _: String, _: String, _: Throwable? -> Event.start()}, options, 1)

    val response = { uuid: String ->
        val responseMessage = message("Response", Direction.FIRST, "somealias").apply {
            addField("code", 200)
            addField("reason", "Test reason")
            metadataBuilder.protocol = "http"
            metadataBuilder.putProperties("uuid", uuid)
        }.build().apply { LOGGER.debug { "Header message is created" } }

        val bodyMessage = RawMessage.newBuilder().apply {
            body = ByteString.copyFrom("SOME BYTES".toByteArray())
            metadata = metadataBuilder.putProperties("contentType", "application").build()
        }.build().apply { LOGGER.debug { "Body message is created" } }

        Th2Response.Builder().setHead(responseMessage).setBody(bodyMessage).build()
    }

    fun start() {
        if (https) {
            val truststore: String = File(this.javaClass.getClassLoader().getResource("TestTrustStore").getFile()).absolutePath
            val pass = "servertest"
            System.setProperty("javax.net.ssl.trustStore", truststore)
            System.setProperty("javax.net.ssl.trustStorePassword", pass)
            System.setProperty("javax.net.debug", "all")
        }

        this.th2server.start()
    }

    fun close() {
        this.th2server.stop()
    }

    fun handleResponse() {
        val th2response = response(options.queue.take())
        th2server.handleResponse(th2response)
    }

}