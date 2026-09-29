package com.acme.performance.auth.web;

import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class PasswordValidationTest {
    @Test
    void acceptsExistingShortPasswordsButKeepsNewPasswordLength() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            assertThat(validator.validate(new AdminAuthController.LoginRequest("test", "test", "MINIPROGRAM"))).isEmpty();
            assertThat(validator.validate(new AdminAuthController.LoginRequest("test", "test", "WEB"))).isEmpty();
            assertThat(validator.validate(new WechatLoginController.BindAccountRequest("binding-token", "test", "test"))).isEmpty();
            assertThat(validator.validate(new AdminAuthController.PasswordRequest("test", "Strong123"))).isEmpty();
            assertThat(validator.validate(new AdminAuthController.PasswordRequest("test", "short"))).isNotEmpty();
            for (String invalid : new String[]{"", "   ", "x".repeat(129)}) {
                assertThat(validator.validate(new AdminAuthController.LoginRequest("test", invalid, "MINIPROGRAM"))).isNotEmpty();
                assertThat(validator.validate(new WechatLoginController.BindAccountRequest("binding-token", "test", invalid))).isNotEmpty();
                assertThat(validator.validate(new AdminAuthController.PasswordRequest(invalid, "Strong123"))).isNotEmpty();
            }
        }
    }
}
