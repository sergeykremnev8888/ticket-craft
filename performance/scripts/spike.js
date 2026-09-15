import exec from 'k6/execution';
import { createMetrics, createOrder, recordCreateOrder } from './_order-load.js';

const metrics = createMetrics('ticketcraft_spike');
const startTicketNumber = Number(__ENV.START_TICKET_NUMBER || '1');
const ticketPoolSize = Number(__ENV.TICKET_POOL_SIZE || '3000');

export const options = {
    scenarios: {
        spike: {
            executor: 'ramping-arrival-rate',
            startRate: 5,
            timeUnit: '1s',
            preAllocatedVUs: 50,
            maxVUs: 150,
            stages: [
                { target: 5, duration: '15s' },
                { target: 30, duration: '1s' },
                { target: 30, duration: '15s' },
                { target: 5, duration: '1s' },
                { target: 5, duration: '30s' },
            ],
            gracefulStop: '30s',
        },
    },
    thresholds: {
        checks: ['rate>0.99'],
        http_req_failed: ['rate<0.01'],
        'http_req_duration{operation:create_order}': ['p(95)<1000', 'p(99)<2000'],
        ticketcraft_spike_create_order_failed: ['rate<0.01'],
    },
};

export default function () {
    const ticketNumber = startTicketNumber + exec.scenario.iterationInTest;
    if (ticketNumber >= startTicketNumber + ticketPoolSize) {
        exec.test.abort('Spike ticket pool exhausted.');
    }
    const response = createOrder('50000000', ticketNumber, 'spike');
    if (!recordCreateOrder(response, metrics)) {
        console.error(`Spike create failed: iteration=${exec.scenario.iterationInTest}, status=${response.status}, body=${response.body}`);
    }
}
