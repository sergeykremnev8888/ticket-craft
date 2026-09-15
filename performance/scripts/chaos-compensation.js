import exec from 'k6/execution';
import { createMetrics, createOrder, recordCreateOrder } from './_order-load.js';

const metrics = createMetrics('ticketcraft_compensation');

export const options = {
    scenarios: {
        compensation_seed: { executor: 'shared-iterations', vus: 1, iterations: 1, maxDuration: '30s' },
    },
    thresholds: {
        checks: ['rate==1'],
        http_req_failed: ['rate==0'],
        ticketcraft_compensation_create_order_failed: ['rate==0'],
    },
};

export default function () {
    const response = createOrder('80000000', 1, 'compensation');
    if (!recordCreateOrder(response, metrics)) {
        exec.test.abort(`Compensation seed order failed: status=${response.status}, body=${response.body}`);
    }
    console.log(`Compensation seed order id=${response.json('id')}, ticketId=80000000-0000-0000-0000-000000000001`);
}
