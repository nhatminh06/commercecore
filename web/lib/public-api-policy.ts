const uuid = "[0-9a-fA-F-]{36}";
const safePublicRules = [
  { method: "GET", pattern: /^\/api\/commercecore\/products(?:\/[^/]+(?:\/inventory)?)?$/ },
  { method: "POST", pattern: /^\/api\/commercecore\/carts$/ },
  { method: "GET", pattern: new RegExp(`^/api/commercecore/carts/${uuid}$`) },
  { method: "PUT", pattern: new RegExp(`^/api/commercecore/carts/${uuid}/items/[^/]+$`) },
  { method: "DELETE", pattern: new RegExp(`^/api/commercecore/carts/${uuid}/items/[^/]+$`) },
  { method: "POST", pattern: new RegExp(`^/api/commercecore/carts/${uuid}/checkout$`) },
  { method: "GET", pattern: new RegExp(`^/api/commercecore/orders/${uuid}(?:/inspection|/payment)?$`) },
  { method: "POST", pattern: new RegExp(`^/api/commercecore/orders/${uuid}/payment$`) },
];

export function isAllowedPublicApi(method: string, pathname: string) {
  return safePublicRules.some((rule) => rule.method === method.toUpperCase() && rule.pattern.test(pathname));
}
