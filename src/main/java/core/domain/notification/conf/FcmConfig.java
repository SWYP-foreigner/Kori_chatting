package core.domain.notification.conf;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;

@Slf4j
@Configuration
public class FcmConfig {
    @PostConstruct
    public void initialize() {
        try {
            ClassPathResource resource = new ClassPathResource("firebase/serviceAccountKey.json");
            if (!resource.exists()) {
                log.warn("Firebase serviceAccountKey.json not found. FCM will be disabled.");
            }
        } catch (Exception e) {
            log.error("Failed to initialize FirebaseApp.", e);
        }
    }
}