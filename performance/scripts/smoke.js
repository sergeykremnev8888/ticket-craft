import http from 'k6/http';
import { check, sleep } from 'k6';
import { environment, requireAccessToken } from '../config/environment.js';

const config = environment();
requireAccessToken(config);

export const options = {
    vus: 1,
    iterations: 1,
    thresholds: {
        http_req_failed: ['rate==0'],
        http_req_duration: ['p(95)<1000'],
        checks: ['rate==1'],
    },
};

function ticketId(number) {
    const suffix = number.toString(16).padStart(12, '0');
    return `20000000-0000-0000-0000-${suffix}`;
}

export default function () {
    const id = ticketId(1);
    const idempotencyKey = `k6-smoke-${Date.now()}-${__VU}-${__ITER}`;

    const response = http.post(
        `${config.orderServiceUrl}/api/v1/orders`,
        JSON.stringify({ ticketId: id }),
        {
            headers: {
                Authorization: `Bearer ${config.accessToken}`,
                'Content-Type': 'application/json',
                'Idempotency-Key': idempotencyKey,
            },
            tags: { scenario: 'smoke', operation: 'create_order' },
        },
    );

    const created = check(response, {
        'POST /orders returns 201': (r) => r.status === 201,
        'response contains order id': (r) => {
            try {
                return Number(r.json('id')) > 0;
            } catch (e) {
                return false;
            }
        },
    });

    if (!created) {
        console.error(`Create order failed: status=${response.status}, body=${response.body}`);
        return;
    }

    const orderId = response.json('id');
    sleep(2);

    const getResponse = http.get(
        `${config.orderServiceUrl}/api/v1/orders/${orderId}`,
        {
            headers: { Authorization: `Bearer ${config.accessToken}` },
            tags: { scenario: 'smoke', operation: 'get_order' },
        },
    );

    check(getResponse, {
        'GET /orders/{id} returns 200': (r) => r.status === 200,
        'returned order id matches': (r) => Number(r.json('id')) === Number(orderId),
    });
}
