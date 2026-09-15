import http from 'k6/http';
import { check } from 'k6';
import exec from 'k6/execution';
import { Counter, Rate, Trend } from 'k6/metrics';
import { environment, requireAccessToken } from '../config/environment.js';

const config = environment();
requireAccessToken(config);

const startTicketNumber = Number(__ENV.START_TICKET_NUMBER || '1');
const ticketPoolSize = Number(__ENV.TICKET_POOL_SIZE || '6000');
const preAllocatedVUs = Number(__ENV.PRE_ALLOCATED_VUS || '100');
const maxVUs = Number(__ENV.MAX_VUS || '300');

if (!Number.isInteger(startTicketNumber) || startTicketNumber < 1) {
    throw new Error('START_TICKET_NUMBER must be a positive integer.');
}
if (!Number.isInteger(ticketPoolSize) || ticketPoolSize < 1) {
    throw new Error('TICKET_POOL_SIZE must be a positive integer.');
}

const ordersCreated = new Counter('ticketcraft_orders_created');
const createOrderFailures = new Rate('ticketcraft_create_order_failed');
const createOrderDuration = new Trend('ticketcraft_create_order_duration', true);

export const options = {
    scenarios: {
        capacity_stress: {
            executor: 'ramping-arrival-rate',
            startRate: 10,
            timeUnit: '1s',
            preAllocatedVUs,
            maxVUs,
            stages: [
                { target: 10, duration: '30s' },
                { target: 20, duration: '30s' },
                { target: 40, duration: '30s' },
                { target: 80, duration: '30s' },
            ],
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
    return `30000000-0000-0000-0000-${suffix}`;
}

export default function () {
    const ticketNumber = startTicketNumber + exec.scenario.iterationInTest;
    const lastTicketNumber = startTicketNumber + ticketPoolSize - 1;

    if (ticketNumber > lastTicketNumber) {
        exec.test.abort(
            `Stage 3 ticket pool exhausted at #${ticketNumber}; last prepared ticket is #${lastTicketNumber}.`,
        );
    }

    const id = ticketId(ticketNumber);
    const idempotencyKey = `k6-stress-${exec.scenario.iterationInTest}-${id}`;

    const response = http.post(
        `${config.orderServiceUrl}/api/v1/orders`,
        JSON.stringify({ ticketId: id }),
        {
            headers: {
                Authorization: `Bearer ${config.accessToken}`,
                'Content-Type': 'application/json',
                'Idempotency-Key': idempotencyKey,
            },
            tags: {
                scenario: 'capacity_stress',
                operation: 'create_order',
            },
            timeout: '5s',
        },
    );

    createOrderDuration.add(response.timings.duration);

    const success = check(response, {
        'POST /orders returns 201': (r) => r.status === 201,
        'created order has id': (r) => {
            if (r.status !== 201) {
                return false;
            }
            try {
                return Number(r.json('id')) > 0;
            } catch (e) {
                return false;
            }
        },
    });

    createOrderFailures.add(!success);

    if (success) {
        ordersCreated.add(1);
        return;
    }

    console.error(
        `Create order failed: iteration=${exec.scenario.iterationInTest}, ` +
        `ticketId=${id}, status=${response.status}, body=${response.body}`,
    );
}
