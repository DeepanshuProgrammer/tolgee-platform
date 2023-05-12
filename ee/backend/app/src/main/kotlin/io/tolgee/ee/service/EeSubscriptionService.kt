package io.tolgee.ee.service

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.tolgee.component.CurrentDateProvider
import io.tolgee.constants.Message
import io.tolgee.ee.EeProperties
import io.tolgee.ee.api.v2.hateoas.PrepareSetEeLicenceKeyModel
import io.tolgee.ee.api.v2.hateoas.SelfHostedEeSubscriptionModel
import io.tolgee.ee.data.GetMySubscriptionDto
import io.tolgee.ee.data.PrepareSetLicenseKeyDto
import io.tolgee.ee.data.ReleaseKeyDto
import io.tolgee.ee.data.ReportErrorDto
import io.tolgee.ee.data.ReportUsageDto
import io.tolgee.ee.data.SetLicenseKeyLicensingDto
import io.tolgee.ee.data.SubscriptionStatus
import io.tolgee.ee.model.EeSubscription
import io.tolgee.ee.repository.EeSubscriptionRepository
import io.tolgee.exceptions.BadRequestException
import io.tolgee.exceptions.ErrorResponseBody
import io.tolgee.service.security.UserAccountService
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.client.HttpClientErrorException
import org.springframework.web.client.RestTemplate
import java.util.*

