package ru.ticketcraft.repository;

import java.time.Instant;
import java.util.UUID;

import org.springframework.data.jdbc.repository.query.Modifying;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;

import ru.ticketcraft.model.OutboxEvent;

public interface OutboxEventRepository extends CrudRepository<OutboxEvent, UUID> {

    @Modifying
    @Query("""
            INSERT INTO outbox_events (
                id,
                aggregate_type,
                aggregate_id,
                event_type,
                topic,
                payload,
                created_at
            )
            VALUES (
                :id,
                :aggregateType,
                :aggregateId,
                :eventType,
                :topic,
                :payload,
                :createdAt
            )
            """)
    int insert(
            @Param("id") UUID id,
            @Param("aggregateType") String aggregateType,
            @Param("aggregateId") String aggregateId,
            @Param("eventType") String eventType,
            @Param("topic") String topic,
            @Param("payload") String payload,
            @Param("createdAt") Instant createdAt);
}