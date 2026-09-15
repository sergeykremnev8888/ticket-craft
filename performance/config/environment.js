export function environment() {
    return {
        orderServiceUrl: __ENV.ORDER_SERVICE_URL || 'http://localhost:8082',
        accessToken: __ENV.ACCESS_TOKEN || '',
        ticketCount: Number(__ENV.TICKET_COUNT || '100'),
    };
}

export function requireAccessToken(config) {
    if (!config.accessToken) {
        throw new Error('ACCESS_TOKEN is required. Obtain an orders.write token in Postman and pass it to k6 via -e ACCESS_TOKEN=...');
    }
}