@Service
class EeSubscriptionService(
  private val eeSubscriptionRepository: EeSubscriptionRepository,
  private val restTemplate: RestTemplate,
  private val eeProperties: EeProperties,
  private val userAccountService: UserAccountService,
  private val currentDateProvider: CurrentDateProvider
) {
  companion object {
    const val setPath: String = "/v2/public/licensing/set-key"
    const val prepareSetKeyPath: String = "/v2/public/licensing/prepare-set-key"
    const val subscriptionInfoPath: String = "/v2/public/licensing/subscription"
    const val reportUsagePath: String = "/v2/public/licensing/report-usage"
    const val releaseKeyPath: String = "/v2/public/licensing/release-key"
    const val reportErrorPath: String = "/v2/public/licensing/report-error"
  }

  fun findSubscriptionEntity(): EeSubscription? {
    return eeSubscriptionRepository.findById(1).orElse(null)
  }

  fun setLicenceKey(licenseKey: String): EeSubscription {
    val seats = userAccountService.countAll()
    val instanceId = findSubscriptionEntity()?.instanceId ?: UUID.randomUUID().toString()

    val responseBody = try {
      postRequest<SelfHostedEeSubscriptionModel>(
        setPath,
        SetLicenseKeyLicensingDto(licenseKey, seats, instanceId)
      )
    } catch (e: HttpClientErrorException.NotFound) {
      throw BadRequestException(Message.LICENSE_KEY_NOT_FOUND)
    }

    if (responseBody != null) {
      val entity = EeSubscription().apply {
        this.licenseKey = licenseKey
        this.name = responseBody.plan.name
        this.currentPeriodEnd = responseBody.currentPeriodEnd?.let { Date(it) }
        this.enabledFeatures = responseBody.plan.enabledFeatures
        this.lastValidCheck = currentDateProvider.date
        this.instanceId = instanceId
      }
      return eeSubscriptionRepository.save(entity)
    }

    throw IllegalStateException("Licence not obtained.")
  }

  fun prepareSetLicenceKey(licenseKey: String): PrepareSetEeLicenceKeyModel {
    val seats = userAccountService.countAll()

    val responseBody = try {
      postRequest<PrepareSetEeLicenceKeyModel>(
        prepareSetKeyPath,
        PrepareSetLicenseKeyDto(licenseKey, seats),
      )
    } catch (e: HttpClientErrorException.NotFound) {
      throw BadRequestException(Message.LICENSE_KEY_NOT_FOUND)
    }

    if (responseBody != null) {
      return responseBody
    }

    throw IllegalStateException("Licence not obtained")
  }

  private inline fun <reified T> postRequest(url: String, body: Any): T? {
    val bodyJson = jacksonObjectMapper().writeValueAsString(body)
    val headers = HttpHeaders().apply {
      contentType = MediaType.APPLICATION_JSON
    }

    val stringResponse = restTemplate.exchange(
      "${eeProperties.licenseServer}$url",
      HttpMethod.POST,
      HttpEntity(bodyJson, headers),
      String::class.java
    )

    return stringResponse.body?.let { stringResponseBody ->
      jacksonObjectMapper()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
        .readValue(stringResponseBody, T::class.java)
    }
  }

  @Scheduled(fixedDelayString = """${'$'}{tolgee.ee.check-period-ms:300000}""")
  @Transactional
  fun checkSubscription() {
    refreshSubscription()
  }

  fun refreshSubscription() {
    val subscription = findSubscriptionEntity()
    if (subscription != null) {
      val responseBody = try {
        getRemoteSubscriptionInfo(subscription)
      } catch (e: HttpClientErrorException.BadRequest) {
        val error = e.parseBody()
        if (error.code == Message.LICENSE_KEY_USED_BY_ANOTHER_INSTANCE.code) {
          setSubscriptionKeyUsedByOtherInstance(subscription)
        }
        null
      } catch (e: Exception) {
        reportError(e.stackTraceToString())
        null
      }
      updateLocalSubscription(responseBody, subscription)
      handleConstantlyFailingRemoteCheck(subscription)
    }
  }

  private fun setSubscriptionKeyUsedByOtherInstance(subscription: EeSubscription) {
    subscription.status = SubscriptionStatus.KEY_USED_BY_ANOTHER_INSTANCE
    eeSubscriptionRepository.save(subscription)
  }

  fun HttpClientErrorException.parseBody(): ErrorResponseBody {
    return jacksonObjectMapper().readValue(this.responseBodyAsString, ErrorResponseBody::class.java)
  }

  private fun updateLocalSubscription(
    responseBody: SelfHostedEeSubscriptionModel?,
    subscription: EeSubscription
  ) {
    if (responseBody != null) {
      subscription.currentPeriodEnd = responseBody.currentPeriodEnd?.let { Date(it) }
      subscription.enabledFeatures = responseBody.plan.enabledFeatures
      subscription.status = responseBody.status
      subscription.lastValidCheck = currentDateProvider.date
      eeSubscriptionRepository.save(subscription)
    }
  }

  private fun handleConstantlyFailingRemoteCheck(subscription: EeSubscription) {
    subscription.lastValidCheck?.let {
      val isConstantlyFailing = currentDateProvider.date.time - it.time > 1000 * 60 * 60 * 24 * 2
      if (isConstantlyFailing) {
        subscription.status = SubscriptionStatus.ERROR
        eeSubscriptionRepository.save(subscription)
      }
    }
  }

  private fun getRemoteSubscriptionInfo(subscription: EeSubscription): SelfHostedEeSubscriptionModel? {
    val responseBody = try {
      postRequest<SelfHostedEeSubscriptionModel>(
        subscriptionInfoPath,
        GetMySubscriptionDto(subscription.licenseKey, subscription.instanceId)
      )
    } catch (e: HttpClientErrorException.NotFound) {
      subscription.status = SubscriptionStatus.CANCELED
      null
    }
    return responseBody
  }

  fun reportError(error: String) {
    try {
      findSubscriptionEntity()?.let {
        postRequest<Any>(reportErrorPath, ReportErrorDto(error, it.licenseKey))
      }
    } catch (e: Exception) {
      e.printStackTrace()
    }
  }

  fun reportUsage() {
    val subscription = findSubscriptionEntity()
    if (subscription != null) {
      val seats = userAccountService.countAllEnabled()
      reportUsageRemote(subscription, seats)
    }
  }

  private fun reportUsageRemote(subscription: EeSubscription, seats: Long) {
    postRequest<Any>(
      reportUsagePath,
      ReportUsageDto(subscription.licenseKey, seats)
    )
  }

  private fun releaseKeyRemote(subscription: EeSubscription) {
    postRequest<Any>(
      releaseKeyPath,
      ReleaseKeyDto(subscription.licenseKey)
    )
  }

  @Transactional
  fun releaseSubscription() {
    val subscription = findSubscriptionEntity()
    if (subscription != null) {
      try {
        releaseKeyRemote(subscription)
      } catch (e: HttpClientErrorException.NotFound) {
        val licenceKeyNotFound = e.message?.contains(Message.LICENSE_KEY_NOT_FOUND.code) == true
        if (!licenceKeyNotFound) {
          throw e
        }
      }
      eeSubscriptionRepository.deleteAll()
    }
  }
}