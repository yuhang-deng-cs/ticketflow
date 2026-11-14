export type View = 'lab' | 'events' | 'orders';

export type Role = 'CUSTOMER' | 'ADMIN';

export type OrderStatus = 'PENDING' | 'CONFIRMED' | 'CANCELLED' | 'EXPIRED';

export interface User {
  userId: number;
  email: string;
  fullName: string;
  role: Role;
  token?: string;
}

export interface TicketTier {
  id: number;
  name: string;
  price: number;
  totalCapacity: number;
  availableCapacity: number;
}

export interface EventItem {
  id: number;
  title: string;
  description: string;
  venue: string;
  city: string;
  category: string;
  imageUrl: string;
  startTime: string;
  endTime: string;
  status: string;
  ticketTiers: TicketTier[];
}

export interface OrderItem {
  ticketTierId: number;
  tierName: string;
  quantity: number;
  unitPrice: number;
  subtotal: number;
}

export interface Order {
  orderId: number;
  orderNumber: string;
  eventId: number;
  eventTitle: string;
  venue: string;
  totalAmount: number;
  status: OrderStatus;
  expiresAt: string;
  createdAt: string;
  items: OrderItem[];
}

export interface ApiResponse<T> {
  success: boolean;
  message: string;
  data: T;
  traceId?: string;
  timestamp: string;
}


export type StrategyType = 'NAIVE' | 'PESSIMISTIC_LOCK' | 'OPTIMISTIC_LOCK' | 'ATOMIC_UPDATE' | 'REDIS_LUA';

export interface StrategyInfo {
  type: StrategyType;
  displayName: string;
  description: string;
  safe: boolean;
  available: boolean;
  active: boolean;
}

export interface LabCatalog {
  activeStrategy: StrategyType;
  limits: { maxStock: number; maxBuyers: number };
  strategies: StrategyInfo[];
}

export interface LabRunResult {
  strategy: StrategyType;
  stock: number;
  buyers: number;
  succeeded: number;
  soldOut: number;
  conflicts: number;
  errors: number;
  finalStock: number;
  oversold: number;
  phantomStock: number;
  consistent: boolean;
  unsold: number;
  durationMs: number;
  throughputPerSecond: number;
  p50Ms: number;
  p95Ms: number;
  p99Ms: number;
  maxMs: number;
}
