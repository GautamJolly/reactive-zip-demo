package demo.reactivezip

import org.reactivestreams.Publisher
import org.springframework.core.io.buffer.DataBuffer
import org.springframework.core.io.buffer.DataBufferFactory
import org.springframework.core.io.buffer.DataBufferUtils
import reactor.core.publisher.Flux
import reactor.core.scheduler.Schedulers
import reactor.kotlin.core.publisher.toFlux
import java.nio.channels.Channels
import java.nio.channels.Pipe
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Combines reactive streams into a [ZipOutputStream], the bytes from which are piped into the response `Flux`. Each
 * entry in the input `sources` map must provide the name to use for a zip entry and a supplier of reactive stream of
 * bytes corresponding to that zip entry.
 *
 * @param sources map of zip entry name and supplier of reactive stream of corresponding bytes.
 * @param dataBufferFactory the factory to create data buffers with.
 * @param bufferSize the maximum size of the data buffers.
 * @return a Flux of data buffers containing zipped data from the input sources.
 */
fun zip(
    sources: Map<String, () -> Publisher<ByteArray>>,
    dataBufferFactory: DataBufferFactory,
    bufferSize: Int
): Flux<DataBuffer> =
    Flux.using(
        { Pipe.open() },
        { pipe ->
            // create a flux that writes content from each source sequentially into the pipe's sink-channel via a ZipOutputStream
            val zipStream = ZipOutputStream(Channels.newOutputStream(pipe.sink()))
            val f1 = sources.entries
                .toFlux()
                .concatMap { (zipEntryName, source) ->
                    source()
                        .toFlux()
                        .doOnSubscribe { zipStream.putNextEntry(ZipEntry(zipEntryName)) }
                        .doOnNext { zipStream.write(it) }
                        .thenMany(Flux.empty<DataBuffer>())
                        .subscribeOn(Schedulers.boundedElastic())
                }
                .doFinally { pipe.sink().close() }
                .doFinally { zipStream.close() }
                .subscribeOn(Schedulers.boundedElastic())

            // create a second flux based on pipe's source-channel, which is expected to receive zipped content from the first flux
            val f2 = Flux.defer {
                DataBufferUtils.readByteChannel({ pipe.source() }, dataBufferFactory, bufferSize)
                    .doOnNext(DataBufferUtils.releaseConsumer())
            }

            Flux.merge(f1, f2)
        },
        { pipe -> pipe.source().close() }
    ).subscribeOn(Schedulers.boundedElastic())