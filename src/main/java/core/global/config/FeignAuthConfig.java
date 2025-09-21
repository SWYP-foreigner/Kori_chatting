package core.global.config;

import feign.RequestInterceptor;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.util.StringUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

@Configuration
public class FeignAuthConfig {

    @Bean
    public RequestInterceptor bearerForwardingInterceptor() {
        return template -> {
            var attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attrs == null) return;

            HttpServletRequest req = attrs.getRequest();
            String auth = req.getHeader(HttpHeaders.AUTHORIZATION);
            if (StringUtils.hasText(auth)) {
                template.header(HttpHeaders.AUTHORIZATION, auth);
            }
        };
    }
}
