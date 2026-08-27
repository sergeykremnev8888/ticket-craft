package ru.ticketcraft.repository;

import java.util.List;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import ru.ticketcraft.model.Event;

@Repository
public interface EventRepository extends JpaRepository<Event, Long> {

    /**
     * Вариант 1. Обычный метод поиска (Порождает проблему N+1).
     * Hibernate выполнит 1 запрос для получения всех мероприятий. 
     * Затем, когда сервис попытается прочитать билеты для каждого мероприятия, 
     * Hibernate сделает еще N дополнительных запросов в таблицу tickets.
     */
    List<Event> findAll();

    /**
     * Вариант 2. Оптимизированный метод через @EntityGraph (Решение проблемы N+1).
     * Аннотация принудительно указывает Hibernate сделать LEFT JOIN FETCH на уровне базы данных.
     * СУБД вернет всё за 1 единственный SQL-запрос, сразу заполнив коллекцию tickets.
     */
    @EntityGraph(attributePaths = {"tickets"})
    @Query("SELECT e FROM Event e")
    List<Event> findAllWithTicketsGraph();
}
