import http from 'k6/http';
import { check } from 'k6';
import exec from 'k6/execution';
import { Counter, Rate, Trend } from 'k6/metrics';
import { environment, requireAccessToken } from '../config/environment.js';

const config = environment();
requireAccessToken(config);

const startTicketNumber = Number(__ENV.START_TICKET_NUMBER || '2');
const rate = Number(__ENV.LOAD_RATE || '5');
const duration = __ENV.LOAD_DURATION || '60s';
const preAllocatedVUs = Number(__ENV.PRE_ALLOCATED_VUS || '20');
const maxVUs = Number(__ENV.MAX_VUS || '50');

if (!Number.isInteger(startTicketNumber) || startTicketNumber < 2 || startTicketNumber > 1000) {
    throw new Error('START_TICKET_NUMBER must be an integer between 2 and 1000. Ticket #1 is reserved for smoke.');
}
if (!Number.isFinite(rate) || rate <= 0) {
    throw new Error('LOAD_RATE must be greater than 0.');
}

const ordersCreated = new Counter('ticketcraft_orders_created');
const createOrderFailures = new Rate('ticketcraft_create_order_failed');
const createOrderDuration = new Trend('ticketcraft_create_order_duration', true);

export const options = {
    scenarios: {
        normal_load: {
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
        'http_req_duration{operation:create_order}': ['p(95)<500', 'p(99)<1000'],
        ticketcraft_create_order_failed: ['rate<0.01'],
    },
};

function ticketId(number) {
    const suffix = number.toString(16).padStart(12, '0');
    return `20000000-0000-0000-0000-${suffix}`;
}

export default function () {
    const ticketNumber = startTicketNumber + exec.scenario.iterationInTest;

    if (ticketNumber > 1000) {
        exec.test.abort(
            `Performance ticket pool exhausted: requested ticket #${ticketNumber}. ` +
            'Reduce LOAD_RATE/LOAD_DURATION or prepare more tickets.',
        );
    }

    const id = ticketId(ticketNumber);
    const idempotencyKey = `k6-load-${exec.scenario.iterationInTest}-${id}`;

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
                scenario: 'normal_load',
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
