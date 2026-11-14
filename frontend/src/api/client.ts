import axios, { isAxiosError } from 'axios';
import type {
  ApiResponse,
  EventItem,
  LabCatalog,
  LabRunResult,
  Order,
  StrategyType,
  User,
} from '../types';

/**
 * Relative by default: Vite proxies /api in development and nginx proxies it in Docker,
 * so the browser never makes a cross-origin call. Set VITE_API_URL to point elsewhere.
 */
const API_BASE_URL = import.meta.env.VITE_API_URL || '/api/v1';

const TOKEN_KEY = 'ticketflow_token';
const USER_KEY = 'ticketflow_user';

export const apiClient = axios.create({
  baseURL: API_BASE_URL,
  headers: { 'Content-Type': 'application/json' },
});

apiClient.interceptors.request.use((config) => {
  const token = localStorage.getItem(TOKEN_KEY);
  if (token) {
    config.headers.Authorization = `Bearer ${token}`;
  }
  return config;
});

/** Turns any thrown value into a message a user can read. */
export function errorMessage(err: unknown, fallback: string): string {
  if (isAxiosError<ApiResponse<unknown>>(err)) {
    return err.response?.data?.message ?? fallback;
  }
  return fallback;
}

/**
 * One key per user intent (one click), reused for automatic retries of that same intent.
 * The server replays the first response for a repeated key instead of booking twice.
 */
function newIdempotencyKey(): string {
  return crypto.randomUUID();
}

function storeSession(user: User): User {
  if (user.token) {
    localStorage.setItem(TOKEN_KEY, user.token);
    localStorage.setItem(USER_KEY, JSON.stringify(user));
  }
  return user;
}

export const api = {
  auth: {
    login: async (email: string, password: string): Promise<User> => {
      const res = await apiClient.post<ApiResponse<User>>('/auth/login', { email, password });
      return storeSession(res.data.data);
    },
    register: async (email: string, password: string, fullName: string): Promise<User> => {
      const res = await apiClient.post<ApiResponse<User>>('/auth/register', { email, password, fullName });
      return storeSession(res.data.data);
    },
    logout: () => {
      localStorage.removeItem(TOKEN_KEY);
      localStorage.removeItem(USER_KEY);
    },
    getCurrentUser: (): User | null => {
      const stored = localStorage.getItem(USER_KEY);
      if (!stored) return null;
      try {
        return JSON.parse(stored) as User;
      } catch {
        return null;
      }
    },
  },
  events: {
    list: async (): Promise<EventItem[]> => {
      const res = await apiClient.get<ApiResponse<EventItem[]>>('/events');
      return res.data.data;
    },
    get: async (id: number): Promise<EventItem> => {
      const res = await apiClient.get<ApiResponse<EventItem>>(`/events/${id}`);
      return res.data.data;
    },
  },
  orders: {
    create: async (eventId: number, ticketTierId: number, quantity: number): Promise<Order> => {
      const res = await apiClient.post<ApiResponse<Order>>(
        '/orders',
        { eventId, ticketTierId, quantity },
        { headers: { 'Idempotency-Key': newIdempotencyKey() } },
      );
      return res.data.data;
    },
    pay: async (orderNumber: string): Promise<Order> => {
      const res = await apiClient.post<ApiResponse<Order>>(
        '/orders/pay',
        { orderNumber, paymentMethod: 'MOCK_CARD' },
        { headers: { 'Idempotency-Key': newIdempotencyKey() } },
      );
      return res.data.data;
    },
    cancel: async (orderNumber: string): Promise<Order> => {
      const res = await apiClient.post<ApiResponse<Order>>(
        `/orders/${encodeURIComponent(orderNumber)}/cancel`,
        null,
        { headers: { 'Idempotency-Key': newIdempotencyKey() } },
      );
      return res.data.data;
    },
    list: async (): Promise<Order[]> => {
      const res = await apiClient.get<ApiResponse<Order[]>>('/orders');
      return res.data.data;
    },
  },
  lab: {
    catalog: async (): Promise<LabCatalog> => {
      const res = await apiClient.get<ApiResponse<LabCatalog>>('/lab/strategies');
      return res.data.data;
    },
    run: async (strategy: StrategyType, stock: number, buyers: number): Promise<LabRunResult> => {
      const res = await apiClient.post<ApiResponse<LabRunResult>>('/lab/runs', { strategy, stock, buyers });
      return res.data.data;
    },
  },
};
