import http from 'k6/http';
import { check } from 'k6';
import exec from 'k6/execution';
import { Counter, Rate, Trend } from 'k6/metrics';
import { environment, requireAccessToken } from '../config/environment.js';

const config = environment();
requireAccessToken(config);

export function createOrder(ticketPrefix, ticketNumber, scenarioName) {
    const suffix = ticketNumber.toString(16).padStart(12, '0');
    const ticketId = `${ticketPrefix}-0000-0000-0000-${suffix}`;
    const idempotencyKey = `k6-${scenarioName}-${exec.scenario.iterationInTest}-${ticketId}`;
    return http.post(`${config.orderServiceUrl}/api/v1/orders`, JSON.stringify({ ticketId }), {
        headers: {
            Authorization: `Bearer ${config.accessToken}`,
            'Content-Type': 'application/json',
            'Idempotency-Key': idempotencyKey,
        },
        tags: { scenario: scenarioName, operation: 'create_order' },
        timeout: '5s',
    });
}

export function recordCreateOrder(response, metrics) {
    metrics.duration.add(response.timings.duration);
    const success = check(response, {
        'POST /orders returns 201': (r) => r.status === 201,
        'created order has id': (r) => {
            if (r.status !== 201) return false;
            try { return Number(r.json('id')) > 0; } catch (e) { return false; }
        },
    });
    metrics.failures.add(!success);
    if (success) metrics.created.add(1);
    return success;
}

export function createMetrics(prefix) {
    return {
        created: new Counter(`${prefix}_orders_created`),
        failures: new Rate(`${prefix}_create_order_failed`),
        duration: new Trend(`${prefix}_create_order_duration`, true),
    };
}
