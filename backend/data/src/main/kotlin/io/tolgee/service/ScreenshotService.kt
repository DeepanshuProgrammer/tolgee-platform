/*
 * Copyright (c) 2020. Tolgee
 */

package io.tolgee.service

import io.tolgee.component.fileStorage.FileStorage
import io.tolgee.configuration.tolgee.TolgeeProperties
import io.tolgee.exceptions.BadRequestException
import io.tolgee.exceptions.NotFoundException
import io.tolgee.exceptions.PermissionException
import io.tolgee.model.Screenshot
import io.tolgee.model.key.Key
import io.tolgee.repository.ScreenshotRepository
import io.tolgee.security.AuthenticationFacade
import io.tolgee.service.ImageUploadService.Companion.UPLOADED_IMAGES_STORAGE_FOLDER_NAME
import io.tolgee.util.ImageConverter
import io.tolgee.util.executeInNewTransaction
import org.springframework.core.io.InputStreamSource
import org.springframework.retry.annotation.Retryable
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.annotation.Transactional

@Service
class ScreenshotService(
  private val screenshotRepository: ScreenshotRepository,
  private val fileStorage: FileStorage,
  private val tolgeeProperties: TolgeeProperties,
  private val imageUploadService: ImageUploadService,
  private val authenticationFacade: AuthenticationFacade,
  private val transactionManager: PlatformTransactionManager
) {
  companion object {
    const val SCREENSHOTS_STORAGE_FOLDER_NAME = "screenshots"
  }

  fun store(screenshotImage: InputStreamSource, key: Key): Screenshot {
    if (getScreenshotsCountForKey(key) >= tolgeeProperties.maxScreenshotsPerKey) {
      throw BadRequestException(
        io.tolgee.constants.Message.MAX_SCREENSHOTS_EXCEEDED,
        listOf(tolgeeProperties.maxScreenshotsPerKey)
      )
    }
    val converter = ImageConverter(screenshotImage.inputStream)
    val image = converter.getImage()
    val thumbnail = converter.getThumbNail()
    return storeProcessed(image.toByteArray(), thumbnail.toByteArray(), key)
  }

  @Retryable
  fun storeProcessed(image: ByteArray, thumbnail: ByteArray, key: Key): Screenshot {
    val screenshotEntity = Screenshot().also {
      it.key = key
      it.extension = "png"
    }
    try {
      return executeInNewTransaction(transactionManager) {
        key.screenshots.add(screenshotEntity)
        screenshotRepository.save(screenshotEntity)
        fileStorage.storeFile(screenshotEntity.getThumbnailPath(), thumbnail)
        fileStorage.storeFile(screenshotEntity.getFilePath(), image)
        screenshotEntity
      }
    } catch (e: Exception) {
      fileStorage.deleteFile(screenshotEntity.getThumbnailPath())
      fileStorage.deleteFile(screenshotEntity.getFilePath())
      throw e
    }
  }

  @Transactional
  fun saveUploadedImages(uploadedImageIds: Collection<Long>, key: Key) {
    val images = imageUploadService.find(uploadedImageIds)
    if (images.size < uploadedImageIds.size) {
      throw NotFoundException(io.tolgee.constants.Message.ONE_OR_MORE_IMAGES_NOT_FOUND)
    }
    images.forEach { uploadedImageEntity ->
      if (authenticationFacade.userAccount.id != uploadedImageEntity.userAccount.id) {
        throw PermissionException()
      }
      val data = fileStorage
        .readFile(
          UPLOADED_IMAGES_STORAGE_FOLDER_NAME + "/" + uploadedImageEntity.filenameWithExtension
        )
      val thumbnail = ImageConverter(data.inputStream()).getThumbNail()
      storeProcessed(data, thumbnail.toByteArray(), key)
      imageUploadService.delete(uploadedImageEntity)
    }
  }

  fun findAll(key: Key): List<Screenshot> {
    return screenshotRepository.findAllByKey(key)
  }

  @Transactional
  fun delete(screenshots: Collection<Screenshot>) {
    screenshots.forEach {
      screenshotRepository.deleteById(it.id)
      deleteFile(it)
    }
  }

  fun findByIdIn(ids: Collection<Long>): MutableList<Screenshot> {
    return screenshotRepository.findAllById(ids)
  }

  fun deleteAllByProject(projectId: Long) {
    val all = screenshotRepository.getAllByKeyProjectId(projectId)
    all.forEach { this.deleteFile(it) }
    screenshotRepository.deleteAllInBatch(all)
  }

  fun deleteAllByKeyId(keyId: Long) {
    val all = screenshotRepository.getAllByKeyId(keyId)
    all.forEach { this.deleteFile(it) }
    screenshotRepository.deleteAllInBatch(all)
  }

  fun deleteAllByKeyId(keyIds: Collection<Long>) {
    val all = screenshotRepository.getAllByKeyIdIn(keyIds)
    all.forEach { this.deleteFile(it) }
    screenshotRepository.deleteAllInBatch(all)
  }

  private fun deleteFile(screenshot: Screenshot) {
    fileStorage.deleteFile(screenshot.getFilePath())
  }

  private fun Screenshot.getFilePath(): String {
    return "$SCREENSHOTS_STORAGE_FOLDER_NAME/${this.filename}"
  }

  private fun Screenshot.getThumbnailPath(): String {
    return "$SCREENSHOTS_STORAGE_FOLDER_NAME/${this.thumbnailFilename}"
  }

  fun saveAll(screenshots: List<Screenshot>) {
    screenshotRepository.saveAll(screenshots)
  }

  fun getScreenshotsCountForKey(key: Key): Long {
    return screenshotRepository.countByKey(key)
  }

  fun getKeysWithScreenshots(ids: Collection<Long>): List<Key> {
    return screenshotRepository.getKeysWithScreenshots(ids)
  }
}
