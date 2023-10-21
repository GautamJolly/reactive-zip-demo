package demo.reactivezip

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Test
import org.mockito.BDDMockito.given
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.core.io.buffer.DataBuffer
import org.springframework.core.io.buffer.DataBufferUtils
import org.springframework.http.ContentDisposition
import org.springframework.http.MediaType.APPLICATION_OCTET_STREAM
import org.springframework.test.web.reactive.server.WebTestClient
import org.springframework.test.web.reactive.server.returnResult
import reactor.core.publisher.Flux
import reactor.core.scheduler.Schedulers
import reactor.kotlin.core.publisher.toFlux
import reactor.kotlin.core.publisher.toMono
import reactor.kotlin.test.test
import java.lang.System.lineSeparator
import java.nio.channels.Channels
import java.nio.channels.Pipe
import java.util.zip.ZipInputStream

/** Tests [ReactiveZipController]. */
@WebFluxTest(controllers = [ReactiveZipController::class])
class ReactiveZipControllerTest {
    @MockBean
    private lateinit var dataProvider: DataProvider

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @Autowired
    private lateinit var webTestClient: WebTestClient

    /** Tests [ReactiveZipController.downloadZipFile]. */
    @Test
    fun downloadZipFile() {
        // setup couple of reactive streams of arbitrary data, to be combined into a zip file.
        data class Type1(val f1: String)
        data class Type2(val f1: String, val f2: Int)

        val type1Data = (1..10).map { objectNo -> Type1("v:$objectNo") }
        val type2Data = (1..20).map { objectNo -> Type2("v:$objectNo", objectNo) }
        given(dataProvider.get()).willReturn(mapOf(
            "type1.json" to {
                type1Data.toMono().map { objectMapper.writeValueAsBytes(it) }
            },
            "type2.ndjson" to {
                type2Data.toFlux().map { "${objectMapper.writeValueAsString(it)}${lineSeparator()}".toByteArray() }
            }
        ))

        // invoke the controller, unzip the response and then assert that it matches the source data
        webTestClient.get()
            .uri("/")
            .exchange()
            .expectStatus().isOk
            .expectHeader().contentType(APPLICATION_OCTET_STREAM)
            .expectHeader()
            .contentDisposition(ContentDisposition.attachment().filename("test.zip").build())
            .returnResult<DataBuffer>()
            .responseBody
            .unzip()
            .doOnNext { println(it) }
            .test()
            .expectNext("type1.json")
            .expectNext(objectMapper.writeValueAsString(type1Data))
            .expectNext("type2.ndjson")
            .expectNextSequence(type2Data.map { objectMapper.writeValueAsString(it) })
            .verifyComplete()
    }

    /**
     * Helper function to transform a reactive stream of zipped bytes into a `Flux` that comprises the name of each
     * zip entry, followed by lines of textual content corresponding to that zip entry.
     */
    private fun Flux<DataBuffer>.unzip(): Flux<String> =
        Flux.using(
            { Pipe.open() },
            { pipe ->
                // create a flux that writes zipped content from the current flux, over to the pipe's sink-channel
                val f1 = DataBufferUtils.write(this, pipe.sink())
                    .doOnNext(DataBufferUtils.releaseConsumer())
                    .doFinally { pipe.sink().close() }
                    .thenMany(Flux.empty<String>())
                    .subscribeOn(Schedulers.boundedElastic())

                // create a flux that reads content from the pipe's source-channel, unzipping it in the process
                val f2 = Flux.defer {
                    val zipInputStream = ZipInputStream(Channels.newInputStream(pipe.source()))
                    val reader = zipInputStream.bufferedReader()
                    generateSequence(zipInputStream.nextEntry) { zipInputStream.nextEntry }
                        .flatMap { zipEntry ->
                            // add the name of the zipEntry, followed by each line associated with it
                            sequenceOf(zipEntry.name) +
                                    generateSequence(reader.readLine()) { reader.readLine() }
                        }
                        .toFlux()
                }

                Flux.merge(f1, f2)
            },
            { pipe -> pipe.source().close() }
        ).subscribeOn(Schedulers.boundedElastic())
}