package io.tolgee.api.v2.controllers

import io.tolgee.AbstractServerAppAuthorizedControllerTest
import io.tolgee.dtos.request.UserMfaRecoveryRequestDto
import io.tolgee.dtos.request.UserTotpDisableRequestDto
import io.tolgee.dtos.request.UserTotpEnableRequestDto
import io.tolgee.fixtures.andIsBadRequest
import io.tolgee.fixtures.andIsForbidden
import io.tolgee.fixtures.andIsOk
import io.tolgee.fixtures.retry
import io.tolgee.testing.assertions.Assertions.assertThat
import org.apache.commons.codec.binary.Base32
import org.assertj.core.api.Assertions
import org.junit.jupiter.api.Test
import org.springframework.test.web.servlet.result.MockMvcResultMatchers

class UserMfaControllerTest : AbstractServerAppAuthorizedControllerTest() {
  companion object {
    private const val TOTP_KEY = "meowmeowmeowmeow"
  }

  private val encodedKey: ByteArray = Base32().decode(TOTP_KEY)

  fun enableMfa() {
    userAccount?.let {
      userAccountService.enableMfaTotp(userAccountService.get(it.id), encodedKey)
    }
  }

  @Test
  fun `it enables MFA`() {
    retry {
      val requestDto = UserTotpEnableRequestDto(
        totpKey = TOTP_KEY,
        otp = mfaService.generateStringCode(encodedKey),
        password = initialPassword
      )

      performAuthPut("/v2/user/mfa/totp", requestDto).andIsOk
      val fromDb = userAccountService.findActive(initialUsername)
      Assertions.assertThat(fromDb!!.totpKey).isEqualTo(encodedKey)
    }
  }

  @Test
  fun `it disables MFA`() {
    enableMfa()
    val requestDto = UserTotpDisableRequestDto(
      password = initialPassword
    )
    performAuthDelete("/v2/user/mfa/totp", requestDto).andIsOk
    val fromDb = userAccountService.findActive(initialUsername)
    Assertions.assertThat(fromDb!!.totpKey).isNull()
  }

  @Test
  fun `it regenerates MFA recovery codes`() {
    enableMfa()
    val requestDto = UserMfaRecoveryRequestDto(
      password = initialPassword
    )

    var fromDb = userAccountService.findActive(initialUsername)
    Assertions.assertThat(fromDb!!.mfaRecoveryCodes).isEmpty()

    performAuthPut("/v2/user/mfa/recovery", requestDto).andIsOk
    fromDb = userAccountService.findActive(initialUsername)
    Assertions.assertThat(fromDb!!.mfaRecoveryCodes).isNotEmpty
  }

  @Test
  fun `it requires valid TOTP code for activation`() {
    retry {
      val requestDto = UserTotpEnableRequestDto(
        totpKey = TOTP_KEY,
        otp = (mfaService.generateCode(encodedKey) + 1).toString().padStart(6, '0'),
        password = initialPassword
      )

      val res = performAuthPut("/v2/user/mfa/totp", requestDto)
        .andIsBadRequest
        .andReturn()

      assertThat(res).error().isCustomValidation.hasMessage("invalid_otp_code")
      val fromDb = userAccountService.findActive(initialUsername)
      Assertions.assertThat(fromDb!!.totpKey).isNull()
    }
  }

  @Test
  fun `it requires valid password`() {
    val enableRequestDto = UserTotpEnableRequestDto(
      totpKey = TOTP_KEY,
      otp = mfaService.generateStringCode(encodedKey),
      password = "pwease let me innn!!!! >:("
    )

    val disableRequestDto = UserTotpEnableRequestDto(
      password = "pwease let me innn!!!! >:("
    )

    val recoveryRequestDto = UserMfaRecoveryRequestDto(
      password = "pwease let me innn!!!! >:("
    )

    performAuthPut("/v2/user/mfa/totp", enableRequestDto).andIsForbidden
    var fromDb = userAccountService.findActive(initialUsername)
    Assertions.assertThat(fromDb!!.totpKey).isNull()

    enableMfa()

    loginAsUser(userAccount!!) // get new token

    performAuthDelete("/v2/user/mfa/totp", disableRequestDto).andIsForbidden
    fromDb = userAccountService.findActive(initialUsername)
    Assertions.assertThat(fromDb!!.totpKey).isNotNull

    performAuthPut("/v2/user/mfa/recovery", recoveryRequestDto).andIsForbidden
    fromDb = userAccountService.findActive(initialUsername)
    Assertions.assertThat(fromDb!!.mfaRecoveryCodes).isEmpty()
  }

  @Test
  fun `it invalidates tokens generated prior a mfa status change`() {
    retry {
      loginAsAdminIfNotLogged()
      Thread.sleep(1000)

      val enableRequestDto = UserTotpEnableRequestDto(
        totpKey = TOTP_KEY,
        otp = mfaService.generateStringCode(encodedKey),
        password = initialPassword
      )

      performAuthPut("/v2/user/mfa/totp", enableRequestDto).andIsOk
      refreshUser()
      performAuthGet("/v2/user").andExpect(MockMvcResultMatchers.status().isUnauthorized)

      logout()
      loginAsAdminIfNotLogged()
      Thread.sleep(1000)

      val disableRequestDto = UserTotpDisableRequestDto(
        password = initialPassword
      )
      performAuthDelete("/v2/user/mfa/totp", disableRequestDto).andIsOk
      refreshUser()
      performAuthGet("/v2/user").andExpect(MockMvcResultMatchers.status().isUnauthorized)
    }
  }
}
