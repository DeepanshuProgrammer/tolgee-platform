package io.tolgee.batch.processors

import io.tolgee.batch.BatchJobDto
import io.tolgee.batch.ChunkProcessor
import io.tolgee.batch.request.DeleteKeysRequest
import io.tolgee.model.EntityWithId
import io.tolgee.model.batch.BatchJob
import io.tolgee.service.key.KeyService
import kotlinx.coroutines.ensureActive
import org.springframework.stereotype.Component
import javax.persistence.EntityManager
import kotlin.coroutines.CoroutineContext

@Component
class DeleteKeysChunkProcessor(
  private val keyService: KeyService,
  private val entityManager: EntityManager
) : ChunkProcessor<DeleteKeysRequest> {
  override fun process(
    job: BatchJobDto,
    chunk: List<Long>,
    coroutineContext: CoroutineContext,
    onProgress: ((Int) -> Unit)
  ) {
    coroutineContext.ensureActive()
    val subChunked = chunk.chunked(100)
    var progress: Int = 0
    subChunked.forEach { subChunk ->
      coroutineContext.ensureActive()
      keyService.deleteMultiple(subChunk)
      entityManager.flush()
      progress += subChunk.size
      onProgress.invoke(progress)
    }
  }

  override fun getTarget(data: DeleteKeysRequest): List<Long> {
    return data.keyIds
  }

  override fun getParams(data: DeleteKeysRequest, job: BatchJob): EntityWithId? {
    return null
  }
}
