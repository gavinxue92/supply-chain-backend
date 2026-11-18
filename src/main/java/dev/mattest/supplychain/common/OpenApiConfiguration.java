package dev.mattest.supplychain.common;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeIn;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import org.springframework.context.annotation.Configuration;

@Configuration
@OpenAPIDefinition(
        info = @Info(
                title = "Supply Chain Backend API",
                version = "v1",
                description = "Admin endpoints use the ADMIN_SESSION cookie. "
                        + "Write requests also require the X-XSRF-TOKEN header "
                        + "obtained from GET /api/v1/admin/auth/csrf."))
@SecurityScheme(
        name = "adminSession",
        type = SecuritySchemeType.APIKEY,
        in = SecuritySchemeIn.COOKIE,
        paramName = "ADMIN_SESSION",
        description = "Admin session cookie returned by the login endpoint.")
public class OpenApiConfiguration {
}
