import exec from 'k6/execution';
import { createMetrics, createOrder, recordCreateOrder } from './_order-load.js';

const rate = Number(__ENV.CHAOS_RATE || '5');
const duration = __ENV.CHAOS_DURATION || '60s';
const startTicketNumber = Number(__ENV.START_TICKET_NUMBER || '1');
const ticketPoolSize = Number(__ENV.TICKET_POOL_SIZE || '2000');
const metrics = createMetrics('ticketcraft_chaos_catalog');

export const options = {
    scenarios: {
        chaos_catalog: {
            executor: 'constant-arrival-rate', rate, timeUnit: '1s', duration,
            preAllocatedVUs: 20, maxVUs: 100, gracefulStop: '30s',
        },
    },
    thresholds: {
        checks: ['rate>0.99'],
        http_req_failed: ['rate<0.01'],
        'http_req_duration{operation:create_order}': ['p(95)<1000', 'p(99)<2000'],
        ticketcraft_chaos_catalog_create_order_failed: ['rate<0.01'],
    },
};

export default function () {
    const ticketNumber = startTicketNumber + exec.scenario.iterationInTest;
    if (ticketNumber >= startTicketNumber + ticketPoolSize) exec.test.abort('Chaos ticket pool exhausted.');
    const response = createOrder('60000000', ticketNumber, 'chaos_catalog');
    if (!recordCreateOrder(response, metrics)) {
        console.error(`Chaos create failed: iteration=${exec.scenario.iterationInTest}, status=${response.status}, body=${response.body}`);
    }
}
