export type RequestErrorBody = {
  code?: string;
  message?: string;
  error?: string;
};

export type Product = {
  sku: string;
  name: string;
  price: number;
};

export type Inventory = {
  sku: string;
  availableQuantity: number;
};

export type CartItem = {
  sku: string;
  quantity: number;
};

export type Cart = {
  id: string;
  items: CartItem[];
};

export type OrderItem = {
  sku: string;
  quantity: number;
  unitPrice: number;
  lineTotal: number;
};

export type Order = {
  orderId: string;
  status: "PENDING" | "CONFIRMED" | "CANCELLED";
  total: number;
  items: OrderItem[];
};
