import http from 'k6/http';
import { check } from 'k6';
import exec from 'k6/execution';
import { Counter, Rate, Trend } from 'k6/metrics';
import { environment, requireAccessToken } from '../config/environment.js';

const config = environment();
requireAccessToken(config);

const rate = Number(__ENV.CAPACITY_RATE || '40');
const duration = __ENV.CAPACITY_DURATION || '120s';
const startTicketNumber = Number(__ENV.START_TICKET_NUMBER || '1');
const ticketPoolSize = Number(__ENV.TICKET_POOL_SIZE || '10000');
const preAllocatedVUs = Number(__ENV.PRE_ALLOCATED_VUS || '100');
const maxVUs = Number(__ENV.MAX_VUS || '300');

if (!Number.isFinite(rate) || rate <= 0) throw new Error('CAPACITY_RATE must be > 0.');
if (!Number.isInteger(startTicketNumber) || startTicketNumber < 1) throw new Error('START_TICKET_NUMBER must be a positive integer.');
if (!Number.isInteger(ticketPoolSize) || ticketPoolSize < 1) throw new Error('TICKET_POOL_SIZE must be a positive integer.');

const ordersCreated = new Counter('ticketcraft_orders_created');
const createOrderFailures = new Rate('ticketcraft_create_order_failed');
const createOrderDuration = new Trend('ticketcraft_create_order_duration', true);

export const options = {
    scenarios: {
        capacity_constant: {
            executor: 'constant-arrival-rate',
            rate,
            timeUnit: '1s',
            duration,
            preAllocatedVUs,
            maxVUs,
            gracefulStop: '30s',
        },
    },
    thresholds: {
        checks: ['rate>0.99'],
        http_req_failed: ['rate<0.01'],
        'http_req_duration{operation:create_order}': ['p(95)<1000', 'p(99)<2000'],
        ticketcraft_create_order_failed: ['rate<0.01'],
    },
};

function ticketId(number) {
    const suffix = number.toString(16).padStart(12, '0');
    return `40000000-0000-0000-0000-${suffix}`;
}

export default function () {
    const ticketNumber = startTicketNumber + exec.scenario.iterationInTest;
    const lastTicketNumber = startTicketNumber + ticketPoolSize - 1;

    if (ticketNumber > lastTicketNumber) {
        exec.test.abort(`Capacity ticket pool exhausted at #${ticketNumber}; last allowed ticket is #${lastTicketNumber}.`);
    }

    const id = ticketId(ticketNumber);
    const idempotencyKey = `k6-capacity-${rate}-${exec.scenario.iterationInTest}-${id}`;
    const response = http.post(`${config.orderServiceUrl}/api/v1/orders`, JSON.stringify({ ticketId: id }), {
        headers: {
            Authorization: `Bearer ${config.accessToken}`,
            'Content-Type': 'application/json',
            'Idempotency-Key': idempotencyKey,
        },
        tags: { scenario: 'capacity_constant', operation: 'create_order', capacity_rate: String(rate) },
        timeout: '5s',
    });

    createOrderDuration.add(response.timings.duration);
    const success = check(response, {
        'POST /orders returns 201': (r) => r.status === 201,
        'created order has id': (r) => {
            if (r.status !== 201) return false;
            try { return Number(r.json('id')) > 0; } catch (e) { return false; }
        },
    });
    createOrderFailures.add(!success);
    if (success) {
        ordersCreated.add(1);
        return;
    }
    console.error(`Create order failed: iteration=${exec.scenario.iterationInTest}, ticketId=${id}, status=${response.status}, body=${response.body}`);
}
