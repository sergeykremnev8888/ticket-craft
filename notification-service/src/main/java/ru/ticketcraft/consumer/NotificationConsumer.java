package ru.ticketcraft.consumer;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Service;

import ru.ticketcraft.dto.OrderEvent;

@Service
public class NotificationConsumer {

	private static final Logger log = LoggerFactory.getLogger(NotificationConsumer.class);

	/**
	 * Потокобезопасный реестр обработанных индентификаторов сообщений. Обеспечивает
	 * идемпотентность (защиту от дубликатов сообщений Kafka).
	 */
	private final Set<String> processedMessageIds = ConcurrentHashMap.newKeySet();

	/**
	 * Метод-слушатель топика Kafka. Благодаря настройкам Spring Boot 4, этот метод
	 * выполняется внутри Виртуального Потока (Virtual Thread).
	 */
	@KafkaListener(topics = "order-events", groupId = "notification-group")
	public void listen(@Payload OrderEvent event, @Header(KafkaHeaders.RECEIVED_KEY) String messageKey,
			@Header(KafkaHeaders.RECEIVED_PARTITION) int partition, @Header(KafkaHeaders.OFFSET) long offset,
			Acknowledgment acknowledgment) {

		log.info("Получено сообщение из Kafka [Партиция: {}, Оффсет: {}, Ключ: {}]", partition, offset, messageKey);

		String messageId = event.getMessageId();

		// ПРОВЕРКА НА ИДЕМПОТЕНТНОСТЬ
		// Метод add() возвращает true, если элемента еще не было в Set.
		// Если элемент уже есть, значит это дубликат, прилетевший из-за сбоя сети по
		// гарантии At-least-once.
		if (!processedMessageIds.add(messageId)) {
			log.warn("Обнаружено дублирующееся сообщение Kafka с messageId: {}. Пропускаем обработку.", messageId);

			// Критически важно подтвердить оффсет даже для дубликата,
			// иначе Kafka продолжит бесконечно присылать нам это сообщение при перезапуске.
			acknowledgment.acknowledge();
			return;
		}

		try {
			// ИМИТАЦИЯ БИЗНЕС-ЛОГИКИ (Отправка Email/SMS клиенту)
			log.info(
					">>>> [УВЕДОМЛЕНИЕ] Пользователь №{} успешно забронировал билеты {} на мероприятие №{}. Итоговая сумма: {} руб.",
					event.getUserId(), event.getTicketIds(), event.getEventId(), event.getTotalPrice());

			// Имитируем небольшую сетевую задержку отправки email
			Thread.sleep(100);

			// ШАГ 2. РУЧНОЙ КОММИТ ОФФСЕТА (Manual Acknowledgment)
			// Говорим Kafka: "Мы успешно отправили письмо, сдвигай указатель для этой
			// партиции".
			acknowledgment.acknowledge();
			log.info("Сообщение с messageId: {} успешно обработано, оффсет закоммичен.", messageId);

		} catch (Exception e) {
			log.error("Ошибка при обработке уведомления для messageId: {}", messageId, e);
			// Здесь в продакшене настраивается логика Dead Letter Queue (DLQ) или
			// сообщение НЕ коммитится, чтобы Spring Kafka попробовал обработать его
			// повторно (Retry).
		}
	}
}