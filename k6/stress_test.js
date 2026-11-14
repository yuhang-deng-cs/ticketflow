// End-to-end load test of the real checkout API (not the Lab).
//
//   k6 run k6/stress_test.js
//   k6 run -e API_URL=http://localhost:3000/api/v1 -e TIER_ID=10 -e EVENT_ID=4 k6/stress_test.js
//
// Each virtual user browses events and tries to reserve one ticket. 200 (reserved) and
// 409 (sold out) are both correct answers; anything 5xx is a failure. Point it at the small
// "Midnight Drop" tier to watch a sell-out under load, then check that the tier's
// available capacity ended at exactly 0.
import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter } from 'k6/metrics';

const BASE_URL = __ENV.API_URL || 'http://localhost:8080/api/v1';
const EVENT_ID = Number(__ENV.EVENT_ID || 1);
const TIER_ID = Number(__ENV.TIER_ID || 1);

const reserved = new Counter('tickets_reserved');
const soldOut = new Counter('tickets_sold_out');

export const options = {
  stages: [
    { duration: '10s', target: 50 }, // warm-up
    { duration: '30s', target: 300 }, // rush
    { duration: '10s', target: 0 }, // ramp down
  ],
  thresholds: {
    // Targets for a laptop running docker compose; adjust for your hardware.
    http_req_duration: ['p(95)<250'],
    'checks{check:no server errors}': ['rate==1'],
  },
};

// Log in once and share the token, so BCrypt does not dominate the measurement.
export function setup() {
  const res = http.post(
    `${BASE_URL}/auth/login`,
    JSON.stringify({ email: 'demo@ticketflow.test', password: 'password123' }),
    { headers: { 'Content-Type': 'application/json' } },
  );
  if (res.status !== 200) {
    throw new Error(`Login failed (${res.status}). Is the backend running at ${BASE_URL}?`);
  }
  return { token: res.json('data.token') };
}

export default function ({ token }) {
  const events = http.get(`${BASE_URL}/events`, { tags: { name: 'GET /events' } });
  check(events, { 'events listed': (r) => r.status === 200 });

  const res = http.post(
    `${BASE_URL}/orders`,
    JSON.stringify({ eventId: EVENT_ID, ticketTierId: TIER_ID, quantity: 1 }),
    {
      tags: { name: 'POST /orders' },
      headers: {
        'Content-Type': 'application/json',
        Authorization: `Bearer ${token}`,
        'Idempotency-Key': `k6-${__VU}-${__ITER}-${Date.now()}`,
      },
    },
  );

  if (res.status === 200) reserved.add(1);
  if (res.status === 409) soldOut.add(1);
  check(res, {
    'no server errors': (r) => r.status < 500,
    'reserved or sold out': (r) => r.status === 200 || r.status === 409,
  });

  sleep(0.5);
}
