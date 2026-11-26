package com.pgconnect.payment.repo;
import com.pgconnect.payment.domain.WebhookEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;
public interface WebhookEventRepository extends JpaRepository<WebhookEvent,UUID> {
 boolean existsByGatewayAndGatewayEventId(String gateway,String eventId);
}
